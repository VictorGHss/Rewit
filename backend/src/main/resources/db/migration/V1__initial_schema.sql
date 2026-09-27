-- ==============================================================================
-- REWIT - V1__initial_schema.sql
-- Migração inicial Flyway: Ativação de extensões e DDL completo do Domínio
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. EXTENSÕES OBRIGATÓRIAS (PostGIS, UUID, Trigram, Crypto)
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------------------------
-- 2. RAIZ DE ALVOS AVALIÁVEIS (RATEABLE TARGETS - ADR-009)
-- ------------------------------------------------------------------------------
CREATE TABLE rateable_targets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type VARCHAR(32) NOT NULL, -- 'PLACE', 'PRODUCT', 'SERVICE', 'EVENT'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rateable_targets_type ON rateable_targets (target_type);

-- ------------------------------------------------------------------------------
-- 3. USUÁRIOS E PERFIS
-- ------------------------------------------------------------------------------
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    auth_provider VARCHAR(32) NOT NULL DEFAULT 'LOCAL', -- 'LOCAL', 'GOOGLE', 'APPLE'
    provider_user_id VARCHAR(128),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_provider ON users (auth_provider, provider_user_id);

CREATE TABLE profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    handle VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    bio TEXT,
    avatar_url TEXT,
    reputation_score INT NOT NULL DEFAULT 0 CHECK (reputation_score >= 0),
    is_anonymous_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_profiles_user_id UNIQUE (user_id),
    CONSTRAINT uq_profiles_handle UNIQUE (handle)
);

CREATE INDEX idx_profiles_handle_trgm ON profiles USING GIN (handle gin_trgm_ops);

-- ------------------------------------------------------------------------------
-- 4. CONTAS COMERCIAIS (BUSINESS)
-- ------------------------------------------------------------------------------
CREATE TABLE business_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    corporate_name VARCHAR(255) NOT NULL,
    tax_id VARCHAR(32) NOT NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'APPROVED', 'REJECTED'
    plan_tier VARCHAR(32) NOT NULL DEFAULT 'FREE', -- 'FREE', 'PREMIUM'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_business_tax_id UNIQUE (tax_id)
);

CREATE INDEX idx_business_user ON business_accounts (user_id);

-- ------------------------------------------------------------------------------
-- 5. LOCAIS FÍSICOS (PLACES)
-- ------------------------------------------------------------------------------
CREATE TABLE places (
    id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    description TEXT,
    address_text TEXT NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(50) NOT NULL,
    country VARCHAR(10) NOT NULL DEFAULT 'BR',
    coordinates GEOGRAPHY(Point, 4326) NOT NULL,
    validation_radius_meters INT NOT NULL DEFAULT 50 CHECK (validation_radius_meters > 0),
    origin VARCHAR(32) NOT NULL DEFAULT 'USER', -- 'USER', 'GOOGLE', 'IMPORT'
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    claimed_by_business_id UUID REFERENCES business_accounts(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'INACTIVE', 'CLOSED'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_places_slug UNIQUE (slug)
);

CREATE INDEX idx_places_coordinates ON places USING GIST (coordinates);
CREATE INDEX idx_places_name_trgm ON places USING GIN (name gin_trgm_ops);
CREATE INDEX idx_places_category ON places (category);
CREATE INDEX idx_places_city_state ON places (city, state);
CREATE INDEX idx_places_business ON places (claimed_by_business_id);

-- ------------------------------------------------------------------------------
-- 6. REFERÊNCIAS EXTERNAS DE LOCAIS (PLACE EXTERNAL REFERENCES - GOOGLE/OUTROS)
-- ------------------------------------------------------------------------------
CREATE TABLE place_external_references (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL, -- 'GOOGLE', 'OPEN_STREET_MAP'
    external_id VARCHAR(255) NOT NULL,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_place_ext_ref UNIQUE (provider, external_id)
);

CREATE INDEX idx_place_ext_ref_place ON place_external_references (place_id);
CREATE INDEX idx_place_ext_ref_lookup ON place_external_references (provider, external_id);

-- ------------------------------------------------------------------------------
-- 7. PRODUTOS GLOBAIS E IDENTIFICADORES ESTRUTURADOS
-- ------------------------------------------------------------------------------
CREATE TABLE products (
    id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    brand VARCHAR(128),
    model VARCHAR(128),
    description TEXT,
    category VARCHAR(64),
    image_url TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
CREATE INDEX idx_products_brand ON products (brand);
CREATE INDEX idx_products_category ON products (category);

CREATE TABLE product_identifiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    identifier_type VARCHAR(32) NOT NULL, -- 'EAN', 'UPC', 'GTIN', 'ISBN'
    identifier_value VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_identifier UNIQUE (identifier_type, identifier_value)
);

CREATE INDEX idx_product_identifiers_product ON product_identifiers (product_id);
CREATE INDEX idx_product_identifiers_value ON product_identifiers (identifier_value);

-- ------------------------------------------------------------------------------
-- 8. PRESENÇA DE PRODUTO EM LOCAL (PRODUCT PRESENCE)
-- ------------------------------------------------------------------------------
CREATE TABLE product_presences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    first_discovered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reported_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'UNCONFIRMED', -- 'UNCONFIRMED', 'VERIFIED'
    status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE', -- 'AVAILABLE', 'OUT_OF_STOCK'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_place UNIQUE (product_id, place_id)
);

CREATE INDEX idx_product_presence_place ON product_presences (place_id);
CREATE INDEX idx_product_presence_product ON product_presences (product_id);

-- ------------------------------------------------------------------------------
-- 9. SERVIÇOS ASSOCIADOS A LOCAIS
-- ------------------------------------------------------------------------------
CREATE TABLE services (
    id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL, -- 'ATENDIMENTO', 'DELIVERY', 'DRIVE_THRU', 'ESTACIONAMENTO', 'WIFI'
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_services_place ON services (place_id);

-- ------------------------------------------------------------------------------
-- 10. EVENTOS TEMPORAIS EM LOCAIS
-- ------------------------------------------------------------------------------
CREATE TABLE events (
    id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(64) NOT NULL,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED', -- 'SCHEDULED', 'HAPPENING_NOW', 'FINISHED', 'CANCELLED'
    images TEXT[],
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_event_dates CHECK (end_at >= start_at)
);

CREATE INDEX idx_events_place ON events (place_id);
CREATE INDEX idx_events_dates ON events (start_at, end_at);
CREATE INDEX idx_events_status ON events (status);

-- ------------------------------------------------------------------------------
-- 11. PUBLICAÇÕES DE AVALIAÇÃO (REVIEWS) E ALVOS MULTI-TARGET (ADR-006 / ADR-009)
-- ------------------------------------------------------------------------------
CREATE TABLE reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    context_place_id UUID REFERENCES places(id) ON DELETE SET NULL,
    experience_text TEXT,
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified_on_site BOOLEAN NOT NULL DEFAULT FALSE,
    user_coordinates GEOGRAPHY(Point, 4326),
    location_accuracy_meters NUMERIC(6, 2),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'UNDER_REVIEW', 'REMOVED'
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC', -- 'PUBLIC', 'PRIVATE', 'FOLLOWERS'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reviews_user ON reviews (user_id);
CREATE INDEX idx_reviews_context_place ON reviews (context_place_id);
CREATE INDEX idx_reviews_status ON reviews (status);
CREATE INDEX idx_reviews_created_at ON reviews (created_at DESC);
CREATE INDEX idx_reviews_coordinates ON reviews USING GIST (user_coordinates);

CREATE TABLE review_targets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    target_id UUID NOT NULL REFERENCES rateable_targets(id) ON DELETE RESTRICT,
    rating NUMERIC(2, 1) NOT NULL CHECK (rating >= 1.0 AND rating <= 5.0),
    specific_comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_target UNIQUE (review_id, target_id)
);

CREATE INDEX idx_review_targets_review ON review_targets (review_id);
CREATE INDEX idx_review_targets_target ON review_targets (target_id);

-- ------------------------------------------------------------------------------
-- 12. TABELA DE ESTATÍSTICAS E MÉDIAS DERIVADAS (SEÇÃO 37)
-- ------------------------------------------------------------------------------
CREATE TABLE rateable_target_stats (
    target_id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
    average_rating NUMERIC(3, 2) NOT NULL DEFAULT 0.00 CHECK (average_rating >= 0.00 AND average_rating <= 5.00),
    reviews_count INT NOT NULL DEFAULT 0 CHECK (reviews_count >= 0),
    last_calculated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ------------------------------------------------------------------------------
-- 13. CHECK-INS VERIFICADOS (SEÇÃO 19 / ADR-005)
-- ------------------------------------------------------------------------------
CREATE TABLE check_ins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    coordinates GEOGRAPHY(Point, 4326) NOT NULL,
    distance_to_centroid_meters NUMERIC(7, 2) NOT NULL CHECK (distance_to_centroid_meters >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'VERIFIED', -- 'PENDING', 'VERIFIED', 'REJECTED'
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_checkin_review UNIQUE (review_id)
);

CREATE INDEX idx_checkins_user ON check_ins (user_id);
CREATE INDEX idx_checkins_place ON check_ins (place_id);
CREATE INDEX idx_checkins_coordinates ON check_ins USING GIST (coordinates);

-- ------------------------------------------------------------------------------
-- 14. REAÇÕES A AVALIAÇÕES (HELPFUL / OUTRAS)
-- ------------------------------------------------------------------------------
CREATE TABLE review_reactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction_type VARCHAR(32) NOT NULL DEFAULT 'HELPFUL', -- 'HELPFUL'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_user_reaction UNIQUE (review_id, user_id, reaction_type)
);

CREATE INDEX idx_review_reactions_review ON review_reactions (review_id);
CREATE INDEX idx_review_reactions_user ON review_reactions (user_id);

-- ------------------------------------------------------------------------------
-- 15. DISCUSSÕES / RESPOSTAS A AVALIAÇÕES (SEÇÃO 22)
-- ------------------------------------------------------------------------------
CREATE TABLE review_discussions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES review_discussions(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    is_from_owner BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_discussions_review ON review_discussions (review_id);
CREATE INDEX idx_review_discussions_parent ON review_discussions (parent_id);

-- ------------------------------------------------------------------------------
-- 16. TAXONOMIAS E TAGS DE CONTEXTO (SEÇÃO 23)
-- ------------------------------------------------------------------------------
CREATE TABLE tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tags_code UNIQUE (code)
);

CREATE TABLE review_tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL DEFAULT 'USER', -- 'USER', 'RULE', 'AI', 'MODERATOR'
    confidence NUMERIC(3, 2) NOT NULL DEFAULT 1.00 CHECK (confidence >= 0.00 AND confidence <= 1.00),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_tag UNIQUE (review_id, tag_id)
);

CREATE INDEX idx_review_tags_review ON review_tags (review_id);
CREATE INDEX idx_review_tags_tag ON review_tags (tag_id);

-- Carga inicial de tags essenciais
INSERT INTO tags (id, code, display_name, category) VALUES
    (gen_random_uuid(), 'ATENDIMENTO', 'Atendimento', 'EXPERIENCIA'),
    (gen_random_uuid(), 'ENTREGA', 'Entrega e Pontualidade', 'EXPERIENCIA'),
    (gen_random_uuid(), 'QUALIDADE', 'Qualidade do Produto', 'EXPERIENCIA'),
    (gen_random_uuid(), 'PRECO', 'Preço Justo', 'EXPERIENCIA'),
    (gen_random_uuid(), 'AMBIENTE', 'Ambiente e Decoração', 'EXPERIENCIA'),
    (gen_random_uuid(), 'LIMPEZA', 'Higiene e Limpeza', 'EXPERIENCIA'),
    (gen_random_uuid(), 'SUPORTE', 'Suporte pós-venda', 'EXPERIENCIA'),
    (gen_random_uuid(), 'TEMPO_DE_ESPERA', 'Tempo de Espera / Fila', 'EXPERIENCIA'),
    (gen_random_uuid(), 'CUSTO_BENEFICIO', 'Custo-Benefício', 'EXPERIENCIA'),
    (gen_random_uuid(), 'DURABILIDADE', 'Durabilidade', 'EXPERIENCIA')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------------------
-- 17. INTERESSES DO USUÁRIO (SEÇÃO 24)
-- ------------------------------------------------------------------------------
CREATE TABLE user_interests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    interest_name VARCHAR(128) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_interest UNIQUE (user_id, category_code)
);

CREATE INDEX idx_user_interests_user ON user_interests (user_id);

-- ------------------------------------------------------------------------------
-- 18. ATIVIDADES DO USUÁRIO (SEÇÃO 25 - RECOMENDAÇÃO FUTURA)
-- ------------------------------------------------------------------------------
CREATE TABLE user_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_type VARCHAR(64) NOT NULL, -- 'SEARCH', 'VIEW_PLACE', 'VIEW_PRODUCT', 'CHECK_IN', 'SAVE', etc.
    target_type VARCHAR(32),
    target_id UUID,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_activities_user ON user_activities (user_id);
CREATE INDEX idx_user_activities_created ON user_activities (created_at DESC);

-- ------------------------------------------------------------------------------
-- 19. ITENS SALVOS / FAVORITOS POLIMÓRFICOS (SEÇÃO 26)
-- ------------------------------------------------------------------------------
CREATE TABLE saved_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_id UUID NOT NULL,
    item_type VARCHAR(32) NOT NULL, -- 'REVIEW', 'PLACE', 'PRODUCT', 'SERVICE', 'EVENT'
    folder_name VARCHAR(128) NOT NULL DEFAULT 'Geral',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_saved_target UNIQUE (user_id, target_id, item_type)
);

CREATE INDEX idx_saved_items_user ON saved_items (user_id);

-- ------------------------------------------------------------------------------
-- 20. SEGUIDORES E CONEXÕES SOCIAIS (SEÇÃO 27)
-- ------------------------------------------------------------------------------
CREATE TABLE user_follows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followed_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_follow UNIQUE (follower_user_id, followed_user_id),
    CONSTRAINT chk_no_self_follow CHECK (follower_user_id <> followed_user_id)
);

CREATE INDEX idx_user_follows_follower ON user_follows (follower_user_id);
CREATE INDEX idx_user_follows_followed ON user_follows (followed_user_id);

-- ------------------------------------------------------------------------------
-- 21. NOTIFICAÇÕES INTERNAS (SEÇÃO 28)
-- ------------------------------------------------------------------------------
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(64) NOT NULL, -- 'NEW_REACTION', 'NEW_REPLY', 'PROXIMITY_EVENT'
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    action_url TEXT,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id, read_at);

-- ------------------------------------------------------------------------------
-- 22. PROMOÇÕES COMERCIAIS (SEÇÃO 30)
-- ------------------------------------------------------------------------------
CREATE TABLE promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    business_account_id UUID REFERENCES business_accounts(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    discount_code VARCHAR(64),
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_promotion_dates CHECK (end_at >= start_at)
);

CREATE INDEX idx_promotions_place ON promotions (place_id);
CREATE INDEX idx_promotions_active ON promotions (is_active, start_at, end_at);
