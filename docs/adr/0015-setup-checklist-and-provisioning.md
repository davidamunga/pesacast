# ADR-0015: Guide phone setup from inside the app, and provision it from outside

- **Status:** Accepted (maintainer approved the approach; the provisioning part is built in a follow-up PR)
- **Date:** 2026-10-25

## Context

PesaCast is meant to run on a dedicated phone that only receives M-PESA SMS and forwards them (to a desktop and/or a webhook). People who set those phones up are often not developers, the phones are often low-end with aggressive battery management, and the app is installed outside the Play Store. Setup today means: grant SMS permission, work around Android's "restricted settings" for sideloaded apps (Android 13+), exclude the app from battery optimisation, find a brand-specific "autostart" menu, enter a webhook URL, token and secret on a small keypad, and then wait for a real SMS to find out whether it works. Nothing tells the user what is still missing, and a script run from a computer cannot do the parts that only the phone's own screens control.

## Decision

1. **Put the logic in the app.** A **Setup checklist** screen checks each requirement and shows done, to do, or recommended, with a button that opens the right system screen:
   - SMS permission (and, when it cannot be granted on Android 13+, the "Allow restricted settings" step in App info);
   - notifications (optional);
   - battery optimisation exclusion, via the system list screen (no extra permission is requested for it);
   - a brand-specific hint for the phone-maker's autostart menu, marked "check manually" because Android exposes no way to read that setting;
   - the foreground service running;
   - at least one destination (webhook, WiFi or desktop over Bluetooth);
   - an **end-to-end test** that sends a clearly labelled test through every enabled destination and reports each result.
   The decision logic is a pure Kotlin function over a small state interface, so it is unit tested on the JVM. The Android side only reads state and opens screens.
2. **Provision from outside through one narrow entry point** (built next, in its own PR): an activity that an `adb` command or another app can start with the webhook settings, which **never applies anything without an on-screen confirmation that shows the destination host**, accepts only `https` URLs, never enables the webhook until a test has succeeded (ADR-0013), clears the incoming extras, and has no browsable intent filter so a web page cannot trigger it.
3. **Keep the `adb` script thin.** It installs the verified APK, grants permissions, sets the battery allowlist, sends the provisioning intent and opens the checklist. Anything that needs judgement or a phone-specific menu stays in the app.
4. **Do not request extra permissions for setup convenience.** In particular, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the list screen needs none), and no spoofing of the installer or disabling of Play Protect.

## Alternatives considered

- **A script only.** Works for an installer with a computer, but leaves every other user with nothing, and cannot reach the brand-specific screens.
- **A desktop app wizard with a bundled `adb`.** The smoothest experience, and the desktop already knows the webhook details, but it ships an extra binary per platform. Left as a later option; the checklist and provisioning entry point are what it would call anyway.
- **Android device-owner provisioning (QR at factory reset).** Powerful for a fleet of identical phones, but wipes the phone and is far heavier than this needs.
- **Request the direct "ignore battery optimisations" dialog.** One tap instead of two, but adds a permission that app stores restrict and that reviewers flag.

## Consequences

- The checklist is the single place that says whether a phone is ready, whether set up by hand or by script, and what to do about each gap. The end-to-end test makes "does it work" answerable without waiting for a real payment SMS.
- Brand-specific menu names change between software versions. The hints are guidance, labelled as such, and wrong hints are a documentation fix, not a code fix.
- The provisioning entry point has to be exported so `adb` can start it, which makes it reachable by other apps. The confirmation screen, the https-only rule and the test-before-enable rule are what stop it being used to redirect a phone's transactions silently; they must not be weakened.
- A test sent to a desktop appears in its feed as a fake received payment (as the existing "Send Test Notification" already does). The webhook gets a `"test": true` ping instead, which carries no transaction.
