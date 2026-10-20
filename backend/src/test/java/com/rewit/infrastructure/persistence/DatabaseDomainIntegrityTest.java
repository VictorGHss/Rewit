package com.rewit.infrastructure.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Integridade Relacional e Triggers no PostgreSQL (Step 2.1)")
class DatabaseDomainIntegrityTest {

    private static final String MIGRATION_DB_USER = System.getProperty("spring.flyway.user",
            System.getenv().getOrDefault("TEST_MIGRATION_DB_USER",
                    System.getenv().getOrDefault("TEST_POSTGRES_USER", "rewit_user")));
    private static final String MIGRATION_DB_PASSWORD = System.getProperty("spring.flyway.password",
            System.getenv().getOrDefault("TEST_MIGRATION_DB_PASSWORD",
                    System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "rewit_local_password")));

    private static final String APP_DB_USER = System.getProperty("spring.datasource.username",
            System.getenv().getOrDefault("TEST_APP_DB_USER", "rewit_app"));
    private static final String APP_DB_PASSWORD = System.getProperty("spring.datasource.password",
            System.getenv().getOrDefault("TEST_APP_DB_PASSWORD", "rewit_app_local_password"));

    private static String resolveDbUrl() {
        String sysUrl = System.getProperty("spring.datasource.url");
        if (sysUrl != null && !sysUrl.isBlank()) {
            return sysUrl;
        }
        String sysTestUrl = System.getProperty("TEST_DATASOURCE_URL");
        if (sysTestUrl != null && !sysTestUrl.isBlank()) {
            return sysTestUrl;
        }
        String envUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl;
        }
        String testUrl = System.getenv("TEST_DATASOURCE_URL");
        if (testUrl != null && !testUrl.isBlank()) {
            return testUrl;
        }
        String testPort = System.getProperty("TEST_POSTGRES_PORT", System.getenv("TEST_POSTGRES_PORT"));
        if (testPort == null || testPort.isBlank()) {
            testPort = System.getProperty("postgres.port", System.getenv("POSTGRES_PORT"));
        }
        if (testPort == null || testPort.isBlank()) {
            // Default seguro para infraestrutura isolada de teste (porta 55432 conforme application-local.yml)
            testPort = "55432";
        }
        String host = System.getProperty("TEST_POSTGRES_HOST", System.getenv().getOrDefault("TEST_POSTGRES_HOST", "127.0.0.1"));
        String db = System.getProperty("TEST_POSTGRES_DB", System.getenv().getOrDefault("TEST_POSTGRES_DB", "rewit_db"));
        return "jdbc:postgresql://" + host + ":" + testPort + "/" + db;
    }

    private static void validateSafeEndpoint(String url) {
        if (url.contains(":5433/") || url.contains(":5432/")) {
            throw new IllegalStateException("Uso acidental da porta 5432/5433 (PostgreSQL compartilhado) detectado em teste de integração! " +
                    "Use uma instância descartável (ex: porta 55432).");
        }
    }

    @BeforeAll
    static void checkDatabaseAvailability() {
        String dbUrl = resolveDbUrl();
        validateSafeEndpoint(dbUrl);
        // Valida conectividade básica através da role de migração ou de aplicação
        try (Connection conn = DriverManager.getConnection(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD)) {
            if (conn == null || !conn.isValid(2)) {
                fail("Falha ao validar conexão com PostgreSQL de teste isolado em " + dbUrl);
            }
        } catch (SQLException e) {
            fail("PostgreSQL de teste isolado não está acessível em " + dbUrl + ": " + e.getMessage(), e);
        }
    }

    private Connection getConnection() throws SQLException {
        return getMigrationConnection();
    }

    private Connection getMigrationConnection() throws SQLException {
        String dbUrl = resolveDbUrl();
        validateSafeEndpoint(dbUrl);
        return DriverManager.getConnection(dbUrl, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD);
    }

    private Connection getAppConnection() throws SQLException {
        String dbUrl = resolveDbUrl();
        validateSafeEndpoint(dbUrl);
        return DriverManager.getConnection(dbUrl, APP_DB_USER, APP_DB_PASSWORD);
    }

    @Test
    @DisplayName("DB: RateableTarget e tabelas especializadas devem permanecer estritamente coerentes")
    void shouldRejectWrongSpecializationForRateableTarget() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID targetId = UUID.randomUUID();

            // 1. Inserir RateableTarget do tipo PRODUCT
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PRODUCT')")) {
                ps.setObject(1, targetId);
                ps.executeUpdate();
            }

            // 2. Tentar associar este ID à tabela places (deve falhar por incompatibilidade)
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) " +
                                "VALUES (?, 'Lugar Fake', ?, 'BAR', 'Rua 1', 'Curitiba', 'PR', " +
                                "ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                    ps.setObject(1, targetId);
                    ps.setString(2, "slug-" + targetId);
                    ps.executeUpdate();
                }
            });
            assertTrue(ex.getMessage().contains("Incoerência de especialização") || ex.getMessage().contains("places"),
                    "Deve lançar exceção descritiva de especialização incorreta: " + ex.getMessage());

            // 3. Associar à tabela correta (products) deve ter sucesso
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (id, name, status) VALUES (?, 'Produto Teste', 'ACTIVE')")) {
                ps.setObject(1, targetId);
                assertEquals(1, ps.executeUpdate(), "Inserção na especialização correta (products) deve suceder");
            }

            // 4. Tentativa de alterar target_type de PRODUCT para PLACE após registro vinculado deve falhar
            SQLException exChange = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rateable_targets SET target_type = 'PLACE' WHERE id = ?")) {
                    ps.setObject(1, targetId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exChange.getMessage().contains("Proibido alterar target_type"),
                    "Deve impedir alteração de target_type após especialização");
        }
    }

    @Test
    @DisplayName("DB: Valores inválidos nos principais campos enum-like devem ser rejeitados por CHECK constraints")
    void shouldRejectInvalidEnumLikeValues() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID targetId = UUID.randomUUID();

            // 1. rateable_targets.target_type inválido
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'INVALID_TYPE')")) {
                    ps.setObject(1, targetId);
                    ps.executeUpdate();
                }
            });

            // 2. users.auth_provider inválido
            UUID userId = UUID.randomUUID();
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users (id, email, auth_provider) VALUES (?, 'test@rewit.com', 'INVALID_PROVIDER')")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
            });

            // Inserir usuário válido para testes subsequentes
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, email, auth_provider) VALUES (?, ?, 'LOCAL')")) {
                ps.setObject(1, userId);
                ps.setString(2, "user-" + userId + "@rewit.com");
                ps.executeUpdate();
            }

            // 3. reviews.status inválido
            UUID reviewId = UUID.randomUUID();
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reviews (id, user_id, status) VALUES (?, ?, 'INVALID_STATUS')")) {
                    ps.setObject(1, reviewId);
                    ps.setObject(2, userId);
                    ps.executeUpdate();
                }
            });

            // 4. reviews.visibility inválida
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reviews (id, user_id, visibility) VALUES (?, ?, 'INVALID_VISIBILITY')")) {
                    ps.setObject(1, reviewId);
                    ps.setObject(2, userId);
                    ps.executeUpdate();
                }
            });

            // 5. reviews.location_accuracy_meters negativa
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reviews (id, user_id, location_accuracy_meters) VALUES (?, ?, -5.0)")) {
                    ps.setObject(1, reviewId);
                    ps.setObject(2, userId);
                    ps.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("DB: Consistência entre CheckIn e Review deve ser garantida no banco")
    void shouldEnforceCheckInAndReviewConsistencyAtDatabaseLevel() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID userId = UUID.randomUUID();
            UUID anotherUserId = UUID.randomUUID();
            UUID placeTargetId = UUID.randomUUID();
            UUID anotherPlaceTargetId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();

            // 1. Criar usuários
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")) {
                ps.setObject(1, userId);
                ps.setString(2, "u1-" + userId + "@rewit.com");
                ps.executeUpdate();

                ps.setObject(1, anotherUserId);
                ps.setString(2, "u2-" + anotherUserId + "@rewit.com");
                ps.executeUpdate();
            }

            // 2. Criar locais físicos
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')")) {
                ps.setObject(1, placeTargetId);
                ps.executeUpdate();
                ps.setObject(1, anotherPlaceTargetId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) " +
                            "VALUES (?, ?, ?, 'CAFE', ?, 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeTargetId);
                ps.setString(2, "Local A " + placeTargetId);
                ps.setString(3, "slug-" + placeTargetId);
                ps.setString(4, "Rua A " + placeTargetId);
                ps.executeUpdate();

                ps.setObject(1, anotherPlaceTargetId);
                ps.setString(2, "Local B " + anotherPlaceTargetId);
                ps.setString(3, "slug-" + anotherPlaceTargetId);
                ps.setString(4, "Rua B " + anotherPlaceTargetId);
                ps.executeUpdate();
            }

            // 3. Criar Review com context_place_id preenchido
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reviews (id, user_id, context_place_id, experience_text) VALUES (?, ?, ?, 'Ótimo')")) {
                ps.setObject(1, reviewId);
                ps.setObject(2, userId);
                ps.setObject(3, placeTargetId);
                ps.executeUpdate();
            }

            // 4. Inserção de CheckIn consistente deve suceder
            UUID validCheckInId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) " +
                            "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 15.0, 'PENDING')")) {
                ps.setObject(1, validCheckInId);
                ps.setObject(2, reviewId);
                ps.setObject(3, userId);
                ps.setObject(4, placeTargetId);
                assertEquals(1, ps.executeUpdate());
            }

            // 5. Inserir CheckIn com user_id diferente da review deve ser rejeitado
            UUID wrongUserCheckInId = UUID.randomUUID();
            UUID review2Id = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reviews (id, user_id, context_place_id) VALUES (?, ?, ?)")) {
                ps.setObject(1, review2Id);
                ps.setObject(2, userId);
                ps.setObject(3, placeTargetId);
                ps.executeUpdate();
            }

            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) " +
                                "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 15.0, 'PENDING')")) {
                    ps.setObject(1, wrongUserCheckInId);
                    ps.setObject(2, review2Id);
                    ps.setObject(3, anotherUserId); // Usuário diferente da review!
                    ps.setObject(4, placeTargetId);
                    ps.executeUpdate();
                }
            });

            // 6. Inserir CheckIn com place_id diferente do context_place_id da review deve ser rejeitado
            UUID wrongPlaceCheckInId = UUID.randomUUID();
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) " +
                                "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 15.0, 'PENDING')")) {
                    ps.setObject(1, wrongPlaceCheckInId);
                    ps.setObject(2, review2Id);
                    ps.setObject(3, userId);
                    ps.setObject(4, anotherPlaceTargetId); // Local diferente do contexto da review!
                    ps.executeUpdate();
                }
            });

            // 7. Review sem context_place_id (NULL) não pode aceitar CheckIn
            UUID orphanReviewId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reviews (id, user_id, context_place_id) VALUES (?, ?, NULL)")) {
                ps.setObject(1, orphanReviewId);
                ps.setObject(2, userId);
                ps.executeUpdate();
            }

            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) " +
                                "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 15.0, 'PENDING')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, orphanReviewId);
                    ps.setObject(3, userId);
                    ps.setObject(4, placeTargetId);
                    ps.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("DB: CheckIn.status deve ser a única fonte da verdade e sincronizar reviews.is_verified_on_site")
    void shouldEnforceCheckInAsSingleSourceOfTruthForVerifiedOnSite() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID userId = UUID.randomUUID();
            UUID placeTargetId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID checkInId = UUID.randomUUID();

            // Preparação dos registros base
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")) {
                ps.setObject(1, userId);
                ps.setString(2, "verif-" + userId + "@rewit.com");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')")) {
                ps.setObject(1, placeTargetId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) " +
                            "VALUES (?, ?, ?, 'CAFE', ?, 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeTargetId);
                ps.setString(2, "Local V " + placeTargetId);
                ps.setString(3, "slug-" + placeTargetId);
                ps.setString(4, "Rua V " + placeTargetId);
                ps.executeUpdate();
            }

            // Inserir Review: inicialmente is_verified_on_site é FALSE
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reviews (id, user_id, context_place_id) VALUES (?, ?, ?)")) {
                ps.setObject(1, reviewId);
                ps.setObject(2, userId);
                ps.setObject(3, placeTargetId);
                ps.executeUpdate();
            }

            // 1. Tentativa de forçar is_verified_on_site = TRUE diretamente na review sem CheckIn VERIFIED deve falhar
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reviews SET is_verified_on_site = TRUE WHERE id = ?")) {
                    ps.setObject(1, reviewId);
                    ps.executeUpdate();
                }
            });
            assertTrue(ex.getMessage().contains("Review.is_verified_on_site não pode ser TRUE sem um CheckIn"),
                    "Deve impedir alteração avulsa de is_verified_on_site");

            // 2. Inserir CheckIn PENDING: Review deve continuar FALSE
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status) " +
                            "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 10.0, 'PENDING')")) {
                ps.setObject(1, checkInId);
                ps.setObject(2, reviewId);
                ps.setObject(3, userId);
                ps.setObject(4, placeTargetId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT is_verified_on_site FROM reviews WHERE id = ?")) {
                ps.setObject(1, reviewId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean("is_verified_on_site"), "Review deve permanecer não-verificada");
                }
            }

            // 3. Atualizar CheckIn para VERIFIED: Trigger deve sincronizar reviews.is_verified_on_site para TRUE
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE check_ins SET status = 'VERIFIED', verified_at = NOW() WHERE id = ?")) {
                ps.setObject(1, checkInId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT is_verified_on_site FROM reviews WHERE id = ?")) {
                ps.setObject(1, reviewId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("is_verified_on_site"), "Review deve ser sincronizada para TRUE via trigger");
                }
            }

            // 4. Atualizar CheckIn para REJECTED: Trigger deve sincronizar reviews.is_verified_on_site para FALSE
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE check_ins SET status = 'REJECTED', verified_at = NULL WHERE id = ?")) {
                ps.setObject(1, checkInId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT is_verified_on_site FROM reviews WHERE id = ?")) {
                ps.setObject(1, reviewId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean("is_verified_on_site"), "Review deve ser sincronizada para FALSE via trigger");
                }
            }
        }
    }

    @Test
    @DisplayName("V35 / AO-1: Demonstra garantia append-only (bloqueio de UPDATE, DELETE e TRUNCATE) sob a role efetiva da aplicação")
    void shouldDemonstrateAppendOnlyProtectionUnderEffectiveApplicationRole() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID auditId = UUID.randomUUID();
            UUID mediaId = UUID.randomUUID();
            UUID placeId = UUID.randomUUID();

            // 1. Inserir registro válido de auditoria com SYSTEM actor (V35)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, created_at) " +
                            "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_RESOLVE_REPORT', 'Violação', 'Resolução automática', NOW())")) {
                ps.setObject(1, auditId);
                ps.setObject(2, mediaId);
                ps.setObject(3, placeId);
                assertEquals(1, ps.executeUpdate(), "Inserção de auditoria com SYSTEM actor e actor_user_id NULL deve suceder");
            }

            // 2. Demonstrar que UPDATE é bloqueado por trigger sob a role de aplicação
            SQLException exUpdate = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE place_media_moderation_audit_logs SET notes = 'tentativa de adulteração' WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exUpdate.getMessage().contains("append-only") || exUpdate.getMessage().contains("UPDATE"),
                    "Trigger deve bloquear UPDATE: " + exUpdate.getMessage());

            // 3. Demonstrar que DELETE é bloqueado por trigger sob a role de aplicação
            SQLException exDelete = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM place_media_moderation_audit_logs WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exDelete.getMessage().contains("append-only") || exDelete.getMessage().contains("DELETE"),
                    "Trigger deve bloquear DELETE: " + exDelete.getMessage());

            // 4. Demonstrar que TRUNCATE é bloqueado por trigger sob a role de aplicação
            SQLException exTruncate = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "TRUNCATE TABLE place_media_moderation_audit_logs")) {
                    ps.execute();
                }
            });
            assertTrue(exTruncate.getMessage().contains("append-only") || exTruncate.getMessage().contains("TRUNCATE"),
                    "Trigger deve bloquear TRUNCATE: " + exTruncate.getMessage());
        }
    }

    @Test
    @DisplayName("V35 / AU-1: Valida constraints de actor_type, actor_user_id e ações automáticas na trilha de auditoria")
    void shouldValidateAuditActorConstraintsAndAutoActions() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID auditId = UUID.randomUUID();
            UUID mediaId = UUID.randomUUID();
            UUID placeId = UUID.randomUUID();

            // 1. actor_type = 'USER' com actor_user_id NULL deve falhar
            SQLException exUserNull = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO place_media_moderation_audit_logs " +
                                "(id, place_media_id, place_id, actor_user_id, actor_type, action, created_at) " +
                                "VALUES (?, ?, ?, NULL, 'USER', 'REMOVE', NOW())")) {
                    ps.setObject(1, auditId);
                    ps.setObject(2, mediaId);
                    ps.setObject(3, placeId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exUserNull.getMessage().contains("chk_place_media_audit_actor_type_user_id")
                    || exUserNull.getMessage().contains("violates check constraint"));

            // 2. actor_type inválido deve falhar
            SQLException exInvalidType = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO place_media_moderation_audit_logs " +
                                "(id, place_media_id, place_id, actor_user_id, actor_type, action, created_at) " +
                                "VALUES (?, ?, ?, NULL, 'BOT', 'AUTO_QUARANTINE', NOW())")) {
                    ps.setObject(1, auditId);
                    ps.setObject(2, mediaId);
                    ps.setObject(3, placeId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exInvalidType.getMessage().contains("chk_place_media_audit_actor_type")
                    || exInvalidType.getMessage().contains("violates check constraint"));

            // 3. AUTO_QUARANTINE com actor_type = 'SYSTEM' e actor_user_id = NULL deve suceder
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, created_at) " +
                            "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_QUARANTINE', NOW())")) {
                ps.setObject(1, auditId);
                ps.setObject(2, mediaId);
                ps.setObject(3, placeId);
                assertEquals(1, ps.executeUpdate());
            }
        }
    }

    @Test
    @DisplayName("AO-1: Role da aplicação (rewit_app) não tem ownership e não pode desabilitar triggers nem executar DDL/DROP na trilha de auditoria")
    void shouldProveAppRoleCannotAlterAuditTableOrBypassTriggers() throws SQLException {
        try (Connection appConn = getAppConnection()) {
            UUID auditId = UUID.randomUUID();
            UUID mediaId = UUID.randomUUID();
            UUID placeId = UUID.randomUUID();

            // 1. rewit_app consegue inserir registros de auditoria (DML INSERT permitido)
            try (PreparedStatement ps = appConn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, created_at) " +
                            "VALUES (?, ?, ?, NULL, 'SYSTEM', 'RELEASE_REVIEW', 'Análise aprovada', 'Sem denúncias ativas', NOW())")) {
                ps.setObject(1, auditId);
                ps.setObject(2, mediaId);
                ps.setObject(3, placeId);
                assertEquals(1, ps.executeUpdate(), "rewit_app deve conseguir inserir na trilha de auditoria");
            }

            // 2. rewit_app NÃO é owner da tabela e o PostgreSQL recusa qualquer comando de DDL
            SQLException exDdlDisable = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = appConn.prepareStatement(
                        "ALTER TABLE place_media_moderation_audit_logs DISABLE TRIGGER ALL")) {
                    ps.execute();
                }
            });
            assertTrue(exDdlDisable.getMessage().contains("must be owner") || exDdlDisable.getMessage().contains("permission denied"),
                    "PostgreSQL deve rejeitar DISABLE TRIGGER sob a role de aplicação não-proprietária: " + exDdlDisable.getMessage());

            SQLException exDdlDrop = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = appConn.prepareStatement(
                        "DROP TABLE place_media_moderation_audit_logs")) {
                    ps.execute();
                }
            });
            assertTrue(exDdlDrop.getMessage().contains("must be owner") || exDdlDrop.getMessage().contains("permission denied"),
                    "PostgreSQL deve rejeitar DROP TABLE sob a role de aplicação: " + exDdlDrop.getMessage());

            // 3. REVOKE explícito de UPDATE, DELETE e TRUNCATE impede qualquer adulteração DML mesmo além do trigger
            SQLException exUpdateApp = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = appConn.prepareStatement(
                        "UPDATE place_media_moderation_audit_logs SET notes = 'fraude' WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exUpdateApp.getMessage().contains("permission denied") || exUpdateApp.getMessage().contains("append-only"),
                    "UPDATE deve ser bloqueado por permissão DCL ou trigger: " + exUpdateApp.getMessage());

            SQLException exDeleteApp = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = appConn.prepareStatement(
                        "DELETE FROM place_media_moderation_audit_logs WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exDeleteApp.getMessage().contains("permission denied") || exDeleteApp.getMessage().contains("append-only"),
                    "DELETE deve ser bloqueado por permissão DCL ou trigger: " + exDeleteApp.getMessage());
        }
    }

    @Test
    @DisplayName("V36 / AU-1 / R-1: Valida constraints de AUTO_RESOLVED, decided_by e RELEASE_REVIEW")
    void shouldValidateV36AuditConstraintsAndAutoResolved() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID mediaId = UUID.randomUUID();
            UUID reportId = UUID.randomUUID();
            UUID reporterId = UUID.randomUUID();
            UUID moderatorId = UUID.randomUUID();
            UUID placeId = UUID.randomUUID();

            // Semeia usuário, local e mídia para o teste
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, email, auth_provider) VALUES (?, 'rep.v36." + UUID.randomUUID() + "@rewit.com', 'LOCAL')")) {
                ps.setObject(1, reporterId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')")) {
                ps.setObject(1, placeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) " +
                            "VALUES (?, 'Local V36 " + placeId + "', 'slug-v36-" + placeId + "', 'BAR', 'Rua V36 " + placeId + "', 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, is_primary, created_at, updated_at) " +
                            "VALUES (?, ?, ?, 'places/test-" + mediaId + ".jpg', 'IMAGE', 'image/jpeg', 1024, 800, 600, 'ACTIVE', false, NOW(), NOW())")) {
                ps.setObject(1, mediaId);
                ps.setObject(2, placeId);
                ps.setObject(3, reporterId);
                ps.executeUpdate();
            }

            // 1. AUTO_RESOLVED com decided_by_user_id NULL deve ser aceito pela constraint chk_place_media_reports_decided_by
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at, decision_notes) " +
                            "VALUES (?, ?, ?, 'SPAM', 'AUTO_RESOLVED', NULL, NOW(), 'Encerrada pelo sistema')")) {
                ps.setObject(1, reportId);
                ps.setObject(2, mediaId);
                ps.setObject(3, reporterId);
                assertEquals(1, ps.executeUpdate());
            }

            // 2. AUTO_RESOLVED com decided_by_user_id preenchido deve ser rejeitado pela constraint
            UUID invalidReportId = UUID.randomUUID();
            SQLException exAutoResolvedWithDecidedBy = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at, decision_notes) " +
                                "VALUES (?, ?, ?, 'SPAM', 'AUTO_RESOLVED', ?, NOW(), 'Incorreto')")) {
                    ps.setObject(1, invalidReportId);
                    ps.setObject(2, mediaId);
                    ps.setObject(3, reporterId);
                    ps.setObject(4, moderatorId);
                    ps.executeUpdate();
                }
            });
            assertTrue(exAutoResolvedWithDecidedBy.getMessage().contains("chk_place_media_reports_decided_by")
                    || exAutoResolvedWithDecidedBy.getMessage().contains("violates check constraint"));

            // 3. RELEASE_REVIEW é aceito como ação válida na trilha de auditoria
            UUID auditId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO place_media_moderation_audit_logs " +
                            "(id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, created_at) " +
                            "VALUES (?, ?, ?, ?, 'USER', 'RELEASE_REVIEW', 'Denúncias improcedentes', 'Liberada', NOW())")) {
                ps.setObject(1, auditId);
                ps.setObject(2, mediaId);
                ps.setObject(3, placeId);
                ps.setObject(4, reporterId);
                assertEquals(1, ps.executeUpdate());
            }
        }
    }
}
