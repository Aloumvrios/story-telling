package com.storytelling.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    /** Stages 2 & 3: narration + scene segmentation + image generation. Runs async. */
    @Async("pipelineExecutor")
    public void runNarrationAndImages(String sessionId) {
        Session session = require(sessionId);
        try {
            // ----- Stage 2a: map-reduce narration -----
            session.setErrorMessage(null); // clear any error from a previous attempt
            session.setStatus(SessionStatus.NARRATING);
            store.save(session);

            String rendered = TranscriptFormatter.render(session.getTranscript(), session.getSpeakerLabels());
            List<String> chunks = TranscriptFormatter.chunk(rendered, props.getNarration());
            log.info("[{}] narrating in {} chunks", sessionId, chunks.size());

            String storySoFar = "";
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                log.info("[{}] narrating chunk {}/{}", sessionId, i + 1, chunks.size());
                String beat = narrationAssistant.summarizeChunk(storySoFar, chunks.get(i));
                full.append(beat).append("\n\n");
                storySoFar = tail(full.toString(), 2000); // rolling context window
            }
            String narration = full.toString().strip();
            session.setNarration(narration);

            // ----- Stage 2b: scene segmentation (structured output) -----
            session.setStatus(SessionStatus.SEGMENTING);
            store.save(session);
            SceneList sceneList = narrationAssistant.segmentScenes(narration);
            session.setScenes(new ArrayList<>(sceneList.scenes()));
            store.save(session);
            log.info("[{}] segmented into {} scenes", sessionId, session.getScenes().size());

            // ----- Stage 3: one image per scene -----
            session.setStatus(SessionStatus.GENERATING_IMAGES);
            store.save(session);
            Path imagesDir = store.imagesDir(sessionId);
            Files.createDirectories(imagesDir);

            List<SceneSpec> scenes = session.getScenes();
            List<SceneSpec> withImages = new ArrayList<>();
            for (int i = 0; i < scenes.size(); i++) {
                SceneSpec scene = scenes.get(i);
                log.info("[{}] image {}/{}: {}", sessionId, i + 1, scenes.size(), scene.title());
                byte[] png = imageClient.generate(scene.imagePrompt());
                String filename = String.format("scene_%02d.png", i + 1);
                Files.write(imagesDir.resolve(filename), png);
                withImages.add(new SceneSpec(scene.title(), scene.narration(),
                        scene.imagePrompt(), scene.characters(), "images/" + filename));
                session.setScenes(withImages.size() == scenes.size() ? withImages : merge(withImages, scenes));
                store.save(session); // persist after each image (resumable)
            }
            session.setScenes(withImages);

            session.setStatus(SessionStatus.COMPLETED);
            store.save(session);
            log.info("[{}] completed", sessionId);
        } catch (Throwable e) {
            fail(session, e, "narration/images");
        }
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






