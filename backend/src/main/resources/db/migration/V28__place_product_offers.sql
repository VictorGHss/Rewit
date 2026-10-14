-- ==============================================================================
-- REWIT DATABASE MIGRATION - V28
-- ==============================================================================
-- F2.3: Ofertas comerciais de produtos por estabelecimento reivindicado
-- Tabela: place_product_offers
-- Trigger: trg_places_owner_change_invalidates_offers
-- ==============================================================================
-- 1. place_product_offers guarda a oferta oficial de um produto global num local,
--    publicada pela conta comercial titular: disponibilidade, preço opcional em BRL
--    e o último usuário que gravou. Uma oferta por (place_id, product_id).
--    É separada de product_presences (presença comunitária, V1), que não muda.
--
-- 2. Decisão T2: quando o titular comercial do local muda, as ofertas anteriores
--    são invalidadas definitivamente (apagadas). A titular que voltar não as
--    recupera. O trigger fica na própria linha de places para valer em qualquer
--    caminho que altere claimed_by_business_id (decisão de reivindicação, um futuro
--    fluxo de transferência, desvinculação ou o ON DELETE SET NULL da conta), na
--    mesma transação da mudança. Inelegibilidade temporária (conta não aprovada,
--    administrador inoperante) e mudança de status do local não alteram
--    claimed_by_business_id e por isso não apagam nada: só ocultam na leitura.

CREATE TABLE place_product_offers (
    id UUID PRIMARY KEY,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    business_account_id UUID NOT NULL REFERENCES business_accounts(id) ON DELETE CASCADE,
    availability VARCHAR(32) NOT NULL,
    price NUMERIC(12, 2),
    currency VARCHAR(3) NOT NULL DEFAULT 'BRL',
    updated_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_place_product_offer UNIQUE (place_id, product_id),
    CONSTRAINT chk_place_product_offer_availability CHECK (availability IN ('AVAILABLE', 'OUT_OF_STOCK')),
    CONSTRAINT chk_place_product_offer_price CHECK (price IS NULL OR price >= 0),
    CONSTRAINT chk_place_product_offer_currency CHECK (currency = 'BRL')
);

-- A listagem filtra por place_id (coberto por uq_place_product_offer); estes índices
-- servem às exclusões em cascata por conta comercial e por produto.
CREATE INDEX idx_place_product_offers_business ON place_product_offers (business_account_id);
CREATE INDEX idx_place_product_offers_product ON place_product_offers (product_id);

-- Apaga todas as ofertas do local, sem filtrar por conta: nenhuma linha presente no
-- momento da troca sobrevive, nem uma que já estivesse associada à conta que entra.
CREATE OR REPLACE FUNCTION fn_invalidate_place_product_offers_on_owner_change()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM place_product_offers
    WHERE place_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_places_owner_change_invalidates_offers
AFTER UPDATE OF claimed_by_business_id ON places
FOR EACH ROW
WHEN (OLD.claimed_by_business_id IS DISTINCT FROM NEW.claimed_by_business_id)
EXECUTE FUNCTION fn_invalidate_place_product_offers_on_owner_change();
