# PADRÕES DE CÓDIGO E DESENVOLVIMENTO (docs/development/code-standards.md)

Este guia estabelece os padrões técnicos, convenções de nomenclatura e fluxo de versionamento do repositório **Rewit**.

---

## 1. Padrões por Linguagem e Ecossistema

### Java / Spring Boot
- **Versão**: Java 25 LTS (conforme `backend/pom.xml` e `infrastructure/docker/backend.Dockerfile`)
- **Padrão de Formatação**: Google Java Format (indentação de 4 espaços para blocos, 8 espaços para quebras contínuas).
- **Nomenclatura**:
  - Classes e Interfaces: `PascalCase` (ex: `ReviewService`, `PostGisConfig`).
  - Métodos e Variáveis: `camelCase` (ex: `calculateAverageRating`, `validationRadiusMeters`).
  - Constantes: `UPPER_SNAKE_CASE` (ex: `MAX_RATING_VALUE`).
  - Pacotes: `lowercase` sem separadores (ex: `com.rewit.domain.model`).
- **Comentários**: Obrigatórios em cálculos não óbvios, regras de tolerância geográfica ou contornos de limitações técnicas. Proibidos comentários redundantes em getters/setters/construtores.

### Dart / Flutter
- **Linter**: `package:flutter_lints/flutter.yaml`
- **Nomenclatura**:
  - Arquivos: `snake_case.dart` (ex: `home_screen.dart`, `review_repository.dart`).
  - Classes e Tipos: `PascalCase` (ex: `ReviewEntity`, `AppTheme`).
  - Funções, Métodos e Variáveis: `camelCase`.
  - Constantes: `lowerCamelCase` ou `SCREAMING_SNAKE_CASE`.

### TypeScript / React
- **Linter e Formatador**: ESLint 9+ e Prettier
- **Nomenclatura**:
  - Componentes React: `PascalCase.tsx` (ex: `ModerationQueue.tsx`).
  - Utilitários e hooks: `camelCase.ts` (ex: `useAuth.ts`, `apiClient.ts`).
  - Tipos e Interfaces: `PascalCase` (ex: `ReviewItem`, `UserSummary`).

---

## 2. Convenção de Commits (Conventional Commits)

Todas as mensagens de commit devem seguir rigorosamente o padrão, com **descrição obrigatória em português brasileiro (PT-BR)** (preservando os tipos padronizados e termos técnicos originais):
```text
<tipo>(<escopo>): <descrição concisa em PT-BR>
```
Exemplo: `feat(deploy): adicionar provisionamento do PostgreSQL gerenciado`.

### Tipos Permitidos:
- `feat`: Nova funcionalidade para o usuário ou cliente;
- `fix`: Correção de bug em código de produção;
- `docs`: Alteração exclusiva em documentação;
- `style`: Ajustes de formatação de código (espaçamento, ponto e vírgula) sem alteração de lógica;
- `refactor`: Refatoração interna que não altera o comportamento externo;
- `perf`: Melhoria de desempenho;
- `test`: Criação ou ajuste de testes automatizados;
- `build`: Alterações no sistema de build, empacotamento, Dockerfiles ou dependências externas;
- `ci`: Alterações nos arquivos e scripts de configuração de integração contínua (GitHub Actions);
- `chore`: Manutenção geral, scripts auxiliares ou tarefas operacionais que não alteram código de produção.

---

## 3. Fluxo de Branches

A branch **`main`** é a única base canônica de integração (ver [`AGENTS.md`](../../AGENTS.md), [`CONTRIBUTING.md`](../../CONTRIBUTING.md) e [`docs/development/agent-workflow.md`](agent-workflow.md)):

- `main`: Única branch canônica de integração (código pronto para produção e releases estáveis).
- `agent/neon/<slug>`: Branches temporárias criadas a partir de `main` para tarefas executadas por agentes em worktrees isoladas (uma tarefa por worktree).
- Branches curtas derivadas diretamente de `main` para contribuições humanas.
- `hotfix/<slug>`: Correções urgentes derivadas diretamente de `main` e integradas de volta em `main`.
