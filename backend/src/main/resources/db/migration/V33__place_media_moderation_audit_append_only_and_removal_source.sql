-- ==============================================================================
-- REWIT DATABASE MIGRATION - V33
-- ==============================================================================
-- S3-A.5: Trilha de auditoria append-only, origem de remoção e integridade
-- Tabelas afetadas: place_media, place_media_moderation_audit_logs
-- ==============================================================================

-- 1. ORIGEM DA REMOÇÃO NA TABELA PLACE_MEDIA
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS removal_source VARCHAR(32);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_place_media_removal_source'
    ) THEN
        ALTER TABLE place_media
            ADD CONSTRAINT chk_place_media_removal_source
            CHECK (removal_source IS NULL OR removal_source IN ('AUTHOR', 'MODERATOR'));
    END IF;
END $$;

-- Preenchimento seguro de mídias legadas já removidas
UPDATE place_media
SET removed_at = NOW(), removal_source = 'MODERATOR'
WHERE status = 'REMOVED' AND removed_at IS NULL;

UPDATE place_media
SET removal_source = 'MODERATOR'
WHERE status = 'REMOVED' AND removal_source IS NULL;

-- 2. INTEGRIDADE E CONSTRAINTS NA TRILHA DE AUDITORIA
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_place_media_moderation_audit_action'
    ) THEN
        ALTER TABLE place_media_moderation_audit_logs
            ADD CONSTRAINT chk_place_media_moderation_audit_action
            CHECK (action IN ('REMOVE', 'RESTORE', 'ACCEPT_REPORT', 'REJECT_REPORT', 'AUTHOR_REMOVE'));
    END IF;
END $$;

-- 3. TRIGGERS APPEND-ONLY (IMPEDIR UPDATE E DELETE)
CREATE OR REPLACE FUNCTION fn_prevent_place_media_moderation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_media_moderation_audit_logs é append-only: UPDATE e DELETE não são permitidos'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_place_media_moderation_audit_update ON place_media_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_media_moderation_audit_update
    BEFORE UPDATE ON place_media_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_place_media_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_place_media_moderation_audit_delete ON place_media_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_media_moderation_audit_delete
    BEFORE DELETE ON place_media_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_place_media_moderation_audit_mutation();

