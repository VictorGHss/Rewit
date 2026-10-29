package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: TargetCategoryMapper (V40)")
class TargetCategoryMapperUnitTest {

    @Nested
    @DisplayName("1. Alimentação e Bebidas (Restaurantes, Cafés, Padarias, Bares, Fast Food, Sorvetes)")
    class FoodAndDrinkMapping {

        @ParameterizedTest
        @CsvSource({
                "restaurante, RESTAURANT",
                "RESTAURANTES, RESTAURANT",
                "restaurant, RESTAURANT",
                "gastronomia, RESTAURANT",
                "culinária, RESTAURANT",
                "pizzaria, RESTAURANT",
                "pizzarias, RESTAURANT",
                "churrascaria, RESTAURANT",
                "churrascarias, RESTAURANT",
                "cantina, RESTAURANT",
                "cantinas, RESTAURANT",
                "bistrô, RESTAURANT",
                "bistros, RESTAURANT",
                "catering.restaurant, RESTAURANT",
                "café, CAFE",
                "Cafés, CAFE",
                "cafeteria, CAFE",
                "cafeterias, CAFE",
                "coffee shop, CAFE",
                "casa de chá, CAFE",
                "casas de cha, CAFE",
                "catering.cafe, CAFE",
                "padaria, BAKERY",
                "Padarias, BAKERY",
                "panificadora, BAKERY",
                "panificadoras, BAKERY",
                "confeitaria, BAKERY",
                "confeitarias, BAKERY",
                "commercial.food_and_drink.bakery, BAKERY",
                "bar, BAR",
                "Bares, BAR",
                "pub, BAR",
                "pubs, BAR",
                "choperia, BAR",
                "choperias, BAR",
                "boteco, BAR",
                "botecos, BAR",
                "botequim, BAR",
                "botequins, BAR",
                "cervejaria, BAR",
                "cervejarias, BAR",
                "catering.bar, BAR",
                "catering.pub, BAR",
                "fast food, FAST_FOOD",
                "FAST_FOOD, FAST_FOOD",
                "fastfood, FAST_FOOD",
                "lanchonete, FAST_FOOD",
                "lanchonetes, FAST_FOOD",
                "hamburgueria, FAST_FOOD",
                "hamburguerias, FAST_FOOD",
                "hambúrguer, FAST_FOOD",
                "hamburgueres, FAST_FOOD",
                "burger, FAST_FOOD",
                "burgers, FAST_FOOD",
                "pastelaria, FAST_FOOD",
                "pastelarias, FAST_FOOD",
                "pastel, FAST_FOOD",
                "lanches, FAST_FOOD",
                "catering.fast_food, FAST_FOOD",
                "sorveteria, ICE_CREAM",
                "sorveterias, ICE_CREAM",
                "sorvete, ICE_CREAM",
                "sorvetes, ICE_CREAM",
                "gelateria, ICE_CREAM",
                "gelaterias, ICE_CREAM",
                "açaí, ICE_CREAM",
                "doceria, ICE_CREAM",
                "docerias, ICE_CREAM",
                "doces, ICE_CREAM",
                "catering.ice_cream, ICE_CREAM"
        })
        @DisplayName("Mapeia corretamente termos de alimentação e bebidas")
        void shouldMapFoodAndDrink(String rawTerm, InterestCategory expectedCategory) {
            Optional<InterestCategory> result = TargetCategoryMapper.map(rawTerm);
            assertTrue(result.isPresent(), () -> "Deveria mapear o termo: " + rawTerm);
            assertEquals(expectedCategory, result.get());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "fast food",
                "fast_food",
                "fastfood",
                "lanchonete",
                "lanchonetes",
                "hamburgueria",
                "hamburguerias",
                "hambúrguer",
                "burger",
                "pastelaria",
                "pastelarias",
                "catering.fast_food"
        })
        @DisplayName("Garante que termos de fast food produzem FAST_FOOD e NUNCA RESTAURANT")
        void shouldProduceFastFoodAndNeverRestaurant(String rawTerm) {
            Optional<InterestCategory> result = TargetCategoryMapper.map(rawTerm);
            assertTrue(result.isPresent(), () -> "Termo deveria ser mapeado: " + rawTerm);
            assertEquals(InterestCategory.FAST_FOOD, result.get(),
                    () -> "Termo " + rawTerm + " deve produzir FAST_FOOD, e não RESTAURANT");
            assertNotEquals(InterestCategory.RESTAURANT, result.get());
        }
    }

    @Nested
    @DisplayName("2. Compras e Serviços (Supermercados, Moda, Livros, Tecnologia, Shoppings, Beleza, Pet, Automotivo)")
    class ShoppingAndServicesMapping {

        @ParameterizedTest
        @CsvSource({
                "supermercado, SUPERMARKET",
                "supermercados, SUPERMARKET",
                "hipermercado, SUPERMARKET",
                "hipermercados, SUPERMARKET",
                "minimercado, SUPERMARKET",
                "minimercados, SUPERMARKET",
                "mercado, SUPERMARKET",
                "mercados, SUPERMARKET",
                "mercearia, SUPERMARKET",
                "mercearias, SUPERMARKET",
                "loja de conveniência, SUPERMARKET",
                "conveniencia, SUPERMARKET",
                "feira, SUPERMARKET",
                "feiras, SUPERMARKET",
                "feira livre, SUPERMARKET",
                "sacolão, SUPERMARKET",
                "hortifrúti, SUPERMARKET",
                "hortifrutis, SUPERMARKET",
                "quitanda, SUPERMARKET",
                "quitandas, SUPERMARKET",
                "commercial.supermarket, SUPERMARKET",
                "commercial.clothing, FASHION",
                "moda, FASHION",
                "vestuário, FASHION",
                "roupa, FASHION",
                "roupas, FASHION",
                "loja de roupas, FASHION",
                "lojas de roupas, FASHION",
                "calçado, FASHION",
                "calçados, FASHION",
                "sapataria, FASHION",
                "sapatarias, FASHION",
                "boutique, FASHION",
                "boutiques, FASHION",
                "livraria, BOOKS",
                "livrarias, BOOKS",
                "livro, BOOKS",
                "livros, BOOKS",
                "sebo, BOOKS",
                "sebos, BOOKS",
                "papelaria, BOOKS",
                "papelarias, BOOKS",
                "commercial.books, BOOKS",
                "tecnologia, TECHNOLOGY",
                "eletrônico, TECHNOLOGY",
                "eletrônicos, TECHNOLOGY",
                "informática, TECHNOLOGY",
                "loja de informática, TECHNOLOGY",
                "loja de eletrônicos, TECHNOLOGY",
                "computador, TECHNOLOGY",
                "computadores, TECHNOLOGY",
                "celular, TECHNOLOGY",
                "celulares, TECHNOLOGY",
                "smartphone, TECHNOLOGY",
                "smartphones, TECHNOLOGY",
                "shopping, SHOPPING_MALL",
                "shoppings, SHOPPING_MALL",
                "shopping center, SHOPPING_MALL",
                "commercial.shopping_mall, SHOPPING_MALL",
                "galeria, SHOPPING_MALL",
                "galerias, SHOPPING_MALL",
                "beleza, BEAUTY",
                "barbearia, BEAUTY",
                "barbearias, BEAUTY",
                "salão de beleza, BEAUTY",
                "salões de beleza, BEAUTY",
                "cabeleireiro, BEAUTY",
                "cabeleireiros, BEAUTY",
                "service.beauty.hairdresser, BEAUTY",
                "estética, BEAUTY",
                "manicure, BEAUTY",
                "esmalteria, BEAUTY",
                "esmalterias, BEAUTY",
                "pet, PET",
                "pets, PET",
                "pet shop, PET",
                "petshop, PET",
                "pet shops, PET",
                "pet.shop, PET",
                "veterinário, PET",
                "veterinários, PET",
                "veterinária, PET",
                "clínica veterinária, PET",
                "clinicas veterinarias, PET",
                "pet.veterinary, PET",
                "automotivo, AUTOMOTIVE",
                "posto, AUTOMOTIVE",
                "postos, AUTOMOTIVE",
                "posto de gasolina, AUTOMOTIVE",
                "postos de gasolina, AUTOMOTIVE",
                "posto de combustível, AUTOMOTIVE",
                "postos de combustivel, AUTOMOTIVE",
                "service.vehicle.fuel, AUTOMOTIVE",
                "oficina, AUTOMOTIVE",
                "oficinas, AUTOMOTIVE",
                "oficina mecânica, AUTOMOTIVE",
                "mecânica, AUTOMOTIVE",
                "service.vehicle.repair.car, AUTOMOTIVE",
                "auto elétrica, AUTOMOTIVE",
                "auto eletricas, AUTOMOTIVE",
                "borracharia, AUTOMOTIVE",
                "borracharias, AUTOMOTIVE",
                "lava rápido, AUTOMOTIVE"
        })
        @DisplayName("Mapeia corretamente termos de compras e serviços")
        void shouldMapShoppingAndServices(String rawTerm, InterestCategory expectedCategory) {
            Optional<InterestCategory> result = TargetCategoryMapper.map(rawTerm);
            assertTrue(result.isPresent(), () -> "Deveria mapear o termo: " + rawTerm);
            assertEquals(expectedCategory, result.get());
        }
    }

    @Nested
    @DisplayName("3. Lazer, Cultura, Jogos, Viagens e Bem-estar (Parques, Fitness, Cultura, Eventos, Games, Viagens, Saúde)")
    class LeisureAndWellbeingMapping {

        @ParameterizedTest
        @CsvSource({
                "parque, PARK",
                "parques, PARK",
                "praça, PARK",
                "praças, PARK",
                "bosque, PARK",
                "bosques, PARK",
                "jardim, PARK",
                "jardins, PARK",
                "parque ecológico, PARK",
                "parks, PARK",
                "leisure.park, PARK",
                "academia, FITNESS",
                "academias, FITNESS",
                "esporte, FITNESS",
                "esportes, FITNESS",
                "fitness, FITNESS",
                "ginástica, FITNESS",
                "crossfit, FITNESS",
                "pilates, FITNESS",
                "quadra esportiva, FITNESS",
                "quadras esportivas, FITNESS",
                "sport.fitness.gym, FITNESS",
                "cultura, CULTURE",
                "cinema, CULTURE",
                "cinemas, CULTURE",
                "teatro, CULTURE",
                "teatros, CULTURE",
                "museu, CULTURE",
                "museus, CULTURE",
                "museum, CULTURE",
                "museums, CULTURE",
                "galeria de arte, CULTURE",
                "galerias de arte, CULTURE",
                "exposição, CULTURE",
                "exposicoes, CULTURE",
                "evento, EVENTS",
                "eventos, EVENTS",
                "show, EVENTS",
                "shows, EVENTS",
                "festival, EVENTS",
                "festivais, EVENTS",
                "casa de shows, EVENTS",
                "casas de shows, EVENTS",
                "casa de eventos, EVENTS",
                "casas de eventos, EVENTS",
                "games, GAMES",
                "game, GAMES",
                "jogo, GAMES",
                "jogos, GAMES",
                "jogos eletrônicos, GAMES",
                "videogame, GAMES",
                "videogames, GAMES",
                "loja de games, GAMES",
                "lojas de games, GAMES",
                "loja de jogos, GAMES",
                "lojas de jogos, GAMES",
                "fliperama, GAMES",
                "fliperamas, GAMES",
                "arcade, GAMES",
                "arcades, GAMES",
                "lan house, GAMES",
                "entretenimento, GAMES",
                "hotel, TRAVEL",
                "hotéis, TRAVEL",
                "hotels, TRAVEL",
                "pousada, TRAVEL",
                "pousadas, TRAVEL",
                "resort, TRAVEL",
                "resorts, TRAVEL",
                "hostel, TRAVEL",
                "hostels, TRAVEL",
                "albergue, TRAVEL",
                "albergues, TRAVEL",
                "viagem, TRAVEL",
                "viagens, TRAVEL",
                "accommodation.hotel, TRAVEL",
                "accommodation.hostel, TRAVEL",
                "saúde, HEALTH",
                "farmácia, HEALTH",
                "farmácias, HEALTH",
                "drogaria, HEALTH",
                "drogarias, HEALTH",
                "pharmacy, HEALTH",
                "pharmacies, HEALTH",
                "hospital, HEALTH",
                "hospitais, HEALTH",
                "clínica, HEALTH",
                "clínicas, HEALTH",
                "dentista, HEALTH",
                "dentistas, HEALTH",
                "odontologia, HEALTH",
                "healthcare.pharmacy, HEALTH",
                "healthcare.hospital, HEALTH",
                "healthcare.clinic_or_praxis, HEALTH",
                "healthcare.dentist, HEALTH"
        })
        @DisplayName("Mapeia corretamente termos de lazer, cultura, games e bem-estar")
        void shouldMapLeisureAndWellbeing(String rawTerm, InterestCategory expectedCategory) {
            Optional<InterestCategory> result = TargetCategoryMapper.map(rawTerm);
            assertTrue(result.isPresent(), () -> "Deveria mapear o termo: " + rawTerm);
            assertEquals(expectedCategory, result.get());
        }
    }

    @Nested
    @DisplayName("4. Categorias Genéricas, Desconhecidas, Nulas e Fora do Catálogo (Afinidade Neutra)")
    class UnmappedAndNeutralCategories {

        @ParameterizedTest
        @ValueSource(strings = {
                "GERAL",
                "geral",
                "OUTROS",
                "outros",
                "DIVERSOS",
                "diversos",
                "VARIADOS",
                "GERAL_SEM_MAPEAMENTO",
                "desconhecido",
                "banco",
                "agencia bancaria",
                "caixa eletronico",
                "correios",
                "escola",
                "colegio",
                "faculdade",
                "universidade",
                "cartorio",
                "material de construcao",
                "materiais de construcao",
                "imobiliaria",
                "advocacia",
                "contabilidade",
                "lavanderia",
                "chaveiro",
                "grafica",
                "estacionamento",
                "otica",
                "igreja",
                "templo",
                "gourmet",
                "delicatessen",
                "specialty_food",
                "services",
                "servicos",
                "serviços",
                "nightlife",
                "",
                "   "
        })
        @DisplayName("Retorna Optional.empty() para termos não mapeáveis ou fora do catálogo de interesses")
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
    }

    @Nested
    @DisplayName("5. Mapeamento das Constantes Canônicas de InterestCategory")
    class CanonicalEnumConstantsMapping {

        @Test
        @DisplayName("Mapeia com sucesso todas as 21 constantes do enum InterestCategory pelo nome exato")
        void shouldMapAllEnumConstantsDirectly() {
            assertEquals(21, InterestCategory.values().length, "Devem existir exatamente 21 categorias canônicas no onboarding V40");
            for (InterestCategory category : InterestCategory.values()) {
                Optional<InterestCategory> result = TargetCategoryMapper.map(category.name());
                assertTrue(result.isPresent(), () -> "Constante de enum não mapeada: " + category.name());
                assertEquals(category, result.get());
            }
        }

        @Test
        @DisplayName("Garante que rótulos ilustrativos não são membros canônicos do enum InterestCategory")
        void shouldConfirmIllustrativeLabelsAreNotEnumConstants() {
            java.util.List<String> illustrativeLabels = java.util.List.of(
                    "PETS", "PARKS", "GOURMET", "SPECIALTY_FOOD",
                    "PHARMACY", "SERVICES", "MUSEUM", "NIGHTLIFE", "HOTEL"
            );
            java.util.List<String> canonicalNames = java.util.Arrays.stream(InterestCategory.values())
                    .map(Enum::name)
                    .toList();

            for (String label : illustrativeLabels) {
                assertFalse(canonicalNames.contains(label),
                        () -> "Rótulo ilustrativo não deve ser constante do enum: " + label);
            }
        }
    }
}
