-- ==============================================================================
-- REWIT DATABASE MIGRATION - V45
-- ==============================================================================
-- Hardening de Moderação e Auditoria F2 (S3-A13-F2 / SEC-F2-03):
-- Arquitetura Híbrida B + D:
--   B) Separação de identidades PostgreSQL:
--        * rewit_app   (runtime público comum; sem escrita em auditorias nem transições de place_media)
--        * rewit_media (ciclo de vida público/automático de mídias de locais e seus 5 eventos auditados)
--        * rewit_mod   (exclusivo dos 6 módulos administrativos de moderação)
--   D) Autenticidade criptográfica externa (authenticity_mac) e vínculo transacional
--      bidirecional entre decisões/transições e registros de auditoria.
--
-- Tabelas afetadas (16 do inventário + 2 novas trilhas de auditoria):
--   Auditorias existentes: moderation_audit_logs, discussion_moderation_audit_logs,
--                          business_response_moderation_audit_logs,
--                          place_media_moderation_audit_logs
--   Auditorias novas:      account_moderation_audit_logs,
--                          place_claim_moderation_audit_logs
--   Denúncias/Claims:      place_media_reports, review_reports,
--                          discussion_reports, place_claim_requests
--   Entidades/Governança:  reviews, review_discussions, business_responses,
--                          place_media, review_media, places,
--                          business_accounts, users
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. ROLES DEDICADAS DE RUNTIME (rewit_mod e rewit_media) SEM SENHA VERSIONADA
-- ------------------------------------------------------------------------------
-- Login e senha são provisionados externamente (infrastructure/postgres + MOD_DB_PASSWORD / MEDIA_DB_PASSWORD).
-- Em instalação limpa sem provisionamento prévio, as roles nascem NOLOGIN e sem privilégios.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_mod') THEN
        CREATE ROLE rewit_mod WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_media') THEN
        CREATE ROLE rewit_media WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rewit_mod', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rewit_media', current_database());
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'GRANT CONNECT para rewit_mod/rewit_media não aplicado: role de migração não administra o database %', current_database();
END
$$;

GRANT USAGE ON SCHEMA public TO rewit_mod;
GRANT USAGE ON SCHEMA public TO rewit_media;

-- Privilégios padrão de rewit_mod e rewit_media: tabelas/funções futuras nunca nascem graváveis
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON TABLES FROM rewit_mod;
ALTER DEFAULT PRIVILEGES
    REVOKE ALL ON TABLES FROM rewit_mod;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM rewit_mod;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON FUNCTIONS FROM rewit_mod;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON TABLES FROM rewit_media;
ALTER DEFAULT PRIVILEGES
    REVOKE ALL ON TABLES FROM rewit_media;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM rewit_media;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON FUNCTIONS FROM rewit_media;

-- Garante ausência de membership cruzada entre rewit_app, rewit_media e rewit_mod (sem SET ROLE)
DO $$
DECLARE
    r1 TEXT;
    r2 TEXT;
    runtime_roles TEXT[] := ARRAY['rewit_app', 'rewit_media', 'rewit_mod'];
BEGIN
    FOREACH r1 IN ARRAY runtime_roles LOOP
        FOREACH r2 IN ARRAY runtime_roles LOOP
            IF r1 <> r2
               AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r1)
               AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r2)
               AND pg_catalog.pg_has_role(r1, r2, 'MEMBER') THEN
                EXECUTE format('REVOKE %I FROM %I', r2, r1);
            END IF;
        END LOOP;
    END LOOP;
END
$$;


-- ------------------------------------------------------------------------------
-- 2. COLUNAS DE AUTENTICIDADE CRIPTOGRÁFICA (MAC) E CORRELAÇÃO TRANSACIONAL
-- ------------------------------------------------------------------------------
-- Registros históricos anteriores à V45 permanecem intactos com authenticity_mac = NULL
-- (explicitamente tratados pela aplicação como históricos não autenticados).
-- Novas inserções administrativas exigem formato canônico aud1$<keyId>$<64-hex>.

-- 2a. Trilhas de auditoria existentes
ALTER TABLE public.moderation_audit_logs
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS txid BIGINT;

ALTER TABLE public.moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_moderation_audit_mac_format;
ALTER TABLE public.moderation_audit_logs
    ADD CONSTRAINT chk_moderation_audit_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_moderation_audit_review_txid
    ON public.moderation_audit_logs (review_id, txid)
    WHERE txid IS NOT NULL;

ALTER TABLE public.discussion_moderation_audit_logs
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS txid BIGINT;

ALTER TABLE public.discussion_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_discussion_mod_audit_mac_format;
ALTER TABLE public.discussion_moderation_audit_logs
    ADD CONSTRAINT chk_discussion_mod_audit_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_discussion_mod_audit_txid
    ON public.discussion_moderation_audit_logs (discussion_id, txid)
    WHERE txid IS NOT NULL;

ALTER TABLE public.business_response_moderation_audit_logs
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS txid BIGINT;

ALTER TABLE public.business_response_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_business_resp_mod_audit_mac_format;
ALTER TABLE public.business_response_moderation_audit_logs
    ADD CONSTRAINT chk_business_resp_mod_audit_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_resp_mod_audit_txid
    ON public.business_response_moderation_audit_logs (response_id, txid)
    WHERE txid IS NOT NULL;

ALTER TABLE public.place_media_moderation_audit_logs
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS txid BIGINT;

ALTER TABLE public.place_media_moderation_audit_logs
    DROP CONSTRAINT IF EXISTS chk_place_media_mod_audit_mac_format;
ALTER TABLE public.place_media_moderation_audit_logs
    ADD CONSTRAINT chk_place_media_mod_audit_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_audit_report_txid
    ON public.place_media_moderation_audit_logs (report_id, action, txid)
    WHERE report_id IS NOT NULL AND txid IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_place_media_audit_media_txid
    ON public.place_media_moderation_audit_logs (place_media_id, action, txid)
    WHERE report_id IS NULL AND txid IS NOT NULL;

-- 2b. Decisões persistidas diretamente em place_media_reports e place_claim_requests
ALTER TABLE public.place_media_reports
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS decision_txid BIGINT;

ALTER TABLE public.place_media_reports
    DROP CONSTRAINT IF EXISTS chk_place_media_reports_mac_format;
ALTER TABLE public.place_media_reports
    ADD CONSTRAINT chk_place_media_reports_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

ALTER TABLE public.place_claim_requests
    ADD COLUMN IF NOT EXISTS authenticity_mac VARCHAR(128),
    ADD COLUMN IF NOT EXISTS decision_txid BIGINT;

ALTER TABLE public.place_claim_requests
    DROP CONSTRAINT IF EXISTS chk_place_claim_requests_mac_format;
ALTER TABLE public.place_claim_requests
    ADD CONSTRAINT chk_place_claim_requests_mac_format
    CHECK (authenticity_mac IS NULL OR authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$');

-- 2c. Colunas internas controladas por trigger nas entidades moderáveis
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS last_moderation_txid BIGINT,
    ADD COLUMN IF NOT EXISTS last_moderation_prev_status VARCHAR(16);

ALTER TABLE public.reviews
    ADD COLUMN IF NOT EXISTS removed_by VARCHAR(16),
    ADD COLUMN IF NOT EXISTS last_moderation_txid BIGINT,
    ADD COLUMN IF NOT EXISTS last_moderation_prev_status VARCHAR(32);

ALTER TABLE public.reviews
    DROP CONSTRAINT IF EXISTS chk_reviews_removed_by;
ALTER TABLE public.reviews
    ADD CONSTRAINT chk_reviews_removed_by
    CHECK (removed_by IS NULL OR removed_by IN ('AUTHOR', 'MODERATION'));

ALTER TABLE public.review_discussions
    ADD COLUMN IF NOT EXISTS removed_by VARCHAR(16),
    ADD COLUMN IF NOT EXISTS last_moderation_txid BIGINT,
    ADD COLUMN IF NOT EXISTS last_moderation_prev_status VARCHAR(32);

ALTER TABLE public.review_discussions
    DROP CONSTRAINT IF EXISTS chk_review_discussions_removed_by;
ALTER TABLE public.review_discussions
    ADD CONSTRAINT chk_review_discussions_removed_by
    CHECK (removed_by IS NULL OR removed_by IN ('AUTHOR', 'MODERATION'));

ALTER TABLE public.review_media
    ADD COLUMN IF NOT EXISTS removed_by VARCHAR(16);

ALTER TABLE public.review_media
    DROP CONSTRAINT IF EXISTS chk_review_media_removed_by;
ALTER TABLE public.review_media
    ADD CONSTRAINT chk_review_media_removed_by
    CHECK (removed_by IS NULL OR removed_by IN ('AUTHOR', 'MODERATION'));

ALTER TABLE public.business_responses
    ADD COLUMN IF NOT EXISTS last_moderation_txid BIGINT,
    ADD COLUMN IF NOT EXISTS last_moderation_prev_status VARCHAR(16);

ALTER TABLE public.place_media
    ADD COLUMN IF NOT EXISTS last_moderation_txid BIGINT,
    ADD COLUMN IF NOT EXISTS last_moderation_prev_status VARCHAR(20);


-- ------------------------------------------------------------------------------
-- 3. NOVAS TRILHAS APPEND-ONLY: ACCOUNT E PLACE CLAIM MODERATION AUDIT LOGS
-- ------------------------------------------------------------------------------

-- 3a. Trilha de auditoria do ciclo de vida administrativo de contas
CREATE TABLE IF NOT EXISTS public.account_moderation_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_user_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    authenticity_mac VARCHAR(128) NOT NULL,
    txid BIGINT,
    CONSTRAINT chk_account_mod_audit_action CHECK (action IN ('SUSPEND', 'REINSTATE', 'DELETE')),
    CONSTRAINT chk_account_mod_audit_prev_status CHECK (previous_status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED', 'DELETED')),
    CONSTRAINT chk_account_mod_audit_new_status CHECK (new_status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED', 'DELETED')),
    CONSTRAINT chk_account_mod_audit_transition CHECK (
        (action = 'SUSPEND' AND previous_status IN ('ACTIVE', 'DEACTIVATED') AND new_status = 'SUSPENDED')
        OR (action = 'REINSTATE' AND previous_status = 'SUSPENDED' AND new_status = 'ACTIVE')
        OR (action = 'DELETE' AND previous_status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED') AND new_status = 'DELETED')
    ),
    CONSTRAINT chk_account_mod_audit_no_self CHECK (target_user_id <> actor_user_id),
    CONSTRAINT chk_account_mod_audit_reason CHECK (length(btrim(reason)) > 0 AND char_length(reason) <= 1000),
    CONSTRAINT chk_account_mod_audit_mac_format CHECK (authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_account_mod_audit_target
    ON public.account_moderation_audit_logs (target_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_account_mod_audit_actor
    ON public.account_moderation_audit_logs (actor_user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_account_mod_audit_txid
    ON public.account_moderation_audit_logs (target_user_id, txid)
    WHERE txid IS NOT NULL;

COMMENT ON TABLE public.account_moderation_audit_logs IS
    'Trilha append-only criptograficamente autenticada (HMAC) das sanções administrativas de ciclo de vida de contas (S3-A13-F2).';

CREATE OR REPLACE FUNCTION public.fn_prevent_account_moderation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'account_moderation_audit_logs é append-only: mutação não é permitida'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

CREATE OR REPLACE FUNCTION public.fn_prevent_account_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'account_moderation_audit_logs é append-only: truncamento não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_prevent_account_moderation_audit_update ON public.account_moderation_audit_logs;
CREATE TRIGGER trg_prevent_account_moderation_audit_update
    BEFORE UPDATE ON public.account_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_prevent_account_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_account_moderation_audit_delete ON public.account_moderation_audit_logs;
CREATE TRIGGER trg_prevent_account_moderation_audit_delete
    BEFORE DELETE ON public.account_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_prevent_account_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_account_moderation_audit_truncate ON public.account_moderation_audit_logs;
CREATE TRIGGER trg_prevent_account_moderation_audit_truncate
    BEFORE TRUNCATE ON public.account_moderation_audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION public.fn_prevent_account_moderation_audit_truncate();

-- 3b. Trilha de auditoria append-only de decisões de place claims
CREATE TABLE IF NOT EXISTS public.place_claim_moderation_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id UUID NOT NULL,
    place_id UUID NOT NULL,
    business_account_id UUID NOT NULL,
    moderator_user_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    decision_reason TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    authenticity_mac VARCHAR(128) NOT NULL,
    txid BIGINT,
    CONSTRAINT chk_place_claim_audit_action CHECK (action IN ('APPROVE_CLAIM', 'REJECT_CLAIM')),
    CONSTRAINT chk_place_claim_audit_prev_status CHECK (previous_status = 'PENDING'),
    CONSTRAINT chk_place_claim_audit_new_status CHECK (
        (action = 'APPROVE_CLAIM' AND new_status = 'APPROVED')
        OR (action = 'REJECT_CLAIM' AND new_status = 'REJECTED')
    ),
    CONSTRAINT chk_place_claim_audit_reason CHECK (length(btrim(decision_reason)) > 0 AND char_length(decision_reason) <= 1000),
    CONSTRAINT chk_place_claim_audit_mac_format CHECK (authenticity_mac ~ '^aud1\$[A-Za-z0-9_-]{1,32}\$[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_place_claim_mod_audit_claim
    ON public.place_claim_moderation_audit_logs (claim_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_place_claim_mod_audit_place
    ON public.place_claim_moderation_audit_logs (place_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_claim_mod_audit_txid
    ON public.place_claim_moderation_audit_logs (claim_id, txid)
    WHERE txid IS NOT NULL;

COMMENT ON TABLE public.place_claim_moderation_audit_logs IS
    'Trilha append-only desacoplada e criptograficamente autenticada (HMAC) das decisões administrativas sobre reivindicações de locais (S3-A13-F2).';

CREATE OR REPLACE FUNCTION public.fn_prevent_place_claim_moderation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_claim_moderation_audit_logs é append-only: mutação não é permitida'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

CREATE OR REPLACE FUNCTION public.fn_prevent_place_claim_moderation_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_claim_moderation_audit_logs é append-only: truncamento não é permitido'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_prevent_place_claim_moderation_audit_update ON public.place_claim_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_claim_moderation_audit_update
    BEFORE UPDATE ON public.place_claim_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_prevent_place_claim_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_place_claim_moderation_audit_delete ON public.place_claim_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_claim_moderation_audit_delete
    BEFORE DELETE ON public.place_claim_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_prevent_place_claim_moderation_audit_mutation();

DROP TRIGGER IF EXISTS trg_prevent_place_claim_moderation_audit_truncate ON public.place_claim_moderation_audit_logs;
CREATE TRIGGER trg_prevent_place_claim_moderation_audit_truncate
    BEFORE TRUNCATE ON public.place_claim_moderation_audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION public.fn_prevent_place_claim_moderation_audit_truncate();


-- ------------------------------------------------------------------------------
-- 4. REVOGAÇÃO DE PRIVILÉGIOS DE rewit_app E GRANTS MÍNIMOS DE rewit_mod E rewit_media
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        -- 4a. Todas as 6 trilhas de auditoria: rewit_app só lê (SELECT), nunca insere (SEC-F2-03)
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON
            public.moderation_audit_logs,
            public.discussion_moderation_audit_logs,
            public.business_response_moderation_audit_logs,
            public.place_media_moderation_audit_logs,
            public.account_moderation_audit_logs,
            public.place_claim_moderation_audit_logs
            FROM rewit_app;
        GRANT SELECT ON
            public.account_moderation_audit_logs,
            public.place_claim_moderation_audit_logs
            TO rewit_app;

        -- 4b. Denúncias, reivindicações e ciclo de vida de place_media:
        --     * rewit_app não atualiza place_media (upload usa apenas INSERT ACTIVE)
        --     * rewit_app não escreve em place_media_reports (denúncias e auto-quarentena rodam sob rewit_media)
        --     * rewit_app apenas cria PENDING em review_reports, discussion_reports e place_claim_requests
        REVOKE UPDATE, DELETE, TRUNCATE ON
            public.place_media,
            public.review_reports,
            public.discussion_reports,
            public.place_claim_requests,
            public.place_media_reports
            FROM rewit_app;

        REVOKE INSERT ON public.place_media_reports FROM rewit_app;

        REVOKE INSERT ON public.place_claim_requests FROM rewit_app;
        GRANT INSERT (id, business_account_id, place_id, evidence_description, status, created_at)
            ON public.place_claim_requests TO rewit_app;
    END IF;
END
$$;

-- 4c. Privilégios mínimos explícitos para rewit_mod (cobrindo estritamente os 6 módulos administrativos)
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM rewit_mod;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM rewit_mod;

-- Leitura necessária para consultas auxiliares, agregações de reputação, triggers e PostGIS
GRANT SELECT ON
    public.users,
    public.auth_sessions,
    public.reviews,
    public.review_targets,
    public.review_reactions,
    public.review_reports,
    public.review_media,
    public.check_ins,
    public.rateable_targets,
    public.rateable_target_stats,
    public.user_reputation,
    public.review_discussions,
    public.discussion_reports,
    public.business_responses,
    public.business_accounts,
    public.places,
    public.place_claim_requests,
    public.place_media,
    public.place_media_reports,
    public.moderation_audit_logs,
    public.discussion_moderation_audit_logs,
    public.business_response_moderation_audit_logs,
    public.place_media_moderation_audit_logs,
    public.account_moderation_audit_logs,
    public.place_claim_moderation_audit_logs,
    public.place_product_offers,
    public.place_opening_hours_schedules,
    public.place_amenity_configurations
    TO rewit_mod;

-- PostGIS spatial_ref_sys (leitura apenas, necessária para desserialização de places.coordinates)
DO $$
BEGIN
    IF to_regclass('public.spatial_ref_sys') IS NOT NULL THEN
        GRANT SELECT ON public.spatial_ref_sys TO rewit_mod;
    END IF;
END
$$;

-- Escrita estrita nas entidades moderadas e denúncias
GRANT UPDATE ON
    public.users,
    public.auth_sessions,
    public.reviews,
    public.review_reports,
    public.review_media,
    public.review_discussions,
    public.discussion_reports,
    public.business_responses,
    public.business_accounts,
    public.places,
    public.place_claim_requests,
    public.place_media
    TO rewit_mod;

-- place_media_reports: somente colunas de decisão (nunca is_legacy_decision, reason, detail, reporter)
GRANT UPDATE (status, updated_at, decided_by_user_id, decided_at, decision_notes, authenticity_mac)
    ON public.place_media_reports TO rewit_mod;

-- Estatísticas e reputação recalculadas atomicamente durante ModerateReviewUseCase
GRANT INSERT, UPDATE ON
    public.rateable_target_stats,
    public.user_reputation
    TO rewit_mod;

-- Trilhas de auditoria: estritamente append-only (SELECT + INSERT apenas)
GRANT INSERT ON
    public.moderation_audit_logs,
    public.discussion_moderation_audit_logs,
    public.business_response_moderation_audit_logs,
    public.place_media_moderation_audit_logs,
    public.account_moderation_audit_logs,
    public.place_claim_moderation_audit_logs
    TO rewit_mod;

-- Invalidação comercial acionada pelos triggers V28/V29 ao alterar places.claimed_by_business_id
GRANT DELETE ON
    public.place_product_offers,
    public.place_opening_hours_schedules,
    public.place_amenity_configurations
    TO rewit_mod;

-- 4d. Privilégios mínimos explícitos para rewit_media (SEC-F2-03: ciclo de vida público/automático de place_media)
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM rewit_media;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM rewit_media;

GRANT SELECT ON
    public.users,
    public.places,
    public.business_accounts,
    public.place_media,
    public.place_media_reports,
    public.place_media_moderation_audit_logs,
    public.storage_object_quarantine
    TO rewit_media;

DO $$
BEGIN
    IF to_regclass('public.spatial_ref_sys') IS NOT NULL THEN
        GRANT SELECT ON public.spatial_ref_sys TO rewit_media;
    END IF;
END
$$;

-- Escrita estrita no ciclo de vida público de mídias de locais
GRANT UPDATE ON public.place_media TO rewit_media;

-- UPDATE (id) em storage_object_quarantine é concedido apenas para permitir SELECT ... FOR UPDATE
-- (StorageObjectQuarantineJpaRepository.lockConfirmedEntry); fn_guard_storage_quarantine_media_writes()
-- bloqueia qualquer UPDATE/INSERT/DELETE efetivo por rewit_media. A liberação de entradas places/ ocorre
-- exclusivamente via rewit_release_place_quarantine_entry(UUID) (SECURITY DEFINER, seção 8b).
GRANT UPDATE (id) ON public.storage_object_quarantine TO rewit_media;

-- UPDATE (id) em places é concedido apenas para permitir SELECT ... FOR UPDATE (ordenação de locks Place -> PlaceMedia);
-- fn_guard_places_governance() bloqueia qualquer mutação efetiva de places por rewit_media.
GRANT UPDATE (id) ON public.places TO rewit_media;

-- Denúncias de mídia: criação apenas em colunas não decisórias (nunca decisão/MAC/legado, nunca UPDATE)
GRANT INSERT (id, place_media_id, reporter_user_id, reason, detail, status, created_at, updated_at)
    ON public.place_media_reports TO rewit_media;

-- Trilha de auditoria de mídias de locais: estritamente append-only (SELECT + INSERT apenas; ações públicas/sistema)
GRANT INSERT ON public.place_media_moderation_audit_logs TO rewit_media;

-- Sem DELETE direto em storage_object_quarantine: a liberação pós-expurgo físico (ADR-015) é restrita ao
-- namespace places/ e ao estado CONFIRMED_ORPHAN pela função rewit_release_place_quarantine_entry(UUID).

-- Guard de quarentena: rewit_media nunca altera, insere nem apaga diretamente linhas da fila compartilhada.
-- current_user (não GUC) identifica a role efetiva; dentro da função SECURITY DEFINER current_user é o owner.
CREATE OR REPLACE FUNCTION public.fn_guard_storage_quarantine_media_writes()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_media' THEN
        RAISE EXCEPTION 'storage_object_quarantine: rewit_media não altera a fila de quarentena diretamente (%)', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_storage_quarantine_media_writes ON public.storage_object_quarantine;
CREATE TRIGGER trg_guard_storage_quarantine_media_writes
    BEFORE INSERT OR UPDATE OR DELETE ON public.storage_object_quarantine
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_storage_quarantine_media_writes();


-- ------------------------------------------------------------------------------
-- 5. GUARDS DE MÁQUINA DE ESTADOS E GOVERNANÇA POR ROLE (FASE 2)
-- ------------------------------------------------------------------------------

-- 5.1 USERS: evoluir fn_guard_user_role_assignment() para F2
CREATE OR REPLACE FUNCTION public.fn_guard_user_role_assignment()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        -- 1. Atribuição de papel é exclusiva de operação de owner/migração (nem rewit_app nem rewit_mod)
        IF (TG_OP = 'INSERT' AND NEW.role IS DISTINCT FROM 'USER')
           OR (TG_OP = 'UPDATE' AND NEW.role IS DISTINCT FROM OLD.role) THEN
            RAISE EXCEPTION 'users.role: atribuição de papel é exclusiva de operação administrativa privilegiada'
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF TG_OP = 'INSERT' THEN
            IF NEW.account_status IS DISTINCT FROM 'ACTIVE' OR NEW.deleted_at IS NOT NULL THEN
                RAISE EXCEPTION 'users.account_status: contas novas nascem exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.last_moderation_txid := NULL;
            NEW.last_moderation_prev_status := NULL;
        ELSE
            -- 2. Campos de identidade estritamente imutáveis (F1)
            IF NEW.id IS DISTINCT FROM OLD.id THEN
                RAISE EXCEPTION 'users.id: identificador imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'users.created_at: timestamp de criação imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.auth_provider IS DISTINCT FROM OLD.auth_provider THEN
                RAISE EXCEPTION 'users.auth_provider: provedor de autenticação imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.provider_user_id IS DISTINCT FROM OLD.provider_user_id THEN
                IF NOT (OLD.account_status = 'DELETED' AND NEW.account_status = 'DELETED' AND NEW.provider_user_id IS NULL) THEN
                    RAISE EXCEPTION 'users.provider_user_id: identificador de provedor federado imutável'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
            END IF;

            -- 3. Conta excluída (DELETED) nunca retorna a outro estado
            IF OLD.account_status = 'DELETED' AND NEW.account_status IS DISTINCT FROM 'DELETED' THEN
                RAISE EXCEPTION 'users.account_status: conta excluída não pode retornar a outro estado'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- 4. Máquina de estados de account_status separada por role (F2)
            IF NOT v_is_mod THEN
                -- rewit_app: apenas ACTIVE <-> DEACTIVATED ou manutenção de DELETED (purge LGPD)
                IF NEW.account_status IS DISTINCT FROM OLD.account_status THEN
                    IF NOT (
                        (OLD.account_status = 'ACTIVE' AND NEW.account_status = 'DEACTIVATED')
                        OR (OLD.account_status = 'DEACTIVATED' AND NEW.account_status = 'ACTIVE')
                    ) THEN
                        RAISE EXCEPTION 'users.account_status: transição % -> % restrita à moderação administrativa (rewit_mod)',
                            OLD.account_status, NEW.account_status
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END IF;
                -- rewit_app não manipula metadados internos de moderação
                NEW.last_moderation_txid := OLD.last_moderation_txid;
                NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
            ELSE
                -- rewit_mod: não altera credenciais/e-mail; apenas sanções de ciclo de vida
                IF NEW.email IS DISTINCT FROM OLD.email
                   OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
                   OR NEW.is_verified IS DISTINCT FROM OLD.is_verified THEN
                    RAISE EXCEPTION 'users: rewit_mod não pode alterar email, password_hash ou is_verified'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;

                IF NEW.account_status IS DISTINCT FROM OLD.account_status THEN
                    IF NOT (
                        (OLD.account_status IN ('ACTIVE', 'DEACTIVATED') AND NEW.account_status = 'SUSPENDED')
                        OR (OLD.account_status = 'SUSPENDED' AND NEW.account_status = 'ACTIVE')
                        OR (OLD.account_status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED') AND NEW.account_status = 'DELETED')
                    ) THEN
                        RAISE EXCEPTION 'users.account_status: transição administrativa inválida % -> %',
                            OLD.account_status, NEW.account_status
                            USING ERRCODE = 'check_violation';
                    END IF;
                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'users.account_status: múltiplas transições administrativas na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.account_status;
                ELSE
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_user_role_assignment ON public.users;
CREATE TRIGGER trg_guard_user_role_assignment
    BEFORE INSERT OR UPDATE ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_user_role_assignment();


-- 5.2 PLACES: proteção de claimed_by_business_id, is_verified e status
-- Nomeado com prefixo trg_a_ para executar ANTES de trg_places_owner_change_clears_business_contacts (V39)
-- e antes dos triggers AFTER (V28/V29), impedindo limpeza comercial indevida.
CREATE OR REPLACE FUNCTION public.fn_guard_places_governance()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF current_user = 'rewit_media' THEN
            RAISE EXCEPTION 'places: rewit_media não cria nem altera estabelecimentos'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'places: rewit_mod não cria estabelecimentos'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.claimed_by_business_id IS NOT NULL THEN
                RAISE EXCEPTION 'places.claimed_by_business_id: novo local não pode nascer reivindicado pelo runtime público'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.is_verified IS DISTINCT FROM FALSE THEN
                RAISE EXCEPTION 'places.is_verified: verificação de local é exclusiva de operação administrativa'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE' THEN
                RAISE EXCEPTION 'places.status: novo local nasce exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.created_at IS DISTINCT FROM OLD.created_at
               OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id THEN
                RAISE EXCEPTION 'places: campos de identidade/criação são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                IF NEW.claimed_by_business_id IS DISTINCT FROM OLD.claimed_by_business_id THEN
                    RAISE EXCEPTION 'places.claimed_by_business_id: transferência de titularidade comercial exige rewit_mod'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF NEW.is_verified IS DISTINCT FROM OLD.is_verified THEN
                    RAISE EXCEPTION 'places.is_verified: alteração restrita à operação administrativa'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    RAISE EXCEPTION 'places.status: alteração de status de local restrita à operação administrativa'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
            ELSE
                -- rewit_mod só vincula claimed_by_business_id (NULL -> business_id) durante aprovação de claim
                IF NEW.is_verified IS DISTINCT FROM OLD.is_verified
                   OR NEW.status IS DISTINCT FROM OLD.status THEN
                    RAISE EXCEPTION 'places: rewit_mod não altera is_verified ou status diretamente'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF NEW.claimed_by_business_id IS DISTINCT FROM OLD.claimed_by_business_id THEN
                    IF OLD.claimed_by_business_id IS NOT NULL OR NEW.claimed_by_business_id IS NULL THEN
                        RAISE EXCEPTION 'places.claimed_by_business_id: rewit_mod só vincula estabelecimento ainda sem titular'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_a_guard_places_governance ON public.places;
CREATE TRIGGER trg_a_guard_places_governance
    BEFORE INSERT OR UPDATE ON public.places
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_places_governance();


-- 5.3 BUSINESS_ACCOUNTS: proteção de verification_status
CREATE OR REPLACE FUNCTION public.fn_guard_business_accounts_governance()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'business_accounts: rewit_mod não cria contas comerciais'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.verification_status IS DISTINCT FROM 'PENDING' THEN
                RAISE EXCEPTION 'business_accounts.verification_status: conta comercial nasce exclusivamente PENDING'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.plan_tier IS DISTINCT FROM 'FREE' THEN
                RAISE EXCEPTION 'business_accounts.plan_tier: conta comercial nasce exclusivamente FREE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.user_id IS DISTINCT FROM OLD.user_id
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'business_accounts: campos de identidade são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                IF NEW.verification_status IS DISTINCT FROM OLD.verification_status THEN
                    RAISE EXCEPTION 'business_accounts.verification_status: aprovação/rejeição exige rewit_mod'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF NEW.plan_tier IS DISTINCT FROM OLD.plan_tier THEN
                    RAISE EXCEPTION 'business_accounts.plan_tier: alteração de plano restrita à operação administrativa'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
            ELSE
                IF NEW.corporate_name IS DISTINCT FROM OLD.corporate_name
                   OR NEW.tax_id IS DISTINCT FROM OLD.tax_id
                   OR NEW.plan_tier IS DISTINCT FROM OLD.plan_tier THEN
                    RAISE EXCEPTION 'business_accounts: rewit_mod só altera verification_status'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF NEW.verification_status IS DISTINCT FROM OLD.verification_status THEN
                    IF NOT (OLD.verification_status = 'PENDING' AND NEW.verification_status IN ('APPROVED', 'REJECTED')) THEN
                        RAISE EXCEPTION 'business_accounts.verification_status: transição inválida % -> %',
                            OLD.verification_status, NEW.verification_status
                            USING ERRCODE = 'check_violation';
                    END IF;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_business_accounts_governance ON public.business_accounts;
CREATE TRIGGER trg_guard_business_accounts_governance
    BEFORE INSERT OR UPDATE ON public.business_accounts
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_business_accounts_governance();


-- 5.4 DENÚNCIAS E REIVINDICAÇÕES (review_reports, discussion_reports, place_claim_requests, place_media_reports)

-- 5.4a. review_reports
CREATE OR REPLACE FUNCTION public.fn_guard_review_report_decision()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF TG_OP = 'INSERT' THEN
        IF NOT v_is_owner AND NEW.status IS DISTINCT FROM 'PENDING' THEN
            RAISE EXCEPTION 'review_reports: denúncias nascem exclusivamente PENDING'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    ELSE
        IF NOT v_is_owner THEN
            IF NOT v_is_mod THEN
                RAISE EXCEPTION 'review_reports: atualização de denúncia restrita à moderação (rewit_mod)'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.review_id IS DISTINCT FROM OLD.review_id
               OR NEW.reporter_user_id IS DISTINCT FROM OLD.reporter_user_id
               OR NEW.reason IS DISTINCT FROM OLD.reason
               OR NEW.detail IS DISTINCT FROM OLD.detail
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'review_reports: metadados originais da denúncia são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;
        IF OLD.status IN ('ACCEPTED', 'REJECTED')
           AND NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION 'review_reports: decisão % é final e não pode ser reaberta ou alterada', OLD.status
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_review_report_decision ON public.review_reports;
CREATE TRIGGER trg_guard_review_report_decision
    BEFORE INSERT OR UPDATE ON public.review_reports
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_review_report_decision();

-- 5.4b. discussion_reports
CREATE OR REPLACE FUNCTION public.fn_guard_discussion_report_decision()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF TG_OP = 'INSERT' THEN
        IF NOT v_is_owner AND NEW.status IS DISTINCT FROM 'PENDING' THEN
            RAISE EXCEPTION 'discussion_reports: denúncias nascem exclusivamente PENDING'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    ELSE
        IF NOT v_is_owner THEN
            IF NOT v_is_mod THEN
                RAISE EXCEPTION 'discussion_reports: atualização de denúncia restrita à moderação (rewit_mod)'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.discussion_id IS DISTINCT FROM OLD.discussion_id
               OR NEW.reporter_user_id IS DISTINCT FROM OLD.reporter_user_id
               OR NEW.reason IS DISTINCT FROM OLD.reason
               OR NEW.detail IS DISTINCT FROM OLD.detail
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'discussion_reports: metadados originais da denúncia são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;
        IF OLD.status IN ('ACCEPTED', 'REJECTED')
           AND NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION 'discussion_reports: decisão % é final e não pode ser reaberta ou alterada', OLD.status
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_discussion_report_decision ON public.discussion_reports;
CREATE TRIGGER trg_guard_discussion_report_decision
    BEFORE INSERT OR UPDATE ON public.discussion_reports
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_discussion_report_decision();

-- 5.4c. place_claim_requests
CREATE OR REPLACE FUNCTION public.fn_guard_place_claim_decision()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_account_owner UUID;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF TG_OP = 'INSERT' THEN
        IF NOT v_is_owner THEN
            IF NEW.status IS DISTINCT FROM 'PENDING'
               OR NEW.decided_at IS NOT NULL
               OR NEW.decided_by_user_id IS NOT NULL
               OR NEW.decision_reason IS NOT NULL
               OR NEW.authenticity_mac IS NOT NULL THEN
                RAISE EXCEPTION 'place_claim_requests: reivindicações nascem exclusivamente PENDING e sem decisão'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.decision_txid := NULL;
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.business_account_id IS DISTINCT FROM OLD.business_account_id
           OR NEW.place_id IS DISTINCT FROM OLD.place_id
           OR NEW.evidence_description IS DISTINCT FROM OLD.evidence_description
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            IF NOT v_is_owner THEN
                RAISE EXCEPTION 'place_claim_requests: campos originais da solicitação são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;

        IF OLD.status IN ('APPROVED', 'REJECTED')
           AND (NEW.status IS DISTINCT FROM OLD.status
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.authenticity_mac IS DISTINCT FROM OLD.authenticity_mac) THEN
            RAISE EXCEPTION 'place_claim_requests: decisão % é final e não pode ser alterada ou reaberta', OLD.status
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF NOT v_is_owner THEN
            IF NOT v_is_mod THEN
                RAISE EXCEPTION 'place_claim_requests: decisão restrita à moderação administrativa (rewit_mod)'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF OLD.status = 'PENDING' AND NEW.status IN ('APPROVED', 'REJECTED') THEN
                -- Ator administrativo precisa existir, ter papel MODERATOR/ADMIN e estar operacional (ACTIVE)
                PERFORM 1
                FROM public.users u
                WHERE u.id = NEW.decided_by_user_id
                  AND u.role IN ('MODERATOR', 'ADMIN')
                  AND u.account_status = 'ACTIVE'
                  AND u.is_active = TRUE;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'place_claim_requests: decisão % exige moderador/administrador ativo', NEW.status
                        USING ERRCODE = 'check_violation';
                END IF;

                SELECT ba.user_id INTO v_account_owner
                FROM public.business_accounts ba
                WHERE ba.id = NEW.business_account_id;
                IF v_account_owner = NEW.decided_by_user_id THEN
                    RAISE EXCEPTION 'place_claim_requests: administrador da conta solicitante não pode decidir a própria reivindicação'
                        USING ERRCODE = 'check_violation';
                END IF;

                IF NEW.authenticity_mac IS NULL THEN
                    RAISE EXCEPTION 'place_claim_requests: decisão administrativa exige authenticity_mac válido'
                        USING ERRCODE = 'check_violation';
                END IF;

                NEW.decision_txid := pg_catalog.txid_current();
            ELSE
                NEW.decision_txid := OLD.decision_txid;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_place_claim_decision ON public.place_claim_requests;
CREATE TRIGGER trg_guard_place_claim_decision
    BEFORE INSERT OR UPDATE ON public.place_claim_requests
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_place_claim_decision();

-- 5.4d. place_media_reports (evolução da V37 com role separation + active moderator + MAC)
CREATE OR REPLACE FUNCTION public.fn_guard_place_media_report_decision()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF TG_OP = 'INSERT' THEN
        IF NOT v_is_owner AND current_user IS DISTINCT FROM 'rewit_media' THEN
            RAISE EXCEPTION 'place_media_reports: criação de denúncias restrita à role rewit_media'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.is_legacy_decision THEN
            RAISE EXCEPTION 'place_media_reports: decisões legadas são exclusivas da migração histórica'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.status IS DISTINCT FROM 'PENDING'
           OR NEW.decided_by_user_id IS NOT NULL
           OR NEW.decided_at IS NOT NULL
           OR NEW.decision_notes IS NOT NULL
           OR NEW.authenticity_mac IS NOT NULL THEN
            RAISE EXCEPTION 'place_media_reports: denúncias nascem pendentes e sem decisão'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        NEW.decision_txid := NULL;
    ELSE
        IF NEW.is_legacy_decision IS DISTINCT FROM OLD.is_legacy_decision THEN
            RAISE EXCEPTION 'place_media_reports: is_legacy_decision não pode ser alterado'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF OLD.is_legacy_decision
           AND (NEW.status IS DISTINCT FROM OLD.status
                OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.authenticity_mac IS DISTINCT FROM OLD.authenticity_mac) THEN
            RAISE EXCEPTION 'place_media_reports: decisões legadas são imutáveis'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF OLD.status IN ('ACCEPTED', 'REJECTED', 'AUTO_RESOLVED')
           AND (NEW.status IS DISTINCT FROM OLD.status
                OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_notes IS DISTINCT FROM OLD.decision_notes
                OR NEW.reason IS DISTINCT FROM OLD.reason
                OR NEW.detail IS DISTINCT FROM OLD.detail
                OR NEW.authenticity_mac IS DISTINCT FROM OLD.authenticity_mac) THEN
            RAISE EXCEPTION 'place_media_reports: a decisão % é final e não pode ser alterada', OLD.status
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF NOT v_is_owner THEN
            IF NOT v_is_mod THEN
                RAISE EXCEPTION 'place_media_reports: atualização direta vedada ao runtime público (use rewit_mod ou função restrita)'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.place_media_id IS DISTINCT FROM OLD.place_media_id
               OR NEW.reporter_user_id IS DISTINCT FROM OLD.reporter_user_id
               OR NEW.reason IS DISTINCT FROM OLD.reason
               OR NEW.detail IS DISTINCT FROM OLD.detail
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'place_media_reports: campos originais da denúncia são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF OLD.status = 'PENDING' AND NEW.status IN ('ACCEPTED', 'REJECTED') THEN
                IF NEW.decided_at IS NULL THEN
                    RAISE EXCEPTION 'place_media_reports: decisão % exige decided_at', NEW.status
                        USING ERRCODE = 'check_violation';
                END IF;
                IF NEW.authenticity_mac IS NULL THEN
                    RAISE EXCEPTION 'place_media_reports: decisão humana % exige authenticity_mac válido', NEW.status
                        USING ERRCODE = 'check_violation';
                END IF;
                NEW.decision_txid := pg_catalog.txid_current();
            ELSIF OLD.status = 'PENDING' AND NEW.status = 'AUTO_RESOLVED' THEN
                IF NEW.authenticity_mac IS NOT NULL THEN
                    RAISE EXCEPTION 'place_media_reports: resolução automática não recebe MAC de decisão humana'
                        USING ERRCODE = 'check_violation';
                END IF;
                NEW.decision_txid := pg_catalog.txid_current();
            ELSE
                NEW.decision_txid := OLD.decision_txid;
            END IF;
        END IF;
    END IF;

    -- Decisão humana nova exige moderador/administrador ativo e operacional
    IF NOT NEW.is_legacy_decision
       AND NEW.status IN ('ACCEPTED', 'REJECTED')
       AND (TG_OP = 'INSERT'
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.decided_by_user_id IS DISTINCT FROM OLD.decided_by_user_id) THEN
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.decided_by_user_id
          AND u.role IN ('MODERATOR', 'ADMIN')
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_media_reports: decisão % exige moderador válido e ativo', NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_place_media_report_decision ON public.place_media_reports;
CREATE TRIGGER trg_guard_place_media_report_decision
    BEFORE INSERT OR UPDATE ON public.place_media_reports
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_place_media_report_decision();


-- 5.5 ENTIDADES MODERÁVEIS (reviews, review_discussions, business_responses, place_media, review_media)

-- 5.5a. reviews
CREATE OR REPLACE FUNCTION public.fn_guard_reviews_state_transitions()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_pending_reports BIGINT;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'reviews: rewit_mod não cria avaliações'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE' OR NEW.removed_by IS NOT NULL THEN
                RAISE EXCEPTION 'reviews: novas avaliações nascem exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.last_moderation_txid := NULL;
            NEW.last_moderation_prev_status := NULL;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.user_id IS DISTINCT FROM OLD.user_id
               OR NEW.context_place_id IS DISTINCT FROM OLD.context_place_id
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'reviews: campos de identidade da avaliação são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                -- rewit_app
                IF NEW.removed_by = 'MODERATION' THEN
                    RAISE EXCEPTION 'reviews.removed_by: origem MODERATION é exclusiva de rewit_mod'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;

                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF OLD.status = 'ACTIVE' AND NEW.status = 'UNDER_REVIEW' THEN
                        SELECT COUNT(*) INTO v_pending_reports
                        FROM public.review_reports rr
                        WHERE rr.review_id = OLD.id AND rr.status = 'PENDING';
                        IF v_pending_reports < 3 THEN
                            RAISE EXCEPTION 'reviews.status: entrada em UNDER_REVIEW pelo runtime exige 3 denúncias pendentes reais'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        NEW.removed_by := NULL;
                    ELSIF OLD.status IN ('ACTIVE', 'UNDER_REVIEW') AND NEW.status = 'REMOVED' THEN
                        -- Exclusão legítima pelo autor (DeleteReviewUseCase): nunca falsifica origem MODERATION
                        NEW.removed_by := 'AUTHOR';
                    ELSE
                        RAISE EXCEPTION 'reviews.status: transição % -> % restrita à moderação administrativa (rewit_mod)',
                            OLD.status, NEW.status
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                ELSE
                    IF NEW.removed_by IS DISTINCT FROM OLD.removed_by THEN
                        RAISE EXCEPTION 'reviews.removed_by: origem da remoção é imutável'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END IF;
                NEW.last_moderation_txid := OLD.last_moderation_txid;
                NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
            ELSE
                -- rewit_mod: apenas moderação de status (ACTIVE/UNDER_REVIEW -> REMOVED ou UNDER_REVIEW -> ACTIVE)
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF NOT (
                        (OLD.status IN ('ACTIVE', 'UNDER_REVIEW') AND NEW.status = 'REMOVED')
                        OR (OLD.status = 'UNDER_REVIEW' AND NEW.status = 'ACTIVE')
                    ) THEN
                        RAISE EXCEPTION 'reviews.status: transição administrativa inválida % -> %',
                            OLD.status, NEW.status
                            USING ERRCODE = 'check_violation';
                    END IF;
                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'reviews.status: múltiplas transições de moderação na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.removed_by := CASE WHEN NEW.status = 'REMOVED' THEN 'MODERATION' ELSE NULL END;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.status;
                ELSE
                    NEW.removed_by := OLD.removed_by;
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_reviews_state_transitions ON public.reviews;
CREATE TRIGGER trg_guard_reviews_state_transitions
    BEFORE INSERT OR UPDATE ON public.reviews
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_reviews_state_transitions();

-- 5.5b. review_discussions
CREATE OR REPLACE FUNCTION public.fn_guard_review_discussions_state_transitions()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_pending_reports BIGINT;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'review_discussions: rewit_mod não cria comentários'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE' OR NEW.removed_by IS NOT NULL THEN
                RAISE EXCEPTION 'review_discussions: novos comentários nascem exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.last_moderation_txid := NULL;
            NEW.last_moderation_prev_status := NULL;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.review_id IS DISTINCT FROM OLD.review_id
               OR NEW.user_id IS DISTINCT FROM OLD.user_id
               OR NEW.parent_id IS DISTINCT FROM OLD.parent_id
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'review_discussions: campos de identidade do comentário são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                -- rewit_app: autor só remove ACTIVE -> REMOVED (em UNDER_REVIEW só a moderação decide!)
                IF NEW.removed_by = 'MODERATION' THEN
                    RAISE EXCEPTION 'review_discussions.removed_by: origem MODERATION é exclusiva de rewit_mod'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;

                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF OLD.status = 'ACTIVE' AND NEW.status = 'UNDER_REVIEW' THEN
                        SELECT COUNT(*) INTO v_pending_reports
                        FROM public.discussion_reports dr
                        WHERE dr.discussion_id = OLD.id AND dr.status = 'PENDING';
                        IF v_pending_reports < 3 THEN
                            RAISE EXCEPTION 'review_discussions.status: entrada em UNDER_REVIEW pelo runtime exige 3 denúncias pendentes reais'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        NEW.removed_by := NULL;
                    ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'REMOVED' THEN
                        NEW.removed_by := 'AUTHOR';
                    ELSE
                        RAISE EXCEPTION 'review_discussions.status: transição % -> % restrita à moderação administrativa (rewit_mod)',
                            OLD.status, NEW.status
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                ELSE
                    IF NEW.removed_by IS DISTINCT FROM OLD.removed_by THEN
                        RAISE EXCEPTION 'review_discussions.removed_by: origem da remoção é imutável'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END IF;
                NEW.last_moderation_txid := OLD.last_moderation_txid;
                NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
            ELSE
                -- rewit_mod: somente UNDER_REVIEW -> REMOVED ou UNDER_REVIEW -> ACTIVE
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF NOT (OLD.status = 'UNDER_REVIEW' AND NEW.status IN ('REMOVED', 'ACTIVE')) THEN
                        RAISE EXCEPTION 'review_discussions.status: transição administrativa inválida % -> %',
                            OLD.status, NEW.status
                            USING ERRCODE = 'check_violation';
                    END IF;
                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'review_discussions.status: múltiplas transições de moderação na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.removed_by := CASE WHEN NEW.status = 'REMOVED' THEN 'MODERATION' ELSE NULL END;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.status;
                ELSE
                    NEW.removed_by := OLD.removed_by;
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_review_discussions_state_transitions ON public.review_discussions;
CREATE TRIGGER trg_guard_review_discussions_state_transitions
    BEFORE INSERT OR UPDATE ON public.review_discussions
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_review_discussions_state_transitions();

-- 5.5c. business_responses
CREATE OR REPLACE FUNCTION public.fn_guard_business_responses_state_transitions()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'business_responses: rewit_mod não publica respostas comerciais'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE' OR NEW.removed_by IS NOT NULL OR NEW.removed_at IS NOT NULL THEN
                RAISE EXCEPTION 'business_responses: novas respostas nascem exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.last_moderation_txid := NULL;
            NEW.last_moderation_prev_status := NULL;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.review_id IS DISTINCT FROM OLD.review_id
               OR NEW.discussion_id IS DISTINCT FROM OLD.discussion_id
               OR NEW.business_account_id IS DISTINCT FROM OLD.business_account_id
               OR NEW.place_id IS DISTINCT FROM OLD.place_id
               OR NEW.author_user_id IS DISTINCT FROM OLD.author_user_id
               OR NEW.content IS DISTINCT FROM OLD.content
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'business_responses: campos de autoria e conteúdo são imutáveis'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                -- rewit_app: empresa só remove sua resposta com removed_by = BUSINESS; nunca MODERATION e nunca restaura
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF NOT (OLD.status = 'ACTIVE' AND NEW.status = 'REMOVED' AND NEW.removed_by = 'BUSINESS') THEN
                        RAISE EXCEPTION 'business_responses: transição % -> % (removed_by=%) restrita à moderação administrativa (rewit_mod)',
                            OLD.status, NEW.status, COALESCE(NEW.removed_by, 'NULL')
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                ELSIF OLD.status = 'REMOVED'
                      AND (NEW.removed_by IS DISTINCT FROM OLD.removed_by OR NEW.removed_at IS DISTINCT FROM OLD.removed_at) THEN
                    RAISE EXCEPTION 'business_responses: metadados de remoção são imutáveis'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                NEW.last_moderation_txid := OLD.last_moderation_txid;
                NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
            ELSE
                -- rewit_mod: remove (ACTIVE -> REMOVED com MODERATION) ou restaura (REMOVED[MODERATION] -> ACTIVE)
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF OLD.status = 'ACTIVE' AND NEW.status = 'REMOVED' THEN
                        IF NEW.removed_by IS DISTINCT FROM 'MODERATION' THEN
                            RAISE EXCEPTION 'business_responses: remoção por rewit_mod exige removed_by = MODERATION'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSIF OLD.status = 'REMOVED' AND NEW.status = 'ACTIVE' THEN
                        IF OLD.removed_by IS DISTINCT FROM 'MODERATION' THEN
                            RAISE EXCEPTION 'business_responses: resposta removida pela própria empresa não pode ser restaurada pela moderação'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSE
                        RAISE EXCEPTION 'business_responses: transição administrativa inválida % -> %',
                            OLD.status, NEW.status
                            USING ERRCODE = 'check_violation';
                    END IF;
                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'business_responses: múltiplas transições de moderação na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.status;
                ELSE
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_business_responses_state_transitions ON public.business_responses;
CREATE TRIGGER trg_guard_business_responses_state_transitions
    BEFORE INSERT OR UPDATE ON public.business_responses
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_business_responses_state_transitions();

-- 5.5d. place_media (protege removal_source, removed_at anti-backdating, purged_at >= 24h ADR-015, restauração; SEC-F2-03)
CREATE OR REPLACE FUNCTION public.fn_guard_place_media_state_transitions()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_is_media BOOLEAN;
    v_pending_reports BIGINT;
    v_now TIMESTAMPTZ := pg_catalog.clock_timestamp();
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');
    v_is_media := (current_user = 'rewit_media');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod OR v_is_media THEN
                RAISE EXCEPTION 'place_media: % não cria fotografias', current_user
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE'
               OR NEW.removal_source IS NOT NULL
               OR NEW.removed_at IS NOT NULL
               OR NEW.purged_at IS NOT NULL THEN
                RAISE EXCEPTION 'place_media: novas fotografias nascem exclusivamente ACTIVE sem remoção ou expurgo'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            NEW.last_moderation_txid := NULL;
            NEW.last_moderation_prev_status := NULL;
        ELSE
            IF NOT v_is_mod AND NOT v_is_media THEN
                RAISE EXCEPTION 'place_media: atualização de estado/metadados vedada a % (use rewit_media ou rewit_mod)', current_user
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.place_id IS DISTINCT FROM OLD.place_id
               OR NEW.user_id IS DISTINCT FROM OLD.user_id
               OR NEW.object_key IS DISTINCT FROM OLD.object_key
               OR NEW.media_type IS DISTINCT FROM OLD.media_type
               OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
               OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
               OR NEW.width IS DISTINCT FROM OLD.width
               OR NEW.height IS DISTINCT FROM OLD.height
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'place_media: metadados imutáveis da fotografia não podem ser alterados'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- purged_at uma vez preenchido é estritamente imutável (ADR-015)
            IF OLD.purged_at IS NOT NULL AND NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
                RAISE EXCEPTION 'place_media.purged_at: marcação de expurgo físico é imutável'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF v_is_media THEN
                -- rewit_media: ciclo de vida público/automático de mídias de locais
                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF OLD.status = 'ACTIVE' AND NEW.status = 'UNDER_REVIEW' THEN
                        -- Auto-quarentena exige 3 denúncias pendentes reais
                        SELECT COUNT(*) INTO v_pending_reports
                        FROM public.place_media_reports pmr
                        WHERE pmr.place_media_id = OLD.id AND pmr.status = 'PENDING';
                        IF v_pending_reports < 3 THEN
                            RAISE EXCEPTION 'place_media.status: quarentena automática exige 3 denúncias pendentes reais'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        IF NEW.is_primary THEN
                            RAISE EXCEPTION 'place_media.is_primary: fotografia em análise não pode ser capa'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF NEW.removal_source IS NOT NULL OR NEW.removed_at IS NOT NULL OR NEW.purged_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media: campos de remoção/expurgo inválidos em UNDER_REVIEW'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSIF OLD.status IN ('ACTIVE', 'UNDER_REVIEW') AND NEW.status = 'REMOVED' THEN
                        -- Remoção pública permitida apenas para AUTHOR, BUSINESS_OWNER ou PLACE_MANAGER
                        IF NEW.removal_source IS NULL
                           OR NEW.removal_source NOT IN ('AUTHOR', 'BUSINESS_OWNER', 'PLACE_MANAGER') THEN
                            RAISE EXCEPTION 'place_media.removal_source: origem % não permitida à role rewit_media',
                                COALESCE(NEW.removal_source, 'NULL')
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        IF NEW.removed_at IS NULL
                           OR NEW.removed_at < (v_now - INTERVAL '5 minutes')
                           OR NEW.removed_at > (v_now + INTERVAL '5 minutes') THEN
                            RAISE EXCEPTION 'place_media.removed_at: timestamp de remoção inválido ou retroativo (anti-backdating ADR-015)'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        IF NEW.is_primary THEN
                            RAISE EXCEPTION 'place_media.is_primary: fotografia removida não pode ser capa'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF NEW.purged_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media.purged_at: expurgo físico prematuro proibido na remoção (carência mínima de 24h ADR-015)'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                    ELSE
                        -- Bloqueia UNDER_REVIEW -> ACTIVE e REMOVED -> ACTIVE/UNDER_REVIEW para rewit_media
                        RAISE EXCEPTION 'place_media.status: transição % -> % restrita à moderação administrativa (rewit_mod)',
                            OLD.status, NEW.status
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;

                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'place_media.status: múltiplas transições de estado na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.status;
                ELSE
                    -- Mesmo status
                    IF OLD.status = 'REMOVED' THEN
                        IF NEW.removal_source IS DISTINCT FROM OLD.removal_source THEN
                            RAISE EXCEPTION 'place_media.removal_source: origem de remoção é imutável enquanto REMOVED'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        IF NEW.removed_at IS DISTINCT FROM OLD.removed_at THEN
                            RAISE EXCEPTION 'place_media.removed_at: timestamp de remoção é imutável (impede backdating ADR-015)'
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                        IF OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL THEN
                            -- Storage GC só pode marcar purged_at após carência mínima real de 24h (ADR-015)
                            IF OLD.removed_at IS NULL OR OLD.removed_at > (v_now - INTERVAL '24 hours') THEN
                                RAISE EXCEPTION 'place_media.purged_at: expurgo físico proibido antes do prazo mínimo de 24 horas (ADR-015)'
                                    USING ERRCODE = 'insufficient_privilege';
                            END IF;
                            IF NEW.purged_at < (v_now - INTERVAL '5 minutes') THEN
                                RAISE EXCEPTION 'place_media.purged_at: timestamp de expurgo retroativo proibido'
                                    USING ERRCODE = 'insufficient_privilege';
                            END IF;
                        END IF;
                    ELSE
                        IF NEW.removal_source IS DISTINCT FROM OLD.removal_source
                           OR NEW.removed_at IS DISTINCT FROM OLD.removed_at
                           OR NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
                            RAISE EXCEPTION 'place_media: campos de remoção/expurgo não podem ser alterados em estado %', OLD.status
                                USING ERRCODE = 'insufficient_privilege';
                        END IF;
                    END IF;
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            ELSE
                -- rewit_mod
                IF NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
                    RAISE EXCEPTION 'place_media.purged_at: rewit_mod não altera marcação de expurgo físico'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;

                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF OLD.status IN ('ACTIVE', 'UNDER_REVIEW') AND NEW.status = 'REMOVED' THEN
                        IF NEW.removal_source IS DISTINCT FROM 'MODERATOR' THEN
                            RAISE EXCEPTION 'place_media.removal_source: remoção por rewit_mod exige removal_source = MODERATOR'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF NEW.removed_at IS NULL
                           OR NEW.removed_at < (v_now - INTERVAL '5 minutes')
                           OR NEW.removed_at > (v_now + INTERVAL '5 minutes') THEN
                            RAISE EXCEPTION 'place_media.removed_at: timestamp de remoção inválido ou retroativo'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSIF OLD.status = 'UNDER_REVIEW' AND NEW.status = 'ACTIVE' THEN
                        IF OLD.purged_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media: fotografia já expurgada não pode ser liberada'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF NEW.removal_source IS NOT NULL OR NEW.removed_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media: liberação de análise exige removal_source e removed_at nulos'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSIF OLD.status = 'REMOVED' AND NEW.status = 'ACTIVE' THEN
                        IF OLD.removal_source IS DISTINCT FROM 'MODERATOR' THEN
                            RAISE EXCEPTION 'place_media: restauração administrativa restrita a remoções de MODERATOR (ADR-015)'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF OLD.purged_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media: fotografia já expurgada fisicamente não pode ser restaurada (ADR-015)'
                                USING ERRCODE = 'check_violation';
                        END IF;
                        IF NEW.removal_source IS NOT NULL OR NEW.removed_at IS NOT NULL THEN
                            RAISE EXCEPTION 'place_media: restauração exige limpeza de removal_source e removed_at'
                                USING ERRCODE = 'check_violation';
                        END IF;
                    ELSE
                        RAISE EXCEPTION 'place_media.status: transição administrativa inválida % -> %',
                            OLD.status, NEW.status
                            USING ERRCODE = 'check_violation';
                    END IF;

                    IF OLD.last_moderation_txid = pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'place_media.status: múltiplas transições de moderação na mesma transação não são permitidas'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.last_moderation_txid := pg_catalog.txid_current();
                    NEW.last_moderation_prev_status := OLD.status;
                ELSE
                    -- Atualização sem troca de status (ex.: promoção de capa is_primary em outra foto ACTIVE)
                    IF NEW.removal_source IS DISTINCT FROM OLD.removal_source
                       OR NEW.removed_at IS DISTINCT FROM OLD.removed_at THEN
                        RAISE EXCEPTION 'place_media: metadados de remoção não podem ser alterados sem transição de status'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                    NEW.last_moderation_txid := OLD.last_moderation_txid;
                    NEW.last_moderation_prev_status := OLD.last_moderation_prev_status;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_place_media_state_transitions ON public.place_media;
CREATE TRIGGER trg_guard_place_media_state_transitions
    BEFORE INSERT OR UPDATE ON public.place_media
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_place_media_state_transitions();

-- 5.5e. review_media
CREATE OR REPLACE FUNCTION public.fn_guard_review_media_state_transitions()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_review_status VARCHAR(32);
    v_review_removed_by VARCHAR(16);
    v_review_mod_txid BIGINT;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;

    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');

    IF NOT v_is_owner THEN
        IF TG_OP = 'INSERT' THEN
            IF v_is_mod THEN
                RAISE EXCEPTION 'review_media: rewit_mod não faz upload de mídias de avaliação'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.status IS DISTINCT FROM 'ACTIVE' OR NEW.removed_by IS NOT NULL THEN
                RAISE EXCEPTION 'review_media: novas mídias nascem exclusivamente ACTIVE'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSE
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.review_id IS DISTINCT FROM OLD.review_id
               OR NEW.user_id IS DISTINCT FROM OLD.user_id
               OR NEW.object_key IS DISTINCT FROM OLD.object_key
               OR NEW.media_type IS DISTINCT FROM OLD.media_type
               OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
               OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
               OR NEW.width IS DISTINCT FROM OLD.width
               OR NEW.height IS DISTINCT FROM OLD.height
               OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                RAISE EXCEPTION 'review_media: metadados imutáveis da mídia não podem ser alterados'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- Nenhuma role runtime restaura review_media REMOVED -> ACTIVE
            IF OLD.status = 'REMOVED' AND NEW.status IS DISTINCT FROM 'REMOVED' THEN
                RAISE EXCEPTION 'review_media.status: mídia removida não pode ser restaurada pelo runtime'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            IF NOT v_is_mod THEN
                IF NEW.removed_by = 'MODERATION' THEN
                    RAISE EXCEPTION 'review_media.removed_by: origem MODERATION é exclusiva de rewit_mod'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                IF OLD.status = 'ACTIVE' AND NEW.status = 'REMOVED' THEN
                    SELECT rv.status, rv.removed_by INTO v_review_status, v_review_removed_by
                    FROM public.reviews rv
                    WHERE rv.id = OLD.review_id;
                    IF v_review_status = 'REMOVED' AND v_review_removed_by = 'MODERATION' THEN
                        RAISE EXCEPTION 'review_media.status: mídia de avaliação moderada não pode ser alterada por rewit_app'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                    NEW.removed_by := 'AUTHOR';
                ELSE
                    IF NEW.removed_by IS DISTINCT FROM OLD.removed_by THEN
                        RAISE EXCEPTION 'review_media.removed_by: origem de remoção imutável'
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END IF;
            ELSE
                -- rewit_mod só marca review_media como REMOVED dentro da mesma transação de remoção da review pai
                IF OLD.status = 'ACTIVE' AND NEW.status = 'REMOVED' THEN
                    SELECT rv.status, rv.last_moderation_txid INTO v_review_status, v_review_mod_txid
                    FROM public.reviews rv
                    WHERE rv.id = OLD.review_id;
                    IF v_review_status IS DISTINCT FROM 'REMOVED'
                       OR v_review_mod_txid IS DISTINCT FROM pg_catalog.txid_current() THEN
                        RAISE EXCEPTION 'review_media: rewit_mod só remove mídias na mesma transação da remoção da avaliação pai'
                            USING ERRCODE = 'check_violation';
                    END IF;
                    NEW.removed_by := 'MODERATION';
                ELSE
                    NEW.removed_by := OLD.removed_by;
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_guard_review_media_state_transitions ON public.review_media;
CREATE TRIGGER trg_guard_review_media_state_transitions
    BEFORE INSERT OR UPDATE ON public.review_media
    FOR EACH ROW EXECUTE FUNCTION public.fn_guard_review_media_state_transitions();


-- ------------------------------------------------------------------------------
-- 6. EVOLUÇÃO DOS TRIGGERS DE REFERÊNCIA E AUTENTICIDADE DAS 6 AUDITORIAS
-- ------------------------------------------------------------------------------

-- 6a. moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_bind_moderation_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);

    PERFORM 1 FROM public.reviews WHERE id = NEW.review_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'moderation_audit_logs: avaliação % inexistente', NEW.review_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT v_is_owner THEN
        IF current_user IS DISTINCT FROM 'rewit_mod' THEN
            RAISE EXCEPTION 'moderation_audit_logs: inserção restrita à role rewit_mod'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.moderator_user_id
          AND u.role IN ('MODERATOR', 'ADMIN')
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'moderation_audit_logs: ator % não é moderador/administrador ativo', NEW.moderator_user_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.authenticity_mac IS NULL THEN
            RAISE EXCEPTION 'moderation_audit_logs: authenticity_mac obrigatório para novas inserções'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

-- 6b. discussion_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_bind_discussion_moderation_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_review_id UUID;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);

    SELECT review_id INTO v_review_id FROM public.review_discussions WHERE id = NEW.discussion_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'discussion_moderation_audit_logs: comentário % inexistente', NEW.discussion_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    NEW.review_id := v_review_id;

    IF NOT v_is_owner THEN
        IF current_user IS DISTINCT FROM 'rewit_mod' THEN
            RAISE EXCEPTION 'discussion_moderation_audit_logs: inserção restrita à role rewit_mod'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.moderator_user_id
          AND u.role IN ('MODERATOR', 'ADMIN')
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'discussion_moderation_audit_logs: ator % não é moderador/administrador ativo', NEW.moderator_user_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.authenticity_mac IS NULL THEN
            RAISE EXCEPTION 'discussion_moderation_audit_logs: authenticity_mac obrigatório para novas inserções'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

-- 6c. business_response_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_bind_business_response_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_review_id UUID;
    v_business_account_id UUID;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);

    SELECT review_id, business_account_id INTO v_review_id, v_business_account_id
    FROM public.business_responses WHERE id = NEW.response_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'business_response_moderation_audit_logs: resposta % inexistente', NEW.response_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    NEW.review_id := v_review_id;
    NEW.business_account_id := v_business_account_id;

    IF NOT v_is_owner THEN
        IF current_user IS DISTINCT FROM 'rewit_mod' THEN
            RAISE EXCEPTION 'business_response_moderation_audit_logs: inserção restrita à role rewit_mod'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.moderator_user_id
          AND u.role IN ('MODERATOR', 'ADMIN')
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'business_response_moderation_audit_logs: ator % não é moderador/administrador ativo', NEW.moderator_user_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.authenticity_mac IS NULL THEN
            RAISE EXCEPTION 'business_response_moderation_audit_logs: authenticity_mac obrigatório para novas inserções'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

-- 6d. place_media_moderation_audit_logs (restringe eventos públicos/automáticos a rewit_media e admin a rewit_mod; SEC-F2-03)
CREATE OR REPLACE FUNCTION public.fn_bind_place_media_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_mod BOOLEAN;
    v_is_media BOOLEAN;
    v_media RECORD;
    v_place RECORD;
    v_report_status VARCHAR(32);
    v_pending_reports BIGINT;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);
    v_is_mod := (current_user = 'rewit_mod');
    v_is_media := (current_user = 'rewit_media');

    SELECT m.place_id, m.user_id, m.status, m.removal_source
    INTO v_media
    FROM public.place_media m
    WHERE m.id = NEW.place_media_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: fotografia % inexistente', NEW.place_media_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NEW.place_id IS DISTINCT FROM v_media.place_id THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: local % não é o local da fotografia %', NEW.place_id, NEW.place_media_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.action IN ('ACCEPT_REPORT', 'REJECT_REPORT', 'AUTO_RESOLVE_REPORT') AND NEW.report_id IS NULL THEN
        RAISE EXCEPTION 'place_media_moderation_audit_logs: a ação % exige a denúncia correspondente', NEW.action
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.report_id IS NOT NULL THEN
        IF v_is_owner OR v_is_mod THEN
            SELECT r.status INTO v_report_status
            FROM public.place_media_reports r
            WHERE r.id = NEW.report_id AND r.place_media_id = NEW.place_media_id
            FOR KEY SHARE;
        ELSE
            -- rewit_media não possui UPDATE em place_media_reports (necessário para FOR KEY SHARE),
            -- nem DELETE; em AUTO_RESOLVE_REPORT a linha já foi travada pela função SECURITY DEFINER.
            SELECT r.status INTO v_report_status
            FROM public.place_media_reports r
            WHERE r.id = NEW.report_id AND r.place_media_id = NEW.place_media_id;
        END IF;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_media_moderation_audit_logs: denúncia % inexistente ou de outra fotografia', NEW.report_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;

    IF NOT v_is_owner THEN
        IF NOT v_is_mod AND NOT v_is_media THEN
            RAISE EXCEPTION 'place_media_moderation_audit_logs: inserção restrita às roles rewit_media e rewit_mod (vedada a %)', current_user
                USING ERRCODE = 'insufficient_privilege';
        END IF;

        IF NEW.authenticity_mac IS NULL THEN
            RAISE EXCEPTION 'place_media_moderation_audit_logs: authenticity_mac obrigatório para novas inserções'
                USING ERRCODE = 'check_violation';
        END IF;

        IF v_is_media THEN
            -- rewit_media: proibido inserir qualquer ação administrativa de moderação
            IF NEW.action IN ('REMOVE', 'RESTORE', 'ACCEPT_REPORT', 'REJECT_REPORT', 'RELEASE_REVIEW') THEN
                RAISE EXCEPTION 'place_media_moderation_audit_logs: ação administrativa % restrita à role rewit_mod', NEW.action
                    USING ERRCODE = 'insufficient_privilege';
            END IF;

            -- Validação de coerência com o estado real da entidade para eventos públicos/sistema
            IF NEW.action = 'AUTO_QUARANTINE' THEN
                IF NEW.actor_type IS DISTINCT FROM 'SYSTEM' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: AUTO_QUARANTINE exige actor_type = SYSTEM'
                        USING ERRCODE = 'check_violation';
                END IF;
                SELECT COUNT(*) INTO v_pending_reports
                FROM public.place_media_reports r
                WHERE r.place_media_id = NEW.place_media_id AND r.status = 'PENDING';
                IF v_media.status IS DISTINCT FROM 'UNDER_REVIEW' OR v_pending_reports < 3 THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: AUTO_QUARANTINE inconsistente com o estado da fotografia'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.action = 'AUTO_RESOLVE_REPORT' THEN
                IF NEW.actor_type IS DISTINCT FROM 'SYSTEM'
                   OR v_media.status IS DISTINCT FROM 'REMOVED'
                   OR v_report_status IS DISTINCT FROM 'AUTO_RESOLVED' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: AUTO_RESOLVE_REPORT exige mídia REMOVED e denúncia AUTO_RESOLVED'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.action = 'AUTHOR_REMOVE' THEN
                IF NEW.actor_type IS DISTINCT FROM 'USER'
                   OR NEW.actor_user_id IS DISTINCT FROM v_media.user_id
                   OR v_media.status IS DISTINCT FROM 'REMOVED'
                   OR v_media.removal_source IS DISTINCT FROM 'AUTHOR' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: AUTHOR_REMOVE inconsistente com o autor/estado da fotografia'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.action = 'PLACE_MANAGER_REMOVE' THEN
                SELECT p.created_by_user_id INTO v_place
                FROM public.places p WHERE p.id = NEW.place_id;
                IF NEW.actor_type IS DISTINCT FROM 'USER'
                   OR NEW.actor_user_id IS DISTINCT FROM v_place.created_by_user_id
                   OR v_media.status IS DISTINCT FROM 'REMOVED'
                   OR v_media.removal_source IS DISTINCT FROM 'PLACE_MANAGER' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: PLACE_MANAGER_REMOVE inconsistente com o criador/estado da fotografia'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.action = 'BUSINESS_OWNER_REMOVE' THEN
                PERFORM 1
                FROM public.places p
                JOIN public.business_accounts ba ON ba.id = p.claimed_by_business_id
                WHERE p.id = NEW.place_id
                  AND ba.user_id = NEW.actor_user_id
                  AND ba.verification_status = 'APPROVED';
                IF NOT FOUND
                   OR NEW.actor_type IS DISTINCT FROM 'USER'
                   OR v_media.status IS DISTINCT FROM 'REMOVED'
                   OR v_media.removal_source IS DISTINCT FROM 'BUSINESS_OWNER' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: BUSINESS_OWNER_REMOVE inconsistente com o representante/estado da fotografia'
                        USING ERRCODE = 'check_violation';
                END IF;
            END IF;
        ELSE
            -- rewit_mod: somente ações administrativas ou AUTO_RESOLVE_REPORT em cascata
            IF NEW.action NOT IN ('REMOVE', 'RESTORE', 'ACCEPT_REPORT', 'REJECT_REPORT', 'RELEASE_REVIEW', 'AUTO_RESOLVE_REPORT') THEN
                RAISE EXCEPTION 'place_media_moderation_audit_logs: ação % não pertence à role rewit_mod', NEW.action
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NEW.action = 'AUTO_RESOLVE_REPORT' THEN
                IF NEW.actor_type IS DISTINCT FROM 'SYSTEM' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: AUTO_RESOLVE_REPORT exige actor_type = SYSTEM'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSE
                IF NEW.actor_type IS DISTINCT FROM 'USER' THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: ação % exige actor_type = USER', NEW.action
                        USING ERRCODE = 'check_violation';
                END IF;
                PERFORM 1 FROM public.users u
                WHERE u.id = NEW.actor_user_id
                  AND u.role IN ('MODERATOR', 'ADMIN')
                  AND u.account_status = 'ACTIVE'
                  AND u.is_active = TRUE;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'place_media_moderation_audit_logs: ator % não é moderador/administrador ativo', NEW.actor_user_id
                        USING ERRCODE = 'check_violation';
                END IF;
            END IF;
        END IF;

        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

-- 6e. account_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_bind_account_moderation_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);

    PERFORM 1 FROM public.users WHERE id = NEW.target_user_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'account_moderation_audit_logs: conta alvo % inexistente', NEW.target_user_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    PERFORM 1 FROM public.users WHERE id = NEW.actor_user_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'account_moderation_audit_logs: ator % inexistente', NEW.actor_user_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT v_is_owner THEN
        IF current_user IS DISTINCT FROM 'rewit_mod' THEN
            RAISE EXCEPTION 'account_moderation_audit_logs: inserção restrita à role rewit_mod'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.actor_user_id
          AND u.role = 'ADMIN'
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'account_moderation_audit_logs: ator % não é ADMIN ativo', NEW.actor_user_id
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_bind_account_moderation_audit_reference ON public.account_moderation_audit_logs;
CREATE TRIGGER trg_bind_account_moderation_audit_reference
    BEFORE INSERT ON public.account_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_bind_account_moderation_audit_reference();

-- 6f. place_claim_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_bind_place_claim_audit_reference()
RETURNS TRIGGER AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_claim RECORD;
    v_account_owner UUID;
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
    INTO v_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = TG_RELID AND r.rolname = current_user;
    v_is_owner := COALESCE(v_is_owner, FALSE);

    SELECT c.place_id, c.business_account_id
    INTO v_claim
    FROM public.place_claim_requests c
    WHERE c.id = NEW.claim_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'place_claim_moderation_audit_logs: reivindicação % inexistente', NEW.claim_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NEW.place_id IS DISTINCT FROM v_claim.place_id
       OR NEW.business_account_id IS DISTINCT FROM v_claim.business_account_id THEN
        RAISE EXCEPTION 'place_claim_moderation_audit_logs: place_id/business_account_id divergem da reivindicação %', NEW.claim_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    PERFORM 1 FROM public.users WHERE id = NEW.moderator_user_id FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'place_claim_moderation_audit_logs: moderador % inexistente', NEW.moderator_user_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT v_is_owner THEN
        IF current_user IS DISTINCT FROM 'rewit_mod' THEN
            RAISE EXCEPTION 'place_claim_moderation_audit_logs: inserção restrita à role rewit_mod'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        PERFORM 1 FROM public.users u
        WHERE u.id = NEW.moderator_user_id
          AND u.role IN ('MODERATOR', 'ADMIN')
          AND u.account_status = 'ACTIVE'
          AND u.is_active = TRUE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'place_claim_moderation_audit_logs: ator % não é moderador/administrador ativo', NEW.moderator_user_id
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT ba.user_id INTO v_account_owner
        FROM public.business_accounts ba WHERE ba.id = NEW.business_account_id;
        IF v_account_owner = NEW.moderator_user_id THEN
            RAISE EXCEPTION 'place_claim_moderation_audit_logs: administrador da conta não pode auditar a própria reivindicação'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.txid := pg_catalog.txid_current();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_bind_place_claim_audit_reference ON public.place_claim_moderation_audit_logs;
CREATE TRIGGER trg_bind_place_claim_audit_reference
    BEFORE INSERT ON public.place_claim_moderation_audit_logs
    FOR EACH ROW EXECUTE FUNCTION public.fn_bind_place_claim_audit_reference();


-- ------------------------------------------------------------------------------
-- 7. INTEGRIDADE TRANSACIONAL BIDIRECIONAL DECISÃO <-> AUDITORIA (CONSTRAINT TRIGGERS DEFERRED)
-- ------------------------------------------------------------------------------

-- 7.1 Reviews <-> moderation_audit_logs (+ review_reports)
CREATE OR REPLACE FUNCTION public.fn_require_review_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.status IS DISTINCT FROM NEW.status
       AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.moderation_audit_logs al
            WHERE al.review_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.previous_review_status = NEW.last_moderation_prev_status
              AND al.new_review_status = NEW.status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: transição de moderação em reviews (%) exige registro correspondente em moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_review_moderation_audit ON public.reviews;
CREATE CONSTRAINT TRIGGER trg_require_review_moderation_audit
    AFTER UPDATE ON public.reviews
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_review_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_review_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NOT (
            (NEW.action = 'REMOVE_REVIEW' AND NEW.decision = 'ACCEPTED'
             AND NEW.previous_review_status IN ('ACTIVE', 'UNDER_REVIEW') AND NEW.new_review_status = 'REMOVED')
            OR (NEW.action = 'RESTORE_REVIEW' AND NEW.decision = 'REJECTED'
                AND NEW.previous_review_status = 'UNDER_REVIEW' AND NEW.new_review_status = 'ACTIVE')
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: ação/decisão/estados incoerentes em moderation_audit_logs'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.reviews r
            WHERE r.id = NEW.review_id
              AND r.last_moderation_txid = NEW.txid
              AND r.last_moderation_prev_status = NEW.previous_review_status
              AND r.status = NEW.new_review_status
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: registro em moderation_audit_logs sem transição real correspondente em reviews (%) na mesma transação', NEW.review_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_review_moderation_transition ON public.moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_review_moderation_transition
    AFTER INSERT ON public.moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_review_moderation_transition();

CREATE OR REPLACE FUNCTION public.fn_require_review_report_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.moderation_audit_logs al
            WHERE al.review_id = NEW.review_id
              AND al.txid = pg_catalog.txid_current()
              AND al.decision = NEW.status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: decisão em review_reports (%) exige registro correspondente em moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_review_report_moderation_audit ON public.review_reports;
CREATE CONSTRAINT TRIGGER trg_require_review_report_moderation_audit
    AFTER UPDATE ON public.review_reports
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_review_report_moderation_audit();

-- 7.2 review_discussions <-> discussion_moderation_audit_logs (+ discussion_reports)
CREATE OR REPLACE FUNCTION public.fn_require_discussion_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.status IS DISTINCT FROM NEW.status
       AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.discussion_moderation_audit_logs al
            WHERE al.discussion_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.previous_status = NEW.last_moderation_prev_status
              AND al.new_status = NEW.status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: transição de moderação em review_discussions (%) exige registro correspondente em discussion_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_discussion_moderation_audit ON public.review_discussions;
CREATE CONSTRAINT TRIGGER trg_require_discussion_moderation_audit
    AFTER UPDATE ON public.review_discussions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_discussion_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_discussion_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NOT (
            (NEW.action = 'REMOVE_DISCUSSION' AND NEW.decision = 'ACCEPTED'
             AND NEW.previous_status = 'UNDER_REVIEW' AND NEW.new_status = 'REMOVED')
            OR (NEW.action = 'RESTORE_DISCUSSION' AND NEW.decision = 'REJECTED'
                AND NEW.previous_status = 'UNDER_REVIEW' AND NEW.new_status = 'ACTIVE')
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: ação/decisão/estados incoerentes em discussion_moderation_audit_logs'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.review_discussions d
            WHERE d.id = NEW.discussion_id
              AND d.last_moderation_txid = NEW.txid
              AND d.last_moderation_prev_status = NEW.previous_status
              AND d.status = NEW.new_status
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: registro em discussion_moderation_audit_logs sem transição real correspondente em review_discussions (%) na mesma transação', NEW.discussion_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_discussion_moderation_transition ON public.discussion_moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_discussion_moderation_transition
    AFTER INSERT ON public.discussion_moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_discussion_moderation_transition();

CREATE OR REPLACE FUNCTION public.fn_require_discussion_report_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.discussion_moderation_audit_logs al
            WHERE al.discussion_id = NEW.discussion_id
              AND al.txid = pg_catalog.txid_current()
              AND al.decision = NEW.status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: decisão em discussion_reports (%) exige registro correspondente em discussion_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_discussion_report_moderation_audit ON public.discussion_reports;
CREATE CONSTRAINT TRIGGER trg_require_discussion_report_moderation_audit
    AFTER UPDATE ON public.discussion_reports
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_discussion_report_moderation_audit();

-- 7.3 business_responses <-> business_response_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_require_business_response_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.status IS DISTINCT FROM NEW.status
       AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.business_response_moderation_audit_logs al
            WHERE al.response_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.previous_status = NEW.last_moderation_prev_status
              AND al.new_status = NEW.status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: transição de moderação em business_responses (%) exige registro correspondente em business_response_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_business_response_moderation_audit ON public.business_responses;
CREATE CONSTRAINT TRIGGER trg_require_business_response_moderation_audit
    AFTER UPDATE ON public.business_responses
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_business_response_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_business_response_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NOT (
            (NEW.action = 'REMOVE_RESPONSE' AND NEW.previous_status = 'ACTIVE' AND NEW.new_status = 'REMOVED')
            OR (NEW.action = 'RESTORE_RESPONSE' AND NEW.previous_status = 'REMOVED' AND NEW.new_status = 'ACTIVE')
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: ação/estados incoerentes em business_response_moderation_audit_logs'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.business_responses br
            WHERE br.id = NEW.response_id
              AND br.last_moderation_txid = NEW.txid
              AND br.last_moderation_prev_status = NEW.previous_status
              AND br.status = NEW.new_status
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: registro em business_response_moderation_audit_logs sem transição real correspondente em business_responses (%) na mesma transação', NEW.response_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_business_response_moderation_transition ON public.business_response_moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_business_response_moderation_transition
    AFTER INSERT ON public.business_response_moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_business_response_moderation_transition();

-- 7.4 place_media & place_media_reports <-> place_media_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_require_place_media_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.status IS DISTINCT FROM NEW.status
       AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NEW.status = 'REMOVED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.place_media_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action IN ('REMOVE', 'ACCEPT_REPORT')
                  AND al.actor_type = 'USER'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: remoção administrativa de place_media (%) exige REMOVE ou ACCEPT_REPORT na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.status = 'ACTIVE' AND NEW.last_moderation_prev_status = 'UNDER_REVIEW' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.place_media_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'RELEASE_REVIEW'
                  AND al.actor_type = 'USER'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: liberação de análise de place_media (%) exige RELEASE_REVIEW na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.status = 'ACTIVE' AND NEW.last_moderation_prev_status = 'REMOVED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.place_media_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'RESTORE'
                  AND al.actor_type = 'USER'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: restauração de place_media (%) exige RESTORE na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    ELSIF current_user = 'rewit_media'
          AND OLD.status IS DISTINCT FROM NEW.status
          AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NEW.status = 'UNDER_REVIEW' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.place_media_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'AUTO_QUARANTINE'
                  AND al.actor_type = 'SYSTEM'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: quarentena automática de place_media (%) exige AUTO_QUARANTINE na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.status = 'REMOVED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.place_media_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = CASE NEW.removal_source
                        WHEN 'AUTHOR' THEN 'AUTHOR_REMOVE'
                        WHEN 'PLACE_MANAGER' THEN 'PLACE_MANAGER_REMOVE'
                        WHEN 'BUSINESS_OWNER' THEN 'BUSINESS_OWNER_REMOVE'
                        ELSE ''
                      END
                  AND al.actor_type = 'USER'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: remoção pública de place_media (%) exige evento correspondente em place_media_moderation_audit_logs na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_place_media_moderation_audit ON public.place_media;
CREATE CONSTRAINT TRIGGER trg_require_place_media_moderation_audit
    AFTER UPDATE ON public.place_media
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_place_media_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_require_place_media_report_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'ACCEPTED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.report_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'ACCEPT_REPORT'
                  AND al.actor_type = 'USER'
                  AND al.actor_user_id = NEW.decided_by_user_id
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: aceite em place_media_reports (%) exige ACCEPT_REPORT correspondente na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.status = 'REJECTED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.report_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'REJECT_REPORT'
                  AND al.actor_type = 'USER'
                  AND al.actor_user_id = NEW.decided_by_user_id
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: rejeição em place_media_reports (%) exige REJECT_REPORT correspondente na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.status = 'AUTO_RESOLVED' THEN
            IF NOT EXISTS (
                SELECT 1
                FROM public.place_media_moderation_audit_logs al
                WHERE al.report_id = NEW.id
                  AND al.txid = pg_catalog.txid_current()
                  AND al.action = 'AUTO_RESOLVE_REPORT'
                  AND al.actor_type = 'SYSTEM'
                  AND al.authenticity_mac IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: auto-resolução em place_media_reports (%) exige AUTO_RESOLVE_REPORT correspondente na mesma transação', NEW.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    ELSIF session_user = 'rewit_media'
          AND OLD.status IS DISTINCT FROM NEW.status
          AND NEW.status = 'AUTO_RESOLVED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.place_media_moderation_audit_logs al
            WHERE al.report_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.action = 'AUTO_RESOLVE_REPORT'
              AND al.actor_type = 'SYSTEM'
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: auto-resolução via rewit_media em place_media_reports (%) exige AUTO_RESOLVE_REPORT correspondente na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_place_media_report_moderation_audit ON public.place_media_reports;
CREATE CONSTRAINT TRIGGER trg_require_place_media_report_moderation_audit
    AFTER UPDATE ON public.place_media_reports
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_place_media_report_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_place_media_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NEW.action = 'REMOVE' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media pm
                WHERE pm.id = NEW.place_media_id
                  AND pm.last_moderation_txid = NEW.txid
                  AND pm.status = 'REMOVED'
                  AND pm.removal_source = 'MODERATOR'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento REMOVE sem transição correspondente em place_media (%) na mesma transação', NEW.place_media_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'RESTORE' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media pm
                WHERE pm.id = NEW.place_media_id
                  AND pm.last_moderation_txid = NEW.txid
                  AND pm.last_moderation_prev_status = 'REMOVED'
                  AND pm.status = 'ACTIVE'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento RESTORE sem transição REMOVED -> ACTIVE em place_media (%) na mesma transação', NEW.place_media_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'RELEASE_REVIEW' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media pm
                WHERE pm.id = NEW.place_media_id
                  AND pm.last_moderation_txid = NEW.txid
                  AND pm.last_moderation_prev_status = 'UNDER_REVIEW'
                  AND pm.status = 'ACTIVE'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento RELEASE_REVIEW sem transição UNDER_REVIEW -> ACTIVE em place_media (%) na mesma transação', NEW.place_media_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'ACCEPT_REPORT' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media_reports r
                WHERE r.id = NEW.report_id
                  AND r.decision_txid = NEW.txid
                  AND r.status = 'ACCEPTED'
                  AND r.decided_by_user_id = NEW.actor_user_id
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento ACCEPT_REPORT sem decisão ACCEPTED correspondente em place_media_reports (%) na mesma transação', NEW.report_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'REJECT_REPORT' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media_reports r
                WHERE r.id = NEW.report_id
                  AND r.decision_txid = NEW.txid
                  AND r.status = 'REJECTED'
                  AND r.decided_by_user_id = NEW.actor_user_id
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento REJECT_REPORT sem decisão REJECTED correspondente em place_media_reports (%) na mesma transação', NEW.report_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'AUTO_RESOLVE_REPORT' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media_reports r
                WHERE r.id = NEW.report_id
                  AND r.decision_txid = NEW.txid
                  AND r.status = 'AUTO_RESOLVED'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento AUTO_RESOLVE_REPORT sem resolução AUTO_RESOLVED correspondente em place_media_reports (%) na mesma transação', NEW.report_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    ELSIF current_user = 'rewit_media' THEN
        IF NEW.action IN ('AUTHOR_REMOVE', 'PLACE_MANAGER_REMOVE', 'BUSINESS_OWNER_REMOVE') THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media pm
                WHERE pm.id = NEW.place_media_id
                  AND pm.last_moderation_txid = NEW.txid
                  AND pm.status = 'REMOVED'
                  AND pm.removal_source = CASE NEW.action
                        WHEN 'AUTHOR_REMOVE' THEN 'AUTHOR'
                        WHEN 'PLACE_MANAGER_REMOVE' THEN 'PLACE_MANAGER'
                        WHEN 'BUSINESS_OWNER_REMOVE' THEN 'BUSINESS_OWNER'
                      END
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento % sem transição correspondente em place_media (%) na mesma transação', NEW.action, NEW.place_media_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'AUTO_QUARANTINE' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media pm
                WHERE pm.id = NEW.place_media_id
                  AND pm.last_moderation_txid = NEW.txid
                  AND pm.last_moderation_prev_status = 'ACTIVE'
                  AND pm.status = 'UNDER_REVIEW'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento AUTO_QUARANTINE sem transição ACTIVE -> UNDER_REVIEW em place_media (%) na mesma transação', NEW.place_media_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.action = 'AUTO_RESOLVE_REPORT' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.place_media_reports r
                WHERE r.id = NEW.report_id
                  AND r.decision_txid = NEW.txid
                  AND r.status = 'AUTO_RESOLVED'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: evento AUTO_RESOLVE_REPORT sem resolução AUTO_RESOLVED correspondente em place_media_reports (%) na mesma transação', NEW.report_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_place_media_moderation_transition ON public.place_media_moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_place_media_moderation_transition
    AFTER INSERT ON public.place_media_moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_place_media_moderation_transition();

-- 7.5 place_claim_requests & places & business_accounts <-> place_claim_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_require_place_claim_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.place_claim_moderation_audit_logs al
            WHERE al.claim_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.new_status = NEW.status
              AND al.moderator_user_id = NEW.decided_by_user_id
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: decisão em place_claim_requests (%) exige registro correspondente em place_claim_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_place_claim_moderation_audit ON public.place_claim_requests;
CREATE CONSTRAINT TRIGGER trg_require_place_claim_moderation_audit
    AFTER UPDATE ON public.place_claim_requests
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_place_claim_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_require_place_owner_claim_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.claimed_by_business_id IS DISTINCT FROM NEW.claimed_by_business_id THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.place_claim_moderation_audit_logs al
            WHERE al.place_id = NEW.id
              AND al.business_account_id = NEW.claimed_by_business_id
              AND al.txid = pg_catalog.txid_current()
              AND al.action = 'APPROVE_CLAIM'
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: vinculação comercial em places (%) exige aprovação auditada em place_claim_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_place_owner_claim_audit ON public.places;
CREATE CONSTRAINT TRIGGER trg_require_place_owner_claim_audit
    AFTER UPDATE OF claimed_by_business_id ON public.places
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_place_owner_claim_audit();

CREATE OR REPLACE FUNCTION public.fn_require_business_verification_claim_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.verification_status IS DISTINCT FROM NEW.verification_status THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.place_claim_moderation_audit_logs al
            WHERE al.business_account_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.action = 'APPROVE_CLAIM'
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: alteração de verification_status em business_accounts (%) exige aprovação auditada em place_claim_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_business_verification_claim_audit ON public.business_accounts;
CREATE CONSTRAINT TRIGGER trg_require_business_verification_claim_audit
    AFTER UPDATE OF verification_status ON public.business_accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_business_verification_claim_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_place_claim_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.place_claim_requests c
            WHERE c.id = NEW.claim_id
              AND c.decision_txid = NEW.txid
              AND c.status = NEW.new_status
              AND c.decided_by_user_id = NEW.moderator_user_id
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: registro em place_claim_moderation_audit_logs sem decisão correspondente em place_claim_requests (%) na mesma transação', NEW.claim_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NEW.action = 'APPROVE_CLAIM' THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.places p
                WHERE p.id = NEW.place_id AND p.claimed_by_business_id = NEW.business_account_id
            ) OR NOT EXISTS (
                SELECT 1 FROM public.business_accounts ba
                WHERE ba.id = NEW.business_account_id AND ba.verification_status = 'APPROVED'
            ) THEN
                RAISE EXCEPTION 'integridade decisão-auditoria: APPROVE_CLAIM exige local vinculado e conta comercial APPROVED na mesma transação'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_place_claim_moderation_transition ON public.place_claim_moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_place_claim_moderation_transition
    AFTER INSERT ON public.place_claim_moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_place_claim_moderation_transition();

-- 7.6 users <-> account_moderation_audit_logs
CREATE OR REPLACE FUNCTION public.fn_require_account_moderation_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod'
       AND OLD.account_status IS DISTINCT FROM NEW.account_status
       AND NEW.last_moderation_txid = pg_catalog.txid_current() THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.account_moderation_audit_logs al
            WHERE al.target_user_id = NEW.id
              AND al.txid = pg_catalog.txid_current()
              AND al.previous_status = NEW.last_moderation_prev_status
              AND al.new_status = NEW.account_status
              AND al.authenticity_mac IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: sanção administrativa em users (%) exige registro correspondente em account_moderation_audit_logs na mesma transação', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_require_account_moderation_audit ON public.users;
CREATE CONSTRAINT TRIGGER trg_require_account_moderation_audit
    AFTER UPDATE ON public.users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_require_account_moderation_audit();

CREATE OR REPLACE FUNCTION public.fn_verify_account_moderation_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF current_user = 'rewit_mod' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.users u
            WHERE u.id = NEW.target_user_id
              AND u.last_moderation_txid = NEW.txid
              AND u.last_moderation_prev_status = NEW.previous_status
              AND u.account_status = NEW.new_status
        ) THEN
            RAISE EXCEPTION 'integridade decisão-auditoria: registro em account_moderation_audit_logs sem transição real correspondente em users (%) na mesma transação', NEW.target_user_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp;

DROP TRIGGER IF EXISTS trg_verify_account_moderation_transition ON public.account_moderation_audit_logs;
CREATE CONSTRAINT TRIGGER trg_verify_account_moderation_transition
    AFTER INSERT ON public.account_moderation_audit_logs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.fn_verify_account_moderation_transition();


-- ------------------------------------------------------------------------------
-- 8. FUNÇÃO SECURITY DEFINER RESTRITA PARA AUTO-RESOLUÇÃO DE DENÚNCIAS DE MÍDIA REMOVIDA
-- ------------------------------------------------------------------------------
-- Em SECURITY DEFINER, current_user passa a ser o owner; session_user preserva a
-- identidade real da conexão chamadora e é explicitamente validado.
CREATE OR REPLACE FUNCTION public.rewit_auto_resolve_removed_place_media_reports(
    p_place_media_id UUID,
    p_decision_notes VARCHAR
)
RETURNS TABLE (report_id UUID, reason VARCHAR) AS $$
DECLARE
    v_session_is_owner BOOLEAN;
    v_media_status VARCHAR(20);
    v_removed_at TIMESTAMPTZ;
    v_removal_source VARCHAR(32);
    v_notes VARCHAR(500);
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(session_user, c.relowner, 'MEMBER')
    INTO v_session_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = 'public.place_media_reports'::regclass AND r.rolname = session_user;

    IF NOT (COALESCE(v_session_is_owner, FALSE) OR session_user = 'rewit_media') THEN
        RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: chamador % não autorizado', session_user
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF p_place_media_id IS NULL THEN
        RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: p_place_media_id não pode ser nulo';
    END IF;

    v_notes := btrim(COALESCE(p_decision_notes, ''));
    IF length(v_notes) = 0 OR char_length(v_notes) > 500 THEN
        RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: p_decision_notes deve ter entre 1 e 500 caracteres';
    END IF;

    SELECT pm.status, pm.removed_at, pm.removal_source
    INTO v_media_status, v_removed_at, v_removal_source
    FROM public.place_media pm
    WHERE pm.id = p_place_media_id
    FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: fotografia % inexistente', p_place_media_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF v_media_status IS DISTINCT FROM 'REMOVED' OR v_removed_at IS NULL THEN
        RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: auto-resolução restrita a fotografia já REMOVED'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- rewit_media só pode auto-resolver denúncias de mídia removida por origem não administrativa
    IF NOT COALESCE(v_session_is_owner, FALSE) THEN
        IF v_removal_source IS NULL OR v_removal_source NOT IN ('AUTHOR', 'BUSINESS_OWNER', 'PLACE_MANAGER') THEN
            RAISE EXCEPTION 'rewit_auto_resolve_removed_place_media_reports: origem de remoção % não autorizada para rewit_media',
                COALESCE(v_removal_source, 'NULL')
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;

    RETURN QUERY
    UPDATE public.place_media_reports r
    SET status = 'AUTO_RESOLVED',
        decided_by_user_id = NULL,
        decided_at = pg_catalog.clock_timestamp(),
        updated_at = pg_catalog.clock_timestamp(),
        decision_notes = v_notes,
        authenticity_mac = NULL,
        decision_txid = pg_catalog.txid_current()
    WHERE r.place_media_id = p_place_media_id
      AND r.status = 'PENDING'
    RETURNING r.id, r.reason;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_auto_resolve_removed_place_media_reports(UUID, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.rewit_auto_resolve_removed_place_media_reports(UUID, VARCHAR) FROM rewit_mod;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        REVOKE ALL ON FUNCTION public.rewit_auto_resolve_removed_place_media_reports(UUID, VARCHAR) FROM rewit_app;
    END IF;
END
$$;
GRANT EXECUTE ON FUNCTION public.rewit_auto_resolve_removed_place_media_reports(UUID, VARCHAR) TO rewit_media;

COMMENT ON FUNCTION public.rewit_auto_resolve_removed_place_media_reports(UUID, VARCHAR) IS
    'Auto-resolve exclusivamente denúncias PENDING de uma place_media já REMOVED por origem não administrativa (AUTHOR, BUSINESS_OWNER, PLACE_MANAGER). '
    'Restrita exclusivamente à role rewit_media (e owner); substitui UPDATE direto em place_media_reports (S3-A13-F2 / SEC-F2-03).';

-- ------------------------------------------------------------------------------
-- 8b. FUNÇÃO SECURITY DEFINER RESTRITA PARA LIBERAÇÃO DE QUARENTENA places/ APÓS EXPURGO
-- ------------------------------------------------------------------------------
-- Substitui DELETE direto de rewit_media em storage_object_quarantine. Remove somente a entrada
-- identificada, somente no namespace places/ e somente em CONFIRMED_ORPHAN (único estado que o
-- expurgo trava via lockConfirmedEntry). Entrada inexistente retorna FALSE (idempotência após
-- rollback/reprocessamento); namespace ou estado incompatível aborta com insufficient_privilege.
CREATE OR REPLACE FUNCTION public.rewit_release_place_quarantine_entry(p_entry_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    v_session_is_owner BOOLEAN;
    v_object_key TEXT;
    v_status VARCHAR(32);
BEGIN
    SELECT r.rolsuper OR pg_catalog.pg_has_role(session_user, c.relowner, 'MEMBER')
    INTO v_session_is_owner
    FROM pg_catalog.pg_class c, pg_catalog.pg_roles r
    WHERE c.oid = 'public.storage_object_quarantine'::regclass AND r.rolname = session_user;

    IF NOT (COALESCE(v_session_is_owner, FALSE) OR session_user = 'rewit_media') THEN
        RAISE EXCEPTION 'rewit_release_place_quarantine_entry: chamador % não autorizado', session_user
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF p_entry_id IS NULL THEN
        RAISE EXCEPTION 'rewit_release_place_quarantine_entry: p_entry_id não pode ser nulo';
    END IF;

    SELECT q.object_key, q.status
    INTO v_object_key, v_status
    FROM public.storage_object_quarantine q
    WHERE q.id = p_entry_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    IF pg_catalog.left(v_object_key, 7) IS DISTINCT FROM 'places/' THEN
        RAISE EXCEPTION 'rewit_release_place_quarantine_entry: entrada fora do namespace places/'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF v_status IS DISTINCT FROM 'CONFIRMED_ORPHAN' THEN
        RAISE EXCEPTION 'rewit_release_place_quarantine_entry: entrada em estado % incompatível com liberação', v_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    DELETE FROM public.storage_object_quarantine q
    WHERE q.id = p_entry_id
      AND q.status = 'CONFIRMED_ORPHAN'
      AND pg_catalog.left(q.object_key, 7) = 'places/';

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION public.rewit_release_place_quarantine_entry(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.rewit_release_place_quarantine_entry(UUID) FROM rewit_mod;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app') THEN
        REVOKE ALL ON FUNCTION public.rewit_release_place_quarantine_entry(UUID) FROM rewit_app;
    END IF;
END
$$;
GRANT EXECUTE ON FUNCTION public.rewit_release_place_quarantine_entry(UUID) TO rewit_media;

COMMENT ON FUNCTION public.rewit_release_place_quarantine_entry(UUID) IS
    'Libera (DELETE) uma única entrada CONFIRMED_ORPHAN do namespace places/ em storage_object_quarantine após '
    'exclusão física confirmada (ADR-015). Restrita a rewit_media (e owner); nunca remove reviews/ nem outros namespaces.';

-- Revoga também funções SECURITY DEFINER da V44 de rewit_mod e rewit_media (privilégio mínimo estrito)
REVOKE ALL ON FUNCTION public.rewit_purge_expired_auth_sessions(TIMESTAMPTZ, INT) FROM rewit_mod;
REVOKE ALL ON FUNCTION public.rewit_purge_deleted_user_auth_sessions(UUID) FROM rewit_mod;
REVOKE ALL ON FUNCTION public.rewit_purge_expired_auth_sessions(TIMESTAMPTZ, INT) FROM rewit_media;
REVOKE ALL ON FUNCTION public.rewit_purge_deleted_user_auth_sessions(UUID) FROM rewit_media;


-- ------------------------------------------------------------------------------
-- 9. ATUALIZAÇÃO DE rewit_assert_runtime_privileges() PARA rewit_app + rewit_media + rewit_mod + V45
-- ------------------------------------------------------------------------------
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
        ['storage_object_quarantine', 'trg_guard_storage_quarantine_media_writes']
    ];
    -- [nome, assinatura, role_autorizada]
    allowed_secdef_functions TEXT[][] := ARRAY[
        ['rewit_purge_expired_auth_sessions', 'p_now timestamp with time zone, p_limit integer', 'rewit_app'],
        ['rewit_purge_deleted_user_auth_sessions', 'p_user_id uuid', 'rewit_app'],
        ['rewit_auto_resolve_removed_place_media_reports', 'p_place_media_id uuid, p_decision_notes character varying', 'rewit_media'],
        ['rewit_release_place_quarantine_entry', 'p_entry_id uuid', 'rewit_media']
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

    -- 2. Privilégios de rewit_app no schema public (sem escrita em auditorias, place_media UPDATE ou place_media_reports)
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
         OR (p.priv = 'UPDATE' AND (c.relname = ANY (all_audit_tables) OR c.relname = ANY (report_and_claim_tables) OR c.relname = 'place_media'))
         OR (p.priv = 'INSERT' AND (c.relname = ANY (all_audit_tables) OR c.relname IN ('place_media_reports', 'place_claim_requests')))
         OR (p.priv IN ('INSERT', 'UPDATE') AND c.relname = 'flyway_schema_history')
         OR (p.priv IN ('INSERT', 'UPDATE')
             AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'))
      );
    IF v_list IS NOT NULL THEN
        problems := problems || format('rewit_app com privilégios indevidos: %s', v_list);
    END IF;

    -- Colunas protegidas de place_media, place_media_reports e place_claim_requests inacessíveis para rewit_app
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
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'authenticity_mac', 'INSERT')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'authenticity_mac', 'UPDATE')
       OR has_column_privilege('rewit_media', 'public.place_media_reports', 'status', 'UPDATE') THEN
        problems := problems || 'rewit_media com escrita em colunas de decisão de place_media_reports'::TEXT;
    END IF;

    -- 3. Privilégios de rewit_mod no schema public (privilégio mínimo para os 6 módulos)
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

    -- 8. Governança de funções SECURITY DEFINER no schema public (V44 + V45 + SEC-F2-03)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_list
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prosecdef
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
      AND p.proname NOT IN ('rewit_purge_expired_auth_sessions', 'rewit_purge_deleted_user_auth_sessions', 'rewit_auto_resolve_removed_place_media_reports',
                            'rewit_release_place_quarantine_entry');
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
        problems := problems || 'rewit_assert_runtime_privileges com EXECUTE indevido'::TEXT;
    END IF;

    IF array_length(problems, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Privilégios do runtime fora da política: %', array_to_string(problems, '; ')
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$ LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public, pg_temp;

REVOKE EXECUTE ON FUNCTION public.rewit_assert_runtime_privileges() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.rewit_assert_runtime_privileges() FROM rewit_app;
REVOKE EXECUTE ON FUNCTION public.rewit_assert_runtime_privileges() FROM rewit_media;
REVOKE EXECUTE ON FUNCTION public.rewit_assert_runtime_privileges() FROM rewit_mod;

COMMENT ON FUNCTION public.rewit_assert_runtime_privileges() IS
    'Aborta se rewit_app, rewit_media ou rewit_mod tiverem privilégios fora da política (V37/V44/V45/SEC-F2-03): '
    'separação de moderação e ciclo de vida de mídias, auditorias append-only, ausência de escrita em auditorias ou '
    'place_media UPDATE em rewit_app, privilégio mínimo em rewit_media e rewit_mod, guards e constraint triggers ativos '
    'e governança estrita das funções SECURITY DEFINER.';

-- 10. VERIFICAÇÃO FINAL NA PRÓPRIA MIGRATION
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_app')
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_media')
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rewit_mod') THEN
        PERFORM public.rewit_assert_runtime_privileges();
    END IF;
END
$$;
