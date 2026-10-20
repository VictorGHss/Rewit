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
--
-- Retomada segura (V16-1): um CREATE INDEX CONCURRENTLY interrompido deixa o índice
-- com indisvalid = false, e IF NOT EXISTS apenas emitiria um NOTICE mantendo-o inválido.
-- Por isso o índice com este nome é sempre removido (também de forma concorrente) e
-- reconstruído; a verificação final aborta a migração se o resultado não for um índice
-- válido, pronto e com a definição canônica. Cada comando roda isolado (sem transação),
-- então uma nova tentativa após falha recomeça do DROP.

DROP INDEX CONCURRENTLY IF EXISTS idx_auth_sessions_replaced_by_session_id;

CREATE INDEX CONCURRENTLY idx_auth_sessions_replaced_by_session_id
    ON auth_sessions (replaced_by_session_id)
    WHERE replaced_by_session_id IS NOT NULL;

DO $$
DECLARE
    v_valid BOOLEAN;
    v_ready BOOLEAN;
    v_definition TEXT;
BEGIN
    SELECT i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid)
    INTO v_valid, v_ready, v_definition
    FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname = 'idx_auth_sessions_replaced_by_session_id'
      AND n.nspname = current_schema();

    IF NOT FOUND THEN
        RAISE EXCEPTION 'V16: índice idx_auth_sessions_replaced_by_session_id não foi criado';
    END IF;
    IF NOT v_valid OR NOT v_ready THEN
        RAISE EXCEPTION 'V16: índice idx_auth_sessions_replaced_by_session_id inválido (indisvalid=%, indisready=%)',
            v_valid, v_ready;
    END IF;
    IF v_definition NOT LIKE '%ON %auth_sessions USING btree (replaced_by_session_id) WHERE (replaced_by_session_id IS NOT NULL)' THEN
        RAISE EXCEPTION 'V16: definição inesperada do índice: %', v_definition;
    END IF;
END
$$;
