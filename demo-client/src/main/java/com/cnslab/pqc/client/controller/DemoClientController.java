package com.cnslab.pqc.client.controller;

import com.cnslab.pqc.client.service.DemoClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/client-api")
public class DemoClientController {

    @Autowired
    private DemoClientService clientService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        try {
            return clientService.register(request);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            return clientService.login(request);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Login failed: " + e.getMessage());
        }
    }

    @GetMapping("/versions")
    public ResponseEntity<?> getVersions(@RequestHeader("Authorization") String token) {
        try {
            return clientService.getVersions(token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getLogs(@RequestHeader("Authorization") String token) {
        try {
            return clientService.getLogs(token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("version") String version,
                                    @RequestParam("folderName") String folderName,
                                    @RequestHeader("Authorization") String token) {
        try {
            return clientService.upload(file, version, folderName, token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/revoke/{fileId}")
    public ResponseEntity<?> revoke(@PathVariable String fileId,
                                    @RequestHeader("Authorization") String token) {
        try {
            return clientService.revoke(fileId, token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to revoke: " + e.getMessage());
        }
    }

    @PostMapping("/download/{fileId}")
    public ResponseEntity<?> download(@PathVariable String fileId,
                                      @RequestHeader("Authorization") String token) {
        try {
            Map<String, Object> results = clientService.secureDownload(fileId, token);
            return ResponseEntity.ok(results);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Decryption or signature validation failed on the client side: " + e.getMessage());
        } catch (Throwable t) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Client-side secure download failed before completion: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @GetMapping("/download-ready/{downloadToken}")
    public ResponseEntity<Resource> downloadReady(@PathVariable String downloadToken,
                                                  @RequestHeader("Authorization") String token) {
        try {
            DemoClientService.DownloadedFile verifiedFile = clientService.getVerifiedDownload(downloadToken);
            FileSystemResource resource = new FileSystemResource(verifiedFile.path());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(verifiedFile.contentType()))
                    .contentLength(resource.contentLength())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + verifiedFile.fileName().replace("\"", "") + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/security-lab/tamper/{fileId}")
    public ResponseEntity<?> runTamperLab(@PathVariable String fileId,
                                          @RequestHeader("Authorization") String token) {
        try {
            return ResponseEntity.ok(clientService.runSecurityLab(fileId, token));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Security lab failed: " + e.getMessage());
        }
    }

    @PostMapping("/test-gateway")
    public ResponseEntity<?> testGateway(
            @RequestParam("path") String path,
            @RequestParam("method") String method,
            @RequestParam("tokenScenario") String tokenScenario,
            @RequestBody(required = false) String body,
            @RequestHeader(value = "Authorization", required = false) String activeToken,
            HttpServletRequest request) {
        try {
            String clientIp = request.getRemoteAddr();
            // For DDoS Simulation, we can let the UI pass a custom header or just use the remote addr
            String simIp = request.getHeader("X-Simulate-IP");
            if (simIp != null && !simIp.isEmpty()) {
                clientIp = simIp;
            }
            Map<String, Object> results = clientService.testGateway(path, method, tokenScenario, body, activeToken, clientIp);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
