-- ==============================================================================
-- REWIT - V5__authentication_sessions.sql
-- Migração incremental Flyway: Sessões de Autenticação e Refresh Tokens (Step 4)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. TABELA DE SESSÕES DE AUTENTICAÇÃO (REFRESH TOKENS PERSISTIDOS E REVOGÁVEIS)
-- Os refresh tokens NUNCA são persistidos em texto puro; apenas seu hash SHA-256
-- é armazenado em token_hash, assegurando proteção contra vazamentos de banco.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_session_id UUID REFERENCES auth_sessions(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP WITH TIME ZONE,
    user_agent TEXT,
    ip_address INET,
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash)
);

-- ------------------------------------------------------------------------------
-- 2. ÍNDICES DE PERFORMANCE E CONSULTA
-- ------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON auth_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires_at ON auth_sessions (expires_at);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_revoked_at ON auth_sessions (revoked_at);
