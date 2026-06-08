package com.storytelling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.storytelling.client.ImageClient;
import com.storytelling.client.TranscriptionClient;
import com.storytelling.llm.NarrationAssistant;
import com.storytelling.model.Campaign;
import com.storytelling.model.CampaignCharacter;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.TranscriptResult;
import com.storytelling.store.CampaignStore;
import com.storytelling.store.SessionStore;

/**
 * Voice-fingerprint matching enabled: verifies labeling suggestions from enrolled
 * voiceprints and the enrollment learning loop. ML services are mocked.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class VoiceMatchingIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("storytelling.data-dir", () -> dataDir.toString());
        registry.add("storytelling.voice.enabled", () -> "true");
        registry.add("storytelling.voice.match-threshold", () -> "0.6");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    SessionStore store;
    @Autowired
    CampaignStore campaignStore;

    @MockBean
    TranscriptionClient transcriptionClient;
    @MockBean
    NarrationAssistant narrationAssistant;
    @MockBean
    ImageClient imageClient;

    @TestConfiguration
    static class SyncConfig {
        @Bean(name = "pipelineExecutor")
        Executor pipelineExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Test
    void suggestsCharacter_whenVoiceMatchesEnrolledVoiceprint() throws Exception {
        Campaign campaign = new Campaign("vc1", "Voiceprint Keep");
        campaign.setCharacters(List.of(new CampaignCharacter(
                "Lyra", "Alice", "a tall elf", List.of(new float[]{1, 0, 0}))));
        campaignStore.save(campaign);

        Session s = new Session("vsess", "clip.m4a");
        s.setCampaignId("vc1");
        s.setTranscript(new TranscriptResult("en",
                List.of(new SpeakerSegment(0, 5, "SPEAKER_00", "Hail and well met.")),
                Map.of("SPEAKER_00", new float[]{0.97f, 0.05f, 0})));
        s.setStatus(SessionStatus.AWAITING_LABELS);
        store.save(s);

        // The labeling page should pre-select Lyra and show the suggested hint.
        mockMvc.perform(get("/sessions/{id}", "vsess"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("suggested")));
    }

    @Test
    void enrollsVoiceprint_onLabelConfirmation() throws Exception {
        when(narrationAssistant.summarizeChunk(anyString(), anyString(), anyString())).thenReturn("A beat.");

        Campaign campaign = new Campaign("vc2", "Learning Keep");
        campaign.setCharacters(List.of(new CampaignCharacter("Thrain", "Bob", "a dwarf"))); // no voiceprints yet
        campaignStore.save(campaign);

        Session s = new Session("vsess2", "clip.m4a");
        s.setCampaignId("vc2");
        s.setTranscript(new TranscriptResult("en",
                List.of(new SpeakerSegment(0, 5, "SPEAKER_00", "By my beard!")),
                Map.of("SPEAKER_00", new float[]{0.1f, 0.9f, 0})));
        s.setStatus(SessionStatus.AWAITING_LABELS);
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/labels", "vsess2")
                        .param("mode", "step")
                        .param("character_SPEAKER_00", "Thrain"))
                .andExpect(status().is3xxRedirection());

        Campaign updated = campaignStore.load("vc2").orElseThrow();
        CampaignCharacter thrain = updated.getCharacters().get(0);
        assertThat(thrain.voiceprints()).hasSize(1);
        assertThat(thrain.voiceprints().get(0)).containsExactly(0.1f, 0.9f, 0);
    }
}

