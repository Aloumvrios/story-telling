package com.storytelling.pipeline;

import com.storytelling.client.ImageClient;
import com.storytelling.client.TranscriptionClient;
import com.storytelling.config.AppProperties;
import com.storytelling.llm.NarrationAssistant;
import com.storytelling.model.SceneList;
import com.storytelling.model.SceneSpec;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.model.TranscriptResult;
import com.storytelling.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives the three pipeline stages. Each stage persists progress so a long
 * (3-4h) job can be resumed after a crash or restart.
 *
 * Flow: TRANSCRIBE -> (pause for speaker labels) -> NARRATE -> SEGMENT -> IMAGES.
 */
@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final SessionStore store;
    private final TranscriptionClient transcriptionClient;
    private final ImageClient imageClient;
    private final NarrationAssistant narrationAssistant;
    private final AppProperties props;

    public PipelineOrchestrator(SessionStore store,
                                TranscriptionClient transcriptionClient,
                                ImageClient imageClient,
                                NarrationAssistant narrationAssistant,
                                AppProperties props) {
        this.store = store;
        this.transcriptionClient = transcriptionClient;
        this.imageClient = imageClient;
        this.narrationAssistant = narrationAssistant;
        this.props = props;
    }

    /** Stage 1: transcription + diarization. Runs async; ends at AWAITING_LABELS. */
    @Async("pipelineExecutor")
    public void runTranscription(String sessionId) {
        Session session = require(sessionId);
        try {
            session.setErrorMessage(null); // clear any error from a previous attempt
            session.setStatus(SessionStatus.TRANSCRIBING);
            store.save(session);

            Path audio = store.findAudio(sessionId)
                    .orElseThrow(() -> new IllegalStateException("No audio for session " + sessionId));
            log.info("[{}] transcribing {}", sessionId, audio);
            TranscriptResult transcript = transcriptionClient.transcribe(audio);
            session.setTranscript(transcript);

            session.setStatus(SessionStatus.AWAITING_LABELS);
            store.save(session);
            log.info("[{}] transcription done, {} segments", sessionId, transcript.segments().size());
        } catch (Throwable e) {
            fail(session, e, "transcription");
        }
    }

    // ----------------------------------------------------------------------
    // Public stage runners. Each is async and can be invoked individually
    // (step-by-step mode) or chained (full pipeline). They end on an idle
    // checkpoint status so the user can review/edit before the next stage.
    // ----------------------------------------------------------------------

    /** Full remaining pipeline after labels: narration -> segmentation -> images. */
    @Async("pipelineExecutor")
    public void runNarrationAndImages(String sessionId) {
        Session session = require(sessionId);
        try {
            narrate(session);
            segment(session);
            images(session);
            complete(session);
        } catch (Throwable e) {
            fail(session, e, "narration/images");
        }
    }

    /** Step only: build the narration from the transcript. Ends at NARRATED. */
    @Async("pipelineExecutor")
    public void runNarration(String sessionId) {
        Session session = require(sessionId);
        try {
            narrate(session);
            session.setStatus(SessionStatus.NARRATED);
            store.save(session);
        } catch (Throwable e) {
            fail(session, e, "narration");
        }
    }

    /** Step only: segment the existing narration into scenes. Ends at SEGMENTED. */
    @Async("pipelineExecutor")
    public void runSegmentation(String sessionId) {
        Session session = require(sessionId);
        try {
            segment(session);
            session.setStatus(SessionStatus.SEGMENTED);
            store.save(session);
        } catch (Throwable e) {
            fail(session, e, "segmentation");
        }
    }

    /** Segmentation then images (full from the narration checkpoint). */
    @Async("pipelineExecutor")
    public void runSegmentationAndImages(String sessionId) {
        Session session = require(sessionId);
        try {
            segment(session);
            images(session);
            complete(session);
        } catch (Throwable e) {
            fail(session, e, "segmentation/images");
        }
    }

    /** Step only: generate one image per existing scene. Ends at COMPLETED. */
    @Async("pipelineExecutor")
    public void runImages(String sessionId) {
        Session session = require(sessionId);
        try {
            images(session);
            complete(session);
        } catch (Throwable e) {
            fail(session, e, "images");
        }
    }

    // ----------------------------------------------------------------------
    // Stage implementations (synchronous; run on the pipeline executor thread).
    // ----------------------------------------------------------------------

    /** Stage 2a: map-reduce narration from the diarized, labeled transcript. */
    private void narrate(Session session) throws Exception {
        if (session.getTranscript() == null) {
            throw new IllegalStateException("No transcript available to narrate.");
        }
        session.setErrorMessage(null); // clear any error from a previous attempt
        session.setStatus(SessionStatus.NARRATING);
        store.save(session);

        String rendered = TranscriptFormatter.render(session.getTranscript(), session.getSpeakerLabels());
        List<String> chunks = TranscriptFormatter.chunk(rendered, props.getNarration());
        String dmNote = TranscriptFormatter.dmNote(session.getSpeakerLabels());
        log.info("[{}] narrating in {} chunks", session.getId(), chunks.size());

        String storySoFar = "";
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            log.info("[{}] narrating chunk {}/{}", session.getId(), i + 1, chunks.size());
            String beat = narrationAssistant.summarizeChunk(storySoFar, chunks.get(i), dmNote);
            full.append(beat).append("\n\n");
            storySoFar = tail(full.toString(), 2000); // rolling context window
        }
        session.setNarration(full.toString().strip());
        store.save(session);
    }

    /** Stage 2b: scene segmentation (structured output) from the narration. */
    private void segment(Session session) {
        String narration = session.getNarration();
        if (narration == null || narration.isBlank()) {
            throw new IllegalStateException("No narration available to segment.");
        }
        session.setErrorMessage(null);
        session.setStatus(SessionStatus.SEGMENTING);
        store.save(session);

        Integer count = session.getRequestedSceneCount();
        String dmNote = TranscriptFormatter.dmNote(session.getSpeakerLabels());
        SceneList sceneList = (count != null && count > 0)
                ? narrationAssistant.segmentScenesInto(narration, count, dmNote)
                : narrationAssistant.segmentScenes(narration, dmNote);
        // Defence-in-depth: ensure the Dungeon Master never appears as a scene
        // character (the prompt forbids it, but the LLM can still slip).
        java.util.Set<String> dmAliases = TranscriptFormatter.dmAliases(session.getSpeakerLabels());
        List<SceneSpec> scenes = sceneList.scenes().stream()
                .map(s -> stripDmCharacters(s, dmAliases))
                .toList();
        session.setScenes(new ArrayList<>(scenes));
        store.save(session);
        log.info("[{}] segmented into {} scenes{}", session.getId(), session.getScenes().size(),
                (count != null && count > 0) ? " (requested " + count + ")" : "");
    }

    /** Stage 3: one image per scene, persisted incrementally (resumable). */
    private void images(Session session) throws Exception {
        List<SceneSpec> scenes = session.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalStateException("No scenes available to illustrate.");
        }
        session.setErrorMessage(null);
        session.setStatus(SessionStatus.GENERATING_IMAGES);
        store.save(session);

        Path imagesDir = store.imagesDir(session.getId());
        Files.createDirectories(imagesDir);

        Map<String, String> characterContext = characterContext(session);
        java.util.Set<String> dmAliases = TranscriptFormatter.dmAliases(session.getSpeakerLabels());
        List<SceneSpec> withImages = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            // Strip the Dungeon Master from the character list so it never reaches
            // Stable Diffusion (covers sessions created directly from scenes too).
            SceneSpec scene = stripDmCharacters(scenes.get(i), dmAliases);
            String prompt = withCharacterContext(scene, characterContext);
            log.info("[{}] image {}/{}: {}", session.getId(), i + 1, scenes.size(), scene.title());
            byte[] png = imageClient.generate(prompt);
            String filename = String.format("scene_%02d.png", i + 1);
            Files.write(imagesDir.resolve(filename), png);
            withImages.add(new SceneSpec(scene.title(), scene.narration(),
                    scene.imagePrompt(), scene.characters(), "images/" + filename));
            session.setScenes(withImages.size() == scenes.size() ? withImages : merge(withImages, scenes));
            store.save(session); // persist after each image (resumable)
        }
        session.setScenes(withImages);
    }

    /** Removes any Dungeon Master aliases from a scene's character list. */
    private static SceneSpec stripDmCharacters(SceneSpec scene, java.util.Set<String> dmAliases) {
        if (scene.characters() == null || scene.characters().isEmpty() || dmAliases.isEmpty()) {
            return scene;
        }
        List<String> filtered = scene.characters().stream()
                .filter(c -> c != null && !c.isBlank())
                .filter(c -> !dmAliases.contains(c.strip().toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
        if (filtered.size() == scene.characters().size()) return scene;
        return new SceneSpec(scene.title(), scene.narration(), scene.imagePrompt(),
                filtered, scene.imagePath());
    }

    /** Lower-cased name -> appearance, for matching scene characters case-insensitively. */
    private static Map<String, String> characterContext(Session session) {
        Map<String, String> map = new java.util.HashMap<>();
        if (session.getCharacterProfiles() == null) return map;
        for (var p : session.getCharacterProfiles()) {
            if (p.name() != null && p.appearance() != null && !p.appearance().isBlank()) {
                map.put(p.name().strip().toLowerCase(), p.appearance().strip());
            }
        }
        return map;
    }

    /**
     * Appends the appearance descriptions of the characters present in a scene to
     * its image prompt, so Stable Diffusion renders them consistently across scenes.
     */
    private static String withCharacterContext(SceneSpec scene, Map<String, String> context) {
        if (context.isEmpty() || scene.characters() == null || scene.characters().isEmpty()) {
            return scene.imagePrompt();
        }
        StringBuilder details = new StringBuilder();
        for (String character : scene.characters()) {
            if (character == null) continue;
            String appearance = context.get(character.strip().toLowerCase());
            if (appearance != null) {
                if (!details.isEmpty()) details.append("; ");
                details.append(character.strip()).append(": ").append(appearance);
            }
        }
        return details.isEmpty()
                ? scene.imagePrompt()
                : scene.imagePrompt() + ". Character appearance — " + details + ".";
    }

    private void complete(Session session) {
        session.setStatus(SessionStatus.COMPLETED);
        store.save(session);
        log.info("[{}] completed", session.getId());
    }

    private static List<SceneSpec> merge(List<SceneSpec> done, List<SceneSpec> all) {
        List<SceneSpec> result = new ArrayList<>(done);
        for (int i = done.size(); i < all.size(); i++) result.add(all.get(i));
        return result;
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private Session require(String id) {
        return store.load(id).orElseThrow(() -> new IllegalArgumentException("Unknown session " + id));
    }

    private void fail(Session session, Throwable e, String stage) {
        log.error("[{}] failed during {}", session.getId(), stage, e);
        session.setStatus(SessionStatus.FAILED);
        session.setErrorMessage(stage + ": " + e.getMessage());
        store.save(session);
    }
}






