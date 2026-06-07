package com.storytelling.store;

import java.io.IOException;
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
import com.storytelling.model.Campaign;

/**
 * File-based persistence for campaigns. Each campaign is a JSON document under
 * {dataDir}/campaigns/{id}.json — independent of session storage.
 */
@Component
public class CampaignStore {

    private final Path root;
    private final ObjectMapper mapper;

    public CampaignStore(AppProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.getDataDir()).toAbsolutePath().normalize().resolve("campaigns");
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create campaigns dir " + root, e);
        }
    }

    private Path file(String id) {
        return root.resolve(id + ".json");
    }

    public synchronized void save(Campaign campaign) {
        try {
            Files.createDirectories(root);
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(campaign);
            Path target = file(campaign.getId());
            Path tmp = root.resolve(campaign.getId() + ".json.tmp");
            Files.write(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<Campaign> load(String id) {
        if (id == null) return Optional.empty();
        Path f = file(id);
        if (!Files.exists(f)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(f.toFile(), Campaign.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Campaign> listAll() {
        List<Campaign> campaigns = new ArrayList<>();
        if (!Files.isDirectory(root)) return campaigns;
        try (var stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                    load(id).ifPresent(campaigns::add);
                } catch (RuntimeException ignored) {
                    // skip corrupt campaign file
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        campaigns.sort(Comparator.comparing(Campaign::getCreatedAt).reversed());
        return campaigns;
    }

    public synchronized boolean delete(String id) {
        try {
            return Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

