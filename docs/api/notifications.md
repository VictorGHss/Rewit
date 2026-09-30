# API REST de Notificações In-App (docs/api/notifications.md)

Este documento especifica os contratos, regras de governança, arquitetura e garantias de privacidade do subsistema de **Notificações In-App Persistidas** do **Rewit** (**Step 22.0**).

---

## 1. Princípios Arquiteturais e de Privacidade

### 1.1 Modelo In-App Baseado em PostgreSQL
O subsistema de notificações do Step 22.0 é estritamente **in-app e persistido no PostgreSQL**:
* Notificações são criadas e persistidas diretamente na tabela `notifications` como consequência atômica da operação de negócio originadora.
* **Não implementado neste MVP**: WebSockets, SSE, e-mail, push notification (FCM/APNS), mensageria distribuída (Kafka/RabbitMQ/Redis Streams) ou digest.

### 1.2 Integridade e Consistência Transacional (ACID)
* A persistência da notificação ocorre **dentro do mesmo fluxo transacional (`@Transactional`) da operação principal** (Follow, Helpful ou Discussion).
* **Consistência Estrita**: Se a operação de negócio falhar, a notificação sofre rollback automático (evita notificações fantasmas). Se a persistência da notificação falhar, a operação de negócio também sofre rollback completo, prevenindo estados inconsistentes no MVP síncrono.
* **Evolução Arquitetural Futura**: Em arquiteturas futuras de larga escala com mensageria assíncrona, este acoplamento síncrono poderá ser migrado para o padrão **Transactional Outbox**, garantindo entrega eventual desacoplada.

### 1.3 Preservação de Anonimato e Privacidade
Seguindo as diretrizes fundamentais de privacidade do Rewit:
1. **Reações Helpful**:
   * O votante é **estritamente anônimo**. O payload da notificação `REVIEW_HELPFUL` armazena `actorId = null`. O autor da avaliação recebe a mensagem genérica de que sua avaliação foi considerada útil, sem qualquer vazamento de identidade do autor do voto.
2. **Reviews Anônimas**:
   * Quando o autor de uma avaliação anônima (`isAnonymous = true`) responde a um comentário em sua avaliação (`DISCUSSION_REPLY`), o campo `actorId` é persistido e exposto como **`null`**, garantindo que a identidade real do proprietário anônimo nunca seja descoberta pelo autor do comentário.
3. **Ausência Absoluta de Notificações para Reports (Denúncias)**:
   * **Denúncias não geram notificações para nenhuma das partes.** O autor da avaliação denunciada nunca é notificado sobre denúncias, e o denunciante nunca é exposto, resguardando a segurança e o sigilo das ferramentas de governança comunitária (Step 19.0).
4. **Proteção Anti-IDOR**:
   * A identidade do destinatário é inferida **exclusivamente do token JWT** do contexto de segurança (`SecurityContextHolder`).
   * Não são aceitos parâmetros como `userId` no path ou no corpo de requisições.
   * Tentativas de marcar ou consultar notificações de outro usuário resultam em `404 Not Found` (`NOTIFICATION_NOT_FOUND`), prevenindo confirmação de existência.

---

## 2. Tipos de Notificação e Eventos Suportados

| Tipo (`NotificationType`) | Evento Originador | Destinatário | Actor Exposto (`actorId`) | Referência (`referenceId`) | Regra de Supressão |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`NEW_FOLLOWER`** | Usuário A segue Usuário B | Usuário B | ID do Usuário A | ID do Usuário A | Suprimido se $A = B$ ou se o follow for duplicado (idempotência). |
| **`REVIEW_HELPFUL`** | Usuário A marca Review de B como útil | Autor B da Review | `null` (anônimo) | ID da Review | Suprimido se $A = B$ (bloqueado por regra) ou se a reação for repetida. Remoção não gera notificação. |
| **`NEW_DISCUSSION`** | Usuário A comenta na Review de B (comentário raiz) | Autor B da Review | ID do Usuário A | ID da Review | Suprimido se $A = B$ (autor comentando na própria avaliação). |
| **`DISCUSSION_REPLY`** | Usuário A responde ao comentário de B | Autor B do comentário pai | ID de A (ou `null` se A for autor anônimo da review) | ID do novo comentário | Suprimido se $A = B$ (resposta ao próprio comentário) ou se comentário pai estiver removido. |

---

## 3. Endpoints da API REST

Base path: `/api/v1/me/notifications`  
Autenticação: **Obrigatória** (`Bearer <JWT>`)

### 3.1 Listar Notificações do Usuário
Recupera a lista paginada de notificações do usuário autenticado em ordem cronológica reversa fixa (`created_at DESC, id DESC`).

* **Método**: `GET`
* **Rota**: `/api/v1/me/notifications`
* **Query Parameters**:
  * `page` (opcional, padrão: `0`, mínimo: `0`)
  * `size` (opcional, padrão: `20`, mínimo: `1`, máximo: `50`)

#### Resposta de Sucesso (`200 OK`):
```json
{
  "content": [
    {
      "id": "7b58797f-1d4e-4f38-9ec1-3f48a1d7c34b",
      "type": "NEW_FOLLOWER",
      "actorId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "referenceId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "readAt": null,
      "createdAt": "2026-09-30T12:00:00Z"
    },
    {
      "id": "8c69808a-2e5f-4g49-0fd2-4g59b2e8d45c",
      "type": "REVIEW_HELPFUL",
      "actorId": null,
      "referenceId": "a12bc34d-56ef-7890-abcd-ef1234567890",
      "readAt": "2026-09-30T12:30:00Z",
      "createdAt": "2026-09-30T12:15:00Z"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 2,
  "totalPages": 1,
  "isLast": true
}
```

---

### 3.2 Quantidade de Notificações Não Lidas
Retorna o total agregado de notificações onde `read_at IS NULL` para o usuário autenticado.

* **Método**: `GET`
* **Rota**: `/api/v1/me/notifications/unread-count`

#### Resposta de Sucesso (`200 OK`):
```json
{
  "count": 1
}
```

---

### 3.3 Marcar Notificação Específica como Lida
Atualiza o timestamp de leitura `read_at` da notificação indicada. Operação atômica e estritamente idempotente.

* **Método**: `PATCH`
* **Rota**: `/api/v1/me/notifications/{notificationId}/read`

#### Resposta de Sucesso (`204 No Content`):
* Corpo vazio.
* Caso a notificação já estivesse lida, retorna `204` sem alterar o `read_at` original.
* Caso pertença a outro usuário ou não exista, retorna `404 Not Found` (`NOTIFICATION_NOT_FOUND`).

---

### 3.4 Marcar Todas as Notificações como Lidas
Executa atualização atômica em lote em uma única instrução SQL (`UPDATE notifications SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL`).

* **Método**: `PATCH`
* **Rota**: `/api/v1/me/notifications/read-all`

#### Resposta de Sucesso (`204 No Content`):
* Corpo vazio. Idempotente.

---

## 4. Códigos de Erro Padronizados (RFC 7807)

| Status HTTP | `code` | Descrição |
| :--- | :--- | :--- |
| `400 Bad Request` | `INVALID_PAGE` | O número da página informado é negativo (`page < 0`). |
| `400 Bad Request` | `INVALID_PAGE_SIZE` | O tamanho da página é menor que 1 (`size < 1`). |
| `400 Bad Request` | `PAGE_SIZE_EXCEEDED` | O tamanho da página ultrapassa o limite máximo permitido de 50 itens (`size > 50`). |
| `400 Bad Request` | `MISSING_NOTIFICATION_ID` | Identificador UUID da notificação ausente ou mal formatado. |
| `401 Unauthorized` | `UNAUTHORIZED` | Token JWT ausente, expirado ou inválido. |
| `404 Not Found` | `NOTIFICATION_NOT_FOUND` | Notificação inexistente ou pertencente a outro usuário (defesa anti-IDOR). |

---

## 5. Garantias Operacionais e de Segurança (Step 22.1)

### 5.1 Atomicidade Transacional Comprovada

Todos os eventos de notificação são **síncronos e participam da mesma transação do evento originador**:

| Serviço              | Método `@Transactional` | Ponto de disparo da Notification                  |
| :------------------- | :---------------------- | :------------------------------------------------ |
| `UserFollowService`  | `followUser()`          | Após `userFollowRepository.follow()` retornar `true` |
| `ReviewHelpfulService` | `addHelpful()`        | Após `reviewReactionRepository.addHelpful()` retornar `true` |
| `DiscussionService`  | `createDiscussion()`    | Após `discussionRepository.save()` persistir o comentário |

**Garantia comprovada em testes de rollback real** (`NotificationRollbackIntegrationTest`):
- Se a transação for revertida após a persistência da Notification → Follow, Helpful, Discussion, Reply e a Notification são todos revertidos atomicamente.
- Se a operação originadora falhar antes da chamada ao `NotificationService` → nenhuma Notification é criada.
- Não existe self-invocation, `REQUIRES_NEW`, nem captura silenciosa de exceções que pudesse quebrar o acoplamento.

**Evolução futura**: em volumes maiores, o acoplamento síncrono poderá ser substituído pelo padrão **Transactional Outbox** com mensageria assíncrona. **Isso ainda não está implementado.**

### 5.2 Estrutura do `metadata_json`

O campo `metadata_json` contém **exclusivamente** dados necessários para renderização e navegação no cliente:

| Campo              | Presente em                                    | Observações                                         |
| :----------------- | :--------------------------------------------- | :-------------------------------------------------- |
| `actorId`          | `NEW_FOLLOWER`, `NEW_DISCUSSION`, `DISCUSSION_REPLY` | `null` em `REVIEW_HELPFUL` e em review anônima     |
| `referenceId`      | Todos os tipos                                 | ID da Review, Follow ou Discussion conforme o tipo  |
| `discussionId`     | `NEW_DISCUSSION`                               | ID do comentário raiz criado                        |
| `reviewId`         | `DISCUSSION_REPLY`                             | ID da Review à qual o comentário pertence           |

**Nunca armazenado em `metadata_json`**:
- E-mail, senha, token JWT ou session ID
- IP de origem
- Conteúdo integral do comentário ou da Review
- `reporterUserId` (Reports não geram notificações)
- Object key ou URL interna do SeaweedFS

### 5.3 Índices de Banco de Dados

O índice `idx_notifications_user_unread(user_id, read_at)` cobre:
- `GET /api/v1/me/notifications/unread-count` → filtragem por `user_id` + `read_at IS NULL`
- `GET /api/v1/me/notifications` → filtragem por `user_id` (ordenação `created_at DESC, id DESC` potencialmente beneficiada por índice composto futuro)

Para volumes maiores, um índice composto em `(user_id, created_at DESC, id DESC)` pode otimizar a listagem paginada. **A criação de V10 somente será justificada por volume real medido em produção.**

### 5.4 Rate Limiting

`POST /api/v1/me/notifications` não existe como endpoint público. O disparo é sempre interno.  
As operações que geram notificações (`discussions`, `helpful`) possuem seus próprios rate limiters process-local documentados em seus respectivos módulos.

