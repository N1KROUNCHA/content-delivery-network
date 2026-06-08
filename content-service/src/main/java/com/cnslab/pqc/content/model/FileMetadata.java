package com.cnslab.pqc.content.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_metadata")
public class FileMetadata {

    @Id
    private String id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String storagePath;

    @Column(nullable = false)
    private String sha256Hash;

    @Column(columnDefinition = "TEXT")
    private String dilithiumSignature;

    @Column(columnDefinition = "TEXT")
    private String dilithiumPublicKey;

    @Column(nullable = false)
    private LocalDateTime uploadTime;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column
    private String uploader;

    @Column
    private String folderName;

    public FileMetadata() {}

    public FileMetadata(String id, String fileName, String version, String storagePath, 
                        String sha256Hash, String dilithiumSignature, String dilithiumPublicKey, 
                        LocalDateTime uploadTime, boolean revoked, String uploader, String folderName) {
        this.id = id;
        this.fileName = fileName;
        this.version = version;
        this.storagePath = storagePath;
        this.sha256Hash = sha256Hash;
        this.dilithiumSignature = dilithiumSignature;
        this.dilithiumPublicKey = dilithiumPublicKey;
        this.uploadTime = uploadTime;
        this.revoked = revoked;
        this.uploader = uploader;
        this.folderName = folderName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public void setSha256Hash(String sha256Hash) {
        this.sha256Hash = sha256Hash;
    }

    public String getDilithiumSignature() {
        return dilithiumSignature;
    }

    public void setDilithiumSignature(String dilithiumSignature) {
        this.dilithiumSignature = dilithiumSignature;
    }

    public String getDilithiumPublicKey() {
        return dilithiumPublicKey;
    }

    public void setDilithiumPublicKey(String dilithiumPublicKey) {
        this.dilithiumPublicKey = dilithiumPublicKey;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public String getUploader() {
        return uploader;
    }

    public void setUploader(String uploader) {
        this.uploader = uploader;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }
}
