package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.DecidePlaceMediaReportRequest;
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

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.service.ModeratorRolePolicy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.containsString;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminPlaceMediaModerationController")
class AdminPlaceMediaModerationControllerUnitTest {

    @Mock
    private PlaceMediaService placeMediaService;

    @Mock
    private ModeratorRolePolicy moderatorRolePolicy;

    private AdminPlaceMediaModerationController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID moderatorId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new AdminPlaceMediaModerationController(placeMediaService, moderatorRolePolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        moderatorId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(moderatorId.toString(), "n/a", List.of());
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

        PlaceMediaReportView reportView = PlaceMediaReportView.of(report, media);
        PageResult<PlaceMediaReportView> pageResult = PageResult.of(List.of(reportView), 0, 20, 1);

        when(placeMediaService.listReports(eq(moderatorId), eq("PENDING"), eq(0), eq(20))).thenReturn(pageResult);

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

        verify(placeMediaService).listReports(moderatorId, "PENDING", 0, 20);
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

        PlaceMediaReportView reportView = PlaceMediaReportView.of(report, media);
        when(placeMediaService.getReport(eq(moderatorId), eq(reportId))).thenReturn(reportView);

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

        PlaceMediaReportView reportView = PlaceMediaReportView.of(report, media);

        when(placeMediaService.decideReport(eq(moderatorId), eq(reportId), eq("ACCEPT"), eq("Removido por violar regras")))
                .thenReturn(reportView);

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

        PlaceMediaModerationResult result = new PlaceMediaModerationResult(
                mediaId, placeId, "REMOVED", false, "Fotografia removida administrativamente"
        );

        when(placeMediaService.removeMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), eq("INADEQUADO"), eq("Foto viola regras")))
                .thenReturn(result);

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

        PlaceMediaModerationResult result = new PlaceMediaModerationResult(
                mediaId, placeId, "ACTIVE", true, "Fotografia restaurada com sucesso"
        );

        when(placeMediaService.restoreMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), eq("Restaurado após recurso")))
                .thenReturn(result);

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

    @Test
    @DisplayName("GET /{placeId}/{mediaId}/content - Download administrativo com headers privados de cache")
    void shouldDownloadMediaWithPrivateCacheHeaders() throws Exception {
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        byte[] fakeData = new byte[]{1, 2, 3, 4};

        MediaDownloadResult result = new MediaDownloadResult(fakeData, "image/jpeg", false);
        when(placeMediaService.downloadMediaForModerator(eq(moderatorId), eq(placeId), eq(mediaId))).thenReturn(result);

        mockMvc.perform(get("/api/v1/admin/place-media/{placeId}/{mediaId}/content", placeId, mediaId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Content-Type", "image/jpeg"));

        verify(moderatorRolePolicy).requireCurrentModerator(moderatorId);
    }

    @Test
    @DisplayName("DELETE /{placeId}/{mediaId} - Remoção administrativa direta via verbo HTTP DELETE")
    void shouldRemoveMediaByModeratorViaDeleteMethod() throws Exception {
        UUID placeId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMediaModerationResult result = new PlaceMediaModerationResult(
                mediaId, placeId, "REMOVED", false, "Fotografia removida pelo moderador"
        );

        when(placeMediaService.removeMediaByModerator(eq(moderatorId), eq(placeId), eq(mediaId), any(), any()))
                .thenReturn(result);

        mockMvc.perform(delete("/api/v1/admin/place-media/{placeId}/{mediaId}", placeId, mediaId)
                        .param("reasonCode", "SPAM")
                        .param("justification", "Propaganda invasiva")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.mediaId").value(mediaId.toString()));

        verify(moderatorRolePolicy).requireCurrentModerator(moderatorId);
    }

    @Test
    @DisplayName("A-4. Stale JWT: recusa com 403 FORBIDDEN quando token tem role mas banco rebaixou")
    void shouldRejectStaleJwtWhenUserDemotedInDatabase() throws Exception {
        doThrow(new BusinessException("Acesso restrito a moderadores e administradores", HttpStatus.FORBIDDEN, "FORBIDDEN"))
                .when(moderatorRolePolicy).requireCurrentModerator(moderatorId);

        mockMvc.perform(get("/api/v1/admin/place-media/reports")
                        .principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("A-4. Stale JWT: recusa com 401 ACCOUNT_DISABLED quando conta foi desativada")
    void shouldRejectWhenModeratorAccountDeactivated() throws Exception {
        doThrow(new BusinessException("Conta inativa ou suspensa", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED"))
                .when(moderatorRolePolicy).requireCurrentModerator(moderatorId);

        mockMvc.perform(get("/api/v1/admin/place-media/reports")
                        .principal(authentication))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }
}
