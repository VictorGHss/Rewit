package com.rewit.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting da busca global ponta a ponta: HTTP, Spring Security, CatalogSearchService e Redis reais.
 * Contexto próprio com limite pequeno e janela de 2 segundos. O contador é conferido por uma conexão Redis
 * independente (como outra instância da API), na mesma chave HMAC que o limitador deriva.
 */
@SpringBootTest(properties = {
        "rewit.rate-limit.query.search.limit=3",
        "rewit.rate-limit.query.search.window=PT2S"
})
@ActiveProfiles("local")
@DisplayName("Rate limiting da busca global: HTTP com Redis real")
class SearchRateLimitIntegrationTest {

    private static final long WINDOW_WAIT_MILLIS = 2_300;
    private static final String REDIS_HOST = envOrDefault("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(envOrDefault("REDIS_PORT", "6379"));

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private RateLimitProperties rateLimitProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private LettuceConnectionFactory independentConnection;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        independentConnection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        independentConnection.afterPropertiesSet();
        independentConnection.start();
        redis = new StringRedisTemplate(independentConnection);
    }

    @AfterEach
    void tearDown() {
        independentConnection.destroy();
    }

    @Test
    @DisplayName("Dentro do limite 200; acima 429 RFC 7807 com Retry-After; contador no Redis; libera após a janela")
    void searchLimitPerUserBackedByRedis() throws Exception {
        TestUser user = register();
        String key = new RateLimitKeyDeriver(rateLimitProperties.getKeySecret())
                .derive(RateLimitedAction.SEARCH, RateLimitSubject.ofUser(user.id()));

        for (int i = 0; i < 3; i++) {
            search(user).andExpect(status().isOk());
        }
        assertEquals(3L, redis.opsForZSet().zCard(key), "o contador vive no Redis compartilhado entre instâncias");

        search(user).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("^[1-9]\\d*$")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertEquals(3L, redis.opsForZSet().zCard(key), "a requisição negada não consome o limite");

        // O limite é por usuário: outro usuário segue buscando
        search(register()).andExpect(status().isOk());

        Thread.sleep(WINDOW_WAIT_MILLIS);
        search(user).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Busca inválida não consome o limite: a validação vem antes do rate limit")
    void invalidSearchDoesNotConsumeLimit() throws Exception {
        TestUser user = register();
        String key = new RateLimitKeyDeriver(rateLimitProperties.getKeySecret())
                .derive(RateLimitedAction.SEARCH, RateLimitSubject.ofUser(user.id()));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/search").param("q", " ")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0L, redis.opsForZSet().zCard(key));
        search(user).andExpect(status().isOk());
    }

    private ResultActions search(TestUser user) throws Exception {
        return mockMvc.perform(get("/api/v1/search").param("q", "cafe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    private TestUser register() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                "busca.limite." + suffix + "@rewit.test", "Senha@" + suffix,
                                "busca_limite_" + suffix, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }

    private record TestUser(UUID id, String accessToken) {}

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
