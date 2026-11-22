-- ==============================================================================
-- REWIT DATABASE MIGRATION - V46
-- ==============================================================================
-- SEC-F2-04: Hardening da integridade da quarentena de storage e proteção do
-- grace period do Storage GC sob comprometimento SQL da credencial rewit_app
-- (namespaces places/ e reviews/).
--
-- Invariantes estabelecidos (SEC-F2-04 / SEC-F2-04-R1):
--   1. Defaults confiáveis: status = 'OBSERVED', first_observed_at = clock_timestamp(),
--      last_observed_at = clock_timestamp(), confirmed_at = NULL.
--   2. Reinício conservador de TODAS as entradas preexistentes em storage_object_quarantine
--      com um único instante confiável do PostgreSQL (clock_timestamp()), neutralizando
--      timestamps retroativos legados tanto em OBSERVED quanto em CONFIRMED_ORPHAN.
--   3. Trigger trg_guard_storage_quarantine_integrity (BEFORE INSERT OR UPDATE):
--      - INSERT: conexões comuns (não owner) criam entradas exclusivamente como OBSERVED
--        com confirmed_at NULL; first_observed_at e last_observed_at são obrigatoriamente
--        gerados pelo clock_timestamp() do banco, neutralizando timestamps manuais.
--      - UPDATE: imutabilidade estrita de id para todas as roles; imutabilidade de
--        object_key e first_observed_at para roles de runtime; conexões comuns são
--        impedidas de alterar status e confirmed_at diretamente.
--   4. Função SECURITY DEFINER rewit_confirm_storage_quarantine_entry(UUID):
--      - Valida session_user (rewit_app ou owner).
--      - Lock FOR UPDATE atômico na linha.
--      - Exige estado anterior OBSERVED (ou valida consistência se já CONFIRMED_ORPHAN).
--      - Valida rigorosamente que first_observed_at <= clock_timestamp() - INTERVAL '24 hours'.
--      - Rejeita timestamps futuros ou inconsistentes.
--      - Atualiza status = 'CONFIRMED_ORPHAN' e confirmed_at = clock_timestamp().
--   5. Privilégios mínimos estritos:
--      - rewit_app: SELECT, INSERT restrito a colunas de observação, UPDATE restrito a
--        (last_observed_at, last_modified_at) — sem UPDATE(id) —, DELETE e EXECUTE na
--        função de confirmação. NUNCA possui escrita direta em id, status nem confirmed_at.
--      - rewit_media: mantém apenas SELECT + UPDATE (id) para FOR UPDATE (protegido por
--        triggers) e liberação via rewit_release_place_quarantine_entry.
--      - rewit_mod: nenhum privilégio em storage_object_quarantine.
--      - PUBLIC: revogado EXECUTE na função de confirmação.
--   6. Atualização de rewit_assert_runtime_privileges() para validar permanentemente todos
--      os novos privilégios, triggers e a 5ª função SECURITY DEFINER autorizada.
-- ==============================================================================

-- 1. Defaults de colunas
ALTER TABLE public.storage_object_quarantine
    ALTER COLUMN status SET DEFAULT 'OBSERVED',
    ALTER COLUMN first_observed_at SET DEFAULT clock_timestamp(),
    ALTER COLUMN last_observed_at SET DEFAULT clock_timestamp(),
    ALTER COLUMN confirmed_at SET DEFAULT NULL;

-- 2. Reinício conservador de TODAS as entradas preexistentes (SEC-F2-04-VULN-01)
--    Antes da ativação dos novos controles, nenhum timestamp legado é confiável.
--    Usa um único instante do relógio do PostgreSQL para todo o lote.
DO $$
DECLARE
    v_reset_at TIMESTAMPTZ := clock_timestamp();
BEGIN
    UPDATE public.storage_object_quarantine
    SET status = 'OBSERVED',
        confirmed_at = NULL,
        first_observed_at = v_reset_at,
        last_observed_at = v_reset_at,
        last_modified_at = CASE
            WHEN last_modified_at > v_reset_at THEN v_reset_at
            ELSE last_modified_at
        END;
END
$$;

-- 3. Trigger guard de integridade de storage_object_quarantine
CREATE OR REPLACE FUNCTION public.fn_guard_storage_quarantine_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);

    IF TG_OP = 'INSERT' THEN
        IF NOT v_is_owner THEN
            IF NEW.status IS DISTINCT FROM 'OBSERVED' OR NEW.confirmed_at IS NOT NULL THEN
                RAISE EXCEPTION 'storage_object_quarantine: novas entradas nascem exclusivamente OBSERVED e sem confirmed_at'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- Início confiável da quarentena gerado pelo banco; ignora valores manuais do cliente
            NEW.first_observed_at := clock_timestamp();
            NEW.last_observed_at := clock_timestamp();
            NEW.status := 'OBSERVED';
            NEW.confirmed_at := NULL;
        ELSE
            IF NEW.first_observed_at IS NULL THEN
                NEW.first_observed_at := clock_timestamp();
            END IF;
            IF NEW.last_observed_at IS NULL THEN
                NEW.last_observed_at := NEW.first_observed_at;
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id THEN
            RAISE EXCEPTION 'storage_object_quarantine: id é estritamente imutável'
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF NOT v_is_owner THEN
            IF NEW.object_key IS DISTINCT FROM OLD.object_key THEN
                RAISE EXCEPTION 'storage_object_quarantine: object_key é estritamente imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NEW.first_observed_at IS DISTINCT FROM OLD.first_observed_at THEN
                RAISE EXCEPTION 'storage_object_quarantine: first_observed_at é estritamente imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NEW.status IS DISTINCT FROM OLD.status OR NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at THEN
                RAISE EXCEPTION 'storage_object_quarantine: transição de status e alteração de confirmed_at exigem função autorizada'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_storage_quarantine_integrity ON public.storage_object_quarantine;
CREATE TRIGGER trg_guard_storage_quarantine_integrity
    BEFORE INSERT OR UPDATE ON public.storage_object_quarantine
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_storage_quarantine_integrity();

-- 4. Função SECURITY DEFINER de confirmação de órfão
CREATE OR REPLACE FUNCTION public.rewit_confirm_storage_quarantine_entry(p_entry_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    v_session_is_owner BOOLEAN;
    v_status VARCHAR(32);
    v_first_observed_at TIMESTAMPTZ;
    v_confirmed_at TIMESTAMPTZ;
    v_now TIMESTAMPTZ;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(session_user, c.relowner, 'MEMBER')
    INTO v_session_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = 'public.storage_object_quarantine'::regclass AND r.rolname = session_user;

    IF NOT (COALESCE(v_session_is_owner, FALSE) OR session_user = 'rewit_app') THEN
        RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: chamador % não autorizado', session_user
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF p_entry_id IS NULL THEN
        RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: p_entry_id não pode ser nulo';
    END IF;

    SELECT q.status, q.first_observed_at, q.confirmed_at
    INTO v_status, v_first_observed_at, v_confirmed_at
    FROM public.storage_object_quarantine q
    WHERE q.id = p_entry_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    v_now := clock_timestamp();

    IF v_status = 'CONFIRMED_ORPHAN' THEN
        IF v_first_observed_at > v_now
           OR v_first_observed_at > v_now - INTERVAL '24 hours'
           OR v_confirmed_at IS NULL
           OR v_confirmed_at > v_now
           OR v_confirmed_at < v_first_observed_at + INTERVAL '24 hours' THEN
            RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: entrada CONFIRMED_ORPHAN com timestamps inconsistentes (first_observed_at=%, confirmed_at=%, now=%)',
                v_first_observed_at, v_confirmed_at, v_now
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN TRUE;
    END IF;

    IF v_status IS DISTINCT FROM 'OBSERVED' THEN
        RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: entrada em estado % incompatível com confirmação', v_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF v_first_observed_at > v_now THEN
        RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: first_observed_at no futuro (%)', v_first_observed_at
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_first_observed_at > v_now - INTERVAL '24 hours' THEN
        RAISE EXCEPTION 'rewit_confirm_storage_quarantine_entry: período mínimo de observação de 24 horas não decorrido (first_observed_at=%, now=%)',
            v_first_observed_at, v_now
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    UPDATE public.storage_object_quarantine
    SET status = 'CONFIRMED_ORPHAN',
        confirmed_at = v_now
    WHERE id = p_entry_id;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_confirm_storage_quarantine_entry(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.rewit_confirm_storage_quarantine_entry(UUID) TO rewit_app;

COMMENT ON FUNCTION public.rewit_confirm_storage_quarantine_entry(UUID) IS
    'Transiciona atomicamente uma entrada OBSERVED para CONFIRMED_ORPHAN em storage_object_quarantine, '
    'validando rigorosamente o transcurso de 24 horas desde first_observed_at pelo relógio do PostgreSQL. '
    'Restrita a rewit_app (e owner); jamais aceita timestamps retroativos manuais (SEC-F2-04).';

-- 5. Privilégios SQL da role rewit_app (SEC-F2-04-R1: sem UPDATE em id; last_observed_at/last_modified_at bastam para FOR UPDATE)
REVOKE ALL ON public.storage_object_quarantine FROM rewit_app;
GRANT SELECT ON public.storage_object_quarantine TO rewit_app;
GRANT INSERT (id, object_key, status, first_observed_at, last_observed_at, last_modified_at)
    ON public.storage_object_quarantine TO rewit_app;
GRANT UPDATE (last_observed_at, last_modified_at)
    ON public.storage_object_quarantine TO rewit_app;
GRANT DELETE ON public.storage_object_quarantine TO rewit_app;

-- 6. Atualização de rewit_assert_runtime_privileges()
CREATE OR REPLACE FUNCTION public.rewit_assert_runtime_privileges()
RETURNS VOID AS $$
DECLARE
    app_deletable TEXT[] := ARRAY[
        'notifications', 'outbox_messages', 'place_amenity_configurations',
        'place_amenity_selections', 'place_external_references', 'place_opening_hours_intervals',
        'place_opening_hours_schedules', 'place_product_offers', 'review_reactions', 'saved_items',
        'storage_object_quarantine', 'user_activities', 'user_follows', 'user_interests', 'user_reputation'
    ];
    all_audit_tables TEXT[] := ARRAY[
        'moderation_audit_logs', 'discussion_moderation_audit_logs',
        'business_response_moderation_audit_logs', 'place_media_moderation_audit_logs',
        'account_moderation_audit_logs', 'place_claim_moderation_audit_logs'
    ];
    report_and_claim_tables TEXT[] := ARRAY[
        'place_media_reports', 'review_reports', 'discussion_reports', 'place_claim_requests'
    ];
    media_insertable TEXT[] := ARRAY[
        'place_media_moderation_audit_logs'
    ];
    media_updatable TEXT[] := ARRAY[
        'place_media'
    ];
    -- rewit_media não possui DELETE direto em nenhuma tabela (quarentena via rewit_release_place_quarantine_entry)
    media_deletable TEXT[] := ARRAY[]::TEXT[];
    mod_insertable TEXT[] := ARRAY[
        'moderation_audit_logs', 'discussion_moderation_audit_logs',
        'business_response_moderation_audit_logs', 'place_media_moderation_audit_logs',
        'account_moderation_audit_logs', 'place_claim_moderation_audit_logs',
        'rateable_target_stats', 'user_reputation'
    ];
    mod_updatable TEXT[] := ARRAY[
        'users', 'auth_sessions', 'reviews', 'review_reports', 'review_media',
        'rateable_target_stats', 'user_reputation', 'review_discussions', 'discussion_reports',
        'business_responses', 'business_accounts', 'places', 'place_claim_requests', 'place_media'
    ];
    mod_deletable TEXT[] := ARRAY[
        'place_product_offers', 'place_opening_hours_schedules', 'place_amenity_configurations'
    ];
    required_triggers TEXT[][] := ARRAY[
        ['moderation_audit_logs', 'trg_bind_moderation_audit_reference'],
        ['moderation_audit_logs', 'trg_verify_review_moderation_transition'],
        ['discussion_moderation_audit_logs', 'trg_bind_discussion_moderation_audit_reference'],
        ['discussion_moderation_audit_logs', 'trg_verify_discussion_moderation_transition'],
        ['business_response_moderation_audit_logs', 'trg_bind_business_response_audit_reference'],
        ['business_response_moderation_audit_logs', 'trg_verify_business_response_moderation_transition'],
        ['place_media_moderation_audit_logs', 'trg_bind_place_media_audit_reference'],
        ['place_media_moderation_audit_logs', 'trg_verify_place_media_moderation_transition'],
        ['account_moderation_audit_logs', 'trg_bind_account_moderation_audit_reference'],
        ['account_moderation_audit_logs', 'trg_verify_account_moderation_transition'],
        ['account_moderation_audit_logs', 'trg_prevent_account_moderation_audit_update'],
        ['account_moderation_audit_logs', 'trg_prevent_account_moderation_audit_delete'],
        ['account_moderation_audit_logs', 'trg_prevent_account_moderation_audit_truncate'],
        ['place_claim_moderation_audit_logs', 'trg_bind_place_claim_audit_reference'],
        ['place_claim_moderation_audit_logs', 'trg_verify_place_claim_moderation_transition'],
        ['place_claim_moderation_audit_logs', 'trg_prevent_place_claim_moderation_audit_update'],
        ['place_claim_moderation_audit_logs', 'trg_prevent_place_claim_moderation_audit_delete'],
        ['place_claim_moderation_audit_logs', 'trg_prevent_place_claim_moderation_audit_truncate'],
        ['place_media_reports', 'trg_guard_place_media_report_decision'],
        ['place_media_reports', 'trg_require_place_media_report_moderation_audit'],
        ['review_reports', 'trg_guard_review_report_decision'],
        ['review_reports', 'trg_require_review_report_moderation_audit'],
        ['discussion_reports', 'trg_guard_discussion_report_decision'],
        ['discussion_reports', 'trg_require_discussion_report_moderation_audit'],
        ['place_claim_requests', 'trg_guard_place_claim_decision'],
        ['place_claim_requests', 'trg_require_place_claim_moderation_audit'],
        ['users', 'trg_guard_user_role_assignment'],
        ['users', 'trg_require_account_moderation_audit'],
        ['auth_sessions', 'trg_guard_auth_session_integrity'],
        ['places', 'trg_a_guard_places_governance'],
        ['places', 'trg_require_place_owner_claim_audit'],
        ['business_accounts', 'trg_guard_business_accounts_governance'],
        ['business_accounts', 'trg_require_business_verification_claim_audit'],
        ['reviews', 'trg_guard_reviews_state_transitions'],
        ['reviews', 'trg_require_review_moderation_audit'],
        ['review_discussions', 'trg_guard_review_discussions_state_transitions'],
        ['review_discussions', 'trg_require_discussion_moderation_audit'],
        ['business_responses', 'trg_guard_business_responses_state_transitions'],
        ['business_responses', 'trg_require_business_response_moderation_audit'],
        ['place_media', 'trg_guard_place_media_state_transitions'],
        ['place_media', 'trg_require_place_media_moderation_audit'],
        ['review_media', 'trg_guard_review_media_state_transitions'],
        ['storage_object_quarantine', 'trg_guard_storage_quarantine_media_writes'],
        ['storage_object_quarantine', 'trg_guard_storage_quarantine_integrity']
    ];
    -- [nome, assinatura, role_autorizada]
    allowed_secdef_functions TEXT[][] := ARRAY[
        ['rewit_purge_expired_auth_sessions', 'p_now timestamp with time zone, p_limit integer', 'rewit_app'],
        ['rewit_purge_deleted_user_auth_sessions', 'p_user_id uuid', 'rewit_app'],
        ['rewit_auto_resolve_removed_place_media_reports', 'p_place_media_id uuid, p_decision_notes character varying', 'rewit_media'],
        ['rewit_release_place_quarantine_entry', 'p_entry_id uuid', 'rewit_media'],
        ['rewit_confirm_storage_quarantine_entry', 'p_entry_id uuid', 'rewit_app']
    ];
    problems TEXT[] := ARRAY[]::TEXT[];
    v_list TEXT;
    v_table_owner OID;
    v_role_name TEXT;
    v_fn_name TEXT;
    v_fn_sig TEXT;
    v_fn_allowed_role TEXT;
    v_fn_count INT;
    v_fn_oid OID;
    v_fn_owner OID;
    v_fn_secdef BOOLEAN;
    v_fn_config TEXT[];
    v_fn_actual_sig TEXT;
    i INT;
BEGIN
    -- 1. Existência, atributos, memberships e ownership de rewit_app, rewit_media e rewit_mod
    FOREACH v_role_name IN ARRAY ARRAY['rewit_app', 'rewit_media', 'rewit_mod'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role_name) THEN
            RAISE EXCEPTION 'rewit_assert_runtime_privileges: role % inexistente', v_role_name;
        END IF;

        SELECT string_agg(attr, ', ') INTO v_list
        FROM pg_roles r,
             LATERAL (VALUES ('SUPERUSER', r.rolsuper), ('CREATEROLE', r.rolcreaterole), ('CREATEDB', r.rolcreatedb),
                             ('REPLICATION', r.rolreplication), ('BYPASSRLS', r.rolbypassrls), ('INHERIT', r.rolinherit)) a(attr, enabled)
        WHERE r.rolname = v_role_name AND a.enabled;
        IF v_list IS NOT NULL THEN
            problems := problems || format('%s com atributos administrativos: %s', v_role_name, v_list);
        END IF;

        SELECT string_agg(g.rolname, ', ') INTO v_list
        FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid JOIN pg_roles u ON u.oid = m.member
        WHERE u.rolname = v_role_name;
        IF v_list IS NOT NULL THEN
            problems := problems || format('%s com memberships: %s', v_role_name, v_list);
        END IF;

        IF EXISTS (
            SELECT 1 FROM pg_shdepend d JOIN pg_roles r ON r.oid = d.refobjid
            WHERE d.refclassid = 'pg_authid'::regclass AND d.deptype = 'o' AND r.rolname = v_role_name
        ) THEN
            problems := problems || format('%s é owner de objetos', v_role_name);
        END IF;
    END LOOP;

    -- 2. Privilégios de rewit_app no schema public (sem escrita em auditorias, place_media UPDATE, place_media_reports nem UPDATE geral em storage_object_quarantine)
    SELECT string_agg(format('%s em %s', p.priv, c.oid::regclass), ', ' ORDER BY c.relname, p.priv) INTO v_list
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_table_privilege('rewit_app', c.oid, p.priv)
      AND (
            p.priv IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
         OR (p.priv = 'DELETE' AND NOT c.relname = ANY (app_deletable))
         OR (p.priv = 'UPDATE' AND (c.relname = ANY (all_audit_tables) OR c.relname = ANY (report_and_claim_tables) OR c.relname IN ('place_media', 'storage_object_quarantine')))
         OR (p.priv = 'INSERT' AND (c.relname = ANY (all_audit_tables) OR c.relname IN ('place_media_reports', 'place_claim_requests')))
         OR (p.priv IN ('INSERT', 'UPDATE') AND c.relname = 'flyway_schema_history')
         OR (p.priv IN ('INSERT', 'UPDATE')
             AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_app com privilégios indevidos: %s', v_list);
    END IF;

    -- Colunas protegidas de place_media, place_media_reports, place_claim_requests e storage_object_quarantine inacessíveis para rewit_app
    IF has_column_privilege('rewit_app', 'public.place_media', 'status', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media', 'removal_source', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media', 'removed_at', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media', 'purged_at', 'UPDATE') THEN
        problems := problems || 'rewit_app com UPDATE em colunas protegidas de place_media'::TEXT;
    END IF;
    IF has_column_privilege('rewit_app', 'public.place_media_reports', 'id', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'is_legacy_decision', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'is_legacy_decision', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'decided_by_user_id', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'decided_by_user_id', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'authenticity_mac', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'authenticity_mac', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_media_reports', 'status', 'UPDATE') THEN
        problems := problems || 'rewit_app com escrita em place_media_reports'::TEXT;
    END IF;
    IF has_column_privilege('rewit_app', 'public.place_claim_requests', 'decided_by_user_id', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_claim_requests', 'decided_by_user_id', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_claim_requests', 'authenticity_mac', 'INSERT')
       OR has_column_privilege('rewit_app', 'public.place_claim_requests', 'authenticity_mac', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.place_claim_requests', 'status', 'UPDATE') THEN
        problems := problems || 'rewit_app com escrita em colunas de decisão de place_claim_requests'::TEXT;
    END IF;

    -- storage_object_quarantine: rewit_app não pode alterar id, status, confirmed_at, first_observed_at, object_key nem inserir confirmed_at (SEC-F2-04 / SEC-F2-04-R1)
    IF has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'id', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'status', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'confirmed_at', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'first_observed_at', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'object_key', 'UPDATE')
       OR has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'confirmed_at', 'INSERT') THEN
        problems := problems || 'rewit_app com escrita em colunas proibidas de storage_object_quarantine'::TEXT;
    END IF;

    -- 2b. Privilégios de rewit_media no schema public (privilégio mínimo para ciclo de vida público de mídias)
    SELECT string_agg(format('%s em %s', p.priv, c.oid::regclass), ', ' ORDER BY c.relname, p.priv) INTO v_list
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_table_privilege('rewit_media', c.oid, p.priv)
      AND (
            p.priv IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
         OR (p.priv = 'DELETE' AND NOT c.relname = ANY (media_deletable))
         OR (p.priv = 'INSERT' AND NOT c.relname = ANY (media_insertable))
         OR (p.priv = 'UPDATE' AND NOT c.relname = ANY (media_updatable))
         OR (p.priv IN ('INSERT', 'UPDATE', 'DELETE') AND c.relname = 'flyway_schema_history')
         OR (p.priv IN ('INSERT', 'UPDATE', 'DELETE')
             AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_media com privilégios indevidos: %s', v_list);
    END IF;

    -- places: rewit_media possui apenas UPDATE (id) para FOR UPDATE (nunca outra coluna)
    SELECT string_agg(a.attname, ', ' ORDER BY a.attname) INTO v_list
    FROM pg_attribute a
    WHERE a.attrelid = 'public.places'::regclass
      AND a.attnum > 0
      AND NOT a.attisdropped
      AND a.attname <> 'id'
      AND has_column_privilege('rewit_media', 'public.places', a.attname, 'UPDATE');
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_media com UPDATE em colunas indevidas de places: %s', v_list);
    END IF;

    -- storage_object_quarantine: rewit_media possui apenas UPDATE (id) para FOR UPDATE; nunca INSERT/DELETE
    SELECT string_agg(a.attname, ', ' ORDER BY a.attname) INTO v_list
    FROM pg_attribute a
    WHERE a.attrelid = 'public.storage_object_quarantine'::regclass
      AND a.attnum > 0
      AND NOT a.attisdropped
      AND a.attname <> 'id'
      AND (has_column_privilege('rewit_media', 'public.storage_object_quarantine', a.attname, 'UPDATE')
           OR has_column_privilege('rewit_media', 'public.storage_object_quarantine', a.attname, 'INSERT'));
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_media com escrita em colunas indevidas de storage_object_quarantine: %s', v_list);
    END IF;
    IF NOT has_column_privilege('rewit_media', 'public.storage_object_quarantine', 'id', 'UPDATE')
       OR has_column_privilege('rewit_media', 'public.storage_object_quarantine', 'id', 'INSERT')
       OR has_table_privilege('rewit_media', 'public.storage_object_quarantine', 'UPDATE')
       OR has_table_privilege('rewit_media', 'public.storage_object_quarantine', 'DELETE') THEN
        problems := problems || 'rewit_media fora da política de storage_object_quarantine (exige somente SELECT + UPDATE (id))'::TEXT;
    END IF;

    -- place_media_reports: rewit_media nunca escreve em colunas de decisão nem faz UPDATE
    IF has_column_privilege('rewit_media', 'public.place_media_reports', 'is_legacy_decision', 'INSERT')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'is_legacy_decision', 'UPDATE')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'decided_by_user_id', 'INSERT')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'decided_by_user_id', 'UPDATE')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'status', 'UPDATE') THEN
        problems := problems || 'rewit_media com escrita indevida em colunas de place_media_reports'::TEXT;
    END IF;

    -- 3. Privilégios de rewit_mod no schema public
    SELECT string_agg(format('%s em %s', p.priv, c.oid::regclass), ', ' ORDER BY c.relname, p.priv) INTO v_list
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_table_privilege('rewit_mod', c.oid, p.priv)
      AND (
            p.priv IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
         OR (p.priv = 'DELETE' AND NOT c.relname = ANY (mod_deletable))
         OR (p.priv = 'INSERT' AND NOT c.relname = ANY (mod_insertable))
         OR (p.priv = 'UPDATE' AND NOT c.relname = ANY (mod_updatable))
         OR (p.priv IN ('INSERT', 'UPDATE', 'DELETE') AND c.relname = 'flyway_schema_history')
         OR (p.priv IN ('INSERT', 'UPDATE', 'DELETE')
             AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_mod com privilégios indevidos: %s', v_list);
    END IF;

    IF has_column_privilege('rewit_mod', 'public.place_media_reports', 'is_legacy_decision', 'INSERT')
       OR has_column_privilege('rewit_mod', 'public.place_media_reports', 'is_legacy_decision', 'UPDATE') THEN
        problems := problems || 'rewit_mod com escrita em place_media_reports.is_legacy_decision'::TEXT;
    END IF;

    -- 4. Cascatas acionáveis pelo runtime (rewit_app, rewit_media, rewit_mod)
    SELECT string_agg(format('%s -> %s (%s)', k.confrelid::regclass, k.conrelid::regclass, k.conname), ', ') INTO v_list
    FROM pg_constraint k
    WHERE k.contype = 'f'
      AND (
            (k.confdeltype IN ('c', 'n', 'd')
             AND has_table_privilege('rewit_app', k.confrelid, 'DELETE')
             AND NOT (SELECT relname FROM pg_class WHERE oid = k.conrelid) = ANY (app_deletable))
         OR (k.confupdtype IN ('c', 'n', 'd')
             AND (has_table_privilege('rewit_app', k.confrelid, 'UPDATE')
                  OR has_table_privilege('rewit_media', k.confrelid, 'UPDATE')
                  OR has_table_privilege('rewit_mod', k.confrelid, 'UPDATE')))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('cascatas acionáveis pelo runtime: %s', v_list);
    END IF;

    -- 5. Privilégios padrão de escrita em tabelas para rewit_app, rewit_media e rewit_mod
    SELECT string_agg(DISTINCT format('%s para %s por %s', a.privilege_type, pg_get_userbyid(a.grantee), pg_get_userbyid(d.defaclrole)), ', ') INTO v_list
    FROM pg_default_acl d
    CROSS JOIN LATERAL aclexplode(d.defaclacl) a
    WHERE d.defaclobjtype = 'r'
      AND a.grantee IN (SELECT oid FROM pg_roles WHERE rolname IN ('rewit_app', 'rewit_media', 'rewit_mod'))
      AND a.privilege_type <> 'SELECT';
    IF v_list IS NOT NULL THEN
        problems := problems || format('privilégios padrão de escrita em tabelas: %s', v_list);
    END IF;

    -- 6. Guards e constraint triggers obrigatórios ativos
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

    -- 7. Funções de trigger com search_path fixo (P2-1)
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

    -- 8. Governança de funções SECURITY DEFINER no schema public (V44 + V45 + SEC-F2-03 + SEC-F2-04)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_list
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prosecdef
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
      AND p.proname NOT IN ('rewit_purge_expired_auth_sessions', 'rewit_purge_deleted_user_auth_sessions', 'rewit_auto_resolve_removed_place_media_reports',
                            'rewit_release_place_quarantine_entry', 'rewit_confirm_storage_quarantine_entry');
    IF v_list IS NOT NULL THEN
        problems := problems || format('funções SECURITY DEFINER não autorizadas: %s', v_list);
    END IF;

    SELECT c.relowner INTO v_table_owner
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'auth_sessions';

    FOR i IN 1 .. array_length(allowed_secdef_functions, 1) LOOP
        v_fn_name := allowed_secdef_functions[i][1];
        v_fn_sig := allowed_secdef_functions[i][2];
        v_fn_allowed_role := allowed_secdef_functions[i][3];

        SELECT COUNT(*) INTO v_fn_count
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = v_fn_name;

        IF v_fn_count = 0 THEN
            problems := problems || format('função SECURITY DEFINER ausente: %s', v_fn_name);
        ELSIF v_fn_count > 1 THEN
            problems := problems || format('overloads não auditados em função %s (%s definições)', v_fn_name, v_fn_count);
        ELSE
            SELECT p.oid, p.proowner, p.prosecdef, p.proconfig, pg_catalog.pg_get_function_identity_arguments(p.oid)
            INTO v_fn_oid, v_fn_owner, v_fn_secdef, v_fn_config, v_fn_actual_sig
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public' AND p.proname = v_fn_name;

            IF v_fn_actual_sig IS DISTINCT FROM v_fn_sig THEN
                problems := problems || format('assinatura inesperada em %s: %s', v_fn_name, v_fn_actual_sig);
            END IF;
            IF NOT v_fn_secdef THEN
                problems := problems || format('função %s não é SECURITY DEFINER', v_fn_name);
            END IF;
            IF NOT pg_has_role(v_fn_owner, v_table_owner, 'MEMBER')
               OR v_fn_owner IN (SELECT oid FROM pg_roles WHERE rolname IN ('rewit_app', 'rewit_media', 'rewit_mod')) THEN
                problems := problems || format('owner incorreto em função %s', v_fn_name);
            END IF;
            IF NOT (COALESCE(v_fn_config, ARRAY[]::TEXT[]) @> ARRAY['search_path=pg_catalog, public, pg_temp']) THEN
                problems := problems || format('função %s sem search_path fixo', v_fn_name);
            END IF;
            IF has_function_privilege('public', v_fn_oid, 'EXECUTE') THEN
                problems := problems || format('função %s com EXECUTE concedido a PUBLIC', v_fn_name);
            END IF;
            FOREACH v_role_name IN ARRAY ARRAY['rewit_app', 'rewit_media', 'rewit_mod'] LOOP
                IF v_role_name = v_fn_allowed_role THEN
                    IF NOT has_function_privilege(v_role_name, v_fn_oid, 'EXECUTE') THEN
                        problems := problems || format('função %s sem EXECUTE para %s', v_fn_name, v_role_name);
                    END IF;
                ELSE
                    IF has_function_privilege(v_role_name, v_fn_oid, 'EXECUTE') THEN
                        problems := problems || format('função %s com EXECUTE indevido para %s', v_fn_name, v_role_name);
                    END IF;
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    -- 9. A própria função de assertion não pode ser executada por PUBLIC, rewit_app, rewit_media nem rewit_mod
    IF has_function_privilege('public', 'public.rewit_assert_runtime_privileges()'::regprocedure, 'EXECUTE')
       OR has_function_privilege('rewit_app', 'public.rewit_assert_runtime_privileges()'::regprocedure, 'EXECUTE')
       OR has_function_privilege('rewit_media', 'public.rewit_assert_runtime_privileges()'::regprocedure, 'EXECUTE')
       OR has_function_privilege('rewit_mod', 'public.rewit_assert_runtime_privileges()'::regprocedure, 'EXECUTE') THEN
        problems := problems || 'rewit_assert_runtime_privileges() executável por roles não autorizadas'::TEXT;
    END IF;

    IF array_length(problems, 1) > 0 THEN
        RAISE EXCEPTION 'rewit_assert_runtime_privileges: violações detectadas: %', array_to_string(problems, '; ');
    END IF;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_assert_runtime_privileges() FROM PUBLIC, rewit_app, rewit_media, rewit_mod;

COMMENT ON FUNCTION public.rewit_assert_runtime_privileges() IS
    'Asserção global de privilégios mínimos e governança das roles de runtime (rewit_app, rewit_media, rewit_mod), '
    'trilhas de auditoria, guards, integridade de storage_object_quarantine e funções SECURITY DEFINER autorizadas (SEC-F2-04).';

-- 7. Execução imediata da asserção
DO $$
BEGIN
    PERFORM public.rewit_assert_runtime_privileges();
END
$$;
