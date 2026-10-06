package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.discussion.CreateDiscussionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Discussions e Comments de Reviews (Step 20.0)")
class DiscussionControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlaceRepository placeRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId, String handle) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rewit.test").toLowerCase(java.util.Locale.ROOT);
        String handle = (prefix + "_" + suffix).toLowerCase(java.util.Locale.ROOT);

        RegisterRequest request = new RegisterRequest(email, "SenhaSegura123!", handle, "Nome " + prefix);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        UUID userId = UUID.fromString(json.get("user").get("id").asText());
        return new TestUser(token, userId, handle);
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Disc " + suffix,
                "restaurante-disc-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua API, 100",
                "100",
                "Bairro",
                "Cidade",
                "UF",
                "BR",
                -23.5505,
                -46.6333,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private Review createReview(UUID authorId, Place place, String visibility, ReviewStatus status) {
        return createReview(authorId, place, visibility, status, false);
    }

    private Review createReview(UUID authorId, Place place, String visibility, ReviewStatus status, boolean isAnonymous) {
        Review review = new Review(
                null,
                authorId,
                place.getId(),
                "Texto avaliativo para testes de API de discussões",
                isAnonymous,
                false,
                status,
                visibility,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        return reviewRepository.save(review);
    }

    // 1. 401 Unauthorized para não autenticado
    @Test
    @DisplayName("1. Deve retornar 401 Unauthorized ao tentar comentar, listar ou deletar sem token JWT")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        UUID randomReviewId = UUID.randomUUID();
        UUID randomDiscussionId = UUID.randomUUID();

        CreateDiscussionRequest request = new CreateDiscussionRequest("Comentário sem auth", null);

        // POST sem auth
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", randomReviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // GET sem auth
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/discussions", randomReviewId))
                .andExpect(status().isUnauthorized());

        // DELETE sem auth
        mockMvc.perform(delete("/api/v1/discussions/{discussionId}", randomDiscussionId))
                .andExpect(status().isUnauthorized());
    }

    // 2. 201 Created ao criar comentário raiz
    @Test
    @DisplayName("2. Deve retornar 201 Created e criar comentário raiz com isFromOwner = false")
    void shouldCreateRootDiscussionWith201() throws Exception {
        TestUser owner = registerUser("owner1");
        TestUser commenter = registerUser("com1");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateDiscussionRequest request = new CreateDiscussionRequest("Excelente publicação!", null);

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.reviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.authorId").value(commenter.userId().toString()))
                .andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.content").value("Excelente publicação!"))
                .andExpect(jsonPath("$.isFromOwner").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // 3. 201 Created ao responder comentário (isFromOwner = true para o autor da review)
    @Test
    @DisplayName("3. Deve retornar 201 Created com parentId preenchido e isFromOwner = true quando respondido pelo autor da review")
    void shouldCreateReplyDiscussionWith201AndIsFromOwnerTrue() throws Exception {
        TestUser owner = registerUser("owner2");
        TestUser commenter = registerUser("com2");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        // Raiz criado pelo commenter
        CreateDiscussionRequest rootReq = new CreateDiscussionRequest("Qual prato você mais recomenda?", null);
        MvcResult rootResult = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rootReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID parentId = UUID.fromString(objectMapper.readTree(rootResult.getResponse().getContentAsString()).get("id").asText());

        // Resposta pelo dono da review
        CreateDiscussionRequest replyReq = new CreateDiscussionRequest("Recomendo o risoto de cogumelos!", parentId);
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replyReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.authorId").value(owner.userId().toString()))
                .andExpect(jsonPath("$.isFromOwner").value(true));
    }

    // 4. 204 No Content na remoção por autor
    @Test
    @DisplayName("4. Deve retornar 204 No Content ao autor remover o próprio comentário")
    void shouldDeleteDiscussionWith204() throws Exception {
        TestUser commenter = registerUser("del_com");
        TestUser owner = registerUser("del_owner");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateDiscussionRequest req = new CreateDiscussionRequest("Comentário a ser deletado", null);
        MvcResult res = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID discussionId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

        // Remoção com 204
        mockMvc.perform(delete("/api/v1/discussions/{discussionId}", discussionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken()))
                .andExpect(status().isNoContent());

        // Remoção subsequente continua 204 (idempotente)
        mockMvc.perform(delete("/api/v1/discussions/{discussionId}", discussionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken()))
                .andExpect(status().isNoContent());
    }

    // 5. 400 Bad Request para conteúdo vazio ou whitespace (RFC 7807)
    @Test
    @DisplayName("5. Deve retornar 400 Bad Request no padrão RFC 7807 quando conteúdo for vazio ou whitespace")
    void shouldReturn400WhenContentIsBlank() throws Exception {
        TestUser commenter = registerUser("blank_com");
        TestUser owner = registerUser("blank_owner");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        String jsonPayload = "{\"content\":\"   \", \"parentId\":null}";

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").exists());
    }

    // 6. 400 Bad Request para conteúdo excedendo 2000 caracteres (RFC 7807)
    @Test
    @DisplayName("6. Deve retornar 400 Bad Request no padrão RFC 7807 quando conteúdo exceder 2000 caracteres")
    void shouldReturn400WhenContentExceeds2000Chars() throws Exception {
        TestUser commenter = registerUser("long_com");
        TestUser owner = registerUser("long_owner");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        String longContent = "A".repeat(2001);
        CreateDiscussionRequest request = new CreateDiscussionRequest(longContent, null);

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // 7. 400 Bad Request para nesting de segundo nível (DISCUSSION_NESTING_LIMIT_EXCEEDED)
    @Test
    @DisplayName("7. Deve retornar 400 DISCUSSION_NESTING_LIMIT_EXCEEDED ao tentar responder a uma resposta")
    void shouldReturn400WhenSecondLevelNestingAttempted() throws Exception {
        TestUser userA = registerUser("nest_a");
        TestUser userB = registerUser("nest_b");
        TestUser userC = registerUser("nest_c");
        Place place = createPlace();
        Review review = createReview(userA.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        // Raiz
        MvcResult rootRes = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Raiz", null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rootId = UUID.fromString(objectMapper.readTree(rootRes.getResponse().getContentAsString()).get("id").asText());

        // Resposta de 1º nível
        MvcResult replyRes = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Resposta nível 1", rootId))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID replyId = UUID.fromString(objectMapper.readTree(replyRes.getResponse().getContentAsString()).get("id").asText());

        // Tentativa de resposta ao replyId (2º nível)
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userC.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Tentativa nível 2", replyId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NESTING_LIMIT_EXCEEDED"));
    }

    // 8. 400 Bad Request ao usar parentId de outra review
    @Test
    @DisplayName("8. Deve retornar 400 INVALID_PARENT_DISCUSSION ao tentar associar parentId de outra review")
    void shouldReturn400WhenParentBelongsToDifferentReview() throws Exception {
        TestUser userA = registerUser("diff_a");
        TestUser userB = registerUser("diff_b");
        Place place1 = createPlace();
        Place place2 = createPlace();
        Review review1 = createReview(userA.userId(), place1, "PUBLIC", ReviewStatus.ACTIVE);
        Review review2 = createReview(userB.userId(), place2, "PUBLIC", ReviewStatus.ACTIVE);

        // Cria discussão na review1
        MvcResult res1 = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review1.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Na Review 1", null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rev1DiscId = UUID.fromString(objectMapper.readTree(res1.getResponse().getContentAsString()).get("id").asText());

        // Tenta responder na review2 usando o id da review1
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Resposta cruzada", rev1DiscId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARENT_DISCUSSION"));
    }

    // 9. 403 Forbidden ao tentar remover comentário de outro usuário
    @Test
    @DisplayName("9. Deve retornar 403 FORBIDDEN ao tentar remover comentário de terceiro")
    void shouldReturn403WhenDeletingOtherUserDiscussion() throws Exception {
        TestUser authorComment = registerUser("forb_auth");
        TestUser intruder = registerUser("forb_intruder");
        Place place = createPlace();
        Review review = createReview(authorComment.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        MvcResult res = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorComment.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário do autor", null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID discussionId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

        // Terceiro tenta deletar
        mockMvc.perform(delete("/api/v1/discussions/{discussionId}", discussionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 10. 403 Forbidden ao tentar comentar em Review PRIVATE por terceiro
    @Test
    @DisplayName("10. Deve retornar 403 FORBIDDEN ao tentar comentar em Review PRIVATE de outro usuário")
    void shouldReturn403WhenCommentingOnPrivateReview() throws Exception {
        TestUser owner = registerUser("priv_owner");
        TestUser thirdParty = registerUser("priv_third");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PRIVATE", ReviewStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdParty.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentando na privada", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 11. 404 Not Found para Review inexistente
    @Test
    @DisplayName("11. Deve retornar 404 REVIEW_NOT_FOUND quando a review não existe")
    void shouldReturn404WhenReviewNotFound() throws Exception {
        TestUser user = registerUser("notf_user");
        UUID fakeReviewId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", fakeReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    // 12. 404 Not Found para Review UNDER_REVIEW (invisibilidade preventiva sem vazamento)
    @Test
    @DisplayName("12. Deve retornar 404 REVIEW_NOT_FOUND quando a review estiver UNDER_REVIEW")
    void shouldReturn404WhenReviewIsUnderReview() throws Exception {
        TestUser owner = registerUser("und_owner");
        TestUser user = registerUser("und_user");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.UNDER_REVIEW);

        // POST bloqueado com 404
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Tentativa em UNDER_REVIEW", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        // GET bloqueado com 404
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    // 13. 404 Not Found quando parentId não existe
    @Test
    @DisplayName("13. Deve retornar 404 DISCUSSION_NOT_FOUND quando parentId não existe")
    void shouldReturn404WhenParentNotFound() throws Exception {
        TestUser user = registerUser("p_notf_user");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        UUID fakeParentId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário", fakeParentId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NOT_FOUND"));
    }

    // 14. Anti-IDOR e Anti-forja de isFromOwner e authorId
    @Test
    @DisplayName("14. Deve ignorar tentativa de forjar authorId ou isFromOwner pelo payload JSON")
    void shouldPreventIdorAndSpoofingInPayload() throws Exception {
        TestUser commenter = registerUser("idor_com");
        TestUser owner = registerUser("idor_owner");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        UUID forgedAuthorId = UUID.randomUUID();
        String maliciousPayload = String.format(
                "{\"content\":\"Tentativa de IDOR\",\"authorId\":\"%s\",\"isFromOwner\":true,\"parentId\":null}",
                forgedAuthorId
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + commenter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPayload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // Deve registrar o commenter real, não o forjado
        assertEquals(commenter.userId().toString(), json.get("authorId").asText());
        // Deve registrar false para isFromOwner pois o commenter não é o dono da review
        assertFalse(json.get("isFromOwner").asBoolean());
    }

    // 15. Rate Limiter (HTTP 429)
    @Test
    @DisplayName("15. Deve retornar 429 RATE_LIMIT_EXCEEDED ao exceder o limite de 15 comentários por minuto")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        TestUser user = registerUser("rate_user");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário " + i, null))))
                    .andExpect(status().isCreated());
        }

        // 16ª tentativa dentro da janela deslizante estoura o rate limit
        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário 16", null))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // 16. Listagem Paginada e Ocultação de REMOVED
    @Test
    @DisplayName("16. Deve retornar 200 OK na listagem de discussões ordenadas e excluir comentários removidos")
    void shouldListDiscussionsExcludingRemoved() throws Exception {
        TestUser owner = registerUser("list_owner");
        TestUser com1 = registerUser("list_c1");
        TestUser com2 = registerUser("list_c2");
        Place place = createPlace();
        Review review = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        // Cria d1
        MvcResult r1 = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + com1.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário 1", null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID d1Id = UUID.fromString(objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText());

        // Cria d2
        MvcResult r2 = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + com2.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário 2", null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID d2Id = UUID.fromString(objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText());

        // Remove d1
        mockMvc.perform(delete("/api/v1/discussions/{discussionId}", d1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + com1.accessToken()))
                .andExpect(status().isNoContent());

        // Listagem
        MvcResult listRes = mockMvc.perform(get("/api/v1/reviews/{reviewId}/discussions", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();

        JsonNode listJson = objectMapper.readTree(listRes.getResponse().getContentAsString());
        assertEquals(1, listJson.get("content").size());
        assertEquals(d2Id.toString(), listJson.get("content").get(0).get("id").asText());
    }

    // 17. Auditoria de Anonimato: POST e GET mascaram authorId do proprietário em Review anônima
    @Test
    @DisplayName("17. Deve mascarar authorId para null em POST e GET quando a Review for anônima e o comentário for do proprietário")
    void shouldMaskAuthorIdInCreationAndListingWhenReviewIsAnonymousAndCommentIsFromOwner() throws Exception {
        TestUser owner = registerUser("anon_owner");
        TestUser thirdParty = registerUser("anon_third");
        Place place = createPlace();
        Review anonymousReview = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, true);

        // 1. Dono comenta na sua própria review anônima -> authorId deve ser retornado como null, isFromOwner = true
        MvcResult ownerResult = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", anonymousReview.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário do dono anônimo", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isFromOwner").value(true))
                .andExpect(jsonPath("$.authorId").isEmpty())
                .andReturn();

        UUID ownerDiscId = UUID.fromString(objectMapper.readTree(ownerResult.getResponse().getContentAsString()).get("id").asText());

        // 2. Terceiro comenta na review anônima -> authorId deve ser revelado com seu ID real, isFromOwner = false
        MvcResult thirdResult = mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", anonymousReview.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdParty.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário de terceiro", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isFromOwner").value(false))
                .andExpect(jsonPath("$.authorId").value(thirdParty.userId().toString()))
                .andReturn();

        UUID thirdDiscId = UUID.fromString(objectMapper.readTree(thirdResult.getResponse().getContentAsString()).get("id").asText());

        // 3. Terceiro consulta listagem de discussões -> authorId do dono mascarado, do terceiro revelado
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/discussions", anonymousReview.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdParty.accessToken())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ownerDiscId.toString()))
                .andExpect(jsonPath("$.content[0].isFromOwner").value(true))
                .andExpect(jsonPath("$.content[0].authorId").isEmpty())
                .andExpect(jsonPath("$.content[1].id").value(thirdDiscId.toString()))
                .andExpect(jsonPath("$.content[1].isFromOwner").value(false))
                .andExpect(jsonPath("$.content[1].authorId").value(thirdParty.userId().toString()));
    }

    // 18. Auditoria de Anonimato: Review não anônima mantém authorId normalmente
    @Test
    @DisplayName("18. Deve expor authorId normalmente quando a Review não for anônima")
    void shouldNotMaskAuthorIdWhenReviewIsNotAnonymous() throws Exception {
        TestUser owner = registerUser("pub_owner");
        Place place = createPlace();
        Review publicReview = createReview(owner.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/discussions", publicReview.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDiscussionRequest("Comentário em review pública", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isFromOwner").value(true))
                .andExpect(jsonPath("$.authorId").value(owner.userId().toString()));
    }
}
