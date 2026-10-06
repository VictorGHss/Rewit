package com.rewit.application.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rewit.application.dto.reputation.ReputationDtos;
import com.rewit.application.port.ReputationRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserReputation;

/**
 * Serviço de aplicação para o subsistema de Reputação V1 (Step 23.0 / Step 23.1).
 *
 * <p><b>Responsabilidade</b>: orquestrar o cálculo, atualização atômica e consulta do snapshot de reputação.
 *
 * <p><b>Estratégia de atualização transacional síncrona</b>:
 * Eventos que alteram sinais (Review criada, Helpful recebido/removido, moderação UNDER_REVIEW)
 * invocam {@link #recalculateAndSave(UUID)} dentro da mesma transação, sob lock pessimista.
 * O snapshot em banco permanece sempre consistente e atômico com os fatos.
 *
 * <p><b>Consulta O(1) com inicialização sob demanda</b>:
 * Ao consultar {@link #getReputation(UUID)}, caso o snapshot já exista, retorna-o diretamente em O(1).
 * Caso ainda não exista para um usuário ativo, computa o estado inicial e persiste-o transacionalmente.
 * Usuários inativos ou inexistentes retornam 404 (USER_NOT_FOUND) sem criar snapshot.
 */
@Service
public class ReputationService {

    private final UserRepository userRepository;
    private final ReputationRepository reputationRepository;
    private final ReputationCalculator calculator;

    public ReputationService(
            UserRepository userRepository,
            ReputationRepository reputationRepository,
            ReputationCalculator calculator
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "reputationRepository must not be null");
        this.calculator = Objects.requireNonNull(calculator, "calculator must not be null");
    }

    /**
     * Recalcula os sinais de reputação e atualiza o snapshot persistido.
     * Executa dentro da transação corrente sob lock pessimista para serializar concorrência.
     *
     * <p>Caso o usuário não exista no sistema, lança 404 (USER_NOT_FOUND).
     * Caso o usuário exista, mas esteja inativo ou excluído (soft-deleted), a operação é tratada
     * de acordo com a semântica do modelo: reputação para usuário inativo é sem significado prático
     * (não possui presença pública nem gera snapshot conforme ADR-008), retornando {@code null}
     * sem lançar exceção nem bloquear operações legítimas sobre avaliações existentes de sua autoria.
     *
     * @param targetUserId UUID do usuário cujo snapshot será recalculado
     * @return snapshot atualizado, ou {@code null} caso o usuário esteja inativo/excluído
     */
    @Transactional
    public UserReputation recalculateAndSave(UUID targetUserId) {
        Objects.requireNonNull(targetUserId, "targetUserId cannot be null");

        User user = userRepository.findByIdIncludingDeleted(targetUserId)
            .orElseThrow(() -> new BusinessException("Usuario nao encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (!user.isActive() || user.isDeleted()) {
            return null;
        }

        // 1. Garante que a linha exista previamente no banco (idempotente)
        reputationRepository.insertInitialRowIfNotExists(targetUserId);

        // 2. Adquire lock pessimista exclusivo (SELECT ... FOR UPDATE) na linha do usuário
        reputationRepository.findByUserIdForUpdate(targetUserId)
            .orElseThrow(() -> new IllegalStateException("Falha ao adquirir lock para reputacao do usuario: " + targetUserId));

        // 3. Recalcula os sinais determinísticos a partir dos fatos confirmados
        UserReputation calculated = calculator.calculate(targetUserId);

        // 4. Persiste o snapshot atualizado
        return reputationRepository.save(calculated);
    }

    /**
     * Retorna a reputação pública do usuário informado.
     *
     * <p>Caso já exista snapshot persistido, retorna em O(1).
     * Caso o usuário seja ativo e ainda não possua snapshot, computa e persiste o snapshot inicial.
     * Usuários inativos ou inexistentes retornam 404 (USER_NOT_FOUND).
     *
     * @param targetUserId UUID do usuário cujos sinais serão retornados
     * @return view pública de reputação (sem score, sem PII)
     */
    @Transactional
    public ReputationDtos.ReputationView getReputation(UUID targetUserId) {
        Objects.requireNonNull(targetUserId, "targetUserId cannot be null");

        User user = userRepository.findByIdIncludingDeleted(targetUserId)
            .orElseThrow(() -> new BusinessException(
                "Usuario nao encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (!user.isActive() || user.isDeleted()) {
            throw new BusinessException(
                "Usuario nao encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        UserReputation reputation = reputationRepository.findByUserId(targetUserId)
            .orElseGet(() -> recalculateAndSave(targetUserId));

        return ReputationDtos.ReputationView.fromDomain(reputation);
    }
}
