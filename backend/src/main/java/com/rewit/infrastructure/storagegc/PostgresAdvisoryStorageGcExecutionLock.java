package com.rewit.infrastructure.storagegc;

import com.rewit.application.port.StorageGcExecutionLock;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lock global do ciclo de GC via advisory lock de sessão do PostgreSQL (Step 28.5).
 *
 * <p>O ciclo é composto por várias transações e chamadas externas, então o lock é de sessão: uma conexão
 * dedicada o mantém do início ao fim do ciclo. {@code pg_try_advisory_lock} não espera: se outra instância
 * detém o lock, o ciclo é ignorado. A chave é exclusiva do GC e não se relaciona a nenhuma linha de domínio,
 * então uploads e operações de usuário nunca aguardam este lock. Se o processo morrer, o PostgreSQL encerra
 * a sessão e libera o lock.
 */
public class PostgresAdvisoryStorageGcExecutionLock implements StorageGcExecutionLock {

    /** Chave do advisory lock do GC de storage ("RWTSTGC1" em ASCII). */
    static final long LOCK_KEY = 0x5257_5453_5447_4331L;

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryStorageGcExecutionLock.class);

    private final DataSource dataSource;

    public PostgresAdvisoryStorageGcExecutionLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public <T> Optional<T> runExclusively(Supplier<T> cycle) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        Connection connection = acquireConnection();
        try {
            if (!queryBoolean(connection, "SELECT pg_try_advisory_lock(?)")) {
                return Optional.empty();
            }
            try {
                return Optional.of(cycle.get());
            } finally {
                release(connection);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Falha ao adquirir o lock do storage GC", e);
        } finally {
            closeQuietly(connection);
        }
    }

    private void release(Connection connection) {
        boolean released;
        try {
            released = queryBoolean(connection, "SELECT pg_advisory_unlock(?)");
        } catch (SQLException e) {
            released = false;
        }
        if (!released) {
            // Uma conexão que pode ainda deter o lock não volta ao pool: a sessão encerrada libera o lock
            log.error("Storage GC: falha ao liberar o advisory lock; a conexão será descartada");
            evict(connection);
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private Connection acquireConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Falha ao obter conexão para o lock do storage GC", e);
        }
    }

    private void evict(Connection connection) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(connection);
            return;
        }
        try {
            connection.abort(command -> command.run());
        } catch (SQLException e) {
            log.error("Storage GC: falha ao descartar a conexão do lock (erro={})", e.getClass().getSimpleName());
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("Storage GC: falha ao devolver a conexão do lock (erro={})", e.getClass().getSimpleName());
        }
    }
}
