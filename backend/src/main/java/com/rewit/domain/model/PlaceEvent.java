package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.EventStatus;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Entidade de Domínio representando um evento temporal associado a um local físico.
 * Herda a identidade relacional de RateableTarget (ADR-009).
 */
public class PlaceEvent extends RateableTarget {

    private final UUID placeId;
    private final String title;
    private final String description;
    private final String category;
    private final Instant startAt;
    private final Instant endAt;
    private EventStatus status;
    private final List<String> images;
    private final Instant updatedAt;

    public PlaceEvent(UUID id, UUID placeId, String title, String description,
                      String category, Instant startAt, Instant endAt,
                      EventStatus status, List<String> images) {
        super(id, TargetType.EVENT);

        if (placeId == null) {
            throw new BusinessException("O local vinculado ao evento é obrigatório", "MISSING_PLACE_ID");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("O título do evento é obrigatório", "INVALID_EVENT_TITLE");
        }
        if (startAt == null || endAt == null) {
            throw new BusinessException("As datas de início e término são obrigatórias", "MISSING_EVENT_DATES");
        }
        if (endAt.isBefore(startAt)) {
            throw new BusinessException("A data de término não pode ser anterior ao início do evento", "INVALID_EVENT_DATE_RANGE");
        }

        this.placeId = placeId;
        this.title = title.trim();
        this.description = description;
        this.category = category != null ? category.trim() : "GERAL";
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status != null ? status : EventStatus.SCHEDULED;
        this.images = images != null ? List.copyOf(images) : Collections.emptyList();
        this.updatedAt = Instant.now();
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public List<String> getImages() {
        return images;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
