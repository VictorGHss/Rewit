# Arquitetura do Núcleo de Reviews Multi-Target (Step 10.0)

Este documento estabelece o modelo conceitual, arquitetural e relacional do subsistema de avaliações do Rewit, implementado no **STEP 10.0**.

---

## 1. Visão Geral e Conceito Central

No ecossistema Rewit, a experiência do usuário com estabelecimentos e itens de consumo é inerentemente multidimensional. Uma ida a um restaurante envolve avaliar o local em si (ambiente, atendimento), mas também pratos, bebidas ou serviços específicos experimentados naquela mesma ocasião.

Para modelar essa realidade sem duplicar publicações ou forçar médias artificiais, a arquitetura divide a publicação em dois conceitos complementares:

```text
User (Autor Confiável)
  │
  └── Review (Unidade Social)
        │
        ├── contextPlace (Place de contexto físico opcional)
        ├── experienceText (Relato qualitativo opcional)
        ├── visibility (PUBLIC, PRIVATE, FOLLOWERS)
        ├── isAnonymous (Visibilidade pública preservando autor interno)
        │
        ├── ReviewTarget → RateableTarget A (Ex: Place X, rating = 4.5)
        ├── ReviewTarget → RateableTarget B (Ex: Product Y, rating = 3.0)
        └── ReviewTarget → RateableTarget C (Ex: Product Z, rating = 5.0)
```

1. **`Review` é a unidade social**:
   - Representa a publicação, o evento social e o relato do usuário na plataforma.
   - Carrega o texto qualitativo da experiência (`experienceText`), flags de visibilidade, contexto físico opcional e timestamps.
2. **`ReviewTarget` é a unidade de avaliação**:
   - Representa a nota quantitativa específica vinculada a um alvo avaliável (`RateableTarget`).
   - Cada alvo possui sua própria nota independente (`rating`).

---

## 2. Invariantes Multi-Target e Integridade

### 2.1 Pelo menos um alvo avaliado (`targets >= 1`)
- Uma publicação de avaliação não pode existir sem pelo menos um alvo avaliado com nota.
- O método de domínio `review.validateHasAtLeastOneTarget()` e o caso de uso `ReviewService.createReview(...)` rejeitam qualquer tentativa de persistir uma Review vazia com o erro de negócio `REVIEW_WITHOUT_TARGET`.

### 2.2 Unicidade do alvo dentro da mesma publicação
- O mesmo `RateableTarget` não pode ser avaliado mais de uma vez dentro da mesma `Review`.
- Essa garantia é imposta em duas camadas complementares:
  1. **Domínio e Aplicação**: O método `Review.addTarget(...)` e a validação do `ReviewService` checam a unicidade dos identificadores de alvo antes de qualquer operação de persistência, rejeitando duplicações com o código `DUPLICATE_REVIEW_TARGET`.
  2. **Banco de Dados PostgreSQL**: A constraint única física `uq_review_target UNIQUE (review_id, target_id)` assegura a invariante contra concorrência ou bypass da aplicação.

---

## 3. Escala e Granularidade de Avaliação

- Cada `ReviewTarget.rating` deve situar-se estritamente no intervalo:
  $$1.0 \le \text{rating} \le 5.0$$
- **Granularidade e Precisão**:
  - Definida na coluna PostgreSQL como `NUMERIC(2, 1)`, suportando até uma casa decimal (ex.: `1.0`, `3.5`, `4.5`, `5.0`).
  - O domínio e a aplicação rejeitam ratings nulos, menores que 1.0, maiores que 5.0 (`INVALID_RATING_RANGE`) ou com mais de uma casa decimal (`INVALID_RATING_PRECISION`).
  - O banco de dados valida via check constraint `CHECK (rating >= 1.0 AND rating <= 5.0)`.

---

## 4. Separação entre Experiência Qualitativa e Rating

- A experiência textual (`experienceText`) é estritamente qualitativa e separada da nota.
- É um campo opcional: um usuário pode registrar uma avaliação com apenas notas em alvos específicos, sem preencher texto longo, ou pode preencher um relato detalhado acompanhado de suas notas.
- Não há média global artificial atribuída diretamente à tabela `reviews`. As notas residem exclusivamente em `review_targets`.

---

## 5. Autoria e Avaliações Anônimas

- Todo `Review` possui um autor confiável (`user_id`).
- A aplicação extrai o autor do contexto autenticado confiável, nunca de campos livres do payload externo.
- O usuário deve existir, estar ativo (`isActive == true`) e não excluído (`isDeleted == false`).
- **Avaliações Anônimas (`isAnonymous = true`)**:
  - O anonimato é uma propriedade de exibição e visibilidade pública da rede social.
  - Internamente, o `user_id` é rigorosamente preservado via Foreign Key no PostgreSQL.
  - Nunca é gerado autor fictício ou UUID nulo.

---

## 6. Níveis de Visibilidade

O campo `visibility` controla quem pode visualizar a publicação:
- `PUBLIC`: Visível publicamente para toda a comunidade Rewit.
- `PRIVATE`: Visível exclusivamente pelo autor.
- `FOLLOWERS`: Visível apenas para conexões/seguidores do autor.
- O domínio normaliza e valida o valor, aplicando `PUBLIC` como padrão quando omitido. O PostgreSQL assegura os valores via check constraint `chk_review_visibility`.

---

## 7. Contexto Físico (`contextPlace`) vs. Alvo Avaliado (`ReviewTarget`)

- `contextPlaceId` (opcional) indica **onde** a experiência aconteceu no mundo real.
- `ReviewTarget` indica **o que** foi avaliado.
- Exemplo: Um usuário avalia 3 produtos (`RateableTarget` de tipo `PRODUCT`) comprados em um café. O café é referenciado como `contextPlaceId`, enquanto os produtos são os `ReviewTarget`s da publicação.
- Quando informado, o `contextPlaceId` deve referenciar um `Place` válido existente no catálogo, com integridade referencial garantida por Foreign Key (`ON DELETE SET NULL`).

---

## 8. Atomismo Transacional e Persistência

- A criação de uma Review e seus alvos é 100% atômica, orquestrada pelo `ReviewService.createReview(...)` com anotação `@Transactional`.
- **Fluxo Atômico**:
  1. Validação do autor (existência, status ativo);
  2. Validação do `contextPlace` (quando fornecido);
  3. Validação em memória de todos os alvos (ausência de duplicidade, limites de rating, precisão de escala);
  4. Validação de existência física de cada `RateableTarget` no banco de dados;
  5. Criação das entidades de domínio e validação de invariantes (`validateHasAtLeastOneTarget`);
  6. Persistência do agregado `Review` no repositório;
  7. Persistência de todos os `ReviewTarget`s no repositório;
  8. Commit atômico da transação.
- **Garantia de Rollback**: Se qualquer alvo falhar (por dados inválidos, violação de constraint física ou erro de infraestrutura), toda a transação sofre rollback imediato. Nenhuma Review órfã ou target inconsistente permanece no PostgreSQL.

---

## 9. Desacoplamento Arquitetural (Ports & Adapters)

A arquitetura hexagonal e o padrão Ports & Adapters são mantidos com rigor:
- **Portas de Aplicação** (`com.rewit.application.port`):
  - `ReviewRepository`
  - `ReviewTargetRepository`
- **Adaptadores de Infraestrutura** (`com.rewit.infrastructure.persistence.adapter`):
  - `ReviewRepositoryAdapter`
  - `ReviewTargetRepositoryAdapter`
- **Entidades de Persistência** (`com.rewit.infrastructure.persistence.entity`):
  - `ReviewJpaEntity` (mapeando a tabela `reviews`)
  - `ReviewTargetJpaEntity` (mapeando a tabela `review_targets`)
- A camada de aplicação e o modelo de domínio não possuem nenhuma dependência direta do Spring Data JPA ou do Hibernate.

---

## 10. Fora do Escopo Deste Step (STEP 10.0)

Para manter a fundação sólida e o escopo rigorosamente controlado, os seguintes componentes **não** foram implementados neste step e serão tratados em etapas posteriores:
- Endpoints REST controllers de Review (criação via HTTP, listagens, etc.);
- Integração de Check-in (`check_ins`), geofencing e validação por GPS;
- Reações (`ReviewReaction`), comentários/discussões (`ReviewDiscussion`) e tags (`ReviewTag`);
- Feed social, ordenações complexas e algoritmos de recomendação;
- Moderação por inteligência artificial ou fluxos administrativos de revisão de conteúdo;
- Upload e anexação de fotos.
