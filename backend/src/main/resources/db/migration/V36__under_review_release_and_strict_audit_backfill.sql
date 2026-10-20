-- ==============================================================================
-- REWIT DATABASE MIGRATION - V36
-- ==============================================================================
-- S3-A.8 / S3-A.10 (Versão Canônica):
-- 1. Suporte à ação RELEASE_REVIEW na trilha de auditoria
-- 2. Suporte ao status AUTO_RESOLVED na tabela place_media_reports (AU-1/AU-2)
-- 3. Suporte a is_legacy_decision e constraint de decided_by_user_id sem bloqueio
--    de upgrades legítimos da V35
-- 4. Backfill estrito de removal_source respeitando a primeira ação do ciclo
--    posterior à última restauração (sem sobrescrever AUTHOR com ACCEPT_REPORT posterior)
-- 5. Trilha de auditoria append-only para as 4 tabelas de auditoria do sistema (AO-2)
-- 6. Privilégio mínimo e ausência de credencial estática para a role rewit_app (SEC-1, AO-1, AO-2)
-- Tabelas afetadas: place_media_moderation_audit_logs, place_media_reports, place_media,
--                   moderation_audit_logs, discussion_moderation_audit_logs,
--                   business_response_moderation_audit_logs, flyway_schema_history
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

-- 3. GARANTIA DE DECIDED_BY_USER_ID E DISTINÇÃO DE DECISÕES LEGADAS (AU-1 / V36-1)
ALTER TABLE place_media_reports
    ADD COLUMN IF NOT EXISTS is_legacy_decision BOOLEAN NOT NULL DEFAULT FALSE;

-- Atualizar registros históricos comprovadamente auto-resolvidos pelo SYSTEM
UPDATE place_media_reports r
SET status = 'AUTO_RESOLVED',
    decided_by_user_id = NULL,
    is_legacy_decision = FALSE
WHERE EXISTS (
    SELECT 1 FROM place_media_moderation_audit_logs al
    WHERE al.report_id = r.id
      AND al.action = 'AUTO_RESOLVE_REPORT'
      AND al.actor_type = 'SYSTEM'
);

-- Marcar decisões históricas ACCEPTED ou REJECTED sem moderador atribuível como legadas
UPDATE place_media_reports
SET is_legacy_decision = TRUE
WHERE status IN ('ACCEPTED', 'REJECTED')
  AND decided_by_user_id IS NULL;

ALTER TABLE place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_decided_by;

ALTER TABLE place_media_reports
    ADD CONSTRAINT chk_place_media_reports_decided_by
    CHECK (
        (status = 'PENDING' AND decided_by_user_id IS NULL AND is_legacy_decision = FALSE)
        OR
        (status = 'AUTO_RESOLVED' AND decided_by_user_id IS NULL AND is_legacy_decision = FALSE)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND decided_by_user_id IS NOT NULL AND is_legacy_decision = FALSE)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND decided_by_user_id IS NULL AND is_legacy_decision = TRUE)
    );

-- 4. BACKFILL ESTRITO DE REMOVAL_SOURCE (V36-1, V36-2 e S3-A.10)
-- A causa real da remoção no ciclo atual é determinada pela PRIMEIRA ação de remoção válida
-- estritamente posterior à última restauração/liberação.
WITH media_last_restore AS (
    SELECT 
        place_media_id,
        MAX(created_at) AS last_restore_at
    FROM place_media_moderation_audit_logs
    WHERE action IN ('RESTORE', 'RELEASE_REVIEW')
    GROUP BY place_media_id
),
first_valid_removal AS (
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
    ORDER BY al.place_media_id, al.created_at ASC, al.id ASC
)
UPDATE place_media pm
SET removal_source = CASE
    WHEN fvr.action = 'AUTHOR_REMOVE' THEN 'AUTHOR'
    WHEN fvr.action = 'PLACE_MANAGER_REMOVE' THEN 'PLACE_MANAGER'
    WHEN fvr.action = 'BUSINESS_OWNER_REMOVE' THEN 'BUSINESS_OWNER'
    WHEN fvr.action = 'REMOVE' AND fvr.actor_user_id = pm.user_id THEN 'AUTHOR'
    WHEN fvr.action = 'REMOVE' AND EXISTS (
        SELECT 1 FROM places p WHERE p.id = pm.place_id AND p.created_by_user_id = fvr.actor_user_id
    ) THEN 'PLACE_MANAGER'
    WHEN fvr.action IN ('REMOVE', 'ACCEPT_REPORT') AND fvr.actor_type = 'USER' AND EXISTS (
        SELECT 1 FROM users u WHERE u.id = fvr.actor_user_id AND u.role IN ('MODERATOR', 'ADMIN')
    ) THEN 'MODERATOR'
    ELSE 'LEGACY'
END
FROM first_valid_removal fvr
WHERE pm.id = fvr.place_media_id
  AND pm.status = 'REMOVED';

-- Qualquer mídia REMOVED que não possua evento válido posterior à última restauração fica LEGACY
UPDATE place_media pm
SET removal_source = 'LEGACY'
WHERE pm.status = 'REMOVED'
  AND (pm.removal_source IS NULL OR NOT EXISTS (
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
  ));

-- 5. PROTEÇÃO APPEND-ONLY PARA TODAS AS 4 TRILHAS DE AUDITORIA DO PRODUTO (AO-2)
-- a) moderation_audit_logs (V11)
CREATE OR REPLACE FUNCTION fn_prevent_moderation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'moderation_audit_logs é append-only: UPDATE e DELETE não são permitidos'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_update ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_update
    BEFORE UPDATE ON moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_delete ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_delete
    BEFORE DELETE ON moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_moderation_audit_mutation();

CREATE OR REPLACE FUNCTION fn_prevent_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_truncate ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_truncate
    BEFORE TRUNCATE ON moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_moderation_audit_truncate();

-- b) discussion_moderation_audit_logs (V20) - append-only com cascata estrutural (V20) e proteção contra TRUNCATE
CREATE OR REPLACE FUNCTION fn_prevent_discussion_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'discussion_moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_discussion_moderation_audit_truncate ON discussion_moderation_audit_logs;
CREATE TRIGGER trg_prevent_discussion_moderation_audit_truncate
    BEFORE TRUNCATE ON discussion_moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_discussion_moderation_audit_truncate();

-- c) business_response_moderation_audit_logs (V24) - append-only com cascata estrutural (V24) e proteção contra TRUNCATE
CREATE OR REPLACE FUNCTION fn_prevent_business_response_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'business_response_moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_business_response_audit_truncate ON business_response_moderation_audit_logs;
CREATE TRIGGER trg_prevent_business_response_audit_truncate
    BEFORE TRUNCATE ON business_response_moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_business_response_audit_truncate();

-- 6. PROVISIONAMENTO SEGURO E PRIVILÉGIO MÍNIMO DA ROLE DE APLICAÇÃO (SEC-1, AO-1, AO-2)
-- A criação/atualização de senha da role ocorre fora da migration versionada.
-- Caso a role não exista no momento da migration, ela é criada sem privilégios elevados.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rewit_app') THEN
        CREATE ROLE rewit_app WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rewit_app', current_database());
EXCEPTION WHEN OTHERS THEN
    NULL;
END
$$;

GRANT USAGE ON SCHEMA public TO rewit_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rewit_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rewit_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO rewit_app;

-- Trilha de auditoria: role de runtime só pode SELECT e INSERT; proibido UPDATE, DELETE e TRUNCATE
REVOKE UPDATE, DELETE, TRUNCATE ON moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON discussion_moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON business_response_moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON place_media_moderation_audit_logs FROM rewit_app;

-- Tabela de controle de migrations do Flyway: rewit_app NÃO pode modificar nem apagar
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history') THEN
        REVOKE ALL ON flyway_schema_history FROM rewit_app;
        GRANT SELECT ON flyway_schema_history TO rewit_app;
    END IF;
END
$$;

-- Privilégios default para tabelas criadas futuramente pelo Flyway
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO rewit_app;
