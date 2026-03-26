---
title: Architecture
nav_order: 6
---

# Architecture

## Overview

```
android-native/app/src/main/kotlin/app/pocketmonk/
├── MainActivity.kt              Navigation host, crash-guard check
├── PocketMonkApp.kt             Application class, crash guard
├── model/                       Conversation, Message data classes + JSON
├── repository/                  JSON persistence (Gson, appFilesDir)
├── service/
│   ├── LlmService.kt            LiteRT inference, streaming, map-reduce
│   ├── ModelManager.kt          Download manager, HuggingFace API
│   ├── PersonaStore.kt          Persona CRUD (SharedPreferences)
│   ├── VoskService.kt           On-device STT (Vosk)
│   └── WebSearchService.kt      Web search + model-side summarisation
├── viewmodel/ChatViewModel.kt   MVVM state (StateFlow), stop/cancel
└── ui/
    ├── ChatScreen.kt            Main chat, live log cards, sticky scroll
    ├── MarkdownContent.kt       Block-level markdown renderer
    ├── MessageBubble.kt         User / assistant bubbles
    ├── ConversationDrawer.kt    Drawer: search, tags, conversation list
    ├── ModelSetupScreen.kt      First-run / model download screen
    ├── NewConversationDialog.kt Model + context picker
    ├── SystemPromptBar.kt       Inline system prompt editor
    ├── PersonaDialog.kt         Persona picker
    ├── QuickPromptRow.kt        One-tap prompt shortcuts
    ├── VoskComposables.kt       Voice input UI
    └── ContextBar.kt            Token usage bar
```

## Inference stack

**LiteRT (MediaPipe LLM Inference)** — prebuilt AAR from `com.google.mediapipe:tasks-genai`.

- `Engine` — loaded once per model; holds the model weights in memory
- `Session` — created per inference call; configured with `SessionConfig(SamplerConfig(...))`
- `generateContentStream` — async streaming via `ResponseCallback`; a `CountDownLatch` blocks the IO coroutine until `onDone` / `onError`
- `generateContent` — blocking, used for the classifier

Sessions are protected by an `engineLock` (reentrant lock) so only one inference runs at a time.

## Document processing pipeline

```
loadDocument()
    └── splits text into chunks

sendMessage() with documentContent != null
    └── classify()                         [blocking generateContent]
            │
            ├── "yes" → streamDocument()   [TRANSFORM]
            │           └── runSessionStreaming() per chunk → concat
            │
            └── "no"  → mapReduceDocument() [ANALYZE]
                        ├── MAP: extractChunk() per chunk
                        └── REDUCE: compressBatch() until single context
```

Each `runSessionStreaming` call fires `onToken(buffer)` per token received. The ViewModel wires these callbacks to `MutableStateFlow` values observed by the UI.

## Stop / cancellation

```
stopGeneration()
    ├── processingJob?.cancel()      cancels the coroutine
    ├── llmService.cancel()          calls currentSession?.cancelProcess()
    ├── _isMapReducing = false
    ├── clears _transformLog, _analyzeIterationLogs
    └── removes pending user message from conversation (fresh MutableList)
```

`CancellationException` is always re-thrown inside coroutine catch blocks so cancellation propagates correctly.

## State flows (ChatViewModel)

| Flow | Type | Purpose |
|------|------|---------|
| `_currentConversation` | `StateFlow<Conversation?>` | Active conversation |
| `_isGenerating` | `StateFlow<Boolean>` | Chat inference in progress |
| `_streamingText` | `StateFlow<String>` | Partial assistant response |
| `_isMapReducing` | `StateFlow<Boolean>` | Document processing in progress |
| `_mapReduceStatus` | `StateFlow<String?>` | Status label (e.g. "Chunk 3/12") |
| `_classifierLog` | `StateFlow<String?>` | Classifier decision log |
| `_transformLog` | `StateFlow<String?>` | Live TRANSFORM output buffer |
| `_analyzeIterationLogs` | `StateFlow<List<String>>` | Per-iteration ANALYZE buffers |

## Key design decisions

- **CPU-only inference** — `Backend.CPU(numOfThreads = 6)`. GPU backend (`Backend.GPU()`) causes a JNI native crash on Tensor G2 that Kotlin cannot catch.
- **Temperature 0.4 for documents** — fixed in `SessionConfig` at session creation; cannot change mid-session.
- **Repetition loop detection** — `trimRepetitionLoop()` checks if a 40/60/100-char pattern repeats 4+ times in the last 600 chars; trims to pre-loop content and cancels the session via `cancelProcess()`.
- **Message aliasing bug avoidance** — message removal always creates a fresh `MutableList` via `filterNot { it === x }.toMutableList()` to avoid Compose seeing the same list reference in old and new state.
- **Mutable list identity** — `conv.copy(messages = conv.messages)` passes the same list reference, which causes `IndexOutOfBoundsException` in Compose's diffing. Always create a new list.
