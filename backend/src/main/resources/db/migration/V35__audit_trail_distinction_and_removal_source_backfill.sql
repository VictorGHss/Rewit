-- ==============================================================================
-- REWIT DATABASE MIGRATION - V35
-- ==============================================================================
-- S3-A.7: Distinção de decisões explícitas vs resoluções automáticas na auditoria,
--          suporte a ator SYSTEM e correção conservadora do backfill de removal_source
-- Tabelas afetadas: place_media_moderation_audit_logs, place_media
-- ==============================================================================

-- 1. SUPORTE A ATOR 'SYSTEM' E AÇÃO 'AUTO_RESOLVE_REPORT' NA TRILHA DE AUDITORIA
-- Permitir actor_user_id nulo para operações automáticas do sistema
ALTER TABLE place_media_moderation_audit_logs
    ALTER COLUMN actor_user_id DROP NOT NULL;

-- Adicionar coluna actor_type com valor default 'USER'
ALTER TABLE place_media_moderation_audit_logs
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_audit_actor_type;

ALTER TABLE place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_audit_actor_type
    CHECK (actor_type IN ('USER', 'SYSTEM'));

ALTER TABLE place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_audit_actor_system;

ALTER TABLE place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_audit_actor_system
    CHECK ((actor_type = 'SYSTEM' AND actor_user_id IS NULL) OR (actor_type = 'USER' AND actor_user_id IS NOT NULL));

-- Atualizar constraint de ações suportadas na trilha de auditoria
ALTER TABLE place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_moderation_audit_action;

ALTER TABLE place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_moderation_audit_action
    CHECK (action IN (
        'REMOVE',
        'RESTORE',
        'ACCEPT_REPORT',
        'REJECT_REPORT',
        'AUTHOR_REMOVE',
        'BUSINESS_OWNER_REMOVE',
        'PLACE_MANAGER_REMOVE',
        'AUTO_QUARANTINE',
        'AUTO_RESOLVE_REPORT'
    ));

-- 2. CORREÇÃO CONSERVADORA DO BACKFILL DE REMOVAL_SOURCE EM PLACE_MEDIA
-- a) Mídias com status REMOVED que não possuem nenhum evento de remoção na auditoria
--    (ou apenas eventos não relacionados como REJECT_REPORT) são classificadas como LEGACY
UPDATE place_media pm
SET removal_source = 'LEGACY'
WHERE pm.status = 'REMOVED'
  AND NOT EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      WHERE al.place_media_id = pm.id
        AND al.action IN ('REMOVE', 'AUTHOR_REMOVE', 'PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE')
  );

-- b) Mídias removidas com evento comprovado pelo criador/responsável direto do local -> PLACE_MANAGER
UPDATE place_media pm
SET removal_source = 'PLACE_MANAGER'
WHERE pm.status = 'REMOVED'
  AND pm.removal_source <> 'AUTHOR'
  AND EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      JOIN places p ON p.id = pm.place_id
      WHERE al.place_media_id = pm.id
        AND (al.action = 'PLACE_MANAGER_REMOVE' OR (al.action = 'REMOVE' AND al.actor_user_id = p.created_by_user_id))
  );

-- c) Mídias removidas com evento comprovado por moderador/admin (não autor e não criador) -> MODERATOR
UPDATE place_media pm
SET removal_source = 'MODERATOR'
WHERE pm.status = 'REMOVED'
  AND pm.removal_source NOT IN ('AUTHOR', 'PLACE_MANAGER', 'BUSINESS_OWNER')
  AND EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      JOIN users u ON u.id = al.actor_user_id
      JOIN places p ON p.id = pm.place_id
      WHERE al.place_media_id = pm.id
        AND al.action = 'REMOVE'
        AND al.actor_user_id <> pm.user_id
        AND (p.created_by_user_id IS NULL OR al.actor_user_id <> p.created_by_user_id)
        AND u.role IN ('MODERATOR', 'ADMIN')
  );

-- d) Qualquer mídia REMOVED que ainda permaneça com removal_source NULL -> LEGACY
UPDATE place_media pm
SET removal_source = 'LEGACY'
WHERE pm.status = 'REMOVED'
  AND pm.removal_source IS NULL;

-- 3. PERMISSÕES DE RUNTIME ROLE (se a role rewit_app existir no ambiente)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        GRANT SELECT, INSERT ON place_media_moderation_audit_logs TO rewit_app;
        REVOKE UPDATE, DELETE, TRUNCATE ON place_media_moderation_audit_logs FROM rewit_app;
    END IF;
END
$$;

