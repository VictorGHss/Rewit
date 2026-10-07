package com.rewit.application.service;

import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Exige que a conta que inicia uma mutação ainda possa operar.
 *
 * <p>O JWT é verificado só pelas claims e pode ter sido emitido antes de a conta ficar inativa ou ser excluída;
 * por isso o estado atual é lido do banco no início da transação da mutação, antes de qualquer lock ou escrita.
 * A leitura não usa lock: uma desativação que confirme depois dela é ordenada após a mutação, como qualquer
 * outra escrita concorrente. Leituras públicas não passam por aqui.
 *
 * <p>Falha com o contrato já usado por autenticação e perfil: {@code 401 ACCOUNT_DISABLED}, sem indicar se a
 * conta está inativa, excluída ou não existe.
 */
@Component
public class AccountStatusPolicy {

    private final UserRepository userRepository;

    public AccountStatusPolicy(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
    }

    /**
     * @param actorUserId usuário autenticado que inicia a mutação
     * @throws BusinessException {@code 401 ACCOUNT_DISABLED} se a conta não existir, estiver inativa ou excluída
     */
    public void requireOperational(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        boolean operational = userRepository.findById(actorUserId)
                .map(User::isOperational)
                .orElse(false);
        if (!operational) {
            throw accountDisabled();
        }
    }

    /**
     * A mesma resposta para conta inexistente, desativada, suspensa ou excluída: o estado não pode ser inferido.
     */
    public static BusinessException accountDisabled() {
        return new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED,
                "ACCOUNT_DISABLED");
    }
}
