-- ==============================================================================
-- REWIT DATABASE MIGRATION - V31
-- ==============================================================================
-- S3-A.3: Segurança das fotos, storage, auditoria de moderação e unicidade persistente de cadastro de locais
-- Tabelas afetadas: places, place_media, place_media_reports
-- ==============================================================================

-- 1. IDENTIFICAÇÃO DO CRIADOR DO LOCAL
ALTER TABLE places ADD COLUMN IF NOT EXISTS created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_places_created_by_user ON places (created_by_user_id);

-- 2. UNICIDADE PERSISTENTE DE LOCAIS ATIVOS COM NOME E ENDEREÇO NORMALIZADOS
-- Validação defensiva: aborta a migração se existirem duplicatas ativas para evitar
-- desativação arbitrária, perda silenciosa de titularidade comercial ou perda de avaliações.
DO $$
DECLARE
    conflict_count INTEGER;
    conflict_summary TEXT;
BEGIN
    SELECT COUNT(*)
    INTO conflict_count
    FROM (
        SELECT regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g') AS norm_name,
               regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g') AS norm_address
        FROM places
        WHERE status = 'ACTIVE' AND address_text IS NOT NULL AND btrim(address_text) <> ''
        GROUP BY regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g'),
                 regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g')
        HAVING COUNT(*) > 1
    ) conflicts;

    IF conflict_count > 0 THEN
        SELECT string_agg(
            format(
                E'\n[Grupo: nome_norm="%s", endereco_norm="%s"]\n  Locais conflitantes:\n%s',
                grp.norm_name,
                grp.norm_address,
                (
                    SELECT string_agg(
                        format(
                            E'    - id=%s, slug="%s", nome="%s", criado_em=%s, claimed_by_business_id=%s, verificado=%s, avaliacoes=%s, horarios=%s, comodidades=%s, ofertas=%s, midias=%s',
                            p.id,
                            p.slug,
                            p.name,
                            p.created_at,
                            COALESCE(p.claimed_by_business_id::text, 'NULL'),
                            p.is_verified,
                            (SELECT COUNT(DISTINCT rt.review_id) FROM review_targets rt WHERE rt.target_id = p.id),
                            (SELECT COUNT(*) FROM place_opening_hours_schedules oh WHERE oh.place_id = p.id),
                            (SELECT COUNT(*) FROM place_amenity_configurations pa WHERE pa.place_id = p.id),
                            (SELECT COUNT(*) FROM place_product_offers po WHERE po.place_id = p.id),
                            (SELECT COUNT(*) FROM place_media pm WHERE pm.place_id = p.id)
                        ),
                        E'\n'
                        ORDER BY p.created_at ASC, p.id ASC
                    )
                    FROM places p
                    WHERE p.status = 'ACTIVE'
                      AND regexp_replace(btrim(rewit_search_normalize(p.name)), '\s+', ' ', 'g') = grp.norm_name
                      AND regexp_replace(btrim(rewit_search_normalize(p.address_text)), '\s+', ' ', 'g') = grp.norm_address
                )
            ),
            E'\n'
        )
        INTO conflict_summary
        FROM (
            SELECT regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g') AS norm_name,
                   regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g') AS norm_address
            FROM places
            WHERE status = 'ACTIVE' AND address_text IS NOT NULL AND btrim(address_text) <> ''
            GROUP BY regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g'),
                     regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g')
            HAVING COUNT(*) > 1
        ) grp;

        RAISE EXCEPTION E'MIGRATION V31 ABORTADA: Existem % grupo(s) de locais ativos com nome e endereço normalizados idênticos. A migração foi cancelada para evitar desativação arbitrária e perda de titularidade/avaliações.\nDiagnóstico dos conflitos:% \n\nProcedimento de resolução: consulte docs/database/schema-overview.md para instruções sobre resolução manual explícita antes de reaplicar a migração.',
            conflict_count, conflict_summary;
    END IF;
END $$;

-- Garante concorrência segura contra duplicidades mesmo com slugs diferentes
CREATE UNIQUE INDEX IF NOT EXISTS uq_places_active_normalized_name_address
ON places (
    regexp_replace(btrim(rewit_search_normalize(name)), '\s+', ' ', 'g'),
    regexp_replace(btrim(rewit_search_normalize(address_text)), '\s+', ' ', 'g')
)
WHERE status = 'ACTIVE' AND address_text IS NOT NULL AND btrim(address_text) <> '';

-- 3. ESTADOS, CAPA, IDEMPOTÊNCIA E PURGE DE FOTOGRAFIAS DE LOCAL
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS content_sha256 VARCHAR(64);
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE place_media DROP CONSTRAINT IF EXISTS chk_place_media_status;
ALTER TABLE place_media ADD CONSTRAINT chk_place_media_status 
    CHECK (status IN ('ACTIVE', 'REMOVED', 'UNDER_REVIEW'));

-- No máximo uma fotografia primária ativa por local
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_primary_active
ON place_media (place_id)
WHERE is_primary = TRUE AND status = 'ACTIVE';

-- Idempotência de upload por usuário e local
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_user_place_idempotency
ON place_media (user_id, place_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- 4. DENÚNCIAS E AUDITORIA DE MODERAÇÃO DE FOTOGRAFIAS DE LOCAL
CREATE TABLE IF NOT EXISTS place_media_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    place_media_id UUID NOT NULL REFERENCES place_media(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(64) NOT NULL,
    detail VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    decided_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    decision_notes VARCHAR(500),
    CONSTRAINT uq_place_media_reports_reporter UNIQUE (place_media_id, reporter_user_id),
    CONSTRAINT chk_place_media_reports_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_place_media_reports_media ON place_media_reports (place_media_id);
CREATE INDEX IF NOT EXISTS idx_place_media_reports_status ON place_media_reports (status);

