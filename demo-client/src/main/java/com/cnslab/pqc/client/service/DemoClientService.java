package com.cnslab.pqc.client.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.cnslab.pqc.common.crypto.SecurityUtils;
import com.cnslab.pqc.common.jwt.JwtUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DemoClientService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${services.gateway-url:http://localhost:8080/api}")
    private String gatewayUrl;

    @Value("${client.downloads-dir:./client_downloads}")
    private String downloadsDir;

    private final Map<String, DownloadedFile> verifiedDownloads = new ConcurrentHashMap<>();

    public void init() {
        File dir = new File(downloadsDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    // Proxy Auth Register
    public ResponseEntity<?> register(Map<String, String> request) {
        return restTemplate.postForEntity(gatewayUrl + "/auth/register", request, Map.class);
    }

    // Proxy Auth Login
    public ResponseEntity<?> login(Map<String, String> request) {
        return restTemplate.postForEntity(gatewayUrl + "/auth/login", request, Map.class);
    }

    // Proxy List Versions
    public ResponseEntity<?> getVersions(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(gatewayUrl + "/content/versions", HttpMethod.GET, entity, Object[].class);
    }

    // Proxy Get Logs
    public ResponseEntity<?> getLogs(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(gatewayUrl + "/logs", HttpMethod.GET, entity, Object[].class);
    }

    // Proxy Upload File
    public ResponseEntity<?> upload(MultipartFile file, String version, String folderName, String token) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };

        body.add("file", fileResource);
        body.add("version", version);
        body.add("folderName", folderName);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity(gatewayUrl + "/content/upload", requestEntity, Map.class);
    }

    // Client-side secure download simulation with complete Kyber + AES + Dilithium flows
    public Map<String, Object> secureDownload(String fileId, String token) throws Exception {
        init();

        Map<String, Object> logs = new HashMap<>();
        logs.put("step1_kyberKeyGen", "In Progress");

        // 1. Generate client-side Kyber Key Pair
        long start = System.currentTimeMillis();
        KeyPair kyberKeyPair = SecurityUtils.generateKyberKeyPair();
        long end = System.currentTimeMillis();

        String kyberPubKeyBase64 = Base64.getEncoder().encodeToString(kyberKeyPair.getPublic().getEncoded());
        String kyberPrivKeyBase64 = Base64.getEncoder().encodeToString(kyberKeyPair.getPrivate().getEncoded());

        logs.put("step1_kyberKeyGen", "SUCCESS");
        logs.put("kyber_public_key", kyberPubKeyBase64);
        logs.put("kyber_private_key", kyberPrivKeyBase64);
        logs.put("kyber_keygen_time_ms", (end - start));

        // 2. Request secure package from Gateway (routes to Download Service)
        logs.put("step2_requestDownload", "In Progress");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("kyberPublicKey", kyberPubKeyBase64);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

        long downloadStart = System.currentTimeMillis();
        // Deserialize as raw Map to avoid Jackson record deserialization issues with Java records
        ResponseEntity<String> responseEntityStr;
        try {
            responseEntityStr = restTemplate.postForEntity(
                    gatewayUrl + "/download/" + fileId, entity, String.class);
        } catch (HttpStatusCodeException ex) {
            long downloadEndEx = System.currentTimeMillis();
            logs.put("step2_requestDownload", "FAILED");
            logs.put("download_response_time_ms", (downloadEndEx - downloadStart));
            String respBody = ex.getResponseBodyAsString();
            String errMsg = "Downstream service returned HTTP " + ex.getStatusCode() + ": " + (respBody == null || respBody.isEmpty() ? "[no body]" : respBody);
            throw new RuntimeException(errMsg, ex);
        } catch (RestClientException ex) {
            long downloadEndEx = System.currentTimeMillis();
            logs.put("step2_requestDownload", "FAILED");
            logs.put("download_response_time_ms", (downloadEndEx - downloadStart));
            throw new RuntimeException("Download request failed: " + ex.getMessage(), ex);
        }

        long downloadEnd = System.currentTimeMillis();
        logs.put("download_response_time_ms", (downloadEnd - downloadStart));

        if (!responseEntityStr.getStatusCode().is2xxSuccessful() || responseEntityStr.getBody() == null) {
            logs.put("step2_requestDownload", "FAILED");
            throw new RuntimeException("Secure download request failed: empty or non-2xx response");
        }

        String bodyStr = responseEntityStr.getBody();
        Map<String, Object> downloadMap = parseDownloadResponse(bodyStr);

        // Extract fields from the Map
        String encapsulationCiphertext = (String) downloadMap.get("encapsulationCiphertext");
        String encryptedFileB64        = (String) downloadMap.get("encryptedFile");
        String dilithiumSignatureB64   = (String) downloadMap.get("dilithiumSignature");
        String dilithiumPublicKeyB64   = (String) downloadMap.get("dilithiumPublicKey");
        String downloadedFileName      = (String) downloadMap.get("fileName");
        String downloadedVersion       = (String) downloadMap.get("version");

        logs.put("step2_requestDownload", "SUCCESS");
        logs.put("download_response_time_ms", (downloadEnd - downloadStart));
        logs.put("fileName", downloadedFileName);
        logs.put("version", downloadedVersion);
        logs.put("kyber_encapsulation_ciphertext", encapsulationCiphertext);
        logs.put("dilithium_signature", dilithiumSignatureB64);
        logs.put("dilithium_public_key", dilithiumPublicKeyB64);

        // 3. Client Decapsulation (Recover AES key using private key)
        logs.put("step3_decapsulation", "In Progress");
        byte[] encapsulationBytes = Base64.getDecoder().decode(encapsulationCiphertext);

        long decStart = System.currentTimeMillis();
        byte[] sharedSecret = SecurityUtils.decapsulate(kyberKeyPair.getPrivate(), encapsulationBytes);
        long decEnd = System.currentTimeMillis();

        String recoveredAesKeyHex = bytesToHex(sharedSecret);
        logs.put("step3_decapsulation", "SUCCESS");
        logs.put("recovered_aes_key_hex", recoveredAesKeyHex);
        logs.put("decapsulation_time_ms", (decEnd - decStart));

        // 4. Decrypt File Bytes using recovered AES-256 Key
        logs.put("step4_decryption", "In Progress");
        byte[] encryptedFileBytes = Base64.getDecoder().decode(encryptedFileB64);

        long decrStart = System.currentTimeMillis();
        byte[] decryptedFileBytes = SecurityUtils.decryptAES_GCM(encryptedFileBytes, sharedSecret);
        long decrEnd = System.currentTimeMillis();

        logs.put("step4_decryption", "SUCCESS");
        logs.put("decrypted_file_size_bytes", decryptedFileBytes.length);
        logs.put("decryption_time_ms", (decrEnd - decrStart));

        // 5. Verify Dilithium Digital Signature of decrypted file
        logs.put("step5_signatureVerification", "In Progress");
        byte[] fileHashBytes = SecurityUtils.calculateSHA256(decryptedFileBytes);
        String calculatedHashHex = bytesToHex(fileHashBytes);

        byte[] signatureBytes = Base64.getDecoder().decode(dilithiumSignatureB64);
        byte[] dilithiumPubBytes = Base64.getDecoder().decode(dilithiumPublicKeyB64);
        PublicKey dilithiumPublicKey = SecurityUtils.getDilithiumPublicKeyFromBytes(dilithiumPubBytes);

        long sigStart = System.currentTimeMillis();
        boolean isSignatureAuthentic = SecurityUtils.verifyDilithium(dilithiumPublicKey, fileHashBytes, signatureBytes);
        long sigEnd = System.currentTimeMillis();

        logs.put("step5_signatureVerification", isSignatureAuthentic ? "SUCCESS" : "FAILED");
        logs.put("calculated_sha256_hex", calculatedHashHex);
        logs.put("signature_verified", isSignatureAuthentic);
        logs.put("verification_time_ms", (sigEnd - sigStart));

        if (!isSignatureAuthentic) {
            throw new SecurityException("Client-side Alert: File authenticity signature verification failed! Possible supply chain tampering detected.");
        }

        // 6. Save package to Local Downloads Directory
        Path targetPath = Paths.get(downloadsDir).resolve(downloadedFileName);
        Files.write(targetPath, decryptedFileBytes);
        String browserDownloadToken = UUID.randomUUID().toString();
        verifiedDownloads.put(browserDownloadToken, new DownloadedFile(
                targetPath.toAbsolutePath(),
                downloadedFileName,
                "application/octet-stream"
        ));
        logs.put("saved_path", targetPath.toAbsolutePath().toString());
        logs.put("download_file_name", downloadedFileName);
        logs.put("download_content_type", "application/octet-stream");
        logs.put("browser_download_url", "/client-api/download-ready/" + browserDownloadToken);

        return logs;
    }

    public Map<String, Object> runSecurityLab(String fileId, String token) throws Exception {
        Map<String, Object> lab = new HashMap<>();

        KeyPair kyberKeyPair = SecurityUtils.generateKyberKeyPair();
        String kyberPubKeyBase64 = Base64.getEncoder().encodeToString(kyberKeyPair.getPublic().getEncoded());
        lab.put("kyberAlgorithm", kyberKeyPair.getPublic().getAlgorithm());
        lab.put("kyberPublicKeyBytes", kyberKeyPair.getPublic().getEncoded().length);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("kyberPublicKey", kyberPubKeyBase64);

        long networkStart = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.postForEntity(
                gatewayUrl + "/download/" + fileId,
                new HttpEntity<>(requestBody, headers),
                String.class);
        long networkEnd = System.currentTimeMillis();

        lab.put("networkRoute", "demo-client -> api-gateway:8080 -> download-service:8083 -> content/verification services");
        lab.put("gatewayStatus", response.getStatusCode().value());
        lab.put("gatewayRoundTripMs", networkEnd - networkStart);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            lab.put("result", "BLOCKED");
            lab.put("reason", "Gateway or download service rejected the request.");
            return lab;
        }

        Map<String, Object> downloadMap = parseDownloadResponse(response.getBody());
        String encapsulationCiphertext = (String) downloadMap.get("encapsulationCiphertext");
        String encryptedFileB64 = (String) downloadMap.get("encryptedFile");
        String dilithiumSignatureB64 = (String) downloadMap.get("dilithiumSignature");
        String dilithiumPublicKeyB64 = (String) downloadMap.get("dilithiumPublicKey");
        String downloadedFileName = (String) downloadMap.get("fileName");
        String downloadedVersion = (String) downloadMap.get("version");

        byte[] sharedSecret = SecurityUtils.decapsulate(
                kyberKeyPair.getPrivate(),
                Base64.getDecoder().decode(encapsulationCiphertext));
        byte[] decryptedFileBytes = SecurityUtils.decryptAES_GCM(
                Base64.getDecoder().decode(encryptedFileB64),
                sharedSecret);

        byte[] originalHash = SecurityUtils.calculateSHA256(decryptedFileBytes);
        byte[] signatureBytes = Base64.getDecoder().decode(dilithiumSignatureB64);
        PublicKey dilithiumPublicKey = SecurityUtils.getDilithiumPublicKeyFromBytes(
                Base64.getDecoder().decode(dilithiumPublicKeyB64));
        boolean originalVerified = SecurityUtils.verifyDilithium(dilithiumPublicKey, originalHash, signatureBytes);

        byte[] tamperedBytes = Arrays.copyOf(decryptedFileBytes, decryptedFileBytes.length);
        if (tamperedBytes.length > 0) {
            tamperedBytes[0] = (byte) (tamperedBytes[0] ^ 0x01);
        }
        byte[] tamperedHash = SecurityUtils.calculateSHA256(tamperedBytes);
        boolean tamperedVerified = SecurityUtils.verifyDilithium(dilithiumPublicKey, tamperedHash, signatureBytes);

        lab.put("fileName", downloadedFileName);
        lab.put("version", downloadedVersion);
        lab.put("plainBytes", decryptedFileBytes.length);
        lab.put("originalSha256", bytesToHex(originalHash));
        lab.put("tamperedSha256", bytesToHex(tamperedHash));
        lab.put("originalSignatureVerified", originalVerified);
        lab.put("tamperedSignatureVerified", tamperedVerified);
        lab.put("attackDetected", originalVerified && !tamperedVerified);
        lab.put("result", originalVerified && !tamperedVerified ? "ATTACK_DETECTED" : "CHECK_FAILED");

        Map<String, Object> invalidJwtProbe = testGateway(
                "/api/download/" + fileId,
                "POST",
                "INVALID",
                "{\"kyberPublicKey\":\"" + kyberPubKeyBase64 + "\"}",
                token,
                "10.10.10.44");
        lab.put("invalidJwtStatus", invalidJwtProbe.get("responseStatus"));
        lab.put("invalidJwtGatewayAction", invalidJwtProbe.get("gatewayAction"));

        return lab;
    }

    public DownloadedFile getVerifiedDownload(String token) {
        DownloadedFile file = verifiedDownloads.get(token);
        if (file == null || !Files.exists(file.path())) {
            throw new IllegalArgumentException("Verified download is no longer available. Please run secure download again.");
        }
        return file;
    }

    private Map<String, Object> parseDownloadResponse(String bodyStr) {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(200 * 1024 * 1024)
                        .build())
                .build();
        ObjectMapper mapper = new ObjectMapper(jsonFactory);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(bodyStr, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            Map<String, Object> downloadMap = extractJsonFieldsViaRegex(bodyStr);
            if (downloadMap == null) {
                String snippet = bodyStr == null ? "[no body]" : (bodyStr.length() > 1000 ? bodyStr.substring(0, 1000) + "..." : bodyStr);
                throw new RuntimeException("Failed to parse download response as JSON. Raw response: " + snippet, e);
            }
            return downloadMap;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Fallback JSON field extractor using regex when Jackson parser fails.
     * Extracts the 6 required fields from raw JSON string.
     */
    private Map<String, Object> extractJsonFieldsViaRegex(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        String[] fields = {
            "encapsulationCiphertext",
            "encryptedFile",
            "dilithiumSignature",
            "dilithiumPublicKey",
            "fileName",
            "version"
        };

        for (String field : fields) {
            String value = extractJsonField(jsonStr, field);
            if (value == null && !field.equals("version")) {
                // version can be missing, but others are critical
                if (!field.equals("dilithiumSignature") && !field.equals("dilithiumPublicKey")) {
                    return null; // critical field missing
                }
            }
            result.put(field, value);
        }
        return result;
    }

    /**
     * Extract a single field value from JSON using regex.
     * Handles both string values and long base64 strings with special characters.
     */
    private String extractJsonField(String jsonStr, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = jsonStr.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = jsonStr.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = jsonStr.indexOf('"', colonIndex + 1);
        if (valueStart < 0) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart + 1; i < jsonStr.length(); i++) {
            char c = jsonStr.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }

    public record DownloadedFile(Path path, String fileName, String contentType) {}

    public ResponseEntity<?> revoke(String fileId, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.postForEntity(gatewayUrl + "/content/revoke/" + fileId, request, String.class);
    }

    // Interactive API Gateway test proxy method
    public Map<String, Object> testGateway(String path, String method, String tokenScenario, String body, String activeToken, String clientIp) {
        Map<String, Object> result = new java.util.HashMap<>();
        
        // 1. Determine token based on scenario
        String tokenToSend = null;
        String scenarioDesc = "";
        
        if ("VALID".equalsIgnoreCase(tokenScenario)) {
            tokenToSend = activeToken; // includes "Bearer "
            scenarioDesc = "Using active logged-in session token.";
        } else if ("INVALID".equalsIgnoreCase(tokenScenario)) {
            if (activeToken != null && activeToken.startsWith("Bearer ") && activeToken.length() > 20) {
                tokenToSend = activeToken + "X"; // Tamper
            } else {
                tokenToSend = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJmYWtlIiwiZXhwIjoxfQ.invalid_sig_xxxxxxxxxxxxxxxxxxxxxx";
            }
            scenarioDesc = "Using tampered/invalid JWT signature.";
        } else if ("MISSING".equalsIgnoreCase(tokenScenario)) {
            tokenToSend = null;
            scenarioDesc = "No Authorization header sent.";
        } else if ("LACKS_ADMIN".equalsIgnoreCase(tokenScenario)) {
            // Generate valid token with role USER
            String tempUserToken = JwtUtils.generateToken("demo_user_no_admin", "USER");
            tokenToSend = "Bearer " + tempUserToken;
            scenarioDesc = "Using a valid JWT with USER role (lacks ADMIN privilege).";
        }
        
        String targetPath = path.startsWith("/api") ? path.substring(4) : path;
        String fullUrl = gatewayUrl + targetPath;
        
        result.put("requestUrl", fullUrl);
        result.put("requestMethod", method);
        result.put("tokenScenario", tokenScenario);
        result.put("scenarioDescription", scenarioDesc);
        result.put("tokenUsed", tokenToSend != null ? tokenToSend : "None");
        
        HttpHeaders headers = new HttpHeaders();
        if (tokenToSend != null) {
            headers.set("Authorization", tokenToSend);
        }
        if (clientIp != null) {
            headers.set("X-Forwarded-For", clientIp);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, String> requestHeadersLog = new java.util.HashMap<>();
        if (tokenToSend != null) {
            requestHeadersLog.put("Authorization", tokenToSend.substring(0, Math.min(25, tokenToSend.length())) + "...");
        }
        if (clientIp != null) {
            requestHeadersLog.put("X-Forwarded-For", clientIp);
        }
        requestHeadersLog.put("Content-Type", "application/json");
        result.put("sentHeaders", requestHeadersLog);
        
        HttpEntity<String> entity = new HttpEntity<>(body != null ? body : "", headers);
        
        long start = System.currentTimeMillis();
        try {
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
            ResponseEntity<String> response = restTemplate.exchange(fullUrl, httpMethod, entity, String.class);
            long duration = System.currentTimeMillis() - start;
            
            result.put("responseStatus", response.getStatusCode().value());
            result.put("responseStatusText", HttpStatus.valueOf(response.getStatusCode().value()).name());
            result.put("durationMs", duration);
            
            Map<String, String> responseHeadersLog = new java.util.HashMap<>();
            response.getHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    responseHeadersLog.put(k, v.get(0));
                }
            });
            result.put("responseHeaders", responseHeadersLog);
            result.put("responseBody", response.getBody());
            
            if (response.getStatusCode().is2xxSuccessful()) {
                result.put("gatewayAction", "AUTHORIZED: Token validated, routed successfully to the target service.");
            } else {
                result.put("gatewayAction", "PASSED THROUGH: Gateway routed request but target service returned status " + response.getStatusCode().value());
            }
            
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            long duration = System.currentTimeMillis() - start;
            result.put("responseStatus", e.getStatusCode().value());
            result.put("responseStatusText", HttpStatus.valueOf(e.getStatusCode().value()).name());
            result.put("durationMs", duration);
            
            Map<String, String> responseHeadersLog = new java.util.HashMap<>();
            e.getResponseHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    responseHeadersLog.put(k, v.get(0));
                }
            });
            result.put("responseHeaders", responseHeadersLog);
            result.put("responseBody", e.getResponseBodyAsString());
            
            int status = e.getStatusCode().value();
            if (status == 401) {
                result.put("gatewayAction", "BLOCKED BY GATEWAY: 401 Unauthorized. Gateway rejected request due to missing or invalid JWT.");
            } else if (status == 403) {
                result.put("gatewayAction", "BLOCKED BY GATEWAY: 403 Forbidden. Gateway rejected request because endpoint requires ADMIN role.");
            } else if (status == 429) {
                result.put("gatewayAction", "BLOCKED BY GATEWAY: 429 Too Many Requests. Gateway rate limit exceeded for your IP.");
            } else if (status == 404) {
                result.put("gatewayAction", "BLOCKED BY GATEWAY: 404 Not Found. Gateway did not match this route pattern to any microservice.");
            } else {
                result.put("gatewayAction", "ROUTING ERROR: Gateway failed to communicate with the target microservice.");
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            result.put("responseStatus", 500);
            result.put("responseStatusText", "INTERNAL SERVER ERROR");
            result.put("durationMs", duration);
            result.put("gatewayAction", "GATEWAY ERROR: " + e.getMessage());
            result.put("responseBody", e.getMessage());
        }
        
        return result;
    }
}
