package com.rewit.infrastructure.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrato entre o schema Flyway e o mapeamento JPA (Step 29.2).
 *
 * <p>O perfil de testes não valida o schema; a aplicação real usa {@code ddl-auto=validate} com o dialeto PostGIS
 * de {@code application-local.yml}. Este teste sobe o contexto com essas mesmas propriedades contra o PostgreSQL
 * migrado pelo Flyway: qualquer divergência de tipo entre entidade e coluna impede o contexto de iniciar.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.spatial.dialect.postgis.PostgisPG95Dialect"
})
@ActiveProfiles("local")
@DisplayName("Testes de Integração: mapeamento JPA validado contra o schema Flyway (Step 29.2)")
class JpaSchemaValidationIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("O contexto sobe com ddl-auto=validate: todas as entidades são compatíveis com as colunas reais")
    void entitiesMatchFlywaySchema() {
        assertEquals("validate", entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"));
        assertFalse(entityManagerFactory.getMetamodel().getEntities().isEmpty());
    }
}
