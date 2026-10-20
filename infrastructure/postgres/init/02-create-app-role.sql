-- ==============================================================================
-- REWIT - INICIALIZAÇÃO DA ROLE DE APLICAÇÃO RUNTIME (AO-1)
-- Executado na inicialização de um container PostgreSQL novo
-- ==============================================================================

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
EXCEPTION WHEN OTHERS THEN
    NULL;
END
$$;

GRANT USAGE ON SCHEMA public TO rewit_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rewit_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rewit_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO rewit_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO rewit_app;
