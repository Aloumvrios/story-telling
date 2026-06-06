package com.storytelling.model;

/**
 * One diarized utterance from WhisperX: a time-bounded chunk of text spoken by a speaker.
 * `speaker` is a raw diarization label such as "SPEAKER_00" until the user maps it.
 */
public record SpeakerSegment(double start, double end, String speaker, String text) {
}

