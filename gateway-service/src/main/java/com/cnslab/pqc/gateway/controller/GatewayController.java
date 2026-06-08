package com.cnslab.pqc.gateway.controller;

import com.cnslab.pqc.common.dto.LogEvent;
import com.cnslab.pqc.common.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@RestController
public class GatewayController {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${services.auth-url:http://localhost:8081}")
    private String authUrl;

    @Value("${services.content-url:http://localhost:8082}")
    private String contentUrl;

    @Value("${services.download-url:http://localhost:8083}")
    private String downloadUrl;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingUrl;

    // Rate Limiting Config: 5 requests per second per IP
    private final ConcurrentHashMap<String, Queue<Long>> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_SECOND = 5;

    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        Queue<Long> times = requestCounts.computeIfAbsent(clientIp, k -> new ConcurrentLinkedQueue<>());
        times.add(now);
        // Remove timestamps older than 1 second
        while (!times.isEmpty() && times.peek() < now - 1000) {
            times.poll();
        }
        return times.size() > MAX_REQUESTS_PER_SECOND;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<?> route(HttpServletRequest request, HttpMethod method) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }

        if (isRateLimited(clientIp)) {
            logGatewayFailure(request.getRequestURI(), "Rate limit exceeded for IP: " + clientIp, "RATE_LIMIT_EXCEEDED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Rate limit exceeded. Try again later.");
        }

        String uri = request.getRequestURI();
        String query = request.getQueryString();

        String targetUrl;
        boolean authRequired = true;
        boolean adminOnly = false;

        // Route mapping and authorization config
        if (uri.startsWith("/api/auth/")) {
            targetUrl = authUrl + uri.substring(4);
            authRequired = false;
        } else if (uri.startsWith("/api/content/")) {
            targetUrl = contentUrl + uri.substring(4);
            if (uri.equals("/api/content/upload") || uri.startsWith("/api/content/revoke/")) {
                adminOnly = true;
            }
        } else if (uri.startsWith("/api/download/")) {
            targetUrl = downloadUrl + uri.substring(4);
        } else if (uri.startsWith("/api/logs")) {
            targetUrl = loggingUrl + uri.substring(4);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Route not found");
        }

        if (query != null) {
            targetUrl += "?" + query;
        }

        // Validate Token if required
        if (authRequired) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logGatewayFailure(uri, "Missing or invalid Authorization header", "UNAUTHORIZED");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid Authorization header");
            }
            String token = authHeader.substring(7);
            try {
                if (!JwtUtils.validateToken(token)) {
                    logGatewayFailure(uri, "Token is expired or invalid", "UNAUTHORIZED");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token has expired or is invalid");
                }
                Claims claims = JwtUtils.parseToken(token);
                String role = claims.get("role", String.class);
                if (adminOnly && !"ADMIN".equalsIgnoreCase(role)) {
                    logGatewayFailure(uri, "User " + claims.getSubject() + " lacks ADMIN role", "FORBIDDEN");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: ADMIN role required");
                }
            } catch (Exception e) {
                logGatewayFailure(uri, "Token validation exception: " + e.getMessage(), "UNAUTHORIZED");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token validation error: " + e.getMessage());
            }
        }

        // Forward request
        try {
            return restTemplate.execute(targetUrl, method,
                    clientHttpRequest -> {
                        // Copy request headers
                        Collections.list(request.getHeaderNames()).forEach(headerName -> {
                            if (!headerName.equalsIgnoreCase("content-length") && !headerName.equalsIgnoreCase("host")) {
                                clientHttpRequest.getHeaders().put(headerName, Collections.list(request.getHeaders(headerName)));
                            }
                        });
                        // Copy request body stream
                        request.getInputStream().transferTo(clientHttpRequest.getBody());
                    },
                    clientHttpResponse -> {
                        // Copy response headers and body
                        HttpHeaders responseHeaders = new HttpHeaders();
                        clientHttpResponse.getHeaders().forEach(responseHeaders::put);
                        byte[] bodyBytes = clientHttpResponse.getBody().readAllBytes();
                        return new ResponseEntity<>(bodyBytes, responseHeaders, clientHttpResponse.getStatusCode());
                    }
            );
        } catch (Exception e) {
            logGatewayFailure(uri, "Forwarding connection failed to URL " + targetUrl + ": " + e.getMessage(), "ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Gateway routing exception: " + e.getMessage());
        }
    }

    private void logGatewayFailure(String route, String details, String status) {
        try {
            LogEvent log = new LogEvent(
                    "gateway-service",
                    "GATEWAY_ROUTING_FAILURE",
                    "Route: " + route + " | Details: " + details,
                    status,
                    LocalDateTime.now()
            );
            restTemplate.postForObject(loggingUrl + "/logs", log, Void.class);
        } catch (Exception e) {
            System.err.println("Gateway failed to log routing failure: " + e.getMessage());
        }
    }
}
