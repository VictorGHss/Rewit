package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.AdminRemovedBusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishDiscussionBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishReviewBusinessResponseCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseModerationAuditLogRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.usecase.ListDiscussionBusinessResponsesUseCase;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.application.usecase.PublishDiscussionBusinessResponseUseCase;
import com.rewit.application.usecase.PublishReviewBusinessResponseUseCase;
import com.rewit.application.usecase.QueryAdminRemovedBusinessResponsesUseCase;
import com.rewit.application.usecase.RemoveBusinessResponseUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import com.rewit.domain.model.ReviewDiscussion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private PublishReviewBusinessResponseUseCase publishUseCase;

    @Autowired
    private RemoveBusinessResponseUseCase removeUseCase;

    @Autowired
    private PublishDiscussionBusinessResponseUseCase publishDiscussionUseCase;

    @Autowired
    private ListDiscussionBusinessResponsesUseCase listDiscussionUseCase;

    @Autowired
    private ModerateBusinessResponseUseCase moderateUseCase;

    @Autowired
    private QueryAdminRemovedBusinessResponsesUseCase removedQueryUseCase;

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

    // ------------------------------------------------------------------------------- caminhos de aplicação (C11)

    @Test
    @Transactional
    @DisplayName("Locais da avaliação: alvo PLACE, local de SERVICE e de EVENT avaliados e local de contexto, sem outros")
    void associatedPlacesFromEachPath() {
        UUID reviewer = insertUser();
        UUID targetPlace = insertPlace();
        UUID servicePlace = insertPlace();
        UUID eventPlace = insertPlace();
        UUID contextPlace = insertPlace();
        UUID unrelated = insertPlace();
        UUID service = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'SERVICE')", service);
        jdbcTemplate.update("INSERT INTO services (id, place_id, name, category) VALUES (?, ?, 'Delivery', 'DELIVERY')",
                service, servicePlace);
        UUID event = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'EVENT')", event);
        jdbcTemplate.update("INSERT INTO events (id, place_id, title, category, start_at, end_at) "
                + "VALUES (?, ?, 'Show', 'MUSICA', NOW(), NOW() + INTERVAL '2 hours')", event, eventPlace);
        UUID review = insertReview(reviewer);
        jdbcTemplate.update("UPDATE reviews SET context_place_id = ? WHERE id = ?", contextPlace, review);
        for (UUID target : List.of(targetPlace, service, event)) {
            jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 4)", review, target);
        }

        assertEquals(Set.of(targetPlace, servicePlace, eventPlace, contextPlace),
                reviewRepository.findAssociatedPlaceIds(review));
        assertFalse(reviewRepository.findAssociatedPlaceIds(review).contains(unrelated));
        // Contexto que coincide com um alvo não duplica; avaliação sem locais devolve vazio
        jdbcTemplate.update("UPDATE reviews SET context_place_id = ? WHERE id = ?", targetPlace, review);
        assertEquals(Set.of(targetPlace, servicePlace, eventPlace), reviewRepository.findAssociatedPlaceIds(review));
        assertTrue(reviewRepository.findAssociatedPlaceIds(insertReview(reviewer)).isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("Visão pública: só respostas ativas diretas, com empresa e local, em ordem estável e paginada")
    void publicViewPage() {
        Fixture f = fixture();
        UUID otherBusiness = insertBusiness(insertUser());
        BusinessResponse first = responseRepository.save(publish(f, null, "Primeira"));
        BusinessResponse second = responseRepository.save(BusinessResponse.publish(f.review(), null, otherBusiness,
                f.place(), f.businessAdmin(), "Segunda", now.plusSeconds(1)));
        responseRepository.save(publish(f, f.rootDiscussion(), "No comentário"));
        BusinessResponse removed = BusinessResponse.publish(f.review(), null, insertBusiness(insertUser()), f.place(),
                f.businessAdmin(), "Removida", now.plusSeconds(2));
        removed.removeByBusiness(now.plusSeconds(3));
        responseRepository.save(removed);

        PageResult<BusinessResponseView> page = responseRepository.findActiveReviewResponseViews(f.review(), 0, 10);
        assertEquals(2, page.totalElements());
        assertEquals(List.of(first.getId(), second.getId()), page.content().stream().map(v -> v.id()).toList());
        BusinessResponseView view = page.content().get(0);
        assertEquals(f.review(), view.reviewId());
        assertEquals(f.business(), view.businessAccountId());
        assertEquals("Empresa C11", view.corporateName());
        assertEquals(f.place(), view.placeId());
        assertEquals("Local C11", view.placeName());
        assertEquals("Primeira", view.content());
        assertEquals(now, view.createdAt());

        PageResult<BusinessResponseView> secondPage = responseRepository.findActiveReviewResponseViews(f.review(), 1, 1);
        assertEquals(2, secondPage.totalElements());
        assertEquals(List.of(second.getId()), secondPage.content().stream().map(v -> v.id()).toList());
    }

    @Test
    @Transactional
    @DisplayName("Ponta a ponta: publica, recusa a segunda ativa, remove e publica outra")
    void publishRemoveRepublish() {
        Fixture f = fixture();
        BusinessResponseView published = publishUseCase.execute(command(f, f.place(), "Obrigado pela visita!"), now);
        assertEquals("Empresa C11", published.corporateName());
        assertEquals("Local C11", published.placeName());
        assertEquals(f.businessAdmin(), responseRepository.findById(published.id()).orElseThrow().getAuthorUserId());

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> publishUseCase.execute(command(f, f.place(), "De novo"), now));
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", duplicate.getErrorCode());

        removeUseCase.execute(f.businessAdmin(), published.id(), now.plusSeconds(5));
        BusinessResponse history = responseRepository.findById(published.id()).orElseThrow();
        assertEquals(BusinessResponseStatus.REMOVED, history.getStatus());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, history.getRemovedBy());

        BusinessResponseView again = publishUseCase.execute(command(f, f.place(), "Nova resposta"), now.plusSeconds(6));
        assertEquals(List.of(again.id()), responseRepository.findActiveReviewResponseViews(f.review(), 0, 10).content()
                .stream().map(v -> v.id()).toList());
    }

    @Test
    @Transactional
    @DisplayName("Vínculo perdido: nova resposta bloqueada (403); a já publicada fica e a conta ainda pode removê-la")
    void lostLinkBlocksNewButAllowsRemoval() {
        Fixture f = fixture();
        BusinessResponseView published = publishUseCase.execute(command(f, f.place(), "Resposta"), now);

        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", insertBusiness(insertUser()),
                f.place());

        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> publishUseCase.execute(command(f, f.place(), "Outra"), now)).getErrorCode());
        assertTrue(responseRepository.findById(published.id()).orElseThrow().isActive());

        removeUseCase.execute(f.businessAdmin(), published.id(), now.plusSeconds(1));
        assertEquals(BusinessResponseStatus.REMOVED, responseRepository.findById(published.id()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Corrida real: a mesma empresa por dois locais da avaliação ao mesmo tempo publica uma única resposta")
    void concurrentPublicationsThroughDifferentPlaces() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Fixture f = tx.execute(_ -> fixture());
        UUID secondPlace = tx.execute(_ -> {
            UUID place = insertPlace();
            jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", f.business(), place);
            jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 5)",
                    f.review(), place);
            return place;
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> attempts = new ArrayList<>();
            for (UUID place : List.of(f.place(), secondPlace)) {
                attempts.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        publishUseCase.execute(command(f, place, "Pelo local " + place), now);
                        return "OK";
                    } catch (BusinessException conflict) {
                        return conflict.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> attempt : attempts) {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter(o -> o.equals("OK")).count(), () -> outcomes.toString());
            assertEquals(1, outcomes.stream().filter(o -> o.equals("BUSINESS_RESPONSE_ALREADY_EXISTS")).count(),
                    () -> outcomes.toString());
            assertEquals(1, count("SELECT count(*) FROM business_responses WHERE review_id = ? AND status = 'ACTIVE'",
                    f.review()));
        } finally {
            executor.shutdownNow();
            cleanup(f);
            jdbcTemplate.update("DELETE FROM places WHERE id = ?", secondPlace);
            jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", secondPlace);
        }
    }

    @Test
    @DisplayName("Corrida real: troca de vínculo com o local travado; a publicação espera o lock e lê o vínculo novo")
    void publicationWaitsForPlaceLockAndSeesNewLink() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Fixture f = tx.execute(_ -> fixture());
        UUID otherAdmin = tx.execute(_ -> insertUser());
        UUID otherBusiness = tx.execute(_ -> insertBusiness(otherAdmin));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Simula a decisão de reivindicação: trava o local (como o C9) e troca o vínculo antes do commit
            Future<?> relink = executor.submit(() -> tx.executeWithoutResult(_ -> {
                jdbcTemplate.queryForObject("SELECT id FROM places WHERE id = ? FOR UPDATE", UUID.class, f.place());
                jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", otherBusiness, f.place());
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<String> publication = executor.submit(() -> {
                try {
                    publishUseCase.execute(command(f, f.place(), "Resposta"), now);
                    return "OK";
                } catch (BusinessException refused) {
                    return refused.getErrorCode();
                }
            });
            waitForLockWaiter();
            release.countDown();
            relink.get(30, TimeUnit.SECONDS);

            assertEquals("BUSINESS_NOT_RESPONSIBLE", publication.get(30, TimeUnit.SECONDS));
            assertEquals(0, count("SELECT count(*) FROM business_responses WHERE review_id = ?", f.review()));
        } finally {
            release.countDown();
            executor.shutdownNow();
            cleanup(f);
            jdbcTemplate.update("DELETE FROM business_accounts WHERE id = ?", otherBusiness);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", otherAdmin);
        }
    }

    @Test
    @Transactional
    @DisplayName("Comentário ponta a ponta: avaliação correta, uma ativa por empresa, outra empresa e alvos independentes")
    void discussionResponsesEndToEnd() {
        Fixture f = fixture();
        UUID otherAdmin = insertUser();
        UUID otherBusiness = insertBusiness(otherAdmin);
        UUID otherPlace = insertPlace();
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", otherBusiness, otherPlace);
        jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 3)", f.review(), otherPlace);
        UUID secondRoot = insertDiscussion(f.review(), f.reviewer());

        BusinessResponseView first = publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Vamos verificar"), now);
        BusinessResponse stored = responseRepository.findById(first.id()).orElseThrow();
        assertEquals(f.review(), stored.getReviewId());
        assertEquals(f.rootDiscussion(), stored.getDiscussionId());
        assertEquals(f.businessAdmin(), stored.getAuthorUserId());

        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", assertThrows(BusinessException.class,
                () -> publishDiscussionUseCase.execute(discussionCommand(f.businessAdmin(), f.business(),
                        f.rootDiscussion(), f.place(), "De novo"), now)).getErrorCode());

        BusinessResponseView fromOther = publishDiscussionUseCase.execute(
                discussionCommand(otherAdmin, otherBusiness, f.rootDiscussion(), otherPlace, "Também respondemos"),
                now.plusSeconds(1));
        // A mesma empresa responde à avaliação e a outro comentário sem conflito com a resposta acima
        publishUseCase.execute(command(f, f.place(), "Resposta à avaliação"), now.plusSeconds(2));
        publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), secondRoot, f.place(), "Outro comentário"), now);

        PageResult<BusinessResponseView> page = listDiscussionUseCase.execute(f.reviewer(), f.rootDiscussion(), 0, 10);
        assertEquals(List.of(first.id(), fromOther.id()), page.content().stream().map(v -> v.id()).toList());
        BusinessResponseView view = page.content().get(1);
        assertEquals(f.review(), view.reviewId());
        assertEquals(f.rootDiscussion(), view.discussionId());
        assertEquals(otherBusiness, view.businessAccountId());
        assertEquals(otherPlace, view.placeId());
        assertEquals(1, listDiscussionUseCase.execute(f.reviewer(), f.rootDiscussion(), 1, 1).content().size());
        // A resposta à avaliação não aparece entre as do comentário, e vice-versa
        assertEquals(1, responseRepository.findActiveReviewResponseViews(f.review(), 0, 10).totalElements());
    }

    @Test
    @Transactional
    @DisplayName("Comentário: remover libera o índice parcial, a nova publicação preserva o histórico e só a ativa é listada")
    void discussionRemoveAndRepublish() {
        Fixture f = fixture();
        BusinessResponseView first = publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Primeira"), now);

        removeUseCase.execute(f.businessAdmin(), first.id(), now.plusSeconds(1));
        BusinessResponseView second = publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Segunda"),
                now.plusSeconds(2));

        assertEquals(BusinessResponseStatus.REMOVED, responseRepository.findById(first.id()).orElseThrow().getStatus());
        assertEquals(2, count("SELECT count(*) FROM business_responses WHERE discussion_id = ?", f.rootDiscussion()));
        assertEquals(List.of(second.id()), listDiscussionUseCase.execute(f.reviewer(), f.rootDiscussion(), 0, 10)
                .content().stream().map(v -> v.id()).toList());
    }

    @Test
    @Transactional
    @DisplayName("Comentário em quarentena: publicação e listagem respondem 404; respostas existentes ficam ocultas")
    void quarantinedDiscussionHidesResponses() {
        Fixture f = fixture();
        publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Resposta"), now);
        // Pelo domínio, como a denúncia faz: um UPDATE direto deixaria a entidade antiga no cache desta transação
        ReviewDiscussion root = discussionRepository.findById(f.rootDiscussion()).orElseThrow();
        root.markUnderReview();
        discussionRepository.save(root);

        assertEquals("DISCUSSION_NOT_FOUND", assertThrows(BusinessException.class,
                () -> listDiscussionUseCase.execute(f.reviewer(), f.rootDiscussion(), 0, 10)).getErrorCode());
        assertEquals("DISCUSSION_NOT_FOUND", assertThrows(BusinessException.class,
                () -> publishDiscussionUseCase.execute(discussionCommand(f.businessAdmin(), f.business(),
                        f.rootDiscussion(), f.place(), "Outra"), now)).getErrorCode());
    }

    @Test
    @DisplayName("Corrida real no comentário: a mesma empresa por dois locais ao mesmo tempo publica uma única resposta")
    void concurrentDiscussionPublications() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Fixture f = tx.execute(_ -> fixture());
        UUID secondPlace = tx.execute(_ -> {
            UUID place = insertPlace();
            jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", f.business(), place);
            jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 5)",
                    f.review(), place);
            return place;
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> attempts = new ArrayList<>();
            for (UUID place : List.of(f.place(), secondPlace)) {
                attempts.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        publishDiscussionUseCase.execute(discussionCommand(f.businessAdmin(), f.business(),
                                f.rootDiscussion(), place, "Pelo local " + place), now);
                        return "OK";
                    } catch (BusinessException conflict) {
                        return conflict.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> attempt : attempts) {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter(o -> o.equals("OK")).count(), () -> outcomes.toString());
            assertEquals(1, outcomes.stream().filter(o -> o.equals("BUSINESS_RESPONSE_ALREADY_EXISTS")).count(),
                    () -> outcomes.toString());
            assertEquals(1, count("SELECT count(*) FROM business_responses WHERE discussion_id = ? AND status = 'ACTIVE'",
                    f.rootDiscussion()));
        } finally {
            executor.shutdownNow();
            cleanup(f);
            jdbcTemplate.update("DELETE FROM places WHERE id = ?", secondPlace);
            jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", secondPlace);
        }
    }

    // ------------------------------------------------------------------------------------ moderação (C11)

    @Test
    @Transactional
    @DisplayName("Moderação: remoção e restauração gravam resposta e auditoria juntas, em ordem e append-only")
    void moderationRemoveAndRestoreWithAudit() {
        Fixture f = fixture();
        UUID moderator = insertModerator();
        BusinessResponseView published = publishUseCase.execute(command(f, f.place(), "Resposta"), now);

        BusinessResponseModerationResult removed = moderateUseCase.execute(moderation(published.id(), moderator,
                BusinessResponseModerationAction.REMOVE_RESPONSE, now.plusSeconds(10)));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, removed_by, removed_at FROM business_responses WHERE id = ?", published.id());
        assertEquals("REMOVED", row.get("status"));
        assertEquals("MODERATION", row.get("removed_by"));
        assertEquals(BusinessResponseStatus.REMOVED, removed.newStatus());

        BusinessResponseModerationResult restored = moderateUseCase.execute(moderation(published.id(), moderator,
                BusinessResponseModerationAction.RESTORE_RESPONSE, now.plusSeconds(20)));
        assertEquals(BusinessResponseStatus.ACTIVE, restored.newStatus());
        assertTrue(responseRepository.findById(published.id()).orElseThrow().isActive());

        List<BusinessResponseModerationAuditLog> logs = auditLogRepository.findByResponseId(published.id());
        assertEquals(List.of(removed.auditLogId(), restored.auditLogId()), logs.stream().map(l -> l.getId()).toList());
        assertEquals(moderator, logs.get(0).getModeratorUserId());
        assertEquals(BusinessResponseModerationAction.REMOVE_RESPONSE, logs.get(0).getAction());
        assertEquals(BusinessResponseStatus.ACTIVE, logs.get(0).getPreviousStatus());
        assertEquals(BusinessResponseStatus.REMOVED, logs.get(0).getNewStatus());
        assertEquals(BusinessResponseModerationAction.RESTORE_RESPONSE, logs.get(1).getAction());
        assertEquals(BusinessResponseStatus.REMOVED, logs.get(1).getPreviousStatus());
        assertEquals(BusinessResponseStatus.ACTIVE, logs.get(1).getNewStatus());
        assertRejected("append-only", "UPDATE business_response_moderation_audit_logs SET reason_code = 'X' WHERE id = ?",
                removed.auditLogId());
    }

    @Test
    @Transactional
    @DisplayName("Restaurar com resposta nova ativa (avaliação e comentário): 409, nova intacta, antiga removida, sem auditoria")
    void restoreConflictsWithNewerResponse() {
        Fixture f = fixture();
        UUID moderator = insertModerator();
        BusinessResponseView onReview = publishUseCase.execute(command(f, f.place(), "Antiga"), now);
        BusinessResponseView onDiscussion = publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Antiga"), now);
        for (UUID old : List.of(onReview.id(), onDiscussion.id())) {
            moderateUseCase.execute(moderation(old, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                    now.plusSeconds(10)));
        }
        BusinessResponseView newOnReview = publishUseCase.execute(command(f, f.place(), "Nova"), now.plusSeconds(20));
        BusinessResponseView newOnDiscussion = publishDiscussionUseCase.execute(
                discussionCommand(f.businessAdmin(), f.business(), f.rootDiscussion(), f.place(), "Nova"),
                now.plusSeconds(20));

        for (UUID old : List.of(onReview.id(), onDiscussion.id())) {
            BusinessException conflict = assertThrows(BusinessException.class, () -> moderateUseCase.execute(
                    moderation(old, moderator, BusinessResponseModerationAction.RESTORE_RESPONSE, now.plusSeconds(30))));
            assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
            assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", conflict.getErrorCode());

            assertEquals(BusinessResponseStatus.REMOVED, responseRepository.findById(old).orElseThrow().getStatus());
            assertEquals(List.of(BusinessResponseModerationAction.REMOVE_RESPONSE),
                    auditLogRepository.findByResponseId(old).stream().map(l -> l.getAction()).toList());
        }
        for (UUID current : List.of(newOnReview.id(), newOnDiscussion.id())) {
            BusinessResponse response = responseRepository.findById(current).orElseThrow();
            assertTrue(response.isActive());
            assertEquals("Nova", response.getContent());
        }
    }

    @Test
    @DisplayName("Corrida real: a restauração passa pela checagem, espera no índice e falha quando a nova é confirmada")
    void restoreRaceDetectedByUniqueIndex() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Fixture f = tx.execute(_ -> fixture());
        UUID moderator = tx.execute(_ -> insertModerator());
        UUID old = publishUseCase.execute(command(f, f.place(), "Antiga"), now).id();
        BusinessResponseModerationResult removal = moderateUseCase.execute(moderation(old, moderator,
                BusinessResponseModerationAction.REMOVE_RESPONSE, now.plusSeconds(10)));
        Map<String, Object> removalRow = jdbcTemplate.queryForMap(
                "SELECT * FROM business_response_moderation_audit_logs WHERE id = ?", removal.auditLogId());
        UUID newer = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Publicação concorrente ainda sem commit: invisível para a checagem prévia da restauração
            Future<?> publication = executor.submit(() -> tx.executeWithoutResult(_ -> {
                jdbcTemplate.update("INSERT INTO business_responses (id, review_id, business_account_id, place_id, "
                        + "author_user_id, content) VALUES (?, ?, ?, ?, ?, 'Nova')",
                        newer, f.review(), f.business(), f.place(), f.businessAdmin());
                inserted.countDown();
                await(release);
            }));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            Future<String> restore = executor.submit(() -> {
                try {
                    moderateUseCase.execute(moderation(old, moderator, BusinessResponseModerationAction.RESTORE_RESPONSE,
                            now.plusSeconds(20)));
                    return "OK";
                } catch (BusinessException conflict) {
                    return conflict.getErrorCode();
                }
            });
            waitForLockWaiter();
            release.countDown();
            publication.get(30, TimeUnit.SECONDS);

            assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", restore.get(30, TimeUnit.SECONDS));
            assertEquals("REMOVED", jdbcTemplate.queryForObject(
                    "SELECT status FROM business_responses WHERE id = ?", String.class, old));
            assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                    "SELECT status FROM business_responses WHERE id = ?", String.class, newer));
            assertEquals(1, count("SELECT count(*) FROM business_responses WHERE review_id = ? AND status = 'ACTIVE'",
                    f.review()));
            // Nenhuma auditoria da restauração falha; a da remoção continua idêntica
            assertEquals(1, count("SELECT count(*) FROM business_response_moderation_audit_logs WHERE response_id = ?", old));
            assertEquals(removalRow, jdbcTemplate.queryForMap(
                    "SELECT * FROM business_response_moderation_audit_logs WHERE id = ?", removal.auditLogId()));
        } finally {
            release.countDown();
            executor.shutdownNow();
            cleanup(f);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", moderator);
        }
    }

    @Test
    @Transactional
    @DisplayName("Removidas para a moderação: só REMOVED, filtros por alvo, conta, local e origem, restorabilidade e última decisão")
    void removedResponsesForModeration() {
        Fixture f = fixture();
        UUID moderator = insertModerator();
        UUID otherAdmin = insertUser();
        UUID otherBusiness = insertBusiness(otherAdmin);
        UUID otherPlace = insertPlace();
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", otherBusiness, otherPlace);
        jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 3)", f.review(), otherPlace);

        // Avaliação: retirada pela empresa, depois outra removida pela moderação (nada ativo no alvo)
        UUID withdrawn = publishUseCase.execute(command(f, f.place(), "Retirada"), now).id();
        removeUseCase.execute(f.businessAdmin(), withdrawn, now.plusSeconds(1));
        UUID moderated = publishUseCase.execute(command(f, f.place(), "Moderada"), now.plusSeconds(2)).id();
        moderateUseCase.execute(moderation(moderated, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                now.plusSeconds(3)));
        // Comentário: removida pela moderação e substituída por uma nova ativa
        UUID replaced = publishDiscussionUseCase.execute(discussionCommand(f.businessAdmin(), f.business(),
                f.rootDiscussion(), f.place(), "Substituída"), now).id();
        moderateUseCase.execute(moderation(replaced, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                now.plusSeconds(4)));
        UUID replacement = publishDiscussionUseCase.execute(discussionCommand(f.businessAdmin(), f.business(),
                f.rootDiscussion(), f.place(), "Nova"), now.plusSeconds(5)).id();
        // Outra empresa: removida, restaurada e removida de novo; vale a última decisão
        UUID twice = publishUseCase.execute(new PublishReviewBusinessResponseCommand(otherAdmin, otherBusiness,
                f.review(), otherPlace, "Outra empresa"), now).id();
        moderateUseCase.execute(moderation(twice, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                now.plusSeconds(6)));
        moderateUseCase.execute(moderation(twice, moderator, BusinessResponseModerationAction.RESTORE_RESPONSE,
                now.plusSeconds(7)));
        moderateUseCase.execute(new ModerateBusinessResponseCommand(twice, moderator,
                BusinessResponseModerationAction.REMOVE_RESPONSE, "OFF_TOPIC", "Fora do assunto da avaliação",
                now.plusSeconds(8)));
        // Outra avaliação da mesma empresa, fora do filtro por avaliação
        UUID otherReview = insertReview(f.reviewer());
        jdbcTemplate.update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 2)", otherReview, f.place());
        UUID elsewhere = publishUseCase.execute(new PublishReviewBusinessResponseCommand(f.businessAdmin(), f.business(),
                otherReview, f.place(), "Em outra avaliação"), now).id();
        removeUseCase.execute(f.businessAdmin(), elsewhere, now.plusSeconds(9));

        List<AdminRemovedBusinessResponseView> byReview = removed(filter(f.review(), null, null, null, null));
        assertEquals(List.of(twice, replaced, moderated, withdrawn), byReview.stream().map(v -> v.id()).toList());
        assertFalse(byReview.stream().anyMatch(v -> v.id().equals(replacement)), "ativa não aparece");

        Map<UUID, AdminRemovedBusinessResponseView> views = new HashMap<>();
        byReview.forEach(view -> views.put(view.id(), view));
        AdminRemovedBusinessResponseView w = views.get(withdrawn);
        assertEquals(BusinessResponseRemovalSource.BUSINESS, w.removedBy());
        assertEquals(now.plusSeconds(1), w.removedAt());
        assertFalse(w.restorable());
        assertNull(w.moderationReasonCode());
        assertNull(w.moderatedAt());
        AdminRemovedBusinessResponseView m = views.get(moderated);
        assertEquals(BusinessResponseRemovalSource.MODERATION, m.removedBy());
        assertTrue(m.restorable());
        assertFalse(m.otherActiveResponse());
        assertEquals("SPAM", m.moderationReasonCode());
        assertEquals("Conteúdo promocional fora das regras", m.moderationJustification());
        assertEquals(now.plusSeconds(3), m.moderatedAt());
        assertEquals("Empresa C11", m.corporateName());
        assertEquals(f.place(), m.placeId());
        assertEquals("Local C11", m.placeName());
        assertEquals("Moderada", m.content());
        assertEquals(now.plusSeconds(2), m.createdAt());
        AdminRemovedBusinessResponseView r = views.get(replaced);
        assertEquals(f.rootDiscussion(), r.discussionId());
        assertTrue(r.otherActiveResponse());
        assertFalse(r.restorable());
        AdminRemovedBusinessResponseView t = views.get(twice);
        assertEquals("OFF_TOPIC", t.moderationReasonCode());
        assertEquals(now.plusSeconds(8), t.moderatedAt());
        assertEquals(now.plusSeconds(8), t.removedAt());
        assertTrue(t.restorable());

        assertEquals(List.of(replaced), ids(filter(null, f.rootDiscussion(), null, null, null)));
        assertEquals(List.of(twice), ids(filter(null, null, otherBusiness, null, null)));
        assertEquals(List.of(twice), ids(filter(null, null, null, otherPlace, null)));
        assertEquals(List.of(elsewhere, replaced, moderated, withdrawn), ids(filter(null, null, f.business(), null, null)));
        assertEquals(List.of(withdrawn), ids(filter(f.review(), null, null, null, BusinessResponseRemovalSource.BUSINESS)));
        assertEquals(List.of(twice, replaced, moderated),
                ids(filter(f.review(), null, null, null, BusinessResponseRemovalSource.MODERATION)));
        assertTrue(ids(filter(otherReview, f.rootDiscussion(), null, null, null)).isEmpty());

        // A consulta não altera nada
        assertEquals("REMOVED", jdbcTemplate.queryForObject("SELECT status FROM business_responses WHERE id = ?",
                String.class, moderated));
        assertEquals(3, count("SELECT count(*) FROM business_response_moderation_audit_logs WHERE response_id = ?", twice));
    }

    @Test
    @Transactional
    @DisplayName("Removidas com o mesmo removed_at: páginas sem duplicar nem omitir, desempate por id decrescente")
    void removedPaginationWithTies() {
        Fixture f = fixture();
        Timestamp sameInstant = Timestamp.from(now);
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content,
                                                    status, removed_by, removed_at)
                    VALUES (?, ?, ?, ?, ?, 'Retirada', 'REMOVED', 'BUSINESS', ?)
                    """, id, f.review(), f.business(), f.place(), f.businessAdmin(), sameInstant);
            inserted.add(id);
        }
        RemovedBusinessResponseFilter byReview = filter(f.review(), null, null, null, null);

        List<UUID> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            PageResult<AdminRemovedBusinessResponseView> result = removedQueryUseCase.execute(insertModerator(), byReview,
                    page, 2);
            assertEquals(5, result.totalElements());
            result.content().forEach(view -> paged.add(view.id()));
        }
        // Ordem do PostgreSQL para uuid (bytes sem sinal) = ordem do texto canônico; UUID.compareTo usa sinal
        List<UUID> expected = inserted.stream()
                .sorted(Comparator.comparing((UUID id) -> id.toString()).reversed())
                .toList();
        assertEquals(5, Set.copyOf(paged).size());
        assertEquals(expected, paged);
    }

    private RemovedBusinessResponseFilter filter(UUID reviewId, UUID discussionId, UUID businessAccountId, UUID placeId,
                                                 BusinessResponseRemovalSource removedBy) {
        return new RemovedBusinessResponseFilter(reviewId, discussionId, businessAccountId, placeId, removedBy);
    }

    private List<AdminRemovedBusinessResponseView> removed(RemovedBusinessResponseFilter filter) {
        return removedQueryUseCase.execute(insertModerator(), filter, 0, 50).content();
    }

    private List<UUID> ids(RemovedBusinessResponseFilter filter) {
        return removed(filter).stream().map(view -> view.id()).toList();
    }

    private UUID insertModerator() {
        UUID moderator = insertUser();
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator);
        return moderator;
    }

    private static ModerateBusinessResponseCommand moderation(UUID responseId, UUID moderator,
                                                              BusinessResponseModerationAction action, Instant at) {
        return new ModerateBusinessResponseCommand(responseId, moderator, action, "SPAM",
                "Conteúdo promocional fora das regras", at);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static PublishDiscussionBusinessResponseCommand discussionCommand(UUID actor, UUID business, UUID discussion,
                                                                             UUID place, String content) {
        return new PublishDiscussionBusinessResponseCommand(actor, business, discussion, place, content);
    }

    private PublishReviewBusinessResponseCommand command(Fixture f, UUID placeId, String content) {
        return new PublishReviewBusinessResponseCommand(f.businessAdmin(), f.business(), f.review(), placeId, content);
    }

    /** Espera alguma sessão bloqueada em lock de linha (a publicação parada no FOR UPDATE do local). */
    private void waitForLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'") > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("A publicação deveria esperar o lock do local");
    }

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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Tempo esgotado esperando a liberação do lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void cleanup(Fixture f) {
        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", f.review());
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", f.place());
        jdbcTemplate.update("DELETE FROM places WHERE id = ?", f.place());
        jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", f.place());
        jdbcTemplate.update("DELETE FROM business_accounts WHERE id = ?", f.business());
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", f.reviewer(), f.businessAdmin());
    }
}
