package com.rewit.application.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.rewit.domain.enums.ReviewStatus;

/**
 * Contrato de consulta compartilhado para review listing, discovery e feed futuro.
 * A validação garante defaults seguros e rejeita status públicos não ativos.
 */
public record ReviewQuery(
        UUID requesterUserId,
        ReviewStatus status,
        ReviewVisibilityScope visibilityScope,
        UUID targetId,
        UUID authorUserId,
        boolean verifiedOnly,
        BigDecimal ratingMin,
        BigDecimal ratingMax,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size,
        ReviewSort sort
) {
    public ReviewQuery {
        if (status == null) {
            status = ReviewStatus.ACTIVE;
        }
        if (status != ReviewStatus.ACTIVE) {
            throw new IllegalArgumentException("ReviewQuery only supports ACTIVE status. UNDER_REVIEW and REMOVED are not eligible for public queries.");
        }

        if (visibilityScope == null) {
            visibilityScope = ReviewVisibilityScope.ONLY_PUBLIC;
        }

        if (sort == null) {
            sort = ReviewSort.NEWEST;
        }

        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("size must be between 1 and 50");
        }
        if (ratingMin != null && ratingMax != null && ratingMin.compareTo(ratingMax) > 0) {
            throw new IllegalArgumentException("ratingMin must be less than or equal to ratingMax");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must be less than or equal to createdTo");
        }
        if (visibilityScope.requiresRequester() && requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId is required when visibilityScope is FOLLOWERS or PRIVATE");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID requesterUserId;
        private ReviewStatus status = ReviewStatus.ACTIVE;
        private ReviewVisibilityScope visibilityScope = ReviewVisibilityScope.ONLY_PUBLIC;
        private UUID targetId;
        private UUID authorUserId;
        private boolean verifiedOnly;
        private BigDecimal ratingMin;
        private BigDecimal ratingMax;
        private Instant createdFrom;
        private Instant createdTo;
        private int page;
        private int size = 20;
        private ReviewSort sort = ReviewSort.NEWEST;

        public Builder requesterUserId(UUID requesterUserId) {
            this.requesterUserId = requesterUserId;
            return this;
        }

        public Builder status(ReviewStatus status) {
            this.status = status;
            return this;
        }

        public Builder visibilityScope(ReviewVisibilityScope visibilityScope) {
            this.visibilityScope = visibilityScope;
            return this;
        }

        public Builder targetId(UUID targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder authorUserId(UUID authorUserId) {
            this.authorUserId = authorUserId;
            return this;
        }

        public Builder verifiedOnly(boolean verifiedOnly) {
            this.verifiedOnly = verifiedOnly;
            return this;
        }

        public Builder ratingMin(BigDecimal ratingMin) {
            this.ratingMin = ratingMin;
            return this;
        }

        public Builder ratingMax(BigDecimal ratingMax) {
            this.ratingMax = ratingMax;
            return this;
        }

        public Builder createdFrom(Instant createdFrom) {
            this.createdFrom = createdFrom;
            return this;
        }

        public Builder createdTo(Instant createdTo) {
            this.createdTo = createdTo;
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Builder sort(ReviewSort sort) {
            this.sort = sort;
            return this;
        }

        public ReviewQuery build() {
            return new ReviewQuery(
                    requesterUserId,
                    status,
                    visibilityScope,
                    targetId,
                    authorUserId,
                    verifiedOnly,
                    ratingMin,
                    ratingMax,
                    createdFrom,
                    createdTo,
                    page,
                    size,
                    sort
            );
        }
    }
}
