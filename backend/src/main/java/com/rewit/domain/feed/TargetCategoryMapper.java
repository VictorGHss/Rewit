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
                "churrascaria", "churrascarias", "pizzaria", "pizzarias", "cantina", "cantinas",
                "bistro", "bistros", "culinaria");

        register(map, InterestCategory.FAST_FOOD,
                "fast food", "fast_food", "fastfood", "lanchonete", "lanchonetes",
                "hamburgueria", "hamburguerias", "hamburguer", "hamburgueres",
                "pastelaria", "pastelarias", "lanches", "pastel");

        register(map, InterestCategory.CAFE,
                "cafe", "cafes", "cafeteria", "cafeterias", "coffee shop", "casa de cha", "casas de cha");

        register(map, InterestCategory.BAKERY,
                "padaria", "padarias", "panificadora", "panificadoras", "bakery",
                "confeitaria", "confeitarias", "doceria", "docerias");

        register(map, InterestCategory.BAR,
                "bar", "bares", "pub", "pubs", "choperia", "choperias", "boteco", "botecos",
                "botequim", "botequins", "cervejaria", "cervejarias");

        register(map, InterestCategory.ICE_CREAM,
                "sorveteria", "sorveterias", "sorvete", "sorvetes", "gelateria", "gelaterias",
                "ice cream", "ice_cream", "acai");

        // 3. SHOPPING_AND_SERVICES
        register(map, InterestCategory.SUPERMARKET,
                "supermercado", "supermercados", "hipermercado", "hipermercados",
                "minimercado", "minimercados", "mercado", "mercados", "mercearia", "mercearias",
                "loja de conveniencia", "conveniencia", "feira", "feiras", "feira livre",
                "sacolao", "hortifruti", "hortifrutis", "quitanda", "quitandas", "supermarket");

        register(map, InterestCategory.FASHION,
                "moda", "vestuario", "roupa", "roupas", "loja de roupas", "lojas de roupas",
                "calcado", "calcados", "sapataria", "sapatarias", "boutique", "boutiques", "fashion");

        register(map, InterestCategory.BOOKS,
                "livraria", "livrarias", "livro", "livros", "sebo", "sebos", "papelaria", "papelarias", "books");

        register(map, InterestCategory.TECHNOLOGY,
                "tecnologia", "eletronico", "eletronicos", "informatica", "loja de informatica",
                "loja de eletronicos", "computador", "computadores", "celular", "celulares",
                "smartphone", "smartphones", "eletronica", "technology");

        register(map, InterestCategory.SHOPPING_MALL,
                "shopping", "shoppings", "shopping center", "shopping mall", "shopping_mall", "galeria", "galerias");

        register(map, InterestCategory.BEAUTY,
                "beleza", "barbearia", "barbearias", "salao de beleza", "saloes de beleza",
                "cabeleireiro", "cabeleireiros", "estetica", "manicure", "esmalteria", "esmalterias", "beauty");

        register(map, InterestCategory.PET,
                "pet", "pets", "pet shop", "pet_shop", "petshop", "veterinario", "veterinarios",
                "veterinaria", "clinica veterinaria", "clinicas veterinarias", "pet shops");

        register(map, InterestCategory.AUTOMOTIVE,
                "automotivo", "posto", "postos", "posto de gasolina", "postos de gasolina",
                "posto de combustivel", "postos de combustivel", "oficina", "oficinas",
                "oficina mecanica", "mecanica", "auto eletrica", "auto eletricas",
                "borracharia", "borracharias", "lava rapido", "automotive");

        // 4. LEISURE_AND_WELLBEING
        register(map, InterestCategory.PARK,
                "parque", "parques", "praca", "pracas", "bosque", "bosques",
                "jardim", "jardins", "parque ecologico", "park", "parks");

        register(map, InterestCategory.FITNESS,
                "academia", "academias", "esporte", "esportes", "fitness", "ginastica",
                "crossfit", "pilates", "quadra esportiva", "quadras esportivas");

        register(map, InterestCategory.CULTURE,
                "cultura", "cinema", "cinemas", "teatro", "teatros", "museu", "museus",
                "galeria de arte", "galerias de arte", "exposicao", "exposicoes", "culture", "museum", "museums");

        register(map, InterestCategory.EVENTS,
                "evento", "eventos", "show", "shows", "festival", "festivais",
                "casa de shows", "casas de shows", "casa de eventos", "casas de eventos", "events");

        register(map, InterestCategory.GAMES,
                "games", "game", "jogo", "jogos", "jogos eletronicos", "videogame", "videogames",
                "loja de games", "lojas de games", "loja de jogos", "lojas de jogos",
                "fliperama", "fliperamas", "arcade", "arcades", "lan house", "entretenimento", "gaming");

        register(map, InterestCategory.TRAVEL,
                "hotel", "hoteis", "hotels", "pousada", "pousadas", "resort", "resorts",
                "hostel", "hostels", "albergue", "albergues", "viagem", "viagens", "travel");

        register(map, InterestCategory.HEALTH,
                "saude", "farmacia", "farmacias", "drogaria", "drogarias", "pharmacy", "pharmacies",
                "hospital", "hospitais", "clinica", "clinicas", "dentista", "dentistas", "odontologia", "health");

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
