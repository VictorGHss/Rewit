-- ==============================================================================
-- REWIT DATABASE MIGRATION - V32
-- ==============================================================================
-- S3-A.4: Ciclo de vida de expurgo físico, data de remoção e trilha de auditoria
-- Tabelas afetadas: place_media, place_media_moderation_audit_logs
-- ==============================================================================

-- 1. DATA PRECISA DE REMOÇÃO LÓGICA PARA PERÍODO DE CARÊNCIA E EXPURGO FÍSICO
ALTER TABLE place_media ADD COLUMN IF NOT EXISTS removed_at TIMESTAMP WITH TIME ZONE;

-- Índice para busca eficiente de mídias elegíveis para expurgo após carência
CREATE INDEX IF NOT EXISTS idx_place_media_removed_at
ON place_media (removed_at)
WHERE status = 'REMOVED' AND purged_at IS NULL;

-- 2. TRILHA DE AUDITORIA DE MODERAÇÃO DE FOTOGRAFIAS DE LOCAL
-- Registra decisões administrativas (remoção direta, restauração, deferimento/indeferimento de denúncias)
-- Preserva histórico mesmo após expurgo físico do arquivo no storage
CREATE TABLE IF NOT EXISTS place_media_moderation_audit_logs (
    id UUID PRIMARY KEY,
    place_media_id UUID NOT NULL,
    place_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(32) NOT NULL, -- 'REMOVE', 'RESTORE', 'ACCEPT_REPORT', 'REJECT_REPORT'
    reason VARCHAR(255),
    notes TEXT,
    report_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_place_media_audit_media ON place_media_moderation_audit_logs (place_media_id);
CREATE INDEX IF NOT EXISTS idx_place_media_audit_place ON place_media_moderation_audit_logs (place_id);
CREATE INDEX IF NOT EXISTS idx_place_media_audit_actor ON place_media_moderation_audit_logs (actor_user_id);

