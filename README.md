# PauzeX Dynamic Island

Native Android Dynamic Island-style floating overlay by PauzeX Developments.

## Current features

- Dark glass-inspired pill with animated expand/collapse feedback.
- Drag-to-reposition overlay.
- Configurable compact pill width.
- Accent colour presets and switches for media, notification, battery and progress modules.
- Media metadata and play/pause controls when Android exposes an active media session.
- Notification previews and progress values when supported by the source app.
- Battery percentage, charging status and a one-minute timer.
- Android setup screen for overlay and notification-access permissions.
- GitHub Actions CI builds a debug APK artifact on each push to `main`.

## Build

Use Android Studio, JDK 17 and Android SDK 35. Sync Gradle and run:

```sh
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Setup

1. Install the debug APK.
2. Open PauzeX Dynamic Island.
3. Grant **Display over other apps**.
4. Grant **Notification access** if you want notification previews and media-session discovery.
5. Tap **Start Dynamic Island**.
6. Tap the pill to expand it; drag it to reposition it.

## Important limitations

This is an actively developed prototype, not a device-verified stable release. Android OEM background limits, media-session access and notification formats vary by device/app. The current media-session discovery happens when the service starts, so changing playback apps may require restarting the overlay. Call-state indicators are not implemented yet. The build pipeline proves compilation only; test on a physical device before relying on it.

Package: `dev.pauzex.dynamicisland`

## License

MIT (see [LICENSE](LICENSE)).
