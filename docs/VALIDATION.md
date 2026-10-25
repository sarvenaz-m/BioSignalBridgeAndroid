# Validation record

Release **1.1.0 · Version 1**. The native baseline was checked on **17 October 2026**; the companion accessibility correction was checked on **25 October 2026**.

| Area | Result | Coverage |
| --- | --- | --- |
| Kotlin signal core | 30 passed | Packet frames, sequence and clock rollover, gaps, exports and numerical window inspection |
| Room storage | 16 passed | Transaction rollback, limits, per-channel timing, recovery and database reopen |
| Application and native interface | 14 passed | Recording lifecycle, explicit transport scenarios, Compose journeys, notes and actual view captures |
| JavaScript model and DOM journeys | 42 passed | Packet parity, calibrated moments, timing, offline startup, navigation, local recording, rename/search/review/delete and keyboard controls |
| Python reader | 16 passed | Portable fields, scales, gaps, interleaved channels, numerical summaries and native export fixtures |
| Chromium companion journeys | 38 checks passed | Playback, markers, packet scenarios, real IndexedDB persistence, exports, review, deletion, unavailable storage and offline startup |
| Browser accessibility and layout | 16 states passed | All four destinations at 320, 390, 768 and 1440 px; no axe WCAG 2.1 A/AA violations or horizontal overflow in the checked states |
| Android lint | 0 errors; 0 warnings | The included Android project |
| Static text contrast | 6 pairs passed | Selected theme text pairs; not a full accessibility audit |

The native run used Gradle 8.13 and JDK 17. Android component tests used Robolectric at API 35. The minified preview APK was built from this release. It uses a development signing certificate. Machine-readable test and lint summaries are in `verification/`.

## Data and interoperability

The signal core writes its public-preview CSV/metadata fixture with the same portable exporter as the application. The application-engine suite produces a preview archive through the recording path. These contain 3,000 public ECG samples, 4 ms sample spacing and the recorded scale of 0.005 mV per count.

Inspection tests cover empty windows, known moments, marked gaps, interleaved channels, repeated timestamps, negative scale and large finite scales. Rates describe the recorded timebase; transport losses are not converted into an exact missing-sample count.

## What the interface checks establish

The Android images in this release are actual Compose views rendered by Robolectric's native graphics. Their source is stated on the overview image.

The JavaScript interaction checks execute the built, self-contained HTML against LinkeDOM and Fake IndexedDB. They exercise behaviour and persistence; they do not render CSS.

The complete Playwright suite was run for the companion correction on 25 October 2026 with Chromium 134.0.6998.35 and Playwright 1.51.1. All 38 checks passed. Axe reported no WCAG 2.1 A/AA violations in the 16 tested page and viewport states, and the layout checks found no horizontal overflow. Playback, packet scenarios, markers, actual IndexedDB storage, CSV/JSON exports, deletion, unavailable storage and offline file startup all passed. The browser reported no page errors or external runtime requests. These automated checks do not establish complete accessibility conformance.

The initial `aria-prohibited-attr` failure on `.overview-track` was reproduced before the correction. The overview now exposes an image role with its accessible name. Window measurements and packet-processing results have labelled group roles. No accessibility rule was disabled. Current results are in `verification/browser-tests.json`; the 1.0.0 audit remains under `verification/1.0.0` as historical evidence.

## Device work still needed

Physical BLE acquisition, sustained capture, reconnect, screen lock, timing and calibration require a compatible phone and peripheral. TalkBack and large-text use also need a device review. The observation sheet is `DEVICE-TEST-RECORD.md`.

This project supports signal engineering and recording review. No diagnostic interpretation or hardware-validation claim is made.

## Repeating the checks

```sh
./gradlew :core:signal:test :core:storage:testDebugUnitTest :app:testDebugUnitTest :app:assemblePreview :app:lintDebug
python3 -m unittest discover -s analysis -p 'test_*.py'
npm test
npm run test:browser
```

The included continuous integration workflow runs these gates. Historical results are kept in their version directory.

## CI correction · 25 October 2026

The Android workflow now sets up the SDK immediately after JDK 17, before Gradle caching. It pins `android-actions/setup-android` to commit `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd`, selects command-line tools 16.0 and installs SDK Platform 36, Build Tools 36.0.0 and platform-tools. A separate step verifies `sdkmanager`, the platform JAR and `aapt2` before the build starts.

The exact setup action was exercised locally with an empty SDK directory. Installation completed successfully, the action's exported PATH resolved `sdkmanager`, and the package checks passed. Actionlint 1.7.12 reported no workflow errors, and every embedded shell step passed Bash syntax validation.

The 42 JavaScript model and DOM tests and 16 Python recording tests were rerun successfully. Both self-contained HTML copies were rebuilt from the corrected companion source and match each other. Git attribute checks confirmed that 40 Kotlin source and build-script files remain in the programming-language totals; the browser companion, Python helpers, public-data preparation and automation are excluded.

The repository's hosted workflow run [40172509225](https://github.com/sarvenaz-m/BioSignalBridgeAndroid/actions/runs/40172509225) completed SDK setup, Android tests/build/lint, Python interoperability, JavaScript tests and the demo build before reaching the reported browser accessibility failure. The companion correction then passed the complete browser suite locally.

Native source and application version 1.1.0 are unchanged. Native compilation, native tests and Android lint were not repeated locally for this browser correction. The supplied APK is the previously validated build. A complete hosted workflow run with the final corrected companion is still pending. The scope and results are recorded in `verification/ci-checks.json`.
