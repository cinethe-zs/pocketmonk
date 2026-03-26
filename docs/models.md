---
title: Models
nav_order: 4
---

# Models

PocketMonk uses **LiteRT** (formerly MediaPipe) task files (`.task` format). Models are downloaded directly in-app from HuggingFace.

## Available models

| Model | Size | Recommended for |
|-------|------|-----------------|
| **Gemma 3 1B IT** INT4 | ~600 MB | Fastest; daily chat on Pixel 7a |
| **Gemma 3 1B IT** INT4 4096 ctx | ~700 MB | Longer conversations |
| **Gemma 2 2B IT** INT8 | ~2.6 GB | Better quality; needs 3+ GB free RAM |

## Downloading a model

1. Open PocketMonk → tap the **Settings** icon
2. Find the model you want and tap **Download**
3. Wait for the download to complete (progress shown in-app)
4. The model is ready to use immediately

## Licensing

Gemma models require:
1. A free [HuggingFace](https://huggingface.co) account
2. Acceptance of the [Gemma licence](https://ai.google.dev/gemma/terms) on the model page

PocketMonk handles the download; you may be prompted for a HuggingFace token in Settings.

## Storage

Models are stored on-device. On a Pixel 7a with 128 GB storage, you can keep multiple models downloaded simultaneously.

## Switching models

Each conversation is tied to the model it was created with. To use a different model, start a new conversation and select the model in the **New Conversation** dialog.
