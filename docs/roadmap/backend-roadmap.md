# Roadmap Técnico e Documento de Recuperação de Contexto do Backend — REWIT

> **Data de Atualização**: 14/10/2026\
> **Status do Repositório**: Verde e Estabilizado\
> **Base Integrada Atual (Política B)**: commit `033a1ab4f25223968ccc67fe12ceddc1684804a8` (merge do PR #2 sobre a base funcional histórica original `dfd84ae0d0b7e14558b03ee08abe359e2dcd5057`)\
> **Branch Atual de Trabalho**: `agent/claude/business-response-eligibility-policy`\
> **Referência da Validação Atual**: commit `b36732221e185a5c2de3b5b78018327ad43ceb76` (validado pelo Gemini em clone isolado; código implementado e testado no commit `8b5dd287f62c173ed5e4bacb4cccd046b35a856c`, com commits seguintes exclusivamente documentais `fd373c3` e `b367322`)\
> **Validação Pós-C11 (Histórica)**: branch integrada `integration/post-c11-docs-hardening`, commit `7b9473d` (base `dfd84ae` + documentação pós-C11 + testes de integração de segurança da consulta de respostas oficiais removidas)\
> **Resultados Mais Recentes por Suíte de Testes (Execuções Individuais)**:\
> - **Backend (Spring Boot / Maven)**: **1.860 testes executados e aprovados** (0 falhas, 0 erros, 0 ignorados, `BUILD SUCCESS`) via `./mvnw clean test` em containers reais, no commit `8b5dd28` da branch da política B. A validação pós-C11 anterior registrou 1.842 na branch integrada (commit `7b9473d`). Inventário estático do código em `8b5dd28` (não é resultado de execução): 202 arquivos `.java` em `backend/src/test/`, 199 deles com testes, e 1.838 métodos anotados (1.831 `@Test` e 7 `@ParameterizedTest`). Registros históricos: 1.271 testes no STEP 29.3 (`2d49b38`) e 1.462 no último total registrado neste roadmap antes da consolidação pós-C11 (`298d0bc`)\
> - **Mobile (Flutter / Dart)**: **618 testes executados e aprovados** (0 falhas, 0 erros) em 48 arquivos via `flutter test`; análise estática `flutter analyze` com 0 pendências (*"No issues found!"*, código de término 0). Nova validação de compatibilidade da política B realizada pelo Gemini em 14/10/2026 no commit `b367322` em clone isolado (Flutter SDK 3.47.7, Dart 3.13.5), confirmando compatibilidade plena sem qualquer alteração em `apps/mobile/`, `pubspec.yaml` ou `pubspec.lock` em relação a `033a1ab`. A execução anterior do Gemini (pré-política B, branch `agent/gemini/post-c11-validation-and-docs`) também registrou 618 testes verdes e análise estática limpa\
> - **Admin (React / Vitest)**: **82 testes executados e aprovados** (0 falhas, 0 erros) em 16 arquivos via `vitest run`, com `eslint` sem pendências e build de produção `tsc && vite build` concluído, no ciclo da política B (conteúdo do commit `8b5dd28`). A execução anterior do Gemini (branch `agent/gemini/post-c11-validation-and-docs`) registrou 78 testes\
> *Nota Metodológica*: Cada módulo opera com runners e ambientes independentes (JVM/Maven, Dart/Flutter e Node/Vitest). Não há pipeline de execução unificado do monorepo, mantendo-se os resultados de execução individuais e sem somas agregadas heterogêneas.\
> **Working Tree**: `Limpo`\
> **Próximo Trabalho**: Consolidação documental pós-C11, preparação da proposta de Gestão de Estabelecimentos Reivindicados / Catálogo Local (Fase 2 Comercial) e resolução de decisões pendentes (ADR-011)

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
*Resultado esperado*: `Tests run: 1860, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS` (última execução completa no commit `8b5dd28`, branch da política B; validação pós-C11 anterior: 1.842 em `7b9473d`; registros históricos: 1.271 testes no STEP 29.3 e 1.462 em `298d0bc`). A suíte usa PostgreSQL, SeaweedFS e Redis reais.

### 1.2 Regras Arquiteturais Inegociáveis
1. **PostgreSQL 18 + PostGIS 3.6 como Source of Truth**: Nenhuma entidade existe fora do banco relacional. Google Places é apenas provider externo consultado via Anti-Corruption Layer (ACL).
2. **Schema Controlado via Flyway**: `spring.jpa.hibernate.ddl-auto=validate`. Nunca utilizar `update` ou `create`. Toda alteração estrutural exige migração incremental (`V1` a `V25` consolidadas).
3. **Isolamento de Camadas (DDD / Clean Architecture)**:
   - `domain`: Modelos puros, enums e Value Objects livres de anotações Spring/JPA.
   - `application`: Casos de uso, orquestradores e portas (`application/port`).
   - `infrastructure`: Entidades JPA, repositórios Spring Data, adaptadores de persistência e clientes externos.
   - `presentation`: Controllers REST, DTOs de entrada/saída, tratamento RFC 7807.
4. **Governança de Privacidade e Segurança**:
   - `ReviewVisibilityPolicy` é a única autoridade para autorizar visualização de avaliações (`PUBLIC`, `FOLLOWERS`, `PRIVATE`).
   - Status `UNDER_REVIEW` e `REMOVED` saem imediatamente da visibilidade pública e feeds.
   - **Anonimato Absoluto**: Avaliações anônimas (`is_anonymous = true`) nunca têm seu autor interno exposto ou inferido via API pública, ranking ou metadados de notificação. A autorização interna é sempre vinculada ao `userId` real do autor (anti-IDOR).
   - **Localização Estática e Protegida**: Sem tracking contínuo. Coordenadas brutas (*raw GPS*) nunca são expostas ao cliente. Metadados EXIF/GPS de imagens são higienizados antes do upload no SeaweedFS.
5. **Separação de Reputação e Ranking**:
   - `UserReputation` é um snapshot factual e versionado de histórico do usuário. Não é score subjetivo e **não deve ser usado como peso de ranking** para não gerar elitismo algorítmico nem penalizar novos usuários.
   - Denúncias (`reports`) pertencem à moderação; nunca viram penalidade matemática de ordenação.
   - Sem modelos de Machine Learning ou embeddings nesta etapa.
6. **Consistência do Ciclo de Vida do Conteúdo**:
   - Edição de avaliações é parcial (PATCH), restrita a 24 horas a partir de `createdAt`, bloqueada para notas caso haja votos úteis recebidos (`helpfulCount > 0`) e proibida sob moderação (`UNDER_REVIEW`).
   - Exclusão é lógica (Soft Delete: `ReviewStatus.REMOVED`), mantendo integridade histórica, auditabilidade e dados físicos de check-in intactos.
   - Estatísticas de alvos (`RateableTargetStats`) utilizam **recomputação integral via PostgreSQL como Source of Truth**, sem deltas incrementais aproximados.

---

## 2. Status Macro do Backend e Ecossistema

A tabela a seguir consolida o estado real verificado no código-fonte, mapeando cada subsistema, suas evidências e o próximo passo imediato:

| Área / Subsistema | Status | Evidência no Código | Próximo Passo |
| :--- | :---: | :--- | :--- |
| **Fundação, Runtime & Config** | ✅ CONCLUÍDO | Java 25, Spring Boot 4.1.1, Virtual Threads ativadas, Actuator e Swagger UI integrados. | Manter configurações e compatibilidade. |
| **Banco de Dados & Migrations** | ✅ CONCLUÍDO | Flyway `V1` a `V25` ativas; PostGIS espacial; [V11](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V11__governance_roles_and_audit.sql) (roles e auditoria de reviews); [V12](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V12__outbox_messages.sql) a [V14](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V14__outbox_observability_indexes.sql) (Outbox); [V15](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V15__storage_object_quarantine.sql) (quarentena de storage); [V16](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V16__auth_sessions_replaced_by_index.sql) (sessões); [V17](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V17__users_account_state_consistency.sql) e [V21](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V21__users_account_status.sql) (ciclo de vida de contas C2); [V18](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V18__discussion_status_integrity.sql) a [V20](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V20__discussion_moderation_audit_logs.sql) (discussões e moderação C3); [V22](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V22__catalog_search_normalization.sql) (busca sem acento C5.1); [V23](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V23__place_claim_requests.sql) (reivindicações C9); [V24](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V24__business_responses.sql) e [V25](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V25__business_responses_removed_index.sql) (respostas oficiais C11). | Manter validação estrita de integridade referencial. |
| **Autenticação, JWT & Sessões** | ✅ CONCLUÍDO | [AuthController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AuthController.java), Argon2id, JWT assinado com claim `role`, authorities `ROLE_USER`/`MODERATOR`/`ADMIN` via [RewitUserPrincipal.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/security/RewitUserPrincipal.java), `@EnableMethodSecurity`. | Manter bloqueio estrito de IDOR e propagação da role persistida. |
| **Catálogo Base & Alvos Avaliáveis** | ✅ CONCLUÍDO | [PlaceController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/PlaceController.java), [ProductController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ProductController.java), raiz polimórfica `RateableTarget` e cálculo de estatísticas. | Expandir endpoints de gestão comercial de estabelecimentos. |
| **Integração Google Places** | ✅ CONCLUÍDO | [PlaceDiscoveryController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/PlaceDiscoveryController.java), isolamento via ACL, deduplicação por `place_external_references`. | Monitorar quotas e latência externa. |
| **Avaliações Multi-Alvo & Check-In** | ✅ CONCLUÍDO | [ReviewController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewController.java), multi-target atômico, validação de presença via PostGIS geofence. | Suporte completo a ciclo de vida (STEP 25) e mutações de moderação (STEP 26.1). |
| **Rede Social (Seguidores & Conexões)**| ✅ CONCLUÍDO | [UserFollowController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/UserFollowController.java), `user_follows`, bloqueio de auto-follow, integridade relacional. | Base consolidada para Feed V1 e Feed V2. |
| **Reações de Utilidade (Helpful)** | ✅ CONCLUÍDO | [ReviewHelpfulController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewHelpfulController.java), `review_reactions`, contagem e batching sem N+1. | Atua como trava de alteração de ratings no lifecycle. |
| **Perfil Público & Estatísticas** | ✅ CONCLUÍDO | [MeController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/MeController.java), [UserController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/UserController.java), agregação factual de reviews e seguidores. | Manter consistência de dados públicos. |
| **Denúncias & Moderação Preventiva** | ✅ CONCLUÍDO | [ReportController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReportController.java), rate limiting distribuído (ADR-013), quarentena preventiva (`UNDER_REVIEW`), mutações de resolução (`resolveAsAccepted`, `resolveAsRejected`) no domínio de [Report.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/Report.java). | Resolução transacional em lote via use case no STEP 26.2. |
| **Discussões, Comentários & Moderação (C3)** | ✅ CONCLUÍDO | [DiscussionController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/DiscussionController.java), [AdminDiscussionModerationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AdminDiscussionModerationController.java), auto-quarentena na 3ª denúncia, thread montada no servidor com estado por leitor (VISIBLE, REMOVED como tombstone, PENDING_REVIEW), moderação administrativa e auditoria append-only ([V18](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V18__discussion_status_integrity.sql) a [V20](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V20__discussion_moderation_audit_logs.sql)). | Manter integridade relacional da árvore hierárquica. |
| **Mídia de Avaliações (Imagens)** | ✅ CONCLUÍDO | [ReviewMediaController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewMediaController.java), SeaweedFS/S3, higienização EXIF/GPS, limite de 5 imagens. | GC de objetos sem referência concluído no STEP 28 (desligado por padrão). Retenção de blobs de mídias `REMOVED` permanece decisão separada. |
| **Notificações In-App** | ✅ CONCLUÍDO | [NotificationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/NotificationController.java), eventos acionados em follow, helpful, discussão e resposta com metadados estruturados (`rootDiscussionId`); enqueue de push na Outbox no momento da criação da `Notification`. | Notificações in-app permanecem síncronas; push assíncrono via Outbox com provider mock. |
| **Reputação V1 (Snapshot Factual)** | ✅ CONCLUÍDO | [ReputationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReputationController.java), [UserReputation.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/UserReputation.java), recálculo atômico e versionado. | Manter isolado do ranking de avaliações. |
| **Busca no Catálogo (C5.1 / Search V1)** | ✅ CONCLUÍDO | [CatalogSearchController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/CatalogSearchController.java), busca global insensível a acento via extensão `unaccent`, função imutável `rewit_search_normalize` e 5 índices GIN trigram ([V22](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V22__catalog_search_normalization.sql)) em places e products. | Monitorar performance dos índices GIN sob volume. |
| **Feed V1 (Social Cronológico)** | ✅ CONCLUÍDO | [FeedController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/FeedController.java), `GET /api/v1/feed`, estritamente cronológico, seguidos diretos, sem N+1. | Manter congelado sem alterações. |
| **Feed V2 (Ranking & Relevância)** | ✅ CONCLUÍDO — versão social + Cold Start | [FeedV2Controller.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/FeedV2Controller.java), [FeedV2QueryService.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/FeedV2QueryService.java), [FeedV2Service.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/FeedV2Service.java), [FeedV2Hydrator.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/FeedV2Hydrator.java), [FeedCandidateRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/FeedCandidateRepositoryAdapter.java). | Pipeline completo com fallback de Cold Start determinístico e sem N+1. |
| **Ciclo de Vida do Conteúdo (Content Lifecycle)** | ✅ CONCLUÍDO | [UpdateReviewUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/UpdateReviewUseCase.java), [DeleteReviewUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/DeleteReviewUseCase.java), [ReviewController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewController.java), [ReviewLifecycleIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ReviewLifecycleIntegrationTest.java), [ReviewLifecycleControllerIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/presentation/controller/ReviewLifecycleControllerIntegrationTest.java). | PATCH/DELETE funcionais, janela de 24h, trava de helpful, soft delete, lock pessimista, recomputação integral de stats no PG. |
| **Moderação Administrativa (Backoffice)** | ✅ CONCLUÍDO — STEPs 26.1 a 26.3 | Fundação V11, [ModerateReviewUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/ModerateReviewUseCase.java), [QueryAdminReportsUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/QueryAdminReportsUseCase.java), [AdminModerationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AdminModerationController.java), [AdminModerationDtos.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/dto/admin/AdminModerationDtos.java). | Manter; sem pendências nesta frente. |
| **Jobs Assíncronos & Outbox** | ✅ CONCLUÍDO — STEPs 27.0 a 27.4 | Migrações [V12](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V12__outbox_messages.sql) a [V14](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V14__outbox_observability_indexes.sql); domínio puro [OutboxMessage.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/OutboxMessage.java); porta [OutboxRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/OutboxRepository.java) com claim atômico `FOR UPDATE SKIP LOCKED`; use case [ProcessOutboxBatchUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/ProcessOutboxBatchUseCase.java); [PushNotificationHandler.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/outbox/PushNotificationHandler.java) entregando a `NotificationProvider` (`MockNotificationAdapter`). | O provider permanece o mock local — integração real com FCM/APNs pendente. |
| **Storage GC (Reconciliação de Mídia)** | ✅ CONCLUÍDO — STEP 28 | Contrato de listagem paginada, quarentena persistente (`V15`), grace period configurável, rechecagem sob lock, exclusão física idempotente, dry-run, scheduler desligado por padrão e métricas `rewit.storage_gc.*`. Ver [storage-reconciliation.md](../architecture/storage-reconciliation.md) e [ADR-010](../decisions/ADR-010-storage-garbage-collection.md). | Ativação operacional conforme o rollout seguro documentado (dry-run primeiro). |
| **Cleanup de Sessões Expiradas** | ✅ TECNICAMENTE CONCLUÍDO — STEP 29 | Job `@Scheduled` ligado por padrão: limpa IP/User-Agent de sessões inativas e expurga sessões expiradas sem sucessora (`expires_at < now AND replaced_by_session_id IS NULL`), em lotes `FOR UPDATE SKIP LOCKED`, sem recursão; índice `V16`; métricas `rewit.auth_session_cleanup.*`. Ver [authentication.md §4.5](../architecture/authentication.md) e [ADR-011](../decisions/ADR-011-session-retention-and-cleanup.md) (Proposto). | Decisão de produto/jurídico sobre a coleta de IP/User-Agent durante a sessão ativa. |
| **Observabilidade V1 (ADR-012)** | ✅ CONCLUÍDO | Métricas por scrape Prometheus (`/actuator/prometheus`), logs JSON no stdout, tracing HTTP (servidor e Google Places) via OTLP desligado sem endpoint, Actuator em porta de management própria (`8081`). Ver [observability.md](../architecture/observability.md) e [ADR-012](../decisions/ADR-012-observability.md). | Definir collector OTLP e Prometheus no deploy; não publicar a porta de management. |
| **Rate Limiting Distribuído (ADR-013)**| ✅ CONCLUÍDO | Porta `RateLimiter`, janela deslizante no Redis por script Lua atômico, chaves HMAC sem IP, `LOCAL_FALLBACK` com o Redis indisponível, métricas `rewit.rate_limit.*`. Login, refresh, cadastro, denúncias, comentários, uploads e respostas oficiais limitados. Ver [authentication.md §4.6](../architecture/authentication.md). | Limite por IP depende da decisão pendente da ADR-011. |
| **Ciclo de Vida de Contas (C2)** | ✅ CONCLUÍDO | `AccountStatusPolicy` lê o estado atual da conta no início das mutações de autoria (`401 ACCOUNT_DISABLED`); `chk_users_active_not_deleted` (V17); coluna `account_status` (`ACTIVE`, `DEACTIVATED`, `SUSPENDED`, `DELETED`) via [V21](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V21__users_account_status.sql); anonimização retroativa de autores excluídos; job de expurgo físico `AccountPurgeScheduler` com retenção de 30 dias. | Manter rotinas de retenção de dados e conformidade LGPD. |
| **Contas Comerciais & Reivindicações (C9)** | ✅ CONCLUÍDO | [BusinessAccountController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/BusinessAccountController.java), [AdminPlaceClaimController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AdminPlaceClaimController.java), tabela `place_claim_requests` via [V23](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V23__place_claim_requests.sql), aprovação/rejeição auditada com justificativa obrigatória e vínculo a `places.claimed_by_business_id`. | Evolução para gestão comercial do estabelecimento reivindicado. |
| **Endurecimento de Autorização de Moderação (C10)** | ✅ CONCLUÍDO | [ModeratorRolePolicy.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/ModeratorRolePolicy.java), revalidação em tempo de execução de papéis de moderação e estado ativo da conta contra o PostgreSQL em todas as rotas de moderação e consultas administrativas, mitigando tokens antigos (stale JWT). | Manter checagens sem bypass. |
| **Respostas Oficiais de Empresas (C11)** | ✅ CONCLUÍDO | [BusinessResponseController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/BusinessResponseController.java), [AdminBusinessResponseModerationController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/AdminBusinessResponseModerationController.java), tabelas `business_responses` e auditoria via [V24](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V24__business_responses.sql) e [V25](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V25__business_responses_removed_index.sql), respostas a avaliações e discussões raiz, rate limiting distribuído `BUSINESS_RESPONSE_CREATION`, moderação e fila administrativa de respostas removidas com restauração. | Manter isolamento de dados pessoais do autor da empresa. |
| **Aplicativo Mobile (Flutter)** | ✅ CONCLUÍDO | 13 módulos em Clean Architecture (`apps/mobile/lib/features/`), scanner de código de barras/QR Code, geolocalização sob demanda, criação multi-alvo, respostas oficiais, 618 testes verdes (`flutter test`). | Preparar integração com push notifications reais (FCM/APNs). |
| **Painel Administrativo Web (React)** | ✅ CONCLUÍDO | React 18, TypeScript, Vite (`apps/admin/`), autenticação com sessionStorage e renovação de token, dashboard de contadores, filas de denúncias de reviews, discussões, reivindicações de locais e respostas oficiais removidas, 82 testes verdes (`vitest run`). | Manter consistência de tipagem e isolamento de PII. |
| **Prontidão de Produção** | ⏳ PENDENTE | Sem backup/restore documentado, sem testes de carga automatizados. | Elaborar runbooks de backup WAL e suíte k6/Gatling. |
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
* `10f7283` — *docs: consolidar roadmap duravel do backend no checkpoint 24.4.0* (documento oficial de recuperação de contexto).
* `cb38aa1` — *feat: adicionar feed v2 orchestration* (`FeedV2Service`, `FeedV2CandidatePage` e testes integrados).
* `243c8b6` — *feat: adicionar hydration e projection do feed v2* (`FeedV2Hydrator`, projeção pública determinística sem N+1, testes de batching).
* `38ea4e9` — *feat: adicionar endpoint http publico do feed v2* (`FeedV2Controller`, `FeedV2QueryService`, `FeedV2PageResponse`, 15 testes de integração MockMvc, 846 testes verdes).
* `3fc7e8c` — *docs: consolidar fechamento do feed v2 no roadmap* (fechamento oficial da fase 24.4, auditoria de I/O e limitações conhecidas).
* `b73aebf` — *feat: adicionar fallback de cold start ao feed v2* (fallback público determinístico para usuários sem seguidos, 859 testes verdes).
* `c35141c` — *docs: consolidar fechamento do cold start no roadmap* (consolidação oficial do STEP 24.5).
* `f311b7b` — *feat: adicionar mutacoes de dominio do ciclo de vida da review* (STEP 25.1: métodos de domínio `editContent`, `softDelete`, `updateVisibility`, porta `ReviewRepository.findByIdForUpdate` com lock pessimista).
* `98854aa` — *feat: adicionar casos de uso do ciclo de vida da review* (STEP 25.2: `UpdateReviewUseCase` e `DeleteReviewUseCase`, orquestração transacional de review, alvos, reputação, mídias e stats).
* `575ed26` — *chore: limpar diagnostics do ciclo de vida da review* (STEP 25.2.1: eliminação pontual de warnings de compilação e null type safety no use case).
* `7dd3ee5` — *feat: expor ciclo de vida de reviews via api* (STEP 25.3: endpoints `PATCH /api/v1/reviews/{id}` e `DELETE /api/v1/reviews/{id}`, DTO `UpdateReviewRequest`, respostas 200 OK / 204 No Content, tratamento RFC 7807 e 19 testes de integração MockMvc com PostgreSQL real).
* `c878153` — *chore: auditar consistencia do ciclo de vida da review* (STEP 25.3.1: auditoria pós-implementação comprovando recomputação integral de stats no PostgreSQL, ausência de `deletedAt` em reviews, `visibility` consistente, boundary transacional, teste de concorrência determinístico com PostgreSQL real e zero warnings; 923 testes verdes).
* `7604a62` — *docs: consolidar fechamento do content lifecycle no roadmap* (fechamento oficial do STEP 25, consolidação das decisões arquiteturais e gates).
* `1cf1e8a` — *feat: adicionar fundacao de governanca e auditoria* (STEP 26.1: enum `Role` [USER, MODERATOR, ADMIN], coluna `users.role` com constraint Flyway `V11`, claim JWT `role`, authorities Spring Security `ROLE_*`, `@EnableMethodSecurity`, entidade append-only `ModerationAuditLog` com port e adapter JPA, mutações de domínio em `Review` e `Report`).
* `a74397f` — *chore: limpar diagnostics de roles e jwt* (STEP 26.1.1: eliminação dos 12 diagnostics `67109822` em `JwtRoleSecurityTest` e `RoleTest`, substituição de method references por lambdas explícitas, 0 warnings, 962 testes verdes).
* `e00ce33` — *feat: implementar casos de uso de moderacao administrativa* (STEP 26.2: `ModerateReviewUseCase` sob lock pessimista com resolução em lote de reports, audit log append-only, recomputação integral de stats no PostgreSQL, recálculo factual de reputação, soft delete de mídias e preservação de check-ins; `QueryAdminReportsUseCase` com filtros dinâmicos, paginação defensiva, ordenação determinística e projeção sem N+1; 39 novos testes automatizados).
* `8045614` — *chore: limpar diagnostics dos testes de moderacao administrativa* (STEP 26.2.1: eliminação dos 8 diagnostics de severidade 4 correspondentes a imports e campo `placeRepository` não utilizados nos testes de moderação administrativa, 0 warnings, suíte completa consolidada em 1001 testes verdes).
* `b1b7871` — *feat: implementar HTTP admin moderation (STEP 26.3)* (`AdminModerationController` com endpoints `GET /api/v1/admin/reports` e `POST /api/v1/admin/reviews/{reviewId}/moderate`, DTOs de apresentação `AdminModerationDtos`, correção do `GlobalExceptionHandler` para re-lançar `AccessDeniedException`/`AuthenticationException` evitando captura indevida como 500, 15 testes de integração MockMvc cobrindo 401/403 de segurança, remoção e restauração por MODERATOR/ADMIN, 404/409 de negócio e validação Bean Validation; 1016 testes verdes, 0 failures, 0 errors).
* `fb9fd5e` — *chore: limpar diagnostics do teste de http admin* (STEP 26.3.2: eliminação dos 4 diagnostics em `AdminModerationControllerIntegrationTest` — imports `AuthProvider`/`User` não utilizados, campo `userRepository` e helper `restoreRequest()` sem referências; diff exclusivamente de deleção, zero alteração comportamental, 1016 testes verdes, 0 diagnostics).
* `30a6191` — *feat: criar fundacao transacional do outbox* (STEP 27.1: migração Flyway `V12` com a tabela `outbox_messages`, domínio puro `OutboxMessage`/`OutboxStatus` com transições protegidas, porta `OutboxRepository` (`save`, `claimBatch`, `findById`, `countByStatus`) e adaptador JPA com claim atômico via `FOR UPDATE SKIP LOCKED`; 25 novos testes — 17 unitários de domínio e 8 de integração no PostgreSQL real; 1041 testes verdes, 0 failures, 0 errors).
* `d11c79a` — *feat: implementar dispatcher e worker do outbox* (STEP 27.2: use case puro `ProcessOutboxBatchUseCase` com ciclo `reclaim → claimBatch → handler fora da transação → finalização owner-checked`, porta `OutboxHandler` com classificação `TRANSIENT`/`PERMANENT`, `OutboxRetryPolicy` com backoff determinístico à prova de overflow, `OutboxErrorSanitizer` do `last_error`, reclaim atômico de leases expiradas, finalizações owner-checked no repositório, poller `@Scheduled` fino com `@EnableScheduling` central, `workerId` único por instância e migração `V13` com índice parcial de lease; 46 novos testes — domínio, aplicação, integração no PostgreSQL real e poller determinístico sem relógio real; 1087 testes verdes, 0 failures, 0 errors).
* `a4ed10f` — *feat: integrar notifications ao outbox* (STEP 27.3: `NotificationService` enfileira `PUSH_NOTIFICATION` na mesma transação dos quatro fluxos de notificação (`NEW_FOLLOWER`, `REVIEW_HELPFUL`, `NEW_DISCUSSION`, `DISCUSSION_REPLY`) com propagação `REQUIRED` e payload exclusivo `{"notificationId"}`; enum `OutboxMessageType` centralizando o message_type sem strings literais espalhadas pelo código de produção; `PushNotificationHandler` puro de `OutboxHandler` que extrai o `notificationId`, carrega a `Notification` pela porta e invoca a `NotificationProvider` com o conteúdo persistido (`MockNotificationAdapter` — sem push real); falhas permanentes → `FAILED` imediato e transitórias no mecanismo de retry/backoff herdado do 27.2; testes de atomicidade commit/rollback no PostgreSQL real com conexão observadora, E2E de entrega, retry sem sleep, falha permanente e anonimato; 30 novos testes — 15 do handler, 6 de enqueue no `NotificationServiceUnitTest`, 3 de atomicidade e 6 E2E; 1117 testes verdes, 0 failures, 0 errors).
* `add58f1` — *docs: consolidar storage gc e gate do step 28* (fechamento do STEP 28 — Storage GC: contrato de reconciliação, quarentena, rechecagem, exclusão física idempotente e operação agendada desligada por padrão; ver §8.11; 1243 testes verdes, 0 failures, 0 errors).

---

## 4. Arquitetura Atual e Princípios Consolidados

O backend adota o paradigma de **Arquitetura Hexagonal (Ports & Adapters)** combinado com **Domain-Driven Design (DDD)**:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           PRESENTATION LAYER                                    │
│   Controllers RESTful (ex: FeedV2Controller, ReviewController, PlaceController) │
│   DTOs de Request/Response • Validações Bean Validation • RFC 7807 Errors        │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │ (Invoca Casos de Uso / Facades)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            APPLICATION LAYER                                    │
│   Casos de Uso (UpdateReviewUseCase, DeleteReviewUseCase)                       │
│   Services de Aplicação (FeedV2QueryService, FeedV2Service, FeedV2Hydrator)     │
│   Portas de Saída / Interfaces (ReviewRepository, RateableTargetStatsRepository)│
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
- **PostgreSQL como Fonte Única da Verdade**: Todas as consultas, agregações analíticas e operações transacionais derivam do PostgreSQL.
- **PostGIS para Operações Espaciais**: Distâncias geodésicas, validação de check-in e centroides são processados no banco via funções espaciais nativas (`ST_DWithin`, coordenadas EPSG:4326).
- **Sem N+1 Queries**: Todo enriquecimento em massa (targets, perfis de autor, contagem de helpful e flags de interação) ocorre obrigatoriamente através de **batch loaders** (`IN (:ids)` e agregações agrupadas em lote).
- **Tratamento de Mídia**: Higienização estrita de streams de imagens. Metadados de geolocalização e identificadores de câmera são expurgados no backend antes de persistir o arquivo no SeaweedFS.
- **Controle Pessimista de Concorrência**: Mutações concorrentes sobre o agregado de avaliações utilizam `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`) gerenciado pelo Spring Transactional (`Propagation.REQUIRED`), garantindo isolamento total durante o recálculo de agregados.

---

## 5. Feed V2 — Estado Atual, Pipeline Oficial, Cold Start e Limitações Conhecidas

O Feed V2 substitui a ordenação puramente cronológica por uma experiência de relevância determinística e transparente que prioriza conexões sociais diretas, autenticidade (verificação presencial no local) e utilidade comunitária.

A versão inicial social e o fallback determinístico de Cold Start encontram-se **✅ CONCLUÍDOS**, auditados contratualmente e validados contra PostgreSQL real.

### 5.1 Pipeline Oficial Ponta a Ponta

```
[Cliente HTTP]
       │
       ▼ (GET /api/v2/feed?page=P&size=S)
[FeedV2Controller] ── Extrai requesterUserId (JWT) e captura referenceTime = Instant.now()
       │
       ▼
[FeedV2QueryService] ── Facade de aplicação com transação readOnly
       │
       ├─► [FeedV2Service] (Orquestrador de Candidatos com Fallback)
       │         │
       │         ├─► [FeedCandidateRepository] (PostgreSQL Retrieval)
       │         │         ├─ Social Retrieval (até 100 candidatos da rede social)
       │         │         └─ Se Social = vazio ──► Discovery Retrieval (até 100 candidatos públicos globais)
       │         │
       │         ├─► [FeedV2Ranker] (Ranking determinístico) ── 100% in-memory (0 queries)
       │         ├─► [FeedV2Diversifier] (Espaçamento de autores/targets) ── 100% in-memory (0 queries)
       │         └─► [Slicing] ── Recorte da fatia paginada [offset, offset + size] em memória (0 queries)
       │
       └─► [FeedV2Hydrator] (Hydration pós-fatiamento da página)
                 ├─ Query H1: carga profunda das reviews fatiadas (findByIdIn)
                 ├─ Query H2: carga de todos os targets da página (com rating/comentários)
                 ├─ Query H3: busca em lote de perfis (apenas autores não-anônimos)
                 ├─ Query H4: contagem de votos de helpful da página
                 └─ Query H5: verificação de helpful pelo requester (isHelpfulByMe)
       │
       ▼
[FeedV2PageResponse] ── Mapeamento DTO público e retorno HTTP 200 OK
```

### 5.2 Arquitetura do Cold Start (STEP 24.5 / 24.5.1)
- **Fallback Puro**: Sem mistura de candidatos sociais e públicos na mesma requisição. Se o retrieval social retornar $\ge 1$ item, a descoberta não é acionada.
- **Elegibilidade**: Reviews com `status = ACTIVE`, `visibility = PUBLIC` e `userId != requesterId` (auto-exclusão estrita).
- **Anonimato**: Máscara pública mantida na projeção; `authorId` interno é usado em memória exclusivamente para espaçamento no diversificador.

---

## 6. Content Lifecycle — Estado Atual, Arquitetura e Regras de Governança (STEP 25)

O ciclo de vida do conteúdo foi oficialmente consolidado no **STEP 25**, permitindo que o próprio autor de uma avaliação possa realizar **atualizações parciais controladas (PATCH)** e **exclusão lógica (DELETE / Soft Delete)**, com garantia de integridade transacional absoluta entre reviews, alvos, estatísticas, votos úteis, moderação, reputação e feeds.

### 6.1 Visão Geral dos Incrementos Concluídos
* **STEP 25.1 (Domínio & Persistência — `f311b7b`)**:
  - Métodos mutadores controlados na entidade de domínio [Review.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/Review.java): `editContent()`, `softDelete()`, `updateExperienceText()`, `updateTargetRating()`, `updateVisibility()`.
  - Método com lock pessimista na porta de persistência [ReviewRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/repository/ReviewRepository.java): `Optional<Review> findByIdForUpdate(UUID id)`.
  - Implementação JPA correspondente utilizando `LockModeType.PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`).
* **STEP 25.2 (Casos de Uso na Camada Application — `98854aa`)**:
  - [UpdateReviewUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/UpdateReviewUseCase.java): Coordena autorização anti-IDOR, janela de 24h, bloqueio por votos de helpful, recálculo atômico de estatísticas de alvos e atualização da reputação factual.
  - [DeleteReviewUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/DeleteReviewUseCase.java): Executa soft delete lógico (`ReviewStatus.REMOVED`), marcação lógica de mídias ativas como `REMOVED`, recálculo integral de estatísticas e dedução factual da reputação.
  - Testes de usecase unitários e de integração com PostgreSQL real ([ReviewLifecycleIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ReviewLifecycleIntegrationTest.java)).
* **STEP 25.2.1 (Limpeza de Diagnostics — `575ed26`)**:
  - Eliminação pontual de warnings de compilação, null type safety e imports não utilizados.
* **STEP 25.3 (Exposição HTTP da API REST — `7dd3ee5`)**:
  - Endpoints REST expostos em [ReviewController.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/controller/ReviewController.java): `PATCH /api/v1/reviews/{reviewId}` e `DELETE /api/v1/reviews/{reviewId}`.
  - DTO representacional `UpdateReviewRequest` em [ReviewPresentationDtos.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/dto/review/ReviewPresentationDtos.java).
  - Tratamento padronizado de erros RFC 7807 (`400`, `401`, `403`, `404`, `409`).
  - Suíte completa de 19 testes de integração MockMvc cobrindo ponta a ponta todas as regras da API ([ReviewLifecycleControllerIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/presentation/controller/ReviewLifecycleControllerIntegrationTest.java)).
* **STEP 25.3.1 (Auditoria de Consistência & Concorrência — `c878153`)**:
  - Auditoria profunda confirmando conformidade arquitetural integral com o STEP 25.0.
  - Prova empírica de recomputação agregada integral no PostgreSQL (sem deltas incrementais).
  - Teste determinístico de concorrência com PostgreSQL real e sincronização explícita via `CountDownLatch`.
  - Zero warnings em todas as classes do backend. Suíte estabilizada em 923 testes verdes.

### 6.2 Fluxo Arquitetural Ponta a Ponta
O processamento de mutações de lifecycle obedece ao isolamento de camadas estrito:

```text
HTTP Request (PATCH / DELETE)
       │
       ▼
[ReviewController] ── Extrai requesterUserId via Authentication.getName() (JWT Bearer)
       │
       ▼
[UpdateReviewUseCase / DeleteReviewUseCase] ── Início do boundary @Transactional
       │
       ├─► [ReviewRepository.findByIdForUpdate] (SELECT ... FOR UPDATE no PostgreSQL)
       │
       ├─► Verificação de Autorização Anti-IDOR (review.userId == requesterUserId)
       ├─► Validação de Estado (ACTIVE / UNDER_REVIEW / REMOVED)
       ├─► Validação de Janela Temporal de 24h (para PATCH)
       ├─► Verificação de Trava de Helpful (helpfulCount == 0 para alteração de notas)
       │
       ├─► [Review Domain Mutation] (review.editContent / review.softDelete)
       ├─► [ReviewRepository.save] (Atualização no PostgreSQL)
       │
       ├─► [RateableTargetStatsRepository.recalculateAndSave] (Ordenado por targetId ASC)
       │         └─► Recomputação integral via SELECT AVG, COUNT na tabela review_targets
       │
       ├─► [ReputationService.recalculateAndSave] (Atualização de snapshot factual)
       ├─► [ReviewMediaRepository.saveAll] (Marcação lógica REMOVED em mídias no DELETE)
       │
       ▼
Commit da Transação (Liberação dos Locks Pessimistas)
       │
       ▼
HTTP Response: 200 OK (com ReviewResponse) no PATCH / 204 No Content no DELETE
```

### 6.3 Regras de Edição Parcial (PATCH)
1. **Autenticação e Identidade**:
   - Requester extraído obrigatoriamente do JWT (`Authentication.getName()`).
   - Bloqueio estrito de IDOR: se `requesterUserId != review.userId`, a requisição é rejeitada com `403 Forbidden` (`REVIEW_NOT_OWNED`).
   - O payload HTTP nunca recebe nem confia em `userId`, `authorId`, `reviewId` ou `createdAt`.
2. **Janela Máxima de Tolerância**:
   - Edições de avaliações são permitidas exclusivamente dentro da **janela de 24 horas** a partir de `createdAt`.
   - Limite exato no boundary de 24h é aceito com sucesso; requisições após 24h são rejeitadas com `409 Conflict` (`REVIEW_EDIT_WINDOW_EXPIRED`).
3. **Semântica de Patch Parcial e Proteção contra Nulos**:
   - Campos omitidos ou explicitamente `null` no JSON da requisição significam "não alterar", preservando fielmente os dados existentes.
   - `experienceText`: se `null`, mantém o texto atual.
   - `targetRatings`: se `null`, mantém as notas atuais dos alvos.
   - `isAnonymous`: se `null`, mantém a flag atual.
   - `visibility`: se `null`, mantém a visibilidade atual.
4. **Regras de Votos de Utilidade (Helpful Locking)**:
   - Se a avaliação **não possui votos de útil** (`helpfulCount == 0`): permite alteração de texto e de notas dos alvos dentro da janela de 24h.
   - Se a avaliação **já recebeu votos de útil** (`helpfulCount > 0`): a alteração de notas é bloqueada com `409 Conflict` (`REVIEW_EDIT_RATING_BLOCKED_BY_HELPFUL`), impedindo estelionato de utilidade. A alteração de texto (`experienceText`) continua permitida dentro das 24h.
5. **Regras de Moderação**:
   - Avaliação com `status = UNDER_REVIEW`: alteração de conteúdo rejeitada com `409 Conflict` (`REVIEW_UNDER_REVIEW_MUTATION_DENIED`).
   - Avaliação com `status = REMOVED`: rejeitada com `409 Conflict` (`REVIEW_ALREADY_REMOVED`).
6. **Multi-Target**:
   - Alvos estruturais são estritamente imutáveis (proibida adição ou remoção de targets via PATCH).
   - Notas de targets existentes podem ser atualizadas pontualmente; apenas os alvos com nota realmente alterada acionam recomputação de estatísticas.
7. **Imutabilidade Temporal**:
   - `createdAt` é `@Column(updatable = false)` e permanece rigorosamente idêntico.
   - `updatedAt` reflete o instante exato da mutação.

### 6.4 Regras de Soft Delete (DELETE)
1. **Transição de Status**:
   - A avaliação transita para `ReviewStatus.REMOVED`.
   - Mídias vinculadas ativas transitam para `ReviewMediaStatus.REMOVED`.
   - Mutações subsequentes sobre review removida são rejeitadas com `409 Conflict` (`REVIEW_ALREADY_REMOVED`).
2. **Exclusão a partir de Moderação**:
   - Se a review estiver em quarentena (`UNDER_REVIEW`), o autor legítimo pode excluí-la voluntariamente para retirá-la imediatamente do ar.
3. **Preservação de Dados e Integridade Física**:
   - O soft delete **não executa hard delete** em nenhuma tabela.
   - Permanecem preservados no PostgreSQL: o registro em `reviews`, os itens em `review_targets`, os votos em `review_reactions`, as denúncias em `reports` e os check-ins em `check_ins`.
   - Os arquivos binários de imagem no SeaweedFS permanecem preservados. Mídias `REMOVED` continuam sendo referência e não são coletadas pelo GC de storage do STEP 28; uma política de retenção para elas é uma decisão separada.

### 6.5 Decisão de Stats: Recomputação Integral via PostgreSQL
Fielmente alinhada à **Estratégia B do STEP 25.0**:
* **Fonte Única da Verdade**: A tabela `rateable_target_stats` é atualizada através do método `recalculateAndSave(targetId)` em [RateableTargetStatsRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/RateableTargetStatsRepositoryAdapter.java).
* **Ausência de Deltas**: Não existe e nunca existiu delta incremental (`recordRatingDelta`), eliminando qualquer risco de drift numérico por arredondamento cumulativo ou concorrência.
* **Consulta de Agregação Nativa**:
  ```sql
  SELECT
      COALESCE(ROUND(AVG(rt.rating), 2), 0.00) AS average_rating,
      COUNT(rt.id) AS reviews_count
  FROM review_targets rt
  JOIN reviews r ON r.id = rt.review_id
  WHERE rt.target_id = :targetId
    AND r.status = 'ACTIVE'
  ```
* **Lock Pessimista e Ordenação**: Para prevenir deadlocks em reviews multi-alvo, os identificadores são processados em ordem lexicográfica ascendente (`targetId ASC`), e cada linha de stats é atualizada sob `SELECT ... FOR UPDATE`.

### 6.6 Recálculo Factual de Reputação
* A reputação do usuário ([UserReputation](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/UserReputation.java)) é um snapshot puramente factual e versionado.
* **Transição de Anonimato**:
  - `Público -> Anônimo`: A review deixa de pontuar como autoria pública verificada; o snapshot deduz os pontos correspondentes.
  - `Anônimo -> Público`: A review volta a pontuar e o snapshot restabelece a pontuação.
* **Soft Delete**:
  - A review em status `REMOVED` é expurgada do cálculo de reputação, deduzindo os pontos anteriormente concedidos pela criação da review e por check-in no local.
* Edições puramente textuais ou de notas não alteram desnecessariamente o snapshot de reputação.

### 6.7 Imutabilidade de Check-in e Governança de Privacidade
1. **Inviolabilidade de Presença Presencial**:
   - Os parâmetros de check-in (`contextPlaceId`, `userLatitude`, `userLongitude`, `locationAccuracyMeters` e `isVerifiedOnSite`) são blindados contra qualquer alteração via PATCH.
   - O registro físico na tabela `check_ins` permanece íntegro no banco mesmo após o soft delete da avaliação.
2. **Anonimato no Lifecycle**:
   - Autores de reviews anônimas possuem permissão total para editar ou excluir suas publicações, pois a autorização interna se baseia estritamente no `userId` extraído do JWT.
   - A resposta da API pública (`ReviewResponse`) continua mascarando integralmente a identidade do autor (`author = null`), impedindo qualquer vazamento de dados.

### 6.8 Contrato da API REST e Códigos de Erro RFC 7807
* **Endpoints Expostos**:
  - `PATCH /api/v1/reviews/{reviewId}` ── Retorna `200 OK` com o DTO `ReviewResponse` atualizado.
  - `DELETE /api/v1/reviews/{reviewId}` ── Retorna `204 No Content` sem corpo.
* **Mapeamento Consistente de Erros**:
  - `400 BAD_REQUEST` / `INVALID_INPUT`: Payload inválido, nota fora de [1.0, 5.0], texto > 2000 chars, visibilidade inválida.
  - `401 UNAUTHORIZED` / `UNAUTHORIZED`: Ausência ou inconsistência de token JWT Bearer.
  - `403 FORBIDDEN` / `REVIEW_NOT_OWNED`: Tentativa de edição ou exclusão por usuário que não seja o autor legítimo.
  - `404 NOT_FOUND` / `REVIEW_NOT_FOUND`: Review inexistente no banco de dados.
  - `409 CONFLICT` / `REVIEW_UNDER_REVIEW_MUTATION_DENIED`: Tentativa de editar review em quarentena de moderação.
  - `409 CONFLICT` / `REVIEW_ALREADY_REMOVED`: Tentativa de editar ou excluir review que já sofreu soft delete.
  - `409 CONFLICT` / `REVIEW_EDIT_WINDOW_EXPIRED`: Tentativa de editar após 24 horas da publicação.
  - `409 CONFLICT` / `REVIEW_EDIT_RATING_BLOCKED_BY_HELPFUL`: Tentativa de alterar notas quando já existem votos de útil.

### 6.9 Preservação e Não-Regressão de Feeds e Search
* **Feed V1 (Social Cronológico)**:
  - Nenhuma alteração de código.
  - Reviews em status `REMOVED` deixam imediatamente de ser retornadas (filtradas por `status = 'ACTIVE'`).
  - Edição de conteúdo preserva `createdAt`, garantindo que reviews editadas não realizam bump artificial na timeline.
* **Feed V2 (Relevância & Descoberta)**:
  - Nenhuma alteração de código.
  - Retrieval exclui reviews não-ativas na fonte (`r.status = 'ACTIVE'`).
  - A hidratação confirma o status ativo como defesa adicional em profundidade.
  - Cold Start exclui reviews removidas do discovery pool.
* **Search V1 (Catálogo)**:
  - Permanece 100% isolado, operando exclusivamente sobre lugares e alvos avaliáveis.

### 6.10 Concorrência Determinística sob Lock Pessimista
* A concorrência transacional é garantida pelo Spring `@Transactional` (`Propagation.REQUIRED`) combinado com `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) no PostgreSQL.
* O lock é mantido durante toda a execução do caso de uso, garantindo que efeitos derivados (targets, stats, reputação, mídias) sejam comitados atomicamente.
* **Validação Empírica em Teste**:
  - O teste [shouldPreventDoubleDeleteAndStateCorruptionUnderConcurrentCallsInPostgres](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ReviewLifecycleIntegrationTest.java#L368) valida duas threads simultâneas disparadas via `CountDownLatch` contra o PostgreSQL real: exatamente uma thread executa o delete com sucesso (204) e a outra é bloqueada pelo lock pessimista e rejeitada deterministicamente com `409 REVIEW_ALREADY_REMOVED`, mantendo a contagem de estatísticas exata (sem double-decrement).

### 6.11 Limitações Conhecidas e Evolução Futura do Lifecycle
* **Histórico de Edições (Audit Trail)**: No MVP do lifecycle, apenas `updatedAt` registra a alteração mais recente. Histórico completo de revisões (*review revisions table*) permanece como evolução futura.
* **Sem Restauração de Avaliação (Undelete)**: O soft delete pelo autor é definitivo para o usuário comum; restauração de publicações excluídas por engano permanece restrita a futura moderação administrativa.
* **Garbage Collection de Imagens**: O soft delete marca mídias lógicas como `REMOVED`, mas não expurga blobs físicos do SeaweedFS de forma síncrona. O GC de storage do STEP 28 (job direto, sem Outbox) remove apenas objetos sem nenhuma linha em `review_media`; blobs de mídias `REMOVED` dependem de uma política de retenção ainda não decidida.

---

## 7. Moderação Administrativa (Backoffice) — STEP 26

A moderação administrativa evolui o mecanismo preventivo comunitário (quarentena após 3 denúncias) para um subsistema completo de governança corporativa, análise humana e backoffice, permitindo que operadores com privilégios específicos (`MODERATOR` e `ADMIN`) atuem sobre conteúdos reportados e avaliações em quarentena com garantia de integridade, imutabilidade de auditoria e consistência com o restante da plataforma.

### 7.1 Visão Geral e Descoberta Arquitetural (STEP 26.0)
No STEP 26.0 foram consolidadas as diretrizes de governança e arquitetura:
1. **Modelo de Decisão Review-Centric**: A moderação atua sobre a avaliação como agregado principal (`reviews`). Ao moderar uma review, todas as denúncias pendentes associadas a ela são resolvidas em lote na mesma transação atômica.
2. **Separação de Privilégios**: Hierarquia simples e direta baseada em `ROLE_USER`, `ROLE_MODERATOR` e `ROLE_ADMIN`. Moderadores e administradores possuem permissão de acesso a rotas `/api/v1/admin/**`.
3. **Auditoria Append-Only Obrigatória**: Toda e qualquer ação de moderação gera compulsoriamente um registro imutável em `moderation_audit_logs`, registrando quem moderou, quando, qual a decisão, ação e justificativa textual.
4. **Resolução de Reports**: Reports não são apagados nem sofrem soft delete; passam do status `PENDING` para `ACCEPTED` (se a denúncia foi julgada procedente) ou `REJECTED` (se descartada).
5. **Efeitos Colaterais em Cascata**: Deferimento com remoção da review aciona a mesma cadeia atômica do lifecycle (recomputação integral de estatísticas de alvos e dedução da reputação factual do autor).

---

### 7.2 STEP 26.1 — Fundação de Governança, Roles e Auditoria (✅ CONCLUÍDO)
A fundação técnica, estrutural e de domínio foi implementada e validada no commit `1cf1e8a`, entregando:

#### 1. Governança e Modelo de Roles
* **Enum [Role.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/enums/Role.java)**:
  - Valores: `USER`, `MODERATOR`, `ADMIN`.
* **Persistência em `users`**:
  - Coluna `users.role` com valor padrão `'USER'`, obrigatoriedade `NOT NULL` e `CHECK constraint` no banco limitando os valores válidos.
  - Carregamento da role na entidade de domínio [User.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/User.java).
  - Alteração controlada de role através do domínio via método `changeRole(Role newRole)`.
  - Persistência JPA em formato textual (`@Enumerated(EnumType.STRING)`).

#### 2. JWT e Spring Security
* **Claim JWT `"role"`**:
  - Emissão no token baseada estritamente na role persistida do usuário ([JwtTokenProvider.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/security/JwtTokenProvider.java)).
  - Fluxos de autenticação (login) e renovação de sessão (refresh token) utilizam a role real consultada no banco via [AuthService.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/AuthService.java).
  - **Nenhuma role é aceita diretamente do cliente**: Payloads de cadastro e autenticação não possuem campo de role; privilégios não podem ser injetados externamente.
  - **Fallback Seguro**: Caso a claim não esteja presente ou seja inválida no token, o sistema assume fallback determinístico para `Role.USER`.
* **Authorities e Spring Security**:
  - [RewitUserPrincipal.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/security/RewitUserPrincipal.java) converte a role para authorities padronizadas do Spring Security:
    - `ROLE_USER`
    - `ROLE_MODERATOR`
    - `ROLE_ADMIN`
  - Suporte correspondente no [JwtAuthenticationConverter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/security/JwtAuthenticationConverter.java).
  - Anotação `@EnableMethodSecurity` ativada em [SecurityConfig.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/config/SecurityConfig.java) como fundação para proteção declarativa de métodos e endpoints (`@PreAuthorize`).

#### 3. Auditoria de Moderação
* **Entidade de Domínio Pura [ModerationAuditLog.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/ModerationAuditLog.java)**:
  - Registro de auditoria contendo: `id`, `reviewId`, `moderatorId`, `action`, `decision`, `reason` e `createdAt`.
  - Enums de suporte:
    - `ModerationAction`: `REMOVE_REVIEW`, `RESTORE_REVIEW`.
    - `ModerationDecision`: `ACCEPTED`, `REJECTED`.
  - Validações rigorosas de domínio: obrigatoriedade de identificadores, ação, decisão e justificativa com tamanho mínimo/máximo (`reason` não nula e não vazia).
* **Porta e Persistência Append-Only**:
  - Porta de aplicação [ModerationAuditLogRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/ModerationAuditLogRepository.java).
  - Adaptador JPA [ModerationAuditLogRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/ModerationAuditLogRepositoryAdapter.java) e interface Spring Data [ModerationAuditLogJpaRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/repository/ModerationAuditLogJpaRepository.java).
  - Persistência estritamente append-only: a infraestrutura expõe apenas operações de inserção (`save`), sem métodos de alteração ou exclusão física.

#### 4. Mutações de Moderação no Agregado de Review
A entidade [Review.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/Review.java) recebeu operações específicas de moderação com regras de transição estritas:
* `markRemovedByModerator(UUID moderatorId, String reason)`:
  - Transições válidas: `ACTIVE -> REMOVED` e `UNDER_REVIEW -> REMOVED`.
  - Atualiza o status para `ReviewStatus.REMOVED`, registra o instante de modificação e invalida exibições públicas.
* `restoreFromUnderReview(UUID moderatorId, String reason)`:
  - Transição válida: `UNDER_REVIEW -> ACTIVE`.
  - Restaura uma avaliação em quarentena de volta ao status ativo.
  - Regra de bloqueio: `REMOVED -> ACTIVE` **não é permitido** por essa operação (avaliação que já sofreu soft delete não pode ser reativada por este método).

#### 5. Resolução Administrativa no Domínio de Reports
A entidade [Report.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/Report.java) recebeu mutações de resolução:
* `resolveAsAccepted(UUID moderatorId, String notes)`: Transita o status para `ReportStatus.ACCEPTED`.
* `resolveAsRejected(UUID moderatorId, String notes)`: Transita o status para `ReportStatus.REJECTED`.
* **Proteção contra Re-Resolução**: Ambas as operações exigem estritamente que o status atual seja `PENDING`. Tentativas de resolver uma denúncia já resolvida lançam exceção de domínio com `409 Conflict`.

#### 6. Migração Flyway V11
A migração [V11__governance_roles_and_audit.sql](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V11__governance_roles_and_audit.sql) consolidou:
* Adição da coluna `role VARCHAR(32) NOT NULL DEFAULT 'USER'` na tabela `users`.
* Constraint `users_role_check` limitando os valores a `'USER'`, `'MODERATOR'`, `'ADMIN'`.
* Criação da tabela `moderation_audit_logs` com chaves primárias, foreign keys para `reviews(id)` e `users(id)`, constraints de validação de valores para `action` e `decision` e índices de busca por `review_id` e `moderator_id`.

---

### 7.3 STEP 26.1.1 — Limpeza de Diagnostics de Roles e JWT (✅ CONCLUÍDO)
Após a entrega funcional do STEP 26.1, uma etapa de refinamento e manutenção de código foi concluída no commit `a74397f`:
* **12 Diagnostics `67109822` Identificados**:
  - O analisador estático do Eclipse JDT acusou avisos de conversão não verificada em referências a métodos (`GrantedAuthority::getAuthority`) no contexto de coleções tipadas.
* **Substituição por Lambdas Explícitas**:
  - As referências a método problemáticas foram substituídas pontualmente por expressões lambdas explícitas (`ga -> ga.getAuthority()`) nas classes de teste afetadas ([JwtRoleSecurityTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/infrastructure/security/JwtRoleSecurityTest.java) e [RoleTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/domain/enums/RoleTest.java)).
* **Garantias de Qualidade**:
  - Nenhuma alteração semântica, arquitetural ou funcional.
  - Nenhuma utilização de anotações supressoras (`@SuppressWarnings`).
  - **Diagnostics finais**: `0`.
  - Testes direcionados de Role: `5/5` passando.
  - Testes direcionados de JWT e Security: `5/5` passando.
  - Suíte completa de testes: **962/962** aprovados (0 failures, 0 errors, 0 skipped).

---

### 7.4 STEP 26.2 — Implementação dos Casos de Uso de Moderação Administrativa (✅ CONCLUÍDO)

A camada de aplicação para a moderação administrativa foi implementada no commit `e00ce33`, estabelecendo os casos de uso orquestradores, novas portas/queries de persistência e validações rigorosas de negócio:

#### 1. `ModerateReviewUseCase`
Orquestrador transacional (`@Transactional`) responsável pela execução das decisões administrativas tomadas por moderadores e administradores:
* **Lock Pessimista da Review**: Aquisição obrigatória de lock de escrita (`findByIdForUpdate`) no início da transação, garantindo serialização estrita contra edições ou exclusões concorrentes do autor.
* **Proibição de Auto-Moderação**: Validação de integridade que impede moderadores de moderar suas próprias avaliações (`moderatorId == review.getAuthorUserId()` lança `CANNOT_MODERATE_OWN_REVIEW`, `403 Forbidden`).
* **Proibição de Moderação Conflitante**: Moderador que possua denúncia pendente contra a mesma avaliação não pode atuar como moderador dela (`reportRepository.existsPendingByReviewIdAndReporterUserId` lança `REPORTER_CANNOT_MODERATE_REVIEW`, `403 Forbidden`).
* **Validação de Justificativa**: Exigência contratual de justificativa obrigatória com comprimento entre 15 e 1000 caracteres (`INVALID_JUSTIFICATION_LENGTH`, `400 Bad Request`).
* **Ação `REMOVE_REVIEW`**:
  - Invoca o método de domínio `review.markRemovedByModerator(moderatorId, reason, justification, now)`.
  - Valida transição de estado (se a avaliação já estiver `REMOVED`, lança `REVIEW_ALREADY_REMOVED`, `409 Conflict`).
  - Resolve em lote todas as denúncias pendentes associadas à avaliação como aceitas (`resolveAsAccepted`).
  - Recomputação integral de estatísticas de alvos (`RateableTargetStatsRepository.recalculateAndSave`) com ordenação determinística dos IDs dos alvos para mitigar riscos de deadlock.
  - Recomputação factual e versionada da reputação do autor da avaliação (`ReputationService.recalculateAndSave(authorId)`).
  - Remoção lógica de mídias associadas (`ReviewMediaRepository.softDeleteByReviewId`).
  - Preservação física e histórica de check-ins (`check_ins` permanecem intactos).
* **Ação `RESTORE_REVIEW`**:
  - Invoca o método de domínio `review.restoreFromUnderReview(moderatorId, justification, now)`.
  - Valida que o status atual seja estritamente `UNDER_REVIEW` (caso contrário lança `REVIEW_NOT_UNDER_REVIEW`, `409 Conflict`).
  - Resolve em lote todas as denúncias pendentes associadas como rejeitadas (`resolveAsRejected`).
  - Retorna o status da avaliação para `ACTIVE`, restabelecendo sua visibilidade pública e participação nos feeds.
* **Contagem de Denúncias Afetadas**: Retorna `reportsAffectedCount` no resultado da moderação (`ModerateReviewResult`).
* **Trilha de Auditoria Imutável**: Gravação append-only em `ModerationAuditLog` (`moderationAuditLogRepository.save(auditLog)`) contendo identificadores da review, moderador, ação, decisão, justificativa, notas opcionais e timestamp `createdAt`.

#### 2. `QueryAdminReportsUseCase`
Caso de uso de triagem e consulta administrativa de denúncias para o backoffice via [QueryAdminReportsFilter](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/dto/report/ReportDtos.java):
* **Filtros Dinâmicos**: Suporte a filtragem por status (`ReportStatus`: `PENDING`, `ACCEPTED`, `REJECTED`), motivo (`ReportReason`), `reviewId` e `reporterUserId`.
* **Paginação Defensiva**: Padrão de paginação `page = 0`, `size = 20`, com teto máximo de `100` itens por página. Validações estritas lançam `INVALID_PAGE_SIZE` (`size <= 0` ou `size > 100`, `400 Bad Request`) e `INVALID_PAGE` (`page < 0`, `400 Bad Request`).
* **Ordenação Determinística**: Suporte a ordenação determinística no PostgreSQL (`ORDER BY r.created_at ASC/DESC, r.id ASC/DESC`).
* **Projeção Enriquecida sem N+1**: Busca paginada de denúncias combinada com carregamento em lote das avaliações correspondentes via `reviewRepository.findByIdIn(reviewIds)`.
* **Proteção de Dados Sensíveis**: Mapeamento seguro para [AdminReportView](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/dto/report/ReportDtos.java), revelando o verdadeiro autor interno da avaliação (`reviewAuthorUserId`) mesmo em avaliações com visibilidade anônima (`isAnonymous = true`), sem expor credenciais, hashes ou dados privados.

#### 3. Extensões na Camada de Persistência
* [ReportRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/ReportRepository.java): Novos contratos `existsPendingByReviewIdAndReporterUserId`, `findPendingByReviewId` e `findAllPaged`.
* [ReportJpaRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/repository/ReportJpaRepository.java): Queries JPQL paginadas e métodos de busca por predicados.
* [ReportRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/ReportRepositoryAdapter.java): Implementação da porta mapeando entidades JPA para modelos de domínio e DTOs paginados [PageResult](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/dto/common/PageResult.java).
* [ModerationAuditLogJpaRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/repository/ModerationAuditLogJpaRepository.java): Método `findByReviewIdOrderByCreatedAtDesc`.

#### 4. Validação e Testes Automatizados (39 Novos Testes)
* [ModerateReviewUseCaseUnitTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ModerateReviewUseCaseUnitTest.java): **25 testes** unitários com mocks cobrindo todas as invariantes de negócio, auto-moderação, repórteres conflitantes, limites de justificativa, lock pessimista e verificações de chamadas em ordem (`InOrder`).
* [QueryAdminReportsUseCaseUnitTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/QueryAdminReportsUseCaseUnitTest.java): **10 testes** unitários cobrindo paginação default, limite de 100, validação de tamanhos inválidos, filtros dinâmicos, ordenação ASC/DESC, hidratação em lote sem N+1 e proteção de autor anônimo.
* [ModerateReviewIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ModerateReviewIntegrationTest.java): **4 testes** de integração de ponta a ponta no PostgreSQL 18 + PostGIS real:
  - *Cenário 1*: Remoção administrativa completa com resolução de denúncias pendentes, soft delete, recálculo de stats/reputação e audit log.
  - *Cenário 2*: Restauração administrativa de review em quarentena (`UNDER_REVIEW`), rejeição de denúncias pendentes, retorno ao status `ACTIVE` e auditoria.
  - *Cenário 3*: Consulta paginada com filtro dinâmico e enriquecimento sem N+1 identificando o autor interno de review anônima.
  - *Cenário 4*: Teste de concorrência com threads simultâneas disputando a mesma avaliação via `ExecutorService` e `CountDownLatch`, comprovando serialização determinística pelo lock pessimista `findByIdForUpdate`.

---

### 7.5 STEP 26.2.1 — Limpeza de Diagnostics dos Testes de Moderação Administrativa (✅ CONCLUÍDO)
Após a implementação funcional do STEP 26.2, uma etapa pontual de manutenção e limpeza foi concluída no commit `8045614`:
* **8 Diagnostics de Severidade 4 Identificados**:
  - `ModerateReviewIntegrationTest.java`: Imports não utilizados `Place` e `Review` (código `268435844`); campo não utilizado `placeRepository` (código `570425421`) e import associado `PlaceRepository`.
  - `ModerateReviewUseCaseUnitTest.java`: Imports não utilizados `ModerationAuditLog`, `ArgumentCaptor` e `ArrayList` (código `268435844`).
  - `QueryAdminReportsUseCaseUnitTest.java`: Import não utilizado `ArgumentCaptor` (código `268435844`).
* **Ações de Limpeza**:
  - Remoção estrita dos imports e do campo `placeRepository` sem uso.
  - Nenhuma alteração em código de produção (`src/main`) ou lógica de testes.
  - Nenhum `@SuppressWarnings` adicionado.
* **Garantias Finais**:
  - **Diagnostics restantes**: `0`.
  - Suíte completa de testes: **1001/1001** aprovados (0 failures, 0 errors, 0 skipped).

---

### 7.6 STEP 26.3 — Exposição HTTP da API de Moderação (✅ CONCLUÍDO)

A camada de apresentação HTTP foi implementada no commit `b1b7871`, expondo os casos de uso do STEP 26.2 através de endpoints REST protegidos por roles:

#### 1. Endpoints Administrativos
* **`GET /api/v1/admin/reports`** — Consulta paginada e filtrada da fila de denúncias via `QueryAdminReportsUseCase`.
  - Parâmetros: `page`, `size`, `status`, `reason`, `reviewId`, `reporterUserId`, `sort` (asc/desc) — padrões: `page=0`, `size=20`, `sort=asc`.
  - Resposta: envelope `PagedResponse<AdminReportResponse>` (via `PagedResponse.of(...)` sobre o `PageResult` da aplicação), reutilizando o contrato de paginação já consolidado no projeto.
* **`POST /api/v1/admin/reviews/{reviewId}/moderate`** — Execução de decisão administrativa via `ModerateReviewUseCase`.
  - Payload: `ModerateReviewRequest` com campos `action` (`REMOVE_REVIEW` | `RESTORE_REVIEW`), `reasonCode` (até 64 chars, o tamanho da coluna `reason_code`) e `justification` (15–1000 chars).
  - Resposta: `ModerateReviewResponse` com dados do `ModerationAuditLog` gerado e status anterior/novo da review.

#### 2. Segurança e Autorização
* Ambos os endpoints protegidos por `@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")`.
* O identificador do moderador (`moderatorUserId`) é extraído **exclusivamente** do JWT via `authentication.getName()` — nunca do payload HTTP.
* Acesso por `ROLE_USER` resulta em `403 Forbidden` (garantido pelo `@PreAuthorize` + Spring Security).
* Acesso sem token resulta em `401 Unauthorized`.
* **Privacidade na visão administrativa**: `AdminReportResponse` expõe `reviewAuthorUserId` exclusivamente no contexto de investigação interna de denúncias (incluindo o autor interno de reviews anônimas). Nenhum dado sensível é exposto — sem e-mail de recuperação, hash de senha, credenciais, IP ou coordenadas GPS brutas — e o anonimato público das avaliações anônimas permanece intacto.

#### 3. Correção no `GlobalExceptionHandler`
Foi identificado e corrigido um bug latente no handler genérico `@ExceptionHandler(Exception.class)`: a captura de `AccessDeniedException` e `AuthenticationException` antes do Spring Security causava retorno de `500 Internal Server Error` em vez de `403 Forbidden` / `401 Unauthorized`. A solução adotada re-lança essas exceções de segurança para que o framework as trate corretamente:
```java
if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
    throw ex;
}
```
Trata-se de correção de mapeamento na camada de segurança, sem qualquer alteração de regras de negócio.

#### 4. DTOs de Apresentação
* [AdminModerationDtos.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/presentation/dto/admin/AdminModerationDtos.java): Contém `ModerateReviewRequest`, `ModerateReviewResponse` e `AdminReportResponse` — separados da camada de aplicação para manter o isolamento arquitetural. São projeções HTTP↔aplicação que reutilizam diretamente os enums de domínio (`ModerationAction`, `ReportReason`, `ReportStatus`, `ReviewStatus`), sem duplicação de enums na camada HTTP.

#### 5. Testes de Integração MockMvc (15 Novos Testes)
[AdminModerationControllerIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/presentation/controller/AdminModerationControllerIntegrationTest.java) cobre:
* **Segurança (4 testes)**: 401 sem token (GET e POST), 403 com `ROLE_USER` (GET e POST).
* **Fluxo feliz — GET** (3 testes): MODERATOR vê página vazia, ADMIN vê relatórios existentes com dados corretos, filtro por `status=PENDING`.
* **Fluxo feliz — POST** (2 testes): MODERATOR remove review ACTIVE com sucesso (verifica `auditLogId`, `previousStatus`, `newStatus`, `resolvedReportsCount`); ADMIN remove review (verificando que a autoridade ADMIN é aceita).
* **Erros de negócio** (3 testes): 404 `REVIEW_NOT_FOUND`, 409 `REVIEW_ALREADY_REMOVED`, 403 `SELF_MODERATION_FORBIDDEN`.
* **Erros de validação** (3 testes): 400 por justificativa curta (< 15 chars), 400 por `action` nulo e 400 por `size=0` na consulta paginada.

#### 6. Estratégia de Promoção de Role nos Testes
Como o endpoint `/api/v1/auth/register` sempre cria usuários com `ROLE_USER`, a promoção para MODERATOR/ADMIN nos testes é realizada via `JdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?")` seguida de novo login via `/api/v1/auth/login` para obter token atualizado com a role persistida — abordagem end-to-end compatível com os padrões do projeto.

### 7.7 STEP 26.3.1 — Auditoria e Saneamento do Estado Git (✅ CONCLUÍDO)

Micro-step exclusivamente de auditoria, sem qualquer alteração funcional no código:

* **Integridade da implementação**: A implementação do STEP 26.3 foi confirmada integralmente presente no commit `b1b7871` — nenhum commit de implementação duplicado foi criado.
* **Reversão de alterações cosméticas**: Três modificações sem justificativa no escopo do STEP 26.3 foram identificadas em arquivos de teste preexistentes (`RoleTest`, `JwtRoleSecurityTest`, `ReviewLifecycleControllerIntegrationTest`) — imports não utilizados e reflow estético — e revertidas para o estado exato de `b1b7871`.
* **Roadmap fora da staged area**: A consolidação documental preliminar do STEP 26.3 foi removida da staged area (`git restore --staged`), preservando o conteúdo em disco para o fechamento documental oficial.
* **Dados efêmeros de teste**: Os 91 registros de reviews com data futura identificados no PostgreSQL compartilhado persistente foram caracterizados como estado transiente de execuções anteriores de testes — não alteração arquitetural. Nenhum arquivo SQL, migration, seed ou fixture do projeto foi modificado.
* **Recorrência pós-consolidação**: As mesmas alterações cosméticas nos 3 arquivos reapareceram no working tree após o commit `fb9fd5e` (modificação externa ao fluxo dos micro-steps, consistente com auto-correção de warnings pela IDE). Permanecem unstaged e fora do commit documental, aguardando decisão explícita do usuário — manter ou reverter novamente.

### 7.8 STEP 26.3.2 — Limpeza de Diagnostics do HTTP Admin Moderation (✅ CONCLUÍDO)

Limpeza pontual de diagnostics em [AdminModerationControllerIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/presentation/controller/AdminModerationControllerIntegrationTest.java), consolidada no commit `fb9fd5e`:

* **Escopo exato**: 4 diagnostics eliminados — imports não utilizados (`AuthProvider` e `User`), campo `userRepository` sem uso (com seu import `UserRepository` órfão removido junto) e método auxiliar privado `restoreRequest()` sem referências.
* **Verificação prévia**: Ausência de uso indireto confirmada por busca em setup, fixtures, helpers e reflection antes de cada remoção.
* **Zero alteração comportamental**: Diff exclusivamente de deleção (0 inserções / 14 remoções); nenhuma asserção, fixture, mock, payload, status code, configuração de segurança, ator ou role foi alterado. Sem `@SuppressWarnings`.
* **Fora de escopo (preservados)**: Os 3 warnings históricos em `RoleTest`, `JwtRoleSecurityTest` e `ReviewLifecycleControllerIntegrationTest` permanecem — anteriores ao STEP 26.3, deliberadamente não tratados neste micro-step e não contabilizados como introduzidos pelo 26.3.
* **Validação**: Suíte completa em **1016/1016** aprovados (0 failures, 0 errors, 0 skipped) e 0 diagnostics no módulo HTTP Admin.

---

## 8. Jobs Assíncronos & Outbox Pattern — STEP 27

O STEP 27 introduz o padrão **Transactional Outbox sobre o próprio PostgreSQL**, permitindo que consumidores assíncronos sejam entregues de forma confiável **at-least-once** sem broker externo e sem quebrar a atomicidade transacional do produtor. O STEP 27.0 (Discovery), o STEP 27.1 (Fundação Transacional), o STEP 27.2 (Dispatcher/Worker), o STEP 27.3 (Produtor de Notifications + `PushNotificationHandler`), o STEP 27.4 (Observabilidade e Retenção) e o STEP 27.4.1 (Limpeza de Diagnostics) encontram-se **✅ CONCLUÍDOS**. **A fila `outbox_messages` agora recebe mensagens `PUSH_NOTIFICATION` enfileiradas na mesma transação dos quatro fluxos de notificação, e o worker as entrega via `PushNotificationHandler` à porta `NotificationProvider`** — porém o provider segue sendo o `MockNotificationAdapter` local: **não há push real externo**. A Notification in-app continua síncrona e transacional; somente o efeito externo de push tornou-se assíncrono, com entrega at-least-once.

### 8.1 STEP 27.0 — Discovery de Jobs Assíncronos (✅ CONCLUÍDO)
O discovery consolidou as diretrizes que governam toda a frente:
1. **Outbox apenas com produtor real**: a fundação foi criada, mas o único produtor previsto é o push externo (`NotificationProvider`, porta ainda nunca invocada). Nenhuma notification foi migrada.
2. **Fluxos same-DB permanecem síncronos**: notificações in-app, reputação e `RateableTargetStats` continuam sendo gravados na transação do produtor — já são atômicos por construção e não devem ser migrados para o Outbox.
3. **Sem broker (Kafka/RabbitMQ)**: worker futuro baseado em `@Scheduled` + `SELECT ... FOR UPDATE SKIP LOCKED` + lease; entrega **at-least-once** com consumidores idempotentes (nunca afirmar "exactly once").
4. **Payload mínimo e sem PII**: mensagens carregam apenas identificadores (ex.: `{"notificationId"}`); senha, token, e-mail, IP, coordenadas GPS e credenciais nunca trafegam no outbox.
5. **Órfãos de mídia**: a futura reconciliação de mídias órfãs do SeaweedFS será um job `@Scheduled` direto de varredura, **não** via Outbox.

### 8.2 STEP 27.1 — Fundação Transacional do Outbox (✅ CONCLUÍDO)
A fundação transacional foi implementada e validada no commit `30a6191`, entregando:

#### 1. Migração Flyway V12
A migração [V12__outbox_messages.sql](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V12__outbox_messages.sql) criou a tabela `outbox_messages`:
* Colunas: `id UUID` (PK, `gen_random_uuid()`), `message_type VARCHAR(64) NOT NULL`, `payload JSONB NOT NULL`, `status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`, `attempts INT NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`, `locked_at TIMESTAMP WITH TIME ZONE`, `locked_by VARCHAR(128)`, `last_error TEXT`, `created_at` e `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`.
* Constraints: `chk_outbox_status` limitando `status` a `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`; `chk_outbox_attempts` garantindo `attempts >= 0`.
* Índice parcial de claim: `idx_outbox_pending ON (next_attempt_at) WHERE status = 'PENDING'`.
* **Índice de lease**: o índice parcial `idx_outbox_processing_lease ON (locked_at) WHERE status = 'PROCESSING'` foi criado no STEP 27.2 pela migração `V13`, atendendo à query real de reclaim por `locked_at`. **O índice operacional de `FAILED` NÃO existe** — nenhuma query do STEP 27.2 o demanda e ele não foi criado preventivamente.

#### 2. Domínio Puro
* Enum `OutboxStatus`: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`.
* Entidade de domínio puro [OutboxMessage.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/model/OutboxMessage.java) com invariantes rigorosas e transições protegidas:
  - `claim(workerId, now)`: transição `PENDING -> PROCESSING`, incrementando `attempts` e registrando o lease (`locked_at`/`locked_by`).
  - `markCompleted(now)`: finalização com estado terminal `COMPLETED`.
  - `markFailed(lastError, now)`: registra o último erro preservando `next_attempt_at`.
  - `requeue(now)`: retorno a `PENDING`, limpando o lease e preservando `attempts`.
  - Transições inválidas lançam `INVALID_OUTBOX_TRANSITION`; `COMPLETED` e `FAILED` são estados terminais.
  - No STEP 27.2 o domínio foi estendido com `retry(lastError, nextAttemptAt, now)` (retorno a `PENDING` com novo `last_error`/`next_attempt_at` e lease limpo, preservando `attempts`) — o cálculo de backoff e a política de tentativas permanecem **fora do domínio**, implementados pelo `OutboxRetryPolicy` da camada de aplicação.

#### 3. Porta de Aplicação
* [OutboxRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/OutboxRepository.java): contrato original do 27.1 com `save`, `claimBatch(batchSize, workerId)`, `findById` e `countByStatus`, ampliado no STEP 27.2 com `reclaimExpiredLeases(now, leaseDuration)` e as finalizações owner-checked `markCompleted(messageId, workerId, now)`, `markFailed(messageId, workerId, lastError, now)` e `scheduleRetry(messageId, workerId, lastError, nextAttemptAt, now)`. Nenhum método além destes.

#### 4. Infraestrutura de Persistência
* Entidade JPA [OutboxMessageJpaEntity.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/entity/OutboxMessageJpaEntity.java) com `payload` mapeado como `jsonb` (`@JdbcTypeCode(SqlTypes.JSON)`) e `status` persistido textualmente.
* Repositório Spring Data [OutboxMessageJpaRepository.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/repository/OutboxMessageJpaRepository.java) com queries nativas de claim.
* Adaptador [OutboxRepositoryAdapter.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/persistence/adapter/OutboxRepositoryAdapter.java):
  - `save` participa da **mesma transação do produtor** (`PROPAGATION_REQUIRED`) — enqueue atômico com a operação de negócio.
  - `claimBatch` executa em **transação própria e curta**: `SELECT id ... FOR UPDATE SKIP LOCKED` seguido de `UPDATE ... WHERE id IN (:ids)` na mesma transação, sem janela SELECT→commit→UPDATE.

#### 5. Testes Automatizados (25 Novos Testes)
* [OutboxMessageTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/domain/model/OutboxMessageTest.java): **17 testes** unitários de domínio cobrindo criação válida de enqueue, validações de argumentos, transições de estado, estados terminais e rehidratação.
* [OutboxPersistenceIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/infrastructure/persistence/OutboxPersistenceIntegrationTest.java): **8 testes** de integração no PostgreSQL real cobrindo persistência com round-trip JSONB, rejeição pelas constraints `chk_outbox_status` e `chk_outbox_attempts`, atomicidade do enqueue (rollback e commit na transação do produtor), claim determinístico ordenado por `next_attempt_at` e **prova de concorrência SKIP LOCKED** entre dois workers com sincronização determinística via `CountDownLatch`.
* Suíte completa: baseline `1016` + incremento `25` = **1041 testes verdes** (0 failures, 0 errors, 0 skipped, BUILD SUCCESS).

### 8.3 STEP 27.2 — Dispatcher/Worker do Outbox (✅ CONCLUÍDO)
O dispatcher/worker foi implementado e validado no commit `d11c79a`: mensagens `PENDING` passam a ser processadas fora do boundary HTTP, embora **sem produtor real conectado** (a fila permanece vazia em runtime).

#### 1. Use Case do Dispatcher
* [ProcessOutboxBatchUseCase.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/usecase/ProcessOutboxBatchUseCase.java): classe **pura de aplicação** (sem `@Service`, sem `@Transactional`, sem `@Scheduled`), montada como bean pela configuração de infraestrutura [OutboxDispatcherConfig.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/outbox/OutboxDispatcherConfig.java) — garantia estrutural de que nenhuma transação envolve o processamento dos handlers.
* Ciclo por execução: `reclaimExpiredLeases` (transação curta própria) → `claimBatch` com `SKIP LOCKED` (transação curta que **commita antes** do processamento) → processamento sequencial de cada mensagem **fora de qualquer transação** → finalização owner-checked em transação curta individual (`COMPLETED`, `PENDING` com retry agendado ou `FAILED`).
* Resultado estruturado do ciclo: `ProcessOutboxBatchResult` com `reclaimedCount`, `claimedCount`, `completedCount`, `retriedCount`, `failedCount`, `lostOwnershipCount` e `finalizeErrorCount`. Erro ao persistir uma finalização é contabilizado em `finalizeErrorCount` e **não mascara** a falha original do handler.
* O worker processa o lote de forma **sequencial** — sem paralelismo intra-lote nesta fase.

#### 2. Handler e Classificação de Falhas
* Porta [OutboxHandler.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/port/OutboxHandler.java) com `supports(messageType)` e `handle(message)`; o roteamento escolhe o primeiro handler que suporta o tipo da mensagem.
* `OutboxFailureClassifier` classifica cada falha em `TRANSIENT` ou `PERMANENT`:
  - `OutboxPermanentException` → **PERMANENTE**: `FAILED` imediato, sem retry.
  - `BusinessException` → **PERMANENTE**: rejeição de negócio determinística não se resolve com retry.
  - Qualquer outra exceção (incluindo `OutboxTransientException` e falhas não classificadas) → **TRANSIENTE**: retry com backoff enquanto restarem tentativas; após esgotar `maxAttempts`, `FAILED` terminal.
  - Mensagem sem handler registrado → **PERMANENTE**: falha de roteamento determinística.
* **Nenhum handler de push foi implementado neste step** — a lista de handlers registrados permaneceu vazia até o STEP 27.3, que registrou o `PushNotificationHandler` (ver seção 8.4).

#### 3. Retry com Backoff Determinístico
* [OutboxRetryPolicy.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/outbox/OutboxRetryPolicy.java): `delay = min(initialDelay × multiplier^(attempts−1), maxDelay)`, com proteção explícita contra overflow e `Infinity`/`NaN` no cálculo exponencial. Sem jitter — decisão documentada desta fase para manter o comportamento previsível.
* Falha `TRANSIENT` dentro do limite → `scheduleRetry` (retorno a `PENDING` com novo `next_attempt_at`); esgotadas as tentativas → `FAILED` terminal.
* `attempts` é incrementado **apenas no claim** (semântica do 27.1) — a finalização não incrementa novamente; o valor reivindicado é o número da tentativa que acabou de falhar.
* Propriedades reais implementadas em `rewit.outbox.*`: `poller-enabled=true`, `poll-interval-ms=5000`, `initial-delay-ms=15000`, `batch-size=10`, `lease-duration=PT2M`, `error-max-length=512`, `retry.initial-delay=PT30S`, `retry.multiplier=2.0`, `retry.max-delay=PT10M`, `retry.max-attempts=5` — defaults conservadores de desenvolvimento, ajustáveis por ambiente.

#### 4. Sanitização do last_error
* [OutboxErrorSanitizer.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/outbox/OutboxErrorSanitizer.java): o `last_error` persistido nunca contém stack trace; tokens `Bearer` e segredos (`password`, `secret`, `token`, `authorization`, `api-key`, `credential` etc.) são substituídos por `[REDACTED]`; o texto é truncado ao limite configurado (`error-max-length`); o payload da mensagem nunca é incluído no erro.

#### 5. Lease e Recuperação Pós-Crash
* Cada claim registra o lease (`locked_at`/`locked_by`); mensagens `PROCESSING` com `locked_at` anterior a `now − leaseDuration` são recuperadas atomicamente para `PENDING` (UPDATE SQL único), com lease limpo, `next_attempt_at = now` e `attempts` preservado.
* Um worker que crashou no meio do processamento não bloqueia a mensagem: outro worker (ou o mesmo após reinício) retoma no próximo ciclo — recuperação pós-crash garantida pelo banco, sem componente adicional.

#### 6. Concorrência
* Seleção e marcação `PROCESSING` na **mesma transação SQL** via `FOR UPDATE SKIP LOCKED`: workers concorrentes nunca reivindicam a mesma linha.
* Finalizações owner-checked: `UPDATE ... WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId`; **zero linhas afetadas significa posse perdida** — a finalização é descartada e a mensagem jamais é ressuscitada nem sobrescreve o trabalho do worker posterior.
* Corrida pós-expiração de lease (worker lento ainda processando enquanto outro recupera e reivindica a mensagem) é resolvida pelo mesmo owner check: o resultado do worker antigo é ignorado.

#### 7. Scheduler
* [OutboxDispatcherPoller.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/outbox/OutboxDispatcherPoller.java): poller `@Scheduled(fixedDelay)` fino que apenas delega ao use case e absorve exceções do ciclo — uma falha nunca derruba o agendador e o próximo tick continua delegando. `@EnableScheduling` central na configuração de infraestrutura; o poller respeita `rewit.outbox.poller-enabled` (desligado em todos os testes automatizados).
* **Múltiplas instâncias são suportadas pelo locking do PostgreSQL (`SKIP LOCKED` + owner check), não pelo scheduler** — o `fixedDelay` apenas evita sobreposição de ciclos dentro da mesma instância.

#### 8. Migração V13
* A migração [V13__outbox_dispatcher_indexes.sql](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V13__outbox_dispatcher_indexes.sql) cria exclusivamente o índice parcial `idx_outbox_processing_lease ON (locked_at) WHERE status = 'PROCESSING'` (com `COMMENT ON INDEX`), demandado pela query real de reclaim por `locked_at`.
* **Nenhum índice para `FAILED` foi criado** — não existe query correspondente no STEP 27.2. A migração `V12` permanece intocada.

#### 9. Testes Automatizados (46 Novos Testes)
* [OutboxMessageTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/domain/model/OutboxMessageTest.java): ampliado de 17 para **20 testes** unitários de domínio (retry com novo `last_error`/`next_attempt_at` e lease limpo; transições a partir de `PROCESSING`).
* [OutboxRetryPolicyTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/outbox/OutboxRetryPolicyTest.java): **8 testes** da política de backoff (fórmula exponencial, teto de atraso, proteção de overflow/`NaN` e limite de tentativas).
* [OutboxErrorSanitizerTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/outbox/OutboxErrorSanitizerTest.java): **8 testes** de sanitização (redação de `Bearer`/segredos, truncamento e preservação de texto legítimo).
* [ProcessOutboxBatchUseCaseTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/usecase/ProcessOutboxBatchUseCaseTest.java): **14 testes** unitários do ciclo (sucesso, classificação `TRANSIENT`/`PERMANENT`, esgotamento, mensagem sem handler, perda de posse e erro de finalização) — incluindo **prova real de processamento fora da transação** via `TransactionSynchronizationManager` com controle positivo.
* [OutboxDispatcherIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/infrastructure/outbox/OutboxDispatcherIntegrationTest.java): **11 testes** de integração no PostgreSQL real — lease recovery, lease válida não recuperada, claim concorrente entre dois workers, owner check (worker dono e worker errado), corrida pós-expiração de lease, retry com backoff, esgotamento até `FAILED`, ciclo end-to-end via poller e `workerId` por instância.
* [OutboxDispatcherPollerTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/infrastructure/outbox/OutboxDispatcherPollerTest.java): **2 testes** determinísticos do poller (delegação integral ao use case e absorção de falha do ciclo), executados sem relógio real — `poller-enabled=false` na suíte de testes.
* Suíte completa: baseline `1041` + incremento `46` = **1087 testes verdes** (0 failures, 0 errors, 0 skipped, BUILD SUCCESS).

#### 10. Correção Registrada Durante a Validação
* Durante a validação do 27.2, uma edição intermediária alterou acidentalmente a query `markClaimed` do 27.1, removendo temporariamente a transição `PENDING -> PROCESSING`. A suíte detectou a regressão (incluindo testes de claim concorrente do próprio 27.1), o diff contra o HEAD revelou a causa e a implementação final consolidada em `d11c79a` restaurou a transição. **Não se trata de bug persistente**: o estado final preserva a semântica do 27.1, comprovada pela suíte completa.

#### 11. Decisões do MVP Atual (não permanentes)
As decisões abaixo valem para o MVP desta fase e devem ser reavaliadas sob carga real — não devem ser tratadas como permanentes:
1. **Entrega at-least-once**: consumidores devem ser idempotentes; nenhuma garantia exactly-once é afirmada.
2. **Processamento sequencial por lote**: sem paralelismo intra-lote no MVP.
3. **PostgreSQL como coordenação**: a exclusividade entre workers vem do locking do banco (`SKIP LOCKED` + owner check), não do scheduler — múltiplas instâncias da API são suportadas sem broker.
4. **Sem broker externo (Kafka/RabbitMQ/Redis/Quartz/Spring Batch)**: a fila transacional em PostgreSQL atende à volumetria atual; substituição apenas sob os decision gates.
5. **Sem jitter no backoff**: comportamento previsível priorizado nesta fase.

### 8.4 STEP 27.3 — Produtor de Notifications + PushNotificationHandler (✅ CONCLUÍDO)
O produtor de push e o handler de entrega foram implementados e validados no commit `a4ed10f`: os quatro fluxos de notificação passaram a enfileirar `PUSH_NOTIFICATION` na própria transação de negócio e o worker do 27.2 passou a entregá-los à porta `NotificationProvider` — **ainda sem provider real** (o único adapter é o `MockNotificationAdapter`).

#### 1. Produtor Transacional (NotificationService)
* [NotificationService.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/service/NotificationService.java): nos quatro fluxos existentes — `NEW_FOLLOWER`, `REVIEW_HELPFUL`, `NEW_DISCUSSION` e `DISCUSSION_REPLY` — a `Notification` é salva e, logo depois, a `OutboxMessage` é enfileirada via `OutboxRepository.save` na **mesma transação** (propagação `REQUIRED`): enqueue atômico com a operação de negócio.
* `message_type = PUSH_NOTIFICATION`, centralizado no enum [OutboxMessageType.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/domain/enums/OutboxMessageType.java) — o roteamento não usa strings literais espalhadas pelo código de produção.
* Payload exclusivo `{"notificationId":"<UUID>"}` (ver item 5).
* **A Notification in-app continua síncrona**: criação, leitura e marcação de leitura permanecem na transação do produtor, como decidido no STEP 27.0. Somente o efeito externo de push tornou-se assíncrono — e **não existe push real externo**, pois o provider real não foi implementado.

#### 2. PushNotificationHandler
* [PushNotificationHandler.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/application/outbox/PushNotificationHandler.java): implementação **pura de aplicação** da porta `OutboxHandler` — não acessa JPA diretamente, não acessa `SecurityContext`, não conhece o scheduler e não abre transação própria; registrado como bean em [OutboxDispatcherConfig.java](file:///d:/Codigos/Projetos/Rewit/backend/src/main/java/com/rewit/infrastructure/outbox/OutboxDispatcherConfig.java).
* Suporta exclusivamente `PUSH_NOTIFICATION`; extrai o `notificationId` do payload; carrega a `Notification` pela porta `NotificationRepository` (novo `findById`); usa **exclusivamente o conteúdo persistido** (destinatário, título, conteúdo e metadados) e chama a porta `NotificationProvider`.
* A porta `NotificationProvider` permanece abstrata; o adapter utilizado é o `MockNotificationAdapter` (log local). Nada de Firebase, FCM, APNs, Expo, HTTP client de push ou credenciais reais neste step.

#### 3. Falhas (Integração com o Mecanismo do STEP 27.2)
* **Permanentes → `FAILED` imediato**, casos efetivamente cobertos: Notification inexistente; payload vazio; payload malformado; `notificationId` ausente; `notificationId` inválido; metadata ilegível.
* **Transitórias**: falhas do provider propagam sem captura e entram no mecanismo existente de retry/backoff do worker (lease, owner check e esgotamento até `FAILED`) — nenhuma estratégia de retry nova foi criada; o handler não possui retry próprio.

#### 4. Atomicidade (PostgreSQL Real)
* **Commit**: a transação do produtor produz a `Notification` e a `OutboxMessage` `PUSH_NOTIFICATION` juntas.
* **Rollback**: nenhuma das duas permanece.
* Uma conexão observadora com autocommit próprio não enxerga a linha do Outbox enquanto a transação produtora não comitou. A evidência se limita ao teste realizado — nenhuma garantia além dela é extrapolada.

#### 5. Payload Mínimo, Segurança e Anonimato
* O payload contém somente `{"notificationId":"<UUID>"}` — sem `title`, `content`, `actorId`, e-mail, `recoveryEmail`, senha, token, IP, GPS, credentials ou PII desnecessária.
* O conteúdo real do push é obtido da `Notification` persistida no momento da entrega — nunca do payload.
* O handler não reconstrói identidade: `actorId = null` continua `null` quando a `Notification` original determina anonimato; o handler não injeta IDs de usuário; o push não desanonimiza Reviews. A política pública de anonimato permanece inalterada.

#### 6. Idempotência — at-least-once
* Garantia de entrega: **at-least-once**. Não existe garantia exactly-once.
* O contrato da `NotificationProvider` não possui idempotency key; um crash entre o push externo e a finalização `COMPLETED` pode causar duplicidade eventual — condição conhecida e aceita na arquitetura atual. Nenhuma deduplicação foi inventada.

#### 7. API Pública de Notifications Intocada
* `findMyNotifications`, unread count, `markAsRead` e `markAllAsRead` permaneceram intocados. O único novo comportamento é a geração do Outbox no momento da criação da `Notification`.

#### 8. Testes Automatizados (30 Novos Testes)
* [PushNotificationHandlerTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/outbox/PushNotificationHandlerTest.java): **15 testes** unitários do handler (roteamento por `supports`, payload mínimo suficiente, conteúdo exato repassado ao provider, metadados, falhas permanentes de payload/Notification inexistente/metadata ilegível, propagação da falha transitória do provider e anonimato preservado).
* [NotificationServiceUnitTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/service/NotificationServiceUnitTest.java): **6 novos testes** de enqueue (Notification + OutboxMessage capturados nos quatro fluxos, `message_type` e payload exatos, self-follow/parâmetros nulos não enfileiram).
* [NotificationOutboxAtomicityIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/application/service/NotificationOutboxAtomicityIntegrationTest.java): **3 testes** de integração no PostgreSQL real — commit produz `Notification` + `OutboxMessage` com payload só de `notificationId`; rollback não deixa nenhum dos dois (com conexão observadora); os quatro fluxos enfileiram exatamente uma mensagem por notificação.
* [NotificationPushDeliveryIntegrationTest.java](file:///d:/Codigos/Projetos/Rewit/backend/src/test/java/com/rewit/infrastructure/outbox/NotificationPushDeliveryIntegrationTest.java): **6 testes** E2E no PostgreSQL real — follow commitado → poller com handler real → `COMPLETED`; conteúdo exato entregue ao provider; retry transitório sem sleep; Notification inexistente → `FAILED` terminal; falha do provider não rollbacka a ação já commitada; anonimato do Helpful preservado na entrega.
* Suíte completa executada com `./mvnw.cmd clean test`: baseline `1087` + incremento `30` = **1117 testes verdes** (0 failures, 0 errors, 0 skipped, BUILD SUCCESS).

#### 9. Banco de Testes Durante a Validação
* O novo comportamento do `NotificationService` fez com que testes de fluxos antigos de Notification também gerassem mensagens Outbox: ao final da suíte foram observadas **25 mensagens `PUSH_NOTIFICATION` `PENDING`**.
* Inspeção confirmou serem resíduos sintéticos de teste — **não** uma falha funcional do Outbox. As mensagens foram removidas após inspeção, sem tocar dado alheio, e **nenhum `PROCESSING` órfão permaneceu**.
* As classes de teste relevantes foram ajustadas para limpar o estado que passaram a produzir; nenhum teste de Feed foi modificado. A tabela `outbox_messages` terminou vazia. **Nenhuma migration adicional foi necessária** — a estrutura de `V12`/`V13` foi suficiente, sem alteração de schema e sem recriação de banco.

#### 10. Diagnostics
* **Zero diagnostics introduzidos pelo STEP 27.3**; nenhuma anotação `@SuppressWarnings` no código novo. Os 3 diagnostics históricos (`RoleTest.java`, `JwtRoleSecurityTest.java`, `ReviewLifecycleControllerIntegrationTest.java`) permanecem fora de escopo e inalterados.

### 8.4.1 STEP 27.4 — Observabilidade, Retenção e Consolidação Operacional (✅ CONCLUÍDO)

O STEP 27.4 foi implementado no commit `eae44ab` e teve a limpeza de diagnostics concluída no commit `6ca6cc`. A etapa tornou o Outbox V1 observável e operável sem criar broker, dashboard, endpoint administrativo ou nova infraestrutura de jobs.

#### 1. Métricas Micrometer
As gauges consultam o PostgreSQL como source of truth:
* `rewit.outbox.pending` — quantidade atual de mensagens `PENDING`.
* `rewit.outbox.failed` — quantidade atual de mensagens `FAILED`.
* `rewit.outbox.oldest_pending_age` — idade em segundos da `PENDING` mais antiga, ou `0` quando não há mensagens pendentes.

Os counters efetivamente implementados são `rewit.outbox.messages.claimed`, `rewit.outbox.messages.completed`, `rewit.outbox.messages.retried`, `rewit.outbox.messages.failed`, `rewit.outbox.leases.reclaimed`, `rewit.outbox.messages.lost_ownership` e `rewit.outbox.finalize.errors`. Também existem `rewit.outbox.purge.deleted` e o timer `rewit.outbox.purge.duration` para o purge. Não há tags de alta cardinalidade e não existe health indicator customizado.

#### 2. Logs e segurança operacional
O worker registra os eventos operacionais relevantes do ciclo, incluindo reclaim, processamento, retry, falha terminal, perda de ownership e falha do ciclo. Payloads, corpos de Notification e IDs sensíveis não são registrados como conteúdo operacional. Erros passam pelo `OutboxErrorSanitizer`; bearer tokens e segredos não são persistidos em `last_error`.

#### 3. Retenção e purge
A política V1 mantém `COMPLETED` por 30 dias e remove registros antigos em lotes limitados, usando `updated_at` como corte. O purge nunca remove `PENDING`, `PROCESSING` ou `FAILED`; `FAILED` permanece visível para análise e não há requeue automático nem endpoint administrativo. A migração [V14__outbox_observability_indexes.sql](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V14__outbox_observability_indexes.sql) adiciona o índice parcial `idx_outbox_pending_created_at` para a consulta da `PENDING` mais antiga. V14 foi aplicada e validada no PostgreSQL real; V13 não foi alterada.

#### 4. Estado operacional observado
Após a suíte final, a tabela `outbox_messages` apresentou `PENDING = 25`, `PROCESSING = 0`, `COMPLETED = 0` e `FAILED = 0`. As 25 mensagens são `PUSH_NOTIFICATION` recentes geradas por testes com o poller desabilitado, e não representam produção. Não foram removidas manualmente nem houve alteração arbitrária no banco; são resíduos sintéticos dos testes que passaram a produzir Outbox no STEP 27.3.

#### 5. Diagnostics e validação
* O diagnostic Java `67109822` em `OutboxRepositoryAdapter.java` foi corrigido substituindo a method reference por lambda explícita.
* Hints de `catch(Throwable)` foram mantidos onde necessários para capturar falhas das threads concorrentes dos testes; não são erros de compilação.
* Hints `never used` em métodos anotados com `@BeforeEach`/`@AfterEach` são falsos positivos do analisador local de callbacks JUnit; os métodos permanecem.
* Os diagnostics `COMMENT ON INDEX` de `mssql9` em V13/V14 são `FALSE_POSITIVE_LIKELY` por incompatibilidade de dialeto: PostgreSQL 18.6 aceitou os comandos, Flyway aplicou as migrations e os índices existem.
* Os diagnostics históricos de `RoleTest.java`, `JwtRoleSecurityTest.java` e `ReviewLifecycleControllerIntegrationTest.java` permanecem fora de escopo.
* Baseline: `1117`; incremento do STEP 27.4: `6`; total final: `1123` testes, `0` failures, `0` errors, `0` skipped, `BUILD SUCCESS`. O cleanup foi validado com 21 testes direcionados e a suíte completa; a consolidação documental não rerodou Maven.

#### 6. Gate final do Outbox V1
O gate final foi atendido: V12, V13 e V14 aplicadas; Outbox transacional; claim concorrente com `FOR UPDATE SKIP LOCKED`; lease recovery; retry/backoff; produtor e handler de push; métricas; retenção; 1123 testes verdes; diagnostics Java novos reais resolvidos; working tree limpo. Reconciliação de mídia, provider real, dashboard, alertas, admin requeue, broker externo, exactly-once e deduplicação distribuída permanecem futuros e dependem de novos decision gates.

#### 7. Checkpoints e fechamento
O histórico relevante permanece: `30a6191` (foundation), `ab317a0` e consolidação documental correspondente, `d11c79a` (dispatcher/worker), `e881406` (consolidação), `a4ed10f` (Notifications → Outbox), `729a0b1` (consolidação do producer), `eae44ab` (observabilidade) e `6ca6cc` (cleanup de diagnostics). Com esta consolidação documental, o **STEP 27 — Jobs Assíncronos & Outbox Pattern → ✅ CONCLUÍDO**.

O backlog posterior permanece separado: Garbage Collection do SeaweedFS e Cleanup de Sessões Expiradas continuam pendentes, cada um sujeito ao seu próprio decision gate. Nenhuma dessas frentes foi iniciada nesta consolidação.

### 8.5 Decisão Arquitetural
1. **PostgreSQL como Source of Truth**: a fila transacional vive no mesmo banco relacional da aplicação; nenhum componente de mensageria externo foi introduzido.
2. **Outbox no mesmo PostgreSQL**: mensagem e negócio compartilham a mesma base, garantindo consistência ACID sem dual-write.
3. **Enqueue na transação do produtor**: a mensagem entra na outbox atomicamente com a operação de negócio — impossível commitar um sem o outro.
4. **Claim atômico**: a seleção de mensagens elegíveis e a marcação `PROCESSING` ocorrem na mesma transação SQL, eliminando a janela de disputa entre workers.
5. **SKIP LOCKED como estratégia de concorrência**: workers concorrentes nunca reivindicam a mesma linha; a elegibilidade respeita `next_attempt_at <= now`.
6. **Produtor transacional e efeito externo assíncrono**: desde o 27.3, os quatro fluxos de notificação enfileiram `PUSH_NOTIFICATION` na mesma transação e o worker entrega o conteúdo persistido via `PushNotificationHandler`; a Notification in-app, a reputação e as estatísticas permanecem síncronas na transação do produtor — nada foi migrado. O provider é o mock local: não há push real externo. **A chamada externa de push nunca ocorre dentro da transação do produtor.**

### 8.6 Limites do STEP 27.1 (NÃO Implementados)
Os itens abaixo permanecem **fora do escopo entregue** e não devem ser assumidos como existentes:
* Scheduler — NÃO implementado.
* `@Scheduled` — NÃO implementado.
* Dispatcher — NÃO implementado.
* Worker — NÃO implementado.
* Retry/backoff — NÃO implementado.
* Lease recovery — NÃO implementado.
* Classificação de falhas (transient/permanent) — NÃO implementada.
* Handler de entrega de mensagens — NÃO implementado.
* Push — NÃO implementado.
* Integração com `NotificationProvider` — NÃO implementada.
* Métricas — NÃO implementadas.
* Logs específicos do worker — NÃO implementados.
* Endpoints administrativos de outbox — NÃO implementados.
* Broker externo (Kafka/RabbitMQ) — NÃO introduzido.

> **Nota histórica**: os limites acima descrevem o estado ao fechamento do STEP 27.1. Vários deles (scheduler, dispatcher, worker, retry/backoff, lease recovery, classificação de falhas, handler genérico e índice de lease) foram entregues no STEP 27.2 — ver seção 8.3 — e a integração com a porta `NotificationProvider` no STEP 27.3 — ver seção 8.4. Os limites vigentes estão em 8.8.

### 8.7 Limites do STEP 27.2 (NÃO Implementados)
Os itens abaixo permanecem **fora do escopo entregue pelo 27.2** e não devem ser assumidos como existentes:
* Produtor real de mensagens (`NotificationService` enfileirando na outbox) — NÃO implementado.
* `PushNotificationHandler` de produção — NÃO implementado.
* Integração com `NotificationProvider` real (push externo) — NÃO implementada.
* Push — NÃO implementado.
* Outbox para notifications — NÃO adotado (notificações in-app permanecem síncronas na transação do produtor).
* Métricas Micrometer do dispatcher — NÃO implementadas.
* Dashboard/observabilidade do worker — NÃO implementados.
* Endpoint administrativo do Outbox — NÃO implementado.
* Requeue administrativo de mensagens `FAILED` — NÃO implementado.
* Kafka — NÃO introduzido.
* RabbitMQ — NÃO introduzido.
* Redis — NÃO introduzido no pipeline do outbox.
* Quartz — NÃO introduzido.
* Spring Batch — NÃO introduzido.
* Reconciliação de mídia (GC do SeaweedFS via outbox) — NÃO implementada (job direto `@Scheduled`, sem Outbox, quando iniciado).

> **Nota histórica**: os limites acima descrevem o estado ao fechamento do STEP 27.2. Vários deles (produtor real de mensagens na `NotificationService`, `PushNotificationHandler`, outbox para push e integração com a porta `NotificationProvider` — ainda via `MockNotificationAdapter`) foram entregues no STEP 27.3 — ver seção 8.4. Os limites vigentes estão em 8.8.

### 8.8 Limites do STEP 27.3 (NÃO Implementados)
Os itens abaixo permanecem **fora do escopo entregue pelo 27.3** e não devem ser assumidos como existentes:
* Provider real de push — NÃO implementado.
* Firebase — NÃO implementado.
* FCM — NÃO implementado.
* APNs — NÃO implementado.
* Expo — NÃO implementado.
* Credenciais reais de push — NÃO configuradas.
* Exactly-once — NÃO garantido (entrega at-least-once).
* Deduplicação distribuída — NÃO implementada.
* Broker externo (Kafka/RabbitMQ) — NÃO introduzido.
* Métricas do dispatcher/worker — NÃO implementadas.
* Dashboard de observabilidade — NÃO implementado.
* Requeue administrativo de mensagens `FAILED` — NÃO implementado.
* API pública nova de notifications/outbox — NÃO exposta.
* Reconciliação de mídia (GC do SeaweedFS via outbox) — NÃO implementada.

### 8.9 Backlog Atualizado de Jobs Assíncronos

| Componente / Cenário | Motivação | Pré-requisito | Estado |
| :--- | :--- | :--- | :--- |
| **Fundação Transacional do Outbox** | Fila `outbox_messages` com enqueue atômico e claim `SKIP LOCKED`. | Concluído na migração `V12`. | ✅ CONCLUÍDO (STEP 27.1) |
| **Dispatcher/Worker do Outbox** | Processar mensagens `PENDING` fora do boundary HTTP. | Concluído no commit `d11c79a` (lease/reclaim, retry com backoff, sanitização e poller `@Scheduled`). | ✅ CONCLUÍDO (STEP 27.2) |
| **Produtor Real de Push** | Único consumidor previsto do outbox nesta fase. | Concluído no commit `a4ed10f` (enqueue transacional nos 4 fluxos de `Notification`, `PushNotificationHandler` e `MockNotificationAdapter` — sem push real externo). | ✅ CONCLUÍDO (STEP 27.3) |
| **Observabilidade do Worker** | Métricas, logs e retenção operacional do dispatcher. | Concluído no STEP 27.4; cleanup de diagnostics no STEP 27.4.1. | ✅ CONCLUÍDO (STEP 27.4) |
| **Notificações In-App** | Evitar lentidão caso o volume de notificações cresça. | Decisão do STEP 27.0: permanecem **síncronas** na transação do produtor (same-DB já é atômico). | **PERMANECE SÍNCRONO** |
| **Recálculo Assíncrono de Reputação** | Eliminar lock pessimista em `users` durante a postagem de reviews. | Decisão do STEP 27.0: permanece **síncrono** na transação do produtor. | **PERMANECE SÍNCRONO** |
| **Garbage Collection do SeaweedFS** | Remover imagens não referenciadas no S3 para economia de storage. | Job `@Scheduled` direto de varredura de órfãos — **sem Outbox** (decisão do STEP 27.0). Implementado no STEP 28, desligado por padrão: [storage-reconciliation.md](../architecture/storage-reconciliation.md), [ADR-010](../decisions/ADR-010-storage-garbage-collection.md). | ✅ CONCLUÍDO (STEP 28) |
| **STEP 29 — Cleanup de Sessões Expiradas** | Expurgar sessões expiradas da tabela `auth_sessions` (o nome `user_sessions` usado anteriormente neste roadmap não é o nome físico) e minimizar IP/User-Agent de sessões inativas. | Job `@Scheduled` direto, **sem Outbox**, com critério estrutural (sem retenção numérica). Implementado no STEP 29.3: [authentication.md §4.5](../architecture/authentication.md), [ADR-011](../decisions/ADR-011-session-retention-and-cleanup.md) (Proposto). | ✅ TECNICAMENTE CONCLUÍDO (STEP 29) — decisão de produto/jurídico pendente; ADR-011 Proposto |

### 8.10 Sequência após o STEP 27
O STEP 27 está fechado. A sequência registrada no backlog seguiu com:

1. **STEP 28 — Garbage Collection do SeaweedFS** (✅ CONCLUÍDO): job direto `@Scheduled`, sem Outbox. Ver §8.11.
2. **STEP 29 — Cleanup de Sessões Expiradas** (✅ TECNICAMENTE CONCLUÍDO): correções 29.1 e 29.2, implementação 29.3 e gate final concluídos; resta a decisão de produto/jurídico sobre IP/User-Agent em sessões ativas, e a ADR-011 permanece Proposto. Ver §8.12 e §13.

### 8.11 STEP 28 — Storage GC (✅ CONCLUÍDO)

O STEP 28 implementou o garbage collection de objetos de mídia sem referência no SeaweedFS, como varredura direta e separada do Outbox:

* **Contrato de reconciliação** (28.1): listagem paginada por `start-after`/`max-keys` restrita ao prefixo `reviews/`, cruzada com `review_media` por página.
* **Quarentena persistente** (28.3): tabela `storage_object_quarantine` (migração `V15`), uma linha por `object_key`.
* **Grace period**: política `StorageQuarantineGracePolicy`, com duração obrigatória na configuração e sem valor padrão.
* **Rechecagem** sob o mesmo lock da review usado pelo upload; `ACTIVE` e `REMOVED` continuam sendo referência.
* **Exclusão física idempotente** (28.4): somente `CONFIRMED_ORPHAN`, após nova consulta sob locks; `NOT_FOUND` é sucesso; sem transação distribuída.
* **Operação** (28.5): scheduler **desligado por padrão**, dry-run estrutural, limites por ciclo, advisory lock do PostgreSQL entre instâncias, métricas `rewit.storage_gc.*` e logs sem object keys.

Fechamento: commit final `add58f1`; suíte com `1243` testes, `0` failures, `0` errors, `0` skipped; diagnostics Java `0`; working tree limpo; sem push. Nenhum objeto histórico do bucket foi removido manualmente. Detalhes em [storage-reconciliation.md](../architecture/storage-reconciliation.md) e na [ADR-010](../decisions/ADR-010-storage-garbage-collection.md).

### 8.12 STEP 29 — Cleanup de Sessões Expiradas (✅ TECNICAMENTE CONCLUÍDO; decisão de produto/jurídico pendente)

* **29.1** (`5336306`): a revogação de todas as sessões do usuário no reúso de refresh token passa a persistir (`noRollbackFor = RefreshTokenReuseDetectedException`).
* **29.2** (`ddc959b`): mapeamento de `check_ins.distance_to_centroid_meters` alinhado ao schema; a aplicação real volta a subir com `ddl-auto=validate`.
* **29.3** (`2d49b38`):
  - **Metadados**: `ip_address` e `user_agent` viram `NULL` em sessões inativas (`revoked_at IS NOT NULL OR expires_at < now`); sessões ativas não são tocadas.
  - **Purge**: remove somente `expires_at < now AND replaced_by_session_id IS NULL`. Antecessoras de cadeias vivas ficam protegidas; o `ON DELETE SET NULL` desmonta a cadeia um elo por lote, sem CTE recursiva.
  - **Concorrência**: lotes `FOR UPDATE SKIP LOCKED`, uma transação por lote, sem advisory lock.
  - **Índice `V16`**: parcial em `replaced_by_session_id`, criado com `CONCURRENTLY` (`executeInTransaction=false` e `spring.flyway.postgresql.transactional-lock=false`).
  - **Operação**: job ligado por padrão (`rewit.auth-session-cleanup.*`, lote 100, 1 lote por fase a cada hora), desligado nos testes, sem dry-run e sem endpoint; métricas `rewit.auth_session_cleanup.*`; logs sem token, hash, IP, User-Agent ou ids.
  - `AuthService`, TTL, refresh e logout não foram alterados.
  - Suíte com `1271` testes, `0` failures, `0` errors, `0` skipped; sem push.

* **Gate final**: V16 válida, query de purge e cadeia conforme a ADR-011, reuse detection do `5336306` intacta (`AuthService` inalterado desde então), scheduler único, métricas e logs sanitizados, documentação consistente; sem alteração de código.

Pendente: decisão de produto/jurídico sobre a coleta de IP/User-Agent enquanto a sessão está ativa ([privacy-and-lgpd.md §6](../security/privacy-and-lgpd.md)). Até lá a [ADR-011](../decisions/ADR-011-session-retention-and-cleanup.md) permanece **Proposto**.

### 8.13 Ciclos Consolidados Pós-STEP 29 (C2, C3, C5.1, C9, C10 e C11) — ✅ CONCLUÍDOS

Entre 04/10/2026 e 14/10/2026, foram implementados, testados e integrados à branch principal (`origin/main`, commit `dfd84ae`) os seguintes ciclos funcionais:

#### 8.13.1 Ciclo C2 — Ciclo de Vida da Conta de Usuário (Fases 1, 2 e 3)
* **Estado Explícito**: Coluna `account_status` na tabela `users` com valores `ACTIVE`, `DEACTIVATED`, `SUSPENDED` e `DELETED` via migração Flyway [V21](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V21__users_account_status.sql).
* **Guarda de Autoria em Mutações**: A classe `AccountStatusPolicy` intercepta mutações de conteúdo (reviews, comentários, votos e denúncias). Contas inativas, suspensas ou excluídas são sumariamente barradas com `401 ACCOUNT_DISABLED`, inclusive quando o token JWT emitido antes da alteração de estado ainda for criptograficamente válido.
* **Anonimização Retroativa**: Em avaliações e discussões de autores com conta `DELETED`, o objeto `author` na projeção pública é sanitizado com `displayName = "Usuário excluído"` e `author.isAnonymous = true`, preservando a integridade das notas, votos de utilidade e árvore de comentários sem vazamento de PII.
* **Job de Purge com Janela de Retenção**: Job agendado `AccountPurgeScheduler` que executa a limpeza física de dados sensíveis de contas `DELETED` após janela regulatória de retenção de 30 dias.

#### 8.13.2 Ciclo C3 — Denúncias e Moderação de Discussões
* **Schema e Integridade**: Migração [V18](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V18__discussion_status_integrity.sql) tipando o enum `DiscussionStatus` (`ACTIVE`, `UNDER_REVIEW`, `REMOVED`), migração [V19](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V19__discussion_reports.sql) criando a tabela `discussion_reports` com unicidade de denúncia por usuário/discussão, e migração [V20](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V20__discussion_moderation_audit_logs.sql) criando a trilha append-only de auditoria.
* **Auto-quarentena Preventiva**: A 3ª denúncia pendente de usuários distintos transiciona atomicamente o comentário para `UNDER_REVIEW` sob lock pessimista (`SELECT FOR UPDATE`), sem penalidade algorítmica e sem notificação.
* **Thread por Leitor (C3 D2)**: Montagem da árvore de comentários em dois níveis no servidor: comentários `ACTIVE` são visíveis; comentários `UNDER_REVIEW` somem para terceiros e são projetados como `PENDING_REVIEW` apenas para o próprio autor; comentários `REMOVED` são projetados como *tombstone* sem conteúdo nem autor exclusivamente se possuírem respostas ativas visíveis.
* **Moderação Administrativa**: Endpoints `/api/v1/admin/discussion-reports` e `/api/v1/admin/discussions/{id}/moderate` (`REMOVE_DISCUSSION` / `RESTORE_DISCUSSION`) com auditoria append-only protegida por trigger contra mutações.

#### 8.13.3 Ciclo C5.1 — Busca no Catálogo com Normalização Sem Acento
* **Normalização Imutável**: Migração [V22](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V22__catalog_search_normalization.sql) introduzindo a extensão `unaccent`, a função imutável `rewit_search_normalize(text)` e 5 índices GIN trigram dedicados sobre nomes, categorias e cidades de `places` e `products`.
* **Desempenho**: Elimina varredura sequencial (*full table scan*) em buscas parciais insensíveis a acentos (ex: busca por "cafe" localiza "Café"), mantendo tempo de resposta O(log N) no catálogo.

#### 8.13.4 Ciclo C9 — Contas Comerciais e Reivindicação de Locais Físicos
* **Schema e Regras**: Migração [V23](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V23__place_claim_requests.sql) com a tabela `place_claim_requests` (unicidade de solicitação pendente por local, validação de evidência documental entre 20 e 1000 caracteres).
* **APIs de Gestão e Decisão**: Endpoints em `BusinessAccountController` para cadastro de empresas e solicitação de reivindicações, e em `AdminPlaceClaimController` para análise e decisão administrativa (`APPROVE` / `REJECT`) com justificativa formal obrigatória e vinculação transacional direta em `places.claimed_by_business_id`.
* **Privacidade e Minimização**: Dados fiscais (`taxId`) e evidências restritos à própria conta e à moderação, sem exposição de PII nas listagens públicas.

#### 8.13.5 Ciclo C10 — Endurecimento de Autorização de Moderação
* **Revalidação em Tempo de Execução**: Introdução do serviço `ModeratorRolePolicy`. Todas as rotas de moderação e consultas administrativas revalidam dinamicamente a autoridade (`ROLE_MODERATOR` / `ROLE_ADMIN`) e o status ativo da conta do moderador contra o banco de dados no momento da execução, eliminando o risco de ações não autorizadas originadas de tokens JWT antigos (*stale token*).

#### 8.13.6 Ciclo C11 — Respostas Oficiais de Empresas a Avaliações e Discussões
* **Modelo e Persistência**: Migrações [V24](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V24__business_responses.sql) e [V25](file:///d:/Codigos/Projetos/Rewit/backend/src/main/resources/db/migration/V25__business_responses_removed_index.sql) com as tabelas `business_responses` e `business_response_moderation_audit_logs`. Índice parcial cobrindo respostas removidas ordenadas cronologicamente (`removed_at DESC, id DESC`).
* **Representação Empresarial**: Uma resposta oficial representa a pessoa jurídica (`businessAccountId`, `corporateName`) e o local específico (`placeId`), ocultando a identidade pessoal do operador (`authorUserId`) e seu documento fiscal perante a comunidade.
* **Escopo e Unicidade**: Suporte a respostas oficiais em avaliações ativas e em comentários raiz (`parentId == null`). Unicidade estrita de no máximo uma resposta ativa por empresa para cada alvo.
* **Rate Limiting Distribuído**: Ação `BUSINESS_RESPONSE_CREATION` limitada a 10 requisições por usuário a cada 60 segundos via janela deslizante no Redis.
* **Moderação e Restauração Administrativa**: Endpoints `/api/v1/admin/business-responses/{id}/moderate` e `/api/v1/admin/business-responses/removed` para consulta paginada e restauração auditada, integrados à interface web administrativa com tratamento defensivo de paginação e compatibilidade a `fieldErrors` como Record e Array.

---

## 9. Observabilidade, Performance e Produção

### 9.1 O que já existe
- **Observabilidade V1** ([observability.md](../architecture/observability.md), [ADR-012](../decisions/ADR-012-observability.md)): Actuator só na porta de management (`MANAGEMENT_SERVER_PORT`, padrão `8081`) com `health`, `info` e `prometheus`; 41 métricas `rewit.*` (Outbox, Storage GC, cleanup de sessões) mais as automáticas de JVM, Hikari e HTTP, exportadas por scrape; logs JSON (formato `logstash` nativo do Spring Boot) com `traceId`/`spanId`; tracing OpenTelemetry de HTTP servidor e do cliente do Google Places, exportado por OTLP só com endpoint configurado; sampling por variável. Sem `userId` em logs, spans ou métricas; sem tracing de JDBC nem de `@Scheduled`.
- **Spring Boot Actuator**: antes `/actuator/metrics` ficava na porta da API e era legível por qualquer usuário autenticado; deixou de ser exposto.
- **Hikari Connection Pool**: Configurado com limite de 10 conexões locais e timeouts defensivos.
- **Rate Limiting Distribuído** ([ADR-013](../decisions/ADR-013-distributed-rate-limiting.md)): login, refresh, cadastro, denúncias, comentários e uploads limitados por janela deslizante no Redis, com fallback em memória por instância se o Redis cair.
- **Flyway Validation**: Validação rigorosa dos checksums das migrações no startup da aplicação.
- **Java Virtual Threads**: Ativadas nativamente (`spring.threads.virtual.enabled: true`) para alto throughput de I/O bloqueante.

### 9.2 O que falta para Prontidão de Produção
Logs JSON, métricas Prometheus e tracing HTTP foram entregues na Observabilidade V1 (§9.1) e saíram desta lista. Restam, como frentes separadas e sem decisão tomada:

- **Métricas de negócio novas**: latência do Feed V2 e contagem de check-ins rejeitados (a "taxa de cache hits" saiu do escopo: o cache está adiado, §10).
- **Tracing além do HTTP**: JDBC e jobs `@Scheduled` foram deliberadamente deixados de fora (ADR-012).
- **Políticas de Backup e Restore**: Rotinas documentadas de backup contínuo (WAL archiving e pg_dump) para o PostgreSQL com PostGIS.
- **Testes de Carga Automatizados**: Scripts de teste de stress (k6 ou Gatling) simulando centenas de usuários navegando no feed simultaneamente.

---

## 10. Evolução Futura Deliberadamente Adiada

As seguintes frentes tecnológicas e melhorias algorítmicas foram **propositadamente congeladas** e não devem ser iniciadas sem a satisfação de seus critérios de entrada (*gates*):

1. **Backfill Parcial entre Social e Descoberta**:
   - *Decisão*: Atualmente o Cold Start é um fallback completo (acionado exclusivamente quando o retrieval social retorna vazio). O preenchimento híbrido parcial foi adiado para evitar mistura de contextos na fase inicial.
2. **Segmentação Visual entre Conteúdo Social e Descoberto**:
   - *Decisão*: Não há flags na API indicando se o item proveio da rede social ou de descoberta global.
3. **Paginação Baseada em Cursor**:
   - *Decisão*: O Feed V2 utiliza offset pagination (`page`, `size`) sobre uma janela delimitada de 100 candidatos. O cursor será avaliado quando for necessário mitigar drift temporal sob alto volume de escrita simultânea.
4. **Ranking Contextual e Personalização**:
   - *Decisão*: O ranking baseia-se exclusivamente em sinais factuais auditáveis (social direto, recência exponencial, check-in no local e votos úteis). Personalização baseada em histórico de cliques ou categorias preferidas está suspensa.
5. **Descoberta Geográfica Explícita**:
   - *Decisão*: O Cold Start atual é global e não utiliza localização implícita para evitar tracking e garantir simplicidade operacional.
6. **Cache Agressivo de Feed em Redis**:
   - *Decisão*: O PostgreSQL 18 resolve o candidate retrieval com tempo de resposta excelente para a janela atual. Não introduzir cache de feed em Redis até que medições sob carga real apontem saturação de CPU/IOPS no banco.
7. **Modelos de Machine Learning & Embeddings Vetoriais**:
   - *Decisão*: O produto prioriza explicabilidade, transparência e determinismo. Modelos neurais ou embeddings de recomendação estão suspensos.
8. **Cluster Externo de Mensageria (Kafka/RabbitMQ)**:
   - *Decisão*: Evitar complexidade operacional prematura enquanto a fila transacional em PostgreSQL atender com folga à volumetria do sistema.
9. **Substituição do Feed V1 ou Search V1**:
   - *Decisão*: O Feed V1 (`GET /api/v1/feed`) e o Search V1 permanecem congelados e independentes.

---

## 11. Decision Gates (Critérios de Decisão Técnica)

Para evitar retrabalho e desvios arquiteturais, toda evolução relevante deve obedecer aos seguintes gates formais:

```
[Decisão Técnica] ────────► [Critério Mínimo / Gate] ─────────► [Ação Permitida]
Cold Start (V2)             Fase de descoberta e fallback       ✅ CONCLUÍDO (STEP 24.5 / 24.5.1)
                            determinístico implementados
Content Lifecycle (V1)      Mutações de domínio, 24h window,    ✅ CONCLUÍDO (STEP 25.1 a 25.3.1)
                            helpful lock, soft delete e HTTP
Moderação Admin (V1)        Roles, auditoria e migração V11     ✅ CONCLUÍDO (STEP 26.1 a 26.3)
Casos de Uso de Moderação   ModerateReviewUseCase & QueryReports✅ CONCLUÍDO (STEP 26.2 / 26.2.1)
HTTP Admin Moderation (V1)  Endpoints REST /api/v1/admin/**     ✅ CONCLUÍDO (STEP 26.3)
Outbox Foundation (V1)      Migração V12, claim atômico e       ✅ CONCLUÍDO (STEP 27.1)
                            SKIP LOCKED validados no PG real
Outbox Dispatcher (V1)      Lease/reclaim, retry backoff e      ✅ CONCLUÍDO (STEP 27.2)
                            worker @Scheduled no PG real
Outbox Producer (V1)        Enqueue transacional + handler      ✅ CONCLUÍDO (STEP 27.3)
                            com retry herdado no PG real
Outbox Operational V1       Métricas, retenção, purge e        ✅ CONCLUÍDO (STEP 27.4 / 27.4.1)
                            diagnostics Java reais resolvidos
Storage GC (V1)             Quarentena, rechecagem sob lock,    ✅ CONCLUÍDO (STEP 28)
                            exclusão idempotente e dry-run
Cleanup de Sessões          Critério estrutural, SKIP LOCKED,   ✅ TECNICAMENTE CONCLUÍDO (STEP 29; ADR-011 Proposto)
                            minimização de IP/User-Agent
Observabilidade V1          Porta 8081, Prometheus, JSON logs  ✅ CONCLUÍDO (ADR-012 Proposto)
Rate Limiting Distribuído   Redis Lua atômico, chaves HMAC      ✅ CONCLUÍDO (ADR-013 Proposto)
Ciclo de Vida de Contas     AccountStatusPolicy, V21 e purge    ✅ CONCLUÍDO (Ciclo C2)
Moderação de Discussões     V18 a V20, quarentena e moderação   ✅ CONCLUÍDO (Ciclo C3)
Busca Otimizada de Catálogo unaccent, GIN trigram e V22         ✅ CONCLUÍDO (Ciclo C5.1)
Reivindicação de Locais     place_claim_requests e V23          ✅ CONCLUÍDO (Ciclo C9)
Autorização de Moderação    ModeratorRolePolicy em runtime      ✅ CONCLUÍDO (Ciclo C10)
Respostas Oficiais Empresas business_responses, rate limit, V24 ✅ CONCLUÍDO (Ciclo C11)
Cache em Redis              Latência p99 > 200ms no banco       Implementar cache layer
Mensageria Externa          Outbox no PG > 5.000 msgs/s         Adicionar broker externo
Migração de Banco           Nova coluna/tabela inevitável       ✅ V1 a V25 CONSOLIDADAS
```

### 11.1 Conclusão dos Gates Históricos (STEPs 24 a 29)
Os gates dos passos fundacionais de Cold Start (24.5), Ciclo de Vida de Avaliações (25), Moderação Administrativa (26), Outbox Transacional (27), Storage GC (28) e Cleanup de Sessões (29) encontram-se integralmente cumpridos e documentados nas subseções correspondentes (§8.1 a §8.12).

### 11.2 Conclusão dos Gates dos Ciclos C2 a C11
* **Gate do Ciclo C2 (Ciclo de Vida de Contas)**: `V21` aplicada e validada (`ddl-auto=validate`), `AccountStatusPolicy` interceptando mutações de autoria com `401 ACCOUNT_DISABLED`, autor com conta `DELETED` projetado anonimamente como *"Usuário excluído"*, e job de expurgo físico validado com retenção de 30 dias.
* **Gate do Ciclo C3 (Discussões & Moderação)**: `V18`, `V19` e `V20` ativas; auto-quarentena concorrente sob lock pessimista na 3ª denúncia; projeção determinística da thread por leitor sem expor status interno de moderação; moderação e auditoria append-only comprovadas em testes integrados.
* **Gate do Ciclo C5.1 (Busca no Catálogo)**: `V22` com extensão `unaccent` e função imutável `rewit_search_normalize`; consultas textuais utilizam índices GIN trigram sem full-table scan; semântica insensível a acentos validada.
* **Gate do Ciclo C9 (Contas Comerciais & Reivindicações)**: `V23` com tabela `place_claim_requests`; submissão, verificação e decisão administrativa auditadas com justificativa obrigatória e vínculo a `places.claimed_by_business_id`.
* **Gate do Ciclo C10 (Endurecimento de Autorização de Moderação)**: Revalidação dinâmica do cargo (`Role`) e estado ativo da conta contra o banco de dados em tempo de execução via `ModeratorRolePolicy`, prevenindo execução de moderação por tokens antigos (*stale JWT*).
* **Gate do Ciclo C11 (Respostas Oficiais de Empresas)**: `V24` e `V25` ativas; publicação restrita a empresas verificadas e locais associados; unicidade de resposta ativa por alvo; rate limiting distribuído `BUSINESS_RESPONSE_CREATION`; moderação administrativa e fila web de consulta e restauração de respostas removidas.

---

## 12. Estado Atual das Suítes de Testes por Módulo

O projeto Rewit é concebido como um monorepo modular composto por três frentes tecnológicas autônomas: API central Spring Boot (Java 25 LTS), aplicativo móvel Flutter (Dart) e painel web administrativo React (TypeScript/Vite). As suítes de testes utilizam executores e ferramentas distintas (`maven-surefire-plugin`, `flutter test` e `vitest`).

Por possuírem granularidades e ambientes distintos (especialmente no backend, onde métodos anotados com `@ParameterizedTest` expandem em múltiplos casos de teste em tempo de execução), **as métricas de execução de cada módulo são registradas e acompanhadas individualmente, sem consolidação em uma soma global heterogênea**.

---

### 12.1 Backend (`backend` — Spring Boot / JUnit 5)
* **Resultados da Execução Mais Recente Comprovada**:
  * **Testes Executados**: **1.860 testes**
  * **Falhas**: `0`
  * **Erros**: `0`
  * **Ignorados / Skipped**: `0`
  * **Taxa de Aprovação**: 100%
  * **Evidência / Origem**: Execução no commit `8b5dd28` da branch `agent/claude/business-response-eligibility-policy` (`Tests run: 1860, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`); os commits seguintes da branch só alteram o roadmap. A validação pós-C11 anterior registrou 1.842 na branch integrada `integration/post-c11-docs-hardening`, commit `7b9473d`.
  * **Ambiente de Teste**: Maven (`./mvnw clean test`), Java 25 LTS, JUnit 5 e Spring Boot 4.1.1 sob perfil `local`, orquestrando containers reais de PostgreSQL 18 + PostGIS 3.6, Redis 7 e SeaweedFS (compatível S3).
* **Inventário Estático de Código de Teste**:
  * **Arquivos de Teste**: 202 arquivos `.java` em `backend/src/test/` no commit `8b5dd28`, dos quais 199 contêm métodos de teste (os demais são classes de apoio).
  * **Métodos Anotados de Teste**: 1.838 declarações no código-fonte do commit `8b5dd28` (1.831 com anotação `@Test` e 7 com `@ParameterizedTest`); na validação pós-C11 (`7b9473d`), 1.820 (1.813 e 7), e na base `dfd84ae`, 1.815 (1.808 e 7). Na execução de `8b5dd28`, os 7 métodos parametrizados geram 29 invocações, que somadas aos 1.831 métodos `@Test` resultam nos 1.860 casos executados.
* **Histórico de Baselines do Backend**:
  * *STEP 29.3* (`2d49b38`): 1.271 testes verdes no checkpoint do Session Cleanup, que fechou os passos 24 a 29 (Cold Start, Content Lifecycle, Moderação Admin V1, Outbox, Storage GC e Session Cleanup).
  * *Último registro antes da consolidação pós-C11* (`298d0bc`, 06/10/2026): 1.462 testes verdes, depois da Observabilidade V1 (1.293 testes em `22b465e`), do rate limiting distribuído, do estado da conta nas mutações (`C2` Fase 1) e das denúncias e moderação de discussões (`C3`).
  * *Evolução de `298d0bc` até a validação pós-C11 (`7b9473d`)*: 380 testes de execução adicionais (1.842 − 1.462), cobrindo o ciclo de vida e o purge de contas (`C2`), normalização e unaccent de catálogo (`C5.1`), contas comerciais e reivindicação de locais (`C9`), revalidação de privilégios de moderação contra stale tokens (`C10`) e respostas oficiais comerciais (`C11`).
  * *Política B de elegibilidade das respostas oficiais* (`8b5dd28`): 18 testes de execução adicionais (1.860 − 1.842), cobrindo a avaliação da representação, o filtro das leituras públicas, o bloqueio da restauração e a consulta administrativa (§13.1).

---

### 12.2 Aplicativo Mobile (`apps/mobile` — Flutter / Dart)
* **Resultados da Execução Mais Recente Comprovada**:
  * **Testes Executados**: **618 testes** em 48 arquivos
  * **Falhas**: `0`
  * **Erros**: `0`
  * **Ignorados / Skipped**: `0`
  * **Taxa de Aprovação**: 100%
  * **Evidência / Origem**: Nova validação de compatibilidade da política B realizada pelo Gemini em 14/10/2026 em clone isolado sobre o commit `b367322` da branch `agent/claude/business-response-eligibility-policy` (`All tests passed!`), confirmando compatibilidade plena com a política B sem qualquer alteração em `apps/mobile/`, `pubspec.yaml` ou `pubspec.lock` em relação à base integrada `033a1ab`. A validação anterior do Gemini (pré-política B, realizada na branch `agent/gemini/post-c11-validation-and-docs`) também registrou os mesmos 618 testes aprovados em 59s.
* **Análise Estática**:
  * `flutter analyze`: **0 problemas encontrados** (*"No issues found!"*, código de término 0 em 39.6s na validação da política B; 26.8s na execução anterior).
* **Ambiente e Ferramentas**:
  * Flutter SDK 3.47.7 (Dart 3.13.5).
* **Escopo e Cobertura Funcional**:
  * 48 arquivos de teste em `apps/mobile/test/` cobrindo fluxos completos de autenticação segura via `FlutterSecureStorage`, ciclo de vida de contas, navegação, feeds, criação multi-alvo com validação geoespacial, scanner óptico de código de barras e QR Code, discussões aninhadas, notificações e visualização/gestão de respostas oficiais de empresas com selo de verificação.

---

### 12.3 Painel Administrativo Web (`apps/admin` — React / TypeScript / Vitest)
* **Resultados da Execução Mais Recente Comprovada**:
  * **Testes Executados**: **82 testes** em 16 arquivos
  * **Falhas**: `0`
  * **Erros**: `0`
  * **Ignorados / Skipped**: `0`
  * **Taxa de Aprovação**: 100%
  * **Evidência / Origem**: Execução via `npx vitest run` no ciclo da política B, sobre o conteúdo do commit `8b5dd28` da branch `agent/claude/business-response-eligibility-policy`. A execução anterior do Gemini, na branch `agent/gemini/post-c11-validation-and-docs`, registrou 78 testes em 41.44s.
* **Compilação, Lint e Build de Produção**:
  * `npm run build` (`tsc && vite build`): concluído sem erros de compilação no mesmo ciclo (bundle Vite em 167ms; dist gerado em `apps/admin/dist/`); `eslint` sem pendências. Na execução anterior do Gemini, o build levou 442ms.
* **Ambiente e Ferramentas**:
  * Node.js, Vitest, `@testing-library/react`, TypeScript 5.
* **Escopo e Cobertura Funcional**:
  * 16 arquivos de teste em `apps/admin/src/` cobrindo cliente HTTP com renovação de sessão e trava de concorrência, dashboard com contadores em tempo real, filas de moderação de avaliações e discussões, fila de solicitações de reivindicação de locais (`place_claim_requests`) e nova fila de triagem de respostas oficiais removidas (`RemovedBusinessResponsesQueue`) com modal de restauração auditada (`RestoreBusinessResponseModal`), recuperação de paginação fora de intervalo e tolerância a `fieldErrors` como Record e Array.

---

## 13. Próximo Passo Imediato e Planejamento

O ecossistema Rewit concluiu formalmente os ciclos C2, C3, C5.1, C9, C10 e C11. As decisões arquiteturais e os próximos passos distribuem-se em:

### 13.1 Decisões Arquiteturais Pendentes

1. **Decisão de Produto/Jurídico sobre IP/User-Agent em Sessões Ativas (ADR-011)**:
   - O job técnico de expurgo de sessões expiradas e sanitização de metadados de sessões inativas está integralmente implementado e testado no código (STEP 29.3 / `CleanupAuthSessionsUseCase`).
   - Permanece pendente a decisão jurídica quanto à base legal e necessidade de coleta de IP e User-Agent enquanto a sessão está ativa. A [ADR-011](../decisions/ADR-011-session-retention-and-cleanup.md) permanece formalmente com status **Proposto** até parecer final.
2. **Homologação das ADRs 012 e 013**:
   - As implementações de Observabilidade V1 (ADR-012) e Rate Limiting Distribuído (ADR-013) encontram-se 100% operacionais no código e com suítes de teste verdes. Seus documentos de decisão permanecem com status formal **Proposto**, cabendo a aprovação formal ao mantenedor do projeto para manter a governança documental.
3. **Decisão de Produto sobre Elegibilidade na Restauração e Visibilidade de Respostas Oficiais (C11)** — **DECIDIDO — opção B aprovada; implementada na branch `agent/claude/business-response-eligibility-policy`, pendente de revisão e integração**:
   - **Comportamento anterior** (até `033a1ab`): a restauração (`RESTORE_RESPONSE` em `ModerateBusinessResponseUseCase`) verificava só a transição de estado, a unicidade da resposta ativa no alvo e o conflito de interesse do moderador, sem revalidar o que a publicação exige via `BusinessRepresentationPolicy`; o indicador `restorable` e a listagem pública também ignoravam esses estados. No hardening do C11 verificou-se que, com conta não aprovada, administrador inoperante ou local inativo ou desvinculado, a restauração respondia `200` e a resposta voltava à listagem pública.
   - **Contexto histórico das alternativas**: avaliou-se a alternativa *(a)*, que preservaria o desacoplamento atual mantendo visíveis respostas históricas e permitindo sua restauração independente do estado superveniente da representação, versus a alternativa *(b)*, que exige coerência estrita de elegibilidade em todo o ciclo de vida da resposta oficial.
   - **Decisão aprovada pelo mantenedor (Opção B)**:
     - A elegibilidade deve ser coerente e unificada entre a **publicação** de novas respostas oficiais, a **restauração** de respostas removidas e a **visibilidade pública** de respostas ativas.
     - Respostas oficiais vinculadas a uma representação comercial atualmente inelegível **não devem aparecer na listagem pública** (ficam ocultas da comunidade enquanto durar a inelegibilidade).
     - A **restauração administrativa deve ser bloqueada** enquanto os requisitos de elegibilidade não forem atendidos.
     - Não basta verificar a elegibilidade apenas no instante da restauração, pois respostas já ativas também precisam obedecer à regra de visibilidade pública.
     - Os critérios de elegibilidade a observar são os já consolidados em `BusinessRepresentationPolicy`: conta comercial aprovada (`verification_status = 'APPROVED'`), usuário administrador operacional (`account_status = 'ACTIVE'`) e estabelecimento ativo vinculado à conta (`places.claimed_by_business_id` e `places.status = 'ACTIVE'`).
   - **Política implementada** (commits `f7bd9e1`, `4522a18`, `341ef22` e `8b5dd28`, sobre `033a1ab`; sem migration):
     - *Regra única*: `BusinessRepresentationPolicy.evaluate` define os três critérios com motivos estruturados (`BusinessRepresentationIneligibility`); `currentIneligibility` avalia a representação sem ator e sem lock. A publicação mantém passos, códigos e ordem de locks.
     - *Leitura pública*: as listagens de respostas a avaliações e a comentários filtram em SQL a representação elegível, com o mesmo FROM/WHERE no conteúdo e na contagem; a resposta oculta não muda de estado nem de auditoria e volta a aparecer automaticamente quando a representação volta a ser elegível (por exemplo, reativação ou reintegração do administrador). Os métodos de listagem sem chamadores em produção usam o mesmo filtro.
     - *Restauração*: depois da autorização, do conflito de interesse e da transição do domínio, a representação inelegível responde `409 BUSINESS_RESPONSE_NOT_RESTORABLE` com os motivos no `detail`, sem gravar nem auditar. Não há lock no local: travá-lo depois da resposta inverteria a ordem da publicação.
     - *Consulta administrativa*: campo aditivo `restorationBlockers` (`REMOVED_BY_BUSINESS`, `OTHER_ACTIVE_RESPONSE`, `ACCOUNT_NOT_APPROVED`, `ADMINISTRATOR_NOT_OPERATIONAL`, `PLACE_NOT_REPRESENTED`), com `restorable` verdadeiro só sem bloqueios; o administrador aparece só como não operacional. O painel exibe uma explicação por motivo.
   - **Validações executadas** (no commit `8b5dd28`):
     - Backend: `./mvnw clean test` com 1.860 testes, 0 falhas, 0 erros e 0 ignorados (18 novos), incluindo a matriz de estados nas listagens, paginação e contagem, paridade entre a regra Java e o SQL nas listagens e na consulta administrativa, restauração bloqueada por motivo e fluxo HTTP com suspensão e reintegração do administrador.
     - Admin: `vitest run` com 82 testes aprovados (4 novos), `eslint` sem pendências e `tsc && vite build` concluído.
     - Mobile: não reexecutado no commit de código `8b5dd28` (contrato público inalterado); validação posterior de compatibilidade executada pelo Gemini no commit `b367322` em clone isolado (618 testes aprovados, `flutter analyze` limpo, sem alterações em `apps/mobile/` em relação a `033a1ab`; ver §12.2).
     - Desempenho: `EXPLAIN ANALYZE` no PostgreSQL local com massa sintética (100.100 respostas) dentro de transação desfeita: a listagem segue o índice único parcial e resolve conta, administrador e local por chave (cerca de 0,1 ms por página); a fila administrativa sem filtro levou 27 ms, a maior parte na subconsulta `other_active` que já existia. Não é medição de produção.
   - **Limitações e questões pendentes**:
     - Uma resposta ativa oculta continua ocupando a vaga da empresa no alvo; para publicar outra, a empresa precisa retirá-la. Mudar isso exige decisão adicional.
     - Administrador `DELETED` não volta a ser operacional, e não há fluxo de troca do administrador da conta: as respostas dessa conta ficam ocultas. Quando a troca existir, as respostas publicadas sob o administrador anterior reapareceriam sob o novo; isso precisa ser decidido junto com o fluxo.
     - Não existem fluxos que revoguem a aprovação da conta, removam ou transfiram o vínculo do local, ou desativem o local; hoje esses estados só ocorrem por escrita direta no banco. A leitura pública já os respeita, mas `BusinessRepresentationPolicy` lê a conta sem lock por pressupor que a aprovação só avança: os fluxos da gestão de estabelecimentos reivindicados (§13.2) devem revisitar esse pressuposto e a ordem de locks.
     - Se a elegibilidade cair entre a checagem e o commit da restauração, a resposta fica restaurada e oculta pela leitura pública até a representação voltar.

### 13.2 Proposta de Continuidade para a Próxima Fase
A próxima etapa de desenvolvimento deve focar na **Gestão de Estabelecimentos Reivindicados e Catálogo Local**, expandindo a capacidade das contas comerciais aprovadas de configurar horários de funcionamento, comodidades e produtos disponíveis no local, consolidando a Fase 2 do produto.

> [!NOTE]
> O subsistema de Moderação Administrativa e Respostas Oficiais está 100% operacional em backend, mobile e admin. O Outbox Pattern opera com entrega at-least-once com provider mock (`MockNotificationAdapter`), sendo a transição para push real (FCM/APNs) parte das frentes técnicas subsequentes.
