package com.rewit.presentation.dto.notification;

/**
 * DTO de resposta HTTP para quantidade de notificações não lidas (Step 22.0).
 */
public record UnreadCountResponse(
        long count
) {}
