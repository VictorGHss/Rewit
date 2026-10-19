-- ==============================================================================
-- REWIT DATABASE MIGRATION - V31
-- ==============================================================================
-- S3-A.2: Segurança das fotos, storage e unicidade persistente de cadastro de locais
-- Tabelas afetadas: places, place_media, place_media_reports
-- ==============================================================================

-- 1. IDENTIFICAÇÃO DO CRIADOR DO LOCAL
ALTER TABLE places ADD COLUMN IF NOT EXISTS created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_places_created_by_user ON places (created_by_user_id);

-- 2. UNICIDADE PERSISTENTE DE LOCAIS ATIVOS COM NOME E ENDEREÇO NORMALIZADOS
-- Desativa duplicidades legadas pré-existentes antes de criar o índice exclusivo (preservando o registro mais antigo)
WITH ranked_duplicates AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g'),
                            regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g')
               ORDER BY created_at ASC NULLS LAST, id ASC
           ) AS rn
    FROM places
    WHERE status = 'ACTIVE' AND address_text IS NOT NULL AND btrim(address_text) <> ''
)
UPDATE places p
SET status = 'INACTIVE',
    updated_at = NOW()
FROM ranked_duplicates rd
WHERE p.id = rd.id AND rd.rn > 1;

-- Garante concorrência segura contra duplicidades mesmo com slugs diferentes
CREATE UNIQUE INDEX IF NOT EXISTS uq_places_active_normalized_name_address
ON places (
    regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g'),
    regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g')
)
WHERE status = 'ACTIVE' AND address_text IS NOT NULL AND btrim(address_text) <> '';

-- 3. ESTADOS, CAPA E IDEMPOTÊNCIA DE FOTOGRAFIAS DE LOCAL
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS content_sha256 VARCHAR(64);

ALTER TABLE place_media DROP CONSTRAINT IF EXISTS chk_place_media_status;
ALTER TABLE place_media ADD CONSTRAINT chk_place_media_status 
    CHECK (status IN ('ACTIVE', 'REMOVED', 'FLAGGED', 'UNDER_REVIEW'));

-- No máximo uma fotografia primária ativa por local
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_primary_active
ON place_media (place_id)
WHERE is_primary = TRUE AND status = 'ACTIVE';

-- Idempotência de upload por usuário e local
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_user_place_idempotency
ON place_media (user_id, place_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- 4. DENÚNCIAS DE FOTOGRAFIAS DE LOCAL
CREATE TABLE IF NOT EXISTS place_media_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    place_media_id UUID NOT NULL REFERENCES place_media(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(64) NOT NULL,
    detail VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_place_media_reports_reporter UNIQUE (place_media_id, reporter_user_id),
    CONSTRAINT chk_place_media_reports_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_place_media_reports_media ON place_media_reports (place_media_id);

