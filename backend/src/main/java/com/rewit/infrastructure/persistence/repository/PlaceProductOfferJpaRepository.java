package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceProductOfferJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlaceProductOfferJpaRepository extends JpaRepository<PlaceProductOfferJpaEntity, UUID> {

    @Query("SELECT o FROM PlaceProductOfferJpaEntity o WHERE o.placeId = :placeId AND o.productId = :productId")
    Optional<PlaceProductOfferJpaEntity> findByPlaceAndProduct(@Param("placeId") UUID placeId,
                                                               @Param("productId") UUID productId);

    @Modifying
    @Query("""
            DELETE FROM PlaceProductOfferJpaEntity o
            WHERE o.placeId = :placeId AND o.productId = :productId AND o.businessAccountId = :businessAccountId
            """)
    int deleteOffer(@Param("placeId") UUID placeId, @Param("productId") UUID productId,
                    @Param("businessAccountId") UUID businessAccountId);

    /** Linhas {@code [product_id, name, brand, model, category, image_url, availability, price, currency]}. */
    @Query(value = """
            SELECT p.id, p.name, p.brand, p.model, p.category, p.image_url, o.availability, o.price, o.currency
            FROM place_product_offers o
            JOIN products p ON p.id = o.product_id
            WHERE o.place_id = :placeId AND o.business_account_id = :businessAccountId AND p.status = 'ACTIVE'
            ORDER BY p.name ASC, p.id ASC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findListedByPlace(@Param("placeId") UUID placeId,
                                     @Param("businessAccountId") UUID businessAccountId,
                                     @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = """
            SELECT COUNT(*)
            FROM place_product_offers o
            JOIN products p ON p.id = o.product_id
            WHERE o.place_id = :placeId AND o.business_account_id = :businessAccountId AND p.status = 'ACTIVE'
            """, nativeQuery = true)
    long countListedByPlace(@Param("placeId") UUID placeId, @Param("businessAccountId") UUID businessAccountId);
}
