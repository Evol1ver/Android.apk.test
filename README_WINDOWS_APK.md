# Windows APK Build (No Linux/Ubuntu Needed)

This project is a native Android Studio project that builds APK directly on Windows.

## Folder

- `android-price-monitor-windows`

## Install on Windows

1. Install **Android Studio** (latest stable).
2. During first start, install:
- Android SDK Platform 34
- Android SDK Build-Tools
- Android Platform-Tools
- Android Emulator (optional)
- Android SDK Command-line tools
3. In Android Studio, open folder `android-price-monitor-windows`.
4. Let Gradle sync complete.

## Build APK

1. In Android Studio menu:
- `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`
2. When build finishes, click the notification link `locate`.
3. APK path will be similar to:
- `app/build/outputs/apk/debug/app-debug.apk`

## Install APK on phone

### Option A: direct file transfer
1. Copy `app-debug.apk` to your phone.
2. On phone, open APK file and install.
3. If asked, allow install from unknown sources for your file manager.

### Option B: ADB (USB)
1. Enable Developer options + USB debugging on phone.
2. Connect phone with USB.
3. Run in terminal (inside project folder):
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## App behavior included

- Product tracking by SKU
- Refresh from Estonian sources
- Open selected link in browser
- Add/Edit/Remove products
- Too-good-price toggle
- Too-good prices never update all-time lowest
- `INCASSO.111.403` searches only `elux.ee`

## Important

If your corporate network blocks some sites (for example `elux.ee`), the app will still save fallback links but may show `Blocked by network policy`.
