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

## Requirements

- **Android 6.0 (API 23) or higher**
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
├── TextRecognizer.kt            — ML Kit OCR wrapper
├── GpsCoordinateParser.kt       — OCR normalization, coordinate regex parsing & validation
├── LocationMocker.kt            — Test provider registration & mock location updates
├── GpsSpoofViewModel.kt         — UI state, coordinate state, one-time events
└── ui/
    ├── GrsScreen.kt             — Main screen layout & snackbar event handling
    ├── CameraPreviewSection.kt  — Camera preview, scan-box overlay, scan controls
    ├── CoordinateInputSection.kt— Lat/lon text fields, format selector, mocking lifecycle
    └── theme/                   — Material 3 theme, colors, typography
```

## Building

```bash
./gradlew assembleDebug
```

For release builds, place a `keystore.properties` file in the project root with your signing credentials. If the file is absent, the release build type falls back to the debug signing config.

## License

GPLv3
