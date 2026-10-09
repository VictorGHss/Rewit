package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BusinessAccount;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Abre uma conta comercial para o usuário autenticado (C9): sempre vinculada ao ator, {@code PENDING} e no plano
 * {@code FREE}. Documento fiscal duplicado responde 409 BUSINESS_TAX_ID_ALREADY_EXISTS, inclusive na corrida em que
 * a unicidade só é detectada pelo banco (uq_business_tax_id). Nenhum dado comercial vai para log.
 */
@Service
public class CreateBusinessAccountUseCase {

    private static final String TAX_ID_UNIQUE_CONSTRAINT = "uq_business_tax_id";

    private final BusinessAccountRepository businessAccountRepository;
    private final AccountStatusPolicy accountStatusPolicy;

    public CreateBusinessAccountUseCase(BusinessAccountRepository businessAccountRepository,
                                        AccountStatusPolicy accountStatusPolicy) {
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
    }

    @Transactional
    public BusinessAccountView execute(UUID actorUserId, String corporateName, String taxId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        // Valida e normaliza antes de qualquer I/O
        BusinessAccount account = BusinessAccount.open(actorUserId, corporateName, taxId, Instant.now());

        accountStatusPolicy.requireOperational(actorUserId);

        if (businessAccountRepository.existsByTaxId(account.getTaxId())) {
            throw taxIdAlreadyExists();
        }
        try {
            return BusinessAccountView.fromDomain(businessAccountRepository.save(account));
        } catch (DataIntegrityViolationException ex) {
            if (violates(ex, TAX_ID_UNIQUE_CONSTRAINT)) {
                throw taxIdAlreadyExists();
            }
            throw ex;
        }
    }

    private static BusinessException taxIdAlreadyExists() {
        return new BusinessException("Documento fiscal já cadastrado em outra conta comercial", HttpStatus.CONFLICT,
                "BUSINESS_TAX_ID_ALREADY_EXISTS");
    }

    static boolean violates(DataIntegrityViolationException ex, String constraint) {
        Throwable root = ex.getMostSpecificCause();
        String message = root.getMessage();
        return message != null && message.contains(constraint);
    }
}
