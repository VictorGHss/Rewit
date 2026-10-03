package com.rewit.application.dto.storage;

/**
 * Resultado explícito de uma exclusão no Object Storage (Step 28.4).
 */
public enum ObjectDeletionResult {
    /** O objeto existia e foi removido. */
    DELETED,
    /** O objeto já não existia: o estado desejado já foi alcançado (sucesso idempotente). */
    NOT_FOUND,
    /** Falha possivelmente temporária (rede, indisponibilidade, erro do servidor); repetir é seguro. */
    TRANSIENT_FAILURE,
    /** Falha de autorização/configuração/contrato que não se resolve repetindo. */
    PERMANENT_FAILURE;

    /** @return true se, após a operação, o objeto comprovadamente não existe no storage */
    public boolean isAbsentAfterwards() {
        return this == DELETED || this == NOT_FOUND;
    }
}
