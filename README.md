# REWIT - REDE SOCIAL GEOGRÁFICA DE AVALIAÇÕES

> Uma rede social geográfica onde a comunidade descobre, avalia e debate sobre o mundo físico (locais, produtos e serviços) com verificação presencial autêntica (**Check-in Verificado**), scanners inteligentes e feeds contextuais.

[![Licença](https://img.shields.io/badge/license-Proprietary-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%20LTS-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.x-green.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-blue.svg)](https://www.postgresql.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.6-brightgreen.svg)](https://postgis.net/)
[![Flutter](https://img.shields.io/badge/Flutter-3.24+-02569B.svg)](https://flutter.dev/)
[![React](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev/)

---

## 1. Visão do Produto

O **Rewit** transcende os catálogos estáticos de avaliação baseados em simples notas de 1 a 5 estrelas. Ele foi concebido como uma **rede social ativa** centrada no mundo real:

- **Avaliações Multi-Alvo**: Uma única publicação pode avaliar simultaneamente o local físico (*Place*), o atendimento recebido (*Service*), os itens consumidos (*Product*) e o relato qualitativo de experiência (*Experience*). Cada alvo alimenta sua própria média de forma independente e isolada.
- **Check-in com Presença Física Verificada**: O check-in é indissociável da avaliação. O selo de *"Avaliado no local"* só é emitido se o PostGIS validar que as coordenadas do usuário no momento da postagem estão dentro do raio de tolerância do estabelecimento.
- **Scanner Inteligente**: Leitura de códigos de barra (EAN/UPC), QR Codes e identificação óptica para avaliação instantânea de produtos.
- **Feed Dinâmico e Contextual**: Alimentado por proximidade espacial, interesses, eventos temporais em andamento e conexões da comunidade.
- **Privacidade por Design**: Minimização de dados, sem rastreamento contínuo em segundo plano, higienização automática de metadados EXIF/GPS de fotos e suporte a avaliações anônimas perante a comunidade.

---

## 2. Stack Tecnológica

| Componente | Tecnologia | Papel no Sistema |
| :--- | :--- | :--- |
| **Backend Central** | Java 25 LTS + Spring Boot 4.1.x | API RESTful central, regras de negócio e orquestração |
| **Banco de Dados Primário** | PostgreSQL 18 + PostGIS 3.6 | Fonte canônica da verdade e motor de consultas geoespaciais |
| **Migrações de Banco** | Flyway 11.x | Versionamento estrito e determinístico do schema relacional |
| **Cache & Filas Rápidas** | Redis 7 / 8 | Cache de feeds, rate limiting e invalidação de sessões |
| **Object Storage** | SeaweedFS 4.47 (Compatível com AWS S3) | Armazenamento de fotos de locais, produtos e avatares |
| **Cliente Mobile** | Flutter (Dart) | App multiplataforma (Android e iOS) para o usuário final |
| **Painel Administrativo** | React 18 + TypeScript + Vite | Interface web de moderação, gestão e auditoria |
| **Túnel de Desenvolvimento** | Cloudflare Tunnel (`cloudflared`) | Acesso seguro e direto do smartphone físico ao notebook local |
| **Serviço de IA (Futuro)** | Python (FastAPI / FastEmbed) | Extração de embeddings visuais e modelos de recomendação |

---

## 3. Estrutura do Repositório (Modular Monorepo)

```
/
├── apps/
│   ├── mobile/                # Aplicação cliente Flutter (Android & iOS) com Design System Next-Gen V1
│   └── admin/                 # Painel administrativo web (React / TypeScript / Vite)
│
├── backend/                   # API REST Central (Java 25 LTS / Spring Boot 4.1.x)
│   ├── src/main/java/com/rewit/
│   │   ├── presentation/      # REST Controllers, DTOs de entrada/saída, validações
│   │   ├── application/       # Serviços de aplicação, orquestração e portas (application/port)
│   │   ├── domain/            # Entidades puras (RateableTarget), enums e value objects
│   │   ├── infrastructure/    # JPA Repositories, PostGIS, Flyway e adaptadores externos
│   │   ├── config/            # Configurações Spring (Security, Cache, OpenAPI)
│   │   └── common/            # Respostas padronizadas, tratamento global de erros RFC 7807
│   ├── src/main/resources/    # application.yml, migrations Flyway (db/migration)
│   └── pom.xml
│
├── services/
│   └── vision/                # Microserviço futuro de visão computacional em Python
│
├── infrastructure/
│   ├── docker/                # Dockerfiles de build de cada componente
│   ├── cloudflare/            # Configurações do túnel seguro cloudflared
│   └── postgres/init/         # Scripts de inicialização PostGIS (01-init-postgis.sql)
│
├── docs/                      # Documentação técnica detalhada
│   ├── architecture/          # Diagramas e decisões arquiteturais
│   ├── domain/                # Modelagem detalhada das 18 entidades de domínio
│   ├── database/              # Esquema relacional e índices espaciais PostGIS
│   ├── api/                   # Padrões REST, versionamento e RFC 7807
│   ├── security/              # Diretrizes de privacidade e conformidade LGPD
│   ├── integrations/          # Detalhamento de integrações externas (Google, Notificações)
│   ├── development/           # Guia de setup local e ferramentas
│   ├── deployment/            # Visão de deploy e ambientes
│   └── decisions/             # Architecture Decision Records (ADRs 001 a 008)
│
├── scripts/                   # Utilitários de automação (PowerShell / Shell)
├── .env.example               # Matriz documentada de variáveis de ambiente
├── .gitignore                 # Filtro de segurança e integridade do repositório
├── PROJECT_RULES.md           # 31 princípios inegociáveis do projeto
├── PRODUCT_VISION.md          # Visão completa de negócio e produto
├── ARCHITECTURE.md            # Arquitetura detalhada do sistema
├── CONTRIBUTING.md            # Guia de contribuição e convenção de commits
├── SECURITY.md                # Política de segurança e reporte de vulnerabilidades
├── CHANGELOG.md               # Histórico de versões
└── docker-compose.yml         # Orquestração local dos containers essenciais
```

---

## 4. Como Iniciar o Ambiente Local

### Pré-Requisitos
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (com suporte a Compose v2)
- [Java JDK 21 LTS](https://adoptium.net/) e Maven 3.9+
- [Node.js 20 LTS](https://nodejs.org/)
- [Flutter SDK 3.24+](https://flutter.dev/docs/get-started/install)

### Passo 1: Configurar Variáveis de Ambiente
Na raiz do projeto, crie o seu `.env` a partir do template:
```powershell
Copy-Item .env.example .env
```

### Passo 2: Subir os Containers de Infraestrutura
Inicie o banco PostgreSQL com PostGIS, o Redis e o SeaweedFS:
```powershell
docker compose up -d
```
*(Ou execute o script de automação: `.\scripts\start-local.ps1`)*.

### Passo 3: Executar o Backend (Spring Boot)
```powershell
cd backend
./mvnw spring-boot:run
```
- **API Health Check**: `http://localhost:8080/api/v1/health`
- **Documentação Swagger UI**: `http://localhost:8080/swagger-ui.html`

### Passo 4: Executar o Painel Admin (React)
```powershell
cd apps/admin
npm install
npm run dev
```
- **Painel Administrativo**: `http://localhost:5173`

### Passo 5: Executar o Aplicativo Mobile (Flutter)
```powershell
cd apps/mobile
flutter pub get
flutter run
```
- **Análise Estática**: `flutter analyze`
- **Execução da Suíte Completa de Testes**: `flutter test`
- **Testes do Design System e Widgets**: `flutter test test/shared/widgets/design_system_widgets_test.dart`

---

## 5. Tabela de Portas e Acessos Locais

| Serviço | Porta Host | Endpoint / URL | Credenciais Padrão (Local) |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + PostGIS** | `5432` | `localhost:5432` | Definido no `.env` (Padrão: `rewit_user` / `rewit_db`) |
| **Redis** | `6379` | `localhost:6379` | Sem senha em ambiente de desenvolvimento local |
| **SeaweedFS S3 API** | `8333` | `http://localhost:8333` | Endpoint S3 compatível (Bucket padrão: `rewit-local`) |
| **SeaweedFS Master UI** | `9333` | `http://localhost:9333` | Painel de status e topologia do cluster SeaweedFS |
| **SeaweedFS Filer UI** | `8888` | `http://localhost:8888` | Navegação de diretórios e buckets do SeaweedFS |
| **Backend REST API** | `8080` | `http://localhost:8080` | N/A |
| **Admin Web (Vite)** | `5173` | `http://localhost:5173` | N/A |

---

## 6. Documentação Arquitetural e ADRs

- [Regras e Diretrizes do Projeto](PROJECT_RULES.md)
- [Visão do Produto e Negócio](PRODUCT_VISION.md)
- [Arquitetura de Software](ARCHITECTURE.md)
- [Modelagem de Entidades do Domínio](docs/domain/entities.md)
- [Esquema de Banco e PostGIS](docs/database/schema-overview.md)
- [Estratégia de Integração Google](docs/integrations/google-strategy.md)
- [Privacidade, Segurança e LGPD](docs/security/privacy-and-lgpd.md)
- [Convenções de API REST](docs/api/conventions.md)
- [ADR-001: Stack Tecnológica Principal](docs/decisions/ADR-001-stack.md)
- [ADR-002: PostgreSQL e PostGIS como Fonte da Verdade](docs/decisions/ADR-002-postgresql-postgis.md)
- [ADR-003: Isolamento do Google via Anti-Corruption Layer](docs/decisions/ADR-003-google-integration.md)
- [ADR-004: Cliente Mobile em Flutter](docs/decisions/ADR-004-mobile-flutter.md)
- [ADR-005: Modelo de Localização e Check-in Espacial](docs/decisions/ADR-005-location-model.md)
- [ADR-006: Publicação Multi-Alvo e Desacoplamento de Métricas](docs/decisions/ADR-006-multi-target-review.md)
- [ADR-007: Evolução em Fases do Scanner e Visão Computacional](docs/decisions/ADR-007-scanner-and-vision-evolution.md)
- [ADR-008: Separação de Reputação e Recompensas Cosméticas](docs/decisions/ADR-008-reputation-and-rewards-separation.md)
- [ADR-009: Modelo de Review Target e Raiz Polimórfica (RateableTarget)](docs/decisions/ADR-009-review-target-model.md)
- [ADR-010: Garbage Collection e Retenção de Objetos Órfãos no Storage](docs/decisions/ADR-010-storage-garbage-collection.md)
- [ADR-011: Retenção e Expiração de Sessões de Usuários](docs/decisions/ADR-011-session-retention-and-cleanup.md)
- [ADR-012: Observabilidade e Rastreabilidade Distribuída](docs/decisions/ADR-012-observability.md)
- [ADR-013: Rate Limiting Distribuído e Proteção Contra Abuso](docs/decisions/ADR-013-distributed-rate-limiting.md)
- [ADR-014: Invalidação de Dados Comerciais na Mudança de Titularidade](docs/decisions/ADR-014-commercial-data-owner-change-invalidation.md)
- [Relatório de Auditoria da Fundação](docs/development/foundation-audit.md)
- [Matriz de Versões Homologadas](docs/development/versions.md)

---

## 7. Status das Funcionalidades

### 7.1 Funcionalidades Implementadas

- **Autenticação e Sessão Segura**:
  - Fluxo completo de autenticação JWT (`/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`);
  - Rotação estrita de refresh tokens e controle de sessão;
  - Armazenamento seguro de credenciais (`sessionStorage` no painel web, `FlutterSecureStorage` no app mobile);
  - Tratamento padronizado de erros RFC 7807 sem exposição de detalhes internos.

- **Feed Social e Avaliações Multi-Alvo**:
  - Criação de avaliações avaliando simultaneamente múltiplos alvos (`Place`, `Product`, `Service`, `Experience`);
  - Validação de presença física no estabelecimento via PostGIS para emissão do selo de *Check-in Verificado*;
  - Listagens paginadas, filtros dinâmicos, anonimato de autoria e reações da comunidade.

- **Discussões e Notificações**:
  - Discussões encadeadas com suporte a respostas aninhadas e indicadores de autoria do proprietário;
  - Notificações de resposta com metadados estruturados (`rootDiscussionId`) e navegação contextual direta.

- **Scanner Inteligente Mobile (Flutter)**:
  - Leitura óptica em tempo real via câmera para códigos de barras (EAN/UPC) e QR Codes;
  - Validação estrita de identificadores contra enumeração fechada de tipos permitidos;
  - Fallback manual com normalização de entrada e tratamento refinado de permissões de câmera.

- **Painel Administrativo Web de Moderação (React + TypeScript + Vite)**:
  - Autenticação com verificação de papéis de acesso (`MODERATOR` / `ADMIN`) e bloqueio de acesso restrito (403);
  - Dashboard operacional com contadores em tempo real de denúncias pendentes de avaliações e discussões;
  - Filas de triagem administrativa com paginação e filtros estruturados por status e motivo;
  - Visualização de contexto completo (relatos, notas por alvo, contexto pai de discussões, denúncias e histórico de auditoria);
  - Execução de ações formais de moderação auditadas (`REMOVE_REVIEW`, `RESTORE_REVIEW`, `REMOVE_DISCUSSION`, `RESTORE_DISCUSSION`) com justificativa obrigatória (15 a 1.000 caracteres) e diálogo de confirmação;
  - Proteção integral de privacidade, sem expor IDs de autores ou denunciantes.

- **Contas Comerciais e Reivindicação de Locais (Fase 2 / C9)**:
  - APIs REST para abertura de contas comerciais (`/api/v1/business-accounts`), listagem de contas do usuário logado e submissão de solicitações de reivindicação de locais físicos (tabela `place_claim_requests`), vinculando o local via `places.claimed_by_business_id`;
  - Fila administrativa de reivindicações (`/api/v1/admin/place-claims`) para análise e decisão (`APPROVE` / `REJECT`) por moderadores e administradores;
  - Experiência mobile para administradores em "Minhas empresas", com acompanhamento de status e solicitação direta na tela de detalhes de locais;
  - Painel administrativo web com fila de triagem de solicitações, filtros por status e modal de decisão auditado com justificativa obrigatória;
  - Preservação estrita de privacidade: dados fiscais e evidências restritos ao proprietário e à moderação, sem exposição de identificadores internos de usuários.

- **Segurança de Moderação em Runtime e Mitigação de Stale Tokens (C10)**:
  - Verificação dinâmica em tempo de execução das permissões de moderação e do status da conta do moderador (`ModeratorRolePolicy`) diretamente contra o banco de dados;
  - Mitigação de tokens JWT ainda válidos após a revogação de privilégios: operações não autorizadas são bloqueadas com `403 FORBIDDEN` (RFC 7807) após a revalidação da política de papéis. Essa resposta não revoga nem encerra a sessão.

- **Respostas Oficiais Comerciais e Moderação Multiplataforma (Fase 2 / C11)**:
  - **Backend REST API**: Publicação, consulta e exclusão de respostas oficiais de estabelecimentos comerciais a avaliações (`/api/v1/reviews/{reviewId}/business-responses`) e discussões raiz (`/api/v1/discussions/{discussionId}/business-responses`), com unicidade de resposta ativa por par (empresa, alvo), rate limiting distribuído com Redis (`BUSINESS_RESPONSE_CREATION`), fila administrativa de respostas removidas (`/api/v1/admin/business-responses/removed`) e moderação/restauração formal auditada com justificativa obrigatória (15 a 1.000 caracteres);
  - **Cliente Mobile (Flutter)**: Renderização destacada de respostas oficiais com selo de verificação da empresa proprietária em avaliações e discussões, além de fluxo de resposta para proprietários verificados;
  - **Painel Administrativo Web (React + TypeScript + Vite)**: Nova área "Respostas Oficiais Removidas" com fila de triagem paginada, filtros avançados (`BUSINESS` / `MODERATION`), visualização contextual de datas (`moderatedAt`), status de restaurabilidade (`restorable`) e modal de restauração com tratamento de conflitos e recarga reativa.

- **Design System Next-Gen V1 Mobile (Flutter)**:
  - **Arquitetura e Fundações**:
    - Centralização de temas em `apps/mobile/lib/app/theme/app_theme.dart` (`AppTheme.lightTheme` e `AppTheme.darkTheme`), plenamente integrado ao Material 3 (`ColorScheme`, tipografia unificada, `AppBarTheme`, `CardTheme`, `BottomSheetThemeData`, `DialogTheme`, `InputDecorationTheme`, `ElevatedButtonThemeData`, `OutlinedButtonThemeData`, `TextButtonThemeData` e `NavigationBarThemeData`);
    - Escala geométrica padronizada de espaçamentos em `apps/mobile/lib/app/theme/rewit_tokens.dart` (`RewitSpacing`: `xxs = 2.0`, `xs = 4.0`, `sm = 8.0`, `md = 12.0`, `lg = 16.0`, `xl = 20.0`, `xxl = 24.0`, `xxxl = 32.0`, além dos preenchimentos semânticos estruturados `screenPadding`, `cardPadding` e `bannerPadding`);
    - Padronização de raios de curvatura e bordas pré-calculadas em `RewitRadius` (`xs = 4.0`, `sm = 6.0`, `md = 8.0`, `lg = 12.0`, `xl = 16.0`, `sheet = 20.0`, `pill = 999.0`, `borderXs`, `borderSm`, `borderMd`, `borderLg`, `borderXl`, `borderPill` e `sheetTop`);
    - Extensão semântica oficial em `apps/mobile/lib/app/theme/rewit_semantic_colors.dart` (`RewitSemanticColors extends ThemeExtension<RewitSemanticColors>`), estruturada em blocos funcionais imutáveis `RewitSemanticTone` (`container`, `onContainer`, `border`, `accent`) para estados contextuais (`success`, `warning`, `danger`, `info`), selo de presença verificada (`verified`), avaliações e estrelas (`ratingBadge`, `starFilled`, `starEmpty`), categorias multi-alvo (`placeTarget`, `productTarget`, `serviceTarget`) e superfícies focais (`elevatedFocalSurface`, `anonymousAvatarBackground`, `anonymousAvatarForeground`, `mediaScrim`, `onMediaScrim`). Acesso via `RewitSemanticColors.of(context)`;
    - Gerenciamento reativo de aparência em `apps/mobile/lib/app/theme/theme_controller.dart` (`ThemeController` baseado em `ChangeNotifier`, com contrato `ThemePreferenceStorage` e escopo `ThemeControllerScope` via `InheritedNotifier`).
  - **Temas Disponíveis e Cores Canônicas**:
    - **Sistema (`ThemeMode.system`)**: Padrão inicial de fábrica. Acompanha instantaneamente o brilho configurado no sistema operacional do dispositivo;
    - **Claro (`ThemeMode.light`)**: Lavanda suave e roxo focal:
      - Fundo da aplicação (`scaffoldBackgroundColor`): `#F6F4FB`;
      - Superfície padrão de cards (`surfaceContainerLow`): `#F1EEFA`;
      - Superfície elevada focal moderada (`elevatedFocalSurface` / `surfaceContainerLowest`): `#FFFFFF` (utilizado com moderação exclusivamente em modais, bottom sheets, inputs e miras do scanner);
      - Cor primária de ação e foco: `#6D28D9`;
      - Cor secundária: `#7C3AED`;
      - Texto principal (`onSurface`): `#181520` (grafite profundo);
      - Texto secundário (`onSurfaceVariant`): `#534C63` (grafite médio);
      - Bordas e divisores: `#D5CDE8` / `#79718A`;
    - **Escuro (`ThemeMode.dark`)**: Predominância de preto e grafite com toques de lavanda:
      - Fundo predominante da tela (`scaffoldBackgroundColor`): `#09090D`;
      - Superfície principal de cards (`surface` / `cardTheme`): `#17151D`;
      - Superfície elevada focal (`elevatedFocalSurface` / `surfaceContainerHigh`): `#211B2B`;
      - Ações primárias (botões e controles ativos): `#8055D9`;
      - Destaque textual primário de alta legibilidade: `#9D78F2`;
      - Detalhes secundários em lavanda (`secondary`): `#B69AFF`;
      - Texto principal suave (`onSurface`): `#F2EFFA`;
      - Texto secundário (`onSurfaceVariant`): `#B5AFC4`;
      - Bordas e divisores sutis: `#2E283D` / `#8A829C`;
    - **Diretrizes Inegociáveis do Modo Escuro**: Proibição terminante de cards brancos no tema escuro; nenhuma grande superfície pintada em roxo saturado em bloco (o roxo é reservado estritamente para ações primárias, botões, indicadores de foco e estados ativos, e o lavanda para detalhes e ícones secundários); legibilidade priorizada e distinção semântica estrita preservada para estados de sucesso, alerta, erro e avaliação.
  - **Persistência Local e Ciclo de Vida Desacoplado**:
    - Persistência exclusiva no dispositivo via `FlutterSecureStorage` sob a chave `rewit_app_theme_mode` (`SecureThemePreferenceStorage`);
    - Desacoplamento total da sessão de usuário: chamadas a `TokenStorage.clearTokens()` decorrentes de logout ou expiração de credenciais não resetam a preferência visual do dispositivo;
    - Fallback resiliente em memória (`InMemoryThemePreferenceStorage`) ativado automaticamente em caso de falha de I/O na chave de armazenamento seguro;
    - Carregamento assíncrono antes do bootstrap do app (`await themeController.load()` em `main.dart`);
    - Painel acessível de configuração na tela de configurações (`AccountSettingsScreen`), com semântica de rádio, ícones dedicados e feedback visual imediato.
  - **Guia Prático de Desenvolvimento (Consumo de Tokens e Componentes)**:
    - O desenvolvimento de novas telas e widgets deve consumir os tokens exclusivamente via contexto do Flutter, prevenindo cores literais hardcoded:
      ```dart
      import 'package:flutter/material.dart';
      import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
      import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';

      class ExemploCardReview extends StatelessWidget {
        const ExemploCardReview({super.key});

        @override
        Widget build(BuildContext context) {
          final theme = Theme.of(context);
          final scheme = theme.colorScheme;
          final semantics = RewitSemanticColors.of(context);

          return Card(
            margin: RewitSpacing.screenPadding,
            shape: const RoundedRectangleBorder(borderRadius: RewitRadius.borderLg),
            child: Padding(
              padding: RewitSpacing.cardPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      // Selo semântico de verificação presencial
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: RewitSpacing.sm,
                          vertical: RewitSpacing.xs,
                        ),
                        decoration: BoxDecoration(
                          color: semantics.verified.container,
                          borderRadius: RewitRadius.borderPill,
                          border: Border.all(color: semantics.verified.border),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.verified, size: 14, color: semantics.verified.accent),
                            const SizedBox(width: RewitSpacing.xs),
                            Text(
                              'Presença Verificada',
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.bold,
                                color: semantics.verified.onContainer,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: RewitSpacing.md),
                  Text(
                    'Excelente atendimento no local',
                    style: theme.textTheme.titleMedium?.copyWith(
                      color: scheme.onSurface,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: RewitSpacing.xs),
                  Text(
                    'Relato da experiência em conformidade com o Design System.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          );
        }
      }
      ```
    - **Boas Práticas de Implementação**:
      - Nunca utilizar `Colors.white`, `Colors.black` ou hexadecimais literais soltos no corpo de widgets. Para superfícies dinâmicas elevadas (modais, cards focais ou inputs), utilize `semantics.elevatedFocalSurface` ou `scheme.surfaceContainerHigh`;
      - Para espaçamentos e alinhamentos, utilize os múltiplos canônicos de `RewitSpacing` (`RewitSpacing.xs`, `RewitSpacing.sm`, `RewitSpacing.md`, `RewitSpacing.lg`, etc.);
      - Para cantos arredondados, utilize as constantes pré-calculadas de `RewitRadius` (`RewitRadius.borderMd`, `RewitRadius.borderLg`, `RewitRadius.borderPill`, `RewitRadius.sheetTop`);
      - Assegure contraste mínimo WCAG AA em ambos os temas testando a legibilidade de textos e ícones contra seus respectivos containers.
  - **Acessibilidade e Garantia de Qualidade**:
    - Verificação estrita de contraste WCAG AA: relação `>= 4.5:1` para tipografia de leitura regular e `>= 3:1` para divisores estruturais, bordas e ícones gráficos;
    - Bateria de testes automatizados: **1.172 testes aprovados** (`All tests passed!`), 0 falhas, 0 erros e 0 alertas no `flutter analyze`;
    - **Status de Homologação**: A suíte de testes automatizados e regressões estruturais de widget foi 100% validada no ambiente de CI/CD. A inspeção visual interativa em hardware físico real (dispositivos Android e iOS) permanece como pendência formal para validação prática pela equipe/mantenedor.

### 7.2 Funcionalidades Planejadas (Fases Futuras)

- Gestão avançada de estabelecimentos e catálogo local por proprietários verificados (Fase 2);
- Eventos temporais de estabelecimentos físicos (Fase 2);
- Provedor real de notificações push para dispositivos móveis (Fase 2);
- Catálogo global e curadoria de marcas e produtos (Fase 2);
- Perfis corporativos, métricas comerciais e anúncios patrocinados (Fase 3);
- Microserviço autônomo de visão computacional em Python com embeddings visuais (`services/vision`).
