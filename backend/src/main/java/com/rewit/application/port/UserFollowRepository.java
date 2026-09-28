package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.UserFollow;

import java.util.UUID;

/**
 * Porta de aplicação para persistência e consulta do subsistema de seguidores e conexões sociais (Step 15.0).
 */
public interface UserFollowRepository {

    /**
     * Estabelece relação de seguidor de forma idempotente.
     * Retorna true se a relação foi criada agora, ou false se já existia previamente.
     */
    boolean follow(UUID followerUserId, UUID followedUserId);

    /**
     * Remove relação de seguidor de forma idempotente.
     * Retorna true se a relação foi removida, ou false se não existia previamente.
     */
    boolean unfollow(UUID followerUserId, UUID followedUserId);

    /**
     * Verifica se followerUserId segue followedUserId de forma ativa.
     */
    boolean isFollowing(UUID followerUserId, UUID followedUserId);

    /**
     * Quantidade total de seguidores de um usuário.
     */
    long countFollowers(UUID userId);

    /**
     * Quantidade total de usuários seguidos por um usuário.
     */
    long countFollowing(UUID userId);

    /**
     * Consulta paginada dos relacionamentos onde followerUserId é o seguidor.
     */
    PageResult<UserFollow> findFollowing(UUID followerUserId, int page, int size);

    /**
     * Consulta paginada dos relacionamentos onde followedUserId é o seguido.
     */
    PageResult<UserFollow> findFollowers(UUID followedUserId, int page, int size);
}
