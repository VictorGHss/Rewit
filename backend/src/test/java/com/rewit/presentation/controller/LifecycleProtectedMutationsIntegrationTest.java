package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.TokenService;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.Role;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AddProductIdentifierRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AssociateProductPresenceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreateProductRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mutações de catálogo e de notificações exigem conta operacional (C2), pela API real: o access token é stateless
 * e continua com assinatura válida depois de uma desativação, suspensão ou exclusão.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Ciclo de vida da conta (C2): catálogo e notificações exigem conta operacional")
class LifecycleProtectedMutationsIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TokenService tokenService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String accessToken) {}

    /** Fixtures criadas enquanto a conta do ator ainda opera. */
    private record Fixtures(UUID placeId, UUID productId, UUID notificationId) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("ACTIVE: criar place, adotar place, criar product, identificador, presença e marcar notificações")
    void activeAccountCanMutate() throws Exception {
        TestUser actor = register("ativa");
        Fixtures fixtures = prepareFixtures(actor);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        createPlace(actor, "ativa-" + suffix).andExpect(status().isCreated());
        adoptPlace(actor, "Adotado ativa " + suffix).andExpect(status().isCreated());
        createProduct(actor, "Produto ativa " + suffix).andExpect(status().isCreated());
        addIdentifier(actor, fixtures.productId(), "ativa-" + suffix).andExpect(status().isCreated());
        associatePresence(actor, fixtures)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedByUserId").value(actor.id().toString()));
        markAsRead(actor, fixtures.notificationId()).andExpect(status().isNoContent());
        markAllAsRead(actor).andExpect(status().isNoContent());

        assertEquals(1, count("SELECT count(*) FROM places WHERE slug = ?", "ativa-" + suffix));
        assertEquals(1, count("SELECT count(*) FROM products WHERE name = ?", "Produto ativa " + suffix));
        assertEquals(1, count("SELECT count(*) FROM product_identifiers WHERE identifier_value = ?", "ativa-" + suffix));
        assertEquals(1, count("SELECT count(*) FROM product_presences WHERE product_id = ? AND place_id = ?",
                fixtures.productId(), fixtures.placeId()));
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", actor.id()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AccountStatus.class, names = {"DEACTIVATED", "SUSPENDED", "DELETED"})
    @DisplayName("Conta não operacional com token anterior à mudança: toda mutação 401 ACCOUNT_DISABLED e sem efeito")
    void nonOperationalAccountCannotMutate(AccountStatus state) throws Exception {
        TestUser actor = register("estado_" + state.name().toLowerCase());
        Fixtures fixtures = prepareFixtures(actor);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Transição pelos fluxos reais; o access token do ator foi emitido antes dela e segue com assinatura válida
        changeState(actor, state);
        assertEquals(state.name(), jdbcTemplate.queryForObject(
                "SELECT account_status FROM users WHERE id = ?", String.class, actor.id()));

        assertAllMutationsRejected(actor, fixtures, suffix);

        assertEquals(0, count("SELECT count(*) FROM places WHERE slug = ? OR name = ?",
                "bloqueada-" + suffix, "Adotado bloqueada " + suffix));
        assertEquals(0, count("SELECT count(*) FROM products WHERE name = ?", "Produto bloqueada " + suffix));
        assertEquals(0, count("SELECT count(*) FROM product_identifiers WHERE identifier_value = ?", "bloqueada-" + suffix));
        assertEquals(0, count("SELECT count(*) FROM product_presences WHERE product_id = ?", fixtures.productId()));
        assertEquals(2, count("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", actor.id()));
    }

    @Test
    @DisplayName("Usuário inexistente com JWT válido: mesmo contrato genérico, 401 ACCOUNT_DISABLED")
    void nonexistentUserKeepsGenericContract() throws Exception {
        TestUser owner = register("inexistente_dono");
        Fixtures fixtures = prepareFixtures(owner);
        TestUser ghost = new TestUser(UUID.randomUUID(), null, null,
                tokenService.generateAccessToken(UUID.randomUUID(), Role.USER));

        assertAllMutationsRejected(ghost, fixtures, UUID.randomUUID().toString().substring(0, 8));
        assertEquals(0, count("SELECT count(*) FROM product_presences WHERE product_id = ?", fixtures.productId()));
    }

    // ---------------------------------------------------------------------------------------------------------

    private void assertAllMutationsRejected(TestUser actor, Fixtures fixtures, String suffix) throws Exception {
        assertAccountDisabled(createPlace(actor, "bloqueada-" + suffix));
        assertAccountDisabled(adoptPlace(actor, "Adotado bloqueada " + suffix));
        assertAccountDisabled(createProduct(actor, "Produto bloqueada " + suffix));
        assertAccountDisabled(addIdentifier(actor, fixtures.productId(), "bloqueada-" + suffix));
        assertAccountDisabled(associatePresence(actor, fixtures));
        assertAccountDisabled(markAsRead(actor, fixtures.notificationId()));
        assertAccountDisabled(markAllAsRead(actor));
    }

    /** Place e product do ator, e duas notificações para ele (dois seguidores), enquanto a conta opera. */
    private Fixtures prepareFixtures(TestUser actor) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID placeId = id(createPlace(actor, "fixture-" + suffix).andExpect(status().isCreated()));
        UUID productId = id(createProduct(actor, "Produto fixture " + suffix).andExpect(status().isCreated()));

        for (int i = 0; i < 2; i++) {
            TestUser follower = register("seguidor");
            mockMvc.perform(post("/api/v1/users/{id}/follow", actor.id()).header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                    .andExpect(status().isOk());
        }
        UUID notificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ? ORDER BY created_at LIMIT 1", UUID.class, actor.id());
        return new Fixtures(placeId, productId, notificationId);
    }

    private void changeState(TestUser actor, AccountStatus state) throws Exception {
        switch (state) {
            case DEACTIVATED -> mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                    .andExpect(status().isNoContent());
            case SUSPENDED -> mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", actor.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                    .andExpect(status().isNoContent());
            case DELETED -> mockMvc.perform(delete("/api/v1/admin/users/{id}", actor.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                    .andExpect(status().isNoContent());
            default -> throw new IllegalArgumentException(state.name());
        }
    }

    private ResultActions createPlace(TestUser actor, String slug) throws Exception {
        CreatePlaceRequest request = new CreatePlaceRequest("Local " + slug, slug, "CAFE", null, "Rua Teste, 1",
                "1", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50);
        return mockMvc.perform(post("/api/v1/places").header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions adoptPlace(TestUser actor, String name) throws Exception {
        AdoptPlaceRequest request = new AdoptPlaceRequest(name, null, "CAFE", null, "Rua Adoção, 2", "2", "Centro",
                "Curitiba", "PR", "BR", -25.44, -49.28, 50, null);
        return mockMvc.perform(post("/api/v1/places/adopt").header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions createProduct(TestUser actor, String name) throws Exception {
        CreateProductRequest request = new CreateProductRequest(name, "Marca", "Modelo", null, "BEBIDA", null);
        return mockMvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions addIdentifier(TestUser actor, UUID productId, String value) throws Exception {
        AddProductIdentifierRequest request = new AddProductIdentifierRequest("EAN", value);
        return mockMvc.perform(post("/api/v1/products/{id}/identifiers", productId).header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions associatePresence(TestUser actor, Fixtures fixtures) throws Exception {
        AssociateProductPresenceRequest request = new AssociateProductPresenceRequest(fixtures.placeId());
        return mockMvc.perform(post("/api/v1/products/{id}/presence", fixtures.productId())
                .header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions markAsRead(TestUser actor, UUID notificationId) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/notifications/{id}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private ResultActions markAllAsRead(TestUser actor) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, password, prefix + "_" + suffix, "Conta " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password,
                node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("mutations_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(),
                objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText());
    }

    private UUID id(ResultActions actions) throws Exception {
        return UUID.fromString(objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asText());
    }

    private static void assertAccountDisabled(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
