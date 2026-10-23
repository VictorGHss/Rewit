-- ==============================================================================
-- REWIT DATABASE MIGRATION - V34
-- ==============================================================================
-- S3-A.6: Proteção contra TRUNCATE em auditoria, origens de remoção e integridade
-- Tabelas afetadas: place_media, place_media_moderation_audit_logs
-- ==============================================================================

-- 1. PROTEÇÃO CONTRA TRUNCATE NA TABELA DE AUDITORIA (APPEND-ONLY)
CREATE OR REPLACE FUNCTION fn_prevent_place_media_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_media_moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_place_media_moderation_audit_truncate ON place_media_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_media_moderation_audit_truncate
    BEFORE TRUNCATE ON place_media_moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_place_media_moderation_audit_truncate();

-- 2. SUPORTE A BUSINESS_OWNER, PLACE_MANAGER E LEGACY EM PLACE_MEDIA.REMOVAL_SOURCE
ALTER TABLE place_media
    DROP CONSTRAINT IF EXISTS chk_place_media_removal_source;

ALTER TABLE place_media
    ADD CONSTRAINT chk_place_media_removal_source
    CHECK (removal_source IS NULL OR removal_source IN ('AUTHOR', 'MODERATOR', 'BUSINESS_OWNER', 'PLACE_MANAGER', 'LEGACY'));

-- 3. SUPORTE A AUTO_QUARANTINE, BUSINESS_OWNER_REMOVE E PLACE_MANAGER_REMOVE NA TRILHA DE AUDITORIA
ALTER TABLE place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_moderation_audit_action;

ALTER TABLE place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_moderation_audit_action
    CHECK (action IN ('REMOVE', 'RESTORE', 'ACCEPT_REPORT', 'REJECT_REPORT', 'AUTHOR_REMOVE', 'BUSINESS_OWNER_REMOVE', 'PLACE_MANAGER_REMOVE', 'AUTO_QUARANTINE'));

-- 4. BACKFILL CONSERVADOR PARA REGISTROS HISTÓRICOS
-- a) Ator é o autor da foto -> AUTHOR
UPDATE place_media pm
SET removal_source = 'AUTHOR'
WHERE pm.status = 'REMOVED'
  AND EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      WHERE al.place_media_id = pm.id
        AND al.actor_user_id = pm.user_id
        AND al.action IN ('REMOVE', 'AUTHOR_REMOVE')
  );

-- b) Ator é o responsável/gerente do local -> PLACE_MANAGER
UPDATE place_media pm
SET removal_source = 'PLACE_MANAGER'
WHERE pm.status = 'REMOVED'
  AND pm.removal_source <> 'AUTHOR'
  AND EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      JOIN places p ON p.id = pm.place_id
      WHERE al.place_media_id = pm.id
        AND (al.action IN ('PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE') OR al.actor_user_id = p.created_by_user_id)
  );

-- c) Mídias removidas sem registro correspondente em auditoria -> LEGACY
UPDATE place_media pm
SET removal_source = 'LEGACY'
WHERE pm.status = 'REMOVED'
  AND NOT EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      WHERE al.place_media_id = pm.id
  );
