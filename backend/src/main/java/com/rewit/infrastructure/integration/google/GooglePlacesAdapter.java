package com.rewit.infrastructure.integration.google;

import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;

/**
 * Adaptador de infraestrutura para integração com o Google Places API (New).
 * Implementa a porta {@link PlaceDiscoveryPort} de forma isolada, sem expor contratos ou detalhes
 * do Google para o núcleo da aplicação.
 */
@Component
public class GooglePlacesAdapter implements PlaceDiscoveryPort {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesAdapter.class);

    public static final String PROVIDER_NAME = "GOOGLE";
    public static final String HEADER_API_KEY = "X-Goog-Api-Key";
    public static final String HEADER_FIELD_MASK = "X-Goog-FieldMask";

    public static final String FIELD_MASK_TEXT_SEARCH = "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.attributions";
    public static final String FIELD_MASK_PLACE_DETAILS = "id,displayName,formattedAddress,location,types,attributions";

    private final GooglePlacesProperties properties;
    private final RestClient restClient;

    @Autowired
    public GooglePlacesAdapter(
            GooglePlacesProperties properties,
            @Autowired(required = false) RestClient.Builder restClientBuilder
    ) {
        this(properties, (restClientBuilder != null ? restClientBuilder : RestClient.builder())
                .baseUrl(properties.getBaseUrl())
                .requestFactory(createRequestFactory(properties))
                .build());
    }



    /**
     * Construtor para injeção direta de RestClient (ex: testes com MockRestServiceServer).
     */
    public GooglePlacesAdapter(GooglePlacesProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "GooglePlacesProperties must not be null");
        this.restClient = Objects.requireNonNull(restClient, "RestClient must not be null");
    }

    private static SimpleClientHttpRequestFactory createRequestFactory(GooglePlacesProperties properties) {
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

        GoogleLocationBias locationBias = null;
        if (latitude != null && longitude != null) {
            double radius = (radiusMeters != null && radiusMeters > 0) ? radiusMeters : 5000.0;
            if (radius > 50000.0) {
                radius = 50000.0;
            }
            locationBias = new GoogleLocationBias(new GoogleCircle(new GoogleLatLng(latitude, longitude), radius));
        }

        int pageSize = (limit != null && limit >= 1 && limit <= 20) ? limit : 10;
        String lang = (languageCode != null && !languageCode.isBlank()) ? languageCode : "pt-BR";

        GoogleTextSearchRequest requestBody = new GoogleTextSearchRequest(query, pageSize, lang, locationBias);

        try {
            GooglePlacesSearchResponse response = restClient.post()
                    .uri("/v1/places:searchText")
                    .header(HEADER_API_KEY, properties.getApiKey())
                    .header(HEADER_FIELD_MASK, FIELD_MASK_TEXT_SEARCH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(GooglePlacesSearchResponse.class);

            if (response == null || response.places() == null || response.places().isEmpty()) {
                return Collections.emptyList();
            }

            List<PlaceCandidate> candidates = new ArrayList<>();
            for (GooglePlaceDto placeDto : response.places()) {
                PlaceCandidate candidate = toCandidate(placeDto);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            return Collections.unmodifiableList(candidates);

        } catch (ResourceAccessException ex) {
            handleResourceAccessException(ex);
            return Collections.emptyList();
        } catch (HttpClientErrorException.NotFound ex) {
            return Collections.emptyList();
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Collections.emptyList();
            }
            handleHttpException(ex);
            return Collections.emptyList();
        } catch (RestClientException ex) {
            log.error("Erro inesperado na resposta da API Google Places: {}", ex.getMessage());
            throw new BusinessException("Resposta inválida recebida do Google Places", HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_INVALID_RESPONSE");
        }
    }

    @Override
    public Optional<PlaceCandidate> getDetails(String externalPlaceId, String languageCode) {
        validateConfiguration();

        if (externalPlaceId == null || externalPlaceId.isBlank()) {
            throw new BusinessException("Identificador externo do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_ID");
        }

        String lang = (languageCode != null && !languageCode.isBlank()) ? languageCode : "pt-BR";

        try {
            GooglePlaceDto response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/places/{placeId}")
                            .queryParam("languageCode", lang)
                            .build(externalPlaceId.trim()))
                    .header(HEADER_API_KEY, properties.getApiKey())
                    .header(HEADER_FIELD_MASK, FIELD_MASK_PLACE_DETAILS)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GooglePlaceDto.class);

            if (response == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(toCandidate(response));

        } catch (ResourceAccessException ex) {
            handleResourceAccessException(ex);
            return Optional.empty();
        } catch (HttpClientErrorException.NotFound ex) {
            log.debug("Lugar não encontrado no Google Places para externalId: {}", externalPlaceId);
            return Optional.empty();
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            handleHttpException(ex);
            return Optional.empty();
        } catch (RestClientException ex) {
            log.error("Erro inesperado na resposta do Google Places Details: {}", ex.getMessage());
            throw new BusinessException("Resposta inválida recebida do Google Places", HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_INVALID_RESPONSE");
        }

    }

    private void validateConfiguration() {
        if (!properties.isConfigured()) {
            log.warn("Integração com Google Places não configurada. Defina GOOGLE_PLACES_API_KEY.");
            throw new BusinessException(
                    "Integração com Google Places não configurada no servidor",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EXTERNAL_SERVICE_NOT_CONFIGURED"
            );
        }
    }

    private void handleResourceAccessException(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            log.error("Timeout de conexão/leitura com a API Google Places.");
            throw new BusinessException(
                    "Tempo limite excedido na comunicação com o Google Places",
                    HttpStatus.GATEWAY_TIMEOUT,
                    "EXTERNAL_SERVICE_TIMEOUT"
            );
        }
        log.error("Google Places indisponível ou inalcançável: {}", ex.getMessage());
        throw new BusinessException(
                "Serviço Google Places indisponível",
                HttpStatus.BAD_GATEWAY,
                "EXTERNAL_SERVICE_UNAVAILABLE"
        );
    }

    private boolean isTimeout(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        String msg = ex.getMessage();
        return msg != null && (msg.contains("timed out") || msg.contains("Timeout"));
    }

    private void handleHttpException(org.springframework.web.client.HttpStatusCodeException ex) {
        int statusCode = ex.getStatusCode().value();
        if (statusCode == 429 || statusCode >= 500) {
            log.error("Google Places retornou status transitório/upstream: {}", statusCode);
            throw new BusinessException(
                    "Serviço Google Places temporariamente indisponível ou limite de taxa excedido",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EXTERNAL_SERVICE_UNAVAILABLE"
            );
        }
        log.error("Google Places retornou erro de cliente (status {}). Verifique credenciais e parâmetros.", statusCode);
        throw new BusinessException(
                "Falha na comunicação com o serviço externo de lugares",
                HttpStatus.BAD_GATEWAY,
                "EXTERNAL_SERVICE_ERROR"
        );
    }

    private PlaceCandidate toCandidate(GooglePlaceDto dto) {
        if (dto == null || dto.id() == null || dto.id().isBlank()) {
            return null;
        }

        String displayName = (dto.displayName() != null && dto.displayName().text() != null && !dto.displayName().text().isBlank())
                ? dto.displayName().text()
                : "Sem nome";

        Double lat = dto.location() != null ? dto.location().latitude() : null;
        Double lng = dto.location() != null ? dto.location().longitude() : null;

        List<com.rewit.domain.model.PlaceAttribution> attributions = Collections.emptyList();
        if (dto.attributions() != null && !dto.attributions().isEmpty()) {
            List<com.rewit.domain.model.PlaceAttribution> list = new ArrayList<>();
            for (GoogleAttributionDto attrDto : dto.attributions()) {
                if (attrDto != null) {
                    list.add(new com.rewit.domain.model.PlaceAttribution(attrDto.provider(), attrDto.providerUri()));
                }
            }
            attributions = Collections.unmodifiableList(list);
        }

        return new PlaceCandidate(
                PROVIDER_NAME,
                dto.id(),
                displayName,
                dto.formattedAddress() != null ? dto.formattedAddress() : "",
                lat,
                lng,
                dto.types() != null ? dto.types() : Collections.emptyList(),
                attributions
        );
    }

    // --- DTOs internos exclusivos da integração com Google Places API (New) ---

    public record GoogleTextSearchRequest(
            String textQuery,
            Integer pageSize,
            String languageCode,
            GoogleLocationBias locationBias
    ) {}

    public record GoogleLocationBias(
            GoogleCircle circle
    ) {}

    public record GoogleCircle(
            GoogleLatLng center,
            Double radius
    ) {}

    public record GoogleLatLng(
            Double latitude,
            Double longitude
    ) {}

    public record GoogleDisplayName(
            String text,
            String languageCode
    ) {}

    public record GoogleAttributionDto(
            String provider,
            String providerUri
    ) {}

    public record GooglePlaceDto(
            String id,
            GoogleDisplayName displayName,
            String formattedAddress,
            GoogleLatLng location,
            List<String> types,
            List<GoogleAttributionDto> attributions
    ) {}

    public record GooglePlacesSearchResponse(
            List<GooglePlaceDto> places
    ) {}
}
