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

### 2.2 Parâmetros Configurados
Considerando as restrições de hardware do ambiente de desenvolvimento (máquinas com aproximadamente 4 GB de RAM) sem comprometer a segurança, os seguintes parâmetros foram configurados:
- **Salt Length**: 16 bytes (128 bits criptograficamente seguros via `SecureRandom`).
- **Hash Length**: 32 bytes (256 bits).
- **Parallelism (Threads)**: 1 thread.
- **Memory Cost**: 16.384 KiB (16 MiB de memória por derivação).
- **Iterations (Time Cost)**: 2 iterações.

### 2.3 Política e Boas Práticas
- Nenhuma senha em texto puro é armazenada em banco de dados ou persistida em logs.
- Senhas são validadas na entrada: mínimo de 8 caracteres e máximo de 128 caracteres.
- A comparação de senhas utiliza estritamente `PasswordEncoder.matches(...)`, que executa comparação em tempo constante para evitar ataques de temporização (timing attacks).
- O hash gerado contém a assinatura formal do Argon2id (ex: `$argon2id$v=19$m=16384,t=2,p=1$...`), permitindo atualização transparente de parâmetros no futuro.

---

## 3. Access Token (JWT - HS256)

### 3.1 Características do Token
- **Tipo**: JWT (RFC 7519) assinado digitalmente com HMAC-SHA256 (`HS256`).
- **Segredo Simétrico**: Configurado exclusivamente via variável de ambiente `JWT_SECRET` (mínimo de 256 bits de entropia). Nunca commitado em código nem exposto em logs.
- **Validade Curta (TTL)**: 15 minutos (900 segundos) por padrão, configurável via `JWT_ACCESS_TOKEN_TTL_SECONDS`.

### 3.2 Claims Emitidas
Para manter o token compacto e evitar vazamento de dados, o JWT contém apenas claims essenciais:
- `iss` (Issuer): Emissor configurado (ex: `rewit-api`).
- `sub` (Subject): Identificador canônico único do usuário (`User.id` em formato UUID).
- `aud` (Audience): Lista de audiências autorizadas (ex: `rewit-clients`).
- `iat` (Issued At): Timestamp UTC de emissão.
- `exp` (Expiration Time): Timestamp UTC de expiração.
- `jti` (JWT ID): Identificador único aleatório do token (UUID v4) para auditoria.

> **Importante**: O JWT **não** contém e-mail completo, dados sensíveis, senhas, papéis excessivos ou tokens de refresh.

### 3.3 Validação no Spring Security
O backend atua como um **OAuth2 Resource Server** utilizando `spring-boot-starter-oauth2-resource-server` com `NimbusJwtDecoder`:
- A validação exige assinatura HMAC-SHA256 válida com o segredo do ambiente.
- São validados compulsoriamente: assinatura, issuer (`iss`), audience (`aud`) e expiração (`exp`).
- Tokens expirados, corrompidos ou com audiência/emissor divergentes são rejeitados com `401 Unauthorized` estruturado no formato RFC 7807 (`ProblemDetail`).

---

## 4. Refresh Token e Gerenciamento de Sessões

### 4.1 Estrutura e Armazenamento
- O Refresh Token é uma sequência criptograficamente aleatória de 256 bits (32 bytes), codificada em Base64 URL-safe (43 caracteres).
- **Nenhum refresh token puro é salvo no banco de dados**. O banco armazena exclusivamente o **hash criptográfico SHA-256** do token (`64 caracteres hexadecimais`).
- Caso a base de dados seja comprometida, os atacantes não conseguem utilizar os hashes para renovar sessões.

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

### 4.3 Rotação de Refresh Token (Rotation)
A cada requisição a `POST /api/v1/auth/refresh`:
1. Calcula-se o hash SHA-256 do token recebido.
2. Localiza-se a sessão correspondente no banco.
3. Valida-se:
   - Se a sessão não está expirada (`expires_at > now`).
   - Se a sessão não está revogada (`revoked_at IS NULL`).
   - Se o usuário proprietário da sessão ainda está ativo (`deleted_at IS NULL`).
4. **Revogação da sessão atual**: `revoked_at` é marcado com o timestamp atual.
5. **Geração da nova sessão**: É gerado um novo refresh token e inserida uma nova sessão vinculada com `replaced_by_session_id`.
6. Um novo par de Access Token e Refresh Token é devolvido ao cliente.

### 4.4 Detecção de Reutilização de Token (Token Reuse Detection)
Se um refresh token já revogado (`revoked_at IS NOT NULL`) for apresentado ao endpoint de refresh:
- A operação é sumariamente rejeitada com erro `REFRESH_TOKEN_REVOKED` (`401 Unauthorized`).
- **Mitigação de Roubo de Sessão**: Todas as sessões ativas do usuário são imediatamente revogadas (`revokeAllByUserId`). Isso impede que um atacante continue navegando com tokens obtidos de forma ilegítima.

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
