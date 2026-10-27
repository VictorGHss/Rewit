# GUIA DE CONFIGURAÇÃO E AMBIENTE LOCAL (docs/development/local-setup.md)

Este guia orienta a preparação do ambiente de desenvolvimento local na máquina de trabalho (Windows, macOS ou Linux).

---

## 1. Pré-Requisitos Recomendados

| Ferramenta | Versão Recomendada | Finalidade |
| :--- | :--- | :--- |
| **Java JDK** | 25 (Temurin / Corretto; `java.version` do `pom.xml`) | Execução do Backend Spring Boot |
| **Maven** | 3.9+ (ou `./mvnw`) | Build e gerenciamento do Java |
| **Docker Desktop** | 26+ (com Compose v2) | Orquestração da infraestrutura de banco e cache |
| **Node.js** | 20 LTS (LTS Iron) | Execução e build do Admin React |
| **Flutter SDK** | 3.24+ | Desenvolvimento do app mobile |
| **Git** | 2.40+ | Controle de versão |

---

## 2. Passo a Passo Inicial

### Passo 1: Configuração do Arquivo de Ambiente
Na raiz do repositório, duplique o modelo de exemplo para criar seu arquivo local:
```powershell
Copy-Item .env.example .env
```
*(No Linux/macOS: `cp .env.example .env`)*.

### Passo 2: Inicialização da Infraestrutura Local
Suba os containers essenciais (PostgreSQL + PostGIS, Redis e SeaweedFS):
```powershell
docker compose up -d
```
Verifique se os serviços estão com status saudável (*healthy*):
```powershell
docker compose ps
```

### Passo 3: Execução do Backend
Acesse a pasta do backend e execute a aplicação:
```powershell
cd backend
./mvnw spring-boot:run
```
O backend estará acessível em: `http://localhost:8080`.
Documentação Swagger/OpenAPI: `http://localhost:8080/swagger-ui.html`.

### Passo 4: Execução do Painel Admin
Em um novo terminal:
```powershell
cd apps/admin
npm install
npm run dev
```
O painel estará acessível em: `http://localhost:5173`.

### Passo 5: Execução do Aplicativo Mobile
Conecte um dispositivo Android/iOS físico ou inicie um emulador:
```powershell
cd apps/mobile
flutter pub get
flutter run
```

---

## 3. Tabela de Portas e Acessos Locais

| Serviço | Porta do Host | Credenciais Padrão (Ambiente Local) | Finalidade |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + PostGIS** | `5432` | Definido no `.env` (Padrão: `rewit_user` / `rewit_db`) | Banco de Dados Primário |
| **Redis** | `6379` | Sem senha em desenvolvimento | Cache e Rate Limiting |
| **SeaweedFS S3 API** | `8333` | Configurado via `.env` (Bucket: `rewit-local`) | API S3 de Upload de Fotos e Mídias |
| **SeaweedFS Master UI** | `9333` | N/A | Painel de Status / Topologia |
| **SeaweedFS Filer UI** | `8888` | N/A | Navegador de Arquivos e Buckets |
| **Backend API** | `8080` | N/A | API REST Central |
| **Admin Web** | `5173` | N/A | Interface de Gestão React |

---

## 4. Role de Runtime do PostgreSQL (`rewit_app`)

O backend conecta como `rewit_app` (sem DDL, sem superuser). O nome é fixo: as migrations concedem os privilégios de runtime somente a ela, e o init recusa outro `APP_DB_USER`. Os privilégios são explícitos (ADR-015): `DELETE` só nas tabelas com exclusão física legítima, PostGIS somente leitura, e uma tabela nova não nasce gravável pelo runtime; a migration que a cria concede o necessário e termina com `PERFORM rewit_assert_runtime_privileges();`. Na criação do volume, o init do container a provisiona com a senha de `APP_DB_PASSWORD` — **obrigatória e sem valor padrão**: com ela vazia, ausente ou igual ao exemplo, o init falha com mensagem explícita.

Para provisionar de novo ou rotacionar a senha num banco existente, altere `APP_DB_PASSWORD` no `.env`, recrie o container (`docker compose up -d postgres`; o volume é preservado) e execute:
```powershell
docker compose exec postgres /docker-entrypoint-initdb.d/02-create-app-role.sh
```
A senha chega ao `psql` pela entrada padrão e o servidor recebe apenas o verificador SCRAM. Fora do Docker, use `psql -U rewit_user -d rewit_db -v app_user=rewit_app -f infrastructure/postgres/provision-runtime-role.sql` (a senha é pedida sem eco).

O teste `bash infrastructure/postgres/tests/provisioning-test.sh [commit]` valida o provisionamento a partir de um clone limpo, em containers descartáveis.

---

## 5. Testes Automatizados do Backend

Os testes de integração usam **somente** infraestrutura descartável declarada por variáveis `TEST_*`; não há valores padrão nem fallback para as variáveis de desenvolvimento (`POSTGRES_*`, `REDIS_*`, `OBJECT_STORAGE_*`). Sem elas, a suíte falha antes de qualquer conexão. Portas compartilhadas (5432, 5433, 6379, 8333) são recusadas.

| Variável | Conteúdo |
| :--- | :--- |
| `TEST_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:<porta>/<database>` do PostgreSQL descartável |
| `TEST_APP_DB_USER` / `TEST_APP_DB_PASSWORD` | Role de runtime (`rewit_app`) provisionada no banco de teste |
| `TEST_MIGRATION_DB_USER` / `TEST_MIGRATION_DB_PASSWORD` | Role de migração (owner do schema; precisa de `CREATEDB` e `CREATEROLE` para o teste de upgrade, que cria databases e roles descartáveis); é também a conexão administrativa das limpezas físicas de fixtures, que o runtime não pode fazer |
| `TEST_REDIS_HOST` / `TEST_REDIS_PORT` | Redis descartável (`TEST_REDIS_PASSWORD` opcional) |
| `TEST_STORAGE_ENDPOINT` | `http://127.0.0.1:<porta>` do S3 descartável (porta explícita) |
| `TEST_STORAGE_ACCESS_KEY` / `TEST_STORAGE_SECRET_KEY` / `TEST_STORAGE_BUCKET` | Credenciais e bucket do S3 descartável |

Operações que a role de runtime não pode executar — atribuir papel a um usuário ou excluir fisicamente avaliações, comentários e respostas — são feitas nos testes por `PrivilegedTestDatabase`, com a credencial de migração do banco descartável; o código sob teste continua como `rewit_app`.

O PostgreSQL de teste deve ser criado com os scripts de `infrastructure/postgres/init` (montando também `infrastructure/postgres` em `/opt/rewit/postgres`), para que `rewit_app` exista. O teste de upgrade de migrations cria e remove o próprio database descartável no mesmo servidor e nunca executa `flyway clean`.

```powershell
cd backend
./mvnw clean test
```

---

## 6. Uso do Cloudflare Tunnel para Testes Externos

O backend roda no Windows (Java 25, `mvnw`) e o `cloudflared` roda em container, encaminhando para `http://host.docker.internal:8080`. O Docker Desktop entrega essa conexão ao host como vinda de `127.0.0.1`, por isso a API publicada escuta só em loopback.

O ambiente publicado nunca usa os serviços de desenvolvimento (`rewit-postgres`, `rewit-redis`, `rewit-seaweedfs`) nem o `.env`: tem infraestrutura própria em `docker-compose.external-test.yml` (projeto `rewit-external-test`) e arquivo de ambiente próprio, `.env.external-test` (ignorado pelo Git).

| Recurso | Nome | Porta no host |
| :--- | :--- | :--- |
| PostgreSQL + PostGIS (banco `rewit_ext`, volume `rewit_ext_postgres_data`) | `rewit-ext-postgres` | `127.0.0.1:15432` |
| Redis com senha (volume `rewit_ext_redis_data`) | `rewit-ext-redis` | `127.0.0.1:16379` |
| SeaweedFS S3 (volume `rewit_ext_seaweedfs_data`) | `rewit-ext-seaweedfs` | `127.0.0.1:18333` |
| Cloudflare Tunnel (profile `tunnel`, rede `rewit-ext-tunnel`, sem acesso aos dados) | `rewit-ext-cloudflared` | nenhuma |

O banco nasce vazio e o schema é criado pelas migrations Flyway na primeira inicialização da API.

### Perfil `external-test`

Use sempre `SPRING_PROFILES_ACTIVE=external-test`, sem o perfil `local`:

- toda credencial vem do ambiente, sem valor padrão (`DATABASE_URL`, `APP_DB_PASSWORD`, `POSTGRES_USER`/`POSTGRES_PASSWORD` ou `FLYWAY_DB_*`, `REDIS_HOST`, `REDIS_PORT`, `OBJECT_STORAGE_*`, `JWT_SECRET`, `RATE_LIMIT_KEY_SECRET`); variável ausente, vazia ou com valor de exemplo (`change-me`, fallbacks `rewit_local_*`) impede a inicialização com mensagem que cita a variável, nunca o valor;
- API em `127.0.0.1:8080` (`SERVER_ADDRESS` sobrescreve) e Actuator em `127.0.0.1:8081`; o túnel encaminha somente a 8080;
- IP do cliente gravado na sessão vem de `CF-Connecting-IP` e só quando a conexão chega de `127.0.0.1` (o `cloudflared`); `X-Forwarded-For` não é lido em nenhum perfil;
- Swagger UI e OpenAPI desligados;
- as portas dos serviços compartilhados (PostgreSQL 5432/5433, Redis 6379, storage 8333) são recusadas: as migrations Flyway nunca rodam no banco de desenvolvimento.

### Inicialização

Na raiz do repositório, uma única vez, gere o ambiente (credenciais aleatórias, nunca exibidas; o script não sobrescreve um arquivo existente):
```powershell
.\scripts\new-external-test-env.ps1
```
Suba a infraestrutura isolada e a API (o script de inicialização só aceita arquivo com `REWIT_EXTERNAL_TEST_ENV=1` e carrega as variáveis só no próprio processo):
```powershell
docker compose -f docker-compose.external-test.yml --env-file .env.external-test up -d
.\scripts\start-external-test.ps1
```
Em outro terminal, depois de preencher `CLOUDFLARE_TUNNEL_TOKEN` no `.env.external-test`:
```powershell
docker compose -f docker-compose.external-test.yml --env-file .env.external-test --profile tunnel up -d cloudflared
```
Para parar sem perder os dados de teste, use `stop` (o `down -v` apagaria os volumes `rewit_ext_*`):
```powershell
docker compose -f docker-compose.external-test.yml --env-file .env.external-test stop
```
No painel Cloudflare, o hostname público do túnel aponta para o serviço `HTTP` `host.docker.internal:8080`, sem aplicação do Cloudflare Access (o app mobile não passa por SSO).

### App mobile

A URL base vem de `--dart-define=API_BASE_URL=<origem>`, sem `/api/v1` (as rotas já incluem o prefixo):
```powershell
cd apps/mobile
flutter run --dart-define=API_BASE_URL=https://rewit-app.ctrls.dev.br
```

#### Login social (opcional)

Os botões "Continuar com Google" e "Continuar com Apple" ficam desabilitados até a build receber os identificadores públicos do provedor; nenhum segredo vai para o app:

| `--dart-define` | Uso |
| :--- | :--- |
| `GOOGLE_SERVER_CLIENT_ID` | Client ID "Web application" do Google Cloud (audiência do ID token). Obrigatório no Android e no iOS |
| `GOOGLE_IOS_CLIENT_ID` | Client ID iOS (exige também o URL scheme invertido em `ios/Runner/Info.plist`) |
| `APPLE_SIGNIN_ENABLED` | `true` quando o app iOS tem a capability Sign in with Apple |
| `APPLE_SERVICE_ID` e `APPLE_REDIRECT_URI` | Services ID e retorno `https://<api>/api/v1/auth/apple/callback` do fluxo web da Apple (Android) |

O backend aceita os mesmos client IDs em `GOOGLE_SIGNIN_CLIENT_IDS` e `APPLE_SIGNIN_CLIENT_IDS` (bundle ID e Services ID). No Android, o Google também exige um client ID "Android" no mesmo projeto do Google Cloud, com o `applicationId` (`app.rewit.mobile`) e o SHA-1 do certificado que assina o APK. Contratos em `docs/api/authentication.md` §7 a §9.
