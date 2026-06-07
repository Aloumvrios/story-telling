package com.storytelling.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.storytelling.model.CampaignCharacter;

class VoiceMatcherTest {

    @Test
    void cosine_identicalVectorsIsOne_orthogonalIsZero() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        assertThat(VoiceMatcher.cosine(a, a)).isCloseTo(1.0, within(1e-9));
        assertThat(VoiceMatcher.cosine(a, b)).isCloseTo(0.0, within(1e-9));
        assertThat(VoiceMatcher.cosine(a, null)).isZero();
        assertThat(VoiceMatcher.cosine(a, new float[]{1, 0})).isZero(); // length mismatch
    }

    @Test
    void suggest_mapsLabelToNearestCharacterAboveThreshold() {
        CampaignCharacter lyra = new CampaignCharacter("Lyra", "Alice", "elf",
                List.of(new float[]{1, 0, 0}));
        CampaignCharacter thrain = new CampaignCharacter("Thrain", "Bob", "dwarf",
                List.of(new float[]{0, 1, 0}));

        Map<String, float[]> embeddings = Map.of(
                "SPEAKER_00", new float[]{0.95f, 0.05f, 0},   // close to Lyra
                "SPEAKER_01", new float[]{0.02f, 0.98f, 0});  // close to Thrain

        Map<String, String> suggestions = VoiceMatcher.suggest(embeddings, List.of(lyra, thrain), 0.6);

        assertThat(suggestions).containsEntry("SPEAKER_00", "Lyra");
        assertThat(suggestions).containsEntry("SPEAKER_01", "Thrain");
    }

    @Test
    void suggest_skipsMatchesBelowThreshold() {
        CampaignCharacter lyra = new CampaignCharacter("Lyra", "Alice", "elf",
                List.of(new float[]{1, 0, 0}));
        Map<String, float[]> embeddings = Map.of("SPEAKER_00", new float[]{0, 1, 0}); // orthogonal

        assertThat(VoiceMatcher.suggest(embeddings, List.of(lyra), 0.6)).isEmpty();
    }

    @Test
    void suggest_assignsEachCharacterToAtMostOneLabel() {
        CampaignCharacter lyra = new CampaignCharacter("Lyra", "Alice", "elf",
                List.of(new float[]{1, 0, 0}));
        // Two voices both closest to Lyra; only the strongest match wins her.
        Map<String, float[]> embeddings = Map.of(
                "SPEAKER_00", new float[]{0.99f, 0.01f, 0},
                "SPEAKER_01", new float[]{0.80f, 0.20f, 0});

        Map<String, String> suggestions = VoiceMatcher.suggest(embeddings, List.of(lyra), 0.6);

        assertThat(suggestions).containsOnlyKeys("SPEAKER_00");
        assertThat(suggestions.get("SPEAKER_00")).isEqualTo("Lyra");
    }
}

