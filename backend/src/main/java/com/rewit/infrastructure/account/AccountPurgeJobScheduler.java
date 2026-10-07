package com.rewit.infrastructure.account;

import com.rewit.application.usecase.PurgeEligibleAccountsUseCase;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase.AccountPurgeRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Job @Scheduled fino do purge de contas excluídas (C2.3), uma passada por dia: apenas dispara
 * {@link PurgeEligibleAccountsUseCase} e registra métricas e um log agregado. Sem regra de elegibilidade, sem lock e
 * sem transação próprios: cada conta é purgada na transação do caso de uso, com o lock da conta.
 *
 * <p>Uma falha que interrompe a passada (erro de programação) é registrada pela classe, sem mensagem nem dados de
 * conta; o que já foi purgado permanece e o próximo ciclo tenta novamente.
 */
@Component
@ConditionalOnProperty(prefix = "rewit.account.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountPurgeJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeJobScheduler.class);

    private final PurgeEligibleAccountsUseCase purgeEligibleAccountsUseCase;
    private final AccountPurgeJobMetrics metrics;

    public AccountPurgeJobScheduler(PurgeEligibleAccountsUseCase purgeEligibleAccountsUseCase,
                                    AccountPurgeJobMetrics metrics) {
        this.purgeEligibleAccountsUseCase =
                Objects.requireNonNull(purgeEligibleAccountsUseCase, "PurgeEligibleAccountsUseCase must not be null");
        this.metrics = Objects.requireNonNull(metrics, "AccountPurgeJobMetrics must not be null");
    }

    @Scheduled(
            fixedDelayString = "${rewit.account.purge.interval-ms:86400000}",
            initialDelayString = "${rewit.account.purge.initial-delay-ms:600000}"
    )
    public void purgeDeletedAccounts() {
        Instant start = Instant.now();
        log.info("Account purge: passada iniciada");
        try {
            AccountPurgeRunResult result = purgeEligibleAccountsUseCase.run(start);
            Duration duration = Duration.between(start, Instant.now());
            metrics.recordRun(result, duration);
            log.info("Account purge: passada concluída, candidatas={}, purgadas={}, semAlteracao={}, naoElegiveis={}, "
                            + "falhas={}, falhasPorTipo={}, bloqueadaSemSegredo={}, limiteAtingido={}, duracaoMs={}",
                    result.candidates(), result.purged(), result.unchanged(), result.notEligible(), result.failed(),
                    result.failuresByType(), result.secretMissing(), result.limitReached(), duration.toMillis());
        } catch (RuntimeException e) {
            metrics.recordFailure(Duration.between(start, Instant.now()));
            log.error("Account purge: passada interrompida; contas já purgadas permanecem e o próximo ciclo tenta "
                    + "novamente (erro={})", e.getClass().getSimpleName());
        }
    }
}
