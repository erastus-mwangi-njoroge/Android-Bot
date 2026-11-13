# AcadBot for Android

Android 6.0+ (API 23) version of the desktop bot. A hidden in-app browser (WebView) runs the same
flow: log in -> watch the order list -> skip bid requests / already-seen orders -> open the order -> click "Get Order".
A foreground service + wake lock keeps it running with the screen off.

## Get the APK (GitHub Actions)
1. Create a new GitHub repo and upload **the contents of this folder** (so `.github/` sits at the repo root).
2. Open the **Actions** tab -> *Build Android APK* -> **Run workflow** (it also runs on every push).
3. When it finishes (~5 min), download **AcadBot-apk** from the run's *Artifacts* and unzip `AcadBot.apk`.
   Tip: push a tag like `v1.0` and the APK is attached to a GitHub Release you can open straight from your phone.
4. Copy the APK to the phone, open it, and allow "Install unknown apps" when asked.

## Using it
* Generate your token in the **desktop** app (this app only verifies tokens), paste it with your email and password.
* Tap **Allow running in background** once so the phone doesn't kill the bot.
* The **Browser** tab shows what the bot sees (useful for a captcha or a layout change).

## Order flow (matches desktop)
Open order -> tick "read" + action button -> wait the mandatory 60s lock -> click "Get Order" when clickable ->
wait for the site's page to react -> only then judge "taken" vs "taken by another user".
One order therefore never takes under 60s. Success markers only count if they are new after the click.

## Timing values
All waits are in `app/src/main/java/com/acadbot/app/Timing.kt`; `(orig)` = same as desktop, `(new)` = Android-specific.

## Notes
* Release APK is signed with the auto-generated debug key (fine for sideloading, not for Play Store).
* The WebView uses a desktop Chrome user agent so the site's selectors match the desktop bot.
