---
title: Privacy
nav_order: 7
---

# Privacy

PocketMonk is designed from the ground up to keep your data on your device.

## What runs locally

| Component | Where it runs |
|-----------|--------------|
| LLM inference | On-device (LiteRT, CPU) |
| Document text extraction | On-device (pdfbox-android, Apache POI, ML Kit) |
| Speech recognition | On-device (Vosk) |
| Conversation storage | On-device (JSON files in app storage) |
| Model storage | On-device (app external files dir) |

## What leaves the device

| Action | Data sent | When |
|--------|-----------|------|
| Model download | Model filename, HuggingFace token | Only when you tap Download in Settings |
| Web search | Your search query | Only when you explicitly ask the assistant to search the web |

No telemetry. No analytics. No crash reporting. No accounts (except HuggingFace for model download).

## Storage locations

| Data | Location |
|------|----------|
| Conversations | `appFilesDir/conversations/` (private app storage) |
| Models | `appExternalFilesDir/models/` (visible in Files app under `Android/data/app.pocketmonk/files/models/`) |
| Vosk STT models | `appFilesDir/vosk-models/` |
| Persona definitions | SharedPreferences |

Uninstalling the app removes all private storage. Model files in external storage may persist depending on Android version; delete them manually if needed.
