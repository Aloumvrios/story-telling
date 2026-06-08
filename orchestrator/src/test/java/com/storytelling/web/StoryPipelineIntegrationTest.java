package com.storytelling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.storytelling.client.ImageClient;
import com.storytelling.client.TranscriptionClient;
import com.storytelling.llm.NarrationAssistant;
import com.storytelling.model.SceneList;
import com.storytelling.model.SceneSpec;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.TranscriptResult;
import com.storytelling.store.SessionStore;

/**
 * End-to-end test of the web flow using a real sample audio clip (src/test/resources/sample.m4a).
 * The ML services (WhisperX, Ollama, SDXL) are mocked so the test is hermetic and fast,
 * but the upload, persistence, speaker-labeling, async pipeline and image-serving are all real.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class StoryPipelineIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("storytelling.data-dir", () -> dataDir.toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionStore store;

    @MockBean
    TranscriptionClient transcriptionClient;

    @MockBean
    NarrationAssistant narrationAssistant;

    @MockBean
    ImageClient imageClient;

    /** Make @Async pipeline stages run synchronously so the test is deterministic. */
    @TestConfiguration
    static class SyncConfig {
        @Bean(name = "pipelineExecutor")
        Executor pipelineExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Test
    void fullPipeline_fromUploadToIllustratedChronicle() throws Exception {
        // --- mock the ML services ---
        when(transcriptionClient.transcribe(any())).thenReturn(new TranscriptResult("en", List.of(
                new SpeakerSegment(0, 3, "SPEAKER_00", "We enter the ruined keep."),
                new SpeakerSegment(3, 6, "SPEAKER_01", "I light a torch and look around."))));
        when(narrationAssistant.summarizeChunk(anyString(), anyString(), anyString()))
                .thenReturn("The party stepped into the ruined keep, torchlight flickering.");
        when(narrationAssistant.segmentScenes(anyString(), anyString())).thenReturn(new SceneList(List.of(
                new SceneSpec("Into the Keep", "They entered the ruined keep.",
                        "a ruined stone keep lit by torchlight", List.of("Lyra"), ""),
                new SceneSpec("Torchlight", "A torch revealed ancient halls.",
                        "ancient halls revealed by a single torch", List.of("Thrain"), ""))));
        when(imageClient.generate(anyString())).thenReturn(pngBytes());

        // --- 1. upload the sample audio clip ---
        byte[] audio = new ClassPathResource("sample.m4a").getInputStream().readAllBytes();
        MockMultipartFile file = new MockMultipartFile("audio", "sample.m4a", "audio/mp4", audio);

        MvcResult uploadResult = mockMvc.perform(multipart("/sessions").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        String location = uploadResult.getResponse().getRedirectedUrl();
        String sessionId = location.substring(location.lastIndexOf('/') + 1);

        // --- 2. transcription ran synchronously; we should now await speaker labels ---
        Session afterTranscription = store.load(sessionId).orElseThrow();
        assertThat(afterTranscription.getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);

        mockMvc.perform(get("/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Who's who")));

        // --- 3. submit speaker labels -> triggers narration + image generation ---
        mockMvc.perform(post("/sessions/{id}/labels", sessionId)
                        .param("player_SPEAKER_00", "Alice")
                        .param("character_SPEAKER_00", "Lyra")
                        .param("player_SPEAKER_01", "Bob")
                        .param("character_SPEAKER_01", "Thrain"))
                .andExpect(status().is3xxRedirection());

        // --- 4. pipeline completed with narration, scenes and image files ---
        Session done = store.load(sessionId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(done.getNarration()).isNotBlank();
        assertThat(done.getScenes()).hasSize(2);
        assertThat(done.getScenes()).allSatisfy(s -> assertThat(s.imagePath()).startsWith("images/scene_"));

        Path sceneImage = store.imagesDir(sessionId).resolve("scene_01.png");
        assertThat(Files.exists(sceneImage)).isTrue();

        // --- 5. the generated image is servable over HTTP ---
        mockMvc.perform(get("/sessions/{id}/images/{file}", sessionId, "scene_01.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(org.springframework.http.MediaType.IMAGE_PNG));
    }

    private static byte[] pngBytes() {
        // minimal PNG signature + a byte; enough to write and serve as image/png in this test
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }
}




