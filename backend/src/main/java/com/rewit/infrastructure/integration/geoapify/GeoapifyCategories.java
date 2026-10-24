package com.rewit.infrastructure.integration.geoapify;

import com.rewit.domain.model.BrazilianState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Termos comuns em português que descrevem um <em>tipo</em> de estabelecimento, mapeados para categorias da
 * taxonomia oficial do Geoapify (https://apidocs.geoapify.com/docs/places/).
 *
 * <p>Só o termo inteiro conta ("padaria", "padarias"); um nome próprio ("Padaria do Tião") não é categoria e segue
 * para a busca por nome. Cada termo aponta para uma única categoria, ou para duas que o OpenStreetMap usa para o mesmo
 * tipo de lugar (ex.: farmácia), nunca para categorias diferentes misturadas.
 */
final class GeoapifyCategories {

    private static final Map<String, List<String>> BY_TERM = new HashMap<>();

    static {
        map(List.of("commercial.food_and_drink.bakery"), "padaria", "padarias", "panificadora", "panificadoras");
        map(List.of("catering.restaurant"), "restaurante", "restaurantes");
        map(List.of("catering.fast_food"), "lanchonete", "lanchonetes", "fast food", "hamburgueria", "hamburguerias");
        map(List.of("catering.cafe"), "cafe", "cafes", "cafeteria", "cafeterias");
        map(List.of("catering.bar"), "bar", "bares");
        map(List.of("catering.pub"), "pub", "pubs");
        map(List.of("catering.ice_cream"), "sorveteria", "sorveterias");
        map(List.of("commercial.supermarket"), "supermercado", "supermercados", "mercado", "mercados");
        map(List.of("commercial.convenience"), "mercearia", "mercearias", "loja de conveniencia", "conveniencia");
        map(List.of("commercial.marketplace"), "feira", "feiras", "feira livre");
        map(List.of("commercial.food_and_drink.butcher"), "acougue", "acougues");
        map(List.of("commercial.food_and_drink.fruit_and_vegetable"), "hortifruti", "sacolao", "quitanda", "quitandas");
        map(List.of("healthcare.pharmacy", "commercial.health_and_beauty.pharmacy"),
                "farmacia", "farmacias", "drogaria", "drogarias");
        map(List.of("healthcare.hospital"), "hospital", "hospitais");
        map(List.of("healthcare.clinic_or_praxis"), "clinica", "clinicas");
        map(List.of("healthcare.dentist"), "dentista", "dentistas");
        map(List.of("service.vehicle.fuel"), "posto", "postos", "posto de gasolina", "postos de gasolina",
                "posto de combustivel");
        map(List.of("accommodation.hotel"), "hotel", "hoteis");
        map(List.of("accommodation.hostel"), "hostel", "hostels", "albergue", "albergues");
        map(List.of("service.financial.bank"), "banco", "bancos");
        map(List.of("service.financial.atm"), "caixa eletronico", "caixas eletronicos");
        map(List.of("service.post.office"), "correios", "agencia dos correios");
        map(List.of("service.beauty.hairdresser"), "cabeleireiro", "cabeleireiros", "barbearia", "barbearias",
                "salao de beleza");
        map(List.of("pet.shop"), "pet shop", "petshop", "pet shops");
        map(List.of("pet.veterinary"), "veterinario", "veterinarios", "clinica veterinaria");
        map(List.of("sport.fitness.gym"), "academia", "academias");
        map(List.of("commercial.books"), "livraria", "livrarias");
        map(List.of("commercial.clothing"), "loja de roupas", "lojas de roupas");
        map(List.of("commercial.shopping_mall"), "shopping", "shoppings", "shopping center");
        map(List.of("commercial.houseware_and_hardware.hardware_and_tools",
                        "commercial.houseware_and_hardware.doityourself"),
                "ferragens", "loja de ferragens", "material de construcao", "materiais de construcao");
        map(List.of("service.vehicle.repair.car"), "oficina", "oficinas", "oficina mecanica", "mecanica");
        map(List.of("education.school"), "escola", "escolas");
        map(List.of("leisure.park"), "parque", "parques");
    }

    private GeoapifyCategories() {
    }

    private static void map(List<String> categories, String... terms) {
        for (String term : terms) {
            BY_TERM.put(term, categories);
        }
    }

    /** Categorias do termo inteiro, sem diferenciar acentos e maiúsculas; vazio quando o texto não é um tipo conhecido. */
    static Optional<List<String>> forQuery(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_TERM.get(BrazilianState.fold(query)));
    }
}
