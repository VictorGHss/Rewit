-- ==============================================================================
-- REWIT - V7__review_listing_indexes.sql
-- Índices para otimização de listagem, paginação e descoberta de Reviews (Step 14.0)
-- ==============================================================================

-- 1. Otimização de consulta paginada por RateableTarget ordenada por rating
-- Cobre o filtro por target_id com ordenação por rating DESC e amarração ao review_id
CREATE INDEX IF NOT EXISTS idx_review_targets_target_rating ON review_targets (target_id, rating DESC, review_id);

-- 2. Otimização de consulta paginada das avaliações do usuário autenticado (/api/v1/me/reviews)
-- Cobre o filtro por user_id com ordenação determinística por created_at DESC e desempate por id
CREATE INDEX IF NOT EXISTS idx_reviews_user_created_at ON reviews (user_id, created_at DESC, id ASC);

-- 3. Otimização de filtros frequentes de visibilidade e moderação (status ACTIVE e visibilidade)
CREATE INDEX IF NOT EXISTS idx_reviews_status_visibility ON reviews (status, visibility);
