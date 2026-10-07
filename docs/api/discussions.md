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
* Comentário em análise (`UNDER_REVIEW`) não pode ser excluído pelo autor: `409 DISCUSSION_UNDER_REVIEW_MUTATION_DENIED`. A quarentena só termina por decisão da moderação, que resolve as denúncias pendentes.

---

### 2.8 Thread por Leitor: Quarentena e Tombstone (C3 D2)

| Status interno | Terceiros | Próprio autor |
|---|---|---|
| `ACTIVE` | `VISIBLE` | `VISIBLE` |
| `UNDER_REVIEW` (raiz) | a conversa inteira some, inclusive as respostas | `PENDING_REVIEW`, sem respostas |
| `UNDER_REVIEW` (resposta) | some | `PENDING_REVIEW` dentro da raiz |
| `REMOVED` (raiz) | tombstone `REMOVED` sem conteúdo nem autor, **apenas se houver resposta visível**; as respostas ativas permanecem | igual a terceiros |

* O status interno de moderação (`ACTIVE`, `UNDER_REVIEW`, `REMOVED`) não é exposto na listagem; o tombstone nunca indica se a remoção foi do autor ou da moderação.
* Respostas não aparecem isoladas: a resposta de uma raiz em quarentena some junto com ela.

### 2.9 Denúncias e Auto-quarentena (C3 D1)

* Denúncias de discussões ficam em `discussion_reports` (V19), separadas das denúncias de avaliação: uma por usuário por discussão (`uq_discussion_report_reporter`), status `PENDING`, `ACCEPTED` ou `REJECTED`.
* A **terceira denúncia `PENDING` de usuários distintos** leva o comentário de `ACTIVE` para `UNDER_REVIEW` na mesma transação. A linha da discussão é travada (`FOR UPDATE`) antes de inserir e contar, então denúncias concorrentes são serializadas e a quarentena acontece exatamente uma vez.
* `UNDER_REVIEW` não sofre nova transição automática e `REMOVED` nunca volta para `UNDER_REVIEW`. A quarentena não altera reputação nem estatísticas, não gera punição nem notificação.
* O autor não denuncia o próprio comentário. Só comentários publicamente visíveis podem ser denunciados: `ACTIVE` e fora de uma conversa em quarentena (resposta a um comentário removido continua denunciável).
* Quem denuncia recebe sempre a mesma confirmação genérica, inclusive na denúncia repetida e na que coloca o comentário em análise. Um comentário indisponível responde `404 DISCUSSION_NOT_FOUND`, sem indicar o motivo.
* Limite de taxa: o mesmo das denúncias de avaliação (`REPORT_CREATION`, 10 por minuto por usuário). A conta precisa estar ativa (`401 ACCOUNT_DISABLED`).

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

### 3.2 Listar a Thread de Comentários de uma Avaliação
* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{reviewId}/discussions`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Parâmetros de Consulta**:
  * `page` (opcional, default `0`, min `0`): página de **comentários raiz**.
  * `size` (opcional, default `20`, min `1`, max `50`): raízes por página.
* **Ordenação**: determinística e cronológica (`created_at ASC, id ASC`), para raízes e respostas.
* **Estrutura**: o servidor monta a thread em dois níveis. Cada raiz traz as **3 primeiras respostas visíveis** em `replies`; `replyCount` é o total visível ao leitor e `hasMoreReplies` indica que o restante deve ser buscado em §3.4. O cliente não precisa reconstruir a árvore nem conhecer o status de moderação (§2.8).

#### Resposta de Sucesso (`200 OK`)
```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
      "parentId": null,
      "state": "VISIBLE",
      "content": "Excelente análise! Há opções vegetarianas no cardápio?",
      "author": {
        "id": "a8a088fc-a3bc-4edc-90aa-190e5a3b2b9b",
        "handle": "maria",
        "displayName": "Maria",
        "avatarUrl": null
      },
      "isFromOwner": false,
      "createdAt": "2026-09-30T11:50:00Z",
      "canReply": true,
      "canDelete": false,
      "replies": [
        {
          "id": "e7b0e271-bf31-48e0-a7d9-3617be349b1a",
          "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
          "parentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "state": "VISIBLE",
          "content": "Sim! Várias massas e risotos vegetarianos.",
          "author": null,
          "isFromOwner": true,
          "createdAt": "2026-09-30T12:00:00Z",
          "canReply": false,
          "canDelete": false
        }
      ],
      "replyCount": 1,
      "hasMoreReplies": false
    },
    {
      "id": "0b9d2a10-6a1c-4c6e-9d7e-4b2f1f0c9a11",
      "reviewId": "b1e9c520-22c6-4d7a-b5e0-82a945d8bfa7",
      "parentId": null,
      "state": "REMOVED",
      "content": null,
      "author": null,
      "isFromOwner": false,
      "createdAt": "2026-09-30T12:10:00Z",
      "canReply": false,
      "canDelete": false,
      "replies": [ { "...": "respostas visíveis preservadas" } ],
      "replyCount": 2,
      "hasMoreReplies": false
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 2,
  "totalPages": 1,
  "isLast": true
}
```

| Campo | Regra |
|---|---|
| `state` | `VISIBLE`, `REMOVED` (tombstone) ou `PENDING_REVIEW` (somente para o próprio autor) |
| `content` | `null` em `REMOVED` |
| `author` | `null` em `REMOVED` e quando o anonimato da avaliação mascara o dono (`isFromOwner = true` em avaliação anônima) |
| `isFromOwner` | `false` em `REMOVED` (o tombstone não revela que era do dono da avaliação) |
| `canReply` | `true` somente em raiz `VISIBLE` (um nível de resposta) |
| `canDelete` | `true` quando o leitor é o autor e o item está `VISIBLE` (em análise, só a moderação decide) |

*Execução*: número constante de consultas por página (raízes paginadas, primeiras respostas por raiz com `ROW_NUMBER()`, contagem agrupada e perfis em lote), sem N+1.

---

### 3.3 Remover Comentário (Soft Delete)
* **Método**: `DELETE`
* **Rota**: `/api/v1/discussions/{discussionId}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Regras:
1. Apenas o autor do comentário pode removê-lo (terceiro recebe `403 FORBIDDEN`).
2. A operação altera o status para `REMOVED` no banco, mantendo integridade com comentários filhos.
3. Operação idempotente.
4. Comentário em análise: `409 DISCUSSION_UNDER_REVIEW_MUTATION_DENIED` (terceiros continuam recebendo `403 FORBIDDEN`).

#### Resposta de Sucesso:
`204 No Content` (corpo vazio).

### 3.4 Listar Respostas de um Comentário Raiz
* **Método**: `GET`
* **Rota**: `/api/v1/discussions/{discussionId}/replies`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Parâmetros**: `page` (default `0`) e `size` (default `20`, max `50`).
* **Resposta**: página de itens no mesmo formato de `replies` em §3.2, com as mesmas regras de visibilidade.
* Raiz inexistente, que seja uma resposta ou que esteja em quarentena: `404 DISCUSSION_NOT_FOUND` (inclusive para o autor). Raiz removida continua listando as respostas visíveis.

### 3.5 Denunciar Comentário ou Resposta
* **Método**: `POST`
* **Rota**: `/api/v1/discussions/{discussionId}/reports`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Payload de Requisição
```json
{
  "reason": "HARASSMENT",
  "detail": "Texto opcional, até 500 caracteres"
}
```
`reason`: `SPAM`, `HARASSMENT`, `HATE_SPEECH`, `MISINFORMATION`, `INAPPROPRIATE_CONTENT` ou `FRAUD`.

#### Resposta de Sucesso (`202 Accepted`)
Idêntica para denúncia nova, repetida ou que coloca o comentário em análise:
```json
{
  "status": "RECEIVED",
  "message": "Denúncia recebida. Obrigado por ajudar a manter a comunidade segura."
}
```

---

## 4. Moderação Administrativa (MODERATOR / ADMIN)

Todas as rotas exigem token com role `MODERATOR` ou `ADMIN` (`401` sem token, `403` para `USER`). As visões administrativas expõem o status interno e o conteúdo, inclusive de comentários removidos.

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/admin/discussion-reports` | Fila de denúncias. Filtros: `status`, `reason`, `discussionId`; `page`, `size` (máx. 100), `sort` (`asc`/`desc` por `createdAt`). Cada item traz `reviewId`, `parentId`, `discussionAuthorUserId` e `discussionStatus` |
| `GET` | `/api/v1/admin/discussions/{discussionId}` | Contexto: `discussion` e `parent` (conteúdo e status), `pendingReportCount`, `reports` e `auditHistory` |
| `POST` | `/api/v1/admin/discussions/{discussionId}/moderate` | `{"action": "REMOVE_DISCUSSION" \| "RESTORE_DISCUSSION", "reasonCode": "...", "justification": "..."}` |

Regras da moderação (`ModerateDiscussionUseCase`, lock pessimista da discussão, transação única):
* Somente comentários `UNDER_REVIEW`: `REMOVE_DISCUSSION` leva a `REMOVED` e resolve as denúncias pendentes como `ACCEPTED`; `RESTORE_DISCUSSION` leva a `ACTIVE` e as resolve como `REJECTED`. Outro estado responde `409 DISCUSSION_NOT_UNDER_REVIEW`.
* O autor do comentário não o modera (`403 SELF_MODERATION_FORBIDDEN`) e quem o denunciou também não (`403 REPORTER_CANNOT_MODERATE`). A conta do moderador precisa estar ativa (`401 ACCOUNT_DISABLED`).
* `reasonCode` de até 64 caracteres; `justification` de 15 a 1000.
* Cada decisão grava um registro em `discussion_moderation_audit_logs` (V20), append-only: o banco rejeita `UPDATE` (`trg_prevent_discussion_moderation_audit_update`).
* Não altera reputação nem estatísticas e não gera notificação. Moderações concorrentes do mesmo comentário são serializadas: a segunda recebe `409`.

---

## 5. Tabela de Códigos de Erro (RFC 7807)

| Código HTTP | Código da Aplicação | Motivo / Cenário |
|---|---|---|
| `400 Bad Request` | `EMPTY_CONTENT` | Conteúdo nulo, vazio ou contendo apenas espaços em branco |
| `400 Bad Request` | `INVALID_CONTENT_LENGTH` | Conteúdo excedendo o limite de 2000 caracteres |
| `400 Bad Request` | `DISCUSSION_NESTING_LIMIT_EXCEEDED` | Tentativa de criar encadeamento de 2º nível (resposta a uma resposta) |
| `400 Bad Request` | `INVALID_PARENT_DISCUSSION` | O `parentId` informado pertence a outra Review |
| `400 Bad Request` | `SELF_REPORT_FORBIDDEN` | Autor tentando denunciar o próprio comentário |
| `400 Bad Request` | `INVALID_PAGE` / `INVALID_PAGE_SIZE` | Paginação com página negativa ou tamanho fora do intervalo [1, 50] |
| `401 Unauthorized` | `UNAUTHORIZED` | Ausência ou token JWT inválido/expirado |
| `403 Forbidden` | `FORBIDDEN` | Tentativa de comentar em Review `PRIVATE` de terceiro, Review `FOLLOWERS` sem ser seguidor, ou excluir comentário de outro usuário |
| `404 Not Found` | `REVIEW_NOT_FOUND` | Avaliação inexistente ou em status `UNDER_REVIEW` / `REMOVED` |
| `409 Conflict` | `DISCUSSION_UNDER_REVIEW_MUTATION_DENIED` | Autor tentando excluir o próprio comentário em análise |
| `404 Not Found` | `DISCUSSION_NOT_FOUND` | Comentário pai (`parentId`) ou comentário a deletar não encontrado ou já indisponível |
| `429 Too Many Requests` | `RATE_LIMIT_EXCEEDED` | Mais de 15 comentários criados em menos de 60 segundos pelo mesmo usuário |
