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
    local name="$1" i state health
    for i in $(seq 1 120); do
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

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "RESULTADO: todos os casos de provisionamento passaram."
else
    echo "RESULTADO: $FAILURES verificação(ões) falharam."
    exit 1
fi
