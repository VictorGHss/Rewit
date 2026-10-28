package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.model.BrazilianState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mapeador determinístico entre categorias textuais de alvos (locais e produtos)
 * e os códigos canônicos de {@link InterestCategory} do onboarding de interesses (V40).
 *
 * <p>Regras de conversão:
 * <ul>
 *   <li>Conversão explícita baseada em correspondência normalizada (minúsculas, sem acentos, trim).</li>
 *   <li>Reconhece os nomes oficiais dos enums e termos em português consolidados.</li>
 *   <li>Valores genéricos ou desconhecidos (ex.: {@code "GERAL"}, {@code "OUTROS"}, {@code null})
 *       retornam {@link Optional#empty()}, gerando afinidade neutra (score 0.0) sem penalização.</li>
 * </ul>
 */
public final class TargetCategoryMapper {

    private static final Map<String, InterestCategory> MAPPING;

    static {
        Map<String, InterestCategory> map = new HashMap<>();

        // 1. Mapeamento direto de todos os nomes de constantes de InterestCategory
        for (InterestCategory category : InterestCategory.values()) {
            map.put(normalize(category.name()), category);
        }

        // 2. FOOD_AND_DRINK
        register(map, InterestCategory.RESTAURANT,
                "restaurante", "restaurantes", "restaurant", "restauracao", "gastronomia",
                "churrascaria", "pizzaria", "cantina", "bistro", "culinaria");

        register(map, InterestCategory.FAST_FOOD,
                "fast food", "fast_food", "fastfood", "lanchonete", "lanchonetes",
                "hamburgueria", "hamburguerias", "pastelaria", "lanches", "pastel");

        register(map, InterestCategory.CAFE,
                "cafe", "cafes", "cafeteria", "cafeterias", "coffee shop", "casa de cha");

        register(map, InterestCategory.BAKERY,
                "padaria", "padarias", "panificadora", "panificadoras", "bakery", "confeitaria", "doceria");

        register(map, InterestCategory.BAR,
                "bar", "bares", "pub", "pubs", "choperia", "boteco", "botequim", "cervejaria");

        register(map, InterestCategory.ICE_CREAM,
                "sorveteria", "sorveterias", "sorvete", "gelateria", "ice cream", "ice_cream", "acai");

        // 3. SHOPPING_AND_SERVICES
        register(map, InterestCategory.SUPERMARKET,
                "supermercado", "supermercados", "mercado", "mercados", "mercearia", "mercearias",
                "feira", "feiras", "feira livre", "sacolao", "hortifruti", "quitanda", "supermarket");

        register(map, InterestCategory.FASHION,
                "moda", "vestuario", "roupa", "roupas", "loja de roupas", "calcados", "sapataria", "fashion");

        register(map, InterestCategory.BOOKS,
                "livraria", "livrarias", "livro", "livros", "sebo", "papelaria", "books");

        register(map, InterestCategory.TECHNOLOGY,
                "tecnologia", "eletronicos", "informatica", "loja de informatica", "loja de eletronicos",
                "computadores", "celulares", "smartphones", "eletronica", "technology");

        register(map, InterestCategory.SHOPPING_MALL,
                "shopping", "shoppings", "shopping center", "shopping mall", "shopping_mall", "galeria");

        register(map, InterestCategory.BEAUTY,
                "beleza", "barbearia", "barbearias", "salao de beleza", "cabeleireiro",
                "cabeleireiros", "estetica", "manicure", "beauty");

        register(map, InterestCategory.PET,
                "pet", "pet shop", "pet_shop", "petshop", "veterinario", "veterinaria",
                "clinica veterinaria", "pet shops");

        register(map, InterestCategory.AUTOMOTIVE,
                "automotivo", "posto", "postos", "posto de gasolina", "posto de combustivel",
                "oficina", "oficinas", "oficina mecanica", "mecanica", "auto eletrica", "automotive");

        // 4. LEISURE_AND_WELLBEING
        register(map, InterestCategory.PARK,
                "parque", "parques", "praca", "bosque", "jardim", "parque ecologico", "park");

        register(map, InterestCategory.FITNESS,
                "academia", "academias", "esporte", "esportes", "fitness", "ginastica",
                "crossfit", "pilates", "quadra esportiva");

        register(map, InterestCategory.CULTURE,
                "cultura", "cinema", "cinemas", "teatro", "teatros", "museu", "museus",
                "galeria de arte", "exposicao", "culture");

        register(map, InterestCategory.EVENTS,
                "evento", "eventos", "show", "shows", "festival", "festivais",
                "casa de shows", "casa de eventos", "events");

        register(map, InterestCategory.GAMES,
                "games", "game", "jogo", "jogos", "jogos eletronicos", "videogame",
                "loja de games", "loja de jogos", "fliperama", "arcade", "lan house", "entretenimento", "gaming");

        register(map, InterestCategory.TRAVEL,
                "hotel", "hoteis", "pousada", "pousadas", "resort", "hostel",
                "albergue", "viagem", "viagens", "travel");

        register(map, InterestCategory.HEALTH,
                "saude", "farmacia", "farmacias", "drogaria", "drogarias", "hospital",
                "hospitais", "clinica", "clinicas", "dentista", "odontologia", "health");

        MAPPING = Collections.unmodifiableMap(map);
    }

    private TargetCategoryMapper() {
    }

    /**
     * Mapeia uma categoria textual arbitrária para uma constante canônica de {@link InterestCategory}.
     *
     * @param rawCategory categoria textual informada no estabelecimento ou produto
     * @return categoria de interesse correspondente, ou {@code Optional.empty()} se não for mapeável
     */
    public static Optional<InterestCategory> map(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(rawCategory);
        return Optional.ofNullable(MAPPING.get(normalized));
    }

    private static void register(Map<String, InterestCategory> map, InterestCategory category, String... terms) {
        for (String term : terms) {
            map.put(normalize(term), category);
        }
    }

    private static String normalize(String value) {
        return BrazilianState.fold(value).replace("_", " ").trim();
    }
}
