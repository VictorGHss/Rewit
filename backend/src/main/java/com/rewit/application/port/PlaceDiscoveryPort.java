package com.rewit.application.port;

import com.rewit.domain.model.PlaceCandidate;

import java.util.List;
import java.util.Optional;

/**
 * Porta da aplicação para descoberta de locais em fontes externas (ex: Google Places API New).
 * Abstrai completamente os detalhes de transporte HTTP, URLs, cabeçalhos e JSON proprietários dos provedores.
 */
public interface PlaceDiscoveryPort {

    /**
     * Retorna o identificador do provedor externo suportado por este adapter (ex: "GOOGLE").
     */
    String getProviderName();

    /**
     * Realiza busca textual por candidatos de lugares externos.
     *
     * @param query          Texto de busca obrigatório (ex: "Café São Paulo").
     * @param latitude       Latitude opcional para viés espacial.
     * @param longitude      Longitude opcional para viés espacial.
     * @param radiusMeters   Raio em metros para viés espacial (opcional).
     * @param limit          Limite de resultados desejado.
     * @param languageCode   Código de idioma (ex: "pt-BR").
     * @return Lista de candidatos retornados pelo provedor externo.
     */
    List<PlaceCandidate> searchByText(
            String query,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Integer limit,
            String languageCode
    );

    /**
     * Obtém os detalhes de um candidato específico a partir de seu identificador externo.
     *
     * @param externalPlaceId Identificador do local no provedor externo (ex: Google Place ID).
     * @param languageCode    Código de idioma (ex: "pt-BR").
     * @return Candidato correspondente ou Optional.empty se inexistente no provedor.
     */
    Optional<PlaceCandidate> getDetails(String externalPlaceId, String languageCode);
}
