package com.cnslab.pqc.gateway.controller;

import com.cnslab.pqc.common.dto.LogEvent;
import com.cnslab.pqc.common.jwt.JwtUtils;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.jsonwebtoken.Claims;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.function.Supplier;

@RestController
public class GatewayController {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${services.auth-url:http://localhost:8081}")
    private String authUrl;

    @Value("${services.content-url:http://localhost:8082}")
    private String contentUrl;

    @Value("${services.download-url:http://localhost:8083}")
    private String downloadUrl;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingUrl;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${rate.limit.requests-per-second:10}")
    private int requestsPerSecond;

    @Value("${rate.limit.burst-capacity:20}")
    private int burstCapacity;

    private RedisClient redisClient;
    private StatefulRedisConnection<byte[], byte[]> redisConnection;
    private ProxyManager<byte[]> proxyManager;

    // Fallback in-memory rate limiting if Redis is unavailable
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Queue<Long>> fallbackCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            redisClient = RedisClient.create("redis://" + redisHost + ":" + redisPort);
            redisConnection = redisClient.connect(ByteArrayCodec.INSTANCE);
            proxyManager = LettuceBasedProxyManager.builderFor(redisConnection).build();
            System.out.println("[GatewayService] Redis rate limiter connected: " + redisHost + ":" + redisPort);
        } catch (Exception e) {
            System.err.println("[GatewayService] Redis unavailable, falling back to in-memory rate limiting: " + e.getMessage());
            proxyManager = null;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (redisConnection != null) redisConnection.close();
        if (redisClient != null) redisClient.shutdown();
    }

    private boolean isRateLimited(String clientIp) {
        if (proxyManager != null) {
            // Distributed Redis-backed Bucket4j rate limiting
            byte[] key = ("rl:" + clientIp).getBytes(StandardCharsets.UTF_8);
            Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(burstCapacity)
                            .refillGreedy(requestsPerSecond, Duration.ofSeconds(1))
                            .build())
                    .build();
            Bucket bucket = proxyManager.builder().build(key, configSupplier);
            return !bucket.tryConsume(1);
        } else {
            // Fallback: in-memory sliding window
            long now = System.currentTimeMillis();
            java.util.Queue<Long> times = fallbackCounts.computeIfAbsent(clientIp,
                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
            times.add(now);
            while (!times.isEmpty() && times.peek() < now - 1000) {
                times.poll();
            }
            return times.size() > requestsPerSecond;
        }
    }

    @RequestMapping("/api/**")
    public ResponseEntity<?> route(HttpServletRequest request, HttpMethod method) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        // Take first IP if there are multiple (proxy chain)
        if (clientIp != null && clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }

        if (isRateLimited(clientIp)) {
            logGatewayFailure(request.getRequestURI(), "Rate limit exceeded for IP: " + clientIp, "RATE_LIMIT_EXCEEDED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Limit", String.valueOf(requestsPerSecond))
                    .header("Retry-After", "1")
                    .body("Rate limit exceeded. Max " + requestsPerSecond + " requests/sec. Try again later.");
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
        final String finalTargetUrl = targetUrl;
        try {
            return restTemplate.execute(finalTargetUrl, method,
                    clientHttpRequest -> {
                        Collections.list(request.getHeaderNames()).forEach(headerName -> {
                            if (!headerName.equalsIgnoreCase("content-length") && !headerName.equalsIgnoreCase("host")) {
                                clientHttpRequest.getHeaders().put(headerName, Collections.list(request.getHeaders(headerName)));
                            }
                        });
                        request.getInputStream().transferTo(clientHttpRequest.getBody());
                    },
                    clientHttpResponse -> {
                        HttpHeaders responseHeaders = new HttpHeaders();
                        clientHttpResponse.getHeaders().forEach((headerName, headerValues) -> {
                            if (!headerName.equalsIgnoreCase("transfer-encoding") && !headerName.equalsIgnoreCase("connection")) {
                                responseHeaders.put(headerName, headerValues);
                            }
                        });
                        byte[] bodyBytes = clientHttpResponse.getBody().readAllBytes();
                        return new ResponseEntity<>(bodyBytes, responseHeaders, clientHttpResponse.getStatusCode());
                    }
            );
        } catch (Exception e) {
            logGatewayFailure(uri, "Forwarding connection failed to URL " + finalTargetUrl + ": " + e.getMessage(), "ERROR");
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
