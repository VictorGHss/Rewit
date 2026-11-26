#!/usr/bin/env bash
# ==============================================================================
# Suíte adversarial descartável para scripts/test-infra.sh (Fase 4 R1)
#
# Executa 100% em diretórios temporários isolados em /tmp com mocks fiéis de
# docker, podman 5.8.9 e backend/mvnw (com árvore real de processos).
# Nunca aciona containers reais, nunca altera o repositório e remove todos os
# temporários via trap EXIT/INT/TERM.
# ==============================================================================

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/test-infra.sh"

if [[ ! -f "$SOURCE_SCRIPT" ]]; then
  printf 'ERRO: scripts/test-infra.sh não encontrado em %s\n' "$SOURCE_SCRIPT" >&2
  exit 1
fi

TEST_ROOT="$(mktemp -d /tmp/rewit-test-infra-tests.XXXXXX)"
cleanup_root() {
  # Encerra eventuais servidores HTTP ou listeners auxiliares criados pelos mocks
  if [[ -d "$TEST_ROOT" ]]; then
    local pf pid
    while IFS= read -r pf; do
      [[ -f "$pf" ]] || continue
      pid="$(cat -- "$pf" 2>/dev/null || true)"
      if [[ "$pid" =~ ^[0-9]+$ ]]; then
        kill -TERM "$pid" 2>/dev/null || true
        kill -KILL "$pid" 2>/dev/null || true
      fi
    done < <(find "$TEST_ROOT" -type f -name '*.pid' 2>/dev/null || true)
    rm -rf -- "$TEST_ROOT"
  fi
}
trap cleanup_root EXIT INT TERM

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

LAST_RC=0
LAST_OUT=""
LAST_ERR=""

CASE_DIR=""
CASE_REPO=""
CASE_BIN=""
CASE_TMP=""
CASE_STATE=""
CASE_LOG=""
CASE_MVN_LOG=""
PG_PORT=""
RD_PORT=""
ST_PORT=""

# Aloca 3 portas TCP livres em 127.0.0.1 fora da lista reservada
alloc_free_ports() {
  python3 - <<'PYEOF'
import socket

reserved = {5432, 5433, 6379, 8333, 8888, 9333, 15432, 16379, 18333, 8080, 8081}
socks = []
ports = []
try:
    while len(ports) < 3:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        p = s.getsockname()[1]
        if p >= 1024 and p not in reserved and p not in ports:
            socks.append(s)
            ports.append(p)
        else:
            s.close()
finally:
    for s in socks:
        s.close()
print(f"{ports[0]} {ports[1]} {ports[2]}")
PYEOF
}

# Prepara um ambiente completamente isolado para um caso de teste
setup_case_env() {
  CASE_DIR="${TEST_ROOT}/case-${TESTS_RUN}"
  CASE_REPO="${CASE_DIR}/repo"
  CASE_BIN="${CASE_DIR}/bin"
  CASE_TMP="${CASE_DIR}/tmp"
  CASE_STATE="${CASE_DIR}/runtime-state.json"
  CASE_LOG="${CASE_DIR}/runtime-events.jsonl"
  CASE_MVN_LOG="${CASE_DIR}/mvn-last.json"

  mkdir -p \
    "${CASE_REPO}/scripts" \
    "${CASE_REPO}/backend" \
    "${CASE_REPO}/infrastructure/postgres/init" \
    "${CASE_REPO}/infrastructure/postgres/tests" \
    "${CASE_BIN}" \
    "${CASE_TMP}"

  cp -- "$SOURCE_SCRIPT" "${CASE_REPO}/scripts/test-infra.sh"
  chmod +x "${CASE_REPO}/scripts/test-infra.sh"

  printf '%s\n' '-- mock postgis init' > "${CASE_REPO}/infrastructure/postgres/init/01-init-postgis.sql"
  printf '#!/usr/bin/env bash\nexit 0\n' > "${CASE_REPO}/infrastructure/postgres/init/02-create-app-role.sh"
  chmod +x "${CASE_REPO}/infrastructure/postgres/init/02-create-app-role.sh"
  printf '%s\n' '-- mock provision-runtime-role' > "${CASE_REPO}/infrastructure/postgres/provision-runtime-role.sql"
  # Arquivos que NÃO devem ser copiados para o staging Podman
  printf '#!/usr/bin/env bash\nexit 0\n' > "${CASE_REPO}/infrastructure/postgres/tests/provisioning-test.sh"
  printf 'SEGREDO=nao_copiar\n' > "${CASE_REPO}/infrastructure/postgres/.env"

  # Estado inicial simulado com um container de desenvolvimento persistente intocável
  cat > "$CASE_STATE" <<'EOF'
{
  "seq": 100,
  "containers": {
    "dev000000001": {
      "Id": "dev000000001",
      "Name": "rewit-postgres",
      "Image": "postgis/postgis:18-3.6",
      "Status": "running",
      "Ports": "127.0.0.1:5432->5432/tcp",
      "PortBindings": {
        "5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5432"}]
      },
      "Labels": {"com.docker.compose.project": "rewit"},
      "Volumes": ["rewit_postgres_data"]
    }
  }
}
EOF
  : > "$CASE_LOG"

  # Symlinks estritamente controlados para utilitários básicos (sem docker/podman real!)
  local util path_u
  for util in bash sh env python3 date sleep head cat grep rm mktemp mkdir chmod cp dirname basename setsid true; do
    path_u="$(type -P "$util" || true)"
    if [[ -n "$path_u" ]]; then
      ln -sf "$path_u" "${CASE_BIN}/${util}"
    fi
  done

  # Mock de java
  cat > "${CASE_BIN}/java" <<'EOF'
#!/usr/bin/env bash
printf 'openjdk version "25.0.0-mock"\n' >&2
exit 0
EOF
  chmod +x "${CASE_BIN}/java"

  # Mock de backend/mvnw:
  # - registra metadados de TEST_* (nunca valores de segredos!)
  # - suporta árvore real de processos (wrapper -> filho -> neto)
  # - suporta filho/neto ignorando SIGTERM (para provar fallback SIGKILL no PGID)
  # - suporta encerramento rápido em corrida com sinal
  cat > "${CASE_REPO}/backend/mvnw" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exec python3 - "$@" <<'PYEOF'
import hashlib, json, os, signal, subprocess, sys, time

log_path = os.environ.get("MOCK_MVN_LOG", "")
exit_code = int(os.environ.get("MOCK_MVN_EXIT", "0"))
sleep_sec = float(os.environ.get("MOCK_MVN_SLEEP", "0"))
ready_file = os.environ.get("MOCK_MVN_READY_FILE", "")
spawn_tree = os.environ.get("MOCK_MVN_SPAWN_TREE", "0") == "1"
ignore_term = os.environ.get("MOCK_MVN_CHILD_IGNORE_TERM", "0") == "1"
tree_pids_file = os.environ.get("MOCK_MVN_TREE_PIDS_FILE", "")

required_vars = [
    "TEST_DATASOURCE_URL",
    "TEST_APP_DB_USER", "TEST_APP_DB_PASSWORD",
    "TEST_MEDIA_DB_USER", "TEST_MEDIA_DB_PASSWORD",
    "TEST_MOD_DB_USER", "TEST_MOD_DB_PASSWORD",
    "TEST_MIGRATION_DB_USER", "TEST_MIGRATION_DB_PASSWORD",
    "TEST_REDIS_HOST", "TEST_REDIS_PORT",
    "TEST_STORAGE_ENDPOINT", "TEST_STORAGE_ACCESS_KEY",
    "TEST_STORAGE_SECRET_KEY", "TEST_STORAGE_BUCKET",
]

secret_vars = [
    "TEST_APP_DB_PASSWORD", "TEST_MEDIA_DB_PASSWORD", "TEST_MOD_DB_PASSWORD",
    "TEST_MIGRATION_DB_PASSWORD", "TEST_STORAGE_ACCESS_KEY", "TEST_STORAGE_SECRET_KEY",
]

record = {
    "cwd": os.getcwd(),
    "pid": os.getpid(),
    "pgid": os.getpgrp(),
    "argv": sys.argv[1:],
    "present": {k: (k in os.environ and len(os.environ[k]) > 0) for k in required_vars},
    "redis_password_present": ("TEST_REDIS_PASSWORD" in os.environ),
    "public_values": {
        "TEST_DATASOURCE_URL": os.environ.get("TEST_DATASOURCE_URL", ""),
        "TEST_APP_DB_USER": os.environ.get("TEST_APP_DB_USER", ""),
        "TEST_MEDIA_DB_USER": os.environ.get("TEST_MEDIA_DB_USER", ""),
        "TEST_MOD_DB_USER": os.environ.get("TEST_MOD_DB_USER", ""),
        "TEST_MIGRATION_DB_USER": os.environ.get("TEST_MIGRATION_DB_USER", ""),
        "TEST_REDIS_HOST": os.environ.get("TEST_REDIS_HOST", ""),
        "TEST_REDIS_PORT": os.environ.get("TEST_REDIS_PORT", ""),
        "TEST_STORAGE_ENDPOINT": os.environ.get("TEST_STORAGE_ENDPOINT", ""),
        "TEST_STORAGE_BUCKET": os.environ.get("TEST_STORAGE_BUCKET", ""),
    },
    "secret_meta": {
        k: {
            "len": len(os.environ.get(k, "")),
            "sha256": hashlib.sha256(os.environ.get(k, "").encode()).hexdigest(),
            "in_argv": any(os.environ.get(k, "") and os.environ.get(k, "") in a for a in sys.argv[1:]),
        }
        for k in secret_vars
    },
}

if log_path:
    with open(log_path, "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2)

child_proc = None
if spawn_tree:
    gc_pid_file = (tree_pids_file + ".gc") if tree_pids_file else ""
    child_code = (
        "import os, signal, subprocess, sys, time\n"
        f"ignore_term = {ignore_term!r}\n"
        f"gc_pid_file = {gc_pid_file!r}\n"
        "if ignore_term:\n"
        "    signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
        "    signal.signal(signal.SIGINT, signal.SIG_IGN)\n"
        "gc_code = (\n"
        "    'import signal, time\\n'\n"
        "    + ('signal.signal(signal.SIGTERM, signal.SIG_IGN)\\nsignal.signal(signal.SIGINT, signal.SIG_IGN)\\n' if ignore_term else '')\n"
        "    + 'time.sleep(60)\\n'\n"
        ")\n"
        "gc = subprocess.Popen(['python3', '-c', gc_code], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "if gc_pid_file:\n"
        "    with open(gc_pid_file, 'w', encoding='utf-8') as f: f.write(str(gc.pid))\n"
        "time.sleep(60)\n"
    )
    child_proc = subprocess.Popen(
        ["python3", "-c", child_code],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.time() + 5.0
    gc_pid = 0
    while gc_pid_file and time.time() < deadline:
        if os.path.isfile(gc_pid_file):
            raw = open(gc_pid_file, "r", encoding="utf-8").read().strip()
            if raw.isdigit():
                gc_pid = int(raw)
                break
        time.sleep(0.02)
    if tree_pids_file:
        with open(tree_pids_file, "w", encoding="utf-8") as f:
            json.dump({
                "wrapper_pid": os.getpid(),
                "wrapper_pgid": os.getpgrp(),
                "child_pid": child_proc.pid,
                "grandchild_pid": gc_pid,
            }, f)

if ready_file:
    with open(ready_file, "w", encoding="utf-8") as f:
        f.write("ready\n")

if sleep_sec > 0:
    time.sleep(sleep_sec)

if child_proc is not None:
    try:
        child_proc.terminate()
    except OSError:
        pass

sys.exit(exit_code)
PYEOF
EOF
  chmod +x "${CASE_REPO}/backend/mvnw"

  read -r PG_PORT RD_PORT ST_PORT <<< "$(alloc_free_ports)"
}

# Instala mock fiel de docker ou podman 5.8.9 no CASE_BIN
install_mock_runtime() {
  local rt="$1"
  local mode="${2:-ok}"

  cat > "${CASE_BIN}/${rt}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec python3 - "${rt}" "${mode}" "${CASE_STATE}" "${CASE_LOG}" "\$@" <<'PYEOF'
import json, os, signal, subprocess, sys, time

rt, mode, state_path, log_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
args = sys.argv[5:]

def log_event(evt):
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(evt) + "\n")

def load_state():
    with open(state_path, "r", encoding="utf-8") as f:
        return json.load(f)

def save_state(st):
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump(st, f, indent=2)

# Comandos proibidos
if args and args[0] in ("volume", "system") and "prune" in args:
    log_event({"rt": rt, "cmd": "FORBIDDEN_PRUNE", "args": args})
    sys.stderr.write("ERRO MOCK: prune é proibido\n")
    sys.exit(99)

if args == ["--version"]:
    ver = "5.8.9-mock" if rt == "podman" else "29.0.0-mock"
    print(f"{rt} version {ver}")
    sys.exit(0)

if args == ["info"]:
    if mode == "denied":
        sys.stderr.write("permission denied while trying to connect to the Docker API\n")
        sys.exit(1)
    if mode == "down":
        sys.stderr.write(f"Cannot connect to {rt} daemon\n")
        sys.exit(1)
    print(f"{rt} info ok")
    sys.exit(0)

if mode != "ok":
    sys.stderr.write(f"{rt} indisponível ({mode})\n")
    sys.exit(1)

if not args:
    sys.exit(1)

cmd = args[0]

# F4-D1: Mock Podman 5.8.9 REJEITA EXPLICITAMENTE --filter publish= com código 125!
if cmd == "ps":
    if os.environ.get("MOCK_FAIL_PS", "0") == "1":
        sys.stderr.write(f"Error: cannot connect to {rt} daemon during ps\n")
        sys.exit(125 if rt == "podman" else 1)

    st = load_state()
    filt_label = None
    quiet = False
    fmt = "{{.Names}}"
    i = 1
    while i < len(args):
        if args[i] in ("-q", "--quiet"):
            quiet = True
            i += 1
        elif args[i] == "--filter" and i + 1 < len(args):
            fv = args[i + 1]
            if fv.startswith("publish="):
                if rt == "podman":
                    sys.stderr.write("Error: publish is an invalid filter\n")
                    sys.exit(125)
                else:
                    sys.stderr.write("ERRO MOCK: --filter publish= não deve mais ser usado\n")
                    sys.exit(98)
            elif fv.startswith("label="):
                filt_label = fv[len("label="):]
            i += 2
        elif args[i] == "--format" and i + 1 < len(args):
            fmt = args[i + 1]
            i += 2
        else:
            i += 1

    for cid, c in st["containers"].items():
        if filt_label is not None:
            if "=" in filt_label:
                k, v = filt_label.split("=", 1)
                if c.get("Labels", {}).get(k) != v:
                    continue
            else:
                if filt_label not in c.get("Labels", {}):
                    continue
        if quiet:
            print(cid)
            continue
        out = fmt
        out = out.replace("{{.ID}}", cid)
        out = out.replace("{{.Names}}", c["Name"])
        out = out.replace("{{.Status}}", c["Status"])
        out = out.replace("{{.Ports}}", c.get("Ports", ""))
        print(out)
    sys.exit(0)

if cmd == "container" and len(args) >= 3 and args[1] == "inspect":
    refs = args[2:]
    fail_mode = os.environ.get("MOCK_FAIL_INSPECT_MODE", "")
    fail_target = os.environ.get("MOCK_FAIL_INSPECT_TARGET", "")

    if fail_mode and (not fail_target or fail_target in refs):
        if fail_mode == "daemon":
            sys.stderr.write(f"Cannot connect to the {rt} daemon at unix:///run/{rt}.sock\n")
            sys.exit(125 if rt == "podman" else 1)
        elif fail_mode == "denied":
            sys.stderr.write("permission denied while trying to connect to socket\n")
            sys.exit(125 if rt == "podman" else 1)
        elif fail_mode == "malformed":
            print("{json-corrompido")
            sys.exit(0)
        elif fail_mode == "empty":
            print("[]")
            sys.exit(0)
        elif fail_mode == "missing_error":
            sys.stderr.write(f'Error: no such container "{refs[0]}"\n')
            sys.exit(125 if rt == "podman" else 1)
        elif fail_mode == "bad_hostport":
            print(json.dumps([{
                "Id": "badhp0000001", "Name": "/bad-hp", "State": {"Status": "running"},
                "Config": {"Labels": {}},
                "HostConfig": {"PortBindings": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "nao-numerico"}]}},
                "NetworkSettings": {"Ports": {}}
            }]))
            sys.exit(0)

    st = load_state()
    results = []
    for ref in refs:
        found = None
        for cid, c in st["containers"].items():
            if cid == ref or c["Name"] == ref or c["Name"] == ref.lstrip("/"):
                found = c
                break
        if found is None:
            if rt == "podman":
                print("[]")
                sys.stderr.write(f'Error: no such container "{ref}"\n')
                sys.exit(125)
            else:
                sys.stderr.write(f"Error: No such container: {ref}\n")
                sys.exit(1)
        pb = found.get("PortBindings", {})
        net_ports = found.get("NetworkPorts", pb if found.get("Status") == "running" else {})
        name_val = found["Name"] if rt == "podman" else ("/" + found["Name"])
        results.append({
            "Id": found["Id"],
            "Name": name_val,
            "State": {"Status": found["Status"]},
            "Config": {"Labels": found.get("Labels", {})},
            "HostConfig": {"PortBindings": pb},
            "NetworkSettings": {"Ports": net_ports},
        })
    print(json.dumps(results))
    sys.exit(0)

if cmd == "run":
    st = load_state()
    name = ""
    labels = {}
    ports = []
    port_bindings = {}
    pub_ports = []
    mounts = []
    env_flags = []
    env_inline_secret_leak = False
    image = ""
    extra_cmd = []

    i = 1
    while i < len(args):
        a = args[i]
        if a == "-d":
            i += 1
        elif a == "--name":
            name = args[i + 1]; i += 2
        elif a == "--label":
            k, v = args[i + 1].split("=", 1)
            labels[k] = v; i += 2
        elif a == "-p":
            spec = args[i + 1]
            ports.append(spec)
            parts = spec.split(":")
            if len(parts) == 3:
                hip, hport, cport = parts
                pub_ports.append(int(hport))
                port_bindings.setdefault(f"{cport}/tcp", []).append({"HostIp": hip, "HostPort": hport})
            i += 2
        elif a == "-v":
            mounts.append(args[i + 1]); i += 2
        elif a == "-e":
            ev = args[i + 1]
            env_flags.append(ev)
            if "=" in ev:
                ek = ev.split("=", 1)[0]
                if ek in ("POSTGRES_PASSWORD", "APP_DB_PASSWORD", "MEDIA_DB_PASSWORD", "MOD_DB_PASSWORD", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"):
                    env_inline_secret_leak = True
            i += 2
        else:
            image = a
            extra_cmd = args[i + 1:]
            break

    # Registra snapshot do conteúdo real dos bind mounts no momento do run (para validar staging F4-V1)
    mount_snapshots = {}
    for m in mounts:
        src = m.split(":", 1)[0]
        if os.path.isdir(src):
            entries = []
            for root, dirs, files in os.walk(src, followlinks=False):
                for fn in sorted(files):
                    fp = os.path.join(root, fn)
                    rel = os.path.relpath(fp, src)
                    entries.append({
                        "rel": rel,
                        "is_link": os.path.islink(fp),
                        "exec": os.access(fp, os.X_OK),
                    })
            mount_snapshots[src] = entries

    fail_suffix = os.environ.get("MOCK_FAIL_RUN_SUFFIX", "")
    if fail_suffix and name.endswith(fail_suffix):
        if os.environ.get("MOCK_FAIL_LEAVES_CREATED", "0") == "1":
            st["seq"] += 1
            cid = f"c{st['seq']:011d}"
            st["containers"][cid] = {
                "Id": cid, "Name": name, "Image": image, "Status": "created",
                "Ports": ",".join(ports), "PortBindings": port_bindings,
                "Labels": labels, "Mounts": mounts, "Cmd": extra_cmd,
            }
            save_state(st)
        log_event({"rt": rt, "cmd": "run_failed", "name": name, "mounts": mounts})
        sys.stderr.write(f"mock error: falha simulada ao criar {name}\n")
        sys.exit(125)

    secret_env_present = {
        k: (k in os.environ and len(os.environ[k]) >= 24)
        for k in ("POSTGRES_PASSWORD", "APP_DB_PASSWORD", "MEDIA_DB_PASSWORD", "MOD_DB_PASSWORD", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
        if k in env_flags
    }

    st["seq"] += 1
    cid = f"c{st['seq']:011d}"
    st["containers"][cid] = {
        "Id": cid,
        "Name": name,
        "Image": image,
        "Status": "running",
        "Ports": ",".join(ports),
        "PortBindings": port_bindings,
        "Labels": labels,
        "Mounts": mounts,
        "Cmd": extra_cmd,
    }
    save_state(st)

    if name.endswith("-storage") and pub_ports and os.environ.get("MOCK_STORAGE_NO_HTTP", "0") != "1":
        http_port = pub_ports[0]
        pid_file = os.path.join(os.path.dirname(state_path), f"http-{cid}.pid")
        srv_code = (
            "import http.server, socketserver\n"
            "class H(http.server.BaseHTTPRequestHandler):\n"
            "  def do_GET(self):\n"
            "    self.send_response(403); self.end_headers()\n"
            "  def log_message(self, *a): pass\n"
            "socketserver.TCPServer.allow_reuse_address = True\n"
            f"srv = socketserver.TCPServer(('127.0.0.1', {http_port}), H)\n"
            "srv.serve_forever()\n"
        )
        proc = subprocess.Popen(
            ["python3", "-c", srv_code],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        with open(pid_file, "w", encoding="utf-8") as pf:
            pf.write(str(proc.pid))

    log_event({
        "rt": rt,
        "cmd": "run",
        "id": cid,
        "name": name,
        "image": image,
        "ports": ports,
        "mounts": mounts,
        "mount_snapshots": mount_snapshots,
        "labels": labels,
        "env_flags": env_flags,
        "env_inline_secret_leak": env_inline_secret_leak,
        "secret_env_present": secret_env_present,
        "extra_cmd": extra_cmd,
    })
    print(cid)
    sys.exit(0)

if cmd == "logs":
    if os.environ.get("MOCK_PG_INIT_ERROR", "0") == "1":
        print("02-create-app-role: ERRO: falha simulada")
        sys.exit(0)
    if os.environ.get("MOCK_PG_ONLY_ONE_READY", "0") == "1":
        print("database system is ready to accept connections")
        sys.exit(0)
    if os.environ.get("MOCK_PG_NEVER_READY", "0") == "1":
        print("starting postgres...")
        sys.exit(0)
    print("database system is ready to accept connections\nPostgreSQL init process complete\ndatabase system is ready to accept connections")
    sys.exit(0)

if cmd == "exec":
    if "redis-cli" in args:
        if os.environ.get("MOCK_REDIS_NEVER_READY", "0") == "1":
            sys.exit(1)
        if os.environ.get("MOCK_REDIS_BAD_PONG", "0") == "1":
            print("LOADING Redis is loading the dataset in memory")
            sys.exit(0)
        print("PONG")
        sys.exit(0)
    if "psql" in args:
        if os.environ.get("MOCK_PG_NEVER_READY", "0") == "1":
            print("0")
            sys.exit(0)
        if os.environ.get("MOCK_PG_MISSING_ROLES", "0") == "1":
            print("2")
            sys.exit(0)
        print("3")
        sys.exit(0)
    sys.exit(0)

if cmd == "rm":
    st = load_state()
    ref = args[-1]
    has_f = "-f" in args
    has_v = "-v" in args
    if os.environ.get("MOCK_FAIL_RM", "0") == "1":
        log_event({"rt": rt, "cmd": "rm_failed", "ref": ref})
        sys.stderr.write(f"mock error: falha ao remover {ref}\n")
        sys.exit(1)
    target_cid = None
    for cid, c in st["containers"].items():
        if cid == ref or c["Name"] == ref:
            target_cid = cid
            break
    if target_cid is None:
        sys.stderr.write(f"No such container: {ref}\n")
        sys.exit(1)
    removed = st["containers"].pop(target_cid)
    save_state(st)
    pid_file = os.path.join(os.path.dirname(state_path), f"http-{target_cid}.pid")
    if os.path.isfile(pid_file):
        try:
            pid = int(open(pid_file).read().strip())
            os.kill(pid, signal.SIGTERM)
            for _ in range(25):
                try:
                    os.kill(pid, 0)
                except OSError:
                    break
                time.sleep(0.02)
            else:
                try:
                    os.kill(pid, signal.SIGKILL)
                except OSError:
                    pass
        except OSError:
            pass
        try:
            os.remove(pid_file)
        except OSError:
            pass
    log_event({"rt": rt, "cmd": "rm", "ref": ref, "id": target_cid, "name": removed["Name"], "f": has_f, "v": has_v})
    print(target_cid)
    sys.exit(0)

sys.stderr.write(f"mock {rt}: subcomando não suportado: {args}\n")
sys.exit(1)
PYEOF
EOF
  chmod +x "${CASE_BIN}/${rt}"
}

run_infra() {
  local extra_env="$1"
  shift
  local out_file="${CASE_DIR}/last.out"
  local err_file="${CASE_DIR}/last.err"
  local env_cmd=(env -i "HOME=${CASE_DIR}" "TMPDIR=${CASE_TMP}" "PATH=${CASE_BIN}" "MOCK_MVN_LOG=${CASE_MVN_LOG}")
  if [[ -n "$extra_env" ]]; then
    local kv
    while IFS= read -r kv; do
      [[ -n "$kv" ]] && env_cmd+=("$kv")
    done <<< "$extra_env"
  fi
  LAST_RC=0
  (
    cd -- "$CASE_REPO"
    "${env_cmd[@]}" "${CASE_REPO}/scripts/test-infra.sh" "$@"
  ) >"$out_file" 2>"$err_file" </dev/null || LAST_RC=$?
  LAST_OUT="$(cat -- "$out_file")"
  LAST_ERR="$(cat -- "$err_file")"
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

assert_only_dev_container_remains() {
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert list(st["containers"].keys()) == ["dev000000001"], f"Containers remanescentes inesperados: {list(st['containers'].keys())}"
assert st["containers"]["dev000000001"]["Volumes"] == ["rewit_postgres_data"], "Volume de dev alterado!"
PYEOF
}

assert_no_podman_staging_leftovers() {
  local left
  left="$(find "$CASE_TMP" -maxdepth 1 -name 'rewit-testinfra-pg-staging.*' 2>/dev/null | wc -l)"
  [[ "$left" == "0" ]] || fail_assert "Restaram ${left} diretório(s) de staging Podman em ${CASE_TMP}!"
}

# Verifica empiricamente em /proc/<pid>/stat que nenhum PID está vivo (distinguindo zombies 'Z')
assert_pids_not_alive() {
  local pids_json="$1"
  python3 - "$pids_json" <<'PYEOF'
import json, os, sys

info = json.load(open(sys.argv[1]))
pids = [
    ("wrapper", info.get("wrapper_pid", 0)),
    ("child", info.get("child_pid", 0)),
    ("grandchild", info.get("grandchild_pid", 0)),
]
alive = []
for role, pid in pids:
    if not pid or pid <= 1:
        continue
    stat_path = f"/proc/{pid}/stat"
    if not os.path.exists(stat_path):
        continue
    try:
        raw = open(stat_path, "r", encoding="utf-8", errors="replace").read()
    except OSError:
        continue
    rparen = raw.rfind(")")
    if rparen < 0:
        continue
    state = raw[rparen + 2:].split()[0]
    if state not in ("Z", "X", "x"):
        alive.append((role, pid, state))

assert not alive, f"Processos descendentes ainda vivos após encerramento do grupo Maven: {alive}"
PYEOF
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
# 1. ARGUMENTOS E TODAS AS 11 PORTAS RESERVADAS
# ==============================================================================

test_args_validation_and_all_11_reserved_ports() {
  install_mock_runtime docker ok

  run_infra ""
  assert_rc 2 "ausência de subcomando deve retornar 2"
  run_infra "" up
  assert_rc 2 "subcomando 'up' não implementado deve retornar 2"
  run_infra "" status --opcao-desconhecida
  assert_rc 2 "opção desconhecida deve retornar 2"
  run_infra "" status -- clean test
  assert_rc 2 "'--' fora de 'run' deve retornar 2"

  local bad_inst
  for bad_inst in "" "Maiuscula" "com-hifen" "com_sub" "123456789012345678901" "../x"; do
    run_infra "" status --instance "$bad_inst"
    assert_rc 2 "--instance inválido '${bad_inst}' deve retornar 2"
  done

  run_infra "" run --postgres-port 80
  assert_rc 2 "porta < 1024 deve retornar 2"
  run_infra "" run --redis-port 70000
  assert_rc 2 "porta > 65535 deve retornar 2"
  run_infra "" run --storage-port abc
  assert_rc 2 "porta não numérica deve retornar 2"
  run_infra "" run --ready-timeout 5
  assert_rc 2 "timeout < 10 deve retornar 2"
  run_infra "" run --ready-timeout 901
  assert_rc 2 "timeout > 900 deve retornar 2"

  run_infra "" run --postgres-port "$PG_PORT" --redis-port "$PG_PORT" --storage-port "$ST_PORT"
  assert_rc 3 "portas duplicadas devem bloquear com 3"

  local reserved=(5432 5433 6379 8333 8888 9333 15432 16379 18333 8080 8081)
  local rp
  for rp in "${reserved[@]}"; do
    run_infra "" run --postgres-port "$rp" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
    assert_rc 3 "porta reservada ${rp} deve bloquear com 3"
    assert_contains "$LAST_ERR" "$rp"
  done

  run_infra "" --help
  assert_rc 0 "--help deve retornar 0"
}

# ==============================================================================
# 2. RUNTIME (DOCKER, PODMAN, PERMISSÃO NEGADA, FALLBACK, INVÁLIDO E SETSID)
# ==============================================================================

test_runtime_detection_fallback_and_setsid_doctor() {
  run_infra "TEST_INFRA_RUNTIME=invalido" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 2 "TEST_INFRA_RUNTIME inválido deve retornar 2"

  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "sem docker nem podman deve retornar 4"

  install_mock_runtime docker denied
  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "docker sem permissão sem podman deve retornar 4"
  assert_contains "$LAST_ERR" "permission denied"

  install_mock_runtime podman ok
  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "fallback para podman quando docker sem permissão deve retornar 0"
  assert_contains "$LAST_OUT" "runtime selecionado: podman"
  assert_contains "$LAST_OUT" "setsid"
  assert_contains "$LAST_OUT$LAST_ERR" "permission denied"

  run_infra "TEST_INFRA_RUNTIME=docker" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "TEST_INFRA_RUNTIME=docker sem permissão deve falhar com 4"
  assert_contains "$LAST_ERR" "permission denied"

  install_mock_runtime docker ok
  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0
  assert_contains "$LAST_OUT" "runtime selecionado: docker"

  run_infra "TEST_INFRA_RUNTIME=podman" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0
  assert_contains "$LAST_OUT" "runtime selecionado: podman"

  # F4-P1: ausência de setsid deve falhar no doctor com código 4
  rm -f "${CASE_BIN}/setsid"
  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "doctor sem setsid deve retornar 4"
  assert_contains "$LAST_ERR" "setsid"
}

# ==============================================================================
# 3. F4-D1 — PORTAS PUBLICADAS SEM --filter publish= (DOCKER E PODMAN)
# ==============================================================================

test_f4_d1_port_conflict_detection_docker_and_podman() {
  install_mock_runtime docker ok
  install_mock_runtime podman ok

  # 1) Confirma que o mock Podman rejeita --filter publish= (125)
  local pub_rc=0
  "${CASE_BIN}/podman" ps -a --filter "publish=${PG_PORT}" >/dev/null 2>&1 || pub_rc=$?
  [[ "$pub_rc" == "125" ]] || fail_assert "Mock Podman deveria rejeitar --filter publish= com 125 (obtido=$pub_rc)"

  # 2) Popula cenários diversos de publicação (parado, múltiplos bindings, 0.0.0.0, faixa, ::, ::1, UDP)
  python3 - "$CASE_STATE" "$PG_PORT" "$RD_PORT" "$ST_PORT" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
pg_p, rd_p, st_p = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])

# Container PARADO publicando PG_PORT em 0.0.0.0
st["containers"]["c_exited_v4"] = {
    "Id": "c_exited_v4", "Name": "other-exited-pg", "Status": "exited", "Labels": {},
    "PortBindings": {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": str(pg_p)}]},
    "NetworkPorts": {},
}
# Container RODANDO com múltiplos bindings + faixa de portas incluindo RD_PORT
st["containers"]["c_multi_rng"] = {
    "Id": "c_multi_rng", "Name": "other-multi-range", "Status": "running", "Labels": {},
    "PortBindings": {
        "6379/tcp": [
            {"HostIp": "192.168.10.2", "HostPort": str(rd_p)},
            {"HostIp": "", "HostPort": f"{rd_p - 1}-{rd_p + 1}"},
        ]
    },
}
# Container com wildcard IPv6 dual-stack (::) em ST_PORT
st["containers"]["c_v6_wild"] = {
    "Id": "c_v6_wild", "Name": "other-v6-wildcard", "Status": "running", "Labels": {},
    "PortBindings": {"8333/tcp": [{"HostIp": "::", "HostPort": str(st_p)}]},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  # Testa em PODMAN: deve bloquear os 3 conflitos sem usar --filter publish= (código 3)
  run_infra "TEST_INFRA_RUNTIME=podman" run --instance d1p \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 3 "Podman deve detectar conflitos de porta (exited/0.0.0.0/faixa/::) com 3"
  assert_contains "$LAST_ERR" "other-exited-pg"
  assert_contains "$LAST_ERR" "other-multi-range"
  assert_contains "$LAST_ERR" "other-v6-wildcard"

  # Testa em DOCKER: exatamente o mesmo comportamento
  run_infra "TEST_INFRA_RUNTIME=docker" run --instance d1d \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 3 "Docker deve detectar conflitos de porta com 3"
  assert_contains "$LAST_ERR" "other-exited-pg"
  assert_contains "$LAST_ERR" "other-multi-range"
  assert_contains "$LAST_ERR" "other-v6-wildcard"

  # 3) Substitui por bindings NÃO conflitantes com 127.0.0.1: ::1 exclusivo, outro IP, UDP e porta diferente
  python3 - "$CASE_STATE" "$PG_PORT" "$RD_PORT" "$ST_PORT" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
pg_p, rd_p, st_p = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
st["containers"] = {
    "dev000000001": st["containers"]["dev000000001"],
    "c_v6_lo": {
        "Id": "c_v6_lo", "Name": "other-v6-loopback-only", "Status": "running", "Labels": {},
        "PortBindings": {"5432/tcp": [{"HostIp": "::1", "HostPort": str(pg_p)}]},
    },
    "c_udp": {
        "Id": "c_udp", "Name": "other-udp-only", "Status": "running", "Labels": {},
        "PortBindings": {"6379/udp": [{"HostIp": "127.0.0.1", "HostPort": str(rd_p)}]},
    },
    "c_other_ip": {
        "Id": "c_other_ip", "Name": "other-lan-ip", "Status": "running", "Labels": {},
        "PortBindings": {"8333/tcp": [{"HostIp": "10.20.30.40", "HostPort": str(st_p)}]},
    },
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  run_infra "TEST_INFRA_RUNTIME=podman" run --instance d1ok \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "Bindings ::1 exclusivo, UDP e outro IP não devem causar falso conflito com 127.0.0.1"
  assert_no_podman_staging_leftovers

  # 4) JSON malformado no inspect de portas → erro 4 (nunca aprovação silenciosa)
  run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_FAIL_INSPECT_MODE=malformed\nMOCK_FAIL_INSPECT_TARGET=dev000000001' \
    run --instance d1m --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "JSON malformado no inspect de portas deve retornar 4"

  # 5) HostPort malformado → erro 4
  run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_FAIL_INSPECT_MODE=bad_hostport\nMOCK_FAIL_INSPECT_TARGET=dev000000001' \
    run --instance d1hp --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "HostPort malformado deve retornar 4"

  # 6) Falha de ps -a -q → erro 4
  run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_FAIL_PS=1' \
    run --instance d1ps --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "Falha de ps -a -q deve retornar 4"

  # 7) Falha de inspect de portas (daemon) → erro 4
  run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_FAIL_INSPECT_MODE=daemon\nMOCK_FAIL_INSPECT_TARGET=dev000000001' \
    run --instance d1in --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "Falha de inspect de portas deve retornar 4"
}

# ==============================================================================
# 4. F4-V1 — STAGING TEMPORÁRIO PODMAN/SELINUX FORA DO REPOSITÓRIO
# ==============================================================================

test_f4_v1_podman_selinux_staging_and_cleanup_all_paths() {
  install_mock_runtime podman ok

  local repo_snap_before repo_snap_after
  repo_snap_before="$(sha256sum \
    "${CASE_REPO}/infrastructure/postgres/init/01-init-postgis.sql" \
    "${CASE_REPO}/infrastructure/postgres/init/02-create-app-role.sh" \
    "${CASE_REPO}/infrastructure/postgres/provision-runtime-role.sql")"

  # 1) Sucesso: mounts apontam SOMENTE para staging em CASE_TMP com :ro,z, contendo todos os assets e sem .env/tests/symlinks
  run_infra "TEST_INFRA_RUNTIME=podman" run --instance v1ok \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "run com podman e staging deve retornar 0"
  assert_only_dev_container_remains
  assert_no_podman_staging_leftovers

  python3 - "$CASE_LOG" "$CASE_TMP" "$CASE_REPO" <<'PYEOF'
import json, os, sys
events = [json.loads(line) for line in open(sys.argv[1]) if line.strip()]
case_tmp = os.path.realpath(sys.argv[2])
case_repo = os.path.realpath(sys.argv[3])
pg = [e for e in events if e["cmd"] == "run" and e["name"] == "rewit-testinfra-v1ok-postgres"][0]
assert pg["rt"] == "podman"
assert len(pg["mounts"]) == 2
for m in pg["mounts"]:
    src, dst, opts = m.split(":", 2)
    assert opts == "ro,z", f"Esperado ro,z no staging Podman, obtido {opts}"
    real_src = os.path.realpath(src) if os.path.exists(src) else os.path.normpath(src)
    assert real_src.startswith(case_tmp + os.sep), f"Mount Podman fora do staging temporário: {m}"
    assert not real_src.startswith(case_repo + os.sep), f"PROIBIDO: mount :ro,z apontando para o repositório: {m}"
    assert dst in ("/docker-entrypoint-initdb.d", "/opt/rewit/postgres")

snaps = pg["mount_snapshots"]
init_src = [m.split(":")[0] for m in pg["mounts"] if ":/docker-entrypoint-initdb.d:" in m][0]
prov_src = [m.split(":")[0] for m in pg["mounts"] if ":/opt/rewit/postgres:" in m][0]

init_files = {item["rel"]: item for item in snaps[init_src]}
prov_files = {item["rel"]: item for item in snaps[prov_src]}

assert "01-init-postgis.sql" in init_files and not init_files["01-init-postgis.sql"]["is_link"]
assert "02-create-app-role.sh" in init_files and init_files["02-create-app-role.sh"]["exec"] is True and not init_files["02-create-app-role.sh"]["is_link"]
assert list(prov_files.keys()) == ["provision-runtime-role.sql"], f"Arquivos indevidos no staging postgres/: {prov_files}"
assert not prov_files["provision-runtime-role.sql"]["is_link"]
PYEOF

  # 2) Falha no 1º, 2º e 3º container → staging sempre removido!
  local suf
  for suf in -postgres -redis -storage; do
    run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_FAIL_RUN_SUFFIX='"${suf}"$'\nMOCK_FAIL_LEAVES_CREATED=1' \
      run --instance "v1f${suf#-}" --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
    assert_rc 4 "falha no container ${suf} deve retornar 4"
    assert_only_dev_container_remains
    assert_no_podman_staging_leftovers
  done

  # 3) Timeout de readiness → staging removido!
  run_infra $'TEST_INFRA_RUNTIME=podman\nMOCK_PG_NEVER_READY=1' \
    run --instance v1to --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT" --ready-timeout 10
  assert_rc 4 "timeout no Podman deve retornar 4"
  assert_only_dev_container_remains
  assert_no_podman_staging_leftovers

  # 4) SIGTERM e SIGINT no Podman → staging removido!
  local sig_name expected_rc
  for sig_name in TERM INT; do
    expected_rc=143
    [[ "$sig_name" == "INT" ]] && expected_rc=130
    local rf="${CASE_DIR}/podman-ready-${sig_name}"
    local got_rc=0
    python3 - "$CASE_REPO" "$CASE_DIR" "$CASE_TMP" "$CASE_BIN" "$CASE_MVN_LOG" "$rf" "$PG_PORT" "$RD_PORT" "$ST_PORT" "$sig_name" <<'PYEOF' || got_rc=$?
import os, signal, subprocess, sys, time
repo, cdir, ctmp, cbin, mlog, rfile, pg, rd, st, sname = sys.argv[1:11]
sig = signal.SIGINT if sname == "INT" else signal.SIGTERM
env = {
    "HOME": cdir, "TMPDIR": ctmp, "PATH": cbin, "TEST_INFRA_RUNTIME": "podman",
    "MOCK_MVN_LOG": mlog, "MOCK_MVN_SLEEP": "30", "MOCK_MVN_READY_FILE": rfile,
}
cmd = [os.path.join(repo, "scripts", "test-infra.sh"), "run", "--instance", f"v1{sname.lower()}",
       "--postgres-port", pg, "--redis-port", rd, "--storage-port", st]
proc = subprocess.Popen(cmd, cwd=repo, env=env, stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL))
deadline = time.time() + 10.0
while not os.path.isfile(rfile) and time.time() < deadline:
    time.sleep(0.05)
proc.send_signal(sig)
sys.exit(proc.wait(timeout=15))
PYEOF
    [[ "$got_rc" == "$expected_rc" ]] || fail_assert "Podman SIG${sig_name} deveria retornar ${expected_rc} (obtido=${got_rc})"
    assert_only_dev_container_remains
    assert_no_podman_staging_leftovers
  done

  # 5) Symlink inesperado em init/ → falha de cópia/validação (4) e limpa staging!
  ln -s /etc/hosts "${CASE_REPO}/infrastructure/postgres/init/99-evil.sql"
  run_infra "TEST_INFRA_RUNTIME=podman" run --instance v1sym \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  rm -f "${CASE_REPO}/infrastructure/postgres/init/99-evil.sql"
  assert_rc 4 "symlink inesperado em init/ deve falhar com 4"
  assert_no_podman_staging_leftovers

  # 6) Arquivo de init obrigatório ausente → falha (4) e limpa staging!
  mv "${CASE_REPO}/infrastructure/postgres/init/01-init-postgis.sql" "${CASE_DIR}/01.bak"
  run_infra "TEST_INFRA_RUNTIME=podman" run --instance v1miss \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  mv "${CASE_DIR}/01.bak" "${CASE_REPO}/infrastructure/postgres/init/01-init-postgis.sql"
  assert_rc 4 "arquivo de init ausente deve falhar com 4"
  assert_no_podman_staging_leftovers

  # 7) Arquivos do repositório permanecem idênticos!
  repo_snap_after="$(sha256sum \
    "${CASE_REPO}/infrastructure/postgres/init/01-init-postgis.sql" \
    "${CASE_REPO}/infrastructure/postgres/init/02-create-app-role.sh" \
    "${CASE_REPO}/infrastructure/postgres/provision-runtime-role.sql")"
  [[ "$repo_snap_before" == "$repo_snap_after" ]] || fail_assert "Arquivos de init do repositório foram modificados!"
}

# ==============================================================================
# 5. RUN (DOCKER) — 3 SERVIÇOS, :ro SEM :z, CSPRNG, MAVEN COM ESPAÇOS E CLEANUP
# ==============================================================================

test_run_docker_full_provisioning_secrets_maven_args_and_cleanup() {
  install_mock_runtime docker ok

  run_infra "TEST_REDIS_PASSWORD=senha-vazada-da-sessao" \
    run --instance ci01 --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT" \
    -- test "-Dtest=Auth Test" "-Dfoo=bar baz"
  assert_rc 0 "run completo com Docker deve retornar 0"

  python3 - "$CASE_LOG" "$CASE_MVN_LOG" "$PG_PORT" "$RD_PORT" "$ST_PORT" <<'PYEOF'
import json, sys
events = [json.loads(line) for line in open(sys.argv[1]) if line.strip()]
mvn = json.load(open(sys.argv[2]))
pg_p, rd_p, st_p = sys.argv[3], sys.argv[4], sys.argv[5]

runs = [e for e in events if e["cmd"] == "run"]
rms = [e for e in events if e["cmd"] == "rm"]
assert len(runs) == 3 and len(rms) == 3
assert not any(e["cmd"] == "FORBIDDEN_PRUNE" for e in events)

by_name = {e["name"]: e for e in runs}
pg, rd, st = by_name["rewit-testinfra-ci01-postgres"], by_name["rewit-testinfra-ci01-redis"], by_name["rewit-testinfra-ci01-storage"]

assert pg["image"] == "postgis/postgis:18-3.6"
assert rd["image"] == "redis:8-alpine"
assert st["image"] == "chrislusf/seaweedfs:4.47"
assert pg["ports"] == [f"127.0.0.1:{pg_p}:5432"]
assert rd["ports"] == [f"127.0.0.1:{rd_p}:6379"]
assert st["ports"] == [f"127.0.0.1:{st_p}:8333"]
assert st["extra_cmd"] == ["mini", "-dir=/data", "-s3.port=8333"]

# Docker usa :ro diretamente (sem :z)
assert any(m.endswith(":/docker-entrypoint-initdb.d:ro") for m in pg["mounts"])
assert any(m.endswith(":/opt/rewit/postgres:ro") for m in pg["mounts"])

for e in runs:
    assert e["labels"]["rewit.test-infra"] == "ci01"
    assert e["labels"]["rewit.test-infra.script"] == "scripts/test-infra.sh"
    assert len(e["labels"].get("rewit.test-infra.run-id", "")) == 16
    assert e["env_inline_secret_leak"] is False

assert all(pg["secret_env_present"].values()) and len(pg["secret_env_present"]) == 4
assert all(st["secret_env_present"].values()) and len(st["secret_env_present"]) == 2
assert {e["id"] for e in runs} == {e["id"] for e in rms}
assert all(e["f"] and e["v"] for e in rms)

assert mvn["argv"] == ["test", "-Dtest=Auth Test", "-Dfoo=bar baz"]
assert all(mvn["present"].values())
assert mvn["redis_password_present"] is False
assert mvn["public_values"]["TEST_DATASOURCE_URL"] == f"jdbc:postgresql://127.0.0.1:{pg_p}/rewit_test"
assert mvn["public_values"]["TEST_STORAGE_ENDPOINT"] == f"http://127.0.0.1:{st_p}"
assert mvn["public_values"]["TEST_STORAGE_BUCKET"] == "rewit-test"

hashes = set()
for k, meta in mvn["secret_meta"].items():
    assert meta["in_argv"] is False
    expected_len = 24 if k == "TEST_STORAGE_ACCESS_KEY" else 48
    assert meta["len"] == expected_len
    hashes.add(meta["sha256"])
assert len(hashes) == 6
PYEOF

  assert_only_dev_container_remains
}

# ==============================================================================
# 6. F4-P1 — ÁRVORE DE PROCESSOS DO MAVEN (FILHO, NETO, SIG_IGN, SIGKILL, CORRIDA)
# ==============================================================================

test_f4_p1_maven_process_group_signals_descendants_and_exit_codes() {
  install_mock_runtime docker ok

  # 1) Maven retorna código 7 → preservado após cleanup OK
  run_infra "MOCK_MVN_EXIT=7" run --instance p1e7 \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 7 "exit code 7 do Maven deve ser preservado"
  assert_only_dev_container_remains

  # Helper para testar sinais com árvore real (wrapper + filho + neto)
  run_signal_tree_case() {
    local inst="$1" sig_name="$2" expected_rc="$3" ignore_term="$4" sleep_sec="$5"
    local rfile="${CASE_DIR}/ready-${inst}"
    local pfile="${CASE_DIR}/pids-${inst}.json"
    local got_rc=0
    python3 - "$CASE_REPO" "$CASE_DIR" "$CASE_TMP" "$CASE_BIN" "$CASE_MVN_LOG" "$rfile" "$pfile" "$inst" "$PG_PORT" "$RD_PORT" "$ST_PORT" "$sig_name" "$ignore_term" "$sleep_sec" <<'PYEOF' || got_rc=$?
import json, os, signal, subprocess, sys, time
repo, cdir, ctmp, cbin, mlog, rfile, pfile, inst, pg, rd, st, sname, ign, ssec = sys.argv[1:15]
sig = signal.SIGINT if sname == "INT" else signal.SIGTERM
env = {
    "HOME": cdir, "TMPDIR": ctmp, "PATH": cbin,
    "MOCK_MVN_LOG": mlog, "MOCK_MVN_SLEEP": ssec,
    "MOCK_MVN_READY_FILE": rfile, "MOCK_MVN_SPAWN_TREE": "1",
    "MOCK_MVN_CHILD_IGNORE_TERM": ign, "MOCK_MVN_TREE_PIDS_FILE": pfile,
}
cmd = [os.path.join(repo, "scripts", "test-infra.sh"), "run", "--instance", inst,
       "--postgres-port", pg, "--redis-port", rd, "--storage-port", st]
proc = subprocess.Popen(cmd, cwd=repo, env=env, stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL))
deadline = time.time() + 10.0
while not os.path.isfile(rfile) and time.time() < deadline:
    time.sleep(0.05)
if not os.path.isfile(rfile):
    proc.kill()
    sys.exit(98)
# Confirma que filho e neto foram criados no PGID isolado do Maven (diferente do PGID do script)
info = json.load(open(pfile))
assert info["child_pid"] > 1 and info["grandchild_pid"] > 1, f"Árvore não criada: {info}"
assert info["wrapper_pgid"] == info["wrapper_pid"], f"Maven não está em sessão isolada: {info}"
assert info["wrapper_pgid"] != os.getpgid(proc.pid), "PGID do Maven não pode ser igual ao do script!"
proc.send_signal(sig)
sys.exit(proc.wait(timeout=20))
PYEOF
    [[ "$got_rc" == "$expected_rc" ]] || fail_assert "Caso ${inst} (SIG${sig_name}, ignore_term=${ignore_term}) esperava ${expected_rc}, obteve ${got_rc}"
    assert_pids_not_alive "$pfile"
    assert_only_dev_container_remains
  }

  # 2) SIGTERM com filho + neto normais → 143 e 0 descendentes vivos
  run_signal_tree_case "p1term" "TERM" 143 0 30

  # 3) SIGINT com filho + neto normais → 130 e 0 descendentes vivos
  run_signal_tree_case "p1int" "INT" 130 0 30

  # 4) Filho + neto que IGNORAM SIGTERM → fallback SIGKILL após grace period → 143 e 0 descendentes vivos!
  run_signal_tree_case "p1kill" "TERM" 143 1 30

  # 5) Corrida: Maven concluindo quase junto com SIGTERM (0.05s) → cleanup OK, 0 descendentes vivos, exit 0 ou 143
  local rfile="${CASE_DIR}/ready-race" pfile="${CASE_DIR}/pids-race.json" race_rc=0
  python3 - "$CASE_REPO" "$CASE_DIR" "$CASE_TMP" "$CASE_BIN" "$CASE_MVN_LOG" "$rfile" "$pfile" "$PG_PORT" "$RD_PORT" "$ST_PORT" <<'PYEOF' || race_rc=$?
import os, signal, subprocess, sys, time
repo, cdir, ctmp, cbin, mlog, rfile, pfile, pg, rd, st = sys.argv[1:11]
env = {
    "HOME": cdir, "TMPDIR": ctmp, "PATH": cbin,
    "MOCK_MVN_LOG": mlog, "MOCK_MVN_SLEEP": "0.05",
    "MOCK_MVN_READY_FILE": rfile, "MOCK_MVN_SPAWN_TREE": "1",
    "MOCK_MVN_TREE_PIDS_FILE": pfile,
}
cmd = [os.path.join(repo, "scripts", "test-infra.sh"), "run", "--instance", "p1race",
       "--postgres-port", pg, "--redis-port", rd, "--storage-port", st]
proc = subprocess.Popen(cmd, cwd=repo, env=env, stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
deadline = time.time() + 10.0
while not os.path.isfile(rfile) and time.time() < deadline:
    time.sleep(0.01)
time.sleep(0.03)
try:
    proc.send_signal(signal.SIGTERM)
except ProcessLookupError:
    pass
sys.exit(proc.wait(timeout=15))
PYEOF
  [[ "$race_rc" == "0" || "$race_rc" == "143" ]] || fail_assert "Corrida conclusão/SIGTERM retornou código inesperado: ${race_rc}"
  assert_pids_not_alive "$pfile"
  assert_only_dev_container_remains

  # 6) Falha de cleanup após falha do Maven (5) → código 4 reportando código original 5
  run_infra $'MOCK_MVN_EXIT=5\nMOCK_FAIL_RM=1' run --instance p1cf \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 4 "falha de cleanup deve retornar 4"
  assert_contains "$LAST_ERR" "código original do Maven: 5"
}

# ==============================================================================
# 7. F4-C1 — PRESENT / ABSENT / ERROR EM INSPECT E DESCOBERTA POR LABEL EM DOWN
# ==============================================================================

test_f4_c1_inspect_error_differentiation_and_unexpected_label_discovery() {
  install_mock_runtime docker ok

  # 1) Container realmente ausente → down idempotente (0)
  run_infra "" down --instance c1abs
  assert_rc 0 "down com containers realmente ausentes deve retornar 0"

  # Popula 2 containers válidos para `c1mix`
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
st["containers"]["c1pg00000001"] = {
    "Id": "c1pg00000001", "Name": "rewit-testinfra-c1mix-postgres", "Status": "running",
    "Labels": {"rewit.test-infra": "c1mix"},
}
st["containers"]["c1rd00000002"] = {
    "Id": "c1rd00000002", "Name": "rewit-testinfra-c1mix-redis", "Status": "running",
    "Labels": {"rewit.test-infra": "c1mix"},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  # 2) Inspect falhando por daemon, permissão, malformado ou container listado em ps mas inspect diz missing → 4 sem remover nada!
  local mode
  for mode in daemon denied malformed empty missing_error; do
    run_infra $'MOCK_FAIL_INSPECT_MODE='"${mode}"$'\nMOCK_FAIL_INSPECT_TARGET=rewit-testinfra-c1mix-redis' \
      down --instance c1mix
    assert_rc 4 "down com inspect '${mode}' deve bloquear todo o conjunto com código 4"
    python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert "c1pg00000001" in st["containers"] and "c1rd00000002" in st["containers"], "down fez remoção parcial diante de erro de inspect!"
assert "dev000000001" in st["containers"]
PYEOF
  done

  # 3) Container com LABEL CORRETO (rewit.test-infra=c1mix) mas NOME INESPERADO → bloqueio 3 sem remoção parcial!
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
st["containers"]["c1rogue00003"] = {
    "Id": "c1rogue00003", "Name": "rewit-custom-unexpected", "Status": "running",
    "Labels": {"rewit.test-infra": "c1mix"},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  run_infra "" down --instance c1mix
  assert_rc 3 "container com label da instância e nome inesperado deve bloquear todo o conjunto com 3"
  assert_contains "$LAST_ERR" "rewit-custom-unexpected"
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert "c1pg00000001" in st["containers"] and "c1rd00000002" in st["containers"] and "c1rogue00003" in st["containers"], "Remoção parcial indevida!"
assert "dev000000001" in st["containers"]
PYEOF

  # 4) Remove o rogue e adiciona nome esperado (storage) com label divergente → bloqueio 3 sem remoção parcial!
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
st["containers"].pop("c1rogue00003")
st["containers"]["c1badst00004"] = {
    "Id": "c1badst00004", "Name": "rewit-testinfra-c1mix-storage", "Status": "running",
    "Labels": {"rewit.test-infra": "outrainst"},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  run_infra "" down --instance c1mix
  assert_rc 3 "nome esperado com label divergente entre candidatos válidos deve bloquear todo o conjunto com 3"
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert "c1pg00000001" in st["containers"] and "c1rd00000002" in st["containers"] and "c1badst00004" in st["containers"], "Remoção parcial indevida!"
assert "dev000000001" in st["containers"]
PYEOF
}

# ==============================================================================
# 8. CONCORRÊNCIA E ISOLAMENTO ENTRE INSTÂNCIAS
# ==============================================================================

test_concurrency_same_instance_blocked_and_different_instances_isolated() {
  install_mock_runtime docker ok

  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
st["containers"]["run1pg000001"] = {
    "Id": "run1pg000001", "Name": "rewit-testinfra-a1-postgres", "Status": "running",
    "PortBindings": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "45101"}]},
    "Labels": {"rewit.test-infra": "a1", "rewit.test-infra.run-id": "run1orig00000001"},
}
st["containers"]["run2pg000002"] = {
    "Id": "run2pg000002", "Name": "rewit-testinfra-b2-postgres", "Status": "running",
    "PortBindings": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "45201"}]},
    "Labels": {"rewit.test-infra": "b2", "rewit.test-infra.run-id": "run2orig00000002"},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  run_infra "" run --instance a1 --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 3 "segunda execução concorrente na mesma instância deve bloquear com 3"
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert "run1pg000001" in st["containers"] and "run2pg000002" in st["containers"]
PYEOF

  run_infra "" down --instance a1
  assert_rc 0 "down seletivo de a1 deve retornar 0"
  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
assert "run1pg000001" not in st["containers"]
assert "run2pg000002" in st["containers"]
assert "dev000000001" in st["containers"]
PYEOF

  run_infra "" down --instance a1
  assert_rc 0 "down idempotente deve retornar 0"
}

# ==============================================================================
# 9. F4-P2 — LISTENERS IPv4, IPv4 WILDCARD, IPv6 ::1, :: DUAL-STACK, :: V6ONLY E SEM IPv6
# ==============================================================================

test_f4_p2_host_port_probes_ipv4_and_ipv6() {
  install_mock_runtime docker ok

  # Helper que inicia um listener real em background, aguarda bind pronto e roda doctor
  test_listener_case() {
    local family="$1" host="$2" v6only="$3" expected_rc="$4" expect_text="$5"
    local ready_f="${CASE_DIR}/lst-${family}-${v6only}.ready"
    local pid_f="${CASE_DIR}/lst-${family}-${v6only}.pid"
    rm -f "$ready_f" "$pid_f"

    python3 - "$family" "$host" "$PG_PORT" "$v6only" "$ready_f" "$pid_f" <<'PYEOF' &
import os, socket, sys, time
fam_s, host, port, v6only, rfile, pfile = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5], sys.argv[6]
fam = socket.AF_INET6 if fam_s == "v6" else socket.AF_INET
s = socket.socket(fam, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
if fam == socket.AF_INET6 and v6only in (0, 1):
    s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, v6only)
try:
    s.bind((host, port))
    s.listen(1)
    with open(pfile, "w") as pf: pf.write(str(os.getpid()))
    with open(rfile, "w") as rf: rf.write("ok\n")
    time.sleep(15)
except OSError as exc:
    with open(rfile, "w") as rf: rf.write(f"SKIP:{exc}\n")
finally:
    s.close()
PYEOF
    local bg_pid=$!
    local w=0
    while [[ ! -f "$ready_f" ]] && (( w < 50 )); do sleep 0.05; w=$((w + 1)); done
    local status_txt=""
    status_txt="$(cat -- "$ready_f" 2>/dev/null || true)"
    if [[ "$status_txt" == SKIP:* ]]; then
      kill -TERM "$bg_pid" 2>/dev/null || true
      wait "$bg_pid" 2>/dev/null || true
      return 0
    fi

    run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
    kill -TERM "$bg_pid" 2>/dev/null || true
    wait "$bg_pid" 2>/dev/null || true

    assert_rc "$expected_rc" "Listener ${family} (${host}, v6only=${v6only}) deveria retornar ${expected_rc}"
    assert_contains "${LAST_OUT}${LAST_ERR}" "$expect_text"
  }

  # 1) Porta realmente disponível
  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "porta livre deve aprovar no doctor"

  # 2) Listener IPv4 loopback (127.0.0.1) → bloqueia (3)
  test_listener_case "v4" "127.0.0.1" -1 3 "$PG_PORT"

  # 3) Listener IPv4 wildcard (0.0.0.0) → bloqueia (3)
  test_listener_case "v4" "0.0.0.0" -1 3 "$PG_PORT"

  # 4) Listener IPv6 wildcard dual-stack (:: com IPV6_V6ONLY=0) → bloqueia (3)
  test_listener_case "v6" "::" 0 3 "$PG_PORT"

  # 5) Listener IPv6 loopback (::1) → NÃO gera falso positivo bloqueante (0 com AVISO)
  test_listener_case "v6" "::1" 1 0 "IPv6-only"

  # 6) Listener IPv6-only wildcard (:: com IPV6_V6ONLY=1) → NÃO gera falso positivo bloqueante (0 com AVISO)
  test_listener_case "v6" "::" 1 0 "IPv6-only"

  # 7) Ambiente sem IPv6 habilitado (via sitecustomize.py isolado em PYTHONPATH) → degrada sem erro (0)
  local no_v6_dir="${CASE_DIR}/no-ipv6-py"
  mkdir -p "$no_v6_dir"
  cat > "${no_v6_dir}/sitecustomize.py" <<'PYEOF'
import socket
socket.has_ipv6 = False
PYEOF
  run_infra "PYTHONPATH=${no_v6_dir}" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "ambiente sem IPv6 habilitado deve degradar graciosamente e retornar 0"
  assert_contains "$LAST_OUT" "pilha IPv6 indisponível"
}

# ==============================================================================
# 10. F4-R1 — READINESS PRECISO (ERRO INIT, BOOTSTRAP INCOMPLETO, ROLES, REDIS, HTTP)
# ==============================================================================

test_f4_r1_readiness_checks_and_failure_modes() {
  install_mock_runtime docker ok

  # 1) Log '02-create-app-role: ERRO' → falha imediata (4) e limpa os 3 containers!
  run_infra "MOCK_PG_INIT_ERROR=1" run --instance r1err \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT" --ready-timeout 30
  assert_rc 4 "erro de provisionamento 02-create-app-role deve falhar imediatamente com 4"
  assert_contains "$LAST_ERR" "Provisionamento das roles de runtime falhou"
  assert_only_dev_container_remains

  # 2) PG com apenas 1 'ready to accept connections' + Redis sem PONG + SeaweedFS sem HTTP → todos pendentes no timeout (4)
  run_infra $'MOCK_PG_ONLY_ONE_READY=1\nMOCK_REDIS_BAD_PONG=1\nMOCK_STORAGE_NO_HTTP=1' \
    run --instance r1all --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT" --ready-timeout 10
  assert_rc 4 "bootstrap PG incompleto + Redis sem PONG + Storage sem HTTP devem expirar com 4"
  assert_contains "$LAST_ERR" "rewit-testinfra-r1all-postgres"
  assert_contains "$LAST_ERR" "rewit-testinfra-r1all-redis"
  assert_contains "$LAST_ERR" "rewit-testinfra-r1all-storage"
  assert_only_dev_container_remains

  # 3) PG com bootstrap completo mas apenas 2 roles (< 3) → Redis e Storage prontos, PG expira com 4!
  run_infra "MOCK_PG_MISSING_ROLES=1" \
    run --instance r1roles --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT" --ready-timeout 10
  assert_rc 4 "PostgreSQL sem as 3 roles não pode aprovar readiness"
  assert_contains "$LAST_ERR" "rewit-testinfra-r1roles-postgres"
  assert_not_contains "$LAST_ERR" "rewit-testinfra-r1roles-redis"
  assert_not_contains "$LAST_ERR" "rewit-testinfra-r1roles-storage"
  assert_only_dev_container_remains

  # 4) Readiness OK emite mensagens precisas sobre limites de cada verificação
  run_infra "" run --instance r1ok \
    --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0
  assert_contains "$LAST_OUT" "psql local"
  assert_contains "$LAST_OUT" "PING/PONG verificado sem senha"
  assert_contains "$LAST_OUT" "disponibilidade HTTP verificada"
  assert_only_dev_container_remains
}

# ==============================================================================
# 11. READ-ONLY (SNAPSHOTS STATUS/DOCTOR) E GUARDAS ESTÁTICAS
# ==============================================================================

test_status_and_doctor_are_strictly_readonly_and_static_guards() {
  install_mock_runtime docker ok

  python3 - "$CASE_STATE" <<'PYEOF'
import json, sys
st = json.load(open(sys.argv[1]))
st["containers"]["st1pg0000001"] = {
    "Id": "st1pg0000001", "Name": "rewit-testinfra-st1-postgres", "Status": "Up 5 minutes",
    "Ports": "127.0.0.1:35432->5432/tcp", "Labels": {"rewit.test-infra": "st1"},
}
json.dump(st, open(sys.argv[1], "w"), indent=2)
PYEOF

  local snap_before snap_after
  snap_before="$(sha256sum "$CASE_STATE" "$CASE_LOG")"

  run_infra "" status
  assert_rc 0 "status deve retornar 0"
  assert_contains "$LAST_OUT" "rewit-testinfra-st1-postgres"
  assert_contains "$LAST_OUT" "validada (rewit.test-infra=st1)"
  assert_not_contains "$LAST_OUT" "PASSWORD"

  run_infra "" doctor --postgres-port "$PG_PORT" --redis-port "$RD_PORT" --storage-port "$ST_PORT"
  assert_rc 0 "doctor em ambiente saudável deve retornar 0"
  assert_contains "$LAST_OUT" "python3"
  assert_contains "$LAST_OUT" "setsid"
  assert_contains "$LAST_OUT" "java"
  assert_contains "$LAST_OUT" "backend/mvnw"

  snap_after="$(sha256sum "$CASE_STATE" "$CASE_LOG")"
  [[ "$snap_before" == "$snap_after" ]] || fail_assert "status ou doctor alteraram o estado simulado!"

  # Guardas estáticas em scripts/test-infra.sh (sobre linhas de código, excluindo comentários)
  ! grep -nE '(^|[|;&[:space:]])eval[[:space:]]' "$SOURCE_SCRIPT" | grep -vE '^[0-9]+:[[:space:]]*#' || fail_assert "eval encontrado em test-infra.sh"
  ! grep -nE 'set[[:space:]]+-[a-zA-Z]*x' "$SOURCE_SCRIPT" | grep -vE '^[0-9]+:[[:space:]]*#' || fail_assert "set -x encontrado em test-infra.sh"
  ! grep -nE '(volume|system)[[:space:]]+prune|compose[[:space:]]+down' "$SOURCE_SCRIPT" | grep -vE '^[0-9]+:[[:space:]]*#' || fail_assert "prune/compose down encontrado em test-infra.sh"
  ! grep -nE -- '--filter[[:space:]]+"?publish=' "$SOURCE_SCRIPT" | grep -vE '^[0-9]+:[[:space:]]*#' || fail_assert "--filter publish= ainda presente em test-infra.sh"
}

# ==============================================================================
# Execução da suíte
# ==============================================================================

printf '=== Executando suíte adversarial descartável de scripts/test-infra.sh (R1) ===\n'
printf 'Diretório temporário isolado: %s\n\n' "$TEST_ROOT"

run_test "1. ARGS: subcomandos, defaults, --instance, faixas, duplicatas e todas as 11 portas reservadas" test_args_validation_and_all_11_reserved_ports
run_test "2. RUNTIME & DOCTOR: docker/podman, permissão negada, fallback, explícito, inválido e ausência de setsid" test_runtime_detection_fallback_and_setsid_doctor
run_test "3. F4-D1: portas Docker/Podman sem --filter publish= (parados, múltiplos, 0.0.0.0, faixas, IPv4/IPv6, JSON malformado, falhas)" test_f4_d1_port_conflict_detection_docker_and_podman
run_test "4. F4-V1: staging temporário Podman/SELinux fora do repo, anti-symlink e cleanup em todos os caminhos" test_f4_v1_podman_selinux_staging_and_cleanup_all_paths
run_test "5. RUN (Docker): 3 serviços, :ro sem :z, rótulos, CSPRNG fora de argv, Maven com espaços e cleanup" test_run_docker_full_provisioning_secrets_maven_args_and_cleanup
run_test "6. F4-P1: process group Maven (setsid), filho+neto, SIG_IGN+SIGKILL, SIGTERM/SIGINT, corrida e exit codes" test_f4_p1_maven_process_group_signals_descendants_and_exit_codes
run_test "7. F4-C1: PRESENT/ABSENT/ERROR em inspect, label correto + nome inesperado e zero remoção parcial em down" test_f4_c1_inspect_error_differentiation_and_unexpected_label_discovery
run_test "8. CONCORRÊNCIA: mesma instância bloqueada sem remover 1ª; instâncias distintas isoladas" test_concurrency_same_instance_blocked_and_different_instances_isolated
run_test "9. F4-P2: probe TCP consciente (127.0.0.1, 0.0.0.0, :: dual-stack, ::1, :: V6ONLY, porta livre e sem IPv6)" test_f4_p2_host_port_probes_ipv4_and_ipv6
run_test "10. F4-R1: readiness preciso (02-create-app-role ERRO, bootstrap incompleto, <3 roles, Redis, HTTP)" test_f4_r1_readiness_checks_and_failure_modes
run_test "11. READ-ONLY & ESTÁTICO: snapshots de status/doctor inalterados e guardas anti-eval/set-x/prune/publish=" test_status_and_doctor_are_strictly_readonly_and_static_guards

printf '\n=== Resumo da Suíte (test-infra R1) ===\n'
printf 'Total executados : %d\n' "$TESTS_RUN"
printf 'Aprovados        : %d\n' "$TESTS_PASSED"
printf 'Falhas           : %d\n' "$TESTS_FAILED"

if (( TESTS_FAILED > 0 )); then
  exit 1
fi
exit 0
