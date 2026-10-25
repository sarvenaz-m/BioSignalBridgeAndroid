# Changes

## Version 1 · Browser accessibility correction · 25 October 2026

- Give the full-recording overview an image role so its accessible name is exposed correctly.
- Identify the window measurements and packet-processing history as labelled groups.
- Rebuild both self-contained companion files from the corrected source.
- Complete all 38 Chromium checks, including 16 accessibility and layout states, and rerun the 42 JavaScript and 16 Python tests.
- Retain the explicit Android SDK setup and Kotlin language attributes from the preceding correction.

The application remains at 1.1.0. Native source and the preview APK are unchanged.

## Version 1 · CI correction · 25 October 2026

- Set up Android command-line tools explicitly before building, with SDK Platform 36 and Build Tools 36.0.0. The workflow verifies the tool path and installed packages before starting Gradle.
- Pin the Android setup action and command-line tools version instead of relying on the runner image's preinstalled SDK.
- Focus repository language totals on the native Kotlin implementation while retaining consistent source line endings.

This correction keeps the application version at 1.1.0. It changes CI setup and repository attributes; the Android application and offline companion are unchanged.

## 1.1.0 · 17 October 2026

- Introduced `SignalTransport` and an injected application coroutine scope. Application tests publish frames through that contract.
- Added constant-memory calibrated-value and timing inspection in Kotlin and JavaScript, including gap-aware rates, population moments and ADC-limit counts.
- Added window details to Android and the browser, plus whole-recording inspection in portable metadata.
- Split Compose presentation into focused screens and shared components.
- Reworked the visual system around midnight blue, iris, porcelain, ice and copper.
- Added a recording overview, session search, sorting, renaming, waveform previews and keyboard controls to the companion.
- Separated browser persistence from presentation and made stale connections visible.
- Kept coroutine cancellation separate from recording failures and fixed ZIP entry times.
- Rewrote the project overview, architecture and confidentiality notes.

## 1.0.0 · 15 October 2026

Initial Android capture and review workspace, public ECG preview, portable export, offline companion and Python recording reader.
