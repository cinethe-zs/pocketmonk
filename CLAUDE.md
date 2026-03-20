# PocketMonk — Project Spec & Build Notes

> Pure offline, private AI assistant running on a **Google Pixel 7a**.
> No server. No cloud. No data leaves the device.

---

## Status: v1.0.0 released

The app is fully functional as a private on-device chat assistant.

---

## Hardware Target

| Property | Value |
|----------|-------|
| Device | Google Pixel 7a |
| Chip | Google Tensor G2 (ARM Cortex-X1 @ 2.85 GHz) |
| RAM | 8 GB total — ~4–5 GB usable after OS |
| Storage | 128 GB |
| GPU | ARM Mali-G710 MP7 |
| Android | 13+ |
| Package | `com.example.pocketmonk` |

---

## Build Environment

| Tool | Value |
|------|-------|
| Flutter | 3.41.5 stable (via puro) |
| Dart | bundled with Flutter |
| Android NDK | **27.0.12077973** (critical — CMake build fails with other versions) |
| Android SDK | API 36.1 |
| build-tools | 36.1.0 |
| Java | 21 (Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr`) |
| ABI filter | arm64-v8a only |
| minSdk | 28 |

### Build command

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
cd android
.\gradlew.bat assembleDebug
```

APK: `build/app/outputs/flutter-apk/app-debug.apk`

---

## Architecture

```
Flutter app (Dart)
    │
    ├── ChatProvider (ChangeNotifier) — conversation state, streaming
    ├── LlmService — fllama wrapper, OpenAiRequest, streaming tokens
    ├── ModelManager — download manager, catalogue, SharedPreferences
    └── ConversationStore — JSON persistence in appDocuments/conversations/
```

### Key decisions

- **fllama** (git: Telosnex/fllama) — Flutter bindings for llama.cpp via JNI
- **CPU-only inference** (`numGpuLayers: 0`) — Android GPU/NPU acceleration not yet supported by fllama
- **No tools/function calling** — removed in v1.0; small models (~4B) do not reliably trigger tool calls and output raw JSON instead, which breaks the UX
- **presencePenalty: 1.1** — prevents token repetition loops (llama.cpp default)
- **`reasoning_format: none`** injected in fllama's openai.dart to disable hardcoded thinking mode
- **GGUF format** for models — quantized with Q4_K_M

---

## File Map

| File | Purpose |
|------|---------|
| `lib/main.dart` | Bootstrap, boot states (loading/needsModel/ready), model switching |
| `lib/services/llm_service.dart` | fllama inference, streaming, LlmConfig |
| `lib/services/chat_provider.dart` | ChangeNotifier, conversation CRUD, sendMessage |
| `lib/services/model_manager.dart` | Download catalogue, progress tracking, SharedPreferences |
| `lib/services/conversation_store.dart` | JSON save/load/delete for conversations |
| `lib/models/message.dart` | Message + MessageRole + toJson/fromJson |
| `lib/models/conversation.dart` | Conversation model + toJson/fromJson |
| `lib/ui/chat_screen.dart` | Main screen, drawer, app bar |
| `lib/ui/chat_input_bar.dart` | Text field + send/stop |
| `lib/ui/message_bubble.dart` | User/assistant bubbles + markdown |
| `lib/ui/settings_screen.dart` | Model management UI |
| `lib/ui/model_setup_screen.dart` | First-run download wizard |
| `lib/theme/app_theme.dart` | Dark theme, colour constants |
| `assets/system_prompt.txt` | System prompt loaded at init |
| `make_icon.py` | Generates monk PNG icons at all mipmap densities |

---

## Model Catalogue

Hosted on Hugging Face (bartowski):

| Model | Filename | URL prefix |
|-------|----------|-----------|
| Gemma 3 4B | `google_gemma-3-4b-it-Q4_K_M.gguf` | `bartowski/google_gemma-3-4b-it-GGUF` |
| Phi-4 Mini 3.8B | `microsoft_Phi-4-mini-instruct-Q4_K_M.gguf` | `bartowski/microsoft_Phi-4-mini-instruct-GGUF` |
| Qwen 2.5 3B | `Qwen2.5-3B-Instruct-Q4_K_M.gguf` | `bartowski/Qwen2.5-3B-Instruct-GGUF` |

Models stored at: `getExternalStorageDirectory()/models/`
Visible in Android Files app under: `Android/data/com.example.pocketmonk/files/models/`

---

## Known Issues & Fixes Applied

| Issue | Fix |
|-------|-----|
| fllama hardcodes `enable_thinking=true` | Added `'reasoning_format': 'none'` in fllama pub cache `lib/misc/openai.dart` |
| Token repetition loops | `presencePenalty: 1.1` |
| CMake `set_target_properties` error (mtmd) | Wrapped in `if(LLAMA_INSTALL_VERSION)`, removed macOS-only flag |
| Linker: unable to find `-lcpp-httplib` | Added `add_subdirectory` for cpp-httplib in android build.gradle |
| Bartowski renamed repos with company prefix | Updated URLs: `google_gemma-3-4b-it-GGUF`, `microsoft_Phi-4-mini-instruct-GGUF` |
| Small models output raw JSON tool calls | **Removed all tool/function-calling** in v1.0 — plain chat only |

---

## What Was Attempted but Removed

**Tool calling / internet access** — implemented in early builds but removed because:
- 3–4B models don't reliably trigger OpenAI-format tool calls
- Models output ad-hoc JSON (e.g. `{"tool_call": {"name": "gpt"...}}`) which leaks to the UI
- Even with detection/suppression, responses were incoherent
- Keeping the feature made the app worse than without it

Candidate approach for a future version: use a larger model (7B+) with proven tool-calling support, or implement a dedicated weather/search widget in the UI that bypasses the model entirely.

---

## Roadmap

| Version | Feature |
|---------|---------|
| v1.0 | Chat QA, model download, conversation persistence ✓ |
| v1.1 | Voice input — Whisper.cpp STT |
| v1.2 | Voice output — Piper TTS |
| v2.0 | GPU inference (Vulkan / NNAPI) |
