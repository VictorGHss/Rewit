# RUNBOOK OPERACIONAL DOS AGENTES (`docs/development/agent-workflow.md`)

Este runbook detalha o ciclo de vida operacional de tarefas executadas por agentes no **Rewit**, complementando o contrato obrigatório em [`AGENTS.md`](../../AGENTS.md) e os 31 princípios arquiteturais em [`PROJECT_RULES.md`](../../PROJECT_RULES.md).

> **Nota de estado operacional:** o fluxo operacional dispõe de dois utilitários complementares (Linux / Bash): [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh) para o ciclo de vida seguro de worktrees (`create`, `status`, `close`) e [`scripts/task-gate.sh`](../../scripts/task-gate.sh) para verificações determinísticas e estritamente READ-ONLY de governança local (`preflight`, `check`, `check-commit-msg`, `pre-merge`). O procedimento manual com comandos Git explícitos permanece documentado na Seção 1.4 e na Seção 3 como referência canônica de recuperação. Nenhuma operação remota (`fetch`, `pull`, `push`), merge ou exclusão de branch local é automatizada por esses utilitários.

---

## 1. Início de Tarefa (Abertura e Baseline)

### 1.1 Convenções e Autoridade de Criação
- **Autoridade de criação**: a worktree é criada pelo mantenedor ou por procedimento explicitamente autorizado na tarefa. O agente **não** decide sozinho criar diretórios, worktrees adicionais ou clones independentes.
- **Diretório centralizado (caminho ASCII, sem espaços ou acentos)**:
  ```text
  $HOME/source/rewit-worktrees/<slug>
  ```
  - *Exemplo no ambiente de desenvolvimento atual:* `/home/vito/source/rewit-worktrees/` (apenas ilustração local; não utilize caminho específico de usuário como regra fixa).
- **Branch padrão**:
  ```text
  agent/neon/<slug>
  ```
- **Regras do `<slug>`**: somente letras ASCII minúsculas (`a-z`), dígitos (`0-9`) e hífen interno (`-`), entre 1 e 63 caracteres, nunca iniciando ou terminando com hífen; são proibidos pontos, path traversal (`..`), barras, espaços, acentos e maiúsculas.
- **Base obrigatória**: SHA hexadecimal local explícito e autorizado pertencente ao histórico local da branch `main` (sem pressupor que seja o `HEAD` atual).
- **Sem sincronização remota automática**: nunca execute `git fetch` ou `git pull` sem autorização expressa.
- **Uma tarefa por worktree**: não misture escopos distintos na mesma worktree nem crie clones independentes do repositório.

### 1.2 Automação Segura do Ciclo de Vida (`scripts/worktree-task.sh`)

O utilitário [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh) implementa as operações padronizadas de gerenciamento de worktrees em modo conservador (*fail-closed*): em qualquer dúvida, inconsistência ou falha de verificação, a operação é bloqueada sem alterar o estado do Git.

#### Requisitos Efetivos do Ambiente e Verificação de Versão
O utilitário valida automaticamente as dependências e versões mínimas em tempo de execução (antes de qualquer operação), comparando versões **numericamente** e encerrando com código `4` em caso de ausência ou incompatibilidade:

- **Sistema operacional**: Linux.
- **Shell**: Bash **4.4 ou superior** (verificado via `BASH_VERSINFO`; utiliza recursos nativos do Bash 4.4+, incluindo conversão de caixa `${var,,}` sob `LC_ALL=C`, dispensando `tr` e arrays associativos com chaves arbitrárias).
- **Git**: **2.36 ou superior** (verificado numericamente via `git --version`; exigido pelo suporte a `git worktree list --porcelain -z`).
- **Utilitários padrão obrigatórios no `PATH`**: `git`, `realpath`, `stat`, `find`, `id` e `mkdir` (POSIX / GNU Coreutils / findutils).
- **Suíte descartável de validação**: [`scripts/tests/worktree-task-test.sh`](../../scripts/tests/worktree-task-test.sh) (exige adicionalmente `python3` com o módulo padrão `pty` para testar confirmação humana interativa e cenários TOCTOU em pseudo-terminal real; executa exclusivamente em repositórios temporários sob `/tmp`).

#### Comandos e Sintaxe
```bash
# 1) Criar nova worktree a partir da worktree principal na branch main
./scripts/worktree-task.sh create --slug <slug> --base <sha> [--allow-dirty-main]

# 2) Inspecionar todas as worktrees registradas (estritamente READ-ONLY)
./scripts/worktree-task.sh status

# 3) Simular encerramento seguro (modo padrão: --dry-run)
./scripts/worktree-task.sh close --slug <slug> [--dry-run]

# 4) Executar remoção da worktree com confirmação humana interativa
./scripts/worktree-task.sh close --slug <slug> --execute

# Ajuda de linha de comando
./scripts/worktree-task.sh --help
```

#### Operação `create` e Comportamento de `--allow-dirty-main`
O subcomando `create`:
1. Exige execução a partir da **worktree principal** na branch `main` (recusa execução a partir de worktrees secundárias, outras branches ou `DETACHED HEAD`).
2. Valida o formato restritivo do `<slug>` e exige um `<sha>` hexadecimal explícito (7 a 64 caracteres, recusando refs simbólicas como `main` ou `HEAD`).
3. Confirma que `<sha>` existe localmente como objeto do tipo `commit` (recusando `blob`, `tree` ou objetos inexistentes) e pertence ao histórico local da `main` (`git merge-base --is-ancestor`), sem executar `fetch` ou `pull`.
4. Valida que `$HOME/source/rewit-worktrees` é estritamente ASCII sem espaços/acentos, pertence ao usuário atual e não possui symlinks em nenhum diretório ancestral nem no destino (a worktree principal pode residir em caminho com espaços ou acentos sem comprometer o destino ASCII padronizado).
5. Bloqueia se a branch `agent/neon/<slug>`, o diretório de destino ou outra worktree para a mesma tarefa já existirem.
6. Cria a worktree vinculada ao repositório Git compartilhado (`git worktree add -b`), sem `git clone`.
7. **Tratamento de modificações locais na `main`**:
   - Arquivos em **staging** na `main` são **sempre recusados**, mesmo com `--allow-dirty-main`.
   - Arquivos **não rastreados (`untracked`)** na `main` são **sempre recusados**, mesmo com `--allow-dirty-main`.
   - Quando existem modificações rastreadas e **unstaged** na `main` (por exemplo, alterações locais protegidas), o script lista todos os caminhos modificados e **recusa** a criação se `--allow-dirty-main` não for informada.
   - Com `--allow-dirty-main`, a criação é autorizada preservando **integralmente intactas** essas modificações locais na `main` — a opção **nunca** executa `add`, `restore`, `checkout`, `clean`, `stash` nem copia tais alterações para a nova worktree.
8. **Tratamento de falhas parciais**: se `mkdir` ou `git worktree add` falharem, o utilitário preserva o diagnóstico original do Git/sistema, retorna o código contratual `4`, **nunca executa limpeza destrutiva automática**, alerta que pode existir estado residual e orienta inspeção manual. Uma tentativa posterior diante de estado residual é bloqueada em modo *fail-closed* (`código 3`).

#### Operação `status` (Estritamente READ-ONLY e Resiliente)
O subcomando `status` consulta todas as worktrees registradas sem alterar arquivos, índice ou referências, exibindo para cada uma:
- Caminho registrado e se é a worktree principal.
- Branch ativa (identificando explicitamente `DETACHED HEAD` ou `BARE` quando aplicável), `HEAD`, e eventuais sinalizações `locked` ou `prunable`.
- Estado da árvore (`limpa`, `suja` ou `INCONSISTENTE / NÃO VERIFICÁVEL`), contagem de alterações rastreadas (deduplicadas de forma literal a partir do fluxo NUL-delimited do Git, sem arrays associativos e seguras para nomes com colchetes, espaços ou acentos), detalhando `staged`, `unstaged` e `untracked`.
- Quantidade de **commits exclusivos** em relação à `main` local calculada por ancestralidade real (`git rev-list --count refs/heads/main..<HEAD>`) e confirmação explícita de ancestralidade direta (`git merge-base --is-ancestor`), sem inferir equivalência por cherry-pick, squash ou PR.
- Detecção heurística de arquivos ignorados potencialmente sensíveis (exibindo apenas caminho relativo e motivo, nunca conteúdo).
- **Resiliência a worktrees individuais corrompidas ou ilegíveis**: se uma worktree individual estiver `prunable`, com diretório ausente, com `.git` ausente/inválido ou com erro de permissão em diretório ignorado, `status` registra claramente o estado como `INCONSISTENTE / NÃO VERIFICÁVEL` (nunca como `limpa`), **continua listando as demais worktrees** e encerra ao final com código `3`.

#### Códigos de Saída Contratuais de `scripts/worktree-task.sh`

| Código | Significado | Casos cobertos |
| :-: | :--- | :--- |
| `0` | Sucesso | `create` concluído; `status` com todas as worktrees verificadas; `close --dry-run` aprovado; `close --execute` concluído; `close` idempotente em worktree já encerrada de forma limpa; `--help`. |
| `2` | Erro de uso | Comando desconhecido; opção desconhecida, duplicada ou conflitante (`--dry-run` + `--execute`); opção arbitrária (ex.: `--path`); `--slug` inválido; `--base` ausente ou não hexadecimal. |
| `3` | Bloqueio de segurança, integridade ou worktree não verificável | `main` suja sem `--allow-dirty-main`; arquivos staged/untracked na `main`; `main` em `DETACHED HEAD` ou outra branch; SHA inexistente, não-commit (`blob`/`tree`) ou fora da ancestralidade da `main`; symlink em ancestral/destino; commits exclusivos ou cherry-pick sem ancestralidade direta; ignorados sensíveis; worktree principal, `locked`, `prunable`, `DETACHED HEAD`, clone independente ou `.git` inválido; execução dentro da própria worktree-alvo; ausência de TTY interativo, confirmação divergente ou EOF; estado/HEAD/main alterados antes da remoção; uma ou mais worktrees não verificáveis em `status`. |
| `4` | Erro de ambiente, dependência ou falha inesperada de comando Git/sistema | Execução fora de repositório Git; Bash `< 4.4` ou Git `< 2.36`; utilitário obrigatório ausente no `PATH`; falha de `mkdir`, `git worktree add`, `git worktree remove` ou comando Git global/auxiliar inesperado. |

### 1.3 Preflight Obrigatório Antes de Qualquer Edição
Antes de alterar qualquer arquivo na worktree atribuída, confirme com comandos reais (preferencialmente via `./scripts/task-gate.sh preflight`, descrito na Seção 2.3):

1. Diretório real (`pwd` / `realpath .`) corresponde à worktree atribuída.
2. Branch atual (`git rev-parse --abbrev-ref HEAD`) corresponde à branch autorizada.
3. Commit base (`git rev-parse HEAD`) corresponde exatamente ao SHA autorizado.
4. Working tree limpa (`git status --short --untracked-files=all`) e `0` arquivos em staging (`git diff --cached --name-only`).
5. Integridade da worktree principal (`main`) e de eventuais alterações locais protegidas (incluindo SHA-256 do diff combinado quando informado pelo mantenedor), sem modificar a worktree principal.

Se qualquer pré-condição divergir, **interrompa imediatamente** e reporte ao mantenedor antes de editar qualquer arquivo.

### 1.4 Procedimento Manual (Referência de Recuperação)
Quando for necessário operar sem o utilitário (por exemplo, recuperação manual ou inspeção fora de Linux/Bash 4.4+), utilize os comandos Git explícitos com quoting adequado:

```bash
# Criação manual de worktree a partir de SHA explícito (executado na worktree principal)
git worktree add -b "agent/neon/<slug>" "$HOME/source/rewit-worktrees/<slug>" "<SHA>"

# Inspeção manual
git worktree list --porcelain
git -C "$HOME/source/rewit-worktrees/<slug>" status --short --untracked-files=all
git rev-list --count "refs/heads/main..refs/heads/agent/neon/<slug>"

# Remoção manual segura (somente após cumprir todas as salvaguardas da Seção 3.2)
git worktree remove "$HOME/source/rewit-worktrees/<slug>"
```

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
   - Para alterações em [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh) ou [`scripts/task-gate.sh`](../../scripts/task-gate.sh), executar `bash -n` e as suítes descartáveis [`./scripts/tests/worktree-task-test.sh`](../../scripts/tests/worktree-task-test.sh) e [`./scripts/tests/task-gate-test.sh`](../../scripts/tests/task-gate-test.sh) (nunca executar `close --execute` contra worktrees reais do repositório durante testes).
   - Executar `./scripts/task-gate.sh check` em modo READ-ONLY antes de encerrar a entrega para auditar o conjunto integral de alterações.
   - Preferir testes reais (Spring Security, PostgreSQL/PostGIS, Flyway, HTTP/RFC 7807, transações, canais nativos Android/iOS) em vez de mocks enganosos.
   - Em tarefas exclusivamente documentais, executar verificações documentais, links e `git diff --check` sem rodar suítes de aplicação ou migrations desnecessariamente.
5. Manter a documentação canônica sincronizada junto da implementação (Seção 4 abaixo).
6. Nunca inspecionar ou expor `.env`, tokens, chaves ou credenciais.

### 2.3 Gate Local de Governança (`scripts/task-gate.sh`)

O utilitário [`scripts/task-gate.sh`](../../scripts/task-gate.sh) transforma verificações recorrentes de governança em checagens locais determinísticas, *fail-closed* e **estritamente READ-ONLY** (usa `GIT_OPTIONAL_LOCKS=0`, isola configurações externas com `GIT_CONFIG_GLOBAL=/dev/null`, `GIT_CONFIG_SYSTEM=/dev/null` e `GIT_CONFIG_NOSYSTEM=1`, neutraliza `diff.external` e cores, não escreve no índice nem em referências, não cria arquivos temporários no repositório, não instala hooks nem dependências e não executa builds pesados).

#### Relação entre `worktree-task.sh` e `task-gate.sh`
- [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh) gerencia o **ciclo de vida das worktrees** (`create`, `status`, `close`).
- [`scripts/task-gate.sh`](../../scripts/task-gate.sh) audita a **conformidade e a integridade da tarefa** antes de editar (`preflight`), antes da entrega (`check`), antes do commit (`check-commit-msg`) e antes da integração manual (`pre-merge`).

#### Segurança de Ambiente (`GIT_*`)
- Antes da primeira invocação do `git` (inclusive em `--help` e na checagem de versão), o gate inspeciona todas as variáveis de ambiente `GIT_*` exportadas e **rejeita com código `3`** qualquer variável fora da allowlist estrita de UX/lock (`GIT_OPTIONAL_LOCKS`, `GIT_PAGER`, `GIT_EDITOR`, `GIT_TERMINAL_PROMPT` — todas neutralizadas internamente em cada chamada Git).
- Essa proteção bloqueia tentativas de redirecionamento de repositório/índice (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`, `GIT_OBJECT_DIRECTORY`, `GIT_ALTERNATE_OBJECT_DIRECTORIES`), injeção de configuração (`GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`, `GIT_CONFIG`) e efeitos colaterais de tracing (`GIT_TRACE*`, `GIT_TRACE2*`). Apenas o nome da variável bloqueada é exibido, nunca seu valor.

#### Requisitos Efetivos e Verificação em Runtime
- **Sistema operacional**: Linux.
- **Shell**: Bash **4.4 ou superior** (verificado numericamente via `BASH_VERSINFO`).
- **Git**: **2.36 ou superior** (verificado numericamente via `git --version`).
- **Python 3**: obrigatório para análise estrutural de diffs, redação de padrões de credenciais, abertura segura por descritor (`O_NOFOLLOW | O_NONBLOCK`, `fstat`/`S_ISREG`) e validação do subconjunto documentado de links Markdown; verificado explicitamente no `PATH` (falha com código `4` quando indisponível).
- **Utilitários padrão**: `realpath`, `stat`, `id` e `sha256sum`.
- **Suíte adversarial descartável**: [`scripts/tests/task-gate-test.sh`](../../scripts/tests/task-gate-test.sh) (executa exclusivamente em repositórios temporários em `/tmp`, validando snapshots READ-ONLY antes/depois — incluindo índice Git, `st_mode`, `symbolic-ref HEAD` e metadados do `git-dir` — e snapshot do repositório hospedeiro sem ler conteúdo de arquivos protegidos).

#### Validação de Arquivos Locais Protegidos na `main`
Quando a `main` possui modificações locais protegidas unstaged (` M`), um SHA-256 isolado **não** é aceito como prova suficiente: `preflight` (quando `--protected-hash` é informado) e `pre-merge` exigem a combinação explícita de:
- `--protected-hash <sha256>` — hash determinístico de `git diff --no-ext-diff --no-color` (imune a `diff.external` e `color.diff=always`).
- `--protected-file <caminho>` (repetível) — identidade e quantidade exata de cada caminho relativo modificado unstaged na `main` (nenhum arquivo ausente, nenhum adicional, nenhum staged, nenhum untracked e nenhum rename/deleção).

Para o estado protegido atual do projeto, os três caminhos esperados são:
- `apps/mobile/android/app/src/main/AndroidManifest.xml`
- `apps/mobile/android/gradle.properties`
- `apps/mobile/lib/app/config/app_config.dart`

#### Quando Executar Cada Subcomando e Exemplos Completos

1. **`preflight`** — imediatamente após abrir a worktree e antes de editar qualquer arquivo:
   ```bash
   cd "$HOME/source/rewit-worktrees/<slug>"
   ./scripts/task-gate.sh preflight \
     --slug <slug> \
     --expected-base <sha> \
     --protected-hash 997473beef188c9021bc63bed60b0f88177155c6683821573d9421613188826f \
     --protected-file apps/mobile/android/app/src/main/AndroidManifest.xml \
     --protected-file apps/mobile/android/gradle.properties \
     --protected-file apps/mobile/lib/app/config/app_config.dart
   ```
   Valida caminho centralizado, ausência de symlinks, metadados reais do Git (recusa clones independentes com mesmo nome de pasta e worktrees `prunable`), branch `agent/neon/<slug>`, `HEAD` exato, árvore limpa, staging vazio, ausência de `untracked`, identificação inequívoca da `main` e, quando informados, hash e conjunto exato de arquivos protegidos na `main` (sem exibir conteúdo).

2. **`check`** — após implementar e testar, antes de entregar para auditoria do Neon Medium:
   ```bash
   ./scripts/task-gate.sh check
   # Ou especificando explicitamente a referência-base:
   ./scripts/task-gate.sh check --base main
   ```
   Inspeciona todas as alterações simultaneamente (**commitadas desde o merge-base**, **staged**, **unstaged**, **untracked não ignorados**, **renames** e **deleções**):
   - **Integridade do diff**: executa `git diff --no-ext-diff --no-color --check` nas três camadas com parser compatível com caminhos contendo `:`, espaços e Unicode (bloqueando genericamente sem vazar conteúdo caso alguma linha não seja parseável e retornando `4` em falhas inesperadas do Git) + verificação de trailing whitespace e marcadores de conflito em `untracked`; binários e symlinks são reportados como `[NAO_VERIFICAVEL]` (sem seguir symlinks para leitura de conteúdo).
   - **Caminhos sensíveis**: bloqueia pelo nome/extensão sem abrir o arquivo (`.env`, `.env.*` exceto `.env.example`/`.env.sample`/`.env.template`, `.envrc`, keystores, chaves privadas, certificados, credenciais conhecidas, `local.properties`, `key.properties`), inclusive caminhos normalmente ignorados forçados no staging.
   - **Padrões conservadores de credenciais**: analisa linhas adicionadas e arquivos `untracked` comuns com **redação total** (`[REDIGIDO]`), sem imprimir valores suspeitos.
   - **Imutabilidade de migrations Flyway históricas**: compara `backend/src/main/resources/db/migration/` contra o `merge-base`, bloqueando modificação, remoção ou renomeação de migrations históricas e permitindo apenas migrations realmente novas.
   - **Supressão de diagnósticos**: bloqueia novas ocorrências de `@SuppressWarnings` adicionadas em arquivos de código/texto fora de documentação Markdown (`*.md`), preservando ocorrências preexistentes e permitindo menções documentais explícitas em arquivos `.md`.
   - **Subconjunto suportado de links Markdown**:
     - *Suportado*: links e imagens inline em linha única (`[texto](dest)`, `![alt](dest)`), badges com imagem inline aninhada (`[![alt](img)](dest)`), destinos com espaços via `<...>` ou `%20`, destinos com parênteses balanceados (ex.: `doc_(v1).md`), títulos opcionais e âncoras GFM de cabeçalhos ATX (`# ...`); ignora blocos de código cercados (respeitando caractere e comprimento do delimitador de abertura/fechamento), spans de código inline com um ou múltiplos backticks e URLs externas (`http://`, `https://`, `mailto:`, `tel:`).
     - *Não suportado (reportado como `[NAO_VERIFICAVEL]`, código `3`)*: links por referência (`[a][b]`, `[id]: url`), tags HTML (`<a href=...>` / `<img src=...>`), links multilinhas ou com parênteses desbalanceados, code fences sem fechamento e âncoras em arquivos não-Markdown fora do padrão `#L<n>`/`#L<n>-L<m>`.
   - **Resumo por módulos**: identifica módulos afetados (`backend`, `apps/mobile`, `apps/admin`, `scripts`, `docs`, `outros`) e recomenda as validações manuais correspondentes (sem executá-las).

3. **`check-commit-msg`** — antes de executar um commit autorizado:
   ```bash
   ./scripts/task-gate.sh check-commit-msg --message "feat(worktrees): adicionar verificação local"
   # Ou a partir de um arquivo regular UTF-8 (<= 64 KiB, aberto via O_NOFOLLOW | O_NONBLOCK):
   ./scripts/task-gate.sh check-commit-msg --file /tmp/mensagem.txt
   ```
   Valida estruturalmente Conventional Commits (`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`, escopo opcional não vazio, `!` e rodapé `BREAKING CHANGE:` consistentes, descrição obrigatória) usando abertura segura por descritor (`os.open` com `O_NOFOLLOW | O_NONBLOCK`, `fstat`/`S_ISREG`, leitura limitada a `64 KiB + 1` com recusa de symlinks, FIFOs, diretórios, binários e caminhos sensíveis) sem exibir conteúdo bruto. **A conformidade com português brasileiro (PT-BR) exige revisão humana** (explicitada em `[AVISO]`).

4. **`pre-merge`** — antes de solicitar/executar integração local por fast-forward na `main`:
   ```bash
   ./scripts/task-gate.sh pre-merge \
     --slug <slug> \
     --protected-hash 997473beef188c9021bc63bed60b0f88177155c6683821573d9421613188826f \
     --protected-file apps/mobile/android/app/src/main/AndroidManifest.xml \
     --protected-file apps/mobile/android/gradle.properties \
     --protected-file apps/mobile/lib/app/config/app_config.dart
   ```
   Estritamente informativo (READ-ONLY; **nunca executa `git merge`**): verifica existência da branch `agent/neon/<slug>`, identificação inequívoca da `main`, existência de commits candidatos, ausência de divergência, ausência de arquivos `staged`/`untracked`/renames na `main`, ausência de colisões de caminho com alterações locais da `main` e validação explícita do hash protegido + conjunto exato de `--protected-file` quando a `main` estiver suja.

#### Interpretação dos Códigos de Saída e Rótulos de `scripts/task-gate.sh`
- Rótulos de saída: `[APROVADO]`, `[BLOQUEADO]`, `[NAO_VERIFICAVEL]` e `[AVISO]`. Qualquer item `[BLOQUEADO]` ou `[NAO_VERIFICAVEL]` resulta em falha *fail-closed* (`código 3`); condições não verificáveis nunca são convertidas em aprovação.
- `0`: verificação aprovada (ou `--help`).
- `2`: erro de uso ou argumentos inválidos/duplicados/conflitantes.
- `3`: bloqueio de governança, integridade, variável `GIT_*` bloqueada ou item obrigatório não verificável.
- `4`: erro de ambiente, dependência ausente (ex.: `python3`, `git`), versão incompatível (`Bash < 4.4` ou `Git < 2.36`) ou falha inesperada de comando Git.

#### Sequência Recomendada Durante uma Tarefa
1. Mantenedor (ou tarefa autorizada) cria a worktree: `./scripts/worktree-task.sh create --slug <slug> --base <sha> [--allow-dirty-main]`.
2. Neon High executa `./scripts/task-gate.sh preflight --slug <slug> --expected-base <sha> [--protected-hash <sha256> --protected-file <caminho>...]` na nova worktree.
3. Neon High implementa código, testes descartáveis e documentação canônica.
4. Neon High executa `./scripts/task-gate.sh check` e as suítes recomendadas para os módulos afetados.
5. Neon Medium executa auditoria adversarial READ-ONLY na mesma worktree.
6. Com autorização de commit do mantenedor: validar a mensagem com `./scripts/task-gate.sh check-commit-msg` e criar o commit local.
7. Com autorização de integração do mantenedor: conferir elegibilidade com `./scripts/task-gate.sh pre-merge --slug <slug> [--protected-hash <sha256> --protected-file <caminho>...]` e integrar manualmente via `git merge --ff-only <SHA>`.
8. Com autorização independente de encerramento: simular e encerrar a worktree com `./scripts/worktree-task.sh close --slug <slug>` (preservando a branch local até autorização separada).

#### Responsabilidades Manuais do Mantenedor e Limitações Conhecidas
- **Responsabilidades manuais**: autorizar separadamente criação de worktree, commit, integração (`git merge --ff-only`), push, remoção de worktree e remoção de branch; revisar conformidade textual em PT-BR das mensagens de commit e documentação; executar suítes de build/teste dos módulos afetados; auditar arquivos reportados como `[NAO_VERIFICAVEL]` (como binários esperados).
- **Limitações conhecidas**:
  - A detecção de arquivos sensíveis e padrões de credenciais é heurística/conservadora e **não garante** identificação de todos os segredos possíveis.
  - O parser de links Markdown cobre exclusivamente o subconjunto documentado acima (não implementa CommonMark/GFM completo, não valida URLs HTTP/HTTPS externas e marca sintaxes não suportadas ou fences sem fechamento como `[NAO_VERIFICAVEL]`).
  - O resultado de `pre-merge` reflete exclusivamente a elegibilidade observada no instante da execução e não garante que o estado não mude antes da integração manual.
  - Compatibilidade com **Bash 4.4** e **Git 2.36** foi verificada **estaticamente** quanto aos recursos empregados (com checagem de versão Git testada via wrapper simulado); a validação empírica ocorreu em Linux com **Bash 5.3.0**, **Git 2.56.1** e **Python 3.14.10**, sem execução de `shellcheck` (ausente no ambiente).

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

### 3.2 Salvaguardas Obrigatórias de Encerramento de Worktree (`close`)

O subcomando `./scripts/worktree-task.sh close --slug <slug>` opera por padrão em **modo de simulação (`--dry-run`)**, executando todas as auditorias sem modificar arquivos nem referências.

#### Identificação Segura e Bloqueios
- O caminho alvo é derivado **exclusivamente** do `--slug` validado (`$HOME/source/rewit-worktrees/<slug>`) e conferido contra `git worktree list --porcelain -z` e contra a branch esperada `agent/neon/<slug>`. Caminhos arbitrários nunca são aceitos.
- Ao detectar `.git` ausente, diretório (clone independente), symlink inesperado ou arquivo inválido, a auditoria da worktree é **interrompida imediatamente** com código `3`, impedindo que comandos Git resolvam acidentalmente para um repositório pai.
- O encerramento é **bloqueado** (`código 3`) sempre que existir qualquer uma das condições abaixo:
  - Alterações rastreadas não commitadas (`unstaged`) ou arquivos em `staging`.
  - Arquivos não rastreados (`untracked`).
  - Commits exclusivos na worktree ou na branch que não sejam **ancestrais diretos** da `main` local (`git rev-list --count` > 0 ou `git merge-base --is-ancestor` falso). Patches equivalentes por `cherry-pick` ou `squash` (mesmo reconhecidos por `git cherry`) **não** autorizam o encerramento automático.
  - **Arquivos ignorados potencialmente sensíveis (detecção heurística por nome/extensão)**: sem abrir ou ler conteúdo e sem seguir symlinks para fora da árvore auditada, reportando apenas caminho relativo e motivo. A heurística cobre:
    - Variáveis de ambiente e segredos: `.env`, `.env.*`, `.envrc`, `secrets.env`, `*.env`.
    - Keystores, chaves e certificados: `*.jks`, `*.keystore`, `*.pem`, `*.crt`, `*.cer`, `*.der`, `*.key`, `*.p12`, `*.pfx`, `*.p8`, `*.ppk`, `*.kdbx`, chaves SSH conhecidas (`id_rsa`, `id_ed25519`, `id_ecdsa`, `id_dsa`).
    - Credenciais e propriedades locais do stack mobile/cloud/dev: `local.properties`, `key.properties`, `google-services.json`, `GoogleService-Info.plist`, `credentials.*`, `secrets.*`, `client_secret*.json`, `service-account*.json`, `service_account*.json`, `*firebase-adminsdk*.json`, `application-secret*`, `.netrc`, `.pgpass`, `.npmrc`, `.pypirc`, `.htpasswd`, `*.tfstate*`, `*.tfvars`.
    - Diretórios padronizados de build/dependências/cache (`build`, `target`, `dist`, `out`, `coverage`, `node_modules`, `.gradle`, `.dart_tool`, `.pub-cache`, `.cache`, `.vite`, `__pycache__`, `.pytest_cache`, `.mypy_cache`, `.next`, `.nuxt`, `.turbo`) não são classificados indiscriminadamente como sensíveis.
    - *Importante*: trata-se de uma **heurística baseada em nomes**, não de uma garantia de identificação de todos os segredos possíveis; arquivos com nomes arbitrários contendo segredos continuam exigindo revisão consciente do mantenedor.
  - Associação Git inconsistente (branch divergente, `DETACHED HEAD`, branch anexada a outro caminho, worktree `locked` ou `prunable`, diretório órfão não registrado, clone independente com diretório `.git`, symlink em ancestral ou no destino).
  - Tentativa de encerrar a **worktree principal** ou de encerrar a worktree a partir de dentro dela mesma.

#### Execução (`--execute`), Exclusividade Operacional, Confirmação Humana e Idempotência
- **Exclusividade operacional obrigatória**: `close --execute` pressupõe que nenhuma outra sessão modificadora (agente ou processo externo) esteja operando simultaneamente na worktree-alvo.
- Para efetivar a remoção, são obrigatórias **duas condições cumulativas**:
  1. Passar explicitamente a opção `--execute`.
  2. Executar em **terminal interativo (TTY)** e digitar **exatamente o `<slug>`** no prompt de confirmação humana (`EOF`/Ctrl-D ou texto divergente cancelam imediatamente com código `3`).
- Imediatamente após a confirmação e antes da remoção, o utilitário executa **revalidação integral**: se o estado da worktree, o `HEAD` da worktree ou o `HEAD` da `main` local tiver mudado durante o prompt, a remoção é cancelada (`código 3`) sem efeitos colaterais.
- A remoção executa exclusivamente `git worktree remove <caminho>` **sem `--force`**, não executa `git worktree prune` e **preserva intacta a branch local** `agent/neon/<slug>`. Em falha de `git worktree remove`, preserva o diagnóstico do Git, não força a remoção, mantém a branch local intacta e retorna `código 4`.
- **Ausência de remoção de branches**: nesta versão, `scripts/worktree-task.sh` **não** possui comando para remover branches locais; a remoção da branch permanece operação manual, explícita e independente.
- **Idempotência**: se a worktree já não existir no disco e não houver registro pendente para o caminho nem para a branch em `git worktree list`, `close` (tanto em `--dry-run` quanto em `--execute`) informa que ela já está encerrada, preserva a branch local sem alterar referências e encerra com código `0`. Associações incompletas (como diretório órfão ou registro `prunable`) **não** são tratadas como sucesso e continuam sendo bloqueadas (`código 3`).

#### Proibições Absolutas sem Autorização Explícita
- Nunca usar `git worktree remove --force`.
- Nunca usar `git branch -D`.
- Não apagar automaticamente branches locais ao encerrar uma worktree.
- Toda remoção de worktree e toda remoção de branch exigem **confirmação humana explícita** após as verificações acima.

#### Limitações Conhecidas e Riscos Residuais
- **Janela residual TOCTOU**: embora o utilitário revalide integralmente a worktree, o `HEAD` e a `main` imediatamente antes de invocar `git worktree remove`, permanece uma janela residual de milissegundos entre essa revalidação final e a conclusão do comando Git. Por esse motivo, a operação **não oferece proteção absoluta contra concorrência externa** e exige exclusividade operacional sobre a worktree durante o encerramento; arquivos ignorados criados concorrentemente nessa janela por outro processo podem ser removidos pelo Git.
- **Heurística de sensíveis**: a detecção de arquivos ignorados sensíveis é estritamente baseada em nomes e extensões conhecidos (sem leitura de conteúdo) e não varre diretórios padronizados de build/dependências/cache. Não garante detecção de segredos salvos sob nomes arbitrários.
- **Compatibilidade de versões ainda não testada empiricamente**: os pisos mínimos declarados (**Bash 4.4+** e **Git 2.36+**) foram verificados **estaticamente** quanto aos recursos utilizados (e a lógica numérica de rejeição de versão do Git foi testada via wrapper simulado); a validação empírica da suíte ocorreu em Linux com **Bash 5.3.0** e **Git 2.56.1**, sem execução empírica em binários reais de Bash 4.4 ou Git 2.36. Além disso, `shellcheck` não estava disponível no ambiente de validação.
- **Escopo operacional**: suporte exclusivo a Linux (sem equivalente PowerShell nativo nesta fase); não remove branches locais; não executa `git worktree prune`, `git fetch` ou `git pull`; worktrees legadas fora da convenção `$HOME/source/rewit-worktrees/<slug>` + `agent/neon/<slug>` são propositalmente bloqueadas por `close` e devem ser tratadas manualmente mediante autorização específica.

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
