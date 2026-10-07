-- ==============================================================================
-- REWIT DATABASE MIGRATION - V21
-- ==============================================================================
-- C2: Estado explícito do ciclo de vida da conta
-- Tabela: users
-- ==============================================================================
-- account_status passa a ser a fonte de verdade: ACTIVE, DEACTIVATED (pelo próprio usuário), SUSPENDED (por ação
-- administrativa) e DELETED (exclusão lógica definitiva). is_active e deleted_at permanecem, derivados dele, porque
-- as leituras existentes os usam (deleted_at também guarda o instante da exclusão); a constraint de consistência
-- impede que voltem a ter uma semântica própria.

-- 1. COLUNA
-- Com DEFAULT constante, o ADD COLUMN não reescreve a tabela; toda linha existente nasce ACTIVE e o backfill
-- abaixo só atualiza as contas que não estão operacionais.
ALTER TABLE users ADD COLUMN account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

-- 2. BACKFILL A PARTIR DA SEMÂNTICA ANTERIOR
-- deleted_at preenchido: excluída (V17 já garante is_active = FALSE nessas linhas).
UPDATE users
SET account_status = 'DELETED'
WHERE deleted_at IS NOT NULL;

-- Inativa sem exclusão: nenhum fluxo da aplicação produzia esse estado, que só resulta de intervenção manual e não
-- registra quem desativou. Vira SUSPENDED, que só a administração reverte; DEACTIVATED permitiria ao próprio
-- usuário desfazer um bloqueio manual.
UPDATE users
SET account_status = 'SUSPENDED'
WHERE is_active = FALSE
  AND deleted_at IS NULL;

-- 3. CONSTRAINTS
ALTER TABLE users ADD CONSTRAINT chk_users_account_status
    CHECK (account_status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED', 'DELETED'));

-- is_active e deleted_at derivados do estado. O nome é avaliado depois de chk_users_active_not_deleted (V17), que
-- continua reportando a violação que já descrevia.
ALTER TABLE users ADD CONSTRAINT chk_users_status_consistency
    CHECK (is_active = (account_status = 'ACTIVE')
       AND (deleted_at IS NOT NULL) = (account_status = 'DELETED'));
