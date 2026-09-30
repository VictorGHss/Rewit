-- ==============================================================================
-- REWIT DATABASE MIGRATION - V9
-- ==============================================================================
-- STEP 21.0: Suporte a Mídias Anexadas a Reviews (Imagens Sanitizadas)
-- Tabela: review_media
-- ==============================================================================

CREATE TABLE review_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    object_key TEXT NOT NULL,
    media_type VARCHAR(32) NOT NULL DEFAULT 'IMAGE',
    mime_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_media_object_key UNIQUE (object_key),
    CONSTRAINT chk_review_media_media_type CHECK (media_type IN ('IMAGE')),
    CONSTRAINT chk_review_media_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_review_media_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT chk_review_media_dimensions CHECK (
        (width IS NULL OR (width > 0 AND width <= 10000)) AND
        (height IS NULL OR (height > 0 AND height <= 10000))
    )
);

CREATE INDEX idx_review_media_review_status ON review_media (review_id, status, created_at ASC, id ASC);
CREATE INDEX idx_review_media_user ON review_media (user_id);
