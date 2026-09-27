-- ==============================================================================
-- REWIT - INICIALIZAÇÃO DE EXTENSÕES POSTGRESQL & POSTGIS
-- Executado automaticamente na inicialização do container PostgreSQL
-- ==============================================================================

\echo 'Iniciando configuração de extensões do Rewit...'

-- Extensão para geração de identificadores universais únicos (UUIDv4)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Extensão geoespacial PostGIS para suporte a tipos espaciais (GEOGRAPHY/GEOMETRY)
CREATE EXTENSION IF NOT EXISTS "postgis";

-- Extensão trigram para busca rápida textual com tolerância a erros e autocomplete
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Extensão criptográfica para funções de hash auxiliares
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

\echo 'Extensões configuradas com sucesso!'
\echo 'PostGIS Version:'
SELECT postgis_full_version();
