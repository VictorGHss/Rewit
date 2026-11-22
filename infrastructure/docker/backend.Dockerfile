# syntax=docker/dockerfile:1.7
# Multi-stage build reproduzível e seguro para a API Spring Boot (Java 25 LTS / Maven Wrapper 3.9.12)
# Contexto esperado (ver docker-compose.yml): ./backend

# ------------------------------------------------------------------------------
# Stage 1: Builder (JDK 25 LTS em Ubuntu 24.04 Noble - multi-arch OCI index)
# ------------------------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS builder

WORKDIR /workspace

# Ferramentas mínimas para o Maven Wrapper (distributionType=only-script baixa e extrai Maven 3.9.12)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Camada dedicada para o Maven Wrapper e resolução de dependências (aproveitamento de cache)
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
RUN chmod +x ./mvnw \
    && ./mvnw -B -ntp dependency:go-offline

# Compilação e empacotamento do JAR executável Spring Boot (testes executados na esteira dedicada)
COPY src ./src
RUN ./mvnw -B -ntp -DskipTests package \
    && set -- target/rewit-backend-*.jar \
    && [ "$#" -eq 1 ] \
    && cp "$1" /workspace/app.jar

# ------------------------------------------------------------------------------
# Stage 2: Runtime (JRE 25 LTS em Ubuntu 24.04 Noble - multi-arch OCI index)
# ------------------------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99 AS runner

WORKDIR /app

# Apenas curl para o HEALTHCHECK interno na porta de management (nenhum JDK, Maven ou ferramenta de build)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 rewit \
    && useradd --system --uid 10001 --gid rewit --no-create-home --shell /usr/sbin/nologin rewit \
    && chown rewit:rewit /app

COPY --from=builder --chown=rewit:rewit --chmod=0444 /workspace/app.jar /app/app.jar

USER rewit:rewit

# Apenas a porta da API; a porta de management (8081) permanece interna à rede do container
EXPOSE 8080

# Memória alinhada aos limites de cgroup do container (substitui heap fixo -Xms/-Xmx)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

STOPSIGNAL SIGTERM

# Verifica o endpoint agregado atual do Actuator na porta interna de management (ADR-012).
# A separação entre /actuator/health/liveness e /readiness pertence às etapas F2-DEPLOY-03.3/03.4.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["sh", "-c", "curl -fsS \"http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health\" >/dev/null || exit 1"]

# exec substitui o processo sh pela JVM como PID 1, garantindo entrega direta de SIGTERM para graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
