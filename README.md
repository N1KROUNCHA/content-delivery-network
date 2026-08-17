# PQC-Secured Package Delivery Network

> A post-quantum cryptography (PQC) hardened microservices PDN built with Java/Spring Boot.  
> Files are signed with **CRYSTALS-Dilithium (ML-DSA)**, encrypted in transit with **CRYSTALS-Kyber (ML-KEM) + AES-256-GCM**, and delivered through a rate-limited API gateway with JWT authentication.

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://docs.docker.com/compose/)
[![BouncyCastle](https://img.shields.io/badge/BouncyCastle-PQC-purple)](https://www.bouncycastle.org/)

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services](#services)
- [Technology Stack](#technology-stack)
- [Security Design](#security-design)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Environment Variables](#environment-variables)
- [API Reference](#api-reference)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Key Generation](#key-generation)
- [Project Structure](#project-structure)

---

## Overview

This project implements a secure content distribution system designed to be resistant to quantum computing attacks. Content (packages, files) is:

1. **Signed** by the server using CRYSTALS-Dilithium (NIST ML-DSA) before storage
2. **Verified** on every download request — tampered files are rejected
3. **Encrypted** in transit using a Kyber KEM + AES-256-GCM hybrid scheme
4. **Access-controlled** via JWT tokens with role-based authorization (USER / ADMIN)
5. **Rate-limited** at the gateway using Redis-backed Bucket4j (distributed sliding window)
6. **Audited** — every significant operation is logged to a dedicated audit service

---

## Architecture

```
                        ┌───────────────────────────────┐
  Client ──HTTPS──▶     │         Nginx Reverse Proxy    │  :443 / :80
                        └──────────────┬────────────────┘
                                       │
                                       ▼
                        ┌─────────────────────────────────┐
                        │          Gateway Service         │  :8080
                        │  • JWT validation                │
                        │  • Role-based authorization      │
                        │  • Redis rate limiting (Bucket4j)│
                        └──┬──────┬──────┬────────────────┘
                           │      │      │
              ┌────────────┘      │      └───────────────┐
              ▼                   ▼                       ▼
   ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
   │   Auth Service   │  │  Content Service │  │ Download Service │
   │  :8081           │  │  :8082           │  │  :8083           │
   │  • Register/Login│  │  • Upload files  │  │  • Kyber KEM     │
   │  • JWT issue     │  │  • Sign w/ Dilith│  │  • AES-GCM dec   │
   │  • BCrypt pass   │  │  • S3 storage    │  │  • Verify sig    │
   └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
            │                     │                       │
            │              ┌──────┴──────┐                │
            │              │             │                 │
            ▼              ▼             ▼                 ▼
   ┌──────────────┐  ┌──────────┐ ┌──────────────────────────────┐
   │  PostgreSQL  │  │  AWS S3  │ │     Verification Service     │
   │  (auth_db,   │  │ (files)  │ │  :8084                       │
   │   content_db,│  └──────────┘ │  • Dilithium sign/verify     │
   │   logging_db)│               │  • Key management            │
   └──────────────┘               └──────────────────────────────┘
                                          │
                        ┌─────────────────┘
                        ▼
              ┌─────────────────────┐        ┌───────────┐
              │   Logging Service   │        │   Redis   │
              │  :8085              │        │  :6379    │
              │  • Audit log events │        │  • Rate   │
              │  • PostgreSQL store │        │    limit  │
              └─────────────────────┘        └───────────┘
```

---

## Services

| Service | Port | Description |
|---|---|---|
| **gateway-service** | 8080 | API gateway — routes requests, validates JWT, enforces rate limits |
| **auth-service** | 8081 | User registration, login, JWT token issuance |
| **content-service** | 8082 | File upload, Dilithium signing, S3 storage, metadata DB |
| **download-service** | 8083 | Kyber KEM encapsulation, AES-GCM encryption, file delivery |
| **verification-service** | 8084 | Dilithium key management, sign/verify operations |
| **logging-service** | 8085 | Audit event collection and storage |
| **demo-client** | 8087 | Demonstration client showing the full upload/download flow |
| **nginx** | 80/443 | TLS termination, reverse proxy |
| **postgres-db** | 5433 | PostgreSQL 15 — three databases (auth, content, logging) |
| **redis** | 6379 | Distributed rate limiting storage |

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| PQC Library | Bouncy Castle PQC (BCPQC) |
| KEM Algorithm | CRYSTALS-Kyber 768 (ML-KEM) |
| Signature Algorithm | CRYSTALS-Dilithium 3 (ML-DSA) |
| Symmetric Encryption | AES-256-GCM |
| Authentication | JWT (HMAC-SHA256) |
| Rate Limiting | Bucket4j + Lettuce (Redis) |
| Database | PostgreSQL 15 |
| Cache / Rate Store | Redis 7 |
| Object Storage | AWS S3 (or MinIO-compatible) |
| Container Runtime | Docker / Docker Compose |
| Orchestration | Kubernetes (manifests in `kubernetes/`) |
| Reverse Proxy | Nginx (TLS termination) |

---

## Security Design

### Post-Quantum Cryptography

The system uses two NIST-standardized post-quantum algorithms:

| Algorithm | Purpose | Parameter Set |
|---|---|---|
| **CRYSTALS-Kyber (ML-KEM)** | Key Encapsulation Mechanism — establishes a shared AES key without transmitting it | Kyber-768 |
| **CRYSTALS-Dilithium (ML-DSA)** | Digital signatures — ensures file integrity and authenticity | Dilithium-3 |

**Download flow:**
1. Client requests a file
2. Client sends its Kyber public key to the download service
3. Download service encapsulates an AES-256 session key using the client's Kyber public key
4. The file is AES-256-GCM encrypted with the session key
5. Both the encapsulated key ciphertext and encrypted file are sent back
6. Client decapsulates using its Kyber private key to recover the AES key, then decrypts the file
7. File signature (Dilithium) is verified before decryption is presented to the user

### Authentication & Authorization

- Passwords hashed with BCrypt (work factor 12)
- JWT tokens signed with HMAC-SHA256 (24-hour expiry)
- Two roles: `USER` (download) and `ADMIN` (upload, revoke)

### Rate Limiting

- Gateway enforces per-IP rate limits using Bucket4j with Redis backend
- Falls back to in-memory sliding window if Redis is unavailable
- Configurable via `RATE_LIMIT_RPS` and `RATE_LIMIT_BURST` env vars

---

## Prerequisites

- **Java 21** (`java -version`)
- **Maven 3.9+** (`mvn -version`)
- **Docker & Docker Compose** (`docker -version`, `docker compose version`)
- **AWS S3 bucket** (or a MinIO instance for local development)
- TLS certificate and key in `nginx/certs/` (see [Key Generation](#key-generation))
- Dilithium key pair in `keys/` (see [Key Generation](#key-generation))

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/N1KROUNCHA/content-delivery-network.git
cd content-delivery-network
```

### 2. Configure environment variables

```bash
cp .env.example .env
# Edit .env and fill in your actual values
nano .env
```

### 3. Generate cryptographic keys

**Dilithium signing keys** (required by `verification-service`):

```bash
# Generate using the verification-service on first startup
# It will auto-generate if keys/dilithium.priv is missing.
# Or generate manually:
#   mvn -pl pqc-common exec:java -Dexec.mainClass="com.cnslab.pqc.common.KeyGenTool"
```

**TLS certificate** (required by nginx):

```bash
cd nginx/certs
openssl req -x509 -newkey rsa:4096 \
  -keyout server.key -out server.crt \
  -days 365 -nodes \
  -subj "/CN=localhost"
cd ../..
```

### 4. Build all services

```bash
mvn clean install -DskipTests
```

### 5. Start the system

```bash
# Load env and start all containers
docker compose --env-file .env up --build
```

### 6. Verify

```bash
# Health check — gateway should respond
curl http://localhost:8080/api/auth/login -d '{"username":"test","password":"test"}' \
  -H "Content-Type: application/json"
```

The demo client is available at **http://localhost:8087**.

---

## Environment Variables

Copy `.env.example` to `.env` and set the following:

| Variable | Required | Description |
|---|---|---|
| `DB_USERNAME` | Yes | PostgreSQL username (default: `postgres`) |
| `DB_PASSWORD` | **Yes** | PostgreSQL password |
| `AWS_S3_ACCESS_KEY` | **Yes** | AWS IAM access key ID |
| `AWS_S3_SECRET_KEY` | **Yes** | AWS IAM secret access key |
| `AWS_S3_REGION` | Yes | AWS region (e.g. `ap-south-2`) |
| `AWS_S3_BUCKET_NAME` | **Yes** | S3 bucket name |
| `AWS_S3_ENDPOINT` | No | Custom S3 endpoint (MinIO, etc.) — leave empty for real AWS |
| `RATE_LIMIT_RPS` | No | Requests per second per IP (default: `5`) |
| `RATE_LIMIT_BURST` | No | Burst capacity (default: `5`) |

> **Note**: The `keys/` directory is mounted as a Docker volume into `verification-service`. Place your Dilithium key files there — they are gitignored and must never be committed.

---

## API Reference

All routes go through the gateway at `http://localhost:8080/api`.

### Authentication (`/api/auth/`) — No JWT required

| Method | Endpoint | Body | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | `{"username":"...", "password":"...", "role":"USER"}` | Register a new user |
| `POST` | `/api/auth/login` | `{"username":"...", "password":"..."}` | Login — returns JWT token |

### Content (`/api/content/`) — JWT required

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/content/upload` | ADMIN | Upload a file (multipart/form-data). Signs with Dilithium and stores to S3 |
| `GET` | `/api/content/list` | USER | List all available files with metadata |
| `DELETE` | `/api/content/revoke/{fileId}` | ADMIN | Revoke/delete a file |

### Download (`/api/download/`) — JWT required

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/download/{fileId}` | USER | Download a file. Send your Kyber public key in the request body; receive Kyber-encrypted AES key + encrypted file |

### Logs (`/api/logs`) — JWT required

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/logs` | USER | Retrieve audit log events |

---

## Kubernetes Deployment

Kubernetes manifests are provided in the `kubernetes/` directory.

```bash
# Apply verification-service deployment
kubectl apply -f kubernetes/verification-service.yaml

# The verification-service uses a PersistentVolumeClaim for the keys/ directory.
# Pre-load your Dilithium keys into the PVC before starting:
kubectl cp keys/dilithium.priv <pod-name>:/app/keys/dilithium.priv
kubectl cp keys/dilithium.pub  <pod-name>:/app/keys/dilithium.pub
```

> Additional manifests for the remaining services follow the same pattern.  
> Use Kubernetes Secrets for all environment variables (`DB_PASSWORD`, `AWS_S3_SECRET_KEY`, etc.).

---

## Key Generation

### Dilithium Key Pair

The `verification-service` will auto-generate a Dilithium-3 key pair on first startup if `keys/dilithium.priv` is not found. The generated keys are written to the `keys/` directory (mounted volume).

To generate manually, use `SecurityUtils.generateDilithiumKeyPair()` from the `pqc-common` module.

### Kyber Key Pair

Kyber key pairs are **ephemeral per download session** — generated by the demo client for each download request. No persistent Kyber keys need to be managed.

---

## Project Structure

```
content-delivery-network/
├── pqc-common/              # Shared library: Kyber, Dilithium, AES-GCM, JWT utils
│   └── src/main/java/com/cnslab/pqc/common/
│       ├── crypto/SecurityUtils.java   # PQC & AES cryptographic operations
│       ├── jwt/JwtUtils.java           # JWT generation & validation
│       └── dto/                        # Shared DTOs (LogEvent, etc.)
│
├── auth-service/            # :8081 — User auth & JWT issuance
├── content-service/         # :8082 — File upload, Dilithium signing, S3
├── download-service/        # :8083 — Kyber KEM, AES-GCM delivery
├── verification-service/    # :8084 — Dilithium key management & verification
├── logging-service/         # :8085 — Audit log storage
├── gateway-service/         # :8080 — API gateway, rate limiting, routing
├── demo-client/             # :8087 — End-to-end demonstration client
│
├── nginx/
│   ├── nginx.conf           # Reverse proxy & TLS configuration
│   └── certs/               # TLS cert & key (gitignored — generate locally)
│
├── kubernetes/              # Kubernetes deployment manifests
├── postgres-init/           # PostgreSQL initialization SQL scripts
├── keys/                    # Dilithium key files (gitignored — generate locally)
│
├── docker-compose.yml       # Full stack local deployment
├── .env.example             # Environment variable template (copy to .env)
├── pom.xml                  # Maven parent POM
└── README.md
```

---

## License

This project is developed for research purposes in the **Computer Networks & Security Lab (CNSLAB)**.

---
