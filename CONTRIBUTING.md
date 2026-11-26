# GUIA DE CONTRIBUIÇÃO (`CONTRIBUTING.md`)

Agradecemos o interesse em contribuir com o desenvolvimento do **Rewit**. Para manter alto padrão de qualidade, segurança e conformidade arquitetural, todas as contribuições — humanas ou assistidas por agentes — devem seguir as diretrizes abaixo.

---

## 1. Documentos de Referência

1. **[`PROJECT_RULES.md`](PROJECT_RULES.md)**: leia e siga integralmente os 31 princípios arquiteturais do projeto.
2. **[`AGENTS.md`](AGENTS.md)** e **[`docs/development/agent-workflow.md`](docs/development/agent-workflow.md)**: obrigatórios para sessões de agentes (e referência recomendada de isolamento por worktree).
3. **[`docs/development/code-standards.md`](docs/development/code-standards.md)**: convenções de código, formatação, commits e branches.
4. **[`docs/development/local-setup.md`](docs/development/local-setup.md)**: preparação do ambiente local e infraestrutura descartável de testes.

---

## 2. Regras de Ouro

1. **Sem implementações especulativas**: entregue apenas o escopo planejado, sem complexidade acidental ou código paralelo.
2. **Nunca versione segredos ou arquivos `.env`**: mantenha credenciais apenas localmente e documente novas variáveis exclusivamente em `.env.example`.
3. **Infraestrutura descartável para testes**: nunca execute migrations ou testes de integração contra bancos persistentes.
4. **Documentação sincronizada**: atualize os documentos canônicos afetados junto da implementação.

---

## 3. Fluxo de Contribuição (Base `main`, Worktrees e Gate Local)

A branch **`main`** é a única base canônica de integração do repositório.

1. **Crie uma worktree isolada a partir de `main`** (uma tarefa por worktree, evitando clones independentes e preservando a worktree principal):
   - Em ambientes Linux (Bash 4.4+ / Git 2.36+), utilize os utilitários oficiais descritos em [`docs/development/agent-workflow.md`](docs/development/agent-workflow.md):
     - [`scripts/worktree-task.sh`](scripts/worktree-task.sh): cria (`create`), inspeciona em modo READ-ONLY (`status`) e encerra com salvaguardas (`close`) worktrees de tarefas em `$HOME/source/rewit-worktrees/<slug>` na branch `agent/neon/<slug>`.
     - [`scripts/task-gate.sh`](scripts/task-gate.sh): executa verificações locais determinísticas e READ-ONLY de governança (`preflight`, `check`, `check-commit-msg` e `pre-merge`).
   - Exemplo rápido para tarefas de agentes (executado na worktree principal na branch `main`):
     ```bash
     ./scripts/worktree-task.sh create --slug <slug> --base <sha> [--allow-dirty-main]
     cd "$HOME/source/rewit-worktrees/<slug>"
     ./scripts/task-gate.sh preflight --slug <slug> --expected-base <sha> \
       [--protected-hash <sha256> --protected-file <caminho>...]
     ```
   - Para contribuidores humanos ou ambientes fora de Linux/Bash 4.4+, crie uma branch curta a partir de `main` (por exemplo, `fix/<slug>` ou `docs/<slug>`) em uma worktree dedicada (procedimento manual de recuperação documentado em [`docs/development/agent-workflow.md`](docs/development/agent-workflow.md)):
     ```bash
     git worktree add -b "<nome-da-branch>" "$HOME/source/rewit-worktrees/<slug>" main
     ```
2. **Implemente respeitando a Clean Architecture** (`domain` puro, portas em `application`, adaptadores em `infrastructure`/`integrations`, contratos HTTP em `presentation`).
3. **Execute o gate local e os testes automatizados**:
   - Execute `./scripts/task-gate.sh check` (verificação READ-ONLY de diff, caminhos sensíveis, padrões de credenciais, migrations Flyway históricas, anotações de supressão e links Markdown) e valide a mensagem com `./scripts/task-gate.sh check-commit-msg --message "<mensagem>"`.
   - Execute os testes e análises estáticas dos módulos alterados utilizando exclusivamente infraestrutura descartável (`TEST_*`).
4. **Atualize a documentação canônica afetada** e valide os links relativos.
5. **Escreva mensagens de commit em PT-BR** seguindo **Conventional Commits** (preservando tipos padronizados como `feat`, `fix`, `docs`, `refactor`, `test`, `chore`):
   ```text
   feat(deploy): adicionar provisionamento do PostgreSQL gerenciado
   ```
6. **Submeta a revisão e integração em `main`** (utilizando `./scripts/task-gate.sh pre-merge --slug <slug> [--protected-hash <sha256> --protected-file <caminho>...]` para checagem informativa pré-integração quando aplicável) com descrição clara do escopo entregue, decisões técnicas, validações executadas e documentos atualizados.
