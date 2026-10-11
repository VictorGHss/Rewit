package com.rewit.application.service;

import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Autorização atual de quem age em nome de uma empresa (C11), revalidada a cada operação contra o banco: nada vem do
 * token além da identidade.
 *
 * <ol>
 *   <li>ator autenticado ({@code 401 UNAUTHORIZED}) e conta pessoal operacional ({@code 401 ACCOUNT_DISABLED});</li>
 *   <li>conta comercial existente e administrada pelo ator. Conta de outro usuário é indistinguível de conta
 *       inexistente: {@code 404 BUSINESS_ACCOUNT_NOT_FOUND}, como no C9;</li>
 *   <li>para novas manifestações, conta aprovada ({@code 409 BUSINESS_ACCOUNT_NOT_APPROVED}). Aprovação é interna:
 *       uma reivindicação aprovada pela moderação, não verificação jurídica externa;</li>
 *   <li>local associado à avaliação, {@code ACTIVE} e vinculado agora à conta
 *       ({@code 403 BUSINESS_NOT_RESPONSIBLE}, a mesma resposta para local inexistente, inativo, de outra empresa ou
 *       fora da avaliação).</li>
 * </ol>
 *
 * <p><b>Concorrência</b>: o vínculo {@code places.claimed_by_business_id} só é gravado pela decisão de reivindicação,
 * com a linha do local travada (FOR UPDATE). {@link #lockRepresentedPlace} trava o mesmo local antes de ler o vínculo,
 * então a publicação não usa um vínculo que mude antes do commit. A ordem é a do C9 (local antes de conta); a conta
 * é lida sem lock porque a aprovação só avança (PENDING para APPROVED) e nenhum fluxo a revoga.
 */
@Component
public class BusinessRepresentationPolicy {

    private final AccountStatusPolicy accountStatusPolicy;
    private final BusinessAccountRepository businessAccountRepository;
    private final PlaceRepository placeRepository;

    public BusinessRepresentationPolicy(AccountStatusPolicy accountStatusPolicy,
                                        BusinessAccountRepository businessAccountRepository,
                                        PlaceRepository placeRepository) {
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
    }

    /** Ator com conta pessoal operacional. */
    public void requireOperationalActor(UUID actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        accountStatusPolicy.requireOperational(actorUserId);
    }

    /**
     * Conta comercial administrada agora pelo ator, em qualquer situação de verificação. Basta para retirar o que a
     * conta já publicou.
     */
    public BusinessAccount requireAdministeredAccount(UUID actorUserId, UUID businessAccountId) {
        requireOperationalActor(actorUserId);
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_BUSINESS_ACCOUNT_ID");
        }
        return businessAccountRepository.findById(businessAccountId)
                .filter(account -> account.isAdministeredBy(actorUserId))
                .orElseThrow(BusinessRepresentationPolicy::businessAccountNotFound);
    }

    /** Conta administrada pelo ator e aprovada: exigência para novas manifestações comerciais. */
    public BusinessAccount requireApprovedAccount(UUID actorUserId, UUID businessAccountId) {
        BusinessAccount account = requireAdministeredAccount(actorUserId, businessAccountId);
        if (account.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new BusinessException("A conta comercial ainda não está aprovada para responder", HttpStatus.CONFLICT,
                    "BUSINESS_ACCOUNT_NOT_APPROVED");
        }
        return account;
    }

    /**
     * Trava (FOR UPDATE) e devolve o local que a conta representa na avaliação. Exige transação.
     *
     * @param reviewPlaceIds locais associados à avaliação; o local precisa ser um deles, escolhido pelo chamador e
     *                       nunca deduzido aqui
     */
    public Place lockRepresentedPlace(BusinessAccount account, UUID placeId, Collection<UUID> reviewPlaceIds) {
        Objects.requireNonNull(account, "account must not be null");
        if (placeId == null) {
            throw new BusinessException("O local representado é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (reviewPlaceIds == null || !reviewPlaceIds.contains(placeId)) {
            throw notResponsible();
        }
        return placeRepository.findByIdForUpdate(placeId)
                .filter(place -> represents(account, place))
                .orElseThrow(BusinessRepresentationPolicy::notResponsible);
    }

    /** O local está ativo e vinculado agora à conta. Não verifica a aprovação da conta nem a avaliação. */
    public static boolean represents(BusinessAccount account, Place place) {
        return account != null && place != null
                && CatalogService.PUBLIC_CATALOG_STATUS.equals(place.getStatus())
                && account.getId().equals(place.getClaimedByBusinessId());
    }

    public static BusinessException businessAccountNotFound() {
        return new BusinessException("Conta comercial não encontrada", HttpStatus.NOT_FOUND, "BUSINESS_ACCOUNT_NOT_FOUND");
    }

    public static BusinessException notResponsible() {
        return new BusinessException("A conta comercial não responde por este local nesta avaliação",
                HttpStatus.FORBIDDEN, "BUSINESS_NOT_RESPONSIBLE");
    }
}
