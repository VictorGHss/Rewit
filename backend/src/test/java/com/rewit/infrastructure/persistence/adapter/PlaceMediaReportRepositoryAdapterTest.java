package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaReportJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AU-2: Testes Unitários de PlaceMediaReportRepositoryAdapter e Filtro AUTO_RESOLVED")
class PlaceMediaReportRepositoryAdapterTest {

    @Mock
    private PlaceMediaReportJpaRepository jpaRepository;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    private PlaceMediaReportRepositoryAdapter adapter;

    private PlaceMediaReportJpaEntity autoResolvedEntity;

    @BeforeEach
    void setUp() {
        autoResolvedEntity = new PlaceMediaReportJpaEntity();
        autoResolvedEntity.setId(UUID.randomUUID());
        autoResolvedEntity.setPlaceMediaId(UUID.randomUUID());
        autoResolvedEntity.setReporterUserId(UUID.randomUUID());
        autoResolvedEntity.setReason("SPAM");
        autoResolvedEntity.setDetail("Spam automatizado");
        autoResolvedEntity.setStatus("AUTO_RESOLVED");
        autoResolvedEntity.setCreatedAt(Instant.now());
        autoResolvedEntity.setUpdatedAt(Instant.now());
        autoResolvedEntity.setDecidedByUserId(null);
        autoResolvedEntity.setDecidedAt(Instant.now());
        autoResolvedEntity.setDecisionNotes("Resolução automática pelo sistema");
    }

    @Test
    @DisplayName("AU-2: findByStatus reconhece status 'AUTO_RESOLVED' sem erro 400 INVALID_REPORT_STATUS")
    void shouldAcceptAutoResolvedFilterStatus() {
        when(jpaRepository.findByStatusOrderByCreatedAtDescIdAsc(eq("AUTO_RESOLVED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(autoResolvedEntity)));

        PageResult<PlaceMediaReport> result = adapter.findByStatus("AUTO_RESOLVED", 0, 20);

        assertNotNull(result);
        assertEquals(1, result.totalElements());
        assertEquals(1, result.content().size());
        PlaceMediaReport report = result.content().get(0);
        assertTrue(report.isAutoResolved());
        assertNull(report.getDecidedByUserId());
        verify(jpaRepository).findByStatusOrderByCreatedAtDescIdAsc(eq("AUTO_RESOLVED"), any(Pageable.class));
    }

    @Test
    @DisplayName("AU-2: findByStatus com status inválido lança BAD_REQUEST INVALID_REPORT_STATUS")
    void shouldThrowBadRequestForInvalidReportStatus() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.findByStatus("STATUS_DESCONHECIDO", 0, 20)
        );
        assertEquals("INVALID_REPORT_STATUS", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("AU-2: findByStatus com status aceitos normaliza corretamente PENDING, ACCEPTED e REJECTED")
    void shouldNormalizeStandardStatuses() {
        when(jpaRepository.findByStatusOrderByCreatedAtDescIdAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(jpaRepository.findByStatusOrderByCreatedAtDescIdAsc(eq("ACCEPTED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(jpaRepository.findByStatusOrderByCreatedAtDescIdAsc(eq("REJECTED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertDoesNotThrow(() -> adapter.findByStatus("open", 0, 20));
        assertDoesNotThrow(() -> adapter.findByStatus("resolved", 0, 20));
        assertDoesNotThrow(() -> adapter.findByStatus("dismissed", 0, 20));
    }
}
