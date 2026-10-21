package com.rewit.infrastructure.persistence;

import com.rewit.config.TestInfrastructureConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrations canônicas em databases descartáveis criados pelo próprio teste (A-1b, V16-1, V36-2, LEG-2).
 *
 * <p>Segurança da operação destrutiva: cada caso cria um database novo, com nome aleatório prefixado e um
 * marcador gravado em {@code COMMENT ON DATABASE}, no servidor de {@code TEST_DATASOURCE_URL} (validado pelo
 * {@link com.rewit.config.TestInfrastructureSafetyValidator} antes do primeiro comando). Não há
 * {@code flyway.clean()}: o único comando destrutivo é o {@code DROP DATABASE} final, executado somente depois
 * de confirmar no catálogo que o alvo tem o nome e o marcador gerados por esta execução.
 */
@DisplayName("Migrations canônicas em database descartável: V16 após falha e upgrade V35 -> V36 com dados históricos")
class DatabaseUpgradeMigrationTest {

    private static final TestInfrastructureConfig INFRASTRUCTURE = TestInfrastructureConfig.current();
    private static final String DATABASE_PREFIX = "rewit_migtest_";
    private static final Pattern DISPOSABLE_NAME = Pattern.compile("^rewit_migtest_[0-9a-f]{16}$");
    private static final String V16_INDEX = "idx_auth_sessions_replaced_by_session_id";
    private static final Pattern DISPOSABLE_ROLE = Pattern.compile("^rewit_migtest_role_[0-9a-f]{16}$");

    private String database;
    private String marker;
    private String databaseUrl;
    /** Roles de cluster criadas por este caso; removidas depois do database (são donas de objetos dele). */
    private final java.util.List<String> createdRoles = new java.util.ArrayList<>();

    @BeforeEach
    void createDisposableDatabase() throws SQLException {
        // Validação completa da configuração antes de qualquer conexão
        String adminUrl = INFRASTRUCTURE.datasourceUrl();
        byte[] random = new byte[8];
        new java.security.SecureRandom().nextBytes(random);
        database = DATABASE_PREFIX + HexFormat.of().formatHex(random);
        marker = "rewit-disposable-migration-test:" + UUID.randomUUID();
        databaseUrl = INFRASTRUCTURE.datasourceUrlForDatabase(database);

        try (Connection conn = DriverManager.getConnection(adminUrl, INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + database);
            st.execute("COMMENT ON DATABASE " + database + " IS '" + marker + "'");
        }
    }

    @AfterEach
    void dropDisposableDatabase() throws SQLException {
        if (database == null) {
            return;
        }
        assertTrue(DISPOSABLE_NAME.matcher(database).matches(), "Nome fora do padrão descartável: " + database);
        try (Connection conn = DriverManager.getConnection(INFRASTRUCTURE.datasourceUrl(),
                INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT shobj_description(oid, 'pg_database'), current_database() FROM pg_database WHERE datname = ?")) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Database descartável não encontrado: " + database);
                    assertEquals(marker, rs.getString(1), "Marcador divergente: DROP recusado para " + database);
                    assertFalse(database.equals(rs.getString(2)), "Nunca remover o database da conexão administrativa");
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE " + database + " WITH (FORCE)");
                for (String role : createdRoles) {
                    assertTrue(DISPOSABLE_ROLE.matcher(role).matches(), "Role fora do padrão descartável: " + role);
                    st.execute("DROP ROLE IF EXISTS " + role);
                }
            }
        }
    }

    private Flyway flyway(String target) {
        return flyway(target, null);
    }

    /** {@code initSql} permite executar as migrations sob outra role (SET ROLE), como uma role de migração sem superuser. */
    private Flyway flyway(String target, String initSql) {
        var config = Flyway.configure()
                .dataSource(databaseUrl, INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword())
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        if (target != null) {
            config.target(target);
        }
        if (initSql != null) {
            config.initSql(initSql);
        }
        return config.load();
    }

    private String createDisposableRole(Statement st) throws SQLException {
        byte[] random = new byte[8];
        new java.security.SecureRandom().nextBytes(random);
        String role = "rewit_migtest_role_" + HexFormat.of().formatHex(random);
        st.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT");
        createdRoles.add(role);
        return role;
    }

    private Connection owner() throws SQLException {
        Connection conn = DriverManager.getConnection(databaseUrl, INFRASTRUCTURE.migrationDbUser(), INFRASTRUCTURE.migrationDbPassword());
        try (PreparedStatement ps = conn.prepareStatement("SELECT current_database(), shobj_description(d.oid, 'pg_database') "
                + "FROM pg_database d WHERE d.datname = current_database()");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(database, rs.getString(1));
            assertEquals(marker, rs.getString(2), "Conexão fora do database descartável desta execução");
        }
        return conn;
    }

    // ============================================================================================ V16

    private record IndexState(boolean exists, boolean valid, boolean ready, String definition) {
    }

    private IndexState v16Index(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid)
                FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = ?
                """)) {
            ps.setString(1, V16_INDEX);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new IndexState(false, false, false, null);
                }
                return new IndexState(true, rs.getBoolean(1), rs.getBoolean(2), rs.getString(3));
            }
        }
    }

    private static void assertCanonicalV16Index(IndexState state) {
        assertTrue(state.exists(), "Índice V16 deve existir");
        assertTrue(state.valid(), "pg_index.indisvalid deve ser true");
        assertTrue(state.ready(), "pg_index.indisready deve ser true");
        assertTrue(state.definition().endsWith("USING btree (replaced_by_session_id) WHERE (replaced_by_session_id IS NOT NULL)"),
                "Definição canônica do índice: " + state.definition());
    }

    private Integer checksum(Connection conn, String version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT checksum FROM flyway_schema_history WHERE version = ? AND success")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    @Test
    @DisplayName("V16: instalação limpa até a última versão deixa o índice válido; checksums canônicos registrados")
    void cleanInstallLeavesValidV16Index() throws SQLException {
        MigrateResult result = flyway(null).migrate();
        assertTrue(result.success);
        try (Connection conn = owner()) {
            assertCanonicalV16Index(v16Index(conn));
            Integer v16 = checksum(conn, "16");
            Integer v36 = checksum(conn, "36");
            assertNotNull(v16);
            assertNotNull(v36);
            System.out.println("[checksum] V16=" + v16 + " V36=" + v36);
        }
    }

    @Test
    @DisplayName("V16: índice válido já existente é reconstruído e continua válido")
    void existingValidIndexIsRebuiltAndStaysValid() throws SQLException {
        flyway("15").migrate();
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX " + V16_INDEX + " ON auth_sessions (replaced_by_session_id) WHERE replaced_by_session_id IS NOT NULL");
            IndexState before = v16Index(conn);
            assertTrue(before.exists() && before.valid());
        }
        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner()) {
            assertCanonicalV16Index(v16Index(conn));
        }
    }

    @Test
    @DisplayName("V16: índice inválido deixado por CREATE INDEX CONCURRENTLY interrompido é removido e recriado válido na nova tentativa")
    void invalidIndexLeftByFailedConcurrentBuildIsRecreated() throws SQLException {
        flyway("15").migrate();
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            UUID user = UUID.randomUUID();
            st.execute("INSERT INTO users (id, email, auth_provider) VALUES ('" + user + "', 'v16." + user + "@rewit.test', 'LOCAL')");
            for (int i = 0; i < 2; i++) {
                st.execute("INSERT INTO auth_sessions (id, user_id, token_hash, expires_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + user + "', '" + UUID.randomUUID().toString().replace("-", "")
                        + "', NOW() + INTERVAL '1 day')");
            }
            // Falha simulada: build concorrente que falha na validação deixa um índice INVÁLIDO com o nome da V16
            SQLException failedBuild = assertThrows(SQLException.class, () -> st.execute(
                    "CREATE UNIQUE INDEX CONCURRENTLY " + V16_INDEX + " ON auth_sessions (user_id)"));
            assertEquals("23505", failedBuild.getSQLState(), "A falha simulada é a violação de unicidade do build concorrente");
            IndexState broken = v16Index(conn);
            assertTrue(broken.exists(), "A falha deve deixar o índice no catálogo");
            assertFalse(broken.valid(), "O índice deixado pela falha é inválido");

            // Defeito original (V16-1): IF NOT EXISTS só emite NOTICE e mantém o índice inválido
            st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + V16_INDEX
                    + " ON auth_sessions (replaced_by_session_id) WHERE replaced_by_session_id IS NOT NULL");
            assertFalse(v16Index(conn).valid(), "IF NOT EXISTS não repara o índice inválido");
        }

        MigrateResult retry = flyway(null).migrate();
        assertTrue(retry.success, "Nova tentativa da V16 conclui");
        try (Connection conn = owner()) {
            assertCanonicalV16Index(v16Index(conn));
            assertEquals(1, count(conn, "SELECT count(*) FROM pg_class WHERE relname = ?", V16_INDEX));
        }
    }

    // ============================================================================================ V36

    @Test
    @DisplayName("V36-2 / LEG-2: upgrade V35 -> V36 classifica removal_source e decisões só com evidência inequívoca, sem alterar auditoria")
    void upgradeFromV35ClassifiesOnlyWithUnequivocalEvidence() throws SQLException {
        flyway("35").migrate();
        Instant t0 = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Fixture f;
        String placeMediaAuditBefore;
        String discussionAuditBefore;
        String responseAuditBefore;
        try (Connection conn = owner()) {
            f = new Fixture(conn, t0);
            f.seed();
            placeMediaAuditBefore = string(conn, "SELECT md5(string_agg(t::text, '|' ORDER BY id)) FROM place_media_moderation_audit_logs t");
            discussionAuditBefore = string(conn, "SELECT md5(string_agg(row(id, discussion_id, moderator_user_id, action, decision, reason_code, "
                    + "justification, previous_status, new_status, reports_affected_count, created_at)::text, '|' ORDER BY id)) "
                    + "FROM discussion_moderation_audit_logs");
            responseAuditBefore = string(conn, "SELECT md5(string_agg(row(id, response_id, moderator_user_id, action, reason_code, justification, "
                    + "previous_status, new_status, created_at)::text, '|' ORDER BY id)) FROM business_response_moderation_audit_logs");
        }

        assertDoesNotThrow(() -> flyway(null).migrate(), "Upgrade V35 -> V36 com dados históricos representativos conclui");

        try (Connection conn = owner()) {
            // ---- V36-2: origem da remoção
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("1. remoção não auditada seguida de aceite", "LEGACY");
            expected.put("2. autor e aceite com timestamp empatado (UUID do aceite menor)", "LEGACY");
            expected.put("3. aceite que realmente provoca a remoção", "MODERATOR");
            expected.put("4. remoção, restauração e nova remoção não auditada", "LEGACY");
            expected.put("4b. remoção, restauração e nova remoção auditada pelo autor", "AUTHOR");
            expected.put("5. somente rejeições", "LEGACY");
            expected.put("6. resolução automática de denúncias", "LEGACY");
            expected.put("7a. REMOVE por usuário sem papel comprovado", "LEGACY");
            expected.put("7b. aceite cuja denúncia não registra decided_at", "LEGACY");
            expected.put("7c. REMOVE por moderador que é o próprio autor", "LEGACY");
            expected.put("8. REMOVE por moderador", "MODERATOR");
            expected.put("9. AUTHOR_REMOVE", "AUTHOR");
            expected.put("10. PLACE_MANAGER_REMOVE", "PLACE_MANAGER");
            expected.put("11. REMOVE genérico pelo criador do local", "PLACE_MANAGER");
            expected.put("12. autor remove antes de aceite posterior", "AUTHOR");
            for (Map.Entry<String, String> e : expected.entrySet()) {
                UUID media = f.media.get(e.getKey());
                assertEquals(e.getValue(), string(conn, "SELECT removal_source FROM place_media WHERE id = ?", media), e.getKey());
            }
            assertNull(string(conn, "SELECT removal_source FROM place_media WHERE id = ?", f.media.get("5b. mídia ativa com rejeições")),
                    "Mídia ativa não recebe origem de remoção");

            // ---- LEG-2: decisões históricas
            assertReport(conn, f.report.get("a. aceite com evento do mesmo moderador"), "ACCEPTED", false, f.moderator);
            assertReport(conn, f.report.get("b. aceite sem decisor"), "ACCEPTED", true, null);
            assertReport(conn, f.report.get("c. aceite com o autor como decisor e sem evento"), "ACCEPTED", true, f.author);
            assertReport(conn, f.report.get("d. aceite com o autor como decisor e AUTO_RESOLVE do SYSTEM"), "AUTO_RESOLVED", false, null);
            assertReport(conn, f.report.get("e. rejeição cujo evento é de outro ator"), "REJECTED", true, f.moderator);
            assertReport(conn, f.report.get("f. pendente com AUTO_RESOLVE do SYSTEM"), "AUTO_RESOLVED", false, null);
            assertReport(conn, f.report.get("g. aceite com evento humano e AUTO_RESOLVE contraditório"), "ACCEPTED", true, f.moderator);
            assertReport(conn, f.report.get("h. rejeição validada"), "REJECTED", false, f.moderator);

            // ---- A trilha histórica é lida e nunca alterada pela migração
            assertEquals(placeMediaAuditBefore,
                    string(conn, "SELECT md5(string_agg(t::text, '|' ORDER BY id)) FROM place_media_moderation_audit_logs t"));
            assertEquals(discussionAuditBefore, string(conn, "SELECT md5(string_agg(row(id, discussion_id, moderator_user_id, action, "
                    + "decision, reason_code, justification, previous_status, new_status, reports_affected_count, created_at)::text, '|' "
                    + "ORDER BY id)) FROM discussion_moderation_audit_logs"));
            assertEquals(responseAuditBefore, string(conn, "SELECT md5(string_agg(row(id, response_id, moderator_user_id, action, "
                    + "reason_code, justification, previous_status, new_status, created_at)::text, '|' ORDER BY id)) "
                    + "FROM business_response_moderation_audit_logs"));

            // ---- AO-2 em base atualizada: auditorias anteriores sobrevivem à exclusão do pai
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM reviews WHERE id = '" + f.review + "'");
            }
            assertEquals(1, count(conn, "SELECT count(*) FROM moderation_audit_logs WHERE review_id = ?", f.review));
            assertEquals(1, count(conn, "SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ?", f.discussion));
            assertEquals(1, count(conn, "SELECT count(*) FROM business_response_moderation_audit_logs WHERE response_id = ?", f.response));
        }
    }

    @Test
    @DisplayName("S3-A.13: upgrade V35 -> V37 sob role de migração sem superuser falha antes de qualquer DDL se uma função de "
            + "trigger tem outro owner, e conclui quando a role passa a administrá-la")
    void upgradeWithDivergentTriggerFunctionOwnerFailsExplicitlyAndCompletesWhenAdministrable() throws SQLException {
        flyway("35").migrate();
        String executor;
        String legacyOwner;
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            // Executor das migrations: role sem superuser que herda a role de migração (owner dos demais objetos)
            executor = createDisposableRole(st);
            st.execute("GRANT " + INFRASTRUCTURE.migrationDbUser() + " TO " + executor);
            // Função de trigger de migration antiga com outro owner
            legacyOwner = createDisposableRole(st);
            st.execute("ALTER FUNCTION fn_prevent_rateable_target_type_change() OWNER TO " + legacyOwner);
        }

        org.flywaydb.core.api.FlywayException failure = assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> flyway(null, "SET ROLE " + executor).migrate());
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("não administra") && message.contains("fn_prevent_rateable_target_type_change")
                && message.contains(legacyOwner), "Mensagem explícita com objeto e owner: " + message);

        try (Connection conn = owner()) {
            // Rollback completo: nenhuma versão nova, nenhum objeto da V36 e a função antiga intocada
            assertEquals("35", string(conn, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"));
            assertEquals(0, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"));
            assertEquals(0, count(conn, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_guard_user_role_assignment'"));
            assertNull(string(conn, "SELECT array_to_string(proconfig, ',') FROM pg_proc WHERE proname = 'fn_prevent_rateable_target_type_change'"));
            try (Statement st = conn.createStatement()) {
                // Modelo suportado: a role de migração administra (herda) o owner da função
                st.execute("GRANT " + legacyOwner + " TO " + executor);
            }
        }

        assertTrue(flyway(null, "SET ROLE " + executor).migrate().success, "Sem flyway repair: a nova tentativa conclui");
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals("37", string(conn, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"));
            assertEquals("search_path=pg_catalog, public, pg_temp",
                    string(conn, "SELECT array_to_string(proconfig, ',') FROM pg_proc WHERE proname = 'fn_prevent_rateable_target_type_change'"));
            st.execute("SELECT rewit_assert_runtime_privileges()");
        }
    }

    @Test
    @DisplayName("S3-A.13: instalação limpa e upgrade a partir da V35 terminam com os mesmos privilégios efetivos do runtime")
    void cleanInstallAndUpgradeProduceSamePrivileges() throws SQLException {
        String privileges = """
                SELECT string_agg(format('%s:%s', c.relname, p.priv), ',' ORDER BY c.relname, p.priv)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')) p(priv)
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v') AND c.relname <> 'flyway_schema_history'
                  AND has_table_privilege('rewit_app', c.oid, p.priv)
                """;
        flyway("35").migrate();
        try (Connection conn = owner()) {
            new Fixture(conn, Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)).seed();
        }
        assertTrue(flyway(null).migrate().success);
        String upgraded;
        try (Connection conn = owner()) {
            upgraded = string(conn, privileges);
        }

        String clean;
        dropDisposableDatabase();
        createDisposableDatabase();
        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner()) {
            clean = string(conn, privileges);
            assertFalse(clean.contains("spatial_ref_sys:DELETE") || clean.contains("spatial_ref_sys:UPDATE"));
            assertFalse(clean.contains("users:DELETE") || clean.contains("place_media:DELETE") || clean.contains("places:DELETE")
                    || clean.contains("rateable_targets:DELETE"));
        }
        assertEquals(clean, upgraded);
    }

    private void assertReport(Connection conn, UUID report, String status, boolean legacy, UUID decidedBy) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status, is_legacy_decision, decided_by_user_id FROM place_media_reports WHERE id = ?")) {
            ps.setObject(1, report);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(status, rs.getString(1), "status de " + report);
                assertEquals(legacy, rs.getBoolean(2), "is_legacy_decision de " + report);
                assertEquals(decidedBy, rs.getObject(3, UUID.class), "decided_by_user_id de " + report);
            }
        }
    }

    /** Dados históricos representativos de uma base V35, gravados antes da V36. */
    private static final class Fixture {
        private final Connection conn;
        private final Instant t0;
        private final Map<String, UUID> media = new LinkedHashMap<>();
        private final Map<String, UUID> report = new LinkedHashMap<>();
        private UUID author;
        private UUID moderator;
        private UUID regular;
        private UUID creator;
        private UUID moderatorAuthor;
        private UUID place;
        private UUID review;
        private UUID discussion;
        private UUID response;

        Fixture(Connection conn, Instant t0) {
            this.conn = conn;
            this.t0 = t0;
        }

        private Timestamp at(long minutes, long micros) {
            return Timestamp.from(t0.plus(minutes, ChronoUnit.MINUTES).plus(micros, ChronoUnit.MICROS));
        }

        private Timestamp at(long minutes) {
            return at(minutes, 0);
        }

        void seed() throws SQLException {
            author = user("USER");
            moderator = user("MODERATOR");
            regular = user("USER");
            creator = user("USER");
            moderatorAuthor = user("MODERATOR");
            place = UUID.randomUUID();
            exec("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", place);
            exec("INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates, created_by_user_id) "
                    + "VALUES (?, 'Local V35', 'local-v35', 'BAR', 'Rua V35', 'Curitiba', 'PR', "
                    + "ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), ?)", place, creator);

            // 1. Remoção não auditada (removed_at T+0) seguida de aceite (decided_at T+60)
            UUID m1 = media("1. remoção não auditada seguida de aceite", "REMOVED", author, at(0));
            UUID r1 = report(m1, "ACCEPTED", moderator, at(60));
            audit(null, m1, moderator, "ACCEPT_REPORT", r1, at(60, 1000));

            // 2. AUTHOR_REMOVE e ACCEPT_REPORT com o mesmo timestamp; o UUID do aceite ordena primeiro
            UUID m2 = media("2. autor e aceite com timestamp empatado (UUID do aceite menor)", "REMOVED", author, at(100));
            UUID r2 = report(m2, "ACCEPTED", moderator, at(100));
            audit(UUID.fromString("00000000-0000-4000-8000-000000000001"), m2, moderator, "ACCEPT_REPORT", r2, at(100));
            audit(UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"), m2, author, "AUTHOR_REMOVE", null, at(100));

            // 3. Aceite que provoca a remoção: decided_at <= removed_at <= evento (ordem do serviço)
            UUID m3 = media("3. aceite que realmente provoca a remoção", "REMOVED", author, at(200, 500));
            audit(null, m3, null, "AUTO_QUARANTINE", null, at(150));
            UUID r3 = report(m3, "ACCEPTED", moderator, at(200));
            audit(null, m3, moderator, "ACCEPT_REPORT", r3, at(200, 1000));

            // 4. Remoção auditada, restauração e nova remoção sem evento
            UUID m4 = media("4. remoção, restauração e nova remoção não auditada", "REMOVED", author, at(320));
            audit(null, m4, moderator, "REMOVE", null, at(300));
            audit(null, m4, moderator, "RESTORE", null, at(310));
            UUID m4b = media("4b. remoção, restauração e nova remoção auditada pelo autor", "REMOVED", author, at(320));
            audit(null, m4b, moderator, "REMOVE", null, at(300));
            audit(null, m4b, moderator, "RESTORE", null, at(310));
            audit(null, m4b, author, "AUTHOR_REMOVE", null, at(320, 100));

            // 5. Somente rejeições (mídia removida sem evento e mídia ativa)
            UUID m5 = media("5. somente rejeições", "REMOVED", author, at(400));
            UUID r5 = report(m5, "REJECTED", moderator, at(410));
            audit(null, m5, moderator, "REJECT_REPORT", r5, at(410, 100));
            UUID m5b = media("5b. mídia ativa com rejeições", "ACTIVE", author, null);
            UUID r5b = report(m5b, "REJECTED", moderator, at(420));
            audit(null, m5b, moderator, "REJECT_REPORT", r5b, at(420, 100));

            // 6. Resolução automática não demonstra remoção humana
            UUID m6 = media("6. resolução automática de denúncias", "REMOVED", author, at(500));
            UUID r6 = report(m6, "PENDING", null, null);
            audit(null, m6, null, "AUTO_RESOLVE_REPORT", r6, at(500, 100));
            report.put("f. pendente com AUTO_RESOLVE do SYSTEM", r6);

            // 7. Origem sem evidência suficiente
            UUID m7a = media("7a. REMOVE por usuário sem papel comprovado", "REMOVED", author, at(600));
            audit(null, m7a, regular, "REMOVE", null, at(600, 100));
            UUID m7b = media("7b. aceite cuja denúncia não registra decided_at", "REMOVED", author, at(610));
            UUID r7b = report(m7b, "ACCEPTED", moderator, null);
            audit(null, m7b, moderator, "ACCEPT_REPORT", r7b, at(610, 100));
            UUID m7c = media("7c. REMOVE por moderador que é o próprio autor", "REMOVED", moderatorAuthor, at(620));
            audit(null, m7c, moderatorAuthor, "REMOVE", null, at(620, 100));

            // Controles positivos
            UUID m8 = media("8. REMOVE por moderador", "REMOVED", author, at(700));
            audit(null, m8, moderator, "REMOVE", null, at(700, 100));
            UUID m9 = media("9. AUTHOR_REMOVE", "REMOVED", author, at(710));
            audit(null, m9, author, "AUTHOR_REMOVE", null, at(710, 100));
            UUID m10 = media("10. PLACE_MANAGER_REMOVE", "REMOVED", author, at(720));
            audit(null, m10, creator, "PLACE_MANAGER_REMOVE", null, at(720, 100));
            UUID m11 = media("11. REMOVE genérico pelo criador do local", "REMOVED", author, at(730));
            audit(null, m11, creator, "REMOVE", null, at(730, 100));
            UUID m12 = media("12. autor remove antes de aceite posterior", "REMOVED", author, at(740));
            audit(null, m12, author, "AUTHOR_REMOVE", null, at(740, 100));
            UUID r12 = report(m12, "ACCEPTED", moderator, at(800));
            audit(null, m12, moderator, "ACCEPT_REPORT", r12, at(800, 100));

            // LEG-2: decisões históricas
            UUID active = media("decisões", "ACTIVE", author, null);
            UUID ra = report(active, "ACCEPTED", moderator, at(900));
            audit(null, active, moderator, "ACCEPT_REPORT", ra, at(900, 100));
            report.put("a. aceite com evento do mesmo moderador", ra);
            report.put("b. aceite sem decisor", report(active, "ACCEPTED", null, at(901)));
            report.put("c. aceite com o autor como decisor e sem evento", report(active, "ACCEPTED", author, at(902)));
            UUID rd = report(active, "ACCEPTED", author, at(903));
            audit(null, active, null, "AUTO_RESOLVE_REPORT", rd, at(903, 100));
            report.put("d. aceite com o autor como decisor e AUTO_RESOLVE do SYSTEM", rd);
            UUID re = report(active, "REJECTED", moderator, at(904));
            audit(null, active, regular, "REJECT_REPORT", re, at(904, 100));
            report.put("e. rejeição cujo evento é de outro ator", re);
            UUID rg = report(active, "ACCEPTED", moderator, at(905));
            audit(null, active, moderator, "ACCEPT_REPORT", rg, at(905, 100));
            audit(null, active, null, "AUTO_RESOLVE_REPORT", rg, at(905, 200));
            report.put("g. aceite com evento humano e AUTO_RESOLVE contraditório", rg);
            UUID rh = report(active, "REJECTED", moderator, at(906));
            audit(null, active, moderator, "REJECT_REPORT", rh, at(906, 100));
            report.put("h. rejeição validada", rh);

            // Trilhas de avaliação, comentário e resposta existentes antes da V36 (ainda com FK em cascata)
            review = UUID.randomUUID();
            exec("INSERT INTO reviews (id, user_id) VALUES (?, ?)", review, author);
            discussion = UUID.randomUUID();
            exec("INSERT INTO review_discussions (id, review_id, user_id, content) VALUES (?, ?, ?, 'Comentário V35')", discussion, review, author);
            UUID business = UUID.randomUUID();
            exec("INSERT INTO business_accounts (id, user_id, corporate_name, tax_id, verification_status) "
                    + "VALUES (?, ?, 'Empresa V35', ?, 'APPROVED')", business, creator, "V35" + business.toString().substring(0, 8));
            exec("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", business, place);
            response = UUID.randomUUID();
            exec("INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content) "
                    + "VALUES (?, ?, ?, ?, ?, 'Resposta V35')", response, review, business, place, creator);
            exec("INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, "
                    + "previous_review_status, new_review_status) VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'V35', 'ACTIVE', 'REMOVED')",
                    review, moderator);
            exec("INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, "
                    + "justification, previous_status, new_status) VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', 'V35', 'ACTIVE', 'REMOVED')",
                    discussion, moderator);
            exec("INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, reason_code, justification, "
                    + "previous_status, new_status) VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'V35', 'ACTIVE', 'REMOVED')", response, moderator);
        }

        private UUID user(String role) throws SQLException {
            UUID id = UUID.randomUUID();
            exec("INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', ?)", id, "v35." + id + "@rewit.test", role);
            return id;
        }

        private UUID media(String name, String status, UUID owner, Timestamp removedAt) throws SQLException {
            UUID id = UUID.randomUUID();
            exec("INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, "
                    + "removed_at, removal_source) VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 800, 600, ?, ?, NULL)",
                    id, place, owner, "places/v35-" + id + ".jpg", status, removedAt);
            media.put(name, id);
            return id;
        }

        private UUID report(UUID mediaId, String status, UUID decidedBy, Timestamp decidedAt) throws SQLException {
            UUID id = UUID.randomUUID();
            exec("INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) "
                    + "VALUES (?, ?, ?, 'SPAM', ?, ?, ?)", id, mediaId, user("USER"), status, decidedBy, decidedAt);
            return id;
        }

        private void audit(UUID id, UUID mediaId, UUID actor, String action, UUID reportId, Timestamp createdAt) throws SQLException {
            exec("INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, "
                    + "report_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id != null ? id : UUID.randomUUID(), mediaId, place, actor, actor != null ? "USER" : "SYSTEM", action, reportId, createdAt);
        }

        private void exec(String sql, Object... args) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
        }
    }

    // ============================================================================================ utilitários

    private static String string(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static long count(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }
}
