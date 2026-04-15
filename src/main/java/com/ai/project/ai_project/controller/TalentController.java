package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.TalentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/talent")
public class TalentController {

    private final TalentService talentService;

    public TalentController(TalentService talentService) {
        this.talentService = talentService;
    }

    @GetMapping("/analyze")
    public String analyze(@RequestParam String query) {
        return talentService.analyze(query);
    }
}
