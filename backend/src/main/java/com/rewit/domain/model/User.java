package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio representando a conta e identidade interna do usuário na plataforma.
 * Mantida estritamente desacoplada de JPA, Spring ou anotações de persistência.
 */
public class User {

    private final UUID id;
    private final String email;
    private String passwordHash;
    private final AuthProvider authProvider;
    private final String providerUserId;
    private AccountStatus status;
    private boolean isVerified;
    private Role role;
    private Instant deletedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(UUID id, String email, String passwordHash, AuthProvider authProvider, String providerUserId) {
        this.id = id != null ? id : UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.authProvider = authProvider != null ? authProvider : AuthProvider.LOCAL;
        this.providerUserId = providerUserId != null && !providerUserId.isBlank() ? providerUserId.trim() : null;
        this.status = AccountStatus.ACTIVE;
        this.isVerified = false;
        this.role = Role.USER;
        this.deletedAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private User(UUID id, String email, String passwordHash, AuthProvider authProvider,
                 String providerUserId, AccountStatus status, boolean isVerified, Role role,
                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.status = status;
        this.isVerified = isVerified;
        this.role = role != null ? role : Role.USER;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Normaliza e valida o formato básico do endereço de e-mail (lowercase e trim).
     */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BusinessException("E-mail inválido ou ausente", "INVALID_EMAIL");
        }
        return email.trim().toLowerCase();
    }

    /**
     * Reconstrói uma instância de User a partir da camada de persistência, com o estado do ciclo de vida.
     *
     * @throws IllegalArgumentException se {@code deletedAt} não corresponder ao estado ({@code DELETED} se e só se
     *                                  preenchido), a mesma invariante de {@code chk_users_status_consistency}
     */
    public static User rehydrate(UUID id, String email, String passwordHash, AuthProvider authProvider,
                                 String providerUserId, AccountStatus status, boolean isVerified, Role role,
                                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        if (id == null) {
            throw new BusinessException("O identificador do usuário é obrigatório", "MISSING_USER_ID");
        }
        Objects.requireNonNull(status, "status must not be null");
        if ((status == AccountStatus.DELETED) != (deletedAt != null)) {
            throw new IllegalArgumentException("deletedAt inconsistente com o estado da conta " + status);
        }
        return new User(id, normalizeEmail(email), passwordHash,
                authProvider != null ? authProvider : AuthProvider.LOCAL,
                providerUserId != null && !providerUserId.isBlank() ? providerUserId.trim() : null,
                status, isVerified, role != null ? role : Role.USER, deletedAt,
                createdAt != null ? createdAt : Instant.now(),
                updatedAt != null ? updatedAt : Instant.now());
    }

    /**
     * Reconstrói uma instância de User a partir da representação anterior ({@code isActive}/{@code deletedAt}),
     * convertida por {@link AccountStatus#fromLegacy}.
     */
    public static User rehydrate(UUID id, String email, String passwordHash, AuthProvider authProvider,
                                 String providerUserId, boolean isActive, boolean isVerified, Role role,
                                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        return rehydrate(id, email, passwordHash, authProvider, providerUserId,
                AccountStatus.fromLegacy(isActive, deletedAt), isVerified, role, deletedAt, createdAt, updatedAt);
    }

    /**
     * Reconstrói uma instância de User a partir da camada de persistência (retrocompatibilidade, default USER).
     */
    public static User rehydrate(UUID id, String email, String passwordHash, AuthProvider authProvider,
                                 String providerUserId, boolean isActive, boolean isVerified,
                                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        return rehydrate(id, email, passwordHash, authProvider, providerUserId, isActive, isVerified, Role.USER,
                deletedAt, createdAt, updatedAt);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ciclo de vida (C2). Cada transição devolve {@code true} se mudou o estado e {@code false} se a conta já estava
    // no estado de destino (idempotente); transição proibida lança 409 ACCOUNT_STATUS_TRANSITION_DENIED. Quem chama
    // decide o que o cliente vê: fluxos do próprio usuário nunca expõem esse código (o estado não pode vazar).
    // ---------------------------------------------------------------------------------------------------------

    /** Desativação pelo próprio usuário: somente a partir de {@code ACTIVE}. */
    public boolean deactivate() {
        return transition(AccountStatus.DEACTIVATED, AccountStatus.ACTIVE);
    }

    /** Reativação pelo próprio usuário: somente de {@code DEACTIVATED}; suspensão e exclusão não se desfazem assim. */
    public boolean reactivate() {
        return transition(AccountStatus.ACTIVE, AccountStatus.DEACTIVATED);
    }

    /**
     * Suspensão administrativa, também de uma conta {@code DEACTIVATED}: do contrário bastaria desativar a conta para
     * escapar da sanção e reativá-la depois.
     */
    public boolean suspend() {
        return transition(AccountStatus.SUSPENDED, AccountStatus.ACTIVE, AccountStatus.DEACTIVATED);
    }

    /** Reversão administrativa da suspensão: somente de {@code SUSPENDED}; nunca sobrepõe uma desativação do usuário. */
    public boolean reinstate() {
        return transition(AccountStatus.ACTIVE, AccountStatus.SUSPENDED);
    }

    /** Exclusão lógica definitiva, de qualquer estado não excluído. Não há transição que saia de {@code DELETED}. */
    public boolean softDelete() {
        boolean changed = transition(AccountStatus.DELETED,
                AccountStatus.ACTIVE, AccountStatus.DEACTIVATED, AccountStatus.SUSPENDED);
        if (changed) {
            this.deletedAt = this.updatedAt;
        }
        return changed;
    }

    private boolean transition(AccountStatus target, AccountStatus... allowedFrom) {
        if (this.status == target) {
            return false;
        }
        if (!Arrays.asList(allowedFrom).contains(this.status)) {
            throw new BusinessException("Transição de estado da conta não permitida", HttpStatus.CONFLICT,
                    "ACCOUNT_STATUS_TRANSITION_DENIED");
        }
        this.status = target;
        this.updatedAt = Instant.now();
        return true;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public boolean isDeleted() {
        return this.status == AccountStatus.DELETED;
    }

    /**
     * Conta apta a operar: a única interpretação do sistema é {@code status == ACTIVE}. {@code is_active} e
     * {@code deleted_at} são derivados do estado e o schema impede divergência (V17 e V21).
     */
    public boolean isOperational() {
        return this.status == AccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    /** Derivado do estado: equivale a {@link #isOperational()} (coluna {@code is_active}). */
    public boolean isActive() {
        return isOperational();
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        this.isVerified = verified;
        this.updatedAt = Instant.now();
    }

    public Role getRole() {
        return role != null ? role : Role.USER;
    }

    public void changeRole(Role newRole) {
        if (newRole == null) {
            throw new BusinessException("O papel (Role) do usuário é obrigatório", "MISSING_ROLE");
        }
        this.role = newRole;
        this.updatedAt = Instant.now();
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changePassword(String newPasswordHash) {
        if (this.authProvider != AuthProvider.LOCAL) {
            throw new BusinessException("Alteração de senha permitida apenas para contas locais", "LOCAL_AUTH_REQUIRED");
        }
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new BusinessException("O hash da nova senha é obrigatório", "INVALID_PASSWORD_HASH");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }
}
