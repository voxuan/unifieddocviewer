package com.xuanvolab.unifieddocviewer.repository;

import com.xuanvolab.unifieddocviewer.model.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    @Query("SELECT d FROM DocumentEntity d WHERE d.vin = :vin AND d.fetchedAt > :threshold ORDER BY d.createdAt DESC")
    List<DocumentEntity> findValidCachedDocuments(@Param("vin") String vin, @Param("threshold") Instant threshold);

    @Modifying
    @Query("DELETE FROM DocumentEntity d WHERE d.fetchedAt < :threshold")
    int deleteExpiredDocuments(@Param("threshold") Instant threshold);
}
