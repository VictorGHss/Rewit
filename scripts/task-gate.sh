#!/usr/bin/env bash
# ==============================================================================
# Rewit — Gate Local de Governança (scripts/task-gate.sh)
#
# Utilitário CLI determinístico, auditável, fail-closed e estritamente READ-ONLY
# para verificações recorrentes de governança local no repositório Rewit.
#
# Requisitos mínimos declarados:
#   - Ambiente Linux com Bash 4.4+
#   - Git 2.36+ (suporte a `git worktree list --porcelain -z`)
#   - Python 3 (análise estrutural de diffs, redação de credenciais, abertura
#     segura por descritor e validação de subconjunto documentado de Markdown)
#   - Utilitários padrão POSIX/GNU Coreutils: realpath, stat, id, sha256sum
#
# Segurança de ambiente:
#   - Antes de qualquer invocação Git (inclusive `--help` e detecção de versão),
#     rejeita com código 3 qualquer variável de ambiente `GIT_*` fora da
#     allowlist mínima (`GIT_OPTIONAL_LOCKS`), impedindo bypass de contexto,
#     substituição de índice, injeção de configuração e efeitos colaterais de
#     tracing (`GIT_TRACE*`, `GIT_TRACE2*`).
#   - Todas as chamadas Git usam `GIT_OPTIONAL_LOCKS=0`, isolam configurações
#     externas (`GIT_CONFIG_GLOBAL=/dev/null`, `GIT_CONFIG_SYSTEM=/dev/null`,
#     `GIT_CONFIG_NOSYSTEM=1`) e neutralizam cores e external diff.
#
# Códigos de saída contratuais:
#   0 — Verificação aprovada (ou exibição de --help)
#   2 — Erro de uso ou argumentos inválidos
#   3 — Bloqueio de governança, integridade, ambiente Git redirecionado ou
#       condição obrigatória não verificável
#   4 — Erro de ambiente, dependência, versão incompatível ou falha Git/sistema
# ==============================================================================

set -Eeuo pipefail

readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_BLOCKED=3
readonly EXIT_ENV=4

readonly MIN_BASH_MAJOR=4
readonly MIN_BASH_MINOR=4
readonly MIN_GIT_MAJOR=2
readonly MIN_GIT_MINOR=36

readonly MAIN_BRANCH="main"
readonly BRANCH_PREFIX="agent/neon/"
readonly WORKTREE_SUBDIR="source/rewit-worktrees"

# Variáveis de contexto do repositório
MAIN_WORKTREE_PATH=""
MAIN_WORKTREE_REALPATH=""
CURRENT_TOPLEVEL=""
BASE_WORKTREE_DIR=""

WT_PATHS=()
WT_HEADS=()
WT_BRANCHES=()
WT_DETACHED=()
WT_BARE=()
WT_LOCKED=()
WT_PRUNABLE=()
WT_IS_MAIN=()

STATUS_TRACKED_PATHS=()
STATUS_STAGED_PATHS=()
STATUS_UNSTAGED_PATHS=()
STATUS_UNTRACKED_PATHS=()
STATUS_UNSTAGED_CODES=()
LAST_INSPECT_ERROR=""

PROTECTED_CHECK_HASH=""
PROTECTED_CHECK_BLOCKERS=()

# ------------------------------------------------------------------------------
# Mensagens padronizadas e utilitários READ-ONLY
# ------------------------------------------------------------------------------

msg_info() { printf '%s\n' "$*"; }
msg_ok() { printf '[APROVADO] %s\n' "$*"; }
msg_warn() { printf '[AVISO] %s\n' "$*"; }
msg_blocked() { printf '[BLOQUEADO] %s\n' "$*" >&2; }
msg_unverifiable() { printf '[NAO_VERIFICAVEL] %s\n' "$*" >&2; }
msg_err() { printf 'ERRO: %s\n' "$*" >&2; }

die_usage() {
  msg_err "$*"
  msg_err "Execute '$0 --help' para consultar a sintaxe."
  exit "$EXIT_USAGE"
}

die_blocked() {
  msg_blocked "$*"
  exit "$EXIT_BLOCKED"
}

die_env() {
  msg_err "$*"
  exit "$EXIT_ENV"
}

on_unexpected_err() {
  local lineno="$1"
  local rc="$2"
  msg_err "Falha inesperada de ambiente ou comando externo na linha ${lineno} (código interno ${rc})."
  exit "$EXIT_ENV"
}
trap 'on_unexpected_err "$LINENO" "$?"' ERR

# Bloqueia qualquer variável de ambiente GIT_* fora da allowlist estrita ANTES
# da primeira invocação de `git` (nunca imprime valores das variáveis).
reject_unsafe_git_env() {
  local blocked_vars=()
  local var_name=""
  for var_name in "${!GIT_@}"; do
    case "$var_name" in
      GIT_OPTIONAL_LOCKS|GIT_PAGER|GIT_EDITOR|GIT_TERMINAL_PROMPT)
        # Variáveis benignas de UX/lock frequentemente definidas por IDEs/terminais;
        # ainda assim são neutralizadas explicitamente em `git_ro()` e `run_git()`.
        ;;
      *)
        blocked_vars+=("$var_name")
        ;;
    esac
  done

  if (( ${#blocked_vars[@]} > 0 )); then
    msg_blocked "Variáveis de ambiente Git não permitidas detectadas (nomes listados sem valores por segurança):"
    for var_name in "${blocked_vars[@]}"; do
      printf '  - %s\n' "$var_name" >&2
    done
    msg_blocked "Remova essas variáveis do ambiente antes de executar o gate."
    exit "$EXIT_BLOCKED"
  fi
}

# Wrapper Git estritamente READ-ONLY e determinístico:
# - desabilita locks opcionais (GIT_OPTIONAL_LOCKS=0) e pager/editor/prompt
# - isola configurações globais/sistema (GIT_CONFIG_GLOBAL/SYSTEM=/dev/null)
# - neutraliza external diff, cores e quoting octal de caminhos
git_ro() {
  LC_ALL=C \
  GIT_OPTIONAL_LOCKS=0 \
  GIT_PAGER=cat \
  GIT_EDITOR=: \
  GIT_TERMINAL_PROMPT=0 \
  GIT_CONFIG_GLOBAL=/dev/null \
  GIT_CONFIG_SYSTEM=/dev/null \
  GIT_CONFIG_NOSYSTEM=1 \
  git \
    --no-pager \
    -c core.quotePath=false \
    -c color.ui=never \
    -c color.diff=never \
    -c diff.external= \
    -c diff.noprefix=false \
    -c diff.mnemonicPrefix=false \
    "$@"
}

matches_c_regex() {
  local value="$1"
  local pattern="$2"
  local LC_ALL=C
  [[ "$value" =~ $pattern ]]
}

git_ref_exists() {
  local repo="$1"
  local ref="$2"
  local rc=0
  git_ro -C "$repo" show-ref --verify --quiet "$ref" || rc=$?
  if (( rc == 0 )); then
    return 0
  elif (( rc == 1 )); then
    return 1
  else
    die_env "Falha inesperada ao consultar referência Git '${ref}' (código ${rc})."
  fi
}

git_is_ancestor() {
  local repo="$1"
  local ancestor="$2"
  local descendant="$3"
  local rc=0
  git_ro -C "$repo" merge-base --is-ancestor "$ancestor" "$descendant" || rc=$?
  if (( rc == 0 )); then
    return 0
  elif (( rc == 1 )); then
    return 1
  else
    die_env "Falha inesperada ao verificar ancestralidade Git entre '${ancestor}' e '${descendant}' (código ${rc})."
  fi
}

# ------------------------------------------------------------------------------
# Ajuda de linha de comando (PT-BR)
# ------------------------------------------------------------------------------

print_usage() {
  cat <<'EOF'
Uso:
  ./scripts/task-gate.sh <comando> [opções]

Comandos disponíveis (todos estritamente READ-ONLY):
  preflight --slug <slug> --expected-base <sha>
            [--protected-hash <sha256> [--protected-file <caminho>]...]
      Valida a worktree atribuída antes da implementação: caminho centralizado,
      metadados Git reais, branch agent/neon/<slug>, HEAD igual ao SHA-base,
      árvore limpa, staging vazio, ausência de untracked, identificação da
      worktree principal main e, quando informado, hash protegido e identidade
      exata dos arquivos locais protegidos na main.

  check [--base <ref>]
      Audita o conjunto completo de alterações (commitadas desde o merge-base
      da referência-base, staged, unstaged, untracked não ignorados, renames e
      deleções): integridade de diff, caminhos sensíveis, padrões conservadores
      de credenciais (saída redigida), imutabilidade de migrations Flyway
      históricas, novas ocorrências de supressão de diagnósticos fora de `.md`,
      validação do subconjunto documentado de links Markdown e resumo de módulos.

  check-commit-msg (--message "<mensagem>" | --file <arquivo> [--no-strip-comments])
      Valida estruturalmente a mensagem segundo Conventional Commits (tipos
      permitidos: feat, fix, docs, style, refactor, perf, test, build, ci, chore).
      A abertura de `--file` usa descritor seguro (O_NOFOLLOW | O_NONBLOCK,
      fstat S_ISREG, limite estrito de 64 KiB) sem imprimir conteúdo bruto.
      Por padrão `--file` ignora comentários iniciados por `#` (compatível com
      COMMIT_EDITMSG); use `--no-strip-comments` para validar mensagens literais
      extraídas de `git log` sem descartar linhas iniciadas por `#`.
      A conformidade com português brasileiro exige revisão humana.

  pre-merge --slug <slug>
            [--protected-hash <sha256> [--protected-file <caminho>]...]
      Avalia (de forma estritamente informativa, sem executar merge) a
      elegibilidade de integração por fast-forward da branch agent/neon/<slug>
      na main local, incluindo divergência, colisões de caminho, estado da main
      e validação explícita do hash protegido e da identidade/quantidade exata
      dos arquivos protegidos quando a main estiver suja.

Opções gerais:
  -h, --help    Exibe esta ajuda e encerra com código 0.

Segurança de ambiente:
  Qualquer variável de ambiente `GIT_*` externa fora da allowlist mínima de
  UX/lock (`GIT_OPTIONAL_LOCKS`, `GIT_PAGER`, `GIT_EDITOR`,
  `GIT_TERMINAL_PROMPT` — todas neutralizadas internamente) é rejeitada com
  código 3 antes da primeira execução do Git.

Códigos de saída:
  0  Verificação aprovada
  2  Erro de uso ou argumentos inválidos
  3  Bloqueio de governança, integridade, ambiente Git redirecionado ou não verificável
  4  Erro de ambiente, dependência, versão incompatível ou falha Git
EOF
}

# ------------------------------------------------------------------------------
# Verificação de requisitos do ambiente e validações de entrada
# ------------------------------------------------------------------------------

check_git_version() {
  local version_out="" rc=0
  version_out="$(git_ro --version 2>&1)" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao consultar a versão do Git (código ${rc}): ${version_out}"
  fi

  local ver_regex='git version ([0-9]+)\.([0-9]+)'
  local LC_ALL=C
  if ! [[ "$version_out" =~ $ver_regex ]]; then
    die_env "Não foi possível extrair a versão numérica do Git de: '${version_out}'."
  fi

  local git_major=$((10#${BASH_REMATCH[1]}))
  local git_minor=$((10#${BASH_REMATCH[2]}))
  if (( git_major < MIN_GIT_MAJOR )) \
    || (( git_major == MIN_GIT_MAJOR && git_minor < MIN_GIT_MINOR )); then
    die_env "Git ${MIN_GIT_MAJOR}.${MIN_GIT_MINOR} ou superior é obrigatório (encontrado: ${git_major}.${git_minor} em '${version_out}')."
  fi
}

check_runtime_requirements() {
  if [[ -z "${BASH_VERSINFO[0]:-}" ]] \
    || (( BASH_VERSINFO[0] < MIN_BASH_MAJOR )) \
    || (( BASH_VERSINFO[0] == MIN_BASH_MAJOR && BASH_VERSINFO[1] < MIN_BASH_MINOR )); then
    die_env "Bash ${MIN_BASH_MAJOR}.${MIN_BASH_MINOR} ou superior é obrigatório (encontrado: ${BASH_VERSION:-desconhecido})."
  fi

  local cmd
  for cmd in git python3 realpath stat id sha256sum; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      die_env "Dependência obrigatória não encontrada no PATH: ${cmd}"
    fi
  done

  check_git_version
}

validate_slug() {
  local slug="$1"
  if [[ -z "$slug" ]]; then
    die_usage "O valor de --slug não pode ser vazio."
  fi

  local slug_regex='^[abcdefghijklmnopqrstuvwxyz0123456789]([abcdefghijklmnopqrstuvwxyz0123456789-]{0,61}[abcdefghijklmnopqrstuvwxyz0123456789])?$'
  if ! matches_c_regex "$slug" "$slug_regex"; then
    die_usage "Slug inválido: '${slug}'. Use apenas ASCII minúsculo (a-z), dígitos (0-9) e hífen interno (1 a 63 caracteres)."
  fi

  local ref_rc=0
  git_ro check-ref-format "refs/heads/${BRANCH_PREFIX}${slug}" >/dev/null 2>&1 || ref_rc=$?
  if (( ref_rc == 1 )); then
    die_usage "Slug inválido para branch Git: '${BRANCH_PREFIX}${slug}'."
  elif (( ref_rc != 0 )); then
    die_env "Falha inesperada ao validar formato de branch via 'git check-ref-format' (código ${ref_rc})."
  fi
}

validate_sha_format() {
  local sha="$1"
  local opt_name="$2"
  local sha_regex='^[0123456789abcdefABCDEF]{7,64}$'
  if ! matches_c_regex "$sha" "$sha_regex"; then
    die_usage "Valor inválido para ${opt_name}: '${sha}'. Informe um SHA hexadecimal explícito (7 a 64 caracteres)."
  fi
}

validate_sha256_format() {
  local hash="$1"
  local hash_regex='^[0123456789abcdefABCDEF]{64}$'
  if ! matches_c_regex "$hash" "$hash_regex"; then
    die_usage "Valor inválido para --protected-hash: informe um hash SHA-256 hexadecimal de 64 caracteres."
  fi
}

validate_protected_file_path() {
  local p="$1"
  if [[ -z "$p" ]]; then
    die_usage "O valor de --protected-file não pode ser vazio."
  fi
  if [[ "$p" == /* || "$p" == *"//"* || "$p" == *"\\"* ]]; then
    die_usage "Caminho inválido em --protected-file: '${p}'. Use caminho relativo ao repositório com '/'."
  fi
  local part="" parts=()
  IFS='/' read -r -a parts <<< "$p"
  for part in "${parts[@]}"; do
    if [[ -z "$part" || "$part" == "." || "$part" == ".." ]]; then
      die_usage "Caminho inválido ou traversal em --protected-file: '${p}'."
    fi
  done
}

check_no_ancestor_symlinks() {
  local path="$1"
  if [[ "$path" != /* ]]; then
    die_blocked "Caminho esperado deve ser absoluto: '${path}'."
  fi

  local remainder="${path#/}"
  local current="" part="" parts=()
  IFS='/' read -r -a parts <<< "$remainder"
  for part in "${parts[@]}"; do
    if [[ -z "$part" || "$part" == "." || "$part" == ".." ]]; then
      die_blocked "Caminho contém componente inválido ou traversal: '${path}'."
    fi
    current="${current}/${part}"
    if [[ -L "$current" ]]; then
      die_blocked "Symlink detectado no caminho ou diretório ancestral: '${current}'."
    fi
    if [[ -e "$current" && ! -d "$current" ]]; then
      die_blocked "Componente existente não é diretório: '${current}'."
    fi
  done
}

validate_base_worktree_dir() {
  local home_raw="${HOME:-}"
  if [[ -z "$home_raw" ]]; then
    die_blocked "Variável de ambiente HOME não está definida."
  fi

  local home_clean="${home_raw%/}"
  if [[ -z "$home_clean" || "$home_clean" != /* ]]; then
    die_blocked "Variável HOME inválida: '${home_raw}'."
  fi

  local base="${home_clean}/${WORKTREE_SUBDIR}"
  local ascii_path_regex='^/[ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._/-]+$'
  if ! matches_c_regex "$base" "$ascii_path_regex" || [[ "$base" == *"//"* ]]; then
    die_blocked "Diretório-base das worktrees não é estritamente ASCII sem espaços ou acentos: '${base}'."
  fi

  check_no_ancestor_symlinks "$base"

  local current_uid=""
  current_uid="$(id -u)" || die_env "Falha ao consultar UID do usuário atual via 'id -u'."
  local d
  for d in "$home_clean" "${home_clean}/source" "$base"; do
    if [[ -e "$d" ]]; then
      local dir_uid=""
      dir_uid="$(stat -c '%u' -- "$d")" || die_env "Falha ao consultar proprietário de '${d}'."
      if [[ "$dir_uid" != "$current_uid" ]]; then
        die_blocked "Diretório '${d}' pertence ao UID ${dir_uid}, divergente do usuário atual (${current_uid})."
      fi
    fi
  done

  BASE_WORKTREE_DIR="$base"
}

# ------------------------------------------------------------------------------
# Resolução de contexto Git e status de worktree (READ-ONLY)
# ------------------------------------------------------------------------------

load_worktrees_list() {
  WT_PATHS=()
  WT_HEADS=()
  WT_BRANCHES=()
  WT_DETACHED=()
  WT_BARE=()
  WT_LOCKED=()
  WT_PRUNABLE=()
  WT_IS_MAIN=()

  local wt_path="" wt_head="" wt_branch="" wt_detached=0 wt_bare=0 wt_locked=0 wt_prunable=0
  local has_record=0 index=0 line="" fd="" pid="" rc=0

  exec {fd}< <(trap - ERR; git_ro worktree list --porcelain -z)
  pid=$!

  while IFS= read -r -u "$fd" -d '' line; do
    if [[ -z "$line" ]]; then
      if (( has_record == 1 )); then
        WT_PATHS+=("$wt_path")
        WT_HEADS+=("$wt_head")
        WT_BRANCHES+=("$wt_branch")
        WT_DETACHED+=("$wt_detached")
        WT_BARE+=("$wt_bare")
        WT_LOCKED+=("$wt_locked")
        WT_PRUNABLE+=("$wt_prunable")
        WT_IS_MAIN+=($(( index == 0 ? 1 : 0 )))
        index=$((index + 1))
        wt_path="" wt_head="" wt_branch="" wt_detached=0 wt_bare=0 wt_locked=0 wt_prunable=0
        has_record=0
      fi
      continue
    fi
    has_record=1
    case "$line" in
      "worktree "*) wt_path="${line#worktree }" ;;
      "HEAD "*)     wt_head="${line#HEAD }" ;;
      "branch "*)   wt_branch="${line#branch }" ;;
      "detached")   wt_detached=1 ;;
      "bare")       wt_bare=1 ;;
      "locked"|"locked "*)     wt_locked=1 ;;
      "prunable"|"prunable "*) wt_prunable=1 ;;
      *) ;;
    esac
  done

  exec {fd}<&-
  wait "$pid" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao consultar 'git worktree list --porcelain -z' (código ${rc})."
  fi

  if (( has_record == 1 )); then
    WT_PATHS+=("$wt_path")
    WT_HEADS+=("$wt_head")
    WT_BRANCHES+=("$wt_branch")
    WT_DETACHED+=("$wt_detached")
    WT_BARE+=("$wt_bare")
    WT_LOCKED+=("$wt_locked")
    WT_PRUNABLE+=("$wt_prunable")
    WT_IS_MAIN+=($(( index == 0 ? 1 : 0 )))
  fi

  if (( ${#WT_PATHS[@]} == 0 )); then
    die_env "Nenhuma worktree retornada pelo Git."
  fi
}

resolve_repo_context() {
  if ! git_ro rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    die_env "Execute este utilitário dentro de uma worktree válida do repositório Git."
  fi

  local toplevel_raw="" common_raw=""
  toplevel_raw="$(git_ro rev-parse --show-toplevel)" || die_env "Falha ao resolver a raiz da worktree atual."
  common_raw="$(git_ro rev-parse --path-format=absolute --git-common-dir)" || die_env "Falha ao resolver o diretório Git comum."

  CURRENT_TOPLEVEL="$(realpath -e -- "$toplevel_raw")" || die_env "Falha ao normalizar a raiz da worktree atual."
  realpath -e -- "$common_raw" >/dev/null || die_env "Falha ao normalizar o diretório Git comum."

  load_worktrees_list

  MAIN_WORKTREE_PATH="${WT_PATHS[0]}"
  if (( WT_BARE[0] == 0 )) && [[ -d "$MAIN_WORKTREE_PATH" ]]; then
    MAIN_WORKTREE_REALPATH="$(realpath -e -- "$MAIN_WORKTREE_PATH" 2>/dev/null || true)"
  else
    MAIN_WORKTREE_REALPATH=""
  fi
}

inspect_worktree_status() {
  local wt_path="$1"

  STATUS_TRACKED_PATHS=()
  STATUS_STAGED_PATHS=()
  STATUS_UNSTAGED_PATHS=()
  STATUS_UNSTAGED_CODES=()
  STATUS_UNTRACKED_PATHS=()
  LAST_INSPECT_ERROR=""

  local entry="" x="" y="" path=""
  local fd="" pid="" rc=0

  exec {fd}< <(trap - ERR; git_ro -C "$wt_path" status --porcelain=v1 -z --untracked-files=all 2>/dev/null)
  pid=$!

  while IFS= read -r -u "$fd" -d '' entry; do
    if (( ${#entry} < 4 )); then
      exec {fd}<&-
      wait "$pid" || true
      LAST_INSPECT_ERROR="saída inesperada de 'git status --porcelain=v1 -z'"
      return 1
    fi

    x="${entry:0:1}"
    y="${entry:1:1}"
    path="${entry:3}"

    if [[ "$x" == "R" || "$x" == "C" ]]; then
      IFS= read -r -u "$fd" -d '' _ || true
    fi

    if [[ "$x" == "?" && "$y" == "?" ]]; then
      STATUS_UNTRACKED_PATHS+=("$path")
      continue
    fi

    STATUS_TRACKED_PATHS+=("$path")
    if [[ "$x" != " " ]]; then
      STATUS_STAGED_PATHS+=("$path")
    fi
    if [[ "$y" != " " ]]; then
      STATUS_UNSTAGED_PATHS+=("$path")
      STATUS_UNSTAGED_CODES+=("${x}${y}")
    fi
  done

  exec {fd}<&-
  wait "$pid" || rc=$?
  if (( rc != 0 )); then
    LAST_INSPECT_ERROR="falha ao consultar 'git status' (código ${rc})"
    return 1
  fi
  return 0
}

# Calcula o hash SHA-256 determinístico do diff unstaged da main sem jamais imprimir conteúdo,
# neutralizando external diff, cores e configurações externas.
compute_main_diff_sha256() {
  local main_path="$1"
  local hash_out="" rc=0
  hash_out="$(git_ro -C "$main_path" diff --no-ext-diff --no-color | sha256sum)" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao calcular SHA-256 determinístico do diff da worktree principal (código ${rc})."
  fi
  printf '%s' "${hash_out%% *}"
}

# Valida de forma unificada (F3-F2) a identidade exata, quantidade, estado Git e hash
# do conjunto de arquivos locais protegidos na worktree principal `main`.
# Uso: validate_main_protected_state <main_path> <hash_set:0|1> <expected_hash> <protected_files...>
# Preenche PROTECTED_CHECK_HASH e PROTECTED_CHECK_BLOCKERS.
validate_main_protected_state() {
  local main_path="$1"
  local hash_set="$2"
  local expected_hash="$3"
  shift 3
  local expected_files=("$@")

  PROTECTED_CHECK_HASH=""
  PROTECTED_CHECK_BLOCKERS=()

  if ! inspect_worktree_status "$main_path"; then
    PROTECTED_CHECK_BLOCKERS+=("Não foi possível inspecionar o status da worktree principal: ${LAST_INSPECT_ERROR}.")
    return 1
  fi

  local main_staged_count="${#STATUS_STAGED_PATHS[@]}"
  local main_unstaged_count="${#STATUS_UNSTAGED_PATHS[@]}"
  local main_untracked_count="${#STATUS_UNTRACKED_PATHS[@]}"

  if (( main_staged_count > 0 )); then
    PROTECTED_CHECK_BLOCKERS+=("Worktree principal (${MAIN_BRANCH}) possui ${main_staged_count} arquivo(s) em staging.")
  fi
  if (( main_untracked_count > 0 )); then
    PROTECTED_CHECK_BLOCKERS+=("Worktree principal (${MAIN_BRANCH}) possui ${main_untracked_count} arquivo(s) untracked inesperados.")
  fi

  # Verifica que todas as alterações unstaged na main são estritamente modificações (' M'),
  # bloqueando deleções (' D'), alterações de tipo (' T') ou estados de conflito.
  local idx
  for (( idx = 0; idx < main_unstaged_count; idx++ )); do
    if [[ "${STATUS_UNSTAGED_CODES[idx]}" != " M" ]]; then
      PROTECTED_CHECK_BLOCKERS+=("Estado Git incompatível na '${MAIN_BRANCH}' para '${STATUS_UNSTAGED_PATHS[idx]}': código '${STATUS_UNSTAGED_CODES[idx]}' (esperado ' M').")
    fi
  done

  PROTECTED_CHECK_HASH="$(compute_main_diff_sha256 "$main_path")"

  # Quando a main está suja (unstaged > 0), exige tanto --protected-hash quanto a lista
  # explícita de --protected-file cobrindo exatamente quantidade e identidade dos caminhos.
  if (( main_unstaged_count > 0 )); then
    if (( hash_set == 0 )); then
      PROTECTED_CHECK_BLOCKERS+=("A '${MAIN_BRANCH}' está suja (${main_unstaged_count} arquivo(s) unstaged) e --protected-hash não foi informado para validação explícita.")
    fi
    if (( ${#expected_files[@]} == 0 )); then
      PROTECTED_CHECK_BLOCKERS+=("A '${MAIN_BRANCH}' está suja (${main_unstaged_count} arquivo(s) unstaged), mas nenhum --protected-file foi declarado para validar identidade e quantidade.")
    fi
  fi

  # Valida identidade e quantidade sempre que --protected-file for informado ou main estiver suja com lista
  if (( ${#expected_files[@]} > 0 )); then
    if (( main_unstaged_count != ${#expected_files[@]} )); then
      PROTECTED_CHECK_BLOCKERS+=("Quantidade de arquivos protegidos na '${MAIN_BRANCH}' diverge (esperado: ${#expected_files[@]}, observado: ${main_unstaged_count}).")
    fi

    local ef obs found
    for ef in "${expected_files[@]}"; do
      found=0
      for obs in "${STATUS_UNSTAGED_PATHS[@]}"; do
        if [[ "$ef" == "$obs" ]]; then
          found=1
          break
        fi
      done
      if (( found == 0 )); then
        PROTECTED_CHECK_BLOCKERS+=("Arquivo protegido esperado ausente nas alterações unstaged da '${MAIN_BRANCH}': '${ef}'.")
      fi
    done

    for obs in "${STATUS_UNSTAGED_PATHS[@]}"; do
      found=0
      for ef in "${expected_files[@]}"; do
        if [[ "$obs" == "$ef" ]]; then
          found=1
          break
        fi
      done
      if (( found == 0 )); then
        PROTECTED_CHECK_BLOCKERS+=("Arquivo alterado não esperado na '${MAIN_BRANCH}' (ausente de --protected-file): '${obs}'.")
      fi
    done
  fi

  if (( hash_set == 1 )); then
    local LC_ALL=C
    if [[ "${PROTECTED_CHECK_HASH,,}" != "${expected_hash,,}" ]]; then
      PROTECTED_CHECK_BLOCKERS+=("Hash protegido da '${MAIN_BRANCH}' diverge (esperado: ${expected_hash,,}, observado: ${PROTECTED_CHECK_HASH,,}).")
    fi
  fi

  if (( ${#PROTECTED_CHECK_BLOCKERS[@]} > 0 )); then
    return 1
  fi
  return 0
}

# Classificação rápida de caminho sensível no lado Bash (usada antes de abrir --file)
is_sensitive_path_bash() {
  local p="$1"
  local base="${p##*/}"
  local LC_ALL=C
  local lower="${base,,}"
  case "$lower" in
    .env.example|.env.sample|.env.template)
      return 1
      ;;
    .env|.env.*|.envrc|secrets.env|*.env|\
    *.jks|*.keystore|*.pem|*.crt|*.cer|*.der|*.key|*.p12|*.pfx|*.p8|*.ppk|*.kdbx|\
    id_rsa|id_ed25519|id_ecdsa|id_dsa|\
    .netrc|.pgpass|.npmrc|.pypirc|.htpasswd|\
    google-services.json|googleservice-info.plist|key.properties|local.properties|\
    credentials.json|credentials.yml|credentials.yaml|credentials.properties|\
    secrets.json|secrets.yml|secrets.yaml|secrets.properties|\
    client_secret*.json|service-account*.json|service_account*.json|\
    *firebase-adminsdk*.json|\
    application-secret*.yml|application-secret*.yaml|application-secret*.properties|\
    *.secret|*.secrets|*secret*.json|*secret*.yml|*secret*.yaml|\
    *credential*.json|*credential*.yml|*credential*.yaml|\
    *private*key*|*.tfstate|*.tfstate.backup|*.tfvars)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# ------------------------------------------------------------------------------
# Subcomando: PREFLIGHT
# ------------------------------------------------------------------------------

cmd_preflight() {
  local slug="" expected_base="" protected_hash=""
  local slug_set=0 base_set=0 hash_set=0
  local protected_files=()

  while (( $# > 0 )); do
    case "$1" in
      -h|--help) print_usage; exit "$EXIT_OK" ;;
      --slug)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --slug exige um valor."
        slug="$2"; slug_set=1; shift 2 ;;
      --slug=*)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        slug="${1#--slug=}"; slug_set=1; shift ;;
      --expected-base)
        (( base_set == 0 )) || die_usage "Opção --expected-base informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --expected-base exige um valor."
        expected_base="$2"; base_set=1; shift 2 ;;
      --expected-base=*)
        (( base_set == 0 )) || die_usage "Opção --expected-base informada mais de uma vez."
        expected_base="${1#--expected-base=}"; base_set=1; shift ;;
      --protected-hash)
        (( hash_set == 0 )) || die_usage "Opção --protected-hash informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --protected-hash exige um valor."
        protected_hash="$2"; hash_set=1; shift 2 ;;
      --protected-hash=*)
        (( hash_set == 0 )) || die_usage "Opção --protected-hash informada mais de uma vez."
        protected_hash="${1#--protected-hash=}"; hash_set=1; shift ;;
      --protected-file)
        (( $# >= 2 )) || die_usage "Opção --protected-file exige um caminho relativo."
        validate_protected_file_path "$2"
        local existing_pf
        for existing_pf in "${protected_files[@]}"; do
          if [[ "$existing_pf" == "$2" ]]; then
            die_usage "Caminho duplicado em --protected-file: '$2'."
          fi
        done
        protected_files+=("$2")
        shift 2 ;;
      --protected-file=*)
        local pf_val="${1#--protected-file=}"
        validate_protected_file_path "$pf_val"
        local existing_pf
        for existing_pf in "${protected_files[@]}"; do
          if [[ "$existing_pf" == "$pf_val" ]]; then
            die_usage "Caminho duplicado em --protected-file: '${pf_val}'."
          fi
        done
        protected_files+=("$pf_val")
        shift ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'preflight': $1" ;;
    esac
  done

  (( slug_set == 1 )) || die_usage "Opção obrigatória ausente em 'preflight': --slug <slug>."
  (( base_set == 1 )) || die_usage "Opção obrigatória ausente em 'preflight': --expected-base <sha>."

  validate_slug "$slug"
  validate_sha_format "$expected_base" "--expected-base"
  if (( hash_set == 1 )); then
    validate_sha256_format "$protected_hash"
  elif (( ${#protected_files[@]} > 0 )); then
    die_usage "A opção --protected-file exige --protected-hash para validação completa do conjunto protegido."
  fi

  resolve_repo_context
  validate_base_worktree_dir

  local expected_path="${BASE_WORKTREE_DIR}/${slug}"
  local expected_branch="${BRANCH_PREFIX}${slug}"
  local expected_ref="refs/heads/${expected_branch}"
  local blockers=()

  # 1. Identificação inequívoca da worktree principal main
  if (( WT_BARE[0] == 1 )) || [[ -z "$MAIN_WORKTREE_REALPATH" ]]; then
    blockers+=("Worktree principal não está acessível ou é repositório bare.")
  elif [[ -L "${MAIN_WORKTREE_PATH}/.git" ]] || [[ ! -d "${MAIN_WORKTREE_PATH}/.git" && ! -f "${MAIN_WORKTREE_PATH}/.git" ]]; then
    blockers+=("Entrada '.git' da worktree principal é inválida ou symlink.")
  elif (( WT_DETACHED[0] == 1 )) || [[ "${WT_BRANCHES[0]}" != "refs/heads/${MAIN_BRANCH}" ]]; then
    blockers+=("Worktree principal ('${MAIN_WORKTREE_PATH}') não está na branch '${MAIN_BRANCH}' (encontrada: '${WT_BRANCHES[0]:-DETACHED}').")
  fi

  # 2. Caminho real esperado e verificação de metadados Git reais
  if [[ -L "$expected_path" ]]; then
    blockers+=("Caminho esperado é um symlink: '${expected_path}'.")
  fi
  if [[ "$CURRENT_TOPLEVEL" != "$expected_path" ]]; then
    blockers+=("Caminho real atual ('${CURRENT_TOPLEVEL}') diverge do esperado ('${expected_path}').")
  fi
  if [[ -n "$MAIN_WORKTREE_REALPATH" && "$CURRENT_TOPLEVEL" == "$MAIN_WORKTREE_REALPATH" ]]; then
    blockers+=("Preflight deve ser executado na worktree isolada da tarefa, não na worktree principal.")
  fi
  if [[ -L "${CURRENT_TOPLEVEL}/.git" ]]; then
    blockers+=("Entrada '.git' na worktree atual é um symlink inesperado.")
  elif [[ -d "${CURRENT_TOPLEVEL}/.git" ]]; then
    blockers+=("Diretório atual possui '.git' como diretório (clone independente, não worktree vinculada).")
  elif [[ ! -f "${CURRENT_TOPLEVEL}/.git" ]]; then
    blockers+=("Arquivo de vínculo '.git' ausente na worktree atual.")
  fi

  # Confirma registro único e consistente em git worktree list --porcelain -z
  local match_count=0 match_idx=-1 i
  for (( i = 0; i < ${#WT_PATHS[@]}; i++ )); do
    if [[ "${WT_PATHS[i]}" == "$expected_path" ]]; then
      match_count=$((match_count + 1))
      match_idx="$i"
    fi
  done
  if (( match_count == 0 )); then
    blockers+=("Worktree '${expected_path}' não está registrada em 'git worktree list'.")
  elif (( match_count > 1 )); then
    blockers+=("Registro ambíguo em 'git worktree list' para '${expected_path}'.")
  else
    if (( WT_IS_MAIN[match_idx] == 1 )); then
      blockers+=("Registro encontrado corresponde à worktree principal, não à worktree da tarefa.")
    fi
    if (( WT_PRUNABLE[match_idx] == 1 )); then
      blockers+=("Worktree '${expected_path}' está marcada como prunable no Git.")
    fi
    if (( WT_DETACHED[match_idx] == 1 )) || [[ "${WT_BRANCHES[match_idx]}" != "$expected_ref" ]]; then
      blockers+=("Metadados Git registram branch '${WT_BRANCHES[match_idx]:-DETACHED}' em vez de '${expected_ref}'.")
    fi
  fi

  # 3. Branch ativa da worktree atual
  local actual_ref="" sym_rc=0
  actual_ref="$(git_ro -C "$CURRENT_TOPLEVEL" symbolic-ref --quiet HEAD 2>/dev/null)" || sym_rc=$?
  if (( sym_rc != 0 )); then
    blockers+=("Worktree atual está em DETACHED HEAD (esperada '${expected_branch}').")
  elif [[ "$actual_ref" != "$expected_ref" ]]; then
    blockers+=("Branch ativa '${actual_ref#refs/heads/}' diverge da esperada '${expected_branch}'.")
  fi

  # 4. HEAD igual ao SHA-base informado
  local resolved_expected_sha="" actual_head=""
  if ! resolved_expected_sha="$(git_ro -C "$CURRENT_TOPLEVEL" rev-parse --verify --quiet "${expected_base}^{commit}")"; then
    blockers+=("SHA informado em --expected-base ('${expected_base}') não existe localmente como commit válido.")
  fi
  if ! actual_head="$(git_ro -C "$CURRENT_TOPLEVEL" rev-parse --verify --quiet "HEAD^{commit}")"; then
    blockers+=("Não foi possível resolver o HEAD atual da worktree.")
  fi
  if [[ -n "$resolved_expected_sha" && -n "$actual_head" && "$actual_head" != "$resolved_expected_sha" ]]; then
    blockers+=("HEAD atual ('${actual_head}') diverge do --expected-base ('${resolved_expected_sha}').")
  fi

  # 5. Working tree limpa, staging vazio e sem untracked
  if inspect_worktree_status "$CURRENT_TOPLEVEL"; then
    if (( ${#STATUS_STAGED_PATHS[@]} > 0 )); then
      blockers+=("Existem ${#STATUS_STAGED_PATHS[@]} arquivo(s) em staging na worktree da tarefa.")
    fi
    if (( ${#STATUS_UNSTAGED_PATHS[@]} > 0 )); then
      blockers+=("Existem ${#STATUS_UNSTAGED_PATHS[@]} modificação(ões) unstaged na worktree da tarefa.")
    fi
    if (( ${#STATUS_UNTRACKED_PATHS[@]} > 0 )); then
      blockers+=("Existem ${#STATUS_UNTRACKED_PATHS[@]} arquivo(s) untracked inesperados na worktree da tarefa.")
    fi
  else
    blockers+=("Não foi possível inspecionar o status da worktree atual: ${LAST_INSPECT_ERROR}.")
  fi

  # 6. Verificação do estado protegido na main (identidade, quantidade e hash, sem conteúdo)
  local observed_hash=""
  if (( hash_set == 1 )); then
    if [[ -n "$MAIN_WORKTREE_REALPATH" ]]; then
      if ! validate_main_protected_state "$MAIN_WORKTREE_PATH" "$hash_set" "$protected_hash" "${protected_files[@]}"; then
        local pb
        for pb in "${PROTECTED_CHECK_BLOCKERS[@]}"; do
          blockers+=("$pb")
        done
      fi
      observed_hash="$PROTECTED_CHECK_HASH"
    else
      blockers+=("Não foi possível verificar --protected-hash porque a worktree principal não está acessível.")
    fi
  fi

  msg_info "=== Gate Local — Preflight (READ-ONLY) ==="
  msg_info "Worktree esperada  : ${expected_path}"
  msg_info "Worktree atual     : ${CURRENT_TOPLEVEL}"
  msg_info "Branch esperada    : ${expected_branch}"
  msg_info "HEAD observado     : ${actual_head:-INDISPONÍVEL}"
  msg_info "Worktree principal : ${MAIN_WORKTREE_PATH:-INDISPONÍVEL}"
  if (( hash_set == 1 )); then
    msg_info "Hash protegido     : ${observed_hash:-INDISPONÍVEL}"
    msg_info "Arquivos declarados: ${#protected_files[@]}"
  fi

  if (( ${#blockers[@]} > 0 )); then
    local b
    for b in "${blockers[@]}"; do
      msg_blocked "$b"
    done
    exit "$EXIT_BLOCKED"
  fi

  msg_ok "Preflight da tarefa '${slug}' aprovado integralmente."
  exit "$EXIT_OK"
}

# ------------------------------------------------------------------------------
# Subcomando: CHECK
# ------------------------------------------------------------------------------

cmd_check() {
  local base_ref="" base_set=0

  while (( $# > 0 )); do
    case "$1" in
      -h|--help) print_usage; exit "$EXIT_OK" ;;
      --base)
        (( base_set == 0 )) || die_usage "Opção --base informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --base exige um valor."
        base_ref="$2"; base_set=1; shift 2 ;;
      --base=*)
        (( base_set == 0 )) || die_usage "Opção --base informada mais de uma vez."
        base_ref="${1#--base=}"; base_set=1; shift ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'check': $1" ;;
    esac
  done

  resolve_repo_context

  if (( base_set == 0 )); then
    base_ref="refs/heads/${MAIN_BRANCH}"
  fi

  local base_commit=""
  if ! base_commit="$(git_ro -C "$CURRENT_TOPLEVEL" rev-parse --verify --quiet "${base_ref}^{commit}")"; then
    die_blocked "Referência-base '${base_ref}' não existe localmente como commit válido."
  fi

  local merge_base="" mb_rc=0
  merge_base="$(git_ro -C "$CURRENT_TOPLEVEL" merge-base "$base_commit" HEAD 2>/dev/null)" || mb_rc=$?
  if (( mb_rc == 1 )) || [[ -z "$merge_base" ]]; then
    die_blocked "Não há ancestral comum (merge-base) entre '${base_ref}' e HEAD."
  elif (( mb_rc != 0 )); then
    die_env "Falha inesperada ao calcular merge-base com '${base_ref}' (código ${mb_rc})."
  fi

  local py_rc=0
  python3 - "$CURRENT_TOPLEVEL" "$base_ref" "$merge_base" <<'PYEOF' || py_rc=$?
import fnmatch
import os
import re
import subprocess
import sys
import urllib.parse

repo_root = sys.argv[1]
base_ref = sys.argv[2]
merge_base = sys.argv[3]

EXIT_OK = 0
EXIT_BLOCKED = 3
EXIT_ENV = 4

MIGRATION_PREFIX = "backend/src/main/resources/db/migration/"
SUPPRESS_TOKEN = "@" + "SuppressWarnings"

blockers = []
unverifiables = []
warnings = []


def run_git(*args):
    env = os.environ.copy()
    # Defesa em profundidade: remove quaisquer GIT_* e isola configs externas
    for k in list(env.keys()):
        if k.startswith("GIT_"):
            del env[k]
    env["LC_ALL"] = "C"
    env["GIT_OPTIONAL_LOCKS"] = "0"
    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
    env["GIT_CONFIG_SYSTEM"] = "/dev/null"
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    p = subprocess.run(
        [
            "git",
            "-c", "core.quotePath=false",
            "-c", "color.ui=never",
            "-c", "color.diff=never",
            "-c", "diff.external=",
            "-c", "diff.noprefix=false",
            "-c", "diff.mnemonicPrefix=false",
            "-C", repo_root,
            *args,
        ],
        capture_output=True,
        env=env,
    )
    return p.returncode, p.stdout, p.stderr


def is_sensitive_path(rel_path):
    base = os.path.basename(rel_path).lower()
    if base in (".env.example", ".env.sample", ".env.template"):
        return None
    if base == ".env" or base.startswith(".env."):
        return "arquivo de variáveis de ambiente (.env / .env.*)"
    if base == ".envrc":
        return "arquivo direnv (.envrc)"
    if base == "secrets.env" or base.endswith(".env"):
        return "arquivo de segredos de ambiente (*.env)"
    ext_map = [
        (".jks", "keystore Java (*.jks)"),
        (".keystore", "keystore de assinatura (*.keystore)"),
        (".pem", "certificado ou chave privada (*.pem)"),
        (".crt", "certificado digital (*.crt)"),
        (".cer", "certificado digital (*.cer)"),
        (".der", "certificado ou chave DER (*.der)"),
        (".key", "chave privada (*.key)"),
        (".p12", "chave/certificado PKCS#12 (*.p12)"),
        (".pfx", "chave/certificado PKCS#12 (*.pfx)"),
        (".p8", "chave privada (*.p8)"),
        (".ppk", "chave privada SSH PuTTY (*.ppk)"),
        (".kdbx", "cofre de senhas (*.kdbx)"),
        (".secret", "arquivo de segredo (*.secret)"),
        (".secrets", "arquivo de segredos (*.secrets)"),
        (".tfstate", "estado Terraform (*.tfstate)"),
        (".tfstate.backup", "backup de estado Terraform (*.tfstate.backup)"),
        (".tfvars", "variáveis Terraform (*.tfvars)"),
    ]
    for ext, reason in ext_map:
        if base.endswith(ext):
            return reason
    exact_map = {
        "id_rsa": "chave privada SSH conhecida",
        "id_ed25519": "chave privada SSH conhecida",
        "id_ecdsa": "chave privada SSH conhecida",
        "id_dsa": "chave privada SSH conhecida",
        ".netrc": "arquivo de credenciais (.netrc)",
        ".pgpass": "arquivo de credenciais PostgreSQL (.pgpass)",
        ".npmrc": "arquivo de credenciais npm (.npmrc)",
        ".pypirc": "arquivo de credenciais PyPI (.pypirc)",
        ".htpasswd": "arquivo de senhas HTTP (.htpasswd)",
        "google-services.json": "arquivo de credenciais mobile (google-services.json)",
        "googleservice-info.plist": "arquivo de credenciais mobile (GoogleService-Info.plist)",
        "key.properties": "credenciais de assinatura Android (key.properties)",
        "local.properties": "propriedades locais potencialmente sensíveis (local.properties)",
        "credentials.json": "arquivo de credenciais conhecido",
        "credentials.yml": "arquivo de credenciais conhecido",
        "credentials.yaml": "arquivo de credenciais conhecido",
        "credentials.properties": "arquivo de credenciais conhecido",
        "secrets.json": "arquivo de segredos conhecido",
        "secrets.yml": "arquivo de segredos conhecido",
        "secrets.yaml": "arquivo de segredos conhecido",
        "secrets.properties": "arquivo de segredos conhecido",
    }
    if base in exact_map:
        return exact_map[base]
    glob_patterns = [
        "client_secret*.json",
        "service-account*.json",
        "service_account*.json",
        "*firebase-adminsdk*.json",
        "application-secret*.yml",
        "application-secret*.yaml",
        "application-secret*.properties",
        "*secret*.json",
        "*secret*.yml",
        "*secret*.yaml",
        "*credential*.json",
        "*credential*.yml",
        "*credential*.yaml",
        "*private*key*",
    ]
    for pat in glob_patterns:
        if fnmatch.fnmatch(base, pat):
            return f"arquivo de credenciais/segredos conhecido ({base})"
    return None


def parse_name_status_z(data):
    tokens = data.split(b"\0")
    if tokens and tokens[-1] == b"":
        tokens.pop()
    entries = []
    i = 0
    while i < len(tokens):
        status = tokens[i].decode("utf-8", "surrogateescape")
        i += 1
        if not status:
            continue
        if status.startswith(("R", "C")):
            if i + 1 >= len(tokens):
                break
            p1 = tokens[i].decode("utf-8", "surrogateescape")
            p2 = tokens[i + 1].decode("utf-8", "surrogateescape")
            i += 2
            entries.append((status, p1, p2))
        else:
            if i >= len(tokens):
                break
            p1 = tokens[i].decode("utf-8", "surrogateescape")
            i += 1
            entries.append((status, p1, None))
    return entries


def parse_numstat_binaries(data):
    tokens = data.split(b"\0")
    if tokens and tokens[-1] == b"":
        tokens.pop()
    bins = set()
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        i += 1
        parts = tok.split(b"\t", 2)
        if len(parts) < 3:
            continue
        added, deleted, rest = parts
        if rest == b"":
            if i + 1 < len(tokens):
                new_p = tokens[i + 1].decode("utf-8", "surrogateescape")
                i += 2
                if added == b"-" and deleted == b"-":
                    bins.add(new_p)
        else:
            path = rest.decode("utf-8", "surrogateescape")
            if added == b"-" and deleted == b"-":
                bins.add(path)
    return bins


# 1. Coleta das 5 categorias de alterações
layers = {}
for name, git_args in [
    ("committed", ("diff", "--no-ext-diff", "--no-color", "--name-status", "-z", "-M", "-C", merge_base, "HEAD")),
    ("staged", ("diff", "--no-ext-diff", "--no-color", "--cached", "--name-status", "-z", "-M", "-C")),
    ("unstaged", ("diff", "--no-ext-diff", "--no-color", "--name-status", "-z", "-M", "-C")),
]:
    rc, out, err = run_git(*git_args)
    if rc != 0:
        sys.stderr.write(f"ERRO: falha ao coletar alterações ({name}): {err.decode('utf-8', 'replace')}\n")
        sys.exit(EXIT_ENV)
    layers[name] = parse_name_status_z(out)

rc, out, err = run_git("ls-files", "--others", "--exclude-standard", "-z")
if rc != 0:
    sys.stderr.write(f"ERRO: falha ao listar arquivos untracked: {err.decode('utf-8', 'replace')}\n")
    sys.exit(EXIT_ENV)
untracked_paths = [
    t.decode("utf-8", "surrogateescape") for t in out.split(b"\0") if t
]

all_touched_paths = set()
for entries in layers.values():
    for st, p1, p2 in entries:
        all_touched_paths.add(p1)
        if p2:
            all_touched_paths.add(p2)
for p in untracked_paths:
    all_touched_paths.add(p)

# 2. Verificação B — Arquivos sensíveis (sem abrir nem ler conteúdo)
sensitive_paths = set()
for p in sorted(all_touched_paths):
    reason = is_sensitive_path(p)
    if reason is not None:
        sensitive_paths.add(p)
        blockers.append(f"Caminho sensível bloqueado: '{p}' — {reason}")

# 3. Detecção de binários nos diffs
binary_paths = set()
for name, git_args in [
    ("committed", ("diff", "--no-ext-diff", "--no-color", "--numstat", "-z", "-M", "-C", merge_base, "HEAD")),
    ("staged", ("diff", "--no-ext-diff", "--no-color", "--numstat", "-z", "-M", "-C")),
    ("unstaged", ("diff", "--no-ext-diff", "--no-color", "--numstat", "-z", "-M", "-C")),
]:
    rc, out, err = run_git(*git_args)
    if rc != 0:
        sys.stderr.write(f"ERRO: falha ao consultar --numstat ({name}): {err.decode('utf-8', 'replace')}\n")
        sys.exit(EXIT_ENV)
    binary_paths.update(parse_numstat_binaries(out))

untracked_text_paths = []
untracked_binary_paths = set()
for p in sorted(untracked_paths):
    if p in sensitive_paths:
        continue
    abs_p = os.path.join(repo_root, p)
    if os.path.islink(abs_p):
        unverifiables.append(f"Symlink não seguido para inspeção de conteúdo: '{p}'")
        continue
    if not os.path.isfile(abs_p):
        unverifiables.append(f"Entrada untracked não é arquivo regular legível: '{p}'")
        continue
    try:
        with open(abs_p, "rb") as f:
            sample = f.read(8192)
    except OSError:
        unverifiables.append(f"Arquivo untracked ilegível (erro de permissão/I/O): '{p}'")
        continue
    if b"\0" in sample:
        untracked_binary_paths.add(p)
    else:
        untracked_text_paths.append(p)

all_binary_paths = (binary_paths | untracked_binary_paths) - sensitive_paths
for bp in sorted(all_binary_paths):
    unverifiables.append(f"Arquivo binário não verificável textualmente: '{bp}'")

for p in sorted(all_touched_paths - set(untracked_paths) - sensitive_paths):
    abs_p = os.path.join(repo_root, p)
    if os.path.islink(abs_p):
        unverifiables.append(f"Symlink rastreado não seguido para inspeção de conteúdo: '{p}'")

# 4. Verificação A — Integridade do diff (`git diff --check`) — F3-F1
# Ordena caminhos conhecidos do mais longo para o mais curto para desambiguar nomes com `:`
known_paths_by_len = sorted(all_touched_paths, key=len, reverse=True)
DIFF_CHECK_FALLBACK_RE = re.compile(r"^(.+):(\d+):\s*(.+)$")


def parse_diff_check_line(line):
    """Separa (caminho, numero_linha, descricao) lidando com `:` no nome do arquivo."""
    for p in known_paths_by_len:
        prefix = p + ":"
        if line.startswith(prefix):
            rest = line[len(prefix):]
            m = re.match(r"^(\d+):\s*(.+)$", rest)
            if m:
                return p, m.group(1), m.group(2).strip()
    m = DIFF_CHECK_FALLBACK_RE.match(line)
    if m:
        return m.group(1), m.group(2), m.group(3).strip()
    return None


for label, git_args in [
    ("commitadas", ("diff", "--no-ext-diff", "--no-color", "--check", merge_base, "HEAD")),
    ("staged", ("diff", "--no-ext-diff", "--no-color", "--cached", "--check")),
    ("unstaged", ("diff", "--no-ext-diff", "--no-color", "--check")),
]:
    rc, out, err = run_git(*git_args)
    if rc == 0:
        continue
    if rc == 2:
        lines = out.decode("utf-8", "replace").splitlines()
        reported = set()
        added_any_blocker = False
        saw_sensitive_only = False
        expect_context_line = False
        for ln in lines:
            if not ln.strip():
                expect_context_line = False
                continue
            # Linha de conteúdo `+...` exibida pelo Git imediatamente após um diagnóstico
            # (omitida para nunca vazar conteúdo potencialmente sensível)
            if expect_context_line and ln.startswith("+"):
                expect_context_line = False
                continue
            parsed = parse_diff_check_line(ln)
            if parsed is not None:
                fp, lno, desc = parsed
                expect_context_line = True
                if fp in sensitive_paths:
                    saw_sensitive_only = True
                    continue
                key = (label, fp, lno, desc)
                if key not in reported:
                    reported.add(key)
                    blockers.append(f"Integridade de diff ({label}) em '{fp}:{lno}': {desc}")
                    added_any_blocker = True
            else:
                expect_context_line = False
                blockers.append(
                    f"Integridade de diff ({label}): diagnóstico de 'git diff --check' em formato não parseável (conteúdo omitido por segurança)"
                )
                added_any_blocker = True
        if not added_any_blocker and not saw_sensitive_only:
            blockers.append(
                f"Integridade de diff ({label}): 'git diff --check' retornou código 2 sem detalhes parseáveis"
            )
    else:
        sys.stderr.write(
            f"ERRO: falha inesperada em 'git diff --check' ({label}, código {rc}): {err.decode('utf-8', 'replace')}\n"
        )
        sys.exit(EXIT_ENV)

# Integridade básica (trailing whitespace e marcadores de conflito) em untracked de texto
conflict_re = re.compile(r"^(<{7}|={7}|>{7})(\s|$)")
for p in untracked_text_paths:
    abs_p = os.path.join(repo_root, p)
    try:
        with open(abs_p, "r", encoding="utf-8", errors="replace") as f:
            for idx, raw_line in enumerate(f, start=1):
                line = raw_line.rstrip("\r\n")
                if conflict_re.match(line):
                    blockers.append(f"Marcador de conflito em arquivo untracked '{p}:{idx}'")
                elif line.endswith((" ", "\t")):
                    blockers.append(
                        f"Espaço em branco ao final da linha (trailing whitespace) em untracked '{p}:{idx}'"
                    )
    except OSError:
        unverifiables.append(f"Erro ao ler arquivo untracked '{p}' para verificação de integridade.")

# 5. Verificação C (Padrões de credenciais) e E (SuppressWarnings)
CRED_RULES = [
    (
        "chave privada PEM",
        re.compile("-----BEGIN " + r"(?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----"),
    ),
    (
        "AWS Access Key ID",
        re.compile(r"\b(?:A" + r"KIA|A" + r"SIA)[0-9A-Z]{16}\b"),
    ),
    (
        "token GitHub",
        re.compile(
            r"\b(?:gh" + r"p|gh" + r"o|gh" + r"u|gh" + r"s|gh" + r"r)_[A-Za-z0-9]{20,}\b|"
            r"\bgithub_" + r"pat_[A-Za-z0-9_]{20,}\b"
        ),
    ),
    (
        "chave de API Google",
        re.compile(r"\bAI" + r"za[0-9A-Za-z_-]{35}\b"),
    ),
    (
        "token Slack",
        re.compile(r"\bxo" + r"x[baprs]-[0-9A-Za-z-]{10,}\b"),
    ),
    (
        "chave secreta Stripe",
        re.compile(r"\bsk_" + r"(?:live|test)_[0-9A-Za-z]{16,}\b"),
    ),
    (
        "JWT literal suspeito",
        re.compile(r"\beyJ" + r"[A-Za-z0-9_-]{10,}\.eyJ" + r"[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
    ),
    (
        "string de conexão com credencial embutida",
        re.compile(r"(?:postgres|postgresql|mysql|mongodb(?:\+srv)?|redis|amqp|amqps)://[^/\s:@]+:[^/\s@]{4,}@"),
    ),
    (
        "atribuição literal de credencial",
        re.compile(
            r"(?i)\b(?:password|passwd|api[_-]?key|access[_-]?token|private[_-]?key|client[_-]?secret|jwt[_-]?secret)\s*[:=]\s*['\"]([^'\"\s]{8,})['\"]"
        ),
    ),
]

PLACEHOLDER_HINTS = (
    "${", "<", ">", "example", "placeholder", "changeme", "change_me",
    "your_", "dummy", "fake", "sample", "not-a-secret", "test_only",
)


def is_placeholder_match(match_text):
    low = match_text.lower()
    return any(h in low for h in PLACEHOLDER_HINTS)


cred_reported = set()
sw_reported = set()


def inspect_added_line(rel_path, line_no, text):
    for rule_name, rx in CRED_RULES:
        m = rx.search(text)
        if m:
            if rule_name in ("atribuição literal de credencial", "string de conexão com credencial embutida"):
                if is_placeholder_match(m.group(0)):
                    continue
            key = (rel_path, line_no, rule_name)
            if key not in cred_reported:
                cred_reported.add(key)
                blockers.append(
                    f"Padrão suspeito de credencial em '{rel_path}' (linha {line_no}, categoria: {rule_name}, trecho: [REDIGIDO])"
                )

    # Regra E: novas ocorrências de SuppressWarnings fora de documentação Markdown (*.md)
    if not rel_path.lower().endswith(".md") and SUPPRESS_TOKEN in text:
        key = (rel_path, line_no)
        if key not in sw_reported:
            sw_reported.add(key)
            blockers.append(
                f"Nova ocorrência de {SUPPRESS_TOKEN} detectada em '{rel_path}' (linha {line_no})"
            )


def scan_unified_diff(git_args):
    rc, out, err = run_git(*git_args)
    if rc != 0:
        sys.stderr.write(f"ERRO: falha ao ler diff para inspeção de conteúdo: {err.decode('utf-8', 'replace')}\n")
        sys.exit(EXIT_ENV)

    cur_file = None
    cur_line = 0
    skip_file = False
    for raw_line in out.decode("utf-8", "replace").splitlines():
        if raw_line.startswith("diff --git "):
            cur_file = None
            cur_line = 0
            skip_file = False
            continue
        if raw_line.startswith("+++ "):
            target = raw_line[4:]
            if target == "/dev/null":
                cur_file = None
                skip_file = True
            else:
                if target.startswith("b/"):
                    target = target[2:]
                cur_file = target
                skip_file = (
                    cur_file in sensitive_paths
                    or cur_file in all_binary_paths
                    or os.path.islink(os.path.join(repo_root, cur_file))
                )
            continue
        if raw_line.startswith("@@ "):
            m = re.search(r"\+(\d+)", raw_line)
            if m:
                cur_line = int(m.group(1))
            continue
        if cur_file is None or skip_file:
            continue
        if raw_line.startswith("+") and not raw_line.startswith("+++"):
            inspect_added_line(cur_file, cur_line, raw_line[1:])
            cur_line += 1
        elif raw_line.startswith(" "):
            cur_line += 1


scan_unified_diff(("diff", "--no-ext-diff", "--no-color", "-U0", merge_base, "HEAD"))
scan_unified_diff(("diff", "--no-ext-diff", "--no-color", "--cached", "-U0"))
scan_unified_diff(("diff", "--no-ext-diff", "--no-color", "-U0"))

for p in untracked_text_paths:
    abs_p = os.path.join(repo_root, p)
    try:
        with open(abs_p, "r", encoding="utf-8", errors="replace") as f:
            for idx, raw_line in enumerate(f, start=1):
                inspect_added_line(p, idx, raw_line.rstrip("\r\n"))
    except OSError:
        unverifiables.append(f"Não foi possível ler '{p}' para inspeção de padrões de credenciais.")

# 6. Verificação D — Imutabilidade de migrations Flyway históricas
rc, out, err = run_git("ls-tree", "-r", "--name-only", "-z", merge_base, "--", MIGRATION_PREFIX.rstrip("/"))
if rc != 0:
    sys.stderr.write(f"ERRO: falha ao listar migrations históricas em '{merge_base}': {err.decode('utf-8', 'replace')}\n")
    sys.exit(EXIT_ENV)
historical_migrations = {
    t.decode("utf-8", "surrogateescape") for t in out.split(b"\0") if t
}

rc, agg_out, err = run_git("diff", "--no-ext-diff", "--no-color", "--name-status", "-z", "-M", "-C", merge_base)
if rc != 0:
    sys.stderr.write(f"ERRO: falha ao consultar diff agregado de migrations: {err.decode('utf-8', 'replace')}\n")
    sys.exit(EXIT_ENV)
agg_entries = parse_name_status_z(agg_out)

migration_violations = set()
for src_name, entries in [
    ("commitadas", layers["committed"]),
    ("staged", layers["staged"]),
    ("unstaged", layers["unstaged"]),
    ("acumuladas", agg_entries),
]:
    for st, p1, p2 in entries:
        code = st[0]
        if code == "R" and p1 in historical_migrations:
            msg = f"Migration Flyway histórica renomeada ({src_name}): '{p1}' -> '{p2}'"
            if msg not in migration_violations:
                migration_violations.add(msg)
                blockers.append(msg)
        elif code == "D" and p1 in historical_migrations:
            msg = f"Migration Flyway histórica removida ({src_name}): '{p1}'"
            if msg not in migration_violations:
                migration_violations.add(msg)
                blockers.append(msg)
        elif code in ("M", "T") and p1 in historical_migrations:
            msg = f"Migration Flyway histórica modificada ({src_name}): '{p1}'"
            if msg not in migration_violations:
                migration_violations.add(msg)
                blockers.append(msg)

# 7. Verificação F — Parser de subconjunto documentado de Markdown (F3-F3 / F3-D1)
FENCE_OPEN_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
FENCE_CLOSE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})[ \t]*$")
REF_DEF_RE = re.compile(r"^ {0,3}\[[^\]]+\]:\s*\S+")
HTML_LINK_RE = re.compile(r"(?i)<\s*(?:a\b[^>]*\bhref\s*=|img\b[^>]*\bsrc\s*=)")


def strip_inline_code_spans(line):
    """Remove spans de código inline respeitando o comprimento exato dos backticks."""
    out = []
    i = 0
    n = len(line)
    while i < n:
        if line[i] == "\\":
            # Mantém caractere escapado literal
            out.append(line[i:i + 2])
            i += 2
            continue
        if line[i] == "`":
            j = i
            while j < n and line[j] == "`":
                j += 1
            tick_len = j - i
            # Procura fechamento com EXATAMENTE tick_len backticks consecutivos
            k = j
            closed = False
            while k < n:
                if line[k] == "`":
                    m = k
                    while m < n and line[m] == "`":
                        m += 1
                    if (m - k) == tick_len:
                        out.append(" ")
                        i = m
                        closed = True
                        break
                    k = m
                else:
                    k += 1
            if not closed:
                out.append(line[i:j])
                i = j
        else:
            out.append(line[i])
            i += 1
    return "".join(out)


def parse_destination(line, start_idx):
    """Faz parsing do conteúdo dentro de `(...)` a partir de start_idx (logo após `(`).
    Suporta `<destino com espaços>`, parênteses balanceados e título opcional.
    Retorna (dest_or_none, next_index, error_reason_or_none)."""
    n = len(line)
    i = start_idx
    while i < n and line[i] in (" ", "\t"):
        i += 1
    if i >= n:
        return None, i, "destino inline sem fechamento na linha"

    if line[i] == ")":
        return "", i + 1, None

    if line[i] == "<":
        end_gt = line.find(">", i + 1)
        if end_gt == -1:
            return None, n, "destino delimitado por '<...>' sem fechamento na linha"
        dest = line[i + 1:end_gt].strip()
        j = end_gt + 1
    else:
        j = i
        paren_depth = 0
        while j < n:
            ch = line[j]
            if ch == "\\" and j + 1 < n:
                j += 2
                continue
            if ch == "(":
                paren_depth += 1
                j += 1
                continue
            if ch == ")":
                if paren_depth == 0:
                    break
                paren_depth -= 1
                j += 1
                continue
            if ch in (" ", "\t") and paren_depth == 0:
                break
            j += 1
        if paren_depth != 0:
            return None, n, "parênteses desbalanceados em destino de link Markdown"
        dest = line[i:j].strip()

    # Pula espaços e título opcional ("...", '...', (...)) até ')'
    while j < n and line[j] in (" ", "\t"):
        j += 1
    if j < n and line[j] in ('"', "'"):
        quote = line[j]
        j += 1
        while j < n:
            if line[j] == "\\" and j + 1 < n:
                j += 2
                continue
            if line[j] == quote:
                j += 1
                break
            j += 1
        else:
            return None, n, "título de link Markdown sem fechamento de aspas"
        while j < n and line[j] in (" ", "\t"):
            j += 1
    elif j < n and line[j] == "(":
        depth = 1
        j += 1
        while j < n and depth > 0:
            if line[j] == "\\" and j + 1 < n:
                j += 2
                continue
            if line[j] == "(":
                depth += 1
            elif line[j] == ")":
                depth -= 1
            j += 1
        if depth != 0:
            return None, n, "título de link Markdown entre parênteses sem fechamento"
        while j < n and line[j] in (" ", "\t"):
            j += 1

    if j >= n or line[j] != ")":
        return None, n, "sintaxe de destino inline não suportada ou sem fechamento ')' na linha"

    return dest, j + 1, None


def find_matching_bracket(line, open_idx):
    """Encontra o `]` correspondente a `[` em open_idx, suportando imagem inline aninhada."""
    n = len(line)
    depth = 1
    i = open_idx + 1
    while i < n:
        ch = line[i]
        if ch == "\\" and i + 1 < n:
            i += 2
            continue
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def parse_markdown_line_links(line):
    """Retorna (lista_destinos, lista_nao_verificaveis) para uma linha sem código inline."""
    dests = []
    unsupported = []
    n = len(line)
    i = 0
    while i < n:
        if line[i] == "\\" and i + 1 < n:
            i += 2
            continue
        if line[i] == "!" and i + 1 < n and line[i + 1] == "[":
            is_img = True
            bracket_open = i + 1
        elif line[i] == "[":
            is_img = False
            bracket_open = i
        else:
            i += 1
            continue

        bracket_close = find_matching_bracket(line, bracket_open)
        if bracket_close == -1:
            i = bracket_open + 1
            continue

        inner_text = line[bracket_open + 1:bracket_close]
        after = bracket_close + 1

        if after < n and line[after] == "(":
            # Se for link contendo badge/imagem inline aninhada `[![alt](img)](url)`,
            # valida também os links internos de `inner_text`
            if not is_img and "![" in inner_text:
                sub_d, sub_u = parse_markdown_line_links(inner_text)
                dests.extend(sub_d)
                unsupported.extend(sub_u)
            dest, next_i, err_reason = parse_destination(line, after + 1)
            if err_reason is not None:
                unsupported.append(err_reason)
                i = after + 1
            else:
                dests.append(dest)
                i = next_i
            continue

        if after < n and line[after] == "[":
            ref_close = find_matching_bracket(line, after)
            if ref_close != -1:
                unsupported.append("link por referência `[texto][id]` não suportado")
                i = ref_close + 1
                continue

        i = bracket_open + 1

    return dests, unsupported


def extract_md_anchors(md_abs_path):
    """Extrai conjunto de slugs GFM de cabeçalhos ATX fora de blocos cercados."""
    anchors = set()
    counts = {}
    in_fence = False
    fence_char = ""
    fence_len = 0
    try:
        with open(md_abs_path, "r", encoding="utf-8", errors="replace") as f:
            for raw in f:
                raw_nl = raw.rstrip("\r\n")
                if not in_fence:
                    m_open = FENCE_OPEN_RE.match(raw_nl)
                    if m_open:
                        ticks, info = m_open.group(1), m_open.group(2)
                        if not (ticks[0] == "`" and "`" in info):
                            in_fence = True
                            fence_char = ticks[0]
                            fence_len = len(ticks)
                            continue
                else:
                    m_close = FENCE_CLOSE_RE.match(raw_nl)
                    if m_close and m_close.group(1)[0] == fence_char and len(m_close.group(1)) >= fence_len:
                        in_fence = False
                    continue

                m_head = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", raw_nl)
                if not m_head:
                    continue
                heading = m_head.group(1)
                heading = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", heading)
                heading = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", heading)
                heading = heading.replace("`", "").replace("*", "")
                heading = heading.strip().lower()
                cleaned = [ch for ch in heading if ch.isalnum() or ch in (" ", "-", "_")]
                slug = "".join(cleaned).replace(" ", "-")
                if slug in counts:
                    counts[slug] += 1
                    anchors.add(f"{slug}-{counts[slug]}")
                else:
                    counts[slug] = 0
                    anchors.add(slug)
    except OSError:
        return None
    return anchors


anchor_cache = {}


def get_anchors(abs_path):
    if abs_path not in anchor_cache:
        anchor_cache[abs_path] = extract_md_anchors(abs_path)
    return anchor_cache[abs_path]


md_candidates = sorted(
    p for p in all_touched_paths
    if p.lower().endswith(".md")
    and p not in sensitive_paths
    and os.path.isfile(os.path.join(repo_root, p))
    and not os.path.islink(os.path.join(repo_root, p))
)

repo_root_real = os.path.realpath(repo_root)

for md_rel in md_candidates:
    md_abs = os.path.join(repo_root, md_rel)
    md_dir = os.path.dirname(md_abs)
    in_fence = False
    fence_char = ""
    fence_len = 0
    fence_open_line = 0
    try:
        with open(md_abs, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError:
        unverifiables.append(f"Arquivo Markdown ilegível: '{md_rel}'")
        continue

    for lno, raw in enumerate(lines, start=1):
        raw_nl = raw.rstrip("\r\n")
        if not in_fence:
            m_open = FENCE_OPEN_RE.match(raw_nl)
            if m_open:
                ticks, info = m_open.group(1), m_open.group(2)
                if not (ticks[0] == "`" and "`" in info):
                    in_fence = True
                    fence_char = ticks[0]
                    fence_len = len(ticks)
                    fence_open_line = lno
                    continue
        else:
            m_close = FENCE_CLOSE_RE.match(raw_nl)
            if m_close and m_close.group(1)[0] == fence_char and len(m_close.group(1)) >= fence_len:
                in_fence = False
            continue

        line_no_code = strip_inline_code_spans(raw_nl)

        if REF_DEF_RE.match(line_no_code):
            unverifiables.append(
                f"Sintaxe Markdown não suportada (definição de link por referência) em '{md_rel}:{lno}'"
            )
            continue
        if HTML_LINK_RE.search(line_no_code):
            unverifiables.append(
                f"Sintaxe Markdown não suportada (tag HTML com href/src) em '{md_rel}:{lno}'"
            )
            continue

        dests, unsup_reasons = parse_markdown_line_links(line_no_code)
        for ur in unsup_reasons:
            unverifiables.append(f"Sintaxe Markdown não suportada ({ur}) em '{md_rel}:{lno}'")

        for dest in dests:
            if not dest:
                blockers.append(f"Link Markdown vazio em '{md_rel}:{lno}'")
                continue
            low_dest = dest.lower()
            if low_dest.startswith(("http://", "https://", "mailto:", "tel:")):
                continue
            if low_dest.startswith("file://"):
                blockers.append(f"Link Markdown com esquema 'file://' não portável em '{md_rel}:{lno}': '{dest}'")
                continue

            if "#" in dest:
                path_part, anchor_part = dest.split("#", 1)
            else:
                path_part, anchor_part = dest, ""

            decoded_path = urllib.parse.unquote(path_part)
            if not decoded_path:
                target_abs = md_abs
            else:
                if decoded_path.startswith("/"):
                    target_abs = os.path.normpath(os.path.join(repo_root_real, decoded_path.lstrip("/")))
                else:
                    target_abs = os.path.normpath(os.path.join(md_dir, decoded_path))

                target_real = os.path.realpath(target_abs)
                if not (target_real == repo_root_real or target_real.startswith(repo_root_real + os.sep)):
                    blockers.append(f"Link Markdown escapa da raiz do repositório em '{md_rel}:{lno}': '{dest}'")
                    continue
                if not os.path.exists(target_abs):
                    blockers.append(f"Link Markdown quebrado em '{md_rel}:{lno}': '{dest}'")
                    continue

            if anchor_part:
                decoded_anchor = urllib.parse.unquote(anchor_part).strip().lower()
                if os.path.isdir(target_abs):
                    unverifiables.append(
                        f"Âncora em destino de diretório não verificável em '{md_rel}:{lno}': '{dest}'"
                    )
                elif target_abs.lower().endswith(".md"):
                    anchors = get_anchors(target_abs)
                    if anchors is None:
                        unverifiables.append(
                            f"Não foi possível ler cabeçalhos de '{dest}' referenciado em '{md_rel}:{lno}'"
                        )
                    elif decoded_anchor not in anchors:
                        blockers.append(
                            f"Âncora Markdown inexistente em '{md_rel}:{lno}': '{dest}'"
                        )
                else:
                    if not re.match(r"^l\d+(?:-l\d+)?$", decoded_anchor):
                        unverifiables.append(
                            f"Âncora em arquivo não-Markdown não verificável em '{md_rel}:{lno}': '{dest}'"
                        )

    if in_fence:
        unverifiables.append(
            f"Bloco de código cercado (code fence) sem fechamento explícito em '{md_rel}:{fence_open_line}'"
        )

# 8. Verificação G — Resumo por módulos e recomendações manuais
modules = {
    "backend": [],
    "apps/mobile": [],
    "apps/admin": [],
    "scripts": [],
    "docs": [],
    "outros": [],
}
for p in sorted(all_touched_paths):
    if p.startswith("backend/"):
        modules["backend"].append(p)
    elif p.startswith("apps/mobile/"):
        modules["apps/mobile"].append(p)
    elif p.startswith("apps/admin/"):
        modules["apps/admin"].append(p)
    elif p.startswith("scripts/"):
        modules["scripts"].append(p)
    elif p.startswith("docs/") or ("/" not in p and p.lower().endswith(".md")):
        modules["docs"].append(p)
    else:
        modules["outros"].append(p)

print("=== Gate Local — Check de Governança (READ-ONLY) ===")
print(f"Referência-base : {base_ref}")
print(f"Merge-base      : {merge_base}")
print(f"Commitadas      : {len(layers['committed'])}")
print(f"Staged          : {len(layers['staged'])}")
print(f"Unstaged        : {len(layers['unstaged'])}")
print(f"Untracked       : {len(untracked_paths)}")
print(f"Total distintos : {len(all_touched_paths)}")
print("")
print("Módulos afetados:")
any_mod = False
for mod, paths in modules.items():
    if paths:
        any_mod = True
        print(f"  - {mod}: {len(paths)} arquivo(s)")
if not any_mod:
    print("  - nenhum arquivo alterado em relação ao merge-base")

print("")
print("Recomendações de validação manual (não executadas pelo gate):")
if modules["backend"]:
    print("  - backend     : executar testes em infraestrutura descartável TEST_* (Linux: ./scripts/test-infra.sh run | Windows: .\\scripts\\test-infra.ps1 run)")
if modules["apps/mobile"]:
    print("  - apps/mobile : executar análise estática e testes Flutter (cd apps/mobile && flutter analyze && flutter test)")
if modules["apps/admin"]:
    print("  - apps/admin  : executar lint/build/testes do painel web (cd apps/admin && npm run build)")
if modules["scripts"]:
    print("  - scripts     : executar bash -n e as suítes descartáveis em scripts/tests/")
if modules["docs"]:
    print("  - docs        : revisar clareza PT-BR, referências cruzadas e git diff --check")
if modules["outros"]:
    print("  - outros      : revisar manualmente impactos de configuração/infraestrutura")
if not any_mod:
    print("  - nenhuma validação de módulo necessária (sem alterações)")

print("")
print("[AVISO] A detecção de arquivos sensíveis e padrões de credenciais é heurística/conservadora e não garante identificação de todos os segredos.")
print("[AVISO] Validação de links Markdown cobre o subconjunto documentado (links/imagens inline em linha única, badges aninhados, parênteses balanceados, `<...>` e cabeçalhos ATX); demais sintaxes ou fences sem fechamento são reportados como [NAO_VERIFICAVEL].")

for w in warnings:
    print(f"[AVISO] {w}")

for u in unverifiables:
    sys.stderr.write(f"[NAO_VERIFICAVEL] {u}\n")

for b in blockers:
    sys.stderr.write(f"[BLOQUEADO] {b}\n")

if blockers or unverifiables:
    sys.stderr.write(
        f"[BLOQUEADO] Check reprovado: {len(blockers)} bloqueio(s), {len(unverifiables)} item(ns) não verificável(is).\n"
    )
    sys.exit(EXIT_BLOCKED)

print("[APROVADO] Todas as verificações de governança do 'check' foram aprovadas.")
sys.exit(EXIT_OK)
PYEOF

  exit "$py_rc"
}

# ------------------------------------------------------------------------------
# Subcomando: CHECK-COMMIT-MSG (abertura segura por descritor — F3-S2)
# ------------------------------------------------------------------------------

cmd_check_commit_msg() {
  local message="" file_path=""
  local msg_set=0 file_set=0 no_strip_comments=0

  while (( $# > 0 )); do
    case "$1" in
      -h|--help) print_usage; exit "$EXIT_OK" ;;
      --message)
        (( msg_set == 0 )) || die_usage "Opção --message informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --message exige um valor."
        message="$2"; msg_set=1; shift 2 ;;
      --message=*)
        (( msg_set == 0 )) || die_usage "Opção --message informada mais de uma vez."
        message="${1#--message=}"; msg_set=1; shift ;;
      --file)
        (( file_set == 0 )) || die_usage "Opção --file informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --file exige um valor."
        file_path="$2"; file_set=1; shift 2 ;;
      --file=*)
        (( file_set == 0 )) || die_usage "Opção --file informada mais de uma vez."
        file_path="${1#--file=}"; file_set=1; shift ;;
      --no-strip-comments)
        (( no_strip_comments == 0 )) || die_usage "Opção --no-strip-comments informada mais de uma vez."
        no_strip_comments=1; shift ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'check-commit-msg': $1" ;;
    esac
  done

  if (( msg_set == 0 && file_set == 0 )); then
    die_usage "Informe exatamente uma opção: --message \"<mensagem>\" ou --file <arquivo>."
  fi
  if (( msg_set == 1 && file_set == 1 )); then
    die_usage "Não combine --message com --file em 'check-commit-msg'."
  fi
  if (( no_strip_comments == 1 && file_set == 0 )); then
    die_usage "A opção --no-strip-comments exige --file <arquivo>."
  fi

  local mode="message"
  local input_arg="$message"
  if (( file_set == 1 )); then
    mode="file"
    input_arg="$file_path"
    if [[ -z "$file_path" ]]; then
      die_usage "O caminho informado em --file não pode ser vazio."
    fi
    # Recusa caminhos sensíveis antes mesmo de qualquer syscall de abertura
    if is_sensitive_path_bash "$file_path"; then
      die_blocked "Leitura recusada: '${file_path}' corresponde a um padrão de arquivo sensível."
    fi
  fi

  local py_rc=0
  python3 - "$mode" "$input_arg" "$no_strip_comments" <<'PYEOF' || py_rc=$?
import errno
import os
import re
import stat
import sys

mode = sys.argv[1]
arg = sys.argv[2]
no_strip_comments = (sys.argv[3] == "1")

EXIT_OK = 0
EXIT_BLOCKED = 3
EXIT_ENV = 4
MAX_BYTES = 65536

if mode == "file":
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_NONBLOCK"):
        sys.stderr.write("ERRO: ambiente não suporta O_NOFOLLOW/O_NONBLOCK para abertura segura de arquivo.\n")
        sys.exit(EXIT_ENV)

    fd = None
    try:
        flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
        try:
            fd = os.open(arg, flags)
        except OSError as exc:
            if exc.errno == errno.ELOOP:
                sys.stderr.write("[BLOQUEADO] Leitura recusada: destino em --file é um symlink (O_NOFOLLOW).\n")
            elif exc.errno == errno.ENOENT:
                sys.stderr.write("[BLOQUEADO] Arquivo de mensagem informado em --file não foi encontrado.\n")
            elif exc.errno == errno.EISDIR:
                sys.stderr.write("[BLOQUEADO] Leitura recusada: destino em --file é um diretório.\n")
            elif exc.errno in (errno.ENXIO, errno.EAGAIN, errno.EWOULDBLOCK):
                sys.stderr.write("[BLOQUEADO] Leitura recusada: destino em --file não é um arquivo regular (FIFO/dispositivo).\n")
            else:
                sys.stderr.write("[BLOQUEADO] Não foi possível abrir o arquivo informado em --file com segurança.\n")
            sys.exit(EXIT_BLOCKED)

        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            sys.stderr.write("[BLOQUEADO] Leitura recusada: destino em --file não é um arquivo regular (S_ISREG).\n")
            sys.exit(EXIT_BLOCKED)

        if st.st_size > MAX_BYTES:
            sys.stderr.write("[BLOQUEADO] Arquivo de mensagem excede o limite seguro de 64 KiB.\n")
            sys.exit(EXIT_BLOCKED)

        chunks = []
        total = 0
        while total <= MAX_BYTES:
            chunk = os.read(fd, (MAX_BYTES + 1) - total)
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        raw = b"".join(chunks)
    except OSError:
        sys.stderr.write("[BLOQUEADO] Falha de leitura segura no descritor do arquivo informado em --file.\n")
        sys.exit(EXIT_BLOCKED)
    finally:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass

    if len(raw) > MAX_BYTES:
        sys.stderr.write("[BLOQUEADO] Conteúdo do arquivo de mensagem excede o limite estrito de 64 KiB.\n")
        sys.exit(EXIT_BLOCKED)

    if b"\0" in raw:
        sys.stderr.write("[BLOQUEADO] Arquivo binário recusado em --file.\n")
        sys.exit(EXIT_BLOCKED)
    try:
        full_text = raw.decode("utf-8")
    except UnicodeDecodeError:
        sys.stderr.write("[BLOQUEADO] Arquivo de mensagem não é UTF-8 válido.\n")
        sys.exit(EXIT_BLOCKED)
else:
    full_text = arg

lines = full_text.splitlines()
if mode == "file" and not no_strip_comments:
    non_comment = [ln for ln in lines if not ln.startswith("#")]
    while non_comment and not non_comment[0].strip():
        non_comment.pop(0)
elif mode == "message":
    non_comment = lines
    while non_comment and not non_comment[0].strip():
        non_comment.pop(0)
else:
    # Modo literal (--file --no-strip-comments): preserva integralmente o subject
    # real do commit, sem descartar linhas iniciadas por '#' nem linhas iniciais.
    non_comment = lines

if not non_comment or all(not ln.strip() for ln in non_comment):
    sys.stderr.write("[BLOQUEADO] Mensagem de commit vazia.\n")
    sys.exit(EXIT_BLOCKED)

header = non_comment[0]
body_lines = non_comment[1:]

HEADER_RE = re.compile(
    r"^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)"
    r"(?:\(([a-z0-9][a-z0-9._/-]*)\))?"
    r"(!)?:\s(.+)$"
)

m = HEADER_RE.match(header)
if not m:
    sys.stderr.write(
        "[BLOQUEADO] Cabeçalho fora do padrão Conventional Commits.\n"
        "  Esperado: <tipo>[(<escopo>)][!]: <descrição>\n"
        "  Tipos permitidos: feat, fix, docs, style, refactor, perf, test, build, ci, chore\n"
    )
    sys.exit(EXIT_BLOCKED)

cc_type, cc_scope, cc_bang, cc_desc = m.group(1), m.group(2), m.group(3), m.group(4)

if not cc_desc.strip() or cc_desc != cc_desc.lstrip():
    sys.stderr.write("[BLOQUEADO] Descrição obrigatória ausente ou com espaçamento inválido após ': '.\n")
    sys.exit(EXIT_BLOCKED)

has_breaking_footer = False
for ln in body_lines:
    if ln.startswith("BREAKING CHANGE:") or ln.startswith("BREAKING-CHANGE:"):
        has_breaking_footer = True
        after = ln.split(":", 1)[1]
        if not after.strip():
            sys.stderr.write("[BLOQUEADO] Rodapé BREAKING CHANGE exige descrição não vazia.\n")
            sys.exit(EXIT_BLOCKED)

breaking = bool(cc_bang) or has_breaking_footer

print("=== Gate Local — Validação de Mensagem de Commit (READ-ONLY) ===")
print(f"Tipo            : {cc_type}")
print(f"Escopo          : {cc_scope if cc_scope else '(nenhum)'}")
print(f"Breaking change : {'sim' if breaking else 'não'}")
print("[AVISO] Conformidade da descrição com português brasileiro (PT-BR) não é verificada automaticamente e exige revisão humana.")
print("[APROVADO] Estrutura Conventional Commits válida.")
sys.exit(EXIT_OK)
PYEOF

  exit "$py_rc"
}

# ------------------------------------------------------------------------------
# Subcomando: PRE-MERGE (estritamente informativo / READ-ONLY)
# ------------------------------------------------------------------------------

cmd_pre_merge() {
  local slug="" protected_hash=""
  local slug_set=0 hash_set=0
  local protected_files=()

  while (( $# > 0 )); do
    case "$1" in
      -h|--help) print_usage; exit "$EXIT_OK" ;;
      --slug)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --slug exige um valor."
        slug="$2"; slug_set=1; shift 2 ;;
      --slug=*)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        slug="${1#--slug=}"; slug_set=1; shift ;;
      --protected-hash)
        (( hash_set == 0 )) || die_usage "Opção --protected-hash informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --protected-hash exige um valor."
        protected_hash="$2"; hash_set=1; shift 2 ;;
      --protected-hash=*)
        (( hash_set == 0 )) || die_usage "Opção --protected-hash informada mais de uma vez."
        protected_hash="${1#--protected-hash=}"; hash_set=1; shift ;;
      --protected-file)
        (( $# >= 2 )) || die_usage "Opção --protected-file exige um caminho relativo."
        validate_protected_file_path "$2"
        local existing_pf
        for existing_pf in "${protected_files[@]}"; do
          if [[ "$existing_pf" == "$2" ]]; then
            die_usage "Caminho duplicado em --protected-file: '$2'."
          fi
        done
        protected_files+=("$2")
        shift 2 ;;
      --protected-file=*)
        local pf_val="${1#--protected-file=}"
        validate_protected_file_path "$pf_val"
        local existing_pf
        for existing_pf in "${protected_files[@]}"; do
          if [[ "$existing_pf" == "$pf_val" ]]; then
            die_usage "Caminho duplicado em --protected-file: '${pf_val}'."
          fi
        done
        protected_files+=("$pf_val")
        shift ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'pre-merge': $1" ;;
    esac
  done

  (( slug_set == 1 )) || die_usage "Opção obrigatória ausente em 'pre-merge': --slug <slug>."
  validate_slug "$slug"
  if (( hash_set == 1 )); then
    validate_sha256_format "$protected_hash"
  elif (( ${#protected_files[@]} > 0 )); then
    die_usage "A opção --protected-file exige --protected-hash para validação completa do conjunto protegido."
  fi

  resolve_repo_context

  local task_branch="${BRANCH_PREFIX}${slug}"
  local task_ref="refs/heads/${task_branch}"
  local main_ref="refs/heads/${MAIN_BRANCH}"
  local blockers=()

  # 1. Identificação inequívoca da worktree principal na branch main
  local main_ok=1
  if (( WT_BARE[0] == 1 )) || [[ -z "$MAIN_WORKTREE_REALPATH" ]]; then
    blockers+=("Worktree principal não identificável ou inacessível.")
    main_ok=0
  elif [[ -L "${MAIN_WORKTREE_PATH}/.git" ]] || [[ ! -d "${MAIN_WORKTREE_PATH}/.git" && ! -f "${MAIN_WORKTREE_PATH}/.git" ]]; then
    blockers+=("Entrada '.git' da worktree principal é inválida.")
    main_ok=0
  elif (( WT_DETACHED[0] == 1 )) || [[ "${WT_BRANCHES[0]}" != "$main_ref" ]]; then
    blockers+=("Worktree principal ('${MAIN_WORKTREE_PATH}') não está na branch '${MAIN_BRANCH}' (atual: '${WT_BRANCHES[0]:-DETACHED}').")
    main_ok=0
  fi

  if ! git_ref_exists "$CURRENT_TOPLEVEL" "$main_ref"; then
    blockers+=("Branch local '${MAIN_BRANCH}' não existe.")
    main_ok=0
  fi

  # 2. Existência da branch da tarefa
  local task_ok=1
  if ! git_ref_exists "$CURRENT_TOPLEVEL" "$task_ref"; then
    blockers+=("Branch da tarefa '${task_branch}' não existe localmente.")
    task_ok=0
  fi

  local main_head="" task_head="" ahead_count="" behind_count=""
  local task_changed_paths=()

  if (( main_ok == 1 && task_ok == 1 )); then
    main_head="$(git_ro -C "$MAIN_WORKTREE_PATH" rev-parse --verify "${main_ref}^{commit}")" \
      || die_env "Falha ao resolver HEAD de '${MAIN_BRANCH}'."
    task_head="$(git_ro -C "$MAIN_WORKTREE_PATH" rev-parse --verify "${task_ref}^{commit}")" \
      || die_env "Falha ao resolver HEAD de '${task_branch}'."

    ahead_count="$(git_ro -C "$MAIN_WORKTREE_PATH" rev-list --count "${main_head}..${task_head}")" \
      || die_env "Falha ao calcular commits candidatos via 'git rev-list'."
    behind_count="$(git_ro -C "$MAIN_WORKTREE_PATH" rev-list --count "${task_head}..${main_head}")" \
      || die_env "Falha ao calcular divergência via 'git rev-list'."

    if (( behind_count > 0 )) || ! git_is_ancestor "$MAIN_WORKTREE_PATH" "$main_head" "$task_head"; then
      blockers+=("Há divergência entre '${MAIN_BRANCH}' e '${task_branch}' (${behind_count} commit(s) exclusivos na main); fast-forward impossível.")
    elif (( ahead_count == 0 )); then
      blockers+=("Nenhum commit candidato à integração em '${task_branch}' em relação à '${MAIN_BRANCH}'.")
    fi

    local st="" p1="" p2="" fd="" pid="" rc=0
    exec {fd}< <(trap - ERR; git_ro -C "$MAIN_WORKTREE_PATH" diff --no-ext-diff --no-color --name-status -z -M -C "$main_head" "$task_head")
    pid=$!
    while IFS= read -r -u "$fd" -d '' st; do
      if [[ "$st" == R* || "$st" == C* ]]; then
        IFS= read -r -u "$fd" -d '' p1 || true
        IFS= read -r -u "$fd" -d '' p2 || true
        [[ -n "$p1" ]] && task_changed_paths+=("$p1")
        [[ -n "$p2" ]] && task_changed_paths+=("$p2")
      else
        IFS= read -r -u "$fd" -d '' p1 || true
        [[ -n "$p1" ]] && task_changed_paths+=("$p1")
      fi
    done
    exec {fd}<&-
    wait "$pid" || rc=$?
    if (( rc != 0 )); then
      die_env "Falha ao listar caminhos alterados pela branch '${task_branch}' (código ${rc})."
    fi
  fi

  # 3. Inspeção da worktree principal, colisões e identidade/hash protegido
  local observed_hash=""
  local main_staged_count=0 main_unstaged_count=0 main_untracked_count=0
  local collisions=()

  if (( main_ok == 1 )); then
    if ! validate_main_protected_state "$MAIN_WORKTREE_PATH" "$hash_set" "$protected_hash" "${protected_files[@]}"; then
      local pb
      for pb in "${PROTECTED_CHECK_BLOCKERS[@]}"; do
        blockers+=("$pb")
      done
    fi
    observed_hash="$PROTECTED_CHECK_HASH"
    main_staged_count="${#STATUS_STAGED_PATHS[@]}"
    main_unstaged_count="${#STATUS_UNSTAGED_PATHS[@]}"
    main_untracked_count="${#STATUS_UNTRACKED_PATHS[@]}"

    local tp mp
    for tp in "${task_changed_paths[@]}"; do
      for mp in "${STATUS_STAGED_PATHS[@]}" "${STATUS_UNSTAGED_PATHS[@]}" "${STATUS_UNTRACKED_PATHS[@]}"; do
        if [[ "$tp" == "$mp" ]]; then
          collisions+=("$tp")
        fi
      done
    done
    if (( ${#collisions[@]} > 0 )); then
      local c
      for c in "${collisions[@]}"; do
        blockers+=("Colisão de caminho entre '${task_branch}' e alteração local na '${MAIN_BRANCH}': '${c}'.")
      done
    fi
  fi

  msg_info "=== Gate Local — Pré-Integração (pre-merge, READ-ONLY) ==="
  msg_info "Worktree principal : ${MAIN_WORKTREE_PATH:-INDISPONÍVEL}"
  msg_info "Branch candidata   : ${task_branch}"
  msg_info "HEAD da main       : ${main_head:-INDISPONÍVEL}"
  msg_info "HEAD da tarefa     : ${task_head:-INDISPONÍVEL}"
  msg_info "Commits candidatos : ${ahead_count:-INDISPONÍVEL}"
  msg_info "Divergência (main) : ${behind_count:-INDISPONÍVEL}"
  msg_info "Caminhos na tarefa : ${#task_changed_paths[@]}"
  msg_info "Estado na main     : staged=${main_staged_count}, unstaged=${main_unstaged_count}, untracked=${main_untracked_count}"
  if (( hash_set == 1 )); then
    msg_info "Hash protegido     : ${observed_hash:-INDISPONÍVEL}"
    msg_info "Arquivos declarados: ${#protected_files[@]}"
  fi
  msg_warn "Este comando é estritamente informativo (READ-ONLY) e NÃO executa merge/fast-forward. O resultado reflete apenas a elegibilidade neste instante; a integração continua manual mediante autorização separada."

  if (( ${#blockers[@]} > 0 )); then
    local b
    for b in "${blockers[@]}"; do
      msg_blocked "$b"
    done
    exit "$EXIT_BLOCKED"
  fi

  msg_ok "Elegibilidade de fast-forward verificada para '${task_branch}' -> '${MAIN_BRANCH}'."
  exit "$EXIT_OK"
}

# ------------------------------------------------------------------------------
# Ponto de entrada principal
# ------------------------------------------------------------------------------

main() {
  # Proteção F3-S1 executada ANTES de qualquer comando Git (inclusive `--help`)
  reject_unsafe_git_env
  check_runtime_requirements

  if (( $# == 0 )); then
    die_usage "Nenhum subcomando informado."
  fi

  local subcommand="$1"
  shift

  case "$subcommand" in
    -h|--help)
      if (( $# > 0 )); then
        die_usage "Argumentos extras não são aceitos com '${subcommand}'."
      fi
      print_usage
      exit "$EXIT_OK"
      ;;
    preflight)
      cmd_preflight "$@"
      ;;
    check)
      cmd_check "$@"
      ;;
    check-commit-msg)
      cmd_check_commit_msg "$@"
      ;;
    pre-merge)
      cmd_pre_merge "$@"
      ;;
    *)
      die_usage "Subcomando desconhecido: '${subcommand}'. Subcomandos válidos: preflight, check, check-commit-msg, pre-merge."
      ;;
  esac
}

main "$@"
