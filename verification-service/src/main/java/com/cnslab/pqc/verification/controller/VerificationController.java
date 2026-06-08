package com.cnslab.pqc.verification.controller;

import com.cnslab.pqc.verification.dto.SignRequest;
import com.cnslab.pqc.verification.dto.SignResponse;
import com.cnslab.pqc.verification.dto.VerifyRequest;
import com.cnslab.pqc.verification.dto.VerifyResponse;
import com.cnslab.pqc.verification.service.VerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/verify")
public class VerificationController {

    @Autowired
    private VerificationService verificationService;

    @PostMapping("/sign")
    public ResponseEntity<?> sign(@RequestBody SignRequest request) {
        try {
            byte[] hashBytes = Base64.getDecoder().decode(request.getHash());
            String signature = verificationService.sign(hashBytes);
            String publicKey = verificationService.getPublicKeyBase64();
            return ResponseEntity.ok(new SignResponse(signature, publicKey));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Signing error: " + e.getMessage());
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest request) {
        try {
            byte[] hashBytes = Base64.getDecoder().decode(request.getHash());
            boolean isValid = verificationService.verify(hashBytes, request.getSignature(), request.getPublicKey());
            return ResponseEntity.ok(new VerifyResponse(isValid));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Verification error: " + e.getMessage());
        }
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Collections.singletonMap("publicKey", verificationService.getPublicKeyBase64()));
    }
}
