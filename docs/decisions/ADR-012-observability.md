# ADR-012: Observabilidade V1 (Métricas, Logs e Tracing)

## Status
Proposto

## Contexto
O backend tinha só o Actuator (`health`, `info` e `metrics`, na porta da API) e 41 métricas Micrometer customizadas dos jobs (Outbox, Storage GC e cleanup de sessões), legíveis apenas por `/actuator/metrics`. Esse endpoint caía na regra `anyRequest().authenticated()`: qualquer usuário com JWT, inclusive `USER`, lia as métricas operacionais. Não havia logs estruturados, correlação entre requisição e log, nem tracing. Erros 500 inesperados não eram logados, e alguns logs registravam mensagens brutas de SDKs e o e-mail do destinatário (`MockEmailAdapter`).

## Decisão
1. **Um protocolo por sinal**: métricas por scrape Prometheus (`micrometer-registry-prometheus`, `/actuator/prometheus`); traces por OTLP; logs em JSON no stdout. Sem métricas ou logs via OTLP (o `micrometer-registry-otlp` do starter é excluído e `management.logging.export.otlp.enabled=false`). Sem fornecedor obrigatório.
2. **Porta de management** (`MANAGEMENT_SERVER_PORT`, padrão `8081`), não publicada externamente; exposição explícita de `health`, `info` e `prometheus`; `metrics` deixa de ser exposto.
3. **Proteção do Actuator**: a `SecurityFilterChain` da API também protege a porta de management. `EndpointRequest.to("health", "info", "prometheus")` sem credencial; `EndpointRequest.toAnyEndpoint()` negado. O scraper não usa JWT; o isolamento é a porta interna, configurada no deploy. Sem role nova e sem migration.
4. **Logs JSON nativos**: `logging.structured.format.console=logstash` do Spring Boot 4.1.1, sem Logstash encoder e sem `logback-spring.xml`; o perfil `local` usa texto. Stack trace limitado e sem mensagens de exceção (`SanitizedStackTracePrinter`).
5. **Dados em logs**: sem userId, sessionId, token, hash, `Authorization`, IP, User-Agent, e-mail, query string, corpo ou SQL. Erros de terceiros são logados pela classe. `objectKey` só nos logs de falha de mídia que já existiam.
6. **Sem userId** em logs, spans ou métricas. Reviews podem ser anônimas (privacidade §4): `userId` ao lado de `reviewId` desanonimizaria o autor para quem lê os logs. O `traceId` correlaciona a requisição sem expor identidade.
7. **Tracing só HTTP**: spans de servidor (Spring MVC) e do cliente do Google Places, com propagação W3C. As observações `tasks.scheduled.execution` e `spring.security` ficam desligadas. Sem instrumentação de JDBC: nenhum proxy de datasource, wrapper ou tracing de SQL.
8. **Exportação OTLP condicional**: só com `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` definido; sem ele não há exportador. Sender do JDK no lugar do OkHttp, cujo `okhttp-jvm` 5.x duplicaria o pacote `okhttp3` do MinIO (OkHttp 4.x).
9. **Sampling** por propriedade (`TRACING_SAMPLING_PROBABILITY`): `0.1` no base, `1.0` no perfil `local`; exportação desligada nos testes.
10. **Cardinalidade**: labels permitidos são método, rota normalizada, status, outcome, classe da exceção, `mode` do Storage GC e as tags padrão de JVM/processo/Hikari/Tomcat. Proibidos: identificadores, UUIDs, objectKey, tokens, IP, User-Agent, query string e mensagens de erro.

## Consequências
### Positivas:
- Métricas operacionais deixam de ser acessíveis a usuários da API.
- As métricas existentes passam a ser coletadas sem mudança de código.
- Erros 500 passam a ser registrados, correlacionados ao trace da requisição, sem mensagens de terceiros.
- Nenhuma dependência de fornecedor; nenhuma conexão de saída sem configuração explícita.

### Negativas:
- A segurança do `prometheus` depende de a porta de management não ser publicada; configurá-la igual à da API expõe o endpoint.
- Os gauges do Outbox e do Storage GC consultam o PostgreSQL a cada scrape.
- `http.url` nos spans pode conter UUIDs do path; o backend de traces precisa de acesso restrito.
- Com sampling `0.1`, só parte das requisições tem `traceId` nos logs.
- Stack traces JSON sem mensagens dificultam diagnósticos que dependem do texto da exceção.

## Alternativas Rejeitadas
- **Role dedicada para o scraper ou JWT de `ADMIN`**: exigiria mudar o enum `Role` e a CHECK constraint de `users.role` (V11); tokens de 15 minutos não servem para scrape.
- **Logstash encoder / `logback-spring.xml`**: o logging estruturado nativo cobre o formato sem dependência nova.
- **Métricas via OTLP (push)**: duplicaria a exportação e exigiria collector.
- **Tracing de JDBC**: exigiria biblioteca fora da BOM do Spring Boot e capturaria SQL e valores de parâmetros (`token_hash`, `user_id`, `ip_address`).
- **Tracing de `@Scheduled`**: redundante com as métricas `rewit.*` dos jobs; o poller do Outbox geraria um trace a cada 5 s.
- **Módulo `spring-boot-restclient` para instrumentar o Google Places**: trocaria o builder e os conversores do adapter; basta entregar o `ObservationRegistry` ao builder existente.
