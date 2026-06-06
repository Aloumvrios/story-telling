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

    private static String displayName(SpeakerLabel l) {
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

