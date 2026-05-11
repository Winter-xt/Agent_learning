package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.DocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "http://localhost:5173")
public class DocumentController {
    private static final String DEFAULT_USER_ID = "default-user";

    private final DocumentLoader documentLoader;
    private final ObjectMapper objectMapper;
    private final Executor aiTaskExecutor;

    public DocumentController(DocumentLoader documentLoader,
                              ObjectMapper objectMapper,
                              @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.documentLoader = documentLoader;
        this.objectMapper = objectMapper;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    @PostMapping(value = "/upload-resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentLoader.UploadResult uploadResume(@RequestPart("file") MultipartFile file) {
        try {
            return documentLoader.loadResume(DEFAULT_USER_ID, file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "解析简历失败", e);
        }
    }

    @PostMapping(value = "/upload-resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentLoader.BatchUploadResult uploadResumes(@RequestPart("files") MultipartFile[] files) {
        try {
            return documentLoader.loadResumes(DEFAULT_USER_ID, files);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "解析简历失败", e);
        }
    }

    @GetMapping(value = "/query-resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryResume(@RequestParam(defaultValue = "default-user") String userId,
                                  @RequestParam String query) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            CompletableFuture.runAsync(
                    () -> documentLoader.queryResumeStream(
                            userId,
                            query,
                            status -> sendEvent(emitter, "status", status),
                            token -> sendEvent(emitter, "token", token),
                            trace -> sendJsonEvent(emitter, "trace", trace),
                            () -> {
                                sendEvent(emitter, "done", "[DONE]");
                                emitter.complete();
                            },
                            error -> {
                                sendEvent(emitter, "error", error.getMessage());
                                emitter.completeWithError(error);
                            }
                    ),
                    aiTaskExecutor
            );
            return emitter;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/resumes")
    public java.util.List<DocumentLoader.ResumeListItem> listResumes() {
        return documentLoader.listResumes(DEFAULT_USER_ID);
    }

    @DeleteMapping("/resumes/{resumeId}")
    public DocumentLoader.DeleteResumeResult deleteResume(@RequestParam(defaultValue = "default-user") String userId,
                                                          @PathVariable Long resumeId) {
        try {
            return documentLoader.deleteResume(userId, resumeId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/resumes/{resumeId}/download")
    public ResponseEntity<ByteArrayResource> downloadResume(@RequestParam(defaultValue = "default-user") String userId,
                                                            @PathVariable Long resumeId) {
        try {
            DocumentLoader.ResumeDownload download = documentLoader.downloadResume(userId, resumeId);
            String contentType = download.contentType() == null || download.contentType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                    : download.contentType();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(download.bytes().length)
                    .header("Content-Disposition", ContentDisposition.attachment()
                            .filename(download.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(new ByteArrayResource(download.bytes()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "下载简历失败", e);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data == null ? "" : data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendJsonEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            sendEvent(emitter, eventName, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

}
