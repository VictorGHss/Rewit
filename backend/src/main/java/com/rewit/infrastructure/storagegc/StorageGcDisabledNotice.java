package com.rewit.infrastructure.storagegc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registra uma única vez, no boot, que o GC de storage está desligado (Step 28.5).
 */
@Component
@ConditionalOnProperty(prefix = "rewit.storage-gc", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StorageGcDisabledNotice {

    private static final Logger log = LoggerFactory.getLogger(StorageGcDisabledNotice.class);

    public StorageGcDisabledNotice() {
        log.info("Storage GC desabilitado (rewit.storage-gc.enabled=false): nenhum ciclo será agendado");
    }
}
