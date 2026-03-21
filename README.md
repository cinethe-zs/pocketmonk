# PocketMonk

> Fully offline, private AI assistant for Android.
> No cloud. No subscriptions. No data ever leaves your device.

**Target:** Google Pixel 7a (Tensor G2, 8 GB RAM) · Android 9+ arm64

---

## Features

| Feature | Description |
|---------|-------------|
| **On-device inference** | llama.cpp via fllama — zero network calls during chat |
| **Streaming responses** | Tokens appear as they're generated |
| **Markdown rendering** | Code blocks, bold, lists, tables in assistant replies |
| **Voice input** | Tap the mic to dictate — speech-to-text via Android STT |
| **Message actions** | Long-press any bubble: star, edit, copy, regenerate |
| **Conversation persistence** | All chats saved locally as JSON |
| **Search & tags** | Search across all conversations; tag and filter them |
| **Dual-model quick-swap** | Set a primary + secondary model, swap with one tap |
| **Per-conversation system prompt** | Override the AI persona per chat |
| **Export** | Copy any conversation to clipboard as Markdown |
| **GPU layer config** | Optionally offload layers to GPU; auto-falls back to CPU |

---

## Install

1. Download **`pocketmonkv1.0.0.apk`** from the [Releases page](../../releases/latest)
2. On Android: **Settings → Security → Install unknown apps** → allow your file manager
3. Open the APK and tap **Install**
4. Launch PocketMonk — on first run choose a model to download (~2–4 GB)

---

## Build from source

### Prerequisites

| Tool | Version |
|------|---------|
| Flutter | 3.41+ via [puro](https://puro.dev) |
| Android NDK | **27.0.12077973** |
| Android SDK | API 36 |
| Java | 21 (Android Studio JBR) |

### Steps

```bash
git clone https://github.com/cinethe-zs/pocketmonk.git
cd pocketmonk
flutter pub get
cd android && ./gradlew assembleDebug
```

APK: `build/app/outputs/flutter-apk/app-debug.apk`

> Use `assembleRelease` for production — debug mode is ~10× slower for LLM inference.

---

## Model catalogue

| Model | Size | Notes |
|-------|------|-------|
| **Gemma 3 4B** Q4\_K\_M | ~2.5 GB | Best quality · recommended |
| **Phi-4 Mini 3.8B** Q4\_K\_M | ~2.5 GB | Strong reasoning · fast |
| **Qwen 2.5 3B** Q4\_K\_M | ~1.9 GB | Lightest · minimal RAM |

Models are downloaded from [Hugging Face / bartowski](https://huggingface.co/bartowski) and stored at:
`Android/data/app.pocketmonk/files/models/`

---

## Project structure

```
lib/
├── main.dart                      # Bootstrap, routing, model switching
├── theme/app_theme.dart           # Dark colour palette
├── models/
│   ├── message.dart               # Message + MessageRole + serialisation
│   └── conversation.dart          # Conversation + tags + system prompt
├── services/
│   ├── llm_service.dart           # fllama wrapper, streaming, GPU fallback
│   ├── chat_provider.dart         # ChangeNotifier — conversation state
│   ├── model_manager.dart         # Download manager, dual-model, GPU prefs
│   └── conversation_store.dart    # JSON persistence
└── ui/
    ├── chat_screen.dart           # Main UI, drawer, system prompt, export
    ├── chat_input_bar.dart        # Text input + mic + send/stop
    ├── message_bubble.dart        # Bubbles, long-press menu, regenerate
    ├── model_setup_screen.dart    # First-run wizard
    └── settings_screen.dart       # Model management + GPU settings
```

---

## Configuration

`LlmConfig` defaults (in `llm_service.dart`):

| Parameter | Default | Notes |
|-----------|---------|-------|
| `contextLength` | 4096 | Reduce to 2048 to save ~400 MB RAM |
| `temperature` | 0.7 | Higher = more creative |
| `topP` | 0.9 | Nucleus sampling |
| `maxTokens` | 2048 | Max tokens per response |
| `numGpuLayers` | 0 | GPU layer count; 0 = CPU only |

---

## Performance (Pixel 7a)

| Metric | Observed |
|--------|----------|
| Cold start | 5–15 s |
| First token | 2–4 s |
| Speed (3B Q4) | 5–10 tok/s |
| RAM during inference | ~3–4 GB |

---

## Dependencies

| Package | Purpose |
|---------|---------|
| `fllama` (git) | llama.cpp Flutter bindings |
| `flutter_markdown` | Markdown rendering |
| `speech_to_text` | Voice input |
| `provider` | State management |
| `path_provider` | Device storage paths |
| `shared_preferences` | Model + GPU prefs persistence |
| `http` | Model downloads |
| `uuid` | Conversation IDs |

---

## Roadmap

| Version | Feature |
|---------|---------|
| v1.0 | Chat, model download, conversations, voice input ✓ |
| v1.1 | Voice output (Piper TTS) |
| v2.0 | GPU acceleration (Vulkan / NNAPI) |
