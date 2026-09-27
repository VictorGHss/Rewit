# Script PowerShell para validar que não existem segredos reais versionados
[CmdletBinding()]
param()

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "Verificando integridade e segurança de variáveis..." -ForegroundColor Cyan

$sensitiveKeywords = @("AIzaSy", "ghp_", "sk_live_", "AKIA", "-----BEGIN RSA PRIVATE KEY-----")
$violations = 0

Get-ChildItem -Path $root -Recurse -Exclude @(".git", ".gemini", "node_modules", "target", ".dart_tool", "verify-env.ps1") -File | ForEach-Object {
    $file = $_.FullName
    $content = Get-Content $file -Raw -ErrorAction SilentlyContinue
    if ($content) {
        foreach ($pattern in $sensitiveKeywords) {
            if ($content -match [regex]::Escape($pattern)) {
                Write-Error "ALERTA DE SEGURANÇA: Possível segredo encontrado em $file (Padrão: $pattern)"
                $violations++
            }
        }
    }
}

if ($violations -eq 0) {
    Write-Host "Nenhum segredo ou chave privada exposta foi detectada. Verificação aprovada!" -ForegroundColor Green
} else {
    Write-Error "$violations violações de segurança encontradas. Corrija antes de prosseguir."
}
