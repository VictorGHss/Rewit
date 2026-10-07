# Documentação da API REST — Conexões Sociais e Seguidores (Step 15.0)

Este documento especifica os contratos da API RESTful para o subsistema de conexões sociais e seguidores do Rewit.

---

## 1. Visão Geral e Princípios Arquiteturais

* **Identidade Segura**: O usuário que segue (`followerUserId`) é identificado **exclusivamente a partir do token JWT autenticado** (`SecurityContext`), impedindo qualquer manipulação de identidade via payload ou cabeçalho falso (anti-IDOR).
* **Idempotência**:
  * Tentar seguir um usuário que já é seguido é uma operação idempotente (retorna `200 OK` com `following: true` sem criar relações duplicadas).
  * Tentar deixar de seguir um usuário que não é seguido também é idempotente (retorna `200 OK` com `following: false`).
* **Bloqueio de Auto-Seguir (Self-Follow)**: Usuários não podem seguir a si mesmos. Essa invariante é garantida na camada de domínio (`SELF_FOLLOW_FORBIDDEN`), no serviço e no PostgreSQL via check constraint `chk_no_self_follow`.
* **Privacidade e Menor Privilégio**: O ato de seguir alguém não concede acesso a e-mails, credenciais, histórico privado de conta ou coordenadas geográficas brutas do usuário seguido. As listagens sociais expõem estritamente perfis públicos resumidos (`id`, `handle`, `displayName`, `avatarUrl`, `followedAt`).
* **Resolução em Lote e Prevenção de N+1**: A resolução dos perfis nas páginas sociais ocorre em lote indexado via busca com cláusula `IN (...)`, eliminando consultas N+1 e mantendo o número de queries previsível por requisição paginada.

---

## 2. Endpoints do Subsistema Social

### 2.1 Seguir Usuário
* **Método**: `POST`
* **Rota**: `/api/v1/users/{id}/follow`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta de Sucesso (`200 OK`):
```json
{
  "following": true
}
```

#### Erros Comuns:
* `400 Bad Request` (`SELF_FOLLOW_FORBIDDEN`): Tentativa de seguir o próprio perfil.
* `401 Unauthorized`: Requisição sem JWT válido.
* `404 Not Found` (`USER_NOT_FOUND`): Usuário `{id}` não existe ou está inativo.

---

### 2.2 Deixar de Seguir Usuário (Unfollow)
* **Método**: `DELETE`
* **Rota**: `/api/v1/users/{id}/follow`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta de Sucesso (`200 OK`):
```json
{
  "following": false
}
```

#### Erros Comuns:
* `400 Bad Request` (`SELF_FOLLOW_FORBIDDEN`): Tentativa de deixar de seguir o próprio perfil.
* `401 Unauthorized`: Requisição sem JWT válido.
* `404 Not Found` (`USER_NOT_FOUND`): Usuário `{id}` não encontrado.

---

### 2.3 Consultar Estado de Conexão (isFollowing)
* **Método**: `GET`
* **Rota**: `/api/v1/users/{id}/follow`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta de Sucesso (`200 OK`):
```json
{
  "following": true
}
```

---

### 2.4 Listar Quem o Usuário Autenticado Segue
* **Método**: `GET`
* **Rota**: `/api/v1/me/following`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta:
| Parâmetro | Tipo | Padrão | Validação |
|---|---|---|---|
| `page` | Integer | `0` | Índice da página (>= 0) |
| `size` | Integer | `10` | Tamanho da página (> 0 e <= 50) |

#### Exemplo de Resposta (`200 OK`):
```json
{
  "content": [
    {
      "id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
      "handle": "carlos_gourmet",
      "displayName": "Carlos Silva",
      "avatarUrl": "https://cdn.rewit.app/avatars/carlos.webp",
      "followedAt": "2026-09-28T19:00:00Z"
    }
  ],
  "pageNumber": 0,
  "pageSize": 10,
  "totalElements": 1,
  "totalPages": 1,
  "isLast": true
}
```

---

### 2.5 Listar Seguidores do Usuário Autenticado
* **Método**: `GET`
* **Rota**: `/api/v1/me/followers`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta:
| Parâmetro | Tipo | Padrão | Validação |
|---|---|---|---|
| `page` | Integer | `0` | Índice da página (>= 0) |
| `size` | Integer | `10` | Tamanho da página (> 0 e <= 50) |

---

### 2.6 Listar Quem um Usuário Segue (Público)
* **Método**: `GET`
* **Rota**: `/api/v1/users/{id}/following`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

---

### 2.7 Listar Seguidores de um Usuário (Público)
* **Método**: `GET`
* **Rota**: `/api/v1/users/{id}/followers`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

---

## 3. Impacto nas Avaliações com Visibilidade FOLLOWERS

Com a introdução do subsistema social no Step 15.0:
1. Avaliações com `visibility: "FOLLOWERS"` são exibidas para os seguidores reais do autor.
2. Na consulta individual (`GET /api/v1/reviews/{id}`):
   * Requester segue o autor: `200 OK`.
   * Requester não segue o autor: `403 Forbidden` (`FORBIDDEN`).
   * Autor consultando sua própria review: `200 OK`.
3. Na listagem por alvo (`GET /api/v1/targets/{id}/reviews`):
   * As avaliações `FOLLOWERS` dos autores seguidos pelo requester são incluídas na página.
   * Avaliações `FOLLOWERS` de autores não seguidos continuam estritamente omitidas da resposta.
4. Anonimização mantida: Se uma avaliação `FOLLOWERS` for marcada com `isAnonymous: true`, a identidade do autor continua mascarada como anônima mesmo para seus seguidores.

---

## 4. Política de Usuários Inativos e Exclusão (Soft-Delete)

* **Seguir Usuário Inativo / Excluído**: O sistema rejeita o seguimento de usuários inativos (`isActive = false`) ou excluídos por soft-delete (`deletedAt != null`), retornando `404 Not Found` (`USER_NOT_FOUND`).
* **Consulta de Listagens de Usuários Inativos**: Ao requisitar seguidores ou quem um usuário segue (`/api/v1/users/{id}/followers` ou `/following`), a existência de usuário ativo é validada. Usuários inexistentes ou inativos resultam em `404 Not Found`.
* **Integridade Referencial no Banco de Dados**: A tabela relacional `user_follows` possui integridade física com `ON DELETE CASCADE` para `follower_user_id` e `followed_user_id`, garantindo que eventuais deleções físicas limpem automaticamente as associações órfãs.
* **Conta excluída (`DELETED`, C2)**: os vínculos continuam no banco até o purge físico, mas não aparecem nas listas de seguidores e seguidos de ninguém nem entram em `followersCount`/`followingCount`. As listas da própria conta excluída respondem `404 USER_NOT_FOUND`, como as de um identificador inexistente.

---

## 5. Índices de Banco de Dados e Desempenho

A infraestrutura de banco de dados do PostgreSQL conta com índices dedicados criados na migração fundacional (V1):

1. **`uq_user_follow`** em `(follower_user_id, followed_user_id)`:
   * Garante a unicidade da relação de seguidor.
   * Utilizado para checagem rápida de relacionamento (`isFollowing`), operando via index seek.
   * Dá suporte à cláusula `ON CONFLICT (follower_user_id, followed_user_id) DO NOTHING` para concorrência atômica.
2. **`idx_user_follows_follower`** em `follower_user_id`:
   * Otimiza a recuperação paginada de "usuários que eu sigo" (`following`).
   * Acelera a subquery de autorização `EXISTS` em `ReviewJpaRepository` quando um usuário consulta avaliações por target.
3. **`idx_user_follows_followed`** em `followed_user_id`:
   * Otimiza a recuperação paginada de "seguidores" de um determinado usuário (`followers`).
   * Acelera contagens de audiência e agrupamentos.
4. **`chk_no_self_follow`**:
   * Constraint de verificação a nível de banco que bloqueia estritamente inserções onde `follower_user_id = followed_user_id`.
