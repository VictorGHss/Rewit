package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.AdminPlaceMediaReportResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.DecidePlaceMediaReportRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.PlaceMediaModerationActionResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RemovePlaceMediaRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RestorePlaceMediaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminPlaceMediaModerationController")
class AdminPlaceMediaModerationControllerUnitTest {

    @Mock
    private PlaceMediaService placeMediaService;

    @InjectMocks
    private AdminPlaceMediaModerationController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID moderatorId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        moderatorId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(moderatorId.toString(), "password");
    }

    @Test
    @DisplayName("GET /reports - Lista denúncias com paginação")
    void shouldListReportsPaged() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(mediaId, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId + "/img.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024, 800, 600);
        PlaceMediaReport report = new PlaceMediaReport(reportId, mediaId, reporterId, "CONTEUDO_INADEQUADO", "Foto ofensiva");

        AdminPlaceMediaReportResponse responseDto = AdminPlaceMediaReportResponse.of(report, media);
        PageResult<AdminPlaceMediaReportResponse> pageResult = PageResult.of(List.of(responseDto), 0, 20, 1);

        when(placeMediaService.listReports(eq("PENDING"), eq(0), eq(20))).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/admin/place-media/reports")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.content[0].placeMediaId").value(mediaId.toString()))
                .andExpect(jsonPath("$.content[0].reason").value("CONTEUDO_INADEQUADO"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(placeMediaService).listReports("PENDING", 0, 20);
    }

    @Test
    @DisplayName("GET /reports/{id} - Obtém detalhe da denúncia")
    void shouldGetReportDetail() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(mediaId, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId + "/img.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024, 800, 600);
        PlaceMediaReport report = new PlaceMediaReport(reportId, mediaId, reporterId, "SPAM", "Propaganda");

        AdminPlaceMediaReportResponse responseDto = AdminPlaceMediaReportResponse.of(report, media);
        when(placeMediaService.getReport(reportId)).thenReturn(responseDto);

        mockMvc.perform(get("/api/v1/admin/place-media/reports/{id}", reportId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId.toString()))
                .andExpect(jsonPath("$.reason").value("SPAM"))
                .andExpect(jsonPath("$.media.id").value(mediaId.toString()));
    }

    @Test
    @DisplayName("POST /reports/{id}/decision - Deferir denúncia (ACCEPT)")
    void shouldAcceptReport() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(mediaId, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId + "/img.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024, 800, 600);
        media.markRemoved();

        PlaceMediaReport report = new PlaceMediaReport(reportId, mediaId, reporterId, "CONTEUDO_INADEQUADO", "Foto ofensiva",
                PlaceMediaReport.STATUS_ACCEPTED, Instant.now(), Instant.now(), moderatorId, Instant.now(), "Removido por violar regras");

        AdminPlaceMediaReportResponse responseDto = AdminPlaceMediaReportResponse.of(report, media);

        when(placeMediaService.decideReport(eq(moderatorId), eq(reportId), eq("ACCEPT"), eq("Removido por violar regras")))
                .thenReturn(responseDto);

        DecidePlaceMediaReportRequest request = new DecidePlaceMediaReportRequest("ACCEPT", "Removido por violar regras");

        mockMvc.perform(post("/api/v1/admin/place-media/reports/{id}/decision", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decidedByUserId").value(moderatorId.toString()))
                .andExpect(jsonPath("$.decisionNotes").value("Removido por violar regras"));
    }

    @Test
    @DisplayName("POST /reports/{id}/decision - Sem autenticação retorna 401")
    void shouldReturn401WhenDecidingWithoutAuth() throws Exception {
        UUID reportId = UUID.randomUUID();
        DecidePlaceMediaReportRequest request = new DecidePlaceMediaReportRequest("ACCEPT", "Notas");

        mockMvc.perform(post("/api/v1/admin/place-media/reports/{id}/decision", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /{placeId}/{mediaId}/remove - Remoção administrativa direta")
    void shouldRemoveMediaByModerator() throws Exception {
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMediaModerationActionResponse response = new PlaceMediaModerationActionResponse(
                mediaId, placeId, "REMOVED", false, "Fotografia removida administrativamente"
        );

        when(placeMediaService.removeMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), eq("INADEQUADO"), eq("Foto viola regras")))
                .thenReturn(response);

        RemovePlaceMediaRequest request = new RemovePlaceMediaRequest("INADEQUADO", "Foto viola regras");

        mockMvc.perform(post("/api/v1/admin/place-media/{placeId}/{mediaId}/remove", placeId, mediaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.mediaId").value(mediaId.toString()));
    }

    @Test
    @DisplayName("POST /{placeId}/{mediaId}/restore - Restauração administrativa com sucesso")
    void shouldRestoreMediaByModerator() throws Exception {
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMediaModerationActionResponse response = new PlaceMediaModerationActionResponse(
                mediaId, placeId, "ACTIVE", true, "Fotografia restaurada com sucesso"
        );

        when(placeMediaService.restoreMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), eq("Restaurado após recurso")))
                .thenReturn(response);

        RestorePlaceMediaRequest request = new RestorePlaceMediaRequest("Restaurado após recurso");

        mockMvc.perform(post("/api/v1/admin/place-media/{placeId}/{mediaId}/restore", placeId, mediaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isPrimary").value(true));
    }

    @Test
    @DisplayName("POST /{placeId}/{mediaId}/restore - Falha 409 quando storage já foi purgado")
    void shouldFailRestoreWhenStoragePurged() throws Exception {
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        when(placeMediaService.restoreMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), any()))
                .thenThrow(new BusinessException("Não é possível restaurar a fotografia pois o arquivo já foi excluído do storage",
                        HttpStatus.CONFLICT, "STORAGE_OBJECT_PURGED"));

        RestorePlaceMediaRequest request = new RestorePlaceMediaRequest("Tentativa de restaurar");

        mockMvc.perform(post("/api/v1/admin/place-media/{placeId}/{mediaId}/restore", placeId, mediaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORAGE_OBJECT_PURGED"));
    }
}
