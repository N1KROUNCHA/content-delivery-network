package com.cnslab.pqc.common.dto;

import java.time.LocalDateTime;

public record VersionInfo(
    String fileId,
    String fileName,
    String version,
    String sha256Hash,
    String dilithiumSignature,
    String dilithiumPublicKey,
    LocalDateTime uploadTime,
    boolean revoked,
    String uploader,
    String folderName,
    String rollbackSuggestion // New optional field to suggest a fallback version if this one is revoked
) {}
