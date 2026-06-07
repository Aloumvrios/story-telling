package com.storytelling.model;

/**
 * User-provided visual description of a character (appearance, clothing,
 * distinguishing features). Injected into the image prompt of every scene the
 * character appears in, so Stable Diffusion renders them consistently.
 */
public record CharacterProfile(String name, String appearance) {
}

