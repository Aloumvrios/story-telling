package com.storytelling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

import com.storytelling.config.AppProperties;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class StoryTellingApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoryTellingApplication.class, args);
    }
}

