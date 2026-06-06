#!/usr/bin/env bash
#
# Runs the real ASR regression test: actually transcribes the sample clip with
# faster-whisper (tiny model, CPU) and asserts a sensible transcript. Lightweight
# and isolated in its own venv (.venv-asr) so it doesn't touch the service venv.
#
# Optional overrides:
#   ASR_TEST_MODEL=base ./scripts/test-asr.sh        # more accurate, slower
#   EXPECTED_WORDS="dragon,castle" ./scripts/test-asr.sh   # content regression
#   SAMPLE_AUDIO=/path/to/clip.m4a ./scripts/test-asr.sh
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"
cd "$SCRIPT_DIR/../services/transcription"

ensure_venv .venv-asr test-requirements.txt

exec "./.venv-asr/bin/python" -m pytest tests/ -v -s "$@"


