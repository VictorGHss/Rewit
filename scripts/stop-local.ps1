# Script PowerShell para parar os serviços locais do Rewit
[CmdletBinding()]
param()

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "Parando containers da infraestrutura local..." -ForegroundColor Yellow
docker compose down
Write-Host "Containers encerrados com sucesso." -ForegroundColor Green
