package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.model.BrazilianState;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
                "bistro", "bistros", "culinaria", "catering.restaurant");

        register(map, InterestCategory.FAST_FOOD,
                "fast food", "fast_food", "fastfood", "lanchonete", "lanchonetes",
                "hamburgueria", "hamburguerias", "hamburguer", "hamburgueres",
                "burger", "burgers", "pastelaria", "pastelarias", "lanches", "pastel",
                "catering.fast_food");

        register(map, InterestCategory.CAFE,
                "cafe", "cafes", "cafeteria", "cafeterias", "coffee shop", "casa de cha", "casas de cha",
                "catering.cafe");

        register(map, InterestCategory.BAKERY,
                "padaria", "padarias", "panificadora", "panificadoras", "bakery",
                "confeitaria", "confeitarias", "commercial.food_and_drink.bakery");

        register(map, InterestCategory.BAR,
                "bar", "bares", "pub", "pubs", "choperia", "choperias", "boteco", "botecos",
                "botequim", "botequins", "cervejaria", "cervejarias", "catering.bar", "catering.pub");

        register(map, InterestCategory.ICE_CREAM,
                "sorveteria", "sorveterias", "sorvete", "sorvetes", "gelateria", "gelaterias",
                "ice cream", "ice_cream", "acai", "doceria", "docerias", "doces", "catering.ice_cream");

        // 3. SHOPPING_AND_SERVICES
        register(map, InterestCategory.SUPERMARKET,
                "supermercado", "supermercados", "hipermercado", "hipermercados",
                "minimercado", "minimercados", "mercado", "mercados", "mercearia", "mercearias",
                "loja de conveniencia", "conveniencia", "feira", "feiras", "feira livre",
                "sacolao", "hortifruti", "hortifrutis", "quitanda", "quitandas", "supermarket",
                "commercial.supermarket", "commercial.convenience", "commercial.marketplace");

        register(map, InterestCategory.FASHION,
                "moda", "vestuario", "roupa", "roupas", "loja de roupas", "lojas de roupas",
                "calcado", "calcados", "sapataria", "sapatarias", "boutique", "boutiques", "fashion",
                "commercial.clothing");

        register(map, InterestCategory.BOOKS,
                "livraria", "livrarias", "livro", "livros", "sebo", "sebos", "papelaria", "papelarias", "books",
                "commercial.books");

        register(map, InterestCategory.TECHNOLOGY,
                "tecnologia", "eletronico", "eletronicos", "informatica", "loja de informatica",
                "loja de eletronicos", "computador", "computadores", "celular", "celulares",
                "smartphone", "smartphones", "eletronica", "technology");

        register(map, InterestCategory.SHOPPING_MALL,
                "shopping", "shoppings", "shopping center", "shopping mall", "shopping_mall", "galeria", "galerias",
                "commercial.shopping_mall");

        register(map, InterestCategory.BEAUTY,
                "beleza", "barbearia", "barbearias", "salao de beleza", "saloes de beleza",
                "cabeleireiro", "cabeleireiros", "estetica", "manicure", "esmalteria", "esmalterias", "beauty",
                "service.beauty.hairdresser");

        register(map, InterestCategory.PET,
                "pet", "pets", "pet shop", "pet_shop", "petshop", "veterinario", "veterinarios",
                "veterinaria", "clinica veterinaria", "clinicas veterinarias", "pet shops",
                "pet.shop", "pet.veterinary");

        register(map, InterestCategory.AUTOMOTIVE,
                "automotivo", "posto", "postos", "posto de gasolina", "postos de gasolina",
                "posto de combustivel", "postos de combustivel", "oficina", "oficinas",
                "oficina mecanica", "mecanica", "auto eletrica", "auto eletricas",
                "borracharia", "borracharias", "lava rapido", "automotive",
                "service.vehicle.fuel", "service.vehicle.repair.car");

        // 4. LEISURE_AND_WELLBEING
        register(map, InterestCategory.PARK,
                "parque", "parques", "praca", "pracas", "bosque", "bosques",
                "jardim", "jardins", "parque ecologico", "park", "parks", "leisure.park");

        register(map, InterestCategory.FITNESS,
                "academia", "academias", "esporte", "esportes", "fitness", "ginastica",
                "crossfit", "pilates", "quadra esportiva", "quadras esportivas", "sport.fitness.gym");

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
                "hostel", "hostels", "albergue", "albergues", "viagem", "viagens", "travel",
                "accommodation.hotel", "accommodation.hostel");

        register(map, InterestCategory.HEALTH,
                "saude", "farmacia", "farmacias", "drogaria", "drogarias", "pharmacy", "pharmacies",
                "hospital", "hospitais", "clinica", "clinicas", "dentista", "dentistas", "odontologia", "health",
                "healthcare.pharmacy", "healthcare.hospital", "healthcare.clinic_or_praxis", "healthcare.dentist");

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

    /**
     * Retorna o conjunto de todas as chaves/termos mapeados que correspondem à categoria informada,
     * incluindo a constante do enum em maiúsculo e minúsculo.
     *
     * @param category constante canônica de {@link InterestCategory}
     * @return conjunto imutável de strings correspondentes
     */
    public static Set<String> getMatchingTerms(InterestCategory category) {
        if (category == null) {
            return Collections.emptySet();
        }
        Set<String> terms = new HashSet<>();
        terms.add(normalize(category.name()));
        terms.add(category.name());
        terms.add(category.name().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, InterestCategory> entry : MAPPING.entrySet()) {
            if (entry.getValue() == category) {
                terms.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(terms);
    }

    private static void register(Map<String, InterestCategory> map, InterestCategory category, String... terms) {
        for (String term : terms) {
            map.put(normalize(term), category);
        }
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return BrazilianState.fold(value).replace("_", " ").replace(".", " ").replaceAll("\\s+", " ").trim();
    }
}
