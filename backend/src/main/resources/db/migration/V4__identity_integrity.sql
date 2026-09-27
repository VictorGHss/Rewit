-- ==============================================================================
-- REWIT - V4__identity_integrity.sql
-- Migração incremental Flyway: Integridade de Identidade, E-mail e Autenticação (Step 3)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. UNICIDADE DE E-MAIL CASE-INSENSITIVE (SEÇÃO 8)
-- Decisão Arquitetural sobre Soft-Delete:
-- O e-mail permanece estritamente reservado no MVP mesmo após soft-delete (deleted_at IS NOT NULL),
-- impedindo sequestro de contas anteriores, impersonação ou re-cadastro conflitante
-- com dados históricos não expurgados.
-- ------------------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_email;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower 
    ON users (LOWER(email));

-- ------------------------------------------------------------------------------
-- 2. UNICIDADE DE IDENTIDADE FEDERADA (AUTH_PROVIDER + PROVIDER_USER_ID - SEÇÃO 9)
-- Substitui o índice não-único herdado da V1 por um índice único parcial.
-- Contas locais (auth_provider = 'LOCAL') possuem provider_user_id IS NULL e não
-- colidem entre si. Contas externas (GOOGLE, APPLE) garantem unicidade estrita do ID remoto.
-- ------------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_users_provider;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_provider_user_id 
    ON users (auth_provider, provider_user_id) 
    WHERE provider_user_id IS NOT NULL;

-- ------------------------------------------------------------------------------
-- 3. UNICIDADE DE HANDLE DO PERFIL CASE-INSENSITIVE (SEÇÃO 4)
-- O @handle é o identificador público único na rede social.
-- A normalização garante que @Usuario e @usuario colidam como o mesmo handle.
-- O índice trigram GIN (idx_profiles_handle_trgm) existente é preservado para buscas textuais.
-- ------------------------------------------------------------------------------
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS uq_profiles_handle;

CREATE UNIQUE INDEX IF NOT EXISTS uq_profiles_handle_lower 
    ON profiles (LOWER(handle));
