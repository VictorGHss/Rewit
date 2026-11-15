package com.rewit.application.usecase;

import com.rewit.application.dto.user.UserInterestDtos.UserInterestProfileView;
import com.rewit.application.port.UserInterestProfileRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Interesses e situação do questionário inicial do usuário autenticado.
 *
 * <p>O usuário vem somente do token; conta inoperante responde {@code 401 ACCOUNT_DISABLED}, como {@code GET /me}.
 * Sem respostas gravadas, a situação é {@code NOT_STARTED}.
 */
@Service
public class GetMyInterestsUseCase {

    private final AccountStatusPolicy accountStatusPolicy;
    private final UserInterestProfileRepository userInterestProfileRepository;

    public GetMyInterestsUseCase(AccountStatusPolicy accountStatusPolicy,
                                 UserInterestProfileRepository userInterestProfileRepository) {
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.userInterestProfileRepository = Objects.requireNonNull(userInterestProfileRepository,
                "UserInterestProfileRepository must not be null");
    }

    @Transactional(readOnly = true)
    public UserInterestProfileView execute(UUID actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        accountStatusPolicy.requireOperational(actorUserId);
        return userInterestProfileRepository.findByUserId(actorUserId)
                .map(UserInterestProfileView::fromDomain)
                .orElseGet(UserInterestProfileView::notStarted);
    }
}
