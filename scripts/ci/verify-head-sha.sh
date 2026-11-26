#!/usr/bin/env bash
# ==============================================================================
# REWIT - VERIFICAÇÃO DE SHA DO HEAD NO CI (FASE 5 R1)
# ==============================================================================
# Confirma que o commit efetivamente checked out (HEAD) corresponde exatamente
# ao SHA imutável esperado pelo evento do CI, interrompendo imediatamente em
# caso de divergência ou falha Git.
#
# Uso:
#   ./scripts/ci/verify-head-sha.sh --expected <sha-40-hex>
# ==============================================================================

set -euo pipefail

usage() {
  cat <<'EOF'
Uso:
  ./scripts/ci/verify-head-sha.sh --expected <sha-40-hex>

Opções:
  --expected <sha>  SHA completo (40 caracteres hexadecimais) esperado em HEAD.
  -h, --help        Exibe esta ajuda e encerra com código 0.
EOF
}

expected=""
expected_set=0

while (( $# > 0 )); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --expected)
      (( expected_set == 0 )) || { echo "ERRO: opção --expected informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --expected exige um valor." >&2; exit 2; }
      expected="$2"
      expected_set=1
      shift 2
      ;;
    --expected=*)
      (( expected_set == 0 )) || { echo "ERRO: opção --expected informada mais de uma vez." >&2; exit 2; }
      expected="${1#--expected=}"
      expected_set=1
      shift
      ;;
    *)
      echo "ERRO: argumento desconhecido em verify-head-sha.sh: $1" >&2
      exit 2
      ;;
  esac
done

if (( expected_set == 0 )) || [[ -z "$expected" ]]; then
  echo "ERRO: informe --expected <sha-40-hex>." >&2
  exit 2
fi

if ! (LC_ALL=C; [[ "$expected" =~ ^[0-9a-fA-F]{40}$ ]]); then
  echo "ERRO: SHA esperado inválido (exige 40 caracteres hexadecimais): ${expected}" >&2
  exit 2
fi

actual_rc=0
actual="$(git rev-parse --verify 'HEAD^{commit}')" || actual_rc=$?
if (( actual_rc != 0 )); then
  echo "ERRO: não foi possível resolver HEAD no repositório Git (código ${actual_rc})." >&2
  exit "$actual_rc"
fi

expected_lc="$(LC_ALL=C printf '%s' "${expected,,}")"
actual_lc="$(LC_ALL=C printf '%s' "${actual,,}")"

if [[ "$actual_lc" != "$expected_lc" ]]; then
  echo "ERRO: HEAD atual (${actual}) diverge do SHA esperado (${expected})." >&2
  exit 1
fi

echo "[OK] HEAD verificado: ${actual}"
