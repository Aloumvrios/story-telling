# Convenience targets. On macOS/Apple Silicon, prefer native (Metal) over Docker.

# Load secrets/config from a git-ignored .env if present (e.g. HF_TOKEN=hf_...).
# Copy .env.example to .env and put your token there. Never commit .env.
-include .env
export

LLM_MODEL ?= llama3.1:8b

.PHONY: help up down status app transcription imagegen ollama test test-asr setup compose-ollama

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS=":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

up: ## Start the whole stack natively (recommended on macOS)
	./scripts/dev-up.sh

down: ## Stop the app + Python services started locally (leaves Ollama running)
	@echo "stopping orchestrator, transcription and imagegen..."
	-@pkill -f "StoryTellingApplication" 2>/dev/null || true
	-@pkill -f "orchestrator:bootRun" 2>/dev/null || true
	-@pkill -f "uvicorn app:app" 2>/dev/null || true
	-@pkill -f "run-transcription.sh" 2>/dev/null || true
	-@pkill -f "run-imagegen.sh" 2>/dev/null || true
	-@pkill -f "scripts/dev-up.sh" 2>/dev/null || true
	@echo "done. (Ollama left running — stop with: pkill -f 'ollama serve')"

status: ## Show which services are up (the native equivalent of 'docker ps')
	@printf "%-16s %-8s %-10s %s\n" SERVICE PORT STATUS PID
	@for svc in "orchestrator 8080 /" "transcription 8001 /health" "imagegen 8002 /health" "ollama 11434 /api/tags"; do \
	  set -- $$svc; name=$$1; port=$$2; path=$$3; \
	  pid=$$(lsof -ti tcp:$$port -sTCP:LISTEN 2>/dev/null | head -1); \
	  if curl -sf "http://localhost:$$port$$path" >/dev/null 2>&1; then st="UP"; \
	  elif [ -n "$$pid" ]; then st="starting"; else st="down"; fi; \
	  printf "%-16s %-8s %-10s %s\n" "$$name" "$$port" "$$st" "$${pid:-—}"; \
	done

app: ## Run only the Java web app (assumes services already up)
	./gradlew :orchestrator:bootRun --console=plain

transcription: ## Run only the transcription service (:8001)
	./scripts/run-transcription.sh

imagegen: ## Run only the SDXL image service (:8002)
	./scripts/run-imagegen.sh

ollama: ## Start ollama and pull the model
	ollama serve & sleep 2 && ollama pull $(LLM_MODEL)

test: ## Run the test suite
	./gradlew :orchestrator:test --console=plain

test-asr: ## Run the real ASR regression test (faster-whisper on the sample clip)
	./scripts/test-asr.sh

setup: ## Build the app and pre-create the Python venvs (uses a compatible Python 3.12)
	./gradlew :orchestrator:classes
	bash -c '. scripts/lib.sh && ensure_venv services/transcription/.venv services/transcription/requirements.txt'
	bash -c '. scripts/lib.sh && ensure_venv services/imagegen/.venv services/imagegen/requirements.txt'

compose-ollama: ## (Optional) run Ollama via docker-compose instead of natively
	docker compose up ollama




