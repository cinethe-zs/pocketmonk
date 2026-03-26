---
title: Document Processing
nav_order: 3
---

# Document Processing

PocketMonk can reason over documents that are larger than the model's context window using a two-path pipeline.

## How it works

When you attach a document and send a message, a **classifier LLM** runs first to decide which path to take:

```
Your message → Classifier → TRANSFORM or ANALYZE
```

The classifier asks: *"Does this task require transforming or translating the text verbatim?"*

- **"Yes"** → **TRANSFORM** path
- **"No"** → **ANALYZE** path

---

## TRANSFORM path

Used for: translate, rewrite, reformat, summarise briefly.

1. Document is split into chunks that fit in the model's context
2. Each chunk is transformed (translated / rewritten / etc.) by the model
3. Outputs are concatenated and returned as the final answer

**Live view:** A teal **TRANSFORM** log card appears in the chat and grows token-by-token as the model works through each chunk.

**Repetition guard:** If the model enters a repetition loop, the loop is detected and trimmed automatically. The clean pre-loop content is preserved in the result.

---

## ANALYZE path

Used for: analyze, find all, compare, list, extract, explain.

1. Document is split into chunks
2. **MAP phase** — each chunk is processed by the model, extracting facts relevant to your question
3. **REDUCE phase** — extracted facts are compressed across multiple passes until they fit in one context window
4. Final answer is synthesised from the compressed facts

**Live view:** An indigo **ANALYZE · iter N** log card appears per pass and grows live. You can watch the model work through MAP and each REDUCE pass.

---

## Supported formats

| Format | Notes |
|--------|-------|
| PDF | Text extracted via pdfbox-android |
| DOCX | Text extracted via Apache POI |
| TXT | Read directly |
| PNG / JPG / WebP | OCR via ML Kit text recognition |
| MP3 / WAV / OGG | Transcribed via Vosk STT |
| MP4 / WebM | Audio track extracted, then transcribed via Vosk |

---

## Temperature

Document processing sessions use **temperature 0.4** (lower than the default chat temperature) to reduce repetition during long generation tasks.

---

## Stopping

Press **Stop** at any time during document processing. The current chunk is abandoned, all live log cards are cleared, and the pending user message is removed from history.
