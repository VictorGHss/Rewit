package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thread de discussões montada pelo servidor (C3 D2), pelo contrato HTTP público e com PostgreSQL real.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("HTTP: thread de discussões com estado derivado")
class DiscussionThreadControllerIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private DiscussionRepository discussionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Instant clock;

    private Account owner;
    private Account viewer;
    private Account commenter;
    private Review review;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        clock = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);
        owner = register("dono");
        viewer = register("leitor");
        commenter = register("autor");
        review = createReview(owner.userId());
    }

    @Test
    @DisplayName("UNDER_REVIEW: conversa inteira some para terceiros, sem respostas isoladas; autor vê PENDING_REVIEW sem respostas")
    void quarantinedThreadIsHiddenAndAuthorSeesPendingReview() throws Exception {
        ReviewDiscussion visible = comment(commenter, null, DiscussionStatus.ACTIVE);
        ReviewDiscussion quarantined = comment(commenter, null, DiscussionStatus.UNDER_REVIEW);
        ReviewDiscussion hiddenReply = comment(viewer, quarantined.getId(), DiscussionStatus.ACTIVE);

        JsonNode forViewer = thread(viewer);
        assertEquals(List.of(visible.getId().toString()), ids(forViewer));
        assertFalse(forViewer.toString().contains("UNDER_REVIEW"), "status interno nunca exposto");
        assertFalse(forViewer.toString().contains(hiddenReply.getId().toString()), "resposta não aparece isolada");
        assertTrue(forViewer.get(0).path("status").isMissingNode(), "sem campo de status bruto");

        JsonNode forAuthor = thread(commenter);
        assertEquals(List.of(visible.getId().toString(), quarantined.getId().toString()), ids(forAuthor));
        JsonNode pending = forAuthor.get(1);
        assertEquals("PENDING_REVIEW", pending.get("state").asText());
        assertEquals("Comentário de teste", pending.get("content").asText());
        assertEquals(0, pending.get("replies").size());
        assertEquals(0, pending.get("replyCount").asLong());
        assertFalse(pending.get("canReply").asBoolean());
        assertFalse(pending.get("canDelete").asBoolean());

        // O autor não exclui o comentário em análise: 409 estável e o comentário segue em análise
        mockMvc.perform(delete("/api/v1/discussions/{id}", quarantined.getId()).header(HttpHeaders.AUTHORIZATION, bearer(commenter)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISCUSSION_UNDER_REVIEW_MUTATION_DENIED"));
        assertEquals("PENDING_REVIEW", thread(commenter).get(1).get("state").asText());

        // Nem a lista de respostas da raiz em análise é acessível, para ninguém
        mockMvc.perform(get("/api/v1/discussions/{id}/replies", quarantined.getId()).header(HttpHeaders.AUTHORIZATION, bearer(commenter)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Resposta UNDER_REVIEW aparece só para o próprio autor, como PENDING_REVIEW, e não entra na contagem de terceiros")
    void quarantinedReplyIsVisibleOnlyToItsAuthor() throws Exception {
        ReviewDiscussion root = comment(owner, null, DiscussionStatus.ACTIVE);
        comment(viewer, root.getId(), DiscussionStatus.ACTIVE);
        ReviewDiscussion pendingReply = comment(commenter, root.getId(), DiscussionStatus.UNDER_REVIEW);

        JsonNode forViewer = thread(viewer).get(0);
        assertEquals(1, forViewer.get("replyCount").asLong());
        assertFalse(forViewer.toString().contains(pendingReply.getId().toString()));

        JsonNode forAuthor = thread(commenter).get(0);
        assertEquals(2, forAuthor.get("replyCount").asLong());
        assertEquals("PENDING_REVIEW", forAuthor.get("replies").get(1).get("state").asText());
    }

    @Test
    @DisplayName("REMOVED com respostas: tombstone sem conteúdo nem autor e respostas preservadas; sem respostas: some")
    void removedRootBecomesTombstoneOnlyWithVisibleReplies() throws Exception {
        ReviewDiscussion tombstone = comment(owner, null, DiscussionStatus.REMOVED);
        ReviewDiscussion keptReply = comment(viewer, tombstone.getId(), DiscussionStatus.ACTIVE);
        comment(commenter, null, DiscussionStatus.REMOVED);
        ReviewDiscussion removedWithRemovedReply = comment(commenter, null, DiscussionStatus.REMOVED);
        comment(viewer, removedWithRemovedReply.getId(), DiscussionStatus.REMOVED);

        JsonNode threads = thread(viewer);
        assertEquals(List.of(tombstone.getId().toString()), ids(threads));
        JsonNode root = threads.get(0);
        assertEquals("REMOVED", root.get("state").asText());
        assertTrue(root.get("content").isNull());
        assertTrue(root.get("author").isNull());
        assertFalse(root.get("isFromOwner").asBoolean(), "tombstone não revela que era do dono da avaliação");
        assertFalse(root.get("canReply").asBoolean());
        assertFalse(root.get("canDelete").asBoolean());
        assertEquals(keptReply.getId().toString(), root.get("replies").get(0).get("id").asText());
        assertEquals("VISIBLE", root.get("replies").get(0).get("state").asText());
        assertEquals(viewer.userId().toString(), root.get("replies").get(0).get("author").get("id").asText());

        // Respostas da raiz removida continuam paginadas
        mockMvc.perform(get("/api/v1/discussions/{id}/replies", tombstone.getId()).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Raiz VISIBLE: autor com handle, primeiras 3 respostas embutidas, replyCount, hasMoreReplies e paginação do restante")
    void visibleRootWithRepliesAndPagination() throws Exception {
        ReviewDiscussion root = comment(commenter, null, DiscussionStatus.ACTIVE);
        List<String> replyIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replyIds.add(comment(viewer, root.getId(), DiscussionStatus.ACTIVE).getId().toString());
        }

        JsonNode thread = thread(viewer).get(0);
        assertEquals("VISIBLE", thread.get("state").asText());
        assertEquals(commenter.handle(), thread.get("author").get("handle").asText());
        assertTrue(thread.get("canReply").asBoolean());
        assertFalse(thread.get("canDelete").asBoolean());
        assertEquals(5, thread.get("replyCount").asLong());
        assertTrue(thread.get("hasMoreReplies").asBoolean());
        assertEquals(replyIds.subList(0, 3), ids(thread.get("replies")));
        assertFalse(thread.get("replies").get(0).get("canReply").asBoolean(), "um nível de resposta");
        assertTrue(thread.get("replies").get(0).get("canDelete").asBoolean(), "o leitor é o autor da resposta");

        String page1 = mockMvc.perform(get("/api/v1/discussions/{id}/replies", root.getId())
                        .param("page", "1").param("size", "3").header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andReturn().getResponse().getContentAsString();
        assertEquals(replyIds.subList(3, 5), ids(objectMapper.readTree(page1).get("content")));
    }

    @Test
    @DisplayName("Paginação de raízes determinística (created_at, id) e sem hasMoreReplies quando todas as respostas cabem")
    void rootPaginationIsDeterministic() throws Exception {
        List<String> rootIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rootIds.add(comment(commenter, null, DiscussionStatus.ACTIVE).getId().toString());
        }
        comment(viewer, UUID.fromString(rootIds.get(0)), DiscussionStatus.ACTIVE);

        String page0 = mockMvc.perform(get("/api/v1/reviews/{id}/discussions", review.getId())
                        .param("page", "0").param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode content0 = objectMapper.readTree(page0).get("content");
        assertEquals(rootIds.subList(0, 2), ids(content0));
        assertEquals(1, content0.get(0).get("replyCount").asLong());
        assertFalse(content0.get(0).get("hasMoreReplies").asBoolean());

        String page1 = mockMvc.perform(get("/api/v1/reviews/{id}/discussions", review.getId())
                        .param("page", "1").param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(rootIds.subList(2, 3), ids(objectMapper.readTree(page1).get("content")));
    }

    private JsonNode thread(Account account) throws Exception {
        String body = mockMvc.perform(get("/api/v1/reviews/{id}/discussions", review.getId())
                        .param("size", "50").header(HttpHeaders.AUTHORIZATION, bearer(account)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("content");
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    /** Cria discussões em ordem cronológica estrita, para tornar a ordenação verificável. */
    private ReviewDiscussion comment(Account author, UUID parentId, DiscussionStatus status) {
        clock = clock.plusSeconds(1);
        return discussionRepository.save(new ReviewDiscussion(null, review.getId(), author.userId(), parentId,
                "Comentário de teste", author.userId().equals(owner.userId()), status, clock, clock));
    }

    private Review createReview(UUID authorId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Lugar Thread " + suffix, "lugar-thread-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
        return reviewRepository.save(new Review(null, authorId, place.getId(), "Avaliação com thread",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
    }

    private static String bearer(Account account) {
        return "Bearer " + account.token();
    }

    private record Account(UUID userId, String handle, String token) {}

    private Account register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String handle = prefix + "_" + suffix;
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                prefix + "." + suffix + "@rewit.test", "SenhaSegura123!", handle, "Conta " + prefix))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Account(UUID.fromString(json.get("user").get("id").asText()), handle, json.get("accessToken").asText());
    }
}
