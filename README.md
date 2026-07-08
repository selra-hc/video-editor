# Video Editor — Android App

A native Android application that rotates and/or cuts a segment out of any video **without re-encoding**, preserving the original frame rate, resolution, quality, and audio exactly as-is. Trimming is also available. though reencoding is mandatory in this case. It is implemented to be as lossless as possible.

---

## Features

| Feature | Detail |
|---|---|
| Lossless cut | FFmpeg `-c:v copy -c:a copy` — zero re-encoding |
| Visual timeline | Thumbnail strip with two draggable orange trim handles |
| Touch scrubbing | Tap inside the selection to jump the preview to that position |
| Manual entry | Type exact start / end times (seconds, two decimal places) |
| Two-way sync | Timeline ↔ text fields stay in sync at all times |
| Full-screen preview | ExoPlayer with built-in transport controls |
| Output | Saved to `Movies/VideoEditor/` in the device gallery |

---

## Requirements

- **Android Studio** Hedgehog (2023.1) or newer
- **Android SDK** 35 (install via SDK Manager)
- **minSdk 24** (Android 7.0)
- Internet connection for first Gradle sync (downloads dependencies)

---

## Building

```bash
# Clone / copy the project into Android Studio
# Then build from the IDE, or from the command line:

./gradlew assembleDebug
```

The signed APK will appear at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Key dependencies

| Library | Purpose |
|---|---|
| `androidx.media3:media3-exoplayer` | In-app video preview |
| `androidx.media3:media3-ui` | `PlayerView` UI component |
| `com.arthenica:ffmpeg-kit-android-min` | FFmpeg for stream-copy cutting |

> **APK size note** — `ffmpeg-kit-android-min` adds ~50 MB to the APK. If you need codec support (e.g. for re-encoding tasks) switch to `ffmpeg-kit-android-full` in `app/build.gradle.kts`.

---

## How cutting works

```
ffmpeg -i <input>
       -ss <start_seconds>
       -to <end_seconds>
       -c:v copy -c:a copy
       -avoid_negative_ts make_zero
       <output.mp4>
```

* `-ss` / `-to` — select the time range (seconds, supports decimals)
* `-c:v copy -c:a copy` — stream copy; **no decoding or encoding**
* `-avoid_negative_ts make_zero` — fixes presentation timestamps after the cut so the output plays from 00:00

The output is written to the app cache first, then moved to `MediaStore` so it appears in the gallery.

---

## Project structure

```
VideoEditor/
├── app/src/main/
│   ├── java/com/videoeditor/
│   │   ├── MainActivity.kt      — ExoPlayer, FFmpeg, UI logic
│   │   └── TrimRangeView.kt     — Custom Canvas view (timeline + handles)
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── values/{colors,strings,themes}.xml
│   └── AndroidManifest.xml
├── app/build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```
