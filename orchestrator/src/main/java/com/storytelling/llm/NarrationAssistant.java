package com.storytelling.llm;

import com.storytelling.model.SceneList;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * langchain4j-backed assistant. Two responsibilities:
 *  1) map-reduce summarization of a long transcript into a flowing third-person narration
 *  2) segmentation of that narration into discrete scenes with image prompts (structured output)
 */
public interface NarrationAssistant {

    @SystemMessage("""
            You are a masterful fantasy chronicler. You turn raw, messy tabletop RPG
            (Dungeons & Dragons) session transcripts into vivid third-person prose.
            Write in past tense, third person. Refer to characters by their character names.
            Do NOT mention dice, rules, the players, or out-of-character chatter.
            Only narrate the in-world story.
            """)
    @UserMessage("""
            Here is the story so far (may be empty):
            ---
            {{storySoFar}}
            ---
            Continue the chronicle using ONLY this next portion of the transcript.
            Keep it tight: summarize the key events as narrative prose, 1-3 paragraphs.

            Transcript portion:
            ---
            {{chunk}}
            ---
            """)
    String summarizeChunk(@V("storySoFar") String storySoFar, @V("chunk") String chunk);

    @SystemMessage("""
            You are a story editor. You split a finished narrative into a sequence of
            distinct visual SCENES suitable for illustration. For each scene produce a
            title, a 2-4 sentence third-person narration, the characters present, and a
            detailed Stable Diffusion image prompt describing setting, characters,
            mood and composition. Leave imagePath empty.
            """)
    @UserMessage("""
            Split the following chronicle into 5 to 12 illustratable scenes.

            Chronicle:
            ---
            {{narration}}
            ---
            """)
    SceneList segmentScenes(@V("narration") String narration);
}

