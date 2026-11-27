# Arquitetura de Autenticação Local e Sessões (Step 4)

Este documento descreve a modelagem, estratégias de segurança e decisões arquiteturais adotadas para a **Autenticação Local** na plataforma **Rewit**.

---

## 1. Visão Geral e Princípios Fundamentais

A autenticação no Rewit foi projetada para ser:
1. **Canônica e Centralizada**: A tabela `users` continua sendo a única fonte canônica de identidade.
2. **Restrita e Segura**: A autenticação local é estritamente permitida apenas quando `auth_provider = 'LOCAL'` e `password_hash IS NOT NULL`. Usuários federados (`GOOGLE`, `APPLE`) não possuem senha local e não podem autenticar por este canal.
3. **Stateless para Requisições de API**: O acesso a endpoints protegidos utiliza Access Tokens no formato JWT (JSON Web Token) transmitidos via cabeçalho HTTP `Authorization: Bearer <token>`, sem criação de sessão HTTP no servidor (`SessionCreationPolicy.STATELESS`).
4. **Stateful para Sessões de Longa Duração**: Refresh tokens são persistidos de forma segura no banco de dados (`auth_sessions`), permitindo controle fino de ciclo de vida, rotação automática, revogação e detecção de comprometimento.
5. **Agnóstica no Domínio**: O domínio do Rewit (`User`, `Profile`, `AuthSession`) permanece puro e desacoplado do Spring Security, Hibernate e bibliotecas criptográficas externas.

---

## 2. Password Hashing (Argon2id + Password Pepper Vinculado ao Usuário)

### 2.1 Algoritmo e Modelo de Ameaça Mitigado (S3-A13-F1)
O armazenamento de credenciais combina o algoritmo **Argon2id** (versão 1.3) com um **Password Pepper criptográfico vinculado à identidade do usuário**, externo ao banco de dados (`PASSWORD_PEPPER_SECRET`).

Essa arquitetura foi introduzida para mitigar o achado de segurança `S3-A13-F1` (comprometimento exclusivo da credencial SQL de runtime `rewit_app`), impedindo que um atacante com permissão de escrita no banco consiga:
1. **Fabricar hashes válidos**: sem o segredo externo `PASSWORD_PEPPER_SECRET`, o atacante não consegue calcular o HMAC correto.
2. **Transplantar hashes entre contas**: o HMAC vincula os 16 bytes binários do UUID da conta; um hash copiado diretamente de uma conta para outra falha compulsoriamente na verificação da conta de destino.
3. **Injetar hashes Argon2id crus**: qualquer hash sem o envelope `pw2` (inclusive o antigo `legacy-sealed`) é rejeitado de forma fail-closed (`INVALID_CREDENTIALS`).

### 2.2 Derivação Criptográfica e Formato Canônico `pw2`
A derivação segue estritamente a especificação criptográfica:
```text
preHash = Base64URL(HMAC-SHA256(K_password, "rewit-password-v1:" || userId_16bytes || rawPassword))
```
- **Domínio criptográfico**: prefixo não ambíguo `rewit-password-v1:`.
- **Identificador**: 16 bytes do UUID estável (big-endian), nunca e-mail ou dados mutáveis.
- **Senha bruta**: preservada exatamente como fornecida pelo usuário, sem `trim()`, truncamento ou normalização prévia.
- **Envelope Canônico**:
  ```text
  pw2$v1$[keyId]$[argon2_phc]
  ```
  Exemplo real: `pw2$v1$v1$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>` (`keyId` padrão `v1`; o PHC é gravado sem o `$` inicial, logo não existe `$$`). O `keyId` segue `[A-Za-z0-9_-]{1,32}` (sem `$`, vazio ou separadores).
  O envelope é armazenado em `users.password_hash VARCHAR(255)`.

### 2.3 Parâmetros do Argon2id (Spring Security & BouncyCastle)
- **Salt Length**: 16 bytes (128 bits criptograficamente seguros via `SecureRandom`).
- **Hash Length**: 32 bytes (256 bits).
- **Parallelism (Threads)**: 1 thread (`p=1`).
- **Memory Cost**: 19.456 KiB (19 MiB de memória por derivação, `m=19456`).
- **Iterations (Time Cost)**: 2 iterações (`t=2`).
- **Tempo médio de hashing / verificação**: ~30 a 50 ms no ambiente de execução.

### 2.4 Rotação de Pepper e Auto-Upgrade de Chaves
- Variáveis: `PASSWORD_PEPPER_KEY_ID` (ativa, padrão `v1`, nunca vazia), `PASSWORD_PEPPER_SECRET_PREVIOUS` e `PASSWORD_PEPPER_PREVIOUS_KEY_ID` (obrigatório quando houver chave anterior). IDs inválidos, vazios ou iguais entre ativa e anterior impedem a inicialização. Os segredos anteriores aceitam o mesmo formato dos ativos (§3.4) e participam da validação de independência.
- O `keyId` gravado no hash seleciona a chave: `keyId` ativo → chave ativa; `keyId` anterior → chave anterior; qualquer outro → rejeição imediata, sem Argon2. Há no máximo **uma** verificação Argon2 por tentativa (sem segunda tentativa após falha).
- Após autenticação bem-sucedida com a chave anterior, `needsUpgrade()` é verdadeiro e `AuthService.login`/`reactivate` regravam o hash com a chave ativa na mesma transação.

### 2.5 Hashes Legados e Ausência de Selamento
Esta entrega **não** inclui mecanismo de selamento/migração de hashes legados. O `LegacyPasswordSealer` e o formato `legacy-sealed` foram removidos: um selamento executado com a credencial de runtime comprometida legitimaria hashes possivelmente adulterados (o MAC passaria a atestar dados sem origem confiável).
- O Rewit não tem produção ativa; hashes Argon2id crus de bancos de desenvolvimento/teste **são rejeitados**. Esses ambientes devem recriar as contas (registro) ou fazer redefinição controlada de senha por operador privilegiado, que grava um `pw2` novo.
- Se no futuro houver necessidade real de preservar dados, será um procedimento operacional separado, exigindo snapshot confiável anterior à exposição da credencial, backup verificado, modo de manutenção, execução privilegiada, transações reais e dry-run, nunca um endpoint ou startup automático. Não existe recuperação de senha por e-mail no produto atual.

### 2.6 Verificação Simulada em Tempo Constante (`simulateVerification`)
Para mitigar enumeração de contas e ataques baseados em tempo, requisições de login para contas inexistentes, federadas ou desativadas executam `simulateVerification`, derivando HMAC e Argon2id contra um UUID fixo dummy (`00000000-0000-0000-0000-000000000000`), garantindo custo temporal indistinguível de um login com senha errada em conta existente.

---

## 3. Access Token (JWT - HS256)

### 3.1 Características do Token
- **Tipo**: JWT (RFC 7519) assinado digitalmente com HMAC-SHA256 (`HS256`).
- **Segredo Simétrico**: Configurado exclusivamente via variável de ambiente `JWT_SECRET`. Nunca commitado em código nem exposto em logs ou exceptions.
- **Validação Estrita de Inicialização (`JwtSecretValidator`)**:
  - Rejeita valores nulos, vazios ou em branco.
  - Exige comprimento mínimo de 32 bytes (256 bits) de entropia real para segurança do HS256.
  - Rejeita compulsoriamente placeholders óbvios (ex: `change-me`, `changeme`, `password`, `secret`, `default`, `123456`, etc.).
  - Rejeita valores de baixa entropia (repetição trivial de caracteres).
  - Proíbe segredos de fallback hardcoded na aplicação.
- **Validade Curta (TTL)**: 15 minutos (900 segundos) por padrão, configurável via `JWT_ACCESS_TOKEN_TTL_SECONDS`.

### 3.2 Claims Emitidas
Para manter o token compacto e evitar vazamento de dados, o JWT contém apenas claims essenciais:
- `iss` (Issuer): Emissor configurado (ex: `rewit-api`).
- `sub` (Subject): Identificador canônico único do usuário (`User.id` em formato UUID).
- `aud` (Audience): Lista de audiências autorizadas (ex: `rewit-clients`).
- `iat` (Issued At): Timestamp UTC de emissão.
- `exp` (Expiration Time): Timestamp UTC de expiração (15 minutos).
- `jti` (JWT ID): Identificador único aleatório do token (UUID v4) para auditoria.

> **Importante**: O JWT **não** contém e-mail completo, dados sensíveis, senhas, papéis excessivos ou tokens de refresh.

### 3.3 Validação e Hardening de Algoritmo
O validador aceita estritamente o algoritmo configurado (`HS256`):
- Rejeição expressa de tokens não assinados (`alg=none` / `PlainJWT`).
- Rejeição de algoritmos diferentes (ex: `HS384`, `RS256`, `ES256`), prevenindo ataques de confusão/downgrade de algoritmo.
- Rejeição de tokens com assinatura inválida ou adulterada.
- Validação temporal rigorosa: rejeita tokens expirados (`exp`) e tokens com data de ativação no futuro (`nbf`).
- Tolerância de relógio (clock skew): mantida em nível padrão de infraestrutura (máximo 60s) para absorver desvios transitórios de NTP.

---

### 3.4 Formato e Validação dos Segredos (`JWT_SECRET`, pepper, refresh)
Um único decodificador, `SecuritySecretValidator.decodeSecret`, é usado por `JwtTokenService` (assinatura), pelo `JwtDecoder` do `SecurityConfig` (validação), pelo hasher e pelo validador de ambiente; não há parsing paralelo.
- **Base64 padrão** (`+/`, com ou sem `=`) ou **Base64URL** (`-_`): decodificado para bytes; precisa resultar em **≥ 32 bytes**. Uma string Base64 válida porém curta (ex.: 32 caracteres = 24 bytes) é **rejeitada**, nunca reinterpretada como UTF-8. Base64 malformada (padding no meio, alfabetos misturados, comprimento incompatível) é rejeitada.
- **Texto** com algum caractere fora do alfabeto Base64/Base64URL: usado como bytes UTF-8, aceito só com ≥ 32 bytes e sem padrões proibidos.
- Rejeitados: ausente/vazio, placeholders conhecidos, caracteres ou bytes todos idênticos. Não se usa contagem de caracteres distintos como medida de entropia; a entropia vem da geração por CSPRNG.
- Os segredos ativos e anteriores (JWT, pepper, refresh) não podem compartilhar material binário.
- Geração recomendada: `openssl rand -base64 32` (ou 48 bytes). `scripts/new-external-test-env.ps1` gera Base64URL de 48 bytes por CSPRNG. Mensagens de erro citam só o nome da variável.

## 4. Refresh Token e Gerenciamento de Sessões

### 4.1 Estrutura Vinculada à Identidade e Hash via HMAC-SHA256 (S3-A13-F1)
O mecanismo de sessões e refresh tokens foi endurecido para mitigar inserção e transplante de sessões por atacante com acesso exclusivo ao SQL de runtime (`rewit_app`):

1. **Estrutura do Token (56 bytes)**:
   - **16 bytes**: UUID canônico da conta proprietária (`User.id` em big-endian).
   - **8 bytes**: `expires_at` em epoch seconds (big-endian), exatamente o valor persistido em `auth_sessions.expires_at`.
   - **32 bytes**: entropia criptográfica gerada por `SecureRandom`.
   - **Codificação**: Base64URL sem padding (**75 caracteres** canônicos, `^[A-Za-z0-9_-]{75}$`). Para os clientes (mobile e admin) o token continua sendo uma string opaca.
   - Tokens com comprimento diferente, padding (`=`), espaços ou representação não canônica são rejeitados de forma fail-closed.

2. **Hash Persistido (HMAC-SHA256)**:
   - O banco armazena exclusivamente:
     ```text
     token_hash = HMAC-SHA256(REFRESH_TOKEN_SECRET, rawRefreshToken)
     ```
   - Persistido em 64 caracteres hexadecimais minúsculos, mantendo 100% de compatibilidade física com `auth_sessions.token_hash VARCHAR(64)`.
   - Um atacante com acesso a SQL não consegue calcular um `token_hash` válido para um refresh token arbitrário gerado por ele, pois `REFRESH_TOKEN_SECRET` é externo ao banco.
   - Hashes SHA-256 sem HMAC não são aceitos (a aplicação só busca por HMAC com as chaves configuradas).

3. **Verificação Estrita de Proprietário na Rotação**:
   - No fluxo `POST /api/v1/auth/refresh`:
     1. Valida-se o formato estrito do token apresentado.
     2. Extrai-se o `UUID` contido nos primeiros 16 bytes do token.
     3. Localiza-se a sessão com bloqueio pessimista (`SELECT FOR UPDATE`).
     4. Compara-se o UUID extraído com `session.getUserId()` e o `expiresAt` embutido com o `expires_at` persistido (igualdade de `UUID` e de epoch seconds; não é uma comparação de segredos, então tempo constante não é requisito).
     5. Havendo qualquer divergência, a requisição é rejeitada com `401 INVALID_REFRESH_TOKEN`.
   - Segunda camada contra transplante: mesmo que a linha fosse recriada apontando para outra conta, o token do cliente carrega o UUID da conta original e a verificação falha. A primeira camada é o runtime **não ter `DELETE`** em `auth_sessions` (§4.2).

4. **Rotação de Segredo (`REFRESH_TOKEN_SECRET`)**:
   - Suporte à transição de chave via `REFRESH_TOKEN_SECRET_PREVIOUS` (mesmo formato e requisitos do segredo ativo; independente dos demais segredos).
   - Sessões geradas durante a chave anterior são aceitas para validação. A chave anterior deve ser mantida por pelo menos o TTL máximo do refresh token (`JWT_REFRESH_TOKEN_TTL_SECONDS`, 30 dias por padrão); depois disso pode ser removida.
   - Na rotação da sessão, o novo refresh token emitido tem seu hash gravado obrigatoriamente com a chave ativa.

### 4.2 Guards de Integridade, Privilégios e Funções Restritas (Migration V44)
Para impedir que a credencial de runtime contorne o ciclo de vida das sessões e dos usuários, a `V44` combina triggers `BEFORE UPDATE`, retirada de privilégio e funções restritas:

1. **Integridade de `auth_sessions` (`trg_guard_auth_session_integrity`)**:
   - Impede `rewit_app` de alterar `id`, `user_id`, `token_hash`, `issued_at`, `expires_at` e `created_at`, e de desrevogar (`revoked_at`).
   - **Um trigger `BEFORE UPDATE` não cobre `DELETE` seguido de `INSERT`.** Por isso `rewit_app` **não possui `DELETE`** em `auth_sessions` (revogado na V44; sem esse privilégio uma sessão revogada ou válida não pode ser apagada nem recriada com os mesmos dados). `INSERT` continua permitido (cadastro, login, rotação), mas uma linha inserida só autentica se o `token_hash` for um HMAC com `REFRESH_TOKEN_SECRET`, que o runtime não conhece.
2. **Remoção legítima por funções `SECURITY DEFINER`** (owner = role de migração, `search_path = pg_catalog, public, pg_temp`, `EXECUTE` revogado de `PUBLIC` e concedido só a `rewit_app`, sem SQL dinâmico):
   - `rewit_purge_expired_auth_sessions(p_now, p_limit)`: cleanup em lotes (`FOR UPDATE SKIP LOCKED`, limite 1–10000) de sessões com `expires_at < LEAST(p_now, clock_timestamp())` e sem sucessora de rotação. O corte nunca passa do relógio do PostgreSQL, então uma data futura não apaga sessões ainda válidas.
   - `rewit_purge_deleted_user_auth_sessions(p_user_id)`: purge LGPD; verifica no banco que a conta está `DELETED` com `deleted_at` preenchido e, caso contrário, falha com `insufficient_privilege`.
3. **Expiração vinculada ao token**: o token embute `expires_at` (§4.1). Depois do purge legítimo de uma sessão expirada, uma linha reconstruída com o mesmo hash e `expires_at` ampliado diverge do valor embutido no token e é rejeitada; um token expirado nunca renova.
4. **Identidade e Provedor em `users` (`trg_guard_user_role_assignment`)**:
   - Impede `rewit_app` de alterar `id`, `created_at` e `auth_provider`, de definir ou alterar `provider_user_id` (única exceção: `NULL` em conta já `DELETED`, para o purge LGPD) e de tirar uma conta do estado `DELETED`.
5. **`rewit_assert_runtime_privileges()`** verifica: ausência de `DELETE` direto em `auth_sessions`, existência das duas funções, owner correto, `SECURITY DEFINER`, `search_path` fixo, `EXECUTE` só para `rewit_app` (nenhum para `PUBLIC`), ausência de outras funções `SECURITY DEFINER` e de memberships indevidas, além de todas as assertions da V37.

### 4.3 Tabela `auth_sessions` e Modelo Físico
```sql
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    replaced_by_session_id UUID NULL REFERENCES auth_sessions(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ NULL,
    user_agent TEXT NULL,
    ip_address INET NULL,
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash)
);
```
- **Índices estratégicos**: `user_id`, `expires_at`, `revoked_at` e índice único em `token_hash`.
- **TTL do Refresh Token**: 30 dias (2.592.000 segundos) por padrão (`JWT_REFRESH_TOKEN_TTL_SECONDS`).

### 4.4 Rotação Atômica e Proteção Contra Concorrência (Row-Level Locking)
Para fechar completamente a janela de corrida (*race condition*) na rotação do refresh token, o fluxo utiliza **bloqueio pessimista de linha no PostgreSQL** (`SELECT FOR UPDATE` / `LockModeType.PESSIMISTIC_WRITE`):
1. **Localização e Bloqueio Exclusivo**: O registro da sessão é localizado com `SELECT ... WHERE token_hash = ? FOR UPDATE`.
2. **Serialização no Banco**: Se duas requisições simultâneas apresentarem o mesmo token `X`, a primeira requisição adquire o lock da linha e a segunda é bloqueada no nível do PostgreSQL.
3. **Validação**: Valida-se expiração, revogação prévia e integridade do usuário proprietário.
4. **Criação da Nova Sessão**: É gerada a nova sessão derivada com novo token e persistida no banco.
5. **Revogação da Sessão Antiga**: `currentSession.rotate(newSessionId)` marca `revoked_at` com o timestamp atual e vincula `replaced_by_session_id` de forma imutável (não sobrescrevível).
6. **Commit da Transação Vencedora**: Libera o lock no banco.
7. **Desbloqueio da Segunda Requisição**: Sob isolamento `READ COMMITTED`, o PostgreSQL reavalia a linha recém-comitada. A segunda requisição recebe a sessão com `revoked_at` preenchido e falha imediatamente com `REFRESH_TOKEN_REVOKED` (401 Unauthorized), garantindo que apenas UMA nova sessão seja gerada.

### 4.4 Detecção de Reutilização de Token (Token Reuse Detection)
Se um refresh token já revogado for apresentado:
- Caso o token tenha sido substituído há menos de 10 segundos (janela transitória de concorrência em clientes legítimos), a requisição concorrente duplicada é apenas rejeitada sem invalidar a nova sessão recém-emitida.
- Caso o token seja reutilizado fora da janela transitória (tentativa de replay de token antigo, inclusive de token encerrado por logout), a aplicação assume potencial roubo de credencial:
  - Invalida compulsoriamente **todas as sessões ativas do usuário** (`revokeAllByUserId`).
  - Rejeita com `REFRESH_TOKEN_REVOKED` (`401 Unauthorized`).
- **Persistência da mitigação (Step 29.1)**: a revogação em massa e o erro acontecem na mesma transação de `AuthService.refresh`, que mantém o lock `FOR UPDATE` da sessão apresentada. O erro do reúso é a exceção específica `RefreshTokenReuseDetectedException` (subclasse de `BusinessException`, com a mesma resposta HTTP), e `refresh` declara `noRollbackFor` **somente** para ela: a revogação é commitada antes de o 401 chegar ao cliente. Qualquer outro erro de `refresh` continua fazendo rollback. Até o Step 29.1, o erro era um `BusinessException` comum e o rollback desfazia a revogação em massa: o token reutilizado era rejeitado, mas as demais sessões permaneciam ativas.

---

### 4.5 Ciclo de Vida e Cleanup de `auth_sessions` (Step 29.3, ADR-011)

Um job agendado (`AuthSessionCleanupScheduler` → `CleanupAuthSessionsUseCase`) executa duas fases. Cada lote roda em transação própria, e as linhas são selecionadas com `FOR UPDATE SKIP LOCKED`.

**1. Metadados técnicos.** `ip_address` e `user_agent` são gravados no login, no registro e no refresh, e não são lidos por nenhum código. O IP é o `remoteAddr` da conexão: headers de proxy enviados pelo cliente (`X-Forwarded-For`) não são lidos; atrás do Cloudflare Tunnel (perfil `external-test`), o RemoteIpValve do Tomcat substitui o `remoteAddr` por `CF-Connecting-IP` só quando o peer é o `cloudflared` local (`127.0.0.1`). O job os limpa (`NULL`) em sessões que deixaram de estar ativas: `revoked_at IS NOT NULL OR expires_at < now()`. Nenhum campo de segurança é alterado. Sessões ativas mantêm os metadados; a retenção nesse período é uma decisão de produto/jurídico pendente (ADR-011).

**2. Purge.** Uma sessão só é removida quando `expires_at < now() AND replaced_by_session_id IS NULL`:

| Sessão | Resultado |
| :--- | :--- |
| Ativa (não expirada, não revogada) | nunca removida |
| Revogada ainda dentro do prazo | mantida até `expires_at` |
| Expirada sem sucessora (inclusive revogada por logout, troca de senha ou reúso) | removida |
| Expirada com sucessora (antecessora de rotação) | mantida enquanto a sucessora existir |

Uma cadeia de rotação A→B→C→D é preservada inteira enquanto a cauda D estiver viva, e a detecção de reúso de A, B ou C continua funcionando (§4.4). Quando D expira e é removida, o `ON DELETE SET NULL` da FK limpa a referência de C, que fica elegível no lote seguinte. A cadeia é desmontada um elo por lote, sem busca recursiva. O índice parcial `idx_auth_sessions_replaced_by_session_id` (V16) atende a busca que o `SET NULL` faz a cada sessão removida.

**Concorrência.** Instâncias concorrentes processam lotes disjuntos por `SKIP LOCKED`, sem lock global. Uma revogação em massa (`revokeAllByUserId`) que alcança linhas travadas por um lote aguarda o commit desse lote e conclui normalmente. Uma falha num lote (inclusive deadlock detectado pelo PostgreSQL) desfaz apenas aquele lote; o próximo ciclo tenta de novo. Fica aceita uma corrida rara do logout, que lê sem lock: se o purge remover a sessão (já expirada) entre a leitura e o `save`, a linha é recriada já revogada e sai no ciclo seguinte.

**Configuração** (`rewit.auth-session-cleanup`): `enabled` (padrão `true`, como o purge do Outbox; desligado nos testes), `interval-ms`, `initial-delay-ms`, `batch-size`, `max-batches-per-run`. Não há propriedade de retenção: a elegibilidade é estrutural. Não há dry-run nem endpoint. Métricas: `rewit.auth_session_cleanup.metadata_cleared`, `.sessions_purged`, `.failures` e o timer `.duration`. Os logs são agregados, sem token, hash, IP, user agent ou ids de sessão.

### 4.6 Rate Limiting e Tempo Uniforme no Login (ADR-013)

Login, refresh e cadastro passam pela porta `RateLimiter` (janela deslizante distribuída no Redis, chaves HMAC, sem IP). Quando o limite é excedido, a resposta é `429 Too Many Requests` com código `RATE_LIMIT_EXCEEDED`, preservando o padrão RFC 7807 (`application/problem+json`) dos demais erros.

#### 4.6.1 Operações Limitadas e Cabeçalho `Retry-After`

As seguintes operações de autenticação podem retornar `429 Too Many Requests`:
- `POST /api/v1/auth/login`: limite de tentativas por identidade (e-mail normalizado).
- `POST /api/v1/auth/refresh`: limite de rotações de sessão por usuário.
- `POST /api/v1/auth/register`: limite global de cadastros na aplicação.

Toda resposta `429 Too Many Requests` originada por excesso de taxa inclui obrigatoriamente o cabeçalho HTTP padrão:
```http
Retry-After: <segundos>
```
- **Significado**: inteiro positivo representando a estimativa em segundos até que uma nova tentativa possa ser aceita pelo servidor. Corresponde ao tempo restante para a tentativa mais antiga sair da janela deslizante, com valor mínimo garantido de 1 segundo.
- **Requisições normais**: requisições bem-sucedidas ou erros com outros códigos HTTP (2xx, 4xx, 5xx) não recebem o cabeçalho `Retry-After`.

| Ação | Endpoint | Sujeito | Padrão | O que conta |
|---|---|---|---|---|
| Login | `POST /api/v1/auth/login` | e-mail normalizado | 10 em 15 min | Tentativas sem sucesso. A tentativa ocupa a janela antes da verificação e é devolvida no sucesso. E-mail inexistente conta igual. |
| Refresh | `POST /api/v1/auth/refresh` | usuário da sessão | 30 em 5 min | Rotações. Checado depois da detecção de reúso e da expiração; o 429 faz rollback sem consumir o token. |
| Cadastro | `POST /api/v1/auth/register` | global | 30 em 1 min | Toda tentativa, inclusive as inválidas. Também a criação de conta pelo login social. |
| Login social | `POST /api/v1/auth/social/nonce` | global | 60 em 1 min | Nonces emitidos; cada tentativa de login social consome um. |

#### 4.6.2 Distribuição e Comportamento Degradado

- **Coordenação Distribuída**: Em operação normal, a contagem e as janelas deslizantes são coordenadas centralmente entre múltiplas instâncias via Redis, garantindo aplicação consistente dos limites em todo o cluster. As chaves usam HMAC-SHA256, sem armazenar ou vazar e-mails, IDs ou dados sensíveis na infraestrutura de cache.
- **Comportamento Degradado**: Caso o armazenamento distribuído fique temporariamente indisponível, a aplicação entra em modo de contingência local por instância (`LOCAL_FALLBACK`). Nesse modo, cada nó mantém sua própria janela deslizante em memória com os mesmos limites configurados e calcula o cabeçalho `Retry-After` de forma coerente com base nas tentativas locais. A degradação ocorre de forma transparente ao cliente, sem expor detalhes internos da infraestrutura nem interromper a proteção da API.
- **Tempo uniforme**: sem conta local elegível (inexistente, desativada ou federada), o login executa `PasswordHasher.simulateVerification`, uma verificação Argon2 contra um hash descartável com os mesmos parâmetros. Assim o custo é o mesmo de uma senha errada. Mensagem, status e código continuam `INVALID_CREDENTIALS` (`401`).
- **Limite e enumeração**: o 429 depende só da contagem por e-mail, nunca da existência da conta.
- **Bloqueio por terceiros**: sem IP, quem conhece um e-mail pode esgotar o limite de login dessa conta por até uma janela. Esse é o custo aceito enquanto a decisão sobre IP estiver pendente (ADR-011).

**Configuração** (`rewit.rate-limit`): `enabled`, `key-secret` (`RATE_LIMIT_KEY_SECRET`, obrigatório), `backend.failure-mode` (`LOCAL_FALLBACK`|`DENY`|`ALLOW`), `backend.retry-interval`, `backend.local-fallback-max-keys`, `auth.{login,refresh,registration}.{enabled,limit,window}` e `content.{report-creation,discussion-creation,media-upload}.{enabled,limit,window}`.

### 4.7 Estado da Conta nas Mutações

O access token é validado só pelas claims, sem consulta ao banco, e pode ter sido emitido antes de a conta ficar inativa ou ser excluída. Por isso as mutações de autoria leem o estado atual da conta (`AccountStatusPolicy`, porta `UserRepository`) no início da própria transação, depois da validação dos argumentos e antes de rate limiting, locks e escritas.

- **Regra**: a conta opera se `account_status = 'ACTIVE'` (`User.isOperational()`; V21). `is_active` e `deleted_at` são derivados do estado e o schema impede divergência, então a regra anterior (`is_active = TRUE` e `deleted_at IS NULL`) continua equivalente. Caso contrário, `401 ACCOUNT_DISABLED` ("Conta de usuário inativa ou inexistente"), o mesmo contrato de refresh, `/auth/me` e `/me`. Inativa, excluída e inexistente são indistinguíveis.
- **Mutações protegidas**: editar e excluir review, marcar e desmarcar helpful, criar e excluir comentário, denunciar, enviar e excluir mídia, seguir e deixar de seguir, moderar review (estado do moderador), criar e adotar place, criar product, adicionar identificador e associar presença de product, e marcar notificação como lida (uma ou todas). A criação de review já checava o autor e mantém o seu contrato (`403 USER_INACTIVE`, `404 USER_NOT_FOUND`); perfil e senha já usavam `ACCOUNT_DISABLED`.
- **Fora da regra**: leituras (feed, listagens, perfis) não consultam o estado da conta; o logout continua permitido; notificações e o conteúdo histórico da conta não mudam.
- **Concorrência**: a leitura do estado não usa lock. Uma desativação que confirme depois dela é ordenada após a mutação; inserts filhos pegam só `FOR KEY SHARE` na linha de `users`, compatível com o `UPDATE` da desativação, então nenhuma das duas espera a outra.
- **Schema**: `chk_users_active_not_deleted` (V17) impede `is_active = TRUE` com `deleted_at` preenchido; `chk_users_status_consistency` (V21) amarra os dois a `account_status`.

### 4.8 Ciclo de Vida da Conta (C2)

- **Estados**: `ACTIVE`; `DEACTIVATED` (pelo próprio usuário, que reativa em `POST /api/v1/auth/reactivate`, ou em `POST /api/v1/auth/social/{provider}/reactivate` se a conta for federada, §4.9); `SUSPENDED` (ação administrativa, só revertida pela administração); `DELETED` (exclusão lógica definitiva, preservada para integridade e histórico). Transições no domínio (`User.deactivate/reactivate/suspend/reinstate/softDelete`); proibidas respondem `409 ACCOUNT_STATUS_TRANSITION_DENIED` apenas nos fluxos administrativos.
- **Sessões**: toda transição que muda o estado revoga as sessões da conta na mesma transação, inclusive a volta para `ACTIVE`. Um refresh concorrente com a desativação pode criar uma sessão que o `UPDATE` de revogação não enxerga (linha nova fora do snapshot do comando); ela não serve enquanto a conta não opera (o refresh lê o estado) e é revogada na reativação ou na reversão, antes de voltar a valer.
- **Access token**: stateless; não é invalidado. Até expirar, leituras continuam possíveis e toda mutação protegida responde `401 ACCOUNT_DISABLED` (§4.7).
- **Concorrência**: toda escrita no estado parte de `UserRepository.findByIdForUpdate`, que trava a linha (`FOR NO KEY UPDATE`) e recarrega o estado com `refresh`. Uma consulta com lock não bastaria: se a conta já estiver carregada na transação (a reativação busca por e-mail antes), o Hibernate trava a linha mas devolve a instância com o estado antigo. A troca de senha usa a mesma leitura, porque grava a linha inteira.
- **Leituras públicas de conta `DELETED`**: a identidade some de toda projeção pública (autor de review e feeds, autor de discussion, listas e contadores de follow, `actorId` de notificações) via `UserRepository.findDeletedUserIds`, uma consulta por página; o conteúdo histórico, as notas, o helpful e os agregados continuam, e o `user_id` permanece nas tabelas e nas visões administrativas. Perfil, follows e reputação da conta respondem como um identificador inexistente. `DEACTIVATED` e `SUSPENDED` não são anonimizadas.
- **Purge de conta `DELETED` (C2.3)**: `PurgeDeletedAccountUseCase`, transacional e idempotente, disparado por um job diário (`AccountPurgeJobScheduler`, uma transação por conta; ver `security/privacy-and-lgpd.md` §6.1). A exclusão lógica é imediata; o purge só é elegível 30 dias depois (`deleted_at <= agora - 30 dias`, `User.PURGE_GRACE_PERIOD`); antes disso responde `NOT_ELIGIBLE` sem alterar nada. O e-mail reservado é um HMAC com `ACCOUNT_EMAIL_RESERVATION_SECRET`; sem o segredo o purge falha antes de qualquer escrita. A linha de `users` permanece (avaliações, comentários, denúncias e a auditoria com `ON DELETE RESTRICT` a referenciam): o e-mail vira o valor reservado, a credencial e o id do provedor externo saem, o perfil recebe handle reservado (`excluido-<id>`) e nome neutro. Sessões, follows, itens salvos, interesses, atividades, notificações próprias e o snapshot de reputação são removidos; coordenadas informadas nas avaliações, a referência em notificações de terceiros e o autor de presenças de produto são anulados. A conta é travada (`findByIdForUpdate`) e precisa estar `DELETED`, de onde nenhuma transição sai; purges concorrentes são serializados e o segundo é no-op.
- **Sem vazar o estado**: fluxos do próprio usuário respondem `401 ACCOUNT_DISABLED` (sessão) ou `401 INVALID_CREDENTIALS` (credenciais), iguais para conta inexistente, desativada, suspensa ou excluída.

### 4.9 Login Social (Google e Apple)

O app obtém o ID token OpenID Connect pelo SDK oficial do provedor e o backend o valida antes de emitir a sessão do Rewit. Contrato HTTP em `docs/api/authentication.md` §7 a §9.

**Nonce de uso único.** `POST /auth/social/nonce` gera 256 bits aleatórios e guarda no Redis apenas o SHA-256 (`rewit:social-login:nonce:<hash>`, TTL de 10 min). O app entrega ao provedor o hash e reenvia ao Rewit o nonce em claro. O backend consome a chave com `GETDEL` (atômico entre instâncias) **antes** de validar o token e exige `nonce == SHA-256(nonce enviado)` no token. Assim:
- um token interceptado no redirecionamento (fluxo web da Apple no Android) carrega só o hash, e o nonce em claro nunca sai do app e do Rewit;
- um token de outra pessoa injetado no app não casa com o nonce da tentativa (sem login CSRF);
- cada nonce serve a uma única tentativa, válida ou não.

Sem Redis, o login social falha fechado (`503 SOCIAL_LOGIN_UNAVAILABLE`), ao contrário do rate limiting, que tem fallback local: um fallback por instância permitiria reúso do nonce entre instâncias. A emissão de nonces conta no limite global `SOCIAL_LOGIN` (padrão 60/min, `RATE_LIMIT_SOCIAL_LOGIN_*`), que também limita as validações de token.

**Validação do token** (`OidcIdTokenVerifier`, Nimbus JOSE, já presente pelo resource server): RS256 com o JWKS do provedor (cache, renovação antecipada, nova busca para `kid` desconhecido e intervalo mínimo entre buscas; timeout de 5 s), emissor, audiência (client IDs configurados), `exp`/`iat`/`nbf` com 60 s de tolerância, `sub` e `nonce`. JWKS inacessível responde `503 SOCIAL_PROVIDER_UNAVAILABLE` sem aceitar o token. Nenhum segredo de provedor é necessário: os client IDs são públicos. O provedor sem client ID fica desligado.

**Conta.** A identidade é o par `auth_provider` + `provider_user_id` (`sub`), único em `uq_users_provider_user_id` (V4) para qualquer estado da conta; a busca também considera contas `DELETED` não purgadas, que respondem `401 ACCOUNT_DISABLED` como desativadas e suspensas. Uma identidade nova cria conta federada sem senha (o login local continua recusando contas federadas, §6) quando o provedor atesta o e-mail. E-mail já usado por outra conta (local, de outro provedor ou reservado após o purge) responde `409 SOCIAL_ACCOUNT_EMAIL_CONFLICT`: **não há vinculação por e-mail coincidente**. O e-mail de uma conta vinculada não é atualizado a partir do token. O perfil novo tem handle aleatório (nunca derivado do e-mail, que é dado pessoal) e o nome da claim `name` (Google) ou `Usuário Rewit`.

**Transações e concorrência.** `SocialAuthService` não abre transação: o consumo do nonce não é desfeito por erro posterior e a consulta ao JWKS não segura conexão do banco. A criação ou recuperação da conta e a sessão ficam em `AuthService.authenticateExternalIdentity`, transação única como no cadastro local. Duas primeiras entradas concorrentes da mesma identidade (cada uma com o próprio nonce) colidem em `uq_users_provider_user_id` ou `uq_users_email_lower`; a perdedora sofre rollback completo e é repetida uma vez em nova transação, que encontra a conta criada. O mesmo cobre a colisão rara do handle gerado. A sessão emitida é a mesma do login local (refresh com rotação, detecção de reúso, logout).

**Apple no Android.** O fluxo web publica o resultado em `POST /auth/apple/callback`, que repassa somente `code`, `id_token`, `state`, `user` e `error`, codificados, ao intent `signinwithapple://callback` do pacote configurado (`APPLE_SIGNIN_ANDROID_PACKAGE`). O endpoint não valida nem grava nada; a validação acontece em `POST /auth/social/apple`.

**Conta federada desativada.** O login social nunca reativa: conta `DEACTIVATED` responde `401 ACCOUNT_DISABLED`, como suspensa e excluída. A reativação é uma ação própria, `POST /auth/social/{provider}/reactivate`, que o app só chama depois da confirmação do usuário e que exige uma autenticação nova no provedor, com nonce novo e a mesma validação do login. `AuthService.reactivateExternalIdentity` espelha a reativação local: só a conta vinculada ao provedor + `sub` e só a partir de `DEACTIVATED` (suspensa, excluída e identidade sem conta respondem `401 ACCOUNT_DISABLED`, sem revelar o estado; nada é criado), lock com releitura do estado (`findByIdForUpdate`, uma suspensão concorrente confirmada antes vence) e revogação das sessões anteriores na mesma transação. Conta já `ACTIVE` apenas autentica.

**App: nonce por tentativa no Google.** Em `google_sign_in` 7.2.0 o nonce só entra pela inicialização. A API de alto nível (`GoogleSignIn.instance.initialize`) deve ser chamada uma única vez, então o app usa `GoogleSignInPlatform`, cujo contrato só exige `init` antes das demais chamadas. Nas implementações auditadas (`google_sign_in_android` 7.2.18, `google_sign_in_ios` 6.3.8), `init` apenas grava configuração e nonce, lidos a cada `authenticate`. Cada tentativa executa `init` (mesma configuração, nonce novo), `authenticate` e `signOut` como seção crítica; uma tentativa simultânea é recusada sem tocar no SDK. As versões estão fixadas e um teste confere o lockfile, para que uma atualização exija nova auditoria.

**Limites atuais.** Vincular uma identidade social a uma conta existente (fluxo autenticado pela conta original) e o `authorization_code`/revogação de token da Apple (exigido pela Apple na exclusão de conta) não estão implementados.

## 5. Endpoints Implementados

| Método | Endpoint | Proteção | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Pública | Cria `User` e `Profile` em transação única. Emite access token + refresh token. |
| `POST` | `/api/v1/auth/login` | Pública | Valida credenciais locais, cria sessão e emite tokens. Erros de credenciais são uniformes. |
| `POST` | `/api/v1/auth/social/nonce` | Pública | Emite nonce de uso único para o login social (§4.9). |
| `POST` | `/api/v1/auth/social/google`, `/api/v1/auth/social/apple` | Pública | Valida o ID token do provedor, cria ou recupera a conta federada e emite tokens. |
| `POST` | `/api/v1/auth/social/google/reactivate`, `/api/v1/auth/social/apple/reactivate` | Pública | Reativa conta federada desativada com autenticação nova no provedor (ação explícita). |
| `POST` | `/api/v1/auth/apple/callback` | Pública | Retorno do fluxo web da Apple (Android), repassado ao app. |
| `POST` | `/api/v1/auth/refresh` | Pública | Executa rotação de refresh token e emite novo par de tokens. |
| `POST` | `/api/v1/auth/logout` | Autenticada (Bearer) | Revoga a sessão do refresh token informado. |
| `GET` | `/api/v1/auth/me` | Autenticada (Bearer) | Retorna dados públicos e seguros da identidade autenticada (`sub` do JWT). |

---

## 6. Tratamento de Erros e Segurança de Resposta

- **Mensagens Genéricas de Login**: A API não revela se o e-mail não existe, se o usuário é federado ou se a senha está errada. Todos retornam `INVALID_CREDENTIALS` (`401 Unauthorized`).
- **Sem Exposição de Dados Sensíveis**: `password_hash`, `token_hash`, tokens completos ou segredos nunca são retornados em DTOs de resposta nem em logs.
- **Usuários Desativados (Soft-deleted)**: Usuários com `deleted_at IS NOT NULL` são rejeitados tanto no login quanto no refresh e na consulta ao `/me` (`ACCOUNT_DISABLED`, `401 Unauthorized`); o login responde `INVALID_CREDENTIALS`. Mutações de autoria com token emitido antes da desativação também recebem `ACCOUNT_DISABLED` (§4.7).
- **Tratamento de Exceções**: Todas as falhas de autenticação e validação são retornadas em conformidade com o RFC 7807 (`application/problem+json`).
- **Tratamento no Cliente Mobile (`apps/mobile`)**:
  - `RewitHttpClient` expõe o callback tipado opcional `onSessionExpiredWithDetails(SessionExpirationDetails)` (transportando `errorCode` e `statusCode`), mantendo `onSessionExpired` como fallback exclusivo quando o tipado não está configurado; erros locais de formulário (`treatInvalidCredentialsAsBusinessError` com `INVALID_CREDENTIALS`) e chamadas `requiresAuth: false` não acionam desautenticação global.
  - Em `401 ACCOUNT_DISABLED` durante uma sessão autenticada, `TokenRefreshingClient` não tenta renovar o token e `AuthNotifier.handleSessionExpired(details)` executa desautenticação global imediata sem chamar `SessionRefresher.refresh()`: incrementa a geração da sessão em `SessionTokenCoordinator.invalidateSession()`, invalida eventual renovação concorrente no `SessionRefresher`, limpa os tokens locais via fila FIFO do coordenador apenas para aquela geração, transiciona para `Unauthenticated(errorCode: 'ACCOUNT_DISABLED')` com mensagem neutra local (`socialProvider == null`, portanto `canOfferSocialReactivation == false`) e libera observadores de `sessionCheckSince()` com `SessionRefreshOutcome.rejected`, permitindo ao `RewitApp` desempilhar até a `LoginScreen`.
  - **Integridade geracional e serialização de `TokenStorage` (`SessionTokenCoordinator`)**: todas as gravações e limpezas de tokens em `AuthRepositoryImpl` (`login`/`loginWithAttempt`, `register`/`registerWithAttempt`, `signInWithSocial`/`signInWithSocialAttempt`, `reactivate`/`reactivateWithAttempt`, `reactivateWithSocial`/`reactivateWithSocialAttempt`, `_storeSession`, `refreshTokens`, `deactivateAccount`, `changePassword`, `logout`), `SessionRefresher` (usando `AuthRepositoryImpl.requestTokenRefresh()` sem gravação prévia antes de confirmar a geração via `saveRefreshedTokens`) e `AuthNotifier` passam por `SessionTokenCoordinator`, que executa mutações em fila FIFO e rejeita gravações ou limpezas tardias cuja geração já tenha sido invalidada ou substituída por uma nova sessão (inclusive quando uma escrita antiga já estava suspensa em `await`).
  - **Propriedade das tentativas de autenticação (prevalece a intenção de autenticação mais recente)**: `SessionTokenCoordinator` separa `_sessionGeneration` (propriedade das credenciais já persistidas e de refreshes) de `_authenticationAttemptSequence` / `_activeAttemptId` (identidade exclusiva da tentativa de login, cadastro, fluxo social ou reativação). `AuthNotifier` e `AttemptScopedAuthRepository` reservam um único `attemptId` via `beginAuthenticationAttempt()` **antes** de iniciar requisições de rede (ou `requestSocialNonce` / `obtainIdToken` no fluxo social) e propagam esse mesmo identificador até `saveNewSessionTokens(..., expectedAttemptId: attemptId)`:
    - Uma tentativa mais nova substitui a propriedade sem alterar silenciosamente `_sessionGeneration` da sessão ativa até o commit; se a nova tentativa falhar (`abandonAuthenticationAttempt`), a tentativa antiga não ressuscita.
    - `logout()`, `deactivateAccount()`, `changePassword()` e `ACCOUNT_DISABLED` chamam `invalidateSession()`, invalidando tanto refreshes quanto tentativas de autenticação pendentes; em `logout()`, `clearTokensForGeneration(logoutGeneration)` evita que um logout remoto lento apague tokens de um login posterior.
    - Respostas tardias de tentativas obsoletas são recusadas na fila FIFO com `StaleAuthenticationAttemptException` sem publicar tokens nem alterar `AuthNotifier.state` (inclusive se chegarem antes ou depois da tentativa vencedora ou se uma falha tardia atingir os blocos `catch` de `AuthNotifier`).
    - **Compensação durante gravação já iniciada**: `SecureTokenStorage.saveTokens()` executa gravações assíncronas paralelas para access e refresh token e não é atômico. Caso outra tentativa, `logout` ou `ACCOUNT_DISABLED` invalide `_activeAttemptId` ou `_sessionGeneration` enquanto `saveTokens()` já estiver suspenso em `await`, o mesmo slot da fila FIFO detecta a perda de propriedade imediatamente após o retorno de `saveTokens()`, executa compensação com `clearTokens()` antes que qualquer operação posterior toque o armazenamento e lança `StaleAuthenticationAttemptException`.
    - **Política 2 — Preservação da sessão anterior durante tentativas concorrentes ou falhas**: enquanto uma nova tentativa explícita (`login`, `register`, fluxo social ou reativação) estiver pendente a partir de uma sessão `Authenticated(X)` válida, a sessão anterior permanece preservada em um snapshot único (`_PreviousSessionSnapshot` com `session` e `persistedGeneration` do coordenador), capturado na primeira transição de `Authenticated` para `Authenticating` e mantido intacto através de tentativas subsequentes que se sobreponham antes da resolução:
      - **Falha da tentativa proprietária**: se o snapshot ainda existir e `persistedGeneration` ainda corresponder a `SessionTokenCoordinator.currentGeneration` (cobrindo inclusive refresh legítimo de `X` concluído durante `Y`, já que `saveRefreshedTokens` mantém a mesma geração), o proprietário final restaura `Authenticated(X)`, descarta o snapshot, preserva os tokens de `X` no `TokenStorage` e registra o erro sanificado em `AuthNotifier.lastAttemptError` sem transicionar falsamente para `Unauthenticated`; caso contrário (sem sessão prévia ou sessão invalidada/substituída), transiciona normalmente para `Unauthenticated`.
      - **Tentativa supersedida**: qualquer tentativa antiga supersedida não altera `AuthNotifier.state`, não limpa tokens, não publica erros e não descarta o snapshot ainda necessário à tentativa proprietária vigente.
      - **Descarte e invalidação explícita**: sucesso de uma nova sessão confirmada, `logout()`, `ACCOUNT_DISABLED`, expiração rejeitada (`SESSION_EXPIRED`), `deactivateAccount()` e `changePassword()` descartam definitivamente o snapshot de `X`, impedindo qualquer ressurreição tardia.
    - **Limites atuais**: operações diretas de `AuthNotifier.reactivate()` e `deactivateAccount()` mantêm proteção de double-submit quando `isLoading == true`; repositórios falsos em testes unitários que implementam apenas `AuthRepository` sem `AttemptScopedAuthRepository` contam com a guarda de intenção/época em `AuthNotifier`.
  - **Precedência de `ACCOUNT_DISABLED` na restauração e gerenciamento de sessão**: `AuthNotifier.checkAuthStatus()` aguarda eventual `_inFlightDisabledExpiry` pendente e preserva `Unauthenticated(errorCode: 'ACCOUNT_DISABLED')` antes de alterar época ou consultar `getMe()` (assim como quando `_activeUiAttemptId != null`), além de bloquear restaurações ou limpezas tardias em todos os desfechos (`getMe`, `ApiException`, `NetworkException`, exceções genéricas e `refreshTokens`); `deactivateAccount()`, `changePassword()` e `logout()` aplicam guarda de época/geração na invalidação de refresh concorrente e na preservação do estado sob `ACCOUNT_DISABLED`.
- **Rate Limiting (`429 Too Many Requests`)**: Requisições de autenticação (`login`, `refresh`, `register`) que excederem as taxas operacionais retornam código `RATE_LIMIT_EXCEEDED` acompanhadas obrigatoriamente do cabeçalho `Retry-After: <segundos>`, informando a duração estimada até a permissão de nova tentativa. Demais respostas ou códigos de status não recebem o cabeçalho.

---

## 7. Decisões Arquiteturais e Limites Deste Step

1. **Assinatura HMAC-SHA256 (Simétrica)**:
   - Adotada para manter a infraestrutura simples e sem dependência de pares de chaves assimétricas em arquivo local neste estágio do projeto.
   - Pode ser migrada transparentemente para RSA (RS256) ou Curvas Elípticas (ES256) em etapas posteriores com chaves KMS/Secret Manager.
2. **Ausência de Blacklist Global de Access Tokens**:
   - Para manter a arquitetura stateless, os access tokens possuem vida útil curta (15 minutos). O logout revoga imediatamente o refresh token no banco de dados.
3. **Limites do Escopo**:
   - Provedores externos (Google, Apple) ficaram fora do Step 4; o login social foi implementado depois (§4.9).
   - Recursos como verificação de e-mail, recuperação de senha e MFA serão incorporados nas etapas subsequentes. O rate limiting distribuído está na seção 4.6.

---

## 8. Análise de Riscos Residuais (S3-A13-F1)

Modelo de ameaça: posse exclusiva da credencial SQL de runtime (`rewit_app`). O F1 só é considerado mitigado depois de os testes de ataque em `RuntimeSqlCredentialCompromiseIntegrationTest` e da regressão completa passarem (ver ADR-017 e o roadmap §13.8).

### 8.1 Vetores mitigados
1. **Senha fabricada ou copiada**: sem `PASSWORD_PEPPER_SECRET` não se calcula um `pw2` válido; o HMAC inclui o UUID da conta, então copiar o hash de A para B falha. Hashes crus ou `legacy-sealed` são rejeitados.
2. **Sessão fabricada**: o `token_hash` é HMAC com segredo externo; um SHA-256 puro não autentica (teste com linha administrativa válida em todos os campos).
3. **Transplante de sessão**: `UPDATE` de `user_id` bloqueado por trigger; `DELETE` negado ao runtime; reconstrução (feita só por conexão privilegiada no teste) cai no vínculo UUID do token.
4. **Ressurreição de sessão revogada** (logout, troca de senha, rotação): não pode ser apagada, desrevogada nem ter `expires_at` alterado; token antigo continua rejeitado.
5. **Expiração artificial após purge legítimo**: o `expires_at` embutido no token (HMAC-autenticado indiretamente pelo hash) precisa coincidir com o persistido.
6. **Sequestro de identidade federada**: `auth_provider` e `provider_user_id` imutáveis para o runtime (exceto anulação em conta `DELETED`).

### 8.2 Riscos residuais (honestos)
- **Quem já possui um refresh token válido** pode renová-lo pela API normalmente; isso não é uma escalada.
- **Purge de sessões já expiradas**: a função restrita pode apagar sessões expiradas sem sucessora, o que é o comportamento do cleanup (ADR-011). Apagar uma sessão expirada não a torna reutilizável, pelo vínculo de expiração no token. Consequência: após o purge, o token expirado recebe `INVALID_REFRESH_TOKEN` em vez de `REFRESH_TOKEN_EXPIRED`.
- **Replay de estados antigos de uma linha**: como `rewit_app` não apaga nem altera campos de segurança, não há como restaurar um estado anterior da linha; por isso não se adicionou coluna de MAC de sessão (custo e complexidade sem benefício adicional para este modelo de ameaça).
- **Negação de serviço**: com `INSERT` e `UPDATE` de `revoked_at` ainda permitidos, o runtime comprometido pode revogar sessões de terceiros. Fora do escopo do F1.
- **Segredos externos comprometidos**: quem obtiver `PASSWORD_PEPPER_SECRET` ou `REFRESH_TOKEN_SECRET` junto com SQL volta ao cenário anterior; os segredos devem ficar em cofre separado do banco e rotacionados (§2.4, §4.1).
- **F2 (moderação) permanece aberto** e não é tratado aqui.
