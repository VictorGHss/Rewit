package com.rewit.infrastructure.observability;

import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.StandardStackTracePrinter;

import java.io.IOException;
import java.util.Objects;

/**
 * Stack trace dos logs JSON sem a mensagem das exceções (Observabilidade V1, ADR-012).
 *
 * <p>Mensagens de driver, SDK ou cliente HTTP podem trazer SQL, valores de parâmetros, URLs ou chaves de objeto.
 * Cada exceção da cadeia é impressa só pelo nome da classe, com os frames. O Spring Boot instancia esta classe a
 * partir de {@code logging.structured.json.stacktrace.printer} e entrega o printer padrão já configurado com os
 * limites de tamanho e profundidade das demais propriedades {@code logging.structured.json.stacktrace.*}.
 */
public class SanitizedStackTracePrinter implements StackTracePrinter {

    private final StackTracePrinter delegate;

    public SanitizedStackTracePrinter(StandardStackTracePrinter standard) {
        this.delegate = Objects.requireNonNull(standard, "standard must not be null")
                .withFormatter(throwable -> throwable.getClass().getName());
    }

    @Override
    public void printStackTrace(Throwable throwable, Appendable out) throws IOException {
        delegate.printStackTrace(throwable, out);
    }
}
