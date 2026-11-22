#!/bin/sh
# ==============================================================================
# REWIT - INICIALIZAÇÃO DAS ROLES DE RUNTIME (rewit_app, rewit_media e rewit_mod)
# Executado pelo entrypoint do PostgreSQL na criação inicial do banco. Também pode ser
# executado manualmente para provisionar de novo ou rotacionar as senhas num banco existente:
#   docker compose exec postgres /docker-entrypoint-initdb.d/02-create-app-role.sh
# (as senhas vêm de APP_DB_PASSWORD, MEDIA_DB_PASSWORD e MOD_DB_PASSWORD no ambiente do container,
# definidas pelo .env).
#
# SEC-2 / SEC-3 / S3-A13-F2 / SEC-F2-03: as senhas nunca são concatenadas em SQL nem passadas em argumentos
# de processo. Elas seguem pela entrada padrão até o \password do psql, que envia ao servidor
# apenas o verificador SCRAM. O SQL é o de provision-runtime-role.sql (fonte única), montado em
# /opt/rewit/postgres pelo docker-compose.yml.
# ==============================================================================
set -eu
export LC_ALL=C

PROVISION_SQL="${REWIT_PROVISION_SQL:-/opt/rewit/postgres/provision-runtime-role.sql}"
APP_USER="${APP_DB_USER:-rewit_app}"
MEDIA_USER="${MEDIA_DB_USER:-rewit_media}"
MOD_USER="${MOD_DB_USER:-rewit_mod}"
ADMIN_USER="${POSTGRES_USER:-postgres}"
ADMIN_DB="${POSTGRES_DB:-$ADMIN_USER}"

fail() {
    printf '02-create-app-role: ERRO: %s\n' "$1" >&2
    exit 1
}

newline='
'
carriage_return=$(printf '\r')

validate_password() {
    var_name="$1"
    var_value="$2"

    if [ -z "$var_value" ]; then
        fail "$var_name ausente ou vazia; defina a senha da role de runtime no ambiente (.env). Não há senha padrão."
    fi

    if [ "$var_value" = "change-me" ]; then
        fail "$var_name ainda contém o valor de exemplo do .env.example; defina uma senha própria."
    fi

    case "$var_value" in
        *"$newline"* | *"$carriage_return"*)
            fail "$var_name não pode conter quebras de linha."
            ;;
    esac
}

validate_role_name() {
    var_name="$1"
    role_value="$2"
    expected_name="$3"

    case "$role_value" in
        '' | [!a-z_]* | *[!a-z0-9_]*)
            fail "$var_name inválido: use apenas letras minúsculas, dígitos e '_' (iniciando por letra ou '_')."
            ;;
        postgres | pg_*)
            fail "$var_name não pode ser uma role reservada do PostgreSQL."
            ;;
    esac
    if [ "$role_value" != "$expected_name" ]; then
        fail "$var_name=$role_value não é suportado: a role de runtime é fixa ($expected_name), pois as migrations concedem privilégios somente a ela."
    fi
    if [ "${#role_value}" -gt 63 ]; then
        fail "$var_name excede 63 caracteres."
    fi
    if [ "$role_value" = "$ADMIN_USER" ]; then
        fail "$var_name não pode ser a role administrativa (POSTGRES_USER)."
    fi
}

validate_password "APP_DB_PASSWORD" "${APP_DB_PASSWORD:-}"
validate_password "MEDIA_DB_PASSWORD" "${MEDIA_DB_PASSWORD:-}"
validate_password "MOD_DB_PASSWORD" "${MOD_DB_PASSWORD:-}"

if [ "$APP_DB_PASSWORD" = "$MOD_DB_PASSWORD" ]; then
    fail "MOD_DB_PASSWORD deve ser independente e diferente de APP_DB_PASSWORD."
fi
if [ "$MEDIA_DB_PASSWORD" = "$APP_DB_PASSWORD" ]; then
    fail "MEDIA_DB_PASSWORD deve ser independente e diferente de APP_DB_PASSWORD."
fi
if [ "$MEDIA_DB_PASSWORD" = "$MOD_DB_PASSWORD" ]; then
    fail "MEDIA_DB_PASSWORD deve ser independente e diferente de MOD_DB_PASSWORD."
fi

validate_role_name "APP_DB_USER" "$APP_USER" "rewit_app"
validate_role_name "MEDIA_DB_USER" "$MEDIA_USER" "rewit_media"
validate_role_name "MOD_DB_USER" "$MOD_USER" "rewit_mod"

if [ ! -r "$PROVISION_SQL" ]; then
    fail "script de provisionamento não encontrado em $PROVISION_SQL (verifique o volume /opt/rewit/postgres)."
fi

if ! command -v setsid >/dev/null 2>&1; then
    fail "setsid indisponível: necessário para o psql ler a senha da entrada padrão e não de um terminal."
fi

provision_role() {
    role_name="$1"
    role_password="$2"
    echo "Provisionando role de runtime '${role_name}'..."
    # printf é builtin do shell: a senha não aparece na lista de processos. setsid retira o terminal
    # de controle, de modo que o \password do psql leia a senha da entrada padrão mesmo sob TTY;
    # -w espera o psql e propaga seu código de saída (sem -w, um líder de grupo faria fork e sairia 0).
    printf '%s\n%s\n' "$role_password" "$role_password" \
        | setsid -w psql -X -q -v ON_ERROR_STOP=1 --no-password \
            --username "$ADMIN_USER" --dbname "$ADMIN_DB" \
            -v app_user="$role_name" \
            -f "$PROVISION_SQL"
    echo "Role de runtime '${role_name}' provisionada sem privilégios administrativos."
}

provision_role "$APP_USER" "$APP_DB_PASSWORD"
provision_role "$MEDIA_USER" "$MEDIA_DB_PASSWORD"
provision_role "$MOD_USER" "$MOD_DB_PASSWORD"
