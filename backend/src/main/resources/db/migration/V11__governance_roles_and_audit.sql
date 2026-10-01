-- ==============================================================================
-- REWIT DATABASE MIGRATION - V11
-- ==============================================================================
-- STEP 26.1: Fundação de Governança, Roles e Trilha de Auditoria Administrativa
-- ==============================================================================

-- 1. ADICIONAR PAPEL (ROLE) DE USUÁRIO NA TABELA USERS
ALTER TABLE users ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN'));

-- 2. TABELA DE AUDITORIA ADMINISTRATIVA DE MODERAÇÃO (APPEND-ONLY)
CREATE TABLE moderation_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    moderator_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    justification TEXT NOT NULL,
    previous_review_status VARCHAR(32) NOT NULL,
    new_review_status VARCHAR(32) NOT NULL,
    reports_affected_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_moderation_audit_action CHECK (action IN ('REMOVE_REVIEW', 'RESTORE_REVIEW')),
    CONSTRAINT chk_moderation_audit_decision CHECK (decision IN ('ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_moderation_audit_review ON moderation_audit_logs (review_id);
CREATE INDEX idx_moderation_audit_moderator ON moderation_audit_logs (moderator_user_id);
CREATE INDEX idx_moderation_audit_created ON moderation_audit_logs (created_at DESC);

COMMENT ON TABLE moderation_audit_logs IS
    'Trilha de auditoria append-only para decisões de moderação de publicações (Step 26.1).';
