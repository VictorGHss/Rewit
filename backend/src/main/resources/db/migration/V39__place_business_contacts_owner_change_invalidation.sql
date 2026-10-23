-- ==============================================================================
-- REWIT DATABASE MIGRATION - V39
-- ==============================================================================
-- Perfil comercial: contatos apagados na mudança de titularidade
-- Função: fn_clear_place_business_contacts_on_owner_change
-- Trigger: trg_places_owner_change_clears_business_contacts
-- ==============================================================================
-- Os contatos da V38 (phone, website_url, instagram_url, facebook_url) são declarações
-- do titular comercial. Quando places.claimed_by_business_id muda de fato (A -> B,
-- A -> NULL ou NULL -> A), os quatro são apagados no mesmo comando que muda o titular:
-- o trigger é BEFORE e zera a própria linha, então titular novo e contatos antigos
-- nunca coexistem, nem depois de rollback (que desfaz os dois juntos). Mesma regra
-- de horários e comodidades (V29, ADR-014) e de ofertas (V28, T2).
--
-- places.description não é apagada: é a descrição pública do local, anterior ao
-- perfil comercial. Inelegibilidade temporária (conta não aprovada, administrador
-- inoperante) não altera o titular: os contatos ficam gravados e só são ocultados na
-- leitura pública. Um mesmo UPDATE que troque o titular e grave contatos fica sem
-- contatos: o titular novo os grava depois, pelo fluxo autorizado.

CREATE FUNCTION fn_clear_place_business_contacts_on_owner_change()
RETURNS TRIGGER AS $$
BEGIN
    NEW.phone := NULL;
    NEW.website_url := NULL;
    NEW.instagram_url := NULL;
    NEW.facebook_url := NULL;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

CREATE TRIGGER trg_places_owner_change_clears_business_contacts
BEFORE UPDATE OF claimed_by_business_id ON places
FOR EACH ROW
WHEN (OLD.claimed_by_business_id IS DISTINCT FROM NEW.claimed_by_business_id)
EXECUTE FUNCTION fn_clear_place_business_contacts_on_owner_change();

-- Nenhuma mudança de privilégio; a verificação da V37 confirma a política do runtime, inclusive o search_path
-- fixo das funções de trigger
DO $$
BEGIN
    PERFORM rewit_assert_runtime_privileges();
END
$$;
