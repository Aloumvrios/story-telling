package com.storytelling.model;

import java.util.List;

import dev.langchain4j.model.output.structured.Description;

/**
 * A single scene extracted from the narration, with a ready-to-use image prompt.
 * Annotated so langchain4j can drive structured (JSON) output from the LLM.
 */
public record SceneSpec(
        @Description("short evocative scene title") String title,
        @Description("third-person narration of this scene, 2-4 sentences") String narration,
        @Description("a vivid Stable Diffusion image prompt describing the scene visually") String imagePrompt,
        @Description("names of characters present in the scene") List<String> characters,
        @Description("relative path to the generated image file, leave empty") String imagePath) {
}

