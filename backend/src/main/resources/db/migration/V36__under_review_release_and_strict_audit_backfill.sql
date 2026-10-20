-- ==============================================================================
-- REWIT DATABASE MIGRATION - V36
-- ==============================================================================
-- S3-A.8 / S3-A.10 / S3-A.11 (Versão Canônica):
-- 1. Suporte à ação RELEASE_REVIEW na trilha de auditoria de fotografias
-- 2. Suporte ao status AUTO_RESOLVED em place_media_reports (AU-1/AU-2)
-- 3. Reclassificação de decisões históricas por evidência e distinção de decisões
--    legadas (is_legacy_decision) sem inventar moderador (AU-3 / LEG-2)
-- 4. Backfill conservador de removal_source por evidência causal (V36-2)
-- 5. Desacoplamento das 4 trilhas de auditoria das entidades operacionais:
--    nenhuma exclusão (direta ou em cascata) alcança o histórico (AO-2)
-- 6. is_legacy_decision é condição histórica fixada pela migração, inacessível ao
--    runtime; decisões novas exigem moderador válido (LEG-1)
-- 7. Privilégio mínimo da role rewit_app sem credencial versionada (SEC-1, AO-1)
-- Tabelas afetadas: place_media_moderation_audit_logs, place_media_reports, place_media,
--                   moderation_audit_logs, discussion_moderation_audit_logs,
--                   business_response_moderation_audit_logs, flyway_schema_history
-- ==============================================================================

-- 1. SUPORTE À AÇÃO 'RELEASE_REVIEW' NA TRILHA DE AUDITORIA
ALTER TABLE place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_moderation_audit_action;

ALTER TABLE place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_moderation_audit_action
    CHECK (action IN (
        'REMOVE',
        'RESTORE',
        'ACCEPT_REPORT',
        'REJECT_REPORT',
        'AUTHOR_REMOVE',
        'BUSINESS_OWNER_REMOVE',
        'PLACE_MANAGER_REMOVE',
        'AUTO_QUARANTINE',
        'AUTO_RESOLVE_REPORT',
        'RELEASE_REVIEW'
    ));

-- 2. SUPORTE AO STATUS 'AUTO_RESOLVED' EM PLACE_MEDIA_REPORTS
ALTER TABLE place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_status;

ALTER TABLE place_media_reports
    ADD CONSTRAINT chk_place_media_reports_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_RESOLVED'));

-- 3. DECISÕES HISTÓRICAS: RECLASSIFICAÇÃO POR EVIDÊNCIA E DECISÕES LEGADAS (AU-3 / LEG-2)
-- A trilha de auditoria é a única evidência; ela é lida e nunca alterada.
ALTER TABLE place_media_reports
    ADD COLUMN IF NOT EXISTS is_legacy_decision BOOLEAN NOT NULL DEFAULT FALSE;

-- a) AUTO_RESOLVED somente com evidência inequívoca: evento AUTO_RESOLVE_REPORT do SYSTEM
--    vinculado ao report_id e nenhuma decisão humana (ACCEPT/REJECT) registrada para a mesma denúncia.
--    O decisor registrado é descartado porque uma resolução automática não tem decisor humano.
UPDATE place_media_reports r
SET status = 'AUTO_RESOLVED',
    decided_by_user_id = NULL,
    is_legacy_decision = FALSE
WHERE EXISTS (
          SELECT 1 FROM place_media_moderation_audit_logs al
          WHERE al.report_id = r.id
            AND al.action = 'AUTO_RESOLVE_REPORT'
            AND al.actor_type = 'SYSTEM'
      )
  AND NOT EXISTS (
          SELECT 1 FROM place_media_moderation_audit_logs al
          WHERE al.report_id = r.id
            AND al.action IN ('ACCEPT_REPORT', 'REJECT_REPORT')
      );

-- b) Decisão humana validada: o decisor registrado coincide com o ator USER do evento
--    ACCEPT_REPORT/REJECT_REPORT correspondente ao status da própria denúncia, e não há evento
--    automático contraditório para ela. Qualquer outra decisão ACCEPTED/REJECTED é histórica sem
--    moderador verificável: é marcada como legada e o decisor eventualmente registrado é preservado
--    como dado não verificado (não é inventado nem apagado).
UPDATE place_media_reports r
SET is_legacy_decision = NOT (
        EXISTS (
            SELECT 1 FROM place_media_moderation_audit_logs al
            WHERE al.report_id = r.id
              AND al.place_media_id = r.place_media_id
              AND al.actor_type = 'USER'
              AND al.actor_user_id = r.decided_by_user_id
              AND al.action = CASE r.status WHEN 'ACCEPTED' THEN 'ACCEPT_REPORT' ELSE 'REJECT_REPORT' END
        )
        AND NOT EXISTS (
            SELECT 1 FROM place_media_moderation_audit_logs al
            WHERE al.report_id = r.id
              AND al.action = 'AUTO_RESOLVE_REPORT'
        )
    )
WHERE r.status IN ('ACCEPTED', 'REJECTED');

ALTER TABLE place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_decided_by;

ALTER TABLE place_media_reports
    ADD CONSTRAINT chk_place_media_reports_decided_by
    CHECK (
        (status IN ('PENDING', 'AUTO_RESOLVED') AND decided_by_user_id IS NULL AND is_legacy_decision = FALSE)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND is_legacy_decision = FALSE AND decided_by_user_id IS NOT NULL)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND is_legacy_decision = TRUE)
    );

COMMENT ON COLUMN place_media_reports.is_legacy_decision IS
    'Decisão histórica sem moderador verificável na trilha de auditoria. Fixada exclusivamente pela migração V36; '
    'o runtime não pode gravar nem alterar esta coluna (LEG-1). decided_by_user_id, quando presente, não é verificado.';

-- 4. BACKFILL CONSERVADOR DE REMOVAL_SOURCE POR EVIDÊNCIA CAUSAL (V36-2)
-- Para cada fotografia REMOVED, a origem é a do evento que comprovadamente provocou a remoção atual.
-- Regras (ausência de evidência inequívoca => LEGACY):
--   * Ciclo atual: somente eventos a partir da última restauração/liberação (RESTORE, RELEASE_REVIEW).
--     Eventos anteriores não determinam a origem de um ciclo posterior; empate com a restauração é ambíguo.
--   * Candidatos: remoções diretas e ACCEPT_REPORT. AUTO_RESOLVE_REPORT, AUTO_QUARANTINE e REJECT_REPORT
--     não demonstram remoção humana.
--   * A causa é o PRIMEIRO candidato do ciclo; remoções posteriores são no-ops no serviço e não sobrescrevem.
--     Se mais de um candidato tem o menor timestamp, não há sequência confiável: o UUID aleatório nunca desempata.
--   * removed_at precisa ser coerente com o evento: posterior à restauração e não posterior ao evento
--     (o serviço grava removed_at antes do evento na mesma transação).
--   * ACCEPT_REPORT só comprova remoção quando a mídia ainda não estava removida no aceite: a denúncia
--     vinculada está ACCEPTED e seu decided_at (gravado antes da remoção) não é posterior a removed_at.
--     Uma remoção não auditada anterior ao aceite deixa removed_at < decided_at e cai em LEGACY.
--   * REMOVE (ação genérica de versões anteriores) só é atribuído quando o papel do ator é inequívoco.
WITH last_restore AS (
    SELECT place_media_id, MAX(created_at) AS restored_at
    FROM place_media_moderation_audit_logs
    WHERE action IN ('RESTORE', 'RELEASE_REVIEW')
    GROUP BY place_media_id
),
cycle_candidates AS (
    SELECT al.place_media_id,
           al.action,
           al.actor_user_id,
           al.actor_type,
           al.report_id,
           al.created_at,
           lr.restored_at
    FROM place_media_moderation_audit_logs al
    JOIN place_media pm ON pm.id = al.place_media_id AND pm.status = 'REMOVED'
    LEFT JOIN last_restore lr ON lr.place_media_id = al.place_media_id
    WHERE al.action IN ('REMOVE', 'ACCEPT_REPORT', 'AUTHOR_REMOVE', 'PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE')
      AND (lr.restored_at IS NULL OR al.created_at >= lr.restored_at)
),
first_at AS (
    SELECT place_media_id,
           MIN(created_at) AS first_created_at,
           bool_or(restored_at IS NOT NULL AND created_at = restored_at) AS ties_restore
    FROM cycle_candidates
    GROUP BY place_media_id
),
first_event AS (
    SELECT c.place_media_id,
           COUNT(*) AS candidates_at_first,
           bool_or(f.ties_restore) AS ties_restore,
           MAX(c.restored_at) AS restored_at,
           MAX(c.created_at) AS created_at,
           (array_agg(c.action))[1] AS action,
           (array_agg(c.actor_user_id))[1] AS actor_user_id,
           (array_agg(c.actor_type))[1] AS actor_type,
           (array_agg(c.report_id))[1] AS report_id
    FROM cycle_candidates c
    JOIN first_at f ON f.place_media_id = c.place_media_id AND c.created_at = f.first_created_at
    GROUP BY c.place_media_id
),
classified AS (
    SELECT pm.id AS media_id,
           CASE
               WHEN fe.place_media_id IS NULL THEN 'LEGACY'
               WHEN fe.candidates_at_first <> 1 OR fe.ties_restore THEN 'LEGACY'
               WHEN pm.removed_at IS NULL
                    OR pm.removed_at > fe.created_at
                    OR (fe.restored_at IS NOT NULL AND pm.removed_at <= fe.restored_at) THEN 'LEGACY'
               WHEN fe.actor_type <> 'USER' OR fe.actor_user_id IS NULL THEN 'LEGACY'
               WHEN fe.action = 'AUTHOR_REMOVE' THEN
                   CASE WHEN fe.actor_user_id = pm.user_id THEN 'AUTHOR' ELSE 'LEGACY' END
               WHEN fe.action = 'PLACE_MANAGER_REMOVE' THEN 'PLACE_MANAGER'
               WHEN fe.action = 'BUSINESS_OWNER_REMOVE' THEN 'BUSINESS_OWNER'
               WHEN fe.action = 'REMOVE' THEN
                   CASE
                       WHEN COALESCE(actor.role IN ('MODERATOR', 'ADMIN'), FALSE)
                            AND fe.actor_user_id <> pm.user_id
                            AND fe.actor_user_id IS DISTINCT FROM p.created_by_user_id THEN 'MODERATOR'
                       WHEN NOT COALESCE(actor.role IN ('MODERATOR', 'ADMIN'), FALSE)
                            AND fe.actor_user_id = pm.user_id THEN 'AUTHOR'
                       WHEN NOT COALESCE(actor.role IN ('MODERATOR', 'ADMIN'), FALSE)
                            AND fe.actor_user_id = p.created_by_user_id THEN 'PLACE_MANAGER'
                       ELSE 'LEGACY'
                   END
               WHEN fe.action = 'ACCEPT_REPORT' THEN
                   CASE WHEN EXISTS (
                            SELECT 1 FROM place_media_reports r
                            WHERE r.id = fe.report_id
                              AND r.place_media_id = pm.id
                              AND r.status = 'ACCEPTED'
                              AND r.decided_at IS NOT NULL
                              AND r.decided_at <= pm.removed_at
                        ) THEN 'MODERATOR'
                        ELSE 'LEGACY'
                   END
               ELSE 'LEGACY'
           END AS removal_source
    FROM place_media pm
    LEFT JOIN first_event fe ON fe.place_media_id = pm.id
    LEFT JOIN places p ON p.id = pm.place_id
    LEFT JOIN users actor ON actor.id = fe.actor_user_id
    WHERE pm.status = 'REMOVED'
)
UPDATE place_media pm
SET removal_source = c.removal_source
FROM classified c
WHERE pm.id = c.media_id;

-- 5. TRILHAS DE AUDITORIA DESACOPLADAS E APPEND-ONLY (AO-2)
-- O histórico não depende da existência da entidade operacional: as FKs com ON DELETE CASCADE eram
-- incompatíveis com a retenção (apagar avaliação, comentário ou resposta apagava a auditoria). Os
-- identificadores são preservados como referência histórica, validados na inserção (com o mesmo lock
-- FOR KEY SHARE de uma FK) e acompanhados de snapshot do contexto necessário à investigação. A FK do
-- moderador para users permanece RESTRICT: usuários nunca são apagados fisicamente.

-- a) moderation_audit_logs (V11): review_id deixa de cascatear
ALTER TABLE moderation_audit_logs DROP CONSTRAINT IF EXISTS moderation_audit_logs_review_id_fkey;

CREATE OR REPLACE FUNCTION fn_bind_moderation_audit_reference()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM reviews WHERE id = NEW.review_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'moderation_audit_logs: avaliação % inexistente', NEW.review_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_bind_moderation_audit_reference ON moderation_audit_logs;
CREATE TRIGGER trg_bind_moderation_audit_reference
    BEFORE INSERT ON moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_bind_moderation_audit_reference();

CREATE OR REPLACE FUNCTION fn_prevent_moderation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'moderation_audit_logs é append-only: UPDATE e DELETE não são permitidos'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_update ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_update
    BEFORE UPDATE ON moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_delete ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_delete
    BEFORE DELETE ON moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_moderation_audit_mutation();

CREATE OR REPLACE FUNCTION fn_prevent_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_moderation_audit_truncate ON moderation_audit_logs;
CREATE TRIGGER trg_prevent_moderation_audit_truncate
    BEFORE TRUNCATE ON moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_moderation_audit_truncate();

-- b) discussion_moderation_audit_logs (V20): discussion_id deixa de cascatear; snapshot da avaliação.
-- Linhas anteriores a esta versão ficam com review_id nulo: preencher exigiria UPDATE no histórico.
ALTER TABLE discussion_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS discussion_moderation_audit_logs_discussion_id_fkey;
ALTER TABLE discussion_moderation_audit_logs ADD COLUMN IF NOT EXISTS review_id UUID;

CREATE OR REPLACE FUNCTION fn_bind_discussion_moderation_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_review_id UUID;
BEGIN
    SELECT review_id INTO v_review_id FROM review_discussions WHERE id = NEW.discussion_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'discussion_moderation_audit_logs: comentário % inexistente', NEW.discussion_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    -- Snapshot sempre derivado da entidade, nunca do valor informado pelo chamador
    NEW.review_id := v_review_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_bind_discussion_moderation_audit_reference ON discussion_moderation_audit_logs;
CREATE TRIGGER trg_bind_discussion_moderation_audit_reference
    BEFORE INSERT ON discussion_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_bind_discussion_moderation_audit_reference();

CREATE OR REPLACE FUNCTION fn_prevent_discussion_moderation_audit_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'discussion_moderation_audit_logs é append-only: UPDATE e DELETE não são permitidos'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_discussion_moderation_audit_delete ON discussion_moderation_audit_logs;
CREATE TRIGGER trg_prevent_discussion_moderation_audit_delete
    BEFORE DELETE ON discussion_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_discussion_moderation_audit_update();

CREATE OR REPLACE FUNCTION fn_prevent_discussion_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'discussion_moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_discussion_moderation_audit_truncate ON discussion_moderation_audit_logs;
CREATE TRIGGER trg_prevent_discussion_moderation_audit_truncate
    BEFORE TRUNCATE ON discussion_moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_discussion_moderation_audit_truncate();

COMMENT ON COLUMN discussion_moderation_audit_logs.review_id IS
    'Snapshot da avaliação do comentário no momento da decisão (V36); nulo apenas em registros anteriores.';

-- c) business_response_moderation_audit_logs (V24): response_id deixa de cascatear; snapshot do contexto.
ALTER TABLE business_response_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS business_response_moderation_audit_logs_response_id_fkey;
ALTER TABLE business_response_moderation_audit_logs ADD COLUMN IF NOT EXISTS review_id UUID;
ALTER TABLE business_response_moderation_audit_logs ADD COLUMN IF NOT EXISTS business_account_id UUID;

CREATE OR REPLACE FUNCTION fn_bind_business_response_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_review_id UUID;
    v_business_account_id UUID;
BEGIN
    SELECT review_id, business_account_id INTO v_review_id, v_business_account_id
    FROM business_responses WHERE id = NEW.response_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'business_response_moderation_audit_logs: resposta % inexistente', NEW.response_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    NEW.review_id := v_review_id;
    NEW.business_account_id := v_business_account_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_bind_business_response_audit_reference ON business_response_moderation_audit_logs;
CREATE TRIGGER trg_bind_business_response_audit_reference
    BEFORE INSERT ON business_response_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_bind_business_response_audit_reference();

CREATE OR REPLACE FUNCTION fn_prevent_business_response_audit_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'business_response_moderation_audit_logs é append-only: UPDATE e DELETE não são permitidos'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_business_response_audit_delete ON business_response_moderation_audit_logs;
CREATE TRIGGER trg_prevent_business_response_audit_delete
    BEFORE DELETE ON business_response_moderation_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_business_response_audit_update();

CREATE OR REPLACE FUNCTION fn_prevent_business_response_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'business_response_moderation_audit_logs é append-only: TRUNCATE não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_business_response_audit_truncate ON business_response_moderation_audit_logs;
CREATE TRIGGER trg_prevent_business_response_audit_truncate
    BEFORE TRUNCATE ON business_response_moderation_audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_prevent_business_response_audit_truncate();

COMMENT ON COLUMN business_response_moderation_audit_logs.review_id IS
    'Snapshot da avaliação da resposta no momento da decisão (V36); nulo apenas em registros anteriores.';
COMMENT ON COLUMN business_response_moderation_audit_logs.business_account_id IS
    'Snapshot da conta comercial da resposta no momento da decisão (V36); nulo apenas em registros anteriores.';

-- d) place_media_moderation_audit_logs (V32-V35) já nasceu desacoplada (sem FK para place_media/places)
--    e com guards de UPDATE, DELETE e TRUNCATE.

-- 6. DECISÕES DE DENÚNCIAS: is_legacy_decision FORA DO ALCANCE DO RUNTIME (LEG-1)
-- Defesa em profundidade: (1) privilégio por coluna (seção 7) e (2) trigger, que vale para qualquer role.
-- Uma migração futura que precise corrigir decisões legadas deve desabilitar o trigger explicitamente
-- (ALTER TABLE ... DISABLE TRIGGER), o que exige ser owner da tabela — a role de runtime não é.
CREATE OR REPLACE FUNCTION fn_guard_place_media_report_decision()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.is_legacy_decision THEN
            RAISE EXCEPTION 'place_media_reports: decisões legadas são exclusivas da migração histórica'
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
    END IF;

    -- Decisão humana nova (ou troca de decisor) exige moderador/administrador existente
    IF NOT NEW.is_legacy_decision
       AND NEW.status IN ('ACCEPTED', 'REJECTED')
       AND (TG_OP = 'INSERT'
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id) THEN
        PERFORM 1 FROM users u
        WHERE u.id = NEW.decided_by_user_id AND u.role IN ('MODERATOR', 'ADMIN');
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_media_reports: decisão % exige moderador válido', NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_place_media_report_decision ON place_media_reports;
CREATE TRIGGER trg_guard_place_media_report_decision
    BEFORE INSERT OR UPDATE ON place_media_reports
    FOR EACH ROW
    EXECUTE FUNCTION fn_guard_place_media_report_decision();

-- 7. PRIVILÉGIO MÍNIMO DA ROLE DE APLICAÇÃO (SEC-1, AO-1, AO-2, LEG-1)
-- Login e senha da role são provisionados fora das migrations versionadas
-- (infrastructure/postgres). Se a role ainda não existir, nasce sem login e sem privilégios elevados.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rewit_app') THEN
        CREATE ROLE rewit_app WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rewit_app', current_database());
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'GRANT CONNECT não aplicado: a role de migração não administra o database %', current_database();
END
$$;

GRANT USAGE ON SCHEMA public TO rewit_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rewit_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rewit_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO rewit_app;

-- Trilhas de auditoria: runtime só pode SELECT e INSERT
REVOKE UPDATE, DELETE, TRUNCATE ON moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON discussion_moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON business_response_moderation_audit_logs FROM rewit_app;
REVOKE UPDATE, DELETE, TRUNCATE ON place_media_moderation_audit_logs FROM rewit_app;

-- Denúncias de fotografias: o privilégio de tabela cobriria todas as colunas, então é substituído por
-- privilégios de coluna que excluem is_legacy_decision (o mapeamento JPA também não a grava).
REVOKE INSERT, UPDATE ON place_media_reports FROM rewit_app;
GRANT INSERT (id, place_media_id, reporter_user_id, reason, detail, status, created_at, updated_at,
              decided_by_user_id, decided_at, decision_notes)
    ON place_media_reports TO rewit_app;
GRANT UPDATE (reason, detail, status, updated_at, decided_by_user_id, decided_at, decision_notes)
    ON place_media_reports TO rewit_app;

-- Tabela de controle de migrations do Flyway: rewit_app NÃO pode modificar nem apagar
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history') THEN
        REVOKE ALL ON flyway_schema_history FROM rewit_app;
        GRANT SELECT ON flyway_schema_history TO rewit_app;
    END IF;
END
$$;

-- Privilégios default para tabelas criadas futuramente pelo Flyway
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO rewit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO rewit_app;

-- 8. VERIFICAÇÃO FINAL: aborta a migração se alguma garantia não estiver em vigor
DO $$
DECLARE
    audit_tables TEXT[] := ARRAY[
        'moderation_audit_logs',
        'discussion_moderation_audit_logs',
        'business_response_moderation_audit_logs',
        'place_media_moderation_audit_logs'
    ];
    t TEXT;
    offending TEXT;
BEGIN
    -- Nenhuma FK das trilhas pode depender de entidade operacional apagável, nem cascatear/anular
    SELECT string_agg(format('%s.%s -> %s (%s)', c.conrelid::regclass, c.conname, c.confrelid::regclass, c.confdeltype), ', ')
    INTO offending
    FROM pg_constraint c
    WHERE c.contype = 'f'
      AND c.conrelid::regclass::text = ANY (audit_tables)
      AND (c.confrelid <> 'users'::regclass OR c.confdeltype NOT IN ('r', 'a'));
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'V36: FKs incompatíveis com retenção de auditoria: %', offending;
    END IF;

    FOREACH t IN ARRAY audit_tables LOOP
        IF has_table_privilege('rewit_app', t, 'UPDATE')
           OR has_table_privilege('rewit_app', t, 'DELETE')
           OR has_table_privilege('rewit_app', t, 'TRUNCATE') THEN
            RAISE EXCEPTION 'V36: rewit_app mantém privilégio de mutação em %', t;
        END IF;
        -- Guards de UPDATE, DELETE e TRUNCATE ativos (tgtype: 8 = DELETE, 16 = UPDATE, 32 = TRUNCATE)
        IF (SELECT COUNT(DISTINCT op) FROM (
                SELECT CASE WHEN (tg.tgtype & 8) <> 0 THEN 'D' END AS op FROM pg_trigger tg
                WHERE tg.tgrelid = t::regclass AND NOT tg.tgisinternal AND tg.tgenabled <> 'D'
                UNION ALL
                SELECT CASE WHEN (tg.tgtype & 16) <> 0 THEN 'U' END FROM pg_trigger tg
                WHERE tg.tgrelid = t::regclass AND NOT tg.tgisinternal AND tg.tgenabled <> 'D'
                UNION ALL
                SELECT CASE WHEN (tg.tgtype & 32) <> 0 THEN 'T' END FROM pg_trigger tg
                WHERE tg.tgrelid = t::regclass AND NOT tg.tgisinternal AND tg.tgenabled <> 'D'
            ) ops WHERE op IS NOT NULL) <> 3 THEN
            RAISE EXCEPTION 'V36: guards append-only incompletos em %', t;
        END IF;
    END LOOP;

    IF has_column_privilege('rewit_app', 'place_media_reports', 'is_legacy_decision', 'UPDATE')
       OR has_column_privilege('rewit_app', 'place_media_reports', 'is_legacy_decision', 'INSERT') THEN
        RAISE EXCEPTION 'V36: rewit_app ainda pode gravar place_media_reports.is_legacy_decision';
    END IF;
END
$$;
