-- ==============================================================================
-- REWIT DATABASE MIGRATION - V40
-- ==============================================================================
-- Questionário inicial de interesses e catálogo controlado de interesses
-- Tabelas: user_interest_profiles (nova), user_interests (V1, CHECK novo)
-- ==============================================================================
-- 1. user_interest_profiles guarda a situação do questionário de cada conta. Linha
--    ausente = questionário não iniciado; onboarding_completed_at NULL = respostas
--    parciais gravadas (em andamento); preenchido = concluído, data da primeira
--    confirmação. A linha também é a trava por usuário das gravações de interesses.
--
-- 2. user_interests (V1) continua guardando um interesse por linha. O catálogo passa
--    a ser controlado (enum InterestCategory no backend) e espelhado no CHECK
--    chk_user_interest_category_code. Nenhum código da aplicação gravava a tabela
--    antes desta versão; um código fora do catálogo faz a migração falhar, em vez
--    de ser apagado ou ignorado.
--
-- 3. Contas existentes mantêm o comportamento anterior: recebem o questionário como
--    concluído, sem interesses, e não são levadas a ele. Podem cadastrar interesses
--    pelo perfil. Contas criadas depois desta versão começam sem linha.
--
-- Privilégios: tabela criada depois da V37, cujos privilégios padrão não concedem
-- escrita. O runtime recebe INSERT e UPDATE; não recebe DELETE (o purge de conta
-- remove os interesses, e a linha da situação, só com datas, permanece como a de
-- users). A FK para users em cascata só é acionável por quem pode apagar users,
-- que não é o runtime.

CREATE TABLE user_interest_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    onboarding_completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE ON user_interest_profiles TO rewit_app;

ALTER TABLE user_interests
    ADD CONSTRAINT chk_user_interest_category_code CHECK (category_code IN (
        'RESTAURANT',
        'FAST_FOOD',
        'CAFE',
        'BAKERY',
        'BAR',
        'ICE_CREAM',
        'SUPERMARKET',
        'FASHION',
        'BOOKS',
        'TECHNOLOGY',
        'SHOPPING_MALL',
        'BEAUTY',
        'PET',
        'AUTOMOTIVE',
        'PARK',
        'FITNESS',
        'CULTURE',
        'EVENTS',
        'GAMES',
        'TRAVEL',
        'HEALTH'
    ));

INSERT INTO user_interest_profiles (user_id, onboarding_completed_at, created_at, updated_at)
SELECT id, NOW(), NOW(), NOW()
FROM users;

-- A verificação da V37 confirma a política do runtime com a tabela nova
DO $$
BEGIN
    PERFORM rewit_assert_runtime_privileges();
END
$$;
