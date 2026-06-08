package com.cnslab.pqc.content.controller;

import com.cnslab.pqc.common.dto.UploadResponse;
import com.cnslab.pqc.common.dto.VersionInfo;
import com.cnslab.pqc.content.model.FileMetadata;
import com.cnslab.pqc.content.service.ContentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.cnslab.pqc.common.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/content")
public class ContentController {

    @Autowired
    private ContentService contentService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("version") String version,
                                    @RequestParam("folderName") String folderName,
                                    @RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.replace("Bearer ", "");
            Claims claims = JwtUtils.parseToken(token);
            String uploader = claims.getSubject();
            UploadResponse response = contentService.uploadFile(file, version, uploader, folderName);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Upload error: " + e.getMessage());
        }
    }

    @GetMapping("/versions")
    public ResponseEntity<List<VersionInfo>> getVersions() {
        return ResponseEntity.ok(contentService.getAllVersions());
    }

    @PostMapping("/revoke/{fileId}")
    public ResponseEntity<?> revoke(@PathVariable String fileId) {
        try {
            contentService.revokePackage(fileId);
            return ResponseEntity.ok("Package " + fileId + " revoked successfully.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to revoke: " + e.getMessage());
        }
    }

    // Internal Endpoint: Fetch file metadata
    @GetMapping("/internal/metadata/{fileId}")
    public ResponseEntity<FileMetadata> getInternalMetadata(@PathVariable String fileId) {
        Optional<FileMetadata> metadataOpt = contentService.getMetadata(fileId);
        return metadataOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // Internal Endpoint: Fetch raw file bytes
    @GetMapping(value = "/internal/files/{fileId}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getInternalFileBytes(@PathVariable String fileId) {
        Optional<FileMetadata> metadataOpt = contentService.getMetadata(fileId);
        if (metadataOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            byte[] bytes = contentService.getFileBytes(metadataOpt.get());
            return ResponseEntity.ok(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
