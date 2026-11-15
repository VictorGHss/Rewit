package com.rewit.domain.enums;

/**
 * Resultado da verificação de presença feita na criação de uma avaliação. Só {@link #VERIFIED} gera check-in
 * verificado; os demais publicam a avaliação sem o selo de presença.
 */
public enum PresenceVerificationOutcome {

    /** Presença confirmada pelo backend: check-in VERIFIED vinculado à avaliação. */
    VERIFIED,

    /** Sem local de contexto ou sem coordenadas: nenhuma verificação foi pedida (ex.: o app não obteve a localização). */
    NOT_REQUESTED,

    /** O local de contexto não tem coordenadas cadastradas; não há como comparar distâncias. */
    PLACE_WITHOUT_COORDINATES,

    /** Coordenadas enviadas sem a precisão horizontal do GPS (ou com valor não numérico). */
    ACCURACY_UNAVAILABLE,

    /** Precisão horizontal informada acima do máximo aceito para verificar a presença. */
    ACCURACY_TOO_LOW,

    /** Fora do raio de validação do local. */
    OUTSIDE_RADIUS,

    /** Já existe check-in verificado do mesmo usuário no mesmo local dentro da janela de frequência. */
    RECENT_CHECK_IN
}
