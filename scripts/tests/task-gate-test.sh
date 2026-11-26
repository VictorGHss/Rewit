#!/usr/bin/env bash
# ==============================================================================
# Rewit — Suíte Adversarial Descartável para scripts/task-gate.sh
#
# Executa exclusivamente contra repositórios Git temporários criados em /tmp,
# nunca modificando o repositório real do Rewit nem o $HOME real do usuário.
# Valida todos os subcomandos (preflight, check, check-commit-msg, pre-merge),
# rejeição de variáveis GIT_* perigosas, snapshots READ-ONLY (incluindo índice,
# st_mode, symbolic-ref, metadados do git-dir e snapshot do hospedeiro) e
# redação total de segredos sintéticos.
# ==============================================================================

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
GATE_SCRIPT="${REPO_ROOT}/scripts/task-gate.sh"
REAL_GIT_BIN="$(command -v git)"

if [[ ! -x "$GATE_SCRIPT" ]]; then
  printf 'ERRO: script alvo ausente ou sem permissão executável: %s\n' "$GATE_SCRIPT" >&2
  exit 1
fi

# Diretório temporário raiz controlado com limpeza garantida via trap
TEST_ROOT="$(mktemp -d /tmp/rewit-task-gate-tests.XXXXXX)"
cleanup() {
  if [[ -n "${TEST_ROOT:-}" && "$TEST_ROOT" == /tmp/rewit-task-gate-tests.* && -d "$TEST_ROOT" ]]; then
    chmod -R u+rwx -- "$TEST_ROOT" 2>/dev/null || true
    rm -rf -- "$TEST_ROOT"
  fi
}
trap cleanup EXIT INT TERM

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
CASE_SEQ=0

LAST_RC=0
LAST_OUT=""
LAST_ERR=""

# ==============================================================================
# Helpers de ambiente descartável, snapshot READ-ONLY expandido (F3-R1) e asserções
# ==============================================================================

setup_case_env() {
  CASE_SEQ=$((CASE_SEQ + 1))
  CASE_DIR="${TEST_ROOT}/case-${CASE_SEQ}"
  CASE_HOME="${CASE_DIR}/home"
  CASE_REPO="${CASE_DIR}/repo"
  CASE_WT_BASE="${CASE_HOME}/source/rewit-worktrees"

  mkdir -p -- "$CASE_HOME" "$CASE_REPO" "$CASE_WT_BASE"
  init_disposable_repo "$CASE_REPO"
}

init_disposable_repo() {
  local repo_dir="$1"
  mkdir -p -- "$repo_dir"

  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" init -b main >/dev/null 2>&1
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" config user.name "Rewit Gate Test"
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" config user.email "rewit-gate-test@example.invalid"

  cat > "${repo_dir}/.gitignore" <<'EOF'
.env
.env.*
!.env.example
*.pem
*.key
*.keystore
build/
EOF

  mkdir -p -- "${repo_dir}/backend/src/main/resources/db/migration"
  mkdir -p -- "${repo_dir}/backend/src/main/java/com/rewit"
  mkdir -p -- "${repo_dir}/docs"

  printf 'CREATE TABLE places (id BIGINT PRIMARY KEY);\n' \
    > "${repo_dir}/backend/src/main/resources/db/migration/V1__init.sql"
  printf 'CREATE TABLE reviews (id BIGINT PRIMARY KEY);\n' \
    > "${repo_dir}/backend/src/main/resources/db/migration/V2__reviews.sql"

  # Ocorrência preexistente da anotação de supressão (não deve ser bloqueada quando intocada)
  local sw_tag="@""SuppressWarnings"
  cat > "${repo_dir}/backend/src/main/java/com/rewit/LegacyService.java" <<EOF
package com.rewit;

public class LegacyService {
    ${sw_tag}("unchecked")
    public void legacy() {}
}
EOF

  cat > "${repo_dir}/docs/guia.md" <<'EOF'
# Guia Inicial

## Seção Um

Texto base.

## Seção Dois

Outro texto.
EOF

  printf 'config=padrao\n' > "${repo_dir}/config.txt"
  printf 'config2=padrao\n' > "${repo_dir}/config2.txt"
  printf 'config3=padrao\n' > "${repo_dir}/config3.txt"
  printf 'APP_ENV=local\n' > "${repo_dir}/.env.example"

  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" add .
  LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" commit -m "chore: commit base descartável" >/dev/null 2>&1
  BASE_SHA="$(LC_ALL=C "$REAL_GIT_BIN" -C "$repo_dir" rev-parse HEAD)"
}

create_task_wt() {
  local slug="$1"
  local base="${2:-$BASE_SHA}"
  local wt_dir="${CASE_WT_BASE}/${slug}"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" worktree add -b "agent/neon/${slug}" "$wt_dir" "$base" >/dev/null 2>&1
  printf '%s' "$wt_dir"
}

# Snapshot completo READ-ONLY (F3-R1): inclui hash de índice real via --git-path index,
# st_mode, symbolic-ref HEAD, HEAD, refs, config local, worktrees, status, diffs
# determinísticos, metadados do git-dir real e árvore de arquivos/symlinks.
capture_repo_snapshot() {
  local main_repo="$1"
  local extra_wt="${2:-}"
  python3 - "$REAL_GIT_BIN" "$main_repo" "$extra_wt" <<'PYEOF'
import hashlib, os, subprocess, sys

git_bin, main_repo, extra_wt = sys.argv[1], sys.argv[2], sys.argv[3]

def run_git(repo, *args):
    env = os.environ.copy()
    for k in list(env.keys()):
        if k.startswith("GIT_"):
            del env[k]
    env["LC_ALL"] = "C"
    env["GIT_OPTIONAL_LOCKS"] = "0"
    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
    env["GIT_CONFIG_SYSTEM"] = "/dev/null"
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    p = subprocess.run(
        [git_bin, "-c", "core.quotePath=false", "-c", "color.ui=never", "-c", "diff.external=", "-C", repo, *args],
        capture_output=True,
        env=env,
    )
    return p.returncode, p.stdout

def file_digest_with_mode(path):
    if not os.path.lexists(path):
        return "ABSENT"
    st = os.lstat(path)
    if os.path.islink(path):
        return f"L:{oct(st.st_mode)}:{os.readlink(path)}"
    if os.path.isfile(path):
        try:
            with open(path, "rb") as f:
                return f"F:{oct(st.st_mode)}:{hashlib.sha256(f.read()).hexdigest()}"
        except OSError:
            return f"U:{oct(st.st_mode)}"
    return f"O:{oct(st.st_mode)}"

def git_dir_metadata_digest(repo):
    rc, out = run_git(repo, "rev-parse", "--path-format=absolute", "--git-dir")
    if rc != 0:
        return f"ERR:{rc}"
    git_dir = out.decode("utf-8", "surrogateescape").strip()
    rc_idx, idx_out = run_git(repo, "rev-parse", "--path-format=absolute", "--git-path", "index")
    idx_path = idx_out.decode("utf-8", "surrogateescape").strip() if rc_idx == 0 else os.path.join(git_dir, "index")

    h = hashlib.sha256()
    h.update(f"index:{file_digest_with_mode(idx_path)}\n".encode("utf-8", "surrogateescape"))
    for name in ("HEAD", "config", "packed-refs", "gitdir", "commondir"):
        p = os.path.join(git_dir, name)
        h.update(f"{name}:{file_digest_with_mode(p)}\n".encode("utf-8", "surrogateescape"))
    # Inventário de nomes + st_mode no nível raiz do git-dir (detecta locks/arquivos residuais)
    try:
        entries = sorted(os.listdir(git_dir))
        for e in entries:
            ep = os.path.join(git_dir, e)
            st = os.lstat(ep)
            h.update(f"dir:{e}:{oct(st.st_mode)}\n".encode("utf-8", "surrogateescape"))
    except OSError:
        h.update(b"dir:ERR\n")
    return h.hexdigest()

def tree_digest(root):
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d != ".git")
        rel_dir = os.path.relpath(dirpath, root)
        st_d = os.lstat(dirpath)
        h.update(f"D:{rel_dir}:{oct(st_d.st_mode)}\0".encode("utf-8", "surrogateescape"))
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root)
            h.update(f"{rel}:{file_digest_with_mode(full)}\0".encode("utf-8", "surrogateescape"))
    return h.hexdigest()

def snap_one(prefix, repo, include_shared):
    res = []
    cmds = [
        ("head", ("rev-parse", "HEAD")),
        ("symref", ("symbolic-ref", "--quiet", "HEAD")),
        ("status", ("status", "--porcelain=v1", "--untracked-files=all")),
        ("cached", ("diff", "--no-ext-diff", "--no-color", "--cached")),
        ("diff", ("diff", "--no-ext-diff", "--no-color")),
    ]
    if include_shared:
        cmds.extend([
            ("refs", ("show-ref",)),
            ("config", ("config", "--list", "--local")),
            ("worktrees", ("worktree", "list", "--porcelain")),
        ])
    for label, args in cmds:
        rc, out = run_git(repo, *args)
        res.append(f"{prefix}_{label}:{rc}:{hashlib.sha256(out).hexdigest()}")
    res.append(f"{prefix}_gitmeta:{git_dir_metadata_digest(repo)}")
    res.append(f"{prefix}_tree:{tree_digest(repo)}")
    return res

parts = snap_one("main", main_repo, True)
if extra_wt and os.path.isdir(extra_wt):
    parts.extend(snap_one("wt", extra_wt, False))

print("\n".join(parts))
PYEOF
}

# Snapshot do repositório hospedeiro real (sem ler conteúdo das modificações protegidas!)
capture_host_snapshot() {
  python3 - "$REAL_GIT_BIN" "$REPO_ROOT" <<'PYEOF'
import hashlib, os, subprocess, sys

git_bin, host_wt = sys.argv[1], sys.argv[2]

def run_git(repo, *args):
    env = os.environ.copy()
    for k in list(env.keys()):
        if k.startswith("GIT_"):
            del env[k]
    env["LC_ALL"] = "C"
    env["GIT_OPTIONAL_LOCKS"] = "0"
    p = subprocess.run([git_bin, "-C", repo, *args], capture_output=True, env=env)
    return p.returncode, p.stdout

def hash_file(path):
    if not os.path.isfile(path):
        return "ABSENT"
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()

rc, wt_out = run_git(host_wt, "worktree", "list", "--porcelain", "-z")
wt_tokens = wt_out.split(b"\0")
host_main = ""
for t in wt_tokens:
    if t.startswith(b"worktree "):
        host_main = t[len(b"worktree "):].decode("utf-8", "surrogateescape")
        break

parts = []
for name, repo in (("host_wt", host_wt), ("host_main", host_main)):
    for label, args in [
        ("head", ("rev-parse", "HEAD")),
        ("symref", ("symbolic-ref", "--quiet", "HEAD")),
        ("refs", ("show-ref",)),
        ("config", ("config", "--list", "--local")),
        ("status", ("status", "--porcelain=v1", "--untracked-files=all")),
        ("diff_hash", ("diff", "--no-ext-diff", "--no-color")),
    ]:
        rc, out = run_git(repo, *args)
        parts.append(f"{name}_{label}:{rc}:{hashlib.sha256(out).hexdigest()}")
    rc_idx, idx_out = run_git(repo, "rev-parse", "--path-format=absolute", "--git-path", "index")
    idx_path = idx_out.decode("utf-8", "surrogateescape").strip() if rc_idx == 0 else ""
    parts.append(f"{name}_index:{hash_file(idx_path)}")

parts.append(f"host_worktrees:{hashlib.sha256(wt_out).hexdigest()}")
print("\n".join(parts))
PYEOF
}

# Executa o gate com ambiente opcional extra, verificando snapshot READ-ONLY antes/depois
run_gate_full() {
  local workdir="$1"
  local main_for_snap="$2"
  local wt_for_snap="$3"
  local custom_path="$4"
  local extra_env_spec="$5"
  shift 5

  local snap_before snap_after
  snap_before="$(capture_repo_snapshot "$main_for_snap" "$wt_for_snap")"

  local out_file="${CASE_DIR}/last.out"
  local err_file="${CASE_DIR}/last.err"
  local env_cmd=(env "HOME=${CASE_HOME}" "PATH=${custom_path}")
  if [[ -n "$extra_env_spec" ]]; then
    local kv
    while IFS= read -r kv; do
      [[ -n "$kv" ]] && env_cmd+=("$kv")
    done <<< "$extra_env_spec"
  fi

  LAST_RC=0
  (
    cd -- "$workdir"
    "${env_cmd[@]}" "$GATE_SCRIPT" "$@"
  ) >"$out_file" 2>"$err_file" </dev/null || LAST_RC=$?
  LAST_OUT="$(cat -- "$out_file")"
  LAST_ERR="$(cat -- "$err_file")"

  snap_after="$(capture_repo_snapshot "$main_for_snap" "$wt_for_snap")"
  if [[ "$snap_before" != "$snap_after" ]]; then
    printf '  [FALHA READ-ONLY] Estado do repositório mudou após executar task-gate.sh %s\n' "$*" >&2
    diff -u <(printf '%s\n' "$snap_before") <(printf '%s\n' "$snap_after") >&2 || true
    exit 1
  fi
}

run_gate_in() {
  local workdir="$1"
  shift
  local wt_snap=""
  if [[ "$workdir" != "$CASE_REPO" ]]; then
    wt_snap="$workdir"
  fi
  run_gate_full "$workdir" "$CASE_REPO" "$wt_snap" "$PATH" "" "$@"
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

create_fake_bin_path() {
  local bin_name="$1"
  local body="$2"
  local wrapper_dir="${CASE_DIR}/fake-bin-${bin_name}-${RANDOM}"
  mkdir -p -- "$wrapper_dir"
  cat > "${wrapper_dir}/${bin_name}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
REAL_GIT="${REAL_GIT_BIN}"
${body}
EOF
  chmod +x "${wrapper_dir}/${bin_name}"
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
# S1. SEGURANÇA DE AMBIENTE — VARIÁVEIS GIT_* E TRACING (F3-S1)
# ==============================================================================

test_s1_blocks_git_env_redirection_config_injection_and_tracing() {
  # Cria um repositório limpo alternativo e um repo real com falha óbvia (credencial)
  local clean_repo="${CASE_DIR}/repo-limpo-falso"
  init_disposable_repo "$clean_repo"

  local wt_dir
  wt_dir="$(create_task_wt "s1-env")"
  local fake_aws="AKIA""IOSFODNN7EXAMPLE"
  printf 'aws = "%s"\n' "$fake_aws" > "${wt_dir}/vazamento.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add vazamento.txt

  local trace_file="${CASE_DIR}/trace-nao-deve-ser-criado.log"
  local trace2_file="${CASE_DIR}/trace2-nao-deve-ser-criado.log"
  local env_secret_val="VALOR_SENSIVEL_ENV_VAR_999"

  # 1) GIT_DIR apontando para repo limpo alternativo → BLOQUEADO (3) sem bypass e sem imprimir valor
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_DIR=${clean_repo}/.git" check
  assert_rc 3 "GIT_DIR deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_DIR"
  assert_not_contains "$LAST_OUT$LAST_ERR" "${clean_repo}/.git"

  # 2) GIT_WORK_TREE → BLOQUEADO (3)
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_WORK_TREE=${clean_repo}" check
  assert_rc 3 "GIT_WORK_TREE deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_WORK_TREE"

  # 3) GIT_INDEX_FILE → BLOQUEADO (3) (não permite ignorar staged)
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_INDEX_FILE=${clean_repo}/.git/index" check
  assert_rc 3 "GIT_INDEX_FILE deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_INDEX_FILE"

  # 4) GIT_COMMON_DIR → BLOQUEADO (3)
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_COMMON_DIR=${clean_repo}/.git" preflight --slug s1-env --expected-base "$BASE_SHA"
  assert_rc 3 "GIT_COMMON_DIR deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_COMMON_DIR"

  # 5) GIT_OBJECT_DIRECTORY e GIT_ALTERNATE_OBJECT_DIRECTORIES → BLOQUEADO (3)
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_OBJECT_DIRECTORY=${clean_repo}/.git/objects" check
  assert_rc 3 "GIT_OBJECT_DIRECTORY deve ser bloqueada (3)"
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_ALTERNATE_OBJECT_DIRECTORIES=${clean_repo}/.git/objects" check
  assert_rc 3 "GIT_ALTERNATE_OBJECT_DIRECTORIES deve ser bloqueada (3)"

  # 6) GIT_CONFIG_PARAMETERS e GIT_CONFIG_COUNT → BLOQUEADO (3) sem vazar valor
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_CONFIG_PARAMETERS='diff.external=${env_secret_val}'" check
  assert_rc 3 "GIT_CONFIG_PARAMETERS deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_CONFIG_PARAMETERS"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$env_secret_val"

  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" $'GIT_CONFIG_COUNT=1\nGIT_CONFIG_KEY_0=color.diff\nGIT_CONFIG_VALUE_0=always' check
  assert_rc 3 "GIT_CONFIG_COUNT deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_CONFIG_COUNT"

  # 7) GIT_CONFIG_GLOBAL → BLOQUEADO (3)
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" "GIT_CONFIG_GLOBAL=${CASE_DIR}/malicious.gitconfig" pre-merge --slug s1-env
  assert_rc 3 "GIT_CONFIG_GLOBAL deve ser bloqueada (3)"
  assert_contains "$LAST_ERR" "GIT_CONFIG_GLOBAL"

  # 8) Variáveis de tracing (inclusive em --help!) → BLOQUEADO (3) e nenhum arquivo criado
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$PATH" $'GIT_TRACE='"${trace_file}"$'\nGIT_TRACE2_EVENT='"${trace2_file}" --help
  assert_rc 3 "GIT_TRACE / GIT_TRACE2_EVENT devem ser bloqueadas inclusive em --help (3)"
  [[ ! -e "$trace_file" && ! -e "$trace2_file" ]] || fail_assert "Variáveis de tracing não podem gerar arquivos!"
}

# ==============================================================================
# A. PREFLIGHT (INCLUINDO IDENTIDADE PROTEGIDA, SYMLINK, PRUNABLE, DETACHED E ERROS)
# ==============================================================================

test_preflight_valid_protected_set_identity_and_hash_determinism() {
  local wt_dir
  wt_dir="$(create_task_wt "tarefa-pf")"

  # Modifica exatamente 3 arquivos protegidos na main (unstaged) sem nunca expor conteúdo
  local m1="PROT_SECRETO_1_XYZ" m2="PROT_SECRETO_2_XYZ" m3="PROT_SECRETO_3_XYZ"
  printf '%s\n' "$m1" >> "${CASE_REPO}/config.txt"
  printf '%s\n' "$m2" >> "${CASE_REPO}/config2.txt"
  printf '%s\n' "$m3" >> "${CASE_REPO}/config3.txt"

  local expected_hash
  expected_hash="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" diff --no-ext-diff --no-color | sha256sum | awk '{print $1}')"

  # Configura diff.external e color.diff=always no repo local para comprovar determinismo (F3-F2)
  local ext_diff="${CASE_DIR}/fake-ext-diff.sh"
  printf '#!/usr/bin/env bash\necho "EXTERNAL_DIFF_EXECUTADO"\n' > "$ext_diff"
  chmod +x "$ext_diff"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config diff.external "$ext_diff"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config color.diff always

  # 1) Conjunto exato dos 3 arquivos + hash correto → APROVADO (0) mesmo com diff.external e color.diff=always!
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" \
    --protected-hash "$expected_hash" \
    --protected-file config.txt \
    --protected-file config2.txt \
    --protected-file config3.txt
  assert_rc 0 "preflight com conjunto exato dos 3 arquivos e hash determinístico deve aprovar (0)"
  assert_contains "$LAST_OUT" "[APROVADO]"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$m1"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$m2"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$m3"

  # 2) Hash válido SEM --protected-file quando main está suja → BLOQUEADO (3)
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" \
    --protected-hash "$expected_hash"
  assert_rc 3 "preflight em main suja sem --protected-file deve bloquear (3)"

  # 3) Um caminho esperado ausente / quantidade diferente (apenas 2 declarados) → BLOQUEADO (3)
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" \
    --protected-hash "$expected_hash" \
    --protected-file config.txt \
    --protected-file config2.txt
  assert_rc 3 "preflight com quantidade divergente de --protected-file deve bloquear (3)"

  # 4) Caminho esperado não existente na lista (identidade trocada, mesma quantidade 3) → BLOQUEADO (3)
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" \
    --protected-hash "$expected_hash" \
    --protected-file config.txt \
    --protected-file config2.txt \
    --protected-file .env.example
  assert_rc 3 "preflight com identidade de arquivo divergente deve bloquear (3)"

  # 5) Hash divergente com conjunto certo → BLOQUEADO (3)
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" \
    --protected-hash "0000000000000000000000000000000000000000000000000000000000000000" \
    --protected-file config.txt \
    --protected-file config2.txt \
    --protected-file config3.txt
  assert_rc 3 "preflight com hash divergente deve bloquear (3)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$m1"

  # 6) --protected-file sem --protected-hash → erro de uso (2)
  run_gate_in "$wt_dir" preflight --slug tarefa-pf --expected-base "$BASE_SHA" --protected-file config.txt
  assert_rc 2 "--protected-file sem --protected-hash deve retornar erro de uso (2)"
}

test_preflight_rejects_wrong_branch_head_path_dirty_staged_untracked_detached_symlink_git_err() {
  local wt_dir
  wt_dir="$(create_task_wt "tarefa-pf-err")"

  # Caminho incorreto (main)
  run_gate_in "$CASE_REPO" preflight --slug tarefa-pf-err --expected-base "$BASE_SHA"
  assert_rc 3 "preflight fora da worktree esperada deve bloquear"

  # Untracked, unstaged, staged, HEAD divergente, branch errada
  printf 'solto\n' > "${wt_dir}/untracked.txt"
  run_gate_in "$wt_dir" preflight --slug tarefa-pf-err --expected-base "$BASE_SHA"
  assert_rc 3 "preflight com untracked deve bloquear"
  rm -f -- "${wt_dir}/untracked.txt"

  printf 'sujo\n' >> "${wt_dir}/config.txt"
  run_gate_in "$wt_dir" preflight --slug tarefa-pf-err --expected-base "$BASE_SHA"
  assert_rc 3 "preflight com unstaged deve bloquear"

  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add config.txt
  run_gate_in "$wt_dir" preflight --slug tarefa-pf-err --expected-base "$BASE_SHA"
  assert_rc 3 "preflight com staged deve bloquear"

  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "chore: avanca head" >/dev/null 2>&1
  run_gate_in "$wt_dir" preflight --slug tarefa-pf-err --expected-base "$BASE_SHA"
  assert_rc 3 "preflight com HEAD divergente deve bloquear"

  local new_head
  new_head="$(LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rev-parse HEAD)"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" switch -c "agent/neon/outra-branch" >/dev/null 2>&1
  run_gate_in "$wt_dir" preflight --slug tarefa-pf-err --expected-base "$new_head"
  assert_rc 3 "preflight com branch incorreta deve bloquear"

  # Worktree principal em DETACHED HEAD → BLOQUEADO (3)
  local wt_ok
  wt_ok="$(create_task_wt "pf-main-det")"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" switch --detach "$BASE_SHA" >/dev/null 2>&1
  run_gate_in "$wt_ok" preflight --slug pf-main-det --expected-base "$BASE_SHA"
  assert_rc 3 "preflight com main principal em DETACHED HEAD deve bloquear (3)"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" switch main >/dev/null 2>&1

  # Caminho symlink no destino esperado → BLOQUEADO (3)
  ln -s "$wt_ok" "${CASE_WT_BASE}/pf-symlink"
  run_gate_in "$wt_ok" preflight --slug pf-symlink --expected-base "$BASE_SHA"
  assert_rc 3 "preflight para slug cujo caminho é symlink deve bloquear (3)"
  rm -f -- "${CASE_WT_BASE}/pf-symlink"

  # Worktree prunable / removida manualmente → BLOQUEADO (3)
  local wt_prun
  wt_prun="$(create_task_wt "pf-prunable")"
  rm -rf -- "$wt_prun"
  run_gate_in "$CASE_REPO" preflight --slug pf-prunable --expected-base "$BASE_SHA"
  assert_rc 3 "preflight para worktree prunable deve bloquear (3)"

  # Erro simulado de comando Git em preflight → código contratual 4
  local fail_git_path
  fail_git_path="$(create_fake_bin_path "git" '
if [[ "$*" == *"worktree list"* ]]; then
  echo "fatal: falha simulada em worktree list" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"
  run_gate_full "$wt_ok" "$CASE_REPO" "$wt_ok" "$fail_git_path" "" preflight --slug pf-main-det --expected-base "$BASE_SHA"
  assert_rc 4 "falha simulada de git worktree list em preflight deve retornar código 4"
}

test_preflight_rejects_clone_masquerading_as_worktree_and_bad_args() {
  local fake_wt="${CASE_WT_BASE}/clone-disfarcado"
  LC_ALL=C "$REAL_GIT_BIN" clone --quiet "$CASE_REPO" "$fake_wt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$fake_wt" switch -c "agent/neon/clone-disfarcado" >/dev/null 2>&1

  run_gate_full "$fake_wt" "$fake_wt" "" "$PATH" "" \
    preflight --slug clone-disfarcado --expected-base "$BASE_SHA"
  assert_rc 3 "clone independente com mesmo nome de pasta deve bloquear em preflight"

  run_gate_in "$CASE_REPO" preflight --slug nao-existe --expected-base "$BASE_SHA"
  assert_rc 3 "preflight para worktree inexistente deve bloquear (3)"
  run_gate_in "$CASE_REPO" preflight --slug "../traversal" --expected-base "$BASE_SHA"
  assert_rc 2 "slug inválido deve retornar erro de uso (2)"
  run_gate_in "$CASE_REPO" preflight --slug ok-slug --expected-base "main"
  assert_rc 2 "--expected-base não hexadecimal deve retornar erro de uso (2)"
  run_gate_in "$CASE_REPO" preflight --slug ok-slug --expected-base "$BASE_SHA" --protected-hash "curto"
  assert_rc 2 "--protected-hash malformado deve retornar erro de uso (2)"
}

# ==============================================================================
# B. CHECK (DIFF --CHECK COM `:`, UNICODE, ESPAÇOS, SENSÍVEIS, FLYWAY, MARKDOWN)
# ==============================================================================

test_check_covers_committed_staged_unstaged_untracked_and_base_flag() {
  local wt_dir
  wt_dir="$(create_task_wt "check-camadas")"

  mkdir -p -- "${wt_dir}/scripts"
  printf '#!/usr/bin/env bash\necho ok\n' > "${wt_dir}/scripts/util.sh"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add scripts/util.sh
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "chore(scripts): adicionar utilitário" >/dev/null 2>&1

  run_gate_in "$wt_dir" check
  assert_rc 0 "alteração commitada válida deve aprovar (0)"
  assert_contains "$LAST_OUT" "scripts"

  printf 'linha limpa\n' > "${wt_dir}/apps_note.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add apps_note.txt
  printf 'alteracao unstaged limpa\n' >> "${wt_dir}/config.txt"
  mkdir -p -- "${wt_dir}/apps/mobile" "${wt_dir}/apps/admin"
  printf '// mobile\n' > "${wt_dir}/apps/mobile/sample.dart"
  printf '// admin\n' > "${wt_dir}/apps/admin/sample.ts"

  run_gate_in "$wt_dir" check
  assert_rc 0 "combinação limpa das 4 camadas deve aprovar (0)"
  assert_contains "$LAST_OUT" "scripts"
  assert_contains "$LAST_OUT" "apps/mobile"
  assert_contains "$LAST_OUT" "apps/admin"

  run_gate_in "$wt_dir" check --base "$BASE_SHA"
  assert_rc 0 "check com --base SHA explícito deve aprovar (0)"
  run_gate_in "$wt_dir" check --base "refs/heads/nao-existe"
  assert_rc 3 "check com --base inexistente deve bloquear (3)"
  run_gate_in "$wt_dir" check --base main --base HEAD
  assert_rc 2 "check com --base duplicado deve retornar erro de uso (2)"
}

test_check_f3_f1_diff_check_colon_spaces_unicode_conflict_unparseable_and_git_fail() {
  local wt_dir
  wt_dir="$(create_task_wt "check-f1")"

  # 1) Arquivo chamado `a:b.txt` com trailing whitespace staged (bug exato F3-F1!) → BLOQUEADO (3)
  printf 'conteudo com espaco   \n' > "${wt_dir}/a:b.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add "a:b.txt"
  run_gate_in "$wt_dir" check
  assert_rc 3 "a:b.txt staged com trailing whitespace deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "a:b.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rm -f --quiet "a:b.txt"

  # 2) Arquivo com espaço e Unicode com trailing whitespace staged → BLOQUEADO (3)
  mkdir -p -- "${wt_dir}/pasta espaço"
  printf 'ação com espaço   \n' > "${wt_dir}/pasta espaço/relatório_ação.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add "pasta espaço/relatório_ação.txt"
  run_gate_in "$wt_dir" check
  assert_rc 3 "arquivo com espaço e Unicode com trailing whitespace deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "pasta espaço/relatório_ação.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rm -f --quiet "pasta espaço/relatório_ação.txt"

  # 3) Marcador de conflito em arquivo staged → BLOQUEADO (3)
  printf '<<<<<<< HEAD\nlinha a\n=======\nlinha b\n>>>>>>> branch\n' > "${wt_dir}/conflito.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add conflito.txt
  run_gate_in "$wt_dir" check
  assert_rc 3 "marcador de conflito deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "conflito.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rm -f --quiet conflito.txt

  # 4) Linha não parseável retornada por `git diff --check` (rc=2) → BLOQUEADO (3), nunca aprovado!
  local unparseable_git_path
  unparseable_git_path="$(create_fake_bin_path "git" '
if [[ "$*" == *"--check"* ]]; then
  echo "SAIDA_ESTRANHA_SEM_FORMATO_DE_LINHA"
  exit 2
fi
exec "$REAL_GIT" "$@"
')"
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$unparseable_git_path" "" check
  assert_rc 3 "saída não parseável de git diff --check (rc=2) deve bloquear genericamente (3)"
  assert_contains "$LAST_ERR" "não parseável"
  assert_not_contains "$LAST_OUT$LAST_ERR" "SAIDA_ESTRANHA_SEM_FORMATO_DE_LINHA"

  # 5) Falha inesperada de `git diff --check` (rc=128) → código contratual 4
  local error_git_path
  error_git_path="$(create_fake_bin_path "git" '
if [[ "$*" == *"--check"* ]]; then
  echo "fatal: falha simulada em diff --check" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"
  run_gate_full "$wt_dir" "$CASE_REPO" "$wt_dir" "$error_git_path" "" check
  assert_rc 4 "falha inesperada de git diff --check (rc=128) deve retornar código 4"
}

test_check_blocks_sensitive_files_and_redacts_credential_patterns() {
  local wt_dir
  wt_dir="$(create_task_wt "check-segredos")"

  # Arquivo sensível staged com trailing whitespace (não pode vazar conteúdo nem na mensagem de diff --check!)
  local env_secret="VALOR_SECRETO_DO_ENV_NAO_VAZAR_999"
  printf 'DB_PASSWORD=%s   \n' "$env_secret" > "${wt_dir}/.env"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add -f .env
  run_gate_in "$wt_dir" check
  assert_rc 3 "arquivo sensível staged (.env) deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" ".env"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$env_secret"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rm --cached --quiet .env
  rm -f -- "${wt_dir}/.env"

  # Arquivo sensível untracked
  local cred_secret="CHAVE_INTERNA_CREDENTIALS_JSON_888"
  printf '{"private_key":"%s"}\n' "$cred_secret" > "${wt_dir}/credentials.json"
  run_gate_in "$wt_dir" check
  assert_rc 3 "arquivo sensível untracked (credentials.json) deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "credentials.json"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$cred_secret"
  rm -f -- "${wt_dir}/credentials.json"

  # Padrões de credencial em arquivo comum untracked e commitado (sempre [REDIGIDO])
  local fake_aws="AKIA""IOSFODNN7EXAMPLE"
  local fake_gh="ghp_""1234567890abcdef1234567890abcdef1234"
  printf 'aws_key = "%s"\n' "$fake_aws" > "${wt_dir}/comum-untracked.txt"
  run_gate_in "$wt_dir" check
  assert_rc 3 "padrão de credencial em untracked deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[REDIGIDO]"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$fake_aws"
  rm -f -- "${wt_dir}/comum-untracked.txt"

  printf 'token = "%s"\n' "$fake_gh" > "${wt_dir}/comum-commitado.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add comum-commitado.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "chore: adiciona segredo sintético" >/dev/null 2>&1
  run_gate_in "$wt_dir" check
  assert_rc 3 "padrão de credencial commitado deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[REDIGIDO]"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$fake_gh"
}

test_check_flyway_migrations_immutable_and_new_allowed() {
  local wt_dir
  wt_dir="$(create_task_wt "check-flyway")"

  # Nova migration permitida
  printf 'CREATE TABLE badges (id BIGINT PRIMARY KEY);\n' \
    > "${wt_dir}/backend/src/main/resources/db/migration/V3__badges.sql"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add backend/src/main/resources/db/migration/V3__badges.sql
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "feat(db): adicionar migration V3" >/dev/null 2>&1
  run_gate_in "$wt_dir" check
  assert_rc 0 "migration Flyway nova deve ser permitida (0)"

  # Histórica modificada
  printf '%s\n' '-- alteracao indevida' >> "${wt_dir}/backend/src/main/resources/db/migration/V1__init.sql"
  run_gate_in "$wt_dir" check
  assert_rc 3 "migration histórica modificada deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "V1__init.sql"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" checkout -- backend/src/main/resources/db/migration/V1__init.sql

  # Histórica removida
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" rm --quiet backend/src/main/resources/db/migration/V2__reviews.sql
  run_gate_in "$wt_dir" check
  assert_rc 3 "migration histórica removida deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "V2__reviews.sql"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" reset --quiet HEAD -- backend/src/main/resources/db/migration/V2__reviews.sql
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" checkout -- backend/src/main/resources/db/migration/V2__reviews.sql

  # Histórica renomeada
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" mv \
    backend/src/main/resources/db/migration/V2__reviews.sql \
    backend/src/main/resources/db/migration/V20__reviews_renamed.sql
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "chore(db): renomeia migration historica" >/dev/null 2>&1
  run_gate_in "$wt_dir" check
  assert_rc 3 "migration histórica renomeada deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "V2__reviews.sql"
}

test_check_suppress_warnings_java_preexisting_and_markdown_exception() {
  local wt_dir
  wt_dir="$(create_task_wt "check-sw")"
  local sw_tag="@""SuppressWarnings"

  # 1) Preexistente em .java fora das linhas adicionadas → PERMITIDO (0)
  printf '\n// comentario limpo\n' >> "${wt_dir}/backend/src/main/java/com/rewit/LegacyService.java"
  run_gate_in "$wt_dir" check
  assert_rc 0 "ocorrência preexistente não deve bloquear (0)"

  # 2) Menção documental em Markdown (*.md) → PERMITIDA (0) conforme regra documentada
  printf '\nRegra: nunca usar `%s` no código.\n' "$sw_tag" >> "${wt_dir}/docs/guia.md"
  run_gate_in "$wt_dir" check
  assert_rc 0 "menção textual de SuppressWarnings em Markdown deve ser permitida (0)"

  # 3) Nova ocorrência em código (.java) → BLOQUEADA (3)
  printf '    %s("deprecation")\n    public void novo() {}\n' "$sw_tag" \
    >> "${wt_dir}/backend/src/main/java/com/rewit/LegacyService.java"
  run_gate_in "$wt_dir" check
  assert_rc 3 "nova ocorrência em arquivo Java deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "LegacyService.java"
}

test_check_f3_f3_markdown_parser_full_matrix_and_binary() {
  local wt_dir
  wt_dir="$(create_task_wt "check-md-f3")"

  mkdir -p -- "${wt_dir}/docs/pasta com espaco"
  printf '# Alvo\n\n## Detalhe Técnico\n' > "${wt_dir}/docs/pasta com espaco/alvo espaço.md"
  printf '# Doc Com Parênteses\n' > "${wt_dir}/docs/doc_(v1).md"
  printf '<svg></svg>\n' > "${wt_dir}/docs/badge.svg"

  # 1) Casos válidos: código inline simples e com múltiplos backticks, fences de comprimentos
  #    diferentes (4 backticks contendo 3 backticks dentro), badge com imagem aninhada,
  #    destino com parênteses balanceados, espaços via <...> e %20, links externos e âncoras válidas
  cat > "${wt_dir}/docs/completo-ok.md" <<'EOF'
# Completo

Inline simples `[falso](inexistente-1.md)` e múltiplos backticks ``[falso](inexistente-2.md)`` e ```[falso](inexistente-3.md)```.

````markdown
```text
[link-dentro-de-fence-4](inexistente-4.md)
```
[ainda-dentro-da-fence-4](inexistente-5.md)
````

~~~text
[link-em-fence-til](inexistente-6.md)
~~~

- [Guia válido](guia.md#seção-um)
- [Espaço angular](<pasta com espaco/alvo espaço.md#detalhe-técnico>)
- [Espaço percent](pasta%20com%20espaco/alvo%20espa%C3%A7o.md)
- [Parênteses balanceados](doc_(v1).md)
- [![Badge válido](badge.svg)](guia.md#seção-dois)
- [Externo](https://example.invalid/nao-consultar)
- [Âncora interna](#subseção-local)

## Subseção Local
EOF
  run_gate_in "$wt_dir" check
  assert_rc 0 "todos os casos suportados de Markdown devem aprovar (0)"

  # 2) Badge aninhado com imagem quebrada → BLOQUEADO (3)
  printf '\n[![Badge quebrado](nao-existe.svg)](guia.md)\n' >> "${wt_dir}/docs/completo-ok.md"
  run_gate_in "$wt_dir" check
  assert_rc 3 "badge aninhado com imagem quebrada deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "nao-existe.svg"

  # 3) Link quebrado → BLOQUEADO (3)
  printf '# X\n\n[Quebrado](nao-existe.md)\n' > "${wt_dir}/docs/completo-ok.md"
  run_gate_in "$wt_dir" check
  assert_rc 3 "link relativo quebrado deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "nao-existe.md"

  # 4) Âncora inválida → BLOQUEADO (3)
  printf '# X\n\n[Âncora ruim](guia.md#inexistente)\n' > "${wt_dir}/docs/completo-ok.md"
  run_gate_in "$wt_dir" check
  assert_rc 3 "âncora inválida deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "inexistente"

  # 5) Fence sem fechamento → NAO_VERIFICAVEL (3, fail-closed!)
  cat > "${wt_dir}/docs/completo-ok.md" <<'EOF'
# Fence Aberta

```bash
echo sem fechamento
EOF
  run_gate_in "$wt_dir" check
  assert_rc 3 "code fence sem fechamento deve reportar NAO_VERIFICAVEL (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[NAO_VERIFICAVEL]"
  assert_contains "$LAST_OUT$LAST_ERR" "sem fechamento"

  # 6) Sintaxe não suportada (link de referência) → NAO_VERIFICAVEL (3)
  printf '# Ref\n\n[texto][id]\n\n[id]: guia.md\n' > "${wt_dir}/docs/completo-ok.md"
  run_gate_in "$wt_dir" check
  assert_rc 3 "link por referência deve reportar NAO_VERIFICAVEL (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[NAO_VERIFICAVEL]"
  rm -f -- "${wt_dir}/docs/completo-ok.md" "${wt_dir}/docs/doc_(v1).md" "${wt_dir}/docs/badge.svg"
  rm -rf -- "${wt_dir}/docs/pasta com espaco"

  # 7) Arquivo binário → NAO_VERIFICAVEL (3)
  printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' > "${wt_dir}/imagem.bin"
  run_gate_in "$wt_dir" check
  assert_rc 3 "arquivo binário deve reportar NAO_VERIFICAVEL (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[NAO_VERIFICAVEL]"
  assert_contains "$LAST_OUT$LAST_ERR" "imagem.bin"
}

test_check_does_not_follow_symlinks_and_handles_adversarial_names() {
  local wt_dir
  wt_dir="$(create_task_wt "check-sym-adv")"

  local adv_name='nota [0] ação $(echo ok).txt'
  printf 'conteudo limpo\n' > "${wt_dir}/${adv_name}"
  run_gate_in "$wt_dir" check
  assert_rc 0 "nome adversarial limpo deve aprovar (0)"
  rm -f -- "${wt_dir}/${adv_name}"

  local ext_secret="SEGREDO_EXTERNO_SYMLINK_NAO_LER_777"
  printf 'secret = "%s"\n' "$ext_secret" > "${CASE_DIR}/externo.txt"
  ln -s "${CASE_DIR}/externo.txt" "${wt_dir}/atalho.txt"
  run_gate_in "$wt_dir" check
  assert_rc 3 "symlink untracked não deve ser seguido e deve reportar NAO_VERIFICAVEL (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "[NAO_VERIFICAVEL]"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$ext_secret"
}

# ==============================================================================
# C. CHECK-COMMIT-MSG (INCLUINDO F3-S2: 64 KiB EXATO/ACIMA, FIFO, DIR, SYMLINK)
# ==============================================================================

test_check_commit_msg_full_matrix_and_f3_s2_safe_fd() {
  local tipos=(feat fix docs style refactor perf test chore)
  local t
  for t in "${tipos[@]}"; do
    run_gate_in "$CASE_REPO" check-commit-msg --message "${t}: adicionar verificação em português"
    assert_rc 0 "tipo '${t}' sem escopo deve aprovar (0)"
    assert_contains "$LAST_OUT" "revisão humana"
    run_gate_in "$CASE_REPO" check-commit-msg --message "${t}(worktrees): validar fluxo operacional"
    assert_rc 0 "tipo '${t}' com escopo deve aprovar (0)"
  done

  # Breaking changes válidos e BREAKING CHANGE vazio (bloqueado)
  run_gate_in "$CASE_REPO" check-commit-msg --message "feat(api)!: alterar contrato de resposta"
  assert_rc 0 "breaking change com ! deve aprovar (0)"

  local msg_file="${CASE_DIR}/commit-msg.txt"
  printf 'refactor(gate): reorganizar saída\n\nBREAKING CHANGE: códigos padronizados\n' > "$msg_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$msg_file"
  assert_rc 0 "arquivo regular válido com BREAKING CHANGE deve aprovar (0)"

  local empty_bc_file="${CASE_DIR}/empty-bc.txt"
  printf 'feat(gate): teste\n\nBREAKING CHANGE:   \n' > "$empty_bc_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$empty_bc_file"
  assert_rc 3 "rodapé BREAKING CHANGE vazio deve bloquear (3)"

  # Limite exato de 64 KiB (65536 bytes) → APROVADO (0); 65537 bytes → BLOQUEADO (3)
  local exact_64k="${CASE_DIR}/exact-64k.txt"
  local over_64k="${CASE_DIR}/over-64k.txt"
  python3 - "$exact_64k" "$over_64k" <<'PYEOF'
import sys
header = b"docs(gate): mensagem exatamente no limite\n\n"
with open(sys.argv[1], "wb") as f:
    f.write(header + b"a" * (65536 - len(header)))
with open(sys.argv[2], "wb") as f:
    f.write(header + b"a" * (65537 - len(header)))
PYEOF
  run_gate_in "$CASE_REPO" check-commit-msg --file "$exact_64k"
  assert_rc 0 "arquivo exatamente no limite de 64 KiB (65536 bytes) deve aprovar (0)"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$over_64k"
  assert_rc 3 "arquivo maior que 64 KiB (65537 bytes) deve bloquear (3)"
  assert_contains "$LAST_ERR" "64 KiB"

  # Symlink, FIFO (named pipe — não deve travar!), diretório, binário e sensível → BLOQUEADO (3)
  ln -s "$msg_file" "${CASE_DIR}/link-msg.txt"
  run_gate_in "$CASE_REPO" check-commit-msg --file "${CASE_DIR}/link-msg.txt"
  assert_rc 3 "symlink em --file deve bloquear via O_NOFOLLOW (3)"

  local fifo_path="${CASE_DIR}/pipe-msg.fifo"
  mkfifo "$fifo_path"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$fifo_path"
  assert_rc 3 "FIFO em --file deve bloquear sem travar via O_NONBLOCK/S_ISREG (3)"

  mkdir -p -- "${CASE_DIR}/dir-msg"
  run_gate_in "$CASE_REPO" check-commit-msg --file "${CASE_DIR}/dir-msg"
  assert_rc 3 "diretório em --file deve bloquear (3)"

  printf '\x00\x01\x02binario' > "${CASE_DIR}/bin-msg.dat"
  run_gate_in "$CASE_REPO" check-commit-msg --file "${CASE_DIR}/bin-msg.dat"
  assert_rc 3 "arquivo binário em --file deve bloquear (3)"

  local sens_file="${CASE_DIR}/.env"
  local file_secret="SEGREDO_EM_ENV_COMMIT_MSG_666"
  printf 'TOKEN=%s\n' "$file_secret" > "$sens_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$sens_file"
  assert_rc 3 "arquivo sensível em --file deve bloquear sem leitura (3)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$file_secret"

  # Retrocompatibilidade de --file (COMMIT_EDITMSG ignora comentários '#')
  local editmsg_file="${CASE_DIR}/commit-editmsg.txt"
  printf '# Comentário de editor Git\n\nfeat(gate): preservar modo COMMIT_EDITMSG\n# Rodapé comentado\n' > "$editmsg_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$editmsg_file"
  assert_rc 0 "--file padrão deve preservar compatibilidade com comentários '#' de COMMIT_EDITMSG (0)"

  # Modo literal (--file --no-strip-comments): subject iniciado por '#' NÃO pode ser truncado
  local hash_subject_file="${CASE_DIR}/hash-subject.txt"
  printf '#123 ajuste sem formato\n\nfeat(gate): linha posterior que nao pode mascarar o subject\n' > "$hash_subject_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$hash_subject_file" --no-strip-comments
  assert_rc 3 "--no-strip-comments deve bloquear subject iniciado por '#' mesmo com linha posterior válida (3)"

  local leading_blank_file="${CASE_DIR}/leading-blank.txt"
  printf '\nfeat(gate): linha vazia inicial nao pode ser ignorada em modo literal\n' > "$leading_blank_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$leading_blank_file" --no-strip-comments
  assert_rc 3 "--no-strip-comments deve bloquear linha inicial vazia em mensagem literal (3)"

  local literal_multiline_file="${CASE_DIR}/literal-multiline.txt"
  printf 'feat(ci): validar mensagem multilinha literal\n\nCorpo explicativo.\n# Linha iniciada por cerquilha no corpo\n\nBREAKING CHANGE: contrato atualizado\n' > "$literal_multiline_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$literal_multiline_file" --no-strip-comments
  assert_rc 0 "--no-strip-comments deve aprovar mensagem multilinha válida com '#' no corpo e BREAKING CHANGE (0)"
  assert_contains "$LAST_OUT" "Breaking change : sim"

  local literal_empty_bc_file="${CASE_DIR}/literal-empty-bc.txt"
  printf 'fix(ci): validar rodape vazio\n\nBREAKING CHANGE:   \n' > "$literal_empty_bc_file"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$literal_empty_bc_file" --no-strip-comments
  assert_rc 3 "--no-strip-comments deve bloquear BREAKING CHANGE vazio (3)"

  run_gate_in "$CASE_REPO" check-commit-msg --message "feat: teste" --no-strip-comments
  assert_rc 2 "--no-strip-comments combinado com --message deve retornar erro de uso (2)"
  run_gate_in "$CASE_REPO" check-commit-msg --file "$literal_multiline_file" --no-strip-comments --no-strip-comments
  assert_rc 2 "--no-strip-comments duplicado deve retornar erro de uso (2)"

  # Formatos inválidos e argumentos inválidos
  run_gate_in "$CASE_REPO" check-commit-msg --message "feat: "
  assert_rc 3 "descrição ausente deve bloquear (3)"
  run_gate_in "$CASE_REPO" check-commit-msg --message "invalido: tipo desconhecido"
  assert_rc 3 "tipo desconhecido deve bloquear (3)"
  run_gate_in "$CASE_REPO" check-commit-msg --message "feat(): escopo vazio"
  assert_rc 3 "escopo vazio deve bloquear (3)"
  run_gate_in "$CASE_REPO" check-commit-msg --message "sem formato"
  assert_rc 3 "formato inválido deve bloquear (3)"
  run_gate_in "$CASE_REPO" check-commit-msg
  assert_rc 2 "ausência de --message e --file deve retornar erro de uso (2)"
  run_gate_in "$CASE_REPO" check-commit-msg --message "feat: a" --file "$msg_file"
  assert_rc 2 "combinação --message e --file deve retornar erro de uso (2)"
}

# ==============================================================================
# D. PRE-MERGE (IDENTIDADE PROTEGIDA, COLISÕES, RENAME, DIFF.EXTERNAL, ERRO GIT)
# ==============================================================================

test_pre_merge_ff_clean_and_no_candidates() {
  local wt_dir
  wt_dir="$(create_task_wt "pm-ok")"

  run_gate_in "$CASE_REPO" pre-merge --slug pm-ok
  assert_rc 3 "pre-merge sem commits candidatos deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "Nenhum commit candidato"

  printf 'novo recurso\n' > "${wt_dir}/recurso.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add recurso.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "feat(core): adicionar recurso" >/dev/null 2>&1

  run_gate_in "$CASE_REPO" pre-merge --slug pm-ok
  assert_rc 0 "pre-merge com fast-forward possível e main limpa deve aprovar (0)"
  assert_contains "$LAST_OUT" "[APROVADO]"
}

test_pre_merge_protected_set_collisions_rename_extdiff_divergence_and_git_error() {
  local wt_dir
  wt_dir="$(create_task_wt "pm-dirty")"
  printf 'novo arquivo tarefa\n' > "${wt_dir}/tarefa.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add tarefa.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "feat: entrega sem colisao" >/dev/null 2>&1

  # Modifica 2 arquivos protegidos na main e configura diff.external + color.diff=always
  local secret_main="ALTERACAO_PROTEGIDA_MAIN_PM_555"
  printf '%s\n' "$secret_main" >> "${CASE_REPO}/config.txt"
  printf '%s\n' "$secret_main" >> "${CASE_REPO}/config2.txt"
  local main_hash
  main_hash="$(LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" diff --no-ext-diff --no-color | sha256sum | awk '{print $1}')"

  local ext_diff="${CASE_DIR}/ext-diff-pm.sh"
  printf '#!/usr/bin/env bash\necho "NAO_DEVIA_RODAR"\n' > "$ext_diff"
  chmod +x "$ext_diff"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config diff.external "$ext_diff"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" config color.diff always

  # 1) main suja sem --protected-hash → BLOQUEADO (3)
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty
  assert_rc 3 "main suja sem --protected-hash deve bloquear (3)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$secret_main"

  # 2) main suja com hash mas sem --protected-file → BLOQUEADO (3)
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash"
  assert_rc 3 "main suja sem --protected-file deve bloquear (3)"

  # 3) Conjunto incompleto (só 1 arquivo declarado de 2) → BLOQUEADO (3)
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" --protected-file config.txt
  assert_rc 3 "conjunto protegido incompleto deve bloquear (3)"

  # 4) Conjunto com arquivo adicional não existente na main (3 declarados para 2 modificados) → BLOQUEADO (3)
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt --protected-file config3.txt
  assert_rc 3 "conjunto protegido com arquivo adicional esperado ausente deve bloquear (3)"

  # 5) Conjunto exato + hash correto (com diff.external e color.diff=always ativos!) → APROVADO (0)
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt
  assert_rc 0 "conjunto exato + hash determinístico sem colisão deve aprovar (0)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$secret_main"

  # 6) Colisão com arquivo untracked na main (mesmo nome `tarefa.txt`) → BLOQUEADO (3)
  printf 'colide untracked\n' > "${CASE_REPO}/tarefa.txt"
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt
  assert_rc 3 "colisão com arquivo untracked na main deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "Colisão"
  rm -f -- "${CASE_REPO}/tarefa.txt"

  # 7) Colisão com arquivo staged na main e rename na main → BLOQUEADO (3)
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" mv config3.txt config3-renamed.txt
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt
  assert_rc 3 "rename/staging na main deve bloquear pre-merge (3)"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" mv config3-renamed.txt config3.txt

  # 8) Colisão com arquivo staged na main coincidindo com tarefa.txt → BLOQUEADO (3)
  printf 'colide staged\n' > "${CASE_REPO}/tarefa.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add tarefa.txt
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt
  assert_rc 3 "colisão com arquivo staged na main deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "Colisão"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" rm -f --quiet tarefa.txt

  # 9) Colisão com arquivo unstaged protegido na main → BLOQUEADO (3)
  printf 'alteracao conflitante\n' >> "${wt_dir}/config.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add config.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "fix: toca config" >/dev/null 2>&1
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty --protected-hash "$main_hash" \
    --protected-file config.txt --protected-file config2.txt
  assert_rc 3 "colisão de caminho com alteração local da main deve bloquear (3)"
  assert_contains "$LAST_OUT$LAST_ERR" "config.txt"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$secret_main"

  # 10) Falha simulada de Git em pre-merge → código contratual 4
  local fail_pm_git
  fail_pm_git="$(create_fake_bin_path "git" '
if [[ "$*" == *"rev-list --count"* ]]; then
  echo "fatal: falha simulada em rev-list" >&2
  exit 128
fi
exec "$REAL_GIT" "$@"
')"
  run_gate_full "$CASE_REPO" "$CASE_REPO" "" "$fail_pm_git" "" pre-merge --slug pm-dirty \
    --protected-hash "$main_hash" --protected-file config.txt --protected-file config2.txt
  assert_rc 4 "falha simulada de git rev-list em pre-merge deve retornar código 4"

  # 11) Branch inexistente, divergência e main fora da branch main → BLOQUEADO (3)
  run_gate_in "$CASE_REPO" pre-merge --slug branch-inexistente
  assert_rc 3 "branch inexistente deve bloquear (3)"

  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" checkout -- config.txt config2.txt
  printf 'commit divergente na main\n' > "${CASE_REPO}/main-only.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" add main-only.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" commit -m "chore: avanca main" >/dev/null 2>&1
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty
  assert_rc 3 "divergência entre branches deve bloquear (3)"

  LC_ALL=C "$REAL_GIT_BIN" -C "$CASE_REPO" switch -c "nao-eh-main" >/dev/null 2>&1
  run_gate_in "$CASE_REPO" pre-merge --slug pm-dirty
  assert_rc 3 "worktree principal fora de main deve bloquear (3)"
}

# ==============================================================================
# E. AMBIENTE, VERSÃO DO GIT, DEPENDÊNCIAS E CAMINHOS ACENTUADOS
# ==============================================================================

test_env_rejects_old_git_and_missing_python() {
  local path_old_git
  path_old_git="$(create_fake_bin_path "git" '
if [[ "$*" == *"--version"* ]]; then
  echo "git version 2.35.0"
  exit 0
fi
exec "$REAL_GIT" "$@"
')"
  run_gate_full "$CASE_REPO" "$CASE_REPO" "" "$path_old_git" "" check-commit-msg --message "feat: teste"
  assert_rc 4 "Git < 2.36 deve retornar erro de ambiente (4)"
  assert_contains "$LAST_ERR" "2.36"

  local no_py_dir="${CASE_DIR}/bin-sem-python"
  mkdir -p -- "$no_py_dir"
  local b
  for b in bash env git realpath stat id sha256sum awk cat dirname basename; do
    if command -v "$b" >/dev/null 2>&1; then
      ln -s "$(command -v "$b")" "${no_py_dir}/${b}"
    fi
  done
  run_gate_full "$CASE_REPO" "$CASE_REPO" "" "$no_py_dir" "" check-commit-msg --message "feat: teste"
  assert_rc 4 "ausência de python3 no PATH deve retornar erro de ambiente (4)"
  assert_contains "$LAST_ERR" "python3"
}

test_accented_main_path_with_preflight_and_pre_merge() {
  local accented_repo="${CASE_DIR}/Área de trabalho/Rewit Ação"
  init_disposable_repo "$accented_repo"
  local wt_dir="${CASE_WT_BASE}/acento-gate"
  LC_ALL=C "$REAL_GIT_BIN" -C "$accented_repo" worktree add -b "agent/neon/acento-gate" "$wt_dir" "$BASE_SHA" >/dev/null 2>&1

  local acc_secret="CONTEUDO_PROTEGIDO_ACENTO_321"
  printf '%s\n' "$acc_secret" >> "${accented_repo}/config.txt"
  local main_hash
  main_hash="$(LC_ALL=C "$REAL_GIT_BIN" -C "$accented_repo" diff --no-ext-diff --no-color | sha256sum | awk '{print $1}')"

  run_gate_full "$wt_dir" "$accented_repo" "$wt_dir" "$PATH" "" \
    preflight --slug acento-gate --expected-base "$BASE_SHA" \
    --protected-hash "$main_hash" --protected-file config.txt
  assert_rc 0 "preflight com repo principal acentuado deve aprovar (0)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$acc_secret"

  printf 'entrega valida\n' > "${wt_dir}/entrega.txt"
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" add entrega.txt
  LC_ALL=C "$REAL_GIT_BIN" -C "$wt_dir" commit -m "feat: entrega em repo acentuado" >/dev/null 2>&1

  run_gate_full "$wt_dir" "$accented_repo" "$wt_dir" "$PATH" "" \
    pre-merge --slug acento-gate --protected-hash "$main_hash" --protected-file config.txt
  assert_rc 0 "pre-merge com repo principal acentuado deve aprovar (0)"
  assert_not_contains "$LAST_OUT$LAST_ERR" "$acc_secret"
}

# ==============================================================================
# Execução da suíte com verificação de snapshot do hospedeiro antes/depois
# ==============================================================================

printf '=== Executando suíte adversarial descartável de scripts/task-gate.sh ===\n'
printf 'Diretório temporário isolado: %s\n\n' "$TEST_ROOT"

HOST_SNAP_BEFORE="$(capture_host_snapshot)"

run_test "F3-S1: rejeita variáveis GIT_* de redirecionamento, config injection e tracing sem criar arquivos" test_s1_blocks_git_env_redirection_config_injection_and_tracing

run_test "PREFLIGHT: conjunto exato de 3 arquivos protegidos, identidade, hash determinístico (diff.external/color.diff)" test_preflight_valid_protected_set_identity_and_hash_determinism
run_test "PREFLIGHT: bloqueia caminho, untracked, unstaged, staged, HEAD, branch, main detached, symlink, prunable e erro Git (4)" test_preflight_rejects_wrong_branch_head_path_dirty_staged_untracked_detached_symlink_git_err
run_test "PREFLIGHT: bloqueia clone independente com mesmo nome, worktree inexistente e erros de uso" test_preflight_rejects_clone_masquerading_as_worktree_and_bad_args

run_test "CHECK: alterações commitadas, staged, unstaged, untracked, combinação simultânea e --base" test_check_covers_committed_staged_unstaged_untracked_and_base_flag
run_test "CHECK (F3-F1): a:b.txt com ':', espaço, Unicode, conflito, saída não parseável e falha Git (4)" test_check_f3_f1_diff_check_colon_spaces_unicode_conflict_unparseable_and_git_fail
run_test "CHECK: bloqueia arquivos sensíveis (staged/untracked) sem vazar conteúdo e redige credenciais" test_check_blocks_sensitive_files_and_redacts_credential_patterns
run_test "CHECK: imutabilidade de migrations Flyway (modificada, removida, renomeada vs nova)" test_check_flyway_migrations_immutable_and_new_allowed
run_test "CHECK: bloqueia nova anotação de supressão em código, preserva preexistente e permite menção em Markdown" test_check_suppress_warnings_java_preexisting_and_markdown_exception
run_test "CHECK (F3-F3): parser Markdown (múltiplos backticks, fences, badges, parênteses, fence aberta, ref) e binários" test_check_f3_f3_markdown_parser_full_matrix_and_binary
run_test "CHECK: não segue symlinks para conteúdo e suporta nomes adversariais" test_check_does_not_follow_symlinks_and_handles_adversarial_names

run_test "CHECK-COMMIT-MSG (F3-S2): tipos, breaking, limite exato/acima 64 KiB, symlink, FIFO, dir, binário, sensível e uso" test_check_commit_msg_full_matrix_and_f3_s2_safe_fd

run_test "PRE-MERGE: fast-forward limpo e bloqueio quando não há commits candidatos" test_pre_merge_ff_clean_and_no_candidates
run_test "PRE-MERGE (F3-F2): conjunto protegido, identidade, diff.external, colisões staged/untracked/unstaged, rename, divergência e erro Git (4)" test_pre_merge_protected_set_collisions_rename_extdiff_divergence_and_git_error

run_test "ENV: rejeita Git < 2.36 e ausência de python3 com código contratual 4" test_env_rejects_old_git_and_missing_python
run_test "PATHS: preflight e pre-merge com worktree principal contendo espaços e acentos" test_accented_main_path_with_preflight_and_pre_merge

HOST_SNAP_AFTER="$(capture_host_snapshot)"
if [[ "$HOST_SNAP_BEFORE" != "$HOST_SNAP_AFTER" ]]; then
  printf '[FAIL] SNAPSHOT HOSPEDEIRO: estado do repositório hospedeiro mudou durante a suíte!\n' >&2
  diff -u <(printf '%s\n' "$HOST_SNAP_BEFORE") <(printf '%s\n' "$HOST_SNAP_AFTER") >&2 || true
  TESTS_FAILED=$((TESTS_FAILED + 1))
else
  printf '[OK]   HOST READ-ONLY: snapshot completo do repositório hospedeiro inalterado (HEAD, refs, índice, config, status e hash protegido)\n'
fi

printf '\n=== Resumo da Suíte (task-gate) ===\n'
printf 'Total executados : %d\n' "$TESTS_RUN"
printf 'Aprovados        : %d\n' "$TESTS_PASSED"
printf 'Falhas           : %d\n' "$TESTS_FAILED"

if (( TESTS_FAILED > 0 )); then
  exit 1
fi
exit 0
