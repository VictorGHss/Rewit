# API REST de Avaliações Multi-Alvo (docs/api/reviews.md)

Este documento especifica os contratos da API RESTful inicial para publicação e consulta de avaliações multi-alvo (**Reviews**) do ecossistema **Rewit** (Step 11.0).

---

## 1. Arquitetura e Políticas de Segurança

A camada REST de Reviews segue rigorosamente a arquitetura hexagonal do projeto:

```text
ReviewController (presentation)
  -> ReviewService (application)
      -> Ports (ReviewRepository, ReviewTargetRepository, RateableTargetRepository, PlaceRepository, ProfileRepository)
          -> Adapters (ReviewRepositoryAdapter, etc.)
```

### 1.1 Autenticação e Prevenção de Spoofing (IDOR)
* O autor da avaliação (`user_id`) é extraído **exclusivamente do contexto de autenticação JWT** (`authentication.getName()`).
* Não é aceito `userId` no payload de requisição; tentativas de suplantar identidade são impedidas na fronteira do controller.

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

---

## 2. Endpoints da API REST (`/api/v1/reviews`)

Todos os endpoints utilizam JSON (`Content-Type: application/json;charset=UTF-8`), exigem Bearer Token JWT e seguem o padrão RFC 7807 (`ProblemDetail`) em caso de erro.

### 2.1 Criar Publicação de Avaliação Multi-Alvo
* **Método**: `POST`
* **Rota**: `/api/v1/reviews`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Regras de Validação:
* Pelo menos um alvo avaliado (`targets`) obrigatório.
* Não é permitido avaliar o mesmo alvo mais de uma vez no mesmo Review.
* Cada nota (`rating`) deve ser entre `1.0` e `5.0`, com no máximo 1 casa decimal.
* Caso `contextPlaceId` seja fornecido, o local deve existir no catálogo (caso contrário, retorna `404 Not Found`).
* Todos os alvos referenciados em `targets` devem existir no catálogo (`rateable_targets`).
* O status inicial é sempre `ACTIVE`.

#### Requisição
```json
{
  "contextPlaceId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
  "experienceText": "Excelente experiência no jantar de sexta-feira!",
  "isAnonymous": false,
  "visibility": "PUBLIC",
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

#### Resposta (`201 Created`)
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

### 2.2 Consultar Publicação de Avaliação por ID
* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{id}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Resposta (`200 OK`)
Retorna a representação pública da avaliação com alvos, notas, comentários e identificação do autor (compatível com a regra de anonimização e visibilidade).

Exemplo de resposta quando `isAnonymous: true`:
```json
{
  "id": "e4b1a8d0-6f2c-4e1b-9a3d-5c7e8f9a0b1c",
  "author": {
    "id": null,
    "handle": null,
    "displayName": "Anônimo",
    "avatarUrl": null,
    "isAnonymous": true
  },
  "contextPlaceId": null,
  "experienceText": "Avaliação anônima sincera.",
  "isAnonymous": true,
  "visibility": "PUBLIC",
  "status": "ACTIVE",
  "createdAt": "2026-09-28T15:15:00Z",
  "updatedAt": "2026-09-28T15:15:00Z",
  "targets": [
    {
      "id": "3b2c1d0e-4f5a-6b7c-8d9e-0f1a2b3c4d5e",
      "targetId": "c9b2f6b3-5b87-43cf-bc82-d27a4d5e8654",
      "rating": 4.0,
      "specificComment": "Bom café espresso.",
      "createdAt": "2026-09-28T15:15:00Z"
    }
  ]
}
```

---

## 3. Tratamento de Erros e Códigos HTTP

Os erros seguem estritamente a especificação RFC 7807 (`ProblemDetail`):

| Código HTTP | Cenário | Código da Aplicação |
|---|---|---|
| `400 Bad Request` | Payload sintaticamente malformado, rating fora do intervalo (1.0 - 5.0) via Bean Validation ou lista de alvos vazia | `validation-error` |
| `401 Unauthorized` | Requisição sem token JWT válido no header `Authorization` | N/A (Spring Security filter) |
| `403 Forbidden` | Tentativa de consultar Review com `visibility=PRIVATE` ou `visibility=FOLLOWERS` por usuário que não seja o autor | `FORBIDDEN` |
| `404 Not Found` | Review inexistente (`id` não encontrado) ou `contextPlaceId` inexistente | `REVIEW_NOT_FOUND` / `PLACE_NOT_FOUND` |
| `422 Unprocessable Entity` | Violação de regra de negócio do domínio: alvo duplicado no mesmo Review ou nota com mais de 1 casa decimal | `DUPLICATE_REVIEW_TARGET` / `INVALID_RATING_PRECISION` |

