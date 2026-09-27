package com.rewit.domain.enums;

/**
 * Identifica o tipo de entidade avaliada dentro de uma publicação multi-alvo.
 */
public enum TargetType {
    PLACE,      // Estabelecimento físico (ambiente, localização, estrutura)
    SERVICE,    // Atendimento, velocidade, entrega, suporte
    PRODUCT,    // Item consumido (prato, bebida, produto manufaturado)
    EVENT       // Evento temporal no local
}
