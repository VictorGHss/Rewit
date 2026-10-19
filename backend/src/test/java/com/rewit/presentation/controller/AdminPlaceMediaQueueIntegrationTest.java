package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceMediaReportRepository;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da Fila Operacional UNDER_REVIEW de Fotos (M-2)")
class AdminPlaceMediaQueueIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PlaceMediaRepository placeMediaRepository;

    @Autowired
    private PlaceMediaReportRepository placeMediaReportRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String loginUser(String email, String password, Role role) throws Exception {
        // Registra se não existir
        String handle = "u_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", email,
                                    "password", password,
                                    "handle", handle,
                                    "displayName", "Test User"
                            ))));
        } catch (Exception ignored) {}

        if (role != Role.USER) {
            User user = userRepository.findByEmail(email).orElseThrow();
            user.changeRole(role);
            userRepository.save(user);
        }

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    @DisplayName("Moderador lista fila /under-review e /queue com fotos em revisão e denúncias agregadas")
    @Transactional
    void shouldListUnderReviewQueueWithAggregatedReports() throws Exception {
        String modEmail = "mod.queue." + UUID.randomUUID() + "@rewit.com";
        String token = loginUser(modEmail, "Password123!", Role.MODERATOR);

        User author = userRepository.findByEmail(modEmail).orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(
                null, "Local Fila " + suffix, "local-fila-" + suffix,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Bairro",
                "Cidade", "UF", "BR", -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        ));

        // Cria foto sob revisão
        PlaceMedia media = new PlaceMedia(
                UUID.randomUUID(), place.getId(), author.getId(),
                "places/" + place.getId() + "/" + UUID.randomUUID() + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );
        media = placeMediaRepository.save(media);

        // Cria segundo usuário para segunda denúncia (uq_place_media_reports_reporter)
        String reporter2Email = "rep2." + UUID.randomUUID() + "@rewit.com";
        loginUser(reporter2Email, "Password123!", Role.USER);
        User reporter2 = userRepository.findByEmail(reporter2Email).orElseThrow();

        // Cria denúncias associadas
        PlaceMediaReport report1 = new PlaceMediaReport(UUID.randomUUID(), media.getId(), author.getId(), "SPAM", "Denúncia 1");
        PlaceMediaReport report2 = new PlaceMediaReport(UUID.randomUUID(), media.getId(), reporter2.getId(), "OFENSIVO", "Denúncia 2");
        placeMediaReportRepository.save(report1);
        placeMediaReportRepository.save(report2);

        // GET /api/v1/admin/place-media/under-review
        mockMvc.perform(get("/api/v1/admin/place-media/under-review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.media.id == '" + media.getId() + "')].pendingReportsCount").value(2))
                .andExpect(jsonPath("$.content[?(@.media.id == '" + media.getId() + "')].reports.length()").value(2));

        // GET /api/v1/admin/place-media/queue
        mockMvc.perform(get("/api/v1/admin/place-media/queue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Usuário comum recebe 403 Forbidden ao acessar a fila de moderação")
    void shouldDenyAccessToNonModerator() throws Exception {
        String userEmail = "user.queue." + UUID.randomUUID() + "@rewit.com";
        String token = loginUser(userEmail, "Password123!", Role.USER);

        mockMvc.perform(get("/api/v1/admin/place-media/under-review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}

