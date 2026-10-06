-- ==============================================================================
-- REWIT DATABASE MIGRATION - V17
-- ==============================================================================
-- C2 Fase 1: Consistência do estado da conta em users
-- ==============================================================================
-- Uma conta excluída (deleted_at preenchido) nunca é ativa. User.softDelete() já grava os dois campos
-- juntos, mas o schema aceitava is_active = TRUE com deleted_at preenchido.

-- 1. NORMALIZAÇÃO DE LINHAS ANTERIORES À CONSTRAINT
-- Essas linhas já são tratadas como excluídas em toda leitura do repositório (filtro deleted_at IS NULL);
-- desligar is_active não altera nenhum comportamento observável.
UPDATE users
SET is_active = FALSE
WHERE is_active = TRUE
  AND deleted_at IS NOT NULL;

-- 2. CONSTRAINT DE ESTADO
ALTER TABLE users ADD CONSTRAINT chk_users_active_not_deleted CHECK (NOT (is_active AND deleted_at IS NOT NULL));
