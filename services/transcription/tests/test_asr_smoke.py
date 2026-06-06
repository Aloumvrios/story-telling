"""
Real ASR regression test.

Unlike the Java integration test (which mocks the ML services), this one actually
runs Whisper on a real audio clip and asserts a sensible transcript comes back.
It uses faster-whisper directly with a tiny model so it stays lightweight and has
no torch / pyannote / Hugging-Face-token requirements.

Configure via environment variables:
  SAMPLE_AUDIO    path to the audio file (default: the repo's sample.m4a)
  ASR_TEST_MODEL  whisper model size (default: tiny; try base/small for accuracy)
  EXPECTED_WORDS  optional comma-separated words that MUST appear in the transcript
                  (e.g. EXPECTED_WORDS="dragon,castle") — turns this into a content
                  regression test instead of just a "non-empty" smoke test.

Run with:  ./scripts/test-asr.sh
"""
import os
from pathlib import Path

import pytest

# Skip the whole module cleanly if faster-whisper isn't installed.
pytest.importorskip("faster_whisper", reason="faster-whisper not installed; run ./scripts/test-asr.sh")

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SAMPLE = REPO_ROOT / "orchestrator" / "src" / "test" / "resources" / "sample.m4a"

SAMPLE = Path(os.environ.get("SAMPLE_AUDIO", str(DEFAULT_SAMPLE)))
MODEL = os.environ.get("ASR_TEST_MODEL", "tiny")
EXPECTED_WORDS = [w.strip().lower() for w in os.environ.get("EXPECTED_WORDS", "").split(",") if w.strip()]


@pytest.fixture(scope="module")
def transcript() -> tuple[str, str]:
    """Transcribe the sample once and reuse across assertions. Returns (text, language)."""
    if not SAMPLE.exists():
        pytest.skip(f"sample audio not found: {SAMPLE}")

    from faster_whisper import WhisperModel

    model = WhisperModel(MODEL, device="cpu", compute_type="int8")
    segments, info = model.transcribe(str(SAMPLE), beam_size=1)
    text = " ".join(seg.text for seg in segments).strip()
    print(f"\n[asr] model={MODEL} language={info.language} duration={info.duration:.1f}s")
    print(f"[asr] transcript: {text!r}")
    return text, info.language


def test_transcription_is_not_empty(transcript):
    text, _ = transcript
    assert text, "ASR returned an empty transcript"


def test_transcription_has_several_words(transcript):
    text, _ = transcript
    assert len(text.split()) >= 3, f"expected real speech, got too few words: {text!r}"


def test_transcription_contains_expected_words(transcript):
    if not EXPECTED_WORDS:
        pytest.skip("set EXPECTED_WORDS to assert specific content")
    text, _ = transcript
    lowered = text.lower()
    missing = [w for w in EXPECTED_WORDS if w not in lowered]
    assert not missing, f"expected words {missing} not found in transcript: {text!r}"

