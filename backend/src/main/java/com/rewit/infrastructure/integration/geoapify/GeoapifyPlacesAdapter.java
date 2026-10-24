package com.rewit.infrastructure.integration.geoapify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceAttribution;
import com.rewit.domain.model.PlaceCandidate;
import com.rewit.infrastructure.integration.PlaceDiscoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter de descoberta de locais pelo Geoapify (dados do OpenStreetMap), ativo com
 * {@code rewit.place-discovery.provider=GEOAPIFY}.
 *
 * <p>Busca: Geocoding API ({@code /v1/geocode/search}) com {@code type=amenity}, porque ela aceita texto livre; a
 * Places API exige categorias. Só estabelecimentos com nome viram candidatos: endereço, rua ou cidade geocodificados
 * não são estabelecimentos. Detalhes: Place Details API ({@code /v2/place-details}). Nada é inventado: sem nota,
 * horários ou avaliações, que a fonte não fornece.
 *
 * <p>A chave vai na query string ({@code apiKey}), o único meio que o Geoapify aceita. Por isso nenhum log registra
 * URL ou mensagem de exceção (só o tipo e o status), e este cliente não usa o {@code RestClient.Builder} do Spring:
 * a observação HTTP gravaria a URL completa, com a chave, nos spans.
 */
@Component
@ConditionalOnProperty(name = PlaceDiscoveryProperties.PROPERTY, havingValue = "GEOAPIFY")
public class GeoapifyPlacesAdapter implements PlaceDiscoveryPort {

    private static final Logger log = LoggerFactory.getLogger(GeoapifyPlacesAdapter.class);

    public static final String PROVIDER_NAME = "GEOAPIFY";
    public static final String SEARCH_PATH = "/v1/geocode/search";
    public static final String DETAILS_PATH = "/v2/place-details";
    public static final String RESULT_TYPE_AMENITY = "amenity";
    public static final double MAX_RADIUS_METERS = 50_000.0;

    /** Atribuições exigidas pelos termos do Geoapify e pela licença ODbL do OpenStreetMap. */
    public static final PlaceAttribution GEOAPIFY_ATTRIBUTION =
            new PlaceAttribution("Powered by Geoapify", "https://www.geoapify.com/");
    public static final PlaceAttribution OSM_ATTRIBUTION =
            new PlaceAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright");

    private final GeoapifyProperties properties;
    private final RestClient restClient;

    @Autowired
    public GeoapifyPlacesAdapter(GeoapifyProperties properties) {
        this(properties, RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(createRequestFactory(properties))
                .build());
    }

    /** Construtor para injeção direta de RestClient (testes com MockRestServiceServer). */
    public GeoapifyPlacesAdapter(GeoapifyProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "GeoapifyProperties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "RestClient must not be null");
    }

    private static SimpleClientHttpRequestFactory createRequestFactory(GeoapifyProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return requestFactory;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<PlaceCandidate> searchByText(
            String query,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            Integer limit,
            String languageCode
    ) {
        validateConfiguration();
        int resolvedLimit = (limit != null && limit >= 1 && limit <= 20) ? limit : 10;
        String lang = toIsoLanguage(languageCode);
        try {
            GeoapifySearchResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(SEARCH_PATH)
                                .queryParam("text", query)
                                .queryParam("type", RESULT_TYPE_AMENITY)
                                .queryParam("format", "json")
                                .queryParam("lang", lang)
                                .queryParam("limit", resolvedLimit);
                        if (latitude != null && longitude != null) {
                            uriBuilder.queryParam("bias", "proximity:" + longitude + "," + latitude);
                            if (radiusMeters != null && radiusMeters > 0) {
                                double radius = Math.min(radiusMeters, MAX_RADIUS_METERS);
                                uriBuilder.queryParam("filter", "circle:" + longitude + "," + latitude + "," + radius);
                            }
                        }
                        return uriBuilder.queryParam("apiKey", properties.getApiKey()).build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GeoapifySearchResponse.class);
            if (response == null || response.results() == null || response.results().isEmpty()) {
                return Collections.emptyList();
            }
            List<PlaceCandidate> candidates = new ArrayList<>();
            for (GeoapifyPlace place : response.results()) {
                toCandidate(place).ifPresent(candidates::add);
            }
            return Collections.unmodifiableList(candidates);
        } catch (ResourceAccessException ex) {
            throw resourceAccessFailure(ex);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Collections.emptyList();
            }
            throw httpFailure(ex);
        } catch (RestClientException ex) {
            log.error("Resposta inesperada da busca do Geoapify (erro={})", ex.getClass().getSimpleName());
            throw invalidResponse();
        }
    }

    @Override
    public Optional<PlaceCandidate> getDetails(String externalPlaceId, String languageCode) {
        validateConfiguration();
        if (externalPlaceId == null || externalPlaceId.isBlank()) {
            throw new BusinessException("Identificador externo do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_ID");
        }
        String lang = toIsoLanguage(languageCode);
        try {
            GeoapifyFeatureCollection response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(DETAILS_PATH)
                            .queryParam("id", externalPlaceId.trim())
                            .queryParam("features", "details")
                            .queryParam("lang", lang)
                            .queryParam("apiKey", properties.getApiKey())
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GeoapifyFeatureCollection.class);
            if (response == null || response.features() == null) {
                return Optional.empty();
            }
            return response.features().stream()
                    .filter(Objects::nonNull)
                    .map(GeoapifyFeature::properties)
                    .filter(Objects::nonNull)
                    .filter(p -> p.featureType() == null || "details".equalsIgnoreCase(p.featureType()))
                    .findFirst()
                    // Detalhes de um id conhecido: a busca já garantiu que é estabelecimento; vale só exigir nome
                    .flatMap(p -> toCandidate(p, false));
        } catch (ResourceAccessException ex) {
            throw resourceAccessFailure(ex);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw httpFailure(ex);
        } catch (RestClientException ex) {
            log.error("Resposta inesperada dos detalhes do Geoapify (erro={})", ex.getClass().getSimpleName());
            throw invalidResponse();
        }
    }

    private void validateConfiguration() {
        if (!properties.isConfigured()) {
            log.warn("Descoberta de locais pelo Geoapify não configurada. Defina GEOAPIFY_API_KEY.");
            throw new BusinessException(
                    "Integração com o Geoapify não configurada no servidor",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EXTERNAL_SERVICE_NOT_CONFIGURED"
            );
        }
    }

    /** "pt-BR" vira "pt": o Geoapify aceita só o código ISO 639-1 de 2 letras. */
    static String toIsoLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return "pt";
        }
        String primary = languageCode.trim().split("[-_]")[0].toLowerCase(Locale.ROOT);
        return primary.length() == 2 ? primary : "pt";
    }

    private Optional<PlaceCandidate> toCandidate(GeoapifyPlace place) {
        return toCandidate(place, true);
    }

    private Optional<PlaceCandidate> toCandidate(GeoapifyPlace place, boolean requireAmenity) {
        if (place == null || place.placeId() == null || place.placeId().isBlank()
                || place.name() == null || place.name().isBlank()) {
            return Optional.empty();
        }
        if (requireAmenity && place.resultType() != null && !RESULT_TYPE_AMENITY.equalsIgnoreCase(place.resultType())) {
            return Optional.empty();
        }
        List<String> types = new ArrayList<>();
        if (place.categories() != null) {
            place.categories().stream().filter(c -> c != null && !c.isBlank()).forEach(types::add);
        }
        if (place.category() != null && !place.category().isBlank() && !types.contains(place.category())) {
            types.add(place.category());
        }
        try {
            return Optional.of(new PlaceCandidate(
                    PROVIDER_NAME,
                    place.placeId(),
                    place.name(),
                    place.formatted() != null ? place.formatted() : "",
                    place.lat(),
                    place.lon(),
                    types,
                    attributionsOf(place.datasource()),
                    place.city(),
                    // UF (ex.: "PR") quando o provedor informa; senão o nome do estado
                    place.stateCode() != null && !place.stateCode().isBlank()
                            ? place.stateCode().toUpperCase(Locale.ROOT) : place.state(),
                    place.countryCode()
            ));
        } catch (BusinessException invalid) {
            // Coordenada fora do WGS 84 ou campo inválido vindo do provedor: descarta o item, não a busca
            log.warn("Candidato do Geoapify descartado por dados inválidos (codigo={})", invalid.getErrorCode());
            return Optional.empty();
        }
    }

    private static List<PlaceAttribution> attributionsOf(GeoapifyDatasource datasource) {
        List<PlaceAttribution> attributions = new ArrayList<>();
        attributions.add(GEOAPIFY_ATTRIBUTION);
        if (datasource != null && datasource.attribution() != null && !datasource.attribution().isBlank()) {
            attributions.add(new PlaceAttribution(datasource.attribution(), datasource.url()));
        } else {
            attributions.add(OSM_ATTRIBUTION);
        }
        return Collections.unmodifiableList(attributions);
    }

    private BusinessException resourceAccessFailure(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            log.error("Timeout de conexão/leitura com o Geoapify.");
            return new BusinessException("Tempo limite excedido na comunicação com o Geoapify",
                    HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_SERVICE_TIMEOUT");
        }
        log.error("Geoapify indisponível ou inalcançável (erro={})", ex.getClass().getSimpleName());
        return new BusinessException("Serviço Geoapify indisponível", HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_UNAVAILABLE");
    }

    private static boolean isTimeout(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private BusinessException httpFailure(HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        if (status == 429 || status >= 500) {
            log.error("Geoapify retornou status transitório ou limite de cota: {}", status);
            return new BusinessException(
                    "Serviço Geoapify temporariamente indisponível ou limite de requisições excedido",
                    HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_SERVICE_UNAVAILABLE");
        }
        if (status == 401 || status == 403) {
            log.error("Geoapify recusou a credencial (status {}). Verifique GEOAPIFY_API_KEY.", status);
        } else {
            log.error("Geoapify retornou erro de cliente (status {}).", status);
        }
        return new BusinessException("Falha na comunicação com o serviço externo de lugares",
                HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR");
    }

    private static BusinessException invalidResponse() {
        return new BusinessException("Resposta inválida recebida do Geoapify", HttpStatus.BAD_GATEWAY,
                "EXTERNAL_SERVICE_INVALID_RESPONSE");
    }

    // --- DTOs internos exclusivos da integração com o Geoapify ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoapifySearchResponse(List<GeoapifyPlace> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoapifyFeatureCollection(List<GeoapifyFeature> features) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoapifyFeature(GeoapifyPlace properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoapifyPlace(
            @JsonProperty("place_id") String placeId,
            String name,
            String formatted,
            Double lat,
            Double lon,
            @JsonProperty("result_type") String resultType,
            @JsonProperty("feature_type") String featureType,
            String category,
            List<String> categories,
            GeoapifyDatasource datasource,
            String city,
            String state,
            @JsonProperty("state_code") String stateCode,
            @JsonProperty("country_code") String countryCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoapifyDatasource(String sourcename, String attribution, String license, String url) {}
}
