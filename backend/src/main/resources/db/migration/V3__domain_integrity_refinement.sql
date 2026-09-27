-- ==============================================================================
-- REWIT - V3__domain_integrity_refinement.sql
-- Migração incremental Flyway: Refinamento de Integridade de Domínio e Banco (Step 2.1)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. CHECK-IN: REFINAMENTO DE ESTADO PADRÃO E DATA DE VERIFICAÇÃO (SEÇÃO 2)
-- Invariantes:
-- - Novo CheckIn inicia como PENDING por padrão (nunca VERIFIED).
-- - verified_at deve ser NULL enquanto status não for VERIFIED.
-- - verified_at é preenchido exclusivamente quando status transiciona para VERIFIED.
-- - CheckIn REJECTED ou PENDING não pode possuir verified_at.
-- ------------------------------------------------------------------------------
ALTER TABLE check_ins ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE check_ins ALTER COLUMN verified_at DROP NOT NULL;
ALTER TABLE check_ins ALTER COLUMN verified_at DROP DEFAULT;

-- Garantir valores válidos para check_ins.status
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_checkin_status'
    ) THEN
        ALTER TABLE check_ins ADD CONSTRAINT chk_checkin_status 
            CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED'));
    END IF;
END $$;

-- Invariante de coerência entre status e verified_at
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_checkin_verified_at_consistency'
    ) THEN
        ALTER TABLE check_ins ADD CONSTRAINT chk_checkin_verified_at_consistency 
            CHECK ((status = 'VERIFIED' AND verified_at IS NOT NULL) OR 
                   (status IN ('PENDING', 'REJECTED') AND verified_at IS NULL));
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 2. CONSISTÊNCIA CHECKIN <-> REVIEW (SEÇÃO 3)
-- Invariantes:
-- - check_in.review_id = review.id
-- - check_in.user_id = review.user_id
-- - check_in.place_id = review.context_place_id
-- - Se review.context_place_id for NULL, CheckIn não é permitido.
-- Solução:
-- - Unique constraint composta em reviews (id, user_id, context_place_id)
-- - Foreign Key composta em check_ins referenciando a tupla exata
-- - Trigger relacional para validação explícita com mensagens de erro descritivas
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_reviews_id_user_context_place'
    ) THEN
        ALTER TABLE reviews ADD CONSTRAINT uq_reviews_id_user_context_place 
            UNIQUE (id, user_id, context_place_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_check_ins_review_user_place'
    ) THEN
        ALTER TABLE check_ins ADD CONSTRAINT fk_check_ins_review_user_place 
            FOREIGN KEY (review_id, user_id, place_id) 
            REFERENCES reviews (id, user_id, context_place_id) 
            ON DELETE CASCADE;
    END IF;
END $$;

-- Trigger complementar para validação com mensagens de erro semânticas
CREATE OR REPLACE FUNCTION fn_check_in_review_consistency()
RETURNS TRIGGER AS $$
DECLARE
    v_review RECORD;
BEGIN
    SELECT user_id, context_place_id INTO v_review
    FROM reviews
    WHERE id = NEW.review_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Review associada (%) não encontrada para o check-in', NEW.review_id;
    END IF;

    IF v_review.context_place_id IS NULL THEN
        RAISE EXCEPTION 'Check-in não permitido: a Review associada (%) não possui context_place_id definido', NEW.review_id;
    END IF;

    IF NEW.user_id <> v_review.user_id THEN
        RAISE EXCEPTION 'Inconsistência de integridade: user_id do check-in (%) não coincide com o autor da review (%)', 
            NEW.user_id, v_review.user_id;
    END IF;

    IF NEW.place_id <> v_review.context_place_id THEN
        RAISE EXCEPTION 'Inconsistência de integridade: place_id do check-in (%) não coincide com context_place_id da review (%)', 
            NEW.place_id, v_review.context_place_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_check_in_review_consistency ON check_ins;
CREATE TRIGGER trg_check_in_review_consistency
BEFORE INSERT OR UPDATE ON check_ins
FOR EACH ROW
EXECUTE FUNCTION fn_check_in_review_consistency();

-- ------------------------------------------------------------------------------
-- 3. FONTE DA VERDADE PARA "VERIFIED ON SITE" (SEÇÃO 4)
-- Invariantes:
-- - CheckIn.status == 'VERIFIED' é a ÚNICA fonte de verdade.
-- - reviews.is_verified_on_site é apenas projeção/cache desnormalizado.
-- - Triggers garantem sincronização bidirecional e impedem que Review.is_verified_on_site
--   seja definida como TRUE sem um CheckIn correspondente com status VERIFIED.
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_sync_check_in_to_review_verified()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        UPDATE reviews 
        SET is_verified_on_site = FALSE, updated_at = NOW() 
        WHERE id = OLD.review_id;
        RETURN OLD;
    ELSE
        UPDATE reviews 
        SET is_verified_on_site = (NEW.status = 'VERIFIED'), updated_at = NOW() 
        WHERE id = NEW.review_id;
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_check_in_to_review_verified ON check_ins;
CREATE TRIGGER trg_sync_check_in_to_review_verified
AFTER INSERT OR UPDATE OR DELETE ON check_ins
FOR EACH ROW
EXECUTE FUNCTION fn_sync_check_in_to_review_verified();

-- Trigger preventivo na tabela reviews para impedir alteração manual descolada do CheckIn
CREATE OR REPLACE FUNCTION fn_prevent_unverified_review_flag()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_verified_on_site = TRUE THEN
        IF NOT EXISTS (
            SELECT 1 FROM check_ins 
            WHERE review_id = NEW.id AND status = 'VERIFIED'
        ) THEN
            RAISE EXCEPTION 'Violação de integridade: Review.is_verified_on_site não pode ser TRUE sem um CheckIn com status VERIFIED';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_unverified_review_flag ON reviews;
CREATE TRIGGER trg_prevent_unverified_review_flag
BEFORE INSERT OR UPDATE OF is_verified_on_site ON reviews
FOR EACH ROW
EXECUTE FUNCTION fn_prevent_unverified_review_flag();

-- ------------------------------------------------------------------------------
-- 4. INTEGRIDADE DE RATEABLE TARGET E ESPECIALIZAÇÕES (SEÇÃO 5)
-- Invariantes:
-- - Garante coerência entre rateable_targets.target_type e a tabela especializada.
-- - Um target de tipo 'PLACE' só pode ser inserido em places.
-- - Um target de tipo 'PRODUCT' só pode ser inserido em products.
-- - Um target de tipo 'SERVICE' só pode ser inserido em services.
-- - Um target de tipo 'EVENT' só pode ser inserido em events.
-- - Preserva a integridade da FK física existente.
-- - Impede alteração de target_type se houver registros vinculados em tabelas filhas.
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_rateable_target_type'
    ) THEN
        ALTER TABLE rateable_targets ADD CONSTRAINT chk_rateable_target_type 
            CHECK (target_type IN ('PLACE', 'PRODUCT', 'SERVICE', 'EVENT'));
    END IF;
END $$;

CREATE OR REPLACE FUNCTION fn_validate_rateable_target_specialization()
RETURNS TRIGGER AS $$
DECLARE
    v_actual_type VARCHAR(32);
    v_expected_type VARCHAR(32);
BEGIN
    IF TG_TABLE_NAME = 'places' THEN
        v_expected_type := 'PLACE';
    ELSIF TG_TABLE_NAME = 'products' THEN
        v_expected_type := 'PRODUCT';
    ELSIF TG_TABLE_NAME = 'services' THEN
        v_expected_type := 'SERVICE';
    ELSIF TG_TABLE_NAME = 'events' THEN
        v_expected_type := 'EVENT';
    ELSE
        RAISE EXCEPTION 'Tabela desconhecida na validação de especialização: %', TG_TABLE_NAME;
    END IF;

    SELECT target_type INTO v_actual_type
    FROM rateable_targets
    WHERE id = NEW.id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target ID % não existe em rateable_targets', NEW.id;
    END IF;

    IF v_actual_type <> v_expected_type THEN
        RAISE EXCEPTION 'Incoerência de especialização: rateable_target % é do tipo %, mas registro foi associado à tabela % (esperado: %)',
            NEW.id, v_actual_type, TG_TABLE_NAME, v_expected_type;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_validate_place_specialization ON places;
CREATE TRIGGER trg_validate_place_specialization
BEFORE INSERT OR UPDATE OF id ON places
FOR EACH ROW
EXECUTE FUNCTION fn_validate_rateable_target_specialization();

DROP TRIGGER IF EXISTS trg_validate_product_specialization ON products;
CREATE TRIGGER trg_validate_product_specialization
BEFORE INSERT OR UPDATE OF id ON products
FOR EACH ROW
EXECUTE FUNCTION fn_validate_rateable_target_specialization();

DROP TRIGGER IF EXISTS trg_validate_service_specialization ON services;
CREATE TRIGGER trg_validate_service_specialization
BEFORE INSERT OR UPDATE OF id ON services
FOR EACH ROW
EXECUTE FUNCTION fn_validate_rateable_target_specialization();

DROP TRIGGER IF EXISTS trg_validate_event_specialization ON events;
CREATE TRIGGER trg_validate_event_specialization
BEFORE INSERT OR UPDATE OF id ON events
FOR EACH ROW
EXECUTE FUNCTION fn_validate_rateable_target_specialization();

-- Impedir modificação de target_type quando o alvo já possui especialização
CREATE OR REPLACE FUNCTION fn_prevent_rateable_target_type_change()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.target_type <> NEW.target_type THEN
        IF EXISTS (SELECT 1 FROM places WHERE id = OLD.id)
           OR EXISTS (SELECT 1 FROM products WHERE id = OLD.id)
           OR EXISTS (SELECT 1 FROM services WHERE id = OLD.id)
           OR EXISTS (SELECT 1 FROM events WHERE id = OLD.id) THEN
            RAISE EXCEPTION 'Proibido alterar target_type de % para %: o alvo já possui dados especializados vinculados',
                OLD.target_type, NEW.target_type;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_rateable_target_type_change ON rateable_targets;
CREATE TRIGGER trg_prevent_rateable_target_type_change
BEFORE UPDATE OF target_type ON rateable_targets
FOR EACH ROW
EXECUTE FUNCTION fn_prevent_rateable_target_type_change();

-- ------------------------------------------------------------------------------
-- 5. CHECK CONSTRAINTS PARA CAMPOS ENUM-LIKE NO BANCO (SEÇÃO 5)
-- ------------------------------------------------------------------------------

-- Reviews: status e visibility
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_status') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_status 
            CHECK (status IN ('ACTIVE', 'UNDER_REVIEW', 'REMOVED'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_visibility') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_visibility 
            CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'FOLLOWERS'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_location_accuracy') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_location_accuracy 
            CHECK (location_accuracy_meters IS NULL OR location_accuracy_meters >= 0);
    END IF;
END $$;

-- Places: status
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_places_status') THEN
        ALTER TABLE places ADD CONSTRAINT chk_places_status 
            CHECK (status IN ('ACTIVE', 'INACTIVE', 'CLOSED'));
    END IF;
END $$;

-- Products: status
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_products_status') THEN
        ALTER TABLE products ADD CONSTRAINT chk_products_status 
            CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED'));
    END IF;
END $$;

-- Services: status
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_services_status') THEN
        ALTER TABLE services ADD CONSTRAINT chk_services_status 
            CHECK (status IN ('ACTIVE', 'INACTIVE'));
    END IF;
END $$;

-- Events: status
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_events_status') THEN
        ALTER TABLE events ADD CONSTRAINT chk_events_status 
            CHECK (status IN ('SCHEDULED', 'HAPPENING_NOW', 'FINISHED', 'CANCELLED'));
    END IF;
END $$;

-- Users: auth_provider
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_auth_provider') THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_auth_provider 
            CHECK (auth_provider IN ('LOCAL', 'GOOGLE', 'APPLE'));
    END IF;
END $$;

-- Business Accounts: verification_status e plan_tier
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_business_verification_status') THEN
        ALTER TABLE business_accounts ADD CONSTRAINT chk_business_verification_status 
            CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_business_plan_tier') THEN
        ALTER TABLE business_accounts ADD CONSTRAINT chk_business_plan_tier 
            CHECK (plan_tier IN ('FREE', 'PREMIUM'));
    END IF;
END $$;

-- Review Tags: source
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_tags_source') THEN
        ALTER TABLE review_tags ADD CONSTRAINT chk_review_tags_source 
            CHECK (source IN ('USER', 'RULE', 'AI', 'MODERATOR'));
    END IF;
END $$;
