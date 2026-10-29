package com.rewit.application.usecase;

import com.rewit.application.dto.checkin.CheckInDtos.MyCheckInView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CheckInRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Histórico de check-ins verificados do usuário autenticado.
 *
 * <p>O usuário vem somente do token; conta inoperante responde {@code 401 ACCOUNT_DISABLED}, como {@code GET /me}.
 * Entram só check-ins VERIFIED de avaliações ACTIVE e não anônimas: uma avaliação removida, em moderação ou anônima
 * tira o check-in do histórico, como já o tira de {@code verifiedReviewsCount}. Paginação igual à de
 * {@code GET /me/reviews}.
 */
@Service
public class ListMyCheckInsUseCase {

    static final int MAX_PAGE_SIZE = 50;

    private final AccountStatusPolicy accountStatusPolicy;
    private final CheckInRepository checkInRepository;

    public ListMyCheckInsUseCase(AccountStatusPolicy accountStatusPolicy, CheckInRepository checkInRepository) {
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.checkInRepository = Objects.requireNonNull(checkInRepository, "CheckInRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<MyCheckInView> execute(UUID actorUserId, int page, int size) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size <= 0) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página não pode ser superior a " + MAX_PAGE_SIZE,
                    HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }
        accountStatusPolicy.requireOperational(actorUserId);
        return checkInRepository.findMyVerifiedCheckIns(actorUserId, page, size);
    }
}
