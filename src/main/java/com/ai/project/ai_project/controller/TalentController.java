package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.TalentService;
import dev.langchain4j.service.TokenStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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
    public SseEmitter analyzeStream(@RequestParam(defaultValue = "default-user") String userId,
                                    @RequestParam String query) {
        SseEmitter emitter = new SseEmitter(0L);
        TokenStream tokenStream = talentService.analyzeStream(userId, query);

        tokenStream
                .onPartialResponse(partial -> sendEvent(emitter, "token", partial))
                .onCompleteResponse(response -> {
                    sendEvent(emitter, "done", "[DONE]");
                    emitter.complete();
                })
                .onError(error -> {
                    sendEvent(emitter, "error", error.getMessage());
                    emitter.completeWithError(error);
                })
                .start();

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data == null ? "" : data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
