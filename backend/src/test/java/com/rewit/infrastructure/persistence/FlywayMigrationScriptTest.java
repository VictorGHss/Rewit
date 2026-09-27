package com.rewit.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Validação do Script de Migração Flyway (V1)")
class FlywayMigrationScriptTest {

    @Test
    @DisplayName("V1__initial_schema.sql deve existir e conter extensões e estruturas obrigatórias")
    void shouldValidateMigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql");
        assertNotNull(is, "O script de migração Flyway V1__initial_schema.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Extensões
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"postgis\""), "Deve ativar a extensão PostGIS");
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\""), "Deve ativar a extensão uuid-ossp");
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"pg_trgm\""), "Deve ativar a extensão pg_trgm");

        // Rateable Targets (ADR-009)
        assertTrue(sql.contains("CREATE TABLE rateable_targets"), "Deve criar a tabela raiz rateable_targets");
        assertTrue(sql.contains("REFERENCES rateable_targets(id)"), "Entidades filhas devem referenciar rateable_targets(id)");

        // Índices Espaciais GiST
        assertTrue(sql.contains("idx_places_coordinates ON places USING GIST (coordinates)"), "Deve criar índice GiST para places");
        assertTrue(sql.contains("idx_reviews_coordinates ON reviews USING GIST (user_coordinates)"), "Deve criar índice GiST para reviews");
        assertTrue(sql.contains("idx_checkins_coordinates ON check_ins USING GIST (coordinates)"), "Deve criar índice GiST para check_ins");

        // Constraints de Domínio
        assertTrue(sql.contains("CHECK (rating >= 1.0 AND rating <= 5.0)"), "Deve conter constraint de nota entre 1.0 e 5.0");
        assertTrue(sql.contains("CONSTRAINT chk_no_self_follow CHECK (follower_user_id <> followed_user_id)"), "Deve impedir que usuário siga a si mesmo");
        assertTrue(sql.contains("CONSTRAINT uq_review_target UNIQUE (review_id, target_id)"), "Deve conter constraint de alvo único por review");
        assertTrue(sql.contains("CONSTRAINT uq_checkin_review UNIQUE (review_id)"), "Deve garantir 1 check-in por review");
    }
}
