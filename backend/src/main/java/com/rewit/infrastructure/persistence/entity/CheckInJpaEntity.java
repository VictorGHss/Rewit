package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela check_ins no PostgreSQL com suporte a PostGIS.
 * Valida com o schema gerado pelas migrations Flyway V1..V43.
 *
 * <p>Política estrutural de privacidade (V43): esta entidade nunca converte nem persiste coordenadas GPS
 * em {@code check_ins.coordinates}, e persiste {@code distance_to_centroid_meters} somente para status {@code VERIFIED}.
 */
@Entity
@Table(name = "check_ins")
public class CheckInJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, unique = true)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    @Column(name = "coordinates", columnDefinition = "geography(Point, 4326)")
    private Point coordinates;

    // NUMERIC(7,2) em V1 (anulável desde V43): mapeado como BigDecimal; o domínio mantém Double
    @Column(name = "distance_to_centroid_meters", precision = 7, scale = 2)
    private BigDecimal distanceToCentroidMeters;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "verification_method", nullable = false, length = 32)
    private String verificationMethod;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    public CheckInJpaEntity() {
    }

    public static CheckInJpaEntity fromDomain(CheckIn domain) {
        if (domain == null) {
            return null;
        }
        CheckInStatus resolvedStatus = domain.getStatus() != null ? domain.getStatus() : CheckInStatus.PENDING;
        boolean verified = resolvedStatus == CheckInStatus.VERIFIED;
        boolean rejected = resolvedStatus == CheckInStatus.REJECTED;

        CheckInJpaEntity entity = new CheckInJpaEntity();
        entity.setId(domain.getId());
        entity.setReviewId(domain.getReviewId());
        entity.setUserId(domain.getUserId());
        entity.setPlaceId(domain.getPlaceId());
        // Imposição estrutural (V43): coordenadas transitórias do domínio nunca são convertidas nem persistidas.
        entity.coordinates = null;
        // Apenas check-ins VERIFIED persistem distância até o centróide; PENDING e REJECTED persistem NULL.
        entity.setDistanceToCentroidMeters(verified && domain.getDistanceToCentroidMeters() != null
                ? BigDecimal.valueOf(domain.getDistanceToCentroidMeters()).setScale(2, RoundingMode.HALF_UP)
                : null);
        entity.setStatus(resolvedStatus.name());
        entity.setVerificationMethod(domain.getVerificationMethod() != null ? domain.getVerificationMethod().name() : "GPS");
        entity.setVerifiedAt(domain.getVerifiedAt());
        entity.setRejectionReason(rejected && domain.getRejectionReason() != null ? domain.getRejectionReason().name() : null);
        return entity;
    }

    public CheckIn toDomain() {
        Double lat = coordinates != null ? coordinates.getY() : null;
        Double lon = coordinates != null ? coordinates.getX() : null;
        Double distance = this.distanceToCentroidMeters != null ? this.distanceToCentroidMeters.doubleValue() : null;

        CheckInStatus checkInStatus = CheckInStatus.PENDING;
        if (this.status != null) {
            try {
                checkInStatus = CheckInStatus.valueOf(this.status.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }

        VerificationMethod method = VerificationMethod.GPS;
        if (this.verificationMethod != null) {
            try {
                method = VerificationMethod.valueOf(this.verificationMethod.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }

        PresenceVerificationOutcome reason = null;
        if (this.rejectionReason != null) {
            try {
                reason = PresenceVerificationOutcome.valueOf(this.rejectionReason.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return new CheckIn(
                this.id,
                this.reviewId,
                this.userId,
                this.placeId,
                lat,
                lon,
                checkInStatus == CheckInStatus.VERIFIED ? distance : null,
                checkInStatus,
                method,
                this.verifiedAt,
                checkInStatus == CheckInStatus.REJECTED ? reason : null
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public void setPlaceId(UUID placeId) {
        this.placeId = placeId;
    }

    public Point getCoordinates() {
        return coordinates;
    }

    public BigDecimal getDistanceToCentroidMeters() {
        return distanceToCentroidMeters;
    }

    public void setDistanceToCentroidMeters(BigDecimal distanceToCentroidMeters) {
        this.distanceToCentroidMeters = distanceToCentroidMeters;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVerificationMethod() {
        return verificationMethod;
    }

    public void setVerificationMethod(String verificationMethod) {
        this.verificationMethod = verificationMethod;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CheckInJpaEntity that = (CheckInJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
