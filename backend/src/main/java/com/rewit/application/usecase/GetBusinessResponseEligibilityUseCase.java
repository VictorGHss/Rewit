package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseEligibilityView;
import com.rewit.application.dto.business.BusinessResponseDtos.EligibleBusinessAccount;
import com.rewit.application.dto.business.BusinessResponseDtos.EligiblePlace;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Contas do usuário autenticado que podem responder oficialmente à avaliação (C11), com os locais que cada uma pode
 * representar nela, para o app oferecer a escolha explícita do local.
 *
 * <p>Só aparecem contas administradas pelo próprio ator, aprovadas, que controlam agora um local ativo associado à
 * avaliação. Reivindicação pendente, rejeitada ou inexistente não habilita. Nunca expõe o documento da empresa nem
 * contas de terceiros. Não cria nada e não consome o limite de criação de respostas.
 *
 * <p>É uma leitura sem lock: a publicação revalida tudo com o local travado.
 */
@Service
public class GetBusinessResponseEligibilityUseCase {

    private static final Comparator<Place> PLACE_ORDER = Comparator
            .comparing((Place place) -> place.getName() == null ? "" : place.getName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(place -> place.getId());

    private final BusinessRepresentationPolicy representationPolicy;
    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final BusinessAccountRepository businessAccountRepository;
    private final PlaceRepository placeRepository;
    private final BusinessResponseRepository businessResponseRepository;

    public GetBusinessResponseEligibilityUseCase(BusinessRepresentationPolicy representationPolicy,
                                                 ReviewRepository reviewRepository,
                                                 ReviewVisibilityPolicy reviewVisibilityPolicy,
                                                 BusinessAccountRepository businessAccountRepository,
                                                 PlaceRepository placeRepository,
                                                 BusinessResponseRepository businessResponseRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy,
                "ReviewVisibilityPolicy must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
    }

    @Transactional(readOnly = true)
    public BusinessResponseEligibilityView execute(UUID actorUserId, UUID reviewId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        representationPolicy.requireOperationalActor(actorUserId);

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(PublishReviewBusinessResponseUseCase::reviewNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, actorUserId);
        if (!PublishReviewBusinessResponseUseCase.isRespondable(review)) {
            return new BusinessResponseEligibilityView(reviewId, false, List.of());
        }

        List<BusinessAccount> approved = businessAccountRepository.findByUserId(actorUserId).stream()
                .filter(account -> account.getVerificationStatus() == VerificationStatus.APPROVED)
                .toList();
        if (approved.isEmpty()) {
            return new BusinessResponseEligibilityView(reviewId, true, List.of());
        }

        List<Place> reviewPlaces = reviewRepository.findAssociatedPlaceIds(reviewId).stream()
                .map(placeId -> placeRepository.findById(placeId))
                .flatMap(place -> place.stream())
                .sorted(PLACE_ORDER)
                .toList();

        List<EligibleBusinessAccount> accounts = new ArrayList<>();
        for (BusinessAccount account : approved) {
            List<EligiblePlace> places = reviewPlaces.stream()
                    .filter(place -> BusinessRepresentationPolicy.represents(account, place))
                    .map(place -> new EligiblePlace(place.getId(), place.getName()))
                    .toList();
            if (places.isEmpty()) {
                continue;
            }
            Optional<BusinessResponse> active = businessResponseRepository.findActive(reviewId, null, account.getId());
            UUID activeResponseId = active.map(response -> response.getId()).orElse(null);
            accounts.add(new EligibleBusinessAccount(account.getId(), account.getCorporateName(), places,
                    activeResponseId, activeResponseId == null));
        }
        return new BusinessResponseEligibilityView(reviewId, true, List.copyOf(accounts));
    }
}
