package com.cnslab.pqc.download.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cnslab.pqc.common.dto.DownloadResponse;
import com.cnslab.pqc.download.service.DownloadService;

@RestController
@RequestMapping("/download")
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @PostMapping("/{fileId}")
    public ResponseEntity<?> download(@PathVariable String fileId, @RequestBody Map<String, String> request) {
        String kyberPublicKey = request.get("kyberPublicKey");
        if (kyberPublicKey == null || kyberPublicKey.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing required 'kyberPublicKey' in request body");
        }

        try {
            DownloadResponse response = downloadService.downloadAndSecureFile(fileId, kyberPublicKey);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Secure download execution failed: " + e.getMessage());
        }
    }
}
