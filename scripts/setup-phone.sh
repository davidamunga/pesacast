#!/usr/bin/env bash
# One-command setup for the phone that receives the M-PESA messages (ADR-0015, docs/phone-setup.md).
#
# Needs bash, curl, adb (Android platform-tools) and shasum or sha256sum. Works with the bash 3.2
# that ships with macOS, on Linux, and in Git Bash or WSL on Windows. It runs the same adb commands
# as the manual steps: download and verify the APK, install it, grant what a computer can grant,
# exclude the app from battery saving, optionally send the webhook settings to the phone's
# confirmation screen, then check the result.
#
#   scripts/setup-phone.sh --dry-run
#   scripts/setup-phone.sh --version 0.9.0 --webhook-url https://example.com/hook
#
# Webhook credentials come from the environment, not the command line:
#   PESACAST_WEBHOOK_TOKEN=... PESACAST_WEBHOOK_SECRET=... scripts/setup-phone.sh --webhook-url ...

PACKAGE="com.pesacast.android"
REPO="davidamunga/pesacast"

print_help() {
  cat <<'EOF'
Set up PesaCast on a phone over USB (adb).

Usage: scripts/setup-phone.sh [options]

  --version <x.y.z|latest>  Release to download (default: latest)
  --apk <file>              Use a local APK instead (needs <file>.sha256 next to it, or --sha256)
  --sha256 <hex>            Expected SHA-256 of the APK (overrides the .sha256 file)
  --webhook-url <https url> Send webhook settings to the phone's confirmation screen.
                            Token and secret are read from PESACAST_WEBHOOK_TOKEN and
                            PESACAST_WEBHOOK_SECRET so they do not appear in the process list.
  --serial <id>             Which phone, if more than one is connected
  --adb <path>              Path to adb (default: adb on PATH, or ANDROID_HOME/platform-tools)
  --keep-download           Keep the downloaded APK
  --dry-run                 Print what would happen. Contacts nothing and changes nothing.
  --uninstall --yes         Remove PesaCast from the phone (undoes the install and the settings)
  --help                    Show this help

The APK is never installed unless its SHA-256 matches. The phone still needs a few taps that no
computer can do; the script lists them at the end.
EOF
}

log() { printf '%s\n' "$*"; }

# ---------------------------------------------------------------------------------------------
# Pure helpers (also exercised directly by scripts/test-setup-phone.sh)

# Quotes a value for the phone's shell, which receives adb's arguments joined by spaces.
shq() {
  # The quote and its replacement live in variables: bash 3.2 (macOS) and bash 5 disagree about
  # backslashes in a literal replacement, but agree when it comes from a variable.
  local v=$1 quote="'" escaped="'\\''"
  printf "'%s'" "${v//$quote/$escaped}"
}

is_semver() { [[ $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
is_hex64() { [ "${#1}" -eq 64 ] && [[ $1 =~ ^[0-9a-fA-F]+$ ]]; }

lower() { printf '%s' "$1" | tr 'A-F' 'a-f'; }

# Prints a problem and returns 1 if the webhook settings break the phone's own rules (ProvisionRequest.kt).
webhook_problem() {
  local url=$1 token=${2-} secret=${3-}
  local ascii_only
  ascii_only=$(printf '%s' "$url" | LC_ALL=C tr -d '\000-\177')
  if [ -n "$ascii_only" ]; then
    echo "--webhook-url contains non-ASCII characters; use the ASCII (xn--) form of the host."; return 1
  fi
  if [[ ! $url =~ ^https://[^/?#@:]+(:[0-9]+)?([/?#].*)?$ ]]; then
    case $url in
      https://*@*) echo "Put credentials in the token or secret, not in the URL." ;;
      https://*) echo "--webhook-url needs a host name." ;;
      *) echo "--webhook-url must start with https://." ;;
    esac
    return 1
  fi
  if [ "${#url}" -gt 2048 ]; then echo "--webhook-url is too long."; return 1; fi
  # Plain printable ASCII only (it goes into an HTTP header). Length is checked separately because
  # macOS caps regular-expression repeat counts at 255.
  if [ -n "$token" ]; then
    # Count leftover bytes with wc: $(...) would silently drop a trailing newline.
    if [ "${#token}" -gt 512 ] || [ "$(printf '%s' "$token" | LC_ALL=C tr -d '!-~' | wc -c | tr -d ' ')" != 0 ]; then
      echo "PESACAST_WEBHOOK_TOKEN must be 1-512 printable ASCII characters with no spaces."; return 1
    fi
  fi
  if [ -n "$secret" ]; then
    if [ "${#secret}" -gt 512 ] || [ "$(printf '%s' "$secret" | LC_ALL=C tr -d -c '\000-\037\177' | wc -c | tr -d ' ')" != 0 ]; then
      echo "PESACAST_WEBHOOK_SECRET is too long or contains control characters."; return 1
    fi
  fi
  return 0
}

# First 64-hex word of a checksum file's text, lower-cased; empty if none.
parse_checksum() {
  local first
  first=$(printf '%s' "$1" | tr -s ' \t\r\n' '\n' | sed -n '1p')
  if is_hex64 "$first"; then lower "$first"; fi
}

# The SHA-256 GitHub records for a release asset, from the (single-line) releases API JSON on stdin.
# Usage: ... | digest_from_release pesacast-0.9.0.apk
digest_from_release() {
  local want=$1 soh
  soh=$(printf '\001')
  sed "s/\"name\":\"/${soh}/g" | tr "$soh" '\n' | grep -F "${want}\"" | head -n 1 \
    | grep -o '"digest":"sha256:[0-9a-fA-F]\{64\}"' | head -n 1 | cut -d: -f3 | tr -d '"' | tr 'A-F' 'a-f'
}

# `adb devices` output on stdin to "serial<TAB>state" lines.
parse_devices() { awk 'NR > 1 && NF >= 2 && $1 !~ /^\*/ { print $1 "\t" $2 }'; }

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else return 1; fi
}

check_sms_granted() { grep -q 'android\.permission\.RECEIVE_SMS: granted=true'; }
check_service_running() { grep -q 'MirrorService'; }
check_battery_exempt() { grep -qF "$PACKAGE"; }

# ---------------------------------------------------------------------------------------------
# The run

DRY=0
UNINSTALL=0
YES=0
KEEP=0
VERSION="latest"
APK=""
SHA256=""
WEBHOOK_URL=""
SERIAL=""
ADB_BIN=""
TMP=""
WARNINGS=()

say() { if [ "$DRY" = 1 ]; then log "[dry run] $*"; else log "$*"; fi; }
fail() { log ""; log "Error: $*"; exit 1; }

usage_error() {
  log "Error: $1"
  log ""
  print_help
  exit 2
}

cleanup() {
  if [ -n "$TMP" ]; then
    if [ "$KEEP" = 1 ]; then log "Download kept in $TMP"; else rm -rf "$TMP"; fi
  fi
}

parse_args() {
  while [ $# -gt 0 ]; do
    case $1 in
      --dry-run) DRY=1 ;;
      --uninstall) UNINSTALL=1 ;;
      --yes) YES=1 ;;
      --keep-download) KEEP=1 ;;
      --help|-h) print_help; exit 0 ;;
      --version|--apk|--sha256|--webhook-url|--serial|--adb)
        local opt=$1
        if [ $# -lt 2 ] || [[ $2 == --* ]]; then usage_error "$opt needs a value."; fi
        shift
        case $opt in
          --version) VERSION=$1 ;;
          --apk) APK=$1 ;;
          --sha256) SHA256=$1 ;;
          --webhook-url) WEBHOOK_URL=$1 ;;
          --serial) SERIAL=$1 ;;
          --adb) ADB_BIN=$1 ;;
        esac
        ;;
      *) usage_error "Unknown option: $1" ;;
    esac
    shift
  done

  if [ "$VERSION" != "latest" ] && ! is_semver "$VERSION"; then
    usage_error "--version must look like 0.9.0 or be \"latest\" (got \"$VERSION\")."
  fi
  if [ -n "$SHA256" ] && ! is_hex64 "$SHA256"; then usage_error "--sha256 must be 64 hexadecimal characters."; fi
  if [ -n "$APK" ] && [ "$VERSION" != "latest" ]; then usage_error "Use either --apk or --version, not both."; fi
  if [ "$UNINSTALL" = 1 ] && [ -n "$WEBHOOK_URL" ]; then usage_error "--uninstall cannot be combined with --webhook-url."; fi
  if { [ -n "${PESACAST_WEBHOOK_TOKEN-}" ] || [ -n "${PESACAST_WEBHOOK_SECRET-}" ]; } && [ -z "$WEBHOOK_URL" ]; then
    usage_error "A token or secret was set in the environment but no --webhook-url was given."
  fi
  if [ -n "$WEBHOOK_URL" ]; then
    local problem
    if ! problem=$(webhook_problem "$WEBHOOK_URL" "${PESACAST_WEBHOOK_TOKEN-}" "${PESACAST_WEBHOOK_SECRET-}"); then
      usage_error "$problem"
    fi
  fi
}

# adb, honouring --dry-run (prints the command, runs nothing). Output of the command goes to stdout.
adb_cmd() {
  if [ "$DRY" = 1 ]; then
    log "[dry run] adb $*"
    return 0
  fi
  "$ADB_BIN" "$@" 2>&1 | tr -d '\r'
  return "${PIPESTATUS[0]}"
}

find_adb() {
  if [ -n "$ADB_BIN" ]; then return; fi
  if [ -n "${ANDROID_HOME-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB_BIN="$ANDROID_HOME/platform-tools/adb"
  else
    ADB_BIN="adb"
  fi
}

require_adb() {
  if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    fail "adb was not found. Install Android platform-tools (https://developer.android.com/tools/releases/platform-tools) or pass --adb."
  fi
}

# Sets SERIAL (and DEVICE_*), or fails with a message the user can act on.
pick_device() {
  local listed lines count state
  listed=$("$ADB_BIN" devices 2>&1 | tr -d '\r') || true
  lines=$(printf '%s\n' "$listed" | parse_devices)
  if [ -n "$SERIAL" ]; then
    state=$(printf '%s\n' "$lines" | awk -F'\t' -v s="$SERIAL" '$1 == s { print $2 }')
    [ -n "$state" ] || fail "No phone with serial $SERIAL is connected."
  else
    count=$(printf '%s\n' "$lines" | grep -c . || true)
    if [ "$count" = 0 ]; then
      fail "No phone found. Plug it in, turn on USB debugging, and accept the prompt on the phone."
    elif [ "$count" -gt 1 ]; then
      fail "More than one phone is connected ($(printf '%s\n' "$lines" | cut -f1 | tr '\n' ' ')). Pass --serial."
    fi
    SERIAL=$(printf '%s\n' "$lines" | cut -f1)
    state=$(printf '%s\n' "$lines" | cut -f2)
  fi
  case $state in
    device) ;;
    unauthorized) fail "The phone $SERIAL has not accepted this computer. Unlock it and tap Allow on the USB debugging prompt." ;;
    *) fail "The phone $SERIAL is not ready (state: $state)." ;;
  esac
}

prop() { "$ADB_BIN" -s "$SERIAL" shell getprop "$1" 2>&1 | tr -d '\r\n'; }

# Downloads (or locates) the APK and sets APK_PATH and EXPECTED. Verification happens afterwards.
fetch_apk() {
  EXPECTED=$(lower "$SHA256")
  if [ -n "$APK" ]; then
    APK_PATH=$APK
    if [ -z "$EXPECTED" ] && [ -r "$APK.sha256" ]; then EXPECTED=$(parse_checksum "$(cat "$APK.sha256")"); fi
    return
  fi

  local version=$VERSION name base rel
  if [ "$version" = "latest" ]; then
    rel=$(curl -fsSL -H 'Accept: application/vnd.github+json' -A 'pesacast-setup' "https://api.github.com/repos/$REPO/releases/latest") \
      || fail "Could not look up the latest release."
    version=$(printf '%s' "$rel" | grep -o '"tag_name":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    version=${version#pesacast@}
    is_semver "$version" || fail "Could not work out the latest version from the release information."
  fi
  name="pesacast-$version.apk"
  base="https://github.com/$REPO/releases/download/pesacast@$version"
  TMP=$(mktemp -d "${TMPDIR:-/tmp}/pesacast.XXXXXX") || fail "Could not create a temporary folder."
  APK_PATH="$TMP/$name"
  log "Downloading PesaCast $version…"
  curl -fsSL -A 'pesacast-setup' -o "$APK_PATH" "$base/$name" || fail "Could not download $base/$name"

  if [ -z "$EXPECTED" ]; then
    # Releases from the checksum step onwards publish pesacast-<v>.apk.sha256.
    if curl -fsSL -A 'pesacast-setup' -o "$TMP/$name.sha256" "$base/$name.sha256" 2>/dev/null; then
      EXPECTED=$(parse_checksum "$(cat "$TMP/$name.sha256")")
    fi
  fi
  if [ -z "$EXPECTED" ]; then
    # Older releases have no .sha256 file, but GitHub records a SHA-256 for every uploaded asset.
    rel=$(curl -fsSL -H 'Accept: application/vnd.github+json' -A 'pesacast-setup' "https://api.github.com/repos/$REPO/releases/tags/pesacast@$version" 2>/dev/null) || rel=""
    EXPECTED=$(printf '%s' "$rel" | digest_from_release "$name")
    if [ -n "$EXPECTED" ]; then log "This release has no .sha256 file; using the SHA-256 that GitHub recorded for the APK."; fi
  fi
}

verify_apk() {
  if [ -z "$EXPECTED" ]; then
    fail "No checksum to verify the APK against: this release has no .sha256 file and GitHub has no recorded digest for it.
  Download the APK yourself, then run again with --apk <file> and --sha256 <hash> (a hash you obtained from a source you trust)."
  fi
  local actual
  actual=$(sha256_file "$APK_PATH") || fail "Neither shasum nor sha256sum is available to verify the APK."
  actual=$(lower "$actual")
  if [ "$actual" != "$EXPECTED" ]; then
    fail "The APK's SHA-256 does not match.
  expected $EXPECTED
  actual   $actual
Not installing it."
  fi
  log "Checksum OK."
}

main() {
  trap cleanup EXIT
  parse_args "$@"
  find_adb

  local sdk=34 bluetooth=1 model="<model>" release="<android>"
  if [ "$DRY" = 1 ]; then
    SERIAL=${SERIAL:-"<serial>"}
    log "[dry run] adb devices   (then: pick the phone, read model and Android version)"
  else
    require_adb
    pick_device
    local features
    features=$("$ADB_BIN" -s "$SERIAL" shell pm list features 2>&1 | tr -d '\r')
    model="$(prop ro.product.manufacturer) $(prop ro.product.model)"
    release=$(prop ro.build.version.release)
    sdk=$(prop ro.build.version.sdk)
    case $sdk in ''|*[!0-9]*) sdk=0 ;; esac
    if printf '%s' "$features" | grep -q 'android\.hardware\.bluetooth\b'; then bluetooth=1; else bluetooth=0; fi
    log "Phone: $model, Android $release (API $sdk), serial $SERIAL"
  fi
  local target=(-s "$SERIAL")

  # --- uninstall
  if [ "$UNINSTALL" = 1 ]; then
    if [ "$YES" != 1 ] && [ "$DRY" != 1 ]; then
      log "This removes PesaCast and its saved settings from the phone. Run again with --yes to confirm."
      exit 2
    fi
    local out
    if [ "$DRY" = 1 ]; then
      adb_cmd "${target[@]}" shell dumpsys deviceidle whitelist "-$PACKAGE"
      adb_cmd "${target[@]}" shell cmd appops reset "$PACKAGE"
      adb_cmd "${target[@]}" uninstall "$PACKAGE"
    else
      adb_cmd "${target[@]}" shell dumpsys deviceidle whitelist "-$PACKAGE" >/dev/null && log "  battery exemption removed"
      adb_cmd "${target[@]}" shell cmd appops reset "$PACKAGE" >/dev/null && log "  app permissions reset"
      out=$(adb_cmd "${target[@]}" uninstall "$PACKAGE")
      printf '%s' "$out" | grep -q Success || fail "Uninstall did not report success: $out"
      log "  uninstalled"
    fi
    say "Done."
    return 0
  fi

  # --- APK
  if [ "$DRY" = 1 ]; then
    if [ -n "$APK" ]; then
      log "[dry run] verify the SHA-256 of the local APK against its .sha256 file (or --sha256)"
      APK_PATH=$APK
    else
      log "[dry run] download pesacast-$([ "$VERSION" = latest ] && echo '<latest>' || echo "$VERSION").apk and its .sha256 from github.com/$REPO/releases"
      log "[dry run] verify the SHA-256 and stop if it does not match"
      APK_PATH="<apk>"
    fi
  else
    fetch_apk
    verify_apk
  fi

  # --- install and grant
  local out
  out=$(adb_cmd "${target[@]}" install -r "$APK_PATH")
  if [ "$DRY" = 1 ]; then printf '%s\n' "$out"; else
    printf '%s' "$out" | grep -q Success || fail "Install failed: $(printf '%s' "$out" | tr '\n' '|')"
    log "  installed"
  fi

  grant() { # perm, label, [warning]
    local o
    if o=$(adb_cmd "${target[@]}" shell pm grant "$PACKAGE" "$1"); then
      if [ "$DRY" = 1 ]; then printf '%s\n' "$o"; else log "  $2 granted"; fi
    else
      WARNINGS+=("${3:-$2 could not be granted from the computer; allow it on the phone.}")
    fi
  }
  grant android.permission.RECEIVE_SMS "SMS permission" \
    "The SMS permission could not be granted from the computer. Open the setup checklist on the phone and allow it (Android 13+ may need App info, three dots, Allow restricted settings)."
  if [ "$DRY" = 1 ] || [ "$sdk" -ge 33 ]; then grant android.permission.POST_NOTIFICATIONS "Notification permission"; fi
  if [ "$DRY" = 1 ] || { [ "$sdk" -ge 31 ] && [ "$bluetooth" = 1 ]; }; then
    grant android.permission.BLUETOOTH_CONNECT "Bluetooth connect permission"
    grant android.permission.BLUETOOTH_ADVERTISE "Bluetooth advertise permission"
  fi

  # --- keep it alive in the background (best effort; failures are reported, not fatal)
  soft() { # label, adb shell args...
    local label=$1 o
    shift
    if o=$(adb_cmd "${target[@]}" shell "$@"); then
      if [ "$DRY" = 1 ]; then printf '%s\n' "$o"; else log "  $label"; fi
    else
      WARNINGS+=("$label could not be set from the computer.")
    fi
  }
  soft "excluded from battery optimisation" dumpsys deviceidle whitelist "+$PACKAGE"
  soft "background running allowed" cmd appops set "$PACKAGE" RUN_ANY_IN_BACKGROUND allow
  soft "restricted settings allowed (not supported on every Android version)" cmd appops set "$PACKAGE" ACCESS_RESTRICTED_SETTINGS allow

  # --- open the app (starts the service), then offer the webhook settings
  out=$(adb_cmd "${target[@]}" shell am start -n "$PACKAGE/.ui.MainActivity")
  if [ "$DRY" = 1 ]; then printf '%s\n' "$out"; else log "  app opened"; sleep "${PESACAST_SETUP_WAIT:-2.5}"; fi

  if [ -n "$WEBHOOK_URL" ]; then
    local cmd="am start -n $PACKAGE/.setup.ProvisionActivity --es webhook_url $(shq "$WEBHOOK_URL")"
    local shown="am start -n $PACKAGE/.setup.ProvisionActivity --es webhook_url $(shq "$WEBHOOK_URL")"
    if [ -n "${PESACAST_WEBHOOK_TOKEN-}" ]; then
      cmd="$cmd --es webhook_token $(shq "$PESACAST_WEBHOOK_TOKEN")"; shown="$shown --es webhook_token '***'"
    fi
    if [ -n "${PESACAST_WEBHOOK_SECRET-}" ]; then
      cmd="$cmd --es webhook_secret $(shq "$PESACAST_WEBHOOK_SECRET")"; shown="$shown --es webhook_secret '***'"
    fi
    if [ "$DRY" = 1 ]; then
      log "[dry run] adb ${target[*]} shell $shown"
    else
      out=$("$ADB_BIN" "${target[@]}" shell "$cmd" 2>&1 | tr -d '\r')
      if printf '%s' "$out" | grep -qiE 'Error|Exception'; then
        WARNINGS+=("This version of PesaCast has no webhook provisioning screen. Update to a newer release, or enter the webhook on the phone.")
      else
        log "  webhook settings sent: check the host shown on the phone and tap Save and test"
      fi
    fi
  fi

  # --- verify
  if [ "$DRY" = 1 ]; then
    log "[dry run] check: SMS permission granted, service running, battery exemption present"
    log "[dry run] Nothing was changed."
    return 0
  fi
  local sms=0 svc=0 bat=0
  adb_cmd "${target[@]}" shell dumpsys package "$PACKAGE" | check_sms_granted && sms=1
  adb_cmd "${target[@]}" shell dumpsys activity services "$PACKAGE" | check_service_running && svc=1
  adb_cmd "${target[@]}" shell dumpsys deviceidle whitelist | check_battery_exempt && bat=1

  log ""
  log "Checks:"
  if [ $sms = 1 ]; then log "  ok       SMS permission granted"; else log "  MISSING  SMS permission granted"; fi
  if [ $svc = 1 ]; then log "  ok       Background service running"; else log "  MISSING  Background service running"; fi
  if [ $bat = 1 ]; then log "  ok       Excluded from battery optimisation"; else log "  not set  Excluded from battery optimisation"; fi
  if [ "${#WARNINGS[@]}" -gt 0 ]; then
    local i
    for ((i = 0; i < ${#WARNINGS[@]}; i++)); do log ""; log "Note: ${WARNINGS[$i]}"; done
  fi

  log ""
  log "Still to do on the phone (no computer can do these):"
  log "  - Open PesaCast, then Connect, then Setup checklist, and work down it."
  log "  - Confirm your phone maker's auto-start / background menu if the checklist shows one."
  log "  - Run the checklist's end-to-end test."
  if [ $sms = 1 ] && [ $svc = 1 ]; then
    log ""
    log "All required checks passed."
    return 0
  fi
  log ""
  log "Not ready yet: fix the items marked MISSING, then run this again."
  return 1
}

# Run only when executed, not when sourced by the tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
