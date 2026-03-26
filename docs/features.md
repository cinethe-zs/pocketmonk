---
title: Features
nav_order: 2
---

# Features

## Chat

| Feature | Description |
|---------|-------------|
| **Token streaming** | Responses appear word by word as the model generates |
| **Markdown rendering** | Headings, fenced code blocks with copy button, lists, blockquotes, bold/italic |
| **Per-conversation model** | Pick any downloaded model when creating a conversation |
| **Context window** | Choose 512 / 1K / 2K / 4K tokens at conversation creation |
| **Context compression** | History auto-compressed at 85% usage; pending messages are queued |
| **Stop generation** | Cancel at any time; partial response is discarded cleanly |

## Document Processing

Attach a document and ask a question or give an instruction. PocketMonk uses a two-path pipeline — see [Document Processing](document-processing) for full details.

**Supported formats:** PDF · DOCX · TXT · PNG/JPG/WebP · MP3/WAV/OGG · MP4/WebM

When a document is attached, the conversation is automatically tagged **"file"**.

## Voice Input

Tap the microphone button to speak. Transcription runs on-device using **Vosk** — no internet required. Supports multiple languages.

## Web Search

Ask the assistant to search the web. Results are fetched and summarised locally by the model.

## Conversation Management

| Feature | How to access |
|---------|--------------|
| Search | Drawer → search bar |
| Filter by tag | Drawer → tap a tag chip |
| Star / archive | Long-press a conversation in the drawer |
| Fork | Long-press → Fork |
| Export | Long-press → Export |
| Auto-rename | Happens automatically after the first exchange |
| Manual rename | Tap the title in the app bar |
| Edit message | Long-press a message bubble |
| Regenerate | Long-press the last assistant message |

## Personas

Create named system prompts (personas) and switch between them per conversation. Access via the persona icon in the app bar.

## UI Details

- **Sticky scroll-to-bottom** — viewport follows new tokens as they arrive, including live log cards during document processing
- **Scroll-to-bottom button** — appears when scrolled up in a long conversation
- **Context bar** — shows live token count during inference
- **Crash guard** — detects native crashes on next launch and shows a diagnostic prompt
