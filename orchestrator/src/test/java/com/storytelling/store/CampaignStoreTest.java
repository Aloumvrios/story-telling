package com.storytelling.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.storytelling.config.AppProperties;
import com.storytelling.model.Campaign;
import com.storytelling.model.CampaignCharacter;

class CampaignStoreTest {

    @TempDir
    Path tempDir;

    private CampaignStore store;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new CampaignStore(props, mapper);
    }

    @Test
    void saveAndLoad_roundTripsCharacters() {
        Campaign c = new Campaign("camp1", "Crimson Keep");
        c.setCharacters(List.of(
                new CampaignCharacter("Lyra", "Alice", "a tall elf with silver hair"),
                new CampaignCharacter("Thrain", "Bob", "a stout dwarf with a red beard")));
        store.save(c);

        Campaign loaded = store.load("camp1").orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Crimson Keep");
        assertThat(loaded.getCharacters()).extracting(CampaignCharacter::name)
                .containsExactly("Lyra", "Thrain");
        assertThat(loaded.getCharacters().get(0).appearance()).contains("silver hair");
    }

    @Test
    void load_returnsEmptyForUnknownCampaign() {
        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void listAll_returnsSavedCampaigns_andDeleteRemoves() {
        store.save(new Campaign("c1", "One"));
        store.save(new Campaign("c2", "Two"));

        assertThat(store.listAll()).extracting(Campaign::getId).containsExactlyInAnyOrder("c1", "c2");

        assertThat(store.delete("c1")).isTrue();
        assertThat(store.listAll()).extracting(Campaign::getId).containsExactly("c2");
        assertThat(store.delete("c1")).isFalse();
    }
}

