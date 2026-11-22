package com.rewit.infrastructure.persistence;

import com.rewit.config.TestInfrastructureConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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
            placeMediaAuditBefore = string(conn, "SELECT md5(string_agg(row(id, place_media_id, place_id, actor_user_id, action, reason, "
                    + "notes, report_id, created_at, actor_type)::text, '|' ORDER BY id)) FROM place_media_moderation_audit_logs");
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
                    string(conn, "SELECT md5(string_agg(row(id, place_media_id, place_id, actor_user_id, action, reason, "
                            + "notes, report_id, created_at, actor_type)::text, '|' ORDER BY id)) FROM place_media_moderation_audit_logs"));
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
        // A nova tentativa aplica a V37 e todas as posteriores; nenhuma fica pendente nem falha
        assertEquals(0, flyway(null).info().pending().length);
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '37' AND success"));
            assertEquals(0, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"));
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

    // ============================================================================================ V38

    @Test
    @DisplayName("V38: upgrade V37 -> V38 preserva os locais existentes, que ficam sem contatos; CHECKs e privilégios ativos")
    void upgradeToV38KeepsExistingPlacesWithoutContacts() throws SQLException {
        flyway("37").migrate();
        UUID user = UUID.randomUUID();
        UUID business = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();
        UUID community = UUID.randomUUID();
        String snapshot = """
                SELECT string_agg(concat_ws('|', id, name, slug, category, description, address_text, city, state,
                    ST_AsText(coordinates), status, claimed_by_business_id, created_by_user_id, is_verified, created_at,
                    updated_at), ',' ORDER BY id)
                FROM places WHERE id IN (?, ?)
                """;
        String before;
        try (Connection conn = owner()) {
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    user, "v37." + user + "@rewit.test");
            update(conn, "INSERT INTO business_accounts (id, user_id, corporate_name, tax_id, verification_status) "
                    + "VALUES (?, ?, 'Empresa V37', ?, 'APPROVED')", business, user, "V37" + business.toString().substring(0, 8));
            for (UUID place : new UUID[]{claimed, community}) {
                update(conn, "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", place);
            }
            update(conn, "INSERT INTO places (id, name, slug, category, description, address_text, city, state, "
                    + "coordinates, claimed_by_business_id, created_by_user_id) VALUES (?, 'Local V37', ?, 'BAR', "
                    + "'Descrição anterior à V38', 'Rua V37', 'Curitiba', 'PR', "
                    + "ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), ?, ?)", claimed, "v37-" + claimed, business, user);
            update(conn, "INSERT INTO places (id, name, slug, category, address_text, city, state) "
                    + "VALUES (?, 'Local V37 sem descrição', ?, 'CAFE', 'Rua V37, 2', 'Curitiba', 'PR')",
                    community, "v37-" + community);
            before = string(conn, snapshot, claimed, community);
        }

        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '38' AND success"));
            assertEquals(before, string(conn, snapshot, claimed, community), "Nenhum dado existente muda");
            assertEquals(2, count(conn, "SELECT count(*) FROM places WHERE id IN (?, ?) AND phone IS NULL "
                    + "AND website_url IS NULL AND instagram_url IS NULL AND facebook_url IS NULL", claimed, community));
            assertEquals(4, count(conn, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'places'::regclass AND conname IN "
                    + "('chk_places_phone_format', 'chk_places_website_url_scheme', 'chk_places_instagram_url_scheme', "
                    + "'chk_places_facebook_url_scheme')"));
            // Privilégio de tabela da V36 já cobre as colunas novas; a política da V37 continua intacta
            assertEquals("true", string(conn, "SELECT has_column_privilege('rewit_app', 'places', 'phone', 'UPDATE')::text"));
            st.execute("SELECT rewit_assert_runtime_privileges()");
        }
    }

    // ============================================================================================ V39

    @Test
    @DisplayName("V39: upgrade V38 -> V39 preserva contatos e descrições existentes; depois, só a troca de titular apaga "
            + "os contatos, sem tocar na descrição; privilégios do runtime intactos")
    void upgradeToV39KeepsContactsUntilOwnerChanges() throws SQLException {
        flyway("38").migrate();
        UUID user = UUID.randomUUID();
        UUID businessA = UUID.randomUUID();
        UUID businessB = UUID.randomUUID();
        UUID place = UUID.randomUUID();
        String profile = "SELECT concat_ws('|', description, phone, website_url, instagram_url, facebook_url) "
                + "FROM places WHERE id = ?";
        String stored;
        try (Connection conn = owner()) {
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    user, "v38." + user + "@rewit.test");
            for (UUID business : new UUID[]{businessA, businessB}) {
                update(conn, "INSERT INTO business_accounts (id, user_id, corporate_name, tax_id, verification_status) "
                        + "VALUES (?, ?, 'Empresa V38', ?, 'APPROVED')", business, user,
                        "V38" + business.toString().substring(0, 8));
            }
            update(conn, "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", place);
            update(conn, "INSERT INTO places (id, name, slug, category, description, address_text, city, state, "
                    + "claimed_by_business_id, phone, website_url, instagram_url, facebook_url) VALUES (?, 'Local V38', ?, "
                    + "'CAFE', 'Descrição V38', 'Rua V38', 'Curitiba', 'PR', ?, '+55 41 3333-4444', 'https://v38.example', "
                    + "'https://instagram.com/v38', 'https://facebook.com/v38')", place, "v38-" + place, businessA);
            stored = string(conn, profile, place);
        }

        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '39' AND success"));
            assertEquals(stored, string(conn, profile, place), "A migração não apaga contatos já gravados");
            assertEquals("search_path=pg_catalog, public, pg_temp", string(conn,
                    "SELECT array_to_string(proconfig, ',') FROM pg_proc WHERE proname = 'fn_clear_place_business_contacts_on_owner_change'"));
            st.execute("SELECT rewit_assert_runtime_privileges()");

            update(conn, "UPDATE places SET claimed_by_business_id = ? WHERE id = ?", businessA, place);
            assertEquals(stored, string(conn, profile, place), "Mesmo titular: nada muda");
            update(conn, "UPDATE places SET claimed_by_business_id = ? WHERE id = ?", businessB, place);
            assertEquals("Descrição V38", string(conn, profile, place), "Troca de titular: só a descrição permanece");
        }
    }

    // ============================================================================================ V40

    @Test
    @DisplayName("V40: upgrade V39 -> V40 dispensa do questionário as contas existentes (concluído, sem interesses); "
            + "contas novas começam sem perfil; CHECK do catálogo ativo; runtime grava sem apagar")
    void upgradeToV40ExemptsExistingAccountsFromOnboarding() throws SQLException {
        flyway("39").migrate();
        UUID active = UUID.randomUUID();
        UUID deactivated = UUID.randomUUID();
        try (Connection conn = owner()) {
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    active, "v39a." + active + "@rewit.test");
            update(conn, "INSERT INTO users (id, email, auth_provider, role, account_status, is_active) "
                    + "VALUES (?, ?, 'LOCAL', 'USER', 'DEACTIVATED', false)", deactivated, "v39d." + deactivated + "@rewit.test");
        }

        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '40' AND success"));
            assertEquals(2, count(conn, "SELECT count(*) FROM user_interest_profiles WHERE user_id IN (?, ?) "
                    + "AND onboarding_completed_at IS NOT NULL", active, deactivated));
            assertEquals(0, count(conn, "SELECT count(*) FROM user_interests WHERE user_id IN (?, ?)", active, deactivated));

            UUID created = UUID.randomUUID();
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    created, "v40." + created + "@rewit.test");
            assertEquals(0, count(conn, "SELECT count(*) FROM user_interest_profiles WHERE user_id = ?", created));

            update(conn, "INSERT INTO user_interests (user_id, interest_name, category_code) VALUES (?, 'Cafés', 'CAFE')",
                    created);
            assertThrows(SQLException.class, () -> update(conn,
                    "INSERT INTO user_interests (user_id, interest_name, category_code) VALUES (?, 'Sauna', 'SAUNA')",
                    created));

            for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE"}) {
                assertEquals("true", string(conn,
                        "SELECT has_table_privilege('rewit_app', 'user_interest_profiles', ?)::text", privilege));
            }
            assertEquals("false", string(conn,
                    "SELECT has_table_privilege('rewit_app', 'user_interest_profiles', 'DELETE')::text"));
            st.execute("SELECT rewit_assert_runtime_privileges()");
        }
    }

    @Test
    @DisplayName("V43: upgrade V42 -> V43 limpa coordenadas históricas (VERIFIED/REJECTED/PENDING), preserva reviews.updated_at e aplica constraints estruturais")
    void upgradeFromV42MinimizesHistoricalCheckInCoordinatesAndPreservesVerifiedReviews() throws SQLException {
        flyway("42").migrate();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID verifiedReviewId = UUID.randomUUID();
        UUID rejectedReviewId = UUID.randomUUID();
        UUID pendingReviewId = UUID.randomUUID();
        UUID verifiedCheckInId = UUID.randomUUID();
        UUID rejectedCheckInId = UUID.randomUUID();
        UUID pendingCheckInId = UUID.randomUUID();
        Timestamp verifiedReviewUpdatedAt = Timestamp.from(Instant.parse("2026-01-01T12:00:00Z"));
        Timestamp rejectedReviewUpdatedAt = Timestamp.from(Instant.parse("2026-01-02T12:00:00Z"));
        Timestamp pendingReviewUpdatedAt = Timestamp.from(Instant.parse("2026-01-03T12:00:00Z"));

        try (Connection conn = owner()) {
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    userId, "v42." + userId + "@rewit.test");
            update(conn, "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", placeId);
            update(conn, "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) "
                            + "VALUES (?, 'Local V42', ?, 'CAFE', 'Rua V42, 10', 'Curitiba', 'PR', "
                            + "ST_SetSRID(ST_MakePoint(-49.2850, -25.4385), 4326)::geography)",
                    placeId, "local-v42-" + placeId);
            update(conn, "INSERT INTO reviews (id, user_id, context_place_id, experience_text) VALUES (?, ?, ?, 'Review verificado')",
                    verifiedReviewId, userId, placeId);
            update(conn, "INSERT INTO reviews (id, user_id, context_place_id, experience_text) VALUES (?, ?, ?, 'Review rejeitado')",
                    rejectedReviewId, userId, placeId);
            update(conn, "INSERT INTO reviews (id, user_id, context_place_id, experience_text) VALUES (?, ?, ?, 'Review pendente')",
                    pendingReviewId, userId, placeId);

            update(conn, "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status, verified_at) "
                            + "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.2849, -25.4384), 4326)::geography, 12.50, 'VERIFIED', NOW())",
                    verifiedCheckInId, verifiedReviewId, userId, placeId);
            update(conn, "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) "
                            + "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.2733, -25.4284), 4326)::geography, 1540.00, 'REJECTED')",
                    rejectedCheckInId, rejectedReviewId, userId, placeId);
            update(conn, "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) "
                            + "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.2800, -25.4350), 4326)::geography, 480.00, 'PENDING')",
                    pendingCheckInId, pendingReviewId, userId, placeId);

            // Fixa updated_at históricos após inserção dos check-ins para comprovar que o backfill não altera timestamps
            update(conn, "UPDATE reviews SET updated_at = ? WHERE id = ?", verifiedReviewUpdatedAt, verifiedReviewId);
            update(conn, "UPDATE reviews SET updated_at = ? WHERE id = ?", rejectedReviewUpdatedAt, rejectedReviewId);
            update(conn, "UPDATE reviews SET updated_at = ? WHERE id = ?", pendingReviewUpdatedAt, pendingReviewId);

            assertEquals("true", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", verifiedReviewId));
            assertEquals("false", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", rejectedReviewId));
            assertEquals("false", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", pendingReviewId));
        }

        assertTrue(flyway(null).migrate().success);

        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '43' AND success"));

            // Todas as linhas permanecem; coordenadas históricas são descartadas em VERIFIED, REJECTED e PENDING
            assertEquals(3, count(conn, "SELECT count(*) FROM check_ins WHERE id IN (?, ?, ?)",
                    verifiedCheckInId, rejectedCheckInId, pendingCheckInId));
            assertEquals(0, count(conn, "SELECT count(*) FROM check_ins WHERE coordinates IS NOT NULL"));
            assertEquals("VERIFIED", string(conn, "SELECT status FROM check_ins WHERE id = ?", verifiedCheckInId));
            assertEquals("REJECTED", string(conn, "SELECT status FROM check_ins WHERE id = ?", rejectedCheckInId));
            assertEquals("PENDING", string(conn, "SELECT status FROM check_ins WHERE id = ?", pendingCheckInId));

            // Distância preservada apenas em VERIFIED; minimizada em REJECTED e PENDING históricos
            assertEquals("12.50", string(conn, "SELECT distance_to_centroid_meters::text FROM check_ins WHERE id = ?", verifiedCheckInId));
            assertNull(string(conn, "SELECT distance_to_centroid_meters::text FROM check_ins WHERE id = ?", rejectedCheckInId));
            assertNull(string(conn, "SELECT distance_to_centroid_meters::text FROM check_ins WHERE id = ?", pendingCheckInId));

            // verified_at e rejection_reason coerentes em PENDING e REJECTED históricos
            assertNotNull(string(conn, "SELECT verified_at::text FROM check_ins WHERE id = ?", verifiedCheckInId));
            assertNull(string(conn, "SELECT verified_at::text FROM check_ins WHERE id = ?", rejectedCheckInId));
            assertNull(string(conn, "SELECT verified_at::text FROM check_ins WHERE id = ?", pendingCheckInId));
            assertNull(string(conn, "SELECT rejection_reason FROM check_ins WHERE id = ?", verifiedCheckInId));
            assertNull(string(conn, "SELECT rejection_reason FROM check_ins WHERE id = ?", rejectedCheckInId));
            assertNull(string(conn, "SELECT rejection_reason FROM check_ins WHERE id = ?", pendingCheckInId));

            // Invariante de reviews.is_verified_on_site e updated_at preservados sem efeito colateral
            assertEquals("true", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", verifiedReviewId));
            assertEquals("false", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", rejectedReviewId));
            assertEquals("false", string(conn, "SELECT is_verified_on_site::text FROM reviews WHERE id = ?", pendingReviewId));
            assertEquals(verifiedReviewUpdatedAt.toInstant(),
                    timestamp(conn, "SELECT updated_at FROM reviews WHERE id = ?", verifiedReviewId).toInstant());
            assertEquals(rejectedReviewUpdatedAt.toInstant(),
                    timestamp(conn, "SELECT updated_at FROM reviews WHERE id = ?", rejectedReviewId).toInstant());
            assertEquals(pendingReviewUpdatedAt.toInstant(),
                    timestamp(conn, "SELECT updated_at FROM reviews WHERE id = ?", pendingReviewId).toInstant());

            // Nulabilidade e coluna rejection_reason no catálogo
            assertEquals("YES", string(conn, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'check_ins' AND column_name = 'coordinates'"));
            assertEquals("YES", string(conn, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'check_ins' AND column_name = 'distance_to_centroid_meters'"));
            assertEquals("YES", string(conn, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'check_ins' AND column_name = 'rejection_reason'"));
            assertEquals(0, count(conn, "SELECT count(*) FROM pg_indexes WHERE tablename = 'check_ins' AND indexname = 'idx_checkins_coordinates'"));

            // Constraints estruturais de coordenadas, distância em REJECTED e rejection_reason
            UUID newReviewForValidReject = UUID.randomUUID();
            update(conn, "INSERT INTO reviews (id, user_id, context_place_id) VALUES (?, ?, ?)",
                    newReviewForValidReject, userId, placeId);
            update(conn, "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status, rejection_reason) "
                            + "VALUES (?, ?, ?, ?, NULL, NULL, 'REJECTED', 'OUTSIDE_RADIUS')",
                    UUID.randomUUID(), newReviewForValidReject, userId, placeId);

            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET rejection_reason = 'OUTSIDE_RADIUS' WHERE id = ?", verifiedCheckInId));
            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET rejection_reason = 'OUTSIDE_RADIUS' WHERE id = ?", pendingCheckInId));
            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET rejection_reason = 'INVENTED_REASON' WHERE id = ?", rejectedCheckInId));
            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET coordinates = ST_SetSRID(ST_MakePoint(-49.28, -25.43), 4326)::geography WHERE id = ?",
                    verifiedCheckInId));
            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET distance_to_centroid_meters = 500.00 WHERE id = ?", rejectedCheckInId));
            assertThrows(SQLException.class, () -> update(conn,
                    "UPDATE check_ins SET distance_to_centroid_meters = 50.00 WHERE id = ?", pendingCheckInId));

            st.execute("SELECT rewit_assert_runtime_privileges()");
        }
    }

    // ============================================================================================ V45 (S3-A13-F2)

    @Test
    @DisplayName("V45: upgrade V44 -> V45 preserva registros históricos como não autenticados (authenticity_mac NULL), cria rewit_mod e iguala privilégios à instalação limpa")
    void upgradeFromV44ToV45PreservesHistoricalRecordsAndSeparatesRoles() throws SQLException {
        flyway("44").migrate();
        UUID moderator = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID businessOwner = UUID.randomUUID();
        UUID place = UUID.randomUUID();
        UUID business = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        UUID discussion = UUID.randomUUID();
        UUID response = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        UUID report = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        UUID reviewAudit = UUID.randomUUID();
        UUID discussionAudit = UUID.randomUUID();
        UUID responseAudit = UUID.randomUUID();
        UUID mediaAudit = UUID.randomUUID();

        try (Connection conn = owner()) {
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'MODERATOR')",
                    moderator, "v44mod." + moderator + "@rewit.test");
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    author, "v44author." + author + "@rewit.test");
            update(conn, "INSERT INTO users (id, email, auth_provider, role) VALUES (?, ?, 'LOCAL', 'USER')",
                    businessOwner, "v44biz." + businessOwner + "@rewit.test");
            update(conn, "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", place);
            update(conn, "INSERT INTO places (id, name, slug, category, address_text, city, state) "
                    + "VALUES (?, 'Local V44', ?, 'CAFE', 'Rua V44', 'Curitiba', 'PR')", place, "v44-" + place);
            update(conn, "INSERT INTO business_accounts (id, user_id, corporate_name, tax_id, verification_status) "
                    + "VALUES (?, ?, 'Empresa V44', ?, 'APPROVED')", business, businessOwner, "44" + business.toString().replace("-", "").substring(0, 12));
            update(conn, "UPDATE places SET claimed_by_business_id = ? WHERE id = ?", business, place);
            update(conn, "INSERT INTO reviews (id, user_id, context_place_id, status) VALUES (?, ?, ?, 'REMOVED')", review, author, place);
            update(conn, "INSERT INTO review_discussions (id, review_id, user_id, content, status) VALUES (?, ?, ?, 'Comentário V44', 'REMOVED')",
                    discussion, review, author);
            update(conn, "INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content, status, removed_by, removed_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'Resposta V44', 'REMOVED', 'MODERATION', NOW())", response, review, business, place, businessOwner);
            update(conn, "INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, removed_at, removal_source) "
                    + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 800, 600, 'REMOVED', NOW() - INTERVAL '2 days', 'MODERATOR')",
                    media, place, author, "places/v44-" + media + ".jpg");
            update(conn, "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason) "
                    + "VALUES (?, ?, ?, 'SPAM')", report, media, businessOwner);
            update(conn, "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW() WHERE id = ?",
                    moderator, report);
            update(conn, "INSERT INTO place_claim_requests (id, business_account_id, place_id, evidence_description, status, decided_at, decided_by_user_id, decision_reason) "
                    + "VALUES (?, ?, ?, 'Evidência histórica V44 suficiente para teste de upgrade.', 'APPROVED', NOW(), ?, 'Justificativa histórica V44 válida.')",
                    claim, business, place, moderator);
            update(conn, "INSERT INTO moderation_audit_logs (id, review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status) "
                    + "VALUES (?, ?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Histórico V44', 'ACTIVE', 'REMOVED')", reviewAudit, review, moderator);
            update(conn, "INSERT INTO discussion_moderation_audit_logs (id, discussion_id, moderator_user_id, action, decision, reason_code, justification, previous_status, new_status) "
                    + "VALUES (?, ?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', 'Histórico V44', 'ACTIVE', 'REMOVED')", discussionAudit, discussion, moderator);
            update(conn, "INSERT INTO business_response_moderation_audit_logs (id, response_id, moderator_user_id, action, reason_code, justification, previous_status, new_status) "
                    + "VALUES (?, ?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Histórico V44', 'ACTIVE', 'REMOVED')", responseAudit, response, moderator);
            update(conn, "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, report_id) "
                    + "VALUES (?, ?, ?, ?, 'USER', 'ACCEPT_REPORT', ?)", mediaAudit, media, place, moderator, report);
            UUID legacyQuarantineId = UUID.randomUUID();
            update(conn, "INSERT INTO storage_object_quarantine (id, object_key, status, first_observed_at, last_observed_at, confirmed_at) "
                    + "VALUES (?, 'reviews/legacy-test.jpg', 'CONFIRMED_ORPHAN', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day')",
                    legacyQuarantineId);
        }

        assertTrue(flyway(null).migrate().success);

        String privilegesForRole = """
                SELECT string_agg(format('%s:%s', c.relname, p.priv), ',' ORDER BY c.relname, p.priv)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')) p(priv)
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v') AND c.relname <> 'flyway_schema_history'
                  AND has_table_privilege(?, c.oid, p.priv)
                """;
        String upgradedAppPrivs;
        String upgradedMediaPrivs;
        String upgradedModPrivs;
        try (Connection conn = owner(); Statement st = conn.createStatement()) {
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '45' AND success"));
            assertEquals(1, count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '46' AND success"));
            st.execute("SELECT rewit_assert_runtime_privileges()");

            // V46: Sanitização de registros legados inconsistentes de quarentena
            assertEquals("OBSERVED", string(conn, "SELECT status FROM storage_object_quarantine WHERE object_key = 'reviews/legacy-test.jpg'"));
            assertNull(string(conn, "SELECT confirmed_at::text FROM storage_object_quarantine WHERE object_key = 'reviews/legacy-test.jpg'"));

            // Registros históricos permanecem intactos e explicitamente sem MAC (authenticity_mac IS NULL)
            assertNull(string(conn, "SELECT authenticity_mac FROM moderation_audit_logs WHERE id = ?", reviewAudit));
            assertNull(string(conn, "SELECT authenticity_mac FROM discussion_moderation_audit_logs WHERE id = ?", discussionAudit));
            assertNull(string(conn, "SELECT authenticity_mac FROM business_response_moderation_audit_logs WHERE id = ?", responseAudit));
            assertNull(string(conn, "SELECT authenticity_mac FROM place_media_moderation_audit_logs WHERE id = ?", mediaAudit));
            assertNull(string(conn, "SELECT authenticity_mac FROM place_media_reports WHERE id = ?", report));
            assertNull(string(conn, "SELECT authenticity_mac FROM place_claim_requests WHERE id = ?", claim));

            // Novas trilhas existem e são append-only
            assertNotNull(string(conn, "SELECT to_regclass('public.account_moderation_audit_logs')::text"));
            assertNotNull(string(conn, "SELECT to_regclass('public.place_claim_moderation_audit_logs')::text"));

            upgradedAppPrivs = string(conn, privilegesForRole, "rewit_app");
            upgradedMediaPrivs = string(conn, privilegesForRole, "rewit_media");
            upgradedModPrivs = string(conn, privilegesForRole, "rewit_mod");
        }

        // Compara privilégios efetivos de upgrade V44 -> V45 com instalação limpa
        dropDisposableDatabase();
        createDisposableDatabase();
        assertTrue(flyway(null).migrate().success);
        try (Connection conn = owner()) {
            assertEquals(string(conn, privilegesForRole, "rewit_app"), upgradedAppPrivs);
            assertEquals(string(conn, privilegesForRole, "rewit_media"), upgradedMediaPrivs);
            assertEquals(string(conn, privilegesForRole, "rewit_mod"), upgradedModPrivs);
        }
    }

    @Test
    @DisplayName("SEC-F2-04-VULN-01: upgrade V45 -> V46 reinicia TODAS as entradas legadas (OBSERVED e CONFIRMED_ORPHAN) e bloqueia confirmação/expurgo imediato")
    void upgradeV45ToV46ResetsAllLegacyQuarantineEntriesAndBlocksImmediateConfirmationAndPurge() throws Exception {
        // 1. Provisiona schema até V45 (antes da proteção V46)
        assertTrue(flyway("45").migrate().success);

        UUID legacyObservedId = UUID.randomUUID();
        UUID legacyConfirmedId = UUID.randomUUID();
        UUID legacyFutureModifiedId = UUID.randomUUID();
        String observedKey = "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";
        String confirmedKey = "places/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";
        String futureModKey = "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";

        MinioClient minio = MinioClient.builder()
                .endpoint(INFRASTRUCTURE.storageEndpoint())
                .credentials(INFRASTRUCTURE.storageAccessKey(), INFRASTRUCTURE.storageSecretKey())
                .build();
        String bucket = INFRASTRUCTURE.storageBucket();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        byte[] payload = "legacy-orphan-payload".getBytes();
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(observedKey)
                .stream(new ByteArrayInputStream(payload), payload.length, -1).contentType("image/jpeg").build());
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(confirmedKey)
                .stream(new ByteArrayInputStream(payload), payload.length, -1).contentType("image/jpeg").build());

        try {
            // 2-4. Conecta como rewit_app real pré-V46 e fabrica entradas retroativas (OBSERVED 48h e CONFIRMED_ORPHAN 48h)
            try (Connection appConn = DriverManager.getConnection(
                    databaseUrl, INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.appDbPassword())) {
                update(appConn, "INSERT INTO storage_object_quarantine (id, object_key, status, first_observed_at, last_observed_at, confirmed_at) "
                                + "VALUES (?, ?, 'OBSERVED', NOW() - INTERVAL '48 hours', NOW() - INTERVAL '48 hours', NULL)",
                        legacyObservedId, observedKey);
                update(appConn, "INSERT INTO storage_object_quarantine (id, object_key, status, first_observed_at, last_observed_at, confirmed_at) "
                                + "VALUES (?, ?, 'CONFIRMED_ORPHAN', NOW() - INTERVAL '48 hours', NOW() - INTERVAL '48 hours', NOW() - INTERVAL '24 hours')",
                        legacyConfirmedId, confirmedKey);
                update(appConn, "INSERT INTO storage_object_quarantine (id, object_key, status, first_observed_at, last_observed_at, last_modified_at, confirmed_at) "
                                + "VALUES (?, ?, 'OBSERVED', NOW() - INTERVAL '25 hours', NOW() - INTERVAL '25 hours', NOW() + INTERVAL '10 hours', NULL)",
                        legacyFutureModifiedId, futureModKey);
            }

            // 6. Executa migration corretiva V46
            assertTrue(flyway(null).migrate().success);

            // 7. Confere estado pós-V46: TODAS as entradas reiniciadas para OBSERVED com um único instante confiável
            try (Connection ownerConn = owner()) {
                assertEquals("OBSERVED", string(ownerConn, "SELECT status FROM storage_object_quarantine WHERE id = ?", legacyObservedId));
                assertNull(string(ownerConn, "SELECT confirmed_at::text FROM storage_object_quarantine WHERE id = ?", legacyObservedId));
                assertEquals(observedKey, string(ownerConn, "SELECT object_key FROM storage_object_quarantine WHERE id = ?", legacyObservedId));

                assertEquals("OBSERVED", string(ownerConn, "SELECT status FROM storage_object_quarantine WHERE id = ?", legacyConfirmedId));
                assertNull(string(ownerConn, "SELECT confirmed_at::text FROM storage_object_quarantine WHERE id = ?", legacyConfirmedId));
                assertEquals(confirmedKey, string(ownerConn, "SELECT object_key FROM storage_object_quarantine WHERE id = ?", legacyConfirmedId));

                Timestamp obsFirst = timestamp(ownerConn, "SELECT first_observed_at FROM storage_object_quarantine WHERE id = ?", legacyObservedId);
                Timestamp obsLast = timestamp(ownerConn, "SELECT last_observed_at FROM storage_object_quarantine WHERE id = ?", legacyObservedId);
                Timestamp confFirst = timestamp(ownerConn, "SELECT first_observed_at FROM storage_object_quarantine WHERE id = ?", legacyConfirmedId);
                Timestamp futureMod = timestamp(ownerConn, "SELECT last_modified_at FROM storage_object_quarantine WHERE id = ?", legacyFutureModifiedId);

                assertEquals(obsFirst, obsLast, "first_observed_at e last_observed_at devem usar o mesmo instante confiável");
                assertEquals(obsFirst, confFirst, "Todo o lote da migration deve compartilhar um único instante confiável do PostgreSQL");
                assertTrue(obsFirst.toInstant().isAfter(Instant.now().minusSeconds(120)),
                        "Idade da entrada OBSERVED legada de 48h deve ter sido reiniciada");
                assertFalse(futureMod.toInstant().isAfter(obsFirst.toInstant()),
                        "last_modified_at futuro deve ter sido ajustado de forma coerente");
            }

            // 8-10. Reconecta como rewit_app real e tenta confirmar/expurgar imediatamente: bloqueio obrigatório
            try (Connection appConn = DriverManager.getConnection(
                    databaseUrl, INFRASTRUCTURE.appDbUser(), INFRASTRUCTURE.appDbPassword())) {
                SQLException exObs = assertThrows(SQLException.class,
                        () -> string(appConn, "SELECT public.rewit_confirm_storage_quarantine_entry(?)::text", legacyObservedId));
                assertTrue(exObs.getMessage().contains("24 horas") || "42501".equals(exObs.getSQLState()),
                        "Confirmação imediata de entrada OBSERVED legada deve ser bloqueada: " + exObs.getMessage());

                SQLException exConf = assertThrows(SQLException.class,
                        () -> string(appConn, "SELECT public.rewit_confirm_storage_quarantine_entry(?)::text", legacyConfirmedId));
                assertTrue(exConf.getMessage().contains("24 horas") || "42501".equals(exConf.getSQLState()),
                        "Confirmação imediata de entrada CONFIRMED_ORPHAN rebaixada deve ser bloqueada: " + exConf.getMessage());

                // Elegibilidade final de expurgo pelo PostgreSQL retorna false para ambas as entradas
                String eligibilitySql = """
                        SELECT (
                            q.status = 'CONFIRMED_ORPHAN'
                            AND q.first_observed_at <= t.v_now
                            AND q.first_observed_at <= t.v_now - INTERVAL '24 hours'
                            AND q.confirmed_at IS NOT NULL
                            AND q.confirmed_at <= t.v_now
                            AND q.confirmed_at >= q.first_observed_at + INTERVAL '24 hours'
                        )::text
                        FROM public.storage_object_quarantine q
                        CROSS JOIN (SELECT clock_timestamp() AS v_now) t
                        WHERE q.id = ?
                        """;
                assertEquals("false", string(appConn, eligibilitySql, legacyObservedId));
                assertEquals("false", string(appConn, eligibilitySql, legacyConfirmedId));
            }

            // Objetos físicos no SeaweedFS descartável permanecem intactos
            assertDoesNotThrow(() -> minio.statObject(StatObjectArgs.builder().bucket(bucket).object(observedKey).build()),
                    "Objeto físico órfão legado (OBSERVED) deve permanecer preservado no storage");
            assertDoesNotThrow(() -> minio.statObject(StatObjectArgs.builder().bucket(bucket).object(confirmedKey).build()),
                    "Objeto físico órfão legado (CONFIRMED_ORPHAN rebaixado) deve permanecer preservado no storage");
        } finally {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(observedKey).build());
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(confirmedKey).build());
        }
    }

    private static Timestamp timestamp(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getTimestamp(1);
            }
        }
    }

    private static void update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
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
