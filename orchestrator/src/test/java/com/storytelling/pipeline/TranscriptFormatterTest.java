package com.storytelling.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.storytelling.config.AppProperties;
import com.storytelling.model.SpeakerLabel;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.TranscriptResult;

class TranscriptFormatterTest {

    @Test
    void render_appliesCharacterThenPlayerThenRawLabel() {
        TranscriptResult transcript = new TranscriptResult("en", List.of(
                new SpeakerSegment(0, 1, "SPEAKER_00", "  hello  "),
                new SpeakerSegment(1, 2, "SPEAKER_01", "world"),
                new SpeakerSegment(2, 3, "SPEAKER_99", "unmapped")));

        List<SpeakerLabel> labels = List.of(
                new SpeakerLabel("SPEAKER_00", "Alice", "Lyra"),   // character wins
                new SpeakerLabel("SPEAKER_01", "Bob", ""));        // falls back to player

        String rendered = TranscriptFormatter.render(transcript, labels);

        assertThat(rendered).isEqualTo("""
                Lyra: hello
                Bob: world
                SPEAKER_99: unmapped
                """);
    }

    @Test
    void render_labelsDungeonMasterRegardlessOfCharacter() {
        TranscriptResult transcript = new TranscriptResult("en", List.of(
                new SpeakerSegment(0, 1, "SPEAKER_00", "You enter a smoky tavern."),
                new SpeakerSegment(1, 2, "SPEAKER_01", "I order an ale.")));

        List<SpeakerLabel> labels = List.of(
                new SpeakerLabel("SPEAKER_00", "Dave", "ignored", true), // DM wins over character
                new SpeakerLabel("SPEAKER_01", "Bob", "Thrain"));

        String rendered = TranscriptFormatter.render(transcript, labels);

        assertThat(rendered).isEqualTo("""
                Dungeon Master: You enter a smoky tavern.
                Thrain: I order an ale.
                """);
    }

    @Test
    void dmNote_emptyWhenNoDungeonMaster() {
        List<SpeakerLabel> labels = List.of(new SpeakerLabel("SPEAKER_00", "Bob", "Thrain"));
        assertThat(TranscriptFormatter.dmNote(labels)).isEmpty();
    }

    @Test
    void dmNote_describesDmRoleWhenPresent() {
        List<SpeakerLabel> labels = List.of(new SpeakerLabel("SPEAKER_00", "Dave", "", true));
        assertThat(TranscriptFormatter.dmNote(labels))
                .contains("Dungeon Master")
                .contains("non-player character");
    }

    @Test
    void dmAliases_includeGenericTermsAndFlaggedDmNames() {
        List<SpeakerLabel> labels = List.of(
                new SpeakerLabel("SPEAKER_00", "Dave", "Old Tom", true), // flagged DM
                new SpeakerLabel("SPEAKER_01", "Bob", "Thrain"));

        var aliases = TranscriptFormatter.dmAliases(labels);

        assertThat(aliases).contains("dungeon master", "dm", "narrator",
                "dave", "old tom", "speaker_00");
        assertThat(aliases).doesNotContain("thrain", "bob");
    }

    @Test
    void chunk_returnsSingleChunkWhenShorterThanSize() {
        AppProperties.Narration cfg = narrationCfg(1000, 100);
        List<String> chunks = TranscriptFormatter.chunk("a short line\n", cfg);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void chunk_splitsLongTextWithOverlapAndCoversAllContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("line ").append(i).append(" some narration content\n");
        }
        String text = sb.toString();

        AppProperties.Narration cfg = narrationCfg(120, 30);
        List<String> chunks = TranscriptFormatter.chunk(text, cfg);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c).isNotEmpty());
        // every original line must appear somewhere in the chunks
        for (int i = 0; i < 50; i++) {
            String needle = "line " + i + " ";
            assertThat(chunks).anySatisfy(c -> assertThat(c).contains(needle));
        }
    }

    private static AppProperties.Narration narrationCfg(int size, int overlap) {
        AppProperties.Narration cfg = new AppProperties.Narration();
        cfg.setChunkSizeChars(size);
        cfg.setChunkOverlapChars(overlap);
        return cfg;
    }
}




