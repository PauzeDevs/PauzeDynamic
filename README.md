<div align="center">

# PauzeX Dynamic Island

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=22&duration=2800&pause=900&color=8B9CFF&center=true&vCenter=true&width=650&lines=Your+Android.+More+alive.;Live+activities+in+a+floating+pill.;Built+by+PauzeX+Developments." alt="Animated introduction" />

<p><strong>A Dynamic Island-inspired overlay for Android.</strong><br />
Media • Notifications • Battery • Progress • Customisation</p>

<p>
  <a href="https://github.com/PauzeDevs/PauzeDynamic/actions/workflows/android.yml"><img src="https://github.com/PauzeDevs/PauzeDynamic/actions/workflows/android.yml/badge.svg" alt="Android CI" /></a>
  <a href="https://github.com/PauzeDevs/PauzeDynamic/stargazers"><img src="https://img.shields.io/github/stars/PauzeDevs/PauzeDynamic?style=flat&color=8B9CFF" alt="GitHub stars" /></a>
  <a href="https://github.com/PauzeDevs/PauzeDynamic/issues"><img src="https://img.shields.io/github/issues/PauzeDevs/PauzeDynamic?style=flat&color=70E1C1" alt="Open issues" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-70E1C1.svg?style=flat" alt="Apache License 2.0" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin" />
</p>

**[Source code](https://github.com/PauzeDevs/PauzeDynamic) · [Builds & APK artifacts](https://github.com/PauzeDevs/PauzeDynamic/actions) · [Report a bug](https://github.com/PauzeDevs/PauzeDynamic/issues/new)**

</div>

---

## ✨ What is PauzeX Dynamic Island?

PauzeX Dynamic Island brings a compact, expandable live-activity pill to Android. It stays above other apps and surfaces useful information without requiring you to switch screens.

> **Project status:** Early prototype. CI builds the app, but behaviour has not yet been verified across physical devices. Do not treat this as a stable release.

## 🚀 Features

| Feature | Details |
| --- | --- |
| **Floating pill** | Dark, glass-inspired appearance with expandable content |
| **Motion** | Expand/collapse feedback animation |
| **Move it** | Drag the overlay to reposition it |
| **Appearance** | Choose an accent colour and adjust compact pill width |
| **Media** | Display available track metadata and send play/pause commands |
| **Notifications** | Preview the latest supported notification |
| **Progress** | Show progress when a notification provides determinate progress values |
| **Battery** | Show battery percentage and charging state |
| **Quick timer** | Start or cancel a one-minute timer |
| **Module toggles** | Enable or disable media, notification, battery and progress indicators |

<div align="center">

<img src="https://img.shields.io/badge/DESIGN-DARK%20GLASS-10111A?style=for-the-badge&labelColor=08090E" alt="Dark glass design" />
<img src="https://img.shields.io/badge/BUILT%20WITH-KOTLIN-7F52FF?style=for-the-badge&labelColor=08090E" alt="Built with Kotlin" />

</div>

## 📲 Installation

### Option 1 — Download a CI build

1. Open **[GitHub Actions](https://github.com/PauzeDevs/PauzeDynamic/actions)**.
2. Select the latest successful **Android CI** run on `main`.
3. Scroll to **Artifacts** and download `PauzeX-Dynamic-Island-debug`.
4. Extract the ZIP and install `app-debug.apk` on your Android device.

> Debug builds are development builds. Only download artifacts from this repository, and remember that an APK may need permission to install apps from your chosen file manager or browser.

### Option 2 — Build from source

**Requirements:** Android Studio, JDK 17, Android SDK 35 and internet access for Gradle dependencies.

```bash
git clone https://github.com/PauzeDevs/PauzeDynamic.git
cd PauzeDynamic
gradle assembleDebug
```

The APK will be generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## ⚙️ First-time setup

1. Install and open **PauzeX Dynamic Island**.
2. Tap **Allow display over other apps** and grant the permission in Android Settings.
3. Enable **Notification access** if you want notification previews and media-session discovery.
4. Return to the app and tap **Start Dynamic Island**.
5. Tap the pill to expand it. Drag the pill to reposition it.
6. Open the Appearance and Live Activity Modules settings to adjust the width, accent colour and visible modules.

Some manufacturers apply additional background-service restrictions. If the pill disappears, check the app's battery/background settings and start it again.

## 🧱 Tech stack

- **Kotlin** — native Android implementation
- **Android SDK 35** — target/build platform
- **Gradle** — build automation
- **GitHub Actions** — automated debug APK builds

## 🗺️ Roadmap

- [x] Initial native Android overlay
- [x] Expandable pill and basic motion feedback
- [x] Width and accent-colour customisation
- [x] Toggles for available activity modules
- [x] CI workflow for debug APK artifacts
- [ ] More robust live media-session switching
- [ ] Album-art presentation and richer media layouts
- [ ] Call-state indicators and device-specific permission handling
- [ ] Physical-device testing and stability pass
- [ ] Signed release build after validation

## ⚠️ Known limitations

- This is a prototype, **not a stable or device-certified release**.
- Media-session discovery currently happens when the service starts; switching players may require restarting the overlay.
- Media metadata, notification content and progress indicators depend on what other apps expose to Android.
- Call-state indicators are not implemented.
- Battery optimisation and background-service rules differ by Android version and manufacturer.
- The CI workflow confirms that the project compiles; it does not replace real-device testing.

## 🐛 Bugs and contributions

Found a bug or have an idea?

- **[Open an issue](https://github.com/PauzeDevs/PauzeDynamic/issues/new)** with your Android version, device model and steps to reproduce.
- For build failures, include the relevant GitHub Actions run link and error log.
- Pull requests should focus on one change at a time and include testing notes.

## 📄 License

Released under the **[Apache License, Version 2.0](LICENSE)** ([SPDX: Apache-2.0](https://spdx.org/licenses/Apache-2.0.html)).

You may use, modify, and redistribute this project, including commercially, subject to the license terms. Redistributions must include a copy of the license and preserve applicable copyright, patent, trademark, and attribution notices; modified files must clearly indicate that they were changed. Apache-2.0 also includes an express patent license from contributors, subject to its terms. The software is provided **without warranties or conditions**, as described in the license.

See [NOTICE](NOTICE) for project attribution. Third-party dependencies and assets, if any, remain subject to their respective licenses.

<div align="center">

**Made by PauzeX Developments**

<sub>Android, with a little more life.</sub>

---

### 🛠️ Development note

<sub>Approximately 90% human-coded, with AI assistance used to identify, troubleshoot, and fix errors or bugs found during development. Changes should still be reviewed and tested; automated fixes do not guarantee that every issue has been found.</sub>

</div>
