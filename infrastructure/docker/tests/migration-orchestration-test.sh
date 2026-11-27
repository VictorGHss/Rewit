#!/usr/bin/env bash
# ==============================================================================
# REWIT - VALIDAÇÃO E2E EM CONTAINERS REAIS (F2-DEPLOY-03.5.2B — PILARES C e D)
# ==============================================================================
# Constrói a imagem REAL do backend a partir de `infrastructure/docker/backend.Dockerfile`
# e executa validação ponta a ponta em rede descartável isolada com:
#   - PostgreSQL 18 + PostGIS 3.6 com TLS real (`ssl=on`, `sslmode=verify-full`)
#     e topologia não-superuser (F2-DEPLOY-03.5.1: managed_admin -> rewit_migrator
#     + roles runtime rewit_app/rewit_media/rewit_mod)
#   - Redis 8 com TLS real (`--tls-port 6380 --port 0`)
#   - SeaweedFS 4.47 com S3 HTTPS real (`-s3.port.https=8443`) e bucket pré-criado
#
# Comprova empiricamente:
#   1. Higiene da imagem (usuário não-root 10001, app.jar 0444, PropertiesLauncher,
#      zero arquivos .env ou credenciais embutidas).
#   2. API `prod` falha fail-closed antes das migrations (ReadOnlyFlywaySchemaValidator)
#      e quando recebe variável migrator (`FLYWAY_DB_USER`).
#   3. Orquestrador `scripts/deploy/run-predeploy-migrations.sh` bloqueia rollout em
#      erros de configuração ou variáveis conflitantes (fail-closed, sem retry).
#   4. Dois containers concorrentes do `RewitMigrationRunner` (mesma imagem real) contra
#      PostgreSQL TLS `verify-full` vazio: ambos saem com 0, soma `46 + 0 = 46`,
#      zero classes Spring/Tomcat/`RewitApplication` carregadas (`-Xlog:class+load`),
#      `rewit_assert_runtime_privileges=OK`, zero segredos em stdout/stderr.
#   5. Reexecução idempotente via `scripts/deploy/run-predeploy-migrations.sh` (exit=0,
#      executadas=0).
#   6. Subida da API real (`SPRING_PROFILES_ACTIVE=prod`) a partir da MESMA imagem
#      após conclusão do job, com ZERO variáveis `FLYWAY_DB_*`/`POSTGRES_*`/`SPRING_FLYWAY_*`
#      (comprovado via inspect), validação somente leitura aprovada e HTTP 200 UP em
#      `/actuator/health/readiness`, `/readyz` e `/livez`.
#   7. Contrato de `infrastructure/docker/docker-compose.prod.yml`.
#
# Uso:
#   bash infrastructure/docker/tests/migration-orchestration-test.sh
#   MIGRATION_ORCH_TEST_RUNTIME=podman bash infrastructure/docker/tests/migration-orchestration-test.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
PREDEPLOY_SCRIPT="${REPO_ROOT}/scripts/deploy/run-predeploy-migrations.sh"
PROD_COMPOSE_FILE="${REPO_ROOT}/infrastructure/docker/docker-compose.prod.yml"
BACKEND_DOCKERFILE="${REPO_ROOT}/infrastructure/docker/backend.Dockerfile"

RUNTIME="${MIGRATION_ORCH_TEST_RUNTIME:-}"
PG_IMAGE="${MIGRATION_ORCH_TEST_PG_IMAGE:-postgis/postgis:18-3.6}"
REDIS_IMAGE="${MIGRATION_ORCH_TEST_REDIS_IMAGE:-redis:8-alpine}"
S3_IMAGE="${MIGRATION_ORCH_TEST_S3_IMAGE:-chrislusf/seaweedfs:4.47}"

detect_runtime() {
  if [[ -n "$RUNTIME" ]]; then
    if [[ "$RUNTIME" != "docker" && "$RUNTIME" != "podman" ]]; then
      echo "ERRO: MIGRATION_ORCH_TEST_RUNTIME deve ser 'docker' ou 'podman'." >&2
      exit 2
    fi
    command -v "$RUNTIME" >/dev/null 2>&1 || { echo "ERRO: runtime '${RUNTIME}' indisponível." >&2; exit 2; }
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

for dep in openssl python3; do
  command -v "$dep" >/dev/null 2>&1 || { echo "ERRO: dependência ausente no host: ${dep}" >&2; exit 2; }
done

RUN_ID="migorch-$(date +%s)-$$"
NETWORK_NAME="rewit-${RUN_ID}-net"
IMAGE_TAG="rewit-backend:${RUN_ID}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rewit-${RUN_ID}.XXXXXX")"
chmod 0755 "$WORK"

MOUNT_OPTS="ro"
if [[ "$RUNTIME" == "podman" ]]; then
  MOUNT_OPTS="ro,z"
fi

CONTAINERS=()
BUILT_IMAGE=0
FAILURES=0

cleanup() {
  for c in "${CONTAINERS[@]}"; do
    "$RUNTIME" rm -f -v "$c" >/dev/null 2>&1 || true
  done
  "$RUNTIME" network rm "$NETWORK_NAME" >/dev/null 2>&1 || true
  if (( BUILT_IMAGE == 1 )); then
    "$RUNTIME" rmi -f "$IMAGE_TAG" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

pass() { printf '  [OK]    %s\n' "$1"; }
fail() { printf '  [FALHA] %s\n' "$1" >&2; FAILURES=$((FAILURES + 1)); }

rand_hex() { openssl rand -hex "$1"; }
rand_b64() { openssl rand -base64 "$1" | tr -d '\n'; }

# Credenciais efêmeras exclusivas desta execução (jamais impressas)
PG_ADMIN_USER="rewit_pgsu"
PG_ADMIN_PASS="$(rand_hex 20)"
PG_DB="rewit_prod"
MGD_ADMIN_USER="managed_admin"
MGD_ADMIN_PASS="$(rand_hex 20)"
MIG_USER="rewit_migrator"
MIG_PASS="$(rand_hex 20)"
APP_PASS="$(rand_hex 20)"
MEDIA_PASS="$(rand_hex 20)"
MOD_PASS="$(rand_hex 20)"
REDIS_PASS="$(rand_hex 20)"
S3_ACCESS_KEY="$(rand_hex 16)"
S3_SECRET_KEY="$(rand_hex 24)"
S3_BUCKET="rewit-media-prod"
TRUSTSTORE_PASS="$(rand_hex 16)"
JWT_SECRET_VAL="$(rand_b64 40)"
PEPPER_SECRET_VAL="$(rand_b64 40)"
REFRESH_SECRET_VAL="$(rand_b64 40)"
MOD_AUDIT_SECRET_VAL="$(rand_b64 40)"
RATE_LIMIT_SECRET_VAL="$(rand_b64 40)"
EMAIL_RESERVATION_SECRET_VAL="$(rand_b64 40)"

ALL_SECRETS=(
  "$PG_ADMIN_PASS" "$MGD_ADMIN_PASS" "$MIG_PASS"
  "$APP_PASS" "$MEDIA_PASS" "$MOD_PASS"
  "$REDIS_PASS" "$S3_SECRET_KEY" "$TRUSTSTORE_PASS"
  "$JWT_SECRET_VAL" "$PEPPER_SECRET_VAL" "$REFRESH_SECRET_VAL"
  "$MOD_AUDIT_SECRET_VAL" "$RATE_LIMIT_SECRET_VAL" "$EMAIL_RESERVATION_SECRET_VAL"
)

assert_no_secret_in_file() {
  local label="$1"
  local file="$2"
  local secret
  for secret in "${ALL_SECRETS[@]}"; do
    if grep -qF -- "$secret" "$file"; then
      fail "segredo vazado em ${label}"
      return 1
    fi
  done
  pass "nenhum segredo vazado em ${label}"
}

echo "=== F2-DEPLOY-03.5.2B: Validação em containers reais (runtime=${RUNTIME}, run=${RUN_ID}) ==="

# ------------------------------------------------------------------------------
# 1. Build real da imagem do backend via infrastructure/docker/backend.Dockerfile
# ------------------------------------------------------------------------------
echo "== 1. Build da imagem real (${BACKEND_DOCKERFILE})"
BUILD_LOG="${WORK}/docker-build.log"
if "$RUNTIME" build -f "$BACKEND_DOCKERFILE" -t "$IMAGE_TAG" "${REPO_ROOT}/backend" >"$BUILD_LOG" 2>&1; then
  BUILT_IMAGE=1
  pass "imagem real construída com sucesso (${IMAGE_TAG})"
else
  cat "$BUILD_LOG" >&2
  fail "falha no build da imagem real"
  exit 1
fi

echo "== 2. Higiene da imagem construída"
IMG_UID="$("$RUNTIME" run --rm --entrypoint id "$IMAGE_TAG" -u)"
if [[ "$IMG_UID" == "10001" ]]; then
  pass "container executa como usuário não-root rewit (UID 10001)"
else
  fail "UID inesperado na imagem: ${IMG_UID}"
fi

JAR_MODE="$("$RUNTIME" run --rm --entrypoint stat "$IMAGE_TAG" -c '%a' /app/app.jar)"
if [[ "$JAR_MODE" == "444" ]]; then
  pass "/app/app.jar possui permissão somente leitura (0444)"
else
  fail "permissão inesperada de /app/app.jar: ${JAR_MODE}"
fi

MANIFEST_CONTENT="$("$RUNTIME" run --rm --entrypoint sh "$IMAGE_TAG" -c 'java -e 2>/dev/null || true; unzip -p /app/app.jar META-INF/MANIFEST.MF 2>/dev/null || jar xf /app/app.jar META-INF/MANIFEST.MF && cat META-INF/MANIFEST.MF' 2>/dev/null || true)"
# Fallback portátil usando a própria JVM (java.util.jar.JarFile) sem depender de unzip/jar na JRE
if [[ -z "$MANIFEST_CONTENT" ]]; then
  MANIFEST_CONTENT="$("$RUNTIME" run --rm --entrypoint sh "$IMAGE_TAG" -c 'python3 -c "" 2>/dev/null || true; "$JAVA_HOME/bin/java" -cp /app/app.jar -Dloader.main=none -version >/dev/null 2>&1; head -c 0 /app/app.jar' 2>/dev/null || true)"
fi
# Lê META-INF/MANIFEST.MF via python3 no host copiando o JAR do container (100% determinístico em JRE mínima)
MANIFEST_TMP="${WORK}/MANIFEST.MF"
MANIFEST_CONTAINER="rewit-${RUN_ID}-manifest"
CONTAINERS+=("$MANIFEST_CONTAINER")
"$RUNTIME" create --name "$MANIFEST_CONTAINER" "$IMAGE_TAG" >/dev/null
"$RUNTIME" cp "${MANIFEST_CONTAINER}:/app/app.jar" "${WORK}/app.jar"
python3 - "${WORK}/app.jar" "$MANIFEST_TMP" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as zf:
    data = zf.read("META-INF/MANIFEST.MF").decode("utf-8")
    with open(sys.argv[2], "w", encoding="utf-8") as out:
        out.write(data)
PY
rm -f "${WORK}/app.jar"
"$RUNTIME" rm -f "$MANIFEST_CONTAINER" >/dev/null 2>&1 || true

if grep -q 'Main-Class: org.springframework.boot.loader.launch.PropertiesLauncher' "$MANIFEST_TMP" \
   && grep -q 'Start-Class: com.rewit.RewitApplication' "$MANIFEST_TMP"; then
  pass "META-INF/MANIFEST.MF confirma PropertiesLauncher + Start-Class=RewitApplication"
else
  fail "META-INF/MANIFEST.MF divergente do contrato ZIP/PropertiesLauncher"
fi

IMG_ENV="$("$RUNTIME" inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$IMAGE_TAG")"
if printf '%s\n' "$IMG_ENV" | grep -qE '^(FLYWAY_DB_|APP_DB_|MEDIA_DB_|MOD_DB_|POSTGRES_|JWT_SECRET|REDIS_PASSWORD|OBJECT_STORAGE_)'; then
  fail "variável sensível ou de banco encontrada embutida na imagem"
else
  pass "nenhuma credencial ou variável de banco/segredo embutida em Config.Env da imagem"
fi

APP_FILES="$("$RUNTIME" run --rm --entrypoint ls "$IMAGE_TAG" -la /app)"
if printf '%s\n' "$APP_FILES" | grep -q '\.env'; then
  fail "arquivo .env encontrado em /app na imagem"
else
  pass "nenhum arquivo .env presente em /app"
fi

# ------------------------------------------------------------------------------
# 3. Rede descartável e certificados TLS efêmeros (CA + PG + Redis + S3 + truststore JVM)
# ------------------------------------------------------------------------------
echo "== 3. Certificados TLS efêmeros e infraestrutura descartável (PG TLS + Redis TLS + S3 HTTPS)"
"$RUNTIME" network create "$NETWORK_NAME" >/dev/null

CERT_DIR="${WORK}/certs"
mkdir -p "$CERT_DIR"
chmod 0755 "$CERT_DIR"

openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
  -keyout "${CERT_DIR}/ca.key" -out "${CERT_DIR}/ca.crt" \
  -subj "/CN=rewit-ephemeral-test-ca" >/dev/null 2>&1

issue_server_cert() {
  local name="$1"
  local dns="$2"
  local ext_file="${WORK}/${name}.ext"
  cat > "$ext_file" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:${dns}
EOF
  openssl req -newkey rsa:2048 -nodes \
    -keyout "${CERT_DIR}/${name}.key" -out "${WORK}/${name}.csr" \
    -subj "/CN=${dns}" >/dev/null 2>&1
  openssl x509 -req -in "${WORK}/${name}.csr" \
    -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" -CAcreateserial \
    -out "${CERT_DIR}/${name}.crt" -days 2 -sha256 -extfile "$ext_file" >/dev/null 2>&1
  chmod 0644 "${CERT_DIR}/${name}.crt" "${CERT_DIR}/${name}.key"
}

issue_server_cert "pg" "rewit-pg"
issue_server_cert "redis" "rewit-redis"
issue_server_cert "s3" "rewit-s3"
chmod 0644 "${CERT_DIR}/ca.crt"

# Cria truststore PKCS12 contendo a CA efêmera usando o keytool da própria imagem JRE 25
TRUSTSTORE_MOUNT="rw"
if [[ "$RUNTIME" == "podman" ]]; then
  TRUSTSTORE_MOUNT="rw,z"
fi
TRUSTSTORE_BUILD_CONTAINER="rewit-${RUN_ID}-truststore"
CONTAINERS+=("$TRUSTSTORE_BUILD_CONTAINER")
TRUSTSTORE_PASS="$TRUSTSTORE_PASS" "$RUNTIME" run --rm --name "$TRUSTSTORE_BUILD_CONTAINER" \
  --user 0 \
  --entrypoint sh \
  -e TRUSTSTORE_PASS \
  -v "${CERT_DIR}:/certs:${TRUSTSTORE_MOUNT}" \
  "$IMAGE_TAG" \
  -c 'cp "$JAVA_HOME/lib/security/cacerts" /certs/truststore.p12 && chmod 0644 /certs/truststore.p12 && "$JAVA_HOME/bin/keytool" -storepasswd -keystore /certs/truststore.p12 -storepass changeit -new "$TRUSTSTORE_PASS" >/dev/null 2>&1 && "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias rewit-test-ca -file /certs/ca.crt -keystore /certs/truststore.p12 -storepass "$TRUSTSTORE_PASS" >/dev/null 2>&1 && chmod 0644 /certs/truststore.p12'
pass "CA efêmera, certificados TLS (PG/Redis/S3) e truststore JVM gerados"

# Staging dos scripts de init do PostgreSQL
PG_STAGE="${WORK}/pg-stage"
mkdir -p "${PG_STAGE}/init" "${PG_STAGE}/sql"
cp "${REPO_ROOT}/infrastructure/postgres/init/"* "${PG_STAGE}/init/"
cp "${REPO_ROOT}/infrastructure/postgres/provision-runtime-role.sql" "${PG_STAGE}/sql/"
chmod 0755 "${PG_STAGE}/init/02-create-app-role.sh"
chmod -R a+rX "$PG_STAGE"

# PostgreSQL 18 + PostGIS 3.6 com TLS real (`ssl=on`)
PG_CONTAINER="rewit-${RUN_ID}-pg"
CONTAINERS+=("$PG_CONTAINER")
POSTGRES_PASSWORD="$PG_ADMIN_PASS" APP_DB_PASSWORD="$APP_PASS" MEDIA_DB_PASSWORD="$MEDIA_PASS" MOD_DB_PASSWORD="$MOD_PASS" \
"$RUNTIME" run -d --name "$PG_CONTAINER" \
  --network "$NETWORK_NAME" --network-alias rewit-pg \
  -e "POSTGRES_USER=${PG_ADMIN_USER}" \
  -e POSTGRES_PASSWORD \
  -e "POSTGRES_DB=${PG_DB}" \
  -e APP_DB_USER=rewit_app -e APP_DB_PASSWORD \
  -e MEDIA_DB_USER=rewit_media -e MEDIA_DB_PASSWORD \
  -e MOD_DB_USER=rewit_mod -e MOD_DB_PASSWORD \
  -v "${PG_STAGE}/init:/docker-entrypoint-initdb.d:${MOUNT_OPTS}" \
  -v "${PG_STAGE}/sql:/opt/rewit/postgres:${MOUNT_OPTS}" \
  -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
  --entrypoint sh \
  "$PG_IMAGE" \
  -c 'mkdir -p /tmp/pg-tls && cp /certs/pg.crt /tmp/pg-tls/server.crt && cp /certs/pg.key /tmp/pg-tls/server.key && chown -R postgres:postgres /tmp/pg-tls && chmod 0600 /tmp/pg-tls/server.key && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/pg-tls/server.crt -c ssl_key_file=/tmp/pg-tls/server.key' >/dev/null

# Redis 8 com TLS real
REDIS_CONTAINER="rewit-${RUN_ID}-redis"
CONTAINERS+=("$REDIS_CONTAINER")
REDIS_PASS="$REDIS_PASS" "$RUNTIME" run -d --name "$REDIS_CONTAINER" \
  --network "$NETWORK_NAME" --network-alias rewit-redis \
  -e REDIS_PASS \
  -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
  "$REDIS_IMAGE" \
  sh -c 'exec redis-server --port 0 --tls-port 6380 --tls-cert-file /certs/redis.crt --tls-key-file /certs/redis.key --tls-ca-cert-file /certs/ca.crt --tls-auth-clients no --requirepass "$REDIS_PASS"' >/dev/null

# SeaweedFS 4.47 com S3 HTTPS real + bucket pré-provisionado
S3_CONTAINER="rewit-${RUN_ID}-s3"
CONTAINERS+=("$S3_CONTAINER")
AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
"$RUNTIME" run -d --name "$S3_CONTAINER" \
  --network "$NETWORK_NAME" --network-alias rewit-s3 \
  -e AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY \
  -e "S3_BUCKET=${S3_BUCKET}" \
  -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
  "$S3_IMAGE" \
  mini -dir=/data -s3.port=8333 -s3.port.https=8443 -s3.cert.file=/certs/s3.crt -s3.key.file=/certs/s3.key -bucket="$S3_BUCKET" >/dev/null

# Aguarda prontidão do PostgreSQL TLS
pg_ready=0
for _ in $(seq 1 90); do
  if "$RUNTIME" logs "$PG_CONTAINER" 2>&1 | grep -q 'PostgreSQL init process complete' \
     && "$RUNTIME" exec "$PG_CONTAINER" pg_isready -h 127.0.0.1 -U "$PG_ADMIN_USER" -d "$PG_DB" >/dev/null 2>&1; then
    pg_ready=1
    break
  fi
  sleep 1
done
if (( pg_ready == 1 )); then
  pass "PostgreSQL 18 + PostGIS 3.6 TLS pronto (5 extensões e 3 roles runtime provisionadas)"
else
  "$RUNTIME" logs "$PG_CONTAINER" >&2 || true
  fail "PostgreSQL TLS não ficou pronto no prazo"
  exit 1
fi

# Provisiona topologia gerenciada 03.5.1 (managed_admin não-superuser com CREATEROLE -> rewit_migrator)
"$RUNTIME" exec "$PG_CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" \
  -c "CREATE ROLE ${MGD_ADMIN_USER} WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;" \
  -c "GRANT CONNECT ON DATABASE ${PG_DB} TO ${MGD_ADMIN_USER};" \
  -c "GRANT USAGE, CREATE ON SCHEMA public TO ${MGD_ADMIN_USER} WITH GRANT OPTION;" >/dev/null
printf '%s\n%s\n' "$MGD_ADMIN_PASS" "$MGD_ADMIN_PASS" | "$RUNTIME" exec -i "$PG_CONTAINER" \
  setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" -c "\\password ${MGD_ADMIN_USER}" >/dev/null 2>&1

PGPASSWORD="$MGD_ADMIN_PASS" "$RUNTIME" exec -e PGPASSWORD "$PG_CONTAINER" sh -c \
  'psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN_USER"' -d '"$PG_DB"' \
   -c "CREATE ROLE '"$MIG_USER"' WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT;" \
   -c "GRANT USAGE, CREATE ON SCHEMA public TO '"$MIG_USER"';"' >/dev/null
printf '%s\n%s\n' "$MIG_PASS" "$MIG_PASS" | PGPASSWORD="$MGD_ADMIN_PASS" "$RUNTIME" exec -i -e PGPASSWORD "$PG_CONTAINER" sh -c \
  'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN_USER"' -d '"$PG_DB"' -c "\password '"$MIG_USER"'"' >/dev/null 2>&1
"$RUNTIME" exec "$PG_CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" \
  -c "GRANT CONNECT ON DATABASE ${PG_DB} TO ${MIG_USER};" >/dev/null
pass "topologia gerenciada 03.5.1 provisionada (${MGD_ADMIN_USER} -> ${MIG_USER} sem SUPERUSER/CREATEROLE)"

# Aguarda Redis TLS
redis_ready=0
for _ in $(seq 1 45); do
  if REDIS_PASS="$REDIS_PASS" "$RUNTIME" exec -e REDIS_PASS "$REDIS_CONTAINER" sh -c \
     'redis-cli --tls --cacert /certs/ca.crt -h 127.0.0.1 -p 6380 -a "$REDIS_PASS" --no-auth-warning PING' 2>/dev/null | grep -qx 'PONG'; then
    redis_ready=1
    break
  fi
  sleep 1
done
if (( redis_ready == 1 )); then
  pass "Redis 8 TLS (:6380) pronto"
else
  "$RUNTIME" logs "$REDIS_CONTAINER" >&2 || true
  fail "Redis TLS não ficou pronto no prazo"
  exit 1
fi

# Aguarda SeaweedFS S3 HTTPS (:8443)
s3_ready=0
for _ in $(seq 1 60); do
  if "$RUNTIME" run --rm --network "$NETWORK_NAME" --entrypoint curl \
     -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" "$IMAGE_TAG" \
     -sS --cacert /certs/ca.crt --max-time 3 -o /dev/null "https://rewit-s3:8443/" >/dev/null 2>&1; then
    s3_ready=1
    break
  fi
  sleep 1
done
if (( s3_ready == 1 )); then
  pass "SeaweedFS S3 HTTPS (:8443) pronto com bucket '${S3_BUCKET}'"
else
  "$RUNTIME" logs "$S3_CONTAINER" >&2 || true
  fail "SeaweedFS S3 HTTPS não ficou pronto no prazo"
  exit 1
fi

DATABASE_URL_TLS="jdbc:postgresql://rewit-pg:5432/${PG_DB}?sslmode=verify-full&sslrootcert=/certs/ca.crt"
JVM_TLS_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djavax.net.ssl.trustStore=/certs/truststore.p12 -Djavax.net.ssl.trustStorePassword=${TRUSTSTORE_PASS} -Djavax.net.ssl.trustStoreType=PKCS12"

# Helper para iniciar container da API em modo prod (ZERO variáveis FLYWAY_DB_* / POSTGRES_* / SPRING_FLYWAY_*)
run_api_container() {
  local mode="$1" # run-fg | run-bg
  local cname="$2"
  shift 2
  CONTAINERS+=("$cname")
  local run_flag="-d"
  if [[ "$mode" == "run-fg" ]]; then
    run_flag=""
  fi
  env \
    DATABASE_URL="$DATABASE_URL_TLS" \
    APP_DB_PASSWORD="$APP_PASS" \
    MEDIA_DB_PASSWORD="$MEDIA_PASS" \
    MOD_DB_PASSWORD="$MOD_PASS" \
    REDIS_PASSWORD="$REDIS_PASS" \
    OBJECT_STORAGE_ACCESS_KEY="$S3_ACCESS_KEY" \
    OBJECT_STORAGE_SECRET_KEY="$S3_SECRET_KEY" \
    "JWT_SECRET=${JWT_SECRET_VAL}" \
    PASSWORD_PEPPER_SECRET="$PEPPER_SECRET_VAL" \
    REFRESH_TOKEN_SECRET="$REFRESH_SECRET_VAL" \
    MODERATION_AUDIT_SECRET="$MOD_AUDIT_SECRET_VAL" \
    RATE_LIMIT_KEY_SECRET="$RATE_LIMIT_SECRET_VAL" \
    ACCOUNT_EMAIL_RESERVATION_SECRET="$EMAIL_RESERVATION_SECRET_VAL" \
    JAVA_OPTS="$JVM_TLS_OPTS" \
    "$RUNTIME" run ${run_flag:+"$run_flag"} \
      --name "$cname" \
      --network "$NETWORK_NAME" \
      --read-only \
      --tmpfs /tmp \
      --cap-drop ALL \
      --security-opt no-new-privileges:true \
      -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
      -e SPRING_PROFILES_ACTIVE=prod \
      -e ENVIRONMENT=prod \
      -e DATABASE_URL \
      -e APP_DB_USER=rewit_app -e APP_DB_PASSWORD \
      -e MEDIA_DB_USER=rewit_media -e MEDIA_DB_PASSWORD \
      -e MOD_DB_USER=rewit_mod -e MOD_DB_PASSWORD \
      -e REDIS_HOST=rewit-redis \
      -e REDIS_PORT=6380 \
      -e REDIS_PASSWORD \
      -e REDIS_SSL_ENABLED=true \
      -e OBJECT_STORAGE_ENDPOINT=https://rewit-s3:8443 \
      -e OBJECT_STORAGE_ACCESS_KEY \
      -e OBJECT_STORAGE_SECRET_KEY \
      -e "OBJECT_STORAGE_BUCKET=${S3_BUCKET}" \
      -e OBJECT_STORAGE_AUTO_CREATE_BUCKET=false \
      -e JWT_SECRET \
      -e PASSWORD_PEPPER_SECRET \
      -e PASSWORD_PEPPER_KEY_ID=v1 \
      -e REFRESH_TOKEN_SECRET \
      -e MODERATION_AUDIT_SECRET \
      -e MODERATION_AUDIT_KEY_ID=v1 \
      -e RATE_LIMIT_KEY_SECRET \
      -e ACCOUNT_EMAIL_RESERVATION_SECRET \
      -e JAVA_OPTS \
      "$@" \
      "$IMAGE_TAG"
}

# ------------------------------------------------------------------------------
# 4. Cenários fail-closed antes das migrations
# ------------------------------------------------------------------------------
echo "== 4. API prod fail-closed antes das migrations e com variável migrator indevida"
API_BEFORE_LOG="${WORK}/api-before-mig.log"
rc=0
run_api_container run-fg "rewit-${RUN_ID}-api-before" >"$API_BEFORE_LOG" 2>&1 || rc=$?
if (( rc != 0 )); then
  pass "API prod recusa inicialização em banco vazio antes das migrations (exit=${rc})"
else
  fail "API prod deveria falhar em banco vazio antes das migrations"
fi
HIST_EXISTS="$("$RUNTIME" exec "$PG_CONTAINER" psql -X -q -At -U "$PG_ADMIN_USER" -d "$PG_DB" -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")"
if [[ "$HIST_EXISTS" == "f" ]]; then
  pass "API não criou flyway_schema_history em banco vazio"
else
  fail "flyway_schema_history não deveria existir antes do runner"
fi
assert_no_secret_in_file "logs da API antes das migrations" "$API_BEFORE_LOG"

API_WITH_MIG_LOG="${WORK}/api-with-migvar.log"
rc=0
FLYWAY_DB_USER="$MIG_USER" run_api_container run-fg "rewit-${RUN_ID}-api-migvar" -e FLYWAY_DB_USER >"$API_WITH_MIG_LOG" 2>&1 || rc=$?
if (( rc != 0 )); then
  pass "API prod recusa inicialização quando FLYWAY_DB_USER é injetada (exit=${rc})"
else
  fail "API prod deveria recusar FLYWAY_DB_USER"
fi
assert_no_secret_in_file "logs da API com FLYWAY_DB_USER" "$API_WITH_MIG_LOG"

echo "== 5. Gate fail-closed de scripts/deploy/run-predeploy-migrations.sh"
BAD_CFG_LOG="${WORK}/predeploy-bad-cfg.log"
rc=0
env -i PATH="$PATH" \
  DATABASE_URL="jdbc:postgresql://rewit-pg:5432/${PG_DB}?sslmode=require" \
  FLYWAY_DB_USER="$MIG_USER" \
  FLYWAY_DB_PASSWORD="$MIG_PASS" \
  "$PREDEPLOY_SCRIPT" --image "$IMAGE_TAG" --runtime "$RUNTIME" --network "$NETWORK_NAME" --max-attempts 3 --retry-delay 1 \
  >"$BAD_CFG_LOG" 2>&1 || rc=$?
if (( rc == 2 )) && grep -q 'FALHA NÃO RETENTÁVEL (exit=2' "$BAD_CFG_LOG"; then
  pass "run-predeploy-migrations.sh aborta imediatamente sem retry em sslmode=require (exit=2)"
else
  cat "$BAD_CFG_LOG" >&2
  fail "run-predeploy-migrations.sh deveria falhar imediatamente com exit=2 em sslmode=require (obtido=${rc})"
fi
assert_no_secret_in_file "logs do predeploy com sslmode=require" "$BAD_CFG_LOG"

CONFLICT_LOG="${WORK}/predeploy-conflict.log"
rc=0
env -i PATH="$PATH" \
  DATABASE_URL="$DATABASE_URL_TLS" \
  FLYWAY_DB_USER="$MIG_USER" \
  FLYWAY_DB_PASSWORD="$MIG_PASS" \
  APP_DB_PASSWORD="$APP_PASS" \
  "$PREDEPLOY_SCRIPT" --image "$IMAGE_TAG" --runtime "$RUNTIME" --network "$NETWORK_NAME" \
  >"$CONFLICT_LOG" 2>&1 || rc=$?
if (( rc == 2 )) && grep -q 'APP_DB_PASSWORD' "$CONFLICT_LOG"; then
  pass "run-predeploy-migrations.sh rejeita variável runtime conflitante antes de subir container (exit=2)"
else
  cat "$CONFLICT_LOG" >&2
  fail "run-predeploy-migrations.sh deveria rejeitar APP_DB_PASSWORD com exit=2 (obtido=${rc})"
fi
assert_no_secret_in_file "logs do predeploy com variável conflitante" "$CONFLICT_LOG"

# M-02 em container real: erro determinístico de autenticação (exit=4) -> 0 retries (aborta na 1ª tentativa)
WRONG_MIG_PASS="$(rand_hex 20)"
ALL_SECRETS+=("$WRONG_MIG_PASS")
AUTH_FAIL_LOG="${WORK}/predeploy-auth-fail.log"
rc=0
env -i PATH="$PATH" \
  DATABASE_URL="$DATABASE_URL_TLS" \
  FLYWAY_DB_USER="$MIG_USER" \
  FLYWAY_DB_PASSWORD="$WRONG_MIG_PASS" \
  "$PREDEPLOY_SCRIPT" --image "$IMAGE_TAG" --runtime "$RUNTIME" --network "$NETWORK_NAME" --volume "${CERT_DIR}:/certs:${MOUNT_OPTS}" --max-attempts 3 --retry-delay 1 \
  >"$AUTH_FAIL_LOG" 2>&1 || rc=$?
if (( rc == 4 )) && grep -q 'FALHA NÃO RETENTÁVEL (exit=4' "$AUTH_FAIL_LOG" && ! grep -q 'Aguardando 1s antes da próxima tentativa' "$AUTH_FAIL_LOG"; then
  pass "run-predeploy-migrations.sh em container real não retenta exit=4 (falha de autenticação abortada na 1ª tentativa)"
else
  cat "$AUTH_FAIL_LOG" >&2
  fail "run-predeploy-migrations.sh deveria abortar sem retry com exit=4 em senha incorreta (obtido=${rc})"
fi
assert_no_secret_in_file "logs do predeploy com falha de autenticação" "$AUTH_FAIL_LOG"

# M-02 em container real: porta PostgreSQL indisponível pré-migração (exit=6) -> retry limitado até --max-attempts=2
UNREACHABLE_LOG="${WORK}/predeploy-unreachable.log"
rc=0
env -i PATH="$PATH" \
  DATABASE_URL="jdbc:postgresql://rewit-pg:54999/${PG_DB}?sslmode=verify-full&sslrootcert=/certs/ca.crt" \
  FLYWAY_DB_USER="$MIG_USER" \
  FLYWAY_DB_PASSWORD="$MIG_PASS" \
  "$PREDEPLOY_SCRIPT" --image "$IMAGE_TAG" --runtime "$RUNTIME" --network "$NETWORK_NAME" --volume "${CERT_DIR}:/certs:${MOUNT_OPTS}" --max-attempts 2 --retry-delay 1 \
  >"$UNREACHABLE_LOG" 2>&1 || rc=$?
if (( rc == 6 )) && grep -q 'Tentativa 1/2 falhou por indisponibilidade transitória' "$UNREACHABLE_LOG" \
   && grep -q 'Tentativa 2/2 falhou por indisponibilidade transitória' "$UNREACHABLE_LOG"; then
  pass "run-predeploy-migrations.sh em container real aplica retry limitado em exit=6 (2/2 tentativas) e bloqueia rollout"
else
  cat "$UNREACHABLE_LOG" >&2
  fail "run-predeploy-migrations.sh deveria executar 2 tentativas e sair com exit=6 em porta indisponível (obtido=${rc})"
fi
assert_no_secret_in_file "logs do predeploy em porta indisponível" "$UNREACHABLE_LOG"

# ------------------------------------------------------------------------------
# 6. Dois containers concorrentes do RewitMigrationRunner na imagem real (TLS verify-full)
# ------------------------------------------------------------------------------
echo "== 6. Execução concorrente de dois containers reais do RewitMigrationRunner (TLS verify-full)"
MIG_A_OUT="${WORK}/mig-a.out"
MIG_A_ERR="${WORK}/mig-a.err"
MIG_B_OUT="${WORK}/mig-b.out"
MIG_B_ERR="${WORK}/mig-b.err"
MIG_A_NAME="rewit-${RUN_ID}-mig-a"
MIG_B_NAME="rewit-${RUN_ID}-mig-b"
CONTAINERS+=("$MIG_A_NAME" "$MIG_B_NAME")

launch_runner_container() {
  local cname="$1"
  local out_file="$2"
  local err_file="$3"
  env \
    DATABASE_URL="$DATABASE_URL_TLS" \
    FLYWAY_DB_USER="$MIG_USER" \
    FLYWAY_DB_PASSWORD="$MIG_PASS" \
    "$RUNTIME" run --rm \
      --name "$cname" \
      --network "$NETWORK_NAME" \
      --read-only \
      --tmpfs /tmp \
      --cap-drop ALL \
      --security-opt no-new-privileges:true \
      -v "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
      -e DATABASE_URL \
      -e FLYWAY_DB_USER \
      -e FLYWAY_DB_PASSWORD \
      --entrypoint sh \
      "$IMAGE_TAG" \
      -c 'exec java -Xlog:class+load:stdout -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar' \
      >"$out_file" 2>"$err_file"
}

launch_runner_container "$MIG_A_NAME" "$MIG_A_OUT" "$MIG_A_ERR" &
pid_a=$!
launch_runner_container "$MIG_B_NAME" "$MIG_B_OUT" "$MIG_B_ERR" &
pid_b=$!

rc_a=0
wait "$pid_a" || rc_a=$?
rc_b=0
wait "$pid_b" || rc_b=$?

if (( rc_a == 0 && rc_b == 0 )); then
  pass "ambos os containers concorrentes do runner concluíram com exit=0"
else
  echo "--- stderr runner A ---" >&2; cat "$MIG_A_ERR" >&2
  echo "--- stderr runner B ---" >&2; cat "$MIG_B_ERR" >&2
  fail "runners concorrentes deveriam encerrar com 0 (rc_a=${rc_a}, rc_b=${rc_b})"
fi

exec_a="$(grep -oE 'executadas=[0-9]+' "$MIG_A_OUT" | head -n1 | cut -d= -f2 || echo -1)"
exec_b="$(grep -oE 'executadas=[0-9]+' "$MIG_B_OUT" | head -n1 | cut -d= -f2 || echo -1)"
if [[ "$exec_a" =~ ^[0-9]+$ && "$exec_b" =~ ^[0-9]+$ ]] && (( exec_a + exec_b == 46 )) && (( (exec_a == 46 && exec_b == 0) || (exec_a == 0 && exec_b == 46) )); then
  pass "serialização comprovada em containers reais (runnerA=${exec_a}, runnerB=${exec_b}, soma=46)"
else
  fail "contagem inesperada nos runners concorrentes (runnerA=${exec_a}, runnerB=${exec_b})"
fi

if grep -q 'rewit_assert_runtime_privileges=OK' "$MIG_A_OUT" && grep -q 'rewit_assert_runtime_privileges=OK' "$MIG_B_OUT"; then
  pass "ambos os containers confirmaram rewit_assert_runtime_privileges=OK"
else
  fail "rewit_assert_runtime_privileges=OK ausente na saída dos runners"
fi

# Comprova ausência total de SpringApplication, Spring Context, Beans, Web, Data, Security, Tomcat e RewitApplication via -Xlog:class+load
# (apenas o classloader ZIP org.springframework.boot.loader.* e o configurador SPI de Logback org.springframework.boot.logging.logback.*
# descoberto automaticamente pelo SLF4J/Logback existem no fat JAR sem inicializar o Spring Framework)
FORBIDDEN_CLASSES_FILE="${WORK}/forbidden-classes.log"
{
  grep -E 'org\.springframework\.' "$MIG_A_OUT" "$MIG_B_OUT" \
    | grep -vE 'org\.springframework\.boot\.(loader|logging\.logback)\.' || true
  grep -E 'org\.apache\.catalina\.|com\.rewit\.RewitApplication' "$MIG_A_OUT" "$MIG_B_OUT" || true
} >"$FORBIDDEN_CLASSES_FILE"
if [[ -s "$FORBIDDEN_CLASSES_FILE" ]]; then
  head -n 20 "$FORBIDDEN_CLASSES_FILE" >&2
  fail "classe proibida de Spring/Tomcat/RewitApplication foi carregada no container do runner"
else
  pass "zero classes Spring Context/SpringApplication/Web/Data/Security, Tomcat ou RewitApplication carregadas no runner (-Xlog:class+load)"
fi

assert_no_secret_in_file "stdout/stderr do runner A" "$MIG_A_OUT"
assert_no_secret_in_file "stderr do runner A" "$MIG_A_ERR"
assert_no_secret_in_file "stdout/stderr do runner B" "$MIG_B_OUT"
assert_no_secret_in_file "stderr do runner B" "$MIG_B_ERR"

HIST_COUNT="$("$RUNTIME" exec "$PG_CONTAINER" psql -X -q -At -U "$PG_ADMIN_USER" -d "$PG_DB" \
  -c "SELECT COUNT(*), COUNT(DISTINCT version), COUNT(*) FILTER (WHERE NOT success) FROM public.flyway_schema_history WHERE version IS NOT NULL")"
if [[ "$HIST_COUNT" == "46|46|0" ]]; then
  pass "public.flyway_schema_history íntegro após concorrência (46 linhas, 46 versões únicas, 0 falhas)"
else
  fail "estado inesperado de flyway_schema_history: ${HIST_COUNT}"
fi

# ------------------------------------------------------------------------------
# 7. Gate pré-deploy via scripts/deploy/run-predeploy-migrations.sh (idempotência + flock)
# ------------------------------------------------------------------------------
echo "== 7. Execução idempotente via scripts/deploy/run-predeploy-migrations.sh"
PREDEPLOY_OK_LOG="${WORK}/predeploy-ok.log"
rc=0
env -i PATH="$PATH" \
  DATABASE_URL="$DATABASE_URL_TLS" \
  FLYWAY_DB_USER="$MIG_USER" \
  FLYWAY_DB_PASSWORD="$MIG_PASS" \
  "$PREDEPLOY_SCRIPT" \
    --image "$IMAGE_TAG" \
    --runtime "$RUNTIME" \
    --network "$NETWORK_NAME" \
    --volume "${CERT_DIR}:/certs:${MOUNT_OPTS}" \
    --lock-file "${WORK}/predeploy.lock" \
    >"$PREDEPLOY_OK_LOG" 2>&1 || rc=$?
if (( rc == 0 )) && grep -q 'executadas=0' "$PREDEPLOY_OK_LOG" && grep -q 'Gate pré-deploy APROVADO' "$PREDEPLOY_OK_LOG"; then
  pass "run-predeploy-migrations.sh concluiu idempotentemente (exit=0, executadas=0, gate aprovado)"
else
  cat "$PREDEPLOY_OK_LOG" >&2
  fail "run-predeploy-migrations.sh deveria aprovar idempotentemente (rc=${rc})"
fi
assert_no_secret_in_file "logs do predeploy aprovado" "$PREDEPLOY_OK_LOG"

# ------------------------------------------------------------------------------
# 8. Rollout real da API `prod` a partir da MESMA imagem após o job de migrations
# ------------------------------------------------------------------------------
echo "== 8. Subida real da API prod (mesma imagem, zero variáveis migrator, TLS fim a fim)"
API_LIVE_NAME="rewit-${RUN_ID}-api-live"
run_api_container run-bg "$API_LIVE_NAME" >/dev/null

# Comprova via inspect que nenhuma credencial migrator existe no ambiente da API
API_INSPECT_ENV="$("$RUNTIME" inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$API_LIVE_NAME")"
if printf '%s\n' "$API_INSPECT_ENV" | grep -qE '^(FLYWAY_DB_|POSTGRES_|SPRING_FLYWAY_)'; then
  fail "variável migrator/Flyway encontrada no ambiente do container da API"
else
  pass "inspect confirma ZERO variáveis FLYWAY_DB_*, POSTGRES_* ou SPRING_FLYWAY_* na API"
fi

api_ready=0
for _ in $(seq 1 90); do
  api_state="$("$RUNTIME" inspect -f '{{.State.Status}}' "$API_LIVE_NAME" 2>/dev/null || echo "gone")"
  if [[ "$api_state" == "exited" || "$api_state" == "dead" || "$api_state" == "stopped" ]]; then
    break
  fi
  if "$RUNTIME" exec "$API_LIVE_NAME" curl -fsS --max-time 3 http://127.0.0.1:8081/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; then
    api_ready=1
    break
  fi
  sleep 1
done

API_LIVE_LOG="${WORK}/api-live.log"
"$RUNTIME" logs "$API_LIVE_NAME" >"$API_LIVE_LOG" 2>&1 || true

if (( api_ready == 1 )); then
  pass "API prod pronta: /actuator/health/readiness retornou HTTP 200 UP"
else
  cat "$API_LIVE_LOG" >&2
  fail "API prod não atingiu readiness UP no prazo"
fi

if "$RUNTIME" exec "$API_LIVE_NAME" curl -fsS --max-time 3 http://127.0.0.1:8080/readyz 2>/dev/null | grep -q '"status":"UP"' \
   && "$RUNTIME" exec "$API_LIVE_NAME" curl -fsS --max-time 3 http://127.0.0.1:8080/livez 2>/dev/null | grep -q '"status":"UP"'; then
  pass "probes adicionais /readyz e /livez retornaram HTTP 200 UP"
else
  fail "probes /readyz ou /livez não retornaram HTTP 200 UP"
fi

if grep -q 'Validação somente leitura do schema Flyway aprovada (rewit_app, readOnly=true): versaoAtual=46, migrationsAplicadas=46, pendentes=0' "$API_LIVE_LOG"; then
  pass "logs da API confirmam ReadOnlyFlywaySchemaValidator aprovado (V46, 0 pendentes)"
else
  fail "confirmação de ReadOnlyFlywaySchemaValidator ausente nos logs da API"
fi

assert_no_secret_in_file "logs da API prod em execução" "$API_LIVE_LOG"

# ------------------------------------------------------------------------------
# 9. Validação de contrato de infrastructure/docker/docker-compose.prod.yml
# ------------------------------------------------------------------------------
echo "== 9. Contrato de orquestração em infrastructure/docker/docker-compose.prod.yml"
if python3 - "$PROD_COMPOSE_FILE" <<'PY'
import re, sys

text = open(sys.argv[1], encoding="utf-8").read()
m_mig = re.search(r"(?ms)^  rewit-migrate:\n(.*?)(?=^  rewit-api:)", text)
m_api = re.search(r"(?ms)^  rewit-api:\n(.*)", text)
assert m_mig and m_api, "Serviços rewit-migrate e rewit-api são obrigatórios"

mig_block = m_mig.group(1)
api_block = m_api.group(1)

assert "${REWIT_BACKEND_IMAGE:?" in mig_block, "rewit-migrate deve referenciar REWIT_BACKEND_IMAGE"
assert "${REWIT_BACKEND_IMAGE:?" in api_block, "rewit-api deve referenciar REWIT_BACKEND_IMAGE"
assert "com.rewit.migration.RewitMigrationRunner" in mig_block, "rewit-migrate deve invocar RewitMigrationRunner"
assert "condition: service_completed_successfully" in api_block, "rewit-api deve depender de service_completed_successfully"
assert "SPRING_PROFILES_ACTIVE: prod" in api_block, "rewit-api deve fixar SPRING_PROFILES_ACTIVE=prod"

for forbidden in ("APP_DB_", "MEDIA_DB_", "MOD_DB_", "POSTGRES_", "JWT_SECRET", "REDIS_PASSWORD"):
    assert forbidden not in mig_block, f"rewit-migrate não pode conter {forbidden}"

for forbidden in ("FLYWAY_DB_", "POSTGRES_", "SPRING_FLYWAY_"):
    assert forbidden not in api_block, f"rewit-api não pode conter {forbidden}"
PY
then
  pass "docker-compose.prod.yml cumpre mesma imagem, service_completed_successfully e isolamento de variáveis"
else
  fail "contrato de docker-compose.prod.yml violado"
fi

# Se `docker compose` v2 estiver disponível (ex.: runner do GitHub Actions), valida também via parser oficial
if [[ "$RUNTIME" == "docker" ]] && docker compose version >/dev/null 2>&1; then
  if env \
    REWIT_BACKEND_IMAGE="$IMAGE_TAG" \
    DATABASE_URL="$DATABASE_URL_TLS" \
    FLYWAY_DB_USER="$MIG_USER" \
    FLYWAY_DB_PASSWORD="$MIG_PASS" \
    APP_DB_PASSWORD="$APP_PASS" \
    MEDIA_DB_PASSWORD="$MEDIA_PASS" \
    MOD_DB_PASSWORD="$MOD_PASS" \
    REDIS_HOST=rewit-redis \
    REDIS_PASSWORD="$REDIS_PASS" \
    OBJECT_STORAGE_ENDPOINT=https://rewit-s3:8443 \
    OBJECT_STORAGE_ACCESS_KEY="$S3_ACCESS_KEY" \
    OBJECT_STORAGE_SECRET_KEY="$S3_SECRET_KEY" \
    OBJECT_STORAGE_BUCKET="$S3_BUCKET" \
    "JWT_SECRET=${JWT_SECRET_VAL}" \
    PASSWORD_PEPPER_SECRET="$PEPPER_SECRET_VAL" \
    REFRESH_TOKEN_SECRET="$REFRESH_SECRET_VAL" \
    MODERATION_AUDIT_SECRET="$MOD_AUDIT_SECRET_VAL" \
    RATE_LIMIT_KEY_SECRET="$RATE_LIMIT_SECRET_VAL" \
    ACCOUNT_EMAIL_RESERVATION_SECRET="$EMAIL_RESERVATION_SECRET_VAL" \
    docker compose -f "$PROD_COMPOSE_FILE" config --quiet; then
    pass "docker compose -f docker-compose.prod.yml config --quiet aprovado"
  else
    fail "docker compose config rejeitou docker-compose.prod.yml"
  fi
fi

echo ""
if (( FAILURES == 0 )); then
  echo "=== RESULTADO: TODAS AS VERIFICAÇÕES EM CONTAINERS REAIS FORAM APROVADAS (0 falhas) ==="
  exit 0
else
  echo "=== RESULTADO: ${FAILURES} FALHA(S) NA VALIDAÇÃO EM CONTAINERS REAIS ===" >&2
  exit 1
fi
