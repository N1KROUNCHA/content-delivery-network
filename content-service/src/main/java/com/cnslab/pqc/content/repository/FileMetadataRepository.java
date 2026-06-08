package com.cnslab.pqc.content.repository;

import com.cnslab.pqc.content.model.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, String> {
    List<FileMetadata> findByFileName(String fileName);
    Optional<FileMetadata> findByFileNameAndVersion(String fileName, String version);
}
