package com.rewit.infrastructure.security;

import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador de infraestrutura representando o usuário autenticado no Spring Security.
 * Mantém o modelo de domínio User completamente desacoplado de UserDetails.
 */
public class RewitUserPrincipal implements UserDetails, Principal {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean active;
    private final Collection<? extends GrantedAuthority> authorities;

    public RewitUserPrincipal(UUID id, String email, String passwordHash, boolean active, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        Role effectiveRole = role != null ? role : Role.USER;
        this.authorities = List.of(new SimpleGrantedAuthority(effectiveRole.getAuthority()));
    }

    public RewitUserPrincipal(UUID id, String email, String passwordHash, boolean active) {
        this(id, email, passwordHash, active, Role.USER);
    }

    public static RewitUserPrincipal fromUser(User user) {
        return new RewitUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isActive() && !user.isDeleted(),
                user.getRole()
        );
    }

    @Override
    public String getName() {
        return id != null ? id.toString() : null;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
