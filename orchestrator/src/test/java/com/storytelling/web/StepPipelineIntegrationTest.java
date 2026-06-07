package com.storytelling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
 * Integration tests for the step-by-step pipeline controls, the intermediate
 * entry points (transcript/narration/scenes), reuse/fork, deletion and the
 * range-enabled audio endpoint. ML services are mocked; persistence, the async
 * pipeline (run synchronously here) and HTTP handling are real.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class StepPipelineIntegrationTest {

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

    @Autowired
    com.storytelling.store.CampaignStore campaignStore;

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

    // ------------------------------------------------------------------
    // Step-by-step mode
    // ------------------------------------------------------------------

    @Test
    void labelsInStepMode_runsNarrationOnlyAndPausesAtNarrated() throws Exception {
        when(narrationAssistant.summarizeChunk(anyString(), anyString())).thenReturn("A quiet beat.");
        String id = persisted(awaitingLabels("step-narrate"));

        mockMvc.perform(post("/sessions/{id}/labels", id)
                        .param("mode", "step")
                        .param("player_SPEAKER_00", "Alice")
                        .param("character_SPEAKER_00", "Lyra"))
                .andExpect(status().is3xxRedirection());

        Session result = store.load(id).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SessionStatus.NARRATED);
        assertThat(result.getNarration()).contains("A quiet beat.");
        assertThat(result.getScenes()).isEmpty();
    }

    @Test
    void campaignLabeling_autoFillsPlayerAndCarriesAppearanceProfiles() throws Exception {
        when(narrationAssistant.summarizeChunk(anyString(), anyString())).thenReturn("A beat.");

        com.storytelling.model.Campaign campaign = new com.storytelling.model.Campaign("campX", "Crimson Keep");
        campaign.setCharacters(List.of(
                new com.storytelling.model.CampaignCharacter("Lyra", "Alice", "a tall elf with silver hair")));
        campaignStore.save(campaign);

        Session s = awaitingLabels("camp-session");
        s.setCampaignId("campX");
        store.save(s);

        // Note: no player param sent — it should be auto-filled from the campaign.
        mockMvc.perform(post("/sessions/{id}/labels", "camp-session")
                        .param("mode", "step")
                        .param("character_SPEAKER_00", "Lyra"))
                .andExpect(status().is3xxRedirection());

        Session result = store.load("camp-session").orElseThrow();
        assertThat(result.getSpeakerLabels()).anySatisfy(l -> {
            assertThat(l.character()).isEqualTo("Lyra");
            assertThat(l.player()).isEqualTo("Alice");
        });
        assertThat(result.getCharacterProfiles()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("Lyra");
            assertThat(p.appearance()).contains("silver hair");
        });
    }

    @Test
    void setCampaign_linksCampaignSoLabelingShowsCharacterDropdown() throws Exception {
        com.storytelling.model.Campaign campaign = new com.storytelling.model.Campaign("link1", "Late Link");
        campaign.setCharacters(List.of(
                new com.storytelling.model.CampaignCharacter("Mirabel", "Cara", "a gnome tinkerer")));
        campaignStore.save(campaign);

        // A session created WITHOUT a campaign (e.g. reused audio).
        Session s = awaitingLabels("link-session");
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/campaign", "link-session").param("campaignId", "link1"))
                .andExpect(status().is3xxRedirection());

        assertThat(store.load("link-session").orElseThrow().getCampaignId()).isEqualTo("link1");

        // The labeling page now offers the campaign character as a dropdown option.
        mockMvc.perform(get("/sessions/{id}", "link-session"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mirabel")));
    }

    @Test
    void runSegmentationStep_thenRunImages_completesPipeline() throws Exception {
        when(narrationAssistant.segmentScenes(anyString())).thenReturn(twoScenes());
        when(imageClient.generate(anyString())).thenReturn(pngBytes());

        Session s = new Session("seg-step", "(pasted narration)");
        s.setNarration("The heroes pressed on.");
        s.setStatus(SessionStatus.NARRATED);
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/run-segmentation", "seg-step").param("mode", "step"))
                .andExpect(status().is3xxRedirection());

        Session segmented = store.load("seg-step").orElseThrow();
        assertThat(segmented.getStatus()).isEqualTo(SessionStatus.SEGMENTED);
        assertThat(segmented.getScenes()).hasSize(2);
        assertThat(segmented.getScenes()).allSatisfy(sc -> assertThat(sc.imagePath()).isEmpty());

        mockMvc.perform(post("/sessions/{id}/run-images", "seg-step"))
                .andExpect(status().is3xxRedirection());

        Session done = store.load("seg-step").orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(done.getScenes()).allSatisfy(sc -> assertThat(sc.imagePath()).startsWith("images/scene_"));
    }

    @Test
    void runSegmentationWithSceneCount_usesExactCountAndPersistsIt() throws Exception {
        when(narrationAssistant.segmentScenesInto(anyString(), anyInt())).thenReturn(twoScenes());

        Session s = new Session("seg-count", "(pasted narration)");
        s.setNarration("A long chronicle.");
        s.setStatus(SessionStatus.NARRATED);
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/run-segmentation", "seg-count")
                        .param("mode", "step")
                        .param("sceneCount", "3"))
                .andExpect(status().is3xxRedirection());

        verify(narrationAssistant).segmentScenesInto(anyString(), eq(3));
        Session result = store.load("seg-count").orElseThrow();
        assertThat(result.getRequestedSceneCount()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo(SessionStatus.SEGMENTED);
    }

    @Test
    void runSegmentationWithoutSceneCount_usesDefaultRangeMethod() throws Exception {
        when(narrationAssistant.segmentScenes(anyString())).thenReturn(twoScenes());

        Session s = new Session("seg-default", "(pasted narration)");
        s.setNarration("Another chronicle.");
        s.setStatus(SessionStatus.NARRATED);
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/run-segmentation", "seg-default").param("mode", "step"))
                .andExpect(status().is3xxRedirection());

        verify(narrationAssistant).segmentScenes(anyString());
        assertThat(store.load("seg-default").orElseThrow().getRequestedSceneCount()).isNull();
    }

    // ------------------------------------------------------------------
    // Intermediate entry points
    // ------------------------------------------------------------------

    @Test
    void fromTranscript_createsSessionAwaitingLabelsWithParsedSpeakers() throws Exception {
        MvcResult res = mockMvc.perform(post("/sessions/from-transcript")
                        .param("transcript", "Lyra: We should rest.\nThrain: Agreed."))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        String id = idFrom(res);
        Session s = store.load(id).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);
        assertThat(s.getTranscript().segments()).hasSize(2);
        assertThat(s.getTranscript().segments()).extracting(SpeakerSegment::speaker)
                .containsExactly("Lyra", "Thrain");
    }

    @Test
    void fromNarration_createsSessionAtNarratedCheckpoint() throws Exception {
        MvcResult res = mockMvc.perform(post("/sessions/from-narration")
                        .param("narration", "The party descended into the cavern."))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        Session s = store.load(idFrom(res)).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.NARRATED);
        assertThat(s.getNarration()).isEqualTo("The party descended into the cavern.");
    }

    @Test
    void indexAndSessionPagesRenderWhenSessionHasNarrationAndImagePaths() throws Exception {
        Session s = new Session("render-ok", "clip.m4a");
        s.setNarration("A non-boolean narration string that must not be coerced by Thymeleaf.");
        s.setScenes(List.of(new SceneSpec("Scene", "Narration", "prompt", List.of("Lyra"), "images/scene_01.png")));
        s.setStatus(SessionStatus.COMPLETED);
        store.save(s);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("render-ok")));

        mockMvc.perform(get("/sessions/{id}", "render-ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A non-boolean narration string")));
    }

    @Test
    void fromScenes_createsSessionAtSegmentedThenGeneratesImages() throws Exception {
        when(imageClient.generate(anyString())).thenReturn(pngBytes());
        String scenesJson = "[{\"title\":\"Cave\",\"narration\":\"In the cave.\","
                + "\"imagePrompt\":\"a dark cave, fantasy art\",\"characters\":[\"Lyra\"],\"imagePath\":\"\"}]";

        MvcResult res = mockMvc.perform(post("/sessions/from-scenes").param("scenesJson", scenesJson))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        String id = idFrom(res);
        Session s = store.load(id).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.SEGMENTED);
        assertThat(s.getScenes()).hasSize(1);

        mockMvc.perform(post("/sessions/{id}/run-images", id)).andExpect(status().is3xxRedirection());
        assertThat(store.load(id).orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void fromScenes_withInvalidJson_redirectsHomeWithError() throws Exception {
        mockMvc.perform(post("/sessions/from-scenes").param("scenesJson", "not json"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void runImages_injectsCharacterContextIntoPromptAndPersistsProfiles() throws Exception {
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(imageClient.generate(promptCaptor.capture())).thenReturn(pngBytes());

        Session s = new Session("char-ctx", "(pasted scenes)");
        s.setScenes(List.of(
                new SceneSpec("Gate", "At the gate.", "a stone gate at dusk", List.of("Lyra"), ""),
                new SceneSpec("Camp", "At camp.", "a campfire at night", List.of("Thrain"), "")));
        s.setStatus(SessionStatus.SEGMENTED);
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/run-images", "char-ctx")
                        .param("charName", "Lyra").param("charDesc", "a tall elf with silver hair")
                        .param("charName", "Thrain").param("charDesc", "a stout dwarf with a red beard"))
                .andExpect(status().is3xxRedirection());

        // Each scene's prompt should carry only the present character's appearance.
        List<String> prompts = promptCaptor.getAllValues();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).contains("a stone gate at dusk").contains("a tall elf with silver hair");
        assertThat(prompts.get(0)).doesNotContain("red beard");
        assertThat(prompts.get(1)).contains("a campfire at night").contains("a stout dwarf with a red beard");

        Session done = store.load("char-ctx").orElseThrow();
        assertThat(done.getCharacterProfiles()).extracting(p -> p.name())
                .containsExactlyInAnyOrder("Lyra", "Thrain");
    }

    // ------------------------------------------------------------------
    // Reuse / fork
    // ------------------------------------------------------------------

    @Test
    void forkFromNarration_createsIndependentCopyAtNarrated() throws Exception {
        Session src = new Session("fork-src", "orig.m4a");
        src.setNarration("Original chronicle text.");
        src.setStatus(SessionStatus.COMPLETED);
        store.save(src);

        MvcResult res = mockMvc.perform(post("/sessions/{id}/fork", "fork-src").param("from", "narration"))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        String newId = idFrom(res);
        assertThat(newId).isNotEqualTo("fork-src");
        Session copy = store.load(newId).orElseThrow();
        assertThat(copy.getStatus()).isEqualTo(SessionStatus.NARRATED);
        assertThat(copy.getNarration()).isEqualTo("Original chronicle text.");
        // original untouched
        assertThat(store.load("fork-src").orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void forkFromScenes_clearsImagePathsSoTheyRegenerate() throws Exception {
        Session src = new Session("fork-scenes", "orig.m4a");
        src.setScenes(List.of(new SceneSpec("S1", "n", "p", List.of("Lyra"), "images/scene_01.png")));
        src.setStatus(SessionStatus.COMPLETED);
        store.save(src);

        MvcResult res = mockMvc.perform(post("/sessions/{id}/fork", "fork-scenes").param("from", "scenes"))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        Session copy = store.load(idFrom(res)).orElseThrow();
        assertThat(copy.getStatus()).isEqualTo(SessionStatus.SEGMENTED);
        assertThat(copy.getScenes()).allSatisfy(sc -> assertThat(sc.imagePath()).isEmpty());
    }

    @Test
    void reuseAudio_clonesAudioIntoNewSessionAndTranscribes() throws Exception {
        when(transcriptionClient.transcribe(any())).thenReturn(sampleTranscript());
        Session src = new Session("audio-src", "clip.m4a");
        store.save(src);
        store.saveAudio("audio-src", "clip.m4a", new ByteArrayInputStream("audio".getBytes(StandardCharsets.UTF_8)));

        MvcResult res = mockMvc.perform(post("/sessions/reuse-audio").param("sourceId", "audio-src"))
                .andExpect(redirectedUrlPattern("/sessions/*"))
                .andReturn();

        String newId = idFrom(res);
        assertThat(store.findAudio(newId)).isPresent();
        assertThat(store.load(newId).orElseThrow().getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);
    }

    // ------------------------------------------------------------------
    // Deletion
    // ------------------------------------------------------------------

    @Test
    void deleteSession_removesItAndRedirectsHome() throws Exception {
        Session s = new Session("to-delete", "x.m4a");
        store.save(s);
        store.saveAudio("to-delete", "x.m4a", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(post("/sessions/{id}/delete", "to-delete"))
                .andExpect(redirectedUrl("/"));

        assertThat(store.load("to-delete")).isEmpty();
        assertThat(store.findAudio("to-delete")).isEmpty();
    }

    @Test
    void retry_failedTranscription_reRunsTranscription() throws Exception {
        when(transcriptionClient.transcribe(any())).thenReturn(sampleTranscript());
        Session s = new Session("retry-asr", "x.m4a");
        s.setStatus(SessionStatus.FAILED);
        s.setErrorMessage("transcription: boom");
        store.save(s);
        store.saveAudio("retry-asr", "x.m4a", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(post("/sessions/{id}/retry", "retry-asr"))
                .andExpect(status().is3xxRedirection());

        assertThat(store.load("retry-asr").orElseThrow().getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);
    }

    @Test
    void retry_failedAfterNarration_resumesAtSegmentation() throws Exception {
        when(narrationAssistant.segmentScenes(anyString())).thenReturn(twoScenes());
        when(imageClient.generate(anyString())).thenReturn(pngBytes());
        Session s = new Session("retry-seg", "x.m4a");
        s.setNarration("A finished chronicle.");
        s.setStatus(SessionStatus.FAILED);
        s.setErrorMessage("segmentation/images: boom");
        store.save(s);

        mockMvc.perform(post("/sessions/{id}/retry", "retry-seg"))
                .andExpect(status().is3xxRedirection());

        assertThat(store.load("retry-seg").orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    // ------------------------------------------------------------------
    // Audio endpoint (range support powers the speaker preview)
    // ------------------------------------------------------------------

    @Test
    void audioEndpoint_servesFullFileWithAcceptRanges() throws Exception {
        Session s = new Session("audio-full", "v.m4a");
        store.save(s);
        store.saveAudio("audio-full", "v.m4a", new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/sessions/{id}/audio", "audio-full"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().longValue("Content-Length", 10));
    }

    @Test
    void audioEndpoint_servesPartialContentForRangeRequest() throws Exception {
        Session s = new Session("audio-range", "v.m4a");
        store.save(s);
        store.saveAudio("audio-range", "v.m4a", new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/sessions/{id}/audio", "audio-range").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", Matchers.equalTo("bytes 2-5/10")))
                .andExpect(header().longValue("Content-Length", 4))
                .andExpect(content().string("2345"));
    }

    @Test
    void audioEndpoint_returns404WhenNoAudio() throws Exception {
        Session s = new Session("no-audio", "(pasted narration)");
        s.setNarration("text");
        s.setStatus(SessionStatus.NARRATED);
        store.save(s);

        mockMvc.perform(get("/sessions/{id}/audio", "no-audio"))
                .andExpect(status().isNotFound());
    }

    @Test
    void labelingPage_showsSpeakerSampleControlsWhenAudioPresent() throws Exception {
        Session s = awaitingLabels("labels-ui");
        store.save(s);
        store.saveAudio("labels-ui", "v.m4a", new ByteArrayInputStream("audio".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/sessions/{id}", "labels-ui"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("play-sample")))
                .andExpect(content().string(Matchers.containsString("/audio")));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String persisted(Session s) {
        store.save(s);
        return s.getId();
    }

    private static Session awaitingLabels(String id) {
        Session s = new Session(id, "clip.m4a");
        s.setTranscript(sampleTranscript());
        s.setStatus(SessionStatus.AWAITING_LABELS);
        return s;
    }

    private static SceneList twoScenes() {
        return new SceneList(List.of(
                new SceneSpec("The Gate", "They reached the gate.", "a stone gate at dusk", List.of("Lyra"), ""),
                new SceneSpec("The Battle", "A fight broke out.", "a fierce sword fight", List.of("Thrain"), "")));
    }

    private static TranscriptResult sampleTranscript() {
        return new TranscriptResult("en", List.of(
                new SpeakerSegment(0, 4, "SPEAKER_00", "We approach the dungeon."),
                new SpeakerSegment(4, 6, "SPEAKER_00", "I draw my axe.")));
    }

    private static String idFrom(MvcResult res) {
        String location = res.getResponse().getRedirectedUrl();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }
}

