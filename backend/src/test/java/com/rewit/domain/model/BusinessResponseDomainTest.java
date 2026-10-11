package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Domínio das respostas oficiais de empresas (C11)")
class BusinessResponseDomainTest {

    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");
    private final Instant later = now.plusSeconds(60);
    private final UUID reviewId = UUID.randomUUID();
    private final UUID discussionId = UUID.randomUUID();
    private final UUID businessAccountId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID authorUserId = UUID.randomUUID();

    private BusinessResponse publishToReview(String content) {
        return BusinessResponse.publish(reviewId, null, businessAccountId, placeId, authorUserId, content, now);
    }

    @Test
    @DisplayName("Publicação: ativa, conteúdo aparado, alvo, empresa, local e autor registrados")
    void publish() {
        BusinessResponse response = publishToReview("  Obrigado pela visita!  ");

        assertEquals(BusinessResponseStatus.ACTIVE, response.getStatus());
        assertTrue(response.isActive());
        assertEquals("Obrigado pela visita!", response.getContent());
        assertEquals(reviewId, response.getReviewId());
        assertNull(response.getDiscussionId());
        assertFalse(response.respondsToDiscussion());
        assertEquals(businessAccountId, response.getBusinessAccountId());
        assertEquals(placeId, response.getPlaceId());
        assertEquals(authorUserId, response.getAuthorUserId());
        assertNull(response.getRemovedBy());
        assertNull(response.getRemovedAt());
        assertEquals(now, response.getCreatedAt());
        assertEquals(now, response.getUpdatedAt());
    }

    @Test
    @DisplayName("Resposta a um comentário raiz guarda a discussão-alvo")
    void publishToDiscussion() {
        BusinessResponse response = BusinessResponse.publish(reviewId, discussionId, businessAccountId, placeId,
                authorUserId, "Vamos verificar", now);

        assertEquals(discussionId, response.getDiscussionId());
        assertTrue(response.respondsToDiscussion());
    }

    @Test
    @DisplayName("Conteúdo vazio, só espaços ou acima de 2000 caracteres é recusado com 400")
    void invalidContent() {
        for (String blank : new String[] {null, "", "   ", "\n\t "}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> publishToReview(blank));
            assertEquals("EMPTY_CONTENT", ex.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
        BusinessException tooLong = assertThrows(BusinessException.class, () -> publishToReview("a".repeat(2001)));
        assertEquals("INVALID_CONTENT_LENGTH", tooLong.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatus());
    }

    @Test
    @DisplayName("Limite de 2000 conta caracteres como o banco (code points) e ignora espaços externos")
    void contentLimitBoundaries() {
        assertEquals(2000, publishToReview("a".repeat(2000)).getContent().length());
        assertEquals(2000, publishToReview("  " + "a".repeat(2000) + "  ").getContent().length());
        // Emoji fora do BMP: 2 chars Java, 1 caractere para o PostgreSQL (char_length)
        String emojis = "😀".repeat(2000);
        assertEquals(emojis, publishToReview(emojis).getContent());
        assertThrows(BusinessException.class, () -> publishToReview(emojis + "a"));
    }

    @Test
    @DisplayName("Alvo, empresa, local e autor são obrigatórios")
    void requiredIdentifiers() {
        assertEquals("MISSING_REVIEW_ID", assertThrows(BusinessException.class, () -> BusinessResponse.publish(
                null, null, businessAccountId, placeId, authorUserId, "x", now)).getErrorCode());
        assertEquals("MISSING_BUSINESS_ACCOUNT_ID", assertThrows(BusinessException.class, () -> BusinessResponse.publish(
                reviewId, null, null, placeId, authorUserId, "x", now)).getErrorCode());
        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class, () -> BusinessResponse.publish(
                reviewId, null, businessAccountId, null, authorUserId, "x", now)).getErrorCode());
        assertEquals("MISSING_USER_ID", assertThrows(BusinessException.class, () -> BusinessResponse.publish(
                reviewId, null, businessAccountId, placeId, null, "x", now)).getErrorCode());
    }

    @Test
    @DisplayName("Remoção pela empresa registra origem e instante e preserva o registro")
    void removeByBusiness() {
        BusinessResponse response = publishToReview("Resposta");

        response.removeByBusiness(later);

        assertEquals(BusinessResponseStatus.REMOVED, response.getStatus());
        assertFalse(response.isActive());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, response.getRemovedBy());
        assertEquals(later, response.getRemovedAt());
        assertEquals(later, response.getUpdatedAt());
        assertEquals("Resposta", response.getContent());
        assertEquals(now, response.getCreatedAt());
    }

    @Test
    @DisplayName("Remoção pela moderação registra a origem MODERATION")
    void removeByModeration() {
        BusinessResponse response = publishToReview("Resposta");

        response.removeByModeration(later);

        assertEquals(BusinessResponseStatus.REMOVED, response.getStatus());
        assertEquals(BusinessResponseRemovalSource.MODERATION, response.getRemovedBy());
        assertEquals(later, response.getRemovedAt());
    }

    @Test
    @DisplayName("Remover de novo é recusado com 409 e não sobrescreve a remoção original")
    void removeTwice() {
        BusinessResponse response = publishToReview("Resposta");
        response.removeByBusiness(now);

        BusinessException byModeration = assertThrows(BusinessException.class, () -> response.removeByModeration(later));
        BusinessException byBusiness = assertThrows(BusinessException.class, () -> response.removeByBusiness(later));

        assertEquals("BUSINESS_RESPONSE_ALREADY_REMOVED", byModeration.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, byBusiness.getStatus());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, response.getRemovedBy());
        assertEquals(now, response.getRemovedAt());
    }

    @Test
    @DisplayName("Só a moderação desfaz a própria remoção; a remoção da empresa e a resposta ativa não são restauráveis")
    void restore() {
        BusinessResponse moderated = publishToReview("Resposta");
        moderated.removeByModeration(now);
        moderated.restoreByModeration(later);
        assertEquals(BusinessResponseStatus.ACTIVE, moderated.getStatus());
        assertNull(moderated.getRemovedBy());
        assertNull(moderated.getRemovedAt());
        assertEquals(later, moderated.getUpdatedAt());

        BusinessResponse withdrawn = publishToReview("Resposta");
        withdrawn.removeByBusiness(now);
        BusinessException ex = assertThrows(BusinessException.class, () -> withdrawn.restoreByModeration(later));
        assertEquals("BUSINESS_RESPONSE_NOT_RESTORABLE", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(BusinessResponseStatus.REMOVED, withdrawn.getStatus());

        BusinessResponse active = publishToReview("Resposta");
        assertEquals("BUSINESS_RESPONSE_NOT_RESTORABLE",
                assertThrows(BusinessException.class, () -> active.restoreByModeration(later)).getErrorCode());
    }

    @Test
    @DisplayName("Reconstituição recusa metadados de remoção incoerentes com o status")
    void reconstitutionInvariants() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new BusinessResponse(id, reviewId, null, businessAccountId,
                placeId, authorUserId, "x", BusinessResponseStatus.ACTIVE, BusinessResponseRemovalSource.BUSINESS, now,
                now, now));
        assertThrows(IllegalStateException.class, () -> new BusinessResponse(id, reviewId, null, businessAccountId,
                placeId, authorUserId, "x", BusinessResponseStatus.REMOVED, null, now, now, now));
        assertThrows(IllegalStateException.class, () -> new BusinessResponse(id, reviewId, null, businessAccountId,
                placeId, authorUserId, "x", BusinessResponseStatus.REMOVED, BusinessResponseRemovalSource.MODERATION,
                null, now, now));

        BusinessResponse removed = new BusinessResponse(id, reviewId, discussionId, businessAccountId, placeId,
                authorUserId, "x", BusinessResponseStatus.REMOVED, BusinessResponseRemovalSource.MODERATION, later, now,
                later);
        assertEquals(BusinessResponseRemovalSource.MODERATION, removed.getRemovedBy());
    }

    @Test
    @DisplayName("Alvo, empresa, local, autor e conteúdo são imutáveis: campos final e sem mutadores")
    void identityIsImmutable() throws NoSuchFieldException {
        for (String name : List.of("id", "reviewId", "discussionId", "businessAccountId", "placeId", "authorUserId",
                "content", "createdAt")) {
            Field field = BusinessResponse.class.getDeclaredField(name);
            assertTrue(Modifier.isFinal(field.getModifiers()), name + " deve ser final");
        }

        BusinessResponse response = BusinessResponse.publish(reviewId, discussionId, businessAccountId, placeId,
                authorUserId, "Resposta", now);
        response.removeByModeration(now);
        response.restoreByModeration(later);
        response.removeByBusiness(later);

        assertEquals(reviewId, response.getReviewId());
        assertEquals(discussionId, response.getDiscussionId());
        assertEquals(businessAccountId, response.getBusinessAccountId());
        assertEquals(placeId, response.getPlaceId());
        assertEquals(authorUserId, response.getAuthorUserId());
        assertEquals("Resposta", response.getContent());
    }

    @Test
    @DisplayName("Auditoria de moderação exige alvo, moderador, ação, motivo, justificativa e status")
    void auditLog() {
        UUID responseId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        BusinessResponseModerationAuditLog log = new BusinessResponseModerationAuditLog(null, responseId, moderatorId,
                BusinessResponseModerationAction.REMOVE_RESPONSE, " SPAM ", " Conteúdo promocional ",
                BusinessResponseStatus.ACTIVE, BusinessResponseStatus.REMOVED, now);

        assertEquals(responseId, log.getResponseId());
        assertEquals(moderatorId, log.getModeratorUserId());
        assertEquals("SPAM", log.getReasonCode());
        assertEquals("Conteúdo promocional", log.getJustification());
        assertEquals(now, log.getCreatedAt());

        assertEquals("MISSING_BUSINESS_RESPONSE_ID", assertThrows(BusinessException.class,
                () -> new BusinessResponseModerationAuditLog(null, null, moderatorId,
                        BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", "j", BusinessResponseStatus.ACTIVE,
                        BusinessResponseStatus.REMOVED, now)).getErrorCode());
        assertEquals("MISSING_MODERATOR_ID", assertThrows(BusinessException.class,
                () -> new BusinessResponseModerationAuditLog(null, responseId, null,
                        BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", "j", BusinessResponseStatus.ACTIVE,
                        BusinessResponseStatus.REMOVED, now)).getErrorCode());
        assertEquals("MISSING_JUSTIFICATION", assertThrows(BusinessException.class,
                () -> new BusinessResponseModerationAuditLog(null, responseId, moderatorId,
                        BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", "  ", BusinessResponseStatus.ACTIVE,
                        BusinessResponseStatus.REMOVED, now)).getErrorCode());
        assertEquals("INVALID_REASON_CODE_LENGTH", assertThrows(BusinessException.class,
                () -> new BusinessResponseModerationAuditLog(null, responseId, moderatorId,
                        BusinessResponseModerationAction.REMOVE_RESPONSE, "X".repeat(65), "j",
                        BusinessResponseStatus.ACTIVE, BusinessResponseStatus.REMOVED, now)).getErrorCode());
        assertEquals("MISSING_BUSINESS_RESPONSE_STATUS", assertThrows(BusinessException.class,
                () -> new BusinessResponseModerationAuditLog(null, responseId, moderatorId,
                        BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", "j", null,
                        BusinessResponseStatus.REMOVED, now)).getErrorCode());
    }
}
