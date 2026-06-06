package com.storytelling.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.storytelling.model.SpeakerLabel;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.pipeline.PipelineOrchestrator;
import com.storytelling.store.SessionStore;

@Controller
public class StoryController {

    private final SessionStore store;
    private final PipelineOrchestrator pipeline;

    public StoryController(SessionStore store, PipelineOrchestrator pipeline) {
        this.store = store;
        this.pipeline = pipeline;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("sessions", store.listAll());
        return "index";
    }

    @PostMapping("/sessions")
    public String upload(@RequestParam("audio") MultipartFile audio, RedirectAttributes ra) {
        if (audio.isEmpty()) {
            ra.addFlashAttribute("error", "Please choose an audio file.");
            return "redirect:/";
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(id, audio.getOriginalFilename());
        store.save(session);
        try {
            store.saveAudio(id, audio.getOriginalFilename(), audio.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pipeline.runTranscription(id); // async
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/sessions/{id}")
    public String view(@PathVariable String id, Model model) {
        Session session = store.load(id).orElseThrow();
        model.addAttribute("story", session);
        model.addAttribute("speakers", distinctSpeakers(session));
        model.addAttribute("autoRefresh", isInProgress(session.getStatus()));
        return "session";
    }

    private static boolean isInProgress(SessionStatus status) {
        return status != SessionStatus.COMPLETED
                && status != SessionStatus.AWAITING_LABELS
                && status != SessionStatus.FAILED;
    }

    @PostMapping("/sessions/{id}/labels")
    public String submitLabels(@PathVariable String id,
                               @RequestParam java.util.Map<String, String> params) {
        Session session = store.load(id).orElseThrow();
        List<SpeakerLabel> labels = new ArrayList<>();
        for (String raw : distinctSpeakers(session)) {
            String player = params.get("player_" + raw);
            String character = params.get("character_" + raw);
            labels.add(new SpeakerLabel(raw, player, character));
        }
        session.setSpeakerLabels(labels);
        store.save(session);
        pipeline.runNarrationAndImages(id); // async
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/sessions/{id}/images/{file}")
    public ResponseEntity<Resource> image(@PathVariable String id, @PathVariable String file) {
        Path path = store.imagesDir(id).resolve(file).normalize();
        if (!path.startsWith(store.imagesDir(id)) || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(path));
    }

    private Set<String> distinctSpeakers(Session session) {
        Set<String> speakers = new LinkedHashSet<>();
        if (session.getTranscript() != null) {
            for (SpeakerSegment s : session.getTranscript().segments()) {
                speakers.add(s.speaker());
            }
        }
        return speakers;
    }
}





