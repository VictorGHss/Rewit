package com.rewit.application.port;

import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;

/**
 * Porta somente leitura de listagem paginada do Object Storage (Step 28.1).
 *
 * <p>Separada de {@link ObjectStoragePort} de propósito: quem depende apenas desta porta
 * (como a reconciliação) não tem como gravar nem remover objetos.
 */
public interface ObjectStorageListingPort {

    /**
     * Lista uma página de objetos sob o prefixo, em ordem lexicográfica de chave.
     *
     * @param prefix     prefixo obrigatório da listagem
     * @param startAfter retorna somente chaves estritamente maiores que este marcador; null para começar do início
     * @param maxKeys    tamanho máximo da página (positivo)
     * @return página com no máximo {@code maxKeys} objetos e o marcador da próxima página
     */
    StoredObjectPage listObjects(String prefix, String startAfter, int maxKeys);
}
