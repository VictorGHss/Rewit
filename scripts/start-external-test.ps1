# Script PowerShell para iniciar a API no perfil external-test (testes externos pelo Cloudflare Tunnel)
# As variáveis do arquivo de ambiente entram só neste processo: nada é impresso nem passado na linha de comando.
[CmdletBinding()]
param(
    # Arquivo de ambiente com os segredos (padrão: .env na raiz do repositório)
    [string]$EnvFile
)

$root = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) {
    $EnvFile = Join-Path $root ".env"
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   Iniciando API Rewit (perfil external-test)     " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

if (-not (Test-Path $EnvFile)) {
    Write-Error "Arquivo de ambiente não encontrado: $EnvFile"
    exit 1
}

$loaded = 0
foreach ($line in Get-Content $EnvFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        # Valor vazio remove a variável do processo, como se estivesse ausente
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        $loaded++
    }
}
Write-Host "$loaded variáveis carregadas de $EnvFile (valores não exibidos)." -ForegroundColor Green

# O perfil é fixo: o external-test não herda os fallbacks de desenvolvimento do perfil local
$env:SPRING_PROFILES_ACTIVE = "external-test"
Write-Host "API em http://127.0.0.1:$(if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }) (somente loopback)." -ForegroundColor Yellow

Set-Location (Join-Path $root "backend")
& .\mvnw.cmd spring-boot:run
exit $LASTEXITCODE
