package com.storytelling.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.storytelling.model.CampaignCharacter;

/**
 * Pure helpers for matching diarized speaker embeddings to enrolled campaign
 * character voiceprints via cosine similarity. No external dependencies, so this
 * is fully unit-testable.
 */
public final class VoiceMatcher {

    private VoiceMatcher() {
    }

    /** Cosine similarity in [-1, 1]; 0 when either vector is null/empty/zero. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Best similarity of {@code embedding} against any of a character's voiceprints. */
    public static double bestSimilarity(float[] embedding, CampaignCharacter character) {
        double best = 0.0;
        if (character == null || character.voiceprints() == null) return best;
        for (float[] vp : character.voiceprints()) {
            best = Math.max(best, cosine(embedding, vp));
        }
        return best;
    }

    /**
     * Suggests a character name for each raw speaker label. A label is mapped to
     * the best-scoring character whose similarity is at least {@code threshold}.
     * Each character is assigned to at most one label (greedy, highest score first).
     *
     * @return rawLabel -> suggested character name (only above-threshold matches)
     */
    public static Map<String, String> suggest(Map<String, float[]> speakerEmbeddings,
                                              List<CampaignCharacter> characters,
                                              double threshold) {
        Map<String, String> result = new LinkedHashMap<>();
        if (speakerEmbeddings == null || speakerEmbeddings.isEmpty()
                || characters == null || characters.isEmpty()) {
            return result;
        }

        // Score every (label, character) pair, then assign greedily by descending score.
        record Pair(String label, String character, double score) {
        }
        List<Pair> pairs = new java.util.ArrayList<>();
        for (var e : speakerEmbeddings.entrySet()) {
            for (CampaignCharacter c : characters) {
                double s = bestSimilarity(e.getValue(), c);
                if (s >= threshold) pairs.add(new Pair(e.getKey(), c.name(), s));
            }
        }
        pairs.sort((p1, p2) -> Double.compare(p2.score(), p1.score()));

        java.util.Set<String> usedCharacters = new java.util.HashSet<>();
        for (Pair p : pairs) {
            if (result.containsKey(p.label()) || usedCharacters.contains(p.character())) continue;
            result.put(p.label(), p.character());
            usedCharacters.add(p.character());
        }
        return result;
    }
}

