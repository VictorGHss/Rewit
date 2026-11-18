-- ==============================================================================
-- REWIT DATABASE MIGRATION - V43
-- ==============================================================================
-- Minimização e proteção de coordenadas GPS em check_ins
-- Tabela: check_ins (remoção de índice GiST em coordinates, nulabilidade de
--         coordinates e distance_to_centroid_meters, adição de rejection_reason,
--         backfill sem alterar reviews.updated_at e constraints estruturais)
-- ==============================================================================
-- 1. Nenhuma coordenada GPS exata do usuário pode permanecer ou ser gravada em
--    check_ins, independentemente do status (VERIFIED, REJECTED ou PENDING),
--    imposto por backfill histórico e pela CHECK chk_checkin_coordinates_null.
-- 2. Apenas check-ins VERIFIED podem manter distance_to_centroid_meters
--    (chk_checkin_distance_only_verified); REJECTED registra o motivo estruturado
--    em rejection_reason (valores reais de PresenceVerificationOutcome).
-- 3. Backfill de check-ins não verificados (REJECTED e PENDING) também descarta
--    distance_to_centroid_meters histórica para não reter distância de origem.
-- 4. Nenhuma linha de check_ins é excluída; preserva-se reviews.is_verified_on_site
--    e restaura-se reviews.updated_at alterado colateralmente pela trigger da V3.
-- 5. Verificação final dos privilégios mínimos da role de runtime (V37).
-- ==============================================================================

DROP INDEX IF EXISTS idx_checkins_coordinates;

ALTER TABLE check_ins
    ALTER COLUMN coordinates DROP NOT NULL,
    ALTER COLUMN distance_to_centroid_meters DROP NOT NULL,
    ADD COLUMN rejection_reason VARCHAR(64);

-- Preserva reviews.updated_at ao executar o backfill de check_ins dentro da mesma
-- transação: trg_sync_check_in_to_review_verified (V3) dispara em todo UPDATE de
-- check_ins, mas a única trigger em reviews (trg_prevent_unverified_review_flag)
-- monitora apenas INSERT/UPDATE OF is_verified_on_site, permitindo restaurar
-- updated_at sem desabilitar triggers nem enfraquecer invariantes.
CREATE TEMP TABLE _v43_review_timestamps ON COMMIT DROP AS
SELECT DISTINCT r.id, r.updated_at
FROM reviews r
JOIN check_ins c ON c.review_id = r.id
WHERE c.coordinates IS NOT NULL
   OR (c.status IN ('REJECTED', 'PENDING') AND c.distance_to_centroid_meters IS NOT NULL);

UPDATE check_ins
SET coordinates = NULL,
    distance_to_centroid_meters = CASE
        WHEN status IN ('REJECTED', 'PENDING') THEN NULL
        ELSE distance_to_centroid_meters
    END
WHERE coordinates IS NOT NULL
   OR (status IN ('REJECTED', 'PENDING') AND distance_to_centroid_meters IS NOT NULL);

UPDATE reviews r
SET updated_at = t.updated_at
FROM _v43_review_timestamps t
WHERE r.id = t.id
  AND r.updated_at IS DISTINCT FROM t.updated_at;

-- Constraints estruturais aplicadas após a conclusão do backfill histórico
ALTER TABLE check_ins
    ADD CONSTRAINT chk_checkin_coordinates_null
        CHECK (coordinates IS NULL),
    ADD CONSTRAINT chk_checkin_distance_only_verified
        CHECK (status = 'VERIFIED' OR distance_to_centroid_meters IS NULL),
    ADD CONSTRAINT chk_checkin_rejection_reason
        CHECK (rejection_reason IS NULL OR rejection_reason IN (
            'ACCURACY_UNAVAILABLE',
            'ACCURACY_TOO_LOW',
            'OUTSIDE_RADIUS',
            'RECENT_CHECK_IN'
        )),
    ADD CONSTRAINT chk_checkin_rejection_reason_consistency
        CHECK (status = 'REJECTED' OR rejection_reason IS NULL);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        PERFORM rewit_assert_runtime_privileges();
    END IF;
END
$$;
