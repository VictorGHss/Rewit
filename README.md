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
| **Object Storage** | MinIO (Compatível com AWS S3) | Armazenamento de fotos de locais, produtos e avatares |
| **Cliente Mobile** | Flutter (Dart) | App multiplataforma (Android e iOS) para o usuário final |
| **Painel Administrativo** | React 18 + TypeScript + Vite | Interface web de moderação, gestão e auditoria |
| **Túnel de Desenvolvimento** | Cloudflare Tunnel (`cloudflared`) | Acesso seguro e direto do smartphone físico ao notebook local |
| **Serviço de IA (Futuro)** | Python (FastAPI / FastEmbed) | Extração de embeddings visuais e modelos de recomendação |

---

## 3. Estrutura do Repositório (Modular Monorepo)

```
/
├── apps/
│   ├── mobile/                # Aplicação cliente Flutter (Android & iOS)
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
Inicie o banco PostgreSQL com PostGIS, o Redis e o MinIO:
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

---

## 5. Tabela de Portas e Acessos Locais

| Serviço | Porta Host | Endpoint / URL | Credenciais Padrão (Local) |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + PostGIS** | `5432` | `localhost:5432` | User: `rewit_user` / Senha: `rewit_local_password` / DB: `rewit_db` |
| **Redis** | `6379` | `localhost:6379` | Sem senha em ambiente de desenvolvimento local |
| **MinIO API** | `9000` | `http://localhost:9000` | User: `rewit_minio_admin` / Senha: `rewit_minio_secret` |
| **MinIO Web Console** | `9001` | `http://localhost:9001` | User: `rewit_minio_admin` / Senha: `rewit_minio_secret` |
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
- [Relatório de Auditoria da Fundação](docs/development/foundation-audit.md)
- [Matriz de Versões Homologadas](docs/development/versions.md)

---

## 7. Itens Deixados Deliberadamente para os Próximos Prompts

Em estrito cumprimento às regras da etapa de fundação:
- Implementação dos fluxos de autenticação (OAuth2 Google / JWT completo);
- Algoritmo de cálculo e agregação contínua de médias de notas;
- Mecanismo do scanner de código de barras e OCR no Flutter;
- Feed dinâmico com ordenação por relevância e proximidade;
- Regras de avanço de reputação, rankings de check-in e badges cosméticos;
- CRUDs completos e interfaces de usuário detalhadas;
- Modelos neurais do serviço de visão computacional em Python.
