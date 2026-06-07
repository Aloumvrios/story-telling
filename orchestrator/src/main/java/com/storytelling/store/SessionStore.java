package com.storytelling.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storytelling.config.AppProperties;
import com.storytelling.model.Session;

/**
 * File-based, fully-local persistence. Each session lives in its own folder:
 * <pre>
 *   {dataDir}/{id}/
 *       audio.&lt;ext&gt;        original recording
 *       session.json        serialized {@link Session} state
 *       images/scene_NN.png generated images
 * </pre>
 */
@Component
public class SessionStore {

    private final Path root;
    private final ObjectMapper mapper;

    public SessionStore(AppProperties props, ObjectMapper mapper) {
        // Normalize to an absolute path so directory containment checks (used by
        // delete) are reliable regardless of relative "./data" style configs.
        this.root = Path.of(props.getDataDir()).toAbsolutePath().normalize();
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data dir " + root, e);
        }
    }

    public Path sessionDir(String id) {
        return root.resolve(id);
    }

    public Path imagesDir(String id) {
        return sessionDir(id).resolve("images");
    }

    public Path saveAudio(String id, String filename, InputStream in) {
        try {
            Path dir = sessionDir(id);
            Files.createDirectories(dir);
            String ext = filename != null && filename.contains(".")
                    ? filename.substring(filename.lastIndexOf('.')) : ".bin";
            Path target = dir.resolve("audio" + ext);
            Files.copy(in, target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Path> findAudio(String id) {
        Path dir = sessionDir(id);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("audio")).findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void save(Session session) {
        try {
            Path dir = sessionDir(session.getId());
            Files.createDirectories(dir);
            session.touch();
            // Atomic write: serialize to a temp file in the same dir, then rename it
            // over session.json. This guarantees concurrent readers (the page
            // auto-refreshes while the pipeline saves frequently) always see a
            // complete file — never a truncated/empty one mid-write.
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(session);
            Path target = dir.resolve("session.json");
            Path tmp = dir.resolve("session.json.tmp");
            Files.write(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                // Fallback if the filesystem doesn't support atomic moves.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<Session> load(String id) {
        Path file = sessionDir(id).resolve("session.json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), Session.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Permanently removes a session and all of its files (audio, json, images). */
    public synchronized boolean delete(String id) {
        Path dir = sessionDir(id).toAbsolutePath().normalize();
        // Safety: only delete directories that live directly under the data root.
        if (!dir.startsWith(root) || dir.equals(root) || !Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return !Files.exists(dir);
    }

    public List<Session> listAll() {
        List<Session> sessions = new ArrayList<>();
        if (!Files.isDirectory(root)) return sessions;
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                // Be resilient: a single unreadable/partial session must not blank
                // the entire list (e.g. after an interrupted delete).
                try {
                    load(dir.getFileName().toString()).ifPresent(sessions::add);
                } catch (RuntimeException ignored) {
                    // skip corrupt/partial session folder
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sessions.sort(Comparator.comparing(Session::getCreatedAt).reversed());
        return sessions;
    }
}



