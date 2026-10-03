-- ==============================================================================
-- REWIT DATABASE MIGRATION - V15
-- ==============================================================================
-- STEP 28.3: Quarentena persistente de objetos de storage sem referência
-- Tabela: storage_object_quarantine
-- ==============================================================================
-- Registra observações de objetos sob reviews/ sem linha em review_media.
-- Sem FK para reviews/review_media: o objeto em quarentena justamente não tem
-- referência válida. Nenhuma linha aqui autoriza remoção física.

CREATE TABLE storage_object_quarantine (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    object_key TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OBSERVED',
    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_modified_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_storage_object_quarantine_object_key UNIQUE (object_key),
    CONSTRAINT chk_storage_object_quarantine_status CHECK (status IN ('OBSERVED', 'CONFIRMED_ORPHAN')),
    CONSTRAINT chk_storage_object_quarantine_observation_order CHECK (last_observed_at >= first_observed_at),
    CONSTRAINT chk_storage_object_quarantine_confirmation CHECK (
        (status = 'CONFIRMED_ORPHAN') = (confirmed_at IS NOT NULL)
    )
);

COMMENT ON COLUMN storage_object_quarantine.first_observed_at IS
    'Primeira observação persistida sem referência; início do grace period (Step 28.3).';
