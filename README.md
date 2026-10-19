# PauzeX Dynamic Island

Native Android Dynamic Island-style overlay prototype by PauzeX Developments.

## Features
- Expandable floating pill with a dark glass-inspired appearance.
- Media session metadata and play/pause controls where a player exposes an active media session.
- Notification previews via optional Android notification access.
- Battery and charging status.
- One-minute timer control.
- Standard notification download progress when provided by the source app.
- Setup screen for overlay and notification-access permissions.
- GitHub Actions workflow to build and upload a debug APK.

## Build
Use Android Studio, JDK 17 and Android SDK 35. Sync Gradle and run gradle assembleDebug. The APK is at app/build/outputs/apk/debug/app-debug.apk.

## Permissions and limitations
Display-over-other-apps permission is required for the overlay. Notification access is required for previews and media-session discovery. Android versions, OEM background restrictions, app notification formats and media-session availability vary. Call-state support is scaffolded but needs explicit permission handling and device testing before being considered enabled. This is an initial implementation, not a device-verified stable release.

Package: dev.pauzex.dynamicisland

## License
MIT (see LICENSE).
