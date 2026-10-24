#!/usr/bin/env bash
# Tests for scripts/setup-phone.sh. They use a fake `adb` and a fake `curl` placed first on PATH, so
# they never touch a real phone or the network. Run: bash scripts/test-setup-phone.sh
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/setup-phone.sh"
PASS=0
FAIL=0
FAILED_NAMES=()

fail_test() { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$CURRENT"); printf '  FAIL: %s\n    %s\n' "$CURRENT" "$1"; }
pass_test() { PASS=$((PASS + 1)); }

assert_eq() { [ "$1" = "$2" ] || { fail_test "expected [$1], got [$2] ($3)"; return 1; }; }
assert_contains() { case $1 in *"$2"*) ;; *) fail_test "missing [$2] in output:
$1"; return 1 ;; esac; }
assert_not_contains() { case $1 in *"$2"*) fail_test "unexpected [$2] in output:
$1"; return 1 ;; *) ;; esac; }
assert_true() { eval "$1" >/dev/null 2>&1 || { fail_test "condition false: $1"; return 1; }; }

CURRENT=""
test_case() { CURRENT=$1; FAIL_BEFORE=$FAIL; "$2"; [ "$FAIL" = "$FAIL_BEFORE" ] && pass_test; }

# ---- sandbox with fakes ----------------------------------------------------------------------

new_sandbox() {
  SB=$(mktemp -d "${TMPDIR:-/tmp}/pesacast-test.XXXXXX")
  mkdir -p "$SB/bin"
  export FAKE_LOG="$SB/adb.log" FAKE_CURL_LOG="$SB/curl.log"
  : > "$FAKE_LOG"; : > "$FAKE_CURL_LOG"
  export FAKE_DEVICES=$'List of devices attached\nABC123\tdevice\n'
  export FAKE_MANUFACTURER=TECNO FAKE_MODEL=KG5 FAKE_RELEASE=13 FAKE_SDK=33
  export FAKE_FEATURES=$'feature:android.hardware.bluetooth\nfeature:android.hardware.telephony\n'
  export FAKE_INSTALL_OUT=$'Performing Streamed Install\nSuccess\n'
  export FAKE_PKGINFO=$'    android.permission.RECEIVE_SMS: granted=true\n'
  export FAKE_SERVICES=$'ServiceRecord{x u0 com.pesacast.android/.service.MirrorService}\n'
  export FAKE_IDLE=$'system-excidle,x\nuser,com.pesacast.android,10123\n'
  export FAKE_AMSTART=$'Starting: Intent { cmp=... }\n'
  export FAKE_FAIL="" FAKE_PROVISION_ERROR=0
  export FAKE_SHA="" FAKE_RELEASE_JSON="" FAKE_NO_SHA_FILE=0
  export PESACAST_SETUP_WAIT=0
  unset PESACAST_WEBHOOK_TOKEN PESACAST_WEBHOOK_SECRET ANDROID_HOME

  cat > "$SB/bin/adb" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOG"
args="$*"
if [ -n "${FAKE_FAIL:-}" ] && [[ "$args" == *"$FAKE_FAIL"* ]]; then echo "failed"; exit 1; fi
case "$args" in
  devices) printf '%s' "$FAKE_DEVICES" ;;
  *"getprop ro.product.manufacturer"*) echo "$FAKE_MANUFACTURER" ;;
  *"getprop ro.product.model"*) echo "$FAKE_MODEL" ;;
  *"getprop ro.build.version.release"*) echo "$FAKE_RELEASE" ;;
  *"getprop ro.build.version.sdk"*) echo "$FAKE_SDK" ;;
  *"pm list features"*) printf '%s' "$FAKE_FEATURES" ;;
  *" install -r "*) printf '%s' "$FAKE_INSTALL_OUT"; [ -n "${FAKE_INSTALL_ERR:-}" ] && { echo "$FAKE_INSTALL_ERR" >&2; exit 1; } ;;
  *" uninstall "*) echo "Success" ;;
  *"dumpsys package"*) printf '%s' "$FAKE_PKGINFO" ;;
  *"dumpsys activity services"*) printf '%s' "$FAKE_SERVICES" ;;
  *"deviceidle whitelist +"*|*"deviceidle whitelist -"*) : ;;
  *"deviceidle whitelist"*) printf '%s' "$FAKE_IDLE" ;;
  *"ProvisionActivity"*) if [ "$FAKE_PROVISION_ERROR" = 1 ]; then printf 'Error type 3\nError: Activity class does not exist.\n'; else printf 'Starting: Intent\n'; fi ;;
  *"am start"*) printf '%s' "$FAKE_AMSTART" ;;
esac
exit 0
EOF
  cat > "$SB/bin/curl" <<'EOF'
#!/usr/bin/env bash
out=""; url=""
while [ $# -gt 0 ]; do
  case $1 in -o) out=$2; shift ;; -H|-A) shift ;; -*) ;; *) url=$1 ;; esac
  shift
done
echo "$url" >> "$FAKE_CURL_LOG"
case "$url" in
  */releases/latest) echo '{"tag_name":"pesacast@0.9.0","assets":[]}' ;;
  */releases/tags/*) [ -n "$FAKE_RELEASE_JSON" ] && cat "$FAKE_RELEASE_JSON" || exit 22 ;;
  *.apk.sha256) if [ "$FAKE_NO_SHA_FILE" = 1 ] || [ -z "$FAKE_SHA" ]; then exit 22; fi; echo "$FAKE_SHA  pesacast.apk" > "$out" ;;
  *.apk) printf 'APK-BYTES' > "$out" ;;
  *) exit 22 ;;
esac
exit 0
EOF
  chmod +x "$SB/bin/adb" "$SB/bin/curl"
  # Safety: the real adb must never be reachable from a test.
  export PATH="$SB/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  if [ "$(command -v adb)" != "$SB/bin/adb" ]; then echo "refusing to run: adb on PATH is not the fake"; exit 99; fi
}

end_sandbox() { rm -rf "$SB"; }

run() { OUT=$(bash "$SCRIPT" "$@" 2>&1); CODE=$?; }
adb_log() { cat "$FAKE_LOG"; }
sha_of() { printf '%s' "$1" | { shasum -a 256 2>/dev/null || sha256sum; } | cut -d' ' -f1; }

make_apk() { # contents -> sets APKFILE with a matching .sha256
  APKFILE="$SB/pesacast-0.9.0.apk"
  printf '%s' "${1:-APK-BYTES}" > "$APKFILE"
  echo "$(sha_of "${1:-APK-BYTES}")  pesacast-0.9.0.apk" > "$APKFILE.sha256"
}

line_of() { adb_log | grep -n -- "$1" | head -1 | cut -d: -f1; }

# ---- helpers (sourced) -----------------------------------------------------------------------

t_shq_roundtrip() {
  source "$SCRIPT"
  local v out
  for v in "plain" "with space" "it's" "a&b;c|d" '$HOME `id` $(id)' 'back\slash' '"dq"' $'multi\nline' '*?[x]'; do
    out=$(sh -c "printf %s $(shq "$v")")
    assert_eq "$v" "$out" "shq round trip"
  done
}

t_webhook_rules() {
  source "$SCRIPT"
  webhook_problem "https://example.com/hook" "tok-1" "a secret ✓" >/dev/null || fail_test "valid settings were refused"
  local bad
  for bad in "http://example.com/h" "https://user:pw@example.com/h" "https://" "ftp://x.com" $'https://\xd0\xb0pple.com/h' "not a url"; do
    webhook_problem "$bad" >/dev/null && fail_test "accepted bad url: $bad"
  done
  webhook_problem "https://e.com/h" "has space" >/dev/null && fail_test "accepted a token with a space"
  webhook_problem "https://e.com/h" $'line\nbreak' >/dev/null && fail_test "accepted a token with a newline"
  webhook_problem "https://e.com/h" "$(printf 'a%.0s' $(seq 1 513))" >/dev/null && fail_test "accepted a 513 char token"
  webhook_problem "https://e.com/h" "" $'bad\001secret' >/dev/null && fail_test "accepted a secret with a control character"
}

t_parsers() {
  source "$SCRIPT"
  local h; h=$(printf 'a%.0s' $(seq 1 64))
  assert_eq "$h" "$(parse_checksum "$(printf 'A%.0s' $(seq 1 64))  pesacast.apk")" "uppercase hash is lowered"
  assert_eq "" "$(parse_checksum "nonsense")" "garbage"
  assert_eq "" "$(parse_checksum "")" "empty"
  assert_eq $'AAA\tdevice\nBBB\tunauthorized' "$(printf 'List of devices attached\n* daemon started\nAAA\tdevice\nBBB\tunauthorized\n\n' | parse_devices)" "devices"
  printf 'android.permission.RECEIVE_SMS: granted=true' | check_sms_granted || fail_test "sms granted not detected"
  printf 'android.permission.RECEIVE_SMS: granted=false' | check_sms_granted && fail_test "sms denied detected as granted"
  printf 'ServiceRecord{.. com.pesacast.android/.service.MirrorService}' | check_service_running || fail_test "service not detected"
  printf '(nothing)' | check_service_running && fail_test "missing service detected"
  printf 'user,com.pesacast.android,1\n' | check_battery_exempt || fail_test "battery exemption not detected"
  printf 'user,com.other,1\n' | check_battery_exempt && fail_test "other app counted"
}

t_digest_parser() {
  source "$SCRIPT"
  local h1 h2 json
  h1=$(printf 'b%.0s' $(seq 1 64)); h2=$(printf 'c%.0s' $(seq 1 64))
  # Same shape as GitHub's single-line API response: the uploader object sits between name and digest.
  json='{"assets":[{"id":1,"name":"latest.json","uploader":{"login":"bot","id":9},"digest":"sha256:'$h2'","download_count":0},{"id":2,"name":"pesacast-0.8.0.apk","label":null,"uploader":{"login":"bot","id":9},"content_type":"x","digest":"sha256:'$(printf 'B%.0s' $(seq 1 64))'","download_count":3}]}'
  assert_eq "$h1" "$(printf '%s' "$json" | digest_from_release pesacast-0.8.0.apk)" "named asset digest"
  assert_eq "$h2" "$(printf '%s' "$json" | digest_from_release latest.json)" "other asset digest"
  assert_eq "" "$(printf '%s' "$json" | digest_from_release missing.apk)" "missing asset"
  assert_eq "" "$(printf '%s' '{"assets":[{"name":"pesacast-0.8.0.apk","digest":"md5:abc"}]}' | digest_from_release pesacast-0.8.0.apk)" "non sha256 digest"
}

t_real_release_shape() {
  # A captured response from the real API (trimmed) must parse the same way.
  source "$SCRIPT"
  local f="$HERE/testdata/release-0.8.0.json"
  [ -f "$f" ] || { fail_test "missing fixture $f"; return; }
  assert_eq "0b8e691b3497b91b914a67f3034c44db02d6465ddc5ae68b883189628381a4de" "$(digest_from_release pesacast-0.8.0.apk < "$f")" "real digest"
}

# ---- arguments -------------------------------------------------------------------------------

t_args_errors() {
  new_sandbox
  local argv
  for argv in "--nope" "--version" "--version 1.2" "--version ../../x" "--sha256 abc" "--apk a.apk --version 0.9.0" "--uninstall --webhook-url https://e.com/h" "--webhook-url"; do
    run $argv
    assert_eq 2 "$CODE" "exit code for: $argv"
  done
  PESACAST_WEBHOOK_TOKEN=t run
  assert_eq 2 "$CODE" "token without a url"
  assert_eq 0 "$(grep -c . "$FAKE_LOG")" "no adb call on a usage error"
  end_sandbox
}

t_help() {
  new_sandbox
  run --help
  assert_eq 0 "$CODE" "help exit"
  assert_contains "$OUT" "Usage:"
  run --bogus
  assert_eq 2 "$CODE" "usage error exit"
  assert_contains "$OUT" "Unknown option"
  end_sandbox
}

# ---- the run ---------------------------------------------------------------------------------

t_dry_run() {
  new_sandbox
  PESACAST_WEBHOOK_TOKEN="tok-SECRET" PESACAST_WEBHOOK_SECRET="sec-SECRET" run --dry-run --webhook-url https://example.com/hook
  assert_eq 0 "$CODE" "dry run exit"
  assert_eq 0 "$(grep -c . "$FAKE_LOG")" "no adb command may run"
  assert_eq 0 "$(grep -c . "$FAKE_CURL_LOG")" "nothing may be downloaded"
  assert_contains "$OUT" "install -r"
  assert_contains "$OUT" "pm grant com.pesacast.android android.permission.RECEIVE_SMS"
  assert_contains "$OUT" "ProvisionActivity"
  assert_contains "$OUT" "https://example.com/hook"
  assert_not_contains "$OUT" "tok-SECRET"
  assert_not_contains "$OUT" "sec-SECRET"
  end_sandbox
}

t_happy_path() {
  new_sandbox; make_apk
  run --apk "$APKFILE"
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_contains "$OUT" "Checksum OK."
  assert_contains "$OUT" "All required checks passed"
  local inst grant idle start verify
  inst=$(line_of " install -r"); grant=$(line_of "RECEIVE_SMS"); idle=$(line_of "whitelist +"); start=$(line_of ".ui.MainActivity"); verify=$(line_of "dumpsys package")
  assert_true "[ $inst -gt $(line_of getprop) ]"
  assert_true "[ $grant -gt $inst ]"
  assert_true "[ $idle -gt $grant ]"
  assert_true "[ $start -gt $idle ]"
  assert_true "[ $verify -gt $start ]"
  assert_contains "$(adb_log)" "POST_NOTIFICATIONS"
  assert_contains "$(adb_log)" "BLUETOOTH_CONNECT"
  end_sandbox
}

t_checksum_mismatch() {
  new_sandbox; make_apk
  printf 'tampered' > "$APKFILE"
  run --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "does not match"
  assert_not_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_no_checksum() {
  new_sandbox
  APKFILE="$SB/x.apk"; printf 'x' > "$APKFILE"
  run --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "No checksum"
  assert_not_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_sha_flag() {
  new_sandbox
  APKFILE="$SB/x.apk"; printf 'bytes' > "$APKFILE"
  run --apk "$APKFILE" --sha256 "$(sha_of bytes)"
  assert_eq 0 "$CODE" "exit: $OUT"
  end_sandbox
}

t_old_android() {
  new_sandbox; make_apk
  FAKE_SDK=29 run --apk "$APKFILE"
  assert_contains "$(adb_log)" "RECEIVE_SMS"
  assert_not_contains "$(adb_log)" "POST_NOTIFICATIONS"
  assert_not_contains "$(adb_log)" "BLUETOOTH_CONNECT"
  end_sandbox
}

t_no_bluetooth() {
  new_sandbox; make_apk
  FAKE_SDK=34 FAKE_FEATURES=$'feature:android.hardware.telephony\n' run --apk "$APKFILE"
  assert_not_contains "$(adb_log)" "BLUETOOTH"
  end_sandbox
}

t_sms_not_granted() {
  new_sandbox; make_apk
  FAKE_FAIL="RECEIVE_SMS" FAKE_PKGINFO="android.permission.RECEIVE_SMS: granted=false" run --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "MISSING  SMS permission granted"
  assert_contains "$OUT" "restricted settings"
  assert_contains "$OUT" "Not ready yet"
  end_sandbox
}

t_service_not_running() {
  new_sandbox; make_apk
  FAKE_SERVICES="(nothing)" run --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "MISSING  Background service running"
  end_sandbox
}

t_battery_soft_failure() {
  new_sandbox; make_apk
  FAKE_FAIL="whitelist +" FAKE_IDLE="user,com.other,1" run --apk "$APKFILE"
  assert_eq 0 "$CODE" "not fatal: $OUT"
  assert_contains "$OUT" "not set  Excluded from battery optimisation"
  assert_contains "$OUT" "could not be set"
  end_sandbox
}

t_webhook_provisioning() {
  new_sandbox; make_apk
  PESACAST_WEBHOOK_TOKEN="tok123" PESACAST_WEBHOOK_SECRET="it's a \$ecret; rm -rf" run --apk "$APKFILE" --webhook-url "https://example.com/h?a=1&b=2"
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_true "[ $(line_of ProvisionActivity) -gt $(line_of .ui.MainActivity) ]"
  assert_contains "$(adb_log)" "--es webhook_url 'https://example.com/h?a=1&b=2'"
  assert_contains "$(adb_log)" "--es webhook_secret 'it'\\''s a \$ecret; rm -rf'"
  assert_not_contains "$OUT" "tok123"
  assert_contains "$OUT" "tap Save and test"
  end_sandbox
}

t_old_app_no_provisioning() {
  new_sandbox; make_apk
  FAKE_PROVISION_ERROR=1 run --apk "$APKFILE" --webhook-url https://example.com/h
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_contains "$OUT" "no webhook provisioning screen"
  end_sandbox
}

t_download_latest() {
  new_sandbox
  FAKE_SHA=$(sha_of APK-BYTES) run
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_contains "$(cat "$FAKE_CURL_LOG")" "releases/download/pesacast@0.9.0/pesacast-0.9.0.apk"
  assert_contains "$(cat "$FAKE_CURL_LOG")" "pesacast-0.9.0.apk.sha256"
  assert_contains "$OUT" "Checksum OK"
  end_sandbox
}

t_download_mismatch() {
  new_sandbox
  FAKE_SHA=$(sha_of different) run --version 0.9.0
  assert_eq 1 "$CODE" "exit"
  assert_not_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_github_digest_fallback() {
  new_sandbox
  printf '{"assets":[{"id":1,"name":"pesacast-0.9.0.apk","uploader":{"login":"b"},"digest":"sha256:%s"}]}' "$(sha_of APK-BYTES)" > "$SB/rel.json"
  FAKE_RELEASE_JSON="$SB/rel.json" run
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_contains "$OUT" "GitHub recorded"
  assert_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_github_digest_mismatch() {
  new_sandbox
  printf '{"assets":[{"name":"pesacast-0.9.0.apk","digest":"sha256:%s"}]}' "$(sha_of other)" > "$SB/rel.json"
  FAKE_RELEASE_JSON="$SB/rel.json" run
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "does not match"
  assert_not_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_no_digest_anywhere() {
  new_sandbox
  printf '{"assets":[{"name":"pesacast-0.9.0.apk"}]}' > "$SB/rel.json"
  FAKE_RELEASE_JSON="$SB/rel.json" run
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "--sha256"
  assert_not_contains "$(adb_log)" " install -r"
  end_sandbox
}

t_device_selection() {
  new_sandbox; make_apk
  FAKE_DEVICES=$'List of devices attached\n\n' run --apk "$APKFILE"
  assert_eq 1 "$CODE" "none"; assert_contains "$OUT" "No phone found"
  FAKE_DEVICES=$'List of devices attached\nA\tdevice\nB\tdevice\n' run --apk "$APKFILE"
  assert_eq 1 "$CODE" "many"; assert_contains "$OUT" "--serial"
  FAKE_DEVICES=$'List of devices attached\nA\tunauthorized\n' run --apk "$APKFILE"
  assert_eq 1 "$CODE" "unauthorized"; assert_contains "$OUT" "tap Allow"
  FAKE_DEVICES=$'List of devices attached\nA\toffline\n' run --apk "$APKFILE"
  assert_eq 1 "$CODE" "offline"; assert_contains "$OUT" "not ready"
  FAKE_DEVICES=$'List of devices attached\nA\tdevice\nB\tdevice\n' run --apk "$APKFILE" --serial B
  assert_contains "$(adb_log)" "-s B install"
  FAKE_DEVICES=$'List of devices attached\nA\tdevice\n' run --apk "$APKFILE" --serial Z
  assert_eq 1 "$CODE" "unknown serial"; assert_contains "$OUT" "No phone with serial Z"
  end_sandbox
}

t_adb_missing() {
  new_sandbox; make_apk
  run --adb "$SB/does-not-exist" --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "adb was not found"
  end_sandbox
}

t_install_failure_reason() {
  new_sandbox; make_apk
  FAKE_INSTALL_OUT=$'Performing Streamed Install\n' FAKE_INSTALL_ERR="adb: failed to install x.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" run --apk "$APKFILE"
  assert_eq 1 "$CODE" "exit"
  assert_contains "$OUT" "INSTALL_FAILED_VERSION_DOWNGRADE"
  end_sandbox
}

t_uninstall() {
  new_sandbox
  run --uninstall
  assert_eq 2 "$CODE" "needs --yes"
  assert_not_contains "$(adb_log)" "uninstall"
  run --uninstall --yes
  assert_eq 0 "$CODE" "exit: $OUT"
  assert_contains "$(adb_log)" "whitelist -com.pesacast.android"
  assert_contains "$(adb_log)" "appops reset"
  assert_contains "$(adb_log)" "uninstall com.pesacast.android"
  end_sandbox
}

t_bash32_features() {
  # The macOS default bash is 3.2; make sure nothing newer is relied on.
  if [ -x /bin/bash ] && /bin/bash --version 2>/dev/null | head -1 | grep -q 'version 3\.'; then
    new_sandbox; make_apk
    OUT=$(/bin/bash "$SCRIPT" --apk "$APKFILE" 2>&1); CODE=$?
    assert_eq 0 "$CODE" "bash 3.2 run: $OUT"
    end_sandbox
  fi
}

for t in t_shq_roundtrip t_webhook_rules t_parsers t_digest_parser t_real_release_shape t_args_errors t_help \
         t_dry_run t_happy_path t_checksum_mismatch t_no_checksum t_sha_flag t_old_android t_no_bluetooth \
         t_sms_not_granted t_service_not_running t_battery_soft_failure t_webhook_provisioning t_old_app_no_provisioning \
         t_download_latest t_download_mismatch t_github_digest_fallback t_github_digest_mismatch t_no_digest_anywhere \
         t_device_selection t_adb_missing t_install_failure_reason t_uninstall t_bash32_features; do
  test_case "$t" "$t"
done

echo
echo "passed: $PASS  failed: $FAIL"
[ "$FAIL" = 0 ] || { printf 'failed: %s\n' "${FAILED_NAMES[*]}"; exit 1; }
