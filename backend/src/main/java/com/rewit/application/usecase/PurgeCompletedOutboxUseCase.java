package com.rewit.application.usecase;

import com.rewit.application.port.OutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Executa uma passada do purge de retenção do Outbox (Step 27.4, Partes I/J/K).
 *
 * <p>Decisão de retenção V1: apenas mensagens COMPLETED antigas são removidas —
 * a Notification in-app é a fonte de verdade do histórico do usuário e não há
 * requisito de auditoria sobre as linhas do Outbox. FAILED permanece terminal e
 * nunca é removido automaticamente (visibilidade operacional); PENDING e
 * PROCESSING também são intocáveis.
 *
 * <p>O corte é calculado sobre updated_at (mantido por toda mutação de estado),
 * então mensagens finalizadas há mais de {@code retention} são elegíveis e
 * registros recém-atualizados nunca são apagados. A remoção é limitada por lote:
 * uma execução remove no máximo {@code batchSize} linhas — a próxima passada
 * (agendada) continua o trabalho, mantendo cada DELETE curto.
 *
 * <p>Classe deliberadamente livre de Spring (sem @Service, sem @Transactional,
 * sem @Scheduled): é montada pela configuração de infraestrutura e pode ser
 * executada manualmente ou por qualquer agendador. O @Scheduled fino que a
 * invoca vive em {@code OutboxPurgeScheduler}.
 */
public class PurgeCompletedOutboxUseCase {

    private final OutboxRepository outboxRepository;
    private final Duration retention;
    private final int batchSize;

    public PurgeCompletedOutboxUseCase(OutboxRepository outboxRepository, Duration retention, int batchSize) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /**
     * Executa uma passada de purge: remove até {@code batchSize} mensagens
     * COMPLETED com updated_at anterior a {@code now - retention}.
     *
     * @param now instante de referência para o cálculo do corte
     * @return quantidade efetivamente removida nesta passada
     */
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Instant cutoff = now.minus(retention);
        return outboxRepository.purgeCompletedBefore(cutoff, batchSize);
    }
}
