package com.storytelling.model;

import java.util.List;

/** The LLM's scene-segmentation output: an ordered list of scenes. */
public record SceneList(List<SceneSpec> scenes) {
}

