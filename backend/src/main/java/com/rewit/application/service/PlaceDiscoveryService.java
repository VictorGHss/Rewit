package com.rewit.application.service;

import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceCandidate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Serviço de aplicação para descoberta externa de locais (Step 8).
 * Orquestra validações de entrada, limites e chamadas à porta {@link PlaceDiscoveryPort}.
 * Os resultados são transitórios e NÃO sofrem persistência automática.
 */
@Service
public class PlaceDiscoveryService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    public static final int MIN_LIMIT = 1;
    public static final String DEFAULT_LANGUAGE_CODE = "pt-BR";
    public static final String SUPPORTED_PROVIDER = "GOOGLE";

    private final PlaceDiscoveryPort placeDiscoveryPort;

    public PlaceDiscoveryService(PlaceDiscoveryPort placeDiscoveryPort) {
        this.placeDiscoveryPort = Objects.requireNonNull(placeDiscoveryPort, "PlaceDiscoveryPort must not be null");
    }

    /**
     * Busca candidatos de locais externos por texto.
     */
    public List<PlaceCandidate> searchCandidates(
            String query,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Integer limit
    ) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("O termo de busca (query) é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_QUERY");
        }

        String normalizedQuery = query.trim();

        int resolvedLimit = DEFAULT_LIMIT;
        if (limit != null) {
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                throw new BusinessException(
                        "O limite de resultados deve ser entre " + MIN_LIMIT + " e " + MAX_LIMIT,
                        HttpStatus.BAD_REQUEST,
                        "INVALID_LIMIT"
                );
            }
            resolvedLimit = limit;
        }

        validateCoordinates(latitude, longitude, radiusMeters);

        return placeDiscoveryPort.searchByText(
                normalizedQuery,
                latitude,
                longitude,
                radiusMeters,
                resolvedLimit,
                DEFAULT_LANGUAGE_CODE
        );
    }

    /**
     * Obtém detalhes de um candidato externo através de seu identificador no provedor.
     */
    public PlaceCandidate getCandidateDetails(String provider, String externalId) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessException("O provedor é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_PROVIDER");
        }

        String normalizedProvider = provider.trim().toUpperCase();
        if (!SUPPORTED_PROVIDER.equals(normalizedProvider)) {
            throw new BusinessException(
                    "Provedor não suportado. Provedor suportado atualmente: " + SUPPORTED_PROVIDER,
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_PROVIDER"
            );
        }

        if (externalId == null || externalId.isBlank()) {
            throw new BusinessException("O identificador externo do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_ID");
        }

        return placeDiscoveryPort.getDetails(externalId.trim(), DEFAULT_LANGUAGE_CODE)
                .orElseThrow(() -> new BusinessException(
                        "Lugar externo não encontrado no provedor " + normalizedProvider + " para o id: " + externalId.trim(),
                        HttpStatus.NOT_FOUND,
                        "EXTERNAL_PLACE_NOT_FOUND"
                ));
    }

    private void validateCoordinates(Double latitude, Double longitude, Double radiusMeters) {
        if (latitude == null && longitude == null) {
            return;
        }

        if (latitude == null || longitude == null) {
            throw new BusinessException(
                    "Ambas latitude e longitude devem ser informadas para viés geográfico",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COORDINATES"
            );
        }

        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new BusinessException(
                    "Coordenadas geográficas fora do elipsoide WGS 84 (lat -90 a 90, lng -180 a 180)",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COORDINATES"
            );
        }

        if (radiusMeters != null && radiusMeters <= 0) {
            throw new BusinessException("O raio de busca deve ser estritamente positivo", HttpStatus.BAD_REQUEST, "INVALID_RADIUS");
        }
    }
}
