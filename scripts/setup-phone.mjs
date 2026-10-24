#!/usr/bin/env node
// One-command setup for the phone that receives the M-PESA messages (ADR-0015, docs/phone-setup.md).
// Node 20+, no dependencies, works on macOS, Linux and Windows. It runs the same `adb` commands as
// the manual steps in the docs: download and verify the APK, install it, grant what can be granted
// from a computer, exclude it from battery saving, optionally send the webhook settings to the
// phone's confirmation screen, then check the result.
//
//   node scripts/setup-phone.mjs --dry-run
//   node scripts/setup-phone.mjs --version 0.9.0 --webhook-url https://example.com/hook
//
// Webhook credentials come from the environment, not the command line:
//   PESACAST_WEBHOOK_TOKEN=... PESACAST_WEBHOOK_SECRET=... node scripts/setup-phone.mjs --webhook-url ...
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const PACKAGE = "com.pesacast.android";
const REPO = "davidamunga/pesacast";
const SEMVER = /^\d+\.\d+\.\d+$/;
const HEX64 = /^[0-9a-f]{64}$/i;

export const HELP = `Set up PesaCast on a phone over USB (adb).

Usage: node scripts/setup-phone.mjs [options]

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
computer can do; the script lists them at the end.`;

// ---------------------------------------------------------------------------------------------
// Pure helpers (unit tested)

export function parseArgs(argv, env = {}) {
  const opts = { version: "latest", dryRun: false, uninstall: false, yes: false, keepDownload: false };
  const needsValue = new Set(["--version", "--apk", "--sha256", "--webhook-url", "--serial", "--adb"]);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--dry-run") opts.dryRun = true;
    else if (a === "--uninstall") opts.uninstall = true;
    else if (a === "--yes") opts.yes = true;
    else if (a === "--keep-download") opts.keepDownload = true;
    else if (a === "--help" || a === "-h") opts.help = true;
    else if (needsValue.has(a)) {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new UsageError(`${a} needs a value.`);
      opts[a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = v;
    } else throw new UsageError(`Unknown option: ${a}`);
  }
  if (opts.version !== "latest" && !SEMVER.test(opts.version)) {
    throw new UsageError(`--version must look like 0.9.0 or be "latest" (got "${opts.version}").`);
  }
  if (opts.sha256 !== undefined && !HEX64.test(opts.sha256)) {
    throw new UsageError("--sha256 must be 64 hexadecimal characters.");
  }
  if (opts.apk && opts.version !== "latest") throw new UsageError("Use either --apk or --version, not both.");
  if (opts.uninstall && opts.webhookUrl) throw new UsageError("--uninstall cannot be combined with --webhook-url.");
  opts.webhookToken = env.PESACAST_WEBHOOK_TOKEN || undefined;
  opts.webhookSecret = env.PESACAST_WEBHOOK_SECRET || undefined;
  if ((opts.webhookToken || opts.webhookSecret) && !opts.webhookUrl) {
    throw new UsageError("A token or secret was set in the environment but no --webhook-url was given.");
  }
  if (opts.webhookUrl) validateWebhook(opts.webhookUrl, opts.webhookToken, opts.webhookSecret);
  return opts;
}

export class UsageError extends Error {}

/** The same rules the phone enforces (ProvisionRequest.kt), so a bad request fails here, early. */
export function validateWebhook(url, token, secret) {
  if ([...url].some((c) => c.charCodeAt(0) > 127)) {
    throw new UsageError("--webhook-url contains non-ASCII characters; use the ASCII (xn--) form of the host.");
  }
  let u;
  try {
    u = new URL(url.trim());
  } catch {
    throw new UsageError("--webhook-url is not a valid URL.");
  }
  if (u.protocol !== "https:") throw new UsageError("--webhook-url must start with https://.");
  if (!u.hostname) throw new UsageError("--webhook-url needs a host name.");
  if (u.username || u.password) throw new UsageError("Put credentials in the token or secret, not in the URL.");
  if (url.length > 2048) throw new UsageError("--webhook-url is too long.");
  if (token !== undefined && !/^[\x21-\x7e]{1,512}$/.test(token)) {
    throw new UsageError("PESACAST_WEBHOOK_TOKEN must be 1-512 printable ASCII characters with no spaces.");
  }
  if (secret !== undefined && (secret.length > 512 || /[\x00-\x1f\x7f]/.test(secret))) {
    throw new UsageError("PESACAST_WEBHOOK_SECRET is too long or contains control characters.");
  }
}

/** Quotes a value for the phone's shell, which receives adb's arguments joined by spaces. */
export function shQuote(value) {
  return `'${String(value).replaceAll("'", `'\\''`)}'`;
}

/** The command line for display, with webhook credentials hidden. */
export function formatCommand(file, args) {
  const shown = args.map((a, i) =>
    ["webhook_token", "webhook_secret"].includes(args[i - 1]) ? "'***'" : /[\s'"]/.test(a) && !a.startsWith("'") ? JSON.stringify(a) : a,
  );
  return [file, ...shown].join(" ");
}

export function parseChecksumFile(text) {
  const first = text.trim().split(/\s+/)[0] ?? "";
  return HEX64.test(first) ? first.toLowerCase() : null;
}

/** `adb devices` output to a list of { serial, state }. */
export function parseDevices(text) {
  return text
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[0] && !p[0].startsWith("*"))
    .map(([serial, state]) => ({ serial, state }));
}

export function pickDevice(devices, wantedSerial) {
  if (wantedSerial) {
    const d = devices.find((x) => x.serial === wantedSerial);
    if (!d) throw new Error(`No phone with serial ${wantedSerial} is connected.`);
    return needReady(d);
  }
  if (devices.length === 0) {
    throw new Error("No phone found. Plug it in, turn on USB debugging, and accept the prompt on the phone.");
  }
  if (devices.length > 1) {
    throw new Error(`More than one phone is connected (${devices.map((d) => d.serial).join(", ")}). Pass --serial.`);
  }
  return needReady(devices[0]);
}

function needReady(d) {
  if (d.state === "unauthorized") throw new Error(`The phone ${d.serial} has not accepted this computer. Unlock it and tap Allow on the USB debugging prompt.`);
  if (d.state !== "device") throw new Error(`The phone ${d.serial} is not ready (state: ${d.state}).`);
  return d;
}

export const checks = {
  smsGranted: (dumpsysPackage) => /android\.permission\.RECEIVE_SMS: granted=true/.test(dumpsysPackage),
  serviceRunning: (dumpsysServices) => dumpsysServices.includes("MirrorService"),
  batteryExempt: (whitelist) => whitelist.split(/\r?\n/).some((l) => l.includes(PACKAGE)),
};

// ---------------------------------------------------------------------------------------------
// I/O (replaced by a fake in tests)

export function defaultIo() {
  return {
    log: (...a) => console.log(...a),
    env: process.env,
    run: (file, args) =>
      new Promise((res) => {
        execFile(file, args, { maxBuffer: 16 * 1024 * 1024, windowsHide: true }, (error, stdout, stderr) => {
          res({ code: error ? (typeof error.code === "number" ? error.code : 1) : 0, stdout: String(stdout), stderr: String(stderr), spawnError: error?.code === "ENOENT" });
        });
      }),
    fetchJson: async (url) => {
      const r = await fetch(url, { headers: { Accept: "application/vnd.github+json", "User-Agent": "pesacast-setup" } });
      if (!r.ok) throw new Error(`${url} answered HTTP ${r.status}`);
      return r.json();
    },
    download: async (url, dest) => {
      const r = await fetch(url, { headers: { "User-Agent": "pesacast-setup" } });
      if (!r.ok) throw new Error(`${url} answered HTTP ${r.status}`);
      await writeFile(dest, Buffer.from(await r.arrayBuffer()));
    },
    readText: (p) => readFile(p, "utf8"),
    sha256File: (p) =>
      new Promise((ok, fail) => {
        const h = createHash("sha256");
        createReadStream(p).on("data", (c) => h.update(c)).on("error", fail).on("end", () => ok(h.digest("hex")));
      }),
    makeTempDir: () => mkdtemp(join(tmpdir(), "pesacast-")),
    removeDir: (p) => rm(p, { recursive: true, force: true }),
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  };
}

// ---------------------------------------------------------------------------------------------
// The run

export async function main(argv, io = defaultIo()) {
  let opts;
  try {
    opts = parseArgs(argv, io.env);
  } catch (e) {
    if (e instanceof UsageError) {
      io.log(`Error: ${e.message}\n\n${HELP}`);
      return 2;
    }
    throw e;
  }
  if (opts.help) {
    io.log(HELP);
    return 0;
  }

  const dry = opts.dryRun;
  const say = (m) => io.log(dry ? `[dry run] ${m}` : m);
  let tmp = null;

  try {
    // --- 1. adb and the phone
    const adbPath = opts.adb ?? (io.env.ANDROID_HOME ? join(io.env.ANDROID_HOME, "platform-tools", "adb") : "adb");
    const adb = async (serialArgs, label) => {
      const full = [...serialArgs];
      if (dry) {
        io.log(`[dry run] ${formatCommand("adb", full)}`);
        return { code: 0, stdout: "", stderr: "" };
      }
      const r = await io.run(adbPath, full);
      if (r.spawnError) throw new Error("adb was not found. Install Android platform-tools (https://developer.android.com/tools/releases/platform-tools) or pass --adb.");
      if (label && r.code === 0) io.log(`  ${label}`);
      return r;
    };

    let serial = opts.serial ?? "<serial>";
    let device = { model: "<model>", release: "<android>", sdk: 34, bluetooth: true };

    if (!dry) {
      const listed = await adb(["devices"], null);
      serial = pickDevice(parseDevices(listed.stdout), opts.serial).serial;
      const prop = async (name) => (await adb(["-s", serial, "shell", "getprop", name], null)).stdout.trim();
      const features = (await adb(["-s", serial, "shell", "pm", "list", "features"], null)).stdout;
      device = {
        model: `${await prop("ro.product.manufacturer")} ${await prop("ro.product.model")}`.trim(),
        release: await prop("ro.build.version.release"),
        sdk: Number.parseInt(await prop("ro.build.version.sdk"), 10) || 0,
        bluetooth: /android\.hardware\.bluetooth\b/.test(features),
      };
      io.log(`Phone: ${device.model}, Android ${device.release} (API ${device.sdk}), serial ${serial}`);
    } else {
      io.log("[dry run] adb devices   (then: pick the phone, read model and Android version)");
    }
    const target = (...rest) => ["-s", serial, ...rest];

    // --- uninstall path
    if (opts.uninstall) {
      if (!opts.yes && !dry) {
        io.log("This removes PesaCast and its saved settings from the phone. Run again with --yes to confirm.");
        return 2;
      }
      await adb(target("shell", "dumpsys", "deviceidle", "whitelist", `-${PACKAGE}`), "battery exemption removed");
      await adb(target("shell", "cmd", "appops", "reset", PACKAGE), "app permissions reset");
      const r = await adb(target("uninstall", PACKAGE), "uninstalled");
      if (!dry && !/Success/.test(r.stdout)) {
        io.log(`Uninstall did not report success: ${(r.stdout || r.stderr).trim()}`);
        return 1;
      }
      say("Done.");
      return 0;
    }

    // --- 2. get and verify the APK
    let apkPath = opts.apk ? resolve(opts.apk) : null;
    let version = opts.version;
    let expected = opts.sha256?.toLowerCase() ?? null;

    if (!apkPath) {
      if (dry) {
        io.log(`[dry run] download pesacast-${version === "latest" ? "<latest>" : version}.apk and its .sha256 from github.com/${REPO}/releases`);
        io.log("[dry run] verify the SHA-256 and stop if it does not match");
      } else {
        if (version === "latest") {
          const rel = await io.fetchJson(`https://api.github.com/repos/${REPO}/releases/latest`);
          version = String(rel.tag_name).replace(/^pesacast@/, "");
          if (!SEMVER.test(version)) throw new Error(`Could not work out the latest version from tag "${rel.tag_name}".`);
        }
        tmp = await io.makeTempDir();
        const base = `https://github.com/${REPO}/releases/download/pesacast@${version}`;
        apkPath = join(tmp, `pesacast-${version}.apk`);
        io.log(`Downloading PesaCast ${version}…`);
        await io.download(`${base}/pesacast-${version}.apk`, apkPath);
        if (!expected) {
          const sumPath = join(tmp, `pesacast-${version}.apk.sha256`);
          await io.download(`${base}/pesacast-${version}.apk.sha256`, sumPath);
          expected = parseChecksumFile(await io.readText(sumPath));
        }
      }
    } else if (!dry && !expected) {
      try {
        expected = parseChecksumFile(await io.readText(`${apkPath}.sha256`));
      } catch {
        expected = null;
      }
    }

    if (!dry) {
      if (!expected) {
        throw new Error("No checksum to verify the APK against. Put pesacast-<version>.apk.sha256 next to it, or pass --sha256.");
      }
      const actual = (await io.sha256File(apkPath)).toLowerCase();
      if (actual !== expected) {
        throw new Error(`The APK's SHA-256 does not match.\n  expected ${expected}\n  actual   ${actual}\nNot installing it.`);
      }
      io.log("Checksum OK.");
    } else if (opts.apk) {
      io.log("[dry run] verify the SHA-256 of the local APK against its .sha256 file (or --sha256)");
    }

    // --- 3. install and grant
    const warnings = [];
    const apkArg = apkPath ?? "<apk>";
    const inst = await adb(target("install", "-r", apkArg), "installed");
    if (!dry && !/Success/.test(inst.stdout)) {
      // adb prints progress lines on stdout and the actual reason (INSTALL_FAILED_...) on stderr.
      throw new Error(`Install failed: ${[inst.stderr, inst.stdout].map((t) => t.trim()).filter(Boolean).join(" | ")}`);
    }

    const grant = async (perm, label, requiredMsg) => {
      const r = await adb(target("shell", "pm", "grant", PACKAGE, perm), null);
      if (!dry && r.code !== 0) warnings.push(requiredMsg ?? `${label} could not be granted from the computer; allow it on the phone.`);
      else if (!dry) io.log(`  ${label} granted`);
    };
    await grant("android.permission.RECEIVE_SMS", "SMS permission", "The SMS permission could not be granted from the computer. Open the setup checklist on the phone and allow it (Android 13+ may need App info, three dots, Allow restricted settings).");
    if (dry || device.sdk >= 33) await grant("android.permission.POST_NOTIFICATIONS", "Notification permission");
    if (dry || (device.sdk >= 31 && device.bluetooth)) {
      await grant("android.permission.BLUETOOTH_CONNECT", "Bluetooth connect permission");
      await grant("android.permission.BLUETOOTH_ADVERTISE", "Bluetooth advertise permission");
    }

    // --- 4. keep it alive in the background (best effort; failures are reported, not fatal)
    const soft = async (args, label) => {
      const r = await adb(target("shell", ...args), null);
      if (!dry) {
        if (r.code === 0) io.log(`  ${label}`);
        else warnings.push(`${label} could not be set from the computer.`);
      }
    };
    await soft(["dumpsys", "deviceidle", "whitelist", `+${PACKAGE}`], "excluded from battery optimisation");
    await soft(["cmd", "appops", "set", PACKAGE, "RUN_ANY_IN_BACKGROUND", "allow"], "background running allowed");
    await soft(["cmd", "appops", "set", PACKAGE, "ACCESS_RESTRICTED_SETTINGS", "allow"], "restricted settings allowed (not supported on every Android version)");

    // --- 5. open the app (starts the service), then offer the webhook settings
    await adb(target("shell", "am", "start", "-n", `${PACKAGE}/.ui.MainActivity`), "app opened");
    if (!dry) await io.sleep(2500);

    if (opts.webhookUrl) {
      const args = target("shell", "am", "start", "-n", `${PACKAGE}/.setup.ProvisionActivity`, "--es", "webhook_url", shQuote(opts.webhookUrl.trim()));
      if (opts.webhookToken) args.push("--es", "webhook_token", shQuote(opts.webhookToken));
      if (opts.webhookSecret) args.push("--es", "webhook_secret", shQuote(opts.webhookSecret));
      const r = await adb(args, null);
      if (!dry) {
        if (/Error|Exception/i.test(r.stdout + r.stderr)) {
          warnings.push("This version of PesaCast has no webhook provisioning screen. Update to a newer release, or enter the webhook on the phone.");
        } else {
          io.log("  webhook settings sent: check the host shown on the phone and tap Save and test");
        }
      }
    }

    // --- 6. verify
    if (dry) {
      io.log("[dry run] check: SMS permission granted, service running, battery exemption present");
      io.log("[dry run] Nothing was changed.");
      return 0;
    }
    const pkgInfo = (await adb(target("shell", "dumpsys", "package", PACKAGE), null)).stdout;
    const services = (await adb(target("shell", "dumpsys", "activity", "services", PACKAGE), null)).stdout;
    const idle = (await adb(target("shell", "dumpsys", "deviceidle", "whitelist"), null)).stdout;
    const results = [
      ["SMS permission granted", checks.smsGranted(pkgInfo), true],
      ["Background service running", checks.serviceRunning(services), true],
      ["Excluded from battery optimisation", checks.batteryExempt(idle), false],
    ];
    io.log("\nChecks:");
    for (const [name, ok, required] of results) io.log(`  ${ok ? "ok     " : required ? "MISSING" : "not set"}  ${name}`);
    for (const w of warnings) io.log(`\nNote: ${w}`);

    const ready = results.filter((r) => r[2]).every((r) => r[1]);
    io.log("\nStill to do on the phone (no computer can do these):");
    io.log("  - Open PesaCast, then Connect, then Setup checklist, and work down it.");
    io.log("  - Confirm your phone maker's auto-start / background menu if the checklist shows one.");
    io.log("  - Run the checklist's end-to-end test.");
    io.log(ready ? "\nAll required checks passed." : "\nNot ready yet: fix the items marked MISSING, then run this again.");
    return ready ? 0 : 1;
  } catch (e) {
    io.log(`\nError: ${e.message}`);
    return 1;
  } finally {
    if (tmp && !opts.keepDownload) await io.removeDir(tmp);
    else if (tmp) io.log(`Download kept in ${tmp}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then((code) => process.exit(code));
}
