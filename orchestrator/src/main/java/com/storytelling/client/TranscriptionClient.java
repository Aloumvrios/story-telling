package com.storytelling.client;

import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.storytelling.config.AppProperties;
import com.storytelling.model.TranscriptResult;

/**
 * Calls the Python transcription FastAPI service (faster-whisper + pyannote) to
 * transcribe + diarize an audio file.
 */
@Component
public class TranscriptionClient {

    private final RestClient client;
    private final AppProperties props;

    public TranscriptionClient(RestClient transcriptionRestClient, AppProperties props) {
        this.client = transcriptionRestClient;
        this.props = props;
    }

    public TranscriptResult transcribe(Path audioFile) {
        // MultipartBodyBuilder produces proper per-part headers (Content-Disposition
        // with a filename for the file part) that FastAPI's UploadFile requires.
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new FileSystemResource(audioFile))
                .filename(audioFile.getFileName().toString());
        builder.part("model", props.getTranscription().getModel());
        builder.part("glossary", props.getTranscription().getGlossary());
        MultiValueMap<String, HttpEntity<?>> body = builder.build();

        // NOTE: do NOT set Content-Type manually here. Spring's FormHttpMessageConverter
        // detects the MultiValueMap<String, HttpEntity<?>> body and writes it as
        // multipart/form-data *with a generated boundary*. Forcing
        // MediaType.MULTIPART_FORM_DATA emits a Content-Type without a boundary, leaving
        // the server unable to parse any parts (FastAPI then reports `file` as missing).
        return client.post()
                .uri("/transcribe")
                .body(body)
                .retrieve()
                .body(TranscriptResult.class);
    }
}




