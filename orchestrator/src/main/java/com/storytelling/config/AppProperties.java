package com.storytelling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration for the pipeline. Override via application.yml or env vars.
 */
@ConfigurationProperties(prefix = "storytelling")
public class AppProperties {

    /** Root folder where each session's workspace (audio, transcript, images) is stored. */
    private String dataDir = "./data";

    private final Transcription transcription = new Transcription();
    private final Image image = new Image();
    private final Llm llm = new Llm();
    private final Narration narration = new Narration();

    public static class Transcription {
        /** Base URL of the Python WhisperX FastAPI service. */
        private String baseUrl = "http://localhost:8001";
        /** Whisper model size: tiny|base|small|medium|large-v3. */
        private String model = "large-v3";
        /** Optional glossary of DnD-specific terms to reduce mis-transcriptions. */
        private String glossary = "";
        /** TCP connect timeout (seconds). */
        private int connectTimeoutSeconds = 10;
        /** Read/response timeout (seconds). Long audio with large-v3 can take a while,
         *  so this defaults high; HttpClient5's own default is only 3 minutes. */
        private int readTimeoutSeconds = 3600;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getGlossary() { return glossary; }
        public void setGlossary(String glossary) { this.glossary = glossary; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
    }

    public static class Image {
        /** Base URL of the Python image-gen FastAPI service (SDXL/ComfyUI wrapper). */
        private String baseUrl = "http://localhost:8002";
        /** Style suffix appended to every scene prompt. */
        private String stylePrompt = "fantasy concept art, painterly, dramatic lighting, highly detailed, artstation";
        private String negativePrompt = "lowres, blurry, text, watermark, deformed";
        /** TCP connect timeout (seconds). */
        private int connectTimeoutSeconds = 10;
        /** Read/response timeout (seconds). SDXL generation can be slow on a laptop. */
        private int readTimeoutSeconds = 1200;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getStylePrompt() { return stylePrompt; }
        public void setStylePrompt(String stylePrompt) { this.stylePrompt = stylePrompt; }
        public String getNegativePrompt() { return negativePrompt; }
        public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
    }

    public static class Llm {
        /** Ollama base URL. */
        private String baseUrl = "http://localhost:11434";
        /** Ollama model name, e.g. llama3.1:8b or qwen2.5:14b. */
        private String model = "llama3.1:8b";
        private double temperature = 0.7;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class Narration {
        /** Approx characters per transcript chunk for the map-reduce summarization. */
        private int chunkSizeChars = 9000;
        /** Character overlap between chunks to preserve continuity. */
        private int chunkOverlapChars = 500;

        public int getChunkSizeChars() { return chunkSizeChars; }
        public void setChunkSizeChars(int chunkSizeChars) { this.chunkSizeChars = chunkSizeChars; }
        public int getChunkOverlapChars() { return chunkOverlapChars; }
        public void setChunkOverlapChars(int chunkOverlapChars) { this.chunkOverlapChars = chunkOverlapChars; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public Transcription getTranscription() { return transcription; }
    public Image getImage() { return image; }
    public Llm getLlm() { return llm; }
    public Narration getNarration() { return narration; }
}


