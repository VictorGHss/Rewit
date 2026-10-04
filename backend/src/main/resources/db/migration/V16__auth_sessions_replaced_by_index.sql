-- ==============================================================================
-- REWIT DATABASE MIGRATION - V16
-- ==============================================================================
-- STEP 29.3: índice da referência de rotação de auth_sessions
-- ==============================================================================
-- A FK replaced_by_session_id -> auth_sessions(id) ON DELETE SET NULL procura,
-- a cada sessão apagada pelo cleanup, as linhas que a referenciam. Sem índice,
-- essa busca percorre a tabela inteira por linha apagada.
-- CONCURRENTLY não bloqueia escritas e não roda em transação: ver o arquivo
-- V16__auth_sessions_replaced_by_index.sql.conf (executeInTransaction=false).

CREATE INDEX CONCURRENTLY idx_auth_sessions_replaced_by_session_id
    ON auth_sessions (replaced_by_session_id)
    WHERE replaced_by_session_id IS NOT NULL;
