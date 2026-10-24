package com.rewit.infrastructure.integration.geoapify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BrazilianState;
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
import org.springframework.web.util.UriBuilder;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Adapter de descoberta de locais pelo Geoapify (dados do OpenStreetMap), ativo com
 * {@code rewit.place-discovery.provider=GEOAPIFY}.
 *
 * <p>Busca: Geocoding API ({@code /v1/geocode/search}) com {@code type=amenity}, porque ela aceita texto livre; a
 * Places API exige categorias. Só estabelecimentos com nome viram candidatos: endereço, rua ou cidade geocodificados
 * não são estabelecimentos. Detalhes: Place Details API ({@code /v2/place-details}). Nada é inventado: sem nota,
 * horários ou avaliações, que a fonte não fornece.
 *
 * <p>Região: com cidade e UF, a cidade é resolvida uma vez ({@code type=city}, {@code filter=countrycode:br}, cache em
 * memória) e a busca usa {@code filter=place:<id da cidade>}, que exclui resultados de fora da fronteira. Com
 * coordenadas, {@code filter=circle} (raio padrão de 10 km, teto de 50 km). Sem região, a busca não é restrita.
 *
 * <p>UF: para locais do Brasil ({@code country_code=br}) vale só uma das 27 siglas oficiais, vinda de
 * {@code state_code} ou do nome oficial do estado; regiões como "Sul" ficam nulas. Fora do Brasil, o código ou o nome
 * informado pelo provedor, sem regras de UF; sem país, o estado fica nulo.
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
    public static final String RESULT_TYPE_CITY = "city";
    public static final double MAX_RADIUS_METERS = 50_000.0;
    public static final double DEFAULT_RADIUS_METERS = 10_000.0;
    static final int CITY_CACHE_SIZE = 256;

    /** Atribuições exigidas pelos termos do Geoapify e pela licença ODbL do OpenStreetMap. */
    public static final PlaceAttribution GEOAPIFY_ATTRIBUTION =
            new PlaceAttribution("Powered by Geoapify", "https://www.geoapify.com/");
    public static final PlaceAttribution OSM_ATTRIBUTION =
            new PlaceAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright");

    private final GeoapifyProperties properties;
    private final RestClient restClient;

    /** Cidade já resolvida: evita gastar cota a cada busca na mesma cidade. Só ids e centro, nada do usuário. */
    private final Map<String, ResolvedCity> cityCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResolvedCity> eldest) {
                    return size() > CITY_CACHE_SIZE;
                }
            });

    record ResolvedCity(String placeId, double latitude, double longitude) {}

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
        return search(query, limit, toIsoLanguage(languageCode), uriBuilder -> {
            if (latitude != null && longitude != null) {
                double radius = radiusMeters != null && radiusMeters > 0
                        ? Math.min(radiusMeters, MAX_RADIUS_METERS) : DEFAULT_RADIUS_METERS;
                uriBuilder.queryParam("bias", "proximity:" + longitude + "," + latitude)
                        .queryParam("filter", "circle:" + longitude + "," + latitude + "," + radius);
            }
        });
    }

    @Override
    public List<PlaceCandidate> searchByTextInCity(
            String query,
            String city,
            BrazilianState state,
            Integer limit,
            String languageCode
    ) {
        validateConfiguration();
        String lang = toIsoLanguage(languageCode);
        ResolvedCity resolved = resolveCity(city, state, lang);
        return search(query, limit, lang, uriBuilder -> uriBuilder
                .queryParam("filter", "place:" + resolved.placeId())
                .queryParam("bias", "proximity:" + resolved.longitude() + "," + resolved.latitude()));
    }

    private List<PlaceCandidate> search(String query, Integer limit, String lang, Consumer<UriBuilder> region) {
        int resolvedLimit = (limit != null && limit >= 1 && limit <= 20) ? limit : 10;
        GeoapifySearchResponse response = fetch(uriBuilder -> {
            uriBuilder.path(SEARCH_PATH)
                    .queryParam("text", query)
                    .queryParam("type", RESULT_TYPE_AMENITY)
                    .queryParam("format", "json")
                    .queryParam("lang", lang)
                    .queryParam("limit", resolvedLimit);
            region.accept(uriBuilder);
            return uriBuilder.queryParam("apiKey", properties.getApiKey()).build();
        }, GeoapifySearchResponse.class, "busca");
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return Collections.emptyList();
        }
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (GeoapifyPlace place : response.results()) {
            toCandidate(place).ifPresent(candidates::add);
        }
        return Collections.unmodifiableList(candidates);
    }

    /**
     * Fronteira da cidade pedida. Só vale um resultado do tipo cidade, com o mesmo nome (sem diferenciar acentos) e a
     * mesma UF: na dúvida a busca é recusada, nunca feita em outra cidade.
     */
    private ResolvedCity resolveCity(String city, BrazilianState state, String lang) {
        String key = BrazilianState.fold(city) + "|" + state.name();
        ResolvedCity cached = cityCache.get(key);
        if (cached != null) {
            return cached;
        }
        GeoapifySearchResponse response = fetch(uriBuilder -> uriBuilder.path(SEARCH_PATH)
                .queryParam("city", city)
                .queryParam("state", state.getOfficialName())
                .queryParam("country", "Brasil")
                .queryParam("type", RESULT_TYPE_CITY)
                .queryParam("filter", "countrycode:br")
                .queryParam("format", "json")
                .queryParam("lang", lang)
                .queryParam("limit", 5)
                .queryParam("apiKey", properties.getApiKey())
                .build(), GeoapifySearchResponse.class, "resolução da cidade");
        String wanted = BrazilianState.fold(city);
        ResolvedCity resolved = response == null || response.results() == null ? null : response.results().stream()
                .filter(Objects::nonNull)
                .filter(p -> RESULT_TYPE_CITY.equalsIgnoreCase(p.resultType()))
                .filter(p -> p.placeId() != null && !p.placeId().isBlank())
                .filter(p -> isValidCoordinate(p.lat(), p.lon()))
                .filter(p -> state.name().equals(stateFor(p.countryCode(), p.stateCode(), p.state())))
                .filter(p -> (p.city() != null && BrazilianState.fold(p.city()).equals(wanted))
                        || (p.name() != null && BrazilianState.fold(p.name()).equals(wanted)))
                .findFirst()
                .map(p -> new ResolvedCity(p.placeId(), p.lat(), p.lon()))
                .orElse(null);
        if (resolved == null) {
            throw new BusinessException("Cidade não encontrada para a UF informada. Confira o nome e a UF.",
                    HttpStatus.UNPROCESSABLE_CONTENT, "REGION_NOT_FOUND");
        }
        cityCache.put(key, resolved);
        return resolved;
    }

    /** GET no Geoapify; 404 vira {@code null}. Falhas são traduzidas sem registrar URL nem mensagem (contêm a chave). */
    private <T> T fetch(Function<UriBuilder, URI> uri, Class<T> type, String operation) {
        try {
            return restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(type);
        } catch (ResourceAccessException ex) {
            throw resourceAccessFailure(ex);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw httpFailure(ex);
        } catch (RestClientException ex) {
            log.error("Resposta inesperada do Geoapify na {} (erro={})", operation, ex.getClass().getSimpleName());
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
                    stateFor(place.countryCode(), place.stateCode(), place.state()),
                    place.countryCode()
            ));
        } catch (BusinessException invalid) {
            // Coordenada fora do WGS 84 ou campo inválido vindo do provedor: descarta o item, não a busca
            log.warn("Candidato do Geoapify descartado por dados inválidos (codigo={})", invalid.getErrorCode());
            return Optional.empty();
        }
    }

    /**
     * Estado validado. Brasil: UF oficial por {@code state_code} ou pelo nome oficial; região ("Sul") ou sigla
     * desconhecida → {@code null}. Outro país: código ou nome do provedor, sem regras de UF. Sem país → {@code null}.
     */
    static String stateFor(String countryCode, String stateCode, String stateName) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }
        if ("BR".equalsIgnoreCase(countryCode.trim())) {
            return BrazilianState.fromCode(stateCode)
                    .or(() -> BrazilianState.fromOfficialName(stateName))
                    .map(Enum::name)
                    .orElse(null);
        }
        if (stateCode != null && !stateCode.isBlank()) {
            return stateCode.trim().toUpperCase(Locale.ROOT);
        }
        return stateName == null || stateName.isBlank() ? null : stateName.trim();
    }

    private static boolean isValidCoordinate(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0;
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
