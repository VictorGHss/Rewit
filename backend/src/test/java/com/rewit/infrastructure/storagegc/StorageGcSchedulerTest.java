package com.rewit.infrastructure.storagegc;

import com.rewit.application.usecase.RunStorageGcCycleUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: StorageGcScheduler (Step 28.5)")
class StorageGcSchedulerTest {

    private final RunStorageGcCycleUseCase useCase = mock(RunStorageGcCycleUseCase.class);
    private final StorageGcScheduler scheduler = new StorageGcScheduler(useCase);

    @Test
    @DisplayName("Cada tick dispara exatamente um ciclo do orquestrador")
    void tickDelegatesToOrchestrator() {
        scheduler.runScheduledCycle();

        verify(useCase, times(1)).runCycle();
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("Falha inesperada do ciclo é absorvida: o próximo tick continua")
    void failureDoesNotEscapeTheScheduler() {
        when(useCase.runCycle()).thenThrow(new IllegalStateException("falha"));

        assertDoesNotThrow(scheduler::runScheduledCycle);
    }

    @Test
    @DisplayName("O scheduler só depende do orquestrador e usa o intervalo configurado")
    void schedulerOnlyDependsOnOrchestrator() throws Exception {
        for (Field field : StorageGcScheduler.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                assertEquals(RunStorageGcCycleUseCase.class, field.getType(), field.getName());
            }
        }
        Method tick = StorageGcScheduler.class.getMethod("runScheduledCycle");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertEquals("${rewit.storage-gc.interval-ms}", scheduled.fixedDelayString());
        assertTrue(Arrays.stream(StorageGcScheduler.class.getConstructors())
                .allMatch(constructor -> constructor.getParameterCount() == 1));
    }
}
