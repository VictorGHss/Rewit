#!/bin/sh
set -e

# ==============================================================================
# REWIT - INICIALIZAÇÃO DA ROLE DE RUNTIME (rewit_app)
# Executado automaticamente na criação inicial do banco pelo container PostgreSQL
# A credencial é fornecida externamente via variáveis de ambiente (SEC-1 / AO-1).
# ==============================================================================

APP_USER="${APP_DB_USER:-rewit_app}"
APP_PASS="${APP_DB_PASSWORD}"

if [ -n "$APP_PASS" ]; then
    echo "Provisionando role de runtime '${APP_USER}' com credencial fornecida externamente..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        DO \$\$
        BEGIN
            IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_USER') THEN
                CREATE ROLE $APP_USER WITH LOGIN PASSWORD '$APP_PASS' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
            ELSE
                ALTER ROLE $APP_USER WITH LOGIN PASSWORD '$APP_PASS' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
            END IF;
        END
        \$\$;
EOSQL
    echo "Role de runtime '${APP_USER}' provisionada com sucesso."
else
    echo "AVISO: APP_DB_PASSWORD não definida; provisionando role '${APP_USER}' sem senha inicial..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        DO \$\$
        BEGIN
            IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_USER') THEN
                CREATE ROLE $APP_USER WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
            END IF;
        END
        \$\$;
EOSQL
fi

