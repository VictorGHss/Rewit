package com.rewit.application.dto.discussion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visões da thread de discussões montada pelo servidor (C3 D2). O estado interno de moderação nunca sai daqui:
 * o cliente recebe apenas o estado de apresentação calculado para o leitor.
 */
public final class DiscussionThreadDtos {

    private DiscussionThreadDtos() {
    }

    /**
     * Estado de apresentação de um item para o leitor.
     * <ul>
     *   <li>{@code VISIBLE}: comentário publicado;</li>
     *   <li>{@code REMOVED}: tombstone de um comentário removido que ainda tem respostas visíveis, sem conteúdo,
     *       sem autor e sem indicar quem removeu;</li>
     *   <li>{@code PENDING_REVIEW}: comentário do próprio leitor em análise; terceiros nunca recebem este valor.</li>
     * </ul>
     */
    public enum DiscussionViewState {
        VISIBLE,
        REMOVED,
        PENDING_REVIEW
    }

    public record DiscussionAuthorView(UUID id, String handle, String displayName, String avatarUrl) {}

    /**
     * @param content    {@code null} quando {@code REMOVED}
     * @param author     {@code null} quando {@code REMOVED} ou quando o anonimato da avaliação mascara o autor
     * @param canReply   o leitor pode responder: somente raiz {@code VISIBLE} (um nível de resposta)
     * @param canDelete  o leitor é o autor e o item está {@code VISIBLE} (em análise, só a moderação decide)
     */
    public record DiscussionItemView(
            UUID id,
            UUID reviewId,
            UUID parentId,
            DiscussionViewState state,
            String content,
            DiscussionAuthorView author,
            boolean isFromOwner,
            Instant createdAt,
            boolean canReply,
            boolean canDelete
    ) {}

    /**
     * Raiz com as primeiras respostas visíveis embutidas.
     *
     * @param replyCount     total de respostas visíveis ao leitor
     * @param hasMoreReplies há respostas além das embutidas (buscar em {@code /discussions/{id}/replies})
     */
    public record DiscussionThreadView(
            DiscussionItemView root,
            List<DiscussionItemView> replies,
            long replyCount,
            boolean hasMoreReplies
    ) {}
}
