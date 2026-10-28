# Documentação da API REST — Feed Social V1 (Step 17.0)

Este documento especifica os contratos da API RESTful para a primeira versão da Timeline/Feed Social do Rewit.

---

## 1. Visão Geral e Princípios Arquiteturais

O **Feed Social V1** é uma **timeline social estritamente cronológica** das avaliações publicadas pelos usuários que o solicitante (*requester*) segue.

> **Nota Arquitetural Importante:**
> O Feed V1 é estritamente cronológico. Proximidade geográfica, trending, pontuação de relevância, eventos e algoritmos de recomendação (ML/embeddings) pertencem a fases posteriores.

### Princípios Chave:
* **Identidade Segura e Exclusiva**: O feed pertence exclusivamente ao usuário autenticado identificado via JWT (`SecurityContext`). Nunca é permitido passar parâmetros como `?userId=...` para inspecionar o feed de outro usuário.
* **Fonte dos Relacionamentos**: O feed baseia-se exclusivamente na tabela relacional `user_follows` (`follower_user_id = requester`). Não inclui avaliações de usuários não seguidos e não inclui as avaliações do próprio solicitante.
* **Regras de Visibilidade e Status**:
  * Avaliações com status `ACTIVE` são elegíveis.
  * Visibilidade `PUBLIC`: Entram normalmente no feed.
  * Visibilidade `FOLLOWERS`: Entram no feed porque a relação de seguidor já está estruturalmente satisfeita.
  * Visibilidade `PRIVATE`: **Nunca** entram no feed.
  * Status `UNDER_REVIEW` e `REMOVED`: **Nunca** entram no feed.
* **Anonimato**: avaliações com `isAnonymous = true` **nunca** entram no feed. Num feed montado só com autores seguidos, a presença delas restringiria a autoria ao conjunto de seguidos (seguir uma única conta a revelaria); por isso não basta mascarar o autor. Elas continuam na listagem por alvo, com o autor mascarado (ver `reviews.md` §1.2). No Feed V2, anônimas `PUBLIC` só aparecem pela descoberta pública, que não depende de follows, e a diversidade por autor usa uma chave da própria avaliação em vez do autor real.
* **Autor com conta excluída (`DELETED`)**: a avaliação continua no feed, com o autor sem `id`, `handle` e `avatarUrl` e `displayName = "Usuário excluído"` (ver `reviews.md` §1.2).
* **Autoria Contextual (`isMine`)**: O campo booleano `isMine` presente em cada item retornado (`ReviewResponse`) indica se a avaliação pertence ao usuário autenticado solicitante (`requesterUserId == review.userId`). Para avaliações de terceiros seguidos, é retornado como `false`. Se uma avaliação do próprio usuário estiver presente no feed (inclusive quando `isAnonymous: true`), `isMine` é retornado como `true` sem revelar os dados de autoria pública da avaliação anônima para outros usuários.
* **Ordenação Determinística**:
  * Fixa em: `created_at DESC, id ASC`.
  * Nenhum outro critério de ordenação é aceito no V1 (e.g., `sort=rating`, `sort=helpful`, `sort=trending`).
* **Prevenção de N+1 (Batch Loading)**:
  1. A página de avaliações é recuperada em uma única query indexada (`ReviewJpaRepository.findFeedByFollowing`).
  2. Os alvos (`ReviewTargets`) são resolvidos em lote indexado (`findByReviewIdIn`).
  3. Os perfis dos autores (`Profiles`) são resolvidos em lote (`findByUserIdIn`).
  4. As contagens de utilidade (`helpfulCount`) e a indicação de voto do solicitante (`isHelpfulByMe`) são calculadas em lote (`ReviewReactionJpaRepository`).

---

## 2. Endpoint do Feed Social

### 2.1 Obter Feed Social Paginado
* **Método**: `GET`
* **Rota**: `/api/v1/feed`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta (Query Parameters):
| Parâmetro | Tipo | Padrão | Descrição | Restrições |
|---|---|---|---|---|
| `page` | Integer | `0` | Índice da página (0-indexed) | Deve ser `>= 0` (retorna `400 INVALID_PAGE` se menor) |
| `size` | Integer | `10` | Quantidade de itens por página | Deve ser `>= 1` (retorna `400 INVALID_SIZE`) e `<= 50` (retorna `400 PAGE_SIZE_EXCEEDED`) |
| `sort` | String | `newest` | Critério de ordenação | Apenas `newest` é aceito no V1 (qualquer outro gera `400 INVALID_SORT`) |

---

### 2.2 Exemplos de Requisição e Resposta

#### Requisição:
```http
GET /api/v1/feed?page=0&size=10 HTTP/1.1
Host: api.rewit.com
Authorization: Bearer <jwt-token>
```

#### Resposta de Sucesso (`200 OK`):
```json
{
  "content": [
    {
      "id": "7b0d2358-1f6e-4ab4-802b-3e5e7da09101",
      "author": {
        "id": "01944883-9366-71d3-a5c8-c672b1234567",
        "handle": "alice",
        "displayName": "Alice Cooper",
        "avatarUrl": "https://cdn.rewit.com/avatars/alice.png"
      },
      "comment": "Lugar fantástico com excelente café!",
      "status": "ACTIVE",
      "visibility": "PUBLIC",
      "isAnonymous": false,
      "isVerifiedOnSite": true,
      "helpfulCount": 3,
      "isHelpfulByMe": true,
      "isMine": false,
      "targets": [
        {
          "targetType": "PLACE",
          "targetId": "c9287311-2e63-4414-87a3-e380e5b7c7b1",
          "rating": 5.0
        },
        {
          "targetType": "PRODUCT",
          "targetId": "01944883-9366-71d3-a5c8-c672b9999999",
          "rating": 4.5
        }
      ],
      "createdAt": "2026-09-28T21:15:00Z",
      "updatedAt": "2026-09-28T21:15:00Z"
    },
    {
      "id": "e4414322-965a-493d-9d58-45a8989c9d74",
      "author": {
        "id": "7a1f3c52-0b8e-4d6a-9f21-3c4b5d6e7f80",
        "handle": "bruno",
        "displayName": "Bruno Lima",
        "avatarUrl": null
      },
      "comment": "Atendimento rápido, mas ambiente barulhento.",
      "status": "ACTIVE",
      "visibility": "FOLLOWERS",
      "isAnonymous": false,
      "isVerifiedOnSite": false,
      "helpfulCount": 0,
      "isHelpfulByMe": false,
      "isMine": false,
      "targets": [
        {
          "targetType": "PLACE",
          "targetId": "c9287311-2e63-4414-87a3-e380e5b7c7b1",
          "rating": 3.0
        }
      ],
      "createdAt": "2026-09-28T20:00:00Z",
      "updatedAt": "2026-09-28T20:00:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1,
  "last": true
}
```

---

## 3. Respostas de Erro

| Código HTTP | Código Interno (`code`) | Motivo |
|---|---|---|
| `401 Unauthorized` | — | Requisição ausente de token JWT ou com token inválido/expirado |
| `400 Bad Request` | `INVALID_PAGE` | Parâmetro `page < 0` |
| `400 Bad Request` | `INVALID_SIZE` | Parâmetro `size < 1` |
| `400 Bad Request` | `PAGE_SIZE_EXCEEDED` | Parâmetro `size > 50` |
| `400 Bad Request` | `INVALID_SORT` | Parâmetro `sort` diferente de `newest` |

Exemplo de erro de validação (`400 Bad Request`):
```json
{
  "code": "PAGE_SIZE_EXCEEDED",
  "message": "O tamanho da página não pode exceder 50 itens."
}
```

---

## 4. Desempenho e Estratégia de Carregamento em Lote

A consulta de feed opera diretamente sobre o relacionamento indexado entre seguidores e avaliações:
```sql
SELECT r.*
FROM reviews r
JOIN user_follows uf ON uf.followed_user_id = r.user_id
WHERE uf.follower_user_id = :requesterUserId
  AND r.status = 'ACTIVE'
  AND r.visibility IN ('PUBLIC', 'FOLLOWERS')
  AND r.is_anonymous = FALSE
ORDER BY r.created_at DESC, r.id ASC
LIMIT :limit OFFSET :offset;
```

### Ciclo de Consultas Nominais (Ausência de N+1)
Para evitar o problema de N+1 consultas, o serviço executa o enriquecimento estritamente em lote:
1. **Query Principal Paginada**: Busca o bloco da página de avaliações elegíveis via junção indexada com `user_follows`.
2. **Query de Contagem (`COUNT`)**: Executada pela paginação JPA quando necessária para determinar `totalElements` e `totalPages`.
3. **Batch de Alvos (`ReviewTargets`)**: Resolução de todos os alvos das avaliações da página em lote indexado (`findByReviewIdIn`).
4. **Batch de Perfis (`Profiles`)**: Resolução de todos os perfis dos autores não anônimos em lote (`findByUserIdIn`).
5. **Batch de Contagem de Helpful**: Agregação dos votos úteis das avaliações da página em lote (`countHelpfulByReviewIds`).
6. **Batch de Helpful do Solicitante (`isHelpfulByMe`)**: Verificação dos votos úteis concedidos pelo solicitante em lote (`findHelpfulReviewIdsByUser`).

*Nota*: O número de consultas nominais permanece constante por página requisitada, independentemente do tamanho de página (`size`), eliminando consultas individuais por item.

### Índices Existentes Utilizados
* `idx_user_follows_follower` (`user_follows(follower_user_id)`): Permite resolução imediata dos seguidos pelo requester.
* `idx_user_follows_followed` (`user_follows(followed_user_id)`): Otimiza junções reversas e verificação de conexões.
* `idx_reviews_user_created_at` (`reviews(user_id, created_at DESC)`): Otimiza a ordenação cronológica por usuário.
* `idx_reviews_status_visibility` (`reviews(status, visibility)`): Filtra eficientemente os registros ativos e elegíveis.

Nenhuma nova migration Flyway foi necessária (Flyway Schema Version mantido em `7`).


---

# Documentação da API REST — Feed V2 com Relevância Determinística e Interesses (Step 24 / V40)

O **Feed V2** evolui o feed cronológico do Rewit para um pipeline de relevância explicável, auditável e transparente, combinando sinais sociais, afinidade suave com os interesses declarados no onboarding, recência temporal, verificação presencial e aprovação comunitária.

## 5. Princípios Arquiteturais do Feed V2

* **Identidade Segura e Exclusiva**: Autenticação via JWT (`SecurityContext`). Parâmetros que tentem inspecionar o feed de terceiros (e.g. `?userId=...`) são estritamente ignorados (proteção anti-IDOR).
* **Consumo Canônico de Interesses (V40)**:
  - Os interesses são recuperados diretamente da persistência (`UserInterestProfileRepository.findByUserId(requesterId)`). O feed **nunca** aceita parâmetros de interesses enviados pelo cliente HTTP.
  - A conversão entre categorias livres de alvos (`places.category`, `products.category`) e as constantes canônicas de `InterestCategory` é realizada deterministicamente pelo `TargetCategoryMapper`. Categorias não mapeadas (ex.: `"GERAL"`, `"OUTROS"`, nulo) recebem afinidade neutra (`0.0`).
* **Sinais e Pesos do Ranking V2 (Soma Exata = 1.0)**:

| Sinal | Peso | Descrição |
|---|---:|---|
| **Conexão Social Direta** | `0.35` | `1.0` se a avaliação é de autor seguido diretamente; `0.0` se proveniente da descoberta. |
| **Afinidade por Interesses** | `0.20` | `1.0` se a categoria canônica do alvo corresponde aos interesses do usuário; `0.0` caso contrário. |
| **Recência Temporal** | `0.25` | Decaimento exponencial suave sobre a idade da avaliação em relação ao instante de referência: $e^{-\lambda \cdot \Delta t}$. |
| **Verificação Presencial** | `0.10` | `1.0` se a avaliação foi realizada com confirmação presencial no local (`isVerifiedOnSite = true`); `0.0` caso contrário. |
| **Votos Úteis da Comunidade** | `0.10` | Saturação logarítmica normalizada sobre a contagem de reações úteis: $\frac{\ln(1 + helpfulCount)}{\ln(1 + 50)}$. |

* **Afinidade Suave (*Soft Boost*)**:
  - O sinal de afinidade eleva a posição dos conteúdos de interesse do usuário sem descartar candidatos de outros nichos.
  - Usuários sem questionário preenchido ou sem interesses cadastrados recebem afinidade `0.0` em todos os itens, preservando a ordenação factual sem qualquer degradação.
* **Diversificação Pura em Memória (`FeedV2Diversifier`)**:
  - Limita no máximo 2 avaliações consecutivas do mesmo autor (`MAX_CONSECUTIVE_AUTHOR = 2`).
  - Limita no máximo 2 avaliações consecutivas do mesmo alvo (`MAX_CONSECUTIVE_TARGET = 2`).
  - O diversificador atua estritamente após o ranking, garantindo variedade de autores e estabelecimentos no feed.
* **Desempate Determinístico**:
  - Ordem rigorosa: `score DESC`, `created_at DESC`, `review_id ASC`.

---

## 6. Endpoint do Feed V2

### 6.1 Obter Feed Personalizado Paginado
* **Método**: `GET`
* **Rota**: `/api/v2/feed`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta (Query Parameters):
| Parâmetro | Tipo | Padrão | Descrição | Restrições |
|---|---|---|---|---|
| `page` | Integer | `0` | Índice da página (0-indexed) | Deve ser `>= 0` (retorna `400 INVALID_PAGE` se negativo) |
| `size` | Integer | `10` | Quantidade de itens por página | Deve ser `>= 1` (retorna `400 INVALID_SIZE`) e `<= 50` (retorna `400 PAGE_SIZE_EXCEEDED`) |

#### Resposta de Sucesso (`200 OK`):
```json
{
  "items": [
    {
      "id": "7b0d2358-1f6e-4ab4-802b-3e5e7da09101",
      "author": {
        "id": "01944883-9366-71d3-a5c8-c672b1234567",
        "handle": "alice",
        "displayName": "Alice Cooper",
        "avatarUrl": "https://cdn.rewit.com/avatars/alice.png",
        "isAnonymous": false
      },
      "contextPlaceId": "c9287311-2e63-4414-87a3-e380e5b7c7b1",
      "targets": [
        {
          "targetId": "c9287311-2e63-4414-87a3-e380e5b7c7b1",
          "targetType": "PLACE",
          "name": "Café Central",
          "rating": 5.0,
          "comment": "Ambiente acolhedor e café especial excelente."
        }
      ],
      "experienceText": "Excelente experiência matinal.",
      "isAnonymous": false,
      "isVerifiedOnSite": true,
      "status": "ACTIVE",
      "visibility": "PUBLIC",
      "createdAt": "2026-10-28T12:00:00Z",
      "helpfulCount": 4,
      "isHelpfulByMe": true
    }
  ],
  "page": 0,
  "size": 10,
  "windowSize": 18,
  "totalPages": 2
}
```

*Nota de Privacidade e Segurança*: Nenhum sinal interno de pontuação (`score`), multiplicador de peso ou dados confidenciais de perfil é exposto na resposta HTTP do cliente.
