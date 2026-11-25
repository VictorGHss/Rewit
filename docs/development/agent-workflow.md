# RUNBOOK OPERACIONAL DOS AGENTES (`docs/development/agent-workflow.md`)

Este runbook detalha o ciclo de vida operacional de tarefas executadas por agentes no **Rewit**, complementando o contrato obrigatório em [`AGENTS.md`](../../AGENTS.md) e os 31 princípios arquiteturais em [`PROJECT_RULES.md`](../../PROJECT_RULES.md).

> **Nota de estado operacional:** o procedimento abaixo é manual e governado por comandos Git explícitos e autorizações do mantenedor. Não existem scripts automatizados de ciclo de vida de worktrees nesta fase; não presuma nem invoque automação inexistente.

---

## 1. Início de Tarefa (Abertura e Baseline)

### 1.1 Criação da Worktree
- **Autoridade de criação**: a worktree é criada pelo mantenedor ou por procedimento explicitamente autorizado na tarefa. O agente **não** decide sozinho criar diretórios, worktrees adicionais ou clones independentes.
- **Diretório centralizado (caminho ASCII, sem espaços ou acentos)**:
  ```text
  $HOME/source/rewit-worktrees/<slug>
  ```
  - Em comandos de shell, utilize quoting adequado: `"$HOME/source/rewit-worktrees/<slug>"` (por exemplo, `git worktree add -b "agent/neon/<slug>" "$HOME/source/rewit-worktrees/<slug>" "<SHA>"`).
  - *Exemplo no ambiente de desenvolvimento atual:* `/home/vito/source/rewit-worktrees/` (apenas ilustração local; não utilize caminho específico de usuário como regra fixa).
- **Branch padrão**:
  ```text
  agent/neon/<slug>
  ```
- **Base obrigatória**: SHA local explícito e autorizado (em geral o `HEAD` vigente de `main`).
- **Sem sincronização remota automática**: nunca execute `git fetch` ou `git pull` sem autorização expressa.
- **Uma tarefa por worktree**: não misture escopos distintos na mesma worktree nem crie clones independentes do repositório.

### 1.2 Preflight Obrigatório Antes de Qualquer Edição
Antes de alterar qualquer arquivo na worktree atribuída, confirme com comandos reais:

1. Diretório real (`pwd` / `realpath`) corresponde à worktree atribuída.
2. Branch atual (`git rev-parse --abbrev-ref HEAD`) corresponde à branch autorizada.
3. Commit base (`git rev-parse HEAD`) corresponde exatamente ao SHA autorizado.
4. Working tree limpa (`git status --short --untracked-files=all`) e `0` arquivos em staging (`git diff --cached --name-only`).
5. Integridade da worktree principal (`main`) e de eventuais alterações locais protegidas (incluindo SHA-256 do diff combinado quando informado pelo mantenedor), sem modificar a worktree principal.

Se qualquer pré-condição divergir, **interrompa imediatamente** e reporte ao mantenedor antes de editar qualquer arquivo.

---

## 2. Execução da Tarefa

### 2.1 Divisão de Sessões (Neon High vs. Neon Medium)
- **Neon High**: executa a implementação (código, testes automatizados e atualização documental).
- **Neon Medium**: executa a segunda revisão adversarial em modo estritamente **READ-ONLY** sobre a mesma worktree.
- **Isolamento temporal**: nunca execute duas sessões modificadoras simultaneamente na mesma worktree. Implementação e revisão ocorrem em momentos distintos.
- **Transparência sobre revisão**: Neon High e Neon Medium pertencem ao mesmo modelo/família; a segunda revisão aumenta o rigor técnico, mas **não** substitui auditoria independente de fornecedor.

### 2.2 Protocolo de Trabalho
1. Ler [`AGENTS.md`](../../AGENTS.md), [`PROJECT_RULES.md`](../../PROJECT_RULES.md) e a documentação canônica pertinente ao módulo.
2. Inspecionar o código real no `HEAD` (nunca presumir estado a partir de roadmaps ou resumos antigos).
3. Implementar estritamente o escopo autorizado, respeitando idioma PT-BR em documentação, comentários explicativos e mensagens de commit.
4. Executar validações apropriadas ao escopo:
   - Testes de integração backend **exclusivamente** em infraestrutura descartável (`TEST_*`, conforme [`docs/development/local-setup.md`](local-setup.md)); nunca rodar migrations ou testes contra bancos persistentes.
   - Preferir testes reais (Spring Security, PostgreSQL/PostGIS, Flyway, HTTP/RFC 7807, transações, canais nativos Android/iOS) em vez de mocks enganosos.
   - Em tarefas exclusivamente documentais, executar verificações documentais, links e `git diff --check` sem rodar suítes de aplicação ou migrations desnecessariamente.
5. Manter a documentação canônica sincronizada junto da implementação (Seção 4 abaixo).
6. Nunca inspecionar ou expor `.env`, tokens, chaves ou credenciais.

---

## 3. Encerramento, Integração e Salvaguardas

### 3.1 Etapas e Autorizações Distintas
Cada transição abaixo exige autorização explícita e separada do mantenedor:

1. **Entrega em working tree** (sem commit) → aguardando revisão READ-ONLY do Neon Medium.
2. **Commit local** na branch `agent/neon/<slug>` (mensagem Conventional Commits em PT-BR).
3. **Integração local na `main`** (tipicamente `git merge --ff-only <SHA>`, preservando integralmente alterações locais protegidas na worktree principal).
4. **Push para o remoto** (autorização independente, nunca implícita na integração local).
5. **Remoção de worktree** (operação independente da remoção de branch).
6. **Remoção de branch local** (operação independente da remoção de worktree).

### 3.2 Salvaguardas Obrigatórias de Encerramento de Worktree
O encerramento/remoção de uma worktree **deve ser recusado** sempre que qualquer uma das condições abaixo existir:

- Alterações locais não commitadas (`git diff` ou staging não vazio).
- Arquivos não rastreados (`untracked`).
- Commits exclusivos da branch ainda não integrados em `main`.
- Arquivos ignorados sensíveis ou locais que não foram conferidos/descartados conscientemente pelo mantenedor.

**Proibições absolutas sem autorização explícita:**
- Nunca usar `git worktree remove --force`.
- Nunca usar `git branch -D`.
- Não apagar automaticamente branches locais ao encerrar uma worktree.
- Toda remoção de worktree e toda remoção de branch exigem **confirmação humana explícita** após as verificações acima.

---

## 4. Checklist Documental Obrigatório (8 Passos)

Toda tarefa que altere código, contrato, configuração ou governança deve executar os oito passos abaixo:

1. **Identificar a documentação canônica** relacionada à tarefa antes de editar código (`ARCHITECTURE.md`, `PROJECT_RULES.md`, `SECURITY.md`, `docs/decisions/`, `docs/api/`, `docs/architecture/`, `docs/database/`, `docs/deployment/`, `docs/development/`).
2. **Consultar índices e referências cruzadas** relevantes (`README.md`, índices de `docs/`, ADRs relacionados) para não deixar referências obsoletas ou ocultas.
3. **Mapear todos os documentos potencialmente afetados**, distinguindo documentos normativos ativos (que devem ser corrigidos) de registros históricos como ADRs antigos ou `CHANGELOG.md` (que devem ser preservados).
4. **Atualizar todos os documentos efetivamente pertinentes junto da implementação**, refletindo o estado factual do código (nunca documentar planejamento como se estivesse implementado).
5. **Evitar redundância e documentação paralela**: acomodar atualizações nos documentos canônicos existentes em vez de criar arquivos duplicados; nunca tratar `README.md` genérico como substituto de documentos específicos.
6. **Conferir links relativos, caminhos de arquivos e `git diff --check`** após as edições, garantindo ausência de referências quebradas ou conflitos entre documentos.
7. **Registrar no relatório final todos os documentos atualizados**, indicando explicitamente o motivo de cada alteração.
8. **Justificar no relatório final os documentos relevantes analisados que não precisaram de alteração** (inclusive quando uma tarefa de código não exigir mudança documental).

### 4.1 Contingência Documental

Quando o fluxo padrão encontrar lacunas ou conflitos na base documental, aplique o procedimento correspondente:

#### Cenário A — Não existe documento canônico
1. Consultar a estrutura de `docs/` e os índices existentes (`README.md` e índices por área).
2. Confirmar que não existe documento canônico adequado que comporte o conteúdo.
3. Identificar a localização correta conforme o assunto (ex.: `docs/architecture/`, `docs/api/`, `docs/development/`, `docs/deployment/`, `docs/decisions/`).
4. Criar o novo documento somente quando a necessidade estiver concretamente justificada.
5. Atualizar os índices e referências cruzadas pertinentes na mesma tarefa.
6. Evitar criar outro documento que trate do mesmo assunto.

#### Cenário B — Índice documental incompleto
1. Consultar a árvore de documentação e os índices relacionados.
2. Verificar se os documentos existentes estão referenciados corretamente e com caminhos válidos.
3. Corrigir o índice pertinente junto da implementação.
4. Não criar índices paralelos ou redundantes.

#### Cenário C — Contradição documental
Conforme a regra de conflito estabelecida na hierarquia de autoridade de [`AGENTS.md`](../../AGENTS.md) (§1):
1. Identificar exatamente as fontes conflitantes (arquivo, seção e trecho).
2. Registrar a natureza da contradição.
3. Interromper qualquer decisão que dependa da regra conflitante.
4. Apresentar as evidências objetivas ao mantenedor para resolução explícita.
5. Não escolher silenciosamente uma fonte conveniente nem alterar registros históricos (como ADRs antigos ou `CHANGELOG.md`) sem justificativa e autorização.

---

## 5. Estrutura do Relatório Final

Ao concluir a tarefa, entregue em PT-BR:

1. **Integridade inicial e final do Git**: branch, caminho real da worktree, SHA inicial/final, `git status`, quantidade de arquivos staged/modificados/untracked, confirmação explícita de ausência de commit/push/merge não autorizados e preservação de arquivos locais protegidos.
2. **Implementação realizada**: resumo objetivo das mudanças, arquivos alterados/criados/removidos e invariantes preservadas.
3. **Documentação**: documentos atualizados (com justificativa), documentos avaliados sem alteração (com justificativa) e confirmação de links verificados.
4. **Testes e validações**: comandos executados, resultados numéricos reais, análise estática e `git diff --check`.
5. **Limitações e riscos residuais**: o que não pôde ser validado localmente e eventuais pendências fora do escopo.
