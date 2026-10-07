-- ==============================================================================
-- REWIT DATABASE MIGRATION - V20
-- ==============================================================================
-- C3: Trilha de auditoria append-only da moderação administrativa de discussões
-- Tabela: discussion_moderation_audit_logs
-- ==============================================================================
-- Separada de moderation_audit_logs (avaliações), sem generalizar a existente. Mesma política de FKs:
-- CASCADE pela discussão e RESTRICT pelo moderador.

CREATE TABLE discussion_moderation_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    discussion_id UUID NOT NULL REFERENCES review_discussions(id) ON DELETE CASCADE,
    moderator_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    justification TEXT NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    new_status VARCHAR(32) NOT NULL,
    reports_affected_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_discussion_moderation_audit_action CHECK (action IN ('REMOVE_DISCUSSION', 'RESTORE_DISCUSSION')),
    CONSTRAINT chk_discussion_moderation_audit_decision CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_discussion_moderation_audit_previous_status CHECK (previous_status IN ('ACTIVE', 'UNDER_REVIEW', 'REMOVED')),
    CONSTRAINT chk_discussion_moderation_audit_new_status CHECK (new_status IN ('ACTIVE', 'UNDER_REVIEW', 'REMOVED')),
    CONSTRAINT chk_discussion_moderation_audit_reports CHECK (reports_affected_count >= 0),
    CONSTRAINT chk_discussion_moderation_audit_justification CHECK (length(btrim(justification)) > 0)
);

CREATE INDEX idx_discussion_moderation_audit_discussion ON discussion_moderation_audit_logs (discussion_id, created_at);
CREATE INDEX idx_discussion_moderation_audit_moderator ON discussion_moderation_audit_logs (moderator_user_id);

-- Append-only: registros não são alterados. A remoção só ocorre por cascata da discussão.
CREATE OR REPLACE FUNCTION fn_prevent_discussion_moderation_audit_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'discussion_moderation_audit_logs é append-only: UPDATE não permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_discussion_moderation_audit_update
    BEFORE UPDATE ON discussion_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_discussion_moderation_audit_update();

COMMENT ON TABLE discussion_moderation_audit_logs IS
    'Trilha de auditoria append-only das decisões de moderação de discussões (C3).';
