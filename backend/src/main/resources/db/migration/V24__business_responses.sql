-- ==============================================================================
-- REWIT DATABASE MIGRATION - V24
-- ==============================================================================
-- C11: Respostas oficiais de empresas a avaliações e a comentários raiz
-- Tabelas: business_responses, business_response_moderation_audit_logs
-- ==============================================================================
-- Entidade própria, independente de review_discussions: review_discussions.is_from_owner continua significando
-- "comentário do autor da avaliação" e não identifica empresas.
--
-- Política de FKs: nenhum fluxo da aplicação apaga fisicamente usuários (o purge preserva a linha), avaliações,
-- comentários, locais ou contas comerciais. A resposta acompanha o alvo (CASCADE pela avaliação e pelo comentário,
-- como denúncias e auditoria de discussões); a identidade empresarial e o autor pessoal são histórico preservado
-- (RESTRICT, como moderator_user_id na auditoria e decided_by_user_id nas reivindicações).

-- 1. INTEGRIDADE COMENTÁRIO x AVALIAÇÃO
-- Alvo da FK composta abaixo. id já é a chave primária, então a unicidade de (id, review_id) vale para qualquer
-- dado existente.
ALTER TABLE review_discussions
    ADD CONSTRAINT uq_review_discussions_id_review UNIQUE (id, review_id);

-- 2. RESPOSTAS OFICIAIS
CREATE TABLE business_responses (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    -- Nulo: resposta à avaliação. Preenchido: resposta a um comentário raiz da MESMA avaliação (FK composta)
    discussion_id UUID,
    business_account_id UUID NOT NULL REFERENCES business_accounts(id) ON DELETE RESTRICT,
    -- Local específico que a resposta representa (uma empresa pode controlar mais de um local da avaliação)
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    -- Autor pessoal, só para auditoria interna; nunca é a identidade pública da resposta
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    removed_by VARCHAR(16),
    removed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_business_responses_discussion FOREIGN KEY (discussion_id, review_id)
        REFERENCES review_discussions (id, review_id) ON DELETE CASCADE,
    CONSTRAINT chk_business_responses_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_business_responses_removed_by CHECK (removed_by IS NULL OR removed_by IN ('BUSINESS', 'MODERATION')),
    -- Ativa não tem remoção; removida registra quando e por quem
    CONSTRAINT chk_business_responses_removal CHECK (
        (status = 'ACTIVE' AND removed_at IS NULL AND removed_by IS NULL)
        OR (status = 'REMOVED' AND removed_at IS NOT NULL AND removed_by IS NOT NULL)
    ),
    CONSTRAINT chk_business_responses_content CHECK (length(btrim(content)) > 0 AND char_length(content) <= 2000)
);

-- No máximo uma resposta ATIVA por empresa e alvo; removidas ficam como histórico e liberam nova publicação.
-- Garantia final contra publicações concorrentes que passem juntas por uma checagem prévia da aplicação.
CREATE UNIQUE INDEX uq_business_responses_review_active
    ON business_responses (review_id, business_account_id)
    WHERE discussion_id IS NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_business_responses_discussion_active
    ON business_responses (discussion_id, business_account_id)
    WHERE discussion_id IS NOT NULL AND status = 'ACTIVE';

-- Leitura paginada por avaliação (cronológica) e histórico por conta comercial (mais recentes primeiro)
CREATE INDEX idx_business_responses_review ON business_responses (review_id, created_at, id);
CREATE INDEX idx_business_responses_business ON business_responses (business_account_id, created_at DESC, id);

-- 3. AUDITORIA DE MODERAÇÃO DAS RESPOSTAS
-- Separada das trilhas de avaliações (V11) e discussões (V20), no mesmo formato append-only. Sem decision nem
-- reports_affected_count: respostas não têm denúncias nesta versão; a decisão é a própria ação.
CREATE TABLE business_response_moderation_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    response_id UUID NOT NULL REFERENCES business_responses(id) ON DELETE CASCADE,
    moderator_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    justification TEXT NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_business_response_audit_action CHECK (action IN ('REMOVE_RESPONSE', 'RESTORE_RESPONSE')),
    CONSTRAINT chk_business_response_audit_previous_status CHECK (previous_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_business_response_audit_new_status CHECK (new_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_business_response_audit_justification CHECK (length(btrim(justification)) > 0)
);

CREATE INDEX idx_business_response_audit_response ON business_response_moderation_audit_logs (response_id, created_at);
CREATE INDEX idx_business_response_audit_moderator ON business_response_moderation_audit_logs (moderator_user_id);

-- Append-only: registros não são alterados. A remoção só ocorre por cascata da resposta.
CREATE OR REPLACE FUNCTION fn_prevent_business_response_audit_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'business_response_moderation_audit_logs é append-only: UPDATE não permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_business_response_audit_update
    BEFORE UPDATE ON business_response_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_business_response_audit_update();

COMMENT ON TABLE business_responses IS
    'Respostas oficiais de empresas a avaliações e comentários raiz (C11). Independente de review_discussions.';
COMMENT ON TABLE business_response_moderation_audit_logs IS
    'Trilha de auditoria append-only das decisões de moderação de respostas oficiais (C11).';
