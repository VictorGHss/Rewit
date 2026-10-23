-- ==============================================================================
-- REWIT DATABASE MIGRATION - V37
-- ==============================================================================
-- S3-A.13: privilégio mínimo da role de runtime e integridade da trilha de fotografias
-- 1. DELETE do runtime somente nas tabelas com exclusão física legítima (lista explícita); nenhuma
--    cascata a partir delas alcança outra tabela (usuários, avaliações, comentários, respostas, denúncias)
-- 2. Objetos de extensões (PostGIS: spatial_ref_sys e views) somente leitura para o runtime
-- 3. Privilégios padrão: tabelas criadas por migrations futuras não nascem graváveis pelo runtime
-- 4. place_media_moderation_audit_logs: fotografia, local e denúncia verificados na inserção
-- 5. place_media_reports: denúncias nascem pendentes; motivo e detalhe congelados após decisão final
-- 6. rewit_assert_runtime_privileges(): verificação reutilizável, executada ao final desta versão
-- Tabelas afetadas: place_media_moderation_audit_logs, place_media_reports (triggers);
--                   privilégios de rewit_app em todas as tabelas do schema public
-- ==============================================================================

-- 1. DELETE DO RUNTIME SOMENTE ONDE A APLICAÇÃO EXCLUI FISICAMENTE
-- Usuários, locais, alvos, mídias, avaliações, comentários, respostas e denúncias usam exclusão lógica; as
-- FKs em cascata a partir deles executam com o owner e só são acionadas por operação administrativa.
-- Origem de cada item: purge de contas (AccountPurgeRepositoryAdapter), limpeza de sessões e do outbox,
-- repositórios de seguidores, reações, ofertas, horários, comodidades e referências externas, quarentena
-- do GC de storage e as invalidações por troca de titular (triggers da V28/V29, executadas pelo runtime).
REVOKE DELETE, TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public FROM rewit_app;
GRANT DELETE ON
    auth_sessions,
    notifications,
    outbox_messages,
    place_amenity_configurations,
    place_amenity_selections,
    place_external_references,
    place_opening_hours_intervals,
    place_opening_hours_schedules,
    place_product_offers,
    review_reactions,
    saved_items,
    storage_object_quarantine,
    user_activities,
    user_follows,
    user_interests,
    user_reputation
    TO rewit_app;

-- 2. OBJETOS DE EXTENSÕES SOMENTE LEITURA
-- O GRANT amplo da V36 alcançou spatial_ref_sys: apagar o SRID 4326 derrubava toda consulta geográfica.
-- A estrutura da extensão não é alterada; apenas os privilégios concedidos ao runtime.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS rel
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
          AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
    LOOP
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON %s FROM rewit_app', r.rel);
    END LOOP;
END
$$;

-- 3. PRIVILÉGIOS PADRÃO SEM ESCRITA
-- Valem para os objetos criados pela role de migração. Uma tabela nova é legível pelo runtime, mas
-- INSERT, UPDATE e DELETE precisam ser concedidos explicitamente pela migration que a cria.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM rewit_app;
ALTER DEFAULT PRIVILEGES
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM rewit_app;

-- 4. TRILHA DE FOTOGRAFIAS COM REFERÊNCIAS VERIFICADAS NA INSERÇÃO
-- Como nas demais trilhas (V36), não há FK: a exclusão de mídia, local ou denúncia não alcança o histórico.
-- Na inserção, a fotografia precisa existir, o local gravado é o da fotografia e a denúncia citada
-- precisa ser da mesma fotografia (lock FOR KEY SHARE, como uma FK). Registros anteriores não são
-- revalidados nem alterados: referências históricas desconhecidas continuam como foram gravadas.
CREATE OR REPLACE FUNCTION fn_bind_place_media_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_place_id UUID;
BEGIN
    SELECT m.place_id INTO v_place_id
    FROM public.place_media m
    WHERE m.id = NEW.place_media_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: fotografia % inexistente', NEW.place_media_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NEW.place_id IS DISTINCT FROM v_place_id THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: local % não é o local da fotografia %', NEW.place_id, NEW.place_media_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.action IN ('ACCEPT_REPORT', 'REJECT_REPORT', 'AUTO_RESOLVE_REPORT') AND NEW.report_id IS NULL THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: a ação % exige a denúncia correspondente', NEW.action
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.report_id IS NOT NULL THEN
        PERFORM 1
        FROM public.place_media_reports r
        WHERE r.id = NEW.report_id AND r.place_media_id = NEW.place_media_id
        FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_media_moderation_audit_logs: denúncia % inexistente ou de outra fotografia', NEW.report_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_bind_place_media_audit_reference ON place_media_moderation_audit_logs;
CREATE TRIGGER trg_bind_place_media_audit_reference
    BEFORE INSERT ON place_media_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_bind_place_media_audit_reference();

-- 5. DENÚNCIAS NASCEM PENDENTES E A DECISÃO FINAL CONGELA TAMBÉM MOTIVO E DETALHE
-- Mesma função da V36, com duas regras a mais. Vale para qualquer role: uma carga histórica precisa
-- desabilitar o trigger explicitamente (ALTER TABLE ... DISABLE TRIGGER, só o owner).
CREATE OR REPLACE FUNCTION fn_guard_place_media_report_decision()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.is_legacy_decision THEN
            RAISE EXCEPTION 'place_media_reports: decisões legadas são exclusivas da migração histórica'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        -- A decisão só existe como transição de uma denúncia pendente (aceite, rejeição ou resolução automática)
        IF NEW.status IS DISTINCT FROM 'PENDING'
           OR NEW.decided_by_user_id IS NOT NULL
           OR NEW.decided_at IS NOT NULL
           OR NEW.decision_notes IS NOT NULL THEN
            RAISE EXCEPTION 'place_media_reports: denúncias nascem pendentes e sem decisão'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    ELSE
        IF NEW.is_legacy_decision IS DISTINCT FROM OLD.is_legacy_decision THEN
            RAISE EXCEPTION 'place_media_reports: is_legacy_decision não pode ser alterado'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF OLD.is_legacy_decision
           AND (NEW.status IS DISTINCT FROM OLD.status
                OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
            RAISE EXCEPTION 'place_media_reports: decisões legadas são imutáveis'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        -- Decisão final (aceite, rejeição ou resolução automática) não é reaberta nem reescrita, nem o que foi
        -- denunciado: um novo ciclo de moderação é uma nova denúncia. Updates sem mudança efetiva passam.
        IF OLD.status IN ('ACCEPTED', 'REJECTED', 'AUTO_RESOLVED')
           AND (NEW.status IS DISTINCT FROM OLD.status
                OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_notes IS DISTINCT FROM OLD.decision_notes
                OR NEW.reason IS DISTINCT FROM OLD.reason
                OR NEW.detail IS DISTINCT FROM OLD.detail) THEN
            RAISE EXCEPTION 'place_media_reports: a decisão % é final e não pode ser alterada', OLD.status
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;

    -- Decisão humana nova (ou troca de decisor) exige moderador/administrador existente
    IF NOT NEW.is_legacy_decision
       AND NEW.status IN ('ACCEPTED', 'REJECTED')
       AND (TG_OP = 'INSERT'
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id) THEN
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.decided_by_user_id AND u.role IN ('MODERATOR', 'ADMIN');
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_media_reports: decisão % exige moderador válido', NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

-- 6. VERIFICAÇÃO REUTILIZÁVEL DOS PRIVILÉGIOS DO RUNTIME
-- Fonte única da lista de DELETE permitido. Migrations futuras que mudem privilégios ou criem tabelas
-- devem terminar com PERFORM rewit_assert_runtime_privileges(); a verificação lê apenas o catálogo.
CREATE OR REPLACE FUNCTION rewit_assert_runtime_privileges()
RETURNS VOID AS $$
DECLARE
    deletable TEXT[] := ARRAY[
        'auth_sessions', 'notifications', 'outbox_messages', 'place_amenity_configurations',
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
        ['users', 'trg_guard_user_role_assignment']
    ];
    problems TEXT[] := ARRAY[]::TEXT[];
    v_list TEXT;
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

    IF array_length(problems, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Privilégios do runtime fora da política: %', array_to_string(problems, '; ')
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$ LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public, pg_temp;

-- Ferramenta da migração e dos testes administrativos, não do runtime
REVOKE EXECUTE ON FUNCTION rewit_assert_runtime_privileges() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION rewit_assert_runtime_privileges() FROM rewit_app;

COMMENT ON FUNCTION rewit_assert_runtime_privileges() IS
    'Aborta se rewit_app tiver privilégios fora da política (V37): DELETE fora da lista, escrita em auditoria, '
    'extensões ou flyway_schema_history, cascatas acionáveis, privilégios padrão de escrita ou guards ausentes.';

-- 7. VERIFICAÇÃO FINAL
DO $$
BEGIN
    PERFORM rewit_assert_runtime_privileges();
END
$$;
