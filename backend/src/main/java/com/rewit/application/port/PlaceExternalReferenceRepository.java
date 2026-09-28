package com.rewit.application.port;

import com.rewit.domain.model.PlaceExternalReference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída (Output Port) para operações de persistência e consulta
 * da entidade PlaceExternalReference (vínculo de identidade externa).
 */
public interface PlaceExternalReferenceRepository {

    /**
     * Persiste ou atualiza uma referência externa.
     *
     * @param externalReference a referência a ser salva
     * @return a referência persistida
     */
    PlaceExternalReference save(PlaceExternalReference externalReference);

    /**
     * Localiza uma referência externa pela combinação única de provedor e identificador externo.
     * Exemplo: provider = "GOOGLE", externalId = "ChIJN1t_tDeuEmsRUsoyG83frY4"
     *
     * @param provider   o nome do provedor (ex: GOOGLE)
     * @param externalId o identificador fornecido pelo provedor externo
     * @return Optional contendo a referência se encontrada
     */
    Optional<PlaceExternalReference> findByProviderAndExternalId(String provider, String externalId);

    /**
     * Lista todas as referências externas associadas a um determinado local interno do catálogo.
     *
     * @param placeId o UUID do local no catálogo Rewit
     * @return lista de referências externas vinculadas
     */
    List<PlaceExternalReference> findByPlaceId(UUID placeId);

    /**
     * Verifica a existência de uma referência externa para o par provedor e identificador externo.
     *
     * @param provider   o nome do provedor
     * @param externalId o identificador externo
     * @return true se já existir vínculo registrado
     */
    boolean existsByProviderAndExternalId(String provider, String externalId);

    /**
     * Remove uma referência externa pelo seu identificador primário.
     *
     * @param id o UUID da referência externa
     */
    void deleteById(UUID id);
}
