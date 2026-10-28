package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: TargetCategoryMapper (V40)")
class TargetCategoryMapperUnitTest {

    @ParameterizedTest
    @CsvSource({
            "RESTAURANT, RESTAURANT",
            "restaurante, RESTAURANT",
            "RESTAURANTES, RESTAURANT",
            "pizzaria, RESTAURANT",
            "churrascaria, RESTAURANT",
            "fast food, FAST_FOOD",
            "FAST_FOOD, FAST_FOOD",
            "lanchonete, FAST_FOOD",
            "hamburgueria, FAST_FOOD",
            "Café, CAFE",
            "cafeteria, CAFE",
            "Padaria, BAKERY",
            "panificadora, BAKERY",
            "Bar, BAR",
            "pub, BAR",
            "sorveteria, ICE_CREAM",
            "Supermercado, SUPERMARKET",
            "mercado, SUPERMARKET",
            "feira, SUPERMARKET",
            "moda, FASHION",
            "loja de roupas, FASHION",
            "livraria, BOOKS",
            "Tecnologia, TECHNOLOGY",
            "eletronicos, TECHNOLOGY",
            "shopping, SHOPPING_MALL",
            "barbearia, BEAUTY",
            "salao de beleza, BEAUTY",
            "Pet Shop, PET",
            "veterinario, PET",
            "posto, AUTOMOTIVE",
            "oficina mecanica, AUTOMOTIVE",
            "Parque, PARK",
            "academia, FITNESS",
            "cinema, CULTURE",
            "museu, CULTURE",
            "show, EVENTS",
            "evento, EVENTS",
            "games, GAMES",
            "jogos, GAMES",
            "loja de games, GAMES",
            "loja de informatica, TECHNOLOGY",
            "hotel, TRAVEL",
            "pousada, TRAVEL",
            "Farmácia, HEALTH",
            "hospital, HEALTH"
    })
    @DisplayName("Mapeia corretamente termos conhecidos para suas respectivas InterestCategories")
    void shouldMapKnownTerms(String rawTerm, InterestCategory expectedCategory) {
        Optional<InterestCategory> result = TargetCategoryMapper.map(rawTerm);
        assertTrue(result.isPresent(), () -> "Deveria mapear o termo: " + rawTerm);
        assertEquals(expectedCategory, result.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GERAL",
            "OUTROS",
            "geral",
            "desconhecido",
            "banco",
            "escola",
            "material de construcao",
            "imobiliaria",
            "advocacia",
            "",
            "   "
    })
    @DisplayName("Retorna Optional.empty() para termos não mapeáveis, gerando afinidade neutra")
    void shouldReturnEmptyForUnmappedTerms(String unmappedTerm) {
        Optional<InterestCategory> result = TargetCategoryMapper.map(unmappedTerm);
        assertTrue(result.isEmpty(), () -> "Termo não mapeável deveria retornar vazio: " + unmappedTerm);
    }

    @Test
    @DisplayName("Retorna Optional.empty() para null de forma segura")
    void shouldReturnEmptyForNull() {
        Optional<InterestCategory> result = TargetCategoryMapper.map(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Mapeia com sucesso todas as constantes do enum InterestCategory pelo nome exato")
    void shouldMapAllEnumConstantsDirectly() {
        for (InterestCategory category : InterestCategory.values()) {
            Optional<InterestCategory> result = TargetCategoryMapper.map(category.name());
            assertTrue(result.isPresent(), () -> "Constante de enum não mapeada: " + category.name());
            assertEquals(category, result.get());
        }
    }
}
