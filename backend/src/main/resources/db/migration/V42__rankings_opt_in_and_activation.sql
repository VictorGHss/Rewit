-- ==============================================================================
-- REWIT DATABASE MIGRATION - V42
-- ==============================================================================
-- Rankings V1: opt-in de participação, configurações de ativação e suporte a consultas
-- Tabelas: profiles (coluna show_in_rankings), ranking_configurations (nova), review_reactions (índice)
-- ==============================================================================
-- 1. Opt-in de participação nos rankings (ADR-016):
--    Por padrão, novos e antigos usuários não aparecem nos rankings até ativarem a
--    participação (profiles.show_in_rankings DEFAULT FALSE).
--
-- 2. Tabela singleton de configurações dos rankings com timestamp de ativação persistente
--    (rankings_activated_at). O período efetivo do ranking tem início no maior entre
--    o início civil do período e a ativação dos rankings.
--
-- 3. Índice parcial em review_reactions para acelerar a agregação de votos úteis.
--
-- 4. Privilégios de runtime e asserção de conformidade (V37).
-- ==============================================================================

ALTER TABLE profiles
    ADD COLUMN show_in_rankings BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_profiles_show_in_rankings
    ON profiles (user_id)
    WHERE show_in_rankings = TRUE;

CREATE TABLE ranking_configurations (
    key VARCHAR(64) PRIMARY KEY,
    value_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

INSERT INTO ranking_configurations (key, value_timestamp)
VALUES ('rankings_activated_at', NOW())
ON CONFLICT (key) DO NOTHING;

CREATE INDEX idx_review_reactions_helpful_ranking
    ON review_reactions (created_at DESC, review_id, user_id)
    WHERE reaction_type = 'HELPFUL';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        GRANT SELECT, INSERT, UPDATE ON ranking_configurations TO rewit_app;
        PERFORM rewit_assert_runtime_privileges();
    END IF;
END
$$;
