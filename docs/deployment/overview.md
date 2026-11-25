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
   - Redis Cluster para alta disponibilidade de cache e rate limiting (planejado: hoje o rate limiting é em memória e o Redis não é usado por nenhum fluxo).
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
- **Healthcheck interno**: consulta `http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health` sem expor a porta `8081` externamente (`EXPOSE 8080` apenas). A separação dedicada de probes `/actuator/health/liveness` e `/actuator/health/readiness` e o desacoplamento do indicador de saúde do Redis pertencem à etapa F2-DEPLOY-03.4.

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
| **Migrator (Flyway)** | `FLYWAY_DB_USER` (ou `POSTGRES_USER`) / `FLYWAY_DB_PASSWORD` (ou `POSTGRES_PASSWORD`) | `spring.flyway.user/password`, `spring.flyway.url` | Obrigatório enquanto Flyway roda no startup; distinto das 3 roles runtime. `baseline-on-migrate=false`, `transactional-lock=false`. Se `spring.flyway.url` for definida, deve usar `sslmode=verify-full` (sem `sslfactory`/`sslhostnameverifier`) e apontar para o mesmo destino lógico (host, porta e database) de `spring.datasource.url`. |
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
- **PostgreSQL**: exige exclusivamente **`sslmode=verify-full`** (em minúsculas, sem duplicatas nem `ssl=false`) na `DATABASE_URL` e em `spring.flyway.url`, garantindo criptografia, verificação da cadeia CA e validação do hostname do servidor (proteção contra MitM). O certificado CA confiável do provedor deve ser disponibilizado no container via `sslrootcert=/etc/ssl/certs/<ca>.pem` na URL JDBC ou no truststore da JVM.
- **Redis**: exige `REDIS_SSL_ENABLED=true` (e esquema `rediss://` caso `spring.data.redis.url` seja usada). Certificados privados de cluster gerenciado devem constar no truststore da JVM.
- **Object Storage S3**: exige `https://` em `OBJECT_STORAGE_ENDPOINT`.

### 5.6 Provisionamento Prévio do Bucket e Política de `ACCOUNT_EMAIL_RESERVATION_SECRET`
- **Bucket S3 pré-provisionado**: em `prod`, `OBJECT_STORAGE_AUTO_CREATE_BUCKET` é obrigatoriamente `false`. O bucket deve existir antes do deploy e a credencial do backend runtime **não** deve receber permissão `s3:CreateBucket`. Nota operacional: `auto-create-bucket=false` não comprova no startup que o bucket existe; a verificação somente leitura de acessibilidade pode ser avaliada na etapa **F2-DEPLOY-03.4**.
- **Consequência de perda ou rotação de `ACCOUNT_EMAIL_RESERVATION_SECRET`**: o segredo gera o `HMAC-SHA256` das reservas de e-mail de contas purgadas. Ele deve ser **estável e idêntico em todas as instâncias** e protegido em cofre com backup/versionamento. **Perder ou rotacionar este segredo sem migração controlada invalida o reconhecimento de reservas já gravadas**, permitindo que e-mails previamente purgados voltem a ser cadastrados.

### 5.7 Riscos Residuais e Decisões Pendentes
- **Flyway no processo de startup da API**: nesta etapa o Flyway permanece habilitado no startup (`spring.flyway.enabled=true`), mantendo a credencial migrator (`FLYWAY_DB_*` / `POSTGRES_*`) acessível nas variáveis de ambiente do processo da API. A extração de migrations para um job dedicado de deploy (removendo a credencial migrator do container runtime da API) permanece como evolução planejada.
- **Probes de liveness/readiness e política de falha do Redis**: a separação de `/actuator/health/liveness` e `/actuator/health/readiness` e o desacoplamento do health indicator do Redis serão implementados na etapa **F2-DEPLOY-03.4**.
