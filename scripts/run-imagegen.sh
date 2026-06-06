#!/usr/bin/env bash
# Starts the SDXL image-gen service on :8002
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"
load_env "$SCRIPT_DIR/.."

cd "$SCRIPT_DIR/../services/imagegen"
ensure_venv .venv requirements.txt

# Prefer a locally pre-downloaded SDXL folder if present (loads from disk, no
# network). Falls back to the Hugging Face repo id otherwise. Override by
# exporting SDXL_MODEL yourself before running.
LOCAL_SDXL="${SDXL_LOCAL_DIR:-$HOME/sdxl-base}"
if [ -z "${SDXL_MODEL:-}" ] && [ -f "$LOCAL_SDXL/model_index.json" ]; then
  export SDXL_MODEL="$LOCAL_SDXL"
  echo "[imagegen] using local SDXL model at $SDXL_MODEL"
fi

exec ./.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8002




