-- ==============================================================================
-- REWIT DATABASE MIGRATION - V23
-- ==============================================================================
-- C9: Solicitações de reivindicação de locais por contas comerciais
-- Tabelas: place_claim_requests
-- ==============================================================================
-- A aprovação vincula o local à conta (places.claimed_by_business_id, V1) na mesma transação que decide a
-- solicitação. A decisão fica registrada na própria linha (quem, quando e por quê).

CREATE TABLE place_claim_requests (
    id UUID PRIMARY KEY,
    business_account_id UUID NOT NULL REFERENCES business_accounts(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    evidence_description TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    decided_at TIMESTAMP WITH TIME ZONE,
    -- Usuários nunca são apagados fisicamente (exclusão lógica e purge preservam a linha): RESTRICT, como na auditoria
    decided_by_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    decision_reason TEXT,
    CONSTRAINT chk_place_claim_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_place_claim_evidence_length CHECK (char_length(evidence_description) BETWEEN 20 AND 1000),
    -- Pendente não tem decisão; decidida tem decisão completa e justificada
    CONSTRAINT chk_place_claim_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by_user_id IS NULL AND decision_reason IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by_user_id IS NOT NULL
            AND decision_reason IS NOT NULL AND length(btrim(decision_reason)) > 0
            AND char_length(decision_reason) <= 1000)
    )
);

-- No máximo uma solicitação pendente por local, qualquer que seja a conta solicitante
CREATE UNIQUE INDEX uq_place_claim_pending_place ON place_claim_requests (place_id) WHERE status = 'PENDING';

-- Fila administrativa: filtro por status, mais antigas primeiro
CREATE INDEX idx_place_claim_status_created ON place_claim_requests (status, created_at, id);

-- Histórico de uma conta: mais recentes primeiro
CREATE INDEX idx_place_claim_business_created ON place_claim_requests (business_account_id, created_at DESC, id);
