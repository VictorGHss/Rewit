-- V30__place_nullable_location_and_media.sql
-- Permite cadastro de locais sem coordenadas geográficas ou cidade/estado conhecidos
-- e adiciona armazenamento persistente de fotografias vinculadas diretamente ao local (Place).

ALTER TABLE places ALTER COLUMN city DROP NOT NULL;
ALTER TABLE places ALTER COLUMN state DROP NOT NULL;
ALTER TABLE places ALTER COLUMN coordinates DROP NOT NULL;

CREATE TABLE place_media (
    id UUID PRIMARY KEY,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    object_key VARCHAR(255) NOT NULL,
    media_type VARCHAR(20) NOT NULL DEFAULT 'IMAGE',
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_place_media_object_key UNIQUE (object_key),
    CONSTRAINT chk_place_media_type CHECK (media_type IN ('IMAGE')),
    CONSTRAINT chk_place_media_mime CHECK (mime_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT chk_place_media_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_place_media_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT chk_place_media_dimensions CHECK (width > 0 AND height > 0)
);

CREATE INDEX idx_place_media_place_id_status ON place_media(place_id, status);

