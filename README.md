# Save to Downloads

Android 10+ share target that copies files straight to Downloads.

With Bash, curl, unzip, and zip installed, run `mise exec java yq -- mise install android-sdk`, then `mise run build`. Install `build/save-to-downloads.apk` and pick **Save to Downloads** from the share sheet.

For an emulator, run `mise run emulator:setup` once, then `mise run emulator`.
