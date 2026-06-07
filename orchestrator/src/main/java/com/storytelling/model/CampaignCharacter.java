package com.storytelling.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A character defined as part of a {@link Campaign}: who plays them and what
 * they look like. The appearance feeds Stable Diffusion for consistent imagery.
 *
 * <p>{@code voiceprints} are enrolled voice embeddings accumulated as the user
 * confirms labels in past sessions; they let the labeling step suggest this
 * character for a matching voice.
 */
public record CampaignCharacter(String name, String player, String appearance, List<float[]> voiceprints) {

    public CampaignCharacter {
        if (voiceprints == null) voiceprints = new ArrayList<>();
    }

    /** Convenience constructor for characters without enrolled voiceprints. */
    public CampaignCharacter(String name, String player, String appearance) {
        this(name, player, appearance, new ArrayList<>());
    }
}


