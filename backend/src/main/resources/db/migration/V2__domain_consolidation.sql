-- ==============================================================================
-- REWIT - V2__domain_consolidation.sql
-- Migração incremental Flyway: Consolidação do Domínio Principal e Refinamentos
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. USUÁRIOS: PREPARAÇÃO PARA SOFT-DELETE (SEÇÃO 4)
-- ------------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- ------------------------------------------------------------------------------
-- 2. LOCAIS FÍSICOS (PLACES): ENDEREÇO ESTRUTURADO (NÚMERO E BAIRRO - SEÇÃO 8)
-- ------------------------------------------------------------------------------
ALTER TABLE places ADD COLUMN IF NOT EXISTS street_number VARCHAR(32);
ALTER TABLE places ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(128);

-- Índice composto para buscas locais por cidade e bairro
CREATE INDEX IF NOT EXISTS idx_places_city_neighborhood ON places (city, neighborhood);

-- ------------------------------------------------------------------------------
-- 3. CHECK-INS: MÉTODO DE VERIFICAÇÃO PRESENCIAL (GPS, QR_CODE, NFC, BEACON - SEÇÃO 19)
-- ------------------------------------------------------------------------------
ALTER TABLE check_ins ADD COLUMN IF NOT EXISTS verification_method VARCHAR(32) NOT NULL DEFAULT 'GPS';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_checkin_verification_method'
    ) THEN
        ALTER TABLE check_ins ADD CONSTRAINT chk_checkin_verification_method 
            CHECK (verification_method IN ('GPS', 'QR_CODE', 'NFC', 'BEACON'));
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 4. INTERESSES DO USUÁRIO: PESO / PREFERÊNCIA RELATIVA (SEÇÃO 23)
-- ------------------------------------------------------------------------------
ALTER TABLE user_interests ADD COLUMN IF NOT EXISTS weight NUMERIC(3, 2) NOT NULL DEFAULT 1.00;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_interest_weight'
    ) THEN
        ALTER TABLE user_interests ADD CONSTRAINT chk_user_interest_weight 
            CHECK (weight >= 0.00 AND weight <= 1.00);
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 5. NOTIFICAÇÕES INTERNAS: CARGA DE METADADOS FLEXÍVEIS (SEÇÃO 27)
-- ------------------------------------------------------------------------------
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS metadata_json JSONB;

-- ------------------------------------------------------------------------------
-- 6. ESTATÍSTICAS DERIVADAS (RATEABLE_TARGET_STATS): ÍNDICE DE PERFORMANCE
-- ------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rateable_target_stats_rating ON rateable_target_stats (average_rating DESC);
