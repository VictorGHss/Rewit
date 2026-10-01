-- ==============================================================================
-- REWIT DATABASE MIGRATION - V13
-- ==============================================================================
-- STEP 27.2: Dispatcher/Worker do Outbox — índice de recuperação de leases
-- ==============================================================================

-- 1. ÍNDICE DE RECLAIM DE LEASES EXPIRADAS
-- A única nova query do dispatcher que varre a fila é o reclaim: PROCESSING
-- com locked_at anterior ao cutoff (now - lease_duration). O índice parcial
-- mantém apenas as linhas em PROCESSING (população pequena por definição).
-- Índice FAILED/created_at avaliado e NÃO criado: não há query no Step 27.2
-- que o aproveite (countByStatus é contagem integral via seq scan).
CREATE INDEX idx_outbox_processing_lease
    ON outbox_messages (locked_at)
    WHERE status = 'PROCESSING';

COMMENT ON INDEX idx_outbox_processing_lease IS
    'Suporta o reclaim atômico de leases expiradas do dispatcher do outbox (Step 27.2).';
