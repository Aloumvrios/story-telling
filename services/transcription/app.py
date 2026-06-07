"""
Transcription + diarization service.

POST /transcribe  (multipart: file, model, glossary)
  -> { "language": "en", "segments": [ {start, end, speaker, text}, ... ] }

Runs fully locally. Uses faster-whisper (CTranslate2) for ASR and pyannote.audio for
speaker diarization — both have prebuilt wheels for CPython 3.10–3.12. (The older
whisperx stack pinned an `av` version that won't build against modern ffmpeg.)

On Apple Silicon, faster-whisper runs on CPU (CTranslate2 has no Metal backend yet)
but is well suited to offline batch processing.

Diarization uses pyannote, which requires a (free) Hugging Face token with access to
the gated `pyannote/speaker-diarization-3.1` model. Set HF_TOKEN in the environment.
"""
import os
import tempfile

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

app = FastAPI(title="transcription-service")

# Lazy globals so the (heavy) models load once, on first request.
_model = None
_diarizer = None
_embedder = None

# Voice fingerprints: when enabled, return a mean embedding per speaker so the
# orchestrator can recognise the same voice across sessions. Needs HF_TOKEN.
EMBEDDINGS_ENABLED = os.environ.get("ASR_EMBEDDINGS", "0").strip() in ("1", "true", "yes")
EMBEDDING_MODEL = os.environ.get("ASR_EMBEDDING_MODEL", "pyannote/embedding")


def _device_and_compute():
    # CPU + int8 is the reliable path on macOS/Apple Silicon.
    return "cpu", "int8"


def _get_model(model_name: str):
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        device, compute_type = _device_and_compute()
        _model = WhisperModel(model_name, device=device, compute_type=compute_type)
    return _model


def _get_diarizer():
    global _diarizer
    if _diarizer is None:
        from pyannote.audio import Pipeline
        token = os.environ.get("HF_TOKEN")
        _diarizer = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1", use_auth_token=token
        )
    return _diarizer


def _get_embedder():
    """Lazy pyannote embedding model (ECAPA-style). Returns None if unavailable."""
    global _embedder
    if _embedder is None:
        try:
            from pyannote.audio import Inference
            token = os.environ.get("HF_TOKEN")
            # window="whole" -> one fixed-size embedding for the whole cropped region.
            _embedder = Inference(EMBEDDING_MODEL, window="whole", use_auth_token=token)
        except Exception as e:  # noqa: BLE001 - embeddings are optional
            print(f"[warn] voice embedding model unavailable: {e}")
            _embedder = False  # sentinel: tried and failed
    return _embedder or None


def _speaker_embeddings(audio_path: str, turns) -> dict:
    """
    Mean L2-normalised embedding per speaker, computed over that speaker's
    diarization turns. Best-effort: returns {} on any failure or when disabled.
    """
    if not EMBEDDINGS_ENABLED or not turns:
        return {}
    embedder = _get_embedder()
    if embedder is None:
        return {}
    try:
        import numpy as np
        from pyannote.core import Segment

        sums: dict = {}
        counts: dict = {}
        for t_start, t_end, speaker in turns:
            if t_end - t_start < 0.5:  # skip very short turns (noisy embeddings)
                continue
            try:
                vec = embedder.crop(audio_path, Segment(t_start, t_end))
            except Exception:
                continue
            vec = np.asarray(vec, dtype="float32").reshape(-1)
            sums[speaker] = vec if speaker not in sums else sums[speaker] + vec
            counts[speaker] = counts.get(speaker, 0) + 1

        result = {}
        for speaker, total in sums.items():
            mean = total / max(1, counts[speaker])
            norm = float(np.linalg.norm(mean))
            if norm > 0:
                mean = mean / norm  # L2-normalise so cosine == dot product
            result[speaker] = [float(x) for x in mean.tolist()]
        return result
    except Exception as e:  # noqa: BLE001
        print(f"[warn] failed to compute speaker embeddings: {e}")
        return {}


def _assign_speaker(start: float, end: float, turns) -> str:
    """Pick the diarization turn that overlaps this segment the most."""
    best, best_overlap = "SPEAKER_00", 0.0
    for t_start, t_end, speaker in turns:
        overlap = min(end, t_end) - max(start, t_start)
        if overlap > best_overlap:
            best_overlap, best = overlap, speaker
    return best


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form("large-v3"),
    glossary: str = Form(""),
):
    suffix = os.path.splitext(file.filename or "audio.wav")[1] or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        audio_path = tmp.name

    try:
        # 1) transcribe (glossary biases recognition of names/jargon)
        segments_gen, info = _get_model(model).transcribe(
            audio_path,
            beam_size=5,
            vad_filter=True,
            initial_prompt=glossary or None,
        )
        segments = [
            {"start": float(s.start), "end": float(s.end), "text": (s.text or "").strip()}
            for s in segments_gen
        ]

        # 2) diarize + assign each segment its dominant speaker (best-effort).
        #    Skip entirely when no HF token is configured, otherwise pyannote would
        #    retry the gated-model download for minutes before failing.
        turns = []
        if os.environ.get("HF_TOKEN"):
            try:
                diarization = _get_diarizer()(audio_path)
                for turn, _, speaker in diarization.itertracks(yield_label=True):
                    turns.append((turn.start, turn.end, speaker))
            except Exception as e:  # diarization is optional; degrade gracefully
                print(f"[warn] diarization unavailable: {e}")
        else:
            print("[info] HF_TOKEN not set — skipping diarization (single speaker).")

        for seg in segments:
            seg["speaker"] = _assign_speaker(seg["start"], seg["end"], turns) if turns else "SPEAKER_00"

        # 3) optional voice fingerprints (mean embedding per speaker)
        speaker_embeddings = _speaker_embeddings(audio_path, turns)

        return JSONResponse({
            "language": info.language,
            "segments": segments,
            "speakerEmbeddings": speaker_embeddings,
        })
    finally:
        os.unlink(audio_path)





