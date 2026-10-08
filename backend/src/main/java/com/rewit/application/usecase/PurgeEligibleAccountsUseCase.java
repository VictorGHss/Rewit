package com.rewit.application.usecase;

import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase.Outcome;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase.PurgeResult;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Passada do purge periódico de contas excluídas (C2.3): busca em lotes as contas {@code DELETED} candidatas e chama
 * {@link PurgeDeletedAccountUseCase} para cada uma. Sem transação própria: cada conta é purgada na transação do caso de
 * uso, então a falha de uma não desfaz as outras.
 *
 * <p>A regra de elegibilidade não é duplicada: a consulta só seleciona candidatas pelo mesmo corte
 * ({@link User#PURGE_GRACE_PERIOD}) e o caso de uso faz a validação final sob lock ({@code DELETED}, prazo,
 * idempotência). Passadas concorrentes (outra instância) são seguras: o lock da conta as serializa e a segunda vira
 * no-op.
 *
 * <p>Falhas:
 * <ul>
 *   <li>sem o segredo da reserva de e-mail, nenhuma conta é tocada (a passada é registrada como bloqueada);</li>
 *   <li>falha de uma conta por erro de negócio ou de acesso a dados é contada pela classe e a passada segue;</li>
 *   <li>qualquer outro erro (de programação) interrompe a passada e é propagado; o que já foi purgado permanece.</li>
 * </ul>
 * Logs sem identificador de conta, e-mail ou mensagem de erro (ADR-012): só contagens e a classe da exceção.
 */
public class PurgeEligibleAccountsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurgeEligibleAccountsUseCase.class);

    /**
     * @param candidates    contas candidatas encontradas nesta passada
     * @param purged        purgadas agora
     * @param unchanged     já minimizadas (no-op idempotente, por exemplo outra instância chegou antes)
     * @param notEligible   recusadas pelo caso de uso como dentro do prazo
     * @param failed        falhas individuais (erro de negócio ou de acesso a dados), por classe em {@code failuresByType}
     * @param secretMissing havia candidatas e o segredo da reserva de e-mail não está configurado: nada foi alterado
     * @param limitReached  o limite de lotes da passada foi atingido com candidatas ainda pendentes
     */
    public record AccountPurgeRunResult(int candidates, int purged, int unchanged, int notEligible, int failed,
                                        Map<String, Integer> failuresByType, boolean secretMissing,
                                        boolean limitReached) {

        public AccountPurgeRunResult {
            failuresByType = Map.copyOf(failuresByType);
        }
    }

    private final UserRepository userRepository;
    private final PurgeDeletedAccountUseCase purgeDeletedAccountUseCase;
    private final EmailReservation emailReservation;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public PurgeEligibleAccountsUseCase(UserRepository userRepository,
                                        PurgeDeletedAccountUseCase purgeDeletedAccountUseCase,
                                        EmailReservation emailReservation,
                                        int batchSize,
                                        int maxBatchesPerRun) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.purgeDeletedAccountUseCase = Objects.requireNonNull(purgeDeletedAccountUseCase,
                "purgeDeletedAccountUseCase must not be null");
        this.emailReservation = Objects.requireNonNull(emailReservation, "emailReservation must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("maxBatchesPerRun must be positive");
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /**
     * @param now instante de referência da passada, repassado ao caso de uso
     */
    public AccountPurgeRunResult run(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Instant cutoff = now.minus(User.PURGE_GRACE_PERIOD);

        Set<UUID> attempted = new HashSet<>();
        Map<String, Integer> failuresByType = new LinkedHashMap<>();
        int purged = 0;
        int unchanged = 0;
        int notEligible = 0;
        int failed = 0;
        boolean limitReached = false;

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            // Candidatas que falharam nesta passada continuam pendentes e voltariam no próximo lote: ficam de fora
            List<UUID> candidates = userRepository.findDeletedUserIdsPendingPurge(cutoff, batchSize + attempted.size())
                    .stream()
                    .filter(id -> !attempted.contains(id))
                    .limit(batchSize)
                    .toList();
            if (candidates.isEmpty()) {
                break;
            }
            if (!emailReservation.isConfigured()) {
                // Fail-safe: o caso de uso falharia em todas, antes de qualquer escrita; nada é tentado
                log.error("Account purge: bloqueado, ACCOUNT_EMAIL_RESERVATION_SECRET ausente; contas elegíveis aguardando={}",
                        candidates.size());
                return new AccountPurgeRunResult(candidates.size(), 0, 0, 0, 0, Map.of(), true, false);
            }

            for (UUID userId : candidates) {
                attempted.add(userId);
                try {
                    PurgeResult result = purgeDeletedAccountUseCase.execute(userId, now);
                    if (result.outcome() == Outcome.NOT_ELIGIBLE) {
                        notEligible++;
                    } else if (result.changedAnything()) {
                        purged++;
                    } else {
                        unchanged++;
                    }
                } catch (BusinessException | DataAccessException e) {
                    failed++;
                    failuresByType.merge(e.getClass().getSimpleName(), 1, (current, increment) -> current + increment);
                    log.warn("Account purge: falha de uma conta, a passada continua (erro={})", e.getClass().getSimpleName());
                }
            }

            if (batch == maxBatchesPerRun - 1 && candidates.size() == batchSize) {
                limitReached = true;
            }
        }

        return new AccountPurgeRunResult(attempted.size(), purged, unchanged, notEligible, failed, failuresByType,
                false, limitReached);
    }
}
