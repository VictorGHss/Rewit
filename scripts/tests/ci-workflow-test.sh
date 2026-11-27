#!/usr/bin/env bash
# ==============================================================================
# REWIT - SUÍTE DESCARTÁVEL DE REGRESSÃO DOS HELPERS DE CI (FASE 5 R1)
# ==============================================================================
# Valida localmente, em repositórios Git temporários isolados sob /tmp e sem
# qualquer dependência de GitHub remoto, todas as regressões da Fase 5 R1:
#   1. Propagação de erro de `git diff` (sem gerar outputs `false` com exit 0)
#   2. Propagação de erro de `git rev-list` (sem gerar `checked=0` com exit 0)
#   3. Renomeação de dentro de módulo para fora do módulo ativa o módulo origem
#   4. Modificação em `.gitattributes` ativa `backend` e `postgres_provisioning`
#   5. SHA divergente no checkout provoca falha imediata
#   6. Commit subject iniciado por `#` não é truncado nem aprovado indevidamente
#   7. Mensagens multilinha preservam a semântica completa de `check-commit-msg`
#      (e comportamento explícito de intervalo apenas com merge commits)
#   8. Caminhos com espaços, tabs, aspas, colchetes, Unicode e quebra de linha
#      são tratados com segurança via NUL (`-z`), mesmo com `core.quotePath=true`
#
# Execução:
#   ./scripts/tests/ci-workflow-test.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VERIFY_SHA_BIN="${REPO_ROOT}/scripts/ci/verify-head-sha.sh"
DETECT_CHANGES_BIN="${REPO_ROOT}/scripts/ci/detect-changes.sh"
VALIDATE_COMMITS_BIN="${REPO_ROOT}/scripts/ci/validate-pr-commits.sh"
TASK_GATE_BIN="${REPO_ROOT}/scripts/task-gate.sh"
REAL_GIT_BIN="$(command -v git)"

for bin in "$VERIFY_SHA_BIN" "$DETECT_CHANGES_BIN" "$VALIDATE_COMMITS_BIN" "$TASK_GATE_BIN"; do
  if [[ ! -x "$bin" ]]; then
    printf 'ERRO: binário obrigatório ausente ou não executável: %s\n' "$bin" >&2
    exit 1
  fi
done

TEST_ROOT="$(mktemp -d /tmp/rewit-ci-workflow-test.XXXXXX)"
cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

CASE_IDX=0
CASE_DIR=""
CASE_REPO=""
BASE_SHA=""
LAST_RC=0
LAST_OUT=""
LAST_ERR=""

assert_eq() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf '    [FALHA] %s: esperado=%q obtido=%q\n' "$label" "$expected" "$actual" >&2
    return 1
  fi
}

assert_ne() {
  local unexpected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" == "$unexpected" ]]; then
    printf '    [FALHA] %s: não deveria ser=%q\n' "$label" "$unexpected" >&2
    return 1
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf '    [FALHA] %s: texto esperado não encontrado: %q\n' "$label" "$needle" >&2
    return 1
  fi
}

init_case_repo() {
  CASE_IDX=$((CASE_IDX + 1))
  CASE_DIR="${TEST_ROOT}/case-${CASE_IDX}"
  CASE_REPO="${CASE_DIR}/repo"
  mkdir -p -- \
    "${CASE_REPO}/backend/src" \
    "${CASE_REPO}/infrastructure/postgres/init" \
    "${CASE_REPO}/apps/admin/src" \
    "${CASE_REPO}/apps/mobile/lib" \
    "${CASE_REPO}/docs"

  LC_ALL=C "$REAL_GIT_BIN" init -q -b main "$CASE_REPO"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config user.name "Rewit CI Test"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config user.email "ci-test@rewit.invalid"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config core.quotePath true

  printf '*.sh text eol=lf\n' > "${CASE_REPO}/.gitattributes"
  printf 'class App {}\n' > "${CASE_REPO}/backend/src/App.java"
  printf 'SELECT 1;\n' > "${CASE_REPO}/infrastructure/postgres/init/01.sql"
  printf 'export const a = 1;\n' > "${CASE_REPO}/apps/admin/src/index.ts"
  printf 'void main() {}\n' > "${CASE_REPO}/apps/mobile/lib/main.dart"
  printf '# Docs\n' > "${CASE_REPO}/docs/readme.md"

  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add -A
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "chore(init): estado inicial do repositório de teste"
  BASE_SHA="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"
}

create_fake_git_path() {
  local snippet="$1"
  local fake_dir="${CASE_DIR}/fake-bin-${RANDOM}"
  mkdir -p -- "$fake_dir"
  cat > "${fake_dir}/git" <<EOF
#!/usr/bin/env bash
set -euo pipefail
REAL_GIT='${REAL_GIT_BIN}'
${snippet}
EOF
  chmod +x "${fake_dir}/git"
  printf '%s:%s' "$fake_dir" "$PATH"
}

run_cmd_in() {
  local workdir="$1"
  local custom_path="$2"
  shift 2
  local out_file="${CASE_DIR}/cmd.out"
  local err_file="${CASE_DIR}/cmd.err"
  LAST_RC=0
  (
    cd "$workdir"
    PATH="$custom_path" "$@"
  ) >"$out_file" 2>"$err_file" || LAST_RC=$?
  LAST_OUT="$(cat "$out_file")"
  LAST_ERR="$(cat "$err_file")"
}

run_test() {
  local name="$1"
  local fn="$2"
  TESTS_RUN=$((TESTS_RUN + 1))
  init_case_repo
  if "$fn"; then
    TESTS_PASSED=$((TESTS_PASSED + 1))
    printf '[OK]   %s\n' "$name"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    printf '[FAIL] %s\n' "$name" >&2
  fi
}

# ==============================================================================
# 1. Erro simulado de `git diff` propagado corretamente (sem outputs `false`)
# ==============================================================================

test_git_diff_error_propagates_without_false_outputs() {
  printf 'alteracao\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/readme.md
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "docs: atualizar documento"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local gh_out="${CASE_DIR}/github_output.txt"
  : > "$gh_out"

  local fail_diff_path
  fail_diff_path="$(create_fake_git_path '
if [[ "${1:-}" == "diff" ]]; then
  echo "fatal: falha simulada em git diff" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"

  run_cmd_in "$CASE_REPO" "$fail_diff_path" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "128" "$LAST_RC" "código de saída de git diff deve propagar (128)"
  assert_eq "" "$(cat "$gh_out")" "GITHUB_OUTPUT não deve receber outputs false quando git diff falha"
  assert_contains "$LAST_ERR" "git diff" "mensagem de erro deve mencionar git diff"
}

# ==============================================================================
# 2. Erro simulado de `git rev-list` propagado corretamente
# ==============================================================================

test_git_rev_list_error_propagates() {
  printf 'alteracao\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/readme.md
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "docs: commit valido"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local fail_revlist_path
  fail_revlist_path="$(create_fake_git_path '
if [[ "${1:-}" == "rev-list" && "$*" == *"--no-merges"* ]]; then
  echo "fatal: falha simulada em git rev-list" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"

  run_cmd_in "$CASE_REPO" "$fail_revlist_path" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$BASE_SHA" \
    --expected-head "$head_sha" \
    --gate-script "$TASK_GATE_BIN"

  assert_eq "128" "$LAST_RC" "falha em git rev-list --no-merges deve propagar código 128"
  assert_contains "$LAST_ERR" "git rev-list" "erro deve identificar git rev-list"
}

# ==============================================================================
# 3. Renomeação de dentro de módulo para fora ativa o módulo de origem
# ==============================================================================

test_rename_out_of_module_triggers_source_module() {
  # Move arquivo de backend/ para docs/ (100% idêntico -> rename puro no Git!)
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" mv backend/src/App.java docs/App.java
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "refactor(backend): mover arquivo para docs"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local gh_out="${CASE_DIR}/rename-backend.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes com rename backend->docs deve concluir com 0"
  local out_content
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "backend=true" "mover backend/ -> docs/ deve ativar backend=true"
  assert_contains "$out_content" "postgres_provisioning=false" "postgres_provisioning deve permanecer false"
  assert_contains "$out_content" "admin=false" "admin deve permanecer false"
  assert_contains "$out_content" "mobile=false" "mobile deve permanecer false"

  # Também testa rename de apps/mobile/ -> docs/ em outro caso isolado
  init_case_repo
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" mv apps/mobile/lib/main.dart docs/main.dart
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "refactor(mobile): mover arquivo para docs"
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  gh_out="${CASE_DIR}/rename-mobile.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes com rename mobile->docs deve concluir com 0"
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "mobile=true" "mover apps/mobile/ -> docs/ deve ativar mobile=true"
  assert_contains "$out_content" "backend=false" "backend deve permanecer false"
}

# ==============================================================================
# 4. `.gitattributes` ativa `backend` e `postgres_provisioning`
# ==============================================================================

test_gitattributes_triggers_backend_and_provisioning() {
  printf 'infrastructure/postgres/** text eol=lf\n' >> "${CASE_REPO}/.gitattributes"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add .gitattributes
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "chore(git): atualizar regras de fim de linha"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local gh_out="${CASE_DIR}/gitattributes.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes em .gitattributes deve concluir com 0"
  local out_content
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "backend=true" ".gitattributes deve ativar backend=true"
  assert_contains "$out_content" "postgres_provisioning=true" ".gitattributes deve ativar postgres_provisioning=true"
  assert_contains "$out_content" "admin=false" "admin deve permanecer false"
  assert_contains "$out_content" "mobile=false" "mobile deve permanecer false"
}

# ==============================================================================
# 5. SHA divergente no checkout provoca falha imediata
# ==============================================================================

test_divergent_head_sha_fails_immediately() {
  local wrong_sha="0123456789abcdef0123456789abcdef01234567"
  if [[ "$BASE_SHA" == "$wrong_sha" ]]; then
    wrong_sha="fedcba9876543210fedcba9876543210fedcba98"
  fi

  run_cmd_in "$CASE_REPO" "$PATH" "$VERIFY_SHA_BIN" --expected "$wrong_sha"
  assert_eq "1" "$LAST_RC" "verify-head-sha.sh deve falhar com código 1 em SHA divergente"
  assert_contains "$LAST_ERR" "diverge" "mensagem deve indicar divergência de SHA"

  local gh_out="${CASE_DIR}/divergent.out"
  : > "$gh_out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$wrong_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"
  assert_ne "0" "$LAST_RC" "detect-changes.sh deve falhar em SHA divergente"
  assert_eq "" "$(cat "$gh_out")" "detect-changes.sh não deve emitir outputs em SHA divergente"

  run_cmd_in "$CASE_REPO" "$PATH" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$BASE_SHA" \
    --expected-head "$wrong_sha" \
    --gate-script "$TASK_GATE_BIN"
  assert_ne "0" "$LAST_RC" "validate-pr-commits.sh deve falhar em SHA divergente"

  # Confirma sucesso quando o SHA corresponde exatamente
  run_cmd_in "$CASE_REPO" "$PATH" "$VERIFY_SHA_BIN" --expected "$BASE_SHA"
  assert_eq "0" "$LAST_RC" "verify-head-sha.sh deve aprovar quando HEAD coincide com SHA esperado"
}

# ==============================================================================
# 6. Commit subject iniciado por `#` não é truncado nem aprovado indevidamente
# ==============================================================================

test_hash_prefixed_commit_subject_is_rejected() {
  printf 'nota\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/readme.md
  # --cleanup=verbatim cria um commit real cujo subject começa literalmente com '#'
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q --cleanup=verbatim \
    -F - <<'EOF'
#123 titulo fora do padrao com cerquilha

feat(ci): linha posterior valida que nao pode mascarar o subject real
EOF
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  run_cmd_in "$CASE_REPO" "$PATH" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$BASE_SHA" \
    --expected-head "$head_sha" \
    --gate-script "$TASK_GATE_BIN"

  assert_eq "1" "$LAST_RC" "commit cujo subject começa com '#' deve ser rejeitado (exit 1)"
  assert_contains "$LAST_ERR" "Cabeçalho fora do padrão Conventional Commits" "erro deve acusar cabeçalho inválido"
}

# ==============================================================================
# 7. Mensagens multilinha preservam semântica de check-commit-msg + merge-only
# ==============================================================================

test_multiline_commit_messages_and_merge_only_range() {
  # Commit 1: multilinha válida com '#' no corpo e rodapé BREAKING CHANGE válido
  printf 'v1\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/readme.md
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q --cleanup=verbatim -F - <<'EOF'
feat(ci): validar mensagens multilinha no workflow

Corpo explicativo em português.
# Item de referência no corpo não invalida o commit.

BREAKING CHANGE: exige SHA explícito em todos os jobs
EOF
  local head_ok
  head_ok="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  run_cmd_in "$CASE_REPO" "$PATH" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$BASE_SHA" \
    --expected-head "$head_ok" \
    --gate-script "$TASK_GATE_BIN"

  assert_eq "0" "$LAST_RC" "mensagem multilinha válida deve aprovar (0)"
  assert_contains "$LAST_OUT" "Breaking change : sim" "BREAKING CHANGE multilinha deve ser reconhecido"
  assert_contains "$LAST_OUT" "Commits não-merge avaliados no PR: 1" "contagem deve refletir 1 commit"

  # Commit 2: BREAKING CHANGE com descrição vazia deve falhar
  printf 'v2\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/readme.md
  printf 'fix(ci): validar rodape breaking vazio\n\nBREAKING CHANGE:   \n' \
    | LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q --cleanup=verbatim -F -
  local head_bad_bc
  head_bad_bc="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  run_cmd_in "$CASE_REPO" "$PATH" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$BASE_SHA" \
    --expected-head "$head_bad_bc" \
    --gate-script "$TASK_GATE_BIN"

  assert_eq "1" "$LAST_RC" "commit com BREAKING CHANGE vazio deve falhar (1)"
  assert_contains "$LAST_ERR" "BREAKING CHANGE" "erro deve mencionar BREAKING CHANGE"

  # Cenário merge-only (checked=0, total_commits=1): comportamento explícito e seguro
  init_case_repo
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" checkout -q -b feature
  printf 'base avanco\n' >> "${CASE_REPO}/docs/readme.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" checkout -q main
  printf 'main avanco\n' > "${CASE_REPO}/docs/main.md"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add docs/main.md
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "chore(main): avancar base"
  local new_base
  new_base="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" checkout -q feature
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" merge -q --no-ff main -m "Merge branch 'main' into feature"
  local merge_head
  merge_head="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  run_cmd_in "$CASE_REPO" "$PATH" \
    "$VALIDATE_COMMITS_BIN" \
    --base "$new_base" \
    --expected-head "$merge_head" \
    --gate-script "$TASK_GATE_BIN"

  assert_eq "0" "$LAST_RC" "intervalo com apenas commits de merge deve encerrar com 0 e aviso explícito"
  assert_contains "$LAST_OUT" "commit(s) de merge e 0 commits não-merge" "deve registrar aviso explícito para checked=0"
}

# ==============================================================================
# 8. Caminhos com espaços, tabs, aspas, Unicode e quebra de linha (`-z`)
# ==============================================================================

test_special_filenames_handled_safely_with_nul_output() {
  # Garante core.quotePath=true para provar independência de core.quotePath=false
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config core.quotePath true

  local special_dir="${CASE_REPO}/backend/pasta espaço	tab"
  mkdir -p -- "$special_dir"
  local special_file="${special_dir}/ação \"aspas\" [0]
linha2.txt"
  printf 'conteudo especial\n' > "$special_file"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add -A
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "test(backend): adicionar caminho com espaços, tab, unicode e newline"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local gh_out="${CASE_DIR}/special-paths.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes deve lidar com nomes contendo espaços, tab, aspas, Unicode e newline"
  local out_content
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "backend=true" "caminho especial em backend/ deve ativar backend=true"
  assert_contains "$out_content" "admin=false" "admin deve permanecer false"
  assert_contains "$out_content" "mobile=false" "mobile deve permanecer false"
}

# ==============================================================================
# 9. Escopo `migration_container` ativado para orquestração pré-deploy / Docker
# ==============================================================================

test_migration_container_scope_detection() {
  mkdir -p -- "${CASE_REPO}/scripts/deploy" "${CASE_REPO}/infrastructure/docker"
  printf '#!/usr/bin/env bash\nexit 0\n' > "${CASE_REPO}/scripts/deploy/run-predeploy-migrations.sh"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add scripts/deploy/run-predeploy-migrations.sh
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "feat(deploy): adicionar script de migrations pre-deploy"
  local head_sha
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  local gh_out="${CASE_DIR}/mig-container.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes em scripts/deploy/* deve concluir com 0"
  local out_content
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "backend=true" "scripts/deploy/* deve ativar backend=true"
  assert_contains "$out_content" "migration_container=true" "scripts/deploy/* deve ativar migration_container=true"
  assert_contains "$out_content" "admin=false" "admin deve permanecer false"
  assert_contains "$out_content" "mobile=false" "mobile deve permanecer false"

  # Alteração comum de backend fora de migration/schema/prod não deve ativar migration_container
  init_case_repo
  printf '// ajuste comum\n' >> "${CASE_REPO}/backend/src/App.java"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add backend/src/App.java
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -q -m "fix(backend): ajuste fora de migrations"
  head_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse HEAD)"

  gh_out="${CASE_DIR}/backend-only.out"
  run_cmd_in "$CASE_REPO" "$PATH" \
    "$DETECT_CHANGES_BIN" \
    --event pull_request \
    --expected-head "$head_sha" \
    --base "$BASE_SHA" \
    --output "$gh_out"

  assert_eq "0" "$LAST_RC" "detect-changes em backend/src/App.java deve concluir com 0"
  out_content="$(cat "$gh_out")"
  assert_contains "$out_content" "backend=true" "backend/src/App.java deve ativar backend=true"
  assert_contains "$out_content" "migration_container=false" "alteração comum de backend não deve duplicar build Docker de migration_container"
}

printf '=== Executando suíte descartável de regressão dos helpers de CI (Fase 5 R1) ===\n'
printf 'Diretório temporário isolado: %s\n\n' "$TEST_ROOT"

run_test "1. Erro simulado de git diff propaga falha sem emitir outputs false" test_git_diff_error_propagates_without_false_outputs
run_test "2. Erro simulado de git rev-list propaga falha sem converter em sucesso" test_git_rev_list_error_propagates
run_test "3. Renomeação de dentro de módulo para fora ativa o módulo de origem" test_rename_out_of_module_triggers_source_module
run_test "4. Modificação em .gitattributes ativa backend e postgres_provisioning" test_gitattributes_triggers_backend_and_provisioning
run_test "5. SHA divergente no checkout falha imediatamente em todos os helpers" test_divergent_head_sha_fails_immediately
run_test "6. Commit subject iniciado por '#' é rejeitado sem truncamento" test_hash_prefixed_commit_subject_is_rejected
run_test "7. Mensagens multilinha preservam semântica de check-commit-msg e merge-only explícito" test_multiline_commit_messages_and_merge_only_range
run_test "8. Caminhos com espaços, tab, aspas, Unicode e quebra de linha são tratados via -z" test_special_filenames_handled_safely_with_nul_output
run_test "9. Escopo migration_container ativa para scripts/deploy/* sem duplicar PRs comuns" test_migration_container_scope_detection

printf '\n=== Resumo da Suíte (ci-workflow) ===\n'
printf 'Total executados : %d\n' "$TESTS_RUN"
printf 'Aprovados        : %d\n' "$TESTS_PASSED"
printf 'Falhas           : %d\n' "$TESTS_FAILED"

if (( TESTS_FAILED > 0 )); then
  exit 1
fi
exit 0
