# REWIT — CONTRATO OPERACIONAL DOS AGENTES (`AGENTS.md`)

Este arquivo é a fonte normativa central das sessões de agentes no repositório **Rewit**. Aplica-se a toda tarefa de implementação, documentação ou revisão.

## 1. Hierarquia de Autoridade

Em ordem decrescente de precedência:

1. Instruções e autorizações explícitas do mantenedor na solicitação atual.
2. Regras obrigatórias deste `AGENTS.md`.
3. Os 31 princípios arquiteturais de [`PROJECT_RULES.md`](PROJECT_RULES.md).
4. Documentação canônica do módulo ou funcionalidade (`ARCHITECTURE.md`, `SECURITY.md`, `docs/decisions/`, `docs/api/`, `docs/architecture/`, `docs/database/`, `docs/deployment/`).
5. Procedimentos operacionais específicos ([`docs/development/agent-workflow.md`](docs/development/agent-workflow.md), [`docs/development/local-setup.md`](docs/development/local-setup.md), [`docs/development/code-standards.md`](docs/development/code-standards.md)).

Quando houver contradição entre documentos, **interrompa e reporte o conflito** ao mantenedor; nunca escolha silenciosamente a regra mais conveniente.

## 2. Idioma Obrigatório

- **Português brasileiro (PT-BR)** obrigatório em mensagens de commit, documentação, relatórios e comentários explicativos, salvo pedido explícito em contrário.
- Preserve termos técnicos originais, identificadores, comandos, APIs e tipos padronizados de **Conventional Commits** (ex.: `feat(deploy): adicionar provisionamento do PostgreSQL gerenciado`).

## 3. Papéis dos Agentes

- **Neon High**: implementação de código, testes e documentação.
- **Neon Medium**: segunda revisão adversarial em modo estritamente **READ-ONLY**.
- As duas sessões pertencem ao mesmo modelo/família e **não** constituem auditoria independente de fornecedor.
- Nunca opere duas sessões modificadoras simultaneamente na mesma worktree; implementação e revisão na mesma worktree ocorrem em momentos distintos.

## 4. Git, Branches e Worktrees

- **`main`** é a única branch canônica de integração (não existe branch `develop`).
- Branches temporárias de agentes seguem `agent/neon/<slug>`.
- **Uma tarefa por worktree**, centralizadas em `$HOME/source/rewit-worktrees/<slug>` (caminho ASCII, sem espaços ou acentos).
- **Não** criar clones independentes para tarefas comuns.
- Trabalhe **somente** na worktree atribuída pelo mantenedor. Não crie worktrees autonomamente sem autorização explícita (salvo quando autorizado na tarefa).
- **Inspeção obrigatória antes de editar**: confirme diretório real, branch, SHA do `HEAD`, `git status --short --untracked-files=all` e integridade de arquivos locais protegidos. Nunca execute `fetch` ou `pull` sem autorização.
- **Autorizações independentes**: `commit`, `push` e integração na `main` exigem autorizações separadas do mantenedor. Nenhum agente comita ou integra diretamente na `main` sem autorização expressa.
- **Preservação**: nunca altere, apague ou inclua no staging modificações locais preexistentes nem worktrees/branches de outras tarefas.
- **Proibição de comandos destrutivos** sem autorização explícita: `git reset`, `git clean`, `git rebase`, `git checkout`/`git restore` destrutivos, `git stash`, force push, `git worktree remove --force` e `git branch -D`.
- Detalhes completos de abertura, execução e encerramento seguro de worktrees estão em [`docs/development/agent-workflow.md`](docs/development/agent-workflow.md).

## 5. Escopo, Diagnósticos e Dependências

- Implemente estritamente o escopo autorizado; nunca faça refactors oportunistas.
- Problemas encontrados fora do escopo devem ser registrados no relatório sem correção silenciosa.
- Não use `@SuppressWarnings` para mascarar diagnósticos; corrija a causa real.
- Não adicione dependências externas sem justificar necessidade, compatibilidade, impacto e alternativa nativa considerada.

## 6. Segurança, Privacidade e Testes

- **Segredos**: nunca inspecione, imprima, registre em log ou versione `.env`, tokens, senhas, chaves privadas ou credenciais.
- **Privacidade**: nunca registre PII, e-mails, documentos, coordenadas privadas ou identificadores sensíveis em logs nem use valores de alta cardinalidade (UUID, usuário, IP, sessão, coordenada) como tags de métricas.
- **Infraestrutura descartável**: testes de integração executam **exclusivamente** em infraestrutura descartável (`TEST_*`, conforme [`docs/development/local-setup.md`](docs/development/local-setup.md)). É **proibido** executar migrations ou testes contra bancos persistentes.
- **Qualidade de validação**:
  - Prefira testes reais sobre mocks enganosos quando o comportamento depender de Spring Security, PostgreSQL/PostGIS, Flyway, RFC 7807, transações ou canais nativos Android/iOS.
  - Em endpoints novos/alterados, cubra sucesso, autenticação, autorização, entrada inválida, `404`/`409`, método incorreto e falhas de provedor externo quando aplicável.
  - Nunca mascare falhas, remova testes para forçar aprovação nem declare testes aprovados sem executá-los.
  - Diferencie explicitamente validações automatizadas de validações manuais.
  - Execute `git diff --check` antes de encerrar.

## 7. Guardrails Arquiteturais (Resumo)

Os 31 princípios completos residem em [`PROJECT_RULES.md`](PROJECT_RULES.md). Mantenha sempre ativos:

- **Persistência e Espacial**: PostgreSQL/PostGIS como única fonte canônica da verdade; nunca usar Google/Firebase como banco primário.
- **Schema**: alterações exclusivamente via migrations Flyway forward-only (`classpath:db/migration`); migrations históricas são imutáveis; Hibernate permanece com `ddl-auto=validate`.
- **Clean Architecture**: `domain` puro (zero SDKs/frameworks); `application` com casos de uso e portas; `infrastructure`/`integrations` com adaptadores (ACL); `presentation` sem regra de negócio.
- **Privacidade de localização e mídia**: sem rastreamento contínuo em segundo plano; check-in exige presença validada no backend e é indissociável da avaliação; sanitização EXIF/GPS obrigatória em uploads.
- **API e UI**: rotas `/api/v1/`, erros em RFC 7807 sem vazamento de detalhes internos, proibição de endpoints temporários, telas falsas ou mocks apresentados como funcionalidade real.

## 8. Regra Obrigatória de Documentação

Toda tarefa que altere comportamento, contrato, configuração ou governança deve manter a documentação canônica sincronizada (checklist completo em [`docs/development/agent-workflow.md`](docs/development/agent-workflow.md)):

1. Identificar a documentação canônica relacionada à tarefa antes de implementar.
2. Consultar índices e referências cruzadas relevantes.
3. Mapear todos os documentos potencialmente afetados.
4. Atualizar os documentos efetivamente pertinentes junto da implementação.
5. Não criar documentação paralela quando existir fonte canônica.
6. Não substituir a atualização de documentos específicos por um `README.md` genérico.
7. Conferir links relativos e referências após as alterações.
8. Registrar no relatório final os documentos atualizados e os documentos analisados sem alteração, com justificativa.

## 9. Relatório Final Obrigatório

Toda entrega deve apresentar, em PT-BR e de forma objetiva:

- **Implementação realizada**: escopo entregue, arquivos criados/alterados/removidos e invariantes preservadas.
- **Testes e validações**: comandos executados, contagens reais (executados, falhas, erros, ignorados), análise estática e `git diff --check`.
- **Documentação**: documentos atualizados (com motivo) e documentos examinados sem alteração (com justificativa).
- **Integridade Git**: branch, worktree, SHA inicial/final, status da working tree, confirmação de staging/commit/push conforme autorização e integridade de alterações locais protegidas.
- **Limitações e riscos restantes**: itens não executados, pendências fora do escopo e riscos residuais.
