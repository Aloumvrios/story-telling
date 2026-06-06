#!/usr/bin/env bash
# Shared helpers for the Python service launchers.
#
# The ML stack (PyTorch, whisperx, tokenizers, av, ctranslate2) ships prebuilt
# wheels only for CPython 3.10–3.12. On 3.13/3.14 pip tries to compile from source
# and fails. So we deliberately pick a compatible interpreter instead of whatever
# `python3` happens to be.

# Print the path to a compatible python, or nothing if none is found.
pick_python() {
  if [ -n "${PYTHON:-}" ] && command -v "$PYTHON" >/dev/null 2>&1; then
    command -v "$PYTHON"; return
  fi
  for c in python3.12 python3.11 python3.10; do
    if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
  done
  echo ""
}

# Print a compatible python path or exit with a helpful message.
require_python() {
  local py; py="$(pick_python)"
  if [ -z "$py" ]; then
    echo "ERROR: need Python 3.10–3.12, but none was found." >&2
    echo "       Default python3 is $(python3 --version 2>&1 | awk '{print $2}'), which has no" >&2
    echo "       prebuilt wheels for PyTorch/whisperx/tokenizers." >&2
    echo "       Fix:  brew install python@3.12   (or set PYTHON=/path/to/python3.12)" >&2
    exit 1
  fi
  echo "$py"
}

# Load KEY=VALUE pairs from a git-ignored .env at the repo root, if present.
# <repo-root> is the directory that contains the .env file.
load_env() {
  local root="$1"
  if [ -f "$root/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$root/.env"
    set +a
  fi
}

# ensure_venv <venv-dir> <requirements-file>
# (Re)builds the venv if it isn't fully installed. Uses a '.installed' sentinel so
# a previously *failed/partial* install is retried instead of silently skipped.
ensure_venv() {
  local venv="$1" reqs="$2" py
  if [ -f "$venv/.installed" ]; then
    return 0
  fi
  py="$(require_python)"
  echo "[setup] building venv '$venv' with $("$py" --version 2>&1) (first run can download several GB)..."
  rm -rf "$venv"
  "$py" -m venv "$venv"
  "$venv/bin/pip" install --upgrade pip
  "$venv/bin/pip" install -r "$reqs"
  touch "$venv/.installed"
}



