# HISTÓRICO DE ALTERAÇÕES (CHANGELOG.md)

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/), e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

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
