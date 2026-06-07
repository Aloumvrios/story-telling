package com.storytelling.model;

import java.util.List;
import java.util.Map;

/**
 * Raw result returned by the transcription service.
 *
 * <p>{@code speakerEmbeddings} is optional: when the ASR service is configured to
 * extract voice fingerprints, it maps each raw diarization label (e.g.
 * "SPEAKER_00") to an embedding vector used to recognise the same voice across
 * sessions. Null/absent when embeddings are disabled.
 */
public record TranscriptResult(String language,
                               List<SpeakerSegment> segments,
                               Map<String, float[]> speakerEmbeddings) {

    /** Convenience constructor for callers that don't deal with embeddings. */
    public TranscriptResult(String language, List<SpeakerSegment> segments) {
        this(language, segments, null);
    }
}


