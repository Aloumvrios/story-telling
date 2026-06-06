package com.storytelling.model;

/**
 * User-provided mapping from a raw diarization label to a player and/or character name.
 * e.g. rawLabel="SPEAKER_00", player="Alice", character="Lyra the Rogue".
 */
public record SpeakerLabel(String rawLabel, String player, String character) {
}

