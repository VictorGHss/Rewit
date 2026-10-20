package com.rewit.infrastructure.persistence;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Upgrade de Migrations com Dados Históricos Legítimos (V35 -> V36)")
class DatabaseUpgradeMigrationTest {

    private static final String MIGRATION_DB_USER = System.getProperty("spring.flyway.user",
            System.getenv().getOrDefault("TEST_MIGRATION_DB_USER",
                    System.getenv().getOrDefault("TEST_POSTGRES_USER", "rewit_user")));
    private static final String MIGRATION_DB_PASSWORD = System.getProperty("spring.flyway.password",
            System.getenv().getOrDefault("TEST_MIGRATION_DB_PASSWORD",
                    System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "test_postgres_password")));

    private static String resolveDbUrl() {
        String testPort = System.getProperty("TEST_POSTGRES_PORT", System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432"));
        String host = System.getProperty("TEST_POSTGRES_HOST", System.getenv().getOrDefault("TEST_POSTGRES_HOST", "127.0.0.1"));
        return "jdbc:postgresql://" + host + ":" + testPort + "/rewit_migration_test";
    }

    private static String resolveAdminDbUrl() {
        String testPort = System.getProperty("TEST_POSTGRES_PORT", System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432"));
        String host = System.getProperty("TEST_POSTGRES_HOST", System.getenv().getOrDefault("TEST_POSTGRES_HOST", "127.0.0.1"));
        String db = System.getProperty("TEST_POSTGRES_DB", System.getenv().getOrDefault("TEST_POSTGRES_DB", "rewit_db"));
        return "jdbc:postgresql://" + host + ":" + testPort + "/" + db;
    }

    @BeforeAll
    static void ensureMigrationTestDatabase() {
        try (Connection conn = DriverManager.getConnection(resolveAdminDbUrl(), MIGRATION_DB_USER, MIGRATION_DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'rewit_migration_test'");
            if (!rs.next()) {
                stmt.execute("CREATE DATABASE rewit_migration_test");
            }
        } catch (SQLException ignored) {
        }
    }

    @Test
    @DisplayName("V36 Upgrade: Conclui com sucesso sobre base V35 legítima com denúncias sem moderador e sem sobrescrever autoria")
    void shouldSuccessfullyUpgradeFromV35ToV36WithRepresentativeHistoricalData() throws SQLException {
        String dbUrl = resolveDbUrl();

        // 1. Limpa o schema de teste para garantir execução controlada
        Flyway flywayClean = Flyway.configure()
                .dataSource(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)
                .locations("classpath:db/migration")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .cleanDisabled(false)
                .load();
        flywayClean.clean();

        // 2. Aplica migrations somente até a V35
        Flyway flywayToV35 = Flyway.configure()
                .dataSource(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)
                .locations("classpath:db/migration")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .target("35")
                .load();
        flywayToV35.migrate();

        UUID placeId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID reporter1Id = UUID.randomUUID();
        UUID reporter2Id = UUID.randomUUID();
        UUID reporter3Id = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        UUID media1Id = UUID.randomUUID();
        UUID media2Id = UUID.randomUUID();
        UUID reportAcceptedNoModId = UUID.randomUUID();
        UUID reportRejectedNoModId = UUID.randomUUID();
        UUID reportAutoResolvedId = UUID.randomUUID();

        // 3. Insere dados legítimos de um ambiente em V35
        try (Connection conn = DriverManager.getConnection(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)) {
            // Semeia usuários
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, email, auth_provider, role) VALUES " +
                            "(?, 'author.v35@rewit.com', 'LOCAL', 'USER'), " +
                            "(?, 'rep1.v35@rewit.com', 'LOCAL', 'USER'), " +
                            "(?, 'rep2.v35@rewit.com', 'LOCAL', 'USER'), " +
                            "(?, 'rep3.v35@rewit.com', 'LOCAL', 'USER'), " +
                            "(?, 'mod.v35@rewit.com', 'LOCAL', 'MODERATOR')")) {
                ps.setObject(1, authorId);
                ps.setObject(2, reporter1Id);
                ps.setObject(3, reporter2Id);
                ps.setObject(4, reporter3Id);
                ps.setObject(5, moderatorId);
                ps.executeUpdate();
            }

            // Semeia local e mídias
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')")) {
                ps.setObject(1, placeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) " +
                            "VALUES (?, 'Local V35 Test', 'slug-v35-test', 'BAR', 'Rua V35', 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, is_primary, created_at, updated_at) " +
                            "VALUES (?, ?, ?, 'places/test-v35-1.jpg', 'IMAGE', 'image/jpeg', 1024, 800, 600, 'REMOVED', false, NOW() - INTERVAL '3 hours', NOW()), " +
                            "(?, ?, ?, 'places/test-v35-2.jpg', 'IMAGE', 'image/jpeg', 1024, 800, 600, 'ACTIVE', false, NOW() - INTERVAL '3 hours', NOW())")) {
                ps.setObject(1, media1Id);
                ps.setObject(2, placeId);
                ps.setObject(3, authorId);
                ps.setObject(4, media2Id);
                ps.setObject(5, placeId);
                ps.setObject(6, authorId);
                ps.executeUpdate();
            }

            // Cenário V36-1: Na mídia 1, o autor removeu primeiro (AUTHOR_REMOVE há 2 horas)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, created_at) " +
                            "VALUES (?, ?, ?, ?, 'USER', 'AUTHOR_REMOVE', 'Removido pelo autor', 'Voluntário', NOW() - INTERVAL '2 hours')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, media1Id);
                ps.setObject(3, placeId);
                ps.setObject(4, authorId);
                ps.executeUpdate();
            }

            // Mais tarde, o moderador aceitou denúncia (ACCEPT_REPORT há 1 hora)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, report_id, created_at) " +
                            "VALUES (?, ?, ?, ?, 'USER', 'ACCEPT_REPORT', 'Denúncia acolhida', 'Deferida', ?, NOW() - INTERVAL '1 hour')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, media1Id);
                ps.setObject(3, placeId);
                ps.setObject(4, moderatorId);
                ps.setObject(5, reportAcceptedNoModId);
                ps.executeUpdate();
            }

            // Cenário AU-1: Denúncia aceita legada sem moderador (decided_by_user_id NULL)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) " +
                            "VALUES (?, ?, ?, 'SPAM', 'ACCEPTED', NULL, NOW() - INTERVAL '1 hour')")) {
                ps.setObject(1, reportAcceptedNoModId);
                ps.setObject(2, media1Id);
                ps.setObject(3, reporter1Id);
                ps.executeUpdate();
            }

            // Denúncia rejeitada legada sem moderador (decided_by_user_id NULL)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) " +
                            "VALUES (?, ?, ?, 'OFENSIVO', 'REJECTED', NULL, NOW() - INTERVAL '1 hour')")) {
                ps.setObject(1, reportRejectedNoModId);
                ps.setObject(2, media2Id);
                ps.setObject(3, reporter2Id);
                ps.executeUpdate();
            }

            // Denúncia que teve AUTO_RESOLVE_REPORT com SYSTEM gravado na V35
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) " +
                            "VALUES (?, ?, ?, 'INADEQUADO', 'PENDING', NULL, NULL)")) {
                ps.setObject(1, reportAutoResolvedId);
                ps.setObject(2, media2Id);
                ps.setObject(3, reporter3Id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, report_id, created_at) " +
                            "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_RESOLVE_REPORT', 'Auto resolvida', 'Sistema', ?, NOW())")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, media2Id);
                ps.setObject(3, placeId);
                ps.setObject(4, reportAutoResolvedId);
                ps.executeUpdate();
            }
        }

        // 4. Executa a migração V36
        Flyway flywayUpgrade = Flyway.configure()
                .dataSource(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)
                .locations("classpath:db/migration")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();
        
        assertDoesNotThrow(flywayUpgrade::migrate, "O upgrade da V35 para a V36 com dados históricos legítimos deve concluir sem falhas");

        // 5. Valida os efeitos persistidos e a integridade final
        try (Connection conn = DriverManager.getConnection(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)) {
            // Valida que a denúncia aceita sem moderador virou is_legacy_decision = true
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, decided_by_user_id, is_legacy_decision FROM place_media_reports WHERE id = ?")) {
                ps.setObject(1, reportAcceptedNoModId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("ACCEPTED", rs.getString("status"));
                    assertNull(rs.getObject("decided_by_user_id"), "Não deve inventar moderador");
                    assertTrue(rs.getBoolean("is_legacy_decision"), "Deve estar marcada como decisão legada");
                }
            }

            // Valida que a denúncia rejeitada sem moderador virou is_legacy_decision = true
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, decided_by_user_id, is_legacy_decision FROM place_media_reports WHERE id = ?")) {
                ps.setObject(1, reportRejectedNoModId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("REJECTED", rs.getString("status"));
                    assertNull(rs.getObject("decided_by_user_id"), "Não deve inventar moderador");
                    assertTrue(rs.getBoolean("is_legacy_decision"), "Deve estar marcada como decisão legada");
                }
            }

            // Valida que a denúncia com AUTO_RESOLVE_REPORT virou status AUTO_RESOLVED
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, decided_by_user_id, is_legacy_decision FROM place_media_reports WHERE id = ?")) {
                ps.setObject(1, reportAutoResolvedId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("AUTO_RESOLVED", rs.getString("status"));
                    assertNull(rs.getObject("decided_by_user_id"));
                    assertFalse(rs.getBoolean("is_legacy_decision"));
                }
            }

            // Valida V36-1: Na mídia 1, o autor removeu primeiro; o ACCEPT_REPORT posterior não deve sobrescrever com MODERATOR
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT removal_source FROM place_media WHERE id = ?")) {
                ps.setObject(1, media1Id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("AUTHOR", rs.getString("removal_source"),
                            "A remoção do autor deve prevalecer contra o ACCEPT_REPORT posterior no ciclo");
                }
            }
        }
    }
}

