package com.storytelling.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The full, persisted state of a session. Serialized to {dataDir}/{id}/session.json
 * so the pipeline is resumable across restarts. Mutable by design.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Session {

    private String id;
    private String originalAudioFilename;
    private String campaignId; // optional link to a Campaign
    private SessionStatus status = SessionStatus.CREATED;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private String errorMessage;

    private TranscriptResult transcript;
    private List<SpeakerLabel> speakerLabels = new ArrayList<>();
    private String narration;
    private Integer requestedSceneCount; // optional; null = let the model choose (5-12)
    private List<CharacterProfile> characterProfiles = new ArrayList<>();
    private List<SceneSpec> scenes = new ArrayList<>();

    public Session() {
    }

    public Session(String id, String originalAudioFilename) {
        this.id = id;
        this.originalAudioFilename = originalAudioFilename;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    // getters / setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOriginalAudioFilename() { return originalAudioFilename; }
    public void setOriginalAudioFilename(String f) { this.originalAudioFilename = f; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public TranscriptResult getTranscript() { return transcript; }
    public void setTranscript(TranscriptResult transcript) { this.transcript = transcript; }
    public List<SpeakerLabel> getSpeakerLabels() { return speakerLabels; }
    public void setSpeakerLabels(List<SpeakerLabel> speakerLabels) { this.speakerLabels = speakerLabels; }
    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }
    public Integer getRequestedSceneCount() { return requestedSceneCount; }
    public void setRequestedSceneCount(Integer requestedSceneCount) { this.requestedSceneCount = requestedSceneCount; }
    public List<CharacterProfile> getCharacterProfiles() { return characterProfiles; }
    public void setCharacterProfiles(List<CharacterProfile> characterProfiles) { this.characterProfiles = characterProfiles; }
    public List<SceneSpec> getScenes() { return scenes; }
    public void setScenes(List<SceneSpec> scenes) { this.scenes = scenes; }
}



