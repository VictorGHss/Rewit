-- ==============================================================================
-- REWIT DATABASE MIGRATION - V18
-- ==============================================================================
-- STEP 32.0: Integridade e Tipagem de Status de Discussões (C3 Foundation)
-- ==============================================================================

-- 1. Restrição de integridade para os estados válidos de discussão
ALTER TABLE review_discussions
    ADD CONSTRAINT chk_review_discussions_status
    CHECK (status IN ('ACTIVE', 'UNDER_REVIEW', 'REMOVED'));

-- 2. Índice parcial cobrindo a listagem paginada cronológica de discussões ativas por avaliação
CREATE INDEX IF NOT EXISTS idx_review_discussions_active_listing
    ON review_discussions (review_id, created_at ASC, id ASC)
    WHERE status = 'ACTIVE';
