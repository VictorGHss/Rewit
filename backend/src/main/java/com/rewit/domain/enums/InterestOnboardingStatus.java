package com.rewit.domain.enums;

/**
 * Situação do questionário inicial de interesses de uma conta.
 */
public enum InterestOnboardingStatus {
    /** Nenhuma resposta gravada: o app apresenta o questionário desde a primeira pergunta. */
    NOT_STARTED,
    /** Respostas parciais gravadas e questionário ainda não confirmado: o app retoma de onde parou. */
    IN_PROGRESS,
    /** Questionário confirmado; não volta a ser apresentado. Os interesses continuam editáveis pelo perfil. */
    COMPLETED
}
