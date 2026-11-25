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

## 3. Fluxo de Contribuição (Base `main` e Worktrees)

A branch **`main`** é a única base canônica de integração do repositório.

1. **Crie uma worktree isolada a partir de `main`** (uma tarefa por worktree, evitando clones independentes e preservando a worktree principal):
   - Para sessões de agentes, utilize o padrão de branch `agent/neon/<slug>` em `$HOME/source/rewit-worktrees/<slug>` (caminho ASCII sem espaços ou acentos, criado pelo mantenedor ou mediante autorização explícita).
   - Para contribuidores humanos, crie uma branch curta a partir de `main` (por exemplo, `fix/<slug>` ou `docs/<slug>`) em uma worktree dedicada:
     ```bash
     git worktree add -b "<nome-da-branch>" "$HOME/source/rewit-worktrees/<slug>" main
     ```
2. **Implemente respeitando a Clean Architecture** (`domain` puro, portas em `application`, adaptadores em `infrastructure`/`integrations`, contratos HTTP em `presentation`).
3. **Escreva e execute testes automatizados** (incluindo análise estática e `git diff --check`) usando exclusivamente infraestrutura descartável.
4. **Atualize a documentação canônica afetada** e valide os links relativos.
5. **Escreva mensagens de commit em PT-BR** seguindo **Conventional Commits** (preservando tipos padronizados como `feat`, `fix`, `docs`, `refactor`, `test`, `chore`):
   ```text
   feat(deploy): adicionar provisionamento do PostgreSQL gerenciado
   ```
6. **Submeta a revisão e integração em `main`** com descrição clara do escopo entregue, decisões técnicas, validações executadas e documentos atualizados.
