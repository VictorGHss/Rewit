# HISTÓRICO DE ALTERAÇÕES (CHANGELOG.md)

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/), e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Unreleased]

### Adicionado
- **Denúncia de Avaliações Mobile — Trust & Safety V1.A (`apps/mobile`)**:
  - Criado o módulo dedicado `lib/features/reports/` (`ReportReason`, `ReportStatus`, `ReviewReportOutcome`, `ReviewReportReceipt`, `ReportResponseDto`, `ReportRepository`, `ReportRepositoryImpl` e `ReportReviewDialog`), integrando o endpoint oficial `POST /api/v1/reports` via `RewitHttpClient` e `TokenRefreshingClient` com autenticação `Bearer JWT` e renovação transparente de sessão.
  - Extraída e compartilhada a enumeração `ReportReason` com preservação integral dos códigos (`SPAM`, `HARASSMENT`, `HATE_SPEECH`, `MISINFORMATION`, `INAPPROPRIATE_CONTENT`, `FRAUD`), rótulos em português e re-exportação retrocompatível para o fluxo de discussões.
  - Distinção explícita pelo status HTTP e por `ReportStatus` entre nova denúncia criada (`201 Created` → `ReviewReportOutcome.created`) e denúncia previamente registrada (`200 OK` → `ReviewReportOutcome.alreadyReported`), com mensagens específicas para `PENDING`, `ACCEPTED`, `REJECTED` e valores desconhecidos.
  - Integrada a ação contextual `Denunciar avaliação` no menu da `AppBar` de `ReviewDetailScreen` com verificação *fail-closed* e precedência contra sinais contraditórios de autoria (exibida apenas para avaliações `ACTIVE` com `ReportRepository` disponível e não autoria comprovada via `isMine == false` sem contradição com `widget.isAuthor == true` ou `author.id == currentUserId` em avaliações públicas, ou fallback público estrito com `author.id != currentUserId`; oculta para avaliações anônimas com `isMine == null`, sinais contraditórios e `widget.isAuthor == false` isolado).
  - Tratamento estruturado de erros RFC 7807, expiração de sessão, `ACCOUNT_DISABLED` global e falhas de rede no `ReportReviewDialog` (`400 SELF_REPORT_FORBIDDEN`, `400 VALIDATION_ERROR`/`MALFORMED_REQUEST`, `401 ACCOUNT_DISABLED` propagado via `onSessionExpiredWithDetails`/`SessionExpirationDetails` ao `AuthNotifier.handleSessionExpired(details)` com limpeza imediata de tokens sem acionar refresh, invalidação de renovação concorrente no `SessionRefresher`, mensagem local neutra sem reativação social e desempilhamento único no `RewitApp`, `401 AUTHENTICATION_REQUIRED` integrado a `AuthNotifier.sessionCheckSequence`/`sessionCheckSince()` tratando `rejected`, `refreshed` e `unavailable` sem `Navigator.pop()` duplicado, `403 FORBIDDEN`, `404 REVIEW_NOT_FOUND` fechando o diálogo e transicionando `ReviewDetailScreen` para estado explícito de indisponibilidade, `429 RATE_LIMIT_EXCEEDED` com `Retry-After` via `ErrorBanner.formatRetryAfter`, `NetworkException` e exceções inesperadas com restauração segura do estado de envio).
  - Hardening de integridade de sessão, propriedade de tentativas concorrentes (prevalece a intenção de autenticação mais recente), Política 2 (preservação da sessão anterior sob falha de nova tentativa) e cobertura de testes unitários, de widget e integração E2E em `session_token_coordinator_test.dart`, `token_refreshing_client_test.dart`, `auth_flow_test.dart`, `http_client_test.dart`, `session_expiry_navigation_test.dart`, `report_repository_test.dart` e `report_review_widget_test.dart`: adicionado `SessionTokenCoordinator` com separação entre `sessionGeneration` e `authenticationAttemptSequence` (`beginAuthenticationAttempt`, `abandonAuthenticationAttempt`, `saveNewSessionTokens(..., expectedAttemptId: ...)` com compensação FIFO em escrita suspensa e `StaleAuthenticationAttemptException`), contrato `AttemptScopedAuthRepository` compartilhando o identificador de tentativa desde `AuthNotifier` até `AuthRepositoryImpl` em login, registro, login social e reativações local/social, snapshot geracional da sessão anterior em `AuthNotifier` com `AuthenticationAttemptError` (`lastAttemptError`) preservando `Authenticated(X)` e seus tokens (incluindo refresh legítimo paralelo) quando nova tentativa falha sem invalidar `X`, descarte definitivo em nova sessão confirmada / `logout` / `ACCOUNT_DISABLED` / `deactivateAccount` / `changePassword`, compromisso em duas fases entre `AuthRepositoryImpl.requestTokenRefresh()` e `SessionRefresher`, precedência determinística de `_inFlightDisabledExpiry`/`ACCOUNT_DISABLED` em `AuthNotifier.checkAuthStatus()`, e suíte determinística cobrindo todos os cenários A–K com verificação conjunta de `AuthNotifier.state` e `TokenStorage`.

### Corrigido
- **Minimização e Proteção de Coordenadas GPS em Check-ins (`backend`, `db/migration`, `docs`)**:
  - Criada a migration `V43__checkin_coordinate_minimization.sql`: torna `check_ins.coordinates` e `check_ins.distance_to_centroid_meters` anuláveis, remove o índice `idx_checkins_coordinates`, adiciona `rejection_reason VARCHAR(64)` com constraints `chk_checkin_rejection_reason` e `chk_checkin_rejection_reason_consistency` alinhadas a `PresenceVerificationOutcome`, impõe `chk_checkin_coordinates_null` (`coordinates IS NULL`) e `chk_checkin_distance_only_verified` (`status = 'VERIFIED' OR distance_to_centroid_meters IS NULL`), executa backfill descartando coordenadas históricas de todos os check-ins (`VERIFIED`, `REJECTED` e `PENDING`) e a distância exata em registros `REJECTED` e `PENDING`, preservando todas as linhas, `reviews.is_verified_on_site`, `reviews.updated_at` e os privilégios mínimos de runtime (`rewit_assert_runtime_privileges()`).
  - Atualizados `CheckIn`, `CheckInJpaEntity` e `Review` para usar `Double` anulável em `latitude`, `longitude` e `distanceToCentroidMeters`, remover o fallback silencioso para `(0.0, 0.0)`, garantir estruturalmente em `CheckIn` e `CheckInJpaEntity.fromDomain()` que coordenadas nunca são persistidas e que apenas `VERIFIED` mantém/persiste distância até o centróide, limpar distância em `CheckIn.reject()`, validar valores não finitos (`NaN`/`Infinity`) em `Review` e `CheckIn` e impedir cálculos geográficos na ausência de coordenadas válidas (`CHECKIN_COORDINATES_UNAVAILABLE`).
  - Atualizado `ReviewService.createReviewWithPresence` para manter a validação espacial transitória em memória/PostGIS e nunca persistir coordenadas GPS em `check_ins`, omitindo também `distanceToCentroidMeters` e registrando `rejectionReason` em check-ins `REJECTED`.
  - Atualizado `AccountPurgeRepositoryAdapter` (`PurgeCounts.checkInLocations`) para limpar eventuais coordenadas residuais em `check_ins` (e distância em `REJECTED`) durante o purge de contas excluídas, preservando linhas verificadas e `reviews.is_verified_on_site`.
  - Atualizados `docs/security/privacy-and-lgpd.md`, `docs/decisions/ADR-005-location-model.md` e `docs/database/schema-overview.md` e ampliada a suíte de testes unitários, integração e upgrade de migration (`CheckInDomainTest`, `CheckInJpaEntityMappingTest`, `ReviewServiceTest`, `CheckInPresenceIntegrationTest`, `ReviewCheckInIntegrationTest`, `PurgeDeletedAccountIntegrationTest` e `DatabaseUpgradeMigrationTest`).
- **Retomada automática da localização após retorno das Configurações (`apps/mobile`)**:
  - Adicionado `LocationService.checkReadiness` (`LocationReady` / `LocationNotReady`) em `GeolocatorLocationService` para revalidar serviço e permissão do dispositivo sem disparar diálogo nativo de permissão (`requestPermission`), preservando a separação entre `LocationPurpose.discovery` e `LocationPurpose.presenceVerification` e mantendo o último estado conhecido de `permissionDeniedForever` no Android quando `Geolocator.checkPermission()` ainda retorna `denied` após retorno sem concessão (limpando-o ao conceder ou em recusa temporária).
  - Adicionado `ReviewCreateNotifier.prepareLocationResumeFromSettings` para coordenar retomada pós-configurações, impedir recaptura redundante quando a permissão continua aproximada, atualizar estados revogados/negados sem prompt automático e descartar resultados obsoletos em concorrência ou `dispose`.
  - Integrado `WidgetsBindingObserver` com guarda explícita de abertura prévia de configurações (`openAppSettings` / `openLocationSettings`), consumo único no `AppLifecycleState.resumed` e proteção contra concorrência e descarte em `ExternalPlaceResultsSection`, `CheckInScreen` e `ReviewCreateScreen`.
  - Ampliada a cobertura automatizada em `location_service_test.dart`, `review_create_notifier_test.dart`, `external_place_results_section_test.dart`, `check_in_test.dart` e `review_create_screen_test.dart` (suíte mobile totalizando **1.462 testes aprovados**).
- **Sincronização do flash/lanterna do Scanner (`apps/mobile`)**:
  - Removido o estado booleano local (`_isTorchOn`) em `ScannerScreen`, passando a observar diretamente `MobileScannerController.value.torchState` (`TorchState.unavailable`, `off`, `on`, `auto`), `isInitialized`, `isRunning` e `error`.
  - Câmeras sem unidade de flash (ou com câmera parada/em erro/controller descartado) mantêm o botão desabilitado (`Icons.no_flash`, tooltip `"Flash indisponível"`) sem falsa ativação visual; alternar de câmera com flash ativo reinicia o controle de acordo com o hardware real da nova lente.
  - Adicionados testes de widget cobrindo câmeras com/sem flash, erro de hardware ao acionar lanterna, alternância entre lentes, falha ao trocar câmera, background/resume e descarte do controller.

## [0.5.1] - 2026-11-14

### Corrigido
- **Decodificação UTF-8 por padrão em respostas HTTP (`apps/mobile`)**:
  - Adicionados `ProblemDetail.decodeResponseBytes` e `ProblemDetail.fromResponseBytes` em `lib/core/error/api_exception.dart` e normalização em `RewitHttpClient` (`lib/core/network/http_client.dart`) para decodificar `application/json`, `application/problem+json` e respostas textuais sem `charset` explícito como UTF-8 por padrão, eliminando mojibake (ex.: `"Credenciais inválidas"` no login) e mantendo respeito a charsets declarados e bytes inválidos com `allowMalformed: true`.
- **Contraste da AppBar do Scanner (`apps/mobile`)**:
  - Configurados explicitamente `iconTheme`, `actionsIconTheme`, `titleTextStyle` e `systemOverlayStyle` na `AppBar` de `ScannerScreen` com `RewitSemanticColors.onMediaScrim` sobre `mediaScrim`, impedindo que `AppTheme.appBarTheme` sobrescrevesse os controles com tinta escura no tema claro.
- **Descoberta de Locais Próximos sem Cache Recente (`apps/mobile`)**:
  - `LocationCapturePolicy.discovery` em `GeolocatorLocationService` mantém captura primária leve (`LocationAccuracy.medium`, teto 5s, cache <= 5 min) e executa no máximo um fallback controlado em `LocationAccuracy.high` (até 4s adicionais, orçamento total de 9s) quando não houver cache válido e a tentativa inicial falhar ou expirar.
- **Explicação Contextual Pré-Permissão da Câmera no Scanner (`apps/mobile`)**:
  - Exposto `hasRequestedPermissionBefore` em `MainActivity.kt` e `CameraPermissionService` e adicionado o estado `ScannerPermissionRationale` em `ScannerNotifier` / `ScannerScreen`, exibindo justificativa clara (`"Continuar"` / `"Agora não"`) antes do primeiro prompt nativo sem disparar solicitação prematura nem repetir em loop após recusa prévia.
- **Testes e Validação**:
  - Suíte automatizada mobile expandida para **1.323 testes aprovados** (`flutter test`), 0 falhas e 0 issues no `dart analyze`.
  - Testes unitários nativos Android mantidos verdes: **9 testes aprovados** (`./gradlew :app:testDebugUnitTest` em `CameraPermissionStateMachineTest`).

## [0.5.0] - 2026-11-14

### Adicionado
- **Navegação & Home V2 Mobile (`apps/mobile`)**:
  - Nova barra de navegação inferior (`NavigationBar`) com 5 destinos canônicos: Início (feed dual), Explorar (busca e cadastro de locais), Rankings (classificações dimensionais), Check-in (presença e proximidade) e Coleção (estado vazio honesto aguardando backend de coleções/listas).
  - AppBar integrada com avatar do usuário com inicial de fallback e navegação para Meu Perfil (`/profile`), título centralizado, indicador de mensagens em breve e botão de notificações com badge reativo de não lidas.
  - Floating Action Button (FAB) com folha modal de ações rápidas: "Nova Avaliação" (`/review/create`) e "Fazer Check-in".
  - Navegação defensiva com `PopScope`: voltar fora da aba Início retorna à Home antes de fechar a aplicação.
- **Feed Duplo (Para Você / Seguindo) e Sincronização Reativa**:
  - Integração com os contratos `/api/v2/feed` ("Para você") e `/api/v1/feed` ("Seguindo").
  - `FeedNotifier` com canais de estado isolados para cada feed, rollback granular imediato de votos úteis (`helpfulCount` e `isHelpfulByMe`) em caso de falha de rede e preservação de IDs removidos durante recargas de recuperação.
  - Sincronização reativa de feed após criar avaliações ou salvar novos interesses em `AppRouter.editInterests`.
- **Scanner Inteligente, Target Selection Mode e Hardening de Ciclo de Vida**:
  - Suporte a leitura óptica de códigos de barras (EAN-13, EAN-8, UPC-A, ITF-14) e QR Codes, além de fallback manual normalizado.
  - Modo de seleção de alvo (`ScannerRouteArgs(selectionMode: true)`) integrado diretamente à tela de criação de avaliações (`ReviewCreateScreen` / `TargetSearchSelectorWidget` / `TargetItemInputWidget`), retornando o alvo selecionado tipado para o formulário.
  - Hardening do ciclo de vida: pausa automática da câmera quando o app entra em `inactive` ou `paused`, e religamento com rechecagem limpa de permissões em `resumed`.
  - Debounce ótico prevenindo múltiplas leituras em rajada.
- **Permissões de Câmera e Máquina de Estados Nativa Android (API 36)**:
  - Máquina de estados determinística nativa em Kotlin (`CameraPermissionStateMachine.kt`, `MainActivity.kt`), tratando as especificidades do Android API 36 para evitar que recusas iniciais ou cancelamentos de diálogo causem negação permanente prematura.
  - Persistência das decisões do usuário em `SharedPreferences` (`hasRequestedBefore`, `permanentlyDenied`).
  - Canal nativo `MethodChannel` (`app.rewit.mobile/camera_permission`) e serviço Flutter `AndroidCameraPermissionService`.
  - Diálogo de justificativa contextual, botão de repetição e redirecionamento assistido para configurações do sistema (`openAppSettings`).
- **Localização e Check-in Hardening**:
  - Implementação de `GeolocatorLocationService` com política explícita por finalidade (`LocationCapturePolicy`):
    - `discovery`: raio de até 10 km, aceita cache de até 5 minutos, precisão média e timeout estrito de até 5 segundos;
    - `presenceVerification`: para check-in e avaliações no local, rejeita cache antigo, exige leitura fresca (<= 1 min), alta precisão GPS, timeout ampliado (10s a 15s) e retry único em caso de acurácia aproximada (> 100m).
  - Suporte a localização simulada/mock preservado no cliente para viabilizar testes ponta a ponta e auditorias em emuladores e QA.
  - Validação de presença estritamente autoritativa no backend via PostGIS (`ST_DWithin`, raio de até 250m, acurácia <= 100m, trava `pg_advisory_xact_lock` e teto de 1 check-in por usuário/local em 24h).
- **Testes e Validação**:
  - Suíte de testes automatizados mobile expandida para **1.297 testes aprovados** (`flutter test`), 0 falhas, 0 issues no `dart analyze`.
  - **9 testes unitários nativos Android aprovados** (`CameraPermissionStateMachineTest`).

## [0.4.0] - 2026-11-04

### Adicionado
- **Design System Next-Gen V1 Mobile (`apps/mobile`)**:
  - **Fundações de Temas e Paletas Material 3**:
    - Suporte a três modos de exibição: Sistema (`ThemeMode.system` como padrão de fábrica), Claro (`ThemeMode.light` com fundo lavanda `#F6F4FB` e primária roxa `#6D28D9`) e Escuro (`ThemeMode.dark` com fundo preto predominante `#09090D`, superfícies em grafite `#17151D` / `#211B2B`, ações roxas `#8055D9` e detalhes lavanda `#B69AFF`).
    - Aplicação estrita das diretrizes visuais: proibição de cards brancos e de grandes superfícies roxas no modo escuro, com roxo restrito a elementos interativos e lavanda a detalhes.
  - **Tokens de Espaçamento e Borda (`RewitTokens`)**:
    - Constantes geométricas em `RewitSpacing` (de `xxs = 2.0` a `xxxl = 32.0`) e paddings semânticos estruturados (`screenPadding`, `cardPadding`, `bannerPadding`).
    - Raios de curvatura canônicos em `RewitRadius` (de `xs = 4.0` a `sheet = 20.0` e `pill = 999.0`) e bordas pré-computadas (`borderMd`, `borderLg`, `sheetTop`).
  - **Cores Semânticas Contextuais via ThemeExtension (`RewitSemanticColors`)**:
    - Extensão nativa imutável estruturada em blocos funcionais `RewitSemanticTone` (`container`, `onContainer`, `border`, `accent`) para estados contextuais (`success`, `warning`, `danger`, `info`), selo de presença verificada (`verified`), avaliações e estrelas (`ratingBadge`, `starFilled`, `starEmpty`), categorias multi-alvo (`placeTarget`, `productTarget`, `serviceTarget`) e superfícies focais (`elevatedFocalSurface`, `anonymousAvatarBackground`, `mediaScrim`).
    - Acesso simplificado via `RewitSemanticColors.of(context)` com fallback defensivo por brilho para execução isolada em testes.
  - **Persistência de Preferência e Gerenciamento Reativo (`ThemeController`)**:
    - Controle de ciclo de vida com `ChangeNotifier` e injeção desacoplada na árvore via `ThemeControllerScope` (`InheritedNotifier`).
    - Persistência segura no dispositivo via `FlutterSecureStorage` sob a chave `rewit_app_theme_mode` (`SecureThemePreferenceStorage`), completamente isolada de `TokenStorage.clearTokens()` (a aparência do app é preservada após logout ou troca de conta).
    - Mecanismo resiliente de fallback em memória (`InMemoryThemePreferenceStorage`) contra indisponibilidade de I/O de secure storage.
    - Seletor acessível de aparência na tela de configurações (`AccountSettingsScreen`) com opções para Sistema, Claro e Escuro.
  - **Migração e Homogeneização Visual de Componentes e Telas**:
    - Eliminação completa de cores e espaçamentos hardcoded em telas de Feed V2, Detalhes de Avaliação, Check-in, Scanner, Rankings, Perfil e Configurações de Conta.
    - Adaptação estruturada de modais, diálogos, bottom sheets, inputs e banners de status.
  - **Acessibilidade e Testes Automatizados**:
    - Conformidade matemática com critérios WCAG AA: contraste `>= 4.5:1` para texto regular e `>= 3:1` para divisores e componentes interativos.
    - Suíte de testes expandida para **1.172 testes automatizados** aprovados (`flutter test`), com 0 falhas, 0 erros e 0 alertas no `flutter analyze`.
    - **Registro de Homologação Pendente**: Aprovado em todos os testes unitários e de integração de widgets; a inspeção visual interativa em hardware físico real (dispositivos móveis Android e iOS) permanece pendente de validação operacional pela equipe.

---

## [0.3.0] - 2026-10-14

### Adicionado
- **Respostas Oficiais de Empresas a Avaliações e Discussões (`C11`)**:
  - **Backend Central (`backend`)**:
    - Endpoints para publicação de respostas oficiais a avaliações (`POST /api/v1/reviews/{reviewId}/business-responses`) e discussões raiz (`POST /api/v1/discussions/{discussionId}/business-responses`), com validação de payload (até 2.000 caracteres) e garantia de no máximo uma resposta ativa por par (empresa, alvo).
    - Endpoints de consulta paginada de respostas oficiais ativas por avaliação e discussão (`GET /.../business-responses`), e verificação de elegibilidade (`GET /api/v1/reviews/{reviewId}/business-responses/eligibility`).
    - Remoção autônoma da própria resposta pela empresa com HTTP 204 (`DELETE /api/v1/business-responses/{responseId}`).
    - Fila administrativa de consulta de respostas oficiais removidas (`GET /api/v1/admin/business-responses/removed`) com paginação e filtros dinâmicos por avaliação, discussão, conta comercial, local e autor da remoção (`BUSINESS` ou `MODERATION`).
    - Ação administrativa de moderação (`POST /api/v1/admin/business-responses/{responseId}/moderate`) permitindo remoção e restauração auditada (`RESTORE_RESPONSE`) com justificativa formal obrigatória (15 a 1.000 caracteres) e código de motivo.
    - Migrações Flyway `V24__business_responses.sql` (tabelas `business_responses` e `business_response_moderation_audit_logs`) e `V25__business_responses_removed_index.sql` (índice parcial `idx_business_responses_removed`).
    - Proteção distribuída contra abuso via rate limit deslizante no Redis (`BUSINESS_RESPONSE_CREATION`), com fallback resiliente em memória e resposta HTTP 429 RFC 7807 (`Retry-After`).
  - **Painel Administrativo Web (`apps/admin`)**:
    - Nova aba "Respostas Oficiais Removidas" integrada à navegação principal e badges operacionais.
    - Fila de triagem `RemovedBusinessResponsesQueue` com visualização tabular completa, filtros dinâmicos por autor da remoção (`BUSINESS` vs `MODERATION`), dados do estabelecimento e local, data de criação, data de remoção e data da última moderação (`moderatedAt`).
    - Modal de restauração auditado `RestoreBusinessResponseModal` com validação de justificativa, confirmação prévia, e tratamento robusto de conflitos concorrentes (`409`, `403`, `404`) com recarga reativa e sem mutação otimista.
    - Recuperação resiliente de páginas em caso de remoções que deixem a paginação fora do intervalo válido.
    - Suíte Vitest expandida para 78 testes cobrindo todos os fluxos da nova área com 100% de aprovação.
  - **Aplicativo Mobile (`apps/mobile`)**:
    - Exibição visual destacada de respostas oficiais de estabelecimentos em avaliações e discussões com crachá de verificação oficial da empresa proprietária.
    - Ações contextuais de resposta oficial para proprietários verificados diretamente na tela de detalhes, com suporte a criação e exclusão.
    - Suíte com 618 testes automatizados aprovados no Flutter (`flutter test`).
- **Segurança e Validação de Moderação em Runtime (`C10`)**:
  - Implementação da `ModeratorRolePolicy` no backend, validando permissões de moderação e estado da conta do moderador em tempo real contra o banco de dados em endpoints administrativos sensíveis.
  - Mitigação de tokens JWT ainda válidos após a revogação de privilégios (*stale tokens*): operações não autorizadas são bloqueadas com HTTP 403 (`FORBIDDEN`, RFC 7807) após a revalidação da política de papéis. Essa resposta não revoga nem encerra a sessão.

---

## [0.2.0] - 2026-10-09

### Adicionado
- **Painel Administrativo Web de Moderação (`apps/admin`)**:
  - Implementação da interface web completa em React 18, TypeScript e Vite, substituindo scaffolds e dados fixos por integração com a API REST real do Rewit.
  - Autenticação administrativa via `POST /api/v1/auth/login` com armazenamento seguro exclusivo em `sessionStorage` (sem uso de `localStorage` ou logs de credenciais).
  - Renovação automática de tokens via `POST /api/v1/auth/refresh` com rotação, trava para refreshes concorrentes e retry único da requisição original.
  - Verificação de privilégios de moderação (`MODERATOR` / `ADMIN`) após autenticação, exibindo tela de Acesso Restrito e limpando a sessão em caso de HTTP 403.
  - Dashboard operacional com agregação em tempo real de casos pendentes de avaliações e discussões e atalhos diretos para as filas.
  - Fila de moderação de avaliações (`/api/v1/admin/reports`) com filtros por status e motivo, paginação e visualização contextual completa.
  - Fila de moderação de discussões (`/api/v1/admin/discussion-reports`) com indicação de raiz versus respostas e filtros estruturados.
  - Modais de contexto exibindo relatos, notas por alvo, contexto da mensagem-pai em discussões, histórico de auditoria e lista de denúncias.
  - Ações formais de moderação auditadas (`REMOVE_REVIEW`, `RESTORE_REVIEW`, `REMOVE_DISCUSSION`, `RESTORE_DISCUSSION`) com exigência de código de motivo, justificativa formal (15 a 1.000 caracteres) e diálogo de confirmação.
  - Preservação estrita de privacidade, ocultando identificadores de autores e denunciantes em todas as telas.
  - Suíte completa de testes automatizados com Vitest e `@testing-library/react` cobrindo cliente HTTP, storage de tokens, autenticação, dashboard, filas e modais.
- **Scanner Mobile Inteligente (`apps/mobile`)**:
  - Leitura óptica de códigos de barras (EAN/UPC) e QR Codes com validação restritiva de tipos permitidos e tratamento de ciclo de vida e permissões de câmera.
- **Discussões e Notificações Contextuais**:
  - Exposição de `rootDiscussionId` nas respostas de notificação para navegação direta e precisa.
- **Contas Comerciais e Reivindicação de Locais (`C9`)**:
  - **APIs REST de Apresentação (`backend`)**: Endpoints de cadastro e consulta de contas comerciais (`/api/v1/business-accounts`), solicitação e histórico de reivindicações (`/api/v1/business-accounts/{id}/place-claims`) e fila de análise e decisão administrativa (`/api/v1/admin/place-claims`) para `MODERATOR`/`ADMIN`.
  - **Painel Administrativo Web (`apps/admin`)**: Aba "Reivindicações" com tabela paginada de solicitações, filtros por status (`PENDING`, `APPROVED`, `REJECTED`), visualização dos dados da empresa solicitante e local, e modal de decisão auditado com justificativa obrigatória (15 a 1.000 caracteres) e diálogo de confirmação.
  - **Aplicativo Mobile (`apps/mobile`)**: Área "Minhas empresas" com listagem de contas comerciais e suas reivindicações de local, bottom sheet para criação de conta com validação de razão social e documento fiscal, e fluxo de reivindicação integrado à tela de detalhes de locais (`PlaceDetailScreen`) com seleção de conta, validação de evidência (20 a 1.000 caracteres) e tratamento amigável de erros.
  - **Privacidade e Segurança**: Minimização de dados estrita, garantindo que documentos fiscais e evidências nunca sejam expostos fora da própria conta e da moderação, e que nenhuma resposta HTTP vaze identificadores de proprietários ou moderadores.

### Removido
- Abas e referências a funcionalidades não implementadas no painel administrativo ("Locais (Fase 2)", "Catálogo Global", "Empresas & Contas").

---

## [0.1.0] - 2026-09-27

### Adicionado
- Fundação completa da arquitetura do repositório (**Modular Monorepo**).
- Definição dos 31 princípios inegociáveis do projeto em `PROJECT_RULES.md`.
- Documento de visão de produto em `PRODUCT_VISION.md` (avaliação multi-alvo, dimensões de experiência, scanner, feed em 3 fases, reputação vs cosméticos).
- Documento de arquitetura detalhado em `ARCHITECTURE.md` (Clean Architecture, PostGIS, isolamento do Google via Anti-Corruption Layer, MinIO, Redis).
- Registros de Decisão de Arquitetura iniciais (`ADR-001` até `ADR-008`).
- Modelagem de domínio com 18 entidades conceituais e relacionamentos em `docs/domain/entities.md`.
- Especificação de banco de dados espacial PostGIS em `docs/database/schema-overview.md`.
- Estratégia de integração desacoplada com Google em `docs/integrations/google-strategy.md`.
- Diretrizes de privacidade, minimização e conformidade LGPD em `docs/security/privacy-and-lgpd.md`.
- Padronização de endpoints REST e RFC 7807 em `docs/api/conventions.md`.
- Padrões de código, linters e branches em `docs/development/code-standards.md`.
- Infraestrutura local via `docker-compose.yml` (PostgreSQL 16 + PostGIS 3.4, Redis 7, MinIO S3 com Console, Cloudflare Tunnel).
- Script de inicialização SQL do PostGIS em `infrastructure/postgres/init/01-init-postgis.sql`.
- Matriz completa e exaustiva de variáveis em `.env.example`.
- `.gitignore` robusto cobrindo segredos, Java, Dart/Flutter, Node, Docker e sistemas operacionais.
- Scaffold inicial do backend Spring Boot (Java 21) com camadas de domínio, aplicação, apresentação, infraestrutura e integrações.
- Scaffold inicial do cliente mobile Flutter com Clean Architecture.
- Scaffold inicial do painel administrativo React + TypeScript + Vite.
- Scaffold do serviço futuro de visão computacional em Python (`services/vision`).
- Scripts PowerShell de inicialização e parada local em `scripts/`.
