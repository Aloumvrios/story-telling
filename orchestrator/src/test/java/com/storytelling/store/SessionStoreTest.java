package com.storytelling.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.storytelling.config.AppProperties;
import com.storytelling.model.Session;
import com.storytelling.model.SessionStatus;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SessionStore store;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new SessionStore(props, mapper);
    }

    @Test
    void saveAndLoad_roundTripsState() {
        Session session = new Session("abc123", "session1.wav");
        session.setStatus(SessionStatus.AWAITING_LABELS);
        session.setNarration("Once upon a time...");
        store.save(session);

        Optional<Session> loaded = store.load("abc123");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo("abc123");
        assertThat(loaded.get().getOriginalAudioFilename()).isEqualTo("session1.wav");
        assertThat(loaded.get().getStatus()).isEqualTo(SessionStatus.AWAITING_LABELS);
        assertThat(loaded.get().getNarration()).isEqualTo("Once upon a time...");
    }

    @Test
    void load_returnsEmptyForUnknownSession() {
        assertThat(store.load("does-not-exist")).isEmpty();
    }

    @Test
    void saveAudio_thenFindAudio() {
        byte[] data = "fake audio".getBytes(StandardCharsets.UTF_8);
        store.save(new Session("s1", "rec.mp3"));
        store.saveAudio("s1", "rec.mp3", new ByteArrayInputStream(data));

        Optional<Path> audio = store.findAudio("s1");

        assertThat(audio).isPresent();
        assertThat(audio.get().getFileName().toString()).isEqualTo("audio.mp3");
    }

    @Test
    void listAll_returnsSavedSessions() {
        store.save(new Session("s1", "a.wav"));
        store.save(new Session("s2", "b.wav"));

        List<Session> all = store.listAll();

        assertThat(all).extracting(Session::getId).containsExactlyInAnyOrder("s1", "s2");
    }
}

