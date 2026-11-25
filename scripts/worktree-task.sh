#!/usr/bin/env bash
# ==============================================================================
# Rewit — Automação Segura do Ciclo de Vida de Worktrees (scripts/worktree-task.sh)
#
# Utilitário conservador para padronizar a criação, inspeção e encerramento de
# Git worktrees de tarefas de agentes no repositório Rewit.
#
# Requisitos mínimos declarados:
#   - Ambiente Linux com Bash 4.4+
#   - Git 2.36+ (com suporte a `git worktree list --porcelain -z`)
#   - Utilitários padrão POSIX/GNU Coreutils: realpath, stat, find, id, mkdir
#
# Códigos de saída contratuais:
#   0 — Operação concluída com sucesso (inclui simulação aprovada e idempotência)
#   2 — Erro de uso (subcomando, opção ou argumento inválido/duplicado)
#   3 — Bloqueio de segurança, pré-condição não satisfeita ou worktree não
#       verificável em `status`
#   4 — Erro de ambiente, dependência, versão incompatível ou falha inesperada
#       de comando Git/sistema
# ==============================================================================

set -Eeuo pipefail

# Códigos de saída padronizados
readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_BLOCKED=3
readonly EXIT_ENV=4

# Versões mínimas suportadas
readonly MIN_BASH_MAJOR=4
readonly MIN_BASH_MINOR=4
readonly MIN_GIT_MAJOR=2
readonly MIN_GIT_MINOR=36

# Constantes de convenção do Rewit
readonly MAIN_BRANCH="main"
readonly BRANCH_PREFIX="agent/neon/"
readonly WORKTREE_SUBDIR="source/rewit-worktrees"

# Variáveis globais preenchidas durante a resolução de contexto
REPO_COMMON_DIR=""
MAIN_WORKTREE_PATH=""
MAIN_WORKTREE_REALPATH=""
CURRENT_TOPLEVEL=""
BASE_WORKTREE_DIR=""

# Arrays preenchidos pelo parser de `git worktree list --porcelain -z`
WT_PATHS=()
WT_HEADS=()
WT_BRANCHES=()
WT_DETACHED=()
WT_BARE=()
WT_LOCKED=()
WT_PRUNABLE=()
WT_IS_MAIN=()

# Variáveis de status da árvore de trabalho preenchidas por `inspect_worktree_status`
STATUS_TRACKED_PATHS=()
STATUS_STAGED_PATHS=()
STATUS_UNSTAGED_PATHS=()
STATUS_UNTRACKED_PATHS=()
LAST_INSPECT_ERROR=""

# Array de arquivos ignorados sensíveis preenchido por `scan_sensitive_ignored`
SENSITIVE_FINDINGS=()
LAST_SCAN_ERROR=""

# Variáveis preenchidas pela auditoria de encerramento
AUDIT_BLOCKERS=()
AUDIT_TARGET_PATH=""
AUDIT_TARGET_BRANCH=""
AUDIT_TARGET_REF=""
AUDIT_WT_HEAD=""
AUDIT_MAIN_HEAD=""

# ------------------------------------------------------------------------------
# Mensagens, tratamento de falhas inesperadas e utilitários básicos
# ------------------------------------------------------------------------------

msg_info() {
  printf '%s\n' "$*"
}

msg_warn() {
  printf 'AVISO: %s\n' "$*" >&2
}

msg_err() {
  printf 'ERRO: %s\n' "$*" >&2
}

msg_blocked() {
  printf 'BLOQUEADO: %s\n' "$*" >&2
}

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

# Rede de segurança para qualquer erro não capturado retornar código contratual 4
on_unexpected_err() {
  local lineno="$1"
  local rc="$2"
  msg_err "Falha inesperada de ambiente ou comando externo na linha ${lineno} (código interno ${rc})."
  exit "$EXIT_ENV"
}
trap 'on_unexpected_err "$LINENO" "$?"' ERR

# Wrapper determinístico para chamadas Git cujo formato de saída é inspecionado
git_c() {
  LC_ALL=C git "$@"
}

# Avalia regex estritamente sob locale C sem depender de LC_COLLATE do ambiente
matches_c_regex() {
  local value="$1"
  local pattern="$2"
  local LC_ALL=C
  [[ "$value" =~ $pattern ]]
}

# Verifica existência de referência local distinguindo ausente (1) de erro Git (>=2)
git_ref_exists() {
  local repo="$1"
  local ref="$2"
  local rc=0
  git_c -C "$repo" show-ref --verify --quiet "$ref" || rc=$?
  if (( rc == 0 )); then
    return 0
  elif (( rc == 1 )); then
    return 1
  else
    die_env "Falha inesperada ao consultar referência Git '${ref}' em '${repo}' (código ${rc})."
  fi
}

# Verifica ancestralidade real distinguindo não-ancestral (1) de erro Git (>=2)
git_is_ancestor() {
  local repo="$1"
  local ancestor="$2"
  local descendant="$3"
  local rc=0
  git_c -C "$repo" merge-base --is-ancestor "$ancestor" "$descendant" || rc=$?
  if (( rc == 0 )); then
    return 0
  elif (( rc == 1 )); then
    return 1
  else
    die_env "Falha inesperada ao verificar ancestralidade entre '${ancestor}' e '${descendant}' (código ${rc})."
  fi
}

# ------------------------------------------------------------------------------
# Ajuda de linha de comando (PT-BR)
# ------------------------------------------------------------------------------

print_usage() {
  cat <<'EOF'
Uso:
  ./scripts/worktree-task.sh <comando> [opções]

Comandos disponíveis:
  create --slug <slug> --base <sha> [--allow-dirty-main]
      Cria uma Git worktree isolada em $HOME/source/rewit-worktrees/<slug>
      com a branch agent/neon/<slug> a partir do SHA local explícito.
      Requer execução na worktree principal na branch main.
      Sem --allow-dirty-main, recusa main com modificações locais unstaged.
      Arquivos staged e untracked na main são sempre recusados.

  status
      Inspeciona em modo estritamente READ-ONLY todas as worktrees registradas:
      caminho, branch/DETACHED HEAD, HEAD, limpeza, alterações rastreadas,
      untracked, commits exclusivos em relação à main local, arquivos ignorados
      sensíveis e identificação da worktree principal.
      Se alguma worktree individual estiver inconsistente ou ilegível, reporta
      o problema, continua listando as demais e encerra com código 3.

  close --slug <slug> [--dry-run | --execute]
      Valida todas as salvaguardas de encerramento para a worktree do slug.
      O modo padrão é simulação (--dry-run), sem qualquer efeito colateral.
      Com --execute, exige terminal interativo, confirmação humana digitando
      exatamente o slug e revalidação integral imediatamente antes da remoção.
      Remove apenas a worktree (sem --force); nunca remove a branch local.

Opções gerais:
  -h, --help    Exibe esta ajuda e encerra com código 0.

Códigos de saída:
  0  Sucesso (inclui simulação aprovada e close idempotente)
  2  Erro de uso (argumento, opção ou combinação inválida)
  3  Bloqueio de segurança, pré-condição não satisfeita ou worktree não verificável em status
  4  Erro de ambiente, dependência, versão incompatível ou falha inesperada do Git/sistema
EOF
}

# ------------------------------------------------------------------------------
# Validações de ambiente, versões, entrada e segurança de caminhos
# ------------------------------------------------------------------------------

check_git_version() {
  local version_out=""
  local rc=0
  version_out="$(git_c --version 2>&1)" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao consultar a versão do Git ('git --version' retornou ${rc}): ${version_out}"
  fi

  local ver_regex='git version ([0-9]+)\.([0-9]+)'
  local LC_ALL=C
  if ! [[ "$version_out" =~ $ver_regex ]]; then
    die_env "Não foi possível identificar a versão numérica do Git a partir de: '${version_out}'."
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
  for cmd in git realpath stat find id mkdir; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      die_env "Dependência obrigatória não encontrada no PATH: $cmd"
    fi
  done

  check_git_version
}

# Valida slug restritivo: ASCII minúsculo, números e hífen interno (1 a 63 chars)
validate_slug() {
  local slug="$1"

  if [[ -z "$slug" ]]; then
    die_usage "O valor de --slug não pode ser vazio."
  fi

  # Proíbe explicitamente traversal, barras, espaços, acentos, maiúsculas e caracteres especiais
  local slug_regex='^[abcdefghijklmnopqrstuvwxyz0123456789]([abcdefghijklmnopqrstuvwxyz0123456789-]{0,61}[abcdefghijklmnopqrstuvwxyz0123456789])?$'
  if ! matches_c_regex "$slug" "$slug_regex"; then
    die_usage "Slug inválido: '$slug'. Use apenas letras ASCII minúsculas (a-z), dígitos (0-9) e hífen interno, de 1 a 63 caracteres, sem barras, pontos, espaços ou acentos."
  fi

  local ref_rc=0
  git_c check-ref-format "refs/heads/${BRANCH_PREFIX}${slug}" >/dev/null 2>&1 || ref_rc=$?
  if (( ref_rc == 1 )); then
    die_usage "Slug inválido para nome de branch Git: '${BRANCH_PREFIX}${slug}'."
  elif (( ref_rc != 0 )); then
    die_env "Falha inesperada ao validar formato de branch via 'git check-ref-format' (código ${ref_rc})."
  fi
}

# Valida que --base é um SHA hexadecimal explícito (não aceita refs simbólicas nem expressões)
validate_sha_format() {
  local sha="$1"
  local sha_regex='^[0123456789abcdefABCDEF]{7,64}$'
  if ! matches_c_regex "$sha" "$sha_regex"; then
    die_usage "SHA base inválido: '$sha'. Informe um SHA hexadecimal explícito (7 a 64 caracteres), sem nomes de branch ou expressões."
  fi
}

# Verifica ausência de symlinks em todos os prefixos existentes de um caminho absoluto
check_no_ancestor_symlinks() {
  local path="$1"

  if [[ "$path" != /* ]]; then
    die_blocked "Caminho esperado deve ser absoluto: '$path'."
  fi

  local remainder="${path#/}"
  local current=""
  local part=""
  local parts=()

  IFS='/' read -r -a parts <<< "$remainder"
  for part in "${parts[@]}"; do
    if [[ -z "$part" || "$part" == "." || "$part" == ".." ]]; then
      die_blocked "Caminho contém componente inválido ou traversal: '$path'."
    fi
    current="${current}/${part}"
    if [[ -L "$current" ]]; then
      die_blocked "Symlink detectado no caminho ou diretório ancestral: '$current'."
    fi
    if [[ -e "$current" && ! -d "$current" ]]; then
      die_blocked "Componente existente não é um diretório: '$current'."
    fi
  done
}

# Valida o diretório-base centralizado $HOME/source/rewit-worktrees
validate_base_worktree_dir() {
  local home_raw="${HOME:-}"
  if [[ -z "$home_raw" ]]; then
    die_blocked "Variável de ambiente HOME não está definida."
  fi

  # Remove barras finais exceto se for "/"
  local home_clean="${home_raw%/}"
  if [[ -z "$home_clean" || "$home_clean" != /* ]]; then
    die_blocked "Variável HOME inválida: '$home_raw'."
  fi

  local base="${home_clean}/${WORKTREE_SUBDIR}"

  # Exige caminho estritamente ASCII, sem espaços, acentos, controle ou '//'
  local ascii_path_regex='^/[ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._/-]+$'
  if ! matches_c_regex "$base" "$ascii_path_regex" || [[ "$base" == *"//"* ]]; then
    die_blocked "Diretório-base das worktrees não é estritamente ASCII sem espaços ou acentos: '$base'."
  fi

  check_no_ancestor_symlinks "$base"

  # Confirma propriedade pelo usuário atual para HOME e subdiretórios existentes sob HOME
  local current_uid=""
  current_uid="$(id -u)" || die_env "Falha ao consultar UID do usuário atual via 'id -u'."

  local owner_check_dir=""
  for owner_check_dir in "$home_clean" "${home_clean}/source" "$base"; do
    if [[ -e "$owner_check_dir" ]]; then
      local dir_uid=""
      dir_uid="$(stat -c '%u' -- "$owner_check_dir")" \
        || die_env "Falha ao consultar proprietário do diretório '$owner_check_dir' via 'stat'."
      if [[ "$dir_uid" != "$current_uid" ]]; then
        die_blocked "Diretório '$owner_check_dir' pertence ao UID $dir_uid, divergente do usuário atual ($current_uid)."
      fi
    fi
  done

  BASE_WORKTREE_DIR="$base"
}

# ------------------------------------------------------------------------------
# Inspeção do repositório Git e lista de worktrees
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
  local has_record=0
  local index=0
  local line=""
  local fd=""
  local pid=""
  local rc=0

  exec {fd}< <(trap - ERR; git_c worktree list --porcelain -z)
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
        if (( index == 0 )); then
          WT_IS_MAIN+=(1)
        else
          WT_IS_MAIN+=(0)
        fi
        index=$((index + 1))
        wt_path="" wt_head="" wt_branch="" wt_detached=0 wt_bare=0 wt_locked=0 wt_prunable=0
        has_record=0
      fi
      continue
    fi

    has_record=1
    case "$line" in
      "worktree "*)
        wt_path="${line#worktree }"
        ;;
      "HEAD "*)
        wt_head="${line#HEAD }"
        ;;
      "branch "*)
        wt_branch="${line#branch }"
        ;;
      "detached")
        wt_detached=1
        ;;
      "bare")
        wt_bare=1
        ;;
      "locked"|"locked "*)
        wt_locked=1
        ;;
      "prunable"|"prunable "*)
        wt_prunable=1
        ;;
      *)
        # Atributos futuros desconhecidos são ignorados de forma segura
        ;;
    esac
  done

  exec {fd}<&-
  wait "$pid" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao consultar 'git worktree list --porcelain -z' (código $rc)."
  fi

  # Flush de segurança caso o último registro não seja seguido de linha vazia extra
  if (( has_record == 1 )); then
    WT_PATHS+=("$wt_path")
    WT_HEADS+=("$wt_head")
    WT_BRANCHES+=("$wt_branch")
    WT_DETACHED+=("$wt_detached")
    WT_BARE+=("$wt_bare")
    WT_LOCKED+=("$wt_locked")
    WT_PRUNABLE+=("$wt_prunable")
    if (( index == 0 )); then
      WT_IS_MAIN+=(1)
    else
      WT_IS_MAIN+=(0)
    fi
  fi

  if (( ${#WT_PATHS[@]} == 0 )); then
    die_env "Nenhuma worktree retornada pelo Git."
  fi
}

resolve_repo_context() {
  if ! git_c rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    die_env "Execute este utilitário dentro de uma worktree válida do repositório Git."
  fi

  local toplevel_raw="" common_raw=""
  toplevel_raw="$(git_c rev-parse --show-toplevel)" || die_env "Não foi possível resolver a raiz da worktree atual."
  common_raw="$(git_c rev-parse --path-format=absolute --git-common-dir)" || die_env "Não foi possível resolver o diretório Git comum."

  CURRENT_TOPLEVEL="$(realpath -e -- "$toplevel_raw")" || die_env "Falha ao normalizar o caminho da worktree atual."
  REPO_COMMON_DIR="$(realpath -e -- "$common_raw")" || die_env "Falha ao normalizar o diretório Git comum."

  load_worktrees_list

  MAIN_WORKTREE_PATH="${WT_PATHS[0]}"
  if (( WT_BARE[0] == 1 )); then
    die_blocked "Repositório bare não é suportado como worktree principal."
  fi
  if [[ ! -d "$MAIN_WORKTREE_PATH" ]]; then
    die_env "Diretório da worktree principal não está acessível: '$MAIN_WORKTREE_PATH'."
  fi
  MAIN_WORKTREE_REALPATH="$(realpath -e -- "$MAIN_WORKTREE_PATH")" || die_env "Falha ao normalizar o caminho da worktree principal."
}

# Valida o vínculo .git de uma worktree sem permitir fallback para um repositório pai.
# Preenche LAST_INSPECT_ERROR e retorna 1 em caso de inconsistência; 0 se íntegro.
verify_worktree_git_link() {
  local wt_path="$1"
  local expect_main="$2"
  LAST_INSPECT_ERROR=""

  if [[ ! -d "$wt_path" ]]; then
    LAST_INSPECT_ERROR="diretório ausente ou inacessível"
    return 1
  fi

  local dot_git="${wt_path}/.git"
  if [[ -L "$dot_git" ]]; then
    LAST_INSPECT_ERROR="entrada '.git' é um symlink inesperado"
    return 1
  fi

  if (( expect_main == 1 )); then
    if [[ ! -d "$dot_git" && ! -f "$dot_git" ]]; then
      LAST_INSPECT_ERROR="entrada '.git' ausente na worktree principal"
      return 1
    fi
  else
    if [[ -d "$dot_git" ]]; then
      LAST_INSPECT_ERROR="'.git' é um diretório (possível clone independente, não uma worktree vinculada)"
      return 1
    fi
    if [[ ! -f "$dot_git" ]]; then
      LAST_INSPECT_ERROR="arquivo de vínculo '.git' ausente ou inválido"
      return 1
    fi
  fi

  local wt_real="" toplevel_raw="" toplevel_real="" common_raw="" common_real=""
  if ! wt_real="$(realpath -e -- "$wt_path" 2>/dev/null)"; then
    LAST_INSPECT_ERROR="não foi possível normalizar o caminho da worktree"
    return 1
  fi

  if ! toplevel_raw="$(git_c -C "$wt_path" rev-parse --show-toplevel 2>/dev/null)"; then
    LAST_INSPECT_ERROR="Git não reconhece '.git' válido nesta worktree"
    return 1
  fi
  if ! toplevel_real="$(realpath -e -- "$toplevel_raw" 2>/dev/null)"; then
    LAST_INSPECT_ERROR="não foi possível normalizar a raiz reportada pelo Git"
    return 1
  fi
  if [[ "$toplevel_real" != "$wt_real" ]]; then
    LAST_INSPECT_ERROR="raiz Git resolvida ('${toplevel_real}') diverge da worktree ('${wt_real}'; possível fallback para repositório pai)"
    return 1
  fi

  if ! common_raw="$(git_c -C "$wt_path" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)"; then
    LAST_INSPECT_ERROR="não foi possível consultar o diretório Git comum da worktree"
    return 1
  fi
  if ! common_real="$(realpath -e -- "$common_raw" 2>/dev/null)"; then
    LAST_INSPECT_ERROR="não foi possível normalizar o diretório Git comum da worktree"
    return 1
  fi
  if [[ "$common_real" != "$REPO_COMMON_DIR" ]]; then
    LAST_INSPECT_ERROR="worktree pertence a outro repositório Git comum ('${common_real}')"
    return 1
  fi

  return 0
}

# Inspeciona o status de uma worktree sem modificar nenhum arquivo e sem usar arrays
# associativos (seguro para nomes com colchetes, espaços, acentos e metacaracteres).
# Preenche STATUS_TRACKED_PATHS, STATUS_STAGED_PATHS, STATUS_UNSTAGED_PATHS e
# STATUS_UNTRACKED_PATHS. Em erro, preenche LAST_INSPECT_ERROR e retorna 1.
inspect_worktree_status() {
  local wt_path="$1"

  STATUS_TRACKED_PATHS=()
  STATUS_STAGED_PATHS=()
  STATUS_UNSTAGED_PATHS=()
  STATUS_UNTRACKED_PATHS=()
  LAST_INSPECT_ERROR=""

  local entry="" x="" y="" path="" skip_old_path=""
  local fd="" pid="" rc=0

  exec {fd}< <(trap - ERR; git_c -C "$wt_path" status --porcelain=v1 -z --untracked-files=all 2>/dev/null)
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

    # Em rename/copy no índice (R ou C), o Git emite o caminho original logo em seguida
    if [[ "$x" == "R" || "$x" == "C" ]]; then
      IFS= read -r -u "$fd" -d '' skip_old_path || true
    fi

    if [[ "$x" == "?" && "$y" == "?" ]]; then
      STATUS_UNTRACKED_PATHS+=("$path")
      continue
    fi

    # Em `git status --porcelain=v1 -z`, cada caminho rastreado alterado aparece
    # uma única vez com os dois indicadores XY, dispensando array associativo.
    STATUS_TRACKED_PATHS+=("$path")

    if [[ "$x" != " " ]]; then
      STATUS_STAGED_PATHS+=("$path")
    fi

    if [[ "$y" != " " ]]; then
      STATUS_UNSTAGED_PATHS+=("$path")
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

# ------------------------------------------------------------------------------
# Detecção heurística de arquivos ignorados potencialmente sensíveis
# (baseada exclusivamente em nomes/extensões, sem abrir ou ler conteúdo)
# ------------------------------------------------------------------------------

# Diretórios de build, dependências e cache que não devem ser classificados
# indiscriminadamente como sensíveis
is_known_build_or_cache_dir() {
  local rel_dir="$1"
  local clean_dir="${rel_dir%/}"
  local base_name="${clean_dir##*/}"

  case "$base_name" in
    build|target|dist|out|coverage|node_modules|.gradle|.dart_tool|.pub-cache|.cache|.vite|__pycache__|.pytest_cache|.mypy_cache|.next|.nuxt|.turbo)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# Classifica apenas pelo nome do arquivo (nunca abre nem lê conteúdo).
# Usa conversão nativa de caixa do Bash 4.4+ sob LC_ALL=C (sem dependência de `tr`).
# Imprime o motivo em stdout e retorna 0 se sensível; retorna 1 caso contrário.
classify_sensitive_filename() {
  local rel_path="$1"
  local base_name="${rel_path##*/}"
  local LC_ALL=C
  local lower_name="${base_name,,}"

  case "$lower_name" in
    .env|.env.*)
      printf '%s' "arquivo de variáveis de ambiente (.env / .env.*)"
      return 0
      ;;
    .envrc)
      printf '%s' "arquivo de ambiente direnv (.envrc)"
      return 0
      ;;
    secrets.env|*.env)
      printf '%s' "arquivo de variáveis/segredos de ambiente (*.env / secrets.env)"
      return 0
      ;;
    *.jks)
      printf '%s' "keystore Java (*.jks)"
      return 0
      ;;
    *.keystore)
      printf '%s' "keystore de assinatura (*.keystore)"
      return 0
      ;;
    *.pem)
      printf '%s' "certificado ou chave privada (*.pem)"
      return 0
      ;;
    *.crt|*.cer)
      printf '%s' "certificado digital (*.crt / *.cer)"
      return 0
      ;;
    *.der)
      printf '%s' "certificado ou chave em formato DER (*.der)"
      return 0
      ;;
    *.key)
      printf '%s' "chave privada (*.key)"
      return 0
      ;;
    *.p12|*.pfx)
      printf '%s' "certificado/chave PKCS#12 (*.p12 / *.pfx)"
      return 0
      ;;
    *.p8|*.ppk|*.kdbx)
      printf '%s' "chave privada ou cofre de credenciais (*.p8 / *.ppk / *.kdbx)"
      return 0
      ;;
    id_rsa|id_ed25519|id_ecdsa|id_dsa)
      printf '%s' "chave privada SSH conhecida ($base_name)"
      return 0
      ;;
    .netrc|.pgpass|.npmrc|.pypirc|.htpasswd)
      printf '%s' "arquivo de credenciais conhecido ($base_name)"
      return 0
      ;;
    google-services.json|googleservice-info.plist|key.properties)
      printf '%s' "arquivo de credenciais/assinatura mobile ($base_name)"
      return 0
      ;;
    local.properties)
      printf '%s' "arquivo de propriedades locais potencialmente sensível (local.properties)"
      return 0
      ;;
    credentials.json|credentials.yml|credentials.yaml|credentials.properties|\
    secrets.json|secrets.yml|secrets.yaml|secrets.properties|\
    client_secret*.json|service-account*.json|service_account*.json|\
    *firebase-adminsdk*.json|\
    application-secret*.yml|application-secret*.yaml|application-secret*.properties|\
    *.secret|*.secrets|*secret*.json|*secret*.yml|*secret*.yaml|\
    *credential*.json|*credential*.yml|*credential*.yaml|\
    *private*key*|*.tfstate|*.tfstate.backup|*.tfvars)
      printf '%s' "arquivo de credenciais ou segredos conhecido ($base_name)"
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# Varre arquivos e diretórios ignorados na worktree sem ler conteúdo e sem
# seguir symlinks para fora da árvore auditada.
# Preenche SENSITIVE_FINDINGS. Em falha de permissão ou de listagem Git,
# preenche LAST_SCAN_ERROR e retorna 1 (deixando o chamador decidir o tratamento).
scan_sensitive_ignored() {
  local wt_path="$1"
  SENSITIVE_FINDINGS=()
  LAST_SCAN_ERROR=""

  local entry="" abs_entry="" reason="" sub_abs="" sub_rel=""
  local fd="" pid="" rc=0
  local find_fd="" find_pid="" find_rc=0

  exec {fd}< <(trap - ERR; git_c -C "$wt_path" ls-files --others --ignored --exclude-standard --directory -z 2>/dev/null)
  pid=$!

  while IFS= read -r -u "$fd" -d '' entry; do
    if [[ -z "$entry" ]]; then
      continue
    fi

    # Diretório inteiramente ignorado reportado com '/' final
    if [[ "$entry" == */ ]]; then
      local rel_dir="${entry%/}"
      if is_known_build_or_cache_dir "$rel_dir"; then
        continue
      fi

      abs_entry="${wt_path}/${rel_dir}"
      # Nunca seguir symlinks de diretório
      if [[ -L "$abs_entry" ]]; then
        if reason="$(classify_sensitive_filename "$rel_dir")"; then
          SENSITIVE_FINDINGS+=("${rel_dir} — ${reason} (symlink não seguido)")
        fi
        continue
      fi

      if [[ -d "$abs_entry" ]]; then
        find_rc=0
        exec {find_fd}< <(trap - ERR; find -P "$abs_entry" -mindepth 1 \( -type f -o -type l \) -print0 2>/dev/null)
        find_pid=$!
        while IFS= read -r -u "$find_fd" -d '' sub_abs; do
          sub_rel="${sub_abs#"${wt_path}/"}"
          if reason="$(classify_sensitive_filename "$sub_rel")"; then
            SENSITIVE_FINDINGS+=("${sub_rel} — ${reason}")
          fi
        done
        exec {find_fd}<&-
        wait "$find_pid" || find_rc=$?
        if (( find_rc != 0 )); then
          exec {fd}<&-
          wait "$pid" || true
          LAST_SCAN_ERROR="não foi possível auditar completamente o diretório ignorado '${rel_dir}' (permissão insuficiente ou erro de leitura)"
          return 1
        fi
      fi
      continue
    fi

    # Arquivo regular ou symlink ignorado
    if reason="$(classify_sensitive_filename "$entry")"; then
      abs_entry="${wt_path}/${entry}"
      if [[ -L "$abs_entry" ]]; then
        SENSITIVE_FINDINGS+=("${entry} — ${reason} (symlink não seguido)")
      else
        SENSITIVE_FINDINGS+=("${entry} — ${reason}")
      fi
    fi
  done

  exec {fd}<&-
  wait "$pid" || rc=$?
  if (( rc != 0 )); then
    LAST_SCAN_ERROR="falha ao listar arquivos ignorados via 'git ls-files' (código ${rc})"
    return 1
  fi

  return 0
}

# ------------------------------------------------------------------------------
# Subcomando: CREATE
# ------------------------------------------------------------------------------

cmd_create() {
  local slug=""
  local base_sha=""
  local allow_dirty_main=0
  local slug_set=0
  local base_set=0
  local dirty_set=0

  while (( $# > 0 )); do
    case "$1" in
      -h|--help)
        print_usage
        exit "$EXIT_OK"
        ;;
      --slug)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --slug exige um valor."
        slug="$2"
        slug_set=1
        shift 2
        ;;
      --slug=*)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        slug="${1#--slug=}"
        slug_set=1
        shift
        ;;
      --base)
        (( base_set == 0 )) || die_usage "Opção --base informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --base exige um valor."
        base_sha="$2"
        base_set=1
        shift 2
        ;;
      --base=*)
        (( base_set == 0 )) || die_usage "Opção --base informada mais de uma vez."
        base_sha="${1#--base=}"
        base_set=1
        shift
        ;;
      --allow-dirty-main)
        (( dirty_set == 0 )) || die_usage "Opção --allow-dirty-main informada mais de uma vez."
        allow_dirty_main=1
        dirty_set=1
        shift
        ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'create': $1"
        ;;
    esac
  done

  (( slug_set == 1 )) || die_usage "Opção obrigatória ausente em 'create': --slug <slug>."
  (( base_set == 1 )) || die_usage "Opção obrigatória ausente em 'create': --base <sha>."

  validate_slug "$slug"
  validate_sha_format "$base_sha"
  resolve_repo_context
  validate_base_worktree_dir

  # Exige execução a partir da worktree principal
  if [[ "$CURRENT_TOPLEVEL" != "$MAIN_WORKTREE_REALPATH" ]]; then
    die_blocked "O comando 'create' deve ser executado a partir da worktree principal ('$MAIN_WORKTREE_PATH'), e não de '$CURRENT_TOPLEVEL'."
  fi

  # Confirma vínculo direto do .git principal (não worktree secundária)
  local current_git_dir_raw="" current_git_dir=""
  current_git_dir_raw="$(git_c rev-parse --path-format=absolute --git-dir)" \
    || die_env "Não foi possível consultar '--git-dir' da worktree atual."
  current_git_dir="$(realpath -e -- "$current_git_dir_raw")" \
    || die_env "Falha ao normalizar '--git-dir' da worktree atual."
  if [[ "$current_git_dir" != "$REPO_COMMON_DIR" ]]; then
    die_blocked "Diretório atual não corresponde à worktree principal do repositório compartilhado."
  fi

  # Exige que a worktree principal esteja na branch main (não detached, não outra branch)
  local current_branch=""
  local sym_rc=0
  current_branch="$(git_c -C "$MAIN_WORKTREE_PATH" symbolic-ref --quiet --short HEAD)" || sym_rc=$?
  if (( sym_rc == 1 )); then
    die_blocked "A worktree principal está em DETACHED HEAD. É obrigatório estar na branch '${MAIN_BRANCH}'."
  elif (( sym_rc != 0 )); then
    die_env "Falha ao consultar a branch ativa da worktree principal (código ${sym_rc})."
  fi
  if [[ "$current_branch" != "$MAIN_BRANCH" ]]; then
    die_blocked "A worktree principal está na branch '${current_branch}'. É obrigatório estar na branch '${MAIN_BRANCH}'."
  fi

  # Confirma existência da branch local main
  if ! git_ref_exists "$MAIN_WORKTREE_PATH" "refs/heads/${MAIN_BRANCH}"; then
    die_blocked "A branch local '${MAIN_BRANCH}' não foi encontrada."
  fi

  # Valida existência local do SHA-base e recusa objetos que não sejam commit (ex.: blob, tree)
  local resolved_obj=""
  if ! resolved_obj="$(git_c -C "$MAIN_WORKTREE_PATH" rev-parse --verify --quiet "${base_sha}^{object}")"; then
    die_blocked "O SHA base '${base_sha}' não existe localmente neste repositório."
  fi

  local obj_type=""
  obj_type="$(git_c -C "$MAIN_WORKTREE_PATH" cat-file -t "$resolved_obj" 2>/dev/null)" \
    || die_env "Falha ao consultar o tipo do objeto Git '${resolved_obj}'."
  if [[ "$obj_type" != "commit" ]]; then
    die_blocked "O SHA base '${base_sha}' aponta para um objeto do tipo '${obj_type}', não um commit válido."
  fi

  local full_base_sha="$resolved_obj"

  # Valida que o SHA pertence ao histórico local da main (ancestralidade direta)
  if ! git_is_ancestor "$MAIN_WORKTREE_PATH" "$full_base_sha" "refs/heads/${MAIN_BRANCH}"; then
    die_blocked "O SHA '${full_base_sha}' não pertence ao histórico local da branch '${MAIN_BRANCH}'."
  fi

  local target_branch="${BRANCH_PREFIX}${slug}"
  local target_ref="refs/heads/${target_branch}"
  local target_dir="${BASE_WORKTREE_DIR}/${slug}"

  # Impede branch preexistente
  if git_ref_exists "$MAIN_WORKTREE_PATH" "$target_ref"; then
    die_blocked "A branch '${target_branch}' já existe localmente."
  fi

  # Impede destino preexistente ou symlink no destino
  if [[ -L "$target_dir" ]]; then
    die_blocked "O caminho de destino é um symlink: '$target_dir'."
  fi
  if [[ -e "$target_dir" ]]; then
    die_blocked "O diretório ou arquivo de destino já existe: '$target_dir'."
  fi

  # Impede worktrees duplicadas para a mesma tarefa (por caminho ou por branch)
  local i
  for (( i = 0; i < ${#WT_PATHS[@]}; i++ )); do
    if [[ "${WT_PATHS[i]}" == "$target_dir" ]]; then
      die_blocked "Já existe uma worktree registrada para o destino '$target_dir'."
    fi
    if [[ "${WT_BRANCHES[i]}" == "$target_ref" ]]; then
      die_blocked "A branch '${target_branch}' já está associada à worktree '${WT_PATHS[i]}'."
    fi
  done

  # Audita estado da worktree principal (main) sem modificar nenhum arquivo
  if ! inspect_worktree_status "$MAIN_WORKTREE_PATH"; then
    die_env "Não foi possível inspecionar o status da worktree principal: ${LAST_INSPECT_ERROR}."
  fi

  if (( ${#STATUS_STAGED_PATHS[@]} > 0 )); then
    msg_blocked "A worktree principal (${MAIN_BRANCH}) possui arquivos em staging. Criação recusada:"
    local p
    for p in "${STATUS_STAGED_PATHS[@]}"; do
      printf '  [staged] %s\n' "$p" >&2
    done
    exit "$EXIT_BLOCKED"
  fi

  if (( ${#STATUS_UNTRACKED_PATHS[@]} > 0 )); then
    msg_blocked "A worktree principal (${MAIN_BRANCH}) possui arquivos não rastreados (untracked). Criação recusada:"
    local p
    for p in "${STATUS_UNTRACKED_PATHS[@]}"; do
      printf '  [untracked] %s\n' "$p" >&2
    done
    exit "$EXIT_BLOCKED"
  fi

  if (( ${#STATUS_UNSTAGED_PATHS[@]} > 0 )); then
    msg_info "Modificações locais rastreadas e unstaged detectadas na worktree principal (${MAIN_BRANCH}):"
    local p
    for p in "${STATUS_UNSTAGED_PATHS[@]}"; do
      printf '  [modificado unstaged] %s\n' "$p"
    done

    if (( allow_dirty_main == 0 )); then
      die_blocked "A worktree principal está suja. Repita com --allow-dirty-main para autorizar a criação preservando intactas essas modificações na '${MAIN_BRANCH}'."
    fi

    msg_info "Opção --allow-dirty-main informada: as alterações locais da '${MAIN_BRANCH}' serão preservadas intactas e não serão incluídas na nova worktree."
  fi

  # Somente após todas as validações passarem, garante o diretório-base
  if [[ ! -d "$BASE_WORKTREE_DIR" ]]; then
    local mkdir_rc=0
    mkdir -p -- "$BASE_WORKTREE_DIR" || mkdir_rc=$?
    if (( mkdir_rc != 0 )); then
      die_env "Falha ao criar o diretório-base das worktrees '${BASE_WORKTREE_DIR}' (mkdir retornou ${mkdir_rc})."
    fi
    validate_base_worktree_dir
  fi

  # Revalida imediatamente antes de criar para evitar condição de corrida no destino
  if [[ -L "$target_dir" || -e "$target_dir" ]]; then
    die_blocked "O caminho de destino passou a existir antes da criação: '$target_dir'."
  fi

  # Cria worktree vinculada ao repositório Git compartilhado (sem git clone, sem fetch/pull).
  # Em caso de falha parcial, preserva diagnósticos do Git, nunca executa cleanup destrutivo
  # automático e retorna o código contratual 4 orientando inspeção manual.
  local add_rc=0
  git_c -C "$MAIN_WORKTREE_PATH" worktree add -b "$target_branch" "$target_dir" "$full_base_sha" || add_rc=$?
  if (( add_rc != 0 )); then
    msg_err "Falha ao executar 'git worktree add' para '${target_dir}' (código Git ${add_rc})."
    msg_err "ATENÇÃO: pode existir estado residual parcial (diretório '${target_dir}', registro em 'git worktree list' ou branch '${target_branch}')."
    msg_err "Nenhuma limpeza destrutiva automática foi executada. Inspecione manualmente antes de tentar novamente:"
    msg_err "  git worktree list --porcelain"
    msg_err "  git show-ref --heads"
    msg_err "  ls -la \"${BASE_WORKTREE_DIR}\""
    exit "$EXIT_ENV"
  fi

  msg_info ""
  msg_info "Worktree criada com sucesso:"
  msg_info "  Caminho      : $target_dir"
  msg_info "  Branch       : $target_branch"
  msg_info "  SHA-base     : $full_base_sha"
  msg_info "  Git comum    : $REPO_COMMON_DIR"
  msg_info ""
  msg_info "Instruções iniciais (preflight obrigatório na nova worktree):"
  msg_info "  cd \"$target_dir\""
  msg_info "  pwd && realpath ."
  msg_info "  git rev-parse --abbrev-ref HEAD && git rev-parse HEAD"
  msg_info "  git status --short --untracked-files=all"
}

# ------------------------------------------------------------------------------
# Subcomando: STATUS (estritamente READ-ONLY e resiliente a worktrees individuais)
# ------------------------------------------------------------------------------

cmd_status() {
  while (( $# > 0 )); do
    case "$1" in
      -h|--help)
        print_usage
        exit "$EXIT_OK"
        ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'status': $1"
        ;;
    esac
  done

  resolve_repo_context

  local has_local_main=0
  local main_ref_sha=""
  if git_ref_exists "$MAIN_WORKTREE_PATH" "refs/heads/${MAIN_BRANCH}"; then
    has_local_main=1
    main_ref_sha="$(git_c -C "$MAIN_WORKTREE_PATH" rev-parse --verify "refs/heads/${MAIN_BRANCH}")" \
      || die_env "Falha ao resolver o SHA da branch local '${MAIN_BRANCH}'."
  fi

  msg_info "=== Status das Worktrees do Rewit (READ-ONLY) ==="
  msg_info "Repositório comum : $REPO_COMMON_DIR"
  if (( has_local_main == 1 )); then
    msg_info "Branch main local : $main_ref_sha"
  else
    msg_info "Branch main local : AUSENTE"
  fi
  msg_info "Total registradas : ${#WT_PATHS[@]}"
  msg_info ""

  local unverifiable_count=0
  local i
  for (( i = 0; i < ${#WT_PATHS[@]}; i++ )); do
    local wt_path="${WT_PATHS[i]}"
    local wt_head="${WT_HEADS[i]}"
    local wt_branch_ref="${WT_BRANCHES[i]}"
    local wt_detached="${WT_DETACHED[i]}"
    local wt_bare="${WT_BARE[i]}"
    local wt_locked="${WT_LOCKED[i]}"
    local wt_prunable="${WT_PRUNABLE[i]}"
    local wt_is_main="${WT_IS_MAIN[i]}"
    local wt_unverifiable=0

    local principal_label="não"
    if (( wt_is_main == 1 )); then
      principal_label="sim"
    fi

    local branch_label=""
    if (( wt_bare == 1 )); then
      branch_label="BARE"
    elif (( wt_detached == 1 )); then
      branch_label="DETACHED HEAD"
    elif [[ -n "$wt_branch_ref" ]]; then
      branch_label="${wt_branch_ref#refs/heads/}"
    else
      branch_label="DESCONHECIDA"
    fi

    msg_info "----------------------------------------------------------------------"
    msg_info "Caminho                : $wt_path"
    msg_info "Worktree principal     : $principal_label"
    msg_info "Branch                 : $branch_label"
    msg_info "HEAD                   : ${wt_head:-DESCONHECIDO}"

    if (( wt_locked == 1 )); then
      msg_info "Bloqueio Git (locked)  : sim"
    fi
    if (( wt_prunable == 1 )); then
      msg_info "Registro prunable      : sim"
    fi

    # Worktree prunable ou com diretório ausente: marca como não verificável e continua
    if (( wt_prunable == 1 )) || [[ ! -d "$wt_path" ]]; then
      unverifiable_count=$((unverifiable_count + 1))
      if (( wt_prunable == 1 )) && [[ ! -d "$wt_path" ]]; then
        msg_info "Estado da árvore       : INCONSISTENTE / PRUNABLE (diretório ausente)"
      elif (( wt_prunable == 1 )); then
        msg_info "Estado da árvore       : INCONSISTENTE / PRUNABLE"
      else
        msg_info "Estado da árvore       : INCONSISTENTE / DIRETÓRIO AUSENTE"
      fi
      msg_info "Alterações rastreadas  : não verificável"
      msg_info "Arquivos untracked     : não verificável"
      msg_info "Commits exclusivos     : não verificável"
      msg_info "Ancestral da main      : não verificável"
      msg_info "Ignorados sensíveis    : não verificável"
      continue
    fi

    # Valida .git da worktree antes de qualquer comando Git dentro dela
    if ! verify_worktree_git_link "$wt_path" "$wt_is_main"; then
      unverifiable_count=$((unverifiable_count + 1))
      msg_info "Estado da árvore       : INCONSISTENTE / NÃO VERIFICÁVEL (${LAST_INSPECT_ERROR})"
      msg_info "Alterações rastreadas  : não verificável"
      msg_info "Arquivos untracked     : não verificável"
      msg_info "Commits exclusivos     : não verificável"
      msg_info "Ancestral da main      : não verificável"
      msg_info "Ignorados sensíveis    : não verificável"
      continue
    fi

    # Inspeciona status da árvore de trabalho
    if inspect_worktree_status "$wt_path"; then
      local tracked_count="${#STATUS_TRACKED_PATHS[@]}"
      local staged_count="${#STATUS_STAGED_PATHS[@]}"
      local unstaged_count="${#STATUS_UNSTAGED_PATHS[@]}"
      local untracked_count="${#STATUS_UNTRACKED_PATHS[@]}"

      local cleanliness="limpa"
      if (( tracked_count > 0 || untracked_count > 0 )); then
        cleanliness="suja"
      fi

      msg_info "Estado da árvore       : $cleanliness"
      msg_info "Alterações rastreadas  : $tracked_count (staged: $staged_count, unstaged: $unstaged_count)"
      msg_info "Arquivos untracked     : $untracked_count"
    else
      wt_unverifiable=1
      msg_info "Estado da árvore       : NÃO VERIFICÁVEL (${LAST_INSPECT_ERROR})"
      msg_info "Alterações rastreadas  : não verificável"
      msg_info "Arquivos untracked     : não verificável"
    fi

    # Calcula commits exclusivos e ancestralidade real em relação à main local
    local exclusive_label="não verificável (branch main ausente)"
    local ancestor_label="não verificável"
    if (( has_local_main == 1 )) && [[ -n "$wt_head" ]]; then
      if git_c -C "$MAIN_WORKTREE_PATH" rev-parse --verify --quiet "${wt_head}^{commit}" >/dev/null 2>&1; then
        local exclusive_commits="" rev_rc=0
        exclusive_commits="$(git_c -C "$MAIN_WORKTREE_PATH" rev-list --count "refs/heads/${MAIN_BRANCH}..${wt_head}" 2>/dev/null)" || rev_rc=$?
        if (( rev_rc == 0 )); then
          exclusive_label="$exclusive_commits"
          local anc_rc=0
          git_c -C "$MAIN_WORKTREE_PATH" merge-base --is-ancestor "$wt_head" "refs/heads/${MAIN_BRANCH}" || anc_rc=$?
          if (( anc_rc == 0 )); then
            ancestor_label="sim (ancestral direto da main local)"
          elif (( anc_rc == 1 )); then
            ancestor_label="não (não é ancestral direto da main local)"
          else
            wt_unverifiable=1
            ancestor_label="não verificável (erro Git ${anc_rc})"
          fi
        else
          wt_unverifiable=1
          exclusive_label="não verificável (erro em git rev-list: ${rev_rc})"
        fi
      else
        wt_unverifiable=1
        exclusive_label="não verificável (HEAD inválido)"
      fi
    else
      wt_unverifiable=1
    fi

    msg_info "Commits exclusivos     : $exclusive_label"
    msg_info "Ancestral da main      : $ancestor_label"

    # Audita ignorados sensíveis sem abortar o restante da listagem em caso de erro de permissão
    if scan_sensitive_ignored "$wt_path"; then
      local sensitive_count="${#SENSITIVE_FINDINGS[@]}"
      if (( sensitive_count == 0 )); then
        msg_info "Ignorados sensíveis    : não"
      else
        msg_info "Ignorados sensíveis    : sim ($sensitive_count detectado(s))"
        local finding
        for finding in "${SENSITIVE_FINDINGS[@]}"; do
          msg_info "  - $finding"
        done
      fi
    else
      wt_unverifiable=1
      msg_info "Ignorados sensíveis    : NÃO VERIFICÁVEL (${LAST_SCAN_ERROR})"
    fi

    if (( wt_unverifiable == 1 )); then
      unverifiable_count=$((unverifiable_count + 1))
    fi
  done

  msg_info "----------------------------------------------------------------------"

  if (( unverifiable_count > 0 )); then
    msg_blocked "${unverifiable_count} worktree(s) não puderam ser verificadas completamente."
    exit "$EXIT_BLOCKED"
  fi
}

# ------------------------------------------------------------------------------
# Auditoria conservadora e subcomando: CLOSE
# ------------------------------------------------------------------------------

# Verifica idempotência limpa vs. inconsistência estrutural para o slug informado.
# Retorna:
#   0  — worktree registrada e presente para prosseguir para auditoria completa
#   10 — worktree já encerrada de forma limpa (idempotente)
#   (Em inconsistência estrutural, encerra com EXIT_BLOCKED)
check_close_registration_state() {
  local slug="$1"

  resolve_repo_context
  validate_base_worktree_dir

  AUDIT_TARGET_PATH="${BASE_WORKTREE_DIR}/${slug}"
  AUDIT_TARGET_BRANCH="${BRANCH_PREFIX}${slug}"
  AUDIT_TARGET_REF="refs/heads/${AUDIT_TARGET_BRANCH}"

  # Nunca aceitar symlink no caminho de destino
  if [[ -L "$AUDIT_TARGET_PATH" ]]; then
    die_blocked "O caminho derivado para o slug '${slug}' é um symlink: '${AUDIT_TARGET_PATH}'."
  fi

  local path_match_idx=-1
  local path_match_count=0
  local branch_match_idx=-1
  local branch_match_count=0
  local target_realpath=""
  if [[ -e "$AUDIT_TARGET_PATH" ]]; then
    target_realpath="$(realpath -e -- "$AUDIT_TARGET_PATH" 2>/dev/null || true)"
  fi

  local i wt_p wt_rp
  for (( i = 0; i < ${#WT_PATHS[@]}; i++ )); do
    wt_p="${WT_PATHS[i]}"
    wt_rp=""
    if [[ -e "$wt_p" ]]; then
      wt_rp="$(realpath -e -- "$wt_p" 2>/dev/null || true)"
    fi

    if [[ "$wt_p" == "$AUDIT_TARGET_PATH" ]] || [[ -n "$target_realpath" && -n "$wt_rp" && "$wt_rp" == "$target_realpath" ]]; then
      path_match_idx="$i"
      path_match_count=$((path_match_count + 1))
    fi

    if [[ "${WT_BRANCHES[i]}" == "$AUDIT_TARGET_REF" ]]; then
      branch_match_idx="$i"
      branch_match_count=$((branch_match_count + 1))
    fi
  done

  # Caso: nenhuma worktree registrada no caminho esperado e nenhuma com a branch esperada
  if (( path_match_count == 0 && branch_match_count == 0 )); then
    if [[ -e "$AUDIT_TARGET_PATH" ]]; then
      if [[ -d "${AUDIT_TARGET_PATH}/.git" ]]; then
        die_blocked "Associação incompleta: '${AUDIT_TARGET_PATH}' contém um diretório '.git' (possível clone independente) e não é uma worktree registrada."
      fi
      die_blocked "Associação incompleta: existe diretório/arquivo em '${AUDIT_TARGET_PATH}', mas ele não está registrado em 'git worktree list'."
    fi
    return 10
  fi

  # Verifica worktree principal antes de qualquer outra coisa
  if (( path_match_idx == 0 || branch_match_idx == 0 )); then
    die_blocked "A worktree principal nunca pode ser removida pelo comando 'close'."
  fi

  if (( path_match_count > 1 || branch_match_count > 1 )); then
    die_blocked "Associação Git inconsistente: múltiplos registros encontrados para '${slug}'."
  fi

  # Branch registrada em caminho diferente do esperado
  if (( branch_match_count == 1 && path_match_count == 0 )); then
    die_blocked "A branch '${AUDIT_TARGET_BRANCH}' está anexada a uma worktree fora do caminho esperado: '${WT_PATHS[branch_match_idx]}' (esperado: '${AUDIT_TARGET_PATH}')."
  fi

  # Caminho registrado, mas estado inconsistente ou branch divergente
  if (( path_match_count == 1 )); then
    if (( WT_IS_MAIN[path_match_idx] == 1 )); then
      die_blocked "A worktree principal nunca pode ser removida."
    fi
    if [[ "${WT_PATHS[path_match_idx]}" != "$AUDIT_TARGET_PATH" ]]; then
      die_blocked "Caminho registrado '${WT_PATHS[path_match_idx]}' diverge do caminho derivado esperado '${AUDIT_TARGET_PATH}'."
    fi
    if (( WT_PRUNABLE[path_match_idx] == 1 )) || [[ ! -d "$AUDIT_TARGET_PATH" ]]; then
      die_blocked "Associação incompleta/prunable para '${AUDIT_TARGET_PATH}'. O utilitário não executa 'git worktree prune' automaticamente."
    fi
    if (( WT_LOCKED[path_match_idx] == 1 )); then
      die_blocked "A worktree '${AUDIT_TARGET_PATH}' está bloqueada (locked) no Git."
    fi
    if (( WT_DETACHED[path_match_idx] == 1 )); then
      die_blocked "A worktree '${AUDIT_TARGET_PATH}' está em DETACHED HEAD (esperada branch '${AUDIT_TARGET_BRANCH}')."
    fi
    if [[ "${WT_BRANCHES[path_match_idx]}" != "$AUDIT_TARGET_REF" ]]; then
      die_blocked "Associação de branch divergente em '${AUDIT_TARGET_PATH}': encontrada '${WT_BRANCHES[path_match_idx]:-nenhuma}', esperada '${AUDIT_TARGET_REF}'."
    fi
    if (( branch_match_idx != path_match_idx )); then
      die_blocked "Inconsistência de associação entre caminho e branch para o slug '${slug}'."
    fi
  fi

  return 0
}

# Audita integralmente todas as salvaguardas obrigatórias de encerramento.
# Interrompe imediatamente em .git ausente/inválido para evitar resolução em repo pai.
# Acumula motivos em AUDIT_BLOCKERS; retorna 0 se 100% aprovada ou 1 se houver bloqueios.
audit_worktree_for_close() {
  local slug="$1"

  AUDIT_BLOCKERS=()
  AUDIT_WT_HEAD=""
  AUDIT_MAIN_HEAD=""

  local reg_rc=0
  check_close_registration_state "$slug" || reg_rc=$?
  if (( reg_rc == 10 )); then
    AUDIT_BLOCKERS+=("A worktree '${AUDIT_TARGET_PATH}' deixou de existir durante a validação.")
    return 1
  fi

  # Bloqueia se o operador estiver dentro da própria worktree que será removida
  if [[ "$CURRENT_TOPLEVEL" == "$AUDIT_TARGET_PATH" ]]; then
    AUDIT_BLOCKERS+=("Não é permitido encerrar a worktree a partir de dentro dela mesma. Execute a partir da worktree principal.")
    return 1
  fi

  # Valida imediatamente o vínculo .git antes de rodar qualquer comando Git dentro da worktree
  # (impede fallback acidental para um repositório pai ou operação sobre clone independente)
  if ! verify_worktree_git_link "$AUDIT_TARGET_PATH" 0; then
    AUDIT_BLOCKERS+=("Vínculo '.git' inválido em '${AUDIT_TARGET_PATH}': ${LAST_INSPECT_ERROR}.")
    return 1
  fi

  # Confirma branch simbólica ativa na worktree e consistência com refs/heads/agent/neon/<slug>
  local wt_symref="" sym_rc=0
  wt_symref="$(git_c -C "$AUDIT_TARGET_PATH" symbolic-ref --quiet HEAD 2>/dev/null)" || sym_rc=$?
  if (( sym_rc != 0 )); then
    AUDIT_BLOCKERS+=("A worktree '${AUDIT_TARGET_PATH}' está em DETACHED HEAD ou com HEAD ilegível.")
  elif [[ "$wt_symref" != "$AUDIT_TARGET_REF" ]]; then
    AUDIT_BLOCKERS+=("Branch ativa da worktree ('${wt_symref}') diverge do esperado ('${AUDIT_TARGET_REF}').")
  fi

  local wt_head="" branch_ref_head=""
  if ! wt_head="$(git_c -C "$AUDIT_TARGET_PATH" rev-parse --verify --quiet "HEAD^{commit}")"; then
    AUDIT_BLOCKERS+=("Não foi possível resolver o commit HEAD da worktree '${AUDIT_TARGET_PATH}'.")
  fi
  if ! branch_ref_head="$(git_c -C "$MAIN_WORKTREE_PATH" rev-parse --verify --quiet "${AUDIT_TARGET_REF}^{commit}")"; then
    AUDIT_BLOCKERS+=("A branch esperada '${AUDIT_TARGET_BRANCH}' não foi resolvida como commit local válido.")
  fi
  if [[ -n "$wt_head" && -n "$branch_ref_head" && "$wt_head" != "$branch_ref_head" ]]; then
    AUDIT_BLOCKERS+=("Inconsistência: HEAD da worktree ('${wt_head}') diverge do commit da branch '${AUDIT_TARGET_BRANCH}' ('${branch_ref_head}').")
  fi
  AUDIT_WT_HEAD="$wt_head"

  # Verifica branch main local
  if ! AUDIT_MAIN_HEAD="$(git_c -C "$MAIN_WORKTREE_PATH" rev-parse --verify --quiet "refs/heads/${MAIN_BRANCH}^{commit}")"; then
    AUDIT_BLOCKERS+=("A branch local '${MAIN_BRANCH}' não foi encontrada para comprovar integração.")
  fi

  # Verifica limpeza da árvore de trabalho (staged, unstaged, untracked)
  if inspect_worktree_status "$AUDIT_TARGET_PATH"; then
    if (( ${#STATUS_STAGED_PATHS[@]} > 0 )); then
      AUDIT_BLOCKERS+=("Existem ${#STATUS_STAGED_PATHS[@]} arquivo(s) em staging na worktree.")
    fi
    if (( ${#STATUS_UNSTAGED_PATHS[@]} > 0 )); then
      AUDIT_BLOCKERS+=("Existem ${#STATUS_UNSTAGED_PATHS[@]} modificação(ões) rastreada(s) não commitada(s) na worktree.")
    fi
    if (( ${#STATUS_UNTRACKED_PATHS[@]} > 0 )); then
      AUDIT_BLOCKERS+=("Existem ${#STATUS_UNTRACKED_PATHS[@]} arquivo(s) não rastreado(s) (untracked) na worktree.")
    fi
  else
    AUDIT_BLOCKERS+=("Não foi possível verificar com segurança o status da worktree: ${LAST_INSPECT_ERROR}.")
  fi

  # Verifica commits exclusivos e ancestralidade real em relação à main local
  # (nunca usa git cherry; exige ancestralidade direta comprovada por rev-list + merge-base)
  if [[ -n "$AUDIT_MAIN_HEAD" && -n "$wt_head" ]]; then
    local exclusive_wt="" rev_rc=0
    exclusive_wt="$(git_c -C "$MAIN_WORKTREE_PATH" rev-list --count "refs/heads/${MAIN_BRANCH}..${wt_head}" 2>/dev/null)" || rev_rc=$?
    if (( rev_rc != 0 )); then
      die_env "Falha inesperada ao calcular commits exclusivos via 'git rev-list' (código ${rev_rc})."
    fi
    if [[ "$exclusive_wt" != "0" ]]; then
      AUDIT_BLOCKERS+=("Existem ${exclusive_wt} commit(s) exclusivo(s) na worktree ainda não integrados como ancestrais diretos da '${MAIN_BRANCH}' local.")
    elif ! git_is_ancestor "$MAIN_WORKTREE_PATH" "$wt_head" "refs/heads/${MAIN_BRANCH}"; then
      AUDIT_BLOCKERS+=("O HEAD da worktree ('${wt_head}') não é ancestral direto da branch '${MAIN_BRANCH}' local.")
    fi
  fi

  if [[ -n "$AUDIT_MAIN_HEAD" && -n "$branch_ref_head" ]]; then
    local exclusive_br="" rev_br_rc=0
    exclusive_br="$(git_c -C "$MAIN_WORKTREE_PATH" rev-list --count "refs/heads/${MAIN_BRANCH}..${branch_ref_head}" 2>/dev/null)" || rev_br_rc=$?
    if (( rev_br_rc != 0 )); then
      die_env "Falha inesperada ao calcular commits exclusivos da branch via 'git rev-list' (código ${rev_br_rc})."
    fi
    if [[ "$exclusive_br" != "0" ]] || ! git_is_ancestor "$MAIN_WORKTREE_PATH" "$branch_ref_head" "refs/heads/${MAIN_BRANCH}"; then
      AUDIT_BLOCKERS+=("A branch '${AUDIT_TARGET_BRANCH}' não está integrada como ancestral direta da '${MAIN_BRANCH}' local (${exclusive_br} commit(s) exclusivo(s)).")
    fi
  fi

  # Verifica arquivos ignorados potencialmente sensíveis (sem ler conteúdo)
  if scan_sensitive_ignored "$AUDIT_TARGET_PATH"; then
    if (( ${#SENSITIVE_FINDINGS[@]} > 0 )); then
      local finding
      for finding in "${SENSITIVE_FINDINGS[@]}"; do
        AUDIT_BLOCKERS+=("Arquivo ignorado sensível detectado: ${finding}")
      done
    fi
  else
    AUDIT_BLOCKERS+=("Não foi possível concluir a auditoria de arquivos ignorados sensíveis: ${LAST_SCAN_ERROR}.")
  fi

  if (( ${#AUDIT_BLOCKERS[@]} > 0 )); then
    return 1
  fi
  return 0
}

print_audit_blockers() {
  local blocker
  msg_blocked "Encerramento da worktree recusado pelos motivos abaixo:"
  for blocker in "${AUDIT_BLOCKERS[@]}"; do
    printf '  - %s\n' "$blocker" >&2
  done
}

cmd_close() {
  local slug=""
  local slug_set=0
  local mode="dry-run"
  local dry_run_set=0
  local execute_set=0

  while (( $# > 0 )); do
    case "$1" in
      -h|--help)
        print_usage
        exit "$EXIT_OK"
        ;;
      --slug)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --slug exige um valor."
        slug="$2"
        slug_set=1
        shift 2
        ;;
      --slug=*)
        (( slug_set == 0 )) || die_usage "Opção --slug informada mais de uma vez."
        slug="${1#--slug=}"
        slug_set=1
        shift
        ;;
      --dry-run)
        (( dry_run_set == 0 )) || die_usage "Opção --dry-run informada mais de uma vez."
        dry_run_set=1
        mode="dry-run"
        shift
        ;;
      --execute)
        (( execute_set == 0 )) || die_usage "Opção --execute informada mais de uma vez."
        execute_set=1
        mode="execute"
        shift
        ;;
      *)
        die_usage "Argumento ou opção desconhecida em 'close': $1"
        ;;
    esac
  done

  (( slug_set == 1 )) || die_usage "Opção obrigatória ausente em 'close': --slug <slug>."
  if (( dry_run_set == 1 && execute_set == 1 )); then
    die_usage "Não combine --dry-run com --execute no comando 'close'."
  fi

  validate_slug "$slug"

  # Verifica primeiro se a worktree já está encerrada de forma limpa (idempotência)
  local reg_rc=0
  check_close_registration_state "$slug" || reg_rc=$?
  if (( reg_rc == 10 )); then
    msg_info "Worktree já encerrada para o slug '${slug}'."
    msg_info "  Caminho esperado : ${AUDIT_TARGET_PATH} (ausente e sem registro no Git)"
    if git_ref_exists "$MAIN_WORKTREE_PATH" "$AUDIT_TARGET_REF"; then
      msg_info "  Branch local     : ${AUDIT_TARGET_BRANCH} (preservada; nenhuma referência alterada)"
    else
      msg_info "  Branch local     : ${AUDIT_TARGET_BRANCH} (não existente localmente; nenhuma referência alterada)"
    fi
    exit "$EXIT_OK"
  fi

  # Executa auditoria completa inicial
  if ! audit_worktree_for_close "$slug"; then
    print_audit_blockers
    exit "$EXIT_BLOCKED"
  fi

  local initial_wt_head="$AUDIT_WT_HEAD"
  local initial_main_head="$AUDIT_MAIN_HEAD"

  # Modo padrão: simulação (--dry-run), zero efeitos colaterais
  if [[ "$mode" == "dry-run" ]]; then
    msg_info "=== Simulação de Encerramento (--dry-run) ==="
    msg_info "Resultado          : APROVADO (todas as salvaguardas satisfeitas)"
    msg_info "Worktree alvo      : $AUDIT_TARGET_PATH"
    msg_info "Branch associada   : $AUDIT_TARGET_BRANCH (seria PRESERVADA)"
    msg_info "HEAD da worktree   : $initial_wt_head"
    msg_info "HEAD da main local : $initial_main_head"
    msg_info "Ação simulada      : git worktree remove \"$AUDIT_TARGET_PATH\""
    msg_info "Efeito aplicado    : nenhum arquivo ou referência foi alterado."
    msg_info ""
    msg_info "Para executar a remoção com confirmação interativa:"
    msg_info "  ./scripts/worktree-task.sh close --slug \"$slug\" --execute"
    exit "$EXIT_OK"
  fi

  # Modo --execute: exige terminal interativo real
  if [[ ! -t 0 ]] || { [[ ! -t 1 ]] && [[ ! -t 2 ]]; }; then
    die_blocked "Execução com --execute exige terminal interativo (TTY) para confirmação humana."
  fi

  msg_info "=== Confirmação de Remoção de Worktree ==="
  msg_info "Worktree a remover : $AUDIT_TARGET_PATH"
  msg_info "Branch preservada  : $AUDIT_TARGET_BRANCH (NÃO será removida)"
  msg_info "HEAD da worktree   : $initial_wt_head"
  msg_info "HEAD da main local : $initial_main_head"
  msg_info "AVISO: esta operação exige exclusividade operacional (nenhuma outra sessão modificadora ativa na worktree)."
  printf "Digite exatamente o slug '%s' para confirmar a remoção da worktree: " "$slug"

  local confirmation=""
  if ! IFS= read -r confirmation; then
    printf '\n' >&2
    die_blocked "Confirmação interativa interrompida (EOF). Operação cancelada sem alterações."
  fi

  if [[ "$confirmation" != "$slug" ]]; then
    die_blocked "Confirmação divergente (esperado '${slug}'). Operação cancelada sem alterações."
  fi

  # Revalidação integral imediatamente antes da remoção (mitigação contra mudanças durante o prompt;
  # mantém-se a exigência de exclusividade operacional para a janela residual até `git worktree remove`)
  if ! audit_worktree_for_close "$slug"; then
    msg_blocked "O estado da worktree mudou antes da remoção."
    print_audit_blockers
    exit "$EXIT_BLOCKED"
  fi

  if [[ "$AUDIT_WT_HEAD" != "$initial_wt_head" ]]; then
    die_blocked "O HEAD da worktree mudou entre a verificação inicial ('${initial_wt_head}') e a revalidação ('${AUDIT_WT_HEAD}'). Remoção cancelada."
  fi

  if [[ "$AUDIT_MAIN_HEAD" != "$initial_main_head" ]]; then
    die_blocked "A branch '${MAIN_BRANCH}' foi movimentada entre a verificação inicial ('${initial_main_head}') e a revalidação ('${AUDIT_MAIN_HEAD}'). Remoção cancelada."
  fi

  # Executa exclusivamente `git worktree remove` sem `--force`.
  # Preserva diagnóstico do Git, não força remoção e retorna código contratual 4 em erro.
  local remove_rc=0
  git_c -C "$MAIN_WORKTREE_PATH" worktree remove "$AUDIT_TARGET_PATH" || remove_rc=$?
  if (( remove_rc != 0 )); then
    msg_err "Falha ao executar 'git worktree remove' para '${AUDIT_TARGET_PATH}' (código Git ${remove_rc})."
    msg_err "A branch local '${AUDIT_TARGET_BRANCH}' foi preservada e nenhuma remoção forçada (--force) foi tentada."
    msg_err "Inspecione manualmente o estado da worktree antes de tentar novamente."
    exit "$EXIT_ENV"
  fi

  msg_info ""
  msg_info "Worktree removida com sucesso:"
  msg_info "  Caminho removido : $AUDIT_TARGET_PATH"
  msg_info "  Branch preservada: $AUDIT_TARGET_BRANCH"
  msg_info "  Observação       : a remoção da branch local é independente e não é executada por este utilitário."
}

# ------------------------------------------------------------------------------
# Ponto de entrada principal
# ------------------------------------------------------------------------------

main() {
  check_runtime_requirements

  if (( $# == 0 )); then
    die_usage "Nenhum comando informado."
  fi

  local subcommand="$1"
  shift

  case "$subcommand" in
    -h|--help)
      if (( $# > 0 )); then
        die_usage "Argumentos extras não são aceitos com '$subcommand'."
      fi
      print_usage
      exit "$EXIT_OK"
      ;;
    create)
      cmd_create "$@"
      ;;
    status)
      cmd_status "$@"
      ;;
    close)
      cmd_close "$@"
      ;;
    *)
      die_usage "Comando desconhecido: '$subcommand'. Comandos válidos: create, status, close."
      ;;
  esac
}

main "$@"
