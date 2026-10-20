-- ==============================================================================
-- REWIT - PROVISIONAMENTO DA ROLE DE RUNTIME (rewit_app)
-- Fonte única do provisionamento: usada pelo init do container (init/02-create-app-role.sh)
-- e diretamente por administradores para provisionar ou rotacionar a senha.
--
-- Uso administrativo (a senha é pedida duas vezes, sem eco, pelo próprio psql):
--   psql -U rewit_user -d rewit_db -v app_user=rewit_app -f provision-runtime-role.sql
-- Uso automatizado: prefira init/02-create-app-role.sh, que valida APP_DB_PASSWORD e a envia
-- pela entrada padrão do psql.
--
-- Segurança (SEC-2 / SEC-3):
--   * A senha NUNCA entra em texto SQL: \password lê a senha, calcula o verificador SCRAM no
--     cliente (libpq) e envia ao servidor apenas o hash. Nada a interpolar, escapar ou registrar.
--   * O nome da role chega como variável psql e é repassado como valor literal (:'app_user') para
--     uma configuração de sessão; o bloco PL/pgSQL o valida e só o usa via format('%I').
--   * Atributos privilegiados são removidos de forma explícita e a role não pode ser owner.
--   * Privilégios de tabela são responsabilidade exclusiva das migrations (V36); este script não
--     os concede, para não reabrir permissões retiradas (ex.: colunas de auditoria e decisão).
-- ==============================================================================

\set ON_ERROR_STOP on
\if :{?app_user}
\else
\set app_user rewit_app
\endif

SET rewit.provision_app_user = :'app_user';

DO $$
DECLARE
    v_user TEXT := current_setting('rewit.provision_app_user');
    v_grant RECORD;
BEGIN
    IF v_user !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Nome de role de runtime inválido: use apenas [a-z_][a-z0-9_]* (até 63 caracteres)';
    END IF;
    IF v_user = current_user OR v_user = 'postgres' OR v_user LIKE 'pg\_%' THEN
        RAISE EXCEPTION 'A role de runtime não pode ser a role administrativa nem uma role reservada: %', v_user;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_user) THEN
        EXECUTE format('CREATE ROLE %I', v_user);
    END IF;

    EXECUTE format(
        'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
        v_user);

    -- Nenhuma membership: a role não herda nem assume privilégios de outras roles
    FOR v_grant IN
        SELECT g.rolname AS granted_role
        FROM pg_auth_members am
        JOIN pg_roles g ON g.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE m.rolname = v_user
    LOOP
        EXECUTE format('REVOKE %I FROM %I', v_grant.granted_role, v_user);
    END LOOP;

    -- A role de runtime nunca é owner de objetos (em nenhum database do cluster)
    IF EXISTS (
        SELECT 1
        FROM pg_shdepend d
        JOIN pg_roles r ON r.oid = d.refobjid
        WHERE d.refclassid = 'pg_authid'::regclass
          AND d.deptype = 'o'
          AND r.rolname = v_user
    ) THEN
        RAISE EXCEPTION 'A role de runtime % é owner de objetos; transfira a propriedade antes de provisionar', v_user;
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), v_user);
END
$$;

\password :"app_user"

DO $$
DECLARE
    v_user TEXT := current_setting('rewit.provision_app_user');
    v_role RECORD;
BEGIN
    SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolcanlogin
    INTO v_role
    FROM pg_roles WHERE rolname = v_user;

    IF v_role.rolsuper OR v_role.rolcreaterole OR v_role.rolcreatedb
       OR v_role.rolreplication OR v_role.rolbypassrls OR NOT v_role.rolcanlogin THEN
        RAISE EXCEPTION 'Role de runtime % ficou com atributos inesperados', v_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles m ON m.oid = am.member WHERE m.rolname = v_user) THEN
        RAISE EXCEPTION 'Role de runtime % possui memberships', v_user;
    END IF;
    RAISE NOTICE 'Role de runtime % provisionada com privilégios mínimos', v_user;
END
$$;

RESET rewit.provision_app_user;
