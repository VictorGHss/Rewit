-- ==============================================================================
-- V10: Fundação de Reputação V1 (STEP 23.0)
--
-- Cria a tabela `user_reputation` como snapshot derivado dos fatos existentes.
-- NAO e source of truth -- os fatos continuam em `reviews`, `review_reactions`,
-- `user_follows` e `review_targets`.
--
-- Nao implementa score numerico: ADR-008 nao especifica pesos formais.
-- Versao da regra de calculo: 1
-- Reviews anonimas excluidas dos sinais publicos (ADR-008, secao 3).
-- ==============================================================================

CREATE TABLE user_reputation (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version         INT  NOT NULL DEFAULT 1 CHECK (version >= 1),
    active_reviews             INT NOT NULL DEFAULT 0 CHECK (active_reviews >= 0),
    verified_reviews           INT NOT NULL DEFAULT 0 CHECK (verified_reviews >= 0),
    helpful_votes_received     INT NOT NULL DEFAULT 0 CHECK (helpful_votes_received >= 0),
    distinct_targets_reviewed  INT NOT NULL DEFAULT 0 CHECK (distinct_targets_reviewed >= 0),
    calculated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_verified_lte_active CHECK (verified_reviews <= active_reviews)
);

CREATE INDEX idx_user_reputation_calculated_at ON user_reputation (user_id, calculated_at DESC);

COMMENT ON TABLE user_reputation IS
    'Snapshot derivado de reputacao V1. Source of truth esta em reviews, review_reactions e review_targets. '
    'Reviews anonimas excluidas dos sinais publicos conforme ADR-008.';
