package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.SavedItemType;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um item salvo/favoritado pelo usuário (polimórfico).
 */
public class SavedItem {

    private final UUID id;
    private final UUID userId;
    private final UUID targetId;
    private final SavedItemType itemType;
    private final String folderName;
    private final Instant createdAt;

    public SavedItem(UUID id, UUID userId, UUID targetId, SavedItemType itemType, String folderName) {
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (targetId == null) {
            throw new BusinessException("O item salvo é obrigatório", "MISSING_TARGET_ID");
        }
        if (itemType == null) {
            throw new BusinessException("O tipo de item salvo é obrigatório", "MISSING_ITEM_TYPE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.targetId = targetId;
        this.itemType = itemType;
        this.folderName = folderName != null && !folderName.isBlank() ? folderName.trim() : "Geral";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public SavedItemType getItemType() {
        return itemType;
    }

    public String getFolderName() {
        return folderName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
