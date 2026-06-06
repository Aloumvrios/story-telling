#!/usr/bin/env bash
#
# Brings up the whole stack natively (with Apple Metal acceleration) in one command:
#   - Ollama            :11434   (local LLM)
#   - transcription     :8001    (WhisperX)
#   - imagegen          :8002    (SDXL)
#   - orchestrator      :8080    (Spring Boot web UI, runs in the foreground)
#
# Ctrl-C cleanly stops the background services it started.
#
# This is the recommended way to run locally on macOS / Apple Silicon, because the
# ML services need the Metal (MPS) GPU, which Docker on macOS cannot provide.
#
# Usage:
#   export HF_TOKEN=hf_...        # needed for pyannote diarization (free, gated model)
#   ./scripts/dev-up.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib.sh
. "$ROOT/scripts/lib.sh"

# Load secrets/config from a git-ignored .env if present (HF_TOKEN, LLM_MODEL, ...).
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

PIDS=()
STARTED_OLLAMA=0

LLM_MODEL="${LLM_MODEL:-llama3.1:8b}"

log()  { printf "\033[1;33m[dev-up]\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m[dev-up] %s\033[0m\n" "$*" >&2; }

cleanup() {
  log "shutting down..."
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  [ "$STARTED_OLLAMA" = "1" ] && { kill "$OLLAMA_PID" 2>/dev/null || true; }
  wait 2>/dev/null || true
  log "bye 👋"
}
trap cleanup EXIT INT TERM

wait_for() { # name url
  local name="$1" url="$2" tries=60
  printf "\033[1;33m[dev-up]\033[0m waiting for %s" "$name"
  until curl -sf "$url" >/dev/null 2>&1; do
    tries=$((tries - 1))
    [ "$tries" -le 0 ] && { echo; fail "$name did not become ready at $url"; exit 1; }
    printf "."
    sleep 2
  done
  echo " ✓"
}

# --- 1. Ollama -------------------------------------------------------------
if ! command -v ollama >/dev/null 2>&1; then
  fail "ollama not found. Install with: brew install ollama"; exit 1
fi
if curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then
  log "ollama already running"
else
  log "starting ollama..."
  ollama serve >/tmp/storytelling-ollama.log 2>&1 &
  OLLAMA_PID=$!
  STARTED_OLLAMA=1
  wait_for "ollama" "http://localhost:11434/api/tags"
fi
log "ensuring model '$LLM_MODEL' is available (first pull may take a while)..."
ollama pull "$LLM_MODEL"

# --- Pre-build the Python venvs in the foreground (visible progress) so the
#     health checks below don't race the multi-GB first-run downloads. ---
log "preparing Python service environments (first run downloads several GB)..."
ensure_venv "$ROOT/services/transcription/.venv" "$ROOT/services/transcription/requirements.txt"
ensure_venv "$ROOT/services/imagegen/.venv"      "$ROOT/services/imagegen/requirements.txt"

# --- 2. Transcription service ---------------------------------------------
log "starting transcription service (:8001)..."
"$ROOT/scripts/run-transcription.sh" >/tmp/storytelling-transcription.log 2>&1 &
PIDS+=($!)

# --- 3. Image service ------------------------------------------------------
log "starting imagegen service (:8002)..."
"$ROOT/scripts/run-imagegen.sh" >/tmp/storytelling-imagegen.log 2>&1 &
PIDS+=($!)

# First runs install venvs / download models — give them generous time.
wait_for "transcription" "http://localhost:8001/health"
wait_for "imagegen"      "http://localhost:8002/health"

# --- 4. Orchestrator (foreground) -----------------------------------------
log "all services healthy — starting the web app on http://localhost:8080"
log "service logs: /tmp/storytelling-*.log"
cd "$ROOT"
exec ./gradlew :orchestrator:bootRun --console=plain




