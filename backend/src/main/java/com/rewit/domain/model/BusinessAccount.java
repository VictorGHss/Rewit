package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Entidade de Domínio representando contas empresariais vinculadas a estabelecimentos físicos.
 * Sem dependência de módulos de pagamento ou assinatura prematura (Seção 29).
 *
 * <p>C9: a conta nasce {@code PENDING} no plano {@code FREE} e passa a {@code APPROVED} na primeira reivindicação
 * de local aprovada. Razão social com espaços normalizados; documento fiscal na forma canônica (letras maiúsculas e
 * dígitos, sem máscara), para que a unicidade do banco (uq_business_tax_id) não dependa da formatação digitada.
 */
public class BusinessAccount {

    public static final String DEFAULT_PLAN_TIER = "FREE";
    private static final int MIN_CORPORATE_NAME_LENGTH = 2;
    private static final int MAX_CORPORATE_NAME_LENGTH = 255;
    private static final int MIN_TAX_ID_LENGTH = 8;
    private static final int MAX_TAX_ID_LENGTH = 32;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    private final UUID id;
    private final UUID userId;
    private final String corporateName;
    private final String taxId;
    private VerificationStatus verificationStatus;
    private String planTier;
    private final Instant createdAt;
    private Instant updatedAt;

    public BusinessAccount(UUID id, UUID userId, String corporateName, String taxId,
                           VerificationStatus verificationStatus, String planTier) {
        this(id, userId, corporateName, taxId, verificationStatus, planTier, Instant.now(), Instant.now());
    }

    /** Reconstituição fiel a partir da persistência, com as datas gravadas. */
    public BusinessAccount(UUID id, UUID userId, String corporateName, String taxId,
                           VerificationStatus verificationStatus, String planTier, Instant createdAt, Instant updatedAt) {
        if (userId == null) {
            throw new BusinessException("O usuário administrador da conta comercial é obrigatório", "MISSING_USER_ID");
        }
        if (corporateName == null || corporateName.isBlank()) {
            throw new BusinessException("A razão social é obrigatória", "MISSING_CORPORATE_NAME");
        }
        if (taxId == null || taxId.isBlank()) {
            throw new BusinessException("O documento fiscal (CNPJ/Tax ID) é obrigatório", "MISSING_TAX_ID");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.corporateName = corporateName.trim();
        this.taxId = taxId.trim();
        this.verificationStatus = verificationStatus != null ? verificationStatus : VerificationStatus.PENDING;
        this.planTier = planTier != null ? planTier : DEFAULT_PLAN_TIER;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    /**
     * Nova conta do usuário: {@code PENDING} e plano {@code FREE}, que o cliente não escolhe. Valida e normaliza a
     * razão social (espaços colapsados, 2 a 255 caracteres) e o documento fiscal (forma canônica, 8 a 32).
     */
    public static BusinessAccount open(UUID userId, String corporateName, String taxId, Instant now) {
        return new BusinessAccount(UUID.randomUUID(), userId, normalizeCorporateName(corporateName), normalizeTaxId(taxId),
                VerificationStatus.PENDING, DEFAULT_PLAN_TIER, now, now);
    }

    public static String normalizeCorporateName(String corporateName) {
        String normalized = corporateName == null ? "" : WHITESPACE.matcher(corporateName.trim()).replaceAll(" ");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_CORPORATE_NAME_LENGTH || length > MAX_CORPORATE_NAME_LENGTH) {
            throw new BusinessException("A razão social deve ter entre 2 e 255 caracteres", HttpStatus.BAD_REQUEST,
                    "INVALID_CORPORATE_NAME");
        }
        return normalized;
    }

    /** Remove máscara e espaços ("12.345.678/0001-90" e "12345678000190" são o mesmo documento). */
    public static String normalizeTaxId(String taxId) {
        String canonical = taxId == null ? "" : NOT_ALPHANUMERIC.matcher(taxId.toUpperCase(Locale.ROOT)).replaceAll("");
        if (canonical.length() < MIN_TAX_ID_LENGTH || canonical.length() > MAX_TAX_ID_LENGTH) {
            throw new BusinessException("O documento fiscal deve ter entre 8 e 32 letras ou dígitos", HttpStatus.BAD_REQUEST,
                    "INVALID_TAX_ID");
        }
        return canonical;
    }

    public boolean isAdministeredBy(UUID userId) {
        return userId != null && userId.equals(this.userId);
    }

    public boolean isRejected() {
        return verificationStatus == VerificationStatus.REJECTED;
    }

    /**
     * Verificação pela primeira reivindicação aprovada. Já aprovada: nada muda.
     *
     * @return se o estado mudou e precisa ser gravado
     */
    public boolean approveVerification(Instant now) {
        if (verificationStatus == VerificationStatus.APPROVED) {
            return false;
        }
        this.verificationStatus = VerificationStatus.APPROVED;
        this.updatedAt = now;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCorporateName() {
        return corporateName;
    }

    public String getTaxId() {
        return taxId;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public String getPlanTier() {
        return planTier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
