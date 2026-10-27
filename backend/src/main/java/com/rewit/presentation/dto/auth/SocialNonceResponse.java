package com.rewit.presentation.dto.auth;

/**
 * Nonce de uso único do login social. O app entrega ao provedor o SHA-256 (hex minúsculo) deste valor e o
 * reenvia em claro ao Rewit junto com o ID token.
 */
public record SocialNonceResponse(
        String nonce,
        long expiresIn
) {}
