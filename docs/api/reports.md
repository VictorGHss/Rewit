# Subsistema de Denúncias e Moderação Preventiva de Avaliações

O subsistema de denúncias comunitárias (**Reports**) estabelece a primeira camada de governança da plataforma Rewit, permitindo que a comunidade sinalize avaliações com conteúdo abusivo, falso ou inadequado e acione automaticamente a moderação preventiva sob um limiar determinístico.

---

## 1. Criar Denúncia de Avaliação

Registra uma denúncia estruturada contra uma avaliação que o usuário autenticado pode legitimamente visualizar.

* **Endpoint:** `POST /api/v1/reports`
* **Autenticação:** Obrigatória (`Bearer <JWT>`)
* **Headers:** `Content-Type: application/json`

### 1.1 Identidade e Privacidade do Denunciante (Anti-IDOR)

* A identidade do denunciante (`reporterUserId`) é inferida **exclusivamente do token JWT** validado no `SecurityContext`.
* O cliente **nunca** deve fornecer `reporterUserId`, `userId` ou e-mail no corpo da requisição ou parâmetros de URL.
* O sistema **nunca** expõe publicamente ou no response da API a identidade de quem denunciou (e-mail, ID ou perfil), preservando a integridade e privacidade do denunciante.

### 1.2 Payload da Requisição

```json
{
  "reviewId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reason": "SPAM",
  "detail": "Conteúdo repetitivo de cunho comercial não relacionado ao estabelecimento."
}
```

#### Campos

| Campo | Tipo | Obrigatório | Descrição / Regra |
|---|---|---|---|
| `reviewId` | UUID | Sim | Identificador da avaliação denunciada. |
| `reason` | Enum | Sim | Motivo padronizado da denúncia (ver tabela de motivos abaixo). |
| `detail` | String | Não | Detalhes contextuais adicionais. Máximo de 500 caracteres (com trim). |

### 1.3 Motivos de Denúncia Suportados (`ReportReason`)

| Motivo | Descrição |
|---|---|
| `SPAM` | Divulgação repetitiva, autopromoção descontextualizada ou bots. |
| `HARASSMENT` | Assédio, intimidação, difamação ou ataques direcionados. |
| `HATE_SPEECH` | Discurso de ódio contra grupos protegidos ou discriminação. |
| `MISINFORMATION` | Informações manifestamente falsas, inventadas ou fraudulentas. |
| `INAPPROPRIATE_CONTENT` | Conteúdo explícito, linguagem imprópria ou fora das diretrizes. |
| `FRAUD` | Avaliações pagas, contas falsas ou conflito de interesses financeiro. |

---

## 2. Ciclo de Vida e Status da Denúncia

* Toda nova denúncia é criada com status inicial `PENDING`.
* Os status `ACCEPTED` e `REJECTED` estão definidos no schema do banco de dados para evolução futura e não podem ser alterados por usuários comuns.

---

## 3. Regras de Acesso e Negócio

1. **Auto-denúncia (Self-Report):** O autor da avaliação não pode denunciar sua própria publicação. Retorna `400 Bad Request` com código `SELF_REPORT_FORBIDDEN`.
2. **Avaliação Inexistente ou Inativa:** Se a avaliação não existir ou já estiver nos status `UNDER_REVIEW` ou `REMOVED`, a requisição retorna `404 Not Found` com código `REVIEW_NOT_FOUND`.
3. **Visibilidade da Avaliação:**
   * **`PUBLIC`:** Qualquer usuário autenticado (exceto o próprio autor) pode denunciar.
   * **`FOLLOWERS`:** Apenas seguidores ativos e confirmados do autor podem denunciar. Usuários não seguidores recebem `403 Forbidden` (`FORBIDDEN`).
   * **`PRIVATE`:** Terceiros não possuem autorização de leitura e recebem `403 Forbidden` (`FORBIDDEN`).
4. **Idempotência:** A chave composta `(review_id, reporter_user_id)` é estritamente única no PostgreSQL. Enviar uma nova requisição idêntica pelo mesmo usuário retorna `200 OK` com os dados da denúncia existente, sem duplicar registros no banco.

---

## 4. Moderação Preventiva e Quarentena Automática

Para resguardar a comunidade contra abusos rápidos, o sistema adota uma regra determinística e atômica:

* **Limiar Determinístico:** **3 denúncias distintas com status `PENDING`** de usuários diferentes.
* **Transição Atômica:** Quando a 3ª denúncia válida é inserida sob lock transacional (`SELECT ... FOR UPDATE`), o status da avaliação transiciona atomicamente de `ACTIVE` para `UNDER_REVIEW`.
* **Efeitos Imediatos do status `UNDER_REVIEW`:**
  * Ocultação imediata da listagem pública por estabelecimento (`GET /api/v1/places/{id}/reviews`);
  * Remoção imediata da timeline do Feed Social dos seguidores (`GET /api/v1/feed`);
  * Bloqueio imediato para novos votos de utilidade (`POST /api/v1/reviews/{id}/helpful`);
  * Rejeição imediata de novas denúncias com `404 REVIEW_NOT_FOUND` (evita acúmulo de reports em conteúdo já suspenso);
  * Desconsideração automática no cálculo de notas e métricas de `TargetStats`.

---

## 5. Rate Limiting

Para coibir flood e ataques de negação de serviço na escrita social:

* **Mecanismo:** Janela deslizante em memória (`ReportRateLimiter`) com chaveamento pelo ID do usuário autenticado e rotina preventiva de eviction contra consumo excessivo de memória.
* **Limite:** Máximo de **10 denúncias por minuto** (janela deslizante de 60 segundos por usuário).
* **Resposta ao Exceder:** HTTP `429 Too Many Requests` com código `RATE_LIMIT_EXCEEDED`.
* **Limitação Arquitetural Conhecida:** Esta proteção opera em memória local do processo (JVM local). Em topologias de escalonamento horizontal com múltiplas instâncias da aplicação atrás de um balanceador de carga, o rate limiter deverá ser promovido para armazenamento compartilhado distribuído (ex: Redis).

---

## 6. Respostas da API

### Sucesso - Nova Denúncia Criada (`201 Created`)

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reviewId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reason": "SPAM",
  "status": "PENDING",
  "createdAt": "2026-09-30T14:15:00.000Z"
}
```

### Sucesso - Denúncia Já Existente / Idempotência (`200 OK`)

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reviewId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reason": "SPAM",
  "status": "PENDING",
  "createdAt": "2026-09-30T14:15:00.000Z"
}
```

### Erros Padronizados (RFC 7807)

* **401 Unauthorized:** Requisição sem token JWT válido.
* **400 Bad Request:**
  * Auto-denúncia (`code`: `SELF_REPORT_FORBIDDEN`);
  * Motivo inválido ou mal formatado (`code`: `MALFORMED_REQUEST`);
  * Detalhe excedendo 500 caracteres (`code`: `VALIDATION_ERROR`).
* **403 Forbidden:** Falta de autorização de leitura da publicação (`code`: `FORBIDDEN`).
* **404 Not Found:** Avaliação inexistente ou em quarentena (`code`: `REVIEW_NOT_FOUND`).
* **429 Too Many Requests:** Limite de denúncias por minuto excedido (`code`: `RATE_LIMIT_EXCEEDED`).
