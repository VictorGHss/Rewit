#!/usr/bin/env bash
# ==============================================================================
# REWIT - TESTE DE PROVISIONAMENTO DAS ROLES DE RUNTIME (SEC-2, SEC-3, SEC-4, S3-A13-F2)
# ==============================================================================
# Reproduz a inicialização do PostgreSQL a partir de um CLONE LIMPO do repositório (com
# core.autocrlf=true, como num checkout padrão do Windows) em containers descartáveis, sem
# portas publicadas e sem volumes nomeados. Nenhum serviço existente é acessado.
#
# Uso:
#   infrastructure/postgres/tests/provisioning-test.sh [commit]         (padrão: HEAD)
#   infrastructure/postgres/tests/provisioning-test.sh --working-tree   (arquivos locais sem commit)
#   PROVISIONING_TEST_RUNTIME=podman ...                                (Docker ou Podman rootless)
# As senhas de teste nunca são impressas; a saída mostra apenas o nome de cada caso.
# ==============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

RUNTIME="${PROVISIONING_TEST_RUNTIME:-docker}"
IMAGE="${PROVISIONING_TEST_IMAGE:-postgis/postgis:18-3.6}"
SOURCE_REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
MODE="commit"
if [ "${1:-}" = "--working-tree" ]; then
    MODE="working-tree"
    COMMIT="working-tree"
else
    COMMIT=$(git -C "$SOURCE_REPO" rev-parse --verify "${1:-HEAD}^{commit}")
fi
RUN_ID="rewit-provtest-$(date +%s)-$$"
ADMIN_USER="rewit_provtest_admin"
ADMIN_DB="rewit_provtest"
WORK=$(mktemp -d)
chmod 0755 "$WORK"
MOUNT_OPTS="ro"
if [ "$RUNTIME" = "podman" ]; then MOUNT_OPTS="ro,z"; fi
# No Git Bash, git.exe e docker.exe são nativos: use o mesmo caminho Windows em todos os comandos
if command -v cygpath >/dev/null 2>&1; then WORK=$(cygpath -m "$WORK"); fi
CONTAINERS=()
FAILURES=0

cleanup() {
    for c in "${CONTAINERS[@]}"; do "$RUNTIME" rm -f -v "$c" >/dev/null 2>&1 || true; done
    rm -rf "$WORK"
}
trap cleanup EXIT

host_path() { printf '%s' "$1"; }

pass() { printf '  [OK]    %s\n' "$1"; }
fail() { printf '  [FALHA] %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }

# ------------------------------------------------------------------------------
# 1. Origem dos scripts (clone limpo ou working-tree)
# ------------------------------------------------------------------------------
if [ "$MODE" = "working-tree" ]; then
    echo "== Working tree local ($SOURCE_REPO)"
    mkdir -p "$WORK/repo/infrastructure/postgres/init"
    cp "$SOURCE_REPO/infrastructure/postgres/provision-runtime-role.sql" "$WORK/repo/infrastructure/postgres/"
    cp "$SOURCE_REPO/infrastructure/postgres/init/"* "$WORK/repo/infrastructure/postgres/init/"
    chmod 0755 "$WORK/repo/infrastructure/postgres/init/02-create-app-role.sh"
else
    echo "== Clone limpo de $COMMIT (core.autocrlf=true)"
    git clone --quiet --no-checkout --config core.autocrlf=true "$SOURCE_REPO" "$WORK/repo"
    git -C "$WORK/repo" checkout --quiet "$COMMIT"
fi
INIT_DIR="$WORK/repo/infrastructure/postgres/init"
SQL_DIR="$WORK/repo/infrastructure/postgres"
chmod -R a+rX "$WORK/repo"
SCRIPT="$INIT_DIR/02-create-app-role.sh"

if [ ! -f "$SCRIPT" ] || [ ! -f "$SQL_DIR/provision-runtime-role.sql" ]; then
    echo "Origem sem os scripts de provisionamento"; exit 1
fi
# Conta bytes CR diretamente: o grep do Git Bash descarta CR em modo texto e não serviria de prova
cr_count() { tr -dc '\r' < "$1" | wc -c | tr -d ' '; }
check "script de init sem CR" "[ \"\$(cr_count '$SCRIPT')\" = 0 ]"
check "SQL de provisionamento sem CR" "[ \"\$(cr_count '$SQL_DIR/provision-runtime-role.sql')\" = 0 ]"
check "shebang válido (#!/bin/sh)" "[ \"\$(head -n1 '$SCRIPT')\" = '#!/bin/sh' ]"
if [ "$MODE" = "working-tree" ]; then
    check "bit de execução presente no working tree" "[ -x '$SOURCE_REPO/infrastructure/postgres/init/02-create-app-role.sh' ]"
else
    check "bit de execução registrado no Git (100755)" \
        "git -C '$WORK/repo' ls-files -s infrastructure/postgres/init/02-create-app-role.sh | grep -q '^100755 '"
fi

# ------------------------------------------------------------------------------
# Utilitários de container
# ------------------------------------------------------------------------------
# start_pg <nome> [docker args...]
start_pg() {
    local name="$1"; shift
    CONTAINERS+=("$name")
    "$RUNTIME" run -d --name "$name" \
        -e POSTGRES_USER="$ADMIN_USER" -e POSTGRES_PASSWORD="$RUN_ID-admin" -e POSTGRES_DB="$ADMIN_DB" \
        -e APP_DB_USER=rewit_app -e MEDIA_DB_USER=rewit_media -e MOD_DB_USER=rewit_mod "$@" \
        -v "$(host_path "$INIT_DIR"):/docker-entrypoint-initdb.d:$MOUNT_OPTS" \
        -v "$(host_path "$SQL_DIR"):/opt/rewit/postgres:$MOUNT_OPTS" \
        --health-cmd "pg_isready -h 127.0.0.1 -U $ADMIN_USER -d $ADMIN_DB" \
        --health-interval 1s --health-timeout 3s --health-retries 30 \
        "$IMAGE" >/dev/null
}

# wait_pg <nome> -> 0 quando healthy após o init completo; 1 se o container terminar
wait_pg() {
    local name="$1" state health
    for _ in $(seq 1 120); do
        state=$("$RUNTIME" inspect -f '{{.State.Status}}' "$name")
        if [ "$state" = "exited" ] || [ "$state" = "dead" ] || [ "$state" = "stopped" ]; then return 1; fi
        health=$("$RUNTIME" inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo "")
        if [ "$health" = "healthy" ] && "$RUNTIME" logs "$name" 2>&1 | grep -q 'PostgreSQL init process complete'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

admin_sql() { "$RUNTIME" exec "$1" psql -X -q -At -U "$ADMIN_USER" -d "$ADMIN_DB" -c "$2"; }

role_attrs() {
    local container="$1" role="${2:-rewit_app}"
    admin_sql "$container" "SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolinherit, rolcanlogin,
                           (SELECT count(*) FROM pg_auth_members am WHERE am.member = r.oid),
                           (SELECT count(*) FROM pg_shdepend d WHERE d.refobjid = r.oid AND d.deptype = 'o')
                    FROM pg_roles r WHERE rolname = '$role'"
}

superusers() { admin_sql "$1" "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles WHERE rolsuper"; }

# login_as_role <container> <role> <senha>: conexão TCP pelo IP do container (regra scram-sha-256;
# 127.0.0.1 é trust na imagem oficial e não provaria a senha).
login_as_role() {
    local container="$1" role="$2" pw="$3"
    PGPASSWORD="$pw" "$RUNTIME" exec -e PGPASSWORD "$container" sh -c \
        'psql -X -At -h "$(hostname -i | cut -d" " -f1)" -U '"$role"' -d '"$ADMIN_DB"' -c "SELECT session_user"' \
        2>/dev/null | grep -qx "$role"
}

can_set_role() {
    local container="$1" from_role="$2" pw="$3" to_role="$4"
    PGPASSWORD="$pw" "$RUNTIME" exec -e PGPASSWORD "$container" sh -c \
        'psql -X -At -h "$(hostname -i | cut -d" " -f1)" -U '"$from_role"' -d '"$ADMIN_DB"' -c "SET ROLE '"$to_role"'"' \
        >/dev/null 2>&1
}

logs_contain() { "$RUNTIME" logs "$1" 2>&1 | grep -qF -- "$2"; }

expected_attrs="f|f|f|f|f|f|t|0|0"

# ------------------------------------------------------------------------------
# 2. Provisionamento inicial com senhas especiais (SEC-2 / S3-A13-F2)
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
    media_pw="media-$pw"
    mod_pw="mod-$pw"
    c="$RUN_ID-case$idx"
    echo "== Provisionamento inicial: $name"
    APP_DB_PASSWORD="$pw" MEDIA_DB_PASSWORD="$media_pw" MOD_DB_PASSWORD="$mod_pw" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
    if wait_pg "$c"; then pass "init concluído e container healthy"; else fail "init concluído e container healthy"; fi
    check "atributos de rewit_app sem privilégios administrativos" "[ \"\$(role_attrs '$c' rewit_app)\" = '$expected_attrs' ]"
    check "atributos de rewit_media sem privilégios administrativos" "[ \"\$(role_attrs '$c' rewit_media)\" = '$expected_attrs' ]"
    check "atributos de rewit_mod sem privilégios administrativos" "[ \"\$(role_attrs '$c' rewit_mod)\" = '$expected_attrs' ]"
    check "nenhum superuser além do administrativo" "[ \"\$(superusers '$c')\" = '$ADMIN_USER' ]"
    check "nenhuma role extra criada por injeção" "[ -z \"\$(admin_sql '$c' \"SELECT rolname FROM pg_roles WHERE rolname = 'rewit_evil'\")\" ]"
    check "login real de rewit_app com a senha configurada" "login_as_role '$c' rewit_app \"\$pw\""
    check "login real de rewit_media com a senha configurada" "login_as_role '$c' rewit_media \"\$media_pw\""
    check "login real de rewit_mod com a senha configurada" "login_as_role '$c' rewit_mod \"\$mod_pw\""
    check "login de rewit_app com senha incorreta é recusado" "! login_as_role '$c' rewit_app \"wrong-\$pw\""
    check "login de rewit_media com senha de rewit_app é recusado" "! login_as_role '$c' rewit_media \"\$pw\""
    check "login de rewit_mod com senha de rewit_app é recusado" "! login_as_role '$c' rewit_mod \"\$pw\""
    check "rewit_app não pode executar SET ROLE rewit_media nem rewit_mod" "! can_set_role '$c' rewit_app \"\$pw\" rewit_media && ! can_set_role '$c' rewit_app \"\$pw\" rewit_mod"
    check "rewit_media não pode executar SET ROLE rewit_app nem rewit_mod" "! can_set_role '$c' rewit_media \"\$media_pw\" rewit_app && ! can_set_role '$c' rewit_media \"\$media_pw\" rewit_mod"
    check "rewit_mod não pode executar SET ROLE rewit_app nem rewit_media" "! can_set_role '$c' rewit_mod \"\$mod_pw\" rewit_app && ! can_set_role '$c' rewit_mod \"\$mod_pw\" rewit_media"
    check "senhas ausentes dos logs do container" "! logs_contain '$c' \"\$pw\" && ! logs_contain '$c' \"\$media_pw\" && ! logs_contain '$c' \"\$mod_pw\""
    check "verificador SCRAM armazenado para rewit_app, rewit_media e rewit_mod" \
        "[ \"\$(admin_sql '$c' \"SELECT left(rolpassword, 14) FROM pg_authid WHERE rolname = 'rewit_app'\")\" = 'SCRAM-SHA-256\$' ] && [ \"\$(admin_sql '$c' \"SELECT left(rolpassword, 14) FROM pg_authid WHERE rolname = 'rewit_media'\")\" = 'SCRAM-SHA-256\$' ] && [ \"\$(admin_sql '$c' \"SELECT left(rolpassword, 14) FROM pg_authid WHERE rolname = 'rewit_mod'\")\" = 'SCRAM-SHA-256\$' ]"
done

# ------------------------------------------------------------------------------
# 3. Variável ausente, vazia ou repetida (falha explícita, sem fallback)
# ------------------------------------------------------------------------------
for variant in ausente vazia; do
    c="$RUN_ID-missing-app-$variant"
    echo "== APP_DB_PASSWORD $variant"
    if [ "$variant" = ausente ]; then
        CONTAINERS+=("$c")
        (unset APP_DB_PASSWORD; MEDIA_DB_PASSWORD="Media-$RUN_ID" MOD_DB_PASSWORD="Mod-$RUN_ID" start_pg "$c" -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD)
    else
        APP_DB_PASSWORD="" MEDIA_DB_PASSWORD="Media-$RUN_ID" MOD_DB_PASSWORD="Mod-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
    fi
    if wait_pg "$c"; then fail "init deve falhar"; else pass "init falha e o container termina"; fi
    check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
    check "mensagem explícita sobre APP_DB_PASSWORD" "logs_contain '$c' 'APP_DB_PASSWORD ausente ou vazia'"
done

for variant in ausente vazia; do
    c="$RUN_ID-missing-media-$variant"
    echo "== MEDIA_DB_PASSWORD $variant"
    if [ "$variant" = ausente ]; then
        CONTAINERS+=("$c")
        (unset MEDIA_DB_PASSWORD; APP_DB_PASSWORD="App-$RUN_ID" MOD_DB_PASSWORD="Mod-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MOD_DB_PASSWORD)
    else
        APP_DB_PASSWORD="App-$RUN_ID" MEDIA_DB_PASSWORD="" MOD_DB_PASSWORD="Mod-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
    fi
    if wait_pg "$c"; then fail "init deve falhar"; else pass "init falha e o container termina"; fi
    check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
    check "mensagem explícita sobre MEDIA_DB_PASSWORD" "logs_contain '$c' 'MEDIA_DB_PASSWORD ausente ou vazia'"
done

for variant in ausente vazia; do
    c="$RUN_ID-missing-mod-$variant"
    echo "== MOD_DB_PASSWORD $variant"
    if [ "$variant" = ausente ]; then
        CONTAINERS+=("$c")
        (unset MOD_DB_PASSWORD; APP_DB_PASSWORD="App-$RUN_ID" MEDIA_DB_PASSWORD="Media-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD)
    else
        APP_DB_PASSWORD="App-$RUN_ID" MEDIA_DB_PASSWORD="Media-$RUN_ID" MOD_DB_PASSWORD="" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
    fi
    if wait_pg "$c"; then fail "init deve falhar"; else pass "init falha e o container termina"; fi
    check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
    check "mensagem explícita sobre MOD_DB_PASSWORD" "logs_contain '$c' 'MOD_DB_PASSWORD ausente ou vazia'"
done

c="$RUN_ID-same-password"
echo "== MOD_DB_PASSWORD igual a APP_DB_PASSWORD"
APP_DB_PASSWORD="Same-$RUN_ID" MEDIA_DB_PASSWORD="Media-$RUN_ID" MOD_DB_PASSWORD="Same-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
if wait_pg "$c"; then fail "init deve falhar com senhas iguais"; else pass "init falha com senhas iguais"; fi
check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
check "mensagem explícita sobre independência de senhas" "logs_contain '$c' 'MOD_DB_PASSWORD deve ser independente e diferente de APP_DB_PASSWORD'"

c="$RUN_ID-same-media-password"
echo "== MEDIA_DB_PASSWORD igual a APP_DB_PASSWORD"
APP_DB_PASSWORD="Same-$RUN_ID" MEDIA_DB_PASSWORD="Same-$RUN_ID" MOD_DB_PASSWORD="Mod-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
if wait_pg "$c"; then fail "init deve falhar com MEDIA_DB_PASSWORD igual a APP_DB_PASSWORD"; else pass "init falha com MEDIA_DB_PASSWORD igual a APP_DB_PASSWORD"; fi
check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
check "mensagem explícita sobre independência de MEDIA_DB_PASSWORD" "logs_contain '$c' 'MEDIA_DB_PASSWORD deve ser independente e diferente de APP_DB_PASSWORD'"

# ------------------------------------------------------------------------------
# 3b. APP_DB_USER / MEDIA_DB_USER / MOD_DB_USER diferentes das roles fixas (P3-2 / SEC-F2-03)
# ------------------------------------------------------------------------------
c="$RUN_ID-alt-user"
echo "== APP_DB_USER=rewit_alt"
APP_DB_PASSWORD="Alt-$RUN_ID" MEDIA_DB_PASSWORD="MediaAlt-$RUN_ID" MOD_DB_PASSWORD="ModAlt-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD -e APP_DB_USER=rewit_alt
if wait_pg "$c"; then fail "init deve falhar com APP_DB_USER=rewit_alt"; else pass "init falha com APP_DB_USER=rewit_alt"; fi
check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
check "mensagem explícita sobre a role fixa rewit_app" "logs_contain '$c' 'a role de runtime é fixa (rewit_app)'"

c="$RUN_ID-alt-media-user"
echo "== MEDIA_DB_USER=rewit_media_alt"
APP_DB_PASSWORD="Alt-$RUN_ID" MEDIA_DB_PASSWORD="MediaAlt-$RUN_ID" MOD_DB_PASSWORD="ModAlt-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD -e MEDIA_DB_USER=rewit_media_alt
if wait_pg "$c"; then fail "init deve falhar com MEDIA_DB_USER=rewit_media_alt"; else pass "init falha com MEDIA_DB_USER=rewit_media_alt"; fi
check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
check "mensagem explícita sobre a role fixa rewit_media" "logs_contain '$c' 'a role de runtime é fixa (rewit_media)'"

c="$RUN_ID-alt-mod-user"
echo "== MOD_DB_USER=rewit_mod_alt"
APP_DB_PASSWORD="Alt-$RUN_ID" MEDIA_DB_PASSWORD="MediaAlt-$RUN_ID" MOD_DB_PASSWORD="ModAlt-$RUN_ID" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD -e MOD_DB_USER=rewit_mod_alt
if wait_pg "$c"; then fail "init deve falhar com MOD_DB_USER=rewit_mod_alt"; else pass "init falha com MOD_DB_USER=rewit_mod_alt"; fi
check "código de saída diferente de zero" "[ \"\$('$RUNTIME' inspect -f '{{.State.ExitCode}}' '$c')\" != 0 ]"
check "mensagem explícita sobre a role fixa rewit_mod" "logs_contain '$c' 'a role de runtime é fixa (rewit_mod)'"

# ------------------------------------------------------------------------------
# 4. Reprovisionamento, rotação, saneamento de roles pré-existentes e script administrativo
# ------------------------------------------------------------------------------
c="$RUN_ID-rotation"
PW1="Rotacao-1-'\"\\-$RUN_ID"
PW2="Rotacao-2-x' SUPERUSER; --$RUN_ID"
PW3="Rotacao-3-\$\$\\'-$RUN_ID"
MEDPW1="MediaRot-1-'\"\\-$RUN_ID"
MEDPW2="MediaRot-2-x' SUPERUSER; --$RUN_ID"
MEDPW3="MediaRot-3-\$\$\\'-$RUN_ID"
MPW1="ModRot-1-'\"\\-$RUN_ID"
MPW2="ModRot-2-x' SUPERUSER; --$RUN_ID"
MPW3="ModRot-3-\$\$\\'-$RUN_ID"
echo "== Reprovisionamento e rotação em banco existente"
APP_DB_PASSWORD="$PW1" MEDIA_DB_PASSWORD="$MEDPW1" MOD_DB_PASSWORD="$MPW1" start_pg "$c" -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD
wait_pg "$c" && pass "banco inicial pronto" || fail "banco inicial pronto"

APP_DB_PASSWORD="$PW1" MEDIA_DB_PASSWORD="$MEDPW1" MOD_DB_PASSWORD="$MPW1" "$RUNTIME" exec -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && pass "reprovisionamento repetido com as mesmas senhas" || fail "reprovisionamento repetido com as mesmas senhas"
check "login de rewit_app após reprovisionamento" "login_as_role '$c' rewit_app \"\$PW1\""
check "login de rewit_media após reprovisionamento" "login_as_role '$c' rewit_media \"\$MEDPW1\""
check "login de rewit_mod após reprovisionamento" "login_as_role '$c' rewit_mod \"\$MPW1\""

# Rotação sob TTY: setsid garante que as senhas venham da entrada padrão e não do terminal
if APP_DB_PASSWORD="$PW2" MEDIA_DB_PASSWORD="$MEDPW2" MOD_DB_PASSWORD="$MPW2" timeout --kill-after=5 60 "$RUNTIME" exec -t -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD "$c" \
        /docker-entrypoint-initdb.d/02-create-app-role.sh </dev/null >/dev/null 2>&1; then
    pass "rotação via script de init (com TTY alocado)"
else
    fail "rotação via script de init (com TTY alocado)"
fi
check "nova senha de rewit_app aceita" "login_as_role '$c' rewit_app \"\$PW2\""
check "senha anterior de rewit_app recusada" "! login_as_role '$c' rewit_app \"\$PW1\""
check "nova senha de rewit_media aceita" "login_as_role '$c' rewit_media \"\$MEDPW2\""
check "senha anterior de rewit_media recusada" "! login_as_role '$c' rewit_media \"\$MEDPW1\""
check "nova senha de rewit_mod aceita" "login_as_role '$c' rewit_mod \"\$MPW2\""
check "senha anterior de rewit_mod recusada" "! login_as_role '$c' rewit_mod \"\$MPW1\""

APP_DB_PASSWORD="" MEDIA_DB_PASSWORD="$MEDPW2" MOD_DB_PASSWORD="$MPW2" "$RUNTIME" exec -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && fail "rotação com senha vazia deve falhar" || pass "rotação com senha vazia falha sem alterar as roles"
check "senhas vigentes preservadas após tentativa vazia" "login_as_role '$c' rewit_app \"\$PW2\" && login_as_role '$c' rewit_media \"\$MEDPW2\" && login_as_role '$c' rewit_mod \"\$MPW2\""

# Roles pré-existentes comprometidas (inclusive membership cruzada): o provisionamento saneia todas
admin_sql "$c" "ALTER ROLE rewit_app SUPERUSER CREATEROLE CREATEDB REPLICATION BYPASSRLS INHERIT; ALTER ROLE rewit_media SUPERUSER CREATEROLE CREATEDB REPLICATION BYPASSRLS INHERIT; ALTER ROLE rewit_mod SUPERUSER CREATEROLE CREATEDB REPLICATION BYPASSRLS INHERIT; GRANT pg_read_all_data TO rewit_app; GRANT pg_read_all_data TO rewit_media; GRANT pg_read_all_data TO rewit_mod; GRANT rewit_media TO rewit_app; GRANT rewit_mod TO rewit_media; GRANT rewit_mod TO rewit_app;" >/dev/null
APP_DB_PASSWORD="$PW2" MEDIA_DB_PASSWORD="$MEDPW2" MOD_DB_PASSWORD="$MPW2" "$RUNTIME" exec -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && pass "reprovisionamento de roles pré-existentes com privilégios" || fail "reprovisionamento de roles pré-existentes com privilégios"
check "atributos privilegiados e memberships removidos de rewit_app" "[ \"\$(role_attrs '$c' rewit_app)\" = '$expected_attrs' ]"
check "atributos privilegiados e memberships removidos de rewit_media" "[ \"\$(role_attrs '$c' rewit_media)\" = '$expected_attrs' ]"
check "atributos privilegiados e memberships removidos de rewit_mod" "[ \"\$(role_attrs '$c' rewit_mod)\" = '$expected_attrs' ]"
check "isolamento SET ROLE restaurado" "! can_set_role '$c' rewit_app \"\$PW2\" rewit_media && ! can_set_role '$c' rewit_app \"\$PW2\" rewit_mod && ! can_set_role '$c' rewit_media \"\$MEDPW2\" rewit_mod && ! can_set_role '$c' rewit_mod \"\$MPW2\" rewit_app"

# Falha dentro do psql (role dona de objeto) precisa propagar para o código de saída do script
admin_sql "$c" "CREATE TABLE rewit_provtest_owned (id INT); ALTER TABLE rewit_provtest_owned OWNER TO rewit_media;" >/dev/null
APP_DB_PASSWORD="$PW1" MEDIA_DB_PASSWORD="$MEDPW1" MOD_DB_PASSWORD="$MPW1" "$RUNTIME" exec -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD "$c" /docker-entrypoint-initdb.d/02-create-app-role.sh >/dev/null 2>&1 \
    && fail "script falha quando rewit_media é owner de objetos" || pass "script falha quando rewit_media é owner de objetos"
check "senha de rewit_media não foi trocada pela execução recusada" "login_as_role '$c' rewit_media \"\$MEDPW2\""
admin_sql "$c" "DROP TABLE rewit_provtest_owned;" >/dev/null

# Script SQL administrativo direto (psql -v app_user=... -f), senha pela entrada padrão
if printf '%s\n%s\n' "$PW3" "$PW3" | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 \
        -U "$ADMIN_USER" -d "$ADMIN_DB" -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    pass "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_app"
else
    fail "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_app"
fi
if printf '%s\n%s\n' "$MEDPW3" "$MEDPW3" | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 \
        -U "$ADMIN_USER" -d "$ADMIN_DB" -v app_user=rewit_media -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    pass "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_media"
else
    fail "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_media"
fi
if printf '%s\n%s\n' "$MPW3" "$MPW3" | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 \
        -U "$ADMIN_USER" -d "$ADMIN_DB" -v app_user=rewit_mod -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    pass "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_mod"
else
    fail "script administrativo provision-runtime-role.sql executa com -v app_user=rewit_mod"
fi
check "rotação de rewit_app pelo script administrativo" "login_as_role '$c' rewit_app \"\$PW3\""
check "rotação de rewit_media pelo script administrativo" "login_as_role '$c' rewit_media \"\$MEDPW3\""
check "rotação de rewit_mod pelo script administrativo" "login_as_role '$c' rewit_mod \"\$MPW3\""
check "atributos das três roles após script administrativo" \
    "[ \"\$(role_attrs '$c' rewit_app)\" = '$expected_attrs' ] && [ \"\$(role_attrs '$c' rewit_media)\" = '$expected_attrs' ] && [ \"\$(role_attrs '$c' rewit_mod)\" = '$expected_attrs' ]"

if printf 'x\nx\n' | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$ADMIN_DB" \
        -v app_user="$ADMIN_USER" -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    fail "script administrativo recusa a própria role administrativa"
else
    pass "script administrativo recusa a própria role administrativa"
fi
if printf 'x\nx\n' | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$ADMIN_DB" \
        -v app_user='bad"name; DROP ROLE x' -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    fail "script administrativo recusa identificador inválido"
else
    pass "script administrativo recusa identificador inválido"
fi
if printf 'x\nx\n' | "$RUNTIME" exec -i "$c" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$ADMIN_DB" \
        -v app_user=rewit_alt -f /opt/rewit/postgres/provision-runtime-role.sql >/dev/null 2>&1; then
    fail "script administrativo recusa role diferente de rewit_app/rewit_media/rewit_mod"
else
    pass "script administrativo recusa role diferente de rewit_app/rewit_media/rewit_mod"
fi
check "nenhuma role rewit_alt criada" "[ -z \"\$(admin_sql '$c' \"SELECT rolname FROM pg_roles WHERE rolname = 'rewit_alt'\")\" ]"
check "superusers inalterados" "[ \"\$(superusers '$c')\" = '$ADMIN_USER' ]"

for secret in "$PW1" "$PW2" "$PW3" "$MEDPW1" "$MEDPW2" "$MEDPW3" "$MPW1" "$MPW2" "$MPW3"; do
    if logs_contain "$c" "$secret"; then fail "senha de rotação ausente dos logs"; else pass "senha de rotação ausente dos logs"; fi
done

# ------------------------------------------------------------------------------
# 5. Bootstrap das cinco extensões em public (incluindo unaccent) e spatial_ref_sys
# ------------------------------------------------------------------------------
echo "== Bootstrap das cinco extensões obrigatórias em public (01-init-postgis.sql)"
check "cinco extensões instaladas no schema public" \
    "[ \"\$(admin_sql '$c' \"SELECT string_agg(e.extname, ',' ORDER BY e.extname) FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE n.nspname = 'public' AND e.extname IN ('uuid-ossp','postgis','pg_trgm','pgcrypto','unaccent')\")\" = 'pg_trgm,pgcrypto,postgis,unaccent,uuid-ossp' ]"
check "public.spatial_ref_sys legível (SELECT) e sem escrita para as três roles runtime" \
    "[ \"\$(admin_sql '$c' \"SELECT count(*) FROM unnest(ARRAY['rewit_app','rewit_media','rewit_mod']) r(name) WHERE has_table_privilege(r.name, 'public.spatial_ref_sys', 'SELECT') AND NOT has_table_privilege(r.name, 'public.spatial_ref_sys', 'INSERT') AND NOT has_table_privilege(r.name, 'public.spatial_ref_sys', 'UPDATE') AND NOT has_table_privilege(r.name, 'public.spatial_ref_sys', 'DELETE')\")\" = '3' ]"

# ------------------------------------------------------------------------------
# 6. Cenário gerenciado: administrador não-superuser com CREATEROLE (F2-DEPLOY-03.5.1)
# ------------------------------------------------------------------------------
c_mgd="$RUN_ID-managed-createrole"
MGD_DB="rewit_managed_test"
MGD_ADMIN="managed_admin"
MGD_ADMIN_PW="MgdAdmin-$RUN_ID"
MGD_MIG="rewit_migrator"
MGD_MIG_PW="MgdMig-$RUN_ID"
MGD_APP_PW1="MgdApp1-'\"\\-$RUN_ID"
MGD_APP_PW2="MgdApp2-x' SUPERUSER; --$RUN_ID"
MGD_MED_PW1="MgdMed1-'\"\\-$RUN_ID"
MGD_MED_PW2="MgdMed2-x' SUPERUSER; --$RUN_ID"
MGD_MOD_PW1="MgdMod1-'\"\\-$RUN_ID"
MGD_MOD_PW2="MgdMod2-x' SUPERUSER; --$RUN_ID"

echo "== Cenário gerenciado: bootstrap sem SUPERUSER para administração de roles (CREATEROLE)"
CONTAINERS+=("$c_mgd")
"$RUNTIME" run -d --name "$c_mgd" \
    -e POSTGRES_USER="$ADMIN_USER" -e POSTGRES_PASSWORD="$RUN_ID-admin" -e POSTGRES_DB="$ADMIN_DB" \
    -v "$(host_path "$INIT_DIR/01-init-postgis.sql"):/opt/rewit/postgres/01-init-postgis.sql:$MOUNT_OPTS" \
    -v "$(host_path "$INIT_DIR/02-create-app-role.sh"):/opt/rewit/postgres/02-create-app-role.sh:$MOUNT_OPTS" \
    -v "$(host_path "$SQL_DIR/provision-runtime-role.sql"):/opt/rewit/postgres/provision-runtime-role.sql:$MOUNT_OPTS" \
    --health-cmd "pg_isready -h 127.0.0.1 -U $ADMIN_USER -d $ADMIN_DB" \
    --health-interval 1s --health-timeout 3s --health-retries 30 \
    "$IMAGE" >/dev/null

wait_pg "$c_mgd" && pass "container descartável para cenário gerenciado pronto" || fail "container descartável para cenário gerenciado pronto"

# Bootstrap inicial feito pelo superuser do container (simula camada administrativa do provedor):
# cria database, instala as 5 extensões em public e cria managed_admin NÃO-SUPERUSER com CREATEROLE
admin_sql "$c_mgd" "CREATE DATABASE $MGD_DB;" >/dev/null
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -f /opt/rewit/postgres/01-init-postgis.sql >/dev/null
admin_sql "$c_mgd" "CREATE ROLE $MGD_ADMIN WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;" >/dev/null
printf '%s\n%s\n' "$MGD_ADMIN_PW" "$MGD_ADMIN_PW" | "$RUNTIME" exec -i "$c_mgd" setsid -w psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "\\password $MGD_ADMIN" >/dev/null
admin_sql "$c_mgd" "GRANT CONNECT ON DATABASE $MGD_DB TO $MGD_ADMIN;" >/dev/null
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "GRANT USAGE, CREATE ON SCHEMA public TO $MGD_ADMIN WITH GRANT OPTION;" >/dev/null

# Helper: executa SQL conectado via TCP como managed_admin (não-superuser)
managed_sql() {
    PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -e PGPASSWORD "$c_mgd" sh -c \
        'psql -X -q -At -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -c "$1"' _ "$1"
}

mgd_db_admin_sql() {
    "$RUNTIME" exec "$c_mgd" psql -X -q -At -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "$1"
}

check "managed_admin é não-superuser com CREATEROLE" \
    "[ \"\$(managed_sql \"SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = '$MGD_ADMIN'\")\" = 'f|t|f|f|f' ]"

# Cria rewit_migrator (não-superuser, sem CREATEROLE) sob managed_admin
managed_sql "CREATE ROLE $MGD_MIG WITH LOGIN NOCREATEROLE NOINHERIT;" >/dev/null
printf '%s\n%s\n' "$MGD_MIG_PW" "$MGD_MIG_PW" | PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -i -e PGPASSWORD "$c_mgd" sh -c \
    'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -c "\password '"$MGD_MIG"'"' >/dev/null
check "rewit_migrator criado por managed_admin sem SUPERUSER/CREATEROLE" \
    "[ \"\$(managed_sql \"SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolinherit, rolcanlogin FROM pg_roles WHERE rolname = '$MGD_MIG'\")\" = 'f|f|f|f|f|f|t' ]"

# Executa 02-create-app-role.sh (que delega a provision-runtime-role.sql) conectado como managed_admin via TCP
if PGPASSWORD="$MGD_ADMIN_PW" APP_DB_PASSWORD="$MGD_APP_PW1" MEDIA_DB_PASSWORD="$MGD_MED_PW1" MOD_DB_PASSWORD="$MGD_MOD_PW1" \
        "$RUNTIME" exec -e PGPASSWORD -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e POSTGRES_USER="$MGD_ADMIN" -e POSTGRES_DB="$MGD_DB" \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_mgd" sh -c \
        'PGHOST="$(hostname -i | cut -d" " -f1)" /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "provisionamento inicial das 3 roles runtime via managed_admin (CREATEROLE sem SUPERUSER)"
else
    fail "provisionamento inicial das 3 roles runtime via managed_admin (CREATEROLE sem SUPERUSER)"
fi

# Reexecução idempotente + rotação de senhas sob managed_admin
if PGPASSWORD="$MGD_ADMIN_PW" APP_DB_PASSWORD="$MGD_APP_PW2" MEDIA_DB_PASSWORD="$MGD_MED_PW2" MOD_DB_PASSWORD="$MGD_MOD_PW2" \
        "$RUNTIME" exec -e PGPASSWORD -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e POSTGRES_USER="$MGD_ADMIN" -e POSTGRES_DB="$MGD_DB" \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_mgd" sh -c \
        'PGHOST="$(hostname -i | cut -d" " -f1)" /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "reexecução idempotente e rotação de senhas via managed_admin"
else
    fail "reexecução idempotente e rotação de senhas via managed_admin"
fi

# Comprovação de atributos, zero memberships próprias, ADMIN OPTION inverso e zero ownership
for rt_role in rewit_app rewit_media rewit_mod; do
    check "atributos seguros de $rt_role sob managed_admin" \
        "[ \"\$(mgd_db_admin_sql \"SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolinherit, rolcanlogin, (SELECT count(*) FROM pg_auth_members am WHERE am.member = r.oid), (SELECT count(*) FROM pg_shdepend d WHERE d.refclassid = 'pg_authid'::regclass AND d.refobjid = r.oid AND d.deptype = 'o') FROM pg_roles r WHERE rolname = '$rt_role'\")\" = '$expected_attrs' ]"
    check "ADMIN OPTION inverso de managed_admin sobre $rt_role existe sem falso positivo" \
        "[ \"\$(mgd_db_admin_sql \"SELECT count(*) FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid JOIN pg_roles m ON m.oid = am.member WHERE g.rolname = '$rt_role' AND m.rolname = '$MGD_ADMIN' AND am.admin_option\")\" = '1' ]"
    check "$rt_role não possui database nem schema em $MGD_DB" \
        "[ \"\$(mgd_db_admin_sql \"SELECT (d.datdba = r.oid), (SELECT count(*) FROM pg_namespace n WHERE n.nspowner = r.oid) FROM pg_roles r JOIN pg_database d ON d.datname = current_database() WHERE r.rolname = '$rt_role'\")\" = 'f|0' ]"
done

# Login real com as senhas rotacionadas no banco gerenciado
login_mgd() {
    local role="$1" pw="$2"
    PGPASSWORD="$pw" "$RUNTIME" exec -e PGPASSWORD "$c_mgd" sh -c \
        'psql -X -At -h "$(hostname -i | cut -d" " -f1)" -U '"$role"' -d '"$MGD_DB"' -c "SELECT session_user, current_user"' \
        2>/dev/null | grep -qx "${role}|${role}"
}
check "login real de rewit_app (senha rotacionada) no cenário gerenciado" "login_mgd rewit_app \"\$MGD_APP_PW2\""
check "login real de rewit_media (senha rotacionada) no cenário gerenciado" "login_mgd rewit_media \"\$MGD_MED_PW2\""
check "login real de rewit_mod (senha rotacionada) no cenário gerenciado" "login_mgd rewit_mod \"\$MGD_MOD_PW2\""
check "senha anterior de rewit_app recusada após rotação gerenciada" "! login_mgd rewit_app \"\$MGD_APP_PW1\""

# Cenários adversariais fail-closed sob managed_admin (não-superuser):
# 1) Role preexistente com BYPASSRLS (ou REPLICATION/SUPERUSER): managed_admin não consegue remover e DEVE falhar
admin_sql "$c_mgd" "ALTER ROLE rewit_app BYPASSRLS;" >/dev/null
if printf '%s\n%s\n' "$MGD_APP_PW1" "$MGD_APP_PW1" | PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -i -e PGPASSWORD "$c_mgd" sh -c \
        'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql' >/dev/null 2>&1; then
    fail "managed_admin falha fail-closed se role preexistente tiver BYPASSRLS"
else
    pass "managed_admin falha fail-closed se role preexistente tiver BYPASSRLS"
fi
check "senha de rewit_app preservada quando provisionamento gerenciado falha (BYPASSRLS)" "login_mgd rewit_app \"\$MGD_APP_PW2\""
admin_sql "$c_mgd" "ALTER ROLE rewit_app NOBYPASSRLS;" >/dev/null

# 1b) Role preexistente com CREATEDB quando managed_admin não possui CREATEDB: DEVE falhar fail-closed
admin_sql "$c_mgd" "ALTER ROLE rewit_app CREATEDB;" >/dev/null
if printf '%s\n%s\n' "$MGD_APP_PW1" "$MGD_APP_PW1" | PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -i -e PGPASSWORD "$c_mgd" sh -c \
        'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql' >/dev/null 2>&1; then
    fail "managed_admin sem CREATEDB falha fail-closed se role preexistente tiver CREATEDB"
else
    pass "managed_admin sem CREATEDB falha fail-closed se role preexistente tiver CREATEDB"
fi
check "senha de rewit_app preservada quando provisionamento gerenciado falha (CREATEDB)" "login_mgd rewit_app \"\$MGD_APP_PW2\""
admin_sql "$c_mgd" "ALTER ROLE rewit_app NOCREATEDB;" >/dev/null

# 2) spatial_ref_sys sem SELECT para rewit_app (PostGIS pertence ao superuser): managed_admin DEVE falhar com erro claro
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "REVOKE SELECT ON public.spatial_ref_sys FROM PUBLIC, rewit_app;" >/dev/null
if printf '%s\n%s\n' "$MGD_APP_PW2" "$MGD_APP_PW2" | PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -i -e PGPASSWORD "$c_mgd" sh -c \
        'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql' >/dev/null 2>&1; then
    fail "managed_admin falha fail-closed se public.spatial_ref_sys não tiver SELECT"
else
    pass "managed_admin falha fail-closed se public.spatial_ref_sys não tiver SELECT"
fi
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "GRANT SELECT ON public.spatial_ref_sys TO PUBLIC;" >/dev/null

# 3) spatial_ref_sys com INSERT concedido a PUBLIC: managed_admin não consegue revogar e DEVE falhar fail-closed
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "GRANT INSERT ON public.spatial_ref_sys TO PUBLIC;" >/dev/null
if printf '%s\n%s\n' "$MGD_APP_PW2" "$MGD_APP_PW2" | PGPASSWORD="$MGD_ADMIN_PW" "$RUNTIME" exec -i -e PGPASSWORD "$c_mgd" sh -c \
        'setsid -w psql -X -q -v ON_ERROR_STOP=1 -h "$(hostname -i | cut -d" " -f1)" -U '"$MGD_ADMIN"' -d '"$MGD_DB"' -v app_user=rewit_app -f /opt/rewit/postgres/provision-runtime-role.sql' >/dev/null 2>&1; then
    fail "managed_admin falha fail-closed se public.spatial_ref_sys permitir INSERT"
else
    pass "managed_admin falha fail-closed se public.spatial_ref_sys permitir INSERT"
fi
"$RUNTIME" exec "$c_mgd" psql -X -q -v ON_ERROR_STOP=1 -U "$ADMIN_USER" -d "$MGD_DB" -c "REVOKE INSERT ON public.spatial_ref_sys FROM PUBLIC;" >/dev/null

for secret in "$MGD_ADMIN_PW" "$MGD_MIG_PW" "$MGD_APP_PW1" "$MGD_APP_PW2" "$MGD_MED_PW1" "$MGD_MED_PW2" "$MGD_MOD_PW1" "$MGD_MOD_PW2"; do
    if logs_contain "$c_mgd" "$secret"; then fail "senha do cenário gerenciado ausente dos logs"; else pass "senha do cenário gerenciado ausente dos logs"; fi
done

# ------------------------------------------------------------------------------
# 7. Precedência de variáveis administrativas em 02-create-app-role.sh (M351-01)
#    POSTGRES_USER > PGUSER > postgres | POSTGRES_DB > PGDATABASE > $ADMIN_USER
# ------------------------------------------------------------------------------
echo "== Precedência de variáveis em 02-create-app-role.sh (POSTGRES_* > PG* > postgres)"
PREC_APP_PW1="PrecApp1-$RUN_ID"
PREC_MED_PW1="PrecMed1-$RUN_ID"
PREC_MOD_PW1="PrecMod1-$RUN_ID"
PREC_APP_PW2="PrecApp2-$RUN_ID"
PREC_MED_PW2="PrecMed2-$RUN_ID"
PREC_MOD_PW2="PrecMod2-$RUN_ID"
PREC_APP_PW3="PrecApp3-$RUN_ID"
PREC_MED_PW3="PrecMed3-$RUN_ID"
PREC_MOD_PW3="PrecMod3-$RUN_ID"

# Caso 2: somente PGUSER e PGDATABASE definidos (sem POSTGRES_USER/POSTGRES_DB)
if PGPASSWORD="$MGD_ADMIN_PW" APP_DB_PASSWORD="$PREC_APP_PW1" MEDIA_DB_PASSWORD="$PREC_MED_PW1" MOD_DB_PASSWORD="$PREC_MOD_PW1" \
        "$RUNTIME" exec -e PGPASSWORD -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e PGUSER="$MGD_ADMIN" -e PGDATABASE="$MGD_DB" \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_mgd" sh -c \
        'unset POSTGRES_USER POSTGRES_DB; PGHOST="$(hostname -i | cut -d" " -f1)" /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "02-create-app-role.sh aceita somente PGUSER e PGDATABASE"
else
    fail "02-create-app-role.sh aceita somente PGUSER e PGDATABASE"
fi
check "login real após rotação usando somente PGUSER/PGDATABASE" \
    "login_mgd rewit_app \"\$PREC_APP_PW1\" && login_mgd rewit_media \"\$PREC_MED_PW1\" && login_mgd rewit_mod \"\$PREC_MOD_PW1\""

# Caso 3: POSTGRES_USER sobrescreve PGUSER (PGUSER inválido é ignorado)
if PGPASSWORD="$MGD_ADMIN_PW" APP_DB_PASSWORD="$PREC_APP_PW2" MEDIA_DB_PASSWORD="$PREC_MED_PW2" MOD_DB_PASSWORD="$PREC_MOD_PW2" \
        "$RUNTIME" exec -e PGPASSWORD -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e POSTGRES_USER="$MGD_ADMIN" -e PGUSER="invalid_pguser_ignored" -e PGDATABASE="$MGD_DB" \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_mgd" sh -c \
        'unset POSTGRES_DB; PGHOST="$(hostname -i | cut -d" " -f1)" /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "POSTGRES_USER sobrescreve PGUSER em 02-create-app-role.sh"
else
    fail "POSTGRES_USER sobrescreve PGUSER em 02-create-app-role.sh"
fi
check "login real após rotação com POSTGRES_USER sobrescrevendo PGUSER" \
    "login_mgd rewit_app \"\$PREC_APP_PW2\" && login_mgd rewit_media \"\$PREC_MED_PW2\" && login_mgd rewit_mod \"\$PREC_MOD_PW2\""

# Caso 4: POSTGRES_DB sobrescreve PGDATABASE (PGDATABASE inválido é ignorado)
if PGPASSWORD="$MGD_ADMIN_PW" APP_DB_PASSWORD="$PREC_APP_PW3" MEDIA_DB_PASSWORD="$PREC_MED_PW3" MOD_DB_PASSWORD="$PREC_MOD_PW3" \
        "$RUNTIME" exec -e PGPASSWORD -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e PGUSER="$MGD_ADMIN" -e POSTGRES_DB="$MGD_DB" -e PGDATABASE="invalid_pgdb_ignored" \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_mgd" sh -c \
        'unset POSTGRES_USER; PGHOST="$(hostname -i | cut -d" " -f1)" /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "POSTGRES_DB sobrescreve PGDATABASE em 02-create-app-role.sh"
else
    fail "POSTGRES_DB sobrescreve PGDATABASE em 02-create-app-role.sh"
fi
check "login real após rotação com POSTGRES_DB sobrescrevendo PGDATABASE" \
    "login_mgd rewit_app \"\$PREC_APP_PW3\" && login_mgd rewit_media \"\$PREC_MED_PW3\" && login_mgd rewit_mod \"\$PREC_MOD_PW3\""

for secret in "$PREC_APP_PW1" "$PREC_MED_PW1" "$PREC_MOD_PW1" "$PREC_APP_PW2" "$PREC_MED_PW2" "$PREC_MOD_PW2" "$PREC_APP_PW3" "$PREC_MED_PW3" "$PREC_MOD_PW3"; do
    if logs_contain "$c_mgd" "$secret"; then fail "senha dos casos de precedência ausente dos logs"; else pass "senha dos casos de precedência ausente dos logs"; fi
done

# Caso 5: Fallback final para usuário e database 'postgres' quando nenhuma das 4 variáveis está definida
c_fb="$RUN_ID-fallback-postgres"
FB_APP_PW="FbApp-$RUN_ID"
FB_MED_PW="FbMed-$RUN_ID"
FB_MOD_PW="FbMod-$RUN_ID"
CONTAINERS+=("$c_fb")
"$RUNTIME" run -d --name "$c_fb" \
    -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$RUN_ID-pgpw" -e POSTGRES_DB=postgres \
    -v "$(host_path "$INIT_DIR/01-init-postgis.sql"):/opt/rewit/postgres/01-init-postgis.sql:$MOUNT_OPTS" \
    -v "$(host_path "$INIT_DIR/02-create-app-role.sh"):/opt/rewit/postgres/02-create-app-role.sh:$MOUNT_OPTS" \
    -v "$(host_path "$SQL_DIR/provision-runtime-role.sql"):/opt/rewit/postgres/provision-runtime-role.sql:$MOUNT_OPTS" \
    --health-cmd "pg_isready -h 127.0.0.1 -U postgres -d postgres" \
    --health-interval 1s --health-timeout 3s --health-retries 30 \
    "$IMAGE" >/dev/null
wait_pg "$c_fb" && pass "container de fallback (postgres/postgres) pronto" || fail "container de fallback (postgres/postgres) pronto"
"$RUNTIME" exec "$c_fb" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres -f /opt/rewit/postgres/01-init-postgis.sql >/dev/null
if APP_DB_PASSWORD="$FB_APP_PW" MEDIA_DB_PASSWORD="$FB_MED_PW" MOD_DB_PASSWORD="$FB_MOD_PW" \
        "$RUNTIME" exec -e APP_DB_PASSWORD -e MEDIA_DB_PASSWORD -e MOD_DB_PASSWORD \
        -e REWIT_PROVISION_SQL=/opt/rewit/postgres/provision-runtime-role.sql "$c_fb" sh -c \
        'unset POSTGRES_USER POSTGRES_DB PGUSER PGDATABASE; /opt/rewit/postgres/02-create-app-role.sh' >/dev/null 2>&1; then
    pass "fallback final para postgres/postgres em 02-create-app-role.sh"
else
    fail "fallback final para postgres/postgres em 02-create-app-role.sh"
fi
check "login real após fallback postgres/postgres" \
    "PGPASSWORD=\"\$FB_APP_PW\" \"$RUNTIME\" exec -e PGPASSWORD \"$c_fb\" sh -c 'psql -X -At -h \"\$(hostname -i | cut -d\" \" -f1)\" -U rewit_app -d postgres -c \"SELECT session_user\"' 2>/dev/null | grep -qx 'rewit_app'"
for secret in "$FB_APP_PW" "$FB_MED_PW" "$FB_MOD_PW"; do
    if logs_contain "$c_fb" "$secret"; then fail "senha do caso fallback ausente dos logs"; else pass "senha do caso fallback ausente dos logs"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "RESULTADO: todos os casos de provisionamento passaram."
else
    echo "RESULTADO: $FAILURES verificação(ões) falharam."
    exit 1
fi
