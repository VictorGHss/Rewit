package com.rewit.domain.enums;

/**
 * Por que uma representação comercial (conta e local de uma resposta oficial) não está elegível agora (C11, decisão
 * B). Os critérios são independentes: uma representação pode reunir mais de um motivo.
 */
public enum BusinessRepresentationIneligibility {
    /** A conta comercial não está aprovada. */
    ACCOUNT_NOT_APPROVED,
    /** O administrador atual da conta não pode operar; o estado pessoal (desativado, suspenso, excluído) não é exposto. */
    ADMINISTRATOR_NOT_OPERATIONAL,
    /** O local não está ativo ou não está vinculado agora à conta. */
    PLACE_NOT_REPRESENTED
}
