# HISTÓRICO DE ALTERAÇÕES (CHANGELOG.md)

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/), e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

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
