-- ==============================================================================
-- REWIT DATABASE MIGRATION - V12
-- ==============================================================================
-- STEP 27.1: Fundação Transacional do Outbox (Transactional Outbox em PostgreSQL)
-- ==============================================================================

-- 1. TABELA OUTBOX MESSAGES (FILA TRANSACIONAL)
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    locked_at TIMESTAMP WITH TIME ZONE,
    locked_by VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0)
);

-- 2. ÍNDICE DE CLAIM
-- Única query implementada neste step que consulta a fila é o claim de mensagens
-- PENDING elegíveis (next_attempt_at <= now). Os índices de lease
-- (PROCESSING/locked_at) e operacionais (FAILED/created_at) acompanharão as
-- respectivas queries no STEP 27.2.
CREATE INDEX idx_outbox_pending ON outbox_messages (next_attempt_at) WHERE status = 'PENDING';

COMMENT ON TABLE outbox_messages IS
    'Fila transacional (Transactional Outbox): mensagens enfileiradas na mesma transação PostgreSQL do produtor, para entrega at-least-once por dispatcher futuro (Step 27.1).';
