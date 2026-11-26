#!/usr/bin/env bash
# ==============================================================================
# Rewit — Infraestrutura Descartável de Testes Backend no Linux (scripts/test-infra.sh)
#
# Provisiona PostgreSQL 18 + PostGIS 3.6 (com as três roles de runtime via
# infrastructure/postgres/init), Redis 8 Alpine e SeaweedFS 4.47 (S3) em
# containers efêmeros publicados exclusivamente em 127.0.0.1, com segredos
# gerados por CSPRNG (python3 secrets) nunca expostos em argv, logs ou arquivos.
#
# Subcomandos:
#   run [opções] [-- <argumentos Maven...>]  Provisiona, executa ./mvnw e limpa
#   down [opções]                            Remove seletivamente a instância
#   status [opções]                          Lista recursos (READ-ONLY)
#   doctor [opções]                          Diagnostica ambiente (READ-ONLY)
#
# Códigos de saída contratuais:
#   0   — Sucesso
#   2   — Erro de uso ou argumentos inválidos (inclui TEST_INFRA_RUNTIME inválido)
#   3   — Bloqueio de segurança ou conflito de recursos/portas/ownership
#   4   — Erro de dependência, runtime, daemon, timeout ou falha operacional
#   130 — Interrupção por SIGINT (após encerramento do grupo Maven e cleanup)
#   143 — Encerramento por SIGTERM (após encerramento do grupo Maven e cleanup)
#   *   — Em `run`, quando a execução chega ao Maven e o cleanup conclui sem erro,
#         o código de saída original do Maven é preservado.
# ==============================================================================

set -Eeuo pipefail

# Códigos de saída contratuais
readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_BLOCKED=3
readonly EXIT_ENV=4
readonly EXIT_SIGINT=130
readonly EXIT_SIGTERM=143

# Versão mínima declarada de Bash
readonly MIN_BASH_MAJOR=4
readonly MIN_BASH_MINOR=4

# Timeouts operacionais internos
readonly RUNTIME_CMD_TIMEOUT_SECONDS=30
readonly MAVEN_TERM_GRACE_TENTHS=30

# Raiz do repositório derivada da localização deste script (não depende de Git)
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"

# Contrato canônico equivalente a scripts/test-infra.ps1 e docker-compose.yml
readonly IMAGE_POSTGRES="postgis/postgis:18-3.6"
readonly IMAGE_REDIS="redis:8-alpine"
readonly IMAGE_STORAGE="chrislusf/seaweedfs:4.47"

readonly LABEL_OWNER_KEY="rewit.test-infra"
readonly LABEL_SCRIPT_KEY="rewit.test-infra.script"
readonly LABEL_RUN_ID_KEY="rewit.test-infra.run-id"
readonly LABEL_SCRIPT_VAL="scripts/test-infra.sh"

readonly DB_NAME="rewit_test"
readonly DB_MIGRATION_USER="rewit_migrator"
readonly DB_APP_USER="rewit_app"
readonly DB_MEDIA_USER="rewit_media"
readonly DB_MOD_USER="rewit_mod"
readonly STORAGE_BUCKET="rewit-test"

# Portas reservadas: desenvolvimento, external-test e API local
readonly RESERVED_PORTS=(5432 5433 6379 8333 8888 9333 15432 16379 18333 8080 8081)

# Opções padrão
INSTANCE="local"
INSTANCE_EXPLICIT=0
POSTGRES_PORT=35432
REDIS_PORT=36379
STORAGE_PORT=38333
READY_TIMEOUT=180
MAVEN_ARGS=()

# Estado de runtime e ciclo de vida do comando `run`
SELECTED_RUNTIME=""
RUNTIME_NOTES=()
DOCKER_DIAG=""
PODMAN_DIAG=""

RUN_ID=""
CREATED_CONTAINER_IDS=()
PODMAN_STAGING_DIR=""
RUN_ACTIVE=0
CLEANUP_DONE=0
CLEANUP_FAILED=0
PG_CONTAINER_STILL_PRESENT=0
SCRIPT_PGID=""
MAVEN_PID=""
MAVEN_PGID=""
MAVEN_REAPED=0
RECEIVED_SIGNAL=""

# Nomes de containers da instância atual
NAME_POSTGRES=""
NAME_REDIS=""
NAME_STORAGE=""

# ------------------------------------------------------------------------------
# Mensagens e helpers básicos
# ------------------------------------------------------------------------------

msg_info()    { printf '%s\n' "$*"; }
msg_ok()      { printf '[OK] %s\n' "$*"; }
msg_warn()    { printf '[AVISO] %s\n' "$*" >&2; }
msg_blocked() { printf '[BLOQUEADO] %s\n' "$*" >&2; }
msg_err()     { printf 'ERRO: %s\n' "$*" >&2; }

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

matches_c_regex() {
  local value="$1"
  local pattern="$2"
  local LC_ALL=C
  [[ "$value" =~ $pattern ]]
}

# ------------------------------------------------------------------------------
# Ajuda CLI (PT-BR)
# ------------------------------------------------------------------------------

print_usage() {
  cat <<'EOF'
Uso:
  ./scripts/test-infra.sh <subcomando> [opções] [-- <argumentos Maven...>]

Subcomandos:
  run [opções] [-- <argumentos Maven...>]
      Provisiona containers descartáveis (PostgreSQL/PostGIS + 3 roles, Redis,
      SeaweedFS S3) em 127.0.0.1 com segredos CSPRNG em memória, injeta TEST_*
      exclusivamente no processo Maven (em sessão/process group isolado via
      setsid), executa ./mvnw (padrão: clean test) em backend/ e remove
      seletivamente apenas os containers e o staging temporário desta execução.

  down [opções]
      Inspeciona e valida integralmente todos os containers da instância (por
      nome esperado e por rótulo rewit.test-infra=<instance>) antes de remover
      seletivamente apenas seus IDs e volumes anônimos (-f -v). Bloqueia sem
      remover nada em caso de conflito (código 3) ou erro de inspect (código 4).

  status [opções]
      Lista em modo estritamente READ-ONLY os containers da infraestrutura de
      teste, exibindo instância, nome, estado, runtime, portas e ownership.
      Nunca exibe variáveis de ambiente ou credenciais.

  doctor [opções]
      Diagnostica em modo estritamente READ-ONLY o ambiente Linux: runtime
      (docker/podman), acesso ao daemon, python3, setsid, java, backend/mvnw,
      scripts de provisionamento PostgreSQL e disponibilidade das portas.

Opções comuns:
  --instance <slug>          Identificador da instância (^[a-z0-9]{1,20}$, padrão: local)
  --postgres-port <porta>    Porta loopback do PostgreSQL (1024..65535, padrão: 35432)
  --redis-port <porta>       Porta loopback do Redis (1024..65535, padrão: 36379)
  --storage-port <porta>     Porta loopback do SeaweedFS S3 (1024..65535, padrão: 38333)
  --ready-timeout <segundos> Timeout de readiness em segundos (10..900, padrão: 180)
  -h, --help                 Exibe esta ajuda e encerra com código 0

Variável de seleção de runtime:
  TEST_INFRA_RUNTIME=docker|podman
      Opcional. Quando ausente, detecta deterministicamente docker (se acessível)
      e recorre a podman caso docker não esteja disponível ou acessível.

Códigos de saída:
  0    Sucesso
  2    Erro de uso, opção inválida ou TEST_INFRA_RUNTIME inválido
  3    Bloqueio de segurança ou conflito de recursos/portas/ownership
  4    Erro de dependência, runtime, daemon, timeout ou falha operacional/cleanup
  130  Interrompido por SIGINT (após encerramento do grupo Maven e cleanup)
  143  Encerrado por SIGTERM (após encerramento do grupo Maven e cleanup)
  *    Em `run`, o código de saída do Maven é preservado quando o cleanup passa.
EOF
}

# ------------------------------------------------------------------------------
# Validação de Bash, dependências básicas, argumentos, portas e nomes
# ------------------------------------------------------------------------------

check_bash_version() {
  if [[ -z "${BASH_VERSINFO[0]:-}" ]] \
    || (( BASH_VERSINFO[0] < MIN_BASH_MAJOR )) \
    || (( BASH_VERSINFO[0] == MIN_BASH_MAJOR && BASH_VERSINFO[1] < MIN_BASH_MINOR )); then
    die_env "Bash ${MIN_BASH_MAJOR}.${MIN_BASH_MINOR} ou superior é obrigatório (encontrado: ${BASH_VERSION:-desconhecido})."
  fi
}

check_python3() {
  if ! command -v python3 >/dev/null 2>&1; then
    die_env "Dependência obrigatória não encontrada no PATH: python3 (necessário para CSPRNG, parsing JSON e probes TCP/HTTP)."
  fi
}

check_setsid() {
  if ! command -v setsid >/dev/null 2>&1; then
    die_env "Dependência obrigatória não encontrada no PATH: setsid (necessário para isolar o process group do Maven)."
  fi
  if ! setsid sh -c 'exit 0' >/dev/null 2>&1; then
    die_env "Utilitário 'setsid' presente no PATH, mas incompatível ou sem permissão de execução."
  fi
}

validate_instance() {
  local inst="$1"
  local re='^[abcdefghijklmnopqrstuvwxyz0123456789]{1,20}$'
  if ! matches_c_regex "$inst" "$re"; then
    die_usage "Valor inválido para --instance: '${inst}'. Use apenas letras ASCII minúsculas e dígitos (^[a-z0-9]{1,20}$)."
  fi
}

validate_tcp_port_syntax() {
  local opt="$1"
  local val="$2"
  local re='^[0-9]+$'
  if ! matches_c_regex "$val" "$re"; then
    die_usage "Porta inválida em ${opt}: '${val}'. Informe um inteiro TCP entre 1024 e 65535."
  fi
  local num=$((10#$val))
  if (( num < 1024 || num > 65535 )); then
    die_usage "Porta fora do intervalo permitido em ${opt}: ${num} (permitido: 1024..65535)."
  fi
}

validate_timeout() {
  local val="$1"
  local re='^[0-9]+$'
  if ! matches_c_regex "$val" "$re"; then
    die_usage "Timeout inválido em --ready-timeout: '${val}'. Informe um inteiro entre 10 e 900."
  fi
  local num=$((10#$val))
  if (( num < 10 || num > 900 )); then
    die_usage "Timeout fora do intervalo permitido em --ready-timeout: ${num} (permitido: 10..900)."
  fi
}

is_reserved_port() {
  local port="$1"
  local rp
  for rp in "${RESERVED_PORTS[@]}"; do
    if (( port == rp )); then
      return 0
    fi
  done
  return 1
}

validate_ports_policy() {
  if (( POSTGRES_PORT == REDIS_PORT || POSTGRES_PORT == STORAGE_PORT || REDIS_PORT == STORAGE_PORT )); then
    die_blocked "As portas de PostgreSQL (${POSTGRES_PORT}), Redis (${REDIS_PORT}) e storage (${STORAGE_PORT}) precisam ser distintas."
  fi

  local reserved_hit=()
  local p
  for p in "$POSTGRES_PORT" "$REDIS_PORT" "$STORAGE_PORT"; do
    if is_reserved_port "$p"; then
      reserved_hit+=("$p")
    fi
  done
  if (( ${#reserved_hit[@]} > 0 )); then
    die_blocked "Porta(s) reservada(s) para desenvolvimento, external-test ou API local recusada(s): ${reserved_hit[*]}. Escolha outras com --postgres-port/--redis-port/--storage-port."
  fi
}

init_instance_names() {
  NAME_POSTGRES="rewit-testinfra-${INSTANCE}-postgres"
  NAME_REDIS="rewit-testinfra-${INSTANCE}-redis"
  NAME_STORAGE="rewit-testinfra-${INSTANCE}-storage"
}

is_expected_instance_name() {
  local candidate="$1"
  [[ "$candidate" == "$NAME_POSTGRES" || "$candidate" == "$NAME_REDIS" || "$candidate" == "$NAME_STORAGE" ]]
}

parse_common_args() {
  local subcmd="$1"
  shift

  local inst_set=0 pg_set=0 rd_set=0 st_set=0 to_set=0

  while (( $# > 0 )); do
    case "$1" in
      -h|--help)
        print_usage
        exit "$EXIT_OK"
        ;;
      --)
        if [[ "$subcmd" != "run" ]]; then
          die_usage "Separador '--' e argumentos Maven só são aceitos no subcomando 'run'."
        fi
        shift
        MAVEN_ARGS=("$@")
        break
        ;;
      --instance)
        (( inst_set == 0 )) || die_usage "Opção --instance informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --instance exige um valor."
        INSTANCE="$2"; inst_set=1; INSTANCE_EXPLICIT=1; shift 2
        ;;
      --instance=*)
        (( inst_set == 0 )) || die_usage "Opção --instance informada mais de uma vez."
        INSTANCE="${1#--instance=}"; inst_set=1; INSTANCE_EXPLICIT=1; shift
        ;;
      --postgres-port)
        (( pg_set == 0 )) || die_usage "Opção --postgres-port informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --postgres-port exige um valor."
        POSTGRES_PORT="$2"; pg_set=1; shift 2
        ;;
      --postgres-port=*)
        (( pg_set == 0 )) || die_usage "Opção --postgres-port informada mais de uma vez."
        POSTGRES_PORT="${1#--postgres-port=}"; pg_set=1; shift
        ;;
      --redis-port)
        (( rd_set == 0 )) || die_usage "Opção --redis-port informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --redis-port exige um valor."
        REDIS_PORT="$2"; rd_set=1; shift 2
        ;;
      --redis-port=*)
        (( rd_set == 0 )) || die_usage "Opção --redis-port informada mais de uma vez."
        REDIS_PORT="${1#--redis-port=}"; rd_set=1; shift
        ;;
      --storage-port)
        (( st_set == 0 )) || die_usage "Opção --storage-port informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --storage-port exige um valor."
        STORAGE_PORT="$2"; st_set=1; shift 2
        ;;
      --storage-port=*)
        (( st_set == 0 )) || die_usage "Opção --storage-port informada mais de uma vez."
        STORAGE_PORT="${1#--storage-port=}"; st_set=1; shift
        ;;
      --ready-timeout)
        (( to_set == 0 )) || die_usage "Opção --ready-timeout informada mais de uma vez."
        (( $# >= 2 )) || die_usage "Opção --ready-timeout exige um valor."
        READY_TIMEOUT="$2"; to_set=1; shift 2
        ;;
      --ready-timeout=*)
        (( to_set == 0 )) || die_usage "Opção --ready-timeout informada mais de uma vez."
        READY_TIMEOUT="${1#--ready-timeout=}"; to_set=1; shift
        ;;
      *)
        die_usage "Opção ou argumento desconhecido em '${subcmd}': $1"
        ;;
    esac
  done

  validate_instance "$INSTANCE"
  validate_tcp_port_syntax "--postgres-port" "$POSTGRES_PORT"
  validate_tcp_port_syntax "--redis-port" "$REDIS_PORT"
  validate_tcp_port_syntax "--storage-port" "$STORAGE_PORT"
  validate_timeout "$READY_TIMEOUT"

  POSTGRES_PORT=$((10#$POSTGRES_PORT))
  REDIS_PORT=$((10#$REDIS_PORT))
  STORAGE_PORT=$((10#$STORAGE_PORT))
  READY_TIMEOUT=$((10#$READY_TIMEOUT))

  if [[ "$subcmd" == "run" && ${#MAVEN_ARGS[@]} -eq 0 ]]; then
    MAVEN_ARGS=("clean" "test")
  fi

  init_instance_names
}

# ------------------------------------------------------------------------------
# Detecção determinística e validação de runtime (docker / podman)
# ------------------------------------------------------------------------------

probe_single_runtime() {
  local rt="$1"
  local diag_var="$2"
  local out="" rc=0

  if ! command -v "$rt" >/dev/null 2>&1; then
    printf -v "$diag_var" '%s' "ausente no PATH"
    return 1
  fi

  out="$("$rt" info 2>&1)" || rc=$?
  if (( rc == 0 )); then
    local ver=""
    ver="$("$rt" --version 2>&1 | head -n 1 || true)"
    printf -v "$diag_var" '%s' "acessível (${ver:-versão desconhecida})"
    return 0
  fi

  local low_out=""
  local LC_ALL=C
  low_out="${out,,}"
  if [[ "$low_out" == *"permission denied"* || "$low_out" == *"got permission denied"* ]]; then
    printf -v "$diag_var" '%s' "instalado, mas sem permissão para conectar ao daemon/socket (permission denied — não use sudo automaticamente; verifique associação ao grupo ou utilize podman)"
  else
    local first_line=""
    first_line="$(printf '%s' "$out" | head -n 1)"
    printf -v "$diag_var" '%s' "instalado, mas daemon inacessível (código ${rc}: ${first_line:-sem detalhes})"
  fi
  return 1
}

resolve_runtime() {
  local explicit="${TEST_INFRA_RUNTIME:-}"
  SELECTED_RUNTIME=""
  RUNTIME_NOTES=()
  DOCKER_DIAG=""
  PODMAN_DIAG=""

  if [[ -n "$explicit" ]]; then
    case "$explicit" in
      docker)
        if probe_single_runtime "docker" DOCKER_DIAG; then
          SELECTED_RUNTIME="docker"
          return 0
        fi
        return 1
        ;;
      podman)
        if probe_single_runtime "podman" PODMAN_DIAG; then
          SELECTED_RUNTIME="podman"
          return 0
        fi
        return 1
        ;;
      *)
        die_usage "Valor inválido em TEST_INFRA_RUNTIME: '${explicit}'. Valores permitidos: docker ou podman."
        ;;
    esac
  fi

  # Autodetecção determinística: docker primeiro; se indisponível/sem permissão, avalia podman
  if probe_single_runtime "docker" DOCKER_DIAG; then
    SELECTED_RUNTIME="docker"
    probe_single_runtime "podman" PODMAN_DIAG || true
    return 0
  fi

  if probe_single_runtime "podman" PODMAN_DIAG; then
    SELECTED_RUNTIME="podman"
    RUNTIME_NOTES+=("docker indisponível (${DOCKER_DIAG}); selecionado podman automaticamente (${PODMAN_DIAG}).")
    return 0
  fi

  return 1
}

require_runtime() {
  if ! resolve_runtime; then
    if [[ -n "${TEST_INFRA_RUNTIME:-}" ]]; then
      if [[ "$TEST_INFRA_RUNTIME" == "docker" ]]; then
        die_env "Runtime explicitamente solicitado 'docker' indisponível: ${DOCKER_DIAG}"
      else
        die_env "Runtime explicitamente solicitado 'podman' indisponível: ${PODMAN_DIAG}"
      fi
    fi
    die_env "Nenhum runtime de containers acessível. docker: ${DOCKER_DIAG} | podman: ${PODMAN_DIAG}"
  fi
}

# ------------------------------------------------------------------------------
# Inspeção segura de containers (F4-C1: PRESENT | ABSENT | ERROR)
# ------------------------------------------------------------------------------

# Inspeciona um único container por nome ou ID usando subprocess com timeout e
# validação independente de ausência via `ps -a`, jamais convertendo erros de
# daemon, permissão, timeout ou JSON malformado em ABSENT.
# Nunca lê nem imprime Config.Env ou segredos.
# Saída em stdout:
#   PRESENT|<id>|<nome_sem_barra>|<status>|<owner_label>|<run_id_label>
#   ABSENT
#   ERROR|<motivo_resumido>
inspect_container_metadata() {
  local ref="$1"
  python3 - "$SELECTED_RUNTIME" "$ref" "$LABEL_OWNER_KEY" "$LABEL_RUN_ID_KEY" "$RUNTIME_CMD_TIMEOUT_SECONDS" <<'PYEOF'
import json, subprocess, sys

rt, ref, owner_key, run_id_key, timeout_s = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])

def clean_line(text):
    line = (text or "").strip().splitlines()
    return (line[0] if line else "sem detalhes").replace("|", "/")[:220]

def confirm_absent_via_ps():
    try:
        ps_res = subprocess.run(
            [rt, "ps", "-a", "--format", "{{.ID}}|{{.Names}}"],
            capture_output=True, text=True, timeout=timeout_s
        )
    except subprocess.TimeoutExpired:
        return "ERROR|timeout ao confirmar ausência via ps -a"
    except Exception as exc:
        return f"ERROR|exceção ao confirmar ausência via ps -a: {clean_line(str(exc))}"

    if ps_res.returncode != 0:
        return f"ERROR|falha ao confirmar ausência via ps -a (código {ps_res.returncode}: {clean_line(ps_res.stderr)})"

    ref_norm = ref.lstrip("/")
    for raw_line in ps_res.stdout.splitlines():
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        parts = raw_line.split("|", 1)
        cid = parts[0].strip()
        names_field = parts[1].strip() if len(parts) > 1 else ""
        names = [n.strip().lstrip("/") for n in names_field.split(",") if n.strip()]
        if ref_norm in names:
            return f"ERROR|container '{ref_norm}' listado em ps -a, mas inspect falhou"
        if cid and (cid == ref_norm or (len(ref_norm) >= 12 and cid.startswith(ref_norm)) or (len(cid) >= 12 and ref_norm.startswith(cid))):
            return f"ERROR|container '{ref_norm}' listado por ID em ps -a, mas inspect falhou"
    return "ABSENT"

try:
    res = subprocess.run(
        [rt, "container", "inspect", ref],
        capture_output=True, text=True, timeout=timeout_s
    )
except subprocess.TimeoutExpired:
    print(f"ERROR|timeout ({timeout_s}s) no inspect de '{ref}'")
    sys.exit(0)
except Exception as exc:
    print(f"ERROR|exceção no inspect de '{ref}': {clean_line(str(exc))}")
    sys.exit(0)

if res.returncode != 0:
    low_err = (res.stderr or "").lower()
    low_out = (res.stdout or "").lower()
    combined = low_err + "\n" + low_out

    fatal_markers = (
        "permission denied", "cannot connect", "connection refused",
        "dial unix", "timed out", "timeout", "is the docker daemon running",
        "socket", "unable to connect"
    )
    if any(m in combined for m in fatal_markers):
        print(f"ERROR|falha de comunicação/permissão no inspect de '{ref}' (código {res.returncode}: {clean_line(res.stderr)})")
        sys.exit(0)

    missing_markers = (
        "no such container", "no such object", "no container with name or id",
        "not found"
    )
    if any(m in combined for m in missing_markers):
        print(confirm_absent_via_ps())
        sys.exit(0)

    print(f"ERROR|inspect de '{ref}' falhou (código {res.returncode}: {clean_line(res.stderr)})")
    sys.exit(0)

if not (res.stdout or "").strip():
    print(f"ERROR|inspect de '{ref}' retornou saída vazia")
    sys.exit(0)

try:
    data = json.loads(res.stdout)
except Exception as exc:
    print(f"ERROR|resposta JSON malformada no inspect de '{ref}': {clean_line(str(exc))}")
    sys.exit(0)

if isinstance(data, list):
    if len(data) == 0:
        print(f"ERROR|inspect de '{ref}' retornou lista JSON vazia com código 0")
        sys.exit(0)
    obj = data[0]
elif isinstance(data, dict):
    obj = data
else:
    print(f"ERROR|estrutura JSON inesperada no inspect de '{ref}'")
    sys.exit(0)

if not isinstance(obj, dict):
    print(f"ERROR|objeto de container inválido no inspect de '{ref}'")
    sys.exit(0)

cid = str(obj.get("Id") or "").strip()
name = str(obj.get("Name") or "").strip().lstrip("/")
state_obj = obj.get("State")
if not isinstance(state_obj, dict):
    print(f"ERROR|campo State ausente ou inválido no inspect de '{ref}'")
    sys.exit(0)
status = str(state_obj.get("Status") or "unknown").strip()
cfg = obj.get("Config")
if cfg is not None and not isinstance(cfg, dict):
    print(f"ERROR|campo Config inválido no inspect de '{ref}'")
    sys.exit(0)
labels = (cfg or {}).get("Labels")
if labels is not None and not isinstance(labels, dict):
    print(f"ERROR|campo Config.Labels inválido no inspect de '{ref}'")
    sys.exit(0)
labels = labels or {}

if not cid or not name:
    print(f"ERROR|Id ou Name ausente no inspect de '{ref}'")
    sys.exit(0)

owner = str(labels.get(owner_key) or "").replace("|", "/")
run_id = str(labels.get(run_id_key) or "").replace("|", "/")
print(f"PRESENT|{cid}|{name}|{status}|{owner}|{run_id}")
PYEOF
}

# ------------------------------------------------------------------------------
# Verificação de portas publicadas compatível com Docker e Podman (F4-D1)
# ------------------------------------------------------------------------------

# Consulta todos os containers (parados e em execução) via `ps -a -q` +
# `container inspect <ids...>` (JSON estruturado suportado por Docker e Podman,
# sem usar `--filter publish=`).
# Verifica tanto HostConfig.PortBindings quanto NetworkSettings.Ports, cobrindo
# múltiplos bindings, 0.0.0.0, faixas de porta, IPv4 e IPv6, e abortando com
# código 4 em erro de runtime ou JSON malformado.
# Emite em stdout linhas: CONFLICT|<porta>|<nome1,nome2,...>
find_containers_publishing_ports() {
  local out="" rc=0
  out="$(
    python3 - "$SELECTED_RUNTIME" "$RUNTIME_CMD_TIMEOUT_SECONDS" "$@" <<'PYEOF'
import json, re, subprocess, sys

rt = sys.argv[1]
timeout_s = int(sys.argv[2])
target_ports = [int(p) for p in sys.argv[3:]]

def fail(msg):
    sys.stderr.write(msg + "\n")
    sys.exit(4)

def clean_line(text):
    lines = (text or "").strip().splitlines()
    return lines[0][:220] if lines else "sem detalhes"

try:
    ps_res = subprocess.run(
        [rt, "ps", "-a", "-q"],
        capture_output=True, text=True, timeout=timeout_s
    )
except subprocess.TimeoutExpired:
    fail(f"Timeout ({timeout_s}s) ao listar IDs de containers em '{rt} ps -a -q'.")
except Exception as exc:
    fail(f"Falha ao executar '{rt} ps -a -q': {clean_line(str(exc))}")

if ps_res.returncode != 0:
    fail(f"Falha ao listar containers em '{rt} ps -a -q' (código {ps_res.returncode}: {clean_line(ps_res.stderr)}).")

ids = [line.strip() for line in ps_res.stdout.splitlines() if line.strip()]
if not ids:
    sys.exit(0)

# Conflitos acumulados por porta alvo -> lista ordenada de nomes sem duplicata
conflicts = {p: [] for p in target_ports}

def ip_conflicts_with_ipv4_loopback(host_ip):
    ip = (host_ip or "").strip()
    if ip.startswith("[") and ip.endswith("]"):
        ip = ip[1:-1]
    ip_low = ip.lower()
    # Vazio, wildcard IPv4 (0.0.0.0), loopback IPv4 (127.0.0.1), wildcard dual-stack (::)
    # e formas IPv4-mapped conflitam com publicação em 127.0.0.1.
    # IPv6 loopback exclusivo (::1) ou IPs de outras interfaces não conflitam com 127.0.0.1.
    return ip_low in (
        "", "*", "0.0.0.0", "127.0.0.1", "::",
        "::ffff:0.0.0.0", "::ffff:127.0.0.1"
    )

def parse_port_range(raw_port):
    s = raw_port.strip()
    if re.fullmatch(r"[0-9]+", s):
        val = int(s)
        if not (1 <= val <= 65535):
            fail(f"HostPort fora da faixa TCP válida no inspect: '{raw_port}'.")
        return val, val
    m = re.fullmatch(r"([0-9]+)-([0-9]+)", s)
    if m:
        p1, p2 = int(m.group(1)), int(m.group(2))
        if not (1 <= p1 <= p2 <= 65535):
            fail(f"Faixa HostPort inválida no inspect: '{raw_port}'.")
        return p1, p2
    fail(f"Formato HostPort malformado no inspect: '{raw_port}'.")

def inspect_port_map(port_map, cname):
    if port_map is None:
        return
    if not isinstance(port_map, dict):
        fail(f"Estrutura de portas malformada no inspect do container '{cname}'.")
    for port_key, bindings in port_map.items():
        key_str = str(port_key).lower()
        if "/" in key_str:
            proto = key_str.rsplit("/", 1)[1]
            if proto != "tcp":
                continue
        if bindings is None:
            continue
        if not isinstance(bindings, list):
            fail(f"Lista de bindings malformada para '{port_key}' no container '{cname}'.")
        for b in bindings:
            if not isinstance(b, dict):
                fail(f"Entrada de binding malformada para '{port_key}' no container '{cname}'.")
            host_ip = str(b.get("HostIp") if "HostIp" in b else (b.get("host_ip") or ""))
            host_port_val = b.get("HostPort") if "HostPort" in b else b.get("host_port")
            if host_port_val is None:
                continue
            host_port_str = str(host_port_val).strip()
            if not host_port_str:
                continue
            if "range" in b and b["range"] is not None:
                try:
                    rng = int(b["range"])
                    p1 = int(host_port_str)
                    if rng < 1 or not (1 <= p1 <= 65535):
                        fail(f"Campo range/host_port inválido no container '{cname}'.")
                    p2 = p1 + rng - 1
                except ValueError:
                    fail(f"Campo range/host_port não numérico no container '{cname}'.")
            else:
                p1, p2 = parse_port_range(host_port_str)

            if not ip_conflicts_with_ipv4_loopback(host_ip):
                continue

            for tp in target_ports:
                if p1 <= tp <= p2 and cname not in conflicts[tp]:
                    conflicts[tp].append(cname)

batch_size = 100
for idx in range(0, len(ids), batch_size):
    batch = ids[idx:idx + batch_size]
    try:
        ins_res = subprocess.run(
            [rt, "container", "inspect", *batch],
            capture_output=True, text=True, timeout=timeout_s
        )
    except subprocess.TimeoutExpired:
        fail(f"Timeout ({timeout_s}s) ao inspecionar portas dos containers em '{rt} container inspect'.")
    except Exception as exc:
        fail(f"Falha ao executar '{rt} container inspect': {clean_line(str(exc))}")

    if ins_res.returncode != 0:
        fail(f"Falha em '{rt} container inspect' ao consultar portas publicadas (código {ins_res.returncode}: {clean_line(ins_res.stderr)}).")

    if not (ins_res.stdout or "").strip():
        fail(f"Saída vazia em '{rt} container inspect' ao consultar portas publicadas.")

    try:
        data = json.loads(ins_res.stdout)
    except Exception as exc:
        fail(f"JSON malformado em '{rt} container inspect': {clean_line(str(exc))}")

    if not isinstance(data, list):
        fail(f"Estrutura JSON inesperada em '{rt} container inspect' (esperada lista).")

    for obj in data:
        if not isinstance(obj, dict):
            fail(f"Item malformado na resposta de '{rt} container inspect'.")
        cname = str(obj.get("Name") or obj.get("Id") or "desconhecido").strip().lstrip("/")
        host_cfg = obj.get("HostConfig")
        if host_cfg is not None:
            if not isinstance(host_cfg, dict):
                fail(f"Campo HostConfig malformado no container '{cname}'.")
            inspect_port_map(host_cfg.get("PortBindings"), cname)
        net_cfg = obj.get("NetworkSettings")
        if net_cfg is not None:
            if not isinstance(net_cfg, dict):
                fail(f"Campo NetworkSettings malformado no container '{cname}'.")
            inspect_port_map(net_cfg.get("Ports"), cname)

for tp in target_ports:
    if conflicts[tp]:
        print(f"CONFLICT|{tp}|{','.join(conflicts[tp])}")
PYEOF
  )" || rc=$?

  if (( rc != 0 )); then
    die_env "Não foi possível validar portas publicadas no runtime '${SELECTED_RUNTIME}' (código ${rc})."
  fi
  printf '%s' "$out"
}

# ------------------------------------------------------------------------------
# Probe de disponibilidade de porta TCP no host (F4-P2: IPv4 + IPv6 consciente)
# ------------------------------------------------------------------------------

# Avalia disponibilidade da porta TCP no host considerando:
#   1) Bind IPv4 loopback (127.0.0.1:<port>) com SO_REUSEADDR — detecta listeners
#      em 127.0.0.1, 0.0.0.0 e :: dual-stack (IPV6_V6ONLY=0) -> IN_USE_IPV4.
#   2) Bind IPv6 loopback (::1:<port>) com IPV6_V6ONLY=1 — detecta listeners
#      exclusivos em ::1 ou :: (IPV6_V6ONLY=1) -> IPV6_ONLY_IN_USE (não gera
#      falso positivo bloqueante para publicação exclusiva em 127.0.0.1, mas
#      permite emitir aviso explícito).
#   3) Degradação limpa quando IPv6 está desabilitado no host -> FREE_NO_IPV6.
# Observação: verificação instantânea; existe race residual entre probe e bind
# real do container (o runtime é a autoridade final no start).
probe_host_port_availability() {
  local port="$1"
  python3 - "$port" <<'PYEOF'
import errno, socket, sys

port = int(sys.argv[1])

# 1) Probe IPv4 loopback (detecta 127.0.0.1, 0.0.0.0 e :: dual-stack com IPV6_V6ONLY=0)
s4 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s4.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s4.bind(("127.0.0.1", port))
except OSError as exc:
    if exc.errno in (errno.EADDRINUSE, errno.EACCES):
        print("IN_USE_IPV4")
        sys.exit(0)
    print(f"ERROR|erro no probe IPv4 da porta {port}: {exc}")
    sys.exit(0)
finally:
    s4.close()

# 2) Probe IPv6 loopback com IPV6_V6ONLY=1 (detecta ::1 e :: IPv6-only sem falso positivo IPv4)
if not getattr(socket, "has_ipv6", False):
    print("FREE_NO_IPV6")
    sys.exit(0)

s6 = None
try:
    s6 = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    s6.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "IPPROTO_IPV6") and hasattr(socket, "IPV6_V6ONLY"):
        s6.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
    s6.bind(("::1", port))
except OSError as exc:
    no_ipv6_errnos = {
        errno.EAFNOSUPPORT,
        errno.EPROTONOSUPPORT,
        errno.EADDRNOTAVAIL,
        errno.ENETUNREACH,
        errno.EINVAL,
    }
    if exc.errno in no_ipv6_errnos:
        print("FREE_NO_IPV6")
        sys.exit(0)
    if exc.errno in (errno.EADDRINUSE, errno.EACCES):
        print("IPV6_ONLY_IN_USE")
        sys.exit(0)
    print(f"ERROR|erro no probe IPv6 da porta {port}: {exc}")
    sys.exit(0)
finally:
    if s6 is not None:
        s6.close()

print("FREE")
PYEOF
}

# ------------------------------------------------------------------------------
# Helpers de process group do Maven (F4-P1)
# ------------------------------------------------------------------------------

get_current_pgid() {
  python3 -c 'import os; print(os.getpgrp())'
}

get_pid_pgid() {
  local pid="$1"
  python3 - "$pid" <<'PYEOF'
import os, sys
pid = int(sys.argv[1])
try:
    print(os.getpgid(pid))
except ProcessLookupError:
    print("GONE")
except OSError:
    print("ERR")
PYEOF
}

# Conta processos vivos (excluindo estados Z=zombie e X/x=dead) pertencentes ao PGID alvo.
count_live_pgid_processes() {
  local target_pgid="$1"
  python3 - "$target_pgid" <<'PYEOF'
import os, sys

target = int(sys.argv[1])
count = 0
for entry in os.listdir("/proc"):
    if not entry.isdigit():
        continue
    try:
        with open(f"/proc/{entry}/stat", "r", encoding="utf-8", errors="replace") as f:
            stat = f.read()
    except OSError:
        continue
    rparen = stat.rfind(")")
    if rparen < 0:
        continue
    fields = stat[rparen + 2:].split()
    # fields[0]=state, fields[1]=ppid, fields[2]=pgrp
    if len(fields) < 3:
        continue
    state = fields[0]
    if state in ("Z", "X", "x"):
        continue
    try:
        pgrp = int(fields[2])
    except ValueError:
        continue
    if pgrp == target:
        count += 1
print(count)
PYEOF
}

# ------------------------------------------------------------------------------
# Staging temporário fora do repositório para Podman/SELinux (F4-V1)
# ------------------------------------------------------------------------------

prepare_podman_postgres_staging() {
  local src_init_dir="${REPO_ROOT}/infrastructure/postgres/init"
  local src_provision_sql="${REPO_ROOT}/infrastructure/postgres/provision-runtime-role.sql"
  local tmp_base="${TMPDIR:-/tmp}"

  if [[ ! -d "$src_init_dir" || -L "$src_init_dir" ]]; then
    die_env "Diretório de inicialização PostgreSQL ausente ou symlink inesperado: '${src_init_dir}'."
  fi
  if [[ ! -f "$src_provision_sql" || -L "$src_provision_sql" ]]; then
    die_env "Arquivo obrigatório ausente ou symlink inesperado: '${src_provision_sql}'."
  fi

  local staging=""
  staging="$(mktemp -d "${tmp_base%/}/rewit-testinfra-pg-staging.XXXXXX")" \
    || die_env "Falha ao criar diretório temporário de staging para Podman em '${tmp_base}'."
  PODMAN_STAGING_DIR="$staging"

  # Valida que o diretório temporário está estritamente fora do repositório
  python3 - "$REPO_ROOT" "$PODMAN_STAGING_DIR" <<'PYEOF' || die_env "Diretório de staging temporário não pode residir dentro do repositório."
import os, sys
repo = os.path.realpath(sys.argv[1])
stg = os.path.realpath(sys.argv[2])
if stg == repo or stg.startswith(repo + os.sep):
    sys.exit(1)
PYEOF

  mkdir -p "${PODMAN_STAGING_DIR}/init" "${PODMAN_STAGING_DIR}/postgres" \
    || die_env "Falha ao preparar subdiretórios no staging temporário Podman."

  # Copia somente os assets regulares necessários (.sql e .sh em init/ + provision-runtime-role.sql),
  # rejeitando qualquer symlink e nunca copiando .env, chaves ou testes.
  python3 - "$src_init_dir" "$src_provision_sql" "$PODMAN_STAGING_DIR" <<'PYEOF' || die_env "Falha ao copiar/validar assets de provisionamento PostgreSQL para o staging Podman."
import os, shutil, stat, sys

src_init, src_prov, stg = sys.argv[1], sys.argv[2], sys.argv[3]
dst_init = os.path.join(stg, "init")
dst_pg = os.path.join(stg, "postgres")

def fail(msg):
    sys.stderr.write("ERRO: " + msg + "\n")
    sys.exit(1)

if os.path.islink(src_init) or not os.path.isdir(src_init):
    fail(f"Diretório de init inválido ou symlink: {src_init}")

if os.path.islink(src_prov) or not os.path.isfile(src_prov):
    fail(f"Arquivo provision-runtime-role.sql inválido ou symlink: {src_prov}")

copied_init = []
for name in sorted(os.listdir(src_init)):
    src_path = os.path.join(src_init, name)
    if os.path.islink(src_path):
        fail(f"Symlink inesperado em infrastructure/postgres/init: {name}")
    if not os.path.isfile(src_path):
        continue
    if not (name.endswith(".sql") or name.endswith(".sh")):
        continue
    dst_path = os.path.join(dst_init, name)
    shutil.copyfile(src_path, dst_path)
    mode = 0o755 if name.endswith(".sh") else 0o644
    os.chmod(dst_path, mode)
    copied_init.append(name)

for required in ("01-init-postgis.sql", "02-create-app-role.sh"):
    req_dst = os.path.join(dst_init, required)
    if required not in copied_init or not os.path.isfile(req_dst) or os.path.getsize(req_dst) == 0:
        fail(f"Asset obrigatório de inicialização PostgreSQL ausente ou vazio: {required}")

dst_prov = os.path.join(dst_pg, "provision-runtime-role.sql")
shutil.copyfile(src_prov, dst_prov)
os.chmod(dst_prov, 0o644)
if not os.path.isfile(dst_prov) or os.path.getsize(dst_prov) == 0:
    fail("Asset obrigatório provision-runtime-role.sql ausente ou vazio após cópia.")

# Permissões de travessia para o UID do PostgreSQL dentro do container
os.chmod(stg, 0o755)
os.chmod(dst_init, 0o755)
os.chmod(dst_pg, 0o755)

# Verificação final anti-symlink em todo o staging
for root, dirs, files in os.walk(stg, followlinks=False):
    for d in dirs:
        if os.path.islink(os.path.join(root, d)):
            fail(f"Symlink inesperado encontrado no staging: {d}")
    for f in files:
        if os.path.islink(os.path.join(root, f)):
            fail(f"Symlink inesperado encontrado no staging: {f}")

if not (os.stat(os.path.join(dst_init, "02-create-app-role.sh")).st_mode & stat.S_IXUSR):
    fail("02-create-app-role.sh sem permissão executável no staging.")
PYEOF
}

cleanup_podman_staging() {
  if [[ -z "$PODMAN_STAGING_DIR" ]]; then
    return 0
  fi
  if (( PG_CONTAINER_STILL_PRESENT != 0 )); then
    msg_warn "Staging Podman '${PODMAN_STAGING_DIR}' preservado porque o container PostgreSQL que o utiliza não pôde ser removido."
    return 0
  fi
  local dir_to_remove="$PODMAN_STAGING_DIR"
  PODMAN_STAGING_DIR=""
  # Guarda defensiva: só remove diretórios com nosso prefixo esperado fora do repositório
  local base_name
  base_name="$(basename -- "$dir_to_remove")"
  if [[ -d "$dir_to_remove" && "$base_name" == rewit-testinfra-pg-staging.* ]]; then
    if ! rm -rf -- "$dir_to_remove" 2>/dev/null; then
      msg_err "Falha ao remover diretório temporário de staging Podman '${dir_to_remove}'."
      CLEANUP_FAILED=1
    fi
  fi
}

# ------------------------------------------------------------------------------
# Geração de segredos CSPRNG (python3 secrets) — nunca gravados nem impressos
# ------------------------------------------------------------------------------

generate_csprng_hex() {
  local num_bytes="$1"
  python3 - "$num_bytes" <<'PYEOF'
import secrets, sys
sys.stdout.write(secrets.token_hex(int(sys.argv[1])))
PYEOF
}

# ------------------------------------------------------------------------------
# Readiness checks dos três serviços (F4-R1: escopo exato documentado)
# ------------------------------------------------------------------------------

# Comprova apenas que o endpoint HTTP do SeaweedFS responde em 127.0.0.1:<port>/
# (qualquer status HTTP). Não valida autenticação S3 nem pré-existência do bucket;
# o backend usa rewit.minio.auto-create-bucket=true no perfil de teste.
check_storage_http_ready() {
  local port="$1"
  python3 - "$port" <<'PYEOF'
import http.client, sys

port = int(sys.argv[1])
conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
try:
    conn.request("GET", "/")
    resp = conn.getresponse()
    resp.read(256)
    sys.exit(0)
except Exception:
    sys.exit(1)
finally:
    conn.close()
PYEOF
}

wait_services_ready() {
  local pg_id="$1"
  local rd_id="$2"
  local st_id="$3"

  local pg_ready=0 rd_ready=0 st_ready=0
  local start_ts deadline now
  start_ts="$(date +%s)"
  deadline=$((start_ts + READY_TIMEOUT))

  msg_info "Aguardando readiness dos serviços (timeout: ${READY_TIMEOUT}s)..."

  while (( pg_ready == 0 || rd_ready == 0 || st_ready == 0 )); do
    if [[ -n "$RECEIVED_SIGNAL" ]]; then
      return 1
    fi

    # 1) PostgreSQL: verifica processo em execução, ausência de erro em 02-create-app-role,
    #    conclusão do bootstrap (>= 2 ocorrências de 'ready to accept connections') e
    #    existência das 3 roles via psql local (socket Unix trust; autenticação TCP/SCRAM
    #    é validada pelo backend/Flyway ao conectar).
    if (( pg_ready == 0 )); then
      local meta state err_detail
      meta="$(inspect_container_metadata "$pg_id")"
      if [[ "$meta" == ERROR\|* ]]; then
        err_detail="${meta#ERROR|}"
        msg_err "Falha ao inspecionar ${NAME_POSTGRES}: ${err_detail}"
        return 1
      fi
      if [[ "$meta" == "ABSENT" ]]; then
        msg_err "${NAME_POSTGRES} desapareceu durante a inicialização."
        return 1
      fi
      IFS='|' read -r _ _ _ state _ _ <<< "$meta"
      if [[ "$state" != "running" ]]; then
        msg_err "${NAME_POSTGRES} parou durante a inicialização (estado: ${state}). Consulte: ${SELECTED_RUNTIME} logs ${NAME_POSTGRES}"
        return 1
      fi

      local pg_logs=""
      pg_logs="$("$SELECTED_RUNTIME" logs "$pg_id" 2>&1 || true)"
      if [[ "$pg_logs" == *"02-create-app-role: ERRO"* ]]; then
        msg_err "Provisionamento das roles de runtime falhou em ${NAME_POSTGRES}."
        return 1
      fi

      local ready_hits=0
      ready_hits="$(python3 -c 'import sys; print(sys.stdin.read().count("ready to accept connections"))' <<< "$pg_logs")"
      if (( ready_hits >= 2 )); then
        local role_count="" rc_sql=0
        role_count="$("$SELECTED_RUNTIME" exec "$pg_id" psql -U "$DB_MIGRATION_USER" -d "$DB_NAME" -tAc \
          "SELECT count(*) FROM pg_roles WHERE rolname IN ('${DB_APP_USER}', '${DB_MEDIA_USER}', '${DB_MOD_USER}')" 2>/dev/null)" || rc_sql=$?
        role_count="${role_count//[[:space:]]/}"
        if (( rc_sql == 0 )) && [[ "$role_count" == "3" ]]; then
          pg_ready=1
          msg_ok "${NAME_POSTGRES} pronto (bootstrap concluído e 3 roles verificadas via psql local; autenticação TCP/SCRAM será validada pelo backend)."
        fi
      fi
    fi

    # 2) Redis: verifica PING/PONG na configuração efêmera sem senha
    if (( rd_ready == 0 )); then
      local meta state err_detail
      meta="$(inspect_container_metadata "$rd_id")"
      if [[ "$meta" == ERROR\|* ]]; then
        err_detail="${meta#ERROR|}"
        msg_err "Falha ao inspecionar ${NAME_REDIS}: ${err_detail}"
        return 1
      fi
      if [[ "$meta" == "ABSENT" ]]; then
        msg_err "${NAME_REDIS} desapareceu durante a inicialização."
        return 1
      fi
      IFS='|' read -r _ _ _ state _ _ <<< "$meta"
      if [[ "$state" != "running" ]]; then
        msg_err "${NAME_REDIS} parou durante a inicialização (estado: ${state})."
        return 1
      fi

      local pong="" rc_rd=0
      pong="$("$SELECTED_RUNTIME" exec "$rd_id" redis-cli ping 2>/dev/null)" || rc_rd=$?
      pong="${pong//[[:space:]]/}"
      if (( rc_rd == 0 )) && [[ "$pong" == "PONG" ]]; then
        rd_ready=1
        msg_ok "${NAME_REDIS} pronto (PING/PONG verificado sem senha)."
      fi
    fi

    # 3) SeaweedFS S3: verifica disponibilidade HTTP em GET / (sem validar auth S3 ou bucket)
    if (( st_ready == 0 )); then
      local meta state err_detail
      meta="$(inspect_container_metadata "$st_id")"
      if [[ "$meta" == ERROR\|* ]]; then
        err_detail="${meta#ERROR|}"
        msg_err "Falha ao inspecionar ${NAME_STORAGE}: ${err_detail}"
        return 1
      fi
      if [[ "$meta" == "ABSENT" ]]; then
        msg_err "${NAME_STORAGE} desapareceu durante a inicialização."
        return 1
      fi
      IFS='|' read -r _ _ _ state _ _ <<< "$meta"
      if [[ "$state" != "running" ]]; then
        msg_err "${NAME_STORAGE} parou durante a inicialização (estado: ${state})."
        return 1
      fi

      if check_storage_http_ready "$STORAGE_PORT"; then
        st_ready=1
        msg_ok "${NAME_STORAGE} pronto (disponibilidade HTTP verificada em 127.0.0.1:${STORAGE_PORT}; auth S3 e bucket são validados pelo backend)."
      fi
    fi

    if (( pg_ready == 0 || rd_ready == 0 || st_ready == 0 )); then
      now="$(date +%s)"
      if (( now >= deadline )); then
        local pending=()
        (( pg_ready == 0 )) && pending+=("$NAME_POSTGRES")
        (( rd_ready == 0 )) && pending+=("$NAME_REDIS")
        (( st_ready == 0 )) && pending+=("$NAME_STORAGE")
        msg_err "Tempo esgotado (${READY_TIMEOUT}s) aguardando readiness de: ${pending[*]}"
        return 1
      fi
      sleep 1
    fi
  done

  return 0
}

# ------------------------------------------------------------------------------
# Limpeza seletiva para o subcomando `run` (somente IDs desta execução + staging)
# ------------------------------------------------------------------------------

cleanup_run_containers() {
  if (( RUN_ACTIVE == 0 || CLEANUP_DONE == 1 )); then
    cleanup_podman_staging
    return 0
  fi
  CLEANUP_DONE=1

  local ids_to_remove=()
  local id
  for id in "${CREATED_CONTAINER_IDS[@]}"; do
    [[ -n "$id" ]] && ids_to_remove+=("$id")
  done

  # Adota também eventual container parcialmente criado (ex.: estado 'created')
  # somente se o rótulo rewit.test-infra.run-id corresponder a ESTA execução.
  if [[ -n "$SELECTED_RUNTIME" && -n "$RUN_ID" ]]; then
    local cname meta cid cname_obs owner_obs run_id_obs already existing
    for cname in "$NAME_POSTGRES" "$NAME_REDIS" "$NAME_STORAGE"; do
      meta="$(inspect_container_metadata "$cname" 2>/dev/null || printf 'ERROR|falha interna\n')"
      if [[ "$meta" == PRESENT\|* ]]; then
        IFS='|' read -r _ cid cname_obs _ owner_obs run_id_obs <<< "$meta"
        if [[ "$cname_obs" == "$cname" && "$owner_obs" == "$INSTANCE" && "$run_id_obs" == "$RUN_ID" && -n "$cid" ]]; then
          already=0
          for existing in "${ids_to_remove[@]}"; do
            if [[ "$existing" == "$cid" ]]; then
              already=1
              break
            fi
          done
          (( already == 0 )) && ids_to_remove+=("$cid")
        fi
      fi
    done
  fi

  if (( ${#ids_to_remove[@]} > 0 )); then
    msg_info "Encerrando seletivamente ${#ids_to_remove[@]} container(s) criado(s) por esta execução (instância '${INSTANCE}')..."
    for id in "${ids_to_remove[@]}"; do
      meta="$(inspect_container_metadata "$id" 2>/dev/null || printf 'ERROR|falha no inspect durante cleanup\n')"
      if [[ "$meta" == "ABSENT" ]]; then
        continue
      fi
      if [[ "$meta" == ERROR\|* ]]; then
        msg_err "Falha ao revalidar ownership de '${id}' durante cleanup: ${meta#ERROR|}"
        CLEANUP_FAILED=1
        PG_CONTAINER_STILL_PRESENT=1
        continue
      fi
      IFS='|' read -r _ cid cname_obs _ owner_obs run_id_obs <<< "$meta"
      if [[ "$owner_obs" != "$INSTANCE" || "$run_id_obs" != "$RUN_ID" ]]; then
        msg_err "Recusada remoção de '${id}': ownership divergente (rótulo '${owner_obs}', run-id '${run_id_obs}')."
        CLEANUP_FAILED=1
        if [[ "$cname_obs" == "$NAME_POSTGRES" ]]; then
          PG_CONTAINER_STILL_PRESENT=1
        fi
        continue
      fi
      if "$SELECTED_RUNTIME" rm -f -v "$cid" >/dev/null 2>&1; then
        msg_ok "Container '${cname_obs}' (${cid:0:12}) removido com seus volumes anônimos."
      else
        msg_err "Falha ao remover container '${cname_obs}' (${cid:0:12})."
        CLEANUP_FAILED=1
        if [[ "$cname_obs" == "$NAME_POSTGRES" ]]; then
          PG_CONTAINER_STILL_PRESENT=1
        fi
      fi
    done
  fi

  # Remove o staging temporário Podman SOMENTE após os containers serem removidos
  cleanup_podman_staging
}

# Encerra toda a árvore do Maven (process group exclusivo criado por setsid)
terminate_maven_process_group() {
  if [[ -z "$MAVEN_PID" ]] || (( MAVEN_REAPED == 1 )); then
    return 0
  fi

  local target_pgid="$MAVEN_PGID"
  if [[ -z "$target_pgid" ]]; then
    local obs_pgid=""
    obs_pgid="$(get_pid_pgid "$MAVEN_PID")"
    if [[ "$obs_pgid" =~ ^[0-9]+$ && "$obs_pgid" != "$SCRIPT_PGID" && "$obs_pgid" -gt 1 ]]; then
      target_pgid="$obs_pgid"
    fi
  fi

  if [[ -n "$target_pgid" && "$target_pgid" =~ ^[0-9]+$ && "$target_pgid" != "$SCRIPT_PGID" && "$target_pgid" -gt 1 ]]; then
    # 1) SIGTERM para todo o process group exclusivo do Maven
    kill -TERM -- "-${target_pgid}" 2>/dev/null || true

    # 2) Aguarda grace period até não restarem processos vivos (não-zombie) no grupo
    local tries=0 live=0
    while (( tries < MAVEN_TERM_GRACE_TENTHS )); do
      live="$(count_live_pgid_processes "$target_pgid")"
      if (( live == 0 )); then
        break
      fi
      sleep 0.1
      tries=$((tries + 1))
    done

    # 3) Fallback SIGKILL para qualquer descendente remanescente do mesmo grupo
    live="$(count_live_pgid_processes "$target_pgid")"
    if (( live > 0 )); then
      msg_warn "Grace period esgotado com ${live} processo(s) ativo(s) no grupo Maven (PGID ${target_pgid}); enviando SIGKILL ao grupo."
      kill -KILL -- "-${target_pgid}" 2>/dev/null || true
      tries=0
      while (( tries < 20 )); do
        live="$(count_live_pgid_processes "$target_pgid")"
        if (( live == 0 )); then
          break
        fi
        sleep 0.1
        tries=$((tries + 1))
      done
    fi
  else
    # Fallback defensivo caso o sinal chegue antes da troca de PGID (nunca sinaliza SCRIPT_PGID)
    if kill -0 "$MAVEN_PID" 2>/dev/null; then
      kill -TERM "$MAVEN_PID" 2>/dev/null || true
      local tries=0
      while kill -0 "$MAVEN_PID" 2>/dev/null && (( tries < MAVEN_TERM_GRACE_TENTHS )); do
        sleep 0.1
        tries=$((tries + 1))
      done
      if kill -0 "$MAVEN_PID" 2>/dev/null; then
        kill -KILL "$MAVEN_PID" 2>/dev/null || true
      fi
    fi
  fi

  wait "$MAVEN_PID" 2>/dev/null || true
  MAVEN_REAPED=1
  MAVEN_PID=""
  MAVEN_PGID=""
}

on_run_signal() {
  local sig="$1"
  RECEIVED_SIGNAL="$sig"
  trap '' INT TERM
  msg_warn "Sinal SIG${sig} recebido; encerrando grupo de processos Maven e limpando containers desta instância..."
  terminate_maven_process_group
  cleanup_run_containers
  trap - EXIT
  if [[ "$sig" == "INT" ]]; then
    exit "$EXIT_SIGINT"
  fi
  exit "$EXIT_SIGTERM"
}

# ------------------------------------------------------------------------------
# Subcomando: RUN
# ------------------------------------------------------------------------------

cmd_run() {
  parse_common_args "run" "$@"
  check_python3
  check_setsid
  validate_ports_policy
  require_runtime

  local note
  for note in "${RUNTIME_NOTES[@]}"; do
    msg_warn "$note"
  done

  # Valida pré-requisitos de arquivos no repositório
  local init_dir="${REPO_ROOT}/infrastructure/postgres/init"
  local provision_dir="${REPO_ROOT}/infrastructure/postgres"
  local mvnw_path="${REPO_ROOT}/backend/mvnw"

  if [[ ! -f "${init_dir}/01-init-postgis.sql" \
     || ! -f "${init_dir}/02-create-app-role.sh" \
     || ! -f "${provision_dir}/provision-runtime-role.sql" ]]; then
    die_env "Scripts obrigatórios de provisionamento PostgreSQL não encontrados em '${provision_dir}'."
  fi
  if [[ ! -x "$mvnw_path" ]]; then
    die_env "Maven Wrapper ausente ou sem permissão executável: '${mvnw_path}'."
  fi
  if ! command -v java >/dev/null 2>&1; then
    die_env "Dependência obrigatória não encontrada no PATH: java."
  fi

  # Verifica conflitos de nomes ANTES de criar qualquer container (distinguindo PRESENT/ABSENT/ERROR)
  local conflicts=()
  local cname meta owner_obs
  for cname in "$NAME_POSTGRES" "$NAME_REDIS" "$NAME_STORAGE"; do
    meta="$(inspect_container_metadata "$cname")"
    if [[ "$meta" == ERROR\|* ]]; then
      die_env "Falha ao verificar container '${cname}' antes do provisionamento: ${meta#ERROR|}"
    fi
    if [[ "$meta" == PRESENT\|* ]]; then
      IFS='|' read -r _ _ _ _ owner_obs _ <<< "$meta"
      if [[ "$owner_obs" == "$INSTANCE" ]]; then
        conflicts+=("Container '${cname}' já existe para a instância '${INSTANCE}' (outra execução pode estar ativa). Encerre com './scripts/test-infra.sh down --instance ${INSTANCE}' ou use outra --instance.")
      else
        conflicts+=("Container '${cname}' já existe e NÃO pertence à instância '${INSTANCE}' (rótulo '${LABEL_OWNER_KEY}' ausente ou divergente). Nada foi removido; escolha outra --instance.")
      fi
    fi
  done

  # Verifica portas publicadas por outros containers (Docker e Podman via ps -a -q + inspect JSON)
  local pub_conflicts_raw=""
  pub_conflicts_raw="$(find_containers_publishing_ports "$POSTGRES_PORT" "$REDIS_PORT" "$STORAGE_PORT")"

  local port_conflicts_map=""
  local line c_port c_names
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ "$line" == CONFLICT\|* ]]; then
      IFS='|' read -r _ c_port c_names <<< "$line"
      conflicts+=("Porta ${c_port} já publicada pelo(s) container(s): ${c_names}.")
      port_conflicts_map+=",${c_port},"
    fi
  done <<< "$pub_conflicts_raw"

  # Verifica disponibilidade das portas TCP no host (IPv4 + IPv6 consciente de IPV6_V6ONLY)
  local port probe_res
  for port in "$POSTGRES_PORT" "$REDIS_PORT" "$STORAGE_PORT"; do
    if [[ "$port_conflicts_map" == *",${port},"* ]]; then
      continue
    fi
    probe_res="$(probe_host_port_availability "$port")"
    case "$probe_res" in
      IN_USE_IPV4)
        conflicts+=("Porta loopback 127.0.0.1:${port} já está em uso no host (listener IPv4 ou IPv6 dual-stack).")
        ;;
      IPV6_ONLY_IN_USE)
        msg_warn "Porta [::1]:${port} em uso por listener IPv6-only no host (não bloqueia publicação exclusiva em 127.0.0.1:${port})."
        ;;
      FREE|FREE_NO_IPV6)
        ;;
      ERROR\|*)
        die_env "Falha no probe de disponibilidade da porta ${port}: ${probe_res#ERROR|}"
        ;;
      *)
        die_env "Resposta inesperada no probe de disponibilidade da porta ${port}: '${probe_res}'."
        ;;
    esac
  done

  if (( ${#conflicts[@]} > 0 )); then
    msg_blocked "Conflito detectado antes do provisionamento; nenhum recurso foi criado:"
    local c
    for c in "${conflicts[@]}"; do
      printf '  - %s\n' "$c" >&2
    done
    exit "$EXIT_BLOCKED"
  fi

  # Registra traps antes de criar staging temporário ou containers
  RUN_ACTIVE=1
  trap 'on_run_signal INT' INT
  trap 'on_run_signal TERM' TERM
  trap 'cleanup_run_containers' EXIT

  # Prepara mounts PostgreSQL:
  # - Docker: bind mounts :ro diretamente sobre o repositório (sem relabel)
  # - Podman: staging temporário descartável em /tmp com :ro,z (nunca aplica :z no repositório!)
  local mount_init_spec=""
  local mount_prov_spec=""
  if [[ "$SELECTED_RUNTIME" == "podman" ]]; then
    prepare_podman_postgres_staging
    mount_init_spec="${PODMAN_STAGING_DIR}/init:/docker-entrypoint-initdb.d:ro,z"
    mount_prov_spec="${PODMAN_STAGING_DIR}/postgres:/opt/rewit/postgres:ro,z"
  else
    mount_init_spec="${init_dir}:/docker-entrypoint-initdb.d:ro"
    mount_prov_spec="${provision_dir}:/opt/rewit/postgres:ro"
  fi

  # Gera identificador único desta execução e os 6 segredos CSPRNG em memória
  RUN_ID="$(generate_csprng_hex 8)"
  local s_pg_pw s_app_pw s_media_pw s_mod_pw s_s3_ak s_s3_sk
  s_pg_pw="$(generate_csprng_hex 24)"
  s_app_pw="$(generate_csprng_hex 24)"
  s_media_pw="$(generate_csprng_hex 24)"
  s_mod_pw="$(generate_csprng_hex 24)"
  s_s3_ak="$(generate_csprng_hex 12)"
  s_s3_sk="$(generate_csprng_hex 24)"

  if [[ "$s_app_pw" == "$s_media_pw" || "$s_app_pw" == "$s_mod_pw" || "$s_media_pw" == "$s_mod_pw" ]]; then
    die_env "Falha na geração independente de segredos CSPRNG."
  fi

  local common_labels=(
    "--label" "${LABEL_OWNER_KEY}=${INSTANCE}"
    "--label" "${LABEL_SCRIPT_KEY}=${LABEL_SCRIPT_VAL}"
    "--label" "${LABEL_RUN_ID_KEY}=${RUN_ID}"
  )

  msg_info "Provisionando infraestrutura descartável (runtime: ${SELECTED_RUNTIME}, instância: '${INSTANCE}')..."

  local pg_id="" rd_id="" st_id="" rc=0

  # 1) PostgreSQL 18 + PostGIS 3.6 (segredos passados exclusivamente via -e NOME, nunca em argv)
  pg_id="$(
    env \
      "POSTGRES_PASSWORD=${s_pg_pw}" \
      "APP_DB_PASSWORD=${s_app_pw}" \
      "MEDIA_DB_PASSWORD=${s_media_pw}" \
      "MOD_DB_PASSWORD=${s_mod_pw}" \
      "$SELECTED_RUNTIME" run -d \
        --name "$NAME_POSTGRES" \
        "${common_labels[@]}" \
        -p "127.0.0.1:${POSTGRES_PORT}:5432" \
        -e "POSTGRES_DB=${DB_NAME}" \
        -e "POSTGRES_USER=${DB_MIGRATION_USER}" \
        -e POSTGRES_PASSWORD \
        -e "APP_DB_USER=${DB_APP_USER}" \
        -e APP_DB_PASSWORD \
        -e "MEDIA_DB_USER=${DB_MEDIA_USER}" \
        -e MEDIA_DB_PASSWORD \
        -e "MOD_DB_USER=${DB_MOD_USER}" \
        -e MOD_DB_PASSWORD \
        -v "$mount_init_spec" \
        -v "$mount_prov_spec" \
        "$IMAGE_POSTGRES"
  )" || rc=$?
  pg_id="${pg_id##*$'\n'}"
  pg_id="${pg_id//[[:space:]]/}"
  if (( rc != 0 )) || [[ -z "$pg_id" ]]; then
    msg_err "Falha ao provisionar ${NAME_POSTGRES} (código ${rc})."
    cleanup_run_containers
    exit "$EXIT_ENV"
  fi
  CREATED_CONTAINER_IDS+=("$pg_id")

  # 2) Redis 8 Alpine (sem senha)
  rc=0
  rd_id="$(
    "$SELECTED_RUNTIME" run -d \
      --name "$NAME_REDIS" \
      "${common_labels[@]}" \
      -p "127.0.0.1:${REDIS_PORT}:6379" \
      "$IMAGE_REDIS"
  )" || rc=$?
  rd_id="${rd_id##*$'\n'}"
  rd_id="${rd_id//[[:space:]]/}"
  if (( rc != 0 )) || [[ -z "$rd_id" ]]; then
    msg_err "Falha ao provisionar ${NAME_REDIS} (código ${rc})."
    cleanup_run_containers
    exit "$EXIT_ENV"
  fi
  CREATED_CONTAINER_IDS+=("$rd_id")

  # 3) SeaweedFS 4.47 (S3; credenciais apenas via -e NOME)
  rc=0
  st_id="$(
    env \
      "AWS_ACCESS_KEY_ID=${s_s3_ak}" \
      "AWS_SECRET_ACCESS_KEY=${s_s3_sk}" \
      "$SELECTED_RUNTIME" run -d \
        --name "$NAME_STORAGE" \
        "${common_labels[@]}" \
        -p "127.0.0.1:${STORAGE_PORT}:8333" \
        -e AWS_ACCESS_KEY_ID \
        -e AWS_SECRET_ACCESS_KEY \
        -e "S3_BUCKET=${STORAGE_BUCKET}" \
        "$IMAGE_STORAGE" mini -dir=/data -s3.port=8333
  )" || rc=$?
  st_id="${st_id##*$'\n'}"
  st_id="${st_id//[[:space:]]/}"
  if (( rc != 0 )) || [[ -z "$st_id" ]]; then
    msg_err "Falha ao provisionar ${NAME_STORAGE} (código ${rc})."
    cleanup_run_containers
    exit "$EXIT_ENV"
  fi
  CREATED_CONTAINER_IDS+=("$st_id")

  # Aguarda readiness de todos os serviços
  if ! wait_services_ready "$pg_id" "$rd_id" "$st_id"; then
    cleanup_run_containers
    if [[ "$RECEIVED_SIGNAL" == "INT" ]]; then
      exit "$EXIT_SIGINT"
    elif [[ "$RECEIVED_SIGNAL" == "TERM" ]]; then
      exit "$EXIT_SIGTERM"
    fi
    exit "$EXIT_ENV"
  fi

  msg_info "Infraestrutura pronta (instância '${INSTANCE}'): PostgreSQL 127.0.0.1:${POSTGRES_PORT}, Redis 127.0.0.1:${REDIS_PORT}, S3 127.0.0.1:${STORAGE_PORT}."
  msg_info "Executando Maven em backend/ em sessão/process group isolado (setsid) com variáveis TEST_* somente no processo filho..."

  # Captura PGID do script atual antes de criar a sessão exclusiva do Maven
  SCRIPT_PGID="$(get_current_pgid)"
  MAVEN_REAPED=0
  MAVEN_PGID=""

  # Injeta variáveis TEST_* exclusivamente no processo Maven (removendo TEST_REDIS_PASSWORD)
  local maven_rc=0
  (
    cd -- "${REPO_ROOT}/backend"
    exec setsid env \
      -u TEST_REDIS_PASSWORD \
      "TEST_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${DB_NAME}" \
      "TEST_APP_DB_USER=${DB_APP_USER}" \
      "TEST_APP_DB_PASSWORD=${s_app_pw}" \
      "TEST_MEDIA_DB_USER=${DB_MEDIA_USER}" \
      "TEST_MEDIA_DB_PASSWORD=${s_media_pw}" \
      "TEST_MOD_DB_USER=${DB_MOD_USER}" \
      "TEST_MOD_DB_PASSWORD=${s_mod_pw}" \
      "TEST_MIGRATION_DB_USER=${DB_MIGRATION_USER}" \
      "TEST_MIGRATION_DB_PASSWORD=${s_pg_pw}" \
      "TEST_REDIS_HOST=127.0.0.1" \
      "TEST_REDIS_PORT=${REDIS_PORT}" \
      "TEST_STORAGE_ENDPOINT=http://127.0.0.1:${STORAGE_PORT}" \
      "TEST_STORAGE_ACCESS_KEY=${s_s3_ak}" \
      "TEST_STORAGE_SECRET_KEY=${s_s3_sk}" \
      "TEST_STORAGE_BUCKET=${STORAGE_BUCKET}" \
      ./mvnw "${MAVEN_ARGS[@]}"
  ) &
  MAVEN_PID=$!

  # Confirma que o Maven assumiu seu próprio PGID (diferente de SCRIPT_PGID)
  local pgid_wait=0 obs_pgid=""
  while (( pgid_wait < 200 )); do
    obs_pgid="$(get_pid_pgid "$MAVEN_PID")"
    if [[ "$obs_pgid" == "GONE" ]]; then
      break
    fi
    if [[ "$obs_pgid" =~ ^[0-9]+$ && "$obs_pgid" != "$SCRIPT_PGID" && "$obs_pgid" -gt 1 ]]; then
      MAVEN_PGID="$obs_pgid"
      break
    fi
    sleep 0.01
    pgid_wait=$((pgid_wait + 1))
  done

  if [[ "$obs_pgid" != "GONE" && -z "$MAVEN_PGID" ]]; then
    msg_err "Falha ao isolar o process group do Maven via setsid (PGID permaneceu '${obs_pgid}', igual ao script '${SCRIPT_PGID}')."
    kill -TERM "$MAVEN_PID" 2>/dev/null || true
    wait "$MAVEN_PID" 2>/dev/null || true
    MAVEN_REAPED=1
    MAVEN_PID=""
    cleanup_run_containers
    trap - INT TERM EXIT
    exit "$EXIT_ENV"
  fi

  wait "$MAVEN_PID" || maven_rc=$?
  MAVEN_REAPED=1
  MAVEN_PID=""
  MAVEN_PGID=""

  # Limpeza seletiva garantida dos containers criados por esta execução e do staging Podman
  cleanup_run_containers
  trap - INT TERM EXIT

  if [[ "$RECEIVED_SIGNAL" == "INT" ]]; then
    exit "$EXIT_SIGINT"
  elif [[ "$RECEIVED_SIGNAL" == "TERM" ]]; then
    exit "$EXIT_SIGTERM"
  fi

  if (( CLEANUP_FAILED != 0 )); then
    msg_err "Limpeza seletiva falhou (código original do Maven: ${maven_rc})."
    exit "$EXIT_ENV"
  fi

  exit "$maven_rc"
}

# ------------------------------------------------------------------------------
# Subcomando: DOWN (validação prévia integral por nome e label + remoção seletiva)
# ------------------------------------------------------------------------------

cmd_down() {
  parse_common_args "down" "$@"
  check_python3
  require_runtime

  local note
  for note in "${RUNTIME_NOTES[@]}"; do
    msg_warn "$note"
  done

  msg_info "Validando ownership dos containers da instância '${INSTANCE}' (runtime: ${SELECTED_RUNTIME})..."

  # Fase 1A: inspecionar os 3 nomes esperados (distinguindo PRESENT / ABSENT / ERROR)
  local valid_ids=()
  local valid_names=()
  local violations=()
  local inspect_errors=()
  local cname meta cid cname_obs owner_obs

  for cname in "$NAME_POSTGRES" "$NAME_REDIS" "$NAME_STORAGE"; do
    meta="$(inspect_container_metadata "$cname")"
    if [[ "$meta" == ERROR\|* ]]; then
      inspect_errors+=("Erro ao inspecionar '${cname}': ${meta#ERROR|}")
      continue
    fi
    if [[ "$meta" == "ABSENT" ]]; then
      continue
    fi
    IFS='|' read -r _ cid cname_obs _ owner_obs _ <<< "$meta"
    if [[ "$cname_obs" != "$cname" ]]; then
      violations+=("Nome inspecionado '${cname_obs}' diverge do esperado '${cname}'.")
    elif [[ "$owner_obs" != "$INSTANCE" ]]; then
      violations+=("Container '${cname}' existe, mas não pertence à instância '${INSTANCE}' (rótulo '${LABEL_OWNER_KEY}'='${owner_obs:-ausente}').")
    elif [[ -z "$cid" ]]; then
      inspect_errors+=("Não foi possível obter ID válido para '${cname}'.")
    else
      valid_ids+=("$cid")
      valid_names+=("$cname")
    fi
  done

  # Fase 1B: descobrir containers com rótulo rewit.test-infra=<INSTANCE> mas nome fora do conjunto esperado
  local label_ps_out="" rc_lps=0
  label_ps_out="$("$SELECTED_RUNTIME" ps -a --filter "label=${LABEL_OWNER_KEY}=${INSTANCE}" --format '{{.ID}}|{{.Names}}' 2>/dev/null)" || rc_lps=$?
  if (( rc_lps != 0 )); then
    inspect_errors+=("Falha ao listar containers pelo rótulo '${LABEL_OWNER_KEY}=${INSTANCE}' (código ${rc_lps}).")
  else
    local lline lcid lnames_raw lname
    while IFS= read -r lline; do
      [[ -z "$lline" ]] && continue
      IFS='|' read -r lcid lnames_raw <<< "$lline"
      # Suporta eventual lista separada por vírgula e remove '/' inicial
      local IFS_OLD="$IFS"
      IFS=',' read -r -a lname_arr <<< "$lnames_raw"
      IFS="$IFS_OLD"
      for lname in "${lname_arr[@]}"; do
        lname="${lname#/}"
        lname="${lname//[[:space:]]/}"
        [[ -z "$lname" ]] && continue
        if ! is_expected_instance_name "$lname"; then
          violations+=("Container inesperado '${lname}' (${lcid:0:12}) possui rótulo '${LABEL_OWNER_KEY}=${INSTANCE}', mas nome fora do conjunto esperado (${NAME_POSTGRES}, ${NAME_REDIS}, ${NAME_STORAGE}).")
        fi
      done
    done <<< "$label_ps_out"
  fi

  # Bloqueio integral antes de qualquer remoção: erro operacional/inspect (4) tem precedência sobre conflito (3)
  if (( ${#inspect_errors[@]} > 0 )); then
    msg_err "Remoção abortada sem remover nenhum container devido a erro de inspeção/runtime:"
    local e
    for e in "${inspect_errors[@]}"; do
      printf '  - %s\n' "$e" >&2
    done
    exit "$EXIT_ENV"
  fi

  if (( ${#violations[@]} > 0 )); then
    msg_blocked "Remoção abortada sem remover nenhum container devido a conflito de ownership/nomenclatura:"
    local v
    for v in "${violations[@]}"; do
      printf '  - %s\n' "$v" >&2
    done
    exit "$EXIT_BLOCKED"
  fi

  if (( ${#valid_ids[@]} == 0 )); then
    msg_info "Nenhum container encontrado para a instância '${INSTANCE}'. Nenhuma ação necessária."
    exit "$EXIT_OK"
  fi

  # Fase 2: remover exclusivamente os IDs validados (com -v para eliminar volumes anônimos)
  local failed=0 i
  for (( i = 0; i < ${#valid_ids[@]}; i++ )); do
    local id="${valid_ids[i]}"
    local nm="${valid_names[i]}"
    if "$SELECTED_RUNTIME" rm -f -v "$id" >/dev/null 2>&1; then
      msg_ok "Container '${nm}' (${id:0:12}) removido com seus volumes anônimos."
    else
      msg_err "Falha ao remover container '${nm}' (${id:0:12})."
      failed=1
    fi
  done

  if (( failed != 0 )); then
    die_env "Limpeza incompleta para a instância '${INSTANCE}'."
  fi

  exit "$EXIT_OK"
}

# ------------------------------------------------------------------------------
# Subcomando: STATUS (estritamente READ-ONLY, sem expor env/segredos)
# ------------------------------------------------------------------------------

cmd_status() {
  parse_common_args "status" "$@"
  check_python3
  require_runtime

  local note
  for note in "${RUNTIME_NOTES[@]}"; do
    msg_warn "$note"
  done

  local label_filter="label=${LABEL_OWNER_KEY}"
  local filter_desc="todas"
  if (( INSTANCE_EXPLICIT == 1 )); then
    label_filter="label=${LABEL_OWNER_KEY}=${INSTANCE}"
    filter_desc="${INSTANCE}"
  fi

  local ps_out="" rc=0
  ps_out="$("$SELECTED_RUNTIME" ps -a --filter "$label_filter" --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Ports}}' 2>/dev/null)" || rc=$?
  if (( rc != 0 )); then
    die_env "Falha ao consultar containers no runtime '${SELECTED_RUNTIME}' (código ${rc})."
  fi

  msg_info "=== Status da Infraestrutura Descartável de Testes (READ-ONLY) ==="
  msg_info "Runtime            : ${SELECTED_RUNTIME}"
  msg_info "Instância filtrada : ${filter_desc}"

  if [[ -z "$ps_out" ]]; then
    msg_info "Nenhum container com rótulo '${LABEL_OWNER_KEY}' encontrado."
    exit "$EXIT_OK"
  fi

  local line cid cname cstatus cports meta m_id m_name m_state m_owner ownership
  local shown=0
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    IFS='|' read -r cid cname cstatus cports <<< "$line"
    meta="$(inspect_container_metadata "$cid")"
    if [[ "$meta" == ERROR\|* ]]; then
      die_env "Falha ao inspecionar container '${cid}': ${meta#ERROR|}"
    fi
    m_owner=""
    m_name="$cname"
    if [[ "$meta" == PRESENT\|* ]]; then
      IFS='|' read -r _ m_id m_name m_state m_owner _ <<< "$meta"
    fi

    ownership="inválida/desconhecida"
    if [[ -n "$m_owner" ]]; then
      if [[ "$m_name" == "rewit-testinfra-${m_owner}-postgres" \
         || "$m_name" == "rewit-testinfra-${m_owner}-redis" \
         || "$m_name" == "rewit-testinfra-${m_owner}-storage" ]]; then
        ownership="validada (${LABEL_OWNER_KEY}=${m_owner})"
      else
        ownership="nome divergente para instância '${m_owner}'"
      fi
    fi

    msg_info "----------------------------------------------------------------------"
    msg_info "Instância : ${m_owner:-desconhecida}"
    msg_info "Recurso   : ${m_name}"
    msg_info "Estado    : ${cstatus}"
    msg_info "Runtime   : ${SELECTED_RUNTIME}"
    msg_info "Portas    : ${cports:-nenhuma}"
    msg_info "Ownership : ${ownership}"
    shown=$((shown + 1))
  done <<< "$ps_out"

  if (( shown == 0 )); then
    msg_info "Nenhum container de infraestrutura de teste encontrado."
  fi
  exit "$EXIT_OK"
}

# ------------------------------------------------------------------------------
# Subcomando: DOCTOR (estritamente READ-ONLY)
# ------------------------------------------------------------------------------

cmd_doctor() {
  parse_common_args "doctor" "$@"

  msg_info "=== Diagnóstico da Infraestrutura Descartável de Testes (doctor, READ-ONLY) ==="
  msg_info "Bash               : ${BASH_VERSION:-desconhecido}"
  msg_info "Repositório raiz   : ${REPO_ROOT}"
  msg_info "Instância alvo     : ${INSTANCE}"
  msg_info "Portas configuradas: PostgreSQL=127.0.0.1:${POSTGRES_PORT}, Redis=127.0.0.1:${REDIS_PORT}, Storage=127.0.0.1:${STORAGE_PORT}"

  local env_errors=0
  local policy_blocks=0

  # 1) Python 3
  if command -v python3 >/dev/null 2>&1; then
    msg_ok "python3            : $(python3 --version 2>&1)"
  else
    msg_err "python3 ausente no PATH."
    env_errors=$((env_errors + 1))
  fi

  # 2) setsid (obrigatório para isolar o process group do Maven)
  if command -v setsid >/dev/null 2>&1 && setsid sh -c 'exit 0' >/dev/null 2>&1; then
    local setsid_ver=""
    setsid_ver="$(setsid --version 2>&1 | head -n 1 || true)"
    msg_ok "setsid             : acessível (${setsid_ver:-ok})"
  else
    msg_err "setsid ausente ou incompatível no PATH (obrigatório para encerramento de todo o process group do Maven)."
    env_errors=$((env_errors + 1))
  fi

  # 3) Java
  if command -v java >/dev/null 2>&1; then
    msg_ok "java               : $(java -version 2>&1 | head -n 1)"
  else
    msg_err "java ausente no PATH."
    env_errors=$((env_errors + 1))
  fi

  # 4) backend/mvnw
  if [[ -x "${REPO_ROOT}/backend/mvnw" ]]; then
    msg_ok "backend/mvnw       : presente e executável"
  else
    msg_err "backend/mvnw ausente ou sem permissão executável."
    env_errors=$((env_errors + 1))
  fi

  # 5) Scripts de provisionamento PostgreSQL (e verificação anti-symlink)
  if [[ -f "${REPO_ROOT}/infrastructure/postgres/init/01-init-postgis.sql" \
     && ! -L "${REPO_ROOT}/infrastructure/postgres/init/01-init-postgis.sql" \
     && -x "${REPO_ROOT}/infrastructure/postgres/init/02-create-app-role.sh" \
     && ! -L "${REPO_ROOT}/infrastructure/postgres/init/02-create-app-role.sh" \
     && -f "${REPO_ROOT}/infrastructure/postgres/provision-runtime-role.sql" \
     && ! -L "${REPO_ROOT}/infrastructure/postgres/provision-runtime-role.sql" ]]; then
    msg_ok "postgres init      : scripts presentes (01-init-postgis.sql, 02-create-app-role.sh, provision-runtime-role.sql)"
  else
    msg_err "Scripts de provisionamento PostgreSQL incompletos ou symlinks inesperados em infrastructure/postgres/."
    env_errors=$((env_errors + 1))
  fi

  # 6) Runtime (docker / podman)
  if resolve_runtime; then
    msg_ok "runtime selecionado: ${SELECTED_RUNTIME}"
    [[ -n "$DOCKER_DIAG" ]] && msg_info "  docker           : ${DOCKER_DIAG}"
    [[ -n "$PODMAN_DIAG" ]] && msg_info "  podman           : ${PODMAN_DIAG}"
    local note
    for note in "${RUNTIME_NOTES[@]}"; do
      msg_warn "$note"
    done
  else
    [[ -n "$DOCKER_DIAG" ]] && msg_err "docker             : ${DOCKER_DIAG}"
    [[ -n "$PODMAN_DIAG" ]] && msg_err "podman             : ${PODMAN_DIAG}"
    env_errors=$((env_errors + 1))
  fi

  # 7) Validação de política de portas e disponibilidade instantânea (IPv4 + IPv6 consciente)
  if (( POSTGRES_PORT == REDIS_PORT || POSTGRES_PORT == STORAGE_PORT || REDIS_PORT == STORAGE_PORT )); then
    msg_blocked "Portas configuradas contêm duplicatas (${POSTGRES_PORT}, ${REDIS_PORT}, ${STORAGE_PORT})."
    policy_blocks=$((policy_blocks + 1))
  fi

  local p probe_res
  for p in "$POSTGRES_PORT" "$REDIS_PORT" "$STORAGE_PORT"; do
    if is_reserved_port "$p"; then
      msg_blocked "Porta ${p} é reservada (desenvolvimento/external-test/API)."
      policy_blocks=$((policy_blocks + 1))
    elif command -v python3 >/dev/null 2>&1; then
      probe_res="$(probe_host_port_availability "$p")"
      case "$probe_res" in
        IN_USE_IPV4)
          msg_blocked "Porta 127.0.0.1:${p} em uso no host neste instante (listener IPv4 ou IPv6 dual-stack)."
          policy_blocks=$((policy_blocks + 1))
          ;;
        IPV6_ONLY_IN_USE)
          msg_ok "porta 127.0.0.1:${p} disponível em IPv4 (verificação instantânea)"
          msg_warn "Porta [::1]:${p} ocupada por listener IPv6-only (não impede bind exclusivo em 127.0.0.1:${p})."
          ;;
        FREE)
          msg_ok "porta 127.0.0.1:${p} disponível (verificação instantânea IPv4+IPv6)"
          ;;
        FREE_NO_IPV6)
          msg_ok "porta 127.0.0.1:${p} disponível (verificação instantânea IPv4; pilha IPv6 indisponível no host)"
          ;;
        ERROR\|*)
          msg_err "Falha ao verificar porta ${p}: ${probe_res#ERROR|}"
          env_errors=$((env_errors + 1))
          ;;
        *)
          msg_err "Resposta inesperada ao verificar porta ${p}: '${probe_res}'"
          env_errors=$((env_errors + 1))
          ;;
      esac
    fi
  done

  if (( env_errors > 0 )); then
    die_env "Diagnóstico falhou com ${env_errors} problema(s) de ambiente/runtime."
  fi
  if (( policy_blocks > 0 )); then
    die_blocked "Diagnóstico encontrou ${policy_blocks} conflito(s) de porta/política."
  fi

  msg_ok "Ambiente pronto para executar './scripts/test-infra.sh run --instance ${INSTANCE}'."
  exit "$EXIT_OK"
}

# ------------------------------------------------------------------------------
# Ponto de entrada principal
# ------------------------------------------------------------------------------

main() {
  check_bash_version

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
    run)
      cmd_run "$@"
      ;;
    down)
      cmd_down "$@"
      ;;
    status)
      cmd_status "$@"
      ;;
    doctor)
      cmd_doctor "$@"
      ;;
    *)
      die_usage "Subcomando desconhecido: '${subcommand}'. Subcomandos válidos: run, down, status, doctor."
      ;;
  esac
}

main "$@"
