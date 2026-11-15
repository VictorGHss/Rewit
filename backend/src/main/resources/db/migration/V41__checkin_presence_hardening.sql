-- ==============================================================================
-- REWIT DATABASE MIGRATION - V41
-- ==============================================================================
-- Check-in V1: limite do raio de validação e suporte à regra de frequência
-- Tabelas: places (CHECK novo), check_ins (índice parcial novo)
-- ==============================================================================
-- 1. places.validation_radius_meters passa a ter teto de 250 metros
--    (PresenceVerificationPolicy.MAX_VALIDATION_RADIUS_METERS). Até a V40 qualquer
--    usuário podia criar um local com raio arbitrário e, com ele, confirmar presença
--    de qualquer lugar. Um local gravado acima do teto faz a migração falhar, com a
--    contagem dos registros, em vez de ter o raio reduzido em silêncio: o ajuste de
--    cada local exige decisão de quem opera o ambiente.
--
-- 2. idx_checkins_verified_user_place atende a regra de no máximo um check-in
--    verificado por usuário e local em 24 horas móveis (consulta por user_id,
--    place_id e verified_at, só de VERIFIED) e a listagem dos check-ins do próprio
--    usuário. Parcial: check-ins rejeitados não entram.
--
-- A serialização de verificações concorrentes do mesmo usuário e local é feita na
-- transação da criação da avaliação (pg_advisory_xact_lock), sem tabela nova.

DO $$
DECLARE
    offending BIGINT;
BEGIN
    SELECT count(*) INTO offending FROM places WHERE validation_radius_meters > 250;
    IF offending > 0 THEN
        RAISE EXCEPTION 'V41: % local(is) com validation_radius_meters acima de 250 metros', offending
            USING HINT = 'Revise esses locais (SELECT id, name, validation_radius_meters FROM places WHERE '
                         'validation_radius_meters > 250) e defina o raio de cada um antes de aplicar a V41; '
                         'nenhuma alteração foi aplicada.';
    END IF;
END
$$;

ALTER TABLE places
    ADD CONSTRAINT chk_places_validation_radius_max CHECK (validation_radius_meters <= 250);

CREATE INDEX idx_checkins_verified_user_place
    ON check_ins (user_id, place_id, verified_at DESC)
    WHERE status = 'VERIFIED';
