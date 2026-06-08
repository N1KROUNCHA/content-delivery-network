package com.cnslab.pqc.download.service;

import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.cnslab.pqc.common.crypto.SecurityUtils;
import com.cnslab.pqc.common.dto.DownloadResponse;
import com.cnslab.pqc.common.dto.LogEvent;

@Service
public class DownloadService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${services.content-url:http://localhost:8082}")
    private String contentServiceUrl;

    @Value("${services.verification-url:http://localhost:8084}")
    private String verificationServiceUrl;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingServiceUrl;

    public DownloadResponse downloadAndSecureFile(String fileId, String kyberPublicKeyBase64) throws Exception {
        // 1. Fetch metadata from Content Service
        Map<String, Object> metadata;
        try {
            ResponseEntity<Map> metadataResponse = restTemplate.getForEntity(
                    contentServiceUrl + "/content/internal/metadata/" + fileId, Map.class);
            if (!metadataResponse.getStatusCode().is2xxSuccessful() || metadataResponse.getBody() == null) {
                logEvent("FILE_DOWNLOAD_FAILURE", "File ID not found: " + fileId, "FAILURE");
                throw new IllegalArgumentException("File ID not found in catalog");
            }
            metadata = metadataResponse.getBody();
        } catch (Exception e) {
            logEvent("FILE_DOWNLOAD_FAILURE", "Metadata fetch failed for fileId " + fileId + ": " + e.getMessage(), "FAILURE");
            throw new RuntimeException("Content Service unavailable", e);
        }

        Boolean isRevoked = (Boolean) metadata.get("revoked");
        if (Boolean.TRUE.equals(isRevoked)) {
            logEvent("FILE_DOWNLOAD_FAILURE", "Download blocked: Package " + fileId + " has been revoked by an administrator.", "FAILURE");
            throw new SecurityException("SECURITY ALERT: This software package has been revoked by administrators due to a vulnerability. Download blocked.");
        }

        String fileName = (String) metadata.get("fileName");
        String version = (String) metadata.get("version");
        String dilithiumSignature = (String) metadata.get("dilithiumSignature");
        String dilithiumPublicKey = (String) metadata.get("dilithiumPublicKey");

        // 2. Fetch raw file bytes from Content Service
        byte[] fileBytes;
        try {
            ResponseEntity<byte[]> fileBytesResponse = restTemplate.getForEntity(
                    contentServiceUrl + "/content/internal/files/" + fileId, byte[].class);
            if (!fileBytesResponse.getStatusCode().is2xxSuccessful() || fileBytesResponse.getBody() == null) {
                throw new IllegalStateException("Could not retrieve file content bytes");
            }
            fileBytes = fileBytesResponse.getBody();
        } catch (Exception e) {
            logEvent("FILE_DOWNLOAD_FAILURE", "Failed to retrieve bytes for " + fileName + " (v" + version + "): " + e.getMessage(), "FAILURE");
            throw new RuntimeException("Content Service file retrieval failed", e);
        }

        // 3. Verify file integrity and authenticity using Verification Service (Dilithium signature)
        byte[] sha256Bytes = SecurityUtils.calculateSHA256(fileBytes);
        String sha256Base64 = Base64.getEncoder().encodeToString(sha256Bytes);

        boolean signatureValid = false;
        try {
            Map<String, String> verifyReq = new HashMap<>();
            verifyReq.put("hash", sha256Base64);
            verifyReq.put("signature", dilithiumSignature);
            verifyReq.put("publicKey", dilithiumPublicKey);

            ResponseEntity<Map> verifyResponse = restTemplate.postForEntity(
                    verificationServiceUrl + "/verify/verify", verifyReq, Map.class);

            if (verifyResponse.getStatusCode().is2xxSuccessful() && verifyResponse.getBody() != null) {
                signatureValid = (Boolean) verifyResponse.getBody().get("verified");
            }
        } catch (Exception e) {
            logEvent("FILE_DOWNLOAD_FAILURE", "Dilithium verification request failed for " + fileName + " (v" + version + "): " + e.getMessage(), "FAILURE");
            throw new RuntimeException("Verification Service unavailable", e);
        }

        if (!signatureValid) {
            logEvent("FILE_DOWNLOAD_FAILURE", "Dilithium digital signature verification FAILED for " + fileName + " (v" + version + "). Supply chain attack or corruption detected!", "FAILURE");
            throw new SecurityException("Security Exception: Digital signature of the package is INVALID! Verification failed.");
        }

        // 4. Perform Kyber Key Encapsulation
        byte[] userKyberPubBytes = Base64.getDecoder().decode(kyberPublicKeyBase64);
        PublicKey userKyberPublicKey = SecurityUtils.getKyberPublicKeyFromBytes(userKyberPubBytes);

        // Encapsulate random AES key using the user's Kyber Public Key
        SecurityUtils.KyberEncapsulationResult encResult = SecurityUtils.encapsulate(userKyberPublicKey);

        // 5. Encrypt file with AES-256-GCM using the Kyber shared secret
        byte[] encryptedFileBytes = SecurityUtils.encryptAES_GCM(fileBytes, encResult.sharedSecret());

        String encCiphertextBase64 = Base64.getEncoder().encodeToString(encResult.encapsulationCiphertext());
        String encryptedFileBase64 = Base64.getEncoder().encodeToString(encryptedFileBytes);

        logEvent("FILE_DOWNLOAD_SUCCESS", "Securely distributed file " + fileName + " (v" + version + ") using Kyber + AES-GCM", "SUCCESS");

        return new DownloadResponse(
                encCiphertextBase64,
                encryptedFileBase64,
                dilithiumSignature,
                dilithiumPublicKey,
                fileName,
                version
        );
    }

    private void logEvent(String eventType, String message, String status) {
        try {
            LogEvent log = new LogEvent("download-service", eventType, message, status, LocalDateTime.now());
            restTemplate.postForObject(loggingServiceUrl + "/logs", log, Void.class);
        } catch (Exception e) {
            System.err.println("Failed to write audit log to Logging Service: " + e.getMessage());
        }
    }
}
