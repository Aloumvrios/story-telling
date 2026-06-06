package com.storytelling.model;

import java.util.List;

/** Raw result returned by the transcription service. */
public record TranscriptResult(String language, List<SpeakerSegment> segments) {
}

