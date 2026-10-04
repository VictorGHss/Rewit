package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela check_ins no PostgreSQL com suporte a PostGIS.
 * Valida com o schema gerado pelas migrations Flyway V1, V2 e V3 (Step 12.0).
 */
@Entity
@Table(name = "check_ins")
public class CheckInJpaEntity {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, unique = true)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    @Column(name = "coordinates", columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point coordinates;

    // NUMERIC(7,2) em V1: mapeado como BigDecimal, como as demais colunas NUMERIC; o domínio mantém double
    @Column(name = "distance_to_centroid_meters", nullable = false, precision = 7, scale = 2)
    private BigDecimal distanceToCentroidMeters;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "verification_method", nullable = false, length = 32)
    private String verificationMethod;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    public CheckInJpaEntity() {
    }

    public static Point toPoint(double latitude, double longitude) {
        // WGS 84 / PostGIS: Coordinate(x, y) = Coordinate(longitude, latitude)
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    public static CheckInJpaEntity fromDomain(CheckIn domain) {
        if (domain == null) {
            return null;
        }
        CheckInJpaEntity entity = new CheckInJpaEntity();
        entity.setId(domain.getId());
        entity.setReviewId(domain.getReviewId());
        entity.setUserId(domain.getUserId());
        entity.setPlaceId(domain.getPlaceId());
        entity.setCoordinates(toPoint(domain.getLatitude(), domain.getLongitude()));
        // Mesmo arredondamento que o PostgreSQL aplicava ao receber o double na coluna NUMERIC(7,2)
        entity.setDistanceToCentroidMeters(
                BigDecimal.valueOf(domain.getDistanceToCentroidMeters()).setScale(2, RoundingMode.HALF_UP));
        entity.setStatus(domain.getStatus() != null ? domain.getStatus().name() : "PENDING");
        entity.setVerificationMethod(domain.getVerificationMethod() != null ? domain.getVerificationMethod().name() : "GPS");
        entity.setVerifiedAt(domain.getVerifiedAt());
        return entity;
    }

    public CheckIn toDomain() {
        double lat = coordinates != null ? coordinates.getY() : 0.0;
        double lon = coordinates != null ? coordinates.getX() : 0.0;

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

        return new CheckIn(
                this.id,
                this.reviewId,
                this.userId,
                this.placeId,
                lat,
                lon,
                this.distanceToCentroidMeters.doubleValue(),
                checkInStatus,
                method,
                this.verifiedAt
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

    public void setCoordinates(Point coordinates) {
        this.coordinates = coordinates;
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
