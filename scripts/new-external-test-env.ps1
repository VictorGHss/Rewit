# Script PowerShell para gerar o .env.external-test (infraestrutura isolada do perfil external-test)
# Credenciais aleatórias próprias, nunca exibidas. O arquivo é ignorado pelo Git (.env.*) e não é sobrescrito.
[CmdletBinding()]
param(
    [string]$OutFile,
    [int]$PostgresPort = 15432,
    [int]$RedisPort = 16379,
    [int]$StoragePort = 18333
)

$root = Split-Path -Parent $PSScriptRoot
if (-not $OutFile) {
    $OutFile = Join-Path $root ".env.external-test"
}

if (Test-Path $OutFile) {
    Write-Error "Arquivo já existe e não será sobrescrito: $OutFile"
    exit 1
}

# Portas dos serviços compartilhados de desenvolvimento (docker-compose.yml) e do PostgreSQL nativo
$sharedPorts = @(5432, 5433, 6379, 8333)
foreach ($port in @($PostgresPort, $RedisPort, $StoragePort)) {
    if ($sharedPorts -contains $port) {
        Write-Error "Porta $port pertence aos serviços compartilhados de desenvolvimento."
        exit 1
    }
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Warning "Porta $port já está em escuta; escolha outra antes de subir a infraestrutura."
    }
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-Secret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    $rng.GetBytes($buffer)
    # Somente [A-Za-z0-9]: seguro em URL, shell e arquivos de ambiente
    return ([Convert]::ToBase64String($buffer) -replace '[^A-Za-z0-9]', '')
}

$content = @"
# REWIT - ambiente isolado do perfil external-test (gerado por scripts/new-external-test-env.ps1)
# Não versionar nem compartilhar. Usado por docker-compose.external-test.yml e scripts/start-external-test.ps1.
REWIT_EXTERNAL_TEST_ENV=1

POSTGRES_DB=rewit_ext
POSTGRES_USER=rewit_ext_owner
POSTGRES_PASSWORD=$(New-Secret 24)
POSTGRES_PORT=$PostgresPort
APP_DB_USER=rewit_app
APP_DB_PASSWORD=$(New-Secret 24)
DATABASE_URL=jdbc:postgresql://127.0.0.1:$PostgresPort/rewit_ext

REDIS_HOST=127.0.0.1
REDIS_PORT=$RedisPort
REDIS_PASSWORD=$(New-Secret 24)

OBJECT_STORAGE_PORT=$StoragePort
OBJECT_STORAGE_ENDPOINT=http://127.0.0.1:$StoragePort
OBJECT_STORAGE_ACCESS_KEY=rewitext$(New-Secret 9)
OBJECT_STORAGE_SECRET_KEY=$(New-Secret 30)
OBJECT_STORAGE_BUCKET=rewit-ext

JWT_SECRET=$(New-Secret 48)
RATE_LIMIT_KEY_SECRET=$(New-Secret 48)
ACCOUNT_EMAIL_RESERVATION_SECRET=$(New-Secret 48)

SERVER_PORT=8080

# Token do túnel criado no painel Cloudflare (Zero Trust > Networks > Tunnels)
CLOUDFLARE_TUNNEL_TOKEN=
"@

# UTF-8 sem BOM e com LF: lido pelo Compose e pelo start-external-test.ps1
[System.IO.File]::WriteAllText($OutFile, ($content -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Arquivo de ambiente gerado: $OutFile (valores não exibidos)." -ForegroundColor Green
Write-Host "Preencha CLOUDFLARE_TUNNEL_TOKEN somente quando o túnel existir." -ForegroundColor Yellow
