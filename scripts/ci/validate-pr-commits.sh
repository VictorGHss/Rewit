#!/usr/bin/env bash
# ==============================================================================
# REWIT - VALIDAÇÃO DE MENSAGENS DE COMMIT DE PR NO CI (FASE 5 R1)
# ==============================================================================
# Valida todos os commits não-merge no intervalo merge-base..HEAD usando
# `scripts/task-gate.sh check-commit-msg --file <msg> --no-strip-comments`.
#
# Características de integridade:
# - Sem process substitution (`< <(...)`): redirecionamento direto para arquivo
#   temporário em diretório seguro com cleanup via `trap` e propagação explícita
#   de códigos de saída de `git rev-list` e `git log`.
# - Preserva o subject literal do commit (`--no-strip-comments`), impedindo que
#   linhas iniciadas por `#` sejam truncadas ou aprovadas indevidamente.
# - Higieniza variáveis `GIT_*` fora da allowlist do gate antes de invocá-lo
#   (sem imprimir valores).
# - Trata e documenta explicitamente o caso de 0 commits não-merge (ex.: PR
#   composto apenas por commits de merge).
#
# Uso:
#   ./scripts/ci/validate-pr-commits.sh \
#     --base <sha-40-hex> \
#     --expected-head <sha-40-hex> \
#     [--gate-script <caminho>]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_GATE_SCRIPT="$(cd "${SCRIPT_DIR}/.." && pwd)/task-gate.sh"

usage() {
  cat <<'EOF'
Uso:
  ./scripts/ci/validate-pr-commits.sh \
    --base <sha-40-hex> \
    --expected-head <sha-40-hex> \
    [--gate-script <caminho>]

Opções:
  --base <sha>            SHA completo da base do PR.
  --expected-head <sha>   SHA completo esperado em HEAD.
  --gate-script <caminho> Caminho para scripts/task-gate.sh (opcional).
  -h, --help              Exibe esta ajuda e encerra com código 0.
EOF
}

base_sha=""
expected_head=""
gate_script="$DEFAULT_GATE_SCRIPT"
base_set=0
head_set=0
gate_set=0

while (( $# > 0 )); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --base)
      (( base_set == 0 )) || { echo "ERRO: opção --base informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --base exige um valor." >&2; exit 2; }
      base_sha="$2"; base_set=1; shift 2
      ;;
    --base=*)
      (( base_set == 0 )) || { echo "ERRO: opção --base informada mais de uma vez." >&2; exit 2; }
      base_sha="${1#--base=}"; base_set=1; shift
      ;;
    --expected-head)
      (( head_set == 0 )) || { echo "ERRO: opção --expected-head informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --expected-head exige um valor." >&2; exit 2; }
      expected_head="$2"; head_set=1; shift 2
      ;;
    --expected-head=*)
      (( head_set == 0 )) || { echo "ERRO: opção --expected-head informada mais de uma vez." >&2; exit 2; }
      expected_head="${1#--expected-head=}"; head_set=1; shift
      ;;
    --gate-script)
      (( gate_set == 0 )) || { echo "ERRO: opção --gate-script informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --gate-script exige um valor." >&2; exit 2; }
      gate_script="$2"; gate_set=1; shift 2
      ;;
    --gate-script=*)
      (( gate_set == 0 )) || { echo "ERRO: opção --gate-script informada mais de uma vez." >&2; exit 2; }
      gate_script="${1#--gate-script=}"; gate_set=1; shift
      ;;
    *)
      echo "ERRO: argumento desconhecido em validate-pr-commits.sh: $1" >&2
      exit 2
      ;;
  esac
done

if (( base_set == 0 )) || [[ -z "$base_sha" ]]; then
  echo "ERRO: informe --base <sha-40-hex>." >&2
  exit 2
fi

if (( head_set == 0 )) || [[ -z "$expected_head" ]]; then
  echo "ERRO: informe --expected-head <sha-40-hex>." >&2
  exit 2
fi

if ! (LC_ALL=C; [[ "$base_sha" =~ ^[0-9a-fA-F]{40}$ ]]); then
  echo "ERRO: SHA base inválido (exige 40 caracteres hexadecimais): ${base_sha}" >&2
  exit 2
fi

if [[ ! -x "$gate_script" ]]; then
  echo "ERRO: script de gate não encontrado ou não executável: ${gate_script}" >&2
  exit 4
fi

# Confirma que o HEAD checked out corresponde exatamente ao SHA esperado
"${SCRIPT_DIR}/verify-head-sha.sh" --expected "$expected_head"

cat_rc=0
git cat-file -e "${base_sha}^{commit}" || cat_rc=$?
if (( cat_rc != 0 )); then
  echo "ERRO: commit base (${base_sha}) indisponível no repositório local (código ${cat_rc})." >&2
  exit "$cat_rc"
fi

mb_rc=0
merge_base="$(git merge-base "$base_sha" HEAD)" || mb_rc=$?
if (( mb_rc != 0 )) || [[ -z "$merge_base" ]]; then
  echo "ERRO: não foi possível calcular merge-base entre ${base_sha} e HEAD (código ${mb_rc})." >&2
  exit "${mb_rc:-1}"
fi

tmp_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/rewit-ci-commits.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

count_rc=0
total_commits="$(git rev-list --count "${merge_base}..HEAD")" || count_rc=$?
if (( count_rc != 0 )); then
  echo "ERRO: falha ao executar 'git rev-list --count' (código ${count_rc})." >&2
  exit "$count_rc"
fi

commits_file="${tmp_dir}/commits.txt"
revlist_rc=0
git rev-list --no-merges "${merge_base}..HEAD" > "$commits_file" || revlist_rc=$?
if (( revlist_rc != 0 )); then
  echo "ERRO: falha ao executar 'git rev-list --no-merges' (código ${revlist_rc})." >&2
  exit "$revlist_rc"
fi

# Higieniza variáveis GIT_* fora da allowlist do gate antes de invocá-lo (sem exibir valores)
unset_args=()
for var_name in "${!GIT_@}"; do
  case "$var_name" in
    GIT_OPTIONAL_LOCKS|GIT_PAGER|GIT_EDITOR|GIT_TERMINAL_PROMPT) ;;
    *)
      echo "Higienizando variável do ambiente fora da allowlist antes de check-commit-msg: ${var_name}"
      unset_args+=(-u "$var_name")
      ;;
  esac
done

checked=0
failed=0
while IFS= read -r sha; do
  [[ -z "$sha" ]] && continue
  checked=$((checked + 1))
  msg_file="${tmp_dir}/${sha}.txt"

  log_rc=0
  git log -1 --format=%B "$sha" > "$msg_file" || log_rc=$?
  if (( log_rc != 0 )); then
    echo "ERRO: falha ao obter mensagem do commit ${sha} via 'git log' (código ${log_rc})." >&2
    exit "$log_rc"
  fi

  echo "Validando mensagem literal do commit ${sha}..."
  if (( ${#unset_args[@]} > 0 )); then
    if ! env "${unset_args[@]}" "$gate_script" check-commit-msg --file "$msg_file" --no-strip-comments; then
      failed=$((failed + 1))
    fi
  else
    if ! "$gate_script" check-commit-msg --file "$msg_file" --no-strip-comments; then
      failed=$((failed + 1))
    fi
  fi
done < "$commits_file"

if (( checked == 0 )); then
  if (( total_commits > 0 )); then
    echo "[AVISO] Intervalo ${merge_base}..HEAD contém ${total_commits} commit(s) de merge e 0 commits não-merge; nenhuma mensagem convencional adicional a validar."
  else
    echo "[AVISO] Intervalo ${merge_base}..HEAD não contém commits novos (HEAD coincide com merge-base)."
  fi
fi

echo "Commits não-merge avaliados no PR: ${checked} (total no intervalo: ${total_commits}, falhas: ${failed})."
if (( failed > 0 )); then
  exit 1
fi
