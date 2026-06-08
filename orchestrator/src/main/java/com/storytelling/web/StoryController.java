package com.storytelling.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storytelling.config.AppProperties;
import com.storytelling.model.Campaign;
import com.storytelling.model.CampaignCharacter;
import com.storytelling.model.CharacterProfile;
import com.storytelling.model.SceneList;
import com.storytelling.model.SceneSpec;
import com.storytelling.model.SpeakerLabel;
import com.storytelling.model.SpeakerSample;
import com.storytelling.model.SpeakerSegment;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;
import com.storytelling.model.TranscriptResult;
import com.storytelling.pipeline.PipelineOrchestrator;
import com.storytelling.store.CampaignStore;
import com.storytelling.store.SessionStore;
import com.storytelling.voice.VoiceMatcher;

@Controller
public class StoryController {

    private final SessionStore store;
    private final CampaignStore campaigns;
    private final PipelineOrchestrator pipeline;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public StoryController(SessionStore store, CampaignStore campaigns,
                           PipelineOrchestrator pipeline, ObjectMapper mapper, AppProperties props) {
        this.store = store;
        this.campaigns = campaigns;
        this.pipeline = pipeline;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Session> sessions = store.listAll();
        List<Session> withAudio = sessionsWithAudio(sessions);
        Set<String> audioIds = new LinkedHashSet<>();
        for (Session s : withAudio) audioIds.add(s.getId());
        List<Campaign> allCampaigns = campaigns.listAll();
        Map<String, String> campaignNames = new LinkedHashMap<>();
        for (Campaign c : allCampaigns) campaignNames.put(c.getId(), c.getName());
        model.addAttribute("sessions", sessions);
        model.addAttribute("audioSessions", withAudio);
        model.addAttribute("audioIds", audioIds);
        model.addAttribute("campaigns", allCampaigns);
        model.addAttribute("campaignNames", campaignNames);
        return "index";
    }

    @PostMapping("/sessions")
    public String upload(@RequestParam("audio") MultipartFile audio,
                         @RequestParam(value = "campaignId", required = false) String campaignId,
                         RedirectAttributes ra) {
        if (audio.isEmpty()) {
            ra.addFlashAttribute("error", "Please choose an audio file.");
            return "redirect:/";
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(id, audio.getOriginalFilename());
        if (campaignId != null && !campaignId.isBlank()) {
            session.setCampaignId(campaignId);
        }
        store.save(session);
        try {
            store.saveAudio(id, audio.getOriginalFilename(), audio.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pipeline.runTranscription(id); // async
        return "redirect:/sessions/" + id;
    }

    /** Start from a pasted transcript (skip audio/transcription) -> goes to speaker labeling. */
    @PostMapping("/sessions/from-transcript")
    public String fromTranscript(@RequestParam("transcript") String transcript, RedirectAttributes ra) {
        if (transcript == null || transcript.isBlank()) {
            ra.addFlashAttribute("error", "Please paste a transcript.");
            return "redirect:/";
        }
        String id = newId();
        Session session = new Session(id, "(pasted transcript)");
        session.setTranscript(parseTranscript(transcript));
        session.setStatus(SessionStatus.AWAITING_LABELS);
        store.save(session);
        return "redirect:/sessions/" + id;
    }

    /** Start from a pasted narration (skip transcription + narration) -> ready to segment. */
    @PostMapping("/sessions/from-narration")
    public String fromNarration(@RequestParam("narration") String narration, RedirectAttributes ra) {
        if (narration == null || narration.isBlank()) {
            ra.addFlashAttribute("error", "Please paste a narration.");
            return "redirect:/";
        }
        String id = newId();
        Session session = new Session(id, "(pasted narration)");
        session.setNarration(narration.strip());
        session.setStatus(SessionStatus.NARRATED);
        store.save(session);
        return "redirect:/sessions/" + id;
    }

    /** Start from pasted scenes JSON -> ready to generate images. */
    @PostMapping("/sessions/from-scenes")
    public String fromScenes(@RequestParam("scenesJson") String scenesJson, RedirectAttributes ra) {
        List<SceneSpec> scenes;
        try {
            scenes = parseScenes(scenesJson);
        } catch (IOException e) {
            ra.addFlashAttribute("error", "Could not parse scenes JSON: " + e.getMessage());
            return "redirect:/";
        }
        if (scenes.isEmpty()) {
            ra.addFlashAttribute("error", "No scenes found in the JSON.");
            return "redirect:/";
        }
        String id = newId();
        Session session = new Session(id, "(pasted scenes)");
        session.setScenes(new ArrayList<>(scenes));
        session.setStatus(SessionStatus.SEGMENTED);
        store.save(session);
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/sessions/{id}")
    public String view(@PathVariable String id, Model model) {
        Session session = store.load(id).orElseThrow();
        Campaign campaign = session.getCampaignId() == null ? null
                : campaigns.load(session.getCampaignId()).orElse(null);
        model.addAttribute("story", session);
        model.addAttribute("speakers", distinctSpeakers(session));
        model.addAttribute("speakerSamples", speakerSamples(session));
        model.addAttribute("existingLabels", labelMap(session));
        model.addAttribute("hasAudio", store.findAudio(id).isPresent());
        model.addAttribute("autoRefresh", isInProgress(session.getStatus()));
        model.addAttribute("scenesJson", toScenesJson(session.getScenes()));
        model.addAttribute("sceneCharacters", sceneCharacters(session));
        model.addAttribute("characterProfiles", profileMap(session));
        model.addAttribute("campaign", campaign);
        model.addAttribute("campaignCharacters", campaign == null ? List.of() : campaign.getCharacters());
        model.addAttribute("allCampaigns", campaigns.listAll());
        model.addAttribute("suggestedCharacters", suggestCharacters(session, campaign));
        return "session";
    }

    /** Link (or unlink) a session to a campaign so labeling can use its characters. */
    @PostMapping("/sessions/{id}/campaign")
    public String setCampaign(@PathVariable String id,
                              @RequestParam(value = "campaignId", required = false) String campaignId) {
        Session session = store.load(id).orElseThrow();
        session.setCampaignId(campaignId != null && !campaignId.isBlank() ? campaignId : null);
        store.save(session);
        return "redirect:/sessions/" + id;
    }

    /** Suggest rawLabel -> character name from enrolled voiceprints (when enabled). */
    private Map<String, String> suggestCharacters(Session session, Campaign campaign) {
        if (!props.getVoice().isEnabled() || campaign == null || session.getTranscript() == null) {
            return Map.of();
        }
        return VoiceMatcher.suggest(session.getTranscript().speakerEmbeddings(),
                campaign.getCharacters(), props.getVoice().getMatchThreshold());
    }

    /** In-progress statuses auto-refresh; idle checkpoints (and terminal states) do not. */
    private static boolean isInProgress(SessionStatus status) {
        return status == SessionStatus.CREATED
                || status == SessionStatus.TRANSCRIBING
                || status == SessionStatus.NARRATING
                || status == SessionStatus.SEGMENTING
                || status == SessionStatus.GENERATING_IMAGES;
    }

    @PostMapping("/sessions/{id}/labels")
    public String submitLabels(@PathVariable String id,
                               @RequestParam(value = "mode", defaultValue = "full") String mode,
                               @RequestParam java.util.Map<String, String> params) {
        Session session = store.load(id).orElseThrow();
        Campaign campaign = session.getCampaignId() == null ? null
                : campaigns.load(session.getCampaignId()).orElse(null);

        List<SpeakerLabel> labels = new ArrayList<>();
        for (String raw : distinctSpeakers(session)) {
            String player = params.get("player_" + raw);
            String character = params.get("character_" + raw);
            boolean dm = params.get("dm_" + raw) != null; // checkbox present == DM
            // If a campaign character was picked, fill in the player from the campaign
            // when the form didn't provide one.
            CampaignCharacter cc = findCampaignCharacter(campaign, character);
            if (cc != null && (player == null || player.isBlank())) {
                player = cc.player();
            }
            labels.add(new SpeakerLabel(raw, player, character, dm));
        }
        session.setSpeakerLabels(labels);
        // Carry the campaign's character appearances into the session so they flow
        // through to image generation automatically.
        if (campaign != null) {
            session.setCharacterProfiles(campaignProfiles(campaign));
            enrollVoiceprints(session, campaign, labels);
        }
        store.save(session);
        if ("step".equals(mode)) {
            pipeline.runNarration(id);            // narration only -> NARRATED
        } else {
            pipeline.runNarrationAndImages(id);   // full remaining pipeline
        }
        return "redirect:/sessions/" + id;
    }

    /**
     * Learning loop: when voice matching is enabled, append each confirmed
     * speaker's embedding to the chosen campaign character's voiceprints, so the
     * character is recognised in future sessions. Persists the updated campaign.
     */
    private void enrollVoiceprints(Session session, Campaign campaign, List<SpeakerLabel> labels) {
        if (!props.getVoice().isEnabled() || session.getTranscript() == null) return;
        Map<String, float[]> embeddings = session.getTranscript().speakerEmbeddings();
        if (embeddings == null || embeddings.isEmpty()) return;

        int cap = Math.max(1, props.getVoice().getMaxVoiceprintsPerCharacter());
        boolean changed = false;
        for (SpeakerLabel label : labels) {
            if (label.dungeonMaster()) continue; // DM is the narrator/NPCs, not a character
            float[] embedding = embeddings.get(label.rawLabel());
            CampaignCharacter cc = findCampaignCharacter(campaign, label.character());
            if (embedding == null || cc == null) continue;
            List<float[]> prints = new ArrayList<>(cc.voiceprints());
            prints.add(embedding);
            while (prints.size() > cap) prints.remove(0); // keep the most recent
            replaceCharacter(campaign, new CampaignCharacter(cc.name(), cc.player(), cc.appearance(), prints));
            changed = true;
        }
        if (changed) campaigns.save(campaign);
    }

    /** Replaces a character in the campaign (matched by name) with an updated copy. */
    private static void replaceCharacter(Campaign campaign, CampaignCharacter updated) {
        List<CampaignCharacter> list = campaign.getCharacters();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equalsIgnoreCase(updated.name())) {
                list.set(i, updated);
                return;
            }
        }
    }

    /** Save any edits to the narration, then segment (step) or run all remaining (full). */
    @PostMapping("/sessions/{id}/run-segmentation")
    public String runSegmentation(@PathVariable String id,
                                  @RequestParam(value = "mode", defaultValue = "step") String mode,
                                  @RequestParam(value = "narration", required = false) String narration,
                                  @RequestParam(value = "sceneCount", required = false) Integer sceneCount) {
        Session session = store.load(id).orElseThrow();
        if (narration != null && !narration.isBlank()) {
            session.setNarration(narration.strip());
        }
        // Optional desired scene count; null/<=0 means "let the model choose" (5-12).
        session.setRequestedSceneCount(sceneCount != null && sceneCount > 0
                ? Math.min(sceneCount, 40) : null);
        store.save(session);
        if ("full".equals(mode)) {
            pipeline.runSegmentationAndImages(id);
        } else {
            pipeline.runSegmentation(id);
        }
        return "redirect:/sessions/" + id;
    }

    /** Save any edits to the scenes JSON + character context, then generate one image per scene. */
    @PostMapping("/sessions/{id}/run-images")
    public String runImagesStep(@PathVariable String id,
                                @RequestParam(value = "scenesJson", required = false) String scenesJson,
                                @RequestParam(value = "charName", required = false) List<String> charNames,
                                @RequestParam(value = "charDesc", required = false) List<String> charDescs,
                                RedirectAttributes ra) {
        Session session = store.load(id).orElseThrow();
        if (scenesJson != null && !scenesJson.isBlank()) {
            try {
                session.setScenes(new ArrayList<>(parseScenes(scenesJson)));
            } catch (IOException e) {
                ra.addFlashAttribute("error", "Could not parse scenes JSON: " + e.getMessage());
                return "redirect:/sessions/" + id;
            }
        }
        session.setCharacterProfiles(toProfiles(charNames, charDescs));
        store.save(session);
        pipeline.runImages(id);
        return "redirect:/sessions/" + id;
    }

    /** Zips the parallel charName/charDesc form arrays into character profiles. */
    private static List<CharacterProfile> toProfiles(List<String> names, List<String> descs) {
        List<CharacterProfile> profiles = new ArrayList<>();
        if (names == null) return profiles;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String desc = (descs != null && i < descs.size()) ? descs.get(i) : null;
            if (name != null && !name.isBlank() && desc != null && !desc.isBlank()) {
                profiles.add(new CharacterProfile(name.strip(), desc.strip()));
            }
        }
        return profiles;
    }

    /**
     * Creates a NEW session pre-populated from a past one, picking up the
     * pipeline at the chosen stage. The original session is left untouched.
     * {@code from} is one of: audio, transcript, narration, scenes.
     */
    @PostMapping("/sessions/{id}/fork")
    public String fork(@PathVariable String id, @RequestParam("from") String from, RedirectAttributes ra) {
        return forkSession(id, from, ra);
    }

    /** Convenience entry point: reuse a past session's audio from the home page. */
    @PostMapping("/sessions/reuse-audio")
    public String reuseAudio(@RequestParam("sourceId") String sourceId, RedirectAttributes ra) {
        return forkSession(sourceId, "audio", ra);
    }

    /** Permanently deletes a past session and all its files. */
    @PostMapping("/sessions/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        if (store.delete(id)) {
            ra.addFlashAttribute("message", "Deleted session " + id + ".");
        } else {
            ra.addFlashAttribute("error", "Could not delete session " + id + ".");
        }
        return "redirect:/";
    }

    /**
     * Retries a failed session by resuming from the furthest stage whose output is
     * already available: images <- segmentation <- narration <- transcription.
     */
    @PostMapping("/sessions/{id}/retry")
    public String retry(@PathVariable String id, RedirectAttributes ra) {
        Session s = store.load(id).orElseThrow();
        if (s.getScenes() != null && !s.getScenes().isEmpty()) {
            pipeline.runImages(id);                       // failed during image gen
        } else if (s.getNarration() != null && !s.getNarration().isBlank()) {
            pipeline.runSegmentationAndImages(id);        // failed during segmentation
        } else if (s.getTranscript() != null) {
            pipeline.runNarrationAndImages(id);           // failed during narration
        } else if (store.findAudio(id).isPresent()) {
            pipeline.runTranscription(id);                // failed during transcription
        } else {
            ra.addFlashAttribute("error", "Nothing to retry: this session has no audio or transcript.");
        }
        return "redirect:/sessions/" + id;
    }

    private String forkSession(String sourceId, String from, RedirectAttributes ra) {
        Session src = store.load(sourceId).orElseThrow();
        String id = newId();
        Session copy = new Session(id, src.getOriginalAudioFilename());
        copy.setCampaignId(src.getCampaignId()); // keep the campaign link across reuse
        switch (from == null ? "" : from) {
            case "audio" -> {
                Path audio = store.findAudio(sourceId).orElse(null);
                if (audio == null) {
                    ra.addFlashAttribute("error", "That session has no audio to reuse.");
                    return "redirect:/sessions/" + sourceId;
                }
                store.save(copy);
                try (InputStream in = Files.newInputStream(audio)) {
                    store.saveAudio(id, audio.getFileName().toString(), in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                pipeline.runTranscription(id); // re-transcribe from the reused audio
            }
            case "transcript" -> {
                if (src.getTranscript() == null) {
                    ra.addFlashAttribute("error", "That session has no transcript to reuse.");
                    return "redirect:/sessions/" + sourceId;
                }
                copy.setOriginalAudioFilename(reuseLabel(src, "transcript"));
                copy.setTranscript(src.getTranscript());
                copy.setSpeakerLabels(new ArrayList<>(src.getSpeakerLabels()));
                copy.setStatus(SessionStatus.AWAITING_LABELS);
                store.save(copy);
            }
            case "narration" -> {
                if (src.getNarration() == null || src.getNarration().isBlank()) {
                    ra.addFlashAttribute("error", "That session has no narration to reuse.");
                    return "redirect:/sessions/" + sourceId;
                }
                copy.setOriginalAudioFilename(reuseLabel(src, "narration"));
                copy.setNarration(src.getNarration());
                copy.setRequestedSceneCount(src.getRequestedSceneCount());
                copy.setStatus(SessionStatus.NARRATED);
                store.save(copy);
            }
            case "scenes" -> {
                if (src.getScenes() == null || src.getScenes().isEmpty()) {
                    ra.addFlashAttribute("error", "That session has no scenes to reuse.");
                    return "redirect:/sessions/" + sourceId;
                }
                copy.setOriginalAudioFilename(reuseLabel(src, "scenes"));
                copy.setNarration(src.getNarration());
                copy.setScenes(stripImages(src.getScenes()));
                copy.setCharacterProfiles(new ArrayList<>(src.getCharacterProfiles()));
                copy.setStatus(SessionStatus.SEGMENTED);
                store.save(copy);
            }
            default -> {
                ra.addFlashAttribute("error", "Unknown reuse stage: " + from);
                return "redirect:/sessions/" + sourceId;
            }
        }
        return "redirect:/sessions/" + id;
    }

    /** Copies scenes but clears imagePath so images regenerate in the new session. */
    private static List<SceneSpec> stripImages(List<SceneSpec> scenes) {
        List<SceneSpec> out = new ArrayList<>();
        for (SceneSpec s : scenes) {
            out.add(new SceneSpec(s.title(), s.narration(), s.imagePrompt(), s.characters(), ""));
        }
        return out;
    }

    private static String reuseLabel(Session src, String stage) {
        String base = src.getOriginalAudioFilename() == null ? src.getId() : src.getOriginalAudioFilename();
        return base + " (reused " + stage + " from " + src.getId() + ")";
    }

    /** Sessions that have a stored audio file, for the home-page reuse picker. */
    private List<Session> sessionsWithAudio(List<Session> sessions) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (store.findAudio(s.getId()).isPresent()) out.add(s);
        }
        return out;
    }

    /** Map of rawLabel -> SpeakerLabel, used to prefill the labeling form. */
    private static Map<String, SpeakerLabel> labelMap(Session session) {
        Map<String, SpeakerLabel> map = new LinkedHashMap<>();
        for (SpeakerLabel l : session.getSpeakerLabels()) {
            map.put(l.rawLabel(), l);
        }
        return map;
    }

    /** Distinct character names across all scenes, in first-appearance order. */
    private static List<String> sceneCharacters(Session session) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (session.getScenes() != null) {
            for (SceneSpec scene : session.getScenes()) {
                if (scene.characters() == null) continue;
                for (String c : scene.characters()) {
                    if (c != null && !c.isBlank()) names.add(c.strip());
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** Map of character name -> appearance, to prefill the character-context form. */
    private static Map<String, String> profileMap(Session session) {
        Map<String, String> map = new LinkedHashMap<>();
        if (session.getCharacterProfiles() != null) {
            for (CharacterProfile p : session.getCharacterProfiles()) {
                map.put(p.name(), p.appearance());
            }
        }
        return map;
    }

    /** Finds a campaign character by name (case-insensitive), or null. */
    private static CampaignCharacter findCampaignCharacter(Campaign campaign, String name) {
        if (campaign == null || name == null || name.isBlank()) return null;
        for (CampaignCharacter c : campaign.getCharacters()) {
            if (c.name() != null && c.name().equalsIgnoreCase(name.strip())) return c;
        }
        return null;
    }

    /** The campaign's characters as session-level appearance profiles. */
    private static List<CharacterProfile> campaignProfiles(Campaign campaign) {
        List<CharacterProfile> profiles = new ArrayList<>();
        for (CampaignCharacter c : campaign.getCharacters()) {
            if (c.name() != null && c.appearance() != null && !c.appearance().isBlank()) {
                profiles.add(new CharacterProfile(c.name(), c.appearance()));
            }
        }
        return profiles;
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

    /**
     * Streams the session's original audio with HTTP range support, so the
     * browser can seek and play just a slice (used to preview a speaker's voice
     * while labeling). Written directly to the response to guarantee 206 range
     * handling regardless of the configured message converters.
     */
    @GetMapping("/sessions/{id}/audio")
    public void audio(@PathVariable String id,
                      @RequestHeader HttpHeaders headers,
                      HttpServletResponse response) throws IOException {
        Path path = store.findAudio(id).orElse(null);
        if (path == null || !Files.exists(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long fileLength = Files.size(path);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(audioMediaType(path).toString());

        List<HttpRange> ranges = headers.getRange();
        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(fileLength);
            try (InputStream in = Files.newInputStream(path)) {
                in.transferTo(response.getOutputStream());
            }
            return;
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(fileLength);
        long end = range.getRangeEnd(fileLength);
        long count = end - start + 1;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
        response.setContentLengthLong(count);
        try (InputStream in = Files.newInputStream(path)) {
            in.skipNBytes(start);
            copyExactly(in, response.getOutputStream(), count);
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    private static MediaType audioMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (name.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (name.endsWith(".ogg") || name.endsWith(".oga")) return MediaType.parseMediaType("audio/ogg");
        if (name.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (name.endsWith(".webm")) return MediaType.parseMediaType("audio/webm");
        // m4a / mp4 / aac and unknowns default to audio/mp4 (broadly supported)
        return MediaType.parseMediaType("audio/mp4");
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

    /**
     * Picks one representative slice per speaker for voice preview: the longest
     * segment for that speaker (capped to ~8s), which is usually the clearest.
     * Returned in first-appearance order to match the labeling table.
     */
    private List<SpeakerSample> speakerSamples(Session session) {
        if (session.getTranscript() == null) return List.of();
        Map<String, SpeakerSegment> longest = new LinkedHashMap<>();
        for (SpeakerSegment s : session.getTranscript().segments()) {
            SpeakerSegment best = longest.get(s.speaker());
            if (best == null || (s.end() - s.start()) > (best.end() - best.start())) {
                longest.put(s.speaker(), s);
            }
        }
        List<SpeakerSample> samples = new ArrayList<>();
        for (SpeakerSegment s : longest.values()) {
            double start = Math.max(0, s.start());
            double end = s.end() > start ? Math.min(s.end(), start + 8.0) : start;
            samples.add(new SpeakerSample(s.speaker(), start, end));
        }
        return samples;
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Parses a pasted transcript into diarized segments. Lines of the form
     * "Speaker: text" become a segment for that speaker; lines without a colon
     * are appended to the previous segment (or attributed to "SPEAKER_00").
     */
    private static TranscriptResult parseTranscript(String raw) {
        List<SpeakerSegment> segments = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon > 0 && colon <= 40) {
                String speaker = trimmed.substring(0, colon).strip();
                String text = trimmed.substring(colon + 1).strip();
                segments.add(new SpeakerSegment(0, 0, speaker, text));
            } else if (!segments.isEmpty()) {
                SpeakerSegment prev = segments.remove(segments.size() - 1);
                segments.add(new SpeakerSegment(prev.start(), prev.end(), prev.speaker(),
                        (prev.text() + " " + trimmed).strip()));
            } else {
                segments.add(new SpeakerSegment(0, 0, "SPEAKER_00", trimmed));
            }
        }
        return new TranscriptResult("unknown", segments);
    }

    /** Accepts either a bare JSON array of scenes or a {"scenes":[...]} object. */
    private List<SceneSpec> parseScenes(String json) throws IOException {
        if (json == null || json.isBlank()) return List.of();
        String trimmed = json.strip();
        if (trimmed.startsWith("[")) {
            return mapper.readValue(trimmed, new TypeReference<List<SceneSpec>>() {});
        }
        SceneList list = mapper.readValue(trimmed, SceneList.class);
        return list.scenes() == null ? List.of() : list.scenes();
    }

    private String toScenesJson(List<SceneSpec> scenes) {
        if (scenes == null || scenes.isEmpty()) return "";
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenes);
        } catch (IOException e) {
            return "";
        }
    }
}








