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
            Write in past tense, third person. Refer to player characters by their character names.
            Do NOT mention dice, rules, the players, or out-of-character chatter.
            Only narrate the in-world story.

            One participant is usually the Dungeon Master (the narrator and referee).
            The Dungeon Master is NOT a single character: they voice the world itself
            and every non-player character (NPCs) — tavern keepers, merchants, town
            guards, monsters, gods — and they describe the environment, scenery and the
            outcomes of actions. When a line is attributed to the "Dungeon Master",
            treat it as narration or as whichever NPC, creature, or environmental
            description the context implies — never as a single recurring player hero.
            Attribute NPC dialogue to the NPC being voiced, and fold scene and
            environment description naturally into the prose.
            """)
    @UserMessage("""
            Here is the story so far (may be empty):
            ---
            {{storySoFar}}
            ---
            {{dmNote}}
            Continue the chronicle using ONLY this next portion of the transcript.
            Keep it tight: summarize the key events as narrative prose, 1-3 paragraphs.

            Transcript portion:
            ---
            {{chunk}}
            ---
            """)
    String summarizeChunk(@V("storySoFar") String storySoFar, @V("chunk") String chunk, @V("dmNote") String dmNote);

    @SystemMessage("""
            You are a story editor. You split a finished narrative into a sequence of
            distinct visual SCENES suitable for illustration. For each scene produce a
            title, a 2-4 sentence third-person narration, the characters present, and a
            detailed Stable Diffusion image prompt describing setting, characters,
            mood and composition. Leave imagePath empty.

            The "characters present" list must contain only in-world beings actually in
            the scene: player characters and named NPCs (e.g. a tavern keeper, a goblin
            chieftain). NEVER list the "Dungeon Master" as a character — the Dungeon
            Master is the narrator who voices NPCs and the environment, not a being in
            the scene. Attribute NPC actions and dialogue to the NPC itself.
            """)
    @UserMessage("""
            {{dmNote}}
            Split the following chronicle into 5 to 12 illustratable scenes.

            Chronicle:
            ---
            {{narration}}
            ---
            """)
    SceneList segmentScenes(@V("narration") String narration, @V("dmNote") String dmNote);

    @SystemMessage("""
            You are a story editor. You split a finished narrative into a sequence of
            distinct visual SCENES suitable for illustration. For each scene produce a
            title, a 2-4 sentence third-person narration, the characters present, and a
            detailed Stable Diffusion image prompt describing setting, characters,
            mood and composition. Leave imagePath empty.

            The "characters present" list must contain only in-world beings actually in
            the scene: player characters and named NPCs (e.g. a tavern keeper, a goblin
            chieftain). NEVER list the "Dungeon Master" as a character — the Dungeon
            Master is the narrator who voices NPCs and the environment, not a being in
            the scene. Attribute NPC actions and dialogue to the NPC itself.
            """)
    @UserMessage("""
            {{dmNote}}
            Split the following chronicle into exactly {{count}} illustratable scenes,
            distributing the narrative as evenly as possible across them.

            Chronicle:
            ---
            {{narration}}
            ---
            """)
    SceneList segmentScenesInto(@V("narration") String narration, @V("count") int count, @V("dmNote") String dmNote);
}


