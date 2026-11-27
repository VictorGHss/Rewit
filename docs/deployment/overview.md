# ESTRATÉGIA DE DEPLOYMENT E AMBIENTES (docs/deployment/overview.md)

Este documento descreve a topologia de ambientes e o ciclo de implantação da plataforma **Rewit**.

---

## 1. Topologia de Ambientes

```
[ Ambiente Local (Notebook) ] ──► [ Ambiente de Staging (Homologação) ] ──► [ Produção (Cloud) ]
  • Docker Compose                  • Docker / Kubernetes                    • Kubernetes / Managed Services
  • MinIO Local                      • MinIO / S3 Teste                       • AWS S3 / Cloudflare R2
  • Cloudflare Tunnel                • Domínio de Staging                     • Domínio Oficial / CDN Global
```

### Ambientes:
1. **Local (Desenvolvimento)**:
   - Execução local via Docker Compose com PostgreSQL+PostGIS, Redis e MinIO.
   - Variáveis lidas do arquivo `.env`.
   - Baixo consumo de hardware; testado em notebooks convencionais.
2. **Staging (Homologação)**:
   - Ambiente espelho de produção para validação de builds e testes ponta-a-ponta com o app mobile antes de publicar nas lojas.
3. **Produção**:
   - PostgreSQL gerenciado com réplicas de leitura e backups contínuos com retenção (PITR).
   - Redis standalone com TLS na primeira homologação (usado por rate limiting distribuído e nonces de login social; Redis Cluster/Sentinel permanecem fora do contrato inicial).
   - Armazenamento em nuvem com CDN global (Cloudflare + S3/R2).

---

## 2. Gestão de Segredos e Variáveis

- **Desenvolvimento Local**: Gerenciado via arquivo `.env` (ignorado no Git).
- **Produção e CI/CD**:
  - Proibido salvar arquivos `.env` em servidores ou imagens Docker.
  - Injeção através de gerenciadores de segredos seguros (AWS Secrets Manager, HashiCorp Vault ou GitHub Actions Encrypted Secrets).
- **Separação de Credenciais de Banco e Segredos Criptográficos (ADR-017 / ADR-018 / F2-DEPLOY-02 / F2-DEPLOY-02-R1)**:
  - O PostgreSQL utiliza quatro credenciais independentes: role de migração/owner (exclusiva do Flyway), `rewit_app` (`APP_DB_PASSWORD`, tráfego público comum), `rewit_media` (`MEDIA_DB_PASSWORD`, transações de ciclo de vida público/automático de mídias `@MediaLifecycleTransactional`, SEC-F2-03) e `rewit_mod` (`MOD_DB_PASSWORD`, transações administrativas `@ModerationTransactional`).
  - **Requisito obrigatório de startup (fail-closed)**: a aplicação exige `APP_DB_USER=rewit_app`, `MEDIA_DB_USER=rewit_media` e `MOD_DB_USER=rewit_mod` com senhas independentes entre si e diferentes da credencial migrator/owner (`FLYWAY_DB_*` / `POSTGRES_*`), sem fallback de `POSTGRES_USER` nem parâmetros `user`/`password` embutidos na URL JDBC. A validação de propriedades executa antes do Flyway; em seguida, `PostgresRuntimeRoleIdentityValidator` comprova nas conexões reais de cada pool que `session_user` e `current_user` coincidem com a role esperada, que a role mantém `NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT` e zero memberships, e que não é proprietária do banco atual (`pg_database.datdba`) nem de qualquer schema (`pg_namespace.nspowner`). Qualquer divergência ou falha na consulta aborta a inicialização antes de o backend aceitar tráfego. Ownership individual de objetos (tabelas/funções) e auditoria contínua de privilégios continuam sendo validados por `provision-runtime-role.sql` e `rewit_assert_runtime_privileges()`.
  - `MODERATION_AUDIT_SECRET` (>= 32 bytes CSPRNG) deve ser mantido fora do banco de dados, independente de `JWT_SECRET`, `PASSWORD_PEPPER_SECRET`, `REFRESH_TOKEN_SECRET`, `APP_DB_PASSWORD`, `MEDIA_DB_PASSWORD` e `MOD_DB_PASSWORD`. A rotação é controlada via `MODERATION_AUDIT_KEY_ID` + par opcional `MODERATION_AUDIT_SECRET_PREVIOUS` / `MODERATION_AUDIT_PREVIOUS_KEY_ID`.
  - **Evolução operacional opcional (multi-JVM)**: enquanto a API pública, o ciclo de vida de mídias e os módulos administrativos executam no mesmo monólito JVM, RCE na JVM pública ainda teria acesso em memória às credenciais configuradas. Em ambientes de maior criticidade, a arquitetura de roteamento e privilégios já permite separar o deploy em containers JVM distintos (`rewit-api-public` apenas com `rewit_app`; `rewit-api-admin` isolado em rede interna/VPN com `rewit_mod` e `MODERATION_AUDIT_SECRET`).

---

## 3. Observabilidade V1 (ADR-012)

Detalhes em [observability.md](../architecture/observability.md).

- **Portas**: a API fica em `SERVER_PORT` (padrão `8080`); o Actuator fica só em `MANAGEMENT_SERVER_PORT` (padrão `8081`), com `health`, `info` e `prometheus`.
- **Rede**: a porta de management não deve ser publicada externamente; a proteção é configuração de deploy. No `docker-compose.yml` só a `8080` é publicada, e um Prometheus na rede `rewit-network` acessa `rewit-backend:8081`. Probes de saúde usam `/actuator/health` na porta de management.
- **Métricas**: scrape Prometheus em `/actuator/prometheus`, sem credencial e sem fornecedor obrigatório.
- **Traces**: OTLP/HTTP, desligado até `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` ser definido; sampling por `TRACING_SAMPLING_PROBABILITY` (padrão `0.1`; `1.0` no perfil `local`).
- **Logs**: stdout, em JSON por padrão (`LOG_STRUCTURED_FORMAT=logstash`); o perfil `local` usa texto. O compose sobe o backend com o perfil `local`.

---

## 4. Empacotamento e Runtime do Container do Backend (`infrastructure/docker/backend.Dockerfile`)

- **Contexto de build**: `./backend` (protegido por `backend/.dockerignore`, que exclui `target/`, IDEs, logs e arquivos `.env`/segredos sem excluir `mvnw`, `.mvn/`, `pom.xml` ou `src/`).
  ```bash
  docker build -f infrastructure/docker/backend.Dockerfile -t rewit-backend:local ./backend
  ```
- **Multi-stage reproduzível (Java 25 LTS)**:
  - **Builder**: `eclipse-temurin:25-jdk-noble` (digest fixado), compilando exclusivamente via Maven Wrapper (`./mvnw`, Maven `3.9.12`) com camada dedicada de `dependency:go-offline`.
  - **Runner**: `eclipse-temurin:25-jre-noble` (digest fixado), sem JDK, Maven, fontes ou ferramentas de build.
- **Execução segura**:
  - Usuário não-root dedicado (`rewit`, UID/GID `10001`), JAR em modo somente leitura (`0444`).
  - Memória orientada por cgroup via `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"`.
  - `ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]` e `STOPSIGNAL SIGTERM`: o `exec` promove a JVM para PID 1, garantindo entrega direta de `SIGTERM` para shutdown gracioso.
- **Healthcheck interno**: consulta `http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health/readiness` (`--interval=30s --timeout=10s --start-period=60s --retries=3`, `curl -fsS --max-time 8`) sem expor a porta `8081` externamente (`EXPOSE 8080` apenas). O `HEALTHCHECK` do Docker apenas relata a prontidão do container; não deve ser confundido com política de reinicialização automática diante de falhas externas (ver §5.7).

---

## 5. Perfil de Produção e Validação Fail-Closed (`application-prod.yml`, F2-DEPLOY-03.3 / R1)

### 5.1 Seleção Explícita de Profile (Allowlist Estrita, Sem Fallback Silencioso)
- `application.yml` **não define** `spring.profiles.active` padrão. Omitir `SPRING_PROFILES_ACTIVE` aborta o startup em `RuntimeProfileEnvironmentValidator` antes de qualquer DataSource, migration Flyway ou abertura de porta HTTP.
- **Allowlist estrita de profiles reconhecidos:** apenas `local`, `external-test`, `prod` e `test` são aceitos. Qualquer profile desconhecido (por exemplo, `SPRING_PROFILES_ACTIVE=production`) é rejeitado de forma fail-closed.
- Perfis mutuamente exclusivos: `local`, `external-test` e `prod`. Combinações simultâneas entre eles (ou `test` com `prod`/`external-test`) são rejeitadas.
- Consistência com `ENVIRONMENT` (`rewit.environment`):
  - Quando `ENVIRONMENT` é um ambiente de implantação (`prod`, `production`, `staging`, `homolog`, `homologation`, `hml`), apenas `SPRING_PROFILES_ACTIVE=prod` é aceito (`local` e `external-test` são rejeitados).
  - Quando `SPRING_PROFILES_ACTIVE=prod`, valores locais (`ENVIRONMENT=local|dev|development`) são rejeitados.

### 5.2 Hardening HTTP, CORS, Logging e Gestão em `prod`
- `spring.web.error.include-message: never`, `spring.web.error.include-binding-errors: never`, `spring.web.error.include-stacktrace: never` e `spring.web.error.include-exception: false` (validados de forma fail-closed contra sobrescrita via CLI/env/JSON).
- `springdoc.api-docs.enabled: false` e `springdoc.swagger-ui.enabled: false`.
- `spring.jpa.hibernate.ddl-auto: validate` e `spring.jpa.show-sql: false` (SQL binding em `WARN`).
- Logs estruturados em JSON (`LOG_STRUCTURED_FORMAT=logstash`) com `SanitizedStackTracePrinter` — segredos, tokens, senhas e URLs JDBC com credenciais nunca são registrados.
- Encaminhamento de proxy reverso explícito (`server.forward-headers-strategy: native` + `SERVER_INTERNAL_PROXIES` sem wildcard irrestrito).
- **CORS conectado ao `SecurityFilterChain`**: `CORS_ALLOWED_ORIGINS` (`rewit.security.cors.allowed-origins`) vazio por padrão (nenhuma origem cross-origin liberada). Quando configurado, alimenta `CorsConfigurationSource` em `/api/**` com `allowCredentials=false`, métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS` e headers `Authorization, Content-Type, Accept`; em `prod`, exige origens `https://` puras (sem wildcard `*`, path, query, fragmento ou userinfo).
- **Porta de management (`MANAGEMENT_SERVER_PORT=8081`)**: separada de `SERVER_PORT=8080`, restrita por padrão a `MANAGEMENT_SERVER_ADDRESS=127.0.0.1` e nunca publicada externamente.
  - **Nota operacional de observabilidade**: `127.0.0.1` mantém o Actuator acessível ao `HEALTHCHECK` local do container, mas impede scraping de um Prometheus em outro container/pod. Para scraping externo em rede privada, configure `MANAGEMENT_SERVER_ADDRESS` para a interface interna apropriada sem expor `8081` publicamente.

### 5.3 Contrato de Variáveis Obrigatórias em `prod`

| Categoria | Variável de Ambiente | Propriedade Spring | Regras Fail-Closed |
| :--- | :--- | :--- | :--- |
| **Profile** | `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | Deve ser `prod` (único, explícito). |
| **PostgreSQL** | `DATABASE_URL` | `spring.datasource.url` | `jdbc:postgresql://host:porta/db` não local/loopback, sem userinfo ou `user`/`password`, TLS estrito **`sslmode=verify-full`** (case-sensitive, sem duplicatas, `ssl=false` ou sobrescrita via `sslfactory`/`sslfactoryarg`/`sslhostnameverifier`). |
| **Roles Runtime** | `APP_DB_USER` / `APP_DB_PASSWORD` | `spring.datasource.username/password` | Fixo `rewit_app`, senha forte e independente. |
| | `MEDIA_DB_USER` / `MEDIA_DB_PASSWORD` | `rewit.datasource.media.username/password` | Fixo `rewit_media`, senha forte e independente. |
| | `MOD_DB_USER` / `MOD_DB_PASSWORD` | `rewit.datasource.moderation.username/password` | Fixo `rewit_mod`, senha forte e independente. |
| **Flyway / Migrator** | *(Proibido na API em `prod`; exclusivo do runner pré-deploy)* | `spring.flyway.enabled=false` | Em `prod`, a API exige `spring.flyway.enabled=false` e rejeita `FLYWAY_DB_*`, `POSTGRES_USER`, `POSTGRES_PASSWORD` e propriedades `spring.flyway.*` adicionais. As migrations executam exclusivamente via `RewitMigrationRunner` pré-deploy usando `DATABASE_URL`, `FLYWAY_DB_USER` e `FLYWAY_DB_PASSWORD` (ver §5.10). |
| **Segredos Criptográficos** | `JWT_SECRET`, `PASSWORD_PEPPER_SECRET`, `REFRESH_TOKEN_SECRET`, `MODERATION_AUDIT_SECRET`, `RATE_LIMIT_KEY_SECRET` | `rewit.security.*`, `rewit.rate-limit.key-secret` | >= 32 bytes CSPRNG (Base64/Base64URL/UTF-8), sem placeholders, todos distintos entre si e das senhas de banco. `PASSWORD_PEPPER_KEY_ID` e `MODERATION_AUDIT_KEY_ID` válidos (`[A-Za-z0-9_-]{1,32}`). |
| **Account Purge** | `ACCOUNT_EMAIL_RESERVATION_SECRET` | `rewit.account.email-reservation-secret` | Obrigatório e >= 32 bytes quando `ACCOUNT_PURGE_ENABLED=true` (padrão). Opcional somente se `ACCOUNT_PURGE_ENABLED=false`. |
| **Redis** | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` (ou `spring.data.redis.url=rediss://...`) | `spring.data.redis.*` | Somente standalone nesta etapa (`cluster.*`, `sentinel.*` e `masterreplica.*` são rejeitados em `prod`, inclusive via variáveis indexadas). Host não local, porta válida (`1..65535`), autenticação sem placeholders e TLS obrigatório (`REDIS_SSL_ENABLED=true`; proibido `ssl.enabled=false` mesmo com `rediss://`). |
| **Object Storage (S3)** | `OBJECT_STORAGE_ENDPOINT`, `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY`, `OBJECT_STORAGE_BUCKET` | `rewit.minio.*` | Endpoint `https://` com porta válida (`1..65535`, se informada), sem userinfo, query ou fragmento; proibido `localhost`/`.localhost` (inclusive com trailing dot), loopback IPv4/IPv6 (expandido, IPv4-mapped e IPv4-compatible), notações IPv4 ambíguas (octal/hex/inteiro/abreviada) e `0.0.0.0`/`::` (redes privadas RFC1918 permitidas). Chaves sem placeholders, bucket explícito, `OBJECT_STORAGE_AUTO_CREATE_BUCKET=false`. |

### 5.4 Variáveis Condicionais e Integrações Opcionais
A ausência das credenciais abaixo **não** bloqueia a inicialização quando a integração está desabilitada; quando habilitadas, são validadas de forma fail-closed:
- **Rotação de chaves**: `PASSWORD_PEPPER_SECRET_PREVIOUS` + `PASSWORD_PEPPER_PREVIOUS_KEY_ID` (em par, key ID distinto); `MODERATION_AUDIT_SECRET_PREVIOUS` + `MODERATION_AUDIT_PREVIOUS_KEY_ID` (em par, key ID distinto); `REFRESH_TOKEN_SECRET_PREVIOUS`.
- **Object Storage Region**: `OBJECT_STORAGE_REGION` (`rewit.minio.region`) — opcional; quando informado, passado ao cliente S3.
- **Login Social**: `GOOGLE_SIGNIN_CLIENT_IDS` (`rewit.social-auth.google.client-ids`) e `APPLE_SIGNIN_CLIENT_IDS` (`rewit.social-auth.apple.client-ids` + `APPLE_SIGNIN_ANDROID_PACKAGE`).
- **Descoberta de Locais**: `PLACE_DISCOVERY_PROVIDER` (`GOOGLE` ou `GEOAPIFY`), `GOOGLE_PLACES_API_KEY` e `GEOAPIFY_API_KEY` (com URLs base `https://`).
- **OpenTelemetry**: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` e `TRACING_SAMPLING_PROBABILITY` (`0.0` a `1.0`).
- **Storage GC**: `STORAGE_GC_ENABLED` (desligado por padrão) e `STORAGE_GC_DRY_RUN` (`true` por padrão).
- **CORS**: `CORS_ALLOWED_ORIGINS` (vazio por padrão; apenas origens `https://` explícitas).

### 5.5 Regras de TLS e Requisitos de CA
- **PostgreSQL**: exige exclusivamente **`sslmode=verify-full`** (em minúsculas, sem duplicatas nem `ssl=false`, e sem `sslfactory`/`sslfactoryarg`/`sslhostnameverifier`) na `DATABASE_URL` tanto na API quanto no `RewitMigrationRunner`, garantindo criptografia, verificação da cadeia CA e validação do hostname do servidor (proteção contra MitM). O certificado CA confiável do provedor deve ser disponibilizado no container via `sslrootcert=/etc/ssl/certs/<ca>.pem` na URL JDBC ou no truststore da JVM.
- **Redis**: exige `REDIS_SSL_ENABLED=true` (e esquema `rediss://` caso `spring.data.redis.url` seja usada). Certificados privados de cluster gerenciado devem constar no truststore da JVM.
- **Object Storage S3**: exige `https://` em `OBJECT_STORAGE_ENDPOINT`.

### 5.6 Provisionamento Prévio do Bucket e Política de `ACCOUNT_EMAIL_RESERVATION_SECRET`
- **Bucket S3 pré-provisionado**: em `prod`, `OBJECT_STORAGE_AUTO_CREATE_BUCKET` é obrigatoriamente `false`. O bucket deve existir antes do deploy e a credencial do backend runtime **não** deve receber permissão `s3:CreateBucket`. A existência e acessibilidade do bucket são verificadas na readiness via `ObjectStorageHealthIndicator` com operação estritamente somente leitura (`HEAD` / `bucketExists`, ver §5.8).
- **Consequência de perda ou rotação de `ACCOUNT_EMAIL_RESERVATION_SECRET`**: o segredo gera o `HMAC-SHA256` das reservas de e-mail de contas purgadas. Ele deve ser **estável e idêntico em todas as instâncias** e protegido em cofre com backup/versionamento. **Perder ou rotacionar este segredo sem migração controlada invalida o reconhecimento de reservas já gravadas**, permitindo que e-mails previamente purgados voltem a ser cadastrados.

### 5.7 Health Probes, Disponibilidade de Dependências e Verificação S3 (F2-DEPLOY-03.4 / R2)
- **Separação entre Liveness e Readiness (grupos imutáveis em `prod`)**:
  - **Liveness** (`management.endpoint.health.group.liveness.include: livenessState`): avalia **exclusivamente** o estado interno do processo JVM (`management.health.livenessstate.enabled: true`). **Não pode incluir** `*`, PostgreSQL (`db`), Redis (`redis`), Object Storage (`objectStorage`) ou qualquer outro contributor, nem excluir `livenessState`. Falhas externas nunca tornam a liveness `DOWN`, evitando reinicializações em cascata.
  - **Readiness** (`management.endpoint.health.group.readiness.include: readinessState,db,redis,objectStorage`): avalia se a instância está apta a receber tráfego, exigindo `management.health.readinessstate.enabled: true` (`ACCEPTING_TRAFFIC`), os **três pools físicos PostgreSQL** (`appDataSource`, `mediaLifecycleDataSource` e `moderationDataSource`, expostos individualmente pelo `CompositeHealthContributor` `db` com `management.health.db.ignore-routing-data-sources: true`, sem duplicação do `ModerationRoutingDataSource`), conexão Redis (indicador nativo `DataRedisReactiveHealthIndicator`) e Object Storage (`ObjectStorageHealthIndicator`). Exclusões via `readiness.exclude` são bloqueadas em `prod`.
  - **Política de ordem de status (`status.order`) e comportamento de `UNKNOWN`**:
    - Em `prod`, `ProductionEnvironmentValidator` valida a ordem efetiva (global, `liveness` e `readiness`) usando `SimpleStatusAggregator` e `SimpleHttpCodeStatusMapper` reais do Spring Boot 4.1.1: `DOWN` e `OUT_OF_SERVICE` devem sempre constar e ter prioridade estrita sobre `UP` e `UNKNOWN`, e nunca podem ser mapeados para códigos HTTP de sucesso (`2xx`/`3xx`).
    - Na **readiness**, o estado `UNKNOWN` de uma dependência obrigatória **não** é tratado como saudável: a ordem efetiva é `DOWN,OUT_OF_SERVICE,UNKNOWN,UP` (`UNKNOWN` prevalece sobre `UP`) e o mapeamento HTTP explícito define `DOWN: 503`, `OUT_OF_SERVICE: 503` e `UNKNOWN: 503`. Sobrescritas que promovam `UP` acima de `DOWN`/`OUT_OF_SERVICE`/`UNKNOWN` ou mapeiem qualquer um deles para `200` abortam o startup.
- **Política de disponibilidade do Redis e Rate Limiting em `prod`**:
  - No backend, o Redis sustenta **nonces de uso único do login social** (`RedisSocialLoginNonceStore`, fail-closed `503`) e **rate limiting distribuído** (`ResilientRateLimiter`).
  - Em `prod`, `rewit.rate-limit.enabled` (`RATE_LIMIT_ENABLED`) é obrigatoriamente `true` e `rewit.rate-limit.backend.failure-mode` (`RATE_LIMIT_BACKEND_FAILURE_MODE`) **proíbe `ALLOW`** (validação fail-closed no startup).
  - **`LOCAL_FALLBACK` (padrão) vs. `DENY`**: no modo `LOCAL_FALLBACK`, cada réplica mantém contadores locais independentes em memória durante a indisponibilidade do Redis — logo, com `N` réplicas, o limite efetivo agregado pode crescer aproximadamente até `N×` o limite configurado. A readiness `DOWN` atua para drenar novo tráfego do balanceador, mas **não substitui** a proteção local (uma instância ainda pode receber requisições diretas ou em voo). Ambientes com maior exigência contra abuso durante indisponibilidade do Redis podem configurar explicitamente `RATE_LIMIT_BACKEND_FAILURE_MODE=DENY`.
  - O Redis **participa da readiness** (instância sem Redis deixa de receber tráfego), mas **não participa da liveness** (o processo permanece vivo e se recupera sem restart).
- **Verificação S3 estritamente somente leitura (`ObjectStorageHealthIndicator`) e orçamento de timeout**:
  - Executa **exclusivamente** `bucketExists` (requisição HTTP `HEAD` ao bucket configurado), utilizando cliente MinIO dedicado construído com as mesmas credenciais/endpoint/região de `MinioConfig`, timeouts curtos (`OBJECT_STORAGE_HEALTH_TIMEOUT`, padrão `3s` por chamada HTTP, `connect/read/write <= 2s`, sem retry de conexão) e cache concorrente seguro de TTL curto (`OBJECT_STORAGE_HEALTH_CACHE_TTL`, padrão `5s`) para evitar rajadas de chamadas externas.
  - **Orçamento total de timeout com `OBJECT_STORAGE_REGION` opcional**: quando `OBJECT_STORAGE_REGION` não é informada, o MinIO SDK pode executar primeiro a descoberta de região (`GET /?location`) e depois `HEAD /<bucket>` — até **2 chamadas HTTP sequenciais** (`2 × 3s = 6s` no pior caso). Somando eventual espera concorrente no single-flight e latência de DB/Redis, o `HEALTHCHECK` do Docker usa `curl --max-time 8` dentro de `--timeout=10s`, mantendo margem segura sem exigir `OBJECT_STORAGE_REGION` e sem aumentar o timeout por chamada do cliente S3.
  - **Nenhuma mutação é executada**: nunca chama `makeBucket`, `putObject`, `removeObject`, `removeBucket` ou alteração de policy/ACL. Bucket ausente, `AccessDenied`, timeout ou indisponibilidade do endpoint resultam em `DOWN` (readiness `503`), sem tentar provisionar o bucket e sem vazar detalhes.
- **Endpoints, acesso somente leitura, portas e segurança (allowlist estrita do Actuator)**:
  - **Habilitação e acesso obrigatório do endpoint `health`**: em `prod`, `application-prod.yml` define `management.endpoint.health.access: read_only`. `ProductionEnvironmentValidator` verifica o acesso efetivo via `PropertiesEndpointAccessResolver` + `Binder` (considerando `management.endpoints.access.max-permitted`, `management.endpoints.access.default`, `enabled-by-default`, `health.access` e `health.enabled`): um default global restritivo (`management.endpoints.access.default=none`) é aceito **somente** quando acompanhado de acesso de leitura explícito para `health`; configurações que resultem em `health` desabilitado ou `NONE` abortam o startup.
  - Porta interna de management (`MANAGEMENT_SERVER_PORT`, padrão `8081`, bind padrão `127.0.0.1`, `base-path=/actuator` imutável): allowlist estrita de `management.endpoints.web.exposure.include` em `prod` permitindo **somente `health`, `info` e `prometheus`**. Qualquer endpoint adicional (`scheduledtasks`, `env`, `beans`, `configprops`, `heapdump`, `threaddump`, `mappings`, `loggers`, `metrics`, `*`) ou remapeamento que desloque `health` é rejeitado no startup.
  - Porta principal da API (`SERVER_PORT`, padrão `8080`): `management.endpoint.health.probes.add-additional-paths: true` é obrigatório em `prod`, garantindo `/livez` e `/readyz` liberados sem autenticação na `SecurityFilterChain` exclusivamente por `EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, "health")`.
  - Em `prod`, `show-details=never` e `show-components=never` são validados de forma fail-closed globalmente e por grupo — as respostas públicas retornam somente `{"status":"UP"|"DOWN"|"OUT_OF_SERVICE"|"UNKNOWN"}`.
- **Docker `HEALTHCHECK` vs. probes de orquestração**:
  - O `HEALTHCHECK` em `infrastructure/docker/backend.Dockerfile` consulta `http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health/readiness` (`--interval=30s --timeout=10s --start-period=60s --retries=3`, `curl -fsS --max-time 8`). Ele apenas reporta o estado do container no runtime Docker (observação: `podman build` em formato OCI padrão pode descartar `HEALTHCHECK`, como registrado na etapa 03.2).
  - Futuras plataformas de orquestração que separem probes de liveness e readiness podem consultar `/livez` e `/readyz` na porta HTTP principal (`8080`) ou `/actuator/health/liveness` e `/actuator/health/readiness` na porta de management (`8081`, caso a interface de rede privada seja configurada via `MANAGEMENT_SERVER_ADDRESS`).

### 5.8 Riscos Residuais e Decisões Pendentes
- **Orquestradores externos ainda não versionados (Kubernetes / Amazon ECS)**: a orquestração pré-deploy implementada e testada no repositório (`F2-DEPLOY-03.5.2B`) utiliza imagem de container real (`infrastructure/docker/backend.Dockerfile`), composição de produção (`infrastructure/docker/docker-compose.prod.yml`), orquestrador executável (`scripts/deploy/run-predeploy-migrations.sh`) e validação contínua em GitHub Actions (`.github/workflows/ci.yml`). Manifests declarativos específicos de Kubernetes (`Job`/`Deployment`) ou Amazon ECS (`Task Definition`) ainda não estão versionados neste repositório e permanecem apenas como equivalência conceitual até a definição do provedor cloud alvo (ver §5.11).
- **Escape residual de baixo risco em `ReadOnlyEnforcingDataSource`**: `ResultSet.getStatement().getConnection()` permanece registrado como achado residual baixo da Subfase A (`F2-DEPLOY-03.5.2A-R1`); a proteção principal no servidor (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, role `rewit_app` sem DDL e eviction de conexão contaminada) permanece integralmente ativa.
- **Modo degradado do rate limiting (`RATE_LIMIT_BACKEND_FAILURE_MODE`)**: `ALLOW` é bloqueado em `prod`; o padrão `LOCAL_FALLBACK` mantém proteção local por instância durante indisponibilidade do Redis, porém permite crescimento aproximado de até `N×` no limite efetivo entre `N` réplicas. Para rigor máximo contra abuso sem Redis, utilize `DENY`.
- **Cache TTL do `ObjectStorageHealthIndicator` (`5s`)**: protege o provedor S3 contra rajadas de `HEAD`, com janela máxima de até `5s` para refletir transição de disponibilidade do bucket.
- **Scraping externo da porta de management**: `MANAGEMENT_SERVER_ADDRESS=127.0.0.1` isola a porta `8081` ao loopback do container (compatível com o `HEALTHCHECK` local), exigindo ajuste explícito para interface de rede privada caso um coletor externo consulte `/actuator/prometheus`.

### 5.9 Provisionamento e Compatibilidade com PostgreSQL Gerenciado (F2-DEPLOY-03.5.1)
- **Compatibilidade de versões (separação explícita):**
  - **Mínimo de sintaxe SQL identificado (migrations V1–V46):** **PostgreSQL 12** (pois `V1` pré-instala `pgcrypto` antes do primeiro uso de `gen_random_uuid()`, além de recursos de PG 11- como `hashtextextended`, `FOR UPDATE SKIP LOCKED` e `CONSTRAINT TRIGGER DEFERRABLE`). *PostgreSQL 12 **não** é recomendado nem homologado para produção.*
  - **Extensões `trusted`:** **PostgreSQL 13+** permite instalação por usuários não-superusers sob as condições documentadas, mas o provisionamento gerenciado da `03.5.1` adota pré-instalação administrativa das cinco extensões no schema `public`.
  - **Recomendação operacional de produção:** **PostgreSQL 16+** (com `SCRAM-SHA-256`).
  - **Baseline efetivamente homologada no projeto:** **PostgreSQL 18.6 + PostGIS 3.6** (`postgis/postgis:18-3.6`), utilizando `GEOGRAPHY(Point, 4326)`, índices `GIST`, `ST_DWithin`, `ST_Distance`, `ST_SetSRID`, `ST_MakePoint`, `ST_X`/`ST_Y` e leitura em `public.spatial_ref_sys`.
- **Pré-instalação administrativa das cinco extensões no schema `public`:**
  - `infrastructure/postgres/init/01-init-postgis.sql` (e o bootstrap de PostgreSQL gerenciado) pré-instala e verifica as **cinco extensões** exigidas pelo histórico imutável (`V1` e `V22`) no schema `public`: `uuid-ossp`, `postgis`, `pg_trgm`, `pgcrypto` e `unaccent`.
  - **`unaccent` deve estar em `public`**: a migration `V22` qualifica explicitamente `public.unaccent('public.unaccent'::regdictionary, value)`, e as funções/triggers de `V36–V46` fixam `SET search_path = pg_catalog, public, pg_temp`. `uuid-ossp` e `pgcrypto` permanecem pré-instaladas por compatibilidade com a `V1` imutável.
  - Com as cinco extensões pré-instaladas, `CREATE EXTENSION IF NOT EXISTS` em `V1` e `V22` não exige `SUPERUSER` do Migrator.
- **Provisionamento das roles runtime por administrador não-superuser (`CREATEROLE`) e precedência de variáveis:**
  - `infrastructure/postgres/provision-runtime-role.sql` (invocado de forma canônica por `infrastructure/postgres/init/02-create-app-role.sh`) suporta tanto `SUPERUSER` (ambiente local) quanto conta administrativa gerenciada **não-superuser com `CREATEROLE`**: não tenta executar cláusulas exclusivas de superuser (`NOSUPERUSER`, `NOREPLICATION`, `NOBYPASSRLS`) nem `NOCREATEDB` sem `CREATEDB` quando `current_user` não possui o privilégio, mas comprova de forma **fail-closed** em `pg_catalog.pg_roles`, `pg_catalog.pg_auth_members` e `pg_catalog.pg_shdepend` que as três roles fixas (`rewit_app`, `rewit_media`, `rewit_mod`) mantêm `LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOINHERIT`, `NOREPLICATION`, `NOBYPASSRLS`, **zero memberships próprias** (`pg_auth_members.member = runtime_role_oid`, sem falso positivo com o `ADMIN OPTION` inverso do PG 16+) e **zero ownership** de database, schema ou objetos.
  - Em `02-create-app-role.sh`, a conexão administrativa segue precedência explícita `POSTGRES_USER > PGUSER > postgres` e `POSTGRES_DB > PGDATABASE > $ADMIN_USER` (honrando `PGHOST`, `PGPORT`, `PGSSLMODE`, `PGSSLROOTCERT` e `PGPASSWORD` do `libpq`).
  - Se uma role preexistente possuir atributo privilegiado que a conta não-superuser não consiga remover, o script aborta antes de alterar senhas.
- **Semântica operacional da rotação de senhas das três roles runtime:**
  - A rotação ocorre **individualmente por role** (`rewit_app`, `rewit_media`, `rewit_mod`): os blocos SQL e o meta-comando `\password` não compartilham uma transação global entre as três roles.
  - Uma falha intermediária (por exemplo, na segunda ou terceira role) pode deixar credenciais **parcialmente rotacionadas** (roles anteriores já atualizadas). A saída diferente de zero jamais deve ser tratada como sucesso global: o operador deve inspecionar o erro, corrigir a causa e **reexecutar o procedimento completo** (idempotente) até obter código `0`.
  - As três senhas permanecem estritamente independentes entre si e validadas antes da primeira alteração no banco.
- **Permissão somente leitura em `public.spatial_ref_sys`:**
  - `provision-runtime-role.sql` valida de forma fail-closed que `rewit_app`, `rewit_media` e `rewit_mod` possuem `SELECT` em `public.spatial_ref_sys` e **nenhum** privilégio de escrita/DDL (`INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`), falhando com instrução operacional clara caso o PostGIS pertença ao provedor e exija ajuste administrativo pela conta proprietária da extensão.
- **Migrator separado e execução V1–V46 sem `SUPERUSER`:**
  - A identidade de migrations (`rewit_migrator`, distinta das três roles runtime) **não precisa de `SUPERUSER`**, **não precisa de `CREATEROLE`** (quando as três roles runtime já foram pré-provisionadas) e **não precisa possuir ownership do database nem do schema `public`**: basta `CONNECT` no database e `USAGE, CREATE` em `public`.
  - Todos os objetos de domínio (tabelas, triggers e as cinco funções `SECURITY DEFINER`) nascem sob ownership único e consistente do Migrator. Esse fluxo ponta a ponta em banco vazio é verificado automaticamente por `ManagedPostgresNonSuperuserMigrationIntegrationTest` (V1–V46 + `rewit_assert_runtime_privileges()` + `PostgresRuntimeRoleIdentityValidator`).
- **Conectividade e limitações de pooling:**
  - **Conexão direta ou session pooling estável é obrigatório** para o Flyway (`spring.flyway.postgresql.transactional-lock=false`, lock de sessão `pg_advisory_lock` + `V16` com `CREATE INDEX CONCURRENTLY`) e para o runtime quando `STORAGE_GC_ENABLED=true` (`PostgresAdvisoryStorageGcExecutionLock` usa `pg_try_advisory_lock`/`pg_advisory_unlock` em nível de sessão).
  - **Transaction pooling (ex.: PgBouncer `pool_mode=transaction`) não é suportado** nesta etapa.

### 5.10 Runner Isolado de Migrations e Validação Somente Leitura na API (F2-DEPLOY-03.5.2A)
- **Execução pré-deploy no mesmo artefato JAR (layout `ZIP` / `PropertiesLauncher`)**:
  - `backend/pom.xml` configura `spring-boot-maven-plugin` com `<layout>ZIP</layout>` (`Main-Class: org.springframework.boot.loader.launch.PropertiesLauncher`, mantendo `Start-Class: com.rewit.RewitApplication`).
  - O `ENTRYPOINT` padrão de `infrastructure/docker/backend.Dockerfile` é:
    ```dockerfile
    ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
    ```
    Ele continua iniciando `com.rewit.RewitApplication` sem alterações para a API.
  - **Substituição obrigatória do `ENTRYPOINT` no job pré-deploy**: apenas acrescentar argumentos (`CMD`) na imagem continuará executando a API (`RewitApplication`), pois o `ENTRYPOINT` já invoca `-jar /app/app.jar` sem `-Dloader.main`. Para executar o runner isolado a partir da mesma imagem de container, é obrigatório sobrescrever o `ENTRYPOINT`:
    ```bash
    # Docker / Podman (override explícito do ENTRYPOINT — não usar apenas CMD)
    docker run --rm \
      --entrypoint java \
      -e DATABASE_URL="jdbc:postgresql://<host>:5432/<db>?sslmode=verify-full&sslrootcert=/etc/ssl/certs/<ca>.pem" \
      -e FLYWAY_DB_USER="rewit_migrator" \
      -e FLYWAY_DB_PASSWORD="<segredo-migrator>" \
      rewit-backend:<tag> \
      -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar
    ```
    *(Se desejar herdar `$JAVA_OPTS` no container, use `--entrypoint sh` com `-c 'exec java $JAVA_OPTS -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar'`.)*
  - **Orquestração e equivalência em provedores**: a execução canônica pré-deploy via Docker/Podman e Compose (`infrastructure/docker/docker-compose.prod.yml` e `scripts/deploy/run-predeploy-migrations.sh`), bem como a equivalência conceitual para Kubernetes e Amazon ECS, estão detalhadas em §5.11.
  - `RewitMigrationRunner` não possui dependências do Spring Framework e nunca invoca `SpringApplication.run()`, inicializando apenas JDBC + Flyway (`classpath:db/migration`), sem Tomcat, Redis, Object Storage ou schedulers.
- **Contrato de ambiente e validação do migrator**:
  - Consome exclusivamente `DATABASE_URL` (`sslmode=verify-full`, host não-local, sem credenciais embutidas), `FLYWAY_DB_USER` e `FLYWAY_DB_PASSWORD`.
  - Rejeita argumentos CLI, placeholders, roles runtime (`rewit_app`/`rewit_media`/`rewit_mod`), conta `postgres`, variáveis de runtime (`APP_DB_*`/`MEDIA_DB_*`/`MOD_DB_*`), `POSTGRES_*` e overrides `SPRING_FLYWAY_*`/`FLYWAY_*`.
  - Antes de migrar, valida em `pg_catalog.pg_roles` que a role conectada não é `SUPERUSER` nem possui `CREATEROLE`, `CREATEDB`, `REPLICATION`, `BYPASSRLS` ou memberships privilegiadas, exigindo apenas `CONNECT` no database e `USAGE, CREATE` em `public` (sem exigir ownership do DB ou do schema).
  - Executa Flyway com `baselineOnMigrate=false`, `validateOnMigrate=true`, `cleanDisabled=true`, `outOfOrder=false`, `createSchemas=false` e `flyway.postgresql.transactional.lock=false` (lock de sessão via `pg_advisory_lock` para serialização de concorrência e compatibilidade com `V16`), seguido de `SELECT public.rewit_assert_runtime_privileges()`.
  - **Códigos de saída determinísticos**: `0` (sucesso), `2` (configuração inválida — não retentável), `3` (violação de identidade/privilégios do migrator — não retentável), `4` (erro determinístico de SQL/migration, validação, checksum, histórico, autenticação, TLS ou erro não classificado — não retentável), `5` (timeout ao aguardar o advisory lock de sessão porque outra instância está migrando — retentável com limite), `6` (falha transitória de conectividade/disponibilidade PostgreSQL classificada positivamente antes do início de `flyway.migrate()`, excluindo erros de autenticação `28*`, catálogo `3D000` e TLS — retentável com limite).
- **API de produção sem Flyway e sem credencial migrator**:
  - `application-prod.yml` define `spring.flyway.enabled: false` e não referencia `FLYWAY_DB_*` nem `POSTGRES_*`.
  - `ProductionEnvironmentValidator` falha em modo fail-closed caso `spring.flyway.enabled` não seja `false` ou caso qualquer credencial migrator ou propriedade adicional `spring.flyway.*` seja injetada na API em `prod`.
  - `ReadOnlyFlywaySchemaValidator` executa após `PostgresRuntimeRoleIdentityValidator` e antes do `entityManagerFactory` (Hibernate `ddl-auto=validate`) e da abertura da porta HTTP, usando exclusivamente `appDataSource` (`rewit_app`) em sessão explicitamente configurada como `READ ONLY` (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, restaurada para `READ WRITE` ao devolver ao pool). Recusa o startup diante de banco vazio, migrations pendentes, falhas, checksums divergentes ou migrations futuras/inesperadas, sem jamais executar DDL nem `rewit_assert_runtime_privileges()`.
- **Compatibilidade preservada**: os perfis `local` e `external-test` mantêm `spring.flyway.enabled=true` no startup sem alterações.

### 5.11 Concorrência, Recuperação Controlada e Orquestração Pré-Deploy (F2-DEPLOY-03.5.2B)
- **Serialização de concorrência (advisory lock de sessão + Flyway lock)**:
  - Além do lock interno do Flyway (`flyway.postgresql.transactional.lock=false`), `RewitMigrationRunner` adquire um advisory lock de sessão exclusivo no PostgreSQL (`pg_catalog.pg_try_advisory_lock(0x5257544D49475231)`, ASCII `RWTMIGR1`, distinto do lock `RWTSTGC1` do Storage GC) em uma conexão dedicada do migrator antes de `flyway.migrate()`, mantendo-o até concluir `validateWithResult()`, `info()` e `rewit_assert_runtime_privileges()`.
  - Isso elimina disputas na criação inicial de `public.flyway_schema_history` em bancos vazios e serializa execuções concorrentes inclusive através da `V16` (`CREATE INDEX CONCURRENTLY`, `executeInTransaction=false`).
  - Em caso de contenção prolongada (timeout padrão de `300s`), o runner encerra de forma determinística com `exit=5` (`EXIT_LOCK_TIMEOUT`) sem corromper o banco. Em caso de crash/`SIGKILL` do processo detentor, o PostgreSQL encerra a sessão e libera o lock automaticamente.
- **Política de falhas parciais e recuperação (fail-closed, sem auto-`repair`/`baseline`/`clean`)**:
  - **Interrupção entre migrations (progressão parcial)**: migrations já concluídas permanecem registradas em `flyway_schema_history` (`success=true`); a API recusa o startup (`ReadOnlyFlywaySchemaValidator` acusa migrations pendentes); nova execução do `RewitMigrationRunner` retoma automaticamente a partir da próxima migration pendente e é idempotente quando o schema já está em dia (`executadas=0`).
  - **Falha em migration transacional (`executeInTransaction=true`)**: o PostgreSQL reverte integralmente DDL/DML da migration que falhou sem gravar linha `failed` em `flyway_schema_history`. Após sanar a causa transitória, a reexecução do `RewitMigrationRunner` recupera automaticamente até a versão final.
  - **Falha em migration não transacional (`executeInTransaction=false`, ex.: `V16`) ou registro `success=false` no histórico**: artefatos parciais criados antes do erro podem permanecer no banco e o Flyway registra `success=false` em `flyway_schema_history`. Tanto o `RewitMigrationRunner` quanto o `ReadOnlyFlywaySchemaValidator` permanecem **fail-closed** (`exit=4` no runner; startup recusado na API). O runner **jamais** executa `repair()`, `baseline()` ou `clean()` automaticamente nem oculta falhas. A recuperação exige intervenção operacional auditada: inspecionar e sanar o estado parcial no banco, executar `flyway repair` manualmente com a credencial migrator e somente então reexecutar o job pré-deploy.
- **Orquestração pré-deploy implementada e testada no repositório (Docker/Podman + Compose + CI)**:
  - **`infrastructure/docker/docker-compose.prod.yml`**: declara `rewit-migrate` (one-shot `restart: "no"`, override de `entrypoint` para `RewitMigrationRunner`, somente `DATABASE_URL` + `FLYWAY_DB_USER` + `FLYWAY_DB_PASSWORD`) e `rewit-api` (`SPRING_PROFILES_ACTIVE=prod`, `depends_on: rewit-migrate: { condition: service_completed_successfully }`, zero variáveis `FLYWAY_DB_*`/`POSTGRES_*`/`SPRING_FLYWAY_*`), referenciando `${REWIT_BACKEND_IMAGE:?}` nos dois serviços com rootfs somente leitura (`read_only: true`), `cap_drop: [ALL]` e `no-new-privileges:true`.
  - **Imutabilidade de artefato e semântica real do Docker Compose por release**:
    - Usar a mesma variável `${REWIT_BACKEND_IMAGE}` nos dois serviços garante apenas a **mesma referência textual**, mas **não garante** que uma tag mutável (`:latest`, `:prod`) resolva para o mesmo artefato entre execuções ou pulls. Em produção, a referência recomendada deve ser imutável por digest (`imagem@sha256:<digest>`). O Docker Compose não valida sintaxe de digest por conta própria; essa garantia é operacional/de pipeline.
    - `depends_on` com `condition: service_completed_successfully` estabelece dependência de conclusão em subida limpa, porém em releases subsequentes um container one-shot `rewit-migrate` concluído em uma release anterior pode satisfazer a condição em um `docker compose up -d` posterior **sem reexecutar** o job de migrations.
    - Portanto, cada release em Docker Compose deve **baixar previamente o digest imutável, executar explicitamente um novo job `rewit-migrate` e somente após `exit=0` iniciar/atualizar `rewit-api`**:
      ```bash
      export REWIT_BACKEND_IMAGE="registry.exemplo.internal/rewit/rewit-backend@sha256:<digest>"

      # 1. Materializa o mesmo artefato imutável antes do rollout
      docker compose -f infrastructure/docker/docker-compose.prod.yml pull rewit-migrate rewit-api

      # 2. Executa explicitamente o runner pré-deploy para esta release (ou via scripts/deploy/run-predeploy-migrations.sh)
      #    Qualquer código de saída != 0 interrompe o procedimento e bloqueia a etapa seguinte.
      docker compose -f infrastructure/docker/docker-compose.prod.yml run --rm rewit-migrate

      # 3. Somente após sucesso (exit=0) do passo 2, inicia/atualiza a API com a mesma imagem já baixada
      docker compose -f infrastructure/docker/docker-compose.prod.yml up -d --no-deps --pull never rewit-api
      ```
  - **`scripts/deploy/run-predeploy-migrations.sh`**: executa o job pré-deploy em Docker ou Podman a partir da imagem informada em `--image` (preferencialmente `@sha256:<digest>`), rejeita variáveis conflitantes antes de criar o container, oferece lock opcional local via `flock` (`--lock-file`) e orienta retries **exclusivamente por códigos de saída semânticos** (sem heurística de regex em `stderr`):
    - **Não retentáveis (aborta imediatamente na 1ª tentativa e bloqueia rollout)**: `exit=2` (configuração), `exit=3` (privilégios), `exit=4` (falha determinística SQL transacional ou não transacional, checksum, histórico inconsistente, autenticação, TLS ou erro não classificado) e qualquer código de saída desconhecido (fail-closed, sem retry especulativo). Jamais aciona `repair`/`baseline`/`clean`.
    - **Retentáveis dentro de `--max-attempts`**: apenas `exit=5` (timeout de advisory lock) e `exit=6` (falha transitória de conectividade PostgreSQL classificada positivamente antes de iniciar migrations). Se todas as tentativas falharem, propaga o código não-zero e bloqueia o rollout da API.
  - **Validação contínua ponta a ponta (`infrastructure/docker/tests/migration-orchestration-test.sh` e `.github/workflows/ci.yml`)**: constrói a imagem real a partir de `infrastructure/docker/backend.Dockerfile`, executa dois containers concorrentes do runner contra PostgreSQL 18 + PostGIS 3.6 com TLS `sslmode=verify-full` comprovando zero classes Spring (`-Xlog:class+load`), valida o gate do script pré-deploy e sobe o container real da API `prod` (mesma imagem, zero variáveis migrator comprovadas via `inspect`, PostgreSQL TLS + Redis TLS + SeaweedFS S3 HTTPS) até `/actuator/health/readiness`, `/readyz` e `/livez` responderem `200 UP` sem vazamento de credenciais nos logs.
- **Delimitação explícita (implementado vs. orientação conceitual)**:
  - **Implementado e testado nesta etapa**: imagem real `backend.Dockerfile`, composição `infrastructure/docker/docker-compose.prod.yml`, orquestrador `scripts/deploy/run-predeploy-migrations.sh`, testes de concorrência/recuperação em PostgreSQL real e validação E2E em containers reais no CI.
  - **Orientação conceitual para futuros provedores (não versionada em manifests)**: em Kubernetes, o job pré-deploy equivale a um `Job` (ou pre-install/pre-upgrade hook) com `command: ["java", "-Dloader.main=com.rewit.migration.RewitMigrationRunner", "-jar", "/app/app.jar"]`, `backoffLimit` restrito e `Secret` exclusivo do migrator, precedendo o `Deployment` da API (que referencia somente o `Secret` das roles runtime); em Amazon ECS, equivale a uma Task one-shot pré-deploy com `entryPoint: ["java"]` e `command: ["-Dloader.main=com.rewit.migration.RewitMigrationRunner", "-jar", "/app/app.jar"]` bloqueando a atualização do ECS Service da API quando `exitCode != 0`.
