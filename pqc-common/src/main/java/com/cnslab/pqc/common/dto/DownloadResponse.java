package com.cnslab.pqc.common.dto;

public record DownloadResponse(
    String encapsulationCiphertext,
    String encryptedFile,
    String dilithiumSignature,
    String dilithiumPublicKey,
    String fileName,
    String version
) {}
