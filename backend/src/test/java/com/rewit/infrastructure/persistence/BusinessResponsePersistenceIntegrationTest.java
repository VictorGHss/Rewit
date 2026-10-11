package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseModerationAuditLogRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Persistência das respostas oficiais de empresas no PostgreSQL (C11, V24)")
class BusinessResponsePersistenceIntegrationTest {

    @Autowired
    private BusinessResponseRepository responseRepository;

    @Autowired
    private BusinessResponseModerationAuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Precisão do TIMESTAMPTZ: comparar instantes depois de ida e volta ao banco. */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private record Fixture(UUID reviewer, UUID businessAdmin, UUID place, UUID business, UUID review, UUID rootDiscussion) {
    }

    @Test
    @Transactional
    @DisplayName("Resposta à avaliação: grava, relê fiel e aparece só entre as respostas ativas diretas da avaliação")
    void persistsReviewResponse() {
        Fixture f = fixture();
        BusinessResponse saved = responseRepository.save(publish(f, null, "Obrigado pela visita!"));

        BusinessResponse found = responseRepository.findById(saved.getId()).orElseThrow();
        assertEquals(f.review(), found.getReviewId());
        assertNull(found.getDiscussionId());
        assertEquals(f.business(), found.getBusinessAccountId());
        assertEquals(f.place(), found.getPlaceId());
        assertEquals(f.businessAdmin(), found.getAuthorUserId());
        assertEquals("Obrigado pela visita!", found.getContent());
        assertEquals(BusinessResponseStatus.ACTIVE, found.getStatus());
        assertNull(found.getRemovedBy());
        assertNull(found.getRemovedAt());
        assertEquals(now, found.getCreatedAt());

        assertEquals(saved.getId(), responseRepository.findActive(f.review(), null, f.business()).orElseThrow().getId());
        assertTrue(responseRepository.findActive(f.review(), f.rootDiscussion(), f.business()).isEmpty());

        PageResult<BusinessResponse> page = responseRepository.findActiveReviewResponses(f.review(), 0, 10);
        assertEquals(1, page.totalElements());
        assertEquals(saved.getId(), page.content().get(0).getId());
    }

    @Test
    @Transactional
    @DisplayName("Resposta a um comentário da mesma avaliação é aceita e fica fora da lista direta da avaliação")
    void persistsDiscussionResponse() {
        Fixture f = fixture();
        BusinessResponse saved = responseRepository.save(publish(f, f.rootDiscussion(), "Vamos verificar"));

        assertEquals(f.rootDiscussion(), responseRepository.findById(saved.getId()).orElseThrow().getDiscussionId());
        assertEquals(saved.getId(),
                responseRepository.findActive(f.review(), f.rootDiscussion(), f.business()).orElseThrow().getId());
        assertTrue(responseRepository.findActive(f.review(), null, f.business()).isEmpty());
        assertEquals(0, responseRepository.findActiveReviewResponses(f.review(), 0, 10).totalElements());

        List<BusinessResponse> byDiscussion =
                responseRepository.findActiveDiscussionResponses(List.of(f.rootDiscussion(), UUID.randomUUID()));
        assertEquals(1, byDiscussion.size());
        assertEquals(saved.getId(), byDiscussion.get(0).getId());
        assertTrue(responseRepository.findActiveDiscussionResponses(List.of()).isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("Comentário de outra avaliação é recusado pela FK composta (discussion_id, review_id)")
    void rejectsDiscussionFromAnotherReview() {
        Fixture f = fixture();
        UUID otherReview = insertReview(f.reviewer());
        UUID otherDiscussion = insertDiscussion(otherReview, f.reviewer());

        assertRejected("fk_business_responses_discussion", """
                INSERT INTO business_responses (id, review_id, discussion_id, business_account_id, place_id, author_user_id, content)
                VALUES (?, ?, ?, ?, ?, ?, 'Resposta')
                """, UUID.randomUUID(), f.review(), otherDiscussion, f.business(), f.place(), f.businessAdmin());

        assertThrows(DataIntegrityViolationException.class, () -> responseRepository.save(BusinessResponse.publish(
                f.review(), otherDiscussion, f.business(), f.place(), f.businessAdmin(), "Resposta", now)));
    }

    @Test
    @Transactional
    @DisplayName("CHECKs de status, origem da remoção, coerência da remoção e conteúdo")
    void enforcesChecks() {
        Fixture f = fixture();
        String insert = """
                INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content,
                                                status, removed_by, removed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp removedAt = Timestamp.from(now);

        // Status fora do domínio viola também chk_business_responses_removal; o PostgreSQL relata só uma delas
        assertRejected("chk_business_responses_", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "x", "UNDER_REVIEW", null, null);
        assertRejected("chk_business_responses_removed_by", insert, UUID.randomUUID(), f.review(), f.business(),
                f.place(), f.businessAdmin(), "x", "REMOVED", "SYSTEM", removedAt);
        assertRejected("chk_business_responses_removal", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "x", "ACTIVE", "BUSINESS", removedAt);
        assertRejected("chk_business_responses_removal", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "x", "REMOVED", null, removedAt);
        assertRejected("chk_business_responses_removal", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "x", "REMOVED", "MODERATION", null);
        assertRejected("chk_business_responses_content", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "   ", "ACTIVE", null, null);
        assertRejected("chk_business_responses_content", insert, UUID.randomUUID(), f.review(), f.business(), f.place(),
                f.businessAdmin(), "a".repeat(2001), "ACTIVE", null, null);

        // Limite exato e remoção coerente são aceitos
        jdbcTemplate.update(insert, UUID.randomUUID(), f.review(), f.business(), f.place(), f.businessAdmin(),
                "a".repeat(2000), "REMOVED", "MODERATION", removedAt);
    }

    @Test
    @Transactional
    @DisplayName("No máximo uma resposta ativa por empresa e avaliação; outra empresa responde normalmente")
    void uniqueActiveReviewResponse() {
        Fixture f = fixture();
        responseRepository.save(publish(f, null, "Primeira"));
        UUID otherBusiness = insertBusiness(insertUser());
        responseRepository.save(BusinessResponse.publish(f.review(), null, otherBusiness, f.place(), f.businessAdmin(),
                "Outra empresa", now));
        // A resposta ao comentário não conta para o limite da avaliação
        responseRepository.save(publish(f, f.rootDiscussion(), "No comentário"));

        assertRejected("uq_business_responses_review_active", """
                INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content)
                VALUES (?, ?, ?, ?, ?, 'Segunda')
                """, UUID.randomUUID(), f.review(), f.business(), f.place(), f.businessAdmin());

        assertThrows(DataIntegrityViolationException.class, () -> responseRepository.save(publish(f, null, "Segunda")));
    }

    @Test
    @Transactional
    @DisplayName("No máximo uma resposta ativa por empresa e comentário")
    void uniqueActiveDiscussionResponse() {
        Fixture f = fixture();
        responseRepository.save(publish(f, f.rootDiscussion(), "Primeira"));
        UUID otherDiscussion = insertDiscussion(f.review(), f.reviewer());
        responseRepository.save(publish(f, otherDiscussion, "Outro comentário"));

        assertRejected("uq_business_responses_discussion_active", """
                INSERT INTO business_responses (id, review_id, discussion_id, business_account_id, place_id, author_user_id, content)
                VALUES (?, ?, ?, ?, ?, ?, 'Segunda')
                """, UUID.randomUUID(), f.review(), f.rootDiscussion(), f.business(), f.place(), f.businessAdmin());
    }

    @Test
    @Transactional
    @DisplayName("Remoção lógica preserva o histórico e libera nova resposta ao mesmo alvo")
    void republishAfterRemoval() {
        Fixture f = fixture();
        BusinessResponse first = responseRepository.save(publish(f, null, "Primeira"));
        BusinessResponse firstOnDiscussion = responseRepository.save(publish(f, f.rootDiscussion(), "Primeira"));

        BusinessResponse locked = responseRepository.findByIdForUpdate(first.getId()).orElseThrow();
        locked.removeByBusiness(now.plusSeconds(5));
        responseRepository.save(locked);
        firstOnDiscussion.removeByModeration(now.plusSeconds(5));
        responseRepository.save(firstOnDiscussion);

        BusinessResponse second = responseRepository.save(publish(f, null, "Segunda"));
        BusinessResponse secondOnDiscussion = responseRepository.save(publish(f, f.rootDiscussion(), "Segunda"));

        BusinessResponse history = responseRepository.findById(first.getId()).orElseThrow();
        assertEquals(BusinessResponseStatus.REMOVED, history.getStatus());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, history.getRemovedBy());
        assertEquals(now.plusSeconds(5), history.getRemovedAt());
        assertEquals("Primeira", history.getContent());
        assertEquals(f.business(), history.getBusinessAccountId());
        assertEquals(f.place(), history.getPlaceId());
        assertEquals(BusinessResponseRemovalSource.MODERATION,
                responseRepository.findById(firstOnDiscussion.getId()).orElseThrow().getRemovedBy());

        assertEquals(second.getId(), responseRepository.findActive(f.review(), null, f.business()).orElseThrow().getId());
        assertEquals(secondOnDiscussion.getId(),
                responseRepository.findActive(f.review(), f.rootDiscussion(), f.business()).orElseThrow().getId());
        PageResult<BusinessResponse> page = responseRepository.findActiveReviewResponses(f.review(), 0, 10);
        assertEquals(List.of(second.getId()), page.content().stream().map(r -> r.getId()).toList());
        assertEquals(4, count("SELECT count(*) FROM business_responses WHERE review_id = ?", f.review()));
    }

    @Test
    @Transactional
    @DisplayName("Alvo, empresa, local, autor e conteúdo não mudam numa atualização de estado (colunas não atualizáveis)")
    void identityColumnsAreNotUpdated() {
        Fixture f = fixture();
        BusinessResponse saved = responseRepository.save(publish(f, null, "Original"));
        // Alteração externa simulada; a gravação da remoção não deve reescrever as colunas de identidade
        jdbcTemplate.update("UPDATE business_responses SET content = 'Alterado no banco' WHERE id = ?", saved.getId());

        BusinessResponse stale = new BusinessResponse(saved.getId(), saved.getReviewId(), null, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Outro conteúdo", BusinessResponseStatus.ACTIVE, null, null,
                saved.getCreatedAt(), saved.getUpdatedAt());
        stale.removeByBusiness(now.plusSeconds(1));
        responseRepository.save(stale);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT business_account_id, place_id, author_user_id, content, status FROM business_responses WHERE id = ?",
                saved.getId());
        assertEquals(f.business(), row.get("business_account_id"));
        assertEquals(f.place(), row.get("place_id"));
        assertEquals(f.businessAdmin(), row.get("author_user_id"));
        assertEquals("Alterado no banco", row.get("content"));
        assertEquals("REMOVED", row.get("status"));
    }

    @Test
    @Transactional
    @DisplayName("Exclusões: avaliação e comentário levam as respostas; empresa, local e autor são histórico (RESTRICT)")
    void deletionBehaviour() {
        Fixture f = fixture();
        BusinessResponse onReview = responseRepository.save(publish(f, null, "Na avaliação"));
        BusinessResponse onDiscussion = responseRepository.save(publish(f, f.rootDiscussion(), "No comentário"));
        auditLogRepository.save(audit(onReview.getId(), insertUser()));

        assertRejected("business_responses_business_account_id_fkey",
                "DELETE FROM business_accounts WHERE id = ?", f.business());
        assertRejected("business_responses_place_id_fkey", "DELETE FROM places WHERE id = ?", f.place());
        // Autor sem conta comercial própria, para que só a FK do autor esteja em jogo
        UUID writer = insertUser();
        responseRepository.save(BusinessResponse.publish(f.review(), null, insertBusiness(insertUser()), f.place(),
                writer, "Outra empresa", now));
        assertRejected("business_responses_author_user_id_fkey", "DELETE FROM users WHERE id = ?", writer);
        // Administrador: a cascata users -> business_accounts esbarra no RESTRICT da resposta
        assertRejected("on table \"business_responses\"", "DELETE FROM users WHERE id = ?", f.businessAdmin());

        jdbcTemplate.update("DELETE FROM review_discussions WHERE id = ?", f.rootDiscussion());
        assertEquals(0, count("SELECT count(*) FROM business_responses WHERE id = ?", onDiscussion.getId()));
        assertEquals(1, count("SELECT count(*) FROM business_responses WHERE id = ?", onReview.getId()));

        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", f.review());
        assertEquals(0, count("SELECT count(*) FROM business_responses WHERE review_id = ?", f.review()));
        assertEquals(0, count("SELECT count(*) FROM business_response_moderation_audit_logs WHERE response_id = ?",
                onReview.getId()));
    }

    @Test
    @Transactional
    @DisplayName("Auditoria de moderação: grava, lê em ordem, é append-only e o moderador é RESTRICT")
    void auditTrail() {
        Fixture f = fixture();
        BusinessResponse response = responseRepository.save(publish(f, null, "Resposta"));
        UUID moderator = insertUser();

        BusinessResponseModerationAuditLog removal = auditLogRepository.save(audit(response.getId(), moderator));
        BusinessResponseModerationAuditLog restore = auditLogRepository.save(new BusinessResponseModerationAuditLog(
                null, response.getId(), moderator, BusinessResponseModerationAction.RESTORE_RESPONSE, "APPEAL",
                "Recurso aceito", BusinessResponseStatus.REMOVED, BusinessResponseStatus.ACTIVE, now.plusSeconds(10)));

        List<BusinessResponseModerationAuditLog> logs = auditLogRepository.findByResponseId(response.getId());
        assertEquals(List.of(removal.getId(), restore.getId()), logs.stream().map(l -> l.getId()).toList());
        assertEquals(BusinessResponseModerationAction.REMOVE_RESPONSE, logs.get(0).getAction());
        assertEquals(moderator, logs.get(0).getModeratorUserId());
        assertEquals("SPAM", logs.get(0).getReasonCode());
        assertEquals(BusinessResponseStatus.ACTIVE, logs.get(0).getPreviousStatus());
        assertEquals(BusinessResponseStatus.REMOVED, logs.get(0).getNewStatus());
        assertTrue(auditLogRepository.findByResponseId(UUID.randomUUID()).isEmpty());

        assertRejected("append-only", "UPDATE business_response_moderation_audit_logs SET justification = 'x' WHERE id = ?",
                removal.getId());
        assertRejected("chk_business_response_audit_action", """
                INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, reason_code,
                    justification, previous_status, new_status)
                VALUES (?, ?, 'REMOVE_DISCUSSION', 'SPAM', 'j', 'ACTIVE', 'REMOVED')
                """, response.getId(), moderator);
        assertRejected("business_response_moderation_audit_logs_moderator_user_id_fkey",
                "DELETE FROM users WHERE id = ?", moderator);
    }

    @Test
    @DisplayName("Concorrência: duas publicações que passam juntas pela checagem prévia geram uma única resposta ativa")
    void concurrentPublicationsKeepOneActive() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Fixture f = tx.execute(_ -> fixture());
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String content = "Tentativa " + i;
                attempts.add(executor.submit(() -> {
                    try {
                        tx.executeWithoutResult(_ -> {
                            if (responseRepository.findActive(f.review(), null, f.business()).isPresent()) {
                                throw new IllegalStateException("checagem prévia deveria passar nas duas");
                            }
                            await(bothChecked);
                            responseRepository.save(publish(f, null, content));
                        });
                        return true;
                    } catch (DataIntegrityViolationException duplicate) {
                        return false;
                    }
                }));
            }
            int published = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(30, TimeUnit.SECONDS)) {
                    published++;
                }
            }
            assertEquals(1, published);
            assertEquals(1, count("SELECT count(*) FROM business_responses WHERE review_id = ? AND status = 'ACTIVE'",
                    f.review()));
        } finally {
            executor.shutdownNow();
            cleanup(f);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private BusinessResponse publish(Fixture f, UUID discussionId, String content) {
        return BusinessResponse.publish(f.review(), discussionId, f.business(), f.place(), f.businessAdmin(), content, now);
    }

    private BusinessResponseModerationAuditLog audit(UUID responseId, UUID moderator) {
        return new BusinessResponseModerationAuditLog(null, responseId, moderator,
                BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", "Conteúdo promocional",
                BusinessResponseStatus.ACTIVE, BusinessResponseStatus.REMOVED, now);
    }

    private Fixture fixture() {
        UUID reviewer = insertUser();
        UUID businessAdmin = insertUser();
        UUID place = insertPlace();
        UUID business = insertBusiness(businessAdmin);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", business, place);
        UUID review = insertReview(reviewer);
        jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 4)", review, place);
        UUID root = insertDiscussion(review, reviewer);
        return new Fixture(reviewer, businessAdmin, place, business, review, root);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, auth_provider) VALUES (?, ?, 'LOCAL')",
                id, "c11." + id + "@rewit.test");
        return id;
    }

    private UUID insertPlace() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", id);
        jdbcTemplate.update("INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates) "
                + "VALUES (?, 'Local C11', ?, 'CAFE', 'Rua A', 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326))",
                id, "c11-" + id);
        return id;
    }

    private UUID insertBusiness(UUID adminUserId) {
        return jdbcTemplate.queryForObject("INSERT INTO business_accounts (user_id, corporate_name, tax_id, "
                        + "verification_status) VALUES (?, 'Empresa C11', ?, 'APPROVED') RETURNING id",
                UUID.class, adminUserId, UUID.randomUUID().toString().replace("-", "").substring(0, 14));
    }

    private UUID insertReview(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reviews (id, user_id) VALUES (?, ?)", id, userId);
        return id;
    }

    private UUID insertDiscussion(UUID reviewId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_discussions (id, review_id, user_id, content) VALUES (?, ?, ?, 'Comentário')",
                id, reviewId, userId);
        return id;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * Executa o comando num savepoint da transação corrente e exige que o banco o recuse citando {@code expected}
     * (nome da constraint ou trecho da mensagem); o savepoint é desfeito para a transação seguir utilizável.
     */
    private void assertRejected(String expected, String sql, Object... args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                statement.executeUpdate();
                fail("O banco deveria recusar: " + sql);
            } catch (SQLException expectedFailure) {
                connection.rollback(savepoint);
                String message = String.valueOf(expectedFailure.getMessage());
                assertTrue(message.contains(expected), () -> "Esperava " + expected + ", veio: " + message);
            }
            return null;
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }

    private void cleanup(Fixture f) {
        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", f.review());
        jdbcTemplate.update("DELETE FROM places WHERE id = ?", f.place());
        jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", f.place());
        jdbcTemplate.update("DELETE FROM business_accounts WHERE id = ?", f.business());
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", f.reviewer(), f.businessAdmin());
    }
}
