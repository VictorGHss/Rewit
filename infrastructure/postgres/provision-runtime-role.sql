-- ==============================================================================
-- REWIT - PROVISIONAMENTO DAS ROLES DE RUNTIME (rewit_app, rewit_media e rewit_mod)
-- Fonte única do provisionamento: usada pelo init do container (init/02-create-app-role.sh)
-- e diretamente por administradores (SUPERUSER local ou conta administrativa gerenciada
-- não-superuser com CREATEROLE) para provisionar ou rotacionar senhas de forma idempotente.
--
-- Uso administrativo (a senha é pedida duas vezes, sem eco, pelo próprio psql):
--   psql -U <admin_user> -d <db> -v app_user=rewit_app -f provision-runtime-role.sql
--   psql -U <admin_user> -d <db> -v app_user=rewit_media -f provision-runtime-role.sql
--   psql -U <admin_user> -d <db> -v app_user=rewit_mod -f provision-runtime-role.sql
-- Uso automatizado: prefira init/02-create-app-role.sh, que valida APP_DB_PASSWORD,
-- MEDIA_DB_PASSWORD e MOD_DB_PASSWORD e as envia pela entrada padrão do psql.
--
-- Segurança (SEC-2 / SEC-3 / S3-A13-F2 / SEC-F2-03 / F2-DEPLOY-03.5.1):
--   * A senha NUNCA entra em texto SQL: \password lê a senha, calcula o verificador SCRAM no
--     cliente (libpq) e envia ao servidor apenas o hash. Nada a interpolar, escapar ou registrar.
--   * O nome da role chega como variável psql e é repassado como valor literal (:'app_user') para
--     uma configuração de sessão; o bloco PL/pgSQL o valida e só o usa via format('%I').
--   * Compatível com SUPERUSER (local) e com administrador gerenciado não-superuser com CREATEROLE:
--     não tenta executar cláusulas exclusivas de SUPERUSER (NOSUPERUSER, NOREPLICATION, NOBYPASSRLS)
--     quando current_user não é superuser, mas comprova de forma fail-closed em pg_catalog.pg_roles
--     que todos os atributos privilegiados permanecem falsos.
--   * Verifica zero memberships próprias (pg_auth_members.member = runtime_role), preservando
--     a membership administrativa inversa de PG 16+ (roleid = runtime_role, member = admin).
--   * Verifica ausência de ownership de database, schemas ou objetos, presença de CONNECT e
--     permissão estritamente somente leitura (SELECT) em public.spatial_ref_sys.
--   * Privilégios de tabelas de domínio são responsabilidade exclusiva das migrations (V36–V46).
--   * Semântica de rotação (não atômica entre roles): este script atua sobre uma única role por
--     invocação e os blocos SQL + \password executam em passos distintos (sem transação global
--     entre as três roles). Se uma rotação sequencial falhar na 2ª ou 3ª role, as roles anteriores
--     podem já estar com a nova senha; não trate código de saída != 0 como sucesso global — corrija
--     a causa e reexecute o procedimento completo (idempotente), preservando senhas independentes.
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
    v_caller_is_super BOOLEAN;
    v_caller_can_createrole BOOLEAN;
    v_caller_can_createdb BOOLEAN;
    v_existing RECORD;
    v_privileged_attrs TEXT;
    v_alter_clauses TEXT;
    v_grant RECORD;
    v_spatial_writes TEXT;
BEGIN
    IF v_user !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Nome de role de runtime inválido: use apenas [a-z_][a-z0-9_]* (até 63 caracteres)';
    END IF;
    IF v_user = current_user OR v_user = 'postgres' OR v_user LIKE 'pg\_%' THEN
        RAISE EXCEPTION 'A role de runtime não pode ser a role administrativa nem uma role reservada: %', v_user;
    END IF;
    -- As migrations (V36/V45) concedem privilégios de runtime somente a rewit_app (público),
    -- rewit_media (ciclo de vida de mídias) e rewit_mod (moderação administrativa):
    -- outro nome produziria um login sem privilégios (P3-2 / SEC-F2-03).
    IF v_user NOT IN ('rewit_app', 'rewit_media', 'rewit_mod') THEN
        RAISE EXCEPTION 'APP_DB_USER/MEDIA_DB_USER/MOD_DB_USER/app_user=% não é suportado: as roles de runtime são fixas (rewit_app, rewit_media e rewit_mod), pois as migrations concedem privilégios somente a elas', v_user;
    END IF;

    SELECT r.rolsuper, r.rolcreaterole, r.rolcreatedb
    INTO v_caller_is_super, v_caller_can_createrole, v_caller_can_createdb
    FROM pg_catalog.pg_roles r
    WHERE r.rolname = current_user;

    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = v_user) THEN
        IF NOT COALESCE(v_caller_is_super, FALSE) AND NOT COALESCE(v_caller_can_createrole, FALSE) THEN
            RAISE EXCEPTION 'A conta administrativa % precisa de SUPERUSER ou CREATEROLE para criar a role de runtime %',
                current_user, v_user;
        END IF;

        -- Na criação de role comum no PostgreSQL, rolsuper, rolcreatedb, rolcreaterole, rolreplication
        -- e rolbypassrls já nascem FALSE. Aplicamos apenas cláusulas autorizadas ao ator atual
        -- (PG 16+ exige SUPERUSER para NOSUPERUSER/NOREPLICATION/NOBYPASSRLS e CREATEDB para NOCREATEDB).
        IF COALESCE(v_caller_is_super, FALSE) THEN
            EXECUTE format(
                'CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
                v_user);
        ELSE
            v_alter_clauses := 'LOGIN NOINHERIT NOCREATEROLE';
            IF COALESCE(v_caller_can_createdb, FALSE) THEN
                v_alter_clauses := v_alter_clauses || ' NOCREATEDB';
            END IF;
            EXECUTE format('CREATE ROLE %I WITH %s', v_user, v_alter_clauses);
        END IF;
    ELSE
        IF COALESCE(v_caller_is_super, FALSE) THEN
            EXECUTE format(
                'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
                v_user);
        ELSE
            -- Administrador não-superuser: nunca tenta alterar atributos para os quais não possui privilégio
            -- (PG 16+ exige SUPERUSER para NOSUPERUSER/NOREPLICATION/NOBYPASSRLS e CREATEDB para NOCREATEDB),
            -- mas falha de forma fail-closed caso a role preexistente possua qualquer atributo proibido ativo
            -- que a conta atual não consiga remover.
            SELECT r.rolsuper, r.rolreplication, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole
            INTO v_existing
            FROM pg_catalog.pg_roles r
            WHERE r.rolname = v_user;

            SELECT string_agg(attr, ', ')
            INTO v_privileged_attrs
            FROM (VALUES
                ('SUPERUSER', v_existing.rolsuper),
                ('REPLICATION', v_existing.rolreplication),
                ('BYPASSRLS', v_existing.rolbypassrls),
                ('CREATEDB', v_existing.rolcreatedb AND NOT COALESCE(v_caller_can_createdb, FALSE)),
                ('CREATEROLE', v_existing.rolcreaterole AND NOT COALESCE(v_caller_can_createrole, FALSE))
            ) AS a(attr, blocked)
            WHERE a.blocked;

            IF v_privileged_attrs IS NOT NULL THEN
                RAISE EXCEPTION 'A role de runtime preexistente % possui atributo(s) privilegiado(s) (%) que a conta não-superuser % não pode remover. Corrija ou recrie a role com uma conta administrativa privilegiada antes de prosseguir.',
                    v_user, v_privileged_attrs, current_user;
            END IF;

            v_alter_clauses := 'LOGIN NOINHERIT';
            IF COALESCE(v_caller_can_createrole, FALSE) THEN
                v_alter_clauses := v_alter_clauses || ' NOCREATEROLE';
            END IF;
            IF COALESCE(v_caller_can_createdb, FALSE) THEN
                v_alter_clauses := v_alter_clauses || ' NOCREATEDB';
            END IF;
            EXECUTE format('ALTER ROLE %I WITH %s', v_user, v_alter_clauses);
        END IF;
    END IF;

    -- Nenhuma membership própria: a role não herda nem assume privilégios de outras roles.
    -- Nota (PG 16+): memberships inversas (roleid = v_user, member = managed_admin com ADMIN OPTION)
    -- NÃO são afetadas aqui, pois apenas registram quem administra v_user.
    FOR v_grant IN
        SELECT g.rolname AS granted_role
        FROM pg_catalog.pg_auth_members am
        JOIN pg_catalog.pg_roles g ON g.oid = am.roleid
        JOIN pg_catalog.pg_roles m ON m.oid = am.member
        WHERE m.rolname = v_user
    LOOP
        EXECUTE format('REVOKE %I FROM %I', v_grant.granted_role, v_user);
    END LOOP;

    -- Garante também que nenhuma outra role de runtime seja membro desta role (impede SET ROLE cruzado)
    FOR v_grant IN
        SELECT m.rolname AS member_role
        FROM pg_catalog.pg_auth_members am
        JOIN pg_catalog.pg_roles g ON g.oid = am.roleid
        JOIN pg_catalog.pg_roles m ON m.oid = am.member
        WHERE g.rolname = v_user
          AND m.rolname IN ('rewit_app', 'rewit_media', 'rewit_mod')
    LOOP
        EXECUTE format('REVOKE %I FROM %I', v_user, v_grant.member_role);
    END LOOP;

    -- A role de runtime nunca é owner de database, schemas ou objetos (em nenhum database do cluster)
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_database d
        JOIN pg_catalog.pg_roles r ON r.oid = d.datdba
        WHERE d.datname = current_database()
          AND r.rolname = v_user
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace n
        JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner
        WHERE r.rolname = v_user
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_shdepend d
        JOIN pg_catalog.pg_roles r ON r.oid = d.refobjid
        WHERE d.refclassid = 'pg_authid'::regclass
          AND d.deptype = 'o'
          AND r.rolname = v_user
    ) THEN
        RAISE EXCEPTION 'A role de runtime % é owner de database, schema ou objetos; transfira a propriedade antes de provisionar', v_user;
    END IF;

    -- CONNECT no database atual (fail-closed caso a conta não consiga conceder e a role ainda não tenha CONNECT)
    BEGIN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), v_user);
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
    IF NOT pg_catalog.has_database_privilege(v_user, current_database(), 'CONNECT') THEN
        RAISE EXCEPTION 'A role de runtime % não possui CONNECT no database % e a conta % não tem permissão para conceder. Execute GRANT CONNECT ON DATABASE % TO % com a conta proprietária do banco.',
            v_user, current_database(), current_user, current_database(), v_user;
    END IF;

    -- PostGIS public.spatial_ref_sys: exige SELECT e proíbe qualquer escrita/DDL para a role runtime
    IF to_regclass('public.spatial_ref_sys') IS NULL THEN
        RAISE EXCEPTION 'Tabela PostGIS public.spatial_ref_sys não encontrada. Pré-instale as cinco extensões obrigatórias (uuid-ossp, postgis, pg_trgm, pgcrypto, unaccent) no schema public antes de provisionar as roles de runtime.';
    END IF;

    IF NOT pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, 'SELECT') THEN
        BEGIN
            EXECUTE format('GRANT SELECT ON public.spatial_ref_sys TO %I', v_user);
        EXCEPTION WHEN insufficient_privilege THEN
            NULL;
        END;
        IF NOT pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, 'SELECT') THEN
            RAISE EXCEPTION 'A role de runtime % não possui SELECT em public.spatial_ref_sys e a conta % não tem permissão para conceder (PostGIS pertence a outro owner). Execute GRANT SELECT ON public.spatial_ref_sys TO % com a conta administrativa/proprietária da extensão PostGIS.',
                v_user, current_user, v_user;
        END IF;
    END IF;

    SELECT string_agg(p.priv, ', ' ORDER BY p.priv)
    INTO v_spatial_writes
    FROM (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(priv)
    WHERE pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, p.priv);

    IF v_spatial_writes IS NOT NULL THEN
        BEGIN
            EXECUTE format(
                'REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON public.spatial_ref_sys FROM %I',
                v_user);
        EXCEPTION WHEN insufficient_privilege THEN
            NULL;
        END;

        SELECT string_agg(p.priv, ', ' ORDER BY p.priv)
        INTO v_spatial_writes
        FROM (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(priv)
        WHERE pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, p.priv);

        IF v_spatial_writes IS NOT NULL THEN
            RAISE EXCEPTION 'A role de runtime % mantém privilégio(s) indevido(s) (%) em public.spatial_ref_sys que a conta % não conseguiu revogar. Revogue esses privilégios (inclusive de PUBLIC, se aplicável) com a conta proprietária da extensão PostGIS.',
                v_user, v_spatial_writes, current_user;
        END IF;
    END IF;
END
$$;

\password :"app_user"

DO $$
DECLARE
    v_user TEXT := current_setting('rewit.provision_app_user');
    v_role RECORD;
    v_bad_attrs TEXT;
    v_memberships TEXT;
    v_spatial_writes TEXT;
BEGIN
    SELECT r.oid, r.rolsuper, r.rolcreaterole, r.rolcreatedb, r.rolreplication,
           r.rolbypassrls, r.rolcanlogin, r.rolinherit
    INTO v_role
    FROM pg_catalog.pg_roles r
    WHERE r.rolname = v_user;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Role de runtime % ausente após provisionamento', v_user;
    END IF;

    SELECT string_agg(attr, ', ')
    INTO v_bad_attrs
    FROM (VALUES
        ('SUPERUSER', v_role.rolsuper),
        ('CREATEROLE', v_role.rolcreaterole),
        ('CREATEDB', v_role.rolcreatedb),
        ('REPLICATION', v_role.rolreplication),
        ('BYPASSRLS', v_role.rolbypassrls),
        ('INHERIT', v_role.rolinherit),
        ('NOLOGIN', NOT v_role.rolcanlogin)
    ) AS a(attr, failed)
    WHERE a.failed;

    IF v_bad_attrs IS NOT NULL THEN
        RAISE EXCEPTION 'Role de runtime % ficou com atributo(s) incompatível(is): %', v_user, v_bad_attrs;
    END IF;

    -- Verifica exclusivamente memberships próprias (member = runtime_role_oid);
    -- ADMIN OPTION inverso (roleid = runtime_role_oid, member = admin) é permitido e esperado no PG 16+.
    SELECT string_agg(g.rolname, ', ' ORDER BY g.rolname)
    INTO v_memberships
    FROM pg_catalog.pg_auth_members am
    JOIN pg_catalog.pg_roles g ON g.oid = am.roleid
    WHERE am.member = v_role.oid;

    IF v_memberships IS NOT NULL THEN
        RAISE EXCEPTION 'Role de runtime % possui membership(s) proibida(s): %', v_user, v_memberships;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_database d
        WHERE d.datname = current_database() AND d.datdba = v_role.oid
    ) OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_namespace n
        WHERE n.nspowner = v_role.oid
    ) OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_shdepend d
        WHERE d.refclassid = 'pg_authid'::regclass
          AND d.deptype = 'o'
          AND d.refobjid = v_role.oid
    ) THEN
        RAISE EXCEPTION 'Role de runtime % possui ownership indevido de database, schema ou objeto', v_user;
    END IF;

    IF NOT pg_catalog.has_database_privilege(v_user, current_database(), 'CONNECT') THEN
        RAISE EXCEPTION 'Role de runtime % permanece sem CONNECT no database %', v_user, current_database();
    END IF;

    IF to_regclass('public.spatial_ref_sys') IS NULL
       OR NOT pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, 'SELECT') THEN
        RAISE EXCEPTION 'Role de runtime % permanece sem SELECT em public.spatial_ref_sys', v_user;
    END IF;

    SELECT string_agg(p.priv, ', ' ORDER BY p.priv)
    INTO v_spatial_writes
    FROM (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(priv)
    WHERE pg_catalog.has_table_privilege(v_user, 'public.spatial_ref_sys'::regclass, p.priv);

    IF v_spatial_writes IS NOT NULL THEN
        RAISE EXCEPTION 'Role de runtime % permanece com privilégio(s) indevido(s) (%) em public.spatial_ref_sys',
            v_user, v_spatial_writes;
    END IF;

    RAISE NOTICE 'Role de runtime % provisionada com privilégios mínimos verificados', v_user;
END
$$;

RESET rewit.provision_app_user;
