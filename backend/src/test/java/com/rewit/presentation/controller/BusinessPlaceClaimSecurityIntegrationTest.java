package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Segurança das rotas HTTP do C9 pela cadeia real do Spring Security.
 *
 * <p>Os tokens são emitidos pelo registro e login reais (JwtTokenService) e enviados em {@code Authorization: Bearer};
 * cada requisição atravessa o filtro do resource server, o JwtDecoder e o conversor de autoridades do SecurityConfig
 * e o {@code @PreAuthorize} dos controllers. Nenhum {@code @WithMockUser} ou {@code jwt()}.
 *
 * <p>Os casos de uso são os reais, no contexto Spring compartilhado pela suíte (sem MockitoBean: um contexto próprio
 * abriria outro pool de conexões, e a suíte já opera no limite de conexões do PostgreSQL de teste). A identidade
 * interpretada a partir do token é conferida no banco (dono da conta, autor da decisão). A recusa por role é
 * distinguida da regra de negócio: o {@code @PreAuthorize} responde 403 sem corpo antes do controller, e o caso de
 * uso, se alcançado, responderia 403 com ProblemDetail {@code code: FORBIDDEN}.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Segurança das rotas do C9 pela cadeia real (bearer, autoridades e @PreAuthorize)")
class BusinessPlaceClaimSecurityIntegrationTest {

    private static final String JUSTIFICATION = "Documentação conferida e compatível com o local.";
    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis.";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sem autenticação e tokens inválidos
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sem token: as seis rotas respondem 401 AUTHENTICATION_REQUIRED e nada é gravado")
    void unauthenticatedRequestsAreRejected() throws Exception {
        UUID someAccount = UUID.randomUUID();
        Integer accountsBefore = countAccounts();
        for (MockHttpServletRequestBuilder request : allRoutes(someAccount, UUID.randomUUID(), UUID.randomUUID(),
                randomTaxId())) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        assertEquals(accountsBefore, countAccounts(), "nenhuma conta criada sem autenticação");
    }

    @Test
    @DisplayName("Token malformado, com payload adulterado ou assinado com outra chave: 401 no filtro de bearer")
    void invalidBearerTokensAreRejected() throws Exception {
        TestUser user = registerUser("sec_tamper");
        String tampered = withRoleClaim(user.token(), "ADMIN"); // payload ADMIN, assinatura original de USER
        String foreign = signedWithForeignKey(user.id(), "ADMIN");

        for (String token : List.of("nao-e-um-jwt", tampered, foreign)) {
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/api/v1/business-accounts/mine"), get("/api/v1/admin/place-claims"))) {
                mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }
        // Credencial sem o esquema Bearer não autentica
        mockMvc.perform(get("/api/v1/business-accounts/mine").header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isUnauthorized());
        // O token legítimo, no mesmo teste, autentica: a recusa acima é do token, não da rota
        mockMvc.perform(withToken(get("/api/v1/business-accounts/mine"), user)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Usuário comum
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("USER: usa as rotas de conta comercial, e a identidade do token é a dona da conta e da solicitação")
    void userReachesBusinessRoutesWithTokenIdentity() throws Exception {
        TestUser owner = registerUser("sec_owner");
        TestUser stranger = registerUser("sec_stranger");
        UUID place = place();

        UUID accountId = createAccount(owner);
        assertEquals(owner.id(), jdbcTemplate.queryForObject("SELECT user_id FROM business_accounts WHERE id = ?",
                UUID.class, accountId), "a conta pertence ao sub do token");

        mockMvc.perform(withToken(get("/api/v1/business-accounts/mine"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));
        mockMvc.perform(withToken(get("/api/v1/business-accounts/mine"), stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        UUID claimId = requestClaim(owner, accountId, place);
        assertEquals(accountId, jdbcTemplate.queryForObject(
                "SELECT business_account_id FROM place_claim_requests WHERE id = ?", UUID.class, claimId));

        mockMvc.perform(withToken(get("/api/v1/business-accounts/{id}/place-claims", accountId)
                        .param("status", "PENDING"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(claimId.toString()));

        // Outro usuário autenticado não alcança a conta alheia: a identidade do token decide
        mockMvc.perform(withToken(get("/api/v1/business-accounts/{id}/place-claims", accountId), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_FOUND"));
        mockMvc.perform(withToken(post("/api/v1/business-accounts/{id}/place-claims", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody(place())), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("USER: fila e decisão administrativas respondem 403 do @PreAuthorize, sem corpo, e nada é decidido")
    void userCannotReachAdminRoutes() throws Exception {
        TestUser owner = registerUser("sec_admin_owner");
        UUID claimId = requestClaim(owner, createAccount(owner), place());
        TestUser user = registerUser("sec_plain");

        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/admin/place-claims"), decision(claimId))) {
            MvcResult result = mockMvc.perform(withToken(request, user)).andExpect(status().isForbidden()).andReturn();
            // 403 da method security, antes do controller: sem o ProblemDetail que o caso de uso devolveria
            assertTrue(result.getResponse().getContentAsString().isEmpty(), result.getResponse().getContentAsString());
        }
        assertEquals("PENDING", claimStatus(claimId), "a decisão não foi aplicada");
    }

    @Test
    @DisplayName("A role vem das autoridades do token: promovido no banco, mas com token antigo de USER, segue 403")
    void roleComesFromTokenAuthorities() throws Exception {
        TestUser owner = registerUser("sec_stale_owner");
        UUID claimId = requestClaim(owner, createAccount(owner), place());
        TestUser user = registerUser("sec_stale");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());

        mockMvc.perform(withToken(get("/api/v1/admin/place-claims"), user)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(decision(claimId), user)).andExpect(status().isForbidden());
        assertEquals("PENDING", claimStatus(claimId));

        // Novo login emite token com a role atual
        TestUser refreshed = login(user);
        mockMvc.perform(withToken(get("/api/v1/admin/place-claims"), refreshed)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------------------
    // MODERATOR e ADMIN
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("MODERATOR e ADMIN: atravessam a segurança das rotas administrativas; a decisão registra o sub do token")
    void moderatorAndAdminReachAdminRoutes() throws Exception {
        for (String role : List.of("MODERATOR", "ADMIN")) {
            TestUser owner = registerUser("sec_staff_owner");
            UUID claimId = requestClaim(owner, createAccount(owner), place());
            TestUser staff = withRole(registerUser("sec_" + role.toLowerCase()), role);

            List<String> queue = pendingQueueIds(staff);
            assertTrue(queue.contains(claimId.toString()), role + " vê a fila");

            mockMvc.perform(withToken(decision(claimId), staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            assertEquals("APPROVED", claimStatus(claimId));
            assertEquals(staff.id(), jdbcTemplate.queryForObject(
                    "SELECT decided_by_user_id FROM place_claim_requests WHERE id = ?", UUID.class, claimId),
                    "a decisão é registrada com o sub do token de " + role);
        }
    }

    @Test
    @DisplayName("MODERATOR rebaixado a USER: o token antigo passa pelo @PreAuthorize, mas fila e decisão recusam com 403")
    void demotedModeratorWithOldTokenIsRejected() throws Exception {
        TestUser owner = registerUser("sec_demote_owner");
        UUID claimId = requestClaim(owner, createAccount(owner), place());
        TestUser moderator = withRole(registerUser("sec_demoted"), "MODERATOR");

        // Token válido e role atual MODERATOR: a fila responde
        assertTrue(pendingQueueIds(moderator).contains(claimId.toString()));

        // Rebaixado no banco; o token continua dizendo MODERATOR
        jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE id = ?", moderator.id());

        // A autoridade do token ainda satisfaz o @PreAuthorize; a recusa vem da revalidação da role atual no caso de
        // uso: 403 com o ProblemDetail das regras de negócio (o @PreAuthorize responderia 403 sem corpo)
        mockMvc.perform(withToken(get("/api/v1/admin/place-claims").param("status", "PENDING"), moderator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.content").doesNotExist());
        mockMvc.perform(withToken(decision(claimId), moderator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals("PENDING", claimStatus(claimId), "a decisão não foi aplicada");

        // Restabelecida a role no banco, o mesmo token volta a consultar: a recusa acima era só a revalidação
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator.id());
        assertTrue(pendingQueueIds(moderator).contains(claimId.toString()));
    }

    @Test
    @DisplayName("MODERATOR também usa as rotas comuns de conta comercial com a própria identidade")
    void moderatorReachesBusinessRoutes() throws Exception {
        TestUser moderator = withRole(registerUser("sec_mod_business"), "MODERATOR");
        UUID accountId = createAccount(moderator);

        mockMvc.perform(withToken(get("/api/v1/business-accounts/mine"), moderator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private List<MockHttpServletRequestBuilder> allRoutes(UUID accountId, UUID placeId, UUID claimId, String taxId)
            throws Exception {
        return List.of(
                post("/api/v1/business-accounts").contentType(MediaType.APPLICATION_JSON).content(accountBody(taxId)),
                get("/api/v1/business-accounts/mine"),
                post("/api/v1/business-accounts/{id}/place-claims", accountId).contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(placeId)),
                get("/api/v1/business-accounts/{id}/place-claims", accountId),
                get("/api/v1/admin/place-claims"),
                decision(claimId));
    }

    private MockHttpServletRequestBuilder decision(UUID claimId) throws Exception {
        return post("/api/v1/admin/place-claims/{claimId}/decision", claimId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("decision", "APPROVE", "justification", JUSTIFICATION)));
    }

    private String accountBody(String taxId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("corporateName", "Empresa Segurança", "taxId", taxId));
    }

    private String claimBody(UUID placeId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("placeId", placeId.toString(), "evidenceDescription", EVIDENCE));
    }

    private UUID createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody(randomTaxId())), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID requestClaim(TestUser user, UUID accountId, UUID placeId) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts/{id}/place-claims", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody(placeId)), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private List<String> pendingQueueIds(TestUser staff) throws Exception {
        List<String> ids = new ArrayList<>();
        int page = 0;
        JsonNode body;
        do {
            body = read(mockMvc.perform(withToken(get("/api/v1/admin/place-claims").param("status", "PENDING")
                            .param("page", String.valueOf(page++)).param("size", "50"), staff))
                    .andExpect(status().isOk())
                    .andReturn());
            body.get("content").forEach(item -> ids.add(item.get("id").asText()));
        } while (!body.get("isLast").asBoolean());
        return ids;
    }

    private String claimStatus(UUID claimId) {
        return jdbcTemplate.queryForObject("SELECT status FROM place_claim_requests WHERE id = ?", String.class, claimId);
    }

    private Integer countAccounts() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM business_accounts", Integer.class);
    }

    private UUID place() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(null, "Local Segurança " + suffix, "sec-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua Segurança, 1", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, "ACTIVE"));
        return saved.getId();
    }

    private static String randomTaxId() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    /** Troca a role no payload e mantém a assinatura original: a verificação de assinatura precisa recusar. */
    private static String withRoleClaim(String token, String role) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = payload.replaceAll("\"role\"\\s*:\\s*\"[A-Z]+\"", "\"role\":\"" + role + "\"");
        if (forged.equals(payload)) {
            throw new IllegalStateException("token sem claim role: " + payload);
        }
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
    }

    /** Token bem-formado e assinado, com emissor e audiência do projeto, mas com uma chave que não é a do projeto. */
    private static String signedWithForeignKey(UUID subject, String role) throws Exception {
        byte[] secret = new byte[64];
        new SecureRandom().nextBytes(secret);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer("rewit-api")
                .audience("rewit-clients")
                .claim("role", role)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, prefix + "_" + suffix,
                                "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password,
                node.get("accessToken").asText());
    }

    private TestUser withRole(TestUser user, String role) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, user.id());
        return login(user);
    }

    private TestUser login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        String token = read(result).get("accessToken").asText();
        assertEquals(3, token.split("\\.").length, "JWT compacto emitido pelo login real");
        return new TestUser(user.id(), user.email(), user.password(), token);
    }
}
