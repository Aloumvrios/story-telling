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


def _patch_torchaudio_for_pyannote():
    """
    pyannote.audio 3.x imports/annotates ``torchaudio.AudioMetaData``.
    Newer torchaudio releases no longer expose that class at the top level,
    which makes diarization fail before it even starts:

        module 'torchaudio' has no attribute 'AudioMetaData'

    We only need the symbol to exist for pyannote's type annotations/imports, so
    a small NamedTuple-compatible shim is enough and keeps us compatible with
    both older and newer torchaudio versions.
    """
    try:
        import torchaudio
        from typing import NamedTuple

        if not hasattr(torchaudio, "AudioMetaData"):
            class AudioMetaData(NamedTuple):
                sample_rate: int
                num_frames: int
                num_channels: int
                bits_per_sample: int
                encoding: str

            torchaudio.AudioMetaData = AudioMetaData

        # torchaudio 2.9+ removed the old backend-management helpers. pyannote
        # only calls list_audio_backends() during setup/logging; returning a
        # plausible backend list is enough for newer torchaudio where dispatching
        # is always enabled internally.
        if not hasattr(torchaudio, "list_audio_backends"):
            torchaudio.list_audio_backends = lambda: ["ffmpeg", "soundfile"]
        if not hasattr(torchaudio, "get_audio_backend"):
            torchaudio.get_audio_backend = lambda: None
        if not hasattr(torchaudio, "set_audio_backend"):
            torchaudio.set_audio_backend = lambda backend: None

        # torchaudio 2.11 also removed torchaudio.info(), while pyannote still
        # uses it to learn duration/sample-rate before cropping. Implement the
        # small subset pyannote needs via ffprobe (ffmpeg is already required by
        # this service for m4a/mp3/wav decoding).
        if not hasattr(torchaudio, "info"):
            def info_compat(src, *args, **kwargs):
                import json
                import subprocess
                path = src["audio"] if isinstance(src, dict) else src
                proc = subprocess.run(
                    [
                        "ffprobe", "-v", "error", "-select_streams", "a:0",
                        "-show_entries", "stream=sample_rate,channels,bits_per_sample,duration",
                        "-of", "json", str(path),
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                streams = json.loads(proc.stdout or "{}").get("streams", [])
                stream = streams[0] if streams else {}
                sample_rate = int(stream.get("sample_rate") or 0)
                channels = int(stream.get("channels") or 0)
                duration = float(stream.get("duration") or 0.0)
                bits = int(stream.get("bits_per_sample") or 0)
                frames = int(round(duration * sample_rate)) if sample_rate and duration else 0
                return torchaudio.AudioMetaData(
                    sample_rate=sample_rate,
                    num_frames=frames,
                    num_channels=channels,
                    bits_per_sample=bits,
                    encoding="UNKNOWN",
                )

            torchaudio.info = info_compat
    except Exception as e:  # noqa: BLE001 - diarization handles import failures later
        print(f"[warn] could not patch torchaudio for pyannote: {e}")


def _patch_huggingface_hub_for_pyannote():
    """
    pyannote.audio 3.x still calls ``hf_hub_download(..., use_auth_token=...)``.
    Newer huggingface_hub versions renamed that argument to ``token``. Patch the
    function in-place so pyannote works with both API versions.
    """
    try:
        import inspect
        import huggingface_hub

        original = huggingface_hub.hf_hub_download
        signature = inspect.signature(original)
        if "use_auth_token" in signature.parameters:
            return
        if getattr(original, "_storytelling_accepts_use_auth_token", False):
            return

        def hf_hub_download_compat(*args, **kwargs):
            if "use_auth_token" in kwargs and "token" not in kwargs:
                kwargs["token"] = kwargs.pop("use_auth_token")
            else:
                kwargs.pop("use_auth_token", None)
            return original(*args, **kwargs)

        hf_hub_download_compat._storytelling_accepts_use_auth_token = True
        huggingface_hub.hf_hub_download = hf_hub_download_compat

        # Some libraries import from this submodule directly.
        try:
            import huggingface_hub.file_download as file_download
            file_download.hf_hub_download = hf_hub_download_compat
        except Exception:
            pass
    except Exception as e:  # noqa: BLE001 - diarization handles import failures later
        print(f"[warn] could not patch huggingface_hub for pyannote: {e}")


def _patch_torch_load_for_pyannote():
    """
    PyTorch 2.6 changed ``torch.load`` to default to ``weights_only=True``.
    Some trusted pyannote checkpoints contain small metadata objects (for example
    ``torch.torch_version.TorchVersion``), so loading them with the new default
    fails before diarization can run. In this local/offline app we load pyannote
    model files from Hugging Face's official gated repos, so force the historical
    behavior for pyannote compatibility.
    """
    try:
        import inspect
        import torch

        # If pyannote/pytorch-lightning reaches a torch.load path that still uses
        # weights_only=True internally, allowlist the metadata class mentioned by
        # PyTorch's own error message. This is safe for the official pyannote
        # checkpoints used by this local app.
        try:
            torch.serialization.add_safe_globals([torch.torch_version.TorchVersion])
        except Exception:
            pass

        original = torch.load
        if getattr(original, "_storytelling_defaults_weights_only_false", False):
            return
        if "weights_only" not in inspect.signature(original).parameters:
            return

        def torch_load_compat(*args, **kwargs):
            # PyTorch Lightning passes weights_only=True explicitly in recent
            # versions; pyannote 3.x checkpoints require the historical behavior.
            kwargs["weights_only"] = False
            return original(*args, **kwargs)

        torch_load_compat._storytelling_defaults_weights_only_false = True
        torch.load = torch_load_compat
    except Exception as e:  # noqa: BLE001 - diarization handles load failures later
        print(f"[warn] could not patch torch.load for pyannote: {e}")


def _patch_dependencies_for_pyannote():
    _patch_torchaudio_for_pyannote()
    _patch_huggingface_hub_for_pyannote()
    _patch_torch_load_for_pyannote()


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
        _patch_dependencies_for_pyannote()
        from pyannote.audio import Pipeline
        token = os.environ.get("HF_TOKEN")
        _diarizer = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1", use_auth_token=token
        )
        # With newer torchaudio/TorchCodec, crops close to boundaries can be a
        # few samples shorter than requested even in mode="pad". pyannote stacks
        # embedding crops with torch.vstack, which then fails if a batch contains
        # unequal lengths. Batch size 1 avoids stacking unequal crop lengths while
        # keeping diarization correct (slower, but reliable on a laptop).
        if hasattr(_diarizer, "embedding_batch_size"):
            _diarizer.embedding_batch_size = 1
    return _diarizer


def _get_embedder():
    """Lazy pyannote embedding model (ECAPA-style). Returns None if unavailable."""
    global _embedder
    if _embedder is None:
        try:
            _patch_dependencies_for_pyannote()
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
                speakers = sorted({speaker for _, _, speaker in turns})
                print(f"[info] diarization produced {len(turns)} turns across {len(speakers)} speaker(s): {speakers}")
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





