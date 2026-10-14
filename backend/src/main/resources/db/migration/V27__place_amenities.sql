-- ==============================================================================
-- REWIT DATABASE MIGRATION - V27
-- ==============================================================================
-- F2.2: Gestão de comodidades de estabelecimentos reivindicados
-- Tabelas: place_amenity_configurations, place_amenity_selections
-- ==============================================================================
-- 1. place_amenity_configurations registra que o local tem comodidades configuradas,
--    a conta comercial que configurou e o último usuário que gravou. Configuração
--    existente sem seleções = a empresa declarou não oferecer nenhuma comodidade
--    do catálogo; ausência de linha = nunca configurado.
--
-- 2. place_amenity_selections guarda um código por comodidade selecionada. O
--    catálogo é controlado (enum PlaceAmenity no backend) e espelhado no CHECK
--    chk_place_amenity_code; a chave primária composta impede duplicidade.
--
-- Comodidades não são serviços avaliáveis: a tabela services (V1) continua
-- reservada a RateableTarget e não é reutilizada aqui.

CREATE TABLE place_amenity_configurations (
    place_id UUID PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,
    business_account_id UUID NOT NULL REFERENCES business_accounts(id) ON DELETE RESTRICT,
    updated_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE place_amenity_selections (
    place_id UUID NOT NULL REFERENCES place_amenity_configurations(place_id) ON DELETE CASCADE,
    amenity_code VARCHAR(64) NOT NULL,
    CONSTRAINT pk_place_amenity_selections PRIMARY KEY (place_id, amenity_code),
    CONSTRAINT chk_place_amenity_code CHECK (amenity_code IN (
        'WIFI',
        'PARKING',
        'WHEELCHAIR_ACCESSIBLE',
        'ACCESSIBLE_RESTROOM',
        'CUSTOMER_RESTROOM',
        'AIR_CONDITIONING',
        'OUTDOOR_SEATING',
        'PET_FRIENDLY'
    ))
);

CREATE INDEX idx_place_amenity_configurations_business
    ON place_amenity_configurations (business_account_id);
