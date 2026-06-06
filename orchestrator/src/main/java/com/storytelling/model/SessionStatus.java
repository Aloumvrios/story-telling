package com.storytelling.model;

/**
 * Lifecycle of a session as it moves through the pipeline. Persisted so jobs are resumable.
 */
public enum SessionStatus {
    CREATED,            // audio uploaded, nothing processed yet
    TRANSCRIBING,
    AWAITING_LABELS,    // transcript ready; waiting for the user to name speakers/characters
    NARRATING,          // building third-person narration (map-reduce)
    SEGMENTING,         // splitting narration into scenes + image prompts
    GENERATING_IMAGES,
    COMPLETED,
    FAILED
}

