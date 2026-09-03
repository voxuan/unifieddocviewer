package com.xuanvolab.unifieddocviewer.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_vin_fetched", columnList = "vin, fetched_at DESC"),
        @Index(name = "idx_documents_source_docid", columnList = "source_system, document_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vin", nullable = false, length = 17)
    private String vin;

    @Column(name = "source_system", nullable = false, length = 20)
    private String sourceSystem;

    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    @Column(name = "document_type", nullable = false, length = 64)
    private String documentType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "document_url", columnDefinition = "TEXT")
    private String documentUrl;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @CreationTimestamp
    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;
}
