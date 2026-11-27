#!/usr/bin/env bash
# ==============================================================================
# REWIT - DETECÇÃO DE ESCOPO ALTERADO NO CI (FASE 5 R1)
# ==============================================================================
# Classifica módulos afetados sem process substitution (< <(...)), usando
# redirecionamento direto para arquivo temporário com captura explícita do
# código de saída, `--no-renames` e saída delimitada por NUL (`-z`).
#
# Uso:
#   ./scripts/ci/detect-changes.sh \
#     --event <pull_request|push> \
#     --expected-head <sha-40-hex> \
#     [--base <sha-40-hex>] \
#     [--output <arquivo-github-output>]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Uso:
  ./scripts/ci/detect-changes.sh \
    --event <pull_request|push> \
    --expected-head <sha-40-hex> \
    [--base <sha-40-hex>] \
    [--output <arquivo>]

Opções:
  --event <tipo>          Tipo do evento: pull_request ou push.
  --expected-head <sha>   SHA completo esperado em HEAD (verificado antes da análise).
  --base <sha>            SHA completo da base do PR (obrigatório em pull_request).
  --output <arquivo>      Arquivo no formato GITHUB_OUTPUT para anexar outputs.
                          Quando omitido, imprime pares chave=valor em stdout.
  -h, --help              Exibe esta ajuda e encerra com código 0.
EOF
}

event_name=""
expected_head=""
base_sha=""
output_file=""
event_set=0
head_set=0
base_set=0
output_set=0

while (( $# > 0 )); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --event)
      (( event_set == 0 )) || { echo "ERRO: opção --event informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --event exige um valor." >&2; exit 2; }
      event_name="$2"; event_set=1; shift 2
      ;;
    --event=*)
      (( event_set == 0 )) || { echo "ERRO: opção --event informada mais de uma vez." >&2; exit 2; }
      event_name="${1#--event=}"; event_set=1; shift
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
    --base)
      (( base_set == 0 )) || { echo "ERRO: opção --base informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --base exige um valor." >&2; exit 2; }
      base_sha="$2"; base_set=1; shift 2
      ;;
    --base=*)
      (( base_set == 0 )) || { echo "ERRO: opção --base informada mais de uma vez." >&2; exit 2; }
      base_sha="${1#--base=}"; base_set=1; shift
      ;;
    --output)
      (( output_set == 0 )) || { echo "ERRO: opção --output informada mais de uma vez." >&2; exit 2; }
      (( $# >= 2 )) || { echo "ERRO: opção --output exige um valor." >&2; exit 2; }
      output_file="$2"; output_set=1; shift 2
      ;;
    --output=*)
      (( output_set == 0 )) || { echo "ERRO: opção --output informada mais de uma vez." >&2; exit 2; }
      output_file="${1#--output=}"; output_set=1; shift
      ;;
    *)
      echo "ERRO: argumento desconhecido em detect-changes.sh: $1" >&2
      exit 2
      ;;
  esac
done

if (( event_set == 0 )) || [[ "$event_name" != "pull_request" && "$event_name" != "push" ]]; then
  echo "ERRO: informe --event pull_request ou --event push." >&2
  exit 2
fi

if (( head_set == 0 )) || [[ -z "$expected_head" ]]; then
  echo "ERRO: informe --expected-head <sha-40-hex>." >&2
  exit 2
fi

emit_outputs() {
  local b="$1" p="$2" mc="$3" a="$4" m="$5"
  if [[ -n "$output_file" ]]; then
    {
      printf 'backend=%s\n' "$b"
      printf 'postgres_provisioning=%s\n' "$p"
      printf 'migration_container=%s\n' "$mc"
      printf 'admin=%s\n' "$a"
      printf 'mobile=%s\n' "$m"
    } >> "$output_file"
  else
    printf 'backend=%s\n' "$b"
    printf 'postgres_provisioning=%s\n' "$p"
    printf 'migration_container=%s\n' "$mc"
    printf 'admin=%s\n' "$a"
    printf 'mobile=%s\n' "$m"
  fi
}

# Confirma que o HEAD checked out corresponde exatamente ao SHA imutável esperado
"${SCRIPT_DIR}/verify-head-sha.sh" --expected "$expected_head" >&2

if [[ "$event_name" == "push" ]]; then
  emit_outputs "true" "true" "true" "true" "true"
  echo "Evento push em main: todos os módulos serão validados." >&2
  exit 0
fi

if (( base_set == 0 )) || [[ -z "$base_sha" ]]; then
  echo "ERRO: --base <sha-40-hex> é obrigatório em eventos pull_request." >&2
  exit 2
fi

if ! (LC_ALL=C; [[ "$base_sha" =~ ^[0-9a-fA-F]{40}$ ]]); then
  echo "ERRO: SHA base inválido (exige 40 caracteres hexadecimais): ${base_sha}" >&2
  exit 2
fi

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

tmp_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/rewit-ci-changes.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

diff_file="${tmp_dir}/changed-files.nul"
diff_rc=0
git diff --no-renames -z --name-only "$merge_base" HEAD > "$diff_file" || diff_rc=$?
if (( diff_rc != 0 )); then
  echo "ERRO: falha ao executar 'git diff --no-renames -z --name-only' (código ${diff_rc})." >&2
  exit "$diff_rc"
fi

backend=false
postgres_provisioning=false
migration_container=false
admin=false
mobile=false

while IFS= read -r -d '' path; do
  [[ -z "$path" ]] && continue
  case "$path" in
    .github/workflows/ci.yml|scripts/ci/*)
      backend=true
      postgres_provisioning=true
      migration_container=true
      admin=true
      mobile=true
      ;;
    .gitattributes|infrastructure/postgres/*)
      backend=true
      postgres_provisioning=true
      migration_container=true
      ;;
    backend/pom.xml|backend/src/main/java/com/rewit/migration/*|backend/src/main/java/com/rewit/infrastructure/persistence/schema/*|backend/src/main/resources/application-prod.yml|backend/src/main/resources/db/migration/*|infrastructure/docker/backend.Dockerfile|infrastructure/docker/docker-compose.prod.yml|infrastructure/docker/tests/*|scripts/deploy/*)
      backend=true
      migration_container=true
      ;;
    backend/*|docker-compose.yml|docker-compose.external-test.yml|scripts/test-infra.sh)
      backend=true
      ;;
    apps/admin/*|infrastructure/docker/admin.Dockerfile)
      admin=true
      ;;
    apps/mobile/*)
      mobile=true
      ;;
  esac
done < "$diff_file"

emit_outputs "$backend" "$postgres_provisioning" "$migration_container" "$admin" "$mobile"
echo "Escopo detectado (merge-base=${merge_base}): backend=${backend}, postgres_provisioning=${postgres_provisioning}, migration_container=${migration_container}, admin=${admin}, mobile=${mobile}" >&2
