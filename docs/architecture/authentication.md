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

## 2. Password Hashing (Argon2id)

### 2.1 Algoritmo e Justificativa
Utilizamos o algoritmo **Argon2id** (versão 1.3), o vencedor da Password Hashing Competition (PHC) e padrão moderno recomendado pela OWASP. O Argon2id fornece proteção simultânea contra ataques de canal lateral (side-channel attacks) e ataques acelerados por hardware dedicado (GPUs e ASICs).

A implementação utilizada é a oficial do Spring Security:
`org.springframework.security.crypto.argon2.Argon2PasswordEncoder`, suportada pela biblioteca `org.bouncycastle:bcprov-jdk18on`.

### 2.2 Parâmetros Finais Configurados
Revisados no Step 4.1 para atender rigorosamente à recomendação da OWASP sem comprometer o tempo de autenticação interativa:
- **Salt Length**: 16 bytes (128 bits criptograficamente seguros via `SecureRandom`).
- **Hash Length**: 32 bytes (256 bits).
- **Parallelism (Threads)**: 1 thread (`p=1`).
- **Memory Cost**: 19.456 KiB (19 MiB de memória por derivação, `m=19456`).
- **Iterations (Time Cost)**: 2 iterações (`t=2`).

### 2.3 Benchmark Observado (Ambiente Local Real)
Executado via teste automatizado de benchmark no ambiente local real de desenvolvimento (Intel Core i5-10400, 24 GB de RAM, 2 TB de armazenamento, GT 1030):
- **Tempo médio de hashing**: ~32 a 51 ms.
- **Tempo médio de verificação (`matches`)**: ~31 a 51 ms.
- **Consumo aproximado de memória por derivação**: ~21 a 40 MB.
- **Conclusão**: O tempo médio na faixa de ~30 a 50 ms situa-se no limiar ideal para autenticação interativa humana (imperceptível para o usuário final, mas ordens de magnitude superior ao hashing ingênuo para inviabilizar ataques de força bruta offline em hardware com 24 GB de RAM).

### 2.4 Política de Reconfiguração de Custo
Caso o hardware de produção aumente de capacidade ou as diretrizes da OWASP recomendem aumento:
- Os parâmetros podem ser elevados de forma transparente.
- O formato do hash do Argon2id inclui sua assinatura de parâmetros (ex: `$argon2id$v=19$m=19456,t=2,p=1$...`), permitindo compatibilidade retroativa e re-hashing durante o login de usuários com parâmetros antigos.

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

## 4. Refresh Token e Gerenciamento de Sessões

### 4.1 Estrutura e Armazenamento (SHA-256 vs Argon2)
- O Refresh Token é uma sequência criptograficamente aleatória de 256 bits (32 bytes), codificada em Base64 URL-safe (43 caracteres) via `SecureRandom`.
- **Nenhum refresh token puro é salvo no banco de dados**. O banco armazena exclusivamente o **hash criptográfico SHA-256** do token (`64 caracteres hexadecimais`).
- **Por que SHA-256 e não Argon2 para o Refresh Token?**
  - O Argon2id é uma função deliberadamente lenta (*memory-hard* e *time-hard*) concebida para mitigar ataques de força bruta contra senhas humanas (que possuem entropia limitada).
  - O Refresh Token possui **256 bits de entropia puramente criptográfica**, tornando matematicamente impossível qualquer ataque de dicionário ou inversão por força bruta.
  - O uso de SHA-256 provê hashing determinístico e ultra-rápido, essencial para validação de sessões de alta performance sob concorrência sem esgotar CPU/memória da aplicação.

### 4.2 Tabela `auth_sessions` (Migration V5)
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

### 4.3 Rotação Atômica e Proteção Contra Concorrência (Row-Level Locking)
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

## 5. Endpoints Implementados

| Método | Endpoint | Proteção | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Pública | Cria `User` e `Profile` em transação única. Emite access token + refresh token. |
| `POST` | `/api/v1/auth/login` | Pública | Valida credenciais locais, cria sessão e emite tokens. Erros de credenciais são uniformes. |
| `POST` | `/api/v1/auth/refresh` | Pública | Executa rotação de refresh token e emite novo par de tokens. |
| `POST` | `/api/v1/auth/logout` | Autenticada (Bearer) | Revoga a sessão do refresh token informado. |
| `GET` | `/api/v1/auth/me` | Autenticada (Bearer) | Retorna dados públicos e seguros da identidade autenticada (`sub` do JWT). |

---

## 6. Tratamento de Erros e Segurança de Resposta

- **Mensagens Genéricas de Login**: A API não revela se o e-mail não existe, se o usuário é federado ou se a senha está errada. Todos retornam `INVALID_CREDENTIALS` (`401 Unauthorized`).
- **Sem Exposição de Dados Sensíveis**: `password_hash`, `token_hash`, tokens completos ou segredos nunca são retornados em DTOs de resposta nem em logs.
- **Usuários Desativados (Soft-deleted)**: Usuários com `deleted_at IS NOT NULL` são rejeitados tanto no login quanto no refresh e na consulta ao `/me` (`ACCOUNT_DISABLED`, `403 Forbidden`).
- **Tratamento de Exceções**: Todas as falhas de autenticação e validação são retornadas em conformidade com o RFC 7807 (`application/problem+json`).

---

## 7. Decisões Arquiteturais e Limites Deste Step

1. **Assinatura HMAC-SHA256 (Simétrica)**:
   - Adotada para manter a infraestrutura simples e sem dependência de pares de chaves assimétricas em arquivo local neste estágio do projeto.
   - Pode ser migrada transparentemente para RSA (RS256) ou Curvas Elípticas (ES256) em etapas posteriores com chaves KMS/Secret Manager.
2. **Ausência de Blacklist Global de Access Tokens**:
   - Para manter a arquitetura stateless, os access tokens possuem vida útil curta (15 minutos). O logout revoga imediatamente o refresh token no banco de dados.
3. **Limites do Escopo**:
   - Provedores externos (Google OAuth, Apple OAuth) permanecem estritamente fora do escopo deste Step.
   - Recursos como verificação de e-mail, recuperação de senha, MFA e rate-limiting distribuído com Redis serão incorporados nas etapas subsequentes.
