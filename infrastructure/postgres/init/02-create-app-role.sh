#!/bin/sh
# ==============================================================================
# REWIT - INICIALIZAÇÃO DA ROLE DE RUNTIME (rewit_app)
# Executado pelo entrypoint do PostgreSQL na criação inicial do banco. Também pode ser
# executado manualmente para provisionar de novo ou rotacionar a senha num banco existente:
#   docker compose exec postgres /docker-entrypoint-initdb.d/02-create-app-role.sh
# (a senha vem de APP_DB_PASSWORD no ambiente do container, definido pelo .env).
#
# SEC-2 / SEC-3: a senha nunca é concatenada em SQL nem passada em argumentos de processo.
# Ela segue pela entrada padrão até o \password do psql, que envia ao servidor apenas o
# verificador SCRAM. O SQL é o de provision-runtime-role.sql (fonte única), montado em
# /opt/rewit/postgres pelo docker-compose.yml.
# ==============================================================================
set -eu
export LC_ALL=C

PROVISION_SQL="${REWIT_PROVISION_SQL:-/opt/rewit/postgres/provision-runtime-role.sql}"
APP_USER="${APP_DB_USER:-rewit_app}"
ADMIN_USER="${POSTGRES_USER:-postgres}"
ADMIN_DB="${POSTGRES_DB:-$ADMIN_USER}"

fail() {
    printf '02-create-app-role: ERRO: %s\n' "$1" >&2
    exit 1
}

if [ -z "${APP_DB_PASSWORD:-}" ]; then
    fail "APP_DB_PASSWORD ausente ou vazia; defina a senha da role de runtime no ambiente (.env). Não há senha padrão."
fi

if [ "$APP_DB_PASSWORD" = "change-me" ]; then
    fail "APP_DB_PASSWORD ainda contém o valor de exemplo do .env.example; defina uma senha própria."
fi

newline='
'
carriage_return=$(printf '\r')
case "$APP_DB_PASSWORD" in
    *"$newline"* | *"$carriage_return"*)
        fail "APP_DB_PASSWORD não pode conter quebras de linha."
        ;;
esac

case "$APP_USER" in
    '' | [!a-z_]* | *[!a-z0-9_]*)
        fail "APP_DB_USER inválido: use apenas letras minúsculas, dígitos e '_' (iniciando por letra ou '_')."
        ;;
    postgres | pg_*)
        fail "APP_DB_USER não pode ser uma role reservada do PostgreSQL."
        ;;
esac
if [ "${#APP_USER}" -gt 63 ]; then
    fail "APP_DB_USER excede 63 caracteres."
fi
if [ "$APP_USER" = "$ADMIN_USER" ]; then
    fail "APP_DB_USER não pode ser a role administrativa (POSTGRES_USER)."
fi
if [ ! -r "$PROVISION_SQL" ]; then
    fail "script de provisionamento não encontrado em $PROVISION_SQL (verifique o volume /opt/rewit/postgres)."
fi

if ! command -v setsid >/dev/null 2>&1; then
    fail "setsid indisponível: necessário para o psql ler a senha da entrada padrão e não de um terminal."
fi

echo "Provisionando role de runtime '${APP_USER}'..."
# printf é builtin do shell: a senha não aparece na lista de processos. setsid retira o terminal
# de controle, de modo que o \password do psql leia a senha da entrada padrão mesmo sob TTY.
printf '%s\n%s\n' "$APP_DB_PASSWORD" "$APP_DB_PASSWORD" \
    | setsid psql -X -q -v ON_ERROR_STOP=1 --no-password \
        --username "$ADMIN_USER" --dbname "$ADMIN_DB" \
        -v app_user="$APP_USER" \
        -f "$PROVISION_SQL"
echo "Role de runtime '${APP_USER}' provisionada sem privilégios administrativos."
