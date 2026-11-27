package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.AccountPurgeRepository.PurgeCounts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AccountPurgeRepositoryAdapter (defesa adicional de minimização em check_ins)")
class AccountPurgeRepositoryAdapterTest {

    @Mock
    private EntityManager entityManager;

    @Test
    @DisplayName("purgePersonalRelations executa limpeza defensiva de coordenadas residuais em check_ins sem DELETE")
    void purgeExecutesDefensiveCheckInCoordinateMinimizationWithoutDeletingRows() {
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1);
        when(query.executeUpdate()).thenReturn(0, 0, 0, 0, 0, 0, 0, 0, 0, 2);

        AccountPurgeRepositoryAdapter adapter = new AccountPurgeRepositoryAdapter(entityManager);
        UUID userId = UUID.randomUUID();

        PurgeCounts counts = adapter.purgePersonalRelations(userId);

        assertEquals(1, counts.sessions());
        assertEquals(2, counts.checkInLocations());
        assertEquals(3, counts.total());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(11)).createNativeQuery(sqlCaptor.capture());

        List<String> executedSqls = sqlCaptor.getAllValues();
        String checkInSql = executedSqls.getLast();
        assertTrue(checkInSql.contains("UPDATE check_ins"));
        assertTrue(checkInSql.contains("SET coordinates = NULL"));
        assertTrue(checkInSql.contains("WHEN status = 'REJECTED' THEN NULL"));
        assertFalse(executedSqls.stream().anyMatch(sql -> sql.toUpperCase().contains("DELETE FROM CHECK_INS")));
    }
}
