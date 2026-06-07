package com.storytelling.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A campaign: a reusable set of characters defined up front (before any audio is
 * imported). Sessions can be linked to a campaign so the labeling step can offer
 * its characters in a dropdown and their appearances flow through to image gen.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Campaign {

    private String id;
    private String name;
    private Instant createdAt = Instant.now();
    private List<CampaignCharacter> characters = new ArrayList<>();

    public Campaign() {
    }

    public Campaign(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<CampaignCharacter> getCharacters() { return characters; }
    public void setCharacters(List<CampaignCharacter> characters) { this.characters = characters; }
}

