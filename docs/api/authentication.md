# API de Autenticação Local (docs/api/authentication.md)

Este documento especifica os contratos de API RESTful dos endpoints de autenticação local da plataforma **Rewit** (Step 4).

Todos os endpoints utilizam JSON (`Content-Type: application/json;charset=UTF-8`). Erros seguem a especificação [RFC 7807](https://tools.ietf.org/html/rfc7807) (`ProblemDetail`).

---

## 1. Registro de Novo Usuário

Cria uma conta de usuário com credenciais locais (`auth_provider = LOCAL`) e o respectivo perfil público em transação única.

- **Método**: `POST`
- **Rota**: `/api/v1/auth/register`
- **Autenticação**: Pública (não requer token)

### Requisição
```json
{
  "email": "usuario@exemplo.com",
  "password": "SenhaSegura@123",
  "handle": "usuario_rewit",
  "displayName": "Nome do Usuário"
}
```

#### Validações de Entrada
- `email`: Obrigatório, formato RFC 5322 válido. Normalizado para minúsculas e sem espaços laterais.
- `password`: Obrigatória, mínimo de 8 caracteres e máximo de 128 caracteres.
- `handle`: Obrigatório, 3 a 30 caracteres (`[a-z0-9_.]`). Prefixo `@` removido e normalizado para minúsculas.
- `displayName`: Obrigatório, 2 a 100 caracteres.

### Resposta de Sucesso (`201 Created`)
```json
{
  "user": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "email": "usuario@exemplo.com",
    "handle": "usuario_rewit",
    "displayName": "Nome do Usuário",
    "isVerified": false,
    "isAnonymousDefault": false,
    "reputationScore": 0
  },
  "accessToken": "<access-token-jwt>",
  "refreshToken": "<raw-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Códigos de Retorno e Erros
- `201 Created`: Usuário e perfil criados com sucesso. Sessão iniciada.
- `400 Bad Request`: Payload malformado ou campos obrigatórios ausentes.
- `422 Unprocessable Entity`: E-mail ou handle já em uso (`EMAIL_ALREADY_EXISTS`, `HANDLE_ALREADY_EXISTS`), ou senha inválida (`INVALID_PASSWORD_POLICY`).

---

## 2. Login Local

Autentica um usuário existente através de e-mail e senha cadastrados localmente.

- **Método**: `POST`
- **Rota**: `/api/v1/auth/login`
- **Autenticação**: Pública (não requer token)

### Requisição
```json
{
  "email": "usuario@exemplo.com",
  "password": "SenhaSegura@123"
}
```

### Resposta de Sucesso (`200 OK`)
```json
{
  "user": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "email": "usuario@exemplo.com",
    "handle": "usuario_rewit",
    "displayName": "Nome do Usuário",
    "isVerified": false,
    "isAnonymousDefault": false,
    "reputationScore": 0
  },
  "accessToken": "<access-token-jwt>",
  "refreshToken": "<raw-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Segurança e Tratamento de Erros
Para mitigar enumeração de contas, falhas por e-mail inexistente, senha incorreta ou usuário federado (`GOOGLE`/`APPLE`) retornam a mesma mensagem uniforme:
- `401 Unauthorized`:
```json
{
  "type": "about:blank",
  "title": "Regra de Negócio Violada",
  "status": 401,
  "detail": "Credenciais inválidas",
  "instance": "/api/v1/auth/login",
  "code": "INVALID_CREDENTIALS",
  "timestamp": "2026-09-27T18:00:00Z"
}
```

---

## 3. Renovação de Sessão (Refresh Token Rotation)

Renova o par de tokens através de rotação determinística do Refresh Token.

- **Método**: `POST`
- **Rota**: `/api/v1/auth/refresh`
- **Autenticação**: Pública (envia o refresh token no corpo da requisição)

### Requisição
```json
{
  "refreshToken": "<raw-refresh-token>"
}
```

### Resposta de Sucesso (`200 OK`)
```json
{
  "user": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "email": "usuario@exemplo.com",
    "handle": "usuario_rewit",
    "displayName": "Nome do Usuário",
    "isVerified": false,
    "isAnonymousDefault": false,
    "reputationScore": 0
  },
  "accessToken": "<new-access-token-jwt>",
  "refreshToken": "<new-raw-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Comportamento de Rotação e Revogação
1. O token antigo é imediatamente revogado (`revoked_at = now()`).
2. Uma nova sessão é persistida com o hash do novo refresh token e ligada via `replaced_by_session_id`.
3. **Detecção de Reúso (Token Reuse Detection)**: Se um token já revogado for reutilizado fora da janela de 10 segundos após a sua rotação (inclusive um token encerrado por logout), todas as sessões ativas do usuário são revogadas de forma persistente e a requisição retorna `401 Unauthorized` (`REFRESH_TOKEN_REVOKED`); os refresh tokens dessas sessões deixam de funcionar. Dentro da janela de 10 segundos (requisição concorrente legítima do mesmo cliente), a resposta é a mesma, mas nenhuma outra sessão é revogada. Antes do Step 29.1, a revogação em massa era desfeita por rollback e as demais sessões permaneciam ativas.
4. **Sessões expiradas removidas pelo cleanup (Step 29.3)**: uma sessão expirada sem sucessora pode ser removida pelo job de cleanup. Depois disso, o refresh token correspondente passa a receber `401 Unauthorized` com `INVALID_REFRESH_TOKEN`, em vez de `REFRESH_TOKEN_EXPIRED` (ou de `REFRESH_TOKEN_REVOKED`, se estava revogada). Tokens de cadeias de rotação vivas não são afetados.

---

## 4. Encerramento de Sessão (Logout)

Revoga a sessão correspondente ao refresh token informado.

- **Método**: `POST`
- **Rota**: `/api/v1/auth/logout`
- **Autenticação**: Requer Bearer Token no cabeçalho `Authorization: Bearer <access-token>`

### Requisição
```json
{
  "refreshToken": "<raw-refresh-token>"
}
```

### Resposta de Sucesso (`204 No Content`)
Não possui corpo de resposta (`Content-Length: 0`).

### Comportamento
A sessão do refresh token é marcada como revogada (`revoked_at`). O access token existente permanecerá válido até expirar (TTL curto de 15 minutos).

---

## 5. Identidade Autenticada (`/me`)

Retorna a representação pública e segura do usuário associado ao Access Token apresentado.

- **Método**: `GET`
- **Rota**: `/api/v1/auth/me`
- **Autenticação**: Requer Bearer Token no cabeçalho `Authorization: Bearer <access-token>`

### Resposta de Sucesso (`200 OK`)
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "usuario@exemplo.com",
  "handle": "usuario_rewit",
  "displayName": "Nome do Usuário",
  "isVerified": false,
  "isAnonymousDefault": false,
  "reputationScore": 0
}
```

### Garantias de Segurança
- `passwordHash` **nunca** é retornado.
- Refresh tokens, IP, user-agent e metadados internos de segurança são omitidos.
- Se o usuário sofrer soft-delete (`deleted_at IS NOT NULL`), o endpoint recusa a requisição com `401 Unauthorized` (`ACCOUNT_DISABLED`).
