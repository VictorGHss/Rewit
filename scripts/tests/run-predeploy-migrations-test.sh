#!/usr/bin/env bash
# ==============================================================================
# REWIT - SUÍTE DESCARTÁVEL DE REGRESSÃO DE run-predeploy-migrations.sh (M-02)
# ==============================================================================
# Valida, em diretório temporário isolado e com runtime de container simulado,
# a política semântica de classificação de falhas e retries (sem heurística de
# regex em stderr):
#   1. Falha transacional de migration na 1ª tentativa (exit=4): 1 tentativa (sem retry)
#   2. Falha não transacional de migration na 1ª tentativa (exit=4): 1 tentativa (sem retry)
#   3. Erro de checksum/histórico (exit=4): 1 tentativa (sem retry)
#   4. Configuração inválida (exit=2) e privilégio (exit=3): 1 tentativa (sem retry)
#   5. Timeout do advisory lock (exit=5): retry limitado até --max-attempts e sucesso na retomada
#   6. Falha transitória de infraestrutura (exit=6): retry limitado até --max-attempts e sucesso na retomada
#   7. Erros desconhecidos (exit=1, 42, 137): 1 tentativa cada (fail-closed, sem retry)
#   8. Contenção de --lock-file local (flock): bloqueio imediato com exit=5 e 0 execuções
#   9. Variáveis proibidas/conflitantes: bloqueio imediato com exit=2 e 0 execuções
#  10. Ausência total de vazamento de segredo em stdout e stderr em todos os casos
#
# Execução:
#   ./scripts/tests/run-predeploy-migrations-test.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PREDEPLOY_BIN="${REPO_ROOT}/scripts/deploy/run-predeploy-migrations.sh"

if [[ ! -x "$PREDEPLOY_BIN" ]]; then
  echo "ERRO: script obrigatório ausente ou não executável: ${PREDEPLOY_BIN}" >&2
  exit 1
fi

TEST_ROOT="$(mktemp -d /tmp/rewit-predeploy-retry-test.XXXXXX)"
cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

EPHEMERAL_CRED="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
TEST_URL="jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full"

LAST_RC=0
LAST_OUT=""
LAST_ERR=""
LAST_ATTEMPTS=0
CASE_FAILED=0

assert_eq() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf '    [FALHA] %s: esperado=%q obtido=%q\n' "$label" "$expected" "$actual" >&2
    CASE_FAILED=1
    return 1
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf '    [FALHA] %s: texto esperado não encontrado: %q\n' "$label" "$needle" >&2
    CASE_FAILED=1
    return 1
  fi
}

assert_no_secret_leak() {
  local label="$1"
  if [[ "$LAST_OUT" == *"$EPHEMERAL_CRED"* || "$LAST_ERR" == *"$EPHEMERAL_CRED"* ]]; then
    printf '    [FALHA] %s: credencial vazou em stdout/stderr\n' "$label" >&2
    CASE_FAILED=1
    return 1
  fi
}

# run_with_stub <case_name> <codes_csv> <stderr_msg> <extra_env_flag> [predeploy_args...]
run_with_stub() {
  local case_name="$1"
  local codes_csv="$2"
  local stderr_msg="$3"
  local extra_var="$4"
  shift 4

  local case_dir="${TEST_ROOT}/${case_name}"
  local fake_bin="${case_dir}/bin"
  local count_file="${case_dir}/attempts.count"
  local out_file="${case_dir}/run.out"
  local err_file="${case_dir}/run.err"
  mkdir -p "$fake_bin"
  printf '0\n' > "$count_file"

  cat > "${fake_bin}/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "info" ]]; then
  exit 0
fi
count="$(cat "$STUB_COUNT_FILE")"
count=$((count + 1))
printf '%d\n' "$count" > "$STUB_COUNT_FILE"
IFS=',' read -r -a codes <<< "$STUB_CODES_CSV"
idx=$((count - 1))
if (( idx >= ${#codes[@]} )); then
  idx=$((${#codes[@]} - 1))
fi
code="${codes[$idx]}"
if [[ "$code" == "0" ]]; then
  echo "[rewit-migration-runner] Migrations Flyway concluídas com sucesso (executadas=0, versaoAtual=46, rewit_assert_runtime_privileges=OK)."
else
  echo "[rewit-migration-runner] ${STUB_STDERR_MSG} (tentativa=${count}, exit=${code})" >&2
fi
exit "$code"
STUB
  chmod +x "${fake_bin}/docker"

  LAST_RC=0
  if [[ -n "$extra_var" ]]; then
    env -i \
      PATH="${fake_bin}:${PATH}" \
      STUB_COUNT_FILE="$count_file" \
      STUB_CODES_CSV="$codes_csv" \
      STUB_STDERR_MSG="$stderr_msg" \
      DATABASE_URL="$TEST_URL" \
      FLYWAY_DB_USER="rewit_migrator" \
      FLYWAY_DB_PASSWORD="$EPHEMERAL_CRED" \
      "$extra_var" \
      "$PREDEPLOY_BIN" --image "rewit-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000" --runtime docker "$@" \
      >"$out_file" 2>"$err_file" || LAST_RC=$?
  else
    env -i \
      PATH="${fake_bin}:${PATH}" \
      STUB_COUNT_FILE="$count_file" \
      STUB_CODES_CSV="$codes_csv" \
      STUB_STDERR_MSG="$stderr_msg" \
      DATABASE_URL="$TEST_URL" \
      FLYWAY_DB_USER="rewit_migrator" \
      FLYWAY_DB_PASSWORD="$EPHEMERAL_CRED" \
      "$PREDEPLOY_BIN" --image "rewit-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000" --runtime docker "$@" \
      >"$out_file" 2>"$err_file" || LAST_RC=$?
  fi

  LAST_OUT="$(cat "$out_file")"
  LAST_ERR="$(cat "$err_file")"
  LAST_ATTEMPTS="$(cat "$count_file")"
}

run_test() {
  local name="$1"
  local fn="$2"
  TESTS_RUN=$((TESTS_RUN + 1))
  CASE_FAILED=0
  "$fn" || true
  if (( CASE_FAILED == 0 )); then
    TESTS_PASSED=$((TESTS_PASSED + 1))
    printf '[OK]   %s\n' "$name"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    printf '[FAIL] %s\n' "$name" >&2
  fi
}

test_transactional_sql_failure_no_retry() {
  # Mensagem SQL genérica na 1ª tentativa (sem frase 'Detected failed migration')
  run_with_stub "tx-sql-fail" "4,0" "FlywayMigrateException: ERROR: syntax error at or near SELECT" "" --max-attempts 3 --retry-delay 1
  assert_eq "4" "$LAST_RC" "falha SQL transacional deve sair com 4"
  assert_eq "1" "$LAST_ATTEMPTS" "falha SQL transacional não pode ser retentada"
  assert_contains "$LAST_ERR" "FALHA NÃO RETENTÁVEL (exit=4" "deve registrar falha não retentável"
  assert_no_secret_leak "tx-sql-fail"
}

test_nontransactional_sql_failure_no_retry() {
  run_with_stub "nontx-sql-fail" "4,0" "FlywayMigrateException: ERROR: cannot drop index because constraint requires it" "" --max-attempts 3 --retry-delay 1
  assert_eq "4" "$LAST_RC" "falha SQL não transacional deve sair com 4"
  assert_eq "1" "$LAST_ATTEMPTS" "falha SQL não transacional não pode ser retentada"
  assert_no_secret_leak "nontx-sql-fail"
}

test_checksum_and_history_failure_no_retry() {
  run_with_stub "checksum-fail" "4,0" "FlywayValidateException: Migration checksum mismatch for migration version 12" "" --max-attempts 3 --retry-delay 1
  assert_eq "4" "$LAST_RC" "erro de checksum/histórico deve sair com 4"
  assert_eq "1" "$LAST_ATTEMPTS" "erro de checksum/histórico não pode ser retentado"
  assert_no_secret_leak "checksum-fail"
}

test_config_and_privilege_failures_no_retry() {
  run_with_stub "config-fail" "2,0" "Configuração inválida" "" --max-attempts 3 --retry-delay 1
  assert_eq "2" "$LAST_RC" "exit=2 deve propagar sem retry"
  assert_eq "1" "$LAST_ATTEMPTS" "exit=2 não pode ser retentado"
  assert_no_secret_leak "config-fail"

  run_with_stub "priv-fail" "3,0" "Identidade ou privilégios do migrator recusados" "" --max-attempts 3 --retry-delay 1
  assert_eq "3" "$LAST_RC" "exit=3 deve propagar sem retry"
  assert_eq "1" "$LAST_ATTEMPTS" "exit=3 não pode ser retentado"
  assert_no_secret_leak "priv-fail"
}

test_lock_timeout_bounded_retry_and_recovery() {
  # Esgota 3 tentativas -> exit=5
  run_with_stub "lock-exhausted" "5,5,5" "Timeout ao aguardar advisory lock" "" --max-attempts 3 --retry-delay 1
  assert_eq "5" "$LAST_RC" "lock timeout persistente deve sair com 5"
  assert_eq "3" "$LAST_ATTEMPTS" "lock timeout deve respeitar --max-attempts=3"
  assert_no_secret_leak "lock-exhausted"

  # Falha na 1ª tentativa (exit=5) e recupera na 2ª (exit=0)
  run_with_stub "lock-recovered" "5,0" "Timeout ao aguardar advisory lock" "" --max-attempts 3 --retry-delay 1
  assert_eq "0" "$LAST_RC" "lock timeout resolvido na 2ª tentativa deve sair com 0"
  assert_eq "2" "$LAST_ATTEMPTS" "deve executar exatamente 2 tentativas"
  assert_no_secret_leak "lock-recovered"
}

test_transient_infra_bounded_retry_and_recovery() {
  # Esgota 2 tentativas -> exit=6
  run_with_stub "infra-exhausted" "6,6" "Falha transitória de conectividade PostgreSQL (SQLState=08001)" "" --max-attempts 2 --retry-delay 1
  assert_eq "6" "$LAST_RC" "falha transitória persistente deve sair com 6"
  assert_eq "2" "$LAST_ATTEMPTS" "falha transitória classificada (exit=6) deve respeitar --max-attempts=2"
  assert_no_secret_leak "infra-exhausted"

  # Falha transitória na 1ª (exit=6) e recupera na 2ª (exit=0)
  run_with_stub "infra-recovered" "6,0" "Falha transitória de conectividade PostgreSQL (SQLState=57P03)" "" --max-attempts 3 --retry-delay 1
  assert_eq "0" "$LAST_RC" "falha transitória resolvida na 2ª tentativa deve sair com 0"
  assert_eq "2" "$LAST_ATTEMPTS" "deve executar exatamente 2 tentativas"
  assert_no_secret_leak "infra-recovered"
}

test_unknown_exit_codes_fail_closed_without_retry() {
  local code
  for code in 1 42 137; do
    run_with_stub "unknown-${code}" "${code},0" "Erro desconhecido do runtime" "" --max-attempts 3 --retry-delay 1
    assert_eq "$code" "$LAST_RC" "erro desconhecido exit=${code} deve propagar código original"
    assert_eq "1" "$LAST_ATTEMPTS" "erro desconhecido exit=${code} não pode ser retentado (fail-closed)"
    assert_no_secret_leak "unknown-${code}"
  done
}

test_host_lock_file_and_forbidden_env_vars() {
  # Contenção de flock local -> exit=5 sem subir container
  local lock_path="${TEST_ROOT}/host.lock"
  local sub_rc=0
  (
    exec 8>"$lock_path"
    flock -x 8
    run_with_stub "flock-busy" "0" "" "" --lock-file "$lock_path" --max-attempts 3 --retry-delay 1
    assert_eq "5" "$LAST_RC" "flock ocupado deve retornar exit=5" || true
    assert_eq "0" "$LAST_ATTEMPTS" "flock ocupado não pode disparar container" || true
    exit "$CASE_FAILED"
  ) || sub_rc=$?
  if (( sub_rc != 0 )); then
    CASE_FAILED=1
  fi

  # Variável conflitante -> exit=2 sem subir container
  run_with_stub "forbidden-env" "0" "" "SPRING_FLYWAY_ENABLED=true" --max-attempts 3 --retry-delay 1
  assert_eq "2" "$LAST_RC" "SPRING_FLYWAY_ENABLED deve ser rejeitada com exit=2"
  assert_eq "0" "$LAST_ATTEMPTS" "variável proibida não pode disparar container"
  assert_no_secret_leak "forbidden-env"
}

printf '=== Executando suíte descartável de regressão de run-predeploy-migrations.sh (M-02) ===\n'
printf 'Diretório temporário isolado: %s\n\n' "$TEST_ROOT"

run_test "1. Falha SQL transacional na 1ª tentativa (exit=4): 0 retries" test_transactional_sql_failure_no_retry
run_test "2. Falha SQL não transacional na 1ª tentativa (exit=4): 0 retries" test_nontransactional_sql_failure_no_retry
run_test "3. Erro de checksum/histórico (exit=4): 0 retries" test_checksum_and_history_failure_no_retry
run_test "4. Configuração inválida (exit=2) e privilégio (exit=3): 0 retries" test_config_and_privilege_failures_no_retry
run_test "5. Timeout de advisory lock (exit=5): retry limitado e recuperação" test_lock_timeout_bounded_retry_and_recovery
run_test "6. Falha transitória classificada (exit=6): retry limitado e recuperação" test_transient_infra_bounded_retry_and_recovery
run_test "7. Erros desconhecidos (exit=1/42/137): fail-closed com 0 retries" test_unknown_exit_codes_fail_closed_without_retry
run_test "8. Contenção de flock local e variáveis proibidas: 0 containers criados" test_host_lock_file_and_forbidden_env_vars

printf '\n=== Resumo da Suíte (run-predeploy-migrations) ===\n'
printf 'Total executados : %d\n' "$TESTS_RUN"
printf 'Aprovados        : %d\n' "$TESTS_PASSED"
printf 'Falhas           : %d\n' "$TESTS_FAILED"

if (( TESTS_FAILED > 0 )); then
  exit 1
fi
exit 0
