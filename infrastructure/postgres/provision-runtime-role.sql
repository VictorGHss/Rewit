-- ==============================================================================
-- REWIT - PROVISIONAMENTO DA ROLE DE RUNTIME (rewit_app)
-- Script auxiliar idempotente para ambientes em que o container já foi inicializado.
-- Uso via psql:
--   psql -U rewit_user -d rewit_db -v app_user=rewit_app -v app_password='SUA_SENHA_AQUI' -f provision-runtime-role.sql
-- ==============================================================================

\set ON_ERROR_STOP on

DO $$
DECLARE
    target_user TEXT := COALESCE(NULLIF(:'app_user', ''), 'rewit_app');
    target_pass TEXT := NULLIF(:'app_password', '');
BEGIN
    IF target_pass IS NULL OR target_pass = 'change-me' THEN
        RAISE EXCEPTION 'Defina uma senha válida para a role de runtime informando -v app_password=...';
    END IF;

    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = target_user) THEN
        EXECUTE format('CREATE ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', target_user, target_pass);
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', target_user, target_pass);
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), target_user);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', target_user);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', target_user);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', target_user);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I', target_user);

    -- Proteção de trilhas de auditoria
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON moderation_audit_logs, discussion_moderation_audit_logs, business_response_moderation_audit_logs, place_media_moderation_audit_logs FROM %I', target_user);

    -- Proteção de migrations Flyway
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history') THEN
        EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', target_user);
        EXECUTE format('GRANT SELECT ON flyway_schema_history TO %I', target_user);
    END IF;

    RAISE NOTICE 'Role % provisionada com sucesso e com privilégios mínimos de runtime.', target_user;
END
$$;

