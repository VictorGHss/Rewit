package com.rewit.application.port;

import com.rewit.application.dto.checkin.CheckInDtos.MyCheckInView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.CheckIn;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da entidade CheckIn (Step 12.0).
 */
public interface CheckInRepository {

    CheckIn save(CheckIn checkIn);

    Optional<CheckIn> findById(UUID id);

    Optional<CheckIn> findByReviewId(UUID reviewId);

    List<CheckIn> findByUserId(UUID userId);

    List<CheckIn> findByPlaceId(UUID placeId);

    boolean existsByReviewId(UUID reviewId);

    /**
     * Serializa, até o fim da transação corrente, as verificações de presença do mesmo usuário no mesmo local. Exige
     * transação ativa: a trava é liberada no commit ou no rollback, inclusive se o processo cair.
     */
    void lockPresenceVerification(UUID userId, UUID placeId);

    /** Se o usuário tem check-in VERIFIED no local com verificação posterior a {@code since}. */
    boolean existsVerifiedSince(UUID userId, UUID placeId, Instant since);

    /**
     * Check-ins VERIFIED do usuário cujas avaliações seguem ACTIVE e não anônimas (o mesmo conjunto contado em
     * {@code verifiedReviewsCount}), do mais recente ao mais antigo.
     */
    PageResult<MyCheckInView> findMyVerifiedCheckIns(UUID userId, int page, int size);
}
