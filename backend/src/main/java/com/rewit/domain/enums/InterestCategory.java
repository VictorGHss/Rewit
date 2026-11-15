package com.rewit.domain.enums;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

/**
 * Catálogo controlado dos interesses que o usuário declara no questionário inicial ou no perfil.
 *
 * <p>Cada interesse é um tipo de lugar ou de experiência do mundo físico, alinhado aos tipos de estabelecimento que o
 * catálogo já reconhece na descoberta ({@code GeoapifyCategories}) e aos exemplos de {@code UserInterest} em
 * {@code docs/domain/entities.md}. Não há texto livre: o código é o nome da constante, estável na API, no banco
 * ({@code user_interests.category_code}) e para o Feed V2. Incluir um interesse exige uma nova constante e uma
 * migração que amplie {@code chk_user_interest_category_code}; remover exige antes tratar as seleções gravadas.
 */
public enum InterestCategory {
    RESTAURANT(InterestGroup.FOOD_AND_DRINK, "Restaurantes"),
    FAST_FOOD(InterestGroup.FOOD_AND_DRINK, "Lanchonetes e hamburguerias"),
    CAFE(InterestGroup.FOOD_AND_DRINK, "Cafés e cafeterias"),
    BAKERY(InterestGroup.FOOD_AND_DRINK, "Padarias"),
    BAR(InterestGroup.FOOD_AND_DRINK, "Bares e pubs"),
    ICE_CREAM(InterestGroup.FOOD_AND_DRINK, "Sorveterias e doces"),

    SUPERMARKET(InterestGroup.SHOPPING_AND_SERVICES, "Supermercados e feiras"),
    FASHION(InterestGroup.SHOPPING_AND_SERVICES, "Moda e vestuário"),
    BOOKS(InterestGroup.SHOPPING_AND_SERVICES, "Livrarias"),
    TECHNOLOGY(InterestGroup.SHOPPING_AND_SERVICES, "Tecnologia e eletrônicos"),
    SHOPPING_MALL(InterestGroup.SHOPPING_AND_SERVICES, "Shoppings"),
    BEAUTY(InterestGroup.SHOPPING_AND_SERVICES, "Beleza e barbearias"),
    PET(InterestGroup.SHOPPING_AND_SERVICES, "Pet shops e veterinários"),
    AUTOMOTIVE(InterestGroup.SHOPPING_AND_SERVICES, "Postos e oficinas"),

    PARK(InterestGroup.LEISURE_AND_WELLBEING, "Parques e atividades ao ar livre"),
    FITNESS(InterestGroup.LEISURE_AND_WELLBEING, "Academias e esportes"),
    CULTURE(InterestGroup.LEISURE_AND_WELLBEING, "Cinema, teatro e museus"),
    EVENTS(InterestGroup.LEISURE_AND_WELLBEING, "Shows, festivais e eventos"),
    GAMES(InterestGroup.LEISURE_AND_WELLBEING, "Games e entretenimento"),
    TRAVEL(InterestGroup.LEISURE_AND_WELLBEING, "Hotéis e viagens"),
    HEALTH(InterestGroup.LEISURE_AND_WELLBEING, "Saúde e farmácias");

    private final InterestGroup group;
    private final String label;

    InterestCategory(InterestGroup group, String label) {
        this.group = group;
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public InterestGroup getGroup() {
        return group;
    }

    /** Interesses de uma pergunta, na ordem do catálogo. */
    public static List<InterestCategory> ofGroup(InterestGroup group) {
        return Arrays.stream(values()).filter(category -> category.group == group).toList();
    }

    /** Código exato da API; ausente ou fora do catálogo responde {@code 400 INVALID_INTEREST_CODE}. */
    public static InterestCategory fromCode(String code) {
        if (code != null) {
            for (InterestCategory category : values()) {
                if (category.name().equals(code)) {
                    return category;
                }
            }
        }
        throw new BusinessException("Interesse desconhecido: use um código do catálogo de interesses",
                HttpStatus.BAD_REQUEST, "INVALID_INTEREST_CODE");
    }
}
