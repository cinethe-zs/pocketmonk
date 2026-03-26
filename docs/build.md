---
title: Build from Source
nav_order: 5
---

# Build from Source

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | 2025.3.2+ |
| JDK | 21 (bundled Android Studio JBR) |
| Android SDK | API 36 |
| build-tools | 36.1.0 |
| NDK | Not required (LiteRT uses prebuilt AAR) |

## Steps

```bash
git clone https://github.com/cinethe-zs/pocketmonk.git
cd pocketmonk/android-native
```

**Windows:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

**macOS / Linux:**
```bash
export JAVA_HOME=/path/to/jdk21
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/pocketmonk-vX.Y.Z.apk`

## Installing to device

```bash
adb install app/build/outputs/apk/debug/pocketmonk-vX.Y.Z.apk
```

Or copy the APK to your phone and open it in a file manager.

## Key dependencies

| Library | Purpose |
|---------|---------|
| `com.google.mediapipe:tasks-genai` | LiteRT LLM inference |
| Jetpack Compose + Material3 | UI |
| `ai.onnxruntime` / Vosk | On-device STT |
| pdfbox-android | PDF text extraction |
| Apache POI | DOCX text extraction |
| ML Kit text recognition | Image OCR |
| OkHttp + Gson | Networking and JSON |

## Version numbering

`versionCode` and `versionName` are in `android-native/app/build.gradle.kts`. Increment both before tagging a release.
