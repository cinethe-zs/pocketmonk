# PocketMonk

> Fully offline, private AI assistant for Android.
> No cloud. No subscriptions. No data ever leaves your device.

**Target:** Google Pixel 7a (Tensor G2, 8 GB RAM) · Android 9+ · arm64

---

## Install

1. Download **`pocketmonk-v2.0.0.apk`** from the [Releases page](../../releases/latest)
2. On Android: **Settings → Security → Install unknown apps** → allow your file manager
3. Open the APK and tap **Install**
4. Launch PocketMonk — on first run, download a model (~600 MB–2.6 GB)

---

## v2.0 — Native Kotlin app (current)

The app has been fully rewritten in **Kotlin + Jetpack Compose** using **MediaPipe LLM Inference** for on-device inference. The Flutter app remains in this repository for reference.

### Features

| Feature | Description |
|---------|-------------|
| **True token streaming** | Responses appear word by word as the model generates |
| **Markdown rendering** | Headings, fenced code blocks with copy button, lists, blockquotes, inline formatting |
| **Per-conversation model & context** | Choose model and context window (512 / 1K / 2K / 4K) at conversation creation |
| **In-app model download** | Download models from HuggingFace LiteRT directly in the app |
| **Auto-rename** | Conversation titles auto-generated after the first exchange; manual rename via system prompt panel |
| **Context compression** | Auto-compresses history at 85% usage, queues pending messages |
| **Conversation management** | Search, tag filtering, fork, star, edit, regenerate, export |
| **Scroll-to-bottom button** | Appears when scrolled up in long conversations |
| **Crash guard** | Detects native crashes on next launch and shows diagnostics |

### Build from source

**Prerequisites:** Android Studio 2025+, JDK 21, Android SDK API 36

```bash
git clone https://github.com/cinethe-zs/pocketmonk.git
cd pocketmonk/android-native
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### Model catalogue

| Model | Size | Notes |
|-------|------|-------|
| **Gemma 3 1B IT** INT4 | ~600 MB | Fastest · recommended for Pixel 7a |
| **Gemma 3 1B IT** INT4 4096 ctx | ~700 MB | Larger context window |
| **Gemma 2 2B IT** INT8 | ~2.6 GB | Better quality · needs 3+ GB free RAM |
| **Gemma 3 4B IT** INT4 | ~2.6 GB | Best quality · experimental · needs 3+ GB free RAM |

Models are downloaded from [HuggingFace LiteRT community](https://huggingface.co/litert-community) and require a free HuggingFace token and Gemma license acceptance.

### Project structure

```
android-native/
└── app/src/main/kotlin/app/pocketmonk/
    ├── MainActivity.kt              # Navigation host
    ├── PocketMonkApp.kt             # Crash guard
    ├── model/                       # Conversation, Message data classes
    ├── repository/                  # JSON persistence (Gson)
    ├── service/
    │   ├── LlmService.kt            # MediaPipe inference + streaming
    │   └── ModelManager.kt          # Download manager, HF API
    ├── viewmodel/ChatViewModel.kt   # MVVM state (StateFlow)
    └── ui/
        ├── ChatScreen.kt            # Main chat UI
        ├── MarkdownContent.kt       # Block-level markdown renderer
        ├── MessageBubble.kt         # User / assistant bubbles
        ├── ConversationDrawer.kt    # Side drawer with search & tags
        ├── ModelSetupScreen.kt      # First-run / model download screen
        ├── NewConversationDialog.kt # Model + context picker
        ├── SystemPromptBar.kt       # Inline system prompt editor
        └── ContextBar.kt            # Token usage bar
```

---

## v1.0 — Flutter app (legacy)

The original Flutter app using **fllama** (llama.cpp bindings) and GGUF models. Kept in `lib/` and `android/` for reference.

| Feature | Description |
|---------|-------------|
| On-device inference | llama.cpp via fllama, CPU-only |
| Voice input | Android STT |
| Dual-model quick-swap | Primary + secondary model, swap in one tap |
| GPU layer config | Experimental GPU offload |

---

## Performance (Pixel 7a, Gemma 3 1B INT4)

| Metric | Observed |
|--------|----------|
| Cold start | 5–10 s |
| Tokens per second | 10–20 tok/s |
| RAM during inference | ~1.5 GB |

---

## Roadmap

| Version | Feature |
|---------|---------|
| v1.0 | Flutter app — chat, model download, voice input ✓ |
| v2.0 | Native Kotlin rewrite — streaming, markdown, UX ✓ |
| v2.1 | Voice input (Android STT) |
| v3.0 | GPU inference (Vulkan / NNAPI) |
