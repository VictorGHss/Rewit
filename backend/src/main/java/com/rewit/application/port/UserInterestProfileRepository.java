package com.rewit.application.port;

import com.rewit.domain.model.UserInterestProfile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência dos interesses do usuário e da situação do questionário inicial (V40).
 *
 * <p>É também a fonte dos interesses para o Feed V2: o feed lê {@link #findByUserId} com o usuário autenticado, sem
 * depender de uma lista enviada pelo cliente.
 */
public interface UserInterestProfileRepository {

    /** Perfil gravado; vazio quando o usuário nunca gravou respostas (questionário não iniciado). */
    Optional<UserInterestProfile> findByUserId(UUID userId);

    /**
     * Perfil do usuário com a linha travada até o fim da transação; cria a linha (questionário em andamento, sem
     * interesses, datas em {@code now}) quando ainda não existe. Gravações concorrentes do mesmo usuário, inclusive a
     * primeira, são serializadas e a segunda enxerga o estado confirmado pela primeira.
     */
    UserInterestProfile findOrCreateForUpdate(UUID userId, Instant now);

    /**
     * Grava a situação do questionário e substitui integralmente a seleção de interesses. Exige, na mesma transação,
     * a trava de {@link #findOrCreateForUpdate}. Devolve o estado persistido.
     */
    UserInterestProfile save(UserInterestProfile profile);
}
