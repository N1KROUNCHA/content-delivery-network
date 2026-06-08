package com.cnslab.pqc.verification.dto;

public record VerifyRequest(String hash, String signature, String publicKey) {
    public String getHash() {
        return hash;
    }
    public String getSignature() {
        return signature;
    }
    public String getPublicKey() {
        return publicKey;
    }
}
