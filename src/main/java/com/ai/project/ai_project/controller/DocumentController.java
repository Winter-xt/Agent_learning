package com.ai.project.ai_project.controller;

import com.ai.project.ai_project.service.DocumentLoader;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "http://localhost:5173")
public class DocumentController {
    private static final String DEFAULT_USER_ID = "default-user";

    private final DocumentLoader documentLoader;

    public DocumentController(DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
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

    @GetMapping("/query-resume")
    public String queryResume(@RequestParam String query) {
        try {
            return documentLoader.queryResume(DEFAULT_USER_ID, query);
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

}
