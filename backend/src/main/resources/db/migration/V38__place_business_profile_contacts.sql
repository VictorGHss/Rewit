-- ==============================================================================
-- REWIT DATABASE MIGRATION - V38
-- ==============================================================================
-- Perfil comercial: contatos públicos do estabelecimento reivindicado
-- Tabela: places
-- ==============================================================================
-- Colunas opcionais gravadas somente pelo representante comercial aprovado (PUT business-profile); a descrição
-- reutiliza places.description (V1). Locais existentes continuam sem contatos (NULL): sem DEFAULT nem backfill,
-- o ADD COLUMN não reescreve a tabela. As CHECKs repetem o mínimo da validação do domínio, para que escrita direta
-- não grave valor vazio, telefone com caracteres fora do formato ou link com esquema diferente de http/https.
--
-- Privilégios: rewit_app já tem UPDATE em places no nível da tabela (V36), que alcança colunas novas; nenhum
-- GRANT é necessário e nenhum privilégio muda.

ALTER TABLE places
    ADD COLUMN phone VARCHAR(32),
    ADD COLUMN website_url VARCHAR(500),
    ADD COLUMN instagram_url VARCHAR(500),
    ADD COLUMN facebook_url VARCHAR(500),
    ADD CONSTRAINT chk_places_phone_format
        CHECK (phone IS NULL OR phone ~ '^\+?[0-9(][0-9 ().-]*[0-9]$'),
    ADD CONSTRAINT chk_places_website_url_scheme
        CHECK (website_url IS NULL OR website_url ~* '^https?://[^[:space:]]+$'),
    ADD CONSTRAINT chk_places_instagram_url_scheme
        CHECK (instagram_url IS NULL OR instagram_url ~* '^https?://[^[:space:]]+$'),
    ADD CONSTRAINT chk_places_facebook_url_scheme
        CHECK (facebook_url IS NULL OR facebook_url ~* '^https?://[^[:space:]]+$');

-- Nenhuma mudança de privilégio; a verificação da V37 confirma que a política do runtime continua intacta
DO $$
BEGIN
    PERFORM rewit_assert_runtime_privileges();
END
$$;
