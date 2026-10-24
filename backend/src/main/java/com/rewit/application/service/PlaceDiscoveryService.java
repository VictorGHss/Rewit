package com.rewit.application.service;

import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BrazilianState;
import com.rewit.domain.model.PlaceCandidate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Serviço de aplicação para descoberta externa de locais (Step 8).
 * Orquestra validações de entrada, limites e chamadas à porta {@link PlaceDiscoveryPort}.
 * Os resultados são transitórios e NÃO sofrem persistência automática.
 *
 * <p>Há um único provedor ativo (o adapter registrado pela configuração); identificadores de outro provedor não
 * são consultados em nenhum outro, sem fallback.
 */
@Service
public class PlaceDiscoveryService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    public static final int MIN_LIMIT = 1;
    public static final String DEFAULT_LANGUAGE_CODE = "pt-BR";
    public static final int MAX_CITY_LENGTH = 100;

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
        return searchCandidates(query, latitude, longitude, radiusMeters, limit, null, null);
    }

    /**
     * Busca candidatos externos numa região escolhida pelo usuário: uma cidade com UF (resultados de outras cidades
     * são excluídos) ou coordenadas com raio. Cidade e coordenadas são alternativas: a cidade escolhida nunca é
     * substituída pela localização do aparelho. Sem nenhuma das duas, a busca não tem restrição geográfica.
     */
    public List<PlaceCandidate> searchCandidates(
            String query,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Integer limit,
            String city,
            String state
    ) {
        String normalizedCity = city == null || city.isBlank() ? null : city.trim().replaceAll("\\s+", " ");
        String normalizedState = state == null || state.isBlank() ? null : state.trim();
        if (normalizedCity == null && normalizedState == null) {
            return searchWithoutCity(query, latitude, longitude, radiusMeters, limit);
        }
        String normalizedQuery = normalizedQuery(query);
        int resolvedLimit = resolveLimit(limit);
        if (normalizedCity == null) {
            throw new BusinessException("Informe a cidade junto com a UF", HttpStatus.BAD_REQUEST, "INVALID_REGION");
        }
        if (normalizedCity.length() > MAX_CITY_LENGTH) {
            throw new BusinessException("O nome da cidade deve conter até " + MAX_CITY_LENGTH + " caracteres",
                    HttpStatus.BAD_REQUEST, "INVALID_REGION");
        }
        BrazilianState uf = BrazilianState.fromCode(normalizedState).orElseThrow(() -> new BusinessException(
                "Informe a UF da cidade com uma das 27 siglas oficiais (ex.: PR)", HttpStatus.BAD_REQUEST, "INVALID_REGION"));
        if (latitude != null || longitude != null || radiusMeters != null) {
            throw new BusinessException("Use cidade ou coordenadas na busca, não ambos", HttpStatus.BAD_REQUEST,
                    "INVALID_REGION");
        }
        return placeDiscoveryPort.searchByTextInCity(
                normalizedQuery, normalizedCity, uf, resolvedLimit, DEFAULT_LANGUAGE_CODE);
    }

    private List<PlaceCandidate> searchWithoutCity(
            String query,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Integer limit
    ) {
        String normalizedQuery = normalizedQuery(query);
        int resolvedLimit = resolveLimit(limit);

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

    private static String normalizedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("O termo de busca (query) é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_QUERY");
        }
        return query.trim();
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(
                    "O limite de resultados deve ser entre " + MIN_LIMIT + " e " + MAX_LIMIT,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_LIMIT"
            );
        }
        return limit;
    }

    /**
     * Obtém detalhes de um candidato externo através de seu identificador no provedor.
     */
    public PlaceCandidate getCandidateDetails(String provider, String externalId) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessException("O provedor é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_PROVIDER");
        }

        String normalizedProvider = provider.trim().toUpperCase(Locale.ROOT);
        String activeProvider = getActiveProvider();
        if (!activeProvider.equals(normalizedProvider)) {
            throw new BusinessException(
                    "Provedor não suportado. Provedor ativo: " + activeProvider,
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

    /** Nome do provedor do adapter ativo, em maiúsculas (ex.: "GOOGLE", "GEOAPIFY"). */
    public String getActiveProvider() {
        String name = placeDiscoveryPort.getProviderName();
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
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

        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new BusinessException(
                    "Coordenadas geográficas fora do elipsoide WGS 84 (lat -90 a 90, lng -180 a 180)",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COORDINATES"
            );
        }

        if (radiusMeters != null && (!Double.isFinite(radiusMeters) || radiusMeters <= 0)) {
            throw new BusinessException("O raio de busca deve ser estritamente positivo", HttpStatus.BAD_REQUEST, "INVALID_RADIUS");
        }
    }
}
