-- ==============================================================================
-- REWIT DATABASE MIGRATION - V8
-- ==============================================================================
-- STEP 19.0: Subsistema de Denúncias Comunitárias e Moderação Preventiva de Reviews
-- Tabela: review_reports
-- ==============================================================================

CREATE TABLE review_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(64) NOT NULL,
    detail TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_reporter UNIQUE (review_id, reporter_user_id),
    CONSTRAINT chk_review_reports_reason CHECK (reason IN (
        'SPAM',
        'HARASSMENT',
        'HATE_SPEECH',
        'MISINFORMATION',
        'INAPPROPRIATE_CONTENT',
        'FRAUD'
    )),
    CONSTRAINT chk_review_reports_status CHECK (status IN (
        'PENDING',
        'ACCEPTED',
        'REJECTED'
    )),
    CONSTRAINT chk_review_reports_detail_len CHECK (detail IS NULL OR length(detail) <= 500)
);

CREATE INDEX idx_review_reports_review ON review_reports (review_id);
CREATE INDEX idx_review_reports_reporter ON review_reports (reporter_user_id);
CREATE INDEX idx_review_reports_status_created ON review_reports (status, created_at);
