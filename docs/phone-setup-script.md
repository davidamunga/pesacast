# Phone setup script

`scripts/setup-phone.mjs` runs the manual steps from [phone-setup.md](phone-setup.md) for you: it downloads the release APK, verifies its SHA-256, installs it over USB with `adb`, grants what a computer can grant, excludes the app from battery saving, optionally sends the webhook settings to the phone's confirmation screen, and checks the result. Design: [ADR-0015](adr/0015-setup-checklist-and-provisioning.md).

**Needs:** Node 20 or newer and `adb` (Android platform-tools) on the computer; on the phone, Developer options with USB debugging on. No packages to install. Works on macOS, Linux and Windows.

## See what it would do first

```bash
node scripts/setup-phone.mjs --dry-run
```

A dry run prints every step and command, with webhook credentials hidden, and contacts nothing: no download, no `adb`, no changes.

## Run it

```bash
node scripts/setup-phone.mjs
```

With the webhook settings (token and secret go in environment variables so they do not appear in the process list):

```bash
PESACAST_WEBHOOK_TOKEN='...' PESACAST_WEBHOOK_SECRET='...' node scripts/setup-phone.mjs --webhook-url https://example.com/pesacast
```

The phone then shows the destination host and waits for you to tap **Save and test**. Nothing is applied before that.

| Option | Meaning |
|---|---|
| `--version 0.9.0` | A specific release (default: latest) |
| `--apk file.apk` | A local APK; needs `file.apk.sha256` beside it, or `--sha256 <hex>` |
| `--serial <id>` | Which phone, if several are connected |
| `--adb <path>` | Where `adb` is, if it is not on `PATH` |
| `--keep-download` | Keep the downloaded APK |
| `--uninstall --yes` | Remove PesaCast and undo the settings the script changed |

## What it does, in order

1. Finds exactly one connected, authorised phone and reads its model and Android version.
2. Downloads `pesacast-<version>.apk` and `.apk.sha256` from the GitHub release, and **stops if the checksum does not match or is missing**. An APK is never installed unverified.
3. `adb install -r`.
4. Grants SMS, and notifications (Android 13+) and Bluetooth (Android 12+ with Bluetooth hardware) so the phone shows no permission dialogs.
5. Excludes the app from battery optimisation and allows background running. Tries the "restricted settings" switch too; not every Android version supports it, and failure is reported, not fatal.
6. Opens the app, which starts the background service, then sends the webhook settings if you gave a URL.
7. Checks: SMS permission granted, service running, battery exemption present. Exit code 0 only if the required checks pass.

## What it cannot do

It lists these at the end, because no computer can do them:

- Allow "restricted settings" in App info on Android 13+ if the SMS permission could not be granted from `adb`.
- Change your phone maker's own auto-start or background menu (Tecno, Infinix, itel, Xiaomi, Oppo, vivo, Samsung, Huawei).
- Run the in-app end-to-end test.

Open **Connect, Setup checklist** on the phone to finish.

## Safety

- The same webhook rules the phone enforces are checked first, so a bad request fails before anything is sent: `https` only, no non-ASCII host, no credentials in the URL, a token of printable ASCII.
- Values are quoted for the phone's shell, so characters like `'`, `&` or `;` in a token cannot run commands on it.
- Credentials are never printed. They do appear briefly in the `adb` command line on the computer, so use a token made for this phone.
- The script does not disable Play Protect, does not pretend to be a store install, and asks for no extra permissions.

## Tests

```bash
node --test scripts/
```

These use a fake `adb` and fake downloads, and never touch a real phone or the network. They cover the dry run, the checksum rules, device selection, the order of the steps, permission gating by Android version, shell quoting, credential hiding, and the uninstall path.
