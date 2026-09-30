package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.NotificationRepository;
import com.rewit.domain.enums.NotificationType;
import com.rewit.domain.model.Notification;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Notificações In-App (Step 22.0)")
class NotificationControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private NotificationRepository notificationRepository;

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

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        UUID userId = UUID.fromString(json.get("user").get("id").asText());
        return new TestUser(token, userId, handle);
    }

    @Test
    @DisplayName("1. Endpoints de notificações devem exigir autenticação JWT (401)")
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/notifications"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me/notifications/unread-count"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/me/notifications/" + UUID.randomUUID() + "/read"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/me/notifications/read-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Deve listar notificações do usuário autenticado de forma paginada (200 OK)")
    void shouldListMyNotifications() throws Exception {
        TestUser user = registerUser("notif_list");

        notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.NEW_FOLLOWER.name(),
                "Novo seguidor", "Você tem um seguidor", "/url",
                "{\"actorId\":\"" + UUID.randomUUID() + "\",\"referenceId\":\"" + UUID.randomUUID() + "\"}"
        ));

        mockMvc.perform(get("/api/v1/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].type").value("NEW_FOLLOWER"))
                .andExpect(jsonPath("$.content[0].actorId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].referenceId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("3. Deve retornar a quantidade de notificações não lidas (200 OK)")
    void shouldReturnUnreadCount() throws Exception {
        TestUser user = registerUser("notif_count");

        notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.REVIEW_HELPFUL.name(),
                "Útil", "Conteúdo", "/url", null
        ));
        notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.NEW_DISCUSSION.name(),
                "Discussão", "Conteúdo", "/url", null
        ));

        mockMvc.perform(get("/api/v1/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("4. Deve marcar uma notificação como lida (204 No Content) de forma idempotente")
    void shouldMarkNotificationAsRead() throws Exception {
        TestUser user = registerUser("notif_mark");

        Notification notif = notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.NEW_FOLLOWER.name(),
                "Seguidor", "Conteúdo", "/url", null
        ));

        mockMvc.perform(patch("/api/v1/me/notifications/" + notif.getId() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());

        // Segunda chamada (idempotente)
        mockMvc.perform(patch("/api/v1/me/notifications/" + notif.getId() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());

        // Verifica unread count zerado
        mockMvc.perform(get("/api/v1/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("5. Deve marcar todas as notificações como lidas (204 No Content)")
    void shouldMarkAllNotificationsAsRead() throws Exception {
        TestUser user = registerUser("notif_all");

        notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.NEW_FOLLOWER.name(),
                "1", "Conteúdo", null, null
        ));
        notificationRepository.save(new Notification(
                null, user.userId(), NotificationType.NEW_DISCUSSION.name(),
                "2", "Conteúdo", null, null
        ));

        mockMvc.perform(patch("/api/v1/me/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("6. Deve retornar 404 NOT_FOUND para notificação inexistente ou pertencente a outro usuário (Anti-IDOR)")
    void shouldRejectIdorOnMarkRead() throws Exception {
        TestUser userA = registerUser("notif_id_a");
        TestUser userB = registerUser("notif_id_b");

        Notification notifB = notificationRepository.save(new Notification(
                null, userB.userId(), NotificationType.NEW_FOLLOWER.name(),
                "B Privada", "Conteúdo", null, null
        ));

        // User A tenta marcar notificação do User B
        mockMvc.perform(patch("/api/v1/me/notifications/" + notifB.getId() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));

        // Notificação inexistente
        mockMvc.perform(patch("/api/v1/me/notifications/" + UUID.randomUUID() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("7. Parâmetros de paginação inválidos devem retornar 400 Bad Request RFC 7807")
    void shouldRejectInvalidPaginationParameters() throws Exception {
        TestUser user = registerUser("notif_page");

        mockMvc.perform(get("/api/v1/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        mockMvc.perform(get("/api/v1/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));

        mockMvc.perform(get("/api/v1/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));
    }
}
