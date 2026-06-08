package com.cnslab.pqc.common.dto;

public record ValidationResponse(boolean valid, String username, String role) {
    public boolean isValid() {
        return valid;
    }
}
