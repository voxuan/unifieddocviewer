-- Database initialization script for Unified Document Viewer
CREATE TABLE IF NOT EXISTS documents (
    id            BIGSERIAL PRIMARY KEY,
    vin           VARCHAR(17)   NOT NULL,
    source_system VARCHAR(20)   NOT NULL,   -- 'SALES' | 'SERVICE'
    document_id   VARCHAR(64)   NOT NULL,
    document_type VARCHAR(64)   NOT NULL,
    title         VARCHAR(255),
    created_at    TIMESTAMPTZ,
    document_url  TEXT,
    metadata      JSONB,
    fetched_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Primary query index for VIN lookup within TTL
CREATE INDEX IF NOT EXISTS idx_documents_vin_fetched ON documents (vin, fetched_at DESC);

-- Unique index to prevent duplicate inserts during concurrent fetches or retries
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_source_docid ON documents (source_system, document_id);
