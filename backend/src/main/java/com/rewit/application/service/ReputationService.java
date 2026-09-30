package com.rewit.application.service;

import com.rewit.application.dto.reputation.ReputationDtos;
import com.rewit.application.port.ReputationRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserReputation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

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

    @Autowired
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
     * @param targetUserId UUID do usuário cujo snapshot será recalculado
     * @return snapshot atualizado
     */
    @Transactional
    public UserReputation recalculateAndSave(UUID targetUserId) {
        Objects.requireNonNull(targetUserId, "targetUserId cannot be null");

        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new BusinessException("Usuario nao encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (!user.isActive() || user.isDeleted()) {
            throw new BusinessException("Usuario nao encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
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

        User user = userRepository.findById(targetUserId)
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
