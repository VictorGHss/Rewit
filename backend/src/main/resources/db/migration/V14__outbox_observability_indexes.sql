-- ==============================================================================
-- REWIT DATABASE MIGRATION - V14
-- ==============================================================================
-- STEP 27.4: índice da idade da mensagem PENDING mais antiga
-- ==============================================================================

CREATE INDEX idx_outbox_pending_created_at
    ON outbox_messages (created_at)
    WHERE status = 'PENDING';

COMMENT ON INDEX idx_outbox_pending_created_at IS
    'Suporta a gauge da mensagem PENDING mais antiga do Outbox (Step 27.4).';