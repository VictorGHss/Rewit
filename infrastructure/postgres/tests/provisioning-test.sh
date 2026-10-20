#!/usr/bin/env bash
# ==============================================================================
# REWIT - TESTE DE PROVISIONAMENTO DA ROLE DE RUNTIME (SEC-2, SEC-3, SEC-4)
# ==============================================================================
# Reproduz a inicialização do PostgreSQL a partir de um CLONE LIMPO do repositório (com
# core.autocrlf=true, como num checkout padrão do Windows) em containers descartáveis, sem
# portas publicadas e sem volumes nomeados. Nenhum serviço existente é acessado.
#
# Uso: infrastructure/postgres/tests/provisioning-test.sh [commit]   (padrão: HEAD)
# O commit precisa estar gravado: o teste usa somente o que está no repositório.
# As senhas de teste nunca são impressas; a saída mostra apenas o nome de cada caso.
# ==============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

IMAGE="${PROVISIONING_TEST_IMAGE:-postgis/postgis:18-3.6}"
SOURCE_REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
COMMIT=$(git -C "$SOURCE_REPO" rev-parse --verify "${1:-HEAD}^{commit}")
RUN_ID="rewit-provtest-$(date +%s)-$$"
ADMIN_USER="rewit_provtest_admin"
ADMIN_DB="rewit_provtest"
WORK=$(mktemp -d)
# No Git Bash, git.exe e docker.exe são nativos: use o mesmo caminho Windows em todos os comandos
if command -v cygpath >/dev/null 2>&1; then WORK=$(cygpath -m "$WORK"); fi
CONTAINERS=()
FAILURES=0

cleanup() {
    for c in "${CONTAINERS[@]}"; do docker rm -f -v "$c" >/dev/null 2>&1 || true; done
    rm -rf "$WORK"
}
trap cleanup EXIT

host_path() { printf '%s' "$1"; }

pass() { printf '  [OK]    %s\n' "$1"; }
fail() { printf '  [FALHA] %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }

# ------------------------------------------------------------------------------
# 1. Clone limpo
# ------------------------------------------------------------------------------
echo "== Clone limpo de $COMMIT (core.autocrlf=true)"
git clone --quiet --no-checkout --config core.autocrlf=true "$SOURCE_REPO" "$WORK/repo"
git -C "$WORK/repo" checkout --quiet "$COMMIT"
INIT_DIR="$WORK/repo/infrastructure/postgres/init"
SQL_DIR="$WORK/repo/infrastructure/postgres"
SCRIPT="$INIT_DIR/02-create-app-role.sh"

if [ ! -f "$SCRIPT" ] || [ ! -f "$SQL_DIR/provision-runtime-role.sql" ]; then
    echo "Clone limpo sem os scripts de provisionamento"; exit 1
fi
# Conta bytes CR diretamente: o grep do Git Bash descarta CR em modo texto e não serviria de prova
cr_count() { tr -dc '\r' < "$1" | wc -c | tr -d ' '; }
check "script de init sem CR após checkout com autocrlf" "[ \"\$(cr_count '$SCRIPT')\" = 0 ]"
check "SQL de provisionamento sem CR após checkout com autocrlf" "[ \"\$(cr_count '$SQL_DIR/provision-runtime-role.sql')\" = 0 ]"
check "shebang válido (#!/bin/sh)" "[ \"\$(head -n1 '$SCRIPT')\" = '#!/bin/sh' ]"
check "bit de execução registrado no Git (100755)" \
    "git -C '$WORK/repo' ls-files -s infrastructure/postgres/init/02-create-app-role.sh | grep -q '^100755 '"

# ------------------------------------------------------------------------------
# Utilitários de container
# ------------------------------------------------------------------------------
# start_pg <nome> : usa APP_DB_PASSWORD do ambiente atual (pode estar ausente)
start_pg() {
    local name="$1"; shift
    CONTAINERS+=("$name")
    docker run -d --name "$name" \
        -e POSTGRES_USER="$ADMIN_USER" -e POSTGRES_PASSWORD="$RUN_ID-admin" -e POSTGRES_DB="$ADMIN_DB" \
        -e APP_DB_USER=rewit_app "$@" \
        -v "$(host_path "$INIT_DIR"):/docker-entrypoint-initdb.d:ro" \
        -v "$(host_path "$SQL_DIR"):/opt/rewit/postgres:ro" \
        --health-cmd "pg_isready -h 127.0.0.1 -U $ADMIN_USER -d $ADMIN_DB" \
        --health-interval 1s --health-timeout 3s --health-retries 30 \
        "$IMAGE" >/dev/null
}

# wait_pg <nome> -> 0 quando healthy após o init completo; 1 se o container terminar
wait_pg() {
    local name="$1" i state health
    for i in $(seq 1 120); do
        state=$(docker inspect -f '{{.State.Status}}' "$name")
        if [ "$state" = "exited" ] || [ "$state" = "dead" ]; then return 1; fi
        health=$(docker inspect -f '{{.State.Health.Status}}' "$name")
        if [ "$health" = "healthy" ] && docker logs "$name" 2>&1 | grep -q 'PostgreSQL init process complete'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

admin_sql() { docker exec "$1" psql -X -q -At -U "$ADMIN_USER" -d "$ADMIN_DB" -c "$2"; }

role_attrs() {
    admin_sql "$1" "SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolcanlogin,
                           (SELECT count(*) FROM pg_auth_members am WHERE am.member = r.oid),
                           (SELECT count(*) FROM pg_shdepend d WHERE d.refobjid = r.oid AND d.deptype = 'o')
                    FROM pg_roles r WHERE rolname = 'rewit_app'"
}

superusers() { admin_sql "$1" "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles WHERE rolsuper"; }

# login_as_app <container> <senha>: conexão TCP pelo IP do container (regra scram-sha-256;
# 127.0.0.1 é trust na imagem oficial e não provaria a senha).
login_as_app() {
    PGPASSWORD="$2" docker exec -e PGPASSWORD "$1" sh -c \
        'psql -X -At -h "$(hostname -i | cut -d" " -f1)" -U rewit_app -d '"$ADMIN_DB"' -c "SELECT session_user"' \
        2>/dev/null | grep -qx rewit_app
}

logs_contain() { docker logs "$1" 2>&1 | grep -qF -- "$2"; }

expected_attrs="f|f|f|f|f|t|0|0"

# ------------------------------------------------------------------------------
# 2. Provisionamento inicial com senhas especiais (SEC-2)
# ------------------------------------------------------------------------------
declare -a CASE_NAMES=(
    "senha simples"
    "senha com apóstrofo"
    "senha com aspas duplas, aspas simples e barras invertidas"
    "fragmento que encerraria literal e concederia SUPERUSER"
    "fragmento com DDL, dollar-quoting e meta-comandos psql"
    "espaços nas extremidades e caracteres não ASCII"
)
declare -a CASE_PASSWORDS=(
    "SenhaSimples-$RUN_ID"
    "d'Avila-$RUN_ID"
    "a\"b'c\\d\\\\e\"\"-$RUN_ID"
    "x' SUPERUSER; --$RUN_ID"
    "x'; CREATE ROLE rewit_evil SUPERUSER LOGIN; DROP TABLE IF EXISTS pg_temp.x; \$\$ \\! id :'app_user' -- $RUN_ID"
    "  ção ñ €-$RUN_ID  "
)

for idx in "${!CASE_NAMES[@]}"; do
    name="${CASE_NAMES[$idx]}"
    pw="${CASE_PASSWORDS[$idx]}"
    c="$RUN_ID-case$idx"
    echo "== Provisionamento inicial: $name"
    APP_DB_PASSWORD="$pw" start_pg "$c" -e APP_DB_PASSWORD
    if wait_pg "$c"; then pass "init concluído e container healthy"; else fail "init concluído e container healthy"; fi
    check "atributos da role sem privilégios administrativos" "[ \"\$(role_attrs '$c')\" = '$expected_attrs' ]"
    check "nenhum superuser além do administrativo" "[ \"\$(superusers '$c')\" = '$ADMIN_USER' ]"
    check "nenhuma role extra criada por injeção" "[ -z \"\$(admin_sql '$c' \"SELECT rolname FROM pg_roles WHERE rolname = 'rewit_evil'\")\" ]"
    check "login real com a senha configurada" "login_as_app '$c' \"\$pw\""
    check "login com senha incorreta é recusado" "! login_as_app '$c' \"wrong-\$pw\""
    check "senha ausente dos logs do container" "! logs_contain '$c' \"\$pw\""
    check "verificador SCRAM armazenado" "[ \"\$(admin_sql '$c' \"SELECT left(rolpassword, 14) FROM pg_authid WHERE rolname = 'rewit_app'\")\" = 'SCRAM-SHA-256\$' ]"
done

# ------------------------------------------------------------------------------
# 3. Variável ausente ou vazia (falha explícita, sem fallback)
# ------------------------------------------------------------------------------
for variant in ausente vazia; do
    c="$RUN_ID-missing-$variant"
    echo "== APP_DB_PASSWORD $variant"
    if [ "$variant" = ausente ]; then
        # Registrado aqui porque o subshell não propaga CONTAINERS para a limpeza
        CONTAINERS+=("$c")
        (unset APP_DB_PASSWORD; start_pg "$c")
    else
        APP_DB_PASSWORD="" start_pg "$c" -e APP_DB_PASSWORD
    fi
    if wait_pg "$c"; then fail "init deve falhar"; else pass "init falha e o container termina"; fi
    check "código de saída diferente de zero" "[ \"\$(docker inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
    check "mensagem explícita sobre APP_DB_PASSWORD" "logs_contain '$c' 'APP_DB_PASSWORD ausente ou vazia'"
done

# ------------------------------------------------------------------------------
# 4. Reprovisionamento, rotação, saneamento de role pré-existente e script administrativo
# ------------------------------------------------------------------------------
c="$RUN_ID-rotation"
PW1="Rotacao-1-'\"\\-$RUN_ID"
PW2="Rotacao-2-x' SUPERUSER; --$RUN_ID"
PW3="Rotacao-3-\$\$\\'-$RUN_ID"
echo "== Reprovisionamento e rotação em banco existente"
APP_DB_PASSWORD="$PW1" start_pg "$c" -e APP_DB_PASSWORD
wait_pg "$c" && pass "banco inicial pronto" || fail "banco inicial pronto"

APP_DB_PASSWORD="$PW1" docker exec -e APP_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && pass "reprovisionamento repetido com a mesma senha" || fail "reprovisionamento repetido com a mesma senha"
check "login após reprovisionamento" "login_as_app '$c' \"\$PW1\""

# Rotação sob TTY: setsid garante que a senha venha da entrada padrão e não do terminal
if APP_DB_PASSWORD="$PW2" timeout 60 docker exec -t -e APP_DB_PASSWORD "$c" \
        /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1; then
    pass "rotação via script de init (com TTY alocado)"
else
    fail "rotação via script de init (com TTY alocado)"
fi
check "nova senha aceita" "login_as_app '$c' \"\$PW2\""
check "senha anterior recusada" "! login_as_app '$c' \"\$PW1\""

APP_DB_PASSWORD="" docker exec -e APP_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && fail "rotação com senha vazia deve falhar" || pass "rotação com senha vazia falha sem alterar a role"
check "senha vigente preservada após tentativa vazia" "login_as_app '$c' \"\$PW2\""

# Role pré-existente comprometida (ex.: resultado da injeção antiga): o provisionamento a saneia
admin_sql "$c" "ALTER ROLE rewit_app SUPERUSER CREATEROLE CREATEDB REPLICATION BYPASSRLS; GRANT pg_read_all_data TO rewit_app;" >/dev/null
APP_DB_PASSWORD="$PW2" docker exec -e APP_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && pass "reprovisionamento de role pré-existente com privilégios" || fail "reprovisionamento de role pré-existente com privilégios"
check "atributos privilegiados e memberships removidos" "[ \"\$(role_attrs '$c')\" = '$expected_attrs' ]"

# Falha dentro do psql (role dona de objeto) precisa propagar para o código de saída do script
admin_sql "$c" "CREATE TABLE rewit_provtest_owned (id INT); ALTER TABLE rewit_provtest_owned OWNER TO rewit_app;" >/dev/null
APP_DB_PASSWORD="$PW1" docker exec -e APP_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && fail "script falha quando a role é owner de objetos" || pass "script falha quando a role é owner de objetos"
check "senha não foi trocada pela execução recusada" "login_as_app '$c' \"\$PW2\""
admin_sql "$c" "DROP TABLE rewit_provtest_owned;" >/dev/null

# Script SQL administrativo direto (psql -v app_user=... -f), senha pela entrada padrão
if printf '%s\n%s\n' "$PW3" "$PW3" | docker exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 \
        -U "$ADMIN_USER" -d "$ADMIN_DB" -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    pass "script administrativo provision-runtime-role.sql executa com -v app_user"
else
    fail "script administrativo provision-runtime-role.sql executa com -v app_user"
fi
check "rotação pelo script administrativo" "login_as_app '$c' \"\$PW3\""
check "atributos após script administrativo" "[ \"\$(role_attrs '$c')\" = '$expected_attrs' ]"

if printf 'x\nx\n' | docker exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$ADMIN_DB" \
        -v app_user="$ADMIN_USER" -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    fail "script administrativo recusa a própria role administrativa"
else
    pass "script administrativo recusa a própria role administrativa"
fi
if printf 'x\nx\n' | docker exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$ADMIN_DB" \
        -v app_user='bad"name; DROP ROLE x' -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    fail "script administrativo recusa identificador inválido"
else
    pass "script administrativo recusa identificador inválido"
fi
check "superusers inalterados" "[ \"\$(superusers '$c')\" = '$ADMIN_USER' ]"

for secret in "$PW1" "$PW2" "$PW3"; do
    if logs_contain "$c" "$secret"; then fail "senha de rotação ausente dos logs"; else pass "senha de rotação ausente dos logs"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "RESULTADO: todos os casos de provisionamento passaram."
else
    echo "RESULTADO: $FAILURES verificação(ões) falharam."
    exit 1
fi
