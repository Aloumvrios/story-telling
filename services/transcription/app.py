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

        return JSONResponse({"language": info.language, "segments": segments})
    finally:
        os.unlink(audio_path)



