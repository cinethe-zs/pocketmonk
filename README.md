# PocketMonk v1.0.0

> Fully offline, private AI assistant for Android.
> No cloud. No subscriptions. No data ever leaves your device.

**Target device:** Google Pixel 7a (Tensor G2, 8 GB RAM) · Android 13+
**Package:** `com.example.pocketmonk`

---

## Features

- **100% on-device** — llama.cpp via [fllama](https://github.com/Telosnex/fllama), zero network calls during inference
- **Streaming responses** with a live token-by-token display
- **Markdown rendering** — code blocks, bold, lists, tables
- **Multiple models** — download Gemma 3 4B, Phi-4 Mini, or Qwen 2.5 3B directly from the app
- **Persistent conversations** — all chats saved locally, with a swipe-to-delete drawer
- **Model switching** — change the active model from Settings without restarting
- **Stop generation** at any time
- **Dark theme** — easy on the eyes for late-night use

---

## Install (pre-built APK)

1. Download **`pocketmonkv1.0.0.apk`** from the [Releases page](../../releases/latest)
2. On your Android device: **Settings → Security → Install unknown apps** → allow your browser/Files app
3. Open the APK and tap **Install**
4. Launch PocketMonk — on first run, download a model from the in-app catalogue

> **No model is bundled.** The app downloads GGUF models (~2–4 GB) directly to your device storage.

---

## Build from source

### Prerequisites

| Tool | Version |
|------|---------|
| Flutter | 3.41+ (use [puro](https://puro.dev)) |
| Android NDK | 27.0.12077973 |
| Android SDK | API 36 |
| Java | 21 (bundled with Android Studio) |

### Steps

```bash
git clone https://github.com/cinethe-zs/pocketmonk.git
cd pocketmonk
flutter pub get
cd android
./gradlew assembleDebug
```

APK output: `build/app/outputs/flutter-apk/app-debug.apk`

> Always use `--release` / `assembleRelease` for production — debug mode is ~10× slower for LLM inference.

---

## Model catalogue

| Model | Size | RAM | Notes |
|-------|------|-----|-------|
| Gemma 3 4B Q4_K_M | ~2.5 GB | ~3.5 GB | Best quality, recommended |
| Phi-4 Mini 3.8B Q4_K_M | ~2.3 GB | ~3.2 GB | Fast, good at coding |
| Qwen 2.5 3B Q4_K_M | ~1.9 GB | ~2.5 GB | Lightest, fastest |

Models are sourced from [Hugging Face / bartowski](https://huggingface.co/bartowski) and stored at:
`/storage/emulated/0/Android/data/com.example.pocketmonk/files/models/`

---

## Project structure

```
lib/
├── main.dart                     # Bootstrap, routing, model switching
├── theme/app_theme.dart          # Dark colour palette
├── models/
│   ├── message.dart              # Message model + serialisation
│   └── conversation.dart         # Conversation model + persistence
├── services/
│   ├── llm_service.dart          # fllama / llama.cpp wrapper, streaming
│   ├── chat_provider.dart        # ChangeNotifier — conversation state
│   ├── model_manager.dart        # Download manager, model catalogue
│   └── conversation_store.dart   # JSON file-based conversation persistence
└── ui/
    ├── chat_screen.dart          # Main chat UI + conversation drawer
    ├── chat_input_bar.dart       # Text input + send/stop button
    ├── message_bubble.dart       # User & assistant bubbles, markdown
    ├── model_setup_screen.dart   # First-run model download wizard
    └── settings_screen.dart      # Model management, download, switch
```

---

## Configuration

`LlmConfig` defaults (in `llm_service.dart`):

| Parameter | Default | Notes |
|-----------|---------|-------|
| `contextLength` | 4096 | Reduce to 2048 to save ~400 MB RAM |
| `temperature` | 0.7 | Higher = more creative |
| `topP` | 0.9 | Nucleus sampling threshold |
| `maxTokens` | 2048 | Max tokens per response |

---

## Performance (Pixel 7a)

| Metric | Observed |
|--------|----------|
| Model load | 5–15 s cold start |
| First token | 2–4 s |
| Generation speed | 5–10 tok/s (3B Q4) |
| RAM during inference | ~3–4 GB total |

---

## Dependencies

| Package | Purpose |
|---------|---------|
| `fllama` (git) | llama.cpp Flutter bindings |
| `flutter_markdown` | Markdown rendering |
| `provider` | State management |
| `path_provider` | Device storage paths |
| `shared_preferences` | Active model persistence |
| `http` | Model downloads |
| `uuid` | Conversation IDs |

---

## Roadmap

| Version | Feature |
|---------|---------|
| v1.0 | Chat QA, model download, conversation persistence ✓ |
| v1.1 | Voice input (Whisper.cpp STT) |
| v1.2 | Voice output (Piper TTS) |
| v2.0 | GPU acceleration (Vulkan / NNAPI) |

---

## License

MIT — see [LICENSE](LICENSE)
