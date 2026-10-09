# HISTÓRICO DE ALTERAÇÕES (CHANGELOG.md)

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/), e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

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
