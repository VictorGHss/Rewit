package com.rewit.domain.enums;

/**
 * Perguntas do questionário inicial de interesses: cada uma agrupa parte do catálogo {@link InterestCategory}.
 *
 * <p>A ordem de declaração é a ordem em que o app apresenta as perguntas. O código é o nome da constante, estável na
 * API; a pergunta e o título são texto de interface e podem mudar sem migração.
 */
public enum InterestGroup {
    FOOD_AND_DRINK("Comer e beber", "Onde você gosta de comer e beber?"),
    SHOPPING_AND_SERVICES("Compras e serviços", "O que você costuma comprar ou contratar?"),
    LEISURE_AND_WELLBEING("Lazer e bem-estar", "Como você aproveita o tempo livre e cuida de si?");

    private final String title;
    private final String question;

    InterestGroup(String title, String question) {
        this.title = title;
        this.question = question;
    }

    public String getCode() {
        return name();
    }

    public String getTitle() {
        return title;
    }

    public String getQuestion() {
        return question;
    }
}
