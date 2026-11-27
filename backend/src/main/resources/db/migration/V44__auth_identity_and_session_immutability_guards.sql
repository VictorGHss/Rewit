-- ==============================================================================
-- REWIT DATABASE MIGRATION - V44
-- ==============================================================================
-- Hardening de Autenticação F1: Guards de Imutabilidade de Identidade e Sessão,
-- Revogação de DELETE Direto em auth_sessions e Funções Restritas de Cleanup/Purge
-- Tabelas: users, auth_sessions
-- ==============================================================================
-- 1. fn_guard_user_role_assignment: evolui a proteção de users para impedir que a
--    role de runtime (rewit_app) altere id, created_at, auth_provider ou provider_user_id
--    (salvo limpeza para NULL durante purge de conta já DELETED), bem como reativação
--    indevida de contas DELETED.
-- 2. fn_guard_auth_session_integrity e trg_guard_auth_session_integrity: protege
--    auth_sessions contra manipulação de id, user_id, token_hash, issued_at,
--    expires_at, created_at e desrevogação por rewit_app.
-- 3. Revogação de DELETE direto em public.auth_sessions para rewit_app (e PUBLIC),
--    impedindo exclusão arbitrária e ressurreição de sessões via DELETE + INSERT.
-- 4. Funções SECURITY DEFINER restritas (com search_path fixo e validação estrita)
--    para cleanup de sessões expiradas (corte limitado a LEAST(p_now, clock_timestamp()))
--    e expurgo LGPD de sessões de contas definitivamente DELETED.
-- 5. rewit_assert_runtime_privileges(): atualizado para auditar a ausência de DELETE
--    direto em auth_sessions, a presença do trigger de integridade e a governança
--    das funções SECURITY DEFINER autorizadas.
-- ==============================================================================

-- 1. EVOLUÇÃO DO GUARD DE IDENTIDADE EM USERS (S3-A13-F1)
CREATE OR REPLACE FUNCTION fn_guard_user_role_assignment()
RETURNS TRIGGER AS $$
DECLARE
    v_privileged BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_privileged
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    IF NOT COALESCE(v_privileged, FALSE) THEN
        -- 1. Atribuição de papel restrita a operações administrativas (P2-2)
        IF (TG_OP = 'INSERT' AND NEW.role IS DISTINCT FROM 'USER')
           OR (TG_OP = 'UPDATE' AND NEW.role IS DISTINCT FROM OLD.role) THEN
            RAISE EXCEPTION 'users.role: atribuição de papel é exclusiva de operação administrativa'
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF TG_OP = 'UPDATE' THEN
            -- 2. users.id é estritamente imutável
            IF NEW.id IS DISTINCT FROM OLD.id THEN
                RAISE EXCEPTION 'users.id: identificador imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 3. users.created_at é estritamente imutável
            IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'users.created_at: timestamp de criação imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 4. users.auth_provider é estritamente imutável
            IF NEW.auth_provider IS DISTINCT FROM OLD.auth_provider THEN
                RAISE EXCEPTION 'users.auth_provider: provedor de autenticação imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 5. users.provider_user_id não pode ser alterado nem vinculado pelo runtime,
            --    exceto limpeza para NULL durante purge de conta já DELETED (LGPD)
            IF NEW.provider_user_id IS DISTINCT FROM OLD.provider_user_id THEN
                IF NOT (OLD.account_status = 'DELETED' AND NEW.account_status = 'DELETED' AND NEW.provider_user_id IS NULL) THEN
                    RAISE EXCEPTION 'users.provider_user_id: identificador de provedor federado imutável'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
            END IF;

            -- 6. Conta excluída (DELETED) não pode retornar a nenhum outro estado de ciclo de vida
            IF OLD.account_status = 'DELETED' AND NEW.account_status IS DISTINCT FROM 'DELETED' THEN
                RAISE EXCEPTION 'users.account_status: conta excluída não pode retornar a outro estado'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_user_role_assignment ON users;
CREATE TRIGGER trg_guard_user_role_assignment
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION fn_guard_user_role_assignment();


-- 2. GUARD DE INTEGRIDADE E IMUTABILIDADE EM AUTH_SESSIONS
CREATE OR REPLACE FUNCTION fn_guard_auth_session_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_privileged BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_privileged
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    IF NOT COALESCE(v_privileged, FALSE) THEN
        IF TG_OP = 'UPDATE' THEN
            -- 1. id da sessão é estritamente imutável
            IF NEW.id IS DISTINCT FROM OLD.id THEN
                RAISE EXCEPTION 'auth_sessions.id: identificador de sessão imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 2. user_id é estritamente imutável (impede transplante entre contas via UPDATE)
            IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
                RAISE EXCEPTION 'auth_sessions.user_id: proprietário da sessão imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 3. token_hash é estritamente imutável
            IF NEW.token_hash IS DISTINCT FROM OLD.token_hash THEN
                RAISE EXCEPTION 'auth_sessions.token_hash: hash de token imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 4. issued_at é estritamente imutável
            IF NEW.issued_at IS DISTINCT FROM OLD.issued_at THEN
                RAISE EXCEPTION 'auth_sessions.issued_at: timestamp de emissão imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 5. expires_at é estritamente imutável (impede extensão arbitrária de expiração)
            IF NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
                RAISE EXCEPTION 'auth_sessions.expires_at: prazo de expiração imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 6. created_at é estritamente imutável
            IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'auth_sessions.created_at: timestamp de criação imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 7. Sessão revogada não pode voltar a ficar ativa
            IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
                RAISE EXCEPTION 'auth_sessions.revoked_at: sessão revogada não pode ser reativada'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_auth_session_integrity ON auth_sessions;
CREATE TRIGGER trg_guard_auth_session_integrity
    BEFORE UPDATE ON auth_sessions
    FOR EACH ROW
    EXECUTE FUNCTION fn_guard_auth_session_integrity();


-- 3. REVOGAÇÃO DE DELETE DIRETO EM AUTH_SESSIONS PARA A ROLE DE RUNTIME
DO $$
BEGIN
    REVOKE DELETE ON TABLE public.auth_sessions FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        REVOKE DELETE ON TABLE public.auth_sessions FROM rewit_app;
    END IF;
END
$$;


-- 4. FUNÇÕES SECURITY DEFINER RESTRITAS PARA CLEANUP E PURGE LGPD DE AUTH_SESSIONS
CREATE OR REPLACE FUNCTION public.rewit_purge_expired_auth_sessions(p_now TIMESTAMPTZ, p_limit INT)
RETURNS INT AS $$
DECLARE
    v_cutoff TIMESTAMPTZ;
    v_deleted INT;
BEGIN
    IF p_now IS NULL THEN
        RAISE EXCEPTION 'rewit_purge_expired_auth_sessions: p_now não pode ser nulo';
    END IF;
    IF p_limit IS NULL OR p_limit <= 0 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'rewit_purge_expired_auth_sessions: p_limit deve estar entre 1 e 10000';
    END IF;

    -- O corte efetivo nunca ultrapassa o relógio real do PostgreSQL: impede que rewit_app
    -- passe uma data futura para apagar sessões ativas ou revogadas ainda dentro do prazo.
    v_cutoff := LEAST(p_now, pg_catalog.clock_timestamp());

    DELETE FROM public.auth_sessions
    WHERE id IN (
        SELECT id
        FROM public.auth_sessions
        WHERE expires_at < v_cutoff
          AND replaced_by_session_id IS NULL
        ORDER BY expires_at, id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    );

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_purge_expired_auth_sessions(TIMESTAMPTZ, INT) FROM PUBLIC;

COMMENT ON FUNCTION public.rewit_purge_expired_auth_sessions(TIMESTAMPTZ, INT) IS
    'Remove em lotes (FOR UPDATE SKIP LOCKED) sessões expiradas (expires_at < LEAST(p_now, clock_timestamp())) '
    'e sem sucessora de rotação. Substitui DELETE direto em auth_sessions para rewit_app (F1-AUD-02).';


CREATE OR REPLACE FUNCTION public.rewit_purge_deleted_user_auth_sessions(p_user_id UUID)
RETURNS INT AS $$
DECLARE
    v_deleted INT;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'rewit_purge_deleted_user_auth_sessions: p_user_id não pode ser nulo';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.users u
        WHERE u.id = p_user_id
          AND u.account_status = 'DELETED'
          AND u.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'rewit_purge_deleted_user_auth_sessions: remoção de sessões restrita a contas definitivamente DELETED'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    DELETE FROM public.auth_sessions
    WHERE user_id = p_user_id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_purge_deleted_user_auth_sessions(UUID) FROM PUBLIC;

COMMENT ON FUNCTION public.rewit_purge_deleted_user_auth_sessions(UUID) IS
    'Remove sessões de uma conta exclusivamente quando account_status = DELETED e deleted_at IS NOT NULL '
    '(rotina LGPD PurgeDeletedAccountUseCase). Substitui DELETE direto em auth_sessions para rewit_app (F1-AUD-02).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        GRANT EXECUTE ON FUNCTION public.rewit_purge_expired_auth_sessions(TIMESTAMPTZ, INT) TO rewit_app;
        GRANT EXECUTE ON FUNCTION public.rewit_purge_deleted_user_auth_sessions(UUID) TO rewit_app;
    END IF;
END
$$;


-- 5. ATUALIZAÇÃO DA ASSERTION DE PRIVILÉGIOS DE RUNTIME (rewit_assert_runtime_privileges)
CREATE OR REPLACE FUNCTION rewit_assert_runtime_privileges()
RETURNS VOID AS $$
DECLARE
    deletable TEXT[] := ARRAY[
        'notifications', 'outbox_messages', 'place_amenity_configurations',
        'place_amenity_selections', 'place_external_references', 'place_opening_hours_intervals',
        'place_opening_hours_schedules', 'place_product_offers', 'review_reactions', 'saved_items',
        'storage_object_quarantine', 'user_activities', 'user_follows', 'user_interests', 'user_reputation'
    ];
    audit_tables TEXT[] := ARRAY[
        'moderation_audit_logs', 'discussion_moderation_audit_logs',
        'business_response_moderation_audit_logs', 'place_media_moderation_audit_logs'
    ];
    required_triggers TEXT[][] := ARRAY[
        ['moderation_audit_logs', 'trg_bind_moderation_audit_reference'],
        ['discussion_moderation_audit_logs', 'trg_bind_discussion_moderation_audit_reference'],
        ['business_response_moderation_audit_logs', 'trg_bind_business_response_audit_reference'],
        ['place_media_moderation_audit_logs', 'trg_bind_place_media_audit_reference'],
        ['place_media_reports', 'trg_guard_place_media_report_decision'],
        ['users', 'trg_guard_user_role_assignment'],
        ['auth_sessions', 'trg_guard_auth_session_integrity']
    ];
    allowed_secdef_functions TEXT[] := ARRAY[
        'rewit_purge_expired_auth_sessions',
        'rewit_purge_deleted_user_auth_sessions'
    ];
    problems TEXT[] := ARRAY[]::TEXT[];
    v_list TEXT;
    v_table_owner OID;
    v_fn_name TEXT;
    v_fn_oid OID;
    v_fn_owner OID;
    v_fn_secdef BOOLEAN;
    v_fn_config TEXT[];
    i INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        RAISE EXCEPTION 'rewit_assert_runtime_privileges: role rewit_app inexistente';
    END IF;

    -- Atributos e memberships
    SELECT string_agg(attr, ', ') INTO v_list
    FROM pg_roles r,
         LATERAL (VALUES ('SUPERUSER', r.rolsuper), ('CREATEROLE', r.rolcreaterole), ('CREATEDB', r.rolcreatedb),
                         ('REPLICATION', r.rolreplication), ('BYPASSRLS', r.rolbypassrls)) a(attr, enabled)
    WHERE r.rolname = 'rewit_app' AND a.enabled;
    IF v_list IS NOT NULL THEN
        problems := problems || format('atributos administrativos: %s', v_list);
    END IF;
    SELECT string_agg(g.rolname, ', ') INTO v_list
    FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid JOIN pg_roles u ON u.oid = m.member
    WHERE u.rolname = 'rewit_app';
    IF v_list IS NOT NULL THEN
        problems := problems || format('memberships: %s', v_list);
    END IF;

    -- Privilégios por relação do schema public (inclui os herdados de PUBLIC)
    SELECT string_agg(format('%s em %s', p.priv, c.oid::regclass), ', ' ORDER BY c.relname, p.priv) INTO v_list
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_table_privilege('rewit_app', c.oid, p.priv)
      AND (
            p.priv IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
         OR (p.priv = 'DELETE' AND NOT c.relname = ANY (deletable))
         OR (p.priv = 'UPDATE' AND c.relname = ANY (audit_tables))
         OR (p.priv IN ('INSERT', 'UPDATE') AND c.relname IN ('place_media_reports', 'flyway_schema_history'))
         OR (p.priv IN ('INSERT', 'UPDATE')
             AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('privilégios indevidos: %s', v_list);
    END IF;
    IF has_column_privilege('rewit_app', 'public.place_media_reports', 'is_legacy_decision', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'is_legacy_decision', 'UPDATE') THEN
        problems := problems || 'escrita em place_media_reports.is_legacy_decision'::TEXT;
    END IF;

    -- Cascatas: um DELETE permitido ao runtime só pode propagar para tabelas também da lista (fecho), e
    -- nenhum UPDATE do runtime pode propagar por ON UPDATE CASCADE/SET NULL/SET DEFAULT
    SELECT string_agg(format('%s -> %s (%s)', k.confrelid::regclass, k.conrelid::regclass, k.conname), ', ') INTO v_list
    FROM pg_constraint k
    WHERE k.contype = 'f'
      AND (
            (k.confdeltype IN ('c', 'n', 'd')
             AND has_table_privilege('rewit_app', k.confrelid, 'DELETE')
             AND NOT (SELECT relname FROM pg_class WHERE oid = k.conrelid) = ANY (deletable))
         OR (k.confupdtype IN ('c', 'n', 'd') AND has_table_privilege('rewit_app', k.confrelid, 'UPDATE'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('cascatas acionáveis pelo runtime: %s', v_list);
    END IF;

    -- Privilégios padrão de qualquer role que crie objetos: tabelas novas não nascem graváveis
    SELECT string_agg(DISTINCT format('%s por %s', a.privilege_type, pg_get_userbyid(d.defaclrole)), ', ') INTO v_list
    FROM pg_default_acl d
    CROSS JOIN LATERAL aclexplode(d.defaclacl) a
    WHERE d.defaclobjtype = 'r'
      AND a.grantee = (SELECT oid FROM pg_roles WHERE rolname = 'rewit_app')
      AND a.privilege_type <> 'SELECT';
    IF v_list IS NOT NULL THEN
        problems := problems || format('privilégios padrão de escrita em tabelas: %s', v_list);
    END IF;

    -- Guards obrigatórios ativos
    FOR i IN 1 .. array_length(required_triggers, 1) LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_trigger t
            WHERE t.tgrelid = to_regclass('public.' || required_triggers[i][1])
              AND t.tgname = required_triggers[i][2]
              AND t.tgenabled <> 'D'
        ) THEN
            problems := problems || format('trigger ausente ou desabilitado: %s.%s', required_triggers[i][1], required_triggers[i][2]);
        END IF;
    END LOOP;

    -- Funções de trigger com search_path fixo (P2-1)
    SELECT string_agg(p.proname, ', ') INTO v_list
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prorettype = 'trigger'::regtype
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
      AND NOT (COALESCE(p.proconfig, ARRAY[]::TEXT[]) @> ARRAY['search_path=pg_catalog, public, pg_temp']);
    IF v_list IS NOT NULL THEN
        problems := problems || format('funções de trigger sem search_path fixo: %s', v_list);
    END IF;

    -- Governança de funções SECURITY DEFINER no schema public (F1-AUD-02):
    -- apenas as funções restritas de cleanup/purge de auth_sessions são permitidas, com owner privilegiado,
    -- search_path fixo, sem EXECUTE para PUBLIC e com EXECUTE apenas para rewit_app.
    SELECT string_agg(p.proname, ', ') INTO v_list
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prosecdef
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
      AND NOT (p.proname = ANY (allowed_secdef_functions));
    IF v_list IS NOT NULL THEN
        problems := problems || format('funções SECURITY DEFINER não autorizadas: %s', v_list);
    END IF;

    SELECT c.relowner INTO v_table_owner
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'auth_sessions';

    FOREACH v_fn_name IN ARRAY allowed_secdef_functions LOOP
        SELECT p.oid, p.proowner, p.prosecdef, p.proconfig
        INTO v_fn_oid, v_fn_owner, v_fn_secdef, v_fn_config
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = v_fn_name;

        IF v_fn_oid IS NULL THEN
            problems := problems || format('função de cleanup ausente: %s', v_fn_name);
        ELSE
            IF NOT v_fn_secdef THEN
                problems := problems || format('função %s não é SECURITY DEFINER', v_fn_name);
            END IF;
            -- Owner: a role de migração (dona de auth_sessions) ou uma role que a administra (membro), nunca rewit_app
            IF NOT pg_has_role(v_fn_owner, v_table_owner, 'MEMBER')
               OR v_fn_owner = (SELECT oid FROM pg_roles WHERE rolname = 'rewit_app') THEN
                problems := problems || format('owner incorreto em função %s', v_fn_name);
            END IF;
            IF NOT (COALESCE(v_fn_config, ARRAY[]::TEXT[]) @> ARRAY['search_path=pg_catalog, public, pg_temp']) THEN
                problems := problems || format('função %s sem search_path fixo', v_fn_name);
            END IF;
            IF has_function_privilege('public', v_fn_oid, 'EXECUTE') THEN
                problems := problems || format('função %s com EXECUTE concedido a PUBLIC', v_fn_name);
            END IF;
            IF NOT has_function_privilege('rewit_app', v_fn_oid, 'EXECUTE') THEN
                problems := problems || format('função %s sem EXECUTE para rewit_app', v_fn_name);
            END IF;
        END IF;
    END LOOP;

    IF array_length(problems, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Privilégios do runtime fora da política: %', array_to_string(problems, '; ')
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$ LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public, pg_temp;

REVOKE EXECUTE ON FUNCTION rewit_assert_runtime_privileges() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rewit_assert_runtime_privileges() FROM rewit_app;

COMMENT ON FUNCTION rewit_assert_runtime_privileges() IS
    'Aborta se rewit_app tiver privilégios fora da política (V37/V44): DELETE fora da lista (incluindo auth_sessions), '
    'escrita em auditoria, extensões ou flyway_schema_history, cascatas acionáveis, privilégios padrão de escrita, '
    'guards ausentes ou funções SECURITY DEFINER fora da governança.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        PERFORM rewit_assert_runtime_privileges();
    END IF;
END
$$;
