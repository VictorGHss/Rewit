# API REST de Discussões e Comentários de Avaliações (docs/api/discussions.md)

Este documento especifica os contratos da API RESTful para comentários e respostas associados a avaliações (**Discussions/Comments**) do ecossistema **Rewit** (**Step 20.0**).

---

## 1. Visão Geral e Arquitetura

O sistema de discussões viabiliza a interação comunitária factual diretamente associada a uma publicação de avaliação (`Review`).

A implementação adota a arquitetura em camadas do projeto:

```text
DiscussionController (presentation)
  -> DiscussionService (application)
      -> ReviewVisibilityPolicy (application)
      -> DiscussionRateLimiter (infrastructure/security)
      -> DiscussionRepository (port)
          -> DiscussionRepositoryAdapter (infrastructure/persistence)
              -> DiscussionJpaRepository (Spring Data JPA)
                  -> review_discussions (PostgreSQL 18.6)
```

### 1.1 Premissas e Escopo
* Discussões pertencem **exclusivamente a uma Review**.
* **Não implementado neste incremento**: comentários sem Review (Place/Product/Service avulsos), chat privado, menções, hashtags, reações/likes em comentários, edição de comentários, denúncias ou moderação administrativa de comentários, notificações push ou rankings algorítmicos.
* O PostgreSQL é o **source of truth** relacional, utilizando a tabela `review_discussions` com integridade de status tipada via enum `DiscussionStatus` (`ACTIVE`, `UNDER_REVIEW`, `REMOVED`), constraint de validação `chk_review_discussions_status` e índice parcial `idx_review_discussions_active_listing` adicionados na migration `V18__discussion_status_integrity.sql`.
* O estado `UNDER_REVIEW` está modelado no domínio e no schema para suportar a futura moderação (C3), sem alteração de visibilidade pública atual (apenas comentários `ACTIVE` são listados publicamente).

---

## 2. Regras de Negócio e Segurança

### 2.1 Autenticação e Prevenção Anti-IDOR
* Todos os endpoints exigem autenticação obrigatória via Bearer Token JWT.
* A identidade do autor (`authorId` / `user_id`) é extraída **exclusivamente do token JWT validado**.
* Qualquer tentativa do cliente de injetar `authorId`, `userId` ou forjar identidade no payload JSON é sumariamente descartada.

### 2.2 Projeção Derivada de `isFromOwner`
* O campo booleano `isFromOwner` é uma projeção calculada exclusivamente no backend:
  $$\text{isFromOwner} = (\text{discussion.authorUserId} == \text{review.authorUserId})$$
* O cliente **nunca pode enviar ou alterar `isFromOwner`**. Qualquer campo similar no corpo da requisição é ignorado.

### 2.3 Threading e Limite de Profundidade (Nesting Limit)
* Se `parentId == null`: o comentário é **raiz**.
* Se `parentId != null`:
  1. O comentário pai deve existir e estar com status `ACTIVE` na mesma `reviewId`;
  2. O comentário pai não pode pertencer a outra Review (retorna `400 INVALID_PARENT_DISCUSSION`);
  3. **Limite de Profundidade**: adota-se exatamente **uma camada de resposta**:
     * Comentário raiz $\rightarrow$ permitido;
     * Resposta a comentário raiz $\rightarrow$ permitida;
     * Resposta a uma resposta (2º nível) $\rightarrow$ rejeitada com código estável **`DISCUSSION_NESTING_LIMIT_EXCEEDED`** (`400 Bad Request`).

### 2.4 Política de Visibilidade e Acesso (`ReviewVisibilityPolicy`)
A permissão para criar ou visualizar discussões deriva estritamente da visibilidade da Review:
* **`PUBLIC`**: Qualquer usuário autenticado pode comentar e visualizar;
* **`FOLLOWERS`**: Apenas o autor da Review ou seguidores mútuos/ativos confirmados podem comentar e visualizar (terceiros recebem `403 FORBIDDEN`);
* **`PRIVATE`**: Apenas o próprio autor da Review pode comentar e visualizar (terceiros recebem `403 FORBIDDEN`).

### 2.5 Tratamento de Reviews em `UNDER_REVIEW` e `REMOVED`
* Uma Review que atingir moderação preventiva (`UNDER_REVIEW` via Reports) ou for removida (`REMOVED`):
  * Deixa imediatamente de aceitar novos comentários (`404 REVIEW_NOT_FOUND`);
  * Deixa imediatamente de listar seus comentários (`404 REVIEW_NOT_FOUND`);
  * O estado interno não é vazado ao cliente, mantendo consistência com as regras de conteúdo indisponível.

### 2.6 Rate Limiting Process-Local
* Para mitigar automação e spam, a criação de comentários é controlada pelo `DiscussionRateLimiter` em memória (process-local), operando com janela deslizante de 60 segundos com no máximo 15 requisições por usuário.
* Ao exceder, a API retorna `429 Too Many Requests` com código `RATE_LIMIT_EXCEEDED`.
* A leitura (`GET`) não possui rate limiting.

### 2.7 Remoção e Soft Delete
* Apenas o autor original do comentário tem permissão para excluí-lo (`DELETE /api/v1/discussions/{discussionId}`). Terceiros recebem `403 FORBIDDEN`.
* A exclusão é um **soft delete**, alterando o `status` para `REMOVED`. A linha física não é removida para preservar a integridade referencial da árvore de `parent_id`.
* Operação **idempotente**: repetir a exclusão de um comentário já `REMOVED` retorna `204 No Content` com sucesso.

---

## 3. Endpoints da API

### 3.1 Criar Comentário ou Resposta
* **Método**: `POST`
* **Rota**: `/api/v1/reviews/{reviewId}/discussions`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Payload de Requisição
```json
{
  "content": "Excelente análise! Você saberia me dizer se há opções vegetarianas no cardápio?",
  "parentId": null
}
```

Para responder a um comentário existente:
```json
{
  "content": "Sim! Eles têm várias opções de massas e risotos vegetarianos excelentes.",
  "parentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

#### Resposta de Sucesso (`201 Created`)
```json
{
  "id": "e7b0e271-bf31-48e0-a7d9-3617be349b1a",
  "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
  "authorId": "123e4567-e89b-12d3-a456-426614174000",
  "parentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "content": "Sim! Eles têm várias opções de massas e risotos vegetarianos excelentes.",
  "isFromOwner": true,
  "status": "ACTIVE",
  "createdAt": "2026-09-30T12:00:00Z"
}
```

---

### 3.2 Listar Comentários de uma Avaliação
* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{reviewId}/discussions`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Parâmetros de Consulta**:
  * `page` (opcional, default `0`, min `0`): número da página.
  * `size` (opcional, default `20`, min `1`, max `50`): quantidade de elementos por página.
* **Ordenação**: Determinística e estritamente cronológica (`created_at ASC, id ASC`).

#### Resposta de Sucesso (`200 OK`)
```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
      "authorId": "a8a088fc-a3bc-4edc-90aa-190e5a3b2b9b",
      "parentId": null,
      "content": "Excelente análise! Você saberia me dizer se há opções vegetarianas no cardápio?",
      "isFromOwner": false,
      "status": "ACTIVE",
      "createdAt": "2026-09-30T11:50:00Z"
    },
    {
      "id": "e7b0e271-bf31-48e0-a7d9-3617be349b1a",
      "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
      "authorId": "123e4567-e89b-12d3-a456-426614174000",
      "parentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "content": "Sim! Eles têm várias opções de massas e risotos vegetarianos excelentes.",
      "isFromOwner": true,
      "status": "ACTIVE",
      "createdAt": "2026-09-30T12:00:00Z"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 2,
  "totalPages": 1,
  "isLast": true
}
```

*Nota de Performance e Execução*:
* A listagem de discussões utiliza um **número constante de queries por requisição** (exatamente 1 consulta paginada de dados e 1 consulta agregada de `count`), eliminando qualquer risco de N+1 para resolução de autores.
* A ordenação determinística (`created_at ASC, id ASC`) e a paginação são delegadas diretamente ao motor do PostgreSQL.
* O tempo de resposta efetivo é determinado pelo plano de execução gerado pelo PostgreSQL, pela cardinalidade de comentários por avaliação e pelos índices disponíveis.
* **Auditoria de Índices**: Os índices `idx_review_discussions_review` (em `review_id`) e `idx_review_discussions_parent` (em `parent_id`) da V1 atendem com folga às operações do MVP. Como evolução futura para avaliações com dezenas de milhares de comentários, pode-se avaliar um índice composto `(review_id, status, created_at, id)`.
* **Privacidade e Anonimato**: Se a Review for anônima (`isAnonymous = true`) e o comentário for publicado pelo autor da avaliação (`isFromOwner = true`), o campo `authorId` retornado na resposta pública é mascarado para `null`. O campo `isFromOwner` permanece `true` para preservar a semântica de resposta do proprietário da avaliação sem revelar sua identidade.

---

### 3.3 Remover Comentário (Soft Delete)
* **Método**: `DELETE`
* **Rota**: `/api/v1/discussions/{discussionId}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Regras:
1. Apenas o autor do comentário pode removê-lo (terceiro recebe `403 FORBIDDEN`).
2. A operação altera o status para `REMOVED` no banco, mantendo integridade com comentários filhos.
3. Operação idempotente.

#### Resposta de Sucesso:
`204 No Content` (corpo vazio).

---

## 4. Tabela de Códigos de Erro (RFC 7807)

| Código HTTP | Código da Aplicação | Motivo / Cenário |
|---|---|---|
| `400 Bad Request` | `EMPTY_CONTENT` | Conteúdo nulo, vazio ou contendo apenas espaços em branco |
| `400 Bad Request` | `INVALID_CONTENT_LENGTH` | Conteúdo excedendo o limite de 2000 caracteres |
| `400 Bad Request` | `DISCUSSION_NESTING_LIMIT_EXCEEDED` | Tentativa de criar encadeamento de 2º nível (resposta a uma resposta) |
| `400 Bad Request` | `INVALID_PARENT_DISCUSSION` | O `parentId` informado pertence a outra Review |
| `400 Bad Request` | `INVALID_PAGE` / `INVALID_PAGE_SIZE` | Paginação com página negativa ou tamanho fora do intervalo [1, 50] |
| `401 Unauthorized` | `UNAUTHORIZED` | Ausência ou token JWT inválido/expirado |
| `403 Forbidden` | `FORBIDDEN` | Tentativa de comentar em Review `PRIVATE` de terceiro, Review `FOLLOWERS` sem ser seguidor, ou excluir comentário de outro usuário |
| `404 Not Found` | `REVIEW_NOT_FOUND` | Avaliação inexistente ou em status `UNDER_REVIEW` / `REMOVED` |
| `404 Not Found` | `DISCUSSION_NOT_FOUND` | Comentário pai (`parentId`) ou comentário a deletar não encontrado ou já indisponível |
| `429 Too Many Requests` | `RATE_LIMIT_EXCEEDED` | Mais de 15 comentários criados em menos de 60 segundos pelo mesmo usuário |
