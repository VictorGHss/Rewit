package com.rewit.infrastructure.outbox;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Identificador único do worker do dispatcher do Outbox (Step 27.2, Parte K):
 * hostname + PID + sufixo UUID curto, resolvido UMA vez por instância
 * (composição estável para todo o ciclo de vida do processo). O sufixo UUID
 * evita colisão quando hostname e PID coincidem entre reinicializações rápidas.
 *
 * <p>Não há persistência adicional: o valor cabe na coluna {@code locked_by}
 * (VARCHAR(128)) e é suficiente para o ownership check das finalizações.
 */
@Component
public class WorkerIdProvider {

    private static final int MAX_LENGTH = 128;

    private final String workerId;

    public WorkerIdProvider() {
        this.workerId = buildWorkerId();
    }

    public String getWorkerId() {
        return workerId;
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        String shortUuid = UUID.randomUUID().toString().substring(0, 8);
        String candidate = host + "-" + pid + "-" + shortUuid;
        return candidate.length() <= MAX_LENGTH ? candidate : candidate.substring(0, MAX_LENGTH);
    }
}
