package com.storytelling.model;

/**
 * User-provided mapping from a raw diarization label to a player and/or character name.
 * e.g. rawLabel="SPEAKER_00", player="Alice", character="Lyra the Rogue".
 *
 * <p>{@code dungeonMaster} marks the voice of the DM (the narrator/referee). The DM
 * is not a single character: they voice every NPC (tavern keepers, merchants,
 * monsters, gods) and describe the environment. The narration LLM is told to treat
 * these lines as narration / NPC dialogue, never as a recurring player hero.
 */
public record SpeakerLabel(String rawLabel, String player, String character, boolean dungeonMaster) {

    /** Backward-compatible constructor for non-DM speakers. */
    public SpeakerLabel(String rawLabel, String player, String character) {
        this(rawLabel, player, character, false);
    }
}

