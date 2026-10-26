# API de Autenticação (docs/api/authentication.md)

Este documento especifica os contratos de API RESTful dos endpoints de autenticação local (Step 4) e social (Google, Apple; seções 7 a 9) da plataforma **Rewit**.

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
- `handle`: Obrigatório, 3 a 30 letras sem acento, dígitos ou sublinhado (`^@?[a-zA-Z0-9_]{3,30}$`; ponto não é aceito). Prefixo `@` removido e normalizado para minúsculas.
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
  - O e-mail de uma conta excluída continua em uso também depois do purge (guardado como valor reservado, sem o endereço).
  - Endereços no domínio reservado `deleted.invalid` são recusados (`INVALID_EMAIL`).

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

---

## 6. Reativação de Conta Desativada

Reverte uma desativação feita pelo próprio usuário (`POST /api/v1/me/deactivate`) com as credenciais locais e emite uma nova sessão.

* **Método**: `POST`
* **Rota**: `/api/v1/auth/reactivate`
* **Autenticação**: Pública (as credenciais estão no corpo, como no login)

### Requisição
Mesmo corpo do login: `{"email": "...", "password": "..."}`.

### Resposta de Sucesso (`200 OK`)
Mesmo corpo do login (`accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `user`). A conta volta a `ACTIVE`; uma conta já ativa apenas autentica.

### Regras
- **Somente conta desativada pelo usuário**: conta suspensa (ação administrativa) ou excluída não é reativada por aqui.
- **Sem revelar o estado da conta**: senha errada, e-mail inexistente, conta federada, suspensa ou excluída recebem a mesma resposta do login (`401 Unauthorized`, `INVALID_CREDENTIALS`), com exatamente uma verificação Argon2 em todo caminho.
- **Mesmo limite do login**: as tentativas consomem a janela `LOGIN` da mesma identidade; não é um segundo orçamento de tentativas.
- **Sessões**: a transição revoga todas as sessões anteriores antes de criar a nova.
- **Login de conta desativada**: `POST /api/v1/auth/login` continua respondendo `401 INVALID_CREDENTIALS`; o cliente oferece a reativação como fluxo próprio.
- **Contas federadas**: contas criadas por login social não têm senha e não são reativadas por aqui.

---

## 7. Nonce do Login Social

Emite um nonce de uso único para uma tentativa de login social. O cliente entrega ao provedor o **SHA-256 do nonce, em hexadecimal minúsculo** (UTF-8), e reenvia o nonce **em claro** ao Rewit junto com o ID token.

* **Método**: `POST`
* **Rota**: `/api/v1/auth/social/nonce`
* **Autenticação**: Pública, sem corpo

### Resposta de Sucesso (`200 OK`, `Cache-Control: no-store`)
```json
{ "nonce": "<43 caracteres Base64 URL-safe>", "expiresIn": 600 }
```

### Erros
- `429 RATE_LIMIT_EXCEEDED` (com `Retry-After`): limite global de nonces (padrão 60 por minuto).
- `503 SOCIAL_LOGIN_UNAVAILABLE`: armazenamento de nonces (Redis) indisponível. O login social falha fechado.

---

## 8. Login Social (Google e Apple)

Valida o ID token OpenID Connect do provedor e emite a sessão do Rewit (mesmo corpo de resposta do login local).

* **Método**: `POST`
* **Rotas**: `/api/v1/auth/social/google` e `/api/v1/auth/social/apple`
* **Autenticação**: Pública

### Requisição
```json
{ "idToken": "<ID token devolvido pelo SDK do provedor>", "nonce": "<nonce emitido em /social/nonce>" }
```
`idToken` até 8192 caracteres; `nonce` até 64. Qualquer outro campo (e-mail, nome, provedor, `userId`, papel) é ignorado: a identidade vem somente das claims do token validado.

### Validação no servidor
- Assinatura RS256 com as chaves públicas do provedor (JWKS: `https://www.googleapis.com/oauth2/v3/certs`, `https://appleid.apple.com/auth/keys`); tokens sem assinatura, cifrados ou com outro algoritmo são recusados.
- `iss`: `https://accounts.google.com` ou `accounts.google.com` (Google); `https://appleid.apple.com` (Apple).
- `aud`: um dos client IDs configurados (`GOOGLE_SIGNIN_CLIENT_IDS`, `APPLE_SIGNIN_CLIENT_IDS`).
- `exp`, `iat` e `nbf` com tolerância de 60 s; `sub` obrigatório (até 128 caracteres).
- `nonce`: igual ao SHA-256 do nonce enviado, que precisa ter sido emitido pelo Rewit, estar dentro do prazo e não ter sido usado. O nonce é consumido antes da validação: uma tentativa recusada também o gasta.

### Conta
- A conta é localizada somente pelo par provedor + `sub`. Se existir e estiver `ACTIVE`, recebe a sessão; desativada, suspensa ou excluída responde `401 ACCOUNT_DISABLED`, sem distinguir o estado.
- Sem conta vinculada, uma conta federada (sem senha) é criada se o provedor atestar o e-mail (`email_verified`). O handle é gerado (`usuario_` + 10 caracteres aleatórios, sem derivar do e-mail) e o nome de exibição vem da claim `name` (Google) ou é `Usuário Rewit`; os dois podem ser editados em `PATCH /api/v1/me/profile`. A criação conta no limite global de cadastro.
- **Sem vinculação automática**: se o e-mail do token já pertence a outra conta (local, de outro provedor ou reservado de uma conta excluída), a resposta é `409 SOCIAL_ACCOUNT_EMAIL_CONFLICT` e nada é criado ou alterado. A pessoa entra pelo método usado no cadastro. Vincular uma identidade social a uma conta existente ainda não é suportado.

### Erros
| Status | Código | Situação |
| :--- | :--- | :--- |
| `400` | `VALIDATION_ERROR` | `idToken` ou `nonce` ausente ou longo demais |
| `401` | `INVALID_SOCIAL_NONCE` | Nonce com formato inválido, não emitido, expirado ou já usado |
| `401` | `INVALID_SOCIAL_TOKEN` | Assinatura, emissor, audiência, validade, `sub` ou nonce do token inválidos |
| `401` | `ACCOUNT_DISABLED` | Conta vinculada não está ativa |
| `409` | `SOCIAL_ACCOUNT_EMAIL_CONFLICT` | E-mail do token já pertence a outra conta |
| `422` | `SOCIAL_EMAIL_NOT_VERIFIED` | Conta nova sem e-mail atestado pelo provedor |
| `429` | `RATE_LIMIT_EXCEEDED` | Limite global de cadastro na criação da conta |
| `503` | `SOCIAL_PROVIDER_UNAVAILABLE` | Provedor sem client ID configurado, ou JWKS do provedor inacessível |
| `503` | `SOCIAL_LOGIN_UNAVAILABLE` | Armazenamento de nonces indisponível |

O refresh, o logout e `/auth/me` de uma sessão social são os mesmos da sessão local. `POST /api/v1/auth/login` continua respondendo `401 INVALID_CREDENTIALS` para contas federadas.

---

## 9. Retorno do Sign in with Apple no Android

No Android, o Sign in with Apple roda no navegador e a Apple publica o resultado (`response_mode=form_post`) numa URL do backend, que o devolve ao app.

* **Método**: `POST` (`application/x-www-form-urlencoded`, enviado pela Apple)
* **Rota**: `/api/v1/auth/apple/callback`
* **Resposta**: `303 See Other` para `intent://callback?<parâmetros>#Intent;package=<APPLE_SIGNIN_ANDROID_PACKAGE>;scheme=signinwithapple;end`, com `Cache-Control: no-store` e `Referrer-Policy: no-referrer`

Somente `code`, `id_token`, `state`, `user` e `error` são repassados, cada um codificado; parâmetro repetido ou maior que 16 KB responde `400 INVALID_SOCIAL_CALLBACK`. Nada é validado nem gravado neste endpoint: o app envia o token a `POST /api/v1/auth/social/apple`. Com a Apple desligada, responde `503 SOCIAL_PROVIDER_UNAVAILABLE`.
