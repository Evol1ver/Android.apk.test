# Build APK With No Installs (Browser Only)

If you cannot install Android Studio or any tools locally, use GitHub Actions to build the APK in the cloud.

## What you need

- A GitHub account
- A web browser

## One-time setup

1. Create a new repository on GitHub (for example: `price-monitor-apk`).
2. Open your new repo in browser.
3. Click `Add file` -> `Upload files`.
4. Drag and drop ALL contents of this folder (`android-price-monitor-windows`) into the upload page.
5. Commit the upload.

Important: keep folder structure exactly as-is, including:

- `.github/workflows/build-apk.yml`
- `app/...`
- `build.gradle.kts`
- `settings.gradle.kts`

## Build APK in GitHub

Automatic mode is enabled.

1. Push any change to branch `main` or `master`.
2. GitHub Actions builds APK automatically.
3. Workflow updates release tag `latest-apk` automatically.

Manual fallback is still possible from `Actions` -> `Run workflow`.

## Download APK

Preferred (automatic):

1. Open repo `Releases` page.
2. Open release `Latest APK (Auto)` (tag `latest-apk`).
3. Download file `PriceMonitor-debug.apk`.

Alternative:

1. Open successful workflow run.
2. Download artifact `app-debug-apk`.

## Install on phone

1. Send `app-debug.apk` to your Android phone (email, cloud drive, cable, etc.).
2. Open APK on phone.
3. Allow installation from unknown sources when prompted.
4. Install.

## If workflow fails

- Open the failed run log and copy the first error section.
- Share it and I can fix the workflow quickly.
