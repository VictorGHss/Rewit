package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.Place;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela places no PostgreSQL com suporte a PostGIS (hibernate-spatial).
 * Valida com o schema gerado pelas migrations Flyway V1, V2 e V3.
 */
@Entity
@Table(name = "places")
public class PlaceJpaEntity {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, unique = true)
    private String slug;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "address_text", nullable = false, columnDefinition = "TEXT")
    private String addressText;

    @Column(name = "street_number", length = 32)
    private String streetNumber;

    @Column(name = "neighborhood", length = 128)
    private String neighborhood;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 50)
    private String state;

    @Column(name = "country", nullable = false, length = 10)
    private String country;

    @Column(name = "coordinates", columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point coordinates;

    @Column(name = "validation_radius_meters", nullable = false)
    private int validationRadiusMeters = 50;

    @Column(name = "origin", nullable = false, length = 32)
    private String origin = "USER";

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    @Column(name = "claimed_by_business_id")
    private UUID claimedByBusinessId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PlaceJpaEntity() {
    }

    public static Point toPoint(double latitude, double longitude) {
        // WGS 84 / PostGIS: Coordinate(x, y) = Coordinate(longitude, latitude)
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    public Place toDomain() {
        double lat = coordinates != null ? coordinates.getY() : 0.0;
        double lon = coordinates != null ? coordinates.getX() : 0.0;

        return new Place(
                id,
                name,
                slug,
                category,
                description,
                addressText,
                streetNumber,
                neighborhood,
                city,
                state,
                country,
                lat,
                lon,
                validationRadiusMeters,
                origin,
                isVerified,
                claimedByBusinessId,
                status
        );
    }

    public static PlaceJpaEntity fromDomain(Place domain) {
        Objects.requireNonNull(domain, "domain place must not be null");

        PlaceJpaEntity entity = new PlaceJpaEntity();
        entity.id = domain.getId();
        entity.name = domain.getName();
        entity.slug = domain.getSlug();
        entity.category = domain.getCategory();
        entity.description = domain.getDescription();
        entity.addressText = domain.getAddressText();
        entity.streetNumber = domain.getStreetNumber();
        entity.neighborhood = domain.getNeighborhood();
        entity.city = domain.getCity();
        entity.state = domain.getState();
        entity.country = domain.getCountry();
        entity.coordinates = toPoint(domain.getLatitude(), domain.getLongitude());
        entity.validationRadiusMeters = domain.getValidationRadiusMeters();
        entity.origin = domain.getOrigin();
        entity.isVerified = domain.isVerified();
        entity.claimedByBusinessId = domain.getClaimedByBusinessId();
        entity.status = domain.getStatus();
        entity.createdAt = domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now();
        entity.updatedAt = domain.getUpdatedAt() != null ? domain.getUpdatedAt() : Instant.now();
        return entity;
    }

    public void updateFromDomain(Place domain) {
        Objects.requireNonNull(domain, "domain place must not be null");
        this.name = domain.getName();
        this.slug = domain.getSlug();
        this.category = domain.getCategory();
        this.description = domain.getDescription();
        this.addressText = domain.getAddressText();
        this.streetNumber = domain.getStreetNumber();
        this.neighborhood = domain.getNeighborhood();
        this.city = domain.getCity();
        this.state = domain.getState();
        this.country = domain.getCountry();
        this.coordinates = toPoint(domain.getLatitude(), domain.getLongitude());
        this.validationRadiusMeters = domain.getValidationRadiusMeters();
        this.origin = domain.getOrigin();
        this.isVerified = domain.isVerified();
        this.claimedByBusinessId = domain.getClaimedByBusinessId();
        this.status = domain.getStatus();
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddressText() {
        return addressText;
    }

    public void setAddressText(String addressText) {
        this.addressText = addressText;
    }

    public String getStreetNumber() {
        return streetNumber;
    }

    public void setStreetNumber(String streetNumber) {
        this.streetNumber = streetNumber;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public void setNeighborhood(String neighborhood) {
        this.neighborhood = neighborhood;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Point getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(Point coordinates) {
        this.coordinates = coordinates;
    }

    public int getValidationRadiusMeters() {
        return validationRadiusMeters;
    }

    public void setValidationRadiusMeters(int validationRadiusMeters) {
        this.validationRadiusMeters = validationRadiusMeters;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        isVerified = verified;
    }

    public UUID getClaimedByBusinessId() {
        return claimedByBusinessId;
    }

    public void setClaimedByBusinessId(UUID claimedByBusinessId) {
        this.claimedByBusinessId = claimedByBusinessId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
