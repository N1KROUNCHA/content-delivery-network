package com.cnslab.pqc.common.dto;

import java.time.LocalDateTime;

public record LogEvent(
    String serviceName,
    String eventType,
    String message,
    String status,
    LocalDateTime timestamp
) {}
