package com.rewit.infrastructure.persistence.entity;

import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.domain.enums.StorageQuarantineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela storage_object_quarantine (Step 28.3).
 * Escritas acontecem por SQL nativo em {@code StorageObjectQuarantineJpaRepository}.
 */
@Entity
@Table(name = "storage_object_quarantine")
public class StorageObjectQuarantineJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "first_observed_at", nullable = false)
    private Instant firstObservedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public StorageObjectQuarantineJpaEntity() {}

    public QuarantinedStorageObject toDto() {
        return new QuarantinedStorageObject(
                objectKey,
                StorageQuarantineStatus.valueOf(status),
                firstObservedAt,
                lastObservedAt,
                lastModifiedAt,
                confirmedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public String getObjectKey() {
        return objectKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StorageObjectQuarantineJpaEntity that = (StorageObjectQuarantineJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
