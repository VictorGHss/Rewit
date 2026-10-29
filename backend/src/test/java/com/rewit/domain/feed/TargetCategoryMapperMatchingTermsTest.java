package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: TargetCategoryMapper - getMatchingTerms")
class TargetCategoryMapperMatchingTermsTest {

    @Test
    @DisplayName("Retorna termos canônicos e variações normalizadas para RESTAURANT")
    void shouldReturnMatchingTermsForRestaurant() {
        Set<String> terms = TargetCategoryMapper.getMatchingTerms(InterestCategory.RESTAURANT);

        assertNotNull(terms);
        assertFalse(terms.isEmpty());
        assertTrue(terms.contains("RESTAURANT"));
        assertTrue(terms.contains("restaurant"));
        assertTrue(terms.contains("restaurante"));
        assertTrue(terms.contains("churrascaria"));
        assertTrue(terms.contains("pizzaria"));
    }

    @Test
    @DisplayName("Retorna conjunto vazio quando categoria for nula")
    void shouldReturnEmptySetWhenCategoryIsNull() {
        Set<String> terms = TargetCategoryMapper.getMatchingTerms(null);

        assertNotNull(terms);
        assertTrue(terms.isEmpty());
    }
}

