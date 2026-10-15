-- ==============================================================================
-- REWIT DATABASE MIGRATION - V29
-- ==============================================================================
-- F2.5: Invalidação de horários e comodidades na mudança de titularidade (ADR-014)
-- Função: fn_invalidate_place_commercial_data_on_owner_change
-- Trigger: trg_places_owner_change_invalidates_commercial_data
-- ==============================================================================
-- Horários (V26) e comodidades (V27) são declarações do titular comercial sobre o
-- local. Quando places.claimed_by_business_id muda de fato (A -> B, A -> NULL ou
-- NULL -> A), a configuração mestre de cada um é apagada na mesma transação; os
-- intervalos e as seleções saem pelas FKs ON DELETE CASCADE já existentes. Assim os
-- dados de uma titularidade nunca reaparecem numa titularidade posterior, nem quando
-- a mesma conta volta a reivindicar o local.
--
-- Inelegibilidade temporária (conta não aprovada, administrador inoperante) e
-- mudança de status do local não alteram claimed_by_business_id e só ocultam os dados
-- na leitura, sem apagá-los.
--
-- Mecanismo independente do trigger T2 das ofertas (V28,
-- trg_places_owner_change_invalidates_offers), que não é alterado.

CREATE FUNCTION fn_invalidate_place_commercial_data_on_owner_change()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM place_opening_hours_schedules
    WHERE place_id = NEW.id;

    DELETE FROM place_amenity_configurations
    WHERE place_id = NEW.id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_places_owner_change_invalidates_commercial_data
AFTER UPDATE OF claimed_by_business_id ON places
FOR EACH ROW
WHEN (OLD.claimed_by_business_id IS DISTINCT FROM NEW.claimed_by_business_id)
EXECUTE FUNCTION fn_invalidate_place_commercial_data_on_owner_change();
