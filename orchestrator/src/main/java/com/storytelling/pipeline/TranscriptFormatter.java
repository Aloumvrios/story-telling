package com.storytelling.pipeline;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.storytelling.config.AppProperties;
import com.storytelling.model.SpeakerLabel;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.TranscriptResult;

/**
 * Pure helpers for turning a diarized transcript into LLM-ready text:
 * applies speaker labels and chunks the (very long) transcript for map-reduce.
 */
public final class TranscriptFormatter {

    /** Display name used for any voice flagged as the Dungeon Master. */
    public static final String DM_NAME = "Dungeon Master";

    private TranscriptFormatter() {
    }

    /** Renders the transcript with human-readable speaker/character names applied. */
    public static String render(TranscriptResult transcript, List<SpeakerLabel> labels) {
        Map<String, String> names = labels.stream()
                .collect(Collectors.toMap(SpeakerLabel::rawLabel, TranscriptFormatter::displayName, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        for (SpeakerSegment seg : transcript.segments()) {
            String name = names.getOrDefault(seg.speaker(), seg.speaker());
            sb.append(name).append(": ").append(seg.text().strip()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Builds a guidance note (or empty string) telling the narration LLM which
     * lines belong to the Dungeon Master, so the DM is treated as the narrator
     * and the many NPCs/environment rather than a single player character.
     */
    public static String dmNote(List<SpeakerLabel> labels) {
        boolean hasDm = labels != null && labels.stream().anyMatch(SpeakerLabel::dungeonMaster);
        if (!hasDm) return "";
        return "NOTE: Lines attributed to \"" + DM_NAME + "\" are spoken by the Dungeon Master "
                + "(the narrator/referee), not a player character. The Dungeon Master voices "
                + "every non-player character (NPCs) — tavern keepers, merchants, monsters, gods — "
                + "and narrates the world, scenery and outcomes. Treat each such line as narration "
                + "or as whichever NPC, creature or environment the context implies; never as a "
                + "single recurring hero.";
    }

    /**
     * Lower-cased aliases that must never appear in a scene's character list because
     * they refer to the Dungeon Master (the narrator/NPCs), not a being to illustrate.
     * Always includes the generic terms; adds the player/character/raw names of any
     * speaker flagged as the DM.
     */
    public static java.util.Set<String> dmAliases(List<SpeakerLabel> labels) {
        java.util.Set<String> aliases = new java.util.HashSet<>();
        aliases.add(DM_NAME.toLowerCase());
        aliases.add("dm");
        aliases.add("the dungeon master");
        aliases.add("game master");
        aliases.add("gm");
        aliases.add("narrator");
        if (labels == null) return aliases;
        for (SpeakerLabel l : labels) {
            if (!l.dungeonMaster()) continue;
            if (l.character() != null && !l.character().isBlank()) aliases.add(l.character().strip().toLowerCase());
            if (l.player() != null && !l.player().isBlank()) aliases.add(l.player().strip().toLowerCase());
            if (l.rawLabel() != null && !l.rawLabel().isBlank()) aliases.add(l.rawLabel().strip().toLowerCase());
        }
        return aliases;
    }

    private static String displayName(SpeakerLabel l) {
        if (l.dungeonMaster()) return DM_NAME;
        if (l.character() != null && !l.character().isBlank()) return l.character();
        if (l.player() != null && !l.player().isBlank()) return l.player();
        return l.rawLabel();
    }

    /** Splits text into overlapping chunks, breaking on line boundaries where possible. */
    public static List<String> chunk(String text, AppProperties.Narration cfg) {
        int size = cfg.getChunkSizeChars();
        int overlap = cfg.getChunkOverlapChars();
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        int pos = 0;
        int len = text.length();
        while (pos < len) {
            int end = Math.min(pos + size, len);
            if (end < len) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > pos) end = nl;
            }
            chunks.add(text.substring(pos, end));
            if (end >= len) break;
            pos = Math.max(end - overlap, pos + 1);
        }
        return chunks;
    }
}

