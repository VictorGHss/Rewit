-- ==============================================================================
-- REWIT DATABASE MIGRATION - V25
-- ==============================================================================
-- C11: Consulta administrativa de respostas oficiais removidas
-- ==============================================================================
-- A consulta lista respostas REMOVED das mais recentemente removidas para as mais antigas, com filtros opcionais.
-- Os filtros por avaliação e por conta já têm índice (V24); sem filtro, a página percorreria e ordenaria a tabela
-- inteira. O índice parcial cobre só as removidas, na ordem da consulta (removed_at DESC, id DESC).

CREATE INDEX idx_business_responses_removed
    ON business_responses (removed_at DESC, id DESC)
    WHERE status = 'REMOVED';
