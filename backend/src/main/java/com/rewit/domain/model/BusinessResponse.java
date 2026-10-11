package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Resposta oficial de uma empresa a uma avaliação ou a um comentário raiz dela (C11).
 *
 * <p>Entidade própria, distinta de {@link ReviewDiscussion}: {@code ReviewDiscussion.isFromOwner} continua
 * significando "comentário do autor da avaliação" e não identifica empresas. A identidade pública da resposta é a
 * empresa ({@code businessAccountId}) e o local que ela representa ({@code placeId}); {@code authorUserId} é o
 * usuário que publicou, guardado só para auditoria interna.
 *
 * <p>Alvo (avaliação, comentário), empresa, local e autor são fixados na publicação e não mudam; não há edição de
 * conteúdo nesta versão. Remoção é lógica e registra quando e por quem; a resposta removida permanece como
 * histórico. Só a moderação desfaz uma remoção feita pela própria moderação.
 *
 * <p>Quem pode publicar ou remover (administrador da conta, vínculo vigente com o local) é regra da política de
 * aplicação, não desta entidade.
 */
public class BusinessResponse {

    public static final int MAX_CONTENT_LENGTH = 2000;

    private final UUID id;
    private final UUID reviewId;
    private final UUID discussionId;
    private final UUID businessAccountId;
    private final UUID placeId;
    private final UUID authorUserId;
    private final String content;
    private BusinessResponseStatus status;
    private BusinessResponseRemovalSource removedBy;
    private Instant removedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Nova resposta ativa.
     *
     * @param discussionId comentário raiz respondido; {@code null} para responder à avaliação
     */
    public static BusinessResponse publish(UUID reviewId, UUID discussionId, UUID businessAccountId, UUID placeId,
                                           UUID authorUserId, String content, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new BusinessResponse(UUID.randomUUID(), reviewId, discussionId, businessAccountId, placeId, authorUserId,
                normalizeContent(content), BusinessResponseStatus.ACTIVE, null, null, now, now);
    }

    /** Reconstituição fiel a partir da persistência, com as mesmas invariantes de estado. */
    public BusinessResponse(UUID id, UUID reviewId, UUID discussionId, UUID businessAccountId, UUID placeId,
                            UUID authorUserId, String content, BusinessResponseStatus status,
                            BusinessResponseRemovalSource removedBy, Instant removedAt, Instant createdAt,
                            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = required(reviewId, "A avaliação respondida é obrigatória", "MISSING_REVIEW_ID");
        this.discussionId = discussionId;
        this.businessAccountId = required(businessAccountId, "A conta comercial é obrigatória", "MISSING_BUSINESS_ACCOUNT_ID");
        this.placeId = required(placeId, "O local representado é obrigatório", "MISSING_PLACE_ID");
        this.authorUserId = required(authorUserId, "O autor da resposta é obrigatório", "MISSING_USER_ID");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.removedBy = removedBy;
        this.removedAt = removedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        boolean removalRecorded = removedBy != null && removedAt != null;
        boolean noRemoval = removedBy == null && removedAt == null;
        if (status == BusinessResponseStatus.ACTIVE ? !noRemoval : !removalRecorded) {
            throw new IllegalStateException("Metadados de remoção incoerentes com o status " + status);
        }
    }

    /** A empresa retira a própria resposta; pode publicar outra depois. */
    public void removeByBusiness(Instant now) {
        remove(BusinessResponseRemovalSource.BUSINESS, now);
    }

    /** A moderação retira a resposta. */
    public void removeByModeration(Instant now) {
        remove(BusinessResponseRemovalSource.MODERATION, now);
    }

    /**
     * A moderação desfaz a própria remoção. Remoção feita pela empresa não é desfeita: ela publica outra resposta.
     */
    public void restoreByModeration(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BusinessResponseStatus.REMOVED || removedBy != BusinessResponseRemovalSource.MODERATION) {
            throw new BusinessException("Só uma resposta removida pela moderação pode ser restaurada", HttpStatus.CONFLICT,
                    "BUSINESS_RESPONSE_NOT_RESTORABLE");
        }
        this.status = BusinessResponseStatus.ACTIVE;
        this.removedBy = null;
        this.removedAt = null;
        this.updatedAt = now;
    }

    private void remove(BusinessResponseRemovalSource source, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BusinessResponseStatus.ACTIVE) {
            throw new BusinessException("A resposta já foi removida", HttpStatus.CONFLICT, "BUSINESS_RESPONSE_ALREADY_REMOVED");
        }
        this.status = BusinessResponseStatus.REMOVED;
        this.removedBy = source;
        this.removedAt = now;
        this.updatedAt = now;
    }

    /** Aparada; não vazia e com no máximo 2000 caracteres (contados como o banco conta, char_length). */
    public static String normalizeContent(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("O conteúdo da resposta é obrigatório", HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CONTENT_LENGTH) {
            throw new BusinessException("O conteúdo da resposta excede " + MAX_CONTENT_LENGTH + " caracteres",
                    HttpStatus.BAD_REQUEST, "INVALID_CONTENT_LENGTH");
        }
        return trimmed;
    }

    private static UUID required(UUID value, String message, String code) {
        if (value == null) {
            throw new BusinessException(message, HttpStatus.BAD_REQUEST, code);
        }
        return value;
    }

    public boolean isActive() {
        return status == BusinessResponseStatus.ACTIVE;
    }

    /** Resposta a um comentário raiz, e não diretamente à avaliação. */
    public boolean respondsToDiscussion() {
        return discussionId != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getDiscussionId() {
        return discussionId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getContent() {
        return content;
    }

    public BusinessResponseStatus getStatus() {
        return status;
    }

    public BusinessResponseRemovalSource getRemovedBy() {
        return removedBy;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
