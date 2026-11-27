package com.rewit.infrastructure.persistence.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verificador fail-closed da identidade PostgreSQL efetiva e dos atributos de segurança das conexões
 * reais obtidas diretamente de cada um dos três pools de runtime ({@code appDataSource},
 * {@code mediaLifecycleDataSource} e {@code moderationDataSource}).
 *
 * <p>Garantias de segurança:
 * <ul>
 *   <li>Consulta diretamente cada {@link DataSource} concreto (jamais pelo {@link ModerationRoutingDataSource}),
 *       sem abrir transações Spring, sem tocar {@link DatabaseRoleContext} e sem usar credenciais migrator.</li>
 *   <li>Exige que {@code session_user} e {@code current_user} correspondam exatamente à role esperada,
 *       com {@code NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT}, sem memberships
 *       e sem ownership do banco atual ({@code pg_database.datdba}) nem de qualquer schema ({@code pg_namespace.nspowner}).</li>
 *   <li>Executa durante a inicialização do contexto Spring (e antes do {@code Flyway} quando presente),
 *       impedindo que a aplicação aceite tráfego HTTP caso qualquer pool esteja fora do contrato.</li>
 *   <li>Nunca registra senhas, strings JDBC ou credenciais nos logs ou exceções.</li>
 * </ul>
 */
public class PostgresRuntimeRoleIdentityValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresRuntimeRoleIdentityValidator.class);

    static final String IDENTITY_VERIFICATION_SQL = """
            SELECT
                session_user::text AS session_user_name,
                current_user::text AS current_user_name,
                r.rolsuper,
                r.rolcreaterole,
                r.rolcreatedb,
                r.rolreplication,
                r.rolbypassrls,
                r.rolinherit,
                EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_auth_members am
                    WHERE am.member = r.oid
                ) AS has_memberships,
                (d.datdba = r.oid) AS owns_current_database,
                (
                    SELECT COUNT(*)
                    FROM pg_catalog.pg_namespace n
                    WHERE n.nspowner = r.oid
                ) AS owned_schema_count
            FROM pg_catalog.pg_roles r
            JOIN pg_catalog.pg_database d ON d.datname = current_database()
            WHERE r.rolname = current_user
            """;

    private final DataSource appDataSource;
    private final DataSource mediaLifecycleDataSource;
    private final DataSource moderationDataSource;

    public PostgresRuntimeRoleIdentityValidator(
            DataSource appDataSource,
            DataSource mediaLifecycleDataSource,
            DataSource moderationDataSource
    ) {
        this.appDataSource = Objects.requireNonNull(appDataSource, "appDataSource must not be null");
        this.mediaLifecycleDataSource = Objects.requireNonNull(mediaLifecycleDataSource, "mediaLifecycleDataSource must not be null");
        this.moderationDataSource = Objects.requireNonNull(moderationDataSource, "moderationDataSource must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        validateAllPools();
    }

    /**
     * Valida de forma fail-closed a identidade real e os atributos PostgreSQL de todos os três pools.
     *
     * @throws RuntimeDatabaseRoleException se qualquer pool falhar na comprovação de identidade ou privilégios mínimos
     */
    public void validateAllPools() {
        List<String> errors = new ArrayList<>();
        verifyPoolIdentity(RuntimeDatabaseRolePolicy.POOL_APP, appDataSource, RuntimeDatabaseRolePolicy.EXPECTED_APP_ROLE, errors);
        verifyPoolIdentity(RuntimeDatabaseRolePolicy.POOL_MEDIA, mediaLifecycleDataSource, RuntimeDatabaseRolePolicy.EXPECTED_MEDIA_ROLE, errors);
        verifyPoolIdentity(RuntimeDatabaseRolePolicy.POOL_MOD, moderationDataSource, RuntimeDatabaseRolePolicy.EXPECTED_MOD_ROLE, errors);

        if (!errors.isEmpty()) {
            throw new RuntimeDatabaseRoleException(
                    "Falha na verificação fail-closed de identidade real das roles PostgreSQL de runtime: "
                            + String.join("; ", errors),
                    errors
            );
        }

        log.info("Identidade real PostgreSQL validada para os três pools de runtime (app={}, media={}, mod={})",
                RuntimeDatabaseRolePolicy.EXPECTED_APP_ROLE,
                RuntimeDatabaseRolePolicy.EXPECTED_MEDIA_ROLE,
                RuntimeDatabaseRolePolicy.EXPECTED_MOD_ROLE);
    }

    /**
     * Executa a consulta de comprovação de identidade diretamente em um {@link DataSource} concreto.
     */
    public static void verifyPoolIdentity(String poolName, DataSource dataSource, String expectedRole, List<String> errors) {
        if (dataSource == null) {
            errors.add(poolName + ": DataSource ausente (esperado=" + expectedRole + ").");
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IDENTITY_VERIFICATION_SQL);
             ResultSet rs = statement.executeQuery()) {

            if (!rs.next()) {
                errors.add(poolName + ": não foi possível comprovar a identidade PostgreSQL (nenhuma linha retornada em pg_roles; esperado="
                        + expectedRole + ").");
                return;
            }

            String sessionUser = rs.getString("session_user_name");
            String currentUser = rs.getString("current_user_name");
            boolean rolSuper = rs.getBoolean("rolsuper");
            boolean rolCreateRole = rs.getBoolean("rolcreaterole");
            boolean rolCreateDb = rs.getBoolean("rolcreatedb");
            boolean rolReplication = rs.getBoolean("rolreplication");
            boolean rolBypassRls = rs.getBoolean("rolbypassrls");
            boolean rolInherit = rs.getBoolean("rolinherit");
            boolean hasMemberships = rs.getBoolean("has_memberships");
            Boolean ownsCurrentDatabase = rs.getObject("owns_current_database", Boolean.class);
            Long ownedSchemaCount = rs.getObject("owned_schema_count", Long.class);

            if (rs.next()) {
                errors.add(poolName + ": resposta ambígua em pg_roles/pg_database ao comprovar identidade (esperado=" + expectedRole + ").");
                return;
            }

            if (ownsCurrentDatabase == null || ownedSchemaCount == null || ownedSchemaCount < 0) {
                errors.add(poolName + ": não foi possível comprovar ausência de ownership de banco/schemas no PostgreSQL (esperado="
                        + expectedRole + ").");
                return;
            }

            if (!expectedRole.equals(sessionUser)) {
                errors.add(poolName + ": session_user efetivo incorreto (esperado='" + expectedRole
                        + "', observado='" + sanitizeRoleName(sessionUser) + "').");
            }
            if (!expectedRole.equals(currentUser)) {
                errors.add(poolName + ": current_user efetivo incorreto (esperado='" + expectedRole
                        + "', observado='" + sanitizeRoleName(currentUser) + "').");
            }

            List<String> forbiddenAttributes = new ArrayList<>();
            if (rolSuper) {
                forbiddenAttributes.add("SUPERUSER");
            }
            if (rolCreateRole) {
                forbiddenAttributes.add("CREATEROLE");
            }
            if (rolCreateDb) {
                forbiddenAttributes.add("CREATEDB");
            }
            if (rolReplication) {
                forbiddenAttributes.add("REPLICATION");
            }
            if (rolBypassRls) {
                forbiddenAttributes.add("BYPASSRLS");
            }
            if (rolInherit) {
                forbiddenAttributes.add("INHERIT (exige NOINHERIT)");
            }
            if (hasMemberships) {
                forbiddenAttributes.add("MEMBERSHIPS");
            }
            if (Boolean.TRUE.equals(ownsCurrentDatabase)) {
                forbiddenAttributes.add("DATABASE_OWNER");
            }
            if (ownedSchemaCount > 0) {
                forbiddenAttributes.add("SCHEMA_OWNER (" + ownedSchemaCount + " schema(s))");
            }

            if (!forbiddenAttributes.isEmpty()) {
                errors.add(poolName + " (role='" + sanitizeRoleName(currentUser)
                        + "'): atributos, memberships ou ownership incompatíveis com o contrato de privilégio mínimo: "
                        + String.join(", ", forbiddenAttributes) + ".");
            }
        } catch (SQLException e) {
            errors.add(poolName + ": falha ao comprovar identidade PostgreSQL real (esperado='" + expectedRole
                    + "', erro=" + e.getClass().getSimpleName() + ", sqlState=" + e.getSQLState() + ").");
        } catch (RuntimeException e) {
            errors.add(poolName + ": falha inesperada ao comprovar identidade PostgreSQL real (esperado='" + expectedRole
                    + "', erro=" + e.getClass().getSimpleName() + ").");
        }
    }

    private static String sanitizeRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "<indisponível>";
        }
        return roleName.trim();
    }
}
