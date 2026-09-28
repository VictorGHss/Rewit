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

---

## 3. Alteração de Senha da Conta Local

Permite que o usuário autenticado altere a senha da sua conta local, persistindo o novo hash Argon2id e revogando todas as sessões de refresh ativas associadas à sua conta (Step 6).

- **Método**: `POST`
- **Rota**: `/api/v1/me/password`
- **Autenticação**: Requer Bearer Token no cabeçalho `Authorization: Bearer <access-token>`

### Requisição
Payload JSON explícito:
```json
{
  "currentPassword": "senha-atual-correta",
  "newPassword": "nova-senha-forte-123"
}
```

#### Regras e Restrições de Entrada
- `currentPassword`: Obrigatório (não vazio/em branco). Corresponde à senha atual em texto plano.
- `newPassword`: Obrigatório, com restrição estrita de tamanho: **mínimo de 8 caracteres** e **máximo de 128 caracteres**.
- **Campos Proibidos**: O payload rejeita ou ignora quaisquer campos administrativos, de identidade (`userId`), hashes pré-calculados ou tokens de sessão.

### Resposta de Sucesso (`200 OK`)
```json
{
  "message": "Senha alterada com sucesso. Todas as sessões anteriores foram revogadas."
}
```
A resposta intencionalmente **não** expõe segredos, hashes, sessões ou novos tokens.

### Regras de Negócio e Ciclo de Segurança
1. **Identidade Estrita via JWT**: O usuário alvo é identificado exclusivamente pela claim `sub` do token JWT autenticado no contexto do Spring Security.
2. **Exclusividade de Contas `LOCAL`**: Apenas contas registradas com `AuthProvider.LOCAL` podem executar alteração de senha. Contas associadas a provedores OAuth externos (ex: Google, Apple) são rejeitadas com `400 Bad Request` (`LOCAL_AUTH_REQUIRED`).
3. **Validação de Fronteira HTTP e Defense-in-Depth**:
   - **Fronteira Externa (DTO)**: As entradas de senha são validadas na fronteira HTTP via Bean Validation (`@NotBlank`, `@Size(min = 8, max = 128)`). Qualquer requisição com senha ausente, em branco, com menos de 8 caracteres ou com mais de 128 caracteres é sumariamente rejeitada com `400 Bad Request` (`Erro de Validação de Dados`). O objeto `fieldErrors` do Problem Detail expõe exclusivamente o nome do campo e a mensagem instrutiva, sem nunca refletir ou vazar o valor enviado.
   - **Invariante Interna (Application Service)**: O serviço `UserService` mantém a validação da política de tamanho de senha (8 a 128 caracteres) para proteger o núcleo contra chamadas internas diretas (jobs, testes, consumers de mensageria). Como a fronteira HTTP protege o endpoint previamente, requisições HTTP inválidas resultam sempre em `400 Bad Request`.
4. **Validação e Hashing Argon2id**:
   - A senha atual é validada criptograficamente contra o hash armazenado no banco (`Argon2PasswordHasher.matches`).
   - Se incorreta, a requisição é rejeitada com `401 Unauthorized` (`INVALID_CREDENTIALS`), utilizando a mesma resposta opaca de erro de autenticação para mitigar enumeração.
   - A nova senha é processada pelo hasher calibrado do sistema (Argon2id: 19.456 KiB de memória, 2 iterações, paralelismo 1, salt de 16 bytes, hash de 32 bytes).
5. **Revogação Integral das Sessões de Refresh**:
   - Após a atualização atômica do hash do usuário, todas as sessões de refresh ativas do usuário (`AuthSessionJpaEntity` com `revoked_at IS NULL`) são revogadas no PostgreSQL (`revoked_at = CURRENT_TIMESTAMP`).
   - Qualquer tentativa posterior de utilizar um refresh token emitido anteriormente em `POST /api/v1/auth/refresh` falhará com `401 Unauthorized` (`REFRESH_TOKEN_REVOKED`).
6. **Comportamento da Senha Antiga**:
   - Deixa de funcionar imediatamente para quaisquer novos logins em `POST /api/v1/auth/login` (`INVALID_CREDENTIALS`).
7. **Comportamento do Access Token Atual**:
   - Em conformidade com a arquitetura de tokens stateless (JWT), o access token atualmente em posse do cliente permanece válido até o término de sua janela de expiração natural (15 minutos). Não há introdução de blacklist de access tokens nesta etapa.
8. **Transacionalidade e Concorrência**:
   - O método `UserService.changePassword` é anotado com `@Transactional`, garantindo atomicidade entre a persistência do novo hash e a revogação de sessões. Em caso de falha ou exceção, nenhuma alteração parcial é persistida.

### Códigos de Erro
- `400 Bad Request`:
  - Parâmetros da requisição inválidos (senha ausente, em branco, menor que 8 ou maior que 128 caracteres);
  - Tentativa de alterar senha em conta registrada com provedor externo (`LOCAL_AUTH_REQUIRED`).
- `401 Unauthorized`:
  - Token JWT ausente ou expirado (`AUTHENTICATION_REQUIRED`);
  - Conta de usuário inativa ou soft-deleted (`ACCOUNT_DISABLED`);
  - Senha atual incorreta (`INVALID_CREDENTIALS`).
