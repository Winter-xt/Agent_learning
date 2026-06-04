package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.TalentService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/talent")
@CrossOrigin(origins = "http://localhost:5173")
public class TalentController {

    private final TalentService talentService;

    public TalentController(TalentService talentService) {
        this.talentService = talentService;
    }

    @GetMapping("/analyze")
    public String analyze(@RequestParam(defaultValue = "default-user") String userId, @RequestParam String query) {
        return talentService.analyze(userId, query);
    }

    @GetMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> analyzeStream(@RequestParam(defaultValue = "default-user") String userId,
                                                       @RequestParam String query) {
        return talentService.analyzeStream(userId, query)
                .map(token -> ServerSentEvent.builder(token).event("token").build())
                .concatWithValues(ServerSentEvent.builder("[DONE]").event("done").build())
                .onErrorResume(error -> Flux.just(ServerSentEvent.builder(error.getMessage()).event("error").build()));
    }
}
