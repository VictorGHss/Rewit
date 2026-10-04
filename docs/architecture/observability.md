# Observabilidade V1 (docs/architecture/observability.md)

Métricas, logs e traces do backend. Decisões em [ADR-012](../decisions/ADR-012-observability.md).

| Sinal | Como sai | Destino |
| :--- | :--- | :--- |
| Métricas | scrape Prometheus em `/actuator/prometheus` | Prometheus da infraestrutura, pela rede interna |
| Traces | OTLP/HTTP, só quando há endpoint configurado | collector OTLP escolhido pelo deploy (sem fornecedor fixo) |
| Logs | stdout, JSON (formato `logstash` nativo do Spring Boot) | coletor de logs do ambiente |

Não há métricas nem logs via OTLP, endpoint HTTP próprio de observabilidade ou dashboard.

---

## 1. Actuator e Porta de Management

- A API fica em `server.port` (`SERVER_PORT`, padrão `8080`) e não serve nenhum endpoint do Actuator.
- O Actuator fica em `management.server.port` (`MANAGEMENT_SERVER_PORT`, padrão `8081`), com exposição explícita de `health`, `info` e `prometheus`. `metrics`, `env`, `beans`, `configprops`, `mappings`, `loggers`, `threaddump`, `heapdump` e qualquer outro endpoint não são expostos.
- A mesma `SecurityFilterChain` da API protege a porta de management (comportamento verificado no Spring Boot 4.1.1). As regras usam `EndpointRequest`: `health`, `info` e `prometheus` sem credencial; qualquer outro endpoint do Actuator é negado (`denyAll`). Um JWT de `USER`, `MODERATOR` ou `ADMIN` não muda esse acesso. Não há role nem migration para o Actuator.
- **A proteção de rede faz parte do deploy**: a porta de management não pode ser publicada externamente. No `docker-compose.yml` só a `8080` é publicada; um Prometheus na mesma rede (`rewit-network`) acessa `rewit-backend:8081`. Se a porta de management for configurada igual à da API, `prometheus` fica acessível na porta pública: não fazer isso.
- As probes de saúde usam `/actuator/health` na porta de management. O health agrega PostgreSQL, Redis e disco e não mostra detalhes (`show-details=never`).

## 2. Métricas

Exportadas pelo `micrometer-registry-prometheus`; os instrumentos existentes não mudaram.

- **Customizadas** (41 instrumentos, sem tags dinâmicas):
  - `rewit.outbox.*` (12): 8 counters, 1 timer e 3 gauges (`pending`, `failed`, `oldest_pending_age`);
  - `rewit.storage_gc.*` (25, só com `rewit.storage-gc.enabled=true`): 22 counters, 1 timer com a tag `mode=dry_run|destructive` e 2 gauges de quarentena;
  - `rewit.auth_session_cleanup.*` (4): 3 counters e 1 timer.
- Counters e timers registrados sob demanda aparecem no endpoint após o primeiro incremento (ex.: `rewit_auth_session_cleanup_sessions_purged_total` depois do primeiro ciclo).
- **Automáticas do Spring Boot**: `http_server_requests_*`, `jvm_*`, `process_*`, `system_*`, `hikaricp_*`, `tomcat_*`, `logback_events_*`, `application_*`, entre outras.
- **Custo dos gauges**: os 3 gauges do Outbox e os 2 do Storage GC consultam o PostgreSQL a cada leitura, ou seja, a cada scrape. Com o intervalo de scrape padrão (15 s ou mais) o custo é de poucas consultas por minuto. Não há cache: o intervalo de scrape é configuração do Prometheus.
- **Observações desligadas**: `tasks.scheduled.execution` (os quatro jobs já têm métricas `rewit.*`; também elimina spans de `@Scheduled`) e `spring.security` (spans e métricas do Spring Security), via `management.observations.enable`.

### Cardinalidade

| Permitido como label | Proibido como label |
| :--- | :--- |
| método HTTP, rota normalizada (`uri`), status, outcome, classe da exceção | userId, sessionId, reviewId, mediaId, qualquer UUID |
| `mode` do Storage GC | objectKey, token, hash de token, `Authorization` |
| tags padrão de JVM, processo, Hikari e Tomcat | query string, IP, User-Agent, mensagem de exceção |

A rota de `http_server_requests` é o template do Spring MVC (`/api/v1/reviews/{id}`); requisições sem rota mapeada caem em `/**` (handler de recursos estáticos) ou `NOT_FOUND`. O limite padrão de 100 valores de `uri` (`management.metrics.web.server.max-uri-tags`) continua valendo. `ActuatorManagementPortIntegrationTest` inspeciona todas as séries e rejeita labels e valores proibidos.

## 3. Logs

- **Formato**: JSON por linha no stdout, formato `logstash` nativo do Spring Boot (`logging.structured.format.console`), sem Logstash encoder nem `logback-spring.xml`. Campos: `@timestamp`, `@version`, `message`, `logger_name`, `thread_name`, `level`, `level_value`, `traceId` e `spanId` (quando há requisição HTTP rastreada) e `stack_trace` (quando há exceção).
- **Stack trace sanitizado**: `SanitizedStackTracePrinter` imprime cada exceção da cadeia só pelo nome da classe, com os frames; mensagens de driver, SDK ou cliente HTTP não chegam ao log. Limites: 8192 caracteres e 30 frames por exceção.
- **Local**: o perfil `local` usa texto (`LOG_STRUCTURED_FORMAT` vazio). Para ver JSON localmente: `LOG_STRUCTURED_FORMAT=logstash`.
- **Erros 500 inesperados**: `GlobalExceptionHandler` registra um ERROR com a classe da exceção e o stack trace, sem path, headers, corpo ou mensagem. Exceções do framework que já são 4xx (rota inexistente, método não suportado) não geram ERROR. A resposta HTTP não mudou.
- **Erros de terceiros** (MinIO, Google Places, compensação de mídia): o log traz a classe do erro (`erro=...`) e, no MinIO, o código S3 estruturado (`codigo=...`), nunca `getMessage()`.

### Campos de log

| Campo | Regra |
| :--- | :--- |
| userId, sessionId | não logados |
| token, refresh token, hash de token, `Authorization` | não logados |
| IP, User-Agent | não logados |
| e-mail | não logado (o `MockEmailAdapter` deixou de registrar o destinatário) |
| query string, corpo de requisição, SQL e parâmetros | não logados |
| mensagem de exceção de terceiros | não logada (só a classe) |
| objectKey (`reviews/{reviewId}/{mediaId}/...`) | só nos logs de falha já existentes do upload/remoção de mídia; nenhum log novo |
| messageId e workerId do Outbox | permitidos (ids técnicos; o payload só tem `notificationId`) |

Detalhes da política em [privacy-and-lgpd.md §7](../security/privacy-and-lgpd.md).

## 4. Tracing

- **Stack**: Micrometer Tracing com bridge OpenTelemetry (`spring-boot-starter-opentelemetry`, sem o registry OTLP de métricas) e exportador OTLP/HTTP com o sender do JDK (`opentelemetry-exporter-sender-jdk`; o sender OkHttp foi excluído porque duplicaria o pacote `okhttp3` do MinIO).
- **Escopo**: spans de servidor HTTP (Spring MVC) e de cliente HTTP do Google Places, que propaga `traceparent` (W3C). Sem spans de JDBC, SQL, Redis, Spring Security ou `@Scheduled` (Outbox, Storage GC e cleanup de sessões).
- **Atributos**: método, rota normalizada (`uri`), status, outcome, classe da exceção e `http.url`. `http.url` vem da convenção padrão do Spring (`HttpServletRequest#getRequestURI`): é o path sem query string e pode conter UUIDs do path; por isso o backend de traces precisa de acesso restrito. Nenhum atributo com headers, corpo, cookies, query string, principal, IP ou User-Agent.
- **Exportação**: só quando `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` está definido (ex.: `http://<collector>:4318/v1/traces`). Sem a variável, nenhum exportador é criado e não há tentativa de conexão. A propriedade não é declarada nos YAMLs de propósito: com um valor vazio declarado, o Spring Boot criaria o exportador.
- **Sampling**: `management.tracing.sampling.probability`, via `TRACING_SAMPLING_PROBABILITY`: `0.1` no base, `1.0` no perfil `local`. Com sampling, só parte das requisições tem `traceId` no log.
- **Virtual threads**: cada requisição roda em uma virtual thread e não há hand-off assíncrono, então o contexto de trace e o MDC ficam na própria thread. Jobs agendados não têm trace.

## 5. Configuração por Ambiente

| | Base (`application.yml`) | Local (`application-local.yml`) | Testes (`src/test/resources`) |
| :--- | :--- | :--- | :--- |
| Management | `MANAGEMENT_SERVER_PORT`, padrão `8081` | herda `8081` | default do Boot (só `health`, na porta da aplicação); testes de observabilidade usam porta aleatória |
| Exposição | `health,info,prometheus` | herda | default |
| Logs | JSON (`LOG_STRUCTURED_FORMAT`, padrão `logstash`) | texto | texto |
| Sampling | `TRACING_SAMPLING_PROBABILITY`, padrão `0.1` | padrão `1.0` | exportação de spans desligada (`management.tracing.export.enabled=false`) |
| Endpoint OTLP | só por variável de ambiente | só por variável de ambiente | nenhum |

O `src/test/resources/application.yml` substitui o principal no classpath dos testes. `ObservabilityConfigurationTest` lê os arquivos reais do disco e verifica esse contrato.

## 6. Testes

- `ActuatorManagementPortIntegrationTest`: servidor real com API e management em portas aleatórias; endpoints permitidos e negados, `USER` sem acesso, Prometheus ausente da API, métricas presentes e cardinalidade.
- `ObservabilityTracingLoggingIntegrationTest`: exportador de spans em memória e stub HTTP do Google; span de servidor normalizado, span de cliente com `traceparent`, log ERROR JSON com o mesmo `traceId`, ausência de JWT, refresh token, query string, IP, User-Agent, e-mail e userId, e nenhum span fora do HTTP.
- `ObservabilityConfigurationTest`, `SanitizedStackTracePrinterTest`, `MockEmailAdapterTest`, `MinioStorageAdapterErrorLoggingTest` e o caso de log em `GooglePlacesAdapterTest`.
