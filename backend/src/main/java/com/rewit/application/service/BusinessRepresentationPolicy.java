package com.rewit.application.service;

import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
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
 * <p><b>Elegibilidade da representação</b> (decisão B): publicação, restauração e leitura pública usam os mesmos três
 * critérios, avaliados por {@link #evaluate}: conta aprovada, administrador atual da conta operacional e local ativo
 * vinculado à conta. Quem publica é o próprio administrador, então a publicação já os exige pelos passos acima. A
 * restauração e as leituras não têm um ator comercial: {@link #currentIneligibility} avalia a representação sem ator,
 * e as leituras públicas repetem os mesmos predicados em SQL (paridade coberta por teste de persistência).
 *
 * <p><b>Concorrência</b>: o vínculo {@code places.claimed_by_business_id} só é gravado pela decisão de reivindicação,
 * com a linha do local travada (FOR UPDATE). {@link #lockRepresentedPlace} trava o mesmo local antes de ler o vínculo,
 * então a publicação não usa um vínculo que mude antes do commit. A ordem é a do C9 (local antes de conta); a conta
 * é lida sem lock porque a aprovação só avança (PENDING para APPROVED) e nenhum fluxo a revoga.
 * {@link #currentIneligibility} não trava nada: quem a usa depois de travar a resposta (restauração) não pode travar o
 * local, que a publicação trava antes de gravar a resposta; a leitura pública, que filtra a cada consulta, é a
 * garantia final de visibilidade.
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
        if (!isApproved(account)) {
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

    /**
     * Motivos de inelegibilidade atuais da representação (conta e local de uma resposta), sem ator e sem lock. Conta
     * inexistente reúne os três motivos; local inexistente conta como não representado.
     */
    public Set<BusinessRepresentationIneligibility> currentIneligibility(UUID businessAccountId, UUID placeId) {
        BusinessAccount account = businessAccountId == null ? null
                : businessAccountRepository.findById(businessAccountId).orElse(null);
        boolean administratorOperational = account != null && accountStatusPolicy.isOperational(account.getUserId());
        Place place = placeId == null ? null : placeRepository.findById(placeId).orElse(null);
        return evaluate(account, administratorOperational, place);
    }

    /** {@link #evaluate(boolean, boolean, boolean)} a partir da conta e do local carregados. */
    public static Set<BusinessRepresentationIneligibility> evaluate(BusinessAccount account,
                                                                    boolean administratorOperational, Place place) {
        return evaluate(isApproved(account), administratorOperational, represents(account, place));
    }

    /**
     * Regra da elegibilidade: cada critério falso é um motivo; conjunto vazio é representação elegível. Recebe os
     * critérios já resolvidos para servir tanto aos objetos carregados quanto às colunas calculadas em SQL.
     */
    public static Set<BusinessRepresentationIneligibility> evaluate(boolean accountApproved,
                                                                    boolean administratorOperational,
                                                                    boolean placeRepresented) {
        Set<BusinessRepresentationIneligibility> reasons = EnumSet.noneOf(BusinessRepresentationIneligibility.class);
        if (!accountApproved) {
            reasons.add(BusinessRepresentationIneligibility.ACCOUNT_NOT_APPROVED);
        }
        if (!administratorOperational) {
            reasons.add(BusinessRepresentationIneligibility.ADMINISTRATOR_NOT_OPERATIONAL);
        }
        if (!placeRepresented) {
            reasons.add(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED);
        }
        return Collections.unmodifiableSet(reasons);
    }

    /** Conta aprovada: exigência para novas manifestações e para a elegibilidade da representação. */
    public static boolean isApproved(BusinessAccount account) {
        return account != null && account.getVerificationStatus() == VerificationStatus.APPROVED;
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
