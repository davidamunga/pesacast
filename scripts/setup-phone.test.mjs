import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  PACKAGE, UsageError, checks, formatCommand, main, parseArgs, parseChecksumFile, parseDevices, pickDevice, shQuote, validateWebhook,
} from "./setup-phone.mjs";

// ---- fakes ------------------------------------------------------------------------------------

function fakePhone(over = {}) {
  const phone = {
    devices: "List of devices attached\nABC123\tdevice\n",
    props: { "ro.product.manufacturer": "TECNO", "ro.product.model": "KG5", "ro.build.version.release": "13", "ro.build.version.sdk": "33" },
    features: "feature:android.hardware.bluetooth\nfeature:android.hardware.telephony\n",
    install: "Performing Streamed Install\nSuccess\n",
    failCommands: [],
    pkgInfo: "    android.permission.RECEIVE_SMS: granted=true\n",
    services: "ServiceRecord{x u0 com.pesacast.android/.service.MirrorService}\n",
    idle: `system-excidle,x\nuser,${PACKAGE},10123\n`,
    amStart: "Starting: Intent { cmp=... }\n",
    uninstall: "Success\n",
    ...over,
  };
  const calls = [];
  const logs = [];
  const downloads = [];
  const io = {
    env: over.env ?? {},
    log: (...a) => logs.push(a.join(" ")),
    sleep: async () => {},
    run: async (file, args) => {
      calls.push({ file, args });
      const j = args.join(" ");
      const shell = (rest) => j.includes(`shell ${rest}`);
      if (phone.failCommands.some((f) => j.includes(f))) return { code: 1, stdout: "", stderr: "failed" };
      if (j === "devices") return { code: 0, stdout: phone.devices, stderr: "" };
      if (shell("getprop")) return { code: 0, stdout: phone.props[args.at(-1)] + "\n", stderr: "" };
      if (shell("pm list features")) return { code: 0, stdout: phone.features, stderr: "" };
      if (j.includes(" install -r ")) return { code: 0, stdout: phone.install, stderr: "" };
      if (j.includes(" uninstall ")) return { code: 0, stdout: phone.uninstall, stderr: "" };
      if (shell("dumpsys package")) return { code: 0, stdout: phone.pkgInfo, stderr: "" };
      if (shell("dumpsys activity services")) return { code: 0, stdout: phone.services, stderr: "" };
      if (shell("dumpsys deviceidle whitelist") && !j.includes("+") && !j.includes(" -")) return { code: 0, stdout: phone.idle, stderr: "" };
      if (shell("am start")) return { code: 0, stdout: phone.amStart, stderr: "" };
      return { code: 0, stdout: "", stderr: "" };
    },
    fetchJson: async () => ({ tag_name: "pesacast@0.9.0" }),
    download: async (url, dest) => {
      downloads.push(url);
      await writeFile(dest, url.endsWith(".sha256") ? `${phone.sha ?? ""}  pesacast-0.9.0.apk\n` : "APK-BYTES");
    },
    readText: async (p) => (await import("node:fs/promises")).readFile(p, "utf8"),
    sha256File: async (p) => createHash("sha256").update(await (await import("node:fs/promises")).readFile(p)).digest("hex"),
    makeTempDir: () => mkdtemp(join(tmpdir(), "pesacast-test-")),
    removeDir: (p) => rm(p, { recursive: true, force: true }),
  };
  return { io, calls, logs, downloads, phone };
}

const sha = (text) => createHash("sha256").update(text).digest("hex");
const adbArgs = (calls) => calls.map((c) => c.args.filter((a) => a !== "-s" && a !== "ABC123").join(" "));

async function localApk(contents = "APK-BYTES") {
  const dir = await mkdtemp(join(tmpdir(), "pesacast-apk-"));
  const apk = join(dir, "pesacast-0.9.0.apk");
  await writeFile(apk, contents);
  await writeFile(`${apk}.sha256`, `${sha(contents)}  pesacast-0.9.0.apk\n`);
  return { dir, apk };
}

// ---- argument handling -------------------------------------------------------------------------

test("defaults and flags", () => {
  const o = parseArgs([]);
  assert.equal(o.version, "latest");
  assert.equal(o.dryRun, false);
  const p = parseArgs(["--dry-run", "--version", "0.9.0", "--serial", "X1", "--keep-download"]);
  assert.deepEqual([p.dryRun, p.version, p.serial, p.keepDownload], [true, "0.9.0", "X1", true]);
});

test("bad arguments are refused with a usage error", () => {
  for (const argv of [
    ["--nope"], ["--version"], ["--version", "1.2"], ["--version", "../../x"], ["--sha256", "abc"],
    ["--apk", "a.apk", "--version", "0.9.0"], ["--uninstall", "--webhook-url", "https://e.com/h"],
    ["--webhook-url"],
  ]) assert.throws(() => parseArgs(argv), UsageError, argv.join(" "));
  assert.throws(() => parseArgs([], { PESACAST_WEBHOOK_TOKEN: "t" }), UsageError, "token without a url");
});

test("webhook rules match the phone's", () => {
  validateWebhook("https://example.com/hook", "tok-1", "a secret ✓");
  for (const bad of ["http://example.com/h", "https://user:pw@example.com/h", "https://", "ftp://x.com", "https://аpple.com/h", "not a url"]) {
    assert.throws(() => validateWebhook(bad), UsageError, bad);
  }
  assert.throws(() => validateWebhook("https://e.com/h", "has space"), UsageError);
  assert.throws(() => validateWebhook("https://e.com/h", "line\nbreak"), UsageError);
  assert.throws(() => validateWebhook("https://e.com/h", "a".repeat(513)), UsageError);
  assert.throws(() => validateWebhook("https://e.com/h", undefined, "bad\u0000"), UsageError);
});

test("credentials come from the environment, never from arguments", () => {
  const o = parseArgs(["--webhook-url", "https://e.com/h"], { PESACAST_WEBHOOK_TOKEN: "tok", PESACAST_WEBHOOK_SECRET: "sec" });
  assert.equal(o.webhookToken, "tok");
  assert.equal(o.webhookSecret, "sec");
  assert.throws(() => parseArgs(["--webhook-token", "x"]), UsageError);
});

// ---- helpers -----------------------------------------------------------------------------------

test("shQuote survives the phone's shell", { skip: process.platform === "win32" }, () => {
  for (const value of ["plain", "with space", "it's", "a&b;c|d", "$HOME `id` $(id)", "back\\slash", "\"dq\"", "multi\nline", "*?[x]"]) {
    const out = execFileSync("/bin/sh", ["-c", `printf %s ${shQuote(value)}`], { encoding: "utf8" });
    assert.equal(out, value);
  }
});

test("credentials are hidden when a command is shown", () => {
  const shown = formatCommand("adb", ["-s", "X", "shell", "am", "start", "--es", "webhook_url", "'https://e.com/h'", "--es", "webhook_token", "'TOKVALUE'", "--es", "webhook_secret", "'SECVALUE'"]);
  assert.ok(shown.includes("https://e.com/h"));
  assert.ok(!shown.includes("TOKVALUE"));
  assert.ok(!shown.includes("SECVALUE"));
  assert.equal(shown.match(/\*\*\*/g).length, 2);
});

test("checksum files, device lists and device choice", () => {
  const h = "a".repeat(64);
  assert.equal(parseChecksumFile(`${h.toUpperCase()}  pesacast.apk\n`), h);
  assert.equal(parseChecksumFile("nonsense"), null);
  assert.equal(parseChecksumFile(""), null);

  const devs = parseDevices("List of devices attached\n* daemon started\nAAA\tdevice\nBBB\tunauthorized\n\n");
  assert.deepEqual(devs, [{ serial: "AAA", state: "device" }, { serial: "BBB", state: "unauthorized" }]);

  assert.equal(pickDevice([{ serial: "A", state: "device" }]).serial, "A");
  assert.throws(() => pickDevice([]), /No phone found/);
  assert.throws(() => pickDevice([{ serial: "A", state: "device" }, { serial: "B", state: "device" }]), /--serial/);
  assert.equal(pickDevice([{ serial: "A", state: "device" }, { serial: "B", state: "device" }], "B").serial, "B");
  assert.throws(() => pickDevice([{ serial: "A", state: "unauthorized" }]), /Allow/);
  assert.throws(() => pickDevice([{ serial: "A", state: "offline" }]), /not ready/);
  assert.throws(() => pickDevice([{ serial: "A", state: "device" }], "Z"), /No phone with serial/);
});

test("check parsers", () => {
  assert.ok(checks.smsGranted("android.permission.RECEIVE_SMS: granted=true"));
  assert.ok(!checks.smsGranted("android.permission.RECEIVE_SMS: granted=false"));
  assert.ok(checks.serviceRunning("ServiceRecord{.. com.pesacast.android/.service.MirrorService}"));
  assert.ok(!checks.serviceRunning("(nothing)"));
  assert.ok(checks.batteryExempt(`user,${PACKAGE},1\n`));
  assert.ok(!checks.batteryExempt("user,com.other,1\n"));
});

// ---- the run -----------------------------------------------------------------------------------

test("a dry run contacts nothing and changes nothing, and hides credentials", async () => {
  const f = fakePhone({ env: { PESACAST_WEBHOOK_TOKEN: "tok-SECRET", PESACAST_WEBHOOK_SECRET: "sec-SECRET" } });
  const code = await main(["--dry-run", "--webhook-url", "https://example.com/hook"], f.io);
  assert.equal(code, 0);
  assert.equal(f.calls.length, 0, "no adb command may run");
  assert.equal(f.downloads.length, 0, "nothing may be downloaded");
  const out = f.logs.join("\n");
  assert.ok(out.includes("install -r"));
  assert.ok(out.includes("pm grant com.pesacast.android android.permission.RECEIVE_SMS"));
  assert.ok(out.includes("ProvisionActivity"));
  assert.ok(out.includes("https://example.com/hook"));
  assert.ok(!out.includes("tok-SECRET") && !out.includes("sec-SECRET"), "credentials must not be printed");
});

test("happy path: verified local APK is installed, granted, kept alive and checked", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone();
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 0, f.logs.join("\n"));
  const cmds = adbArgs(f.calls);
  const at = (needle) => cmds.findIndex((c) => c.includes(needle));
  assert.ok(at("install -r") > at("getprop"), "installs after reading the phone");
  assert.ok(at("pm grant com.pesacast.android android.permission.RECEIVE_SMS") > at("install -r"));
  assert.ok(at("POST_NOTIFICATIONS") > 0, "API 33 gets the notification permission");
  assert.ok(at("BLUETOOTH_CONNECT") > 0 && at("BLUETOOTH_ADVERTISE") > 0, "API 33 with Bluetooth gets both Bluetooth permissions");
  assert.ok(at("deviceidle whitelist +") > at("pm grant"));
  assert.ok(at("am start -n com.pesacast.android/.ui.MainActivity") > at("deviceidle whitelist +"));
  assert.ok(at("dumpsys package") > at("am start"), "verifies last");
  assert.ok(f.logs.join("\n").includes("All required checks passed"));
});

test("an install failure shows the reason adb gives on stderr", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ install: "Performing Streamed Install\n" });
  const orig = f.io.run;
  f.io.run = async (file, args) =>
    args.join(" ").includes(" install -r ")
      ? { code: 1, stdout: "Performing Streamed Install\n", stderr: "adb: failed to install x.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" }
      : orig(file, args);
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.ok(f.logs.join("\n").includes("INSTALL_FAILED_VERSION_DOWNGRADE"));
});

test("a checksum mismatch stops before anything is installed", async () => {
  const dir = await mkdtemp(join(tmpdir(), "pesacast-apk-"));
  const apk = join(dir, "pesacast-0.9.0.apk");
  await writeFile(apk, "tampered");
  await writeFile(`${apk}.sha256`, `${sha("original")}  pesacast-0.9.0.apk\n`);
  const f = fakePhone();
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.ok(f.logs.join("\n").includes("does not match"));
  assert.ok(!adbArgs(f.calls).some((c) => c.includes("install -r")), "must not install");
});

test("an APK with no checksum is refused", async () => {
  const dir = await mkdtemp(join(tmpdir(), "pesacast-apk-"));
  const apk = join(dir, "pesacast-0.9.0.apk");
  await writeFile(apk, "x");
  const f = fakePhone();
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.ok(f.logs.join("\n").includes("No checksum"));
  assert.ok(!adbArgs(f.calls).some((c) => c.includes("install -r")));
});

test("--sha256 can supply the expected checksum", async () => {
  const dir = await mkdtemp(join(tmpdir(), "pesacast-apk-"));
  const apk = join(dir, "x.apk");
  await writeFile(apk, "bytes");
  const f = fakePhone();
  const code = await main(["--apk", apk, "--sha256", sha("bytes")], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 0, f.logs.join("\n"));
});

test("an older phone gets only the permissions that exist on it", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ props: { "ro.product.manufacturer": "x", "ro.product.model": "y", "ro.build.version.release": "10", "ro.build.version.sdk": "29" } });
  await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  const cmds = adbArgs(f.calls).join("\n");
  assert.ok(cmds.includes("RECEIVE_SMS"));
  assert.ok(!cmds.includes("POST_NOTIFICATIONS"));
  assert.ok(!cmds.includes("BLUETOOTH_CONNECT"));
});

test("a phone without Bluetooth is not asked for Bluetooth permissions", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ features: "feature:android.hardware.telephony\n", props: { "ro.product.manufacturer": "x", "ro.product.model": "y", "ro.build.version.release": "14", "ro.build.version.sdk": "34" } });
  await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.ok(!adbArgs(f.calls).join("\n").includes("BLUETOOTH"));
});

test("when the SMS permission cannot be granted the run reports it and is not ready", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ failCommands: ["RECEIVE_SMS"], pkgInfo: "android.permission.RECEIVE_SMS: granted=false\n" });
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  const out = f.logs.join("\n");
  assert.ok(out.includes("MISSING  SMS permission granted"));
  assert.ok(out.includes("restricted settings"));
  assert.ok(out.includes("Not ready yet"));
});

test("a service that is not running makes the run not ready", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ services: "(nothing)\n" });
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.ok(f.logs.join("\n").includes("MISSING  Background service running"));
});

test("battery settings that fail are reported but are not fatal", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ failCommands: ["deviceidle whitelist +", "ACCESS_RESTRICTED_SETTINGS"], idle: "user,com.other,1\n" });
  const code = await main(["--apk", apk], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 0);
  const out = f.logs.join("\n");
  assert.ok(out.includes("not set  Excluded from battery optimisation"));
  assert.ok(out.includes("could not be set"));
});

test("webhook settings go to the confirmation screen, quoted for the phone's shell, after the app is opened", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ env: { PESACAST_WEBHOOK_TOKEN: "tok123", PESACAST_WEBHOOK_SECRET: "it's a $ecret; rm -rf" } });
  const code = await main(["--apk", apk, "--webhook-url", "https://example.com/h?a=1&b=2"], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 0, f.logs.join("\n"));
  const cmds = adbArgs(f.calls);
  const main_ = cmds.findIndex((c) => c.includes(".ui.MainActivity"));
  const prov = cmds.findIndex((c) => c.includes("ProvisionActivity"));
  assert.ok(prov > main_, "the confirmation screen must open on top of the app");
  const call = f.calls.find((c) => c.args.join(" ").includes("ProvisionActivity")).args;
  assert.ok(call.includes(shQuote("https://example.com/h?a=1&b=2")));
  assert.ok(call.includes(shQuote("it's a $ecret; rm -rf")));
  assert.ok(!f.logs.join("\n").includes("tok123"), "credentials must never be printed");
  assert.ok(f.logs.join("\n").includes("tap Save and test"));
});

test("an older app without the provisioning screen is explained", async () => {
  const { dir, apk } = await localApk();
  const f = fakePhone({ amStart: "Error type 3\nError: Activity class {com.pesacast.android/com.pesacast.android.setup.ProvisionActivity} does not exist.\n" });
  // Only the provisioning start returns the error; the main activity start is fine.
  const orig = f.io.run;
  f.io.run = async (file, args) => (args.join(" ").includes("ui.MainActivity") ? { code: 0, stdout: "Starting\n", stderr: "" } : orig(file, args));
  const code = await main(["--apk", apk, "--webhook-url", "https://example.com/h"], f.io);
  await rm(dir, { recursive: true, force: true });
  assert.equal(code, 0);
  assert.ok(f.logs.join("\n").includes("no webhook provisioning screen"));
});

test("downloading resolves the latest release, verifies it and cleans up", async () => {
  const f = fakePhone();
  f.phone.sha = sha("APK-BYTES");
  const code = await main([], f.io);
  assert.equal(code, 0, f.logs.join("\n"));
  assert.deepEqual(f.downloads, [
    "https://github.com/davidamunga/pesacast/releases/download/pesacast@0.9.0/pesacast-0.9.0.apk",
    "https://github.com/davidamunga/pesacast/releases/download/pesacast@0.9.0/pesacast-0.9.0.apk.sha256",
  ]);
  assert.ok(f.logs.join("\n").includes("Checksum OK"));
});

test("a downloaded APK that does not match its published checksum is not installed", async () => {
  const f = fakePhone();
  f.phone.sha = sha("something else");
  const code = await main(["--version", "0.9.0"], f.io);
  assert.equal(code, 1);
  assert.ok(!adbArgs(f.calls).some((c) => c.includes("install -r")));
});

test("no phone connected, several phones, and adb missing are explained", async () => {
  const { dir, apk } = await localApk();
  let f = fakePhone({ devices: "List of devices attached\n\n" });
  assert.equal(await main(["--apk", apk], f.io), 1);
  assert.ok(f.logs.join("\n").includes("No phone found"));

  f = fakePhone({ devices: "List of devices attached\nA\tdevice\nB\tdevice\n" });
  assert.equal(await main(["--apk", apk], f.io), 1);
  assert.ok(f.logs.join("\n").includes("--serial"));

  f = fakePhone();
  f.io.run = async () => ({ code: 1, stdout: "", stderr: "", spawnError: true });
  assert.equal(await main(["--apk", apk], f.io), 1);
  assert.ok(f.logs.join("\n").includes("adb was not found"));
  await rm(dir, { recursive: true, force: true });
});

test("uninstall needs --yes, then removes the exemption, settings and app", async () => {
  let f = fakePhone();
  assert.equal(await main(["--uninstall"], f.io), 2);
  assert.ok(!adbArgs(f.calls).some((c) => c.includes("uninstall")));

  f = fakePhone();
  assert.equal(await main(["--uninstall", "--yes"], f.io), 0, f.logs.join("\n"));
  const cmds = adbArgs(f.calls).join("\n");
  assert.ok(cmds.includes(`deviceidle whitelist -${PACKAGE}`));
  assert.ok(cmds.includes("appops reset"));
  assert.ok(cmds.includes(`uninstall ${PACKAGE}`));
});

test("--help and usage errors", async () => {
  let f = fakePhone();
  assert.equal(await main(["--help"], f.io), 0);
  assert.ok(f.logs[0].includes("Usage:"));
  f = fakePhone();
  assert.equal(await main(["--bogus"], f.io), 2);
  assert.ok(f.logs[0].includes("Unknown option"));
  assert.equal(f.calls.length, 0);
});
