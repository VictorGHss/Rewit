package com.rewit.infrastructure.persistence.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários controlados do ciclo de vida, descarte fail-closed e isolamento de wrapper
 * de {@link ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource} (F2-DEPLOY-03.5.2A-R1).
 */
@DisplayName("F2-DEPLOY-03.5.2A-R1: descarte seguro (evict/abort) e bloqueio de retorno contaminado no ReadOnlyEnforcingDataSource")
class ReadOnlyEnforcingDataSourceUnitTest {

    @Test
    @DisplayName("Caminho normal com autoCommit=false faz rollback, restaura READ WRITE + autoCommit=true, confirma 'off' e devolve via close()")
    void normalCloseRollsBackRestoresReadWriteVerifiesAndClosesNormally() throws SQLException {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        Connection roConn = ds.getConnection();
        assertTrue(ctrl.readOnly.get());
        assertEquals("on", ctrl.sessionReadOnly.get());

        // Simula transação aberta durante uso
        roConn.setAutoCommit(false);
        assertFalse(ctrl.autoCommit.get());

        roConn.close();
        // Idempotência: segundo close() é no-op
        roConn.close();

        assertEquals(1, ctrl.rollbackCalls.get(), "Deve dar rollback antes de devolver ao pool");
        assertTrue(ctrl.autoCommit.get(), "Deve restaurar autoCommit original");
        assertEquals("off", ctrl.sessionReadOnly.get(), "Deve restaurar READ WRITE");
        assertFalse(ctrl.readOnly.get(), "Deve restaurar setReadOnly(false)");
        assertEquals(1, ctrl.closeCalls.get(), "No caminho limpo deve chamar delegate.close() exatamente 1 vez");
        assertEquals(0, ctrl.abortCalls.get(), "No caminho limpo não deve abortar a conexão");
    }

    @Test
    @DisplayName("Falha em SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY aborta conexão e nunca chama delegate.close()")
    void setupSetReadOnlySqlFailureAbortsAndNeverCallsClose() {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ctrl.failOnSetReadOnlySql = new SQLException("Falha simulada em SET READ ONLY", "08006");
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        SQLException ex = assertThrows(SQLException.class, ds::getConnection);
        assertEquals("Falha simulada em SET READ ONLY", ex.getMessage());
        assertEquals(0, ctrl.closeCalls.get(), "Falha no setup nunca pode devolver conexão via delegate.close()");
        assertEquals(1, ctrl.abortCalls.get(), "Falha no setup deve descartar conexão via abort()");
    }

    @Test
    @DisplayName("Falha na confirmação de default_transaction_read_only=on no setup aborta conexão e nunca chama close()")
    void setupUnconfirmedReadOnlyAbortsAndNeverCallsClose() {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ctrl.overrideShowReadOnly = "off";
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        SQLException ex = assertThrows(SQLException.class, ds::getConnection);
        assertEquals("25006", ex.getSQLState());
        assertEquals(0, ctrl.closeCalls.get());
        assertEquals(1, ctrl.abortCalls.get());
    }

    @Test
    @DisplayName("Falha em SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE aborta conexão, nunca chama close() e propaga falha")
    void restoreSetReadWriteSqlFailureAbortsNeverCallsCloseAndPropagates() throws SQLException {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        Connection roConn = ds.getConnection();
        ctrl.failOnSetReadWriteSql = new SQLException("Falha simulada em SET READ WRITE", "08006");

        SQLException ex = assertThrows(SQLException.class, roConn::close);
        assertEquals("Falha simulada em SET READ WRITE", ex.getMessage());
        assertEquals(0, ctrl.closeCalls.get(), "Falha na restauração nunca pode chamar delegate.close()");
        assertEquals(1, ctrl.abortCalls.get(), "Falha na restauração deve descartar conexão via abort()");
    }

    @Test
    @DisplayName("Falha em Connection.setReadOnly(false) na restauração aborta conexão, nunca chama close() e propaga falha")
    void restoreSetReadOnlyFalseFailureAbortsNeverCallsCloseAndPropagates() throws SQLException {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        Connection roConn = ds.getConnection();
        ctrl.failOnSetReadOnlyFalse = new SQLException("Falha simulada em setReadOnly(false)", "08006");

        SQLException ex = assertThrows(SQLException.class, roConn::close);
        assertEquals("Falha simulada em setReadOnly(false)", ex.getMessage());
        assertEquals(0, ctrl.closeCalls.get(), "Falha em setReadOnly(false) nunca pode chamar delegate.close()");
        assertEquals(1, ctrl.abortCalls.get());
    }

    @Test
    @DisplayName("Falha na confirmação de default_transaction_read_only=off na restauração aborta conexão e nunca chama close()")
    void restoreUnconfirmedReadWriteAbortsAndNeverCallsClose() throws SQLException {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        Connection roConn = ds.getConnection();
        ctrl.overrideShowReadOnly = "on";

        SQLException ex = assertThrows(SQLException.class, roConn::close);
        assertEquals("25006", ex.getSQLState());
        assertEquals(0, ctrl.closeCalls.get());
        assertEquals(1, ctrl.abortCalls.get());
    }

    @Test
    @DisplayName("Falha simultânea na restauração e em abort() preserva exceção original com erro de descarte como suppressed")
    void restoreAndAbortFailurePreservesOriginalExceptionWithSuppressedDiscardError() throws SQLException {
        FakeConnectionControl ctrl = new FakeConnectionControl();
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource ds =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(ctrl.asDataSource());

        Connection roConn = ds.getConnection();
        ctrl.failOnSetReadWriteSql = new SQLException("Erro primario de restauracao", "08006");
        ctrl.failOnAbort = new SQLException("Erro secundario em abort()", "08006");

        SQLException ex = assertThrows(SQLException.class, roConn::close);
        assertEquals("Erro primario de restauracao", ex.getMessage());
        assertEquals(1, ex.getSuppressed().length);
        assertEquals("Erro secundario em abort()", ex.getSuppressed()[0].getMessage());
        assertEquals(0, ctrl.closeCalls.get());
        assertEquals(1, ctrl.abortCalls.get());
    }

    private static final class FakeConnectionControl {
        final AtomicBoolean readOnly = new AtomicBoolean(false);
        final AtomicBoolean autoCommit = new AtomicBoolean(true);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicReference<String> sessionReadOnly = new AtomicReference<>("off");
        final AtomicInteger closeCalls = new AtomicInteger(0);
        final AtomicInteger abortCalls = new AtomicInteger(0);
        final AtomicInteger rollbackCalls = new AtomicInteger(0);

        SQLException failOnSetReadOnlySql;
        SQLException failOnSetReadWriteSql;
        SQLException failOnSetReadOnlyFalse;
        SQLException failOnAbort;
        String overrideShowReadOnly;

        DataSource asDataSource() {
            Connection fakeConn = createFakeConnection();
            return new DataSource() {
                @Override
                public Connection getConnection() {
                    return fakeConn;
                }

                @Override
                public Connection getConnection(String username, String password) {
                    return fakeConn;
                }

                @Override
                public PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public Logger getParentLogger() {
                    return Logger.getGlobal();
                }

                @Override
                public <T> T unwrap(Class<T> iface) throws SQLException {
                    throw new SQLException("Not a wrapper");
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };
        }

        private Connection createFakeConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isReadOnly":
                                return readOnly.get();
                            case "setReadOnly":
                                boolean targetRo = (Boolean) args[0];
                                if (!targetRo && failOnSetReadOnlyFalse != null) {
                                    throw failOnSetReadOnlyFalse;
                                }
                                readOnly.set(targetRo);
                                return null;
                            case "getAutoCommit":
                                return autoCommit.get();
                            case "setAutoCommit":
                                autoCommit.set((Boolean) args[0]);
                                return null;
                            case "rollback":
                                rollbackCalls.incrementAndGet();
                                return null;
                            case "isClosed":
                                return closed.get();
                            case "close":
                                closeCalls.incrementAndGet();
                                closed.set(true);
                                return null;
                            case "abort":
                                abortCalls.incrementAndGet();
                                if (failOnAbort != null) {
                                    throw failOnAbort;
                                }
                                if (args != null && args.length == 1 && args[0] instanceof Executor exec) {
                                    exec.execute(() -> closed.set(true));
                                } else {
                                    closed.set(true);
                                }
                                return null;
                            case "createStatement":
                                return createFakeStatement();
                            default:
                                throw new UnsupportedOperationException("Método não simulado: " + method.getName());
                        }
                    }
            );
        }

        private Statement createFakeStatement() {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "execute":
                                String sql = String.valueOf(args[0]);
                                if (sql.contains("READ ONLY")) {
                                    if (failOnSetReadOnlySql != null) {
                                        throw failOnSetReadOnlySql;
                                    }
                                    sessionReadOnly.set("on");
                                } else if (sql.contains("READ WRITE")) {
                                    if (failOnSetReadWriteSql != null) {
                                        throw failOnSetReadWriteSql;
                                    }
                                    sessionReadOnly.set("off");
                                }
                                return false;
                            case "executeQuery":
                                String q = String.valueOf(args[0]);
                                if (q.contains("SHOW default_transaction_read_only")) {
                                    String val = overrideShowReadOnly != null
                                            ? overrideShowReadOnly
                                            : sessionReadOnly.get();
                                    return createSingleStringResultSet(val);
                                }
                                throw new UnsupportedOperationException("Query não simulada: " + q);
                            case "close":
                                return null;
                            default:
                                throw new UnsupportedOperationException("Statement método não simulado: " + method.getName());
                        }
                    }
            );
        }

        private static ResultSet createSingleStringResultSet(String value) {
            AtomicBoolean nextCalled = new AtomicBoolean(false);
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next":
                                return nextCalled.compareAndSet(false, true);
                            case "getString":
                                return value;
                            case "close":
                                return null;
                            default:
                                throw new UnsupportedOperationException("ResultSet método não simulado: " + method.getName());
                        }
                    }
            );
        }
    }
}
