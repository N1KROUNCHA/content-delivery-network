package com.cnslab.pqc.content.service;

import com.cnslab.pqc.common.crypto.SecurityUtils;
import com.cnslab.pqc.common.dto.LogEvent;
import com.cnslab.pqc.common.dto.UploadResponse;
import com.cnslab.pqc.common.dto.VersionInfo;
import com.cnslab.pqc.content.model.FileMetadata;
import com.cnslab.pqc.content.repository.FileMetadataRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContentService {

    @Autowired
    private FileMetadataRepository metadataRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    @Value("${services.verification-url:http://localhost:8084}")
    private String verificationServiceUrl;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingServiceUrl;

    @PostConstruct
    public void init() {
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public UploadResponse uploadFile(MultipartFile file, String version, String uploader, String folderName) throws Exception {
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null) {
            throw new IllegalArgumentException("Invalid file name");
        }

        // Check if version already exists for the file
        Optional<FileMetadata> existingOpt = metadataRepository.findByFileNameAndVersion(originalFileName, version);
        if (existingOpt.isPresent()) {
            logEvent("FILE_UPLOAD_FAILURE", "File " + originalFileName + " version " + version + " already exists", "FAILURE");
            throw new IllegalArgumentException("Version " + version + " already exists for file " + originalFileName);
        }

        // Save file physically
        String fileId = UUID.randomUUID().toString();
        String fileExtension = "";
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            fileExtension = originalFileName.substring(dotIndex);
        }
        String storageFileName = fileId + fileExtension;
        Path targetPath = Paths.get(uploadDir).resolve(storageFileName);
        Files.copy(file.getInputStream(), targetPath);

        // Calculate SHA-256
        byte[] fileBytes = file.getBytes();
        byte[] sha256Bytes = SecurityUtils.calculateSHA256(fileBytes);
        String sha256Hex = bytesToHex(sha256Bytes);
        String sha256Base64 = Base64.getEncoder().encodeToString(sha256Bytes);

        // Request Dilithium signature from Verification Service
        String signatureBase64 = "";
        String publicKeyBase64 = "";
        try {
            Map<String, String> signRequest = Collections.singletonMap("hash", sha256Base64);
            ResponseEntity<Map> responseEntity = restTemplate.postForEntity(
                    verificationServiceUrl + "/verify/sign", signRequest, Map.class);
            
            if (responseEntity.getStatusCode().is2xxSuccessful() && responseEntity.getBody() != null) {
                Map<String, String> body = responseEntity.getBody();
                signatureBase64 = body.get("signature");
                publicKeyBase64 = body.get("publicKey");
            } else {
                throw new RuntimeException("Verification Service returned error status");
            }
        } catch (Exception e) {
            // Rollback stored file
            Files.deleteIfExists(targetPath);
            logEvent("FILE_UPLOAD_FAILURE", "Could not obtain Dilithium signature from verification-service: " + e.getMessage(), "FAILURE");
            throw new RuntimeException("Post-Quantum Digital Signing failed: " + e.getMessage(), e);
        }

        // Save metadata
        FileMetadata metadata = new FileMetadata(
                fileId,
                originalFileName,
                version,
                targetPath.toString(),
                sha256Hex,
                signatureBase64,
                publicKeyBase64,
                LocalDateTime.now(),
                false,
                uploader,
                folderName
        );

        metadataRepository.save(metadata);
        logEvent("FILE_UPLOAD_SUCCESS", "Uploaded " + originalFileName + " v" + version + " (Signed with Dilithium)", "SUCCESS");

        return new UploadResponse(
                fileId,
                originalFileName,
                version,
                sha256Hex,
                signatureBase64
        );
    }

    public List<VersionInfo> getAllVersions() {
        List<FileMetadata> allMeta = metadataRepository.findAll();
        
        // Group by filename
        Map<String, List<FileMetadata>> grouped = allMeta.stream()
                .collect(Collectors.groupingBy(FileMetadata::getFileName));
                
        List<VersionInfo> result = new ArrayList<>();
        
        for (Map.Entry<String, List<FileMetadata>> entry : grouped.entrySet()) {
            List<FileMetadata> fileVersions = entry.getValue();
            // Sort versions newest first
            fileVersions.sort(Comparator.comparing(FileMetadata::getUploadTime).reversed());
            
            String rollbackSuggestion = null;
            if (!fileVersions.isEmpty() && fileVersions.get(0).isRevoked()) {
                // Latest is revoked, find the newest unrevoked version
                for (int i = 1; i < fileVersions.size(); i++) {
                    if (!fileVersions.get(i).isRevoked()) {
                        rollbackSuggestion = fileVersions.get(i).getVersion();
                        break;
                    }
                }
            }
            
            for (int i = 0; i < fileVersions.size(); i++) {
                FileMetadata m = fileVersions.get(i);
                result.add(new VersionInfo(
                        m.getId(),
                        m.getFileName(),
                        m.getVersion(),
                        m.getSha256Hash(),
                        m.getDilithiumSignature(),
                        m.getDilithiumPublicKey(),
                        m.getUploadTime(),
                        m.isRevoked(),
                        m.getUploader(),
                        m.getFolderName(),
                        i == 0 ? rollbackSuggestion : null
                ));
            }
        }
        
        // Sort entire result: by folderName ascending, then fileName ascending, then uploadTime descending
        result.sort(Comparator.comparing(VersionInfo::folderName, Comparator.nullsFirst(String::compareTo))
                .thenComparing(VersionInfo::fileName)
                .thenComparing(Comparator.comparing(VersionInfo::uploadTime).reversed()));
                
        return result;
    }

    public Optional<FileMetadata> getMetadata(String fileId) {
        return metadataRepository.findById(fileId);
    }

    public byte[] getFileBytes(FileMetadata metadata) throws IOException {
        return Files.readAllBytes(Paths.get(metadata.getStoragePath()));
    }

    private VersionInfo mapToVersionInfo(FileMetadata m) {
        return new VersionInfo(
                m.getId(),
                m.getFileName(),
                m.getVersion(),
                m.getSha256Hash(),
                m.getDilithiumSignature(),
                m.getDilithiumPublicKey(),
                m.getUploadTime(),
                m.isRevoked(),
                m.getUploader(),
                m.getFolderName(),
                null
        );
    }

    public void revokePackage(String fileId) {
        FileMetadata metadata = metadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File ID not found"));
        metadata.setRevoked(true);
        metadataRepository.save(metadata);
        logEvent("PACKAGE_REVOKED", "Package " + metadata.getFileName() + " (v" + metadata.getVersion() + ") has been revoked by admin.", "SUCCESS");
    }

    private void logEvent(String eventType, String message, String status) {
        try {
            LogEvent log = new LogEvent("content-service", eventType, message, status, LocalDateTime.now());
            restTemplate.postForObject(loggingServiceUrl + "/logs", log, Void.class);
        } catch (Exception e) {
            System.err.println("Failed to write audit log to Logging Service: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
