package com.rewit.infrastructure.persistence;

import org.junit.jupiter.api.Assumptions;
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

    private static final String DB_PORT = System.getenv("POSTGRES_PORT") != null ? System.getenv("POSTGRES_PORT") : "5433";
    private static final String DB_URL = "jdbc:postgresql://localhost:" + DB_PORT + "/rewit_db";
    private static final String DB_USER = "rewit_user";
    private static final String DB_PASSWORD = "rewit_local_password";
    private static boolean databaseAvailable = false;

    @BeforeAll
    static void checkDatabaseAvailability() {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            databaseAvailable = conn != null && conn.isValid(2);
        } catch (SQLException e) {
            databaseAvailable = false;
        }
    }

    private Connection getConnection() throws SQLException {
        Assumptions.assumeTrue(databaseAvailable, "PostgreSQL 18 local não está acessível no momento");
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
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
                            "VALUES (?, 'Local A', ?, 'CAFE', 'Rua A', 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeTargetId);
                ps.setString(2, "slug-" + placeTargetId);
                ps.executeUpdate();

                ps.setObject(1, anotherPlaceTargetId);
                ps.setString(2, "slug-" + anotherPlaceTargetId);
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
                            "VALUES (?, 'Local V', ?, 'CAFE', 'Rua V', 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))")) {
                ps.setObject(1, placeTargetId);
                ps.setString(2, "slug-" + placeTargetId);
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
}
