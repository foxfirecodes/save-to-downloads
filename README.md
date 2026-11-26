# Save to Downloads

Android 10+ share target that copies files straight to Downloads.

With Bash, curl, unzip, and zip installed, run `mise exec java yq -- mise install android-sdk`, then `mise run build`. Install `build/save-to-downloads.apk` and pick **Save to Downloads** from the share sheet.

For an emulator, run `mise run emulator:setup` once, then `mise run emulator`.

To publish an APK, run **Release APK** from `main` in GitHub Actions and enter a new tag such as `v1.0.0`.
