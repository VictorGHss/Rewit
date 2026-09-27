package com.rewit.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Validação dos Scripts de Migração Flyway (V1 e V2)")
class FlywayMigrationScriptTest {

    @Test
    @DisplayName("V1__initial_schema.sql deve existir e conter extensões, tabelas, constraints e índices obrigatórios")
    void shouldValidateV1MigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql");
        assertNotNull(is, "O script de migração Flyway V1__initial_schema.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Extensões
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"postgis\""), "Deve ativar a extensão PostGIS");
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\""), "Deve ativar a extensão uuid-ossp");
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS \"pg_trgm\""), "Deve ativar a extensão pg_trgm");

        // 2. Rateable Targets (ADR-009)
        assertTrue(sql.contains("CREATE TABLE rateable_targets"), "Deve criar a tabela raiz rateable_targets");
        assertTrue(sql.contains("REFERENCES rateable_targets(id)"), "Entidades filhas devem referenciar rateable_targets(id)");

        // 3. Índices Espaciais GiST
        assertTrue(sql.contains("idx_places_coordinates ON places USING GIST (coordinates)"), "Deve criar índice GiST para places");
        assertTrue(sql.contains("idx_reviews_coordinates ON reviews USING GIST (user_coordinates)"), "Deve criar índice GiST para reviews");
        assertTrue(sql.contains("idx_checkins_coordinates ON check_ins USING GIST (coordinates)"), "Deve criar índice GiST para check_ins");

        // 4. Índices Trigram (GIN)
        assertTrue(sql.contains("idx_places_name_trgm ON places USING GIN (name gin_trgm_ops)"), "Deve conter índice GIN/trigram para busca de locais");
        assertTrue(sql.contains("idx_products_name_trgm ON products USING GIN (name gin_trgm_ops)"), "Deve conter índice GIN/trigram para busca de produtos");

        // 5. Constraints de Domínio e Integridade Referencial
        assertTrue(sql.contains("CHECK (rating >= 1.0 AND rating <= 5.0)"), "Deve conter constraint de nota entre 1.0 e 5.0");
        assertTrue(sql.contains("CONSTRAINT chk_no_self_follow CHECK (follower_user_id <> followed_user_id)"), "Deve impedir que usuário siga a si mesmo");
        assertTrue(sql.contains("CONSTRAINT uq_review_target UNIQUE (review_id, target_id)"), "Deve conter constraint de alvo único por review");
        assertTrue(sql.contains("CONSTRAINT uq_checkin_review UNIQUE (review_id)"), "Deve garantir 1 check-in por review");
        assertTrue(sql.contains("CONSTRAINT uq_product_identifier UNIQUE (identifier_type, identifier_value)"), "Deve impedir duplicação de códigos GTIN/EAN");
        assertTrue(sql.contains("CONSTRAINT uq_place_ext_ref UNIQUE (provider, external_id)"), "Deve impedir referências externas duplicadas");
        assertTrue(sql.contains("CONSTRAINT chk_event_dates CHECK (end_at >= start_at)"), "Eventos devem exigir término >= início");
        assertTrue(sql.contains("CONSTRAINT chk_promotion_dates CHECK (end_at >= start_at)"), "Promoções devem exigir término >= início");

        // 6. Tabela de Estatísticas Derivadas (Seção 7 e 35)
        assertTrue(sql.contains("CREATE TABLE rateable_target_stats"), "Deve conter tabela rateable_target_stats");
    }

    @Test
    @DisplayName("V2__domain_consolidation.sql deve existir e consolidar atributos refinados do domínio")
    void shouldValidateV2MigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V2__domain_consolidation.sql");
        assertNotNull(is, "O script de migração Flyway V2__domain_consolidation.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Soft delete em usuários
        assertTrue(sql.contains("ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE"),
                "V2 deve adicionar deleted_at em users");

        // Endereço granular em places
        assertTrue(sql.contains("ALTER TABLE places ADD COLUMN IF NOT EXISTS street_number VARCHAR(32)"),
                "V2 deve adicionar street_number em places");
        assertTrue(sql.contains("ALTER TABLE places ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(128)"),
                "V2 deve adicionar neighborhood em places");
        assertTrue(sql.contains("idx_places_city_neighborhood ON places (city, neighborhood)"),
                "V2 deve criar índice composto de cidade e bairro");

        // Método de verificação em check_ins
        assertTrue(sql.contains("ALTER TABLE check_ins ADD COLUMN IF NOT EXISTS verification_method VARCHAR(32)"),
                "V2 deve adicionar verification_method em check_ins");
        assertTrue(sql.contains("chk_checkin_verification_method"),
                "V2 deve conter constraint de métodos válidos de check-in");

        // Peso / preferência em user_interests
        assertTrue(sql.contains("ALTER TABLE user_interests ADD COLUMN IF NOT EXISTS weight NUMERIC(3, 2)"),
                "V2 deve adicionar weight em user_interests");
        assertTrue(sql.contains("chk_user_interest_weight"),
                "V2 deve conter constraint de peso entre 0.00 e 1.00");

        // Metadados em notificações
        assertTrue(sql.contains("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS metadata_json JSONB"),
                "V2 deve adicionar metadata_json em notificações");

        // Índice de ordenação para estatísticas
        assertTrue(sql.contains("idx_rateable_target_stats_rating ON rateable_target_stats (average_rating DESC)"),
                "V2 deve criar índice para ordenação rápida por média");
    }

    @Test
    @DisplayName("V3__domain_integrity_refinement.sql deve existir e conter refinamentos de integridade e consistência")
    void shouldValidateV3MigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V3__domain_integrity_refinement.sql");
        assertNotNull(is, "O script de migração Flyway V3__domain_integrity_refinement.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Check-In: PENDING por padrão e regras de verified_at
        assertTrue(sql.contains("ALTER TABLE check_ins ALTER COLUMN status SET DEFAULT 'PENDING'"),
                "V3 deve configurar status default de check_ins como PENDING");
        assertTrue(sql.contains("ALTER TABLE check_ins ALTER COLUMN verified_at DROP NOT NULL"),
                "V3 deve remover obrigatoriedade NOT NULL de verified_at");
        assertTrue(sql.contains("chk_checkin_status"),
                "V3 deve conter constraint chk_checkin_status");
        assertTrue(sql.contains("chk_checkin_verified_at_consistency"),
                "V3 deve conter constraint de consistência entre status e verified_at");

        // 2. Consistência CheckIn <-> Review
        assertTrue(sql.contains("uq_reviews_id_user_context_place"),
                "V3 deve adicionar constraint única composta uq_reviews_id_user_context_place");
        assertTrue(sql.contains("fk_check_ins_review_user_place"),
                "V3 deve adicionar foreign key composta fk_check_ins_review_user_place");
        assertTrue(sql.contains("trg_check_in_review_consistency"),
                "V3 deve conter trigger trg_check_in_review_consistency");

        // 3. Fonte da verdade para verified on site
        assertTrue(sql.contains("trg_sync_check_in_to_review_verified"),
                "V3 deve conter trigger trg_sync_check_in_to_review_verified");
        assertTrue(sql.contains("trg_prevent_unverified_review_flag"),
                "V3 deve conter trigger trg_prevent_unverified_review_flag");

        // 4. Integridade de RateableTarget e especializações
        assertTrue(sql.contains("chk_rateable_target_type"),
                "V3 deve conter constraint chk_rateable_target_type");
        assertTrue(sql.contains("fn_validate_rateable_target_specialization"),
                "V3 deve conter função de validação de especialização");
        assertTrue(sql.contains("trg_prevent_rateable_target_type_change"),
                "V3 deve impedir alteração de target_type após especialização");

        // 5. CHECK constraints dos principais campos enum-like
        assertTrue(sql.contains("chk_review_status"), "V3 deve conter chk_review_status");
        assertTrue(sql.contains("chk_review_visibility"), "V3 deve conter chk_review_visibility");
        assertTrue(sql.contains("chk_review_location_accuracy"), "V3 deve conter chk_review_location_accuracy");
        assertTrue(sql.contains("chk_places_status"), "V3 deve conter chk_places_status");
        assertTrue(sql.contains("chk_products_status"), "V3 deve conter chk_products_status");
        assertTrue(sql.contains("chk_services_status"), "V3 deve conter chk_services_status");
        assertTrue(sql.contains("chk_events_status"), "V3 deve conter chk_events_status");
        assertTrue(sql.contains("chk_users_auth_provider"), "V3 deve conter chk_users_auth_provider");
        assertTrue(sql.contains("chk_business_verification_status"), "V3 deve conter chk_business_verification_status");
        assertTrue(sql.contains("chk_business_plan_tier"), "V3 deve conter chk_business_plan_tier");
        assertTrue(sql.contains("chk_review_tags_source"), "V3 deve conter chk_review_tags_source");
    }

    @Test
    @DisplayName("V4__identity_integrity.sql deve existir e conter unicidade case-insensitive e de provedor")
    void shouldValidateV4MigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V4__identity_integrity.sql");
        assertNotNull(is, "O script de migração Flyway V4__identity_integrity.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Unicidade de e-mail case-insensitive
        assertTrue(sql.contains("uq_users_email_lower"), "V4 deve conter índice único uq_users_email_lower");
        assertTrue(sql.contains("LOWER(email)"), "V4 deve indexar LOWER(email)");

        // 2. Unicidade de provedor federado
        assertTrue(sql.contains("uq_users_provider_user_id"), "V4 deve conter índice único uq_users_provider_user_id");
        assertTrue(sql.contains("WHERE provider_user_id IS NOT NULL"), "V4 deve ser índice parcial para identidades federadas");

        // 3. Unicidade de handle case-insensitive
        assertTrue(sql.contains("uq_profiles_handle_lower"), "V4 deve conter índice único uq_profiles_handle_lower");
        assertTrue(sql.contains("LOWER(handle)"), "V4 deve indexar LOWER(handle)");
    }

    @Test
    @DisplayName("V5__authentication_sessions.sql deve existir e conter estrutura de sessões e refresh token")
    void shouldValidateV5MigrationScriptContents() throws Exception {
        InputStream is = getClass().getResourceAsStream("/db/migration/V5__authentication_sessions.sql");
        assertNotNull(is, "O script de migração Flyway V5__authentication_sessions.sql deve estar presente no classpath");

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS auth_sessions"), "V5 deve criar a tabela auth_sessions");
        assertTrue(sql.contains("token_hash"), "V5 deve conter coluna token_hash");
        assertTrue(sql.contains("uq_auth_sessions_token_hash"), "V5 deve conter constraint de unicidade para token_hash");
        assertTrue(sql.contains("idx_auth_sessions_user_id"), "V5 deve conter índice para user_id");
        assertTrue(sql.contains("idx_auth_sessions_expires_at"), "V5 deve conter índice para expires_at");
        assertTrue(sql.contains("idx_auth_sessions_revoked_at"), "V5 deve conter índice para revoked_at");
    }

    @Test
    @DisplayName("Todas as migrações Flyway devem existir e manter sequência ordenada (V1, V2, V3, V4, V5)")
    void shouldEnsureMigrationsAreOrderedAndConsecutive() {
        assertNotNull(getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql"),
                "V1 deve existir no classpath");
        assertNotNull(getClass().getResourceAsStream("/db/migration/V2__domain_consolidation.sql"),
                "V2 deve existir no classpath");
        assertNotNull(getClass().getResourceAsStream("/db/migration/V3__domain_integrity_refinement.sql"),
                "V3 deve existir no classpath");
        assertNotNull(getClass().getResourceAsStream("/db/migration/V4__identity_integrity.sql"),
                "V4 deve existir no classpath");
        assertNotNull(getClass().getResourceAsStream("/db/migration/V5__authentication_sessions.sql"),
                "V5 deve existir no classpath");
    }
}
