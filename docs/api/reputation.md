# API REST de Reputação V1 (docs/api/reputation.md)

Este documento especifica os contratos, regras de governança, arquitetura e garantias de privacidade da **Fundação de Reputação V1** do **Rewit** (**Step 23.0 / Step 23.1**).

---

## 1. Princípios Arquiteturais e de Privacidade

### 1.1 Separação entre Factual Statistics e Reputation
O Rewit adota uma separação arquitetural estrita entre:
* **Estatísticas Factuais**: dados brutos e contagens exatas de atividade (ex: total de seguidores, total de seguindo, total de avaliações brutas).
* **Reputação V1 (Snapshot Derivado)**: sinais objetivos de confiabilidade e contribuição calculados a partir de regras de domínio versionadas (`version = 1`), persistidos como snapshots no PostgreSQL.
* O snapshot **não é fonte de verdade**; os fatos originadores residem nas tabelas `reviews`, `review_reactions`, `places` e `review_targets`.

### 1.2 Estratégia de Atualização Transacional Síncrona
A partir do hardening (**Step 23.1**), a estratégia de ciclo de vida do snapshot é **transacional e síncrona nos eventos originadores**:
1. **Criação de Review** (`ReviewService.createReview`): recalcula e persiste o snapshot do autor dentro da mesma transação física (`@Transactional`).
2. **Reação Helpful** (`ReviewHelpfulService.addHelpful` e `removeHelpful`): recalcula e persiste o snapshot do autor da avaliação avaliada dentro da transação.
3. **Moderação Preventiva** (`ReportService.reportReview`): quando uma avaliação atinge o limiar de denúncias (`REPORT_THRESHOLD_FOR_UNDER_REVIEW = 3`) e transiciona para `UNDER_REVIEW`, o snapshot do autor é recalculado e persistido atomicamente.
4. **Serialização via Lock Pessimista**: para prevenir *lost updates* e corridas concorrentes do mesmo usuário, o recálculo executa sob lock exclusivo (`SELECT ... FOR UPDATE`) na linha de `user_reputation`.
5. **Consulta O(1) e Inicialização Sob Demanda**: requisições ao endpoint `GET /api/v1/users/{userId}/reputation` leem diretamente a linha persistida em $O(1)$. Caso o usuário seja ativo e ainda não possua snapshot, ele é computado e persistido de forma transacional.

### 1.3 Ausência de Score Numérico Arbitrário
Conforme as diretrizes do **ADR-008**:
* Não é atribuído nenhum "score numérico" (ex: nota 0-100 ou ranking composto).
* A fórmula de um score sem especificação formal seria opaca, enganosa e prejudicial à transparência.
* A Reputação V1 expõe exclusivamente **sinais objetivos e verificáveis**.

### 1.4 Preservação de Anonimato
* **Reviews Anônimas (`is_anonymous = true`)**:
  * São **completamente excluídas** dos sinais públicos de reputação (`activeReviews`, `verifiedReviews`, `helpfulVotesReceived` e `distinctTargetsReviewed`).
  * Votos Helpful recebidos em avaliações anônimas não são contabilizados na reputação pública do autor.
  * O anonimato do autor é integralmente preservado.

### 1.5 Não Exposição de Dados Sensíveis e Governança
O subsistema de reputação garante:
* **Reports (Denúncias)**: não são utilizados como sinal, penalidade ou peso oculto. O quantitativo de denúncias e a identidade de denunciantes nunca são expostos.
* **Check-In / Geolocalização**: a verificação presencial é utilizada exclusivamente como flag booleana (`is_verified_on_site`). Nenhuma latitude, longitude, raio, distância ou precisão de GPS é armazenada ou exposta na reputação.
* **Dados Pessoais (PII)**: nenhum e-mail, hash de senha ou dado privado de perfil é retornado.
* **Sinais Deliberadamente Excluídos**: contagem de seguidores/seguindo (pertencem ao grafo social factual) e rankings competitivos globais.

### 1.6 Ciclo de Vida dos Status de Review
* **`ACTIVE`**: contabilizada em `activeReviews`, `verifiedReviews` (se presencial), `helpfulVotesReceived` e `distinctTargetsReviewed`.
* **`UNDER_REVIEW`**: temporariamente excluída de todos os sinais públicos enquanto aguarda moderação.
* **`REMOVED`**: excluída permanentemente de todos os sinais de reputação.

---

## 2. Sinais de Reputação V1

| Sinal | Tipo | Descrição | Regras de Domínio |
| :--- | :--- | :--- | :--- |
| **`userId`** | `UUID` | Identificador do usuário. | Deve corresponder a um usuário ativo existente no sistema. |
| **`version`** | `Integer` | Versão das regras de cálculo aplicadas. | Atual: `1`. Backend-controlled (imutável por clientes). |
| **`activeReviews`** | `Integer` | Total de avaliações ativas e públicas do usuário. | Exclui avaliações anônimas (`is_anonymous = false`) e status diferente de `ACTIVE`. |
| **`verifiedReviews`** | `Integer` | Total de avaliações ativas com verificação presencial (*on-site*). | Exclui anônimas. Invariante estrita: $verifiedReviews \le activeReviews$. |
| **`helpfulVotesReceived`** | `Integer` | Total de reações `HELPFUL` recebidas em avaliações ativas e públicas. | Exclui reações em avaliações anônimas. Reversível na remoção do voto. |
| **`distinctTargetsReviewed`** | `Integer` | Quantidade de alvos distintos avaliados pelo usuário. | Baseado em avaliações ativas não-anônimas. Mede a diversidade de contribuições. |
| **`calculatedAt`** | `Instant (ISO-8601)` | Timestamp UTC em que o snapshot foi computado sob lock. | Atualizado deterministicamente a cada recálculo transacional. |

---

## 3. Endpoints da API REST

Base path: `/api/v1/users/{userId}/reputation`  
Autenticação: **Obrigatória** (`Bearer <JWT>`)

---

### 3.1 Obter Reputação do Usuário

Recupera o snapshot de reputação V1 do usuário identificado por `userId`. Consulta executada em $O(1)$ sobre o snapshot persistido. Se inexistente para um usuário ativo, computa e persiste o snapshot inicial.

* **Método**: `GET`
* **Rota**: `/api/v1/users/{userId}/reputation`
* **Headers**:
  * `Authorization: Bearer <access_token>` (Obrigatório)

#### Parâmetros de Path:
* `userId` (UUID, obrigatório): ID do usuário a ter a reputação consultada.

#### Resposta de Sucesso (`200 OK`):
```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "version": 1,
  "signals": {
    "activeReviews": 5,
    "verifiedReviews": 3,
    "helpfulVotesReceived": 12,
    "distinctTargetsReviewed": 4
  },
  "calculatedAt": "2026-09-30T17:00:00.000Z"
}
```

#### Respostas de Erro:

* **`401 Unauthorized`**: Token de autenticação ausente ou inválido.
```json
{
  "status": 401,
  "error": "UNAUTHORIZED",
  "message": "Token de autenticação ausente ou inválido"
}
```

* **`404 Not Found`**: Usuário inexistente ou inativo/excluído.
```json
{
  "status": 404,
  "code": "USER_NOT_FOUND",
  "message": "Usuario nao encontrado"
}
```

---

## 4. Modelagem de Dados (Flyway Migration V10)

```sql
CREATE TABLE user_reputation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 1,
    active_reviews INTEGER NOT NULL DEFAULT 0,
    verified_reviews INTEGER NOT NULL DEFAULT 0,
    helpful_votes_received INTEGER NOT NULL DEFAULT 0,
    distinct_targets_reviewed INTEGER NOT NULL DEFAULT 0,
    calculated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_user_reputation_user UNIQUE (user_id),
    CONSTRAINT chk_reputation_active_reviews CHECK (active_reviews >= 0),
    CONSTRAINT chk_reputation_verified_reviews CHECK (verified_reviews >= 0 AND verified_reviews <= active_reviews),
    CONSTRAINT chk_reputation_helpful_votes CHECK (helpful_votes_received >= 0),
    CONSTRAINT chk_reputation_targets CHECK (distinct_targets_reviewed >= 0),
    CONSTRAINT chk_reputation_version CHECK (version >= 1)
);

CREATE INDEX idx_user_reputation_user_id ON user_reputation(user_id);
```
