# RUNBOOK OPERACIONAL DOS AGENTES (`docs/development/agent-workflow.md`)

Este runbook detalha o ciclo de vida operacional de tarefas executadas por agentes no **Rewit**, complementando o contrato obrigatório em [`AGENTS.md`](../../AGENTS.md) e os 31 princípios arquiteturais em [`PROJECT_RULES.md`](../../PROJECT_RULES.md).

> **Nota de estado operacional:** o ciclo de vida de worktrees conta com o utilitário conservador [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh) (Linux / Bash) para criação (`create`), inspeção READ-ONLY (`status`) e encerramento seguro (`close`). O procedimento manual com comandos Git explícitos permanece documentado na Seção 1.4 e na Seção 3 como referência canônica de recuperação. Nenhuma operação remota (`fetch`, `pull`, `push`) ou exclusão de branch local é automatizada pelo utilitário.

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
Antes de alterar qualquer arquivo na worktree atribuída, confirme com comandos reais:

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
   - Para alterações em [`scripts/worktree-task.sh`](../../scripts/worktree-task.sh), executar `bash -n` e a suíte descartável [`./scripts/tests/worktree-task-test.sh`](../../scripts/tests/worktree-task-test.sh) (nunca executar `close --execute` contra worktrees reais do repositório durante testes).
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
