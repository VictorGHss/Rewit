-- ==============================================================================
-- REWIT DATABASE MIGRATION - V22
-- ==============================================================================
-- C5.1: Busca global sem acento e com índice utilizável
-- Tabelas: places, products
-- ==============================================================================
-- A busca comparava lower(coluna) LIKE '%termo%': "cafe" não encontrava "Café" e os índices trigram existentes
-- (sobre a coluna crua, idx_places_name_trgm e idx_products_name_trgm) não serviam à expressão, então o planner
-- varria as tabelas inteiras.

-- 1. EXTENSÃO
-- unaccent é "trusted" (PostgreSQL 13+): instalável por um usuário com CREATE no banco, como pg_trgm (V1).
CREATE EXTENSION IF NOT EXISTS "unaccent";

-- 2. NORMALIZAÇÃO
-- unaccent(text) é STABLE (depende do search_path para achar o dicionário) e não pode entrar em índice. Esta função
-- fixa o dicionário e o schema e é IMMUTABLE: a mesma expressão é usada na consulta e nos índices abaixo, o que
-- permite ao planner usá-los. Minúsculas e sem acento: "Açaí" -> "acai", "São" -> "sao".
CREATE OR REPLACE FUNCTION rewit_search_normalize(value TEXT)
    RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
    SELECT lower(public.unaccent('public.unaccent'::regdictionary, value))
$$;

-- 3. ÍNDICES TRIGRAM SOBRE A EXPRESSÃO NORMALIZADA
-- Um por coluna buscada: a condição é um OR entre colunas, resolvido com BitmapOr dos índices.
CREATE INDEX idx_places_search_name ON places USING gin (rewit_search_normalize(name) gin_trgm_ops);
CREATE INDEX idx_places_search_category ON places USING gin (rewit_search_normalize(category) gin_trgm_ops);
CREATE INDEX idx_places_search_city ON places USING gin (rewit_search_normalize(city) gin_trgm_ops);
CREATE INDEX idx_products_search_name ON products USING gin (rewit_search_normalize(name) gin_trgm_ops);
CREATE INDEX idx_products_search_category ON products USING gin (rewit_search_normalize(category) gin_trgm_ops);
