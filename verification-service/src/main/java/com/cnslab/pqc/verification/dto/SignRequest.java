package com.cnslab.pqc.verification.dto;

public record SignRequest(String hash) {
    public String getHash() {
        return hash;
    }
}
