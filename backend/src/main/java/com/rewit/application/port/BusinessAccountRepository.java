package com.rewit.application.port;

import com.rewit.domain.model.BusinessAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência de contas comerciais (business_accounts, C9).
 */
public interface BusinessAccountRepository {

    /** Grava e sincroniza com o banco, para que uma violação de unicidade (uq_business_tax_id) surja na chamada. */
    BusinessAccount save(BusinessAccount account);

    Optional<BusinessAccount> findById(UUID id);

    /** Trava a linha (FOR UPDATE) e recarrega o estado confirmado, mesmo se a conta já estiver carregada na transação. */
    Optional<BusinessAccount> findByIdForUpdate(UUID id);

    /** Contas administradas pelo usuário, mais antigas primeiro. */
    List<BusinessAccount> findByUserId(UUID userId);

    boolean existsByTaxId(String taxId);
}
