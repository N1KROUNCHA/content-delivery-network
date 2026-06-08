package com.cnslab.pqc.common.dto;

public record UploadResponse(
    String fileId,
    String fileName,
    String version,
    String sha256Hash,
    String dilithiumSignature
) {}
