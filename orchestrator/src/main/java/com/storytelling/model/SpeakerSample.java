package com.storytelling.model;

/**
 * A representative audio slice for a diarized speaker, used to let the user
 * preview a voice while labeling. Times are in seconds into the recording.
 */
public record SpeakerSample(String speaker, double start, double end) {
}

