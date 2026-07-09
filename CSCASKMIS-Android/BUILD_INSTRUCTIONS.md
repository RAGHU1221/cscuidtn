# CSC ASK MIS — Android APK Build Guide

WebView wrapper app for **https://cscasktn.page.gd** (CSC Aadhaar Seva Kendra MIS).

## Requirements
- Android Studio (Hedgehog / Iguana or newer) — free download: https://developer.android.com/studio
- Internet connection (first build downloads Gradle + dependencies automatically)

## Build Steps (5 minutes)
1. Open Android Studio → **Open** → select this `CSCASKMIS-Android` folder
2. Wait for Gradle Sync to finish (first time: 5–10 min, downloads dependencies)
3. Menu → **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
4. Click the **locate** link in the notification →
   APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
5. Copy `app-debug.apk` to your phone → install (allow "Install from unknown sources")

## For a signed release APK (optional — needed only for Play Store / clean install prompt)
1. Build → **Generate Signed Bundle / APK** → APK
2. Create new keystore (remember the password! keep the .jks file safe)
3. Choose **release** → Finish
4. Output: `app/build/outputs/apk/release/app-release.apk`

## Change the site URL later
Edit one line in `app/src/main/java/com/cscask/mis/MainActivity.kt`:
```kotlin
const val SITE_URL = "https://cscasktn.page.gd/"
const val HOST = "cscasktn.page.gd"
```

## Features included
- Full WebView with JavaScript + localStorage (dark mode toggle works)
- Cookies enabled (login session works, InfinityFree browser-check passes)
- File **downloads** (Excel/PDF/HTML exports → phone Downloads folder, with notification)
- File **uploads** (CSV import / Excel import pages → phone file picker opens)
- Pull-to-refresh
- Hardware Back button navigates inside the site
- Offline screen with Retry button
- tel:/mailto:/WhatsApp links open in external apps
- Google OAuth (Drive backup) opens in external browser (WebView OAuth is blocked by Google)
- Government theme: navy status bar (#0B1B3A), saffron accent, Aadhaar fingerprint launcher icon

## Notes
- InfinityFree sites show a one-time JS cookie check — WebView handles it automatically.
- App needs internet always (PHP runs on server; APK is a client shell).
