-- ==============================================================================
-- REWIT DATABASE MIGRATION - V36
-- ==============================================================================
-- S3-A.8: 
-- 1. Suporte à ação RELEASE_REVIEW na trilha de auditoria
-- 2. Suporte ao status AUTO_RESOLVED na tabela place_media_reports (AU-1)
-- 3. Constraint de decided_by_user_id para denúncias automáticas vs explícitas
-- 4. Backfill estrito de removal_source respeitando ACCEPT_REPORT e o ciclo
--    posterior à última restauração (V36-1, V36-2)
-- 5. Provisionamento e garantias de segurança para a role de runtime rewit_app (AO-1)
-- Tabelas afetadas: place_media_moderation_audit_logs, place_media_reports, place_media
-- ==============================================================================

-- 1. SUPORTE À AÇÃO 'RELEASE_REVIEW' NA TRILHA DE AUDITORIA
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
        'AUTO_RESOLVE_REPORT',
        'RELEASE_REVIEW'
    ));

-- 2. SUPORTE AO STATUS 'AUTO_RESOLVED' EM PLACE_MEDIA_REPORTS
ALTER TABLE place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_status;

ALTER TABLE place_media_reports
    ADD CONSTRAINT chk_place_media_reports_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_RESOLVED'));

-- 3. GARANTIA DE DECIDED_BY_USER_ID COERENTE (AU-1)
-- Denúncias PENDING ou AUTO_RESOLVED nunca possuem decided_by_user_id.
-- Denúncias ACCEPTED ou REJECTED possuem obrigatoriamente decided_by_user_id.
ALTER TABLE place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_decided_by;

-- Atualizar registros históricos que possam ter sido gravados como AUTO_RESOLVE_REPORT com decided_by indevido
UPDATE place_media_reports r
SET status = 'AUTO_RESOLVED',
    decided_by_user_id = NULL
WHERE EXISTS (
    SELECT 1 FROM place_media_moderation_audit_logs al
    WHERE al.report_id = r.id
      AND al.action = 'AUTO_RESOLVE_REPORT'
      AND al.actor_type = 'SYSTEM'
);

ALTER TABLE place_media_reports
    ADD CONSTRAINT chk_place_media_reports_decided_by
    CHECK (
        (status IN ('PENDING', 'AUTO_RESOLVED') AND decided_by_user_id IS NULL)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND decided_by_user_id IS NOT NULL)
    );

-- 4. BACKFILL ESTRITO DE REMOVAL_SOURCE (V36-1 e V36-2)
-- Considera apenas eventos de remoção estritamente posteriores à última restauração/liberação
WITH media_last_restore AS (
    SELECT 
        place_media_id,
        MAX(created_at) AS last_restore_at
    FROM place_media_moderation_audit_logs
    WHERE action IN ('RESTORE', 'RELEASE_REVIEW')
    GROUP BY place_media_id
),
latest_valid_removal AS (
    SELECT DISTINCT ON (al.place_media_id)
        al.place_media_id,
        al.action,
        al.actor_user_id,
        al.actor_type,
        al.created_at,
        al.id
    FROM place_media_moderation_audit_logs al
    LEFT JOIN media_last_restore lr ON lr.place_media_id = al.place_media_id
    WHERE al.action IN ('REMOVE', 'ACCEPT_REPORT', 'AUTHOR_REMOVE', 'PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE')
      AND (lr.last_restore_at IS NULL OR al.created_at > lr.last_restore_at)
    ORDER BY al.place_media_id, al.created_at DESC, al.id DESC
)
UPDATE place_media pm
SET removal_source = CASE
    WHEN lvr.action = 'AUTHOR_REMOVE' THEN 'AUTHOR'
    WHEN lvr.action = 'PLACE_MANAGER_REMOVE' THEN 'PLACE_MANAGER'
    WHEN lvr.action = 'BUSINESS_OWNER_REMOVE' THEN 'BUSINESS_OWNER'
    WHEN lvr.action IN ('REMOVE', 'ACCEPT_REPORT') AND lvr.actor_type = 'USER' AND EXISTS (
        SELECT 1 FROM users u WHERE u.id = lvr.actor_user_id AND u.role IN ('MODERATOR', 'ADMIN')
    ) THEN 'MODERATOR'
    ELSE 'LEGACY'
END
FROM latest_valid_removal lvr
WHERE pm.id = lvr.place_media_id
  AND pm.status = 'REMOVED';

-- Qualquer mídia REMOVED que não possua evento válido posterior à última restauração fica LEGACY
UPDATE place_media pm
SET removal_source = 'LEGACY'
WHERE pm.status = 'REMOVED'
  AND NOT EXISTS (
      SELECT 1
      FROM place_media_moderation_audit_logs al
      LEFT JOIN (
          SELECT place_media_id, MAX(created_at) AS last_restore_at
          FROM place_media_moderation_audit_logs
          WHERE action IN ('RESTORE', 'RELEASE_REVIEW')
          GROUP BY place_media_id
      ) lr ON lr.place_media_id = al.place_media_id
      WHERE al.place_media_id = pm.id
        AND al.action IN ('REMOVE', 'ACCEPT_REPORT', 'AUTHOR_REMOVE', 'PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE')
        AND (lr.last_restore_at IS NULL OR al.created_at > lr.last_restore_at)
  );

-- 5. PROVISIONAMENTO E SEGURANÇA DA ROLE DE APLICAÇÃO (AO-1)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rewit_app') THEN
        CREATE ROLE rewit_app WITH LOGIN PASSWORD 'rewit_app_local_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rewit_app', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO rewit_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rewit_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rewit_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO rewit_app;

-- Trilha de auditoria: role de runtime só pode SELECT e INSERT; proibido UPDATE, DELETE e TRUNCATE
REVOKE UPDATE, DELETE, TRUNCATE ON place_media_moderation_audit_logs FROM rewit_app;

-- Privilégios default para tabelas criadas futuramente pelo Flyway
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO rewit_app;

