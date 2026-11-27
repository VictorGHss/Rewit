package com.rewit.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Acesso administrativo ao PostgreSQL DESCARTÁVEL de teste, com a credencial de migração
 * ({@code TEST_MIGRATION_DB_*}, validada por {@link TestInfrastructureConfig}).
 *
 * <p>Existe para fixtures e limpezas que a role de runtime corretamente não pode executar — atribuir papel
 * administrativo a um usuário (P2-2) e excluir fisicamente avaliações, comentários ou respostas (P3-1). Nunca é
 * usado pelo código sob teste, que continua conectado como {@code TEST_APP_DB_USER}. Cada operação abre e
 * fecha a própria conexão (autocommit), portanto só enxerga dados já confirmados.
 */
public final class PrivilegedTestDatabase {

    private static volatile JdbcTemplate jdbcTemplate;
    private static volatile JdbcTemplate appJdbcTemplate;
    private static volatile JdbcTemplate mediaJdbcTemplate;
    private static volatile JdbcTemplate modJdbcTemplate;

    private PrivilegedTestDatabase() {
    }

    public static JdbcTemplate jdbc() {
        JdbcTemplate current = jdbcTemplate;
        if (current == null) {
            synchronized (PrivilegedTestDatabase.class) {
                current = jdbcTemplate;
                if (current == null) {
                    TestInfrastructureConfig config = TestInfrastructureConfig.current();
                    current = new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
                            config.datasourceUrl(), config.migrationDbUser(), config.migrationDbPassword()));
                    jdbcTemplate = current;
                }
            }
        }
        return current;
    }

    /** Conexão direta de teste como {@code rewit_app} (credencial pública comprometida em cenários de ataque). */
    public static JdbcTemplate appJdbc() {
        JdbcTemplate current = appJdbcTemplate;
        if (current == null) {
            synchronized (PrivilegedTestDatabase.class) {
                current = appJdbcTemplate;
                if (current == null) {
                    TestInfrastructureConfig config = TestInfrastructureConfig.current();
                    current = new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
                            config.datasourceUrl(), config.appDbUser(), config.appDbPassword()));
                    appJdbcTemplate = current;
                }
            }
        }
        return current;
    }

    /** Conexão direta de teste como {@code rewit_media} (ciclo de vida público/automático de mídias, SEC-F2-03). */
    public static JdbcTemplate mediaJdbc() {
        JdbcTemplate current = mediaJdbcTemplate;
        if (current == null) {
            synchronized (PrivilegedTestDatabase.class) {
                current = mediaJdbcTemplate;
                if (current == null) {
                    TestInfrastructureConfig config = TestInfrastructureConfig.current();
                    current = new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
                            config.datasourceUrl(), config.mediaDbUser(), config.mediaDbPassword()));
                    mediaJdbcTemplate = current;
                }
            }
        }
        return current;
    }

    /** Conexão direta de teste como {@code rewit_mod} (credencial restrita de moderação). */
    public static JdbcTemplate modJdbc() {
        JdbcTemplate current = modJdbcTemplate;
        if (current == null) {
            synchronized (PrivilegedTestDatabase.class) {
                current = modJdbcTemplate;
                if (current == null) {
                    TestInfrastructureConfig config = TestInfrastructureConfig.current();
                    current = new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
                            config.datasourceUrl(), config.modDbUser(), config.modDbPassword()));
                    modJdbcTemplate = current;
                }
            }
        }
        return current;
    }

    /**
     * Executa {@code work} em uma única transação PostgreSQL sob a credencial administrativa (owner),
     * fazendo rollback automático em caso de exceção e mantendo locks de linha até o fim do bloco.
     */
    public static void inOwnerTransaction(Consumer<JdbcTemplate> work) {
        JdbcTemplate ownerJdbc = jdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ownerJdbc.getDataSource()));
        tx.executeWithoutResult(status -> work.accept(ownerJdbc));
    }

    /** Cria (e confirma) um usuário local com o papel informado; útil dentro de testes transacionais. */
    public static UUID createUser(String emailPrefix, String role) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO users (id, email, password_hash, auth_provider, role) VALUES (?, ?, 'hash', 'LOCAL', ?)",
                id, emailPrefix + "." + id + "@rewit.test", role);
        return id;
    }

    /** Atribui papel ao usuário já confirmado no banco (operação administrativa, fora do runtime). */
    public static void assignRole(UUID userId, String role) {
        int updated = jdbc().update("UPDATE users SET role = ? WHERE id = ?", role, userId);
        if (updated != 1) {
            throw new IllegalStateException("Usuário de teste não encontrado (já confirmado?) para atribuir " + role + ": " + userId);
        }
    }

    /** Atualiza account_status (e colunas legadas consistentes) de um usuário em fixture administrativa. */
    public static void setUserAccountStatus(UUID userId, String status) {
        boolean active = "ACTIVE".equals(status);
        java.sql.Timestamp deletedAt = "DELETED".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null;
        int updated = jdbc().update(
                "UPDATE users SET account_status = ?, is_active = ?, deleted_at = ? WHERE id = ?",
                status, active, deletedAt, userId);
        if (updated != 1) {
            throw new IllegalStateException("Usuário de teste não encontrado para status " + status + ": " + userId);
        }
    }

    /** Atualiza verification_status de uma conta comercial em fixture administrativa. */
    public static void setBusinessAccountStatus(UUID businessAccountId, String status) {
        int updated = jdbc().update(
                "UPDATE business_accounts SET verification_status = ? WHERE id = ?",
                status, businessAccountId);
        if (updated != 1) {
            throw new IllegalStateException("Conta comercial de teste não encontrada: " + businessAccountId);
        }
    }

    /** Atualiza claimed_by_business_id de um local em fixture administrativa. */
    public static void setPlaceClaimedBy(UUID placeId, UUID businessAccountId) {
        int updated = jdbc().update(
                "UPDATE places SET claimed_by_business_id = ? WHERE id = ?",
                businessAccountId, placeId);
        if (updated != 1) {
            throw new IllegalStateException("Local de teste não encontrado: " + placeId);
        }
    }

    /** Atualiza status de um local em fixture administrativa. */
    public static void setPlaceStatus(UUID placeId, String status) {
        int updated = jdbc().update("UPDATE places SET status = ? WHERE id = ?", status, placeId);
        if (updated != 1) {
            throw new IllegalStateException("Local de teste não encontrado: " + placeId);
        }
    }

    /** Atualiza status de uma avaliação em fixture administrativa. */
    public static void setReviewStatus(UUID reviewId, String status) {
        int updated = jdbc().update("UPDATE reviews SET status = ? WHERE id = ?", status, reviewId);
        if (updated != 1) {
            throw new IllegalStateException("Avaliação de teste não encontrada: " + reviewId);
        }
    }

    /** Atualiza status de um comentário de discussão em fixture administrativa. */
    public static void setDiscussionStatus(UUID discussionId, String status) {
        int updated = jdbc().update("UPDATE review_discussions SET status = ? WHERE id = ?", status, discussionId);
        if (updated != 1) {
            throw new IllegalStateException("Comentário de teste não encontrado: " + discussionId);
        }
    }
}
