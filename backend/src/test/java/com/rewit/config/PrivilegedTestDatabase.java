package com.rewit.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.UUID;

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
}
