#!/usr/bin/env bash
# ==============================================================================
# REWIT - ORQUESTRADOR PRÉ-DEPLOY DE MIGRATIONS FLYWAY (F2-DEPLOY-03.5.2B)
# ==============================================================================
# Executa o job one-shot de migrations (`com.rewit.migration.RewitMigrationRunner`)
# a partir da MESMA imagem imutável de container que será usada no rollout da API.
#
# Garantias operacionais e de segurança:
#   - Funciona em Docker ou Podman (`--runtime docker|podman` ou detecção automática).
#   - Recebe credenciais exclusivamente via variáveis de ambiente (`DATABASE_URL`,
#     `FLYWAY_DB_USER`, `FLYWAY_DB_PASSWORD`), sem jamais expô-las em linha de comando
#     ou nos logs.
#   - Rejeita (fail-closed) variáveis runtime/administrativas conflitantes presentes
#     no ambiente antes mesmo de criar o container.
#   - Política explícita de retry:
#       * exit=0 -> gate liberado para rollout da API.
#       * exit=2 (configuração inválida) -> aborta imediatamente, sem retry.
#       * exit=3 (violação de privilégios do migrator) -> aborta imediatamente, sem retry.
#       * exit=4 com falha registrada no histórico/validação/checksum -> aborta
#         imediatamente, sem retry e sem jamais acionar repair/baseline/clean.
#       * exit=4 transitório (conectividade) ou exit=5 (contenção do advisory lock) ->
#         executa até `--max-attempts` tentativas com backoff; se persistir, falha e
#         bloqueia o rollout da API.
#   - Coordenação opcional local via `flock` (`--lock-file`) complementar ao advisory
#     lock de sessão no PostgreSQL (`pg_try_advisory_lock`).
#
# Uso:
#   DATABASE_URL="jdbc:postgresql://db.internal:5432/rewit?sslmode=verify-full" \
#   FLYWAY_DB_USER="rewit_migrator" \
#   FLYWAY_DB_PASSWORD="<segredo>" \
#   ./scripts/deploy/run-predeploy-migrations.sh --image rewit-backend:<tag>
# ==============================================================================

set -euo pipefail

usage() {
  cat <<'EOF'
Uso:
  ./scripts/deploy/run-predeploy-migrations.sh --image <imagem-imutavel> [opcoes]

Opções:
  --image <ref>           Referência da imagem imutável do backend (obrigatória).
  --runtime <docker|podman>
                          Runtime de container (padrão: detecção automática).
  --network <nome>        Rede de container opcional para alcançar o PostgreSQL.
  --volume <spec>         Montagem somente leitura/adicional (repetível, ex.: /host/ca.crt:/certs/ca.crt:ro).
  --java-opts <opts>      Opções adicionais da JVM (sobrescreve JAVA_OPTS padrão).
  --max-attempts <N>      Máximo de tentativas para erros transitórios/lock (1..10, padrão: 3).
  --retry-delay <seg>     Espera entre tentativas em segundos (1..60, padrão: 5).
  --lock-file <caminho>   Arquivo de lock local (flock) opcional para evitar disparos duplicados no host.
  -h, --help              Exibe esta ajuda e encerra com código 0.

Variáveis de ambiente obrigatórias:
  DATABASE_URL            URL JDBC PostgreSQL com sslmode=verify-full e host não-local.
  FLYWAY_DB_USER          Role migrator dedicada (sem SUPERUSER/CREATEROLE, distinta das roles runtime).
  FLYWAY_DB_PASSWORD      Senha da role migrator.
EOF
}

IMAGE=""
RUNTIME="${REWIT_CONTAINER_RUNTIME:-}"
NETWORK=""
JAVA_OPTS_ARG="${MIGRATE_JAVA_OPTS:--XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError}"
MAX_ATTEMPTS=3
RETRY_DELAY=5
LOCK_FILE=""
VOLUMES=()

while (( $# > 0 )); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --image)
      (( $# >= 2 )) || { echo "ERRO: --image exige um valor." >&2; exit 2; }
      IMAGE="$2"; shift 2
      ;;
    --image=*)
      IMAGE="${1#--image=}"; shift
      ;;
    --runtime)
      (( $# >= 2 )) || { echo "ERRO: --runtime exige docker ou podman." >&2; exit 2; }
      RUNTIME="$2"; shift 2
      ;;
    --runtime=*)
      RUNTIME="${1#--runtime=}"; shift
      ;;
    --network)
      (( $# >= 2 )) || { echo "ERRO: --network exige um valor." >&2; exit 2; }
      NETWORK="$2"; shift 2
      ;;
    --network=*)
      NETWORK="${1#--network=}"; shift
      ;;
    --volume)
      (( $# >= 2 )) || { echo "ERRO: --volume exige um valor." >&2; exit 2; }
      VOLUMES+=("$2"); shift 2
      ;;
    --volume=*)
      VOLUMES+=("${1#--volume=}"); shift
      ;;
    --java-opts)
      (( $# >= 2 )) || { echo "ERRO: --java-opts exige um valor." >&2; exit 2; }
      JAVA_OPTS_ARG="$2"; shift 2
      ;;
    --java-opts=*)
      JAVA_OPTS_ARG="${1#--java-opts=}"; shift
      ;;
    --max-attempts)
      (( $# >= 2 )) || { echo "ERRO: --max-attempts exige um inteiro (1..10)." >&2; exit 2; }
      MAX_ATTEMPTS="$2"; shift 2
      ;;
    --max-attempts=*)
      MAX_ATTEMPTS="${1#--max-attempts=}"; shift
      ;;
    --retry-delay)
      (( $# >= 2 )) || { echo "ERRO: --retry-delay exige um inteiro em segundos (1..60)." >&2; exit 2; }
      RETRY_DELAY="$2"; shift 2
      ;;
    --retry-delay=*)
      RETRY_DELAY="${1#--retry-delay=}"; shift
      ;;
    --lock-file)
      (( $# >= 2 )) || { echo "ERRO: --lock-file exige um caminho." >&2; exit 2; }
      LOCK_FILE="$2"; shift 2
      ;;
    --lock-file=*)
      LOCK_FILE="${1#--lock-file=}"; shift
      ;;
    *)
      echo "ERRO: argumento desconhecido: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$IMAGE" ]]; then
  echo "ERRO: informe --image <imagem-imutavel>." >&2
  exit 2
fi

if ! [[ "$MAX_ATTEMPTS" =~ ^[0-9]+$ ]] || (( MAX_ATTEMPTS < 1 || MAX_ATTEMPTS > 10 )); then
  echo "ERRO: --max-attempts deve ser um inteiro entre 1 e 10." >&2
  exit 2
fi

if ! [[ "$RETRY_DELAY" =~ ^[0-9]+$ ]] || (( RETRY_DELAY < 1 || RETRY_DELAY > 60 )); then
  echo "ERRO: --retry-delay deve ser um inteiro entre 1 e 60 segundos." >&2
  exit 2
fi

detect_runtime() {
  if [[ -n "$RUNTIME" ]]; then
    if [[ "$RUNTIME" != "docker" && "$RUNTIME" != "podman" ]]; then
      echo "ERRO: --runtime suporta apenas 'docker' ou 'podman' (recebido: ${RUNTIME})." >&2
      exit 2
    fi
    if ! command -v "$RUNTIME" >/dev/null 2>&1; then
      echo "ERRO: runtime '${RUNTIME}' não encontrado no PATH." >&2
      exit 2
    fi
    return 0
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    RUNTIME="docker"
    return 0
  fi
  if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    RUNTIME="podman"
    return 0
  fi
  echo "ERRO: nenhum runtime de container acessível (docker ou podman)." >&2
  exit 2
}

detect_runtime

# Validação fail-closed de variáveis obrigatórias (nunca imprime valores)
missing_vars=()
[[ -n "${DATABASE_URL:-}" ]] || missing_vars+=("DATABASE_URL")
[[ -n "${FLYWAY_DB_USER:-}" ]] || missing_vars+=("FLYWAY_DB_USER")
[[ -n "${FLYWAY_DB_PASSWORD:-}" ]] || missing_vars+=("FLYWAY_DB_PASSWORD")
if (( ${#missing_vars[@]} > 0 )); then
  echo "ERRO: variáveis obrigatórias ausentes para o job de migrations (rollout bloqueado): ${missing_vars[*]}." >&2
  exit 2
fi

# Validação fail-closed contra contaminação por variáveis runtime/administrativas ou overrides do Flyway/Spring
forbidden_found=()
for forbidden_var in \
  POSTGRES_USER POSTGRES_PASSWORD \
  APP_DB_USER APP_DB_PASSWORD \
  MEDIA_DB_USER MEDIA_DB_PASSWORD \
  MOD_DB_USER MOD_DB_PASSWORD \
  SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
  SPRING_APPLICATION_JSON; do
  if [[ -n "${!forbidden_var:-}" ]]; then
    forbidden_found+=("$forbidden_var")
  fi
done
for env_name in "${!SPRING_FLYWAY_@}"; do
  if [[ -n "${!env_name:-}" ]]; then
    forbidden_found+=("$env_name")
  fi
done
for env_name in "${!FLYWAY_@}"; do
  if [[ "$env_name" != "FLYWAY_DB_USER" && "$env_name" != "FLYWAY_DB_PASSWORD" && -n "${!env_name:-}" ]]; then
    forbidden_found+=("$env_name")
  fi
done
if (( ${#forbidden_found[@]} > 0 )); then
  echo "ERRO: variáveis proibidas/conflitantes presentes no ambiente do job de migrations (rollout bloqueado): ${forbidden_found[*]}." >&2
  exit 2
fi

# Coordenação opcional no host via flock (complementar ao pg_try_advisory_lock no PostgreSQL)
if [[ -n "$LOCK_FILE" ]]; then
  if ! command -v flock >/dev/null 2>&1; then
    echo "ERRO: --lock-file exige o utilitário 'flock' disponível no host." >&2
    exit 2
  fi
  mkdir -p "$(dirname "$LOCK_FILE")"
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "ERRO: outro processo de deploy já detém o lock local '${LOCK_FILE}' (rollout bloqueado)." >&2
    exit 5
  fi
fi

# Extrai apenas host/porta/database sanitizados (sem credenciais nem query string) para log operacional
sanitize_target() {
  local raw="$1"
  local without_prefix="${raw#jdbc:postgresql://}"
  local without_query="${without_prefix%%\?*}"
  local without_userinfo="${without_query##*@}"
  printf '%s' "$without_userinfo"
}

TARGET_DISPLAY="$(sanitize_target "$DATABASE_URL")"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rewit-predeploy-mig.XXXXXX")"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

is_non_retryable_migration_error() {
  local err_file="$1"
  grep -qiE \
    'Validate failed|Detected failed migration|Migration checksum mismatch|Estado inesperado no histórico|applied to database, but not resolved|Migration description mismatch|Migration type mismatch|Validação pós-migração do Flyway falhou' \
    "$err_file"
}

echo "[predeploy-migrations] Iniciando job pré-deploy (runtime=${RUNTIME}, imagem=${IMAGE}, alvo=${TARGET_DISPLAY}, migrator=${FLYWAY_DB_USER}, maxAttempts=${MAX_ATTEMPTS})."

attempt=1
last_rc=1

while (( attempt <= MAX_ATTEMPTS )); do
  err_log="${TMP_DIR}/attempt-${attempt}.err"
  out_log="${TMP_DIR}/attempt-${attempt}.out"
  container_name="rewit-predeploy-mig-$(date +%s)-$$-a${attempt}"

  run_args=(
    run --rm
    --name "$container_name"
    --read-only
    --tmpfs /tmp
    --cap-drop ALL
    --security-opt no-new-privileges:true
    --entrypoint sh
    -e DATABASE_URL
    -e FLYWAY_DB_USER
    -e FLYWAY_DB_PASSWORD
    -e "JAVA_OPTS=${JAVA_OPTS_ARG}"
    -e "TZ=${TZ:-UTC}"
  )
  if [[ -n "$NETWORK" ]]; then
    run_args+=(--network "$NETWORK")
  fi
  for vol in "${VOLUMES[@]}"; do
    run_args+=(-v "$vol")
  done
  run_args+=(
    "$IMAGE"
    -c 'exec java $JAVA_OPTS -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar'
  )

  rc=0
  "$RUNTIME" "${run_args[@]}" >"$out_log" 2>"$err_log" || rc=$?
  last_rc=$rc

  # Reemite stdout/stderr sanitizados (o runner já redige segredos; como defesa em profundidade, jamais imprima env)
  cat "$out_log"
  if [[ -s "$err_log" ]]; then
    cat "$err_log" >&2
  fi

  case "$rc" in
    0)
      echo "[predeploy-migrations] Gate pré-deploy APROVADO na tentativa ${attempt}/${MAX_ATTEMPTS}: rollout da API liberado."
      exit 0
      ;;
    2)
      echo "[predeploy-migrations] FALHA NÃO RETENTÁVEL (exit=2: configuração inválida). Rollout da API BLOQUEADO." >&2
      exit 2
      ;;
    3)
      echo "[predeploy-migrations] FALHA NÃO RETENTÁVEL (exit=3: violação de identidade/privilégios do migrator). Rollout da API BLOQUEADO." >&2
      exit 3
      ;;
    4)
      if is_non_retryable_migration_error "$err_log"; then
        echo "[predeploy-migrations] FALHA NÃO RETENTÁVEL (exit=4: inconsistência ou migration failed registrada no histórico Flyway). Jamais execute repair/baseline/clean automaticamente; inspecione o estado do banco antes de qualquer ação manual. Rollout da API BLOQUEADO." >&2
        exit 4
      fi
      echo "[predeploy-migrations] Tentativa ${attempt}/${MAX_ATTEMPTS} falhou com exit=4 (erro potencialmente transitório)." >&2
      ;;
    5)
      echo "[predeploy-migrations] Tentativa ${attempt}/${MAX_ATTEMPTS} atingiu timeout do advisory lock (exit=5: outra execução em andamento)." >&2
      ;;
    *)
      echo "[predeploy-migrations] FALHA INESPERADA (exit=${rc}). Rollout da API BLOQUEADO." >&2
      exit "$rc"
      ;;
  esac

  if (( attempt == MAX_ATTEMPTS )); then
    break
  fi
  echo "[predeploy-migrations] Aguardando ${RETRY_DELAY}s antes da próxima tentativa ($((attempt + 1))/${MAX_ATTEMPTS})..." >&2
  sleep "$RETRY_DELAY"
  attempt=$((attempt + 1))
done

echo "[predeploy-migrations] Todas as ${MAX_ATTEMPTS} tentativas falharam (último exit=${last_rc}). Rollout da API BLOQUEADO." >&2
exit "$last_rc"
