package com.storytelling.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.storytelling.client.ImageClient;
import com.storytelling.client.TranscriptionClient;
import com.storytelling.config.AppProperties;
import com.storytelling.llm.NarrationAssistant;
import com.storytelling.model.SceneList;
import com.storytelling.model.SceneSpec;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.model.SpeakerLabel;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.TranscriptResult;
import com.storytelling.store.SessionStore;

/**
 * Exercises the full orchestration logic with the external ML services mocked,
 * but real file-based persistence. Calling the orchestrator methods directly
 * bypasses the @Async proxy, so they run synchronously here.
 */
class PipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    private SessionStore store;
    private TranscriptionClient transcriptionClient;
    private ImageClient imageClient;
    private NarrationAssistant narrationAssistant;
    private PipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new SessionStore(props, mapper);

        transcriptionClient = mock(TranscriptionClient.class);
        imageClient = mock(ImageClient.class);
        narrationAssistant = mock(NarrationAssistant.class);
        orchestrator = new PipelineOrchestrator(store, transcriptionClient, imageClient, narrationAssistant, props);
    }

    @Test
    void runTranscription_storesTranscriptAndPausesForLabels() {
        Session session = new Session("s1", "rec.wav");
        store.save(session);
        store.saveAudio("s1", "rec.wav", new ByteArrayInputStream("audio".getBytes(StandardCharsets.UTF_8)));

        when(transcriptionClient.transcribe(any())).thenReturn(sampleTranscript());

        orchestrator.runTranscription("s1");

        Session result = store.load("s1").orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);
        assertThat(result.getTranscript().segments()).hasSize(2);
    }

    @Test
    void runNarrationAndImages_producesNarrationScenesAndImages() throws Exception {
        Session session = new Session("s2", "rec.wav");
        session.setTranscript(sampleTranscript());
        session.setSpeakerLabels(List.of(
                new SpeakerLabel("SPEAKER_00", "Alice", "Lyra"),
                new SpeakerLabel("SPEAKER_01", "Bob", "Thrain")));
        session.setStatus(SessionStatus.AWAITING_LABELS);
        store.save(session);

        when(narrationAssistant.summarizeChunk(anyString(), anyString(), anyString())).thenReturn("A heroic beat.");
        when(narrationAssistant.segmentScenes(anyString(), anyString())).thenReturn(new SceneList(List.of(
                new SceneSpec("The Gate", "They reached the gate.", "a stone gate at dusk", List.of("Lyra"), ""),
                new SceneSpec("The Battle", "A fight broke out.", "a fierce sword fight", List.of("Thrain"), ""))));
        when(imageClient.generate(anyString())).thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        orchestrator.runNarrationAndImages("s2");

        Session result = store.load("s2").orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(result.getNarration()).contains("A heroic beat.");
        assertThat(result.getScenes()).hasSize(2);
        assertThat(result.getScenes()).allSatisfy(s ->
                assertThat(s.imagePath()).startsWith("images/scene_"));

        Path img1 = store.imagesDir("s2").resolve("scene_01.png");
        Path img2 = store.imagesDir("s2").resolve("scene_02.png");
        assertThat(Files.exists(img1)).isTrue();
        assertThat(Files.exists(img2)).isTrue();
    }

    @Test
    void runTranscription_marksFailedWhenServiceThrows() {
        Session session = new Session("s3", "rec.wav");
        store.save(session);
        store.saveAudio("s3", "rec.wav", new ByteArrayInputStream("audio".getBytes(StandardCharsets.UTF_8)));

        when(transcriptionClient.transcribe(any())).thenThrow(new RuntimeException("service down"));

        orchestrator.runTranscription("s3");

        Session result = store.load("s3").orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("service down");
    }

    private static TranscriptResult sampleTranscript() {
        return new TranscriptResult("en", List.of(
                new SpeakerSegment(0, 2, "SPEAKER_00", "We approach the dungeon."),
                new SpeakerSegment(2, 4, "SPEAKER_01", "I draw my axe.")));
    }
}

