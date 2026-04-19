package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.DocumentLoader;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "http://localhost:5173")
public class DocumentController {

    private final DocumentLoader documentLoader;

    public DocumentController(DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
    }

    @PostMapping(value = "/upload-resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentLoader.UploadResult uploadResume(@RequestParam(defaultValue = "default-user") String userId,
                                                    @RequestPart("file") MultipartFile file) {
        try {
            return documentLoader.loadResume(userId, file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "解析简历失败", e);
        }
    }

    @GetMapping("/query-resume")
    public String queryResume(@RequestParam(defaultValue = "default-user") String userId,
                              @RequestParam String query) {
        try {
            return documentLoader.queryResume(userId, query);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }
}
