# 🐉 DnD Story Teller

Turn a recording of your Dungeons & Dragons session into:

1. a **diarized transcript** (who said what),
2. a **third-person narrated chronicle** of the session, and
3. **one illustration per scene**, in a fantasy painterly style.

Everything runs **locally** — no external API calls, no per-token costs.

---

## Architecture

```
                         ┌────────────────────────────────────────┐
                         │  Java orchestrator (Spring Boot +        │
   Browser  ◄─────────►  │  langchain4j)                            │
   (web UI)              │  • upload, speaker labeling, gallery     │
                         │  • resumable pipeline + JSON persistence │
                         └───┬───────────────┬───────────────┬──────┘
                             │ HTTP          │ HTTP          │ HTTP
                    ┌────────▼──────┐  ┌──────▼───────┐  ┌────▼──────────┐
                    │ transcription │  │   Ollama     │  │   imagegen    │
                    │  :8001        │  │   :11434     │  │   :8002       │
                    │ faster-whisper│  │ llama3.1:8b  │  │  SDXL (MPS)   │
                    │  + pyannote   │  │              │  │  diffusers    │
                    └───────────────┘  └──────────────┘  └───────────────┘
```

The Java app is **not** a multi-agent system — it's a deterministic, resumable
pipeline. langchain4j is used as an LLM-integration + structured-output toolkit.

### Pipeline stages
1. **Transcribe + diarize** (faster-whisper + pyannote) → `AWAITING_LABELS`.
2. **Label speakers** in the web UI (map `SPEAKER_00` → player/character).
3. **Narrate** the transcript via map-reduce summarization with a rolling
   "story-so-far" context (handles 3–4h sessions that exceed the LLM context window).
4. **Segment** the narration into scenes + image prompts (langchain4j structured output).
5. **Generate** one SDXL image per scene.

State is saved to `data/<sessionId>/session.json` after every step, so a long job
can resume after a crash.

---

## Prerequisites (macOS / Apple Silicon)

```bash
brew install ffmpeg          # required for audio decoding
brew install python@3.12     # ML wheels need Python 3.10–3.12 (NOT 3.13/3.14)
# Java 21 (already installed if `java -version` shows 21)
```

> **Python version matters.** PyTorch / whisperx / tokenizers ship prebuilt wheels
> only for CPython **3.10–3.12**. On 3.13/3.14 pip tries to compile from source and
> fails. The launch scripts auto-pick `python3.12`/`3.11`/`3.10` if your default
> `python3` is newer; override with `PYTHON=/path/to/python3.12` if needed.

### 1. Ollama (local LLM)
```bash
brew install ollama          # or download from https://ollama.com
ollama serve &               # starts the server on :11434
ollama pull llama3.1:8b      # or qwen2.5:14b for higher quality
```

### 2. Hugging Face token (for diarization)
`pyannote` is gated but free. The token is used **only** to download the diarization
model weights (the LLM and Whisper models need no token).

1. Create a token at https://huggingface.co/settings/tokens:
   - **`Read` type** is enough, **or** a **Fine-grained** token with just
     *"Read access to contents of all public gated repos you can access"*.
   - No Write / inference / billing scopes are needed.
2. Accept the license terms (same account) on **both** gated model pages:
   - https://huggingface.co/pyannote/speaker-diarization-3.1
   - https://huggingface.co/pyannote/segmentation-3.0
3. Provide it to the app. Easiest is the git-ignored `.env` file (loaded
   automatically by `make` and `./scripts/dev-up.sh`):
   ```bash
   cp .env.example .env        # then edit .env and paste your token
   ```
   See [Secrets & config via `.env`](#secrets--config-via-env) for details.
   (A plain `export HF_TOKEN=hf_xxxx` also works.)

> Skipping this just disables diarization — you'll still get a transcript, but
> without per-speaker labels.

---

## Running everything

### Recommended: one command (native, Metal-accelerated)
```bash
cp .env.example .env             # one-time: paste your HF_TOKEN into .env (see below)
make up                          # == ./scripts/dev-up.sh
```
This starts Ollama, the transcription and image services, waits for them to be
healthy, then launches the web app on **http://localhost:8080**. Press Ctrl-C to
stop everything cleanly. Service logs go to `/tmp/storytelling-*.log`.

### Or start each piece manually
Open four terminals:

```bash
# 1. LLM
ollama serve

# 2. Transcription service (:8001) — first run installs the venv (large download)
./scripts/run-transcription.sh

# 3. Image service (:8002) — first run downloads SDXL (~7 GB)
./scripts/run-imagegen.sh

# 4. Orchestrator + web UI (:8080)
./gradlew :orchestrator:bootRun
```

Then open **http://localhost:8080**, upload an audio file, label the speakers when
prompted, and watch the chronicle + scenes appear.

> **Supported audio formats:** any format `ffmpeg` can decode — `.m4a`, `.mp3`,
> `.wav`, `.flac`, `.ogg`, etc. (`.m4a` from phone/voice-memo recordings works fine).
> This is why `ffmpeg` is a required prerequisite.

### Why not Docker Compose?
On **macOS / Apple Silicon, Docker can't access the Metal (MPS) GPU**, so the ML
services (WhisperX, SDXL) — and even Ollama — would fall back to CPU and run far
slower. For local dev, **run natively** (`make up`). Docker Compose only makes sense
when deploying to a **Linux host with an NVIDIA GPU** (where `--gpus all` passthrough
works); `docker-compose.yml` is therefore scoped to Ollama as a convenience/reference.


---

## Configuration

### Secrets & config via `.env`
Local configuration and secrets live in a **git-ignored `.env`** file at the repo
root. Both `make` and `./scripts/dev-up.sh` load it automatically, so you never have
to `export` anything by hand or hardcode secrets in the Makefile.

```bash
cp .env.example .env     # create your local copy (one time)
# then edit .env and paste your real token
```

Example `.env`:
```dotenv
# Hugging Face token for pyannote diarization (Read token is enough)
HF_TOKEN=hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Local LLM model served by Ollama (optional override)
LLM_MODEL=llama3.1:8b
```

| Variable | Used by | Purpose |
|----------|---------|---------|
| `HF_TOKEN` | transcription service | Download the gated `pyannote` diarization models. Omit to disable diarization. |
| `LLM_MODEL` | `make`, `dev-up.sh` | Ollama model to pull/serve (e.g. `llama3.1:8b`, `qwen2.5:14b`). |

> 🔒 **Security:** `.env` is in `.gitignore` and must never be committed. Only
> `.env.example` (with placeholder values) is tracked. Don't put real tokens in the
> Makefile or any committed file.
>
> Tip: you can still override per-run on the command line, e.g.
> `LLM_MODEL=qwen2.5:14b make up` or `export HF_TOKEN=...` — explicit shell values
> take precedence.

### Application settings
The app's runtime knobs live in `orchestrator/src/main/resources/application.yml`
(override via env vars / `--args`). Highlights:

| Key | Purpose |
|-----|---------|
| `storytelling.llm.model` | Ollama model name (`llama3.1:8b`, `qwen2.5:14b`, …) |
| `storytelling.transcription.model` | Whisper size (`large-v3`, `medium`, …) |
| `storytelling.transcription.glossary` | DnD names/terms to bias Whisper (reduce mis-hears) |
| `storytelling.image.style-prompt` | Style suffix added to every scene prompt |
| `storytelling.narration.chunk-size-chars` | Map-reduce chunk size for long sessions |

---

## Testing

```bash
make test        # Java unit + integration tests (ML services mocked — fast, hermetic)
make test-asr    # Real ASR regression test: actually runs Whisper on a sample clip
```

- **`make test`** — covers transcript formatting, persistence, the full pipeline
  orchestration, and the web flow (upload → labels → narration → images → image
  serving). The ML models are mocked, so it's fast and needs no GPU/downloads.
- **`make test-asr`** — the real one. It transcribes `orchestrator/src/test/resources/sample.m4a`
  with `faster-whisper` (CTranslate2, no torch/pyannote) and asserts a sensible
  transcript. First run installs a small isolated venv (`services/transcription/.venv-asr`)
  and downloads the model. Tune via env vars:

  | Var | Default | Purpose |
  |-----|---------|---------|
  | `ASR_TEST_MODEL` | `tiny` | Whisper size. `tiny` is a fast smoke check; use `base`/`small`/`large-v3` for accuracy. |
  | `SAMPLE_AUDIO` | repo sample | Path to the audio clip to transcribe. |
  | `EXPECTED_WORDS` | (unset) | Comma-separated words that must appear → turns it into a **content** regression test. |

  ```bash
  # higher-accuracy content regression on your own clip:
  ASR_TEST_MODEL=large-v3 EXPECTED_WORDS="dragon,castle" make test-asr
  ```

  > Note: the `tiny` model is intentionally low quality (it produced garbled output
  > on the Greek sample clip). Bump `ASR_TEST_MODEL` for real accuracy.

---

## Cost & performance notes
- **All local.** Cost is electricity + your time.
- On Apple Silicon, faster-whisper runs on CPU (still fine for offline batches).
  SDXL runs on the Metal (MPS) GPU at roughly 30–60s/image.
- A 3–4h session: transcription is the long pole (can be 1–2× realtime on CPU).
  Consider a smaller Whisper model (`medium`) to trade accuracy for speed.

## Alternative image backend (ComfyUI)
Instead of in-process diffusers, you can run ComfyUI and have `services/imagegen`
forward prompts to its `/prompt` API. Useful for advanced workflows, LoRAs, and
faster startup. (Not wired by default — see `services/imagegen/app.py`.)

## Possible v2 ideas
- An "art-director" agent that critiques and regenerates weak images.
- Character reference images / IP-Adapter for consistent character appearance.
- Export the chronicle + gallery to PDF/EPUB.






