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
| **Flutter SDK** | 3.47+ (Dart 3.13+) | Desenvolvimento do app mobile |
| **Android SDK** | API 36 (`compileSdk = 36`) | Compilação e testes nativos Android |
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

### Passo 5: Execução e Validação do Aplicativo Mobile
Conecte um dispositivo Android/iOS físico ou inicie o emulador oficial (`Rewit_API_36` / Android API 36):
```bash
cd apps/mobile
flutter pub get
flutter run
```

Comandos de análise estática e testes automatizados:
```bash
# Análise estática do Dart (0 issues)
dart analyze

# Suíte completa de testes automatizados do Flutter (1.462 testes)
flutter test

# Testes unitários nativos Android (9 testes Kotlin / JUnit na máquina de estados de permissão)
cd android
./gradlew :app:testDebugUnitTest
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

## 4. Roles de Runtime do PostgreSQL (`rewit_app`, `rewit_media` e `rewit_mod`)

O backend opera com três roles PostgreSQL separadas sem DDL e sem superuser (ADR-015, ADR-018):
- **`rewit_app`** (`APP_DB_USER` / `APP_DB_PASSWORD`): identidade pública e comum para fluxos de usuário, catálogo, leitura e criação de denúncias de avaliação/discussão e reivindicações `PENDING` (sem escrita em trilhas de auditoria nem `UPDATE` em `place_media`).
- **`rewit_media`** (`MEDIA_DB_USER` / `MEDIA_DB_PASSWORD`): identidade dedicada exclusivamente ao ciclo de vida público/automático de mídias de locais (remoção por autor/gerente/proprietário, denúncias/auto-quarentena, capa e expurgo `purged_at` > 24h), roteada transacionalmente por `@MediaLifecycleTransactional` (SEC-F2-03).
- **`rewit_mod`** (`MOD_DB_USER` / `MOD_DB_PASSWORD`): identidade restrita exclusivamente aos 6 módulos administrativos de moderação, reivindicações comerciais e ciclo de vida de contas, roteada transacionalmente por `@ModerationTransactional`.

Os nomes são fixos (`rewit_app`, `rewit_media` e `rewit_mod`): as migrations concedem privilégios somente a essas roles, não há membership nem `SET ROLE` cruzado entre elas, e o init recusa outros nomes ou senhas iguais/vazias. Os privilégios são explícitos: `DELETE` só nas tabelas com exclusão física legítima, PostGIS somente leitura, e uma tabela nova não nasce gravável; a migration que a cria concede o necessário e termina com `PERFORM rewit_assert_runtime_privileges();`. Além disso, a aplicação exige `MODERATION_AUDIT_SECRET` (>= 32 bytes, independente de todos os demais segredos e senhas de banco) e `MODERATION_AUDIT_KEY_ID` (padrão `v1`) para autenticação criptográfica `HMAC-SHA256` das trilhas de auditoria e decisões administrativas.

Para provisionar de novo ou rotacionar senhas num banco existente, altere `APP_DB_PASSWORD`, `MEDIA_DB_PASSWORD` e/ou `MOD_DB_PASSWORD` no `.env`, recrie o container (`docker compose up -d postgres`; o volume é preservado) e execute:
```powershell
docker compose exec postgres /docker-entrypoint-initdb.d/02-create-app-role.sh
```
As senhas chegam ao `psql` pela entrada padrão e o servidor recebe apenas os verificadores SCRAM. Fora do Docker, use `psql -U rewit_user -d rewit_db -v app_user=rewit_app -f infrastructure/postgres/provision-runtime-role.sql` (ou `-v app_user=rewit_media` / `-v app_user=rewit_mod`; a senha é pedida sem eco).

O teste `bash infrastructure/postgres/tests/provisioning-test.sh [--working-tree|commit]` valida o provisionamento das três roles e o isolamento `SET ROLE` a partir de um clone limpo (ou do working tree local), em containers descartáveis.

---

## 5. Testes Automatizados do Backend

Os testes de integração usam **somente** infraestrutura descartável declarada por variáveis `TEST_*`; não há valores padrão nem fallback para as variáveis de desenvolvimento (`POSTGRES_*`, `REDIS_*`, `OBJECT_STORAGE_*`). Sem elas, a suíte falha antes de qualquer conexão. Portas compartilhadas (5432, 5433, 6379, 8333) são recusadas.

| Variável | Conteúdo |
| :--- | :--- |
| `TEST_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:<porta>/<database>` do PostgreSQL descartável |
| `TEST_APP_DB_USER` / `TEST_APP_DB_PASSWORD` | Role de runtime pública (`rewit_app`) provisionada no banco de teste |
| `TEST_MEDIA_DB_USER` / `TEST_MEDIA_DB_PASSWORD` | Role dedicada ao ciclo de vida público de mídias (`rewit_media`) provisionada no banco de teste, com senha independente |
| `TEST_MOD_DB_USER` / `TEST_MOD_DB_PASSWORD` | Role restrita de moderação (`rewit_mod`) provisionada no banco de teste, com senha independente de `TEST_APP_DB_PASSWORD` e `TEST_MEDIA_DB_PASSWORD` |
| `TEST_MIGRATION_DB_USER` / `TEST_MIGRATION_DB_PASSWORD` | Role de migração (owner do schema; precisa de `CREATEDB` e `CREATEROLE` para o teste de upgrade, que cria databases e roles descartáveis); é também a conexão administrativa das limpezas físicas de fixtures, que o runtime não pode fazer |
| `TEST_REDIS_HOST` / `TEST_REDIS_PORT` | Redis descartável (`TEST_REDIS_PASSWORD` opcional) |
| `TEST_STORAGE_ENDPOINT` | `http://127.0.0.1:<porta>` do S3 descartável (porta explícita) |
| `TEST_STORAGE_ACCESS_KEY` / `TEST_STORAGE_SECRET_KEY` / `TEST_STORAGE_BUCKET` | Credenciais e bucket do S3 descartável |

Operações que a role de runtime não pode executar — atribuir papel a um usuário ou excluir fisicamente avaliações, comentários e respostas — são feitas nos testes por `PrivilegedTestDatabase`, com a credencial de migração do banco descartável; o código sob teste continua como `rewit_app`.

O PostgreSQL de teste deve ser criado com os scripts de `infrastructure/postgres/init` (montando também `infrastructure/postgres` em `/opt/rewit/postgres`), para que `rewit_app` exista. O teste de upgrade de migrations cria e remove o próprio database descartável no mesmo servidor e nunca executa `flyway clean`.

### 5.1 Infraestrutura descartável com `scripts/test-infra.ps1`

**Pré-requisitos**: Docker Desktop em execução e Windows PowerShell 5.1 ou PowerShell 7. As imagens são as do `docker-compose.yml` (`postgis/postgis:18-3.6`, `redis:8-alpine`, `chrislusf/seaweedfs:4.47`); na primeira execução o Docker as baixa.

Execução completa, com criação, suíte e limpeza garantida mesmo se a suíte falhar (o código de saída é o do Maven):
```powershell
.\scripts\test-infra.ps1 run
# Outros argumentos do Maven, ex.: um teste de integração isolado
.\scripts\test-infra.ps1 run -MavenArgs 'test','-Dtest=AuthenticationIntegrationTest','-Dsurefire.failIfNoSpecifiedTests=false'
```

Passo a passo, para repetir a suíte ou testes isolados na mesma infraestrutura:
```powershell
.\scripts\test-infra.ps1 up        # cria os containers e define as variáveis TEST_* nesta sessão
cd backend
.\mvnw.cmd clean test
cd ..
.\scripts\test-infra.ps1 down      # remove os containers e limpa as variáveis TEST_* da sessão
```

Execute o script diretamente (não com dot-source `.`), na mesma sessão do PowerShell que vai rodar o Maven: as variáveis `TEST_*` são definidas só no processo atual e somem ao fechar a sessão. Um terminal novo não as recebe; nele, rode `down` e `up` de novo.

| Recurso | Padrão | Parâmetro |
| :--- | :--- | :--- |
| Containers | `rewit-testinfra-local-postgres`, `-redis`, `-storage` (rótulo `rewit.test-infra=local`) | `-Instance <a-z0-9>` |
| PostgreSQL (database `rewit_test`, roles `rewit_migrator` e `rewit_app`) | `127.0.0.1:35432` | `-PostgresPort` |
| Redis (sem senha) | `127.0.0.1:36379` | `-RedisPort` |
| S3 (bucket `rewit-test`) | `127.0.0.1:38333` | `-StoragePort` |
| Espera pelos serviços | 180 s | `-ReadyTimeoutSeconds` |

**Segurança e isolamento**
- **Credenciais**: as senhas do PostgreSQL e as chaves do S3 são aleatórias a cada `up`. Chegam ao `docker run` pelo ambiente, sem aparecer na linha de comando, e não são impressas nem gravadas em arquivo. Ficam apenas nas variáveis `TEST_*` da sessão e na configuração do container descartável (`docker inspect`), que deixa de existir no `down`.
- **Portas recusadas**: as de desenvolvimento (5432, 5433, 6379, 8333, 8888, 9333), as do external-test (15432, 16379, 18333) e as da API (8080, 8081). `TestInfrastructureSafetyValidator` continua recusando as portas compartilhadas na inicialização dos testes.
- **Conflitos**: antes de criar qualquer recurso, o `up` verifica se já existe container com os mesmos nomes e se as portas estão publicadas por outro container (inclusive parado) ou em uso no host. Em caso de conflito, nada é criado.
- **Falha no `up`**: se a criação ou a espera falhar, o script remove só os containers desta instância e devolve o erro. Isso inclui um container que ficou no estado `Created` quando a porta não pôde ser publicada.
- **Limpeza**: `down` remove somente containers com o nome esperado **e** o rótulo da instância, junto com os volumes anônimos deles (`docker rm -f -v`). Um container com o mesmo nome sem esse rótulo não é removido e o comando termina com erro. Containers de desenvolvimento (`rewit-postgres`, `rewit-redis`, `rewit-seaweedfs`), do external-test (`rewit-ext-*`) e quaisquer outros nunca são reiniciados, reconfigurados nem removidos. O `.env` e o `.env.external-test` não são lidos.
- **Execuções simultâneas**: use outra instância com portas próprias, ex.: `.\scripts\test-infra.ps1 up -Instance ci -PostgresPort 35532 -RedisPort 36479 -StoragePort 38433` (passe os mesmos parâmetros no `down`).

`.\scripts\test-infra.ps1 status` lista os containers de todas as instâncias desta infraestrutura.

Sem o script, a infraestrutura pode ser criada manualmente nas mesmas condições (PostgreSQL com os scripts de init, Redis e S3 próprios, portas fora das recusadas) e as variáveis da tabela acima definidas na sessão antes de:
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

- toda credencial vem do ambiente, sem valor padrão (`DATABASE_URL`, `APP_DB_PASSWORD`, `POSTGRES_USER`/`POSTGRES_PASSWORD` ou `FLYWAY_DB_*`, `REDIS_HOST`, `REDIS_PORT`, `OBJECT_STORAGE_*`, `JWT_SECRET`, `PASSWORD_PEPPER_SECRET`, `REFRESH_TOKEN_SECRET`, `RATE_LIMIT_KEY_SECRET`); variável ausente, vazia ou com valor de exemplo (`change-me`, fallbacks `rewit_local_*`) impede a inicialização com mensagem que cita a variável, nunca o valor;

### Segredos Criptográficos Obrigatórios (Hardening de Autenticação F1)

Além de `JWT_SECRET`, a aplicação exige dois segredos dedicados e independentes (`.env.example` traz os nomes; valores reais nunca vão para o Git):

| Variável | Finalidade |
| :--- | :--- |
| `JWT_SECRET` | Assinatura HS256 dos access tokens |
| `PASSWORD_PEPPER_SECRET` | HMAC do pepper de senhas (`pw2`) vinculado ao UUID da conta |
| `REFRESH_TOKEN_SECRET` | HMAC-SHA256 do `token_hash` de `auth_sessions` |

**Formato aceito** (todos os segredos, ativos e anteriores): Base64 padrão ou Base64URL que decodifique para **pelo menos 32 bytes**, ou texto UTF-8 (com algum caractere fora do alfabeto Base64) de pelo menos 32 bytes. Uma Base64 válida porém curta (ex.: 32 caracteres = 24 bytes) é rejeitada, sem reinterpretar como texto. Gere por CSPRNG, por exemplo `openssl rand -base64 32`. Os segredos não podem coincidir entre si (inclusive com as chaves anteriores) nem conter placeholders.

**Rotação (opcional, janela transitória):**
- `PASSWORD_PEPPER_KEY_ID`: id da chave ativa, `[A-Za-z0-9_-]{1,32}`, padrão `v1`, nunca vazio.
- `PASSWORD_PEPPER_SECRET_PREVIOUS` + `PASSWORD_PEPPER_PREVIOUS_KEY_ID` (obrigatório se houver anterior; diferente do ativo). O hash antigo autentica uma vez e é regravado com a chave ativa.
- `REFRESH_TOKEN_SECRET_PREVIOUS`: manter por pelo menos o TTL máximo do refresh token (`JWT_REFRESH_TOKEN_TTL_SECONDS`, 30 dias por padrão).

Validação fail-closed no startup: valor ausente, curto, malformado, repetido ou com placeholder aborta a inicialização citando só o nome da variável.

**Bancos de dev/teste com hashes antigos:** hashes sem o formato `pw2` e sessões anteriores deixam de valer (não há produção ativa nem selamento). Recrie as contas pelo registro ou redefina senhas com um operador privilegiado. Tokens de refresh antigos (64 caracteres) são rejeitados e exigem novo login.

**Ambientes:** `docker-compose.yml` repassa os segredos ao serviço `backend`; `.env.example` os lista; `scripts/new-external-test-env.ps1` gera `JWT_SECRET`, `PASSWORD_PEPPER_SECRET` e `REFRESH_TOKEN_SECRET` em Base64URL de 48 bytes por CSPRNG e `PASSWORD_PEPPER_KEY_ID=v1`; `docker-compose.external-test.yml` só sobe a infraestrutura de dados (a API roda no host com `start-external-test.ps1`, que carrega o `.env.external-test`), e `scripts/verify-env.ps1` só procura segredos reais versionados; nenhum dos dois precisou mudar. Os testes automatizados usam segredos fixos de teste em `backend/src/test/resources/application.yml`.

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

##### Pendências para o teste com os provedores reais

Até agora o login social foi validado só com provedores simulados: ID tokens assinados por chaves RSA de teste, conferidos contra um JWKS em memória (backend), e SDKs substituídos por fakes (app). Nenhum teste com Google ou Apple de verdade foi feito. Os client IDs do login são identificadores OAuth públicos, sem relação com a chave `GOOGLE_PLACES_API_KEY` (Places API, paga, fora deste fluxo): não é preciso habilitar Places para testar o login.

Google (Android primeiro):

- [ ] Projeto no Google Cloud com a tela de consentimento OAuth configurada; enquanto em "Testing", as contas de teste cadastradas como usuários de teste.
- [ ] Client ID "Web application": vai em `--dart-define=GOOGLE_SERVER_CLIENT_ID` e em `GOOGLE_SIGNIN_CLIENT_IDS` no backend.
- [ ] Client ID "Android" com o pacote `app.rewit.mobile` e o SHA-1 do certificado que assina o APK. Hoje o build `release` também assina com a chave de debug (`android/app/build.gradle.kts`), então o SHA-1 é o do `debug.keystore` da máquina que gera o APK: cada máquina precisa do próprio SHA-1 cadastrado, ou de uma chave de assinatura compartilhada.
- [ ] iOS (opcional): client ID "iOS" para o bundle `app.rewit.mobile` em `GOOGLE_IOS_CLIENT_ID` e o URL scheme invertido desse client ID em `ios/Runner/Info.plist` (`CFBundleURLTypes`, ainda ausente). A audiência do token emitido no iOS deve ser conferida no primeiro teste; se for o client ID iOS, ele entra também em `GOOGLE_SIGNIN_CLIENT_IDS`.

Apple:

- [ ] Conta no Apple Developer Program e App ID `app.rewit.mobile` com a capability Sign in with Apple; o bundle ID entra em `APPLE_SIGNIN_CLIENT_IDS`.
- [ ] iOS: entitlement `com.apple.developer.applesignin` no target Runner (ainda não existe arquivo `.entitlements` no projeto) e build com `--dart-define=APPLE_SIGNIN_ENABLED=true`.
- [ ] Android (fluxo web): Services ID associado ao App ID, com o domínio público da API verificado e o Return URL `https://<host público>/api/v1/auth/apple/callback` (HTTPS obrigatório; o túnel do external-test serve). O Services ID vai em `APPLE_SERVICE_ID` e em `APPLE_SIGNIN_CLIENT_IDS`; a mesma URL vai em `APPLE_REDIRECT_URI`. `APPLE_SIGNIN_ANDROID_PACKAGE` continua `app.rewit.mobile`.
- [ ] Não há chave privada `.p8` nem client secret: o backend só valida o ID token e nunca troca o authorization code.

Backend e roteiro do teste:

- [ ] Saída HTTPS do backend para `www.googleapis.com` e `appleid.apple.com` (JWKS) e Redis ativo (nonces).
- [ ] Executar e registrar: conta nova criada pelo provedor, segundo login na mesma conta, e-mail de conta local existente (`409`, sem vinculação), cancelamento na tela do provedor, conta desativada (login recusado e reativação explícita) e, na Apple, e-mail oculto (relay `privaterelay.appleid.com`).
