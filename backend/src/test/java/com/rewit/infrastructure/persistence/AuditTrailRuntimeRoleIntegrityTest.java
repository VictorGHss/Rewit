package com.rewit.infrastructure.persistence;

import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.config.TestInfrastructureConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Imutabilidade efetiva das trilhas de auditoria e das decisões legadas sob o login REAL da role de
 * runtime (AO-2, LEG-1). Todas as operações de negócio e todos os ataques usam uma conexão autenticada
 * como {@code TEST_APP_DB_USER}; a conexão de migração só confere o catálogo e simula o caminho da
 * migração histórica. Cada prova consulta a trilha depois da operação (conteúdo por hash da linha),
 * não apenas o resultado do comando.
 */
@DisplayName("AO-2 / LEG-1: trilhas de auditoria e decisões legadas imutáveis sob login real da role de runtime")
class AuditTrailRuntimeRoleIntegrityTest {

    private static final TestInfrastructureConfig INFRASTRUCTURE = TestInfrastructureConfig.current();

    private static final String TEST_VALID_MAC = "aud1$v1$" + "a".repeat(64);

    private static final List<String> AUDIT_TABLES = List.of(
            "moderation_audit_logs",
            "discussion_moderation_audit_logs",
            "business_response_moderation_audit_logs",
            "place_media_moderation_audit_logs",
            "account_moderation_audit_logs",
            "place_claim_moderation_audit_logs");

    private static final List<String> ADMIN_ONLY_AUDIT_TABLES = List.of(
            "moderation_audit_logs",
            "discussion_moderation_audit_logs",
            "business_response_moderation_audit_logs",
            "account_moderation_audit_logs",
            "place_claim_moderation_audit_logs");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(INFRASTRUCTURE.datasourceUrl(), INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword())
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(INFRASTRUCTURE.datasourceUrl(), INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.appDbPassword());
    }

    private static Connection mod() throws SQLException {
        return DriverManager.getConnection(INFRASTRUCTURE.datasourceUrl(), INFRASTRUCTURE.modDbUser(), INFRASTRUCTURE.modDbPassword());
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(INFRASTRUCTURE.datasourceUrl(), INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword());
    }

    // ------------------------------------------------------------------------------------------ cenário

    /** Avaliação com comentário, respostas comerciais (na avaliação e no comentário), mídia e 5 auditorias. */
    private record Scenario(UUID moderator, UUID author, UUID place, UUID business, UUID review, UUID discussion,
                            UUID responseOnReview, UUID responseOnDiscussion, UUID media,
                            UUID reviewAudit, UUID discussionAudit, UUID responseAudit, UUID discussionResponseAudit,
                            UUID mediaAudit) {
    }

    /** Semeia o cenário completo (entidades públicas + aprovações/auditorias administrativas) via conexão owner. */
    private static Scenario seedScenario() throws SQLException {
        try (Connection conn = owner()) {
            UUID moderator = insertUser(conn, "MODERATOR");
            UUID author = insertUser(conn, "USER");
            UUID businessAdmin = insertUser(conn, "USER");
            UUID place = insertPlace(conn);
            UUID business = queryUuid(conn, "INSERT INTO business_accounts (user_id, corporate_name, tax_id, verification_status) "
                    + "VALUES (?, 'Empresa AO-2', ?, 'APPROVED') RETURNING id", businessAdmin,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 14));
            update(conn, "UPDATE places SET claimed_by_business_id = ? WHERE id = ?", business, place);
            UUID review = UUID.randomUUID();
            update(conn, "INSERT INTO reviews (id, user_id) VALUES (?, ?)", review, author);
            update(conn, "INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 4)", review, place);
            UUID discussion = UUID.randomUUID();
            update(conn, "INSERT INTO review_discussions (id, review_id, user_id, content) VALUES (?, ?, ?, 'Comentário AO-2')",
                    discussion, review, author);
            UUID responseOnReview = UUID.randomUUID();
            update(conn, "INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content) "
                    + "VALUES (?, ?, ?, ?, ?, 'Resposta na avaliação')", responseOnReview, review, business, place, businessAdmin);
            UUID responseOnDiscussion = UUID.randomUUID();
            update(conn, "INSERT INTO business_responses (id, review_id, discussion_id, business_account_id, place_id, author_user_id, content) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'Resposta no comentário')", responseOnDiscussion, review, discussion, business, place, businessAdmin);
            UUID media = UUID.randomUUID();
            update(conn, "INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                    + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 800, 600, 'ACTIVE')", media, place, author, "places/ao2-" + media + ".jpg");

            UUID reviewAudit = queryUuid(conn, "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, "
                    + "justification, previous_review_status, new_review_status, authenticity_mac) VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', "
                    + "'Remoção auditada', 'ACTIVE', 'REMOVED', ?) RETURNING id", review, moderator, TEST_VALID_MAC);
            UUID discussionAudit = queryUuid(conn, "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, "
                    + "reason_code, justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', "
                    + "'Remoção auditada', 'ACTIVE', 'REMOVED', ?) RETURNING id", discussion, moderator, TEST_VALID_MAC);
            UUID responseAudit = queryUuid(conn, "INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, "
                    + "reason_code, justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Remoção auditada', "
                    + "'ACTIVE', 'REMOVED', ?) RETURNING id", responseOnReview, moderator, TEST_VALID_MAC);
            UUID discussionResponseAudit = queryUuid(conn, "INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, "
                    + "action, reason_code, justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Remoção auditada', "
                    + "'ACTIVE', 'REMOVED', ?) RETURNING id", responseOnDiscussion, moderator, TEST_VALID_MAC);
            UUID mediaAudit = UUID.randomUUID();
            update(conn, "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                    + "VALUES (?, ?, ?, ?, 'USER', 'REMOVE', 'Remoção auditada', ?)", mediaAudit, media, place, moderator, TEST_VALID_MAC);

            return new Scenario(moderator, author, place, business, review, discussion, responseOnReview, responseOnDiscussion,
                    media, reviewAudit, discussionAudit, responseAudit, discussionResponseAudit, mediaAudit);
        }
    }

    /** Hash do conteúdo completo das linhas de auditoria do cenário: qualquer alteração ou remoção muda o valor. */
    private static String auditFingerprint(Connection conn, Scenario s) throws SQLException {
        StringBuilder all = new StringBuilder();
        all.append(rowHash(conn, "moderation_audit_logs", s.reviewAudit()));
        all.append(rowHash(conn, "discussion_moderation_audit_logs", s.discussionAudit()));
        all.append(rowHash(conn, "business_response_moderation_audit_logs", s.responseAudit()));
        all.append(rowHash(conn, "business_response_moderation_audit_logs", s.discussionResponseAudit()));
        all.append(rowHash(conn, "place_media_moderation_audit_logs", s.mediaAudit()));
        return all.toString();
    }

    private static String rowHash(Connection conn, String table, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT md5(t::text) FROM " + table + " t WHERE t.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) + ";" : "AUSENTE;";
            }
        }
    }

    private static void assertAllAuditsPresent(Connection conn, Scenario s) throws SQLException {
        assertFalse(auditFingerprint(conn, s).contains("AUSENTE"), "Todas as auditorias do cenário devem existir");
    }

    // ------------------------------------------------------------------------------------------ AO-2

    @Test
    @DisplayName("AO-2 / F2: nem rewit_app nem rewit_mod conseguem mutar/truncar/desativar triggers das 6 trilhas de auditoria")
    void runtimeCannotMutateAuditRowsDirectly() throws SQLException {
        Scenario s = seedScenario();
        try (Connection app = app(); Connection mod = mod()) {
            String before = auditFingerprint(app, s);
            assertAllAuditsPresent(app, s);

            Map<String, UUID> rowByTable = Map.of(
                    "moderation_audit_logs", s.reviewAudit(),
                    "discussion_moderation_audit_logs", s.discussionAudit(),
                    "business_response_moderation_audit_logs", s.responseAudit(),
                    "place_media_moderation_audit_logs", s.mediaAudit());
            for (Connection conn : List.of(app, mod)) {
                for (String table : AUDIT_TABLES) {
                    UUID id = rowByTable.getOrDefault(table, UUID.randomUUID());
                    assertDenied(conn, "UPDATE " + table + " SET created_at = created_at - INTERVAL '1 day' WHERE id = ?", id);
                    assertDenied(conn, "DELETE FROM " + table + " WHERE id = ?", id);
                    assertDenied(conn, "TRUNCATE TABLE " + table);
                    assertDenied(conn, "TRUNCATE TABLE " + table + " CASCADE");
                    assertDenied(conn, "ALTER TABLE " + table + " DISABLE TRIGGER ALL");
                }
                // Desligar triggers da sessão (inclusive os de FK e append-only) exige privilégio administrativo
                assertDenied(conn, "SET session_replication_role = replica");
            }

            assertEquals(before, auditFingerprint(app, s), "Conteúdo das auditorias não pode mudar");
        }
    }

    @Test
    @DisplayName("P3-1: runtime não apaga avaliação, comentário nem resposta; exclusão administrativa cascateia sem alcançar as trilhas")
    void parentsAreNotDeletableByRuntimeAndAdministrativeDeletionKeepsAudits() throws SQLException {
        Scenario s = seedScenario();
        try (Connection app = app(); Connection owner = owner()) {
            String before = auditFingerprint(app, s);

            // Snapshot do contexto gravado na inserção, derivado da entidade e não do chamador
            assertEquals(s.review(), queryUuid(app, "SELECT review_id FROM discussion_moderation_audit_logs WHERE id = ?", s.discussionAudit()));
            assertEquals(s.review(), queryUuid(app, "SELECT review_id FROM business_response_moderation_audit_logs WHERE id = ?", s.responseAudit()));
            assertEquals(s.business(), queryUuid(app, "SELECT business_account_id FROM business_response_moderation_audit_logs WHERE id = ?", s.responseAudit()));

            // Exclusão lógica no produto: a role de runtime não tem DELETE nessas entidades
            assertDenied(app, "DELETE FROM review_discussions WHERE id = ?", s.discussion());
            assertDenied(app, "DELETE FROM business_responses WHERE id = ?", s.responseOnReview());
            assertDenied(app, "DELETE FROM reviews WHERE id = ?", s.review());
            assertEquals(1, count(app, "SELECT count(*) FROM reviews WHERE id = ?", s.review()));
            assertEquals(1, count(app, "SELECT count(*) FROM review_discussions WHERE id = ?", s.discussion()));
            assertEquals(2, count(app, "SELECT count(*) FROM business_responses WHERE review_id = ?", s.review()));

            // Exclusão administrativa: comentário cascateia sua resposta, nunca as auditorias
            assertEquals(1, update(owner, "DELETE FROM review_discussions WHERE id = ?", s.discussion()));
            assertEquals(0, count(app, "SELECT count(*) FROM business_responses WHERE id = ?", s.responseOnDiscussion()));
            assertEquals(before, auditFingerprint(app, s), "Excluir o comentário não pode alterar auditorias");
            assertEquals(1, update(owner, "DELETE FROM business_responses WHERE id = ?", s.responseOnReview()));
            assertEquals(before, auditFingerprint(app, s), "Excluir a resposta não pode alterar auditorias");
            assertEquals(1, update(owner, "DELETE FROM reviews WHERE id = ?", s.review()));
            assertEquals(before, auditFingerprint(app, s), "Excluir a avaliação não pode alterar auditorias");

            // Identificadores históricos permanecem interpretáveis mesmo sem a entidade operacional
            assertEquals(1, count(app, "SELECT count(*) FROM moderation_audit_logs WHERE review_id = ?", s.review()));
            assertEquals(1, count(app, "SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ? AND review_id = ?",
                    s.discussion(), s.review()));
            assertEquals(2, count(app, "SELECT count(*) FROM business_response_moderation_audit_logs WHERE review_id = ?", s.review()));

            // Moderador continua protegido por RESTRICT: o histórico não perde o responsável
            assertDenied(app, "DELETE FROM users WHERE id = ?", s.moderator());
            assertEquals(before, auditFingerprint(app, s));
        }
    }

    @Test
    @DisplayName("Mudanças de estado e operações em lote nas tabelas pai não removem nem alteram auditorias")
    void parentStateChangesAndBulkOperationsDoNotTouchAudits() throws SQLException {
        Scenario s = seedScenario();
        try (Connection app = app(); Connection owner = owner()) {
            String before = auditFingerprint(app, s);

            update(owner, "UPDATE reviews SET status = 'REMOVED' WHERE id = ?", s.review());
            update(owner, "UPDATE review_discussions SET status = 'REMOVED' WHERE id = ?", s.discussion());
            update(owner, "UPDATE business_responses SET status = 'REMOVED', removed_by = 'MODERATION', removed_at = NOW() WHERE id = ?",
                    s.responseOnReview());
            update(owner, "UPDATE place_media SET status = 'REMOVED', removal_source = 'MODERATOR', removed_at = NOW() WHERE id = ?", s.media());
            // Troca de titular dispara as invalidações em lote de V28/V29
            update(owner, "UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", s.place());
            // Rebaixar o moderador é administrativo (o runtime é recusado); a autoria registrada permanece
            assertDenied(app, "UPDATE users SET role = 'USER' WHERE id = ?", s.moderator());
            PrivilegedTestDatabase.assignRole(s.moderator(), "USER");
            update(owner, "UPDATE reviews SET status = 'ACTIVE' WHERE id = ?", s.review());

            // TRUNCATE em cascata das tabelas pai é negado ao runtime
            assertDenied(app, "TRUNCATE TABLE reviews CASCADE");
            assertDenied(app, "TRUNCATE TABLE review_discussions, business_responses CASCADE");

            assertEquals(before, auditFingerprint(app, s), "Estado das tabelas pai não pode afetar auditorias");
        }
    }

    @Test
    @DisplayName("Catálogo: nenhuma FK das trilhas cascateia ou depende de entidade apagável e nenhuma função muta as trilhas")
    void catalogHasNoIndirectDeletionPathIntoAuditTrails() throws SQLException {
        try (Connection conn = owner()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.conrelid::regclass::text, c.conname, c.confrelid::regclass::text, c.confdeltype
                    FROM pg_constraint c
                    WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (?)
                    """)) {
                ps.setArray(1, conn.createArrayOf("text", AUDIT_TABLES.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertEquals("users", rs.getString(3), "FK inesperada em " + rs.getString(1) + ": " + rs.getString(2));
                        assertTrue(List.of("r", "a").contains(rs.getString(4)),
                                "FK " + rs.getString(2) + " precisa ser RESTRICT/NO ACTION, não " + rs.getString(4));
                    }
                }
            }

            // Nenhuma função do schema contém DELETE/UPDATE/TRUNCATE dirigido a uma tabela de auditoria
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT p.proname
                         FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'public'
                           AND p.prosrc ~* '(delete\\s+from|update|truncate(\\s+table)?)\\s+(only\\s+)?(public\\.)?[a-z_]*audit_logs'
                         """)) {
                List<String> offenders = new ArrayList<>();
                while (rs.next()) {
                    offenders.add(rs.getString(1));
                }
                assertTrue(offenders.isEmpty(), "Funções que mutam auditoria: " + offenders);
            }

            // P2-1: todas as funções de trigger com search_path fixo e pg_temp por último
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'public' AND p.prorettype = 'trigger'::regtype
                           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                           WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
                           AND NOT (COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'])
                         """)) {
                List<String> unpinned = new ArrayList<>();
                while (rs.next()) {
                    unpinned.add(rs.getString(1));
                }
                assertTrue(unpinned.isEmpty(), "Funções de trigger sem search_path fixo: " + unpinned);
            }
            assertEquals(0, count(conn, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef "
                    + "AND p.proname NOT IN ('rewit_purge_expired_auth_sessions', 'rewit_purge_deleted_user_auth_sessions', "
                    + "'rewit_auto_resolve_removed_place_media_reports', 'rewit_release_place_quarantine_entry', "
                    + "'rewit_confirm_storage_quarantine_entry')"),
                    "Nenhuma função SECURITY DEFINER não autorizada no schema");
            assertEquals(2, count(conn, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef "
                    + "AND p.proname IN ('rewit_purge_expired_auth_sessions', 'rewit_purge_deleted_user_auth_sessions') "
                    + "AND COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'] "
                    + "AND NOT has_function_privilege('public', p.oid, 'EXECUTE') "
                    + "AND has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE')",
                    INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.mediaDbUser(), INFRASTRUCTURE.modDbUser()),
                    "Funções SECURITY DEFINER de auth_sessions devem ser exclusivas de rewit_app");
            assertEquals(1, count(conn, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef "
                    + "AND p.proname = 'rewit_auto_resolve_removed_place_media_reports' "
                    + "AND COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'] "
                    + "AND NOT has_function_privilege('public', p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE')",
                    INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.mediaDbUser(), INFRASTRUCTURE.modDbUser()),
                    "Função SECURITY DEFINER de auto-resolução de place_media_reports deve ser exclusiva de rewit_media (SEC-F2-03)");
            assertEquals(1, count(conn, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef "
                    + "AND p.proname = 'rewit_release_place_quarantine_entry' "
                    + "AND COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'] "
                    + "AND NOT has_function_privilege('public', p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE')",
                    INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.mediaDbUser(), INFRASTRUCTURE.modDbUser()),
                    "Função SECURITY DEFINER de liberação de quarentena de place_media deve ser exclusiva de rewit_media");
            assertEquals(1, count(conn, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef "
                    + "AND p.proname = 'rewit_confirm_storage_quarantine_entry' "
                    + "AND COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'] "
                    + "AND NOT has_function_privilege('public', p.oid, 'EXECUTE') "
                    + "AND has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE') "
                    + "AND NOT has_function_privilege(?, p.oid, 'EXECUTE')",
                    INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.mediaDbUser(), INFRASTRUCTURE.modDbUser()),
                    "Função SECURITY DEFINER de confirmação de quarentena deve ser exclusiva de rewit_app (SEC-F2-04)");
            // P2-2 / P3-1 / F1-AUD-02 / F2: sem DELETE direto para o runtime
            for (String table : List.of("place_media_reports", "reviews", "review_discussions", "business_responses", "auth_sessions")) {
                assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, 'DELETE')", INFRASTRUCTURE.appDbUser(), table),
                        INFRASTRUCTURE.appDbUser() + " não pode ter DELETE em " + table);
            }

            for (String table : AUDIT_TABLES) {
                assertEquals(3, count(conn, """
                        SELECT count(DISTINCT op) FROM (
                            SELECT CASE WHEN (tgtype & 8) <> 0 THEN 'D' END AS op FROM pg_trigger
                            WHERE tgrelid = ?::regclass AND NOT tgisinternal AND tgenabled <> 'D'
                            UNION ALL
                            SELECT CASE WHEN (tgtype & 16) <> 0 THEN 'U' END FROM pg_trigger
                            WHERE tgrelid = ?::regclass AND NOT tgisinternal AND tgenabled <> 'D'
                            UNION ALL
                            SELECT CASE WHEN (tgtype & 32) <> 0 THEN 'T' END FROM pg_trigger
                            WHERE tgrelid = ?::regclass AND NOT tgisinternal AND tgenabled <> 'D'
                        ) ops WHERE op IS NOT NULL
                        """, table, table, table), "Guards de UPDATE, DELETE e TRUNCATE ativos em " + table);
                for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                    assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, ?)", INFRASTRUCTURE.appDbUser(), table, privilege),
                            INFRASTRUCTURE.appDbUser() + " não pode ter " + privilege + " em " + table);
                    assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, ?)", INFRASTRUCTURE.mediaDbUser(), table, privilege),
                            INFRASTRUCTURE.mediaDbUser() + " não pode ter " + privilege + " em " + table);
                    assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, ?)", INFRASTRUCTURE.modDbUser(), table, privilege),
                            INFRASTRUCTURE.modDbUser() + " não pode ter " + privilege + " em " + table);
                }
                assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, 'INSERT')", INFRASTRUCTURE.appDbUser(), table),
                        INFRASTRUCTURE.appDbUser() + " não pode ter INSERT em nenhuma trilha de auditoria (" + table + ")");
                assertTrue(bool(conn, "SELECT has_table_privilege(?, ?, 'INSERT')", INFRASTRUCTURE.modDbUser(), table),
                        INFRASTRUCTURE.modDbUser() + " precisa de INSERT append-only em " + table);
                if (ADMIN_ONLY_AUDIT_TABLES.contains(table)) {
                    assertFalse(bool(conn, "SELECT has_table_privilege(?, ?, 'INSERT')", INFRASTRUCTURE.mediaDbUser(), table),
                            INFRASTRUCTURE.mediaDbUser() + " não pode ter INSERT em trilha administrativa " + table);
                } else {
                    assertTrue(bool(conn, "SELECT has_table_privilege(?, ?, 'INSERT')", INFRASTRUCTURE.mediaDbUser(), table),
                            INFRASTRUCTURE.mediaDbUser() + " precisa de INSERT append-only em " + table);
                }
            }
        }
    }

    @Test
    @DisplayName("Inserção de auditoria referente a entidade inexistente é rejeitada (validação equivalente à FK)")
    void auditInsertRequiresExistingEntity() throws SQLException {
        try (Connection app = app(); Connection mod = mod()) {
            UUID moderator = insertUser(app, "MODERATOR");
            for (Connection conn : List.of(app, mod)) {
                assertDenied(conn, "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, "
                        + "previous_review_status, new_review_status, authenticity_mac) VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'x', 'ACTIVE', 'REMOVED', ?)",
                        UUID.randomUUID(), moderator, TEST_VALID_MAC);
                assertDenied(conn, "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, "
                        + "justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', 'x', 'ACTIVE', 'REMOVED', ?)",
                        UUID.randomUUID(), moderator, TEST_VALID_MAC);
                assertDenied(conn, "INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, reason_code, "
                        + "justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'x', 'ACTIVE', 'REMOVED', ?)",
                        UUID.randomUUID(), moderator, TEST_VALID_MAC);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ LEG-1

    private record ReportFixture(UUID moderator, UUID regularUser, UUID media) {
    }

    private static ReportFixture seedReportFixture(Connection app) throws SQLException {
        UUID moderator = insertUser(app, "MODERATOR");
        UUID regular = insertUser(app, "USER");
        UUID place = insertPlace(app);
        UUID media = UUID.randomUUID();
        update(app, "INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 800, 600, 'ACTIVE')", media, place, regular, "places/leg1-" + media + ".jpg");
        return new ReportFixture(moderator, regular, media);
    }

    private static Connection media() throws SQLException {
        return DriverManager.getConnection(INFRASTRUCTURE.datasourceUrl(), INFRASTRUCTURE.mediaDbUser(), INFRASTRUCTURE.mediaDbPassword());
    }

    private static UUID insertPendingReport(Connection app, UUID media) throws SQLException {
        UUID reporter = insertUser(app, "USER");
        UUID report = UUID.randomUUID();
        try (Connection mediaConn = media()) {
            update(mediaConn, "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason) VALUES (?, ?, ?, 'SPAM')",
                    report, media, reporter);
        }
        return report;
    }

    @Test
    @DisplayName("LEG-1 / F2 / SEC-F2-03: nenhuma role de runtime fabrica decisão legada; decisões humanas exigem rewit_mod + auditoria + MAC")
    void runtimeCannotFabricateLegacyDecisions() throws SQLException {
        try (Connection app = app(); Connection mediaConn = media(); Connection mod = mod()) {
            ReportFixture f = seedReportFixture(app);
            UUID place = queryUuid(app, "SELECT place_id FROM place_media WHERE id = ?", f.media());

            // Inserção normal permitida pelo domínio sob rewit_media (coluna legada nunca informada)
            UUID report = insertPendingReport(app, f.media());
            assertFalse(bool(app, "SELECT is_legacy_decision FROM place_media_reports WHERE id = ?", report));

            // Inserção direta como decisão legada: negada por privilégio de coluna (tanto para rewit_app quanto rewit_media)
            UUID reporter = insertUser(app, "USER");
            for (Connection conn : List.of(app, mediaConn)) {
                assertDenied(conn, "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_at, is_legacy_decision) "
                        + "VALUES (?, ?, ?, 'SPAM', 'ACCEPTED', NOW(), TRUE)", UUID.randomUUID(), f.media(), reporter);
            }

            // Nenhuma role de runtime fabrica decisão legada ou decisão sem moderador válido
            for (Connection conn : List.of(app, mediaConn, mod)) {
                assertDenied(conn, "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = NULL, is_legacy_decision = TRUE "
                        + "WHERE id = ?", report);
                assertDenied(conn, "UPDATE place_media_reports SET is_legacy_decision = TRUE WHERE id = ?", report);
                assertDenied(conn, "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = NULL, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                        TEST_VALID_MAC, report);
                assertDenied(conn, "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                        f.regularUser(), TEST_VALID_MAC, report);
                assertDenied(conn, "UPDATE place_media_reports SET status = 'REJECTED', decided_by_user_id = ?, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                        UUID.randomUUID(), TEST_VALID_MAC, report);
            }
            assertEquals("PENDING", string(app, "SELECT status FROM place_media_reports WHERE id = ?", report));
            assertFalse(bool(app, "SELECT is_legacy_decision FROM place_media_reports WHERE id = ?", report));

            // Aceite, rejeição e auto-resolução legítimos sob rewit_mod com trilha de auditoria na mesma transação
            decideReportWithAudit(mod, f.media(), place, report, f.moderator(), "ACCEPTED", "ACCEPT_REPORT", "ok");
            UUID rejected = insertPendingReport(app, f.media());
            decideReportWithAudit(mod, f.media(), place, rejected, f.moderator(), "REJECTED", "REJECT_REPORT", "improcedente");
            UUID autoResolved = insertPendingReport(app, f.media());
            decideReportWithAudit(mod, f.media(), place, autoResolved, null, "AUTO_RESOLVED", "AUTO_RESOLVE_REPORT", "auto");
            assertDenied(mod, "UPDATE place_media_reports SET is_legacy_decision = TRUE WHERE id = ?", autoResolved);

            // Nenhuma role de runtime desliga o guard nem tem privilégio na coluna legada
            for (Connection conn : List.of(app, mediaConn, mod)) {
                assertDenied(conn, "ALTER TABLE place_media_reports DISABLE TRIGGER trg_guard_place_media_report_decision");
                assertFalse(bool(conn, "SELECT has_column_privilege(current_user, 'place_media_reports', 'is_legacy_decision', 'UPDATE')"));
                assertFalse(bool(conn, "SELECT has_column_privilege(current_user, 'place_media_reports', 'is_legacy_decision', 'INSERT')"));
                assertFalse(bool(conn, "SELECT has_table_privilege(current_user, 'place_media_reports', 'UPDATE')"),
                        "UPDATE de tabela cobriria todas as colunas; só privilégios de coluna são concedidos");
            }
        }
    }

    @Test
    @DisplayName("LEG-1: o guard vale para qualquer role (inclusive owner) e decisões legadas históricas são legíveis e imutáveis")
    void legacyDecisionsAreReadableButImmutable() throws SQLException {
        UUID legacyReport = UUID.randomUUID();
        ReportFixture f;
        try (Connection app = app()) {
            f = seedReportFixture(app);
        }
        try (Connection conn = owner()) {
            UUID reporter;
            try (Connection app = app()) {
                reporter = insertUser(app, "USER");
            }
            // Mesmo o owner é barrado pelo trigger: decisões legadas não nascem fora da migração histórica
            assertDenied(conn, "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_at, is_legacy_decision) "
                    + "VALUES (?, ?, ?, 'SPAM', 'ACCEPTED', NOW(), TRUE)", legacyReport, f.media(), reporter);

            // Caminho da migração histórica: só o owner pode desabilitar o guard explicitamente
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE place_media_reports DISABLE TRIGGER trg_guard_place_media_report_decision");
                update(conn, "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_at, is_legacy_decision) "
                        + "VALUES (?, ?, ?, 'SPAM', 'ACCEPTED', NOW() - INTERVAL '30 days', TRUE)", legacyReport, f.media(), reporter);
                st.execute("ALTER TABLE place_media_reports ENABLE TRIGGER trg_guard_place_media_report_decision");
            }
            conn.commit();
            conn.setAutoCommit(true);
        }

        try (Connection app = app()) {
            assertTrue(bool(app, "SELECT is_legacy_decision FROM place_media_reports WHERE id = ?", legacyReport));
            assertEquals("ACCEPTED", string(app, "SELECT status FROM place_media_reports WHERE id = ?", legacyReport));

            assertDenied(app, "UPDATE place_media_reports SET status = 'REJECTED' WHERE id = ?", legacyReport);
            assertDenied(app, "UPDATE place_media_reports SET decided_by_user_id = ? WHERE id = ?", f.moderator(), legacyReport);
            assertDenied(app, "UPDATE place_media_reports SET is_legacy_decision = FALSE WHERE id = ?", legacyReport);
            assertDenied(app, "UPDATE place_media_reports SET status = 'PENDING', decided_at = NULL WHERE id = ?", legacyReport);
            assertEquals("ACCEPTED", string(app, "SELECT status FROM place_media_reports WHERE id = ?", legacyReport));
            assertTrue(bool(app, "SELECT is_legacy_decision FROM place_media_reports WHERE id = ?", legacyReport));
        }
    }

    // ------------------------------------------------------------------------------------------ P2-1

    /** Tabelas temporárias com os nomes das reais, populadas com dados forjados que tentariam validar a fraude. */
    private static void shadowWithTemporaryTables(Connection app, Scenario s, UUID fakeReview, UUID fakeModerator) throws SQLException {
        try (Statement st = app.createStatement()) {
            st.execute("CREATE TEMP TABLE reviews (id UUID PRIMARY KEY)");
            st.execute("CREATE TEMP TABLE review_discussions (id UUID PRIMARY KEY, review_id UUID)");
            st.execute("CREATE TEMP TABLE business_responses (id UUID PRIMARY KEY, review_id UUID, business_account_id UUID)");
            st.execute("CREATE TEMP TABLE users (id UUID PRIMARY KEY, role VARCHAR(32))");
        }
        update(app, "INSERT INTO pg_temp.reviews (id) VALUES (?)", fakeReview);
        UUID forgedContext = UUID.randomUUID();
        update(app, "INSERT INTO pg_temp.review_discussions (id, review_id) VALUES (?, ?)", s.discussion(), forgedContext);
        update(app, "INSERT INTO pg_temp.business_responses (id, review_id, business_account_id) VALUES (?, ?, ?)",
                s.responseOnReview(), forgedContext, forgedContext);
        update(app, "INSERT INTO pg_temp.users (id, role) VALUES (?, 'MODERATOR')", fakeModerator);
    }

    private static void assertTemporaryTablesCannotForgeReferences(Connection mod, Connection app, Scenario s) throws SQLException {
        UUID fakeReview = UUID.randomUUID();
        UUID fakeModerator = UUID.randomUUID();
        UUID regular = insertUser(app, "USER");
        // Fixtures gravadas antes do sombreamento (depois dele, "users" sem schema resolve na temporária)
        UUID report = insertPendingReport(app, s.media());
        String before = auditFingerprint(app, s);
        shadowWithTemporaryTables(mod, s, fakeReview, fakeModerator);

        // Referência inexistente presente só na tabela temporária: rejeitada
        assertDenied(mod, "INSERT INTO public.moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, "
                + "previous_review_status, new_review_status, authenticity_mac) VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'x', 'ACTIVE', 'REMOVED', ?)",
                fakeReview, s.moderator(), TEST_VALID_MAC);

        // Snapshots derivados da tabela real, não da temporária forjada (em transações atômicas de moderação)
        UUID discussionAudit;
        UUID responseAudit;
        PrivilegedTestDatabase.jdbc().update("UPDATE public.review_discussions SET status = 'UNDER_REVIEW' WHERE id = ?", s.discussion());
        mod.setAutoCommit(false);
        try {
            update(mod, "UPDATE public.review_discussions SET status = 'REMOVED' WHERE id = ?", s.discussion());
            discussionAudit = queryUuid(mod, "INSERT INTO public.discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, "
                    + "decision, reason_code, justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', "
                    + "'SPAM', 'Remoção teste P2-1', 'UNDER_REVIEW', 'REMOVED', ?) RETURNING id", s.discussion(), s.moderator(), TEST_VALID_MAC);
            mod.commit();
        } finally {
            mod.setAutoCommit(true);
        }
        assertEquals(s.review(), queryUuid(mod, "SELECT review_id FROM public.discussion_moderation_audit_logs WHERE id = ?", discussionAudit));

        mod.setAutoCommit(false);
        try {
            update(mod, "UPDATE public.business_responses SET status = 'REMOVED', removed_by = 'MODERATION', removed_at = NOW() WHERE id = ?", s.responseOnReview());
            responseAudit = queryUuid(mod, "INSERT INTO public.business_response_moderation_audit_logs (response_id, moderator_user_id, action, "
                    + "reason_code, justification, previous_status, new_status, authenticity_mac) VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Remoção teste P2-1', "
                    + "'ACTIVE', 'REMOVED', ?) RETURNING id", s.responseOnReview(), s.moderator(), TEST_VALID_MAC);
            mod.commit();
        } finally {
            mod.setAutoCommit(true);
        }
        assertEquals(s.review(), queryUuid(mod, "SELECT review_id FROM public.business_response_moderation_audit_logs WHERE id = ?", responseAudit));
        assertEquals(s.business(), queryUuid(mod, "SELECT business_account_id FROM public.business_response_moderation_audit_logs WHERE id = ?",
                responseAudit));

        // Decisão por "moderador" que só existe na tabela temporária, ou por usuário comum que ela promoveria: rejeitada
        update(mod, "INSERT INTO pg_temp.users (id, role) VALUES (?, 'MODERATOR')", regular);
        assertDenied(mod, "UPDATE public.place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                fakeModerator, TEST_VALID_MAC, report);
        assertDenied(mod, "UPDATE public.place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                regular, TEST_VALID_MAC, report);
        assertEquals("PENDING", string(mod, "SELECT status FROM public.place_media_reports WHERE id = ?", report));

        // Fluxo legítimo continua funcionando na mesma sessão
        decideReportWithAudit(mod, s.media(), s.place(), report, s.moderator(), "ACCEPTED", "ACCEPT_REPORT", "ok");

        // Trilha de fotos (V37): fotografia, local e denúncia que só existem nas temporárias são rejeitados.
        UUID fakeMedia = UUID.randomUUID();
        UUID fakeReport = UUID.randomUUID();
        UUID forgedPlace = UUID.randomUUID();
        try (Statement st = mod.createStatement()) {
            st.execute("CREATE TEMP TABLE place_media (id UUID PRIMARY KEY, place_id UUID)");
            st.execute("CREATE TEMP TABLE place_media_reports (id UUID PRIMARY KEY, place_media_id UUID)");
        }
        update(mod, "INSERT INTO pg_temp.place_media (id, place_id) VALUES (?, ?), (?, ?)", fakeMedia, s.place(), s.media(), forgedPlace);
        update(mod, "INSERT INTO pg_temp.place_media_reports (id, place_media_id) VALUES (?, ?)", fakeReport, s.media());
        assertDenied(mod, "INSERT INTO public.place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, authenticity_mac) "
                + "VALUES (?, ?, ?, ?, 'USER', 'REMOVE', ?)", UUID.randomUUID(), fakeMedia, s.place(), s.moderator(), TEST_VALID_MAC);
        assertDenied(mod, "INSERT INTO public.place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, authenticity_mac) "
                + "VALUES (?, ?, ?, ?, 'USER', 'REMOVE', ?)", UUID.randomUUID(), s.media(), forgedPlace, s.moderator(), TEST_VALID_MAC);
        assertDenied(mod, "INSERT INTO public.place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, report_id, authenticity_mac) "
                + "VALUES (?, ?, ?, ?, 'USER', 'ACCEPT_REPORT', ?, ?)", UUID.randomUUID(), s.media(), s.place(), s.moderator(), fakeReport, TEST_VALID_MAC);
        try (Statement st = mod.createStatement()) {
            st.execute("DROP TABLE pg_temp.place_media, pg_temp.place_media_reports");
        }
        assertEquals(before, auditFingerprint(app, s), "Linhas históricas intactas");
    }

    @Test
    @DisplayName("P2-1: tabelas temporárias homônimas não forjam referências, snapshots nem moderadores (conexão nova)")
    void temporaryTablesCannotShadowReferencesOnFreshConnection() throws SQLException {
        Scenario s = seedScenario();
        try (Connection freshMod = mod(); Connection freshApp = app()) {
            assertTemporaryTablesCannotForgeReferences(freshMod, freshApp, s);
        }
    }

    @Test
    @DisplayName("P2-1: tabelas temporárias homônimas não forjam referências em conexão já usada (pool, planos em cache)")
    void temporaryTablesCannotShadowReferencesOnReusedConnection() throws SQLException {
        Scenario s = seedScenario();
        try (Connection pooledMod = mod(); Connection app = app()) {
            // Uso prévio da conexão: os triggers já executaram e têm planos em cache nesta sessão
            UUID warmup = insertPendingReport(app, s.media());
            decideReportWithAudit(pooledMod, s.media(), s.place(), warmup, s.moderator(), "REJECTED", "REJECT_REPORT", "warmup");
            assertTemporaryTablesCannotForgeReferences(pooledMod, app, s);
        }
    }

    // ------------------------------------------------------------------------------------------ P2-2

    @Test
    @DisplayName("P2-2: decisões finais são imutáveis e denúncias não são apagadas diretamente pelo runtime")
    void finalDecisionsAreImmutableAndReportsAreNotDeletable() throws SQLException {
        try (Connection app = app(); Connection mod = mod()) {
            ReportFixture f = seedReportFixture(app);
            UUID place = queryUuid(app, "SELECT place_id FROM place_media WHERE id = ?", f.media());
            UUID accepted = insertPendingReport(app, f.media());
            decideReportWithAudit(mod, f.media(), place, accepted, f.moderator(), "ACCEPTED", "ACCEPT_REPORT", "procedente");
            String acceptedRow = string(app, "SELECT md5(t::text) FROM place_media_reports t WHERE id = ?", accepted);

            for (Connection conn : List.of(app, mod)) {
                assertDenied(conn, "UPDATE place_media_reports SET status = 'PENDING', decided_by_user_id = NULL, decided_at = NULL WHERE id = ?", accepted);
                assertDenied(conn, "UPDATE place_media_reports SET status = 'REJECTED' WHERE id = ?", accepted);
                assertDenied(conn, "UPDATE place_media_reports SET decided_at = decided_at - INTERVAL '10 days' WHERE id = ?", accepted);
                UUID otherModerator = insertUser(app, "MODERATOR");
                assertDenied(conn, "UPDATE place_media_reports SET decided_by_user_id = ? WHERE id = ?", otherModerator, accepted);
                assertDenied(conn, "UPDATE place_media_reports SET decision_notes = 'reescrita' WHERE id = ?", accepted);
                assertDenied(conn, "DELETE FROM place_media_reports WHERE id = ?", accepted);
            }
            assertEquals(acceptedRow, string(app, "SELECT md5(t::text) FROM place_media_reports t WHERE id = ?", accepted));

            UUID autoResolved = insertPendingReport(app, f.media());
            decideReportWithAudit(mod, f.media(), place, autoResolved, null, "AUTO_RESOLVED", "AUTO_RESOLVE_REPORT", "auto");
            assertDenied(mod, "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, authenticity_mac = ? WHERE id = ?",
                    f.moderator(), TEST_VALID_MAC, autoResolved);

            // Atualização sem mudança dos campos de decisão sob rewit_mod (ex.: merge do JPA) continua aceita
            assertEquals(1, update(mod, "UPDATE place_media_reports SET status = status, updated_at = updated_at WHERE id = ?", accepted));
        }
    }

    @Test
    @DisplayName("P2-2 / S3-A.13: runtime não apaga mídia nem denúncia; a exclusão administrativa da mídia cascateia e preserva a auditoria")
    void parentMediaDeletionCascadesReportsWithoutDirectDeletePrivilege() throws SQLException {
        try (Connection app = app(); Connection mod = mod()) {
            ReportFixture f = seedReportFixture(app);
            UUID report = insertPendingReport(app, f.media());
            UUID place = queryUuid(app, "SELECT place_id FROM place_media WHERE id = ?", f.media());
            UUID audit = decideReportWithAudit(mod, f.media(), place, report, f.moderator(), "ACCEPTED", "ACCEPT_REPORT", "ok");
            String auditRow = rowHash(app, "place_media_moderation_audit_logs", audit);

            // Nem a denúncia nem a mídia-pai: a cascata não é um caminho de exclusão para o runtime
            assertDenied(app, "DELETE FROM place_media_reports WHERE place_media_id = ?", f.media());
            assertDenied(app, "DELETE FROM place_media WHERE id = ?", f.media());
            assertEquals(1, count(app, "SELECT count(*) FROM place_media_reports WHERE id = ?", report));

            // Exclusão física administrativa (owner): cascateia as denúncias e nunca alcança a trilha
            assertEquals(1, PrivilegedTestDatabase.jdbc().update("DELETE FROM place_media WHERE id = ?", f.media()));
            assertEquals(0, count(app, "SELECT count(*) FROM place_media_reports WHERE place_media_id = ?", f.media()));
            assertEquals(auditRow, rowHash(app, "place_media_moderation_audit_logs", audit));
        }
    }

    // ------------------------------------------------------------------------------------------ S3-A.13

    @Test
    @DisplayName("S3-A.13: runtime não exclui usuários, locais, alvos, mídias, avaliações, comentários, respostas nem denúncias, "
            + "diretamente ou por cascata")
    void runtimeCannotDeleteProtectedEntitiesDirectlyOrByCascade() throws SQLException {
        Scenario s = seedScenario();
        try (Connection app = app(); Connection mod = mod()) {
            UUID report = insertPendingReport(app, s.media());
            decideReportWithAudit(mod, s.media(), s.place(), report, s.moderator(), "ACCEPTED", "ACCEPT_REPORT", "ok");
            String before = auditFingerprint(app, s);

            assertDenied(app, "DELETE FROM users WHERE id = ?", s.author());
            assertDenied(app, "DELETE FROM users WHERE id = ?", s.moderator());
            assertDenied(app, "DELETE FROM rateable_targets WHERE id = ?", s.place());
            assertDenied(app, "DELETE FROM places WHERE id = ?", s.place());
            assertDenied(app, "DELETE FROM place_media WHERE id = ?", s.media());
            assertDenied(app, "DELETE FROM reviews WHERE id = ?", s.review());
            assertDenied(app, "DELETE FROM review_discussions WHERE id = ?", s.discussion());
            assertDenied(app, "DELETE FROM business_responses WHERE id = ?", s.responseOnReview());
            assertDenied(app, "DELETE FROM place_media_reports WHERE id = ?", report);
            assertDenied(app, "DELETE FROM business_accounts WHERE id = ?", s.business());
            for (String table : List.of("users", "places", "rateable_targets", "place_media", "reviews", "spatial_ref_sys")) {
                assertDenied(app, "TRUNCATE TABLE " + table + " CASCADE");
            }

            assertEquals(1, count(app, "SELECT count(*) FROM users WHERE id = ?", s.author()));
            assertEquals(1, count(app, "SELECT count(*) FROM reviews WHERE id = ?", s.review()));
            assertEquals(1, count(app, "SELECT count(*) FROM review_discussions WHERE id = ?", s.discussion()));
            assertEquals(2, count(app, "SELECT count(*) FROM business_responses WHERE review_id = ?", s.review()));
            assertEquals(1, count(app, "SELECT count(*) FROM place_media_reports WHERE id = ?", report));
            assertEquals(1, count(app, "SELECT count(*) FROM places WHERE id = ?", s.place()));
            assertEquals(before, auditFingerprint(app, s));

            // Exclusões físicas legítimas do runtime seguem possíveis (lista explícita da V37)
            update(app, "INSERT INTO user_follows (follower_user_id, followed_user_id) VALUES (?, ?)", s.author(), s.moderator());
            assertEquals(1, update(app, "DELETE FROM user_follows WHERE follower_user_id = ? AND followed_user_id = ?",
                    s.author(), s.moderator()));
        }
    }

    @Test
    @DisplayName("S3-A.13: spatial_ref_sys é somente leitura para o runtime e consultas geográficas continuam disponíveis")
    void spatialReferenceSystemsAreReadOnlyForRuntime() throws SQLException {
        String distance = "SELECT round(ST_Distance(ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326)::geography, "
                + "ST_SetSRID(ST_MakePoint(-49.28, -25.43), 4326)::geography))::bigint";
        try (Connection app = app()) {
            assertEquals(1, count(app, "SELECT count(*) FROM spatial_ref_sys WHERE srid = 4326"));
            long meters = count(app, distance);
            assertTrue(meters > 1000 && meters < 2000, "Distância geográfica de referência: " + meters);

            assertDenied(app, "DELETE FROM spatial_ref_sys WHERE srid = 4326");
            assertDenied(app, "UPDATE spatial_ref_sys SET proj4text = 'x' WHERE srid = 4326");
            assertDenied(app, "INSERT INTO spatial_ref_sys (srid, auth_name, auth_srid, srtext, proj4text) VALUES (990001, 'x', 1, 'x', 'x')");
            assertDenied(app, "TRUNCATE TABLE spatial_ref_sys");
        }
        // Conexão nova: nada foi alterado e a busca geográfica segue disponível
        try (Connection app = app()) {
            assertEquals(1, count(app, "SELECT count(*) FROM spatial_ref_sys WHERE srid = 4326"));
            assertTrue(count(app, distance) > 1000);
            assertEquals(1, count(app, "SELECT count(*) FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 3857)) t"));
        }
    }

    @Test
    @DisplayName("S3-A.13: tabela nova criada pela role de migração não nasce gravável pelo runtime")
    void tableCreatedByMigrationOwnerIsNotWritableByRuntime() throws SQLException {
        String probe = "rewit_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            try (Statement st = owner.createStatement()) {
                st.execute("CREATE TABLE " + probe + " (id UUID PRIMARY KEY)");
                // Privilégios padrão da role que executa as migrations
                assertTrue(bool(owner, "SELECT has_table_privilege('" + INFRASTRUCTURE.appDbUser() + "', '" + probe + "', 'SELECT')"));
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertFalse(bool(owner, "SELECT has_table_privilege('" + INFRASTRUCTURE.appDbUser() + "', '" + probe + "', '" + privilege + "')"),
                            "Tabela nova não pode conceder " + privilege + " ao runtime por omissão");
                }
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("S3-A.13: a verificação de privilégios aceita o estado instalado e detecta concessões indevidas introduzidas")
    void runtimePrivilegeVerificationDetectsDeliberateViolations() throws SQLException {
        try (Connection app = app()) {
            assertDenied(app, "SELECT rewit_assert_runtime_privileges()");
        }
        Map<String, String> violations = Map.of(
                "GRANT DELETE ON users TO rewit_app", "DELETE em users",
                "GRANT DELETE ON auth_sessions TO rewit_app", "DELETE em auth_sessions",
                "GRANT UPDATE ON spatial_ref_sys TO rewit_app", "UPDATE em spatial_ref_sys",
                "GRANT UPDATE ON place_media_moderation_audit_logs TO rewit_app", "UPDATE em place_media_moderation_audit_logs",
                "GRANT INSERT ON place_media_reports TO rewit_app", "INSERT em place_media_reports",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT ON TABLES TO rewit_app", "privilégios padrão de escrita",
                "ALTER TABLE place_media_moderation_audit_logs DISABLE TRIGGER trg_bind_place_media_audit_reference",
                "trg_bind_place_media_audit_reference",
                "CREATE TABLE rewit_probe_cascade (id UUID REFERENCES notifications(id) ON DELETE CASCADE)", "cascatas acionáveis");
        try (Connection owner = owner()) {
            try (Statement st = owner.createStatement()) {
                st.execute("SELECT rewit_assert_runtime_privileges()");
            }
            owner.setAutoCommit(false);
            for (Map.Entry<String, String> violation : violations.entrySet()) {
                try (Statement st = owner.createStatement()) {
                    st.execute(violation.getKey());
                    SQLException detected = assertThrows(SQLException.class, () -> st.execute("SELECT rewit_assert_runtime_privileges()"),
                            "Não detectou: " + violation.getKey());
                    assertTrue(detected.getMessage().contains(violation.getValue()),
                            () -> "Mensagem sem '" + violation.getValue() + "': " + detected.getMessage());
                } finally {
                    // A concessão nunca é confirmada: nenhuma outra sessão a enxerga
                    owner.rollback();
                }
            }
            owner.setAutoCommit(true);
            try (Statement st = owner.createStatement()) {
                st.execute("SELECT rewit_assert_runtime_privileges()");
            }
        }
    }

    @Test
    @DisplayName("S3-A.13: trilha de fotos exige fotografia, local e denúncia reais; exclusão administrativa não altera o histórico")
    void placeMediaAuditReferencesAreVerified() throws SQLException {
        try (Connection app = app(); Connection mod = mod()) {
            ReportFixture f = seedReportFixture(app);
            ReportFixture other = seedReportFixture(app);
            UUID place = queryUuid(app, "SELECT place_id FROM place_media WHERE id = ?", f.media());
            UUID report = insertPendingReport(app, f.media());
            UUID otherReport = insertPendingReport(app, other.media());
            String insert = "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, report_id, authenticity_mac) "
                    + "VALUES (?, ?, ?, ?, 'USER', ?, ?, ?)";

            assertDenied(mod, insert, UUID.randomUUID(), UUID.randomUUID(), place, f.moderator(), "ACCEPT_REPORT", report, TEST_VALID_MAC);
            assertDenied(mod, insert, UUID.randomUUID(), f.media(), UUID.randomUUID(), f.moderator(), "REMOVE", null, TEST_VALID_MAC);
            assertDenied(mod, insert, UUID.randomUUID(), f.media(), place, f.moderator(), "ACCEPT_REPORT", UUID.randomUUID(), TEST_VALID_MAC);
            assertDenied(mod, insert, UUID.randomUUID(), f.media(), place, f.moderator(), "ACCEPT_REPORT", otherReport, TEST_VALID_MAC);
            assertDenied(mod, insert, UUID.randomUUID(), f.media(), place, f.moderator(), "REJECT_REPORT", null, TEST_VALID_MAC);
            assertDenied(mod, "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_type, action, authenticity_mac) "
                    + "VALUES (?, ?, ?, 'SYSTEM', 'AUTO_RESOLVE_REPORT', ?)", UUID.randomUUID(), f.media(), place, TEST_VALID_MAC);

            UUID removal = UUID.randomUUID();
            mod.setAutoCommit(false);
            try {
                update(mod, "UPDATE place_media SET status = 'REMOVED', removal_source = 'MODERATOR', removed_at = NOW(), is_primary = FALSE WHERE id = ?", f.media());
                assertEquals(1, update(mod, insert, removal, f.media(), place, f.moderator(), "REMOVE", null, TEST_VALID_MAC));
                mod.commit();
            } finally {
                mod.setAutoCommit(true);
            }
            UUID decision = decideReportWithAudit(mod, f.media(), place, report, f.moderator(), "ACCEPTED", "ACCEPT_REPORT", "procedente");
            assertEquals(place, queryUuid(app, "SELECT place_id FROM place_media_moderation_audit_logs WHERE id = ?", decision));
            assertEquals(report, queryUuid(app, "SELECT report_id FROM place_media_moderation_audit_logs WHERE id = ?", decision));
            String removalRow = rowHash(app, "place_media_moderation_audit_logs", removal);
            String decisionRow = rowHash(app, "place_media_moderation_audit_logs", decision);

            // Exclusão administrativa da mídia e do local: o histórico continua idêntico, com as referências gravadas
            PrivilegedTestDatabase.jdbc().update("DELETE FROM rateable_targets WHERE id = ?", place);
            assertEquals(0, count(app, "SELECT count(*) FROM place_media WHERE id = ?", f.media()));
            assertEquals(removalRow, rowHash(app, "place_media_moderation_audit_logs", removal));
            assertEquals(decisionRow, rowHash(app, "place_media_moderation_audit_logs", decision));
            // ... e a trilha não aceita novos eventos para a mídia que deixou de existir
            assertDenied(mod, insert, UUID.randomUUID(), f.media(), place, f.moderator(), "RESTORE", null, TEST_VALID_MAC);
        }
    }

    @Test
    @DisplayName("S3-A.13: denúncias nascem pendentes; motivo e detalhe congelam após a decisão final; updates sem mudança passam")
    void reportsStartPendingAndFinalDecisionFreezesReasonAndDetail() throws SQLException {
        try (Connection app = app(); Connection mod = mod()) {
            ReportFixture f = seedReportFixture(app);
            UUID place = queryUuid(app, "SELECT place_id FROM place_media WHERE id = ?", f.media());
            String insert = "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) "
                    + "VALUES (?, ?, ?, 'SPAM', ?, ?, ?)";
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            assertDenied(app, insert, UUID.randomUUID(), f.media(), insertUser(app, "USER"), "ACCEPTED", f.moderator(), now);
            assertDenied(app, insert, UUID.randomUUID(), f.media(), insertUser(app, "USER"), "REJECTED", f.moderator(), now);
            assertDenied(app, insert, UUID.randomUUID(), f.media(), insertUser(app, "USER"), "AUTO_RESOLVED", null, now);
            assertDenied(app, insert, UUID.randomUUID(), f.media(), insertUser(app, "USER"), "PENDING", null, now);
            assertDenied(app, insert, UUID.randomUUID(), f.media(), insertUser(app, "USER"), "PENDING", f.moderator(), null);

            for (String finalStatus : List.of("ACCEPTED", "REJECTED", "AUTO_RESOLVED")) {
                UUID report = insertPendingReport(app, f.media());
                String auditAction = switch (finalStatus) {
                    case "ACCEPTED" -> "ACCEPT_REPORT";
                    case "REJECTED" -> "REJECT_REPORT";
                    default -> "AUTO_RESOLVE_REPORT";
                };
                UUID decider = "AUTO_RESOLVED".equals(finalStatus) ? null : f.moderator();
                decideReportWithAudit(mod, f.media(), place, report, decider, finalStatus, auditAction, "final");
                String decided = rowHash(app, "place_media_reports", report);

                for (Connection conn : List.of(app, mod)) {
                    assertDenied(conn, "UPDATE place_media_reports SET reason = 'OUTRO' WHERE id = ?", report);
                    assertDenied(conn, "UPDATE place_media_reports SET detail = 'reescrito' WHERE id = ?", report);
                    assertDenied(conn, "UPDATE place_media_reports SET detail = NULL WHERE id = ?", report);
                }
                assertEquals(decided, rowHash(app, "place_media_reports", report), finalStatus);
                // Como o Hibernate faz sob rewit_mod: colunas atualizáveis regravadas com os mesmos valores
                assertEquals(1, update(mod, "UPDATE place_media_reports SET status = status, "
                        + "decided_by_user_id = decided_by_user_id, decided_at = decided_at, decision_notes = decision_notes, "
                        + "authenticity_mac = authenticity_mac, updated_at = updated_at WHERE id = ?", report));
            }
        }
    }

    private static UUID decideReportWithAudit(Connection mod, UUID mediaId, UUID placeId, UUID reportId,
                                              UUID moderatorId, String status, String auditAction, String notes) throws SQLException {
        UUID auditId = UUID.randomUUID();
        boolean prevAutoCommit = mod.getAutoCommit();
        mod.setAutoCommit(false);
        try {
            if ("AUTO_RESOLVED".equals(status)) {
                assertEquals(1, update(mod, "UPDATE public.place_media_reports SET status = 'AUTO_RESOLVED', decided_by_user_id = NULL, "
                        + "decided_at = NOW(), updated_at = NOW(), decision_notes = ?, authenticity_mac = NULL WHERE id = ?", notes, reportId));
                assertEquals(1, update(mod, "INSERT INTO public.place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, notes, report_id, authenticity_mac) "
                        + "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_RESOLVE_REPORT', ?, ?, ?)", auditId, mediaId, placeId, notes, reportId, TEST_VALID_MAC));
            } else {
                assertEquals(1, update(mod, "UPDATE public.place_media_reports SET status = ?, decided_by_user_id = ?, decided_at = NOW(), "
                        + "updated_at = NOW(), decision_notes = ?, authenticity_mac = ? WHERE id = ?",
                        status, moderatorId, notes, TEST_VALID_MAC, reportId));
                assertEquals(1, update(mod, "INSERT INTO public.place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, notes, report_id, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'USER', ?, ?, ?, ?)", auditId, mediaId, placeId, moderatorId, auditAction, notes, reportId, TEST_VALID_MAC));
            }
            mod.commit();
            return auditId;
        } catch (SQLException e) {
            mod.rollback();
            throw e;
        } finally {
            mod.setAutoCommit(prevAutoCommit);
        }
    }

    @Test
    @DisplayName("P2-2: runtime não promove usuários nem cria usuários com papel administrativo")
    void runtimeCannotAssignRoles() throws SQLException {
        try (Connection app = app()) {
            UUID user = insertUser(app, "USER");
            assertDenied(app, "UPDATE users SET role = 'MODERATOR' WHERE id = ?", user);
            assertDenied(app, "UPDATE users SET role = 'ADMIN' WHERE id = ?", user);
            UUID newcomer = UUID.randomUUID();
            assertDenied(app, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'MODERATOR')",
                    newcomer, "audit." + newcomer + "@rewit.test");
            assertEquals("USER", string(app, "SELECT role FROM users WHERE id = ?", user));
            assertEquals(0, count(app, "SELECT count(*) FROM users WHERE id = ?", newcomer));

            // Atualizações legítimas do perfil da conta seguem permitidas
            assertEquals(1, update(app, "UPDATE users SET role = role, updated_at = NOW() WHERE id = ?", user));
            assertDenied(app, "ALTER TABLE users DISABLE TRIGGER trg_guard_user_role_assignment");

            // A promoção administrativa, fora do runtime, continua possível
            PrivilegedTestDatabase.assignRole(user, "MODERATOR");
            assertEquals("MODERATOR", string(app, "SELECT role FROM users WHERE id = ?", user));
        }
    }

    // ------------------------------------------------------------------------------------------ utilitários

    /** O runtime grava o usuário (sempre USER); papel diferente é atribuído administrativamente (P2-2). */
    private static UUID insertUser(Connection conn, String role) throws SQLException {
        UUID id = UUID.randomUUID();
        update(conn, "INSERT INTO users (id, email, auth_provider) VALUES (?, ?, 'LOCAL')", id, "audit." + id + "@rewit.test");
        if (!"USER".equals(role)) {
            PrivilegedTestDatabase.assignRole(id, role);
        }
        return id;
    }

    private static UUID insertPlace(Connection conn) throws SQLException {
        UUID id = UUID.randomUUID();
        update(conn, "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", id);
        update(conn, "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) "
                        + "VALUES (?, ?, ?, 'CAFE', ?, 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))",
                id, "Local Auditoria " + id, "audit-" + id, "Rua Auditoria, " + id);
        return id;
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private static int update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    private static UUID queryUuid(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static String string(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static boolean bool(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getBoolean(1);
            }
        }
    }

    private static long count(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** Executa a instrução esperando falha do PostgreSQL; a conexão continua utilizável (autocommit). */
    private static void assertDenied(Connection conn, String sql, Object... args) {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                ps.execute();
            }
        }, "Deveria falhar: " + sql);
        assertNotNull(ex.getSQLState(), "Falha precisa vir do PostgreSQL: " + ex.getMessage());
    }
}
