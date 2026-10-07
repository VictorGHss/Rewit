-- ==============================================================================
-- REWIT DATABASE MIGRATION - V19
-- ==============================================================================
-- C3 D1: Denúncias comunitárias de discussões (comentários e respostas)
-- Tabela: discussion_reports
-- ==============================================================================
-- Agregado próprio, separado de review_reports. Uma denúncia por usuário por discussão; três denúncias
-- PENDING de usuários distintos colocam a discussão em UNDER_REVIEW (decidido na aplicação, sob lock da
-- linha da discussão).

CREATE TABLE discussion_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    discussion_id UUID NOT NULL REFERENCES review_discussions(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(64) NOT NULL,
    detail TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_discussion_report_reporter UNIQUE (discussion_id, reporter_user_id),
    CONSTRAINT chk_discussion_reports_reason CHECK (reason IN (
        'SPAM',
        'HARASSMENT',
        'HATE_SPEECH',
        'MISINFORMATION',
        'INAPPROPRIATE_CONTENT',
        'FRAUD'
    )),
    CONSTRAINT chk_discussion_reports_status CHECK (status IN (
        'PENDING',
        'ACCEPTED',
        'REJECTED'
    )),
    CONSTRAINT chk_discussion_reports_detail_len CHECK (detail IS NULL OR length(detail) <= 500)
);

-- Contagem de pendentes por discussão: prefixo (discussion_id) do índice da constraint única.
-- Fila administrativa de pendentes em ordem cronológica:
CREATE INDEX idx_discussion_reports_pending_queue ON discussion_reports (created_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_discussion_reports_reporter ON discussion_reports (reporter_user_id);
