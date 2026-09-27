# Script PowerShell para iniciar a infraestrutura local do Rewit
[CmdletBinding()]
param()

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   Iniciando Infraestrutura Local do Rewit        " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

if (-not (Test-Path "$root\.env")) {
    Write-Warning "Arquivo .env não encontrado. Copiando de .env.example..."
    Copy-Item "$root\.env.example" "$root\.env"
    Write-Host "Arquivo .env criado com sucesso." -ForegroundColor Green
}

# Verifica se o Docker está acessível
try {
    docker info | Out-Null
    Write-Host "Docker daemon detectado com sucesso." -ForegroundColor Green
    Write-Host "Iniciando containers essenciais (Postgres+PostGIS, Redis, SeaweedFS)..." -ForegroundColor Yellow
    docker compose up -d postgres redis seaweedfs
    Write-Host "Containers iniciados! Verificando status..." -ForegroundColor Green
    docker compose ps
} catch {
    Write-Warning "Não foi possível comunicar com o Docker. Verifique se o Docker Desktop está em execução."
}
