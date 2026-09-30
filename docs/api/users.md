# Documentação da API REST — Perfil Público e Estatísticas Factuais do Usuário (Step 18.0)

Este documento especifica o contrato da API RESTful para a consulta do **perfil público factual** de usuários na plataforma Rewit.

---

## 1. Visão Geral e Princípios Arquiteturais

* **Estatísticas Factuais, Não Reputação**: O endpoint expõe exclusivamente números agregados derivados das relações ativas existentes no banco de dados. Não há cálculo de scores, níveis de experiência, posições em rankings, badges ou pontuações ponderadas.
* **Autenticação Padrão**: O endpoint segue o padrão autenticado da plataforma, exigindo cabeçalho `Authorization: Bearer <jwt>`. Isso permite resolver com segurança o estado contextual social (`isFollowing`) para o solicitante autenticado.
* **Proteção Estrita contra IDOR e PII**: O `{id}` na URL identifica estritamente o perfil consultado. Não são expostos dados sensíveis como e-mail, hash de senhas, credenciais, provedor OAuth, tokens, sessões ativas ou coordenadas geográficas.
* **Privacidade dos Contadores**:
  * `totalReviews` contabiliza avaliações ativas criadas pelo autor (incluindo `PUBLIC`, `FOLLOWERS` e `PRIVATE`) sem expor nenhuma lista, conteúdo textual ou metadata individual de avaliações privadas.
  * `helpfulVotesReceived` contabiliza o número agregado de marcações de utilidade sem expor a lista de votantes.
* **Source of Truth no PostgreSQL**: As estatísticas são agregações determinísticas calculadas via SQL indexado (`COUNT`), sem colunas mutáveis desnormalizadas ou caches em memória que possam dessincronizar.

---

## 2. Endpoints

### 2.1 Consultar Perfil Público
* **Método**: `GET`
* **Rota**: `/api/v1/users/{id}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Rota:
| Parâmetro | Tipo | Descrição |
| :--- | :--- | :--- |
| `id` | `UUID` | Identificador único do usuário a ser consultado |

#### Resposta de Sucesso (`200 OK`):
```json
{
  "id": "e4b2d354-93ec-4286-9dc4-83952a233b8a",
  "handle": "vitin",
  "displayName": "Vitin",
  "bio": "Entusiasta de boa gastronomia e café especial",
  "avatarUrl": "https://cdn.rewit.com/avatars/vitin.webp",
  "isFollowing": true,
  "stats": {
    "totalReviews": 42,
    "verifiedReviewsCount": 18,
    "followersCount": 17,
    "followingCount": 23,
    "helpfulVotesReceived": 91
  }
}
```

---

## 3. Especificação dos Campos e Estatísticas

### 3.1 Dados Públicos do Perfil
* `id` (`UUID`): Identificador público imutável do usuário.
* `handle` (`String`): Nome de usuário público exclusivo (sempre minúsculo).
* `displayName` (`String`): Nome de exibição público.
* `bio` (`String` ou `null`): Descrição biográfica do perfil.
* `avatarUrl` (`String` ou `null`): URL pública da imagem de avatar.
* `isFollowing` (`Boolean`):
  * `true`: Se o solicitante autenticado segue o usuário alvo.
  * `false`: Se o solicitante não segue o usuário alvo, ou se o solicitante estiver consultando seu próprio perfil (`requesterId.equals(targetUserId)`).

### 3.2 Estatísticas Factuais (`stats`)
| Campo | Tipo | Regra de Cálculo SQL / Definição |
| :--- | :--- | :--- |
| `totalReviews` | `Long` | Contagem de avaliações ativas criadas pelo usuário (`reviews.user_id = :userId AND reviews.status = 'ACTIVE'`). Avaliações `REMOVED` ou `UNDER_REVIEW` não entram no total. |
| `verifiedReviewsCount` | `Long` | Contagem de avaliações ativas com verificação física no local (`reviews.user_id = :userId AND reviews.status = 'ACTIVE' AND reviews.is_verified_on_site = true`). |
| `followersCount` | `Long` | Total de relações na tabela `user_follows` onde `followed_user_id = :userId`. |
| `followingCount` | `Long` | Total de relações na tabela `user_follows` onde `follower_user_id = :userId`. |
| `helpfulVotesReceived` | `Long` | Contagem agregada de reações `HELPFUL` recebidas em avaliações ativas (`review_reactions.reaction_type = 'HELPFUL' JOIN reviews ON reviews.id = review_reactions.review_id WHERE reviews.user_id = :userId AND reviews.status = 'ACTIVE'`). Não conta reações em avaliações removidas ou sob moderação, nem outros tipos de reações. |

---

## 4. Tratamento de Erros e Casos de Borda

As respostas de erro seguem o padrão RFC 7807 (`application/problem+json`).

### 4.1 Usuário Não Encontrado ou Inativo (`404 Not Found`)
Retornado quando o `{id}` não existe, pertence a um usuário inativo (`isActive = false`) ou com soft-delete (`deletedAt IS NOT NULL`), protegendo a privacidade e prevenindo enumeração de contas inativas.
```json
{
  "type": "about:blank",
  "title": "Usuário não encontrado",
  "status": 404,
  "code": "USER_NOT_FOUND",
  "detail": "Usuário não encontrado ou inativo"
}
```

### 4.2 Falta de Autenticação (`401 Unauthorized`)
Retornado quando a requisição não inclui cabeçalho `Authorization: Bearer <token>` válido.
```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Full authentication is required to access this resource"
}
```

---

## 5. Garantias de Segurança e Performance

* **Índices Utilizados**:
  * `idx_reviews_user_created_at` em `reviews(user_id, created_at DESC)` acelera a filtragem por usuário e status.
  * `idx_review_reactions_review` em `review_reactions(review_id)` viabiliza o JOIN com as avaliações ativas do autor.
  * `idx_user_follows_followed` em `user_follows(followed_user_id)` atende `followersCount`.
  * `idx_user_follows_follower` em `user_follows(follower_user_id)` atende `followingCount`.
  * `pk_user_follows` em `user_follows(follower_user_id, followed_user_id)` atende `isFollowing` em O(1).
* **Ausência de N+1 e Carga em Memória**: Nenhuma lista de avaliações, seguidores ou reações é carregada em memória. Todas as contagens são realizadas diretamente pelo mecanismo de agregação SQL do PostgreSQL (`COUNT`).
* **Composição Real de Consultas SQL por Requisição**:
  * **Chamada por Usuário Terceiro (`requester != target`) — 8 Queries**:
    1. `users`: Consulta por ID para validar existência, `isActive` e ausência de `deletedAt`.
    2. `profiles`: Consulta por `user_id` para obter os dados de exibição pública.
    3. `user_follows`: `EXISTS` para determinar o booleano `isFollowing`.
    4. `reviews`: `COUNT(*)` com `user_id = :id AND status = 'ACTIVE'` para `totalReviews`.
    5. `reviews`: `COUNT(*)` com `user_id = :id AND status = 'ACTIVE' AND is_verified_on_site = true` para `verifiedReviewsCount`.
    6. `user_follows`: `COUNT(*)` com `followed_user_id = :id` para `followersCount`.
    7. `user_follows`: `COUNT(*)` com `follower_user_id = :id` para `followingCount`.
    8. `review_reactions` + `reviews`: `COUNT(*)` com `reaction_type = 'HELPFUL' AND reviews.user_id = :id AND reviews.status = 'ACTIVE'` para `helpfulVotesReceived`.
  * **Chamada pelo Próprio Usuário (`requester == target`) — 7 Queries**:
    * A query de `isFollowing` é omitida e resolvida imediatamente como `false` em memória, totalizando 7 queries indexadas.

