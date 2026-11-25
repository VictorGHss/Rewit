#!/usr/bin/env bash
# ==============================================================================
# Rewit — Suíte de Testes Descartáveis para scripts/worktree-task.sh
#
# Executa exclusivamente contra repositórios Git temporários criados em /tmp,
# nunca modificando o repositório real do Rewit nem o $HOME real do usuário.
# ==============================================================================

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/worktree-task.sh"

# Verifica pré-requisitos da suíte
if [[ ! -x "$TARGET_SCRIPT" ]]; then
  printf 'ERRO: script alvo ausente ou sem permissão executável: %s\n' "$TARGET_SCRIPT" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  printf 'ERRO: python3 é obrigatório para os testes interativos com pseudo-terminal (PTY).\n' >&2
  exit 1
fi

REAL_GIT_BIN="$(command -v git)"

# Diretório temporário raiz controlado com limpeza garantida via trap
TEST_ROOT="$(mktemp -d /tmp/rewit-worktree-task-tests.XXXXXX)"
cleanup() {
  if [[ -n "${TEST_ROOT:-}" && "$TEST_ROOT" == /tmp/rewit-worktree-task-tests.* && -d "$TEST_ROOT" ]]; then
    chmod -R u+rwx -- "$TEST_ROOT" 2>/dev/null || true
    rm -rf -- "$TEST_ROOT"
  fi
}
trap cleanup EXIT INT TERM

# Contadores globais
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
CASE_SEQ=0

# Resultado da última invocação
LAST_RC=0
LAST_OUT=""
LAST_ERR=""

# ------------------------------------------------------------------------------
# Helpers de ambiente descartável e asserções
# ------------------------------------------------------------------------------

# Cria um ambiente completamente isolado (HOME + repositório principal com main)
# Define: CASE_DIR, CASE_HOME, CASE_REPO, MAIN_SHA_1, MAIN_SHA_2
setup_case_env() {
  CASE_SEQ=$((CASE_SEQ + 1))
  CASE_DIR="${TEST_ROOT}/case-${CASE_SEQ}"
  CASE_HOME="${CASE_DIR}/home"
  CASE_REPO="${CASE_DIR}/repo"

  mkdir -p -- "$CASE_HOME" "$CASE_REPO"
  init_disposable_repo "$CASE_REPO"
}

init_disposable_repo() {
  local repo_dir="$1"
  mkdir -p -- "$repo_dir"

  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" init -b main >/dev/null 2>&1
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" config user.name "Rewit Test"
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" config user.email "rewit-test@example.invalid"

  cat > "${repo_dir}/.gitignore" <<'EOF'
.env
.env.*
.envrc
*.env
*.jks
*.keystore
*.pem
*.crt
*.cer
*.der
*.key
*.p12
*.pfx
key.properties
local.properties
secrets/
local-config/
unreadable-ignored/
build/
target/
node_modules/
EOF
  printf 'linha inicial\n' > "${repo_dir}/README.txt"
  printf 'app config\n' > "${repo_dir}/config.txt"

  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" add .gitignore README.txt config.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" commit -m "chore: commit inicial descartável" >/dev/null 2>&1
  MAIN_SHA_1="$(LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" rev-parse HEAD)"

  printf 'segunda linha\n' >> "${repo_dir}/README.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" add README.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" commit -m "feat: segundo commit descartável" >/dev/null 2>&1
  MAIN_SHA_2="$(LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" rev-parse HEAD)"
}

# Executa o script alvo sem TTY (com PATH opcional customizado para wrappers de falha)
run_script_in_with_path() {
  local workdir="$1"
  local custom_path="$2"
  shift 2
  local out_file="${CASE_DIR}/last.out"
  local err_file="${CASE_DIR}/last.err"
  LAST_RC=0
  (
    cd -- "$workdir"
    env HOME="$CASE_HOME" PATH="$custom_path" "$TARGET_SCRIPT" "$@"
  ) >"$out_file" 2>"$err_file" </dev/null || LAST_RC=$?
  LAST_OUT="$(cat -- "$out_file")"
  LAST_ERR="$(cat -- "$err_file")"
}

run_script_in() {
  local workdir="$1"
  shift
  run_script_in_with_path "$workdir" "$PATH" "$@"
}

# Executa o script alvo conectado a um pseudo-terminal real (PTY) via python3.
# Uso: run_script_pty_ex <workdir> <custom_path> <mode:send|eof> <input> <hook_cmd> -- <args...>
run_script_pty_ex() {
  local workdir="$1"
  local custom_path="$2"
  local pty_mode="$3"
  local pty_input="$4"
  local hook_cmd="$5"
  shift 5
  if [[ "${1:-}" == "--" ]]; then
    shift
  fi

  local out_file="${CASE_DIR}/last.pty.out"
  LAST_RC=0
  python3 - "$workdir" "$CASE_HOME" "$custom_path" "$TARGET_SCRIPT" "$pty_mode" "$pty_input" "$hook_cmd" "$out_file" "$@" <<'PYEOF' || LAST_RC=$?
import os
import pty
import select
import subprocess
import sys
import time

workdir = sys.argv[1]
test_home = sys.argv[2]
custom_path = sys.argv[3]
script_path = sys.argv[4]
pty_mode = sys.argv[5]
pty_input = sys.argv[6]
hook_cmd = sys.argv[7]
out_file = sys.argv[8]
script_args = sys.argv[9:]

master_fd, slave_fd = pty.openpty()
env = os.environ.copy()
env["HOME"] = test_home
env["PATH"] = custom_path

proc = subprocess.Popen(
    [script_path] + script_args,
    cwd=workdir,
    env=env,
    stdin=slave_fd,
    stdout=slave_fd,
    stderr=slave_fd,
    close_fds=True,
)
os.close(slave_fd)

captured = bytearray()
sent = False
deadline = time.monotonic() + 15.0

while True:
    if time.monotonic() > deadline:
        proc.kill()
        break

    rlist, _, _ = select.select([master_fd], [], [], 0.05)
    if master_fd in rlist:
        try:
            chunk = os.read(master_fd, 4096)
        except OSError:
            chunk = b""
        if not chunk:
            break
        captured.extend(chunk)
        if not sent and b"Digite exatamente o slug" in captured:
            if hook_cmd:
                hook_env = env.copy()
                subprocess.run(["bash", "-c", hook_cmd], cwd=workdir, env=hook_env, check=True)
            if pty_mode == "eof":
                # Envia Ctrl-D (EOF no modo canônico do PTY)
                os.write(master_fd, b"\x04")
            else:
                os.write(master_fd, (pty_input + "\n").encode("utf-8"))
            sent = True
    else:
        if proc.poll() is not None:
            while True:
                r2, _, _ = select.select([master_fd], [], [], 0.02)
                if not r2:
                    break
                try:
                    chunk = os.read(master_fd, 4096)
                except OSError:
                    break
                if not chunk:
                    break
                captured.extend(chunk)
            break

os.close(master_fd)
rc = proc.wait(timeout=5)
with open(out_file, "wb") as f:
    f.write(captured)
sys.exit(rc)
PYEOF

  LAST_OUT="$(cat -- "$out_file")"
  LAST_ERR="$LAST_OUT"
}

run_script_pty() {
  local workdir="$1"
  local pty_input="$2"
  local hook_cmd="$3"
  shift 3
  run_script_pty_ex "$workdir" "$PATH" "send" "$pty_input" "$hook_cmd" "$@"
}

fail_assert() {
  printf '  [FALHA] %s\n' "$*" >&2
  printf '    rc=%s\n' "$LAST_RC" >&2
  printf '    stdout: %s\n' "$LAST_OUT" >&2
  printf '    stderr: %s\n' "$LAST_ERR" >&2
  exit 1
}

assert_rc() {
  local expected="$1"
  local msg="${2:-código de saída divergente}"
  if [[ "$LAST_RC" != "$expected" ]]; then
    fail_assert "$msg (esperado=$expected, obtido=$LAST_RC)"
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="${3:-texto esperado não encontrado}"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail_assert "$msg (esperado conter: '$needle')"
  fi
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="${3:-texto proibido encontrado}"
  if [[ "$haystack" == *"$needle"* ]]; then
    fail_assert "$msg (não deveria conter: '$needle')"
  fi
}

assert_dir_exists() {
  local d="$1"
  [[ -d "$d" && ! -L "$d" ]] || fail_assert "Diretório esperado ausente ou é symlink: '$d'"
}

assert_path_absent() {
  local p="$1"
  [[ ! -e "$p" && ! -L "$p" ]] || fail_assert "Caminho deveria estar ausente: '$p'"
}

assert_branch_exists() {
  local repo="$1"
  local branch="$2"
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo" show-ref --verify --quiet "refs/heads/${branch}" \
    || fail_assert "Branch deveria existir: '$branch'"
}

assert_branch_absent() {
  local repo="$1"
  local branch="$2"
  if LC_ALL=C "$REAL_GIT_BIN" -C "$repo" show-ref --verify --quiet "refs/heads/${branch}"; then
    fail_assert "Branch não deveria existir: '$branch'"
  fi
}

# Cria um diretório de wrapper com um binário `git` fake customizado
create_fake_git_wrapper() {
  local wrapper_dir="${CASE_DIR}/fake-bin"
  local body="$1"
  mkdir -p -- "$wrapper_dir"
  cat > "${wrapper_dir}/git" <<EOF
#!/usr/bin/env bash
set -euo pipefail
REAL_GIT="${REAL_GIT_BIN}"
${body}
EOF
  chmod +x "${wrapper_dir}/git"
  printf '%s' "${wrapper_dir}:${PATH}"
}

run_test() {
  local name="$1"
  local fn="$2"
  TESTS_RUN=$((TESTS_RUN + 1))
  setup_case_env
  local rc=0
  set +e
  ( set -Eeuo pipefail; "$fn" )
  rc=$?
  set -e
  if (( rc == 0 )); then
    TESTS_PASSED=$((TESTS_PASSED + 1))
    printf '[OK]   %s\n' "$name"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    printf '[FAIL] %s\n' "$name" >&2
  fi
}

# ==============================================================================
# CASOS DE TESTE — AMBIENTE E VERSÃO DO GIT (F2-M1 / F2-B2)
# ==============================================================================

test_env_rejects_old_git_versions_numerically() {
  # 2.9.5 verifica comparação numérica (9 < 36, evitando erro lexical "9" > "36")
  local path_29
  path_29="$(create_fake_git_wrapper '
if [[ "${1:-}" == "--version" ]]; then
  echo "git version 2.9.5"
  exit 0
fi
exec "$REAL_GIT" "$@"
')"
  run_script_in_with_path "$CASE_REPO" "$path_29" status
  assert_rc 4 "Git 2.9.5 deve ser rejeitado com código 4 (comparação numérica)"
  assert_contains "$LAST_ERR" "2.36 ou superior"

  # 2.35.9 (imediatamente abaixo do mínimo 2.36)
  local path_235
  path_235="$(create_fake_git_wrapper '
if [[ "${1:-}" == "--version" ]]; then
  echo "git version 2.35.9"
  exit 0
fi
exec "$REAL_GIT" "$@"
')"
  run_script_in_with_path "$CASE_REPO" "$path_235" status
  assert_rc 4 "Git 2.35.9 deve ser rejeitado com código 4"
  assert_contains "$LAST_ERR" "encontrado: 2.35"

  # 2.36.0 deve passar na checagem de versão
  local path_236
  path_236="$(create_fake_git_wrapper '
if [[ "${1:-}" == "--version" ]]; then
  echo "git version 2.36.0"
  exit 0
fi
exec "$REAL_GIT" "$@"
')"
  run_script_in_with_path "$CASE_REPO" "$path_236" --help
  assert_rc 0 "Git 2.36.0 deve satisfazer o requisito mínimo"
}

# ==============================================================================
# CASOS DE TESTE — CREATE (FUNCIONAL, ADVERSARIAL E FALHAS SIMULADAS)
# ==============================================================================

test_create_valid_and_shared_git() {
  run_script_in "$CASE_REPO" create --slug tarefa-valida --base "$MAIN_SHA_1"
  assert_rc 0 "create válido deve retornar 0"

  local expected_dir="${CASE_HOME}/source/rewit-worktrees/tarefa-valida"
  assert_dir_exists "$expected_dir"
  assert_branch_exists "$CASE_REPO" "agent/neon/tarefa-valida"

  local wt_head
  wt_head="$(LC_ALL=C "$REAL_GIT_BIN" -C "$expected_dir" rev-parse HEAD)"
  [[ "$wt_head" == "$MAIN_SHA_1" ]] || fail_assert "HEAD da nova worktree diverge do SHA base ($wt_head != $MAIN_SHA_1)"

  [[ -f "${expected_dir}/.git" && ! -d "${expected_dir}/.git" ]] \
    || fail_assert "Nova worktree deve possuir arquivo .git vinculado, não clone independente"
  local main_common wt_common
  main_common="$(realpath -e -- "$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse --path-format=absolute --git-common-dir)")"
  wt_common="$(realpath -e -- "$(LC_ALL=C "$REAL_GIT_BIN" -C "$expected_dir" rev-parse --path-format=absolute --git-common-dir)")"
  [[ "$main_common" == "$wt_common" ]] || fail_assert "Diretório Git comum diverge ($main_common != $wt_common)"

  assert_contains "$LAST_OUT" "$expected_dir" "saída deve exibir caminho criado"
  assert_contains "$LAST_OUT" "agent/neon/tarefa-valida" "saída deve exibir branch criada"
  assert_contains "$LAST_OUT" "$MAIN_SHA_1" "saída deve exibir SHA-base"
}

test_create_from_main_with_spaces_and_accents_keeps_ascii_dest() {
  local accented_repo="${CASE_DIR}/Área de trabalho/Rewit Ação"
  init_disposable_repo "$accented_repo"

  run_script_in "$accented_repo" create --slug destino-ascii --base "$MAIN_SHA_2"
  assert_rc 0 "create a partir de caminho com espaços e acentos deve retornar 0"

  local expected_dir="${CASE_HOME}/source/rewit-worktrees/destino-ascii"
  assert_dir_exists "$expected_dir"
  assert_branch_exists "$accented_repo" "agent/neon/destino-ascii"
  local wt_head
  wt_head="$(LC_ALL=C "$REAL_GIT_BIN" -C "$expected_dir" rev-parse HEAD)"
  [[ "$wt_head" == "$MAIN_SHA_2" ]] || fail_assert "HEAD da worktree ASCII diverge do SHA base"
}

test_create_rejects_main_detached_and_other_branch() {
  # 1) Worktree principal em DETACHED HEAD
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" switch --detach "$MAIN_SHA_2" >/dev/null 2>&1
  run_script_in "$CASE_REPO" create --slug na-detached --base "$MAIN_SHA_2"
  assert_rc 3 "create com main em DETACHED HEAD deve bloquear com código 3"
  assert_contains "$LAST_ERR" "DETACHED HEAD"
  assert_branch_absent "$CASE_REPO" "agent/neon/na-detached"
  assert_path_absent "${CASE_HOME}/source/rewit-worktrees/na-detached"

  # 2) Worktree principal em outra branch
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" switch -c "outra-branch" >/dev/null 2>&1
  run_script_in "$CASE_REPO" create --slug em-outra-branch --base "$MAIN_SHA_2"
  assert_rc 3 "create a partir de outra branch deve bloquear com código 3"
  assert_contains "$LAST_ERR" "outra-branch"
  assert_branch_absent "$CASE_REPO" "agent/neon/em-outra-branch"
  assert_path_absent "${CASE_HOME}/source/rewit-worktrees/em-outra-branch"
}

test_create_rejects_non_commit_objects_blob_and_tree() {
  local blob_sha tree_sha
  blob_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse "${MAIN_SHA_2}:README.txt")"
  tree_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rev-parse "${MAIN_SHA_2}^{tree}")"

  run_script_in "$CASE_REPO" create --slug base-blob --base "$blob_sha"
  assert_rc 3 "--base apontando para blob deve bloquear com código 3"
  assert_contains "$LAST_ERR" "blob"
  assert_branch_absent "$CASE_REPO" "agent/neon/base-blob"

  run_script_in "$CASE_REPO" create --slug base-tree --base "$tree_sha"
  assert_rc 3 "--base apontando para tree deve bloquear com código 3"
  assert_contains "$LAST_ERR" "tree"
  assert_branch_absent "$CASE_REPO" "agent/neon/base-tree"
}

test_create_rejects_invalid_slugs() {
  local bad_slugs=("../escape" "foo/bar" "com espaco" "Acento-á" "Maiuscula" "-hifen-inicio" "hifen-fim-" "ponto.slug" "")
  local s
  for s in "${bad_slugs[@]}"; do
    run_script_in "$CASE_REPO" create --slug "$s" --base "$MAIN_SHA_2"
    assert_rc 2 "slug inválido '$s' deve falhar com código de uso 2"
  done
}

test_create_rejects_nonexistent_sha_and_symbolic_base() {
  run_script_in "$CASE_REPO" create --slug sha-fake --base "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
  assert_rc 3 "SHA inexistente deve bloquear com código 3"
  assert_branch_absent "$CASE_REPO" "agent/neon/sha-fake"

  run_script_in "$CASE_REPO" create --slug base-simbolica --base "main"
  assert_rc 2 "ref simbólica em --base deve ser rejeitada com código 2"
  assert_branch_absent "$CASE_REPO" "agent/neon/base-simbolica"
}

test_create_rejects_sha_outside_main_ancestry() {
  local temp_wt="${CASE_DIR}/outra-wt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree add -b "outra-branch" "$temp_wt" "$MAIN_SHA_2" >/dev/null 2>&1
  printf 'fora da main\n' > "${temp_wt}/extra.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$temp_wt" add extra.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$temp_wt" commit -m "feat: commit fora da main" >/dev/null 2>&1
  local outside_sha
  outside_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$temp_wt" rev-parse HEAD)"

  run_script_in "$CASE_REPO" create --slug fora-main --base "$outside_sha"
  assert_rc 3 "SHA fora da ancestralidade da main deve bloquear com código 3"
  assert_branch_absent "$CASE_REPO" "agent/neon/fora-main"
}

test_create_rejects_existing_branch_dest_symlink_and_ancestor_symlink() {
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" branch "agent/neon/ja-existe" "$MAIN_SHA_2"
  run_script_in "$CASE_REPO" create --slug ja-existe --base "$MAIN_SHA_2"
  assert_rc 3 "branch já existente deve bloquear com código 3"
  assert_path_absent "${CASE_HOME}/source/rewit-worktrees/ja-existe"

  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees/dir-ocupado"
  run_script_in "$CASE_REPO" create --slug dir-ocupado --base "$MAIN_SHA_2"
  assert_rc 3 "diretório de destino existente deve bloquear com código 3"
  assert_branch_absent "$CASE_REPO" "agent/neon/dir-ocupado"

  mkdir -p -- "${CASE_DIR}/alvo-externo"
  ln -s "${CASE_DIR}/alvo-externo" "${CASE_HOME}/source/rewit-worktrees/link-dest"
  run_script_in "$CASE_REPO" create --slug link-dest --base "$MAIN_SHA_2"
  assert_rc 3 "symlink no destino deve bloquear com código 3"
  assert_contains "$LAST_ERR" "symlink"
  assert_branch_absent "$CASE_REPO" "agent/neon/link-dest"

  # Novo HOME com symlink em diretório ancestral
  local sym_home="${CASE_DIR}/home-sym"
  mkdir -p -- "$sym_home" "${CASE_DIR}/real-source"
  ln -s "${CASE_DIR}/real-source" "${sym_home}/source"
  CASE_HOME="$sym_home"
  run_script_in "$CASE_REPO" create --slug ancestral-link --base "$MAIN_SHA_2"
  assert_rc 3 "symlink em diretório ancestral deve bloquear com código 3"
  assert_contains "$LAST_ERR" "Symlink"
  assert_branch_absent "$CASE_REPO" "agent/neon/ancestral-link"
}

test_create_dirty_main_staged_and_untracked_rules() {
  # Modificação rastreada unstaged simulando arquivo local protegido na main
  printf 'alteracao local protegida\n' >> "${CASE_REPO}/config.txt"
  local before_diff_hash
  before_diff_hash="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" diff | sha256sum | awk '{print $1}')"

  # Sem --allow-dirty-main bloqueia e exibe caminho
  run_script_in "$CASE_REPO" create --slug tarefa-dirty --base "$MAIN_SHA_2"
  assert_rc 3 "main suja sem --allow-dirty-main deve bloquear com código 3"
  assert_contains "$LAST_OUT" "config.txt"
  assert_branch_absent "$CASE_REPO" "agent/neon/tarefa-dirty"

  # Com --allow-dirty-main cria preservando main intacta e sem herdar sujeira
  run_script_in "$CASE_REPO" create --slug tarefa-dirty --base "$MAIN_SHA_2" --allow-dirty-main
  assert_rc 0 "main suja com --allow-dirty-main deve ser permitida"
  assert_contains "$LAST_OUT" "config.txt"

  local after_diff_hash
  after_diff_hash="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" diff | sha256sum | awk '{print $1}')"
  [[ "$before_diff_hash" == "$after_diff_hash" ]] || fail_assert "Modificação local na main foi alterada!"
  [[ "$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" diff --cached --name-only | wc -l)" == "0" ]] \
    || fail_assert "Staging na main não deve ser alterado"

  local wt_dir="${CASE_HOME}/source/rewit-worktrees/tarefa-dirty"
  [[ -z "$(LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" status --short --untracked-files=all)" ]] \
    || fail_assert "Nova worktree não deve herdar modificações sujas da main"

  # Untracked deve ser recusado mesmo com --allow-dirty-main
  printf 'arquivo solto\n' > "${CASE_REPO}/novo-solto.txt"
  run_script_in "$CASE_REPO" create --slug recusa-untracked --base "$MAIN_SHA_2" --allow-dirty-main
  assert_rc 3 "arquivos untracked na main devem ser recusados mesmo com --allow-dirty-main"
  assert_contains "$LAST_ERR" "untracked"
  assert_branch_absent "$CASE_REPO" "agent/neon/recusa-untracked"
  rm -f -- "${CASE_REPO}/novo-solto.txt"

  # Staged deve ser recusado mesmo com --allow-dirty-main
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add config.txt
  run_script_in "$CASE_REPO" create --slug recusa-staged --base "$MAIN_SHA_2" --allow-dirty-main
  assert_rc 3 "arquivos staged na main devem ser recusados mesmo com --allow-dirty-main"
  assert_contains "$LAST_ERR" "staging"
  assert_branch_absent "$CASE_REPO" "agent/neon/recusa-staged"
}

test_create_rejects_duplicate_flags_and_from_secondary_wt() {
  run_script_in "$CASE_REPO" create --slug dup --slug dup2 --base "$MAIN_SHA_2"
  assert_rc 2 "opção --slug duplicada deve retornar erro de uso 2"

  run_script_in "$CASE_REPO" create --slug ok-wt --base "$MAIN_SHA_2"
  assert_rc 0

  local wt_dir="${CASE_HOME}/source/rewit-worktrees/ok-wt"
  run_script_in "$wt_dir" create --slug da-secundaria --base "$MAIN_SHA_2"
  assert_rc 3 "create a partir de worktree secundária deve ser bloqueado"
  assert_branch_absent "$CASE_REPO" "agent/neon/da-secundaria"
}

test_create_simulated_mkdir_failure_returns_code_4() {
  mkdir -p -- "${CASE_HOME}/source"
  chmod 555 -- "${CASE_HOME}/source"

  run_script_in "$CASE_REPO" create --slug falha-mkdir --base "$MAIN_SHA_2"
  chmod 755 -- "${CASE_HOME}/source"

  assert_rc 4 "falha de mkdir ao criar diretório-base deve retornar código contratual 4"
  assert_contains "$LAST_ERR" "Falha ao criar o diretório-base"
  assert_branch_absent "$CASE_REPO" "agent/neon/falha-mkdir"
}

test_create_simulated_git_worktree_add_failure_and_fail_closed_retry() {
  # Simula falha parcial de `git worktree add`: cria diretório residual parcial e sai com 128
  local partial_dir="${CASE_HOME}/source/rewit-worktrees/falha-parcial"
  local fake_path
  fake_path="$(create_fake_git_wrapper "
if [[ \"\$*\" == *\"worktree add\"* ]]; then
  mkdir -p -- \"${partial_dir}\"
  printf 'residuo parcial\\n' > \"${partial_dir}/parcial.tmp\"
  echo 'fatal: falha simulada durante git worktree add' >&2
  exit 128
fi
exec \"\$REAL_GIT\" \"\$@\"
")"

  run_script_in_with_path "$CASE_REPO" "$fake_path" create --slug falha-parcial --base "$MAIN_SHA_2"
  assert_rc 4 "falha simulada de git worktree add deve retornar código contratual 4"
  assert_contains "$LAST_ERR" "falha simulada durante git worktree add" "deve preservar diagnóstico do Git"
  assert_contains "$LAST_ERR" "estado residual" "deve avisar sobre possível estado residual"
  assert_contains "$LAST_ERR" "Nenhuma limpeza destrutiva automática"
  # Confirma ausência de cleanup destrutivo automático
  assert_dir_exists "$partial_dir"
  [[ -f "${partial_dir}/parcial.tmp" ]] || fail_assert "Arquivo residual não deveria ter sido removido automaticamente"

  # Nova execução com o Git real após a falha parcial DEVE falhar em modo fail-closed (código 3)
  run_script_in "$CASE_REPO" create --slug falha-parcial --base "$MAIN_SHA_2"
  assert_rc 3 "execução posterior após falha parcial deve bloquear (fail-closed) com código 3"
  assert_contains "$LAST_ERR" "já existe"
  assert_branch_absent "$CASE_REPO" "agent/neon/falha-parcial"
}

# ==============================================================================
# CASOS DE TESTE — STATUS (RESILIÊNCIA, NOMES ADVERSARIAIS E READ-ONLY)
# ==============================================================================

test_status_reports_all_states_and_adversarial_filenames_readonly() {
  # 1) Worktree limpa com diretório de build ignorado (não sensível)
  run_script_in "$CASE_REPO" create --slug wt-limpa --base "$MAIN_SHA_2"
  assert_rc 0
  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees/wt-limpa/build"
  printf 'artefato\n' > "${CASE_HOME}/source/rewit-worktrees/wt-limpa/build/app.jar"

  # 2) Worktree com nomes adversariais (colchetes, espaços, acentos, metacaracteres),
  #    alterações staged+unstaged simultâneas (MM), untracked, commit exclusivo e ignorado sensível
  run_script_in "$CASE_REPO" create --slug wt-complexa --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_complex="${CASE_HOME}/source/rewit-worktrees/wt-complexa"
  local f1="arquivo[0].txt"
  local f2='arq[$(touch pwn-nao-deve-existir)].txt'
  local f3="ação espaço [1].txt"

  printf 'base1\n' > "${wt_complex}/${f1}"
  printf 'base2\n' > "${wt_complex}/${f2}"
  printf 'base3\n' > "${wt_complex}/${f3}"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_complex" add -- "$f1" "$f2" "$f3"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_complex" commit -m "feat: commit exclusivo com nomes adversariais" >/dev/null 2>&1

  # Modifica staged + unstaged simultaneamente (MM) nos 3 arquivos adversariais
  printf 'staged\n' >> "${wt_complex}/${f1}"
  printf 'staged\n' >> "${wt_complex}/${f2}"
  printf 'staged\n' >> "${wt_complex}/${f3}"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_complex" add -- "$f1" "$f2" "$f3"
  printf 'unstaged\n' >> "${wt_complex}/${f1}"
  printf 'unstaged\n' >> "${wt_complex}/${f2}"
  printf 'unstaged\n' >> "${wt_complex}/${f3}"

  printf 'arquivo nao rastreado\n' > "${wt_complex}/solto.txt"
  printf 'SEGREDO_SUPER_CONFIDENCIAL=nao-pode-vazar\n' > "${wt_complex}/.env.local"

  # 3) Worktree detached HEAD + locked
  local wt_detached="${CASE_DIR}/wt-detached-locked"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree add --detach "$wt_detached" "$MAIN_SHA_1" >/dev/null 2>&1
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree lock "$wt_detached" >/dev/null 2>&1

  # Snapshot antes do status
  local refs_before status_main_before status_wt_before
  refs_before="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" show-ref)"
  status_main_before="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" status --porcelain=v1 --untracked-files=all)"
  status_wt_before="$(LC_ALL=C "$REAL_GIT_BIN" -C "$wt_complex" status --porcelain=v1 --untracked-files=all)"

  run_script_in "$CASE_REPO" status
  assert_rc 0 "status com todas as worktrees verificáveis deve retornar 0"

  # Confirma ausência de execução de comando via nome de arquivo (F2-B1)
  assert_path_absent "${CASE_REPO}/pwn-nao-deve-existir"
  assert_path_absent "${wt_complex}/pwn-nao-deve-existir"

  assert_contains "$LAST_OUT" "Worktree principal     : sim"
  assert_contains "$LAST_OUT" "Branch                 : DETACHED HEAD"
  assert_contains "$LAST_OUT" "Bloqueio Git (locked)  : sim"
  assert_contains "$LAST_OUT" "Estado da árvore       : limpa"
  assert_contains "$LAST_OUT" "Estado da árvore       : suja"
  assert_contains "$LAST_OUT" "Alterações rastreadas  : 3 (staged: 3, unstaged: 3)" "deduplicação MM de 3 nomes adversariais deve contar exatamente 3"
  assert_contains "$LAST_OUT" "Arquivos untracked     : 1"
  assert_contains "$LAST_OUT" "Commits exclusivos     : 1"
  assert_contains "$LAST_OUT" ".env.local"
  assert_not_contains "$LAST_OUT" "SEGREDO_SUPER_CONFIDENCIAL" "nunca deve imprimir conteúdo de arquivo sensível"
  assert_not_contains "$LAST_OUT" "build/app.jar" "não deve classificar diretório de build como sensível"

  # Verifica zero modificação de estado Git
  [[ "$refs_before" == "$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" show-ref)" ]] || fail_assert "status alterou referências Git"
  [[ "$status_main_before" == "$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" status --porcelain=v1 --untracked-files=all)" ]] || fail_assert "status alterou estado da main"
  [[ "$status_wt_before" == "$(LC_ALL=C "$REAL_GIT_BIN" -C "$wt_complex" status --porcelain=v1 --untracked-files=all)" ]] || fail_assert "status alterou estado da worktree"
}

test_status_resilience_prunable_missing_invalid_dotgit_and_unreadable_dir() {
  # Cria 5 worktrees: 4 defeituosas (prunable/ausente, .git ausente, .git corrompido como diretório,
  # diretório ignorado ilegível) + 1 saudável no final
  run_script_in "$CASE_REPO" create --slug wt-a-prunable --base "$MAIN_SHA_2"
  assert_rc 0
  run_script_in "$CASE_REPO" create --slug wt-b-sem-git --base "$MAIN_SHA_2"
  assert_rc 0
  run_script_in "$CASE_REPO" create --slug wt-c-git-dir --base "$MAIN_SHA_2"
  assert_rc 0
  run_script_in "$CASE_REPO" create --slug wt-d-perm --base "$MAIN_SHA_2"
  assert_rc 0
  run_script_in "$CASE_REPO" create --slug wt-z-saudavel --base "$MAIN_SHA_2"
  assert_rc 0

  # 1) Prunable / diretório ausente
  rm -rf -- "${CASE_HOME}/source/rewit-worktrees/wt-a-prunable"
  # 2) Diretório registrado sem arquivo .git (marcado prunable pelo Git)
  rm -f -- "${CASE_HOME}/source/rewit-worktrees/wt-b-sem-git/.git"
  # 3) Diretório registrado com .git corrompido (substituído por diretório)
  rm -f -- "${CASE_HOME}/source/rewit-worktrees/wt-c-git-dir/.git"
  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees/wt-c-git-dir/.git"
  # 4) Diretório ignorado sem permissão de leitura
  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees/wt-d-perm/unreadable-ignored"
  printf 'x\n' > "${CASE_HOME}/source/rewit-worktrees/wt-d-perm/unreadable-ignored/dado.txt"
  chmod 000 -- "${CASE_HOME}/source/rewit-worktrees/wt-d-perm/unreadable-ignored"

  local refs_before
  refs_before="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" show-ref)"

  run_script_in "$CASE_REPO" status
  chmod 755 -- "${CASE_HOME}/source/rewit-worktrees/wt-d-perm/unreadable-ignored"

  assert_rc 3 "status com worktrees não verificáveis deve retornar código 3"
  assert_contains "$LAST_OUT" "wt-a-prunable" "deve listar worktree prunable/ausente"
  assert_contains "$LAST_OUT" "INCONSISTENTE / PRUNABLE (diretório ausente)"
  assert_contains "$LAST_OUT" "wt-b-sem-git" "deve listar worktree sem arquivo .git"
  assert_contains "$LAST_OUT" "wt-c-git-dir" "deve listar worktree com .git corrompido como diretório"
  assert_contains "$LAST_OUT" "clone independente"
  assert_contains "$LAST_OUT" "wt-d-perm" "deve listar worktree com erro de permissão em ignorado"
  assert_contains "$LAST_OUT" "Ignorados sensíveis    : NÃO VERIFICÁVEL"
  assert_not_contains "$LAST_ERR" "Falha inesperada de ambiente" "erro esperado de find não deve disparar trap ERR espúrio"
  # Confirma que a listagem NÃO foi abortada e apresentou a worktree saudável seguinte
  assert_contains "$LAST_OUT" "wt-z-saudavel" "deve continuar listando worktrees saudáveis após erros individuais"
  assert_contains "$LAST_OUT" "Branch                 : agent/neon/wt-z-saudavel"
  assert_contains "$LAST_ERR" "4 worktree(s) não puderam ser verificadas completamente"

  [[ "$refs_before" == "$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" show-ref)" ]] || fail_assert "status alterou referências Git"
}

# ==============================================================================
# CASOS DE TESTE — CLOSE (SALVAGUARDAS, SENSÍVEIS, PTY, FALHAS E ADVERSARIAIS)
# ==============================================================================

test_close_dry_run_default_does_not_remove() {
  run_script_in "$CASE_REPO" create --slug sim-ok --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/sim-ok"

  run_script_in "$CASE_REPO" close --slug sim-ok
  assert_rc 0 "simulação padrão em worktree limpa e integrada deve retornar 0"
  assert_contains "$LAST_OUT" "APROVADO"
  assert_dir_exists "$wt_dir"
  assert_branch_exists "$CASE_REPO" "agent/neon/sim-ok"
}

test_close_blocks_dirty_staged_untracked_exclusive_and_cherry_pick() {
  run_script_in "$CASE_REPO" create --slug bloqueios --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/bloqueios"

  # 1) Alteração rastreada unstaged
  printf 'mod\n' >> "${wt_dir}/config.txt"
  run_script_in "$CASE_REPO" close --slug bloqueios
  assert_rc 3 "alteração rastreada deve bloquear close"
  assert_contains "$LAST_ERR" "modificação(ões) rastreada(s)"

  # 2) Arquivo staged
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add config.txt
  run_script_in "$CASE_REPO" close --slug bloqueios
  assert_rc 3 "arquivo staged deve bloquear close"
  assert_contains "$LAST_ERR" "staging"

  # 3) Commit exclusivo
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "feat: exclusivo" >/dev/null 2>&1
  run_script_in "$CASE_REPO" close --slug bloqueios
  assert_rc 3 "commit exclusivo deve bloquear close"
  assert_contains "$LAST_ERR" "commit(s) exclusivo(s)"

  # 4) Patch equivalente via cherry-pick sobre commit intermediário (SHA distinto, sem ancestralidade direta)
  printf 'commit intermediario na main\n' >> "${CASE_REPO}/README.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add README.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -m "chore: avanca main antes de cherry-pick" >/dev/null 2>&1
  local exclusive_sha cherry_count
  exclusive_sha="$(LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rev-parse HEAD)"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" cherry-pick "$exclusive_sha" >/dev/null 2>&1
  cherry_count="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" cherry refs/heads/main "$exclusive_sha" | grep -c '^- ' || true)"
  [[ "$cherry_count" == "1" ]] || fail_assert "setup de cherry-pick deveria gerar patch equivalente em git cherry"
  run_script_in "$CASE_REPO" close --slug bloqueios
  assert_rc 3 "patch equivalente por cherry-pick NÃO deve autorizar close sem ancestralidade direta"
  assert_contains "$LAST_ERR" "commit(s) exclusivo(s)"

  # 5) Arquivo untracked
  run_script_in "$CASE_REPO" create --slug bloqueios-untracked --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir2="${CASE_HOME}/source/rewit-worktrees/bloqueios-untracked"
  printf 'untracked\n' > "${wt_dir2}/solto.txt"
  run_script_in "$CASE_REPO" close --slug bloqueios-untracked
  assert_rc 3 "arquivo untracked deve bloquear close"
  assert_contains "$LAST_ERR" "não rastreado(s)"
}

test_close_blocks_each_sensitive_ignored_pattern_individually() {
  run_script_in "$CASE_REPO" create --slug sensiveis --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/sensiveis"
  mkdir -p -- "${wt_dir}/secrets"

  # Cobre todos os padrões originais e cada novo padrão exigido em F2-B4 individualmente
  local sens_files=(
    ".env"
    ".env.prod"
    ".envrc"
    "secrets.env"
    "local.properties"
    "server.crt"
    "client.cer"
    "key.der"
    "release.keystore"
    "cert.pem"
    "priv.key"
    "bundle.p12"
    "cert.pfx"
    "store.jks"
    "key.properties"
    "secrets/token.key"
  )

  local sf
  for sf in "${sens_files[@]}"; do
    printf 'VALOR_SECRETO_UNICO_%s\n' "$sf" > "${wt_dir}/${sf}"
    run_script_in "$CASE_REPO" close --slug sensiveis
    assert_rc 3 "arquivo ignorado sensível '$sf' deve bloquear close com código 3"
    assert_contains "$LAST_ERR" "$sf" "deve reportar caminho relativo '$sf'"
    assert_not_contains "$LAST_ERR" "VALOR_SECRETO_UNICO" "nunca deve expor conteúdo do arquivo sensível '$sf'"
    assert_dir_exists "$wt_dir"
    rm -f -- "${wt_dir}/${sf}"
  done
  rmdir -- "${wt_dir}/secrets"

  # Diretório de build/cache não sensível deve continuar permitido
  mkdir -p -- "${wt_dir}/target" "${wt_dir}/node_modules"
  printf 'compiled\n' > "${wt_dir}/target/out.bin"
  printf 'pkg\n' > "${wt_dir}/node_modules/index.js"
  run_script_in "$CASE_REPO" close --slug sensiveis
  assert_rc 0 "diretórios de build/cache não sensíveis não devem bloquear close"
}

test_close_blocks_self_dir_locked_detached_and_independent_clone() {
  run_script_in "$CASE_REPO" create --slug alvo-estados --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/alvo-estados"

  # 1) Execução a partir de dentro da própria worktree-alvo
  run_script_in "$wt_dir" close --slug alvo-estados
  assert_rc 3 "close executado dentro da própria worktree-alvo deve bloquear"
  assert_contains "$LAST_ERR" "dentro dela mesma"
  assert_dir_exists "$wt_dir"

  # 2) Worktree locked
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree lock "$wt_dir" >/dev/null 2>&1
  run_script_in "$CASE_REPO" close --slug alvo-estados
  assert_rc 3 "worktree locked deve bloquear close"
  assert_contains "$LAST_ERR" "locked"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree unlock "$wt_dir" >/dev/null 2>&1

  # 3) Worktree em DETACHED HEAD
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" switch --detach "$MAIN_SHA_2" >/dev/null 2>&1
  run_script_in "$CASE_REPO" close --slug alvo-estados
  assert_rc 3 "worktree em DETACHED HEAD deve bloquear close"
  assert_contains "$LAST_ERR" "DETACHED HEAD"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" switch "agent/neon/alvo-estados" >/dev/null 2>&1

  # 4) Diretório de clone independente (.git como diretório) no caminho derivado não registrado
  local clone_dir="${CASE_HOME}/source/rewit-worktrees/clone-indep"
  LC_ALL=C "$REAL_GIT_BIN" clone --quiet "$CASE_REPO" "$clone_dir"
  [[ -d "${clone_dir}/.git" ]] || fail_assert "setup deveria criar .git como diretório"
  run_script_in "$CASE_REPO" close --slug clone-indep
  assert_rc 3 "clone independente no caminho esperado deve bloquear close"
  assert_contains "$LAST_ERR" "clone independente"
  assert_dir_exists "$clone_dir"

  # 5) Worktree registrada cujo arquivo .git foi corrompido/substituído por diretório .git
  #    Comprova interrupção imediata em audit_worktree_for_close sem fallback para repo pai
  rm -f -- "${wt_dir}/.git"
  mkdir -p -- "${wt_dir}/.git"
  run_script_in "$CASE_REPO" close --slug alvo-estados
  assert_rc 3 "worktree registrada com .git corrompido como diretório deve bloquear imediatamente"
  assert_contains "$LAST_ERR" "Vínculo '.git' inválido"
  assert_dir_exists "$wt_dir"
}

test_close_blocks_main_worktree_invalid_slug_and_invalid_options() {
  run_script_in "$CASE_REPO" close --slug "../invalido"
  assert_rc 2 "slug inválido em close deve retornar 2"

  local main_at_target="${CASE_HOME}/source/rewit-worktrees/repo-principal"
  init_disposable_repo "$main_at_target"

  run_script_in "$main_at_target" close --slug repo-principal
  assert_rc 3 "tentativa de fechar a worktree principal deve bloquear com código 3"
  assert_contains "$LAST_ERR" "worktree principal"
  assert_dir_exists "$main_at_target"

  run_script_in "$CASE_REPO" close --slug qualq --path "$CASE_REPO"
  assert_rc 2 "opção arbitrária --path em close deve falhar com código 2"
  run_script_in "$CASE_REPO" close --slug qualq --dry-run --execute
  assert_rc 2 "combinação --dry-run e --execute deve falhar com código 2"
}

test_close_blocks_without_tty_wrong_confirmation_and_eof() {
  run_script_in "$CASE_REPO" create --slug tty-test --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/tty-test"

  # 1) Sem terminal interativo
  run_script_in "$CASE_REPO" close --slug tty-test --execute
  assert_rc 3 "close --execute sem TTY deve bloquear com código 3"
  assert_contains "$LAST_ERR" "terminal interativo"
  assert_dir_exists "$wt_dir"

  # 2) Confirmação com slug divergente em PTY
  run_script_pty "$CASE_REPO" "slug-errado" "" -- close --slug tty-test --execute
  assert_rc 3 "confirmação com slug incorreto deve bloquear com código 3"
  assert_contains "$LAST_OUT" "Confirmação divergente"
  assert_dir_exists "$wt_dir"
  assert_branch_exists "$CASE_REPO" "agent/neon/tty-test"

  # 3) EOF (Ctrl-D) durante a confirmação interativa em PTY
  run_script_pty_ex "$CASE_REPO" "$PATH" "eof" "" "" -- close --slug tty-test --execute
  assert_rc 3 "EOF durante a confirmação interativa deve cancelar com código 3"
  assert_contains "$LAST_OUT" "EOF"
  assert_dir_exists "$wt_dir"
  assert_branch_exists "$CASE_REPO" "agent/neon/tty-test"
}

test_close_execute_success_idempotent_both_modes_and_accented_main_path() {
  local accented_repo="${CASE_DIR}/Área de trabalho/Rewit Ação"
  init_disposable_repo "$accented_repo"

  run_script_in "$accented_repo" create --slug fechar-esta --base "$MAIN_SHA_2"
  assert_rc 0
  run_script_in "$accented_repo" create --slug manter-outra --base "$MAIN_SHA_2"
  assert_rc 0

  local wt_close="${CASE_HOME}/source/rewit-worktrees/fechar-esta"
  local wt_keep="${CASE_HOME}/source/rewit-worktrees/manter-outra"

  # Execução autorizada em PTY real com confirmação exata a partir de repo principal com espaços/acentos
  run_script_pty "$accented_repo" "fechar-esta" "" -- close --slug fechar-esta --execute
  assert_rc 0 "close --execute com confirmação exata deve remover somente a worktree"
  assert_path_absent "$wt_close"
  assert_branch_exists "$accented_repo" "agent/neon/fechar-esta"

  # Outra worktree permanece intacta
  assert_dir_exists "$wt_keep"
  assert_branch_exists "$accented_repo" "agent/neon/manter-outra"

  local refs_after_close
  refs_after_close="$(LC_ALL=C "$REAL_GIT_BIN" -C "$accented_repo" show-ref)"

  # Idempotência em --dry-run
  run_script_in "$accented_repo" close --slug fechar-esta
  assert_rc 0 "close idempotente em modo padrão deve retornar 0"
  assert_contains "$LAST_OUT" "já encerrada"
  assert_branch_exists "$accented_repo" "agent/neon/fechar-esta"

  # Idempotência em --execute após já encerrada (sem remover branch nem alterar refs)
  run_script_in "$accented_repo" close --slug fechar-esta --execute
  assert_rc 0 "close --execute após worktree já encerrada deve ser idempotente (código 0)"
  assert_contains "$LAST_OUT" "já encerrada"
  assert_branch_exists "$accented_repo" "agent/neon/fechar-esta"
  [[ "$refs_after_close" == "$(LC_ALL=C "$REAL_GIT_BIN" -C "$accented_repo" show-ref)" ]] \
    || fail_assert "close --execute idempotente não deve alterar referências Git"
}

test_close_simulated_git_worktree_remove_failure_returns_code_4() {
  run_script_in "$CASE_REPO" create --slug falha-remove --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/falha-remove"

  local fake_path
  fake_path="$(create_fake_git_wrapper '
if [[ "$*" == *"worktree remove"* ]]; then
  echo "fatal: falha simulada em git worktree remove" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"

  run_script_pty_ex "$CASE_REPO" "$fake_path" "send" "falha-remove" "" -- close --slug falha-remove --execute
  assert_rc 4 "falha simulada de git worktree remove deve retornar código contratual 4"
  assert_contains "$LAST_OUT" "falha simulada em git worktree remove" "deve preservar diagnóstico do Git"
  assert_contains "$LAST_OUT" "preservada"
  assert_dir_exists "$wt_dir"
  assert_branch_exists "$CASE_REPO" "agent/neon/falha-remove"
}

# ==============================================================================
# CASOS ADVERSARIAIS ADICIONAIS (TOCTOU, INCONSISTÊNCIAS E SYMLINKS)
# ==============================================================================

test_adversarial_branch_divergent_unexpected_path_orphan_and_prunable() {
  # 1) Branch divergente e commit não integrado
  run_script_in "$CASE_REPO" create --slug adv-branch --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/adv-branch"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" switch -c "agent/neon/outra-coisa" >/dev/null 2>&1
  run_script_in "$CASE_REPO" close --slug adv-branch
  assert_rc 3 "worktree apontando para outra branch deve bloquear"
  assert_contains "$LAST_ERR" "divergente"

  # 2) Worktree registrada em caminho inesperado / branch anexada em outro local
  local weird_path="${CASE_DIR}/fora-do-padrao/caminho-estranho"
  mkdir -p -- "${CASE_DIR}/fora-do-padrao"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree add -b "agent/neon/caminho-estranho" "$weird_path" "$MAIN_SHA_2" >/dev/null 2>&1
  run_script_in "$CASE_REPO" close --slug caminho-estranho
  assert_rc 3 "worktree registrada em caminho inesperado deve bloquear"
  assert_contains "$LAST_ERR" "fora do caminho esperado"
  assert_dir_exists "$weird_path"

  # 3) Diretório órfão e prunable não tratados como idempotentes
  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees/orfao"
  run_script_in "$CASE_REPO" close --slug orfao
  assert_rc 3 "diretório órfão não registrado deve bloquear"
  assert_contains "$LAST_ERR" "não está registrado"

  run_script_in "$CASE_REPO" create --slug prunable-wt --base "$MAIN_SHA_2"
  assert_rc 0
  rm -rf -- "${CASE_HOME}/source/rewit-worktrees/prunable-wt"
  run_script_in "$CASE_REPO" close --slug prunable-wt
  assert_rc 3 "associação prunable/incompleta deve bloquear"
  assert_contains "$LAST_ERR" "prunable"
}

test_adversarial_main_moved_after_dry_run_and_toctou_during_prompt() {
  # 1) Main movimentada após dry-run e antes de --execute
  run_script_in "$CASE_REPO" create --slug main-movida --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_movida="${CASE_HOME}/source/rewit-worktrees/main-movida"
  run_script_in "$CASE_REPO" close --slug main-movida
  assert_rc 0
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" update-ref refs/heads/main "$MAIN_SHA_1"
  run_script_pty "$CASE_REPO" "main-movida" "" -- close --slug main-movida --execute
  assert_rc 3 "main movimentada após simulação deve bloquear execução"
  assert_dir_exists "$wt_movida"
  # Restaura main para MAIN_SHA_2 para os próximos subcenários
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" update-ref refs/heads/main "$MAIN_SHA_2"

  # 2) Arquivo untracked criado durante o prompt interativo
  run_script_in "$CASE_REPO" create --slug toctou-dirty --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dirty="${CASE_HOME}/source/rewit-worktrees/toctou-dirty"
  local hook_dirty="printf 'surgiu-no-meio\n' > '${wt_dirty}/surgiu-durante-prompt.txt'"
  run_script_pty "$CASE_REPO" "toctou-dirty" "$hook_dirty" -- close --slug toctou-dirty --execute
  assert_rc 3 "arquivo untracked criado durante prompt deve bloquear na revalidação"
  assert_contains "$LAST_OUT" "mudou antes da remoção"
  assert_dir_exists "$wt_dirty"

  # 3) HEAD da worktree alterado durante o prompt interativo (ex.: movido para MAIN_SHA_1, ainda ancestral)
  run_script_in "$CASE_REPO" create --slug toctou-wt-head --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_head_chg="${CASE_HOME}/source/rewit-worktrees/toctou-wt-head"
  local hook_wt_head="LC_ALL=C '${REAL_GIT_BIN}' -C '${wt_head_chg}' update-ref refs/heads/agent/neon/toctou-wt-head '${MAIN_SHA_1}' && LC_ALL=C '${REAL_GIT_BIN}' -C '${wt_head_chg}' read-tree --reset -u HEAD"
  run_script_pty "$CASE_REPO" "toctou-wt-head" "$hook_wt_head" -- close --slug toctou-wt-head --execute
  assert_rc 3 "HEAD da worktree alterado durante prompt deve bloquear na revalidação"
  assert_contains "$LAST_OUT" "O HEAD da worktree mudou entre a verificação inicial"
  assert_dir_exists "$wt_head_chg"

  # 4) Main movimentada durante o prompt interativo
  run_script_in "$CASE_REPO" create --slug toctou-main --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_main_move="${CASE_HOME}/source/rewit-worktrees/toctou-main"
  local hook_move_main="printf 'novo-na-main\n' >> '${CASE_REPO}/README.txt' && LC_ALL=C '${REAL_GIT_BIN}' -C '${CASE_REPO}' add README.txt && LC_ALL=C '${REAL_GIT_BIN}' -C '${CASE_REPO}' commit -m 'chore: avanca main durante prompt' >/dev/null 2>&1"
  run_script_pty "$CASE_REPO" "toctou-main" "$hook_move_main" -- close --slug toctou-main --execute
  assert_rc 3 "main movimentada entre verificação inicial e revalidação deve bloquear remoção"
  assert_contains "$LAST_OUT" "foi movimentada entre a verificação inicial"
  assert_dir_exists "$wt_main_move"
}

test_adversarial_symlinks_in_close_and_ignored_dirs() {
  # 1) Symlink em diretório ancestral em close
  mkdir -p -- "${CASE_DIR}/real-src/rewit-worktrees"
  ln -s "${CASE_DIR}/real-src" "${CASE_HOME}/source"
  run_script_in "$CASE_REPO" close --slug qualquer-slug
  assert_rc 3 "close deve bloquear quando existe symlink em diretório ancestral"
  assert_contains "$LAST_ERR" "Symlink"

  # 2) Symlink no destino em close
  rm -f -- "${CASE_HOME}/source"
  mkdir -p -- "${CASE_HOME}/source/rewit-worktrees" "${CASE_DIR}/alvo-link"
  ln -s "${CASE_DIR}/alvo-link" "${CASE_HOME}/source/rewit-worktrees/dest-link"
  run_script_in "$CASE_REPO" close --slug dest-link
  assert_rc 3 "close deve bloquear quando o caminho derivado é um symlink"
  assert_contains "$LAST_ERR" "symlink"

  # 3) Symlink para fora dentro de diretório ignorado não seguido; symlink sensível bloqueado
  run_script_in "$CASE_REPO" create --slug sym-ign --base "$MAIN_SHA_2"
  assert_rc 0
  local wt_dir="${CASE_HOME}/source/rewit-worktrees/sym-ign"
  mkdir -p -- "${CASE_DIR}/externo" "${wt_dir}/local-config"
  printf 'FORA_DA_ARVORE_SEGREDO=1\n' > "${CASE_DIR}/externo/.env"
  ln -s "${CASE_DIR}/externo" "${wt_dir}/local-config/link-fora"

  run_script_in "$CASE_REPO" close --slug sym-ign
  assert_rc 0 "symlink de diretório para fora com nome não sensível não deve ser seguido"

  ln -s "${CASE_DIR}/externo/.env" "${wt_dir}/.env"
  run_script_in "$CASE_REPO" close --slug sym-ign
  assert_rc 3 "symlink ignorado com nome sensível (.env) deve bloquear sem ler conteúdo"
  assert_contains "$LAST_ERR" ".env"
  assert_not_contains "$LAST_ERR" "FORA_DA_ARVORE_SEGREDO"
}

# ==============================================================================
# Execução da suíte
# ==============================================================================

printf '=== Executando suíte descartável de scripts/worktree-task.sh ===\n'
printf 'Diretório temporário isolado: %s\n\n' "$TEST_ROOT"

run_test "ENV: validação numérica de versão mínima do Git (2.9.5, 2.35.9 rejeitados; 2.36.0 aceito)" test_env_rejects_old_git_versions_numerically

run_test "CREATE: criação válida, caminho centralizado, branch, SHA-base e Git comum" test_create_valid_and_shared_git
run_test "CREATE: main em caminho com espaços e acentos gera worktree em destino ASCII" test_create_from_main_with_spaces_and_accents_keeps_ascii_dest
run_test "CREATE: recusa execução na main em DETACHED HEAD e a partir de outra branch" test_create_rejects_main_detached_and_other_branch
run_test "CREATE: recusa --base apontando para objetos não-commit (blob e tree)" test_create_rejects_non_commit_objects_blob_and_tree
run_test "CREATE: rejeição de slugs inválidos (traversal, barras, espaços, acentos)" test_create_rejects_invalid_slugs
run_test "CREATE: rejeição de SHA inexistente e ref simbólica em --base" test_create_rejects_nonexistent_sha_and_symbolic_base
run_test "CREATE: rejeição de SHA fora da ancestralidade da main" test_create_rejects_sha_outside_main_ancestry
run_test "CREATE: rejeição de branch existente, diretório ocupado, symlink no destino e symlink ancestral" test_create_rejects_existing_branch_dest_symlink_and_ancestor_symlink
run_test "CREATE: regras de main suja (--allow-dirty-main), recusa de staged e recusa de untracked" test_create_dirty_main_staged_and_untracked_rules
run_test "CREATE: recusa de opções duplicadas e execução a partir de worktree secundária" test_create_rejects_duplicate_flags_and_from_secondary_wt
run_test "CREATE: falha simulada de mkdir retorna código contratual 4" test_create_simulated_mkdir_failure_returns_code_4
run_test "CREATE: falha simulada de git worktree add retorna 4, preserva resíduo e nova tentativa é fail-closed" test_create_simulated_git_worktree_add_failure_and_fail_closed_retry

run_test "STATUS: estados gerais, locked, detached, nomes adversariais MM sem array associativo e READ-ONLY" test_status_reports_all_states_and_adversarial_filenames_readonly
run_test "STATUS: resiliência com prunable/ausente, .git inválido e erro de permissão sem abortar demais worktrees" test_status_resilience_prunable_missing_invalid_dotgit_and_unreadable_dir

run_test "CLOSE: simulação padrão (--dry-run) aprova sem remover" test_close_dry_run_default_does_not_remove
run_test "CLOSE: bloqueios por rastreadas, staged, commit exclusivo, cherry-pick e untracked" test_close_blocks_dirty_staged_untracked_exclusive_and_cherry_pick
run_test "CLOSE: bloqueio individual de todos os padrões sensíveis (incl. local.properties, .envrc, secrets.env, crt, cer, der)" test_close_blocks_each_sensitive_ignored_pattern_individually
run_test "CLOSE: bloqueios dentro da própria worktree, locked, detached HEAD, clone independente e .git corrompido" test_close_blocks_self_dir_locked_detached_and_independent_clone
run_test "CLOSE: bloqueio de worktree principal, slug inválido, --path arbitrário e flags conflitantes" test_close_blocks_main_worktree_invalid_slug_and_invalid_options
run_test "CLOSE: bloqueio sem TTY, confirmação divergente e EOF durante confirmação" test_close_blocks_without_tty_wrong_confirmation_and_eof
run_test "CLOSE: execução em PTY com repo acentuado, preservação da branch, isolamento e idempotência em --dry-run e --execute" test_close_execute_success_idempotent_both_modes_and_accented_main_path
run_test "CLOSE: falha simulada de git worktree remove retorna código contratual 4 preservando branch e diagnóstico" test_close_simulated_git_worktree_remove_failure_returns_code_4

run_test "ADVERSARIAL: branch divergente, caminho inesperado, diretório órfão e prunable" test_adversarial_branch_divergent_unexpected_path_orphan_and_prunable
run_test "ADVERSARIAL: main movimentada pós-dry-run e alterações (untracked, HEAD da worktree e main) durante prompt" test_adversarial_main_moved_after_dry_run_and_toctou_during_prompt
run_test "ADVERSARIAL: symlinks ancestrais, no destino e em diretórios ignorados" test_adversarial_symlinks_in_close_and_ignored_dirs

printf '\n=== Resumo da Suíte ===\n'
printf 'Total executados : %d\n' "$TESTS_RUN"
printf 'Aprovados        : %d\n' "$TESTS_PASSED"
printf 'Falhas           : %d\n' "$TESTS_FAILED"

if (( TESTS_FAILED > 0 )); then
  exit 1
fi
exit 0
