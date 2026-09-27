package com.rewit.infrastructure.security;

import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Adaptador de segurança que integra UserRepository com o Spring Security UserDetailsService.
 * Garante que contas federadas ou soft-deleted não autentiquem com senha local.
 */
@Service
public class RewitUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public RewitUserDetailsService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Credenciais inválidas");
        }
        String normalizedEmail = username.trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));

        if (user.isDeleted() || !user.isActive()) {
            throw new UsernameNotFoundException("Credenciais inválidas");
        }

        if (user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
            throw new UsernameNotFoundException("Credenciais inválidas");
        }

        return RewitUserPrincipal.fromUser(user);
    }
}
