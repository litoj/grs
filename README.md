# GPS Read&Spoof

Scan GPS coordinates with your camera and instantly mock your device's location to match.

Point the camera at any screen or display showing coordinates — a trail camera, a weather station, a GPS device — and the app reads them via OCR and sets your phone's mock location to those coordinates. No manual typing required.

## How It Works

1. **Scan** — The camera continuously analyzes frames using ML Kit text recognition. A centered scan-box overlay shows the target area, and only that crop is sent to OCR for speed and accuracy.
2. **Parse** — A forgiving coordinate parser extracts lat/lon pairs from messy OCR text. It handles common OCR confusions (O→0, l→1, Z→2, S→5, B→8, g→9), strips spurious spaces, and supports all three coordinate formats:
   - **Decimal degrees** — `50.123456 N, 14.456789 E`
   - **Degrees & decimal minutes** — `50°10.050' N, 14°30.050' E`
   - **Degrees, minutes, seconds** — `50°10'30.5" N, 14°30'05.0" E`
   - **Auto** — detects the format automatically
3. **Mock** — Parsed coordinates are pushed to Android's `LocationManager` test providers (`GPS_PROVIDER` and `NETWORK_PROVIDER`), overriding your real GPS location for all apps on the device.

Coordinates can also be set from a **still image** instead of the live camera:

- **From your gallery** — Menu → **Load from file** (opens your gallery app), or using the Load button.
- **From another app** — Use the system share sheet (*Share → GPS Read & Spoof*) from Google Photos, Files, a browser, etc.

The whole image is OCRed (no crop box), and coordinates are parsed exactly like camera OCR. If none are found, a dialog shows the image zoomed into the detected text (red boxes) with the recognized text pre-filled — fix OCR mistakes there and tap **Match**.

### Supported coordinate layouts

The parser handles all combinations of direction-letter position (prefix/suffix) and lat/lon order:

| Layout | Example |
|--------|---------|
| Suffix, lat-first | `N 50.123 E 14.456` |
| Suffix, lon-first | `E 14.456 N 50.123` |
| Prefix, lat-first | `50.123 N 14.456 E` |
| Prefix, lon-first | `14.456 E 50.123 N` |

## Features

- **Continuous auto-scan** — automatically detects coordinates as soon as they appear in the scan box. Adaptive debouncing: scans every 250 ms until coordinates are found, then slows to 1500 ms to save battery.
- **Manual scan mode** — toggle off continuous scanning and press **Scan Now** for a single burst scan (100 ms intervals until a result is found).
- **Scan & Mock shortcut** — a launcher long-press shortcut that opens the app and immediately triggers a burst scan.
- **Coordinate validation** — if newly scanned coordinates differ from the current ones by more than 0.5°, the app shows a confirmation prompt instead of silently applying them.
- **Manual editing** — lat/lon fields are editable. Type or paste coordinates in any supported format with or without direction letters.
- **Live mock refresh** — mock location is refreshed every second to ensure it persists for apps that poll GPS independently.
- **OCR preview** — the raw OCR text is shown at the top of the camera preview so you can verify what was read.
- **Load from image** — set coordinates from an existing photo via your gallery app or the system share sheet. No gallery/photos permission is required: the system photo picker and share intents grant read access to just that one image.
- **Edit & match** — failed image scans can be corrected in place: edit the OCR text, and the app re-runs the parser on your edit.
- **Auto-open** — select an app (e.g. Google Maps) to automatically launch when coordinates are mocked. Searchable app picker with icons. The selection persists across app restarts.

## Requirements

- **Android 8 (API 26) or higher**
- Camera access
- Location access
- The app must be set as the **Mock location app** in Developer Options

## Setup

1. Build and install the app on your device.
2. Open **Settings → System → Developer options → Select mock location app** and choose **GPS Read&Spoof**.
3. Launch the app and grant camera and location permissions.
4. Point the camera at a screen displaying GPS coordinates. The app will detect and mock them automatically.

> **Note:** If mocking fails, the app shows a snackbar with a **Settings** action that takes you straight to Developer Options.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **CameraX** — camera preview and image analysis with backpressure strategy and Camera2 interop for disabled post-processing (edge enhancement, noise reduction, etc.) to preserve raw pixel data for OCR.
- **ML Kit Text Recognition** — on-device Latin text recognition (no internet required).
- **LocationManager test providers** — mocks `GPS_PROVIDER` and `NETWORK_PROVIDER`. The fused provider is intentionally left untouched so that apps using `FusedLocationProviderClient` (e.g. via microG) still receive the aggregated mock location.

## Project Structure

```
app/src/main/java/cz/litoj/grs/
├── MainActivity.kt              — Entry point, permissions, intent handling
├── CameraReaderService.kt       — CameraX preview + image analysis, scan modes
├── ImageOcrAssistant.kt         — Decode / EXIF-rotate still images, OCR, extract coordinates
├── TextRecognizer.kt            — ML Kit OCR wrapper
├── GpsCoordinateParser.kt       — OCR normalization, coordinate regex parsing & validation
├── LocationMocker.kt            — Test provider registration & mock location updates
├── GpsSpoofViewModel.kt         — UI state, coordinate state, one-time events
└── ui/
    ├── GrsScreen.kt             — Main screen layout & snackbar event handling
    ├── CameraPreviewSection.kt  — Camera preview, scan-box overlay, scan controls
    ├── CoordinateInputSection.kt— Lat/lon text fields, format selector, mocking lifecycle
    └── theme/                   — Material 3 theme (dynamic color + fallbacks)
```

## Building

```bash
./gradlew assembleDebug
```

For release builds, place a `keystore.properties` file in the project root with your signing credentials. If the file is absent, the release build type falls back to the debug signing config.

## Development

### Testing

Unit tests cover the core parsing logic (`GpsCoordinateParser`) and ViewModel state management (`GpsSpoofViewModel`). Run them with:

```bash
./gradlew test
```

Components that depend on camera hardware, ML Kit, or Android framework services (`CameraReaderService`, `ImageOcrAssistant`, `TextRecognizer`, `LocationMocker`) are not unit-tested. Strategies for testing them:

- **Extract pure functions** — crop math, sample-size computation, and preview-rect union are pure logic currently embedded in camera/OCR classes. Extracting them into standalone functions makes them unit-testable with no Android dependencies.
- **Interface mocking** — wrap `TextRecognizer` behind an interface so tests can inject a fake that returns canned OCR output, enabling end-to-end pipeline tests without ML Kit.
- **Robolectric** — provides a simulated Android environment for JVM tests, enabling tests for `LocationMocker` (via `LocationManager` shadows) and `ImageOcrAssistant` file I/O without an emulator.
- **Instrumented tests** — golden-image tests (known images → known coordinates) and `LocationMocker` integration with a real `LocationManager` on a device. Note that mock-location instrumented tests require the test app to be selected as the mock location app in Developer Options.

### Architecture notes

- The ViewModel is created directly in `MainActivity` and does not survive configuration changes. If state preservation across rotation becomes important, switch to `by viewModels()`.
- `parseFromText` expects pre-normalized text — callers must run `normalizeOcrText` before parsing.

## License

GPLv3
