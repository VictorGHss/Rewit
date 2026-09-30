package com.rewit.application.port;

import com.rewit.domain.feed.FeedCandidate;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída da aplicação para recuperação de candidatos ao Feed V2.
 *
 * <p>Responsabilidade única: retornar o conjunto factual de candidatos elegíveis
 * para o pipeline de ranking/diversidade do Feed V2, dado um requester e uma
 * janela máxima de candidatos.
 *
 * <p>Contratos de uso:
 * <ul>
 *   <li>O retorno já aplica as regras de visibilidade (PUBLIC e FOLLOWERS de seguidos)
 *       e status (somente ACTIVE).</li>
 *   <li>Reviews do próprio requester nunca são retornadas.</li>
 *   <li>Somente autores diretamente seguidos pelo requester entram na janela.</li>
 *   <li>A ordenação retornada é determinística: {@code createdAt DESC, id ASC}.</li>
 *   <li>O caller não deve passar {@code limit} acima de {@link #CANDIDATE_WINDOW}.</li>
 * </ul>
 *
 * <p>O que este port NÃO faz:
 * <ul>
 *   <li>Não aplica ranking nem score.</li>
 *   <li>Não aplica diversidade.</li>
 *   <li>Não hidrata profiles, targets completos, discussions nem media.</li>
 *   <li>Não expõe DTOs HTTP.</li>
 * </ul>
 */
public interface FeedCandidateRepository {

    /**
     * Janela máxima de candidatos recuperados pelo retrieval V2.
     * Suficiente para o algoritmo de diversidade (MAX_CONSECUTIVE = 2) operar
     * com folga sobre a janela de ranking antes da paginação final.
     */
    int CANDIDATE_WINDOW = 100;

    /**
     * Recupera os candidatos elegíveis ao Feed V2 para o requester informado.
     *
     * @param requesterId identificador interno do usuário autenticado
     * @param limit       número máximo de candidatos; deve ser {@code > 0} e
     *                    {@code <= CANDIDATE_WINDOW}
     * @return lista imutável de candidatos, ordenada por {@code createdAt DESC, id ASC};
     *         lista vazia se não houver follows ou candidatos elegíveis
     */
    List<FeedCandidate> retrieveCandidates(UUID requesterId, int limit);
}
