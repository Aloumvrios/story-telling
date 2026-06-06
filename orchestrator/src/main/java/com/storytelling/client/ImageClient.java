package com.storytelling.client;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.storytelling.config.AppProperties;

/**
 * Calls the Python image-gen FastAPI service (SDXL on MPS / ComfyUI wrapper).
 * Returns raw PNG bytes for a given prompt.
 */
@Component
public class ImageClient {

    private final RestClient client;
    private final AppProperties props;

    public ImageClient(RestClient imageRestClient, AppProperties props) {
        this.client = imageRestClient;
        this.props = props;
    }

    public byte[] generate(String prompt) {
        String fullPrompt = prompt + ", " + props.getImage().getStylePrompt();
        Map<String, Object> body = Map.of(
                "prompt", fullPrompt,
                "negative_prompt", props.getImage().getNegativePrompt());

        return client.post()
                .uri("/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.IMAGE_PNG)
                .body(body)
                .retrieve()
                .body(byte[].class);
    }
}

