package com.rewit.application.usecase;

import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A empresa retira a própria resposta oficial (C11): remoção lógica com origem BUSINESS; o registro fica como
 * histórico e a conta pode publicar outra.
 *
 * <p>Exige só o ator operacional e administrador atual da conta autora. Não exige conta aprovada nem vínculo vigente
 * com o local: retirar o que a conta já publicou não cria manifestação nova. Conta de outro administrador responde
 * {@code 403 BUSINESS_NOT_RESPONSIBLE} (a resposta já é pública com a empresa autora; não há o que ocultar).
 * Resposta já removida, por quem for, responde {@code 409 BUSINESS_RESPONSE_ALREADY_REMOVED}.
 *
 * <p>Depois da autorização, a resposta é travada (FOR UPDATE) e relida antes da transição: remoções concorrentes,
 * inclusive da futura moderação, se serializam e a segunda vê o estado confirmado.
 */
@Service
public class RemoveBusinessResponseUseCase {

    private final BusinessRepresentationPolicy representationPolicy;
    private final BusinessResponseRepository businessResponseRepository;
    private final BusinessAccountRepository businessAccountRepository;

    public RemoveBusinessResponseUseCase(BusinessRepresentationPolicy representationPolicy,
                                         BusinessResponseRepository businessResponseRepository,
                                         BusinessAccountRepository businessAccountRepository) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
    }

    @Transactional
    public void execute(UUID actorUserId, UUID responseId) {
        execute(actorUserId, responseId, Instant.now());
    }

    @Transactional
    public void execute(UUID actorUserId, UUID responseId, Instant now) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (responseId == null) {
            throw new BusinessException("A resposta é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_RESPONSE_ID");
        }
        representationPolicy.requireOperationalActor(actorUserId);

        // Autoriza antes de travar: quem não administra a conta autora não chega a segurar a linha
        BusinessResponse unlocked = businessResponseRepository.findById(responseId)
                .orElseThrow(RemoveBusinessResponseUseCase::responseNotFound);
        boolean administersAuthor = businessAccountRepository.findById(unlocked.getBusinessAccountId())
                .map(account -> account.isAdministeredBy(actorUserId))
                .orElse(false);
        if (!administersAuthor) {
            throw BusinessRepresentationPolicy.notResponsible();
        }

        // A conta autora é imutável na resposta; o lock só recarrega o estado confirmado para a transição
        BusinessResponse response = businessResponseRepository.findByIdForUpdate(responseId)
                .orElseThrow(RemoveBusinessResponseUseCase::responseNotFound);
        response.removeByBusiness(now != null ? now : Instant.now());
        businessResponseRepository.save(response);
    }

    private static BusinessException responseNotFound() {
        return new BusinessException("Resposta oficial não encontrada", HttpStatus.NOT_FOUND, "BUSINESS_RESPONSE_NOT_FOUND");
    }
}
