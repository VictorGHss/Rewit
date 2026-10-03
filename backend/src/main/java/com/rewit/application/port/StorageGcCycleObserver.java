package com.rewit.application.port;

import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;

/**
 * Recebe o relatório de cada ciclo de GC de storage, inclusive ciclos ignorados ou com falha (Step 28.5).
 */
public interface StorageGcCycleObserver {

    void recordCycle(StorageGcCycleReport report);
}
