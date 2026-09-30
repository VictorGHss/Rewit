# Roadmap Técnico e Documento de Recuperação de Contexto do Backend — REWIT

> **Data de Atualização**: 30/09/2026  
> **Status do Repositório**: Verde e Estabilizado  
> **Checkpoint Atual (HEAD)**: `86204f0e9c148f18c39eb6c6ac8b82a7b42c9ec8`  
> **Branch**: `main` (ahead do origin em commits consolidados)  
> **Total de Testes Automatizados**: `791` (0 failures, 0 errors, 0 skipped)  
> **Working Tree**: `clean`  

---

## 1. Como Recuperar o Contexto Rapidamente (Context Recovery)

Este documento é a **fonte primária de recuperação de contexto** para qualquer engenheiro ou agente de IA que assuma o desenvolvimento do backend do Rewit. Ao iniciar um novo ciclo de trabalho:

### 1.1 Comandos Imediatos de Validação
```powershell
# 1. Verificar integridade do workspace
git status

# 2. Executar compilação e suíte completa de testes no PostgreSQL/PostGIS local
cd backend
./mvnw clean test
```
*Resultado esperado*: `Tests run: 791, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

### 1.2 Regras Arquiteturais Inegociáveis
1. **PostgreSQL 18 + PostGIS 3.6 como Source of Truth**: Nenhuma entidade existe fora do banco relacional. Google Places é apenas provider externo consultado via Anti-Corruption Layer (ACL).
2. **Schema Controlado via Flyway**: `spring.jpa.hibernate.ddl-auto=validate`. Nunca utilizar `update` ou `create`. Toda alteração estrutural exige migração incremental (`V1` a `V10` consolidadas).
3. **Isolamento de Camadas (DDD / Clean Architecture)**:
   - `domain`: Modelos puros, enums e Value Objects livres de anotações Spring/JPA.
   - `application`: Casos de uso, orquestradores e portas (`application/port`).
   - `infrastructure`: Entidades JPA, repositórios Spring Data, adaptadores de persistência e clientes externos.
   - `presentation`: Controllers REST, DTOs de entrada/saída, tratamento RFC 7807.
4. **Governança de Privacidade e Segurança**:
   - `ReviewVisibilityPolicy` é a única autoridade para autorizar visualização de avaliações (`PUBLIC`, `FOLLOWERS`, `PRIVATE`).
   - Status `UNDER_REVIEW` e `REMOVED` saem imediatamente da visibilidade pública e feeds.
   - **Anonimato Absoluto**: Avaliações anônimas (`is_anonymous = true`) nunca têm seu autor interno exposto ou inferido via API, ranking ou metadados de notificação.
   - **Localização Estática e Protegida**: Sem tracking contínuo. Coordenadas brutas (*raw GPS*) nunca são expostas ao cliente. Metadados EXIF/GPS de imagens são higienizados antes do upload no SeaweedFS.
5. **Separação de Reputação e Ranking**:
   - `UserReputation` é um snapshot factual e versionado de histórico do usuário. Não é score subjetivo e **não deve ser usado como peso de ranking** para não gerar elitismo algorítmico nem penalizar novos usuários.
   - Denúncias (`reports`) pertencem à moderação; nunca viram penalidade matemática de ordenação.
   - Sem modelos de Machine Learning ou embeddings nesta etapa.

---

## 2. Status Macro do Backend

A tabela a seguir consolida o estado real verificado no código-fonte, mapeando cada subsistema, suas evidências e o próximo passo imediato:

| Área / Subsistema | Status | Evidência no Código | Próximo Passo |
| :--- | :---: | :--- | :--- |
| **Fundação, Runtime & Config** | ✅ CONCLUÍDO | Java 25, Spring Boot 4.1.1, Virtual Threads ativadas, Actuator e Swagger UI integrados. | Manter configurações e compatibilidade. |
| **Banco de Dados & Migrations** | ✅ CONCLUÍDO | Flyway `V1` a `V10` ativas; PostGIS espacial; índices compostos em [V7](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V7__review_listing_indexes.sql) e [V10](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V10__user_reputation.sql). | Criar `V11` apenas sob necessidade comprovada de índices para o Feed V2. |
| **Autenticação, JWT & Sessões** | ✅ CONCLUÍDO | [AuthController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AuthController.java), Argon2id, JWT assinado, revogação de sessões e refresh token. | Manter bloqueio estrito de IDOR em todos os novos endpoints. |
| **Catálogo Base & Alvos Avaliáveis** | ✅ CONCLUÍDO | [PlaceController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/PlaceController.java), [ProductController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ProductController.java), raiz polimórfica `RateableTarget` e cálculo de estatísticas. | Expandir endpoints de catálogo sob demanda do app mobile. |
| **Integração Google Places** | ✅ CONCLUÍDO | [PlaceDiscoveryController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/PlaceDiscoveryController.java), isolamento via ACL, deduplicação por `place_external_references`. | Monitorar quotas e latência externa. |
| **Avaliações Multi-Alvo & Check-In** | ✅ CONCLUÍDO | [ReviewController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewController.java), multi-target atômico, validação de presença via PostGIS geofence. | Suporte futuro a edições com regras estritas. |
| **Rede Social (Seguidores & Conexões)**| ✅ CONCLUÍDO | [UserFollowController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/UserFollowController.java), `user_follows`, bloqueio de auto-follow, integridade relacional. | Base consolidada para Feed V1 e Feed V2. |
| **Reações de Utilidade (Helpful)** | ✅ CONCLUÍDO | [ReviewHelpfulController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewHelpfulController.java), `review_reactions`, contagem e batching sem N+1. | Reutilizado como sinal no ranking V2. |
| **Perfil Público & Estatísticas** | ✅ CONCLUÍDO | [MeController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/MeController.java), [UserController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/UserController.java), agregação factual de reviews e seguidores. | Manter consistência de dados públicos. |
| **Denúncias & Moderação Preventiva** | ✅ CONCLUÍDO | [ReportController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReportController.java), rate limiting in-memory, quarentena automática (`UNDER_REVIEW`) ao atingir 3 denúncias. | Painel e fluxo administrativo de moderação (Backlog). |
| **Discussões & Comentários** | ✅ CONCLUÍDO | [DiscussionController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/DiscussionController.java), respostas hierárquicas, soft delete por autor, notificações. | Manter isolamento e integridade. |
| **Mídia de Avaliações (Imagens)** | ✅ CONCLUÍDO | [ReviewMediaController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewMediaController.java), SeaweedFS/S3, higienização EXIF/GPS, limite de 5 imagens. | Garbage collection de mídias órfãs (Backlog async). |
| **Notificações In-App** | ✅ CONCLUÍDO | [NotificationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/NotificationController.java), eventos acionados em follow, helpful, discussão e resposta. | Migração para processamento assíncrono (Outbox). |
| **Reputação V1 (Snapshot Factual)** | ✅ CONCLUÍDO | [ReputationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReputationController.java), [UserReputation.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/UserReputation.java), recálculo atômico e versionado. | Manter isolado do ranking de avaliações. |
| **Busca no Catálogo (Search V1)** | ✅ CONCLUÍDO | [CatalogSearchController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/CatalogSearchController.java), busca unificada por trigramas (`pg_trgm`) em places/products. | Monitorar performance de índices GIN. |
| **Feed V1 (Social Cronológico)** | ✅ CONCLUÍDO | [FeedController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/FeedController.java), `GET /api/v1/feed`, estritamente cronológico, seguidos diretos, sem N+1. | Manter congelado sem alterações. |
| **Feed V2 (Ranking & Relevância)** | 🚧 EM ANDAMENTO | [FeedCandidate.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedCandidate.java), [FeedV2Ranker.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedV2Ranker.java), [FeedV2Diversifier.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedV2Diversifier.java), [FeedCandidateRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/FeedCandidateRepositoryAdapter.java). | **Implementar Orchestration (Service de Aplicação)**. |
| **Ciclo de Vida do Conteúdo (Edição)** | ⏳ PENDENTE | Soft delete existe em discussões; reviews são imutáveis após criação. | Definir regras de edição/exclusão pós-interações. |
| **Moderação Administrativa (Backoffice)** | ⏳ PENDENTE | Quarentena preventiva comunitária existe; não há controllers de administração. | Especificar API administrativa e roles (`ROLE_ADMIN`). |
| **Jobs Assíncronos & Outbox** | ⏳ PENDENTE | Todas as operações são síncronas/transacionais no PostgreSQL. | Criar padrão de Outbox transacional no banco. |
| **Observabilidade Avançada & Deploy** | ⏳ PENDENTE | Actuator básico habilitado; sem tracing distribuído ou logs estruturados JSON. | Configurar exportação Prometheus/OTel para produção. |
| **Cold Start / Descoberta Fora da Rede** | 🔮 FUTURO ADIADO | Não implementado propositalmente no Feed V2 social. | Reavaliar após validação do feed V2 em produção. |
| **Cache Distribuído em Redis** | 🔮 FUTURO ADIADO | Redis conectado mas sem cache de queries complexas. | Introduzir apenas sob saturação medida do PostgreSQL. |
| **Machine Learning & Embeddings** | 🔮 FUTURO ADIADO | Arquitetura determinística prioritária; sem ML. | Avaliar apenas após escala de dezenas de milhares de reviews. |

---

## 3. Histórico Consolidado de Checkpoints

Os principais marcos de evolução do backend encontram-se registrados nos commits:

* `dfc3e00` — *feat: adicionar descoberta de lugares via google places* (integração externa desacoplada).
* `913fae3` — *feat: consolidar catalogo e dominio de reviews* (fundação multi-target e PostGIS).
* `7573a7a` — *feat: adicionar api rest de reviews* (endpoints REST para criação e leitura de avaliações).
* `05e9f70` — *feat: adicionar check-in e metricas de reviews* (check-in presencial validado via geofence).
* `0543a44` — *feat: adicionar listagem paginada de reviews* (contratos paginados e índices V7).
* `35d6336` — *feat: adicionar sistema de seguidores* (grafo social `user_follows`).
* `dd2e6df` — *feat: adicionar votos de utilidade em reviews* (reações `HELPFUL`).
* `216ad7b` — *feat: adicionar feed social* (Feed V1 cronológico `GET /api/v1/feed`).
* `3dd3756` — *feat: adicionar perfil publico e estatisticas de usuario* (`/api/v1/users/{id}`).
* `d96d7a3` — *feat: adicionar reports e moderacao preventiva* (quarentena preventiva comunitária e tabela V8).
* `628cdd7` — *feat: adicionar discussions e comentarios* (comentários em árvore e soft delete).
* `70473db` — *feat: adicionar media de reviews* (upload no SeaweedFS, remoção de EXIF e tabela V9).
* `087918f` — *feat: adicionar notificacoes in-app* (sistema unificado de notificações de eventos).
* `91971de` — *feat: adicionar fundacao de reputacao* (tabela V10, snapshot derivado factual).
* `f8b9d1a` — *refactor: centralizar contrato de consulta de reviews* (unificação de políticas de consulta).
* `74f97ab` — *chore: consolidar limpeza e estabilização do backend* (Search V1 integrado, 730 testes verdes).
* `8076cae` — *feat: adicionar nucleo deterministico do feed v2* (`FeedV2Ranker` e `FeedV2Diversifier`).
* `70a764c` — *feat: adicionar retrieval do feed v2* (query otimizada em `ReviewJpaRepository`).
* `86204f0` — *feat: integrar base do candidate retrieval do feed v2* (adaptador de persistência e 791 testes verdes).

---

## 4. Arquitetura Atual e Princípios Consolidados

O backend adota o paradigma de **Arquitetura Hexagonal (Ports & Adapters)** combinado com **Domain-Driven Design (DDD)**:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           PRESENTATION LAYER                                    │
│   Controllers RESTful (ex: FeedController, ReviewController, MeController)      │
│   DTOs de Request/Response • Validações Bean Validation • RFC 7807 Errors        │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │ (Invoca Casos de Uso)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            APPLICATION LAYER                                    │
│   Services de Aplicação (ex: ReviewService, CatalogSearchService)               │
│   Portas de Saída / Interfaces (ex: FeedCandidateRepository, ReviewRepository) │
│   Políticas de Autorização (ReviewVisibilityPolicy)                             │
└──────────────────┬───────────────────────────────────────┬──────────────────────┘
                   │                                       │
                   │ (Usa Modelos Puros)                   │ (Implementado por)
                   ▼                                       ▼
┌─────────────────────────────────────┐ ┌─────────────────────────────────────────┐
│            DOMAIN LAYER             │ │          INFRASTRUCTURE LAYER           │
│   Entidades Puras (Review, Target)  │ │   Adaptadores de Persistência           │
│   Value Objects (FeedScore, Weights)│ │   Spring Data JPA & Hibernate Spatial   │
│   Algoritmos Puros:                 │ │   PostgreSQL 18 + PostGIS 3.6           │
│   - FeedV2Ranker (Score Linear)     │ │   Redis Data Client                     │
│   - FeedV2Diversifier (Spacing)     │ │   SeaweedFS / S3 Object Storage         │
│   - ReputationCalculator (Factual)  │ │   Google Places Client (ACL)            │
└─────────────────────────────────────┘ └─────────────────────────────────────────┘
```

### Princípios Técnicos Consolidados
- **PostgreSQL como Fonte Única da Verdade**: Todas as consultas e operações transacionais derivam do PostgreSQL.
- **PostGIS para Operações Espaciais**: Distâncias geodésicas, validação de check-in e centroides são processados no banco via funções espaciais nativas (`ST_DWithin`, coordenadas EPSG:4326).
- **Sem N+1 Queries**: Todo enriquecimento em massa (targets, perfis de autor, contagem de helpful e flags de interação) ocorre obrigatoriamente através de **batch loaders** (`IN (:ids)` e agregações agrupadas em lote).
- **Tratamento de Mídia**: Higienização estrita de streams de imagens. Metadados de geolocalização e identificadores de câmera são expurgados no backend antes de persistir o arquivo no SeaweedFS.

---

## 5. Feed V2 — Estado Atual e Próximos Passos

O Feed V2 foi projetado para substituir a ordenação puramente cronológica por uma experiência de relevância determinística que valoriza autenticidade e utilidade, sem recorrer a algoritmos opacos de recomendação.

### 5.1 O que já está implementado (Núcleo e Persistência)
1. **Value Objects de Domínio**:
   - [FeedCandidate.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedCandidate.java): Modelo imutável com os 5 atributos essenciais para cálculo (`reviewId`, `authorId`, `targetId`, `createdAt`, `isVerifiedOnSite`, `helpfulCount`, `isDirectFollow`).
   - [FeedScore.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedScore.java): Score normalizado em `[0.0, 1.0]`.
   - [FeedRankingWeights.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedRankingWeights.java): Pesos lineares com soma $1.0$ (Social: 0.40, Recência: 0.30, Verificado: 0.20, Helpful: 0.10).
   - [RankedFeedCandidate.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/RankedFeedCandidate.java): Par candidato + score calculado.
2. **Componentes de Domínio Puro**:
   - [FeedV2Ranker.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedV2Ranker.java): Algoritmo de ranqueamento determinístico com decaimento temporal linear em janela de 14 dias, saturação logarítmica de helpful (teto 20) e desempate estrito por `Score DESC, createdAt DESC, id ASC`.
   - [FeedV2Diversifier.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/feed/FeedV2Diversifier.java): Algoritmo de espaçamento de autores e alvos (máximo de 2 itens consecutivos do mesmo autor ou mesmo target) sem descarte de candidatos.
3. **Candidate Retrieval no Banco**:
   - [FeedCandidateRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/FeedCandidateRepository.java): Porta de saída com janela fixa de 100 candidatos (`CANDIDATE_WINDOW = 100`).
   - [FeedCandidateRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/FeedCandidateRepositoryAdapter.java): Adaptador JPA que executa exatamente **3 queries em lote** para recuperar e montar a janela de candidatos (Zero N+1).

### 5.2 Em Andamento
O pipeline `Candidate Retrieval → Ranker → Diversifier` está 100% validado em testes unitários e de integração de persistência. Falta a **camada de orquestração na aplicação**.

### 5.3 Próximas Etapas Concretas
1. **Orchestration**: Criar o serviço de aplicação (`FeedV2Service`) que recebe o `requesterUserId`, invoca o `FeedCandidateRepository`, submete a lista ao `FeedV2Ranker` com `referenceTime`, aplica o `FeedV2Diversifier` e fatia a página solicitada.
2. **Hydration / Projection**: Reutilizar os batch loaders existentes para carregar alvos, autor mascarado (caso anônimo) e contagens de helpful, produzindo `ReviewPublicView`.
3. **Endpoint HTTP**: Criar o controller versionado `GET /api/v2/feed` garantindo tratamento padronizado de paginação e segurança.
4. **Validação de Visibilidade e Anonimato**: Assegurar em testes de API que reviews `UNDER_REVIEW`, `REMOVED` e `PRIVATE` permaneçam inacessíveis, e autores anônimos nunca sejam desmascarados.
5. **Decisão Futura sobre Cold Start / Discovery**: Avaliar inclusão de candidatos fora da rede social apenas após a V2 social estar consolidada em produção.

---

## 6. Backlog: Ciclo de Vida do Conteúdo (Content Lifecycle)

Análise do estado real do código referente a edições e exclusões:

| Funcionalidade | Classificação | Situação no Código Atual | Risco / Decisão Pendente |
| :--- | :---: | :--- | :--- |
| **Edição de Avaliação** | NÃO IMPLEMENTADO | Não existem endpoints `PUT` ou `PATCH` em `ReviewController`. Avaliações são imutáveis após persistência. | Se permitida, a edição pode invalidar o check-in presencial ou alterar o sentido após votos de Helpful recebidos. Requer janela de tolerância de edição (ex: 15 min) ou histórico de revisões. |
| **Exclusão de Avaliação (Soft Delete)** | NÃO IMPLEMENTADO | `ReviewStatus.REMOVED` existe no domínio, mas apenas para uso da moderação preventiva. Não há endpoint de exclusão pelo usuário. | Necessário definir o impacto nas médias calculadas de alvos (`rateable_target_stats`) e na reputação do autor. |
| **Edição de Discussão / Comentário** | NÃO IMPLEMENTADO | Comentários não possuem endpoint de edição. | Evita alteração de contexto em threads onde outros usuários já responderam. |
| **Exclusão de Discussão (Soft Delete)** | JÁ EXISTE | `DELETE /api/v1/discussions/{id}` altera o status para `REMOVED`. Apenas o próprio autor pode remover seu comentário. | Totalmente implementado e coberto por testes. |
| **Regras Pós-Interação** | NÃO DEFINIDO | Não há travas de alteração baseadas na presença de reações ou comentários de terceiros. | Definir se reviews com mais de $N$ votos úteis podem ter seu texto principal alterado. |
| **Trilha de Auditoria / Histórico** | NÃO IMPLEMENTADO | Não há tabelas de auditoria (ex: `review_audits`, `review_history`). | Backlog para requisitos de compliance futuros. |
| **Descarte de Mídia Órfã** | NÃO IMPLEMENTADO | Imagens permanecem no SeaweedFS mesmo se a publicação associada for rejeitada ou entrar em quarentena. | Necessário job de garbage collection assíncrono. |

---

## 7. Backlog: Moderação Administrativa (Backoffice)

O sistema conta atualmente com um mecanismo robusto de **moderação preventiva comunitária** via [ReportService.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/ReportService.java) (3 denúncias pendentes colocam a avaliação automaticamente em quarentena `UNDER_REVIEW`). No entanto, o fluxo administrativo de análise humana encontra-se pendente:

### Componentes Mapeados (Backlog a Validar)
1. **Consulta Administrativa de Denúncias**:
   - Endpoint `GET /api/v1/admin/reports` com filtros por motivo (`reason`), status da denúncia (`PENDING`, `REVIEWED`, `DISMISSED`) e paginação.
2. **Fluxo de Decisão de Moderação**:
   - Ação de **Deferimento**: Modificador altera status da review para `REMOVED`. Recálculo automático de estatísticas do alvo e reputação.
   - Ação de **Indeferimento (Descarte)**: Denúncia marcada como improcedente; review retorna ao status `ACTIVE` e volta ao feed.
3. **Ações Disciplinares sobre Usuários**:
   - Suspensão temporária ou banimento permanente de contas com reincidência de conduta abusiva.
4. **Controle de Acesso & Roles**:
   - Implementação de perfis de segurança (`ROLE_ADMIN`, `ROLE_MODERATOR`) no Spring Security, bloqueando acessos não autorizados a rotas `/admin/**`.
5. **Trilha de Auditoria Administrativa**:
   - Registro imutável de quem moderou, quando e qual foi a justificativa técnica.

---

## 8. Backlog: Processamento Assíncrono & Outbox Pattern

Atualmente, **todas as operações do backend são síncronas e transacionais**. Eventos de notificação e recálculo de reputação rodam dentro da mesma transação de banco da requisição original.

### Proposta Técnica de Transição (Sem Adição Prematura de Mensageria Externa)
Não há necessidade imediata de brokers como Kafka ou RabbitMQ. O padrão **Transactional Outbox sobre PostgreSQL** é suficiente e preserva a consistência ACID:

| Componente / Cenário | Motivação | Pré-requisito | Estado |
| :--- | :--- | :--- | :---: |
| **Tabela `outbox_events`** | Desacoplar operações pesadas da thread HTTP do usuário. | Criar migration Flyway para tabela de outbox. | ⏳ PENDENTE |
| **Notificações Assíncronas** | Evitar lentidão na criação de reviews/comentários caso o volume de notificações cresça. | Worker de polling da outbox (`@Scheduled`). | ⏳ PENDENTE |
| **Recálculo Assíncrono de Reputação** | Eliminar lock pessimista em `users` durante a postagem de reviews. | Worker da outbox para reputação. | ⏳ PENDENTE |
| **Garbage Collection do SeaweedFS** | Remover imagens não referenciadas no S3 para economia de storage. | Job periódico de varredura de órfãos. | ⏳ PENDENTE |
| **Cleanup de Sessões Expiradas** | Expurgar tokens revogados e sessões antigas da tabela `user_sessions`. | Job agendado de expurgo cronológico. | ⏳ PENDENTE |

---

## 9. Observabilidade, Performance e Produção

### 9.1 O que já existe
- **Spring Boot Actuator**: Endpoints `/actuator/health`, `/actuator/info` e `/actuator/metrics` expostos.
- **Hikari Connection Pool**: Configurado com limite de 10 conexões locais e timeouts defensivos.
- **Rate Limiting In-Memory**: Proteção contra flood de denúncias via `ReportRateLimiter`.
- **Flyway Validation**: Validação rigorosa dos checksums das migrações no startup da aplicação.
- **Java Virtual Threads**: Ativadas nativamente (`spring.threads.virtual.enabled: true`) para alto throughput de I/O bloqueante.

### 9.2 O que falta para Prontidão de Produção
- **Logs Estruturados em JSON**: Configuração do Logback com formato JSON estruturado (Logstash encoder) contendo `traceId`, `spanId` e `userId`.
- **Métricas Prometheus / Micrometer**: Exposição de métricas customizadas de latência do Feed V2, taxa de cache hits e contagem de check-ins rejeitados.
- **Distributed Tracing**: Instrumentação com OpenTelemetry ou Micrometer Tracing para rastreabilidade ponta a ponta.
- **Rate Limiting Distribuído**: Migração do rate limiter in-memory para Redis (Token Bucket com scripts Lua) para suportar múltiplas instâncias da API.
- **Políticas de Backup e Restore**: Rotinas documentadas de backup contínuo (WAL archiving e pg_dump) para o PostgreSQL com PostGIS.
- **Testes de Carga Automatizados**: Scripts de teste de stress (k6 ou Gatling) simulando centenas de usuários navegando no feed simultaneamente.

---

## 10. Evolução Futura Deliberadamente Adiada

As seguintes frentes tecnológicas foram **propositadamente congeladas** e não devem ser iniciadas sem a satisfação de seus critérios de entrada (*gates*):

1. **Cold Start & Descoberta Fora da Rede no Feed**:
   - *Decisão*: O Feed V2 foca inicialmente na rede social direta do usuário. A descoberta ampla fora da rede só será inserida após validar o comportamento e os pesos do ranker social.
2. **Cache Agressivo de Feed em Redis**:
   - *Decisão*: O PostgreSQL 18 resolve o candidate retrieval em milissegundos com os índices atuais. Não introduzir cache de feed em Redis até que medições reais sob carga apontem saturação de CPU/IOPS no banco.
3. **Modelos de Machine Learning & Embeddings Vetoriais**:
   - *Decisão*: O produto prioriza explicabilidade, transparência e determinismo. Modelos neurais ou embeddings de recomendação estão suspensos até que haja massa de dados significativa e problema claro de relevância que regras determinísticas não possam resolver.
4. **Cluster Externo de Mensageria (Kafka/RabbitMQ)**:
   - *Decisão*: Evitar complexidade operacional prematura enquanto a fila transacional em PostgreSQL atender com folga à volumetria do sistema.

---

## 11. Decision Gates (Critérios de Decisão Técnica)

Para evitar retrabalho e desvios arquiteturais, toda evolução relevante deve obedecer aos seguintes gates formais:

```
[Decisão Técnica] ────► [Critério Mínimo / Gate] ────► [Ação Permitida]
Feed V2 HTTP             Orchestration e Hydration       Criar endpoint /api/v2/feed
                         completos e testados
Cache em Redis           Latência p99 > 200ms no banco   Implementar cache layer
Mensageria Externa       Outbox no PG > 5.000 msgs/s     Adicionar broker externo
Migração de Banco        Nova coluna/tabela inevitável   Criar V11 com rollback previsto
```

* **Gate do Feed V2**: Só implementar a camada de apresentação (`/api/v2/feed`) após o serviço de aplicação (`FeedV2Service`) estar orquestrado e testado unitariamente com os componentes de ranking e diversidade.
* **Gate do Redis**: Só utilizar Redis para cache de dados relacionais se testes de carga comprovarem que o PostgreSQL atingiu saturação com índices otimizados.
* **Gate de Migrations**: Nenhuma migração Flyway nova deve ser criada sem documentação prévia da motivação e validação do plano de execução (`EXPLAIN ANALYZE`).

---

## 12. Estado Atual da Suíte de Testes

* **Total de Testes**: `791`
* **Falhas**: `0`
* **Erros**: `0`
* **Ignorados / Skipped**: `0`
* **Perfil de Execução**: `local` (executa contra PostgreSQL e PostGIS reais via Docker Compose).
* **Distribuição**:
  - Testes Unitários de Domínio puro (ex: `FeedV2RankerUnitTest`, `FeedV2DiversifierUnitTest`).
  - Testes de Persistência com Spring Boot e banco real (ex: `FeedCandidateRetrievalPersistenceIntegrationTest`, `FeedV2RetrievalRankerIntegrationTest`).
  - Testes de Integração de API com MockMvc e Spring Security (ex: `FeedControllerIntegrationTest`, `ReviewControllerIntegrationTest`).
  - Testes de Não-Regressão das 24 etapas anteriores.

---

## 13. Próximo Passo Imediato

O próximo passo exato a ser executado no repositório é:

### **Implementação do Feed V2 Orchestration (Camada Application)**
1. **Criar a Porta/DTO de Saída da Aplicação**: Modelos de visão ou DTOs internos de projeção do Feed V2.
2. **Criar `FeedV2Service`**:
   - Injetar `FeedCandidateRepository`, `FeedV2Ranker` e `FeedV2Diversifier`.
   - Recuperar janela de 100 candidatos do banco via `retrieveCandidates(requesterId, 100)`.
   - Submeter candidatos ao `FeedV2Ranker.rank(candidates, referenceTime)`.
   - Submeter candidatos rankeados ao `FeedV2Diversifier.diversify(ranked)`.
   - Aplicar fatiamento paginado em memória (`page`, `size`).
3. **Cobrir com Testes Unitários de Aplicação**:
   - Garantir ordenação, paginação e isolamento de regras sem tocar em banco ou HTTP.
4. **Sem Alterações no Feed V1**:
   - O Feed V1 (`GET /api/v1/feed`) e toda a suíte de 791 testes devem permanecer intactos e verdes.
