-- ==============================================================================
-- REWIT - INICIALIZAÇÃO DAS CINCO EXTENSÕES POSTGRESQL & POSTGIS
-- Executado automaticamente na inicialização do container PostgreSQL e usado como
-- referência do bootstrap administrativo em PostgreSQL gerenciado (schema public).
-- ==============================================================================

\set ON_ERROR_STOP on

\echo 'Iniciando configuração das cinco extensões obrigatórias do Rewit no schema public...'

-- Extensão mantida por compatibilidade com a migration V1 imutável (UUIDs usam gen_random_uuid())
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

-- Extensão geoespacial PostGIS para tipos/índices GEOGRAPHY(Point, 4326) e public.spatial_ref_sys
CREATE EXTENSION IF NOT EXISTS "postgis" WITH SCHEMA public;

-- Extensão trigram para busca textual tolerante a erros (GIN gin_trgm_ops e similarity)
CREATE EXTENSION IF NOT EXISTS "pg_trgm" WITH SCHEMA public;

-- Extensão mantida por compatibilidade com a migration V1 imutável
CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA public;

-- Extensão unaccent obrigatoriamente no schema public (V22 qualifica public.unaccent e regdictionary)
CREATE EXTENSION IF NOT EXISTS "unaccent" WITH SCHEMA public;

DO $$
DECLARE
    v_missing TEXT;
BEGIN
    SELECT string_agg(ext, ', ' ORDER BY ext)
    INTO v_missing
    FROM unnest(ARRAY['uuid-ossp', 'postgis', 'pg_trgm', 'pgcrypto', 'unaccent']) AS req(ext)
    WHERE NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension e
        JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
        WHERE e.extname = req.ext
          AND n.nspname = 'public'
    );

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'Bootstrap de extensões incompleto no schema public: ausente(s) %', v_missing;
    END IF;

    IF to_regclass('public.spatial_ref_sys') IS NULL THEN
        RAISE EXCEPTION 'PostGIS instalado sem public.spatial_ref_sys acessível no schema public';
    END IF;
END
$$;

\echo 'Cinco extensões configuradas com sucesso no schema public!'
\echo 'PostGIS Version:'
SELECT postgis_full_version();
