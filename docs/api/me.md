# API de Usuário e Perfil (docs/api/me.md)

Este documento especifica os contratos de API RESTful dos endpoints de consulta e gerenciamento de perfil do próprio usuário autenticado na plataforma **Rewit** (Step 5).

Todos os endpoints utilizam JSON (`Content-Type: application/json;charset=UTF-8`). Erros seguem a especificação [RFC 7807](https://tools.ietf.org/html/rfc7807) (`ProblemDetail`).

---

## 1. Identidade e Perfil Autenticado

Retorna os dados públicos e de conta do usuário atualmente autenticado a partir do token de acesso JWT.

- **Método**: `GET`
- **Rota**: `/api/v1/me`
- **Autenticação**: Requer Bearer Token no cabeçalho `Authorization: Bearer <access-token>`

### Resposta de Sucesso (`200 OK`)
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "usuario@exemplo.com",
  "handle": "usuario_rewit",
  "displayName": "Nome do Usuário",
  "bio": "Entusiasta de cafés e gastronomia local.",
  "avatarUrl": null,
  "isVerified": false,
  "isAnonymousDefault": false,
  "reputationScore": 0,
  "createdAt": "2026-09-27T18:00:00Z"
}
```

### Garantias de Segurança
- **Proteção contra vazamento de segredos**: `passwordHash`, `refreshToken`, `tokenHash`, identificadores de sessão (`sessionId`), IPs ou user-agents **nunca** são expostos.
- **Validação de conta ativa**: Se o usuário associado ao token estiver desativado ou marcado como excluído (`deleted_at IS NOT NULL`), o endpoint retorna `401 Unauthorized` (`ACCOUNT_DISABLED`).
- **Sem autenticação**: Requisições sem o cabeçalho `Authorization` retornam `401 Unauthorized` (`AUTHENTICATION_REQUIRED`).

---

## 2. Atualização Parcial do Perfil

Permite a atualização dos campos editáveis do perfil associado ao usuário autenticado.

- **Método**: `PATCH`
- **Rota**: `/api/v1/me/profile`
- **Autenticação**: Requer Bearer Token no cabeçalho `Authorization: Bearer <access-token>`

### Requisição
Todos os campos são opcionais.

#### Semântica de Atualização Parcial (PATCH Customizado)
O endpoint implementa um **PATCH parcial customizado** (sem uso de JSON Merge Patch ou conformidade com a RFC 7396), operando sob as seguintes regras estritas:
- **Campo ausente**: Não sofre alteração; preserva o valor atualmente persistido.
- **Campo explicitamente `null`**: Não sofre alteração; preserva o valor atualmente persistido.
- **Campo com valor**: Atualiza o campo conforme as validações e regras de negócio do domínio.
- **Limpeza de `bio` (`""` ou `"   "`)**: Como a `bio` é o único campo anulável do perfil, sua limpeza/remoção é efetuada enviando string vazia `""` ou contendo apenas espaços em branco. O domínio normaliza essa entrada para `null`, persistindo a anulação no banco de dados.

```json
{
  "handle": "novo_handle",
  "displayName": "Novo Nome de Exibição",
  "bio": "Nova biografia do perfil",
  "isAnonymousDefault": true
}
```

#### Validações de Entrada
- `handle`: 3 a 30 caracteres alfanuméricos ou sublinhado (`^@?[a-zA-Z0-9_]{3,30}$`). O caractere `@` inicial (se informado) é removido e o valor é normalizado para minúsculas.
- `displayName`: 2 a 100 caracteres. Obrigatório caso informado (não pode ser nulo nem vazio).
- `bio`: Máximo de 500 caracteres. Enviar string vazia `""` limpa a biografia (converte para `null`).
- `isAnonymousDefault`: Booleano indicando se avaliações futuras serão publicadas como anônimas por padrão.

### Resposta de Sucesso (`200 OK`)
Retorna o DTO `UserProfileResponse` com os dados atualizados persistidos.

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "usuario@exemplo.com",
  "handle": "novo_handle",
  "displayName": "Novo Nome de Exibição",
  "bio": "Nova biografia do perfil",
  "isAnonymousDefault": true,
  "avatarUrl": null,
  "isVerified": false,
  "reputationScore": 0,
  "createdAt": "2026-09-27T18:00:00Z"
}
```

### Regras de Negócio e Proteção contra IDOR
1. **Identidade Estrita via JWT (Anti-IDOR)**: O usuário a ser atualizado é extraído **exclusivamente** da claim `sub` do token JWT validado no Spring Security. Qualquer tentativa de enviar `userId`, `id` ou parâmetros de consulta direcionados a outros usuários é sumariamente ignorada.
2. **Imutabilidade de Campos Críticos**: Campos de conta e identidade como `id`, `email`, `reputationScore`, `passwordHash` e `createdAt` são imutáveis por este endpoint.
3. **Unicidade e Normalização de Handle**:
   - O `handle` sofre normalização determinística (`trim().toLowerCase().replaceAll("^@", "")`).
   - Se o novo handle já estiver em uso por outro perfil (comparação case-insensitive), o endpoint rejeita a alteração com `409 Conflict` (`HANDLE_ALREADY_EXISTS`).
   - A garantia final de integridade concorrente é assegurada pelo índice único no PostgreSQL (`uq_profiles_handle_lower`), capturado globalmente pelo `GlobalExceptionHandler` caso ocorram mutações simultâneas.

### Códigos de Erro
- `400 Bad Request`: Payload malformado ou campos fora das restrições de formato/tamanho (`Erro de Validação de Dados`).
- `401 Unauthorized`: Token JWT ausente, expirado ou inválido (`AUTHENTICATION_REQUIRED`, `ACCOUNT_DISABLED`).
- `404 Not Found`: Perfil do usuário não localizado no banco (`PROFILE_NOT_FOUND`).
- `409 Conflict`: Conflito de unicidade de handle com outro perfil existente (`HANDLE_ALREADY_EXISTS`).
