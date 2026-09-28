# API REST de Avaliações Multi-Alvo (docs/api/reviews.md)

Este documento especifica os contratos da API RESTful para publicação e consulta de avaliações multi-alvo (**Reviews**) do ecossistema **Rewit**, incorporando a verificação de presença física e emissão de check-in espacial (**Step 12.0**).

---

## 1. Arquitetura e Políticas de Segurança

A camada REST de Reviews segue rigorosamente a arquitetura hexagonal do projeto:

```text
ReviewController (presentation)
  -> ReviewService (application)
      -> Ports (ReviewRepository, CheckInRepository, ReviewTargetRepository, RateableTargetRepository, PlaceRepository, ProfileRepository)
          -> Adapters (ReviewRepositoryAdapter, CheckInRepositoryAdapter, etc.)
```

### 1.1 Autenticação e Prevenção de Spoofing (IDOR)
* O autor da avaliação (`user_id`) é extraído **exclusivamente do contexto de autenticação JWT** (`authentication.getName()`).
* Não é aceito `userId` no payload de requisição; tentativas de suplantar identidade são impedidas na fronteira do controller.
* O `user_id` do `CheckIn` é sempre idêntico ao `user_id` da `Review`.

### 1.2 Anonimização (`isAnonymous`)
* O vínculo relacional interno com a conta do usuário (`reviews.user_id`) é **sempre preservado** no banco de dados para integridade, auditoria e moderação.
* Quando `isAnonymous: true`:
  * O objeto `author` na representação pública não expõe `id`, `handle` ou `avatarUrl`.
  * O campo `displayName` é retornado como `"Anônimo"`.
  * `isAnonymous` é retornado como `true`.

### 1.3 Controle de Visibilidade (`visibility`)
* **`PUBLIC`**: A avaliação pode ser consultada por usuários autenticados.
* **`PRIVATE`**: Apenas o autor autenticado pode consultar o Review. Consultas por terceiros retornam `403 Forbidden`.
* **`FOLLOWERS`**: Como o subsistema de seguidores não está implementado neste step, consultas de terceiros retornam `403 Forbidden` com a mensagem `"Esta avaliação é visível apenas para seguidores"`. Apenas o próprio autor tem acesso garantido.

### 1.4 Privacidade e Minimização de Coordenadas (LGPD / ADR-005)
* **Localização Sob Demanda**: O usuário fornece suas coordenadas geográficas **explicitamente** no payload de criação. O backend nunca rastreia nem armazena trilhas contínuas de GPS.
* **Privacidade de Coordenadas**: As coordenadas precisas do usuário (`userLatitude`, `userLongitude`, `locationAccuracyMeters`) são dados sensíveis. Elas **NUNCA são expostas publicamente** na `ReviewResponse`. Apenas o fato verificado (`isVerifiedOnSite: true` ou `false`) é retornado pela API.

---

## 2. Regras de Negócio e Verificação de Presença Física (Check-in)

### 2.1 Avaliação Mínima para Check-in
* Conforme a **Regra 18 do `PROJECT_RULES.md`**, `PRODUCT_VISION.md` e `ADR-005`, para que a presença seja verificada e o check-in computado, o usuário deve atribuir no mínimo a nota por estrelas ao local ou serviço (através de pelo menos um `ReviewTarget` com `rating` entre 1.0 e 5.0), sendo o texto livre (`experienceText`) opcional.
* Não existe limiar de nota de corte arbitrário (ex: nota >= 3.0 ou 4.0). Qualquer avaliação válida com notas no intervalo permitido (1.0 a 5.0) é elegível para verificação presencial.

### 2.2 Quando o CheckIn é Criado
Um registro de `CheckIn` é criado e avaliado **exclusivamente** quando:
1. `contextPlaceId` estiver preenchido (não nulo);
2. Coordenadas (`userLatitude` e `userLongitude`) forem explicitamente enviadas pelo cliente.

### 2.3 Presença Verificada vs. Não Verificada
A validação espacial é executada no banco através do **PostgreSQL/PostGIS**, utilizando a função nativa `ST_DWithin` com o `validation_radius_meters` configurado para aquele estabelecimento específico e `ST_Distance` para cálculo métrico da distância até o centróide:

* **Presença Verificada (`isWithinRadius == true`)**:
  * `CheckIn.status`: `VERIFIED`
  * `CheckIn.verificationMethod`: `GPS`
  * `CheckIn.verifiedAt`: timestamp atual (`Instant.now()`)
  * `CheckIn.distanceToCentroidMeters`: distância real calculada em metros pelo PostGIS
  * `Review.isVerifiedOnSite`: `true`
* **Tentativa Não Verificada / Fora do Raio (`isWithinRadius == false`)**:
  * `CheckIn.status`: `REJECTED`
  * `CheckIn.verificationMethod`: `GPS`
  * `CheckIn.verifiedAt`: `null` (CheckIn rejeitado não possui data de verificação)
  * `CheckIn.distanceToCentroidMeters`: distância calculada excedente
  * `Review.isVerifiedOnSite`: `false`
* **Sem Coordenadas ou Sem `contextPlaceId`**:
  * A `Review` é criada normalmente.
  * Nenhum `CheckIn` é gerado.
  * `Review.isVerifiedOnSite`: `false`.

### 2.4 Fonte da Verdade de `isVerifiedOnSite`
* `CheckIn(status = VERIFIED)` é a **única fonte da verdade** para a presença confirmada no local.
* `Review.isVerifiedOnSite` é estritamente uma **projeção/cache de leitura**, sincronizada via agregado de domínio (`attachCheckIn`) e no banco de dados via triggers relacionais (`trg_sync_check_in_to_review_verified` e `trg_prevent_unverified_review_flag`).
* O cliente **não pode forjar** `isVerifiedOnSite`. O campo não é aceito no payload de entrada e qualquer valor malicioso é ignorado.

### 2.5 Agregação e Métricas de Alvos Avaliáveis (Step 13.0 / ADR-009)
* Na mesma transação `@Transactional` de publicação da Review, todos os alvos avaliados (`ReviewTargets`) têm suas estatísticas recalculadas e persistidas atomicamente em `rateable_target_stats`.
* **Serialização e Prevenção de Lost Update**: O sistema assegura a linha de estatística e adquire lock pessimista (`SELECT FOR UPDATE`) antes do recálculo agregado via SQL nativo (`ROUND(AVG(rating), 2)` e `COUNT(id)`).
* **Prevenção de Deadlocks**: Em publicações multi-alvo, a lista de `target_id`s é rigorosamente ordenada de forma determinística (ordem alfabética de UUID) antes de adquirir os locks.
* **Isolamento de Status**: Apenas publicações com `status = 'ACTIVE'` participam do cálculo. Reviews `UNDER_REVIEW` (denunciadas) ou `REMOVED` (excluídas) não distorcem as médias.
* **Avaliações Anônimas**: Publicações com `isAnonymous: true` computam normalmente para a média e contagem do alvo (o anonimato protege a identidade pública do autor, sem invalidar sua avaliação).
* **Peso Unitário**: Avaliações com ou sem presença física verificada possuem peso unitário idêntico (1).

---

## 3. Endpoints da API REST (`/api/v1/reviews` e `/api/v1/targets`)

Todos os endpoints utilizam JSON (`Content-Type: application/json;charset=UTF-8`), exigem Bearer Token JWT e seguem o padrão RFC 7807 (`ProblemDetail`) em caso de erro.

### 3.1 Criar Publicação de Avaliação Multi-Alvo (com Check-in opcional)
* **Método**: `POST`
* **Rota**: `/api/v1/reviews`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Campos da Requisição (`CreateReviewRequest`):
| Campo | Tipo | Obrigatório | Descrição / Validação |
|---|---|---|---|
| `contextPlaceId` | UUID | Não | ID do local físico de contexto da avaliação |
| `experienceText` | String | Não | Texto geral da experiência |
| `isAnonymous` | Boolean | Não | Publicar anonimamente (default: `false`) |
| `visibility` | String | Não | Visibilidade (`PUBLIC`, `PRIVATE`, `FOLLOWERS` - default: `PUBLIC`) |
| `userLatitude` | Double | Não | Latitude do usuário sob demanda (-90.0 a 90.0) |
| `userLongitude` | Double | Não | Longitude do usuário sob demanda (-180.0 a 180.0) |
| `locationAccuracyMeters` | Double | Não | Precisão reportada do GPS em metros (>= 0.0) |
| `targets` | Array | Sim | Pelo menos um alvo avaliado com nota |

#### Exemplo de Requisição (com Localização para Check-in):
```json
{
  "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "experienceText": "Excelente experiência no jantar de sexta-feira!",
  "isAnonymous": false,
  "visibility": "PUBLIC",
  "userLatitude": -25.4384,
  "userLongitude": -49.2849,
  "locationAccuracyMeters": 8.5,
  "targets": [
    {
      "rateableTargetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "rating": 5.0,
      "specificComment": "Atendimento e ambiente impecáveis."
    },
    {
      "rateableTargetId": "8f1a5b6c-3e2d-4f1a-b5c6-d7e8f9a0b1c2",
      "rating": 4.5,
      "specificComment": "Massa artesanal no ponto perfeito."
    }
  ]
}
```

#### Resposta (`201 Created` - Presença Confirmada)
* **Headers**: `Location: /api/v1/reviews/{id}`
```json
{
  "id": "e4b1a8d0-6f2c-4e1b-9a3d-5c7e8f9a0b1c",
  "author": {
    "id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
    "handle": "@maria_silva",
    "displayName": "Maria Silva",
    "avatarUrl": "https://cdn.rewit.app/avatars/maria.webp",
    "isAnonymous": false
  },
  "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "experienceText": "Excelente experiência no jantar de sexta-feira!",
  "isAnonymous": false,
  "isVerifiedOnSite": true,
  "visibility": "PUBLIC",
  "status": "ACTIVE",
  "createdAt": "2026-09-28T15:10:00Z",
  "updatedAt": "2026-09-28T15:10:00Z",
  "targets": [
    {
      "id": "3b2c1d0e-4f5a-6b7c-8d9e-0f1a2b3c4d5e",
      "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "rating": 5.0,
      "specificComment": "Atendimento e ambiente impecáveis.",
      "createdAt": "2026-09-28T15:10:00Z"
    },
    {
      "id": "7a8b9c0d-1e2f-3a4b-5c6d-7e8f9a0b1c2d",
      "targetId": "8f1a5b6c-3e2d-4f1a-b5c6-d7e8f9a0b1c2",
      "rating": 4.5,
      "specificComment": "Massa artesanal no ponto perfeito.",
      "createdAt": "2026-09-28T15:10:00Z"
    }
  ]
}
```

---

### 3.2 Consultar Publicação de Avaliação por ID
* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{id}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta (`200 OK`)
Retorna a representação pública da avaliação com alvos, notas, comentários, identificação do autor e o status `isVerifiedOnSite` (sem expor coordenadas do usuário).

#### Políticas de Visibilidade e Autorização (Step 15.0):
* **`PUBLIC`**: Visível para qualquer usuário autenticado.
* **`PRIVATE`**: Visível exclusivamente para o autor da avaliação. Terceiros (mesmo seguidores) recebem `403 Forbidden` (`FORBIDDEN`).
* **`FOLLOWERS`**: O acesso a publicações com visibilidade `FOLLOWERS` depende do relacionamento persistido entre requester e autor da Review (`user_follows`):
  * **Seguidor ativo**: Consegue visualizar a publicação (`200 OK`).
  * **Autor**: Sempre consegue visualizar sua própria publicação (`200 OK`).
  * **Não-seguidor**: Recebe `403 Forbidden` (`FORBIDDEN`).
  * **Anonimização**: Se `isAnonymous = true`, a identidade do autor permanece anônima (`id = null`, `handle = null`, `displayName = "Anônimo"`), mesmo que o requester seja um seguidor ativo.

```json
{
  "id": "e4b1a8d0-6f2c-4e1b-9a3d-5c7e8f9a0b1c",
  "author": {
    "id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
    "handle": "@maria_silva",
    "displayName": "Maria Silva",
    "avatarUrl": "https://cdn.rewit.app/avatars/maria.webp",
    "isAnonymous": false
  },
  "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "experienceText": "Excelente experiência no jantar de sexta-feira!",
  "isAnonymous": false,
  "isVerifiedOnSite": true,
  "visibility": "PUBLIC",
  "status": "ACTIVE",
  "createdAt": "2026-09-28T15:10:00Z",
  "updatedAt": "2026-09-28T15:10:00Z",
  "targets": [
    {
      "id": "3b2c1d0e-4f5a-6b7c-8d9e-0f1a2b3c4d5e",
      "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "rating": 5.0,
      "specificComment": "Atendimento e ambiente impecáveis.",
      "createdAt": "2026-09-28T15:10:00Z"
    }
  ]
}
```

---

### 3.3 Consultar Estatísticas de um Alvo Avaliável (Step 13.0)
* **Método**: `GET`
* **Rota**: `/api/v1/targets/{id}/stats`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta (`200 OK` - Com Avaliações Existentes)
```json
{
  "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "averageRating": 4.50,
  "reviewsCount": 12,
  "lastCalculatedAt": "2026-09-28T18:00:00Z"
}
```

#### Resposta (`200 OK` - Alvo Existente Sem Nenhuma Avaliação)
Quando o alvo existe no catálogo (`rateable_targets`), mas ainda não recebeu avaliações legítimas:
```json
{
  "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "averageRating": 0.00,
  "reviewsCount": 0,
  "lastCalculatedAt": null
}
```

> **Atenção sobre `reviewsCount` vs. Reviews Visíveis**: O cálculo de `RateableTargetStats` (Step 13.0) agrega todas as avaliações com status `ACTIVE`. Consequentemente, `RateableTargetStats.reviewsCount` pode ser maior que o total de elementos retornado pela listagem pública (`GET /api/v1/targets/{id}/reviews`). Isso ocorre porque avaliações com visibilidade `PRIVATE` ou `FOLLOWERS` participam das métricas consolidadas do alvo, mas são estritamente ocultadas de terceiros nas listagens públicas.

---

### 3.4 Listar Avaliações de um RateableTarget (Step 14.0)
* **Método**: `GET`
* **Rota**: `/api/v1/targets/{id}/reviews`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta (Query Params):
| Parâmetro | Tipo | Padrão | Validação / Descrição |
|---|---|---|---|
| `page` | Integer | `0` | Índice da página (>= 0) |
| `size` | Integer | `10` | Tamanho da página (> 0 e <= 50) |
| `sort` | String | `newest` | Ordenação permitida: `newest`, `rating_desc`, `rating_asc` |
| `verifiedOnly` | Boolean | `false` | Se `true`, retorna apenas avaliações com `isVerifiedOnSite = true` |

#### Regras de Ordenação e Multi-Alvo:
* Nas ordenações `rating_desc` e `rating_asc`, o critério de nota é **exclusivamente a nota contida em `review_targets` para o alvo `{id}` consultado**, garantindo ordenação correta mesmo quando uma mesma Review avalia múltiplos alvos com notas distintas.
* Desempate determinístico e estável entre páginas: `r.createdAt DESC, r.id ASC`.

#### Regras de Visibilidade e Anonimização:
* Reviews com `status` diferente de `ACTIVE` (`UNDER_REVIEW` e `REMOVED`) são rigorosamente omitidas da listagem pública.
* Avaliações com visibilidade `PUBLIC` são acessíveis publicamente.
* Avaliações com visibilidade `FOLLOWERS` (Step 15.0) dependem do relacionamento de seguidor persistido (`user_follows`): são exibidas exclusivamente para o próprio autor e para usuários autenticados que seguem o autor ativamente. Usuários não seguidores não têm acesso (403 no endpoint individual e omitidas na listagem por target).
* Avaliações com visibilidade `PRIVATE` permanecem restritas ao autor, mesmo que haja relação de seguidor.
* Quando `isAnonymous: true`, a identidade pública do autor é mascarada (`displayName: "Anônimo"` e campos `id`, `handle`, `avatarUrl` nulos). Coordenadas brutas nunca são expostas, mesmo para seguidores.
* O array `targets` de cada item da listagem reflete especificamente o alvo consultado.

#### Performance e Complexidade de Consultas:
* **Complexidade O(1) em relação ao `size` da página**: A busca do conteúdo e contagem é realizada em paginação nativa pelo banco via índice composto `idx_review_targets_target_rating`, e os perfis de autor são carregados em lote via query `IN (...)`. O número de queries SQL executadas por requisição paginada é constante (4 queries: verificação de existência, contagem total distinta, slice paginado e batch de perfis), garantindo ausência total de consultas N+1.

#### Exemplo de Resposta (`200 OK` - Envelope Paginado):
```json
{
  "content": [
    {
      "id": "e4b1a8d0-6f2c-4e1b-9a3d-5c7e8f9a0b1c",
      "author": {
        "id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "handle": "maria_silva",
        "displayName": "Maria Silva",
        "avatarUrl": "https://cdn.rewit.app/avatars/maria.webp",
        "isAnonymous": false
      },
      "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "experienceText": "Excelente experiência no jantar de sexta-feira!",
      "isAnonymous": false,
      "isVerifiedOnSite": true,
      "visibility": "PUBLIC",
      "status": "ACTIVE",
      "createdAt": "2026-09-28T15:10:00Z",
      "updatedAt": "2026-09-28T15:10:00Z",
      "targets": [
        {
          "id": "3b2c1d0e-4f5a-6b7c-8d9e-0f1a2b3c4d5e",
          "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
          "rating": 5.0,
          "specificComment": "Atendimento e ambiente impecáveis.",
          "createdAt": "2026-09-28T15:10:00Z"
        }
      ]
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

### 3.5 Listar Avaliações do Usuário Autenticado (Step 14.0)
* **Método**: `GET`
* **Rota**: `/api/v1/me/reviews`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Parâmetros de Consulta (Query Params):
| Parâmetro | Tipo | Padrão | Validação / Descrição |
|---|---|---|---|
| `page` | Integer | `0` | Índice da página (>= 0) |
| `size` | Integer | `10` | Tamanho da página (> 0 e <= 50) |

#### Regras de Segurança e Isolamento:
* O usuário é identificado **exclusivamente a partir do token JWT autenticado**, impossibilitando IDOR ou spoofing.
* Retorna o histórico de avaliações do próprio autor, permitindo visualizar suas publicações `PUBLIC` e `PRIVATE`.
* Ordenação determinística padrão por publicações mais recentes primeiro (`createdAt DESC, id ASC`).
* Inclui todos os alvos avaliados em cada publicação.

#### Performance e Complexidade de Consultas:
* **Complexidade O(1) em relação ao `size` da página**: A busca do histórico do usuário utiliza o índice `idx_reviews_user_created_at`, e os alvos de todas as avaliações da página são recuperados em lote via query `IN (...)`. O número de queries SQL é constante (4 queries: contagem do usuário, slice da página, batch de alvos e perfil do próprio autor), eliminando o problema de N+1.

#### Exemplo de Resposta (`200 OK`):
```json
{
  "content": [
    {
      "id": "e4b1a8d0-6f2c-4e1b-9a3d-5c7e8f9a0b1c",
      "author": {
        "id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "handle": "meu_usuario",
        "displayName": "Meu Nome",
        "avatarUrl": null,
        "isAnonymous": false
      },
      "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "experienceText": "Minha avaliação pessoal",
      "isAnonymous": false,
      "isVerifiedOnSite": false,
      "visibility": "PRIVATE",
      "status": "ACTIVE",
      "createdAt": "2026-09-28T16:00:00Z",
      "updatedAt": "2026-09-28T16:00:00Z",
      "targets": [
        {
          "id": "3b2c1d0e-4f5a-6b7c-8d9e-0f1a2b3c4d5e",
          "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
          "rating": 4.0,
          "specificComment": "Gostei bastante.",
          "createdAt": "2026-09-28T16:00:00Z"
        }
      ]
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

## 4. Tratamento de Erros e Códigos HTTP

Os erros seguem estritamente a especificação RFC 7807 (`ProblemDetail`):

| Código HTTP | Cenário | Código da Aplicação |
|---|---|---|
| `400 Bad Request` | Payload sintaticamente malformado, coordenadas inválidas, página negativa (`page < 0`), tamanho inválido (`size <= 0` ou `size > 50`) ou ordenação não suportada | `validation-error` / `INVALID_PAGE` / `INVALID_SIZE` / `PAGE_SIZE_EXCEEDED` / `INVALID_SORT` |
| `401 Unauthorized` | Requisição sem token JWT válido no header `Authorization` | N/A (Spring Security filter) |
| `403 Forbidden` | Tentativa de consultar Review com `visibility=PRIVATE` por usuário que não seja o autor, ou com `visibility=FOLLOWERS` por usuário que não seja seguidor ativo do autor nem o próprio autor | `FORBIDDEN` |
| `404 Not Found` | Review inexistente (`id` não encontrado), `contextPlaceId` inexistente ou alvo inexistente na consulta de stats ou reviews | `REVIEW_NOT_FOUND` / `PLACE_NOT_FOUND` / `RATEABLE_TARGET_NOT_FOUND` |
| `422 Unprocessable Entity` | Violação de regra de negócio do domínio: alvo duplicado no mesmo Review ou nota com mais de 1 casa decimal | `DUPLICATE_REVIEW_TARGET` / `INVALID_RATING_PRECISION` |
