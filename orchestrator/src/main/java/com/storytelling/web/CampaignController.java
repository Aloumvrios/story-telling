package com.storytelling.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.storytelling.model.Campaign;
import com.storytelling.model.CampaignCharacter;
import com.storytelling.store.CampaignStore;

/**
 * CRUD for campaigns and their characters. Characters defined here are offered
 * in the session labeling dropdown and their appearances flow to image gen.
 */
@Controller
public class CampaignController {

    private final CampaignStore store;

    public CampaignController(CampaignStore store) {
        this.store = store;
    }

    @GetMapping("/campaigns")
    public String list(Model model) {
        model.addAttribute("campaigns", store.listAll());
        return "campaigns";
    }

    @PostMapping("/campaigns")
    public String create(@RequestParam("name") String name, RedirectAttributes ra) {
        if (name == null || name.isBlank()) {
            ra.addFlashAttribute("error", "Please give the campaign a name.");
            return "redirect:/campaigns";
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        store.save(new Campaign(id, name.strip()));
        return "redirect:/campaigns/" + id;
    }

    @GetMapping("/campaigns/{id}")
    public String view(@PathVariable String id, Model model) {
        Campaign campaign = store.load(id).orElseThrow();
        model.addAttribute("campaign", campaign);
        return "campaign";
    }

    /** Replace the campaign's character list from the parallel form arrays. */
    @PostMapping("/campaigns/{id}/characters")
    public String saveCharacters(@PathVariable String id,
                                 @RequestParam(value = "charName", required = false) List<String> names,
                                 @RequestParam(value = "charPlayer", required = false) List<String> players,
                                 @RequestParam(value = "charAppearance", required = false) List<String> appearances) {
        Campaign campaign = store.load(id).orElseThrow();
        // Preserve any enrolled voiceprints from the existing character of the same name.
        java.util.Map<String, List<float[]>> existingVoiceprints = new java.util.HashMap<>();
        for (CampaignCharacter c : campaign.getCharacters()) {
            existingVoiceprints.put(c.name().toLowerCase(), c.voiceprints());
        }
        List<CampaignCharacter> characters = new ArrayList<>();
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (name == null || name.isBlank()) continue;
                String player = at(players, i);
                String appearance = at(appearances, i);
                List<float[]> prints = existingVoiceprints.getOrDefault(name.strip().toLowerCase(), new ArrayList<>());
                characters.add(new CampaignCharacter(name.strip(),
                        player == null ? "" : player.strip(),
                        appearance == null ? "" : appearance.strip(),
                        prints));
            }
        }
        campaign.setCharacters(characters);
        store.save(campaign);
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        if (store.delete(id)) {
            ra.addFlashAttribute("message", "Deleted campaign " + id + ".");
        } else {
            ra.addFlashAttribute("error", "Could not delete campaign " + id + ".");
        }
        return "redirect:/campaigns";
    }

    private static String at(List<String> list, int i) {
        return (list != null && i < list.size()) ? list.get(i) : null;
    }
}


