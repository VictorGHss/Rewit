package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contas comerciais administradas pelo usuário autenticado (C9), mais antigas primeiro. Só as próprias.
 */
@Service
public class ListMyBusinessAccountsUseCase {

    private final BusinessAccountRepository businessAccountRepository;

    public ListMyBusinessAccountsUseCase(BusinessAccountRepository businessAccountRepository) {
        this.businessAccountRepository = Objects.requireNonNull(businessAccountRepository,
                "BusinessAccountRepository must not be null");
    }

    @Transactional(readOnly = true)
    public List<BusinessAccountView> execute(UUID actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return businessAccountRepository.findByUserId(actorUserId).stream()
                .map(account -> BusinessAccountView.fromDomain(account))
                .toList();
    }
}
