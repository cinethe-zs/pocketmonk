# PocketMonk

> Fully offline, private AI assistant for Android.
> No cloud. No subscriptions. No data ever leaves your device.

**Target:** Google Pixel 7a (Tensor G2, 8 GB RAM) · Android 9+ · arm64

---

## Install

1. Download the latest **`pocketmonk-vX.Y.Z.apk`** from the [Releases page](../../releases/latest)
2. On Android: **Settings → Security → Install unknown apps** → allow your file manager
3. Open the APK and tap **Install**
4. Launch PocketMonk — on first run, download a model (~600 MB–2.6 GB)

---

## Features

### Chat

| Feature | Description |
|---------|-------------|
| **True token streaming** | Responses appear word by word as the model generates |
| **Markdown rendering** | Headings, fenced code blocks with copy button, lists, blockquotes, inline formatting |
| **Per-conversation model & context** | Choose model and context window (512 / 1K / 2K / 4K) at conversation creation |
| **Context compression** | Auto-compresses history at 85% usage, queues pending messages |
| **Document temperature** | 0.4 temperature for document sessions to reduce repetition |

### Large Document Processing

Attach any document (PDF, DOCX, image, audio, video) and ask a question or give an instruction. PocketMonk automatically routes your request:

| Path | Trigger | What happens |
|------|---------|--------------|
| **TRANSFORM** | "Translate", "Rewrite", "Summarise briefly" | Chunks the document, transforms each chunk, streams results live in a teal log card |
| **ANALYZE** | "Analyze", "Find all", "List" | Map-reduce: extracts facts per chunk, then synthesises a single answer across passes; each pass shown live in an indigo log card |

Supported formats: **PDF · DOCX · TXT · PNG/JPG/WebP · MP3/WAV/OGG · MP4/WebM**

When a document is attached the conversation is automatically tagged **"file"**.

### Voice Input

Tap the microphone — speech is transcribed on-device using **Vosk** (no internet required).

### Web Search

Ask the assistant to search the web; results are fetched and summarised locally.

### Conversation Management

| Feature | |
|---------|-|
| Search conversations | Full-text search in drawer |
| Tag filtering | Filter by any tag including auto-tags like "file" |
| Star / fork / export | Long-press a conversation |
| Auto-rename | Title generated after first exchange |
| Personas | Custom system prompts saved as personas |
| Regenerate / edit | Long-press any message |

### UI

- Sticky scroll-to-bottom that follows streaming tokens and live log cards
- Scroll-to-bottom button when scrolled up in long conversations
- Context usage bar with token count live during inference
- Crash guard: detects native crashes on next launch and shows diagnostics

---

## Model Catalogue

Models are downloaded directly in-app from HuggingFace (LiteRT community). A free HuggingFace account and Gemma licence acceptance are required for Gemma models.

| Model | Size | Notes |
|-------|------|-------|
| **Gemma 3 1B IT** INT4 | ~600 MB | Fastest · recommended for Pixel 7a |
| **Gemma 3 1B IT** INT4 4096 ctx | ~700 MB | Larger context window |
| **Gemma 2 2B IT** INT8 | ~2.6 GB | Better quality · needs 3+ GB free RAM |

---

## Build from Source

**Prerequisites:** Android Studio 2025+, JDK 21, Android SDK API 36

```bash
git clone https://github.com/cinethe-zs/pocketmonk.git
cd pocketmonk/android-native

# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/pocketmonk-vX.Y.Z.apk`

---

## Architecture

```
android-native/app/src/main/kotlin/app/pocketmonk/
├── MainActivity.kt              Navigation host
├── PocketMonkApp.kt             Crash guard
├── model/                       Conversation, Message data classes
├── repository/                  JSON persistence (Gson)
├── service/
│   ├── LlmService.kt            LiteRT inference, streaming, map-reduce, TRANSFORM/ANALYZE
│   ├── ModelManager.kt          Download manager, HuggingFace API
│   ├── PersonaStore.kt          Persona CRUD
│   ├── VoskService.kt           On-device STT
│   └── WebSearchService.kt      Web search + summarisation
├── viewmodel/ChatViewModel.kt   MVVM state (StateFlow), stop/cancel logic
└── ui/
    ├── ChatScreen.kt            Main chat UI, live log cards, sticky scroll
    ├── MarkdownContent.kt       Block-level markdown renderer
    ├── MessageBubble.kt         User / assistant bubbles
    ├── ConversationDrawer.kt    Side drawer with search & tags
    ├── ModelSetupScreen.kt      First-run / model download screen
    ├── NewConversationDialog.kt Model + context picker
    ├── SystemPromptBar.kt       Inline system prompt editor
    ├── PersonaDialog.kt         Persona picker
    ├── QuickPromptRow.kt        One-tap prompt shortcuts
    ├── VoskComposables.kt       Voice input UI
    └── ContextBar.kt            Token usage bar
```

**Inference stack:** [LiteRT (MediaPipe LLM Inference)](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) — on-device, CPU, sessions with streaming via `generateContentStream`.

---

## Performance (Pixel 7a, Gemma 3 1B INT4)

| Metric | Observed |
|--------|----------|
| Cold start | 5–10 s |
| Tokens per second | 10–20 tok/s |
| RAM during inference | ~1.5 GB |

---

## Privacy

All inference runs locally on your device. PocketMonk makes no outbound connections except:
- **Model download** — HuggingFace (one-time, initiated by you)
- **Web search** — only when you explicitly request it

No telemetry. No analytics. No accounts.

---

## Documentation

Full documentation: **[pocketmonk docs](https://cinethe-zs.github.io/pocketmonk/)**

---

## Roadmap

| Version | Feature |
|---------|---------|
| v2.0 | Native Kotlin rewrite — streaming, markdown, UX ✓ |
| v3.0 | Large document processing (TRANSFORM / ANALYZE) ✓ |
| v3.x | Voice input (Vosk), web search, personas ✓ |
| v4.0 | GPU inference (Vulkan / NNAPI) |
