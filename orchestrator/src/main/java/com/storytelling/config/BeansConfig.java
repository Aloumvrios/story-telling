package com.storytelling.config;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import com.storytelling.llm.NarrationAssistant;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;

@Configuration
public class BeansConfig {

    /**
     * Local LLM via Ollama. Requires `ollama serve` running and the model pulled
     * (e.g. `ollama pull llama3.1:8b`).
     */
    @Bean
    public ChatLanguageModel chatLanguageModel(AppProperties props) {
        return OllamaChatModel.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .modelName(props.getLlm().getModel())
                .temperature(props.getLlm().getTemperature())
                .timeout(Duration.ofMinutes(10))
                .build();
    }

    /** langchain4j structured-output service used for narration + scene segmentation. */
    @Bean
    public NarrationAssistant narrationAssistant(ChatLanguageModel model) {
        return AiServices.create(NarrationAssistant.class, model);
    }

    @Bean
    public RestClient transcriptionRestClient(AppProperties props) {
        return RestClient.builder()
                .baseUrl(props.getTranscription().getBaseUrl())
                .requestFactory(requestFactory(
                        props.getTranscription().getConnectTimeoutSeconds(),
                        props.getTranscription().getReadTimeoutSeconds()))
                .build();
    }

    @Bean
    public RestClient imageRestClient(AppProperties props) {
        return RestClient.builder()
                .baseUrl(props.getImage().getBaseUrl())
                .requestFactory(requestFactory(
                        props.getImage().getConnectTimeoutSeconds(),
                        props.getImage().getReadTimeoutSeconds()))
                .build();
    }

    /**
     * Builds an Apache HttpComponents request factory with explicit connect/read
     * timeouts. The read timeout maps to HttpClient5's connection socket timeout,
     * whose default is only 3 minutes — too short for long transcription/image jobs.
     */
    private static HttpComponentsClientHttpRequestFactory requestFactory(int connectSeconds, int readSeconds) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectSeconds))
                .setSocketTimeout(Timeout.ofSeconds(readSeconds))
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    /** Single-worker executor: long ML jobs run one at a time on a laptop. */
    @Bean("pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("pipeline-");
        exec.initialize();
        return exec;
    }
}





