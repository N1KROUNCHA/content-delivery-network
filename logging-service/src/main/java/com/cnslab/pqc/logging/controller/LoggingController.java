package com.cnslab.pqc.logging.controller;

import com.cnslab.pqc.common.dto.LogEvent;
import com.cnslab.pqc.logging.model.AuditLog;
import com.cnslab.pqc.logging.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/logs")
public class LoggingController {

    @Autowired
    private AuditLogRepository logRepository;

    @PostMapping
    public ResponseEntity<?> writeLog(@RequestBody LogEvent event) {
        LocalDateTime time = event.timestamp() != null ? event.timestamp() : LocalDateTime.now();
        AuditLog log = new AuditLog(
                event.serviceName(),
                event.eventType(),
                event.message(),
                event.status(),
                time
        );

        logRepository.save(log);

        // Console logger output for microservices developer debugging
        System.out.printf("[%s] [%s] %s - %s - STATUS: %s%n",
                log.getTimestamp(),
                log.getServiceName(),
                log.getEventType(),
                log.getMessage(),
                log.getStatus());

        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<AuditLog>> getLogs() {
        return ResponseEntity.ok(logRepository.findAllByOrderByTimestampDesc());
    }
}
