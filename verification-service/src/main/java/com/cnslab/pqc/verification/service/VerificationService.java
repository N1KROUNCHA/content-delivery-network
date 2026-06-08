package com.cnslab.pqc.verification.service;

import com.cnslab.pqc.common.crypto.SecurityUtils;
import com.cnslab.pqc.common.dto.LogEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class VerificationService {

    @Value("${keys.dir:./keys}")
    private String keysDir;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingServiceUrl;

    @Autowired
    private RestTemplate restTemplate;

    private PublicKey publicKey;
    private PrivateKey privateKey;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(keysDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File pubFile = new File(dir, "dilithium.pub");
            File privFile = new File(dir, "dilithium.priv");

            if (pubFile.exists() && privFile.exists()) {
                byte[] pubBytes = Files.readAllBytes(pubFile.toPath());
                byte[] privBytes = Files.readAllBytes(privFile.toPath());
                this.publicKey = SecurityUtils.getDilithiumPublicKeyFromBytes(pubBytes);
                this.privateKey = SecurityUtils.getDilithiumPrivateKeyFromBytes(privBytes);
                System.out.println("Loaded existing Dilithium key pair from: " + dir.getAbsolutePath());
            } else {
                KeyPair kp = SecurityUtils.generateDilithiumKeyPair();
                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();

                Files.write(pubFile.toPath(), this.publicKey.getEncoded());
                Files.write(privFile.toPath(), this.privateKey.getEncoded());
                System.out.println("Generated and saved new Dilithium key pair to: " + dir.getAbsolutePath());
            }
            logEvent("SERVICE_STARTUP", "Verification Service initialized with Dilithium key pair", "SUCCESS");
        } catch (Exception e) {
            System.err.println("Failed to initialize Dilithium keys: " + e.getMessage());
            logEvent("SERVICE_STARTUP", "Verification Service failed to initialize: " + e.getMessage(), "FAILURE");
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
    }

    public String sign(byte[] hashBytes) throws Exception {
        byte[] sigBytes = SecurityUtils.signDilithium(this.privateKey, hashBytes);
        return Base64.getEncoder().encodeToString(sigBytes);
    }

    public boolean verify(byte[] hashBytes, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64);
            byte[] pubBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey pub = SecurityUtils.getDilithiumPublicKeyFromBytes(pubBytes);
            return SecurityUtils.verifyDilithium(pub, hashBytes, sigBytes);
        } catch (Exception e) {
            System.err.println("Verification error: " + e.getMessage());
            return false;
        }
    }

    private void logEvent(String eventType, String message, String status) {
        try {
            LogEvent log = new LogEvent(
                    "verification-service",
                    eventType,
                    message,
                    status,
                    LocalDateTime.now()
            );
            restTemplate.postForObject(loggingServiceUrl + "/logs", log, Void.class);
        } catch (Exception e) {
            System.err.println("Failed to write audit log to Logging Service: " + e.getMessage());
        }
    }
}
