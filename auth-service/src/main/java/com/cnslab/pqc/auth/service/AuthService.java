package com.cnslab.pqc.auth.service;

import com.cnslab.pqc.auth.model.User;
import com.cnslab.pqc.auth.repository.UserRepository;
import com.cnslab.pqc.auth.util.PasswordHasher;
import com.cnslab.pqc.common.dto.AuthRequest;
import com.cnslab.pqc.common.dto.AuthResponse;
import com.cnslab.pqc.common.dto.LogEvent;
import com.cnslab.pqc.common.dto.ValidationResponse;
import com.cnslab.pqc.common.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${services.logging-url:http://localhost:8085}")
    private String loggingServiceUrl;

    public AuthResponse register(AuthRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            logEvent("REGISTER_FAILURE", "Username already exists: " + request.username(), "FAILURE");
            throw new IllegalArgumentException("Username already exists");
        }

        String salt = PasswordHasher.generateSalt();
        String hashedPassword = PasswordHasher.hashPassword(request.password(), salt);

        String role = request.role() != null ? request.role().toUpperCase() : "USER";
        if (!role.equals("ADMIN") && !role.equals("USER")) {
            role = "USER";
        }

        User user = new User(request.username(), hashedPassword, salt, role);
        userRepository.save(user);

        logEvent("REGISTER_SUCCESS", "Successfully registered user: " + request.username() + " with role: " + role, "SUCCESS");

        String token = JwtUtils.generateToken(user.getUsername(), user.getRole());
        return new AuthResponse(token, user.getUsername(), user.getRole());
    }

    public AuthResponse login(AuthRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.username());

        if (userOpt.isEmpty()) {
            logEvent("LOGIN_FAILURE", "User not found: " + request.username(), "FAILURE");
            throw new IllegalArgumentException("Invalid username or password");
        }

        User user = userOpt.get();
        if (!PasswordHasher.verifyPassword(request.password(), user.getSalt(), user.getPasswordHash())) {
            logEvent("LOGIN_FAILURE", "Incorrect password for user: " + request.username(), "FAILURE");
            throw new IllegalArgumentException("Invalid username or password");
        }

        logEvent("LOGIN_SUCCESS", "User logged in: " + request.username(), "SUCCESS");

        String token = JwtUtils.generateToken(user.getUsername(), user.getRole());
        return new AuthResponse(token, user.getUsername(), user.getRole());
    }

    public ValidationResponse validate(String token) {
        try {
            if (JwtUtils.validateToken(token)) {
                Claims claims = JwtUtils.parseToken(token);
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                return new ValidationResponse(true, username, role);
            }
        } catch (Exception e) {
            // Log parse failure locally
        }
        return new ValidationResponse(false, null, null);
    }

    private void logEvent(String eventType, String message, String status) {
        try {
            LogEvent log = new LogEvent("auth-service", eventType, message, status, LocalDateTime.now());
            restTemplate.postForObject(loggingServiceUrl + "/logs", log, Void.class);
        } catch (Exception e) {
            System.err.println("Failed to write audit log to Logging Service: " + e.getMessage());
        }
    }
}
