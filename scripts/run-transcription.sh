#!/usr/bin/env bash
# Starts the WhisperX transcription service on :8001
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"
load_env "$SCRIPT_DIR/.."

cd "$SCRIPT_DIR/../services/transcription"
ensure_venv .venv requirements.txt

# HF_TOKEN is needed for pyannote diarization (free, gated model).
export HF_TOKEN="${HF_TOKEN:-}"
exec ./.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8001



