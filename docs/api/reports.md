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

## 2. Ciclo de Vida, Status da Denúncia e Moderação Administrativa

* Toda nova denúncia é criada com status inicial `PENDING`.
* Os status `ACCEPTED` e `REJECTED` não podem ser alterados por usuários comuns e são resolvidos atomicamente em lote pelos endpoints administrativos de moderação (`MODERATOR` / `ADMIN` validados via `@PreAuthorize` e em tempo real por `ModeratorRolePolicy`):
  * **`GET /api/v1/admin/reports`**: fila paginada de triagem administrativa de denúncias com filtros opcionais por `status`, `reason`, `reviewId`, `reporterUserId` e ordenação (`sort`).
  * **`GET /api/v1/admin/reviews/{reviewId}/context`**: consulta somente leitura do contexto completo de moderação da avaliação (conteúdo, status interno, alvos, denúncias vinculadas e histórico de auditoria, sem expor identidades).
  * **`POST /api/v1/admin/reviews/{reviewId}/moderate`**: executa decisão formal auditada (`ModerationAuditLog` append-only) exigindo `action`, `reasonCode` (até 64 caracteres) e `justification` (15 a 1.000 caracteres), bloqueando auto-moderação (`SELF_MODERATION_FORBIDDEN`) e conflito de interesse quando o moderador possui denúncia pendente (`REPORTER_CANNOT_MODERATE`):
    * `REMOVE_REVIEW`: transiciona a avaliação (`ACTIVE` ou `UNDER_REVIEW`) para `REMOVED`, resolve todas as denúncias `PENDING` vinculadas como `ACCEPTED`, marca mídias ativas como `REMOVED` e recalcula estatísticas dos alvos (`RateableTargetStats`) e reputação do autor (`UserReputation`).
    * `RESTORE_REVIEW`: restaura a avaliação de `UNDER_REVIEW` para `ACTIVE`, resolve todas as denúncias `PENDING` vinculadas como `REJECTED` e recalcula as estatísticas dos alvos e a reputação do autor.

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

## 5. Rate Limiting Distribuído

Para coibir flood e ataques de negação de serviço na escrita social:

* **Mecanismo:** Janela deslizante distribuída no Redis (`ResilientRateLimiter` + `RedisRateLimitStore`) para a ação `RateLimitedAction.REPORT_CREATION`, com chaves derivadas por HMAC (`RateLimitKeyDeriver`) a partir do ID do usuário autenticado (`RateLimitSubject.ofUser(reporterUserId)`).
* **Resiliência e Fallback:** Caso o Redis fique temporariamente indisponível, o `ResilientRateLimiter` opera em modo degradado configurável (`LOCAL_FALLBACK` via `LocalRateLimitStore` em memória com teto de chaves), retomando o Redis automaticamente após o intervalo de retry (`rewit.rate-limit.backend.retry-interval`).
* **Limite:** Máximo de **10 denúncias em janela deslizante de 60 segundos** por usuário autenticado (`rewit.rate-limit.content.report-creation`).
* **Resposta ao Exceder:** HTTP `429 Too Many Requests` (RFC 7807) com código `RATE_LIMIT_EXCEEDED` e cabeçalho HTTP `Retry-After` informando os segundos restantes de espera.

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

* **401 Unauthorized:** Requisição sem token JWT válido (`AUTHENTICATION_REQUIRED` / `UNAUTHORIZED`) ou conta inoperante (`ACCOUNT_DISABLED`).
* **400 Bad Request:**
  * Auto-denúncia (`code`: `SELF_REPORT_FORBIDDEN`);
  * Motivo inválido ou mal formatado (`code`: `MALFORMED_REQUEST`);
  * Detalhe excedendo 500 caracteres (`code`: `VALIDATION_ERROR`).
* **403 Forbidden:** Falta de autorização de leitura da publicação (`code`: `FORBIDDEN`).
* **404 Not Found:** Avaliação inexistente ou em quarentena (`code`: `REVIEW_NOT_FOUND`).
* **429 Too Many Requests:** Limite de denúncias por minuto excedido (`code`: `RATE_LIMIT_EXCEEDED`, acompanhado do cabeçalho `Retry-After`).

---

## 7. Consumo no Cliente Mobile (`apps/mobile`)

O aplicativo Flutter consome `POST /api/v1/reports` por meio do módulo dedicado `apps/mobile/lib/features/reports/`:

* **Repositório e Transporte (`ReportRepository` / `ReportRepositoryImpl`)**:
  * Utiliza `RewitHttpClient` (`requiresAuth: true`) e `TokenRefreshingClient`, preservando a injeção de `Bearer JWT`, renovação transparente de sessão em `401 AUTHENTICATION_REQUIRED` e propagação estruturada via `onSessionExpiredWithDetails` (`SessionExpirationDetails` com fallback legado para `onSessionExpired`);
  * Envia apenas `reviewId`, `reason` (código da enumeração `ReportReason`) e `detail` opcional (com `trim()`, omitido quando vazio e limitado a 500 caracteres), sem jamais enviar `reporterUserId`;
  * Distingue explicitamente pelo status HTTP real entre nova denúncia criada (`201 Created` → `ReviewReportOutcome.created`) e denúncia previamente registrada pela mesma conta (`200 OK` → `ReviewReportOutcome.alreadyReported`), expondo `ReportStatus` (`PENDING`, `ACCEPTED`, `REJECTED` e fallback `unknown`) em `ReviewReportReceipt.reportStatus`.
* **Interface e Experiência (`ReviewDetailScreen` & `ReportReviewDialog`)**:
  * A ação contextual **Denunciar avaliação** segue regra *fail-closed* e sem confiança em sinais contraditórios de autoria: só é exibida para avaliações `ACTIVE` com `ReportRepository` disponível e não autoria comprovada (`isMine == false` sem contradição com `widget.isAuthor == true` ou `author.id == currentUserId` em avaliações públicas; ou, quando `isMine == null`, exclusivamente para avaliações públicas não anônimas com `author.id` e `currentUserId` válidos e distintos), mantendo-se oculta para avaliações anônimas ou com autoria indeterminada ou contraditória (`widget.isAuthor == false` isolado nunca comprova não autoria);
  * O diálogo `ReportReviewDialog` apresenta os seis motivos em português, campo opcional de detalhes (até 500 caracteres), bloqueio contra submissão concorrente, `PopScope` durante o envio e integração opcional com `AuthNotifier` (`sessionCheckSequence` / `sessionCheckSince()`);
  * Trata diferenciadamente `201 Created` (confirmação de envio para análise), `200 OK` conforme `ReportStatus` real (`PENDING` aguardando análise, `ACCEPTED` já processada pela moderação, `REJECTED` e valores desconhecidos com aviso neutro sem prometer análise pendente), `400 SELF_REPORT_FORBIDDEN`, `400 VALIDATION_ERROR`/`MALFORMED_REQUEST`, `401 ACCOUNT_DISABLED` (delega integralmente ao fluxo global `AuthNotifier.handleSessionExpired(details)`: mantém o formulário inerte sem `Navigator.pop()` local, sem banner local contraditório e sem acionar refresh, aguardando o `RewitApp` limpar os tokens e desempilhar até `LoginScreen` com mensagem neutra e sem reativação social), `401` de sessão expirada coordenado com `AuthNotifier.sessionCheckSince()` (`rejected` mantém o formulário bloqueado sem `Navigator.pop()` local aguardando o desempilhamento global de `RewitApp`; `refreshed` e `unavailable` preservam os dados preenchidos e liberam o formulário para nova tentativa), `403 FORBIDDEN`, `404 REVIEW_NOT_FOUND` (fechando o diálogo e transicionando `ReviewDetailScreen` para o estado explícito de indisponibilidade, removendo a avaliação antiga e a possibilidade de denúncia), `429 RATE_LIMIT_EXCEEDED` (exibindo tempo de espera via `ErrorBanner.formatRetryAfter`), `NetworkException` (preservando motivo e detalhes preenchidos) e exceções inesperadas sem travar o botão de envio.
