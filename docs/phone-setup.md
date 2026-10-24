# Setting up a phone

How to get PesaCast running on the phone that receives the M-PESA messages, when it is installed from a file instead of an app store. Design notes: [ADR-0015](adr/0015-setup-checklist-and-provisioning.md). A single-command script that does all of this is planned; until then these are the steps it will run.

## 1. Get the app and check it

Download `pesacast-<version>.apk` and `pesacast-<version>.apk.sha256` from the [releases page](https://github.com/davidamunga/pesacast/releases) into one folder, then check the file is the one that was published:

```bash
shasum -a 256 -c pesacast-0.9.0.apk.sha256
```

(On Linux, `sha256sum -c` does the same.) It must print `OK`. Do not install a file that fails the check or has no checksum.

## 2. Install and grant what you can from the computer

On the phone, turn on **Developer options** (Settings, About phone, tap Build number 7 times) and **USB debugging**, plug it in, and accept the prompt. Then:

```bash
adb install -r pesacast-0.9.0.apk
```

```bash
adb shell pm grant com.pesacast.android android.permission.RECEIVE_SMS
```

`adb` installs skip the "install unknown apps" toggle. Play Protect may still ask about an app it has not seen; do not turn Play Protect off. On Android 13 and newer, an app installed this way can be blocked from the SMS permission until you allow "restricted settings" in its App info (the in-app setup checklist walks through it).

## 3. Send the webhook settings from the computer (optional)

Typing a URL, token and secret on a small keypad is slow and error-prone. Start the provisioning screen with them instead:

```bash
adb shell am start -n com.pesacast.android/.setup.ProvisionActivity --es webhook_url 'https://example.com/pesacast' --es webhook_token 'YOUR_TOKEN' --es webhook_secret 'YOUR_SECRET'
```

`webhook_token` and `webhook_secret` are optional. The phone then shows a screen with the **destination host in large letters** and asks you to tap **Save and test**. Nothing is saved or sent before that tap, and the webhook is only switched on if the test succeeds.

Rules the phone enforces, and why:

| Rule | Why |
|---|---|
| Only `https://` URLs; host names with non-ASCII characters are refused | A look-alike domain or plain HTTP would send payment data somewhere you did not intend |
| The tap is required, and the host is shown | The screen can be started by other apps too, so it must never apply a change silently |
| Token must be plain printable ASCII | The token goes into an HTTP header; a newline in it could inject headers |
| The webhook stays off until a test succeeds | A phone must not forward payments to an unverified server |
| No link or web page can open the screen | It has no intent filter; only an explicit `adb` or app start reaches it |

The token and secret appear in your shell history and in the `adb` process list on the computer while the command runs. Use a token made for this phone, and rotate it if the computer is shared.

## 4. Finish on the phone

Open PesaCast, then **Connect, Setup checklist**. It shows what is still missing (battery optimisation, your phone maker's auto-start menu, a destination) and has an **end-to-end test** that sends a labelled test through everything that is on.

## Verifying a download later

Keep the `.sha256` file with the APK. If the APK is ever re-downloaded or copied, run the check in step 1 again.
