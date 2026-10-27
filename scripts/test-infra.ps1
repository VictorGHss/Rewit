# Infraestrutura descartável dos testes de integração do backend (docs/development/local-setup.md §5).
#
# Cria PostgreSQL/PostGIS (com os scripts de infrastructure/postgres/init), Redis e SeaweedFS (S3) em containers
# próprios, publicados só em 127.0.0.1, com credenciais aleatórias que nunca são impressas nem gravadas em arquivo, e
# define as variáveis TEST_* no processo PowerShell atual. Não toca em containers de desenvolvimento, do external-test
# nem em qualquer container sem o rótulo desta infraestrutura.
#
#   .\scripts\test-infra.ps1 up       # cria e define TEST_* nesta sessão; depois: cd backend; .\mvnw.cmd clean test
#   .\scripts\test-infra.ps1 down     # remove somente os containers (e volumes anônimos) criados por este script
#   .\scripts\test-infra.ps1 run      # up + .\mvnw.cmd clean test + down (sempre)
#   .\scripts\test-infra.ps1 status   # lista os containers desta infraestrutura
#
# Execute como script (não com dot-source "."): as variáveis de ambiente já valem para a sessão que o chamou.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'status', 'run')]
    [string]$Action = 'up',

    # Identifica o conjunto de containers (permite mais de um conjunto, com portas diferentes)
    [ValidatePattern('^[a-z0-9]{1,20}$')]
    [string]$Instance = 'local',

    [ValidateRange(1024, 65535)][int]$PostgresPort = 35432,
    [ValidateRange(1024, 65535)][int]$RedisPort = 36379,
    [ValidateRange(1024, 65535)][int]$StoragePort = 38333,

    [ValidateRange(10, 900)][int]$ReadyTimeoutSeconds = 180,

    # Argumentos do Maven na ação run
    [string[]]$MavenArgs = @('clean', 'test')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($MyInvocation.InvocationName -eq '.') {
    throw 'Execute .\scripts\test-infra.ps1 sem dot-source: o script usa exit e as variáveis TEST_* já valem para esta sessão.'
}

$root = Split-Path -Parent $PSScriptRoot

# Mesmas imagens do docker-compose.yml
$images = @{
    postgres = 'postgis/postgis:18-3.6'
    redis    = 'redis:8-alpine'
    storage  = 'chrislusf/seaweedfs:4.47'
}
$labelKey = 'rewit.test-infra'
$names = [ordered]@{
    postgres = "rewit-testinfra-$Instance-postgres"
    redis    = "rewit-testinfra-$Instance-redis"
    storage  = "rewit-testinfra-$Instance-storage"
}

$database = 'rewit_test'
$migrationUser = 'rewit_migrator'
$appUser = 'rewit_app'
$bucket = 'rewit-test'

# Desenvolvimento (recusadas também por TestInfrastructureSafetyValidator), external-test e API local
$reservedPorts = @(5432, 5433, 6379, 8333, 8888, 9333, 15432, 16379, 18333, 8080, 8081)

$testVariables = @(
    'TEST_DATASOURCE_URL', 'TEST_APP_DB_USER', 'TEST_APP_DB_PASSWORD', 'TEST_MIGRATION_DB_USER',
    'TEST_MIGRATION_DB_PASSWORD', 'TEST_REDIS_HOST', 'TEST_REDIS_PORT', 'TEST_REDIS_PASSWORD',
    'TEST_STORAGE_ENDPOINT', 'TEST_STORAGE_ACCESS_KEY', 'TEST_STORAGE_SECRET_KEY', 'TEST_STORAGE_BUCKET'
)

function Invoke-Docker {
    param([string[]]$Arguments, [switch]$AllowFailure)
    # No Windows PowerShell 5.1, stderr de comando nativo redirecionado com ErrorActionPreference=Stop vira exceção
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Linhas vazias de stderr chegam como RemoteException sem texto
        $output = @(& docker @Arguments 2>&1 | ForEach-Object { "$_" } |
            Where-Object { $_ -and $_ -ne 'System.Management.Automation.RemoteException' })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "docker $($Arguments[0]) falhou (código $code): $(($output | Select-Object -Last 3) -join ' ')"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $output }
}

function Assert-Docker {
    $result = Invoke-Docker -Arguments @('version', '--format', '{{.Server.Version}}') -AllowFailure
    if ($result.ExitCode -ne 0) {
        throw 'Docker indisponível: inicie o Docker Desktop e tente de novo.'
    }
}

# Rótulo da infraestrutura no container, '' se o container não tiver o rótulo, $null se não existir
function Get-OwnerLabel([string]$Name) {
    # Templates sem aspas: o Windows PowerShell 5.1 não escapa aspas internas em argumentos de comando nativo
    $result = Invoke-Docker -Arguments @('container', 'inspect', '--format', '{{json .Config.Labels}}', $Name) -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    $labels = ($result.Output -join '') | ConvertFrom-Json
    if ($null -eq $labels) { return '' }
    $property = $labels.PSObject.Properties[$labelKey]
    if ($null -eq $property) { return '' }
    return [string]$property.Value
}

function Test-PortInUse([int]$Port) {
    $listening = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listening) { return $true }
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
        return $false
    } catch {
        return $true
    } finally {
        $listener.Stop()
    }
}

function New-Secret([int]$Bytes = 24) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return ($buffer | ForEach-Object { $_.ToString('x2') }) -join ''
}

# Remove somente containers com o nome esperado E o rótulo desta instância; devolve as falhas
function Remove-OwnContainers([string[]]$ContainerNames) {
    $failures = @()
    foreach ($name in $ContainerNames) {
        $owner = Get-OwnerLabel $name
        if ($null -eq $owner) {
            Write-Host "  $name não existe." -ForegroundColor DarkGray
            continue
        }
        if ($owner -ne $Instance) {
            $failures += "$name existe mas não pertence a esta infraestrutura (rótulo $labelKey ausente ou diferente); não foi removido."
            continue
        }
        # -v remove só os volumes anônimos deste container
        $result = Invoke-Docker -Arguments @('rm', '-f', '-v', $name) -AllowFailure
        if ($result.ExitCode -eq 0) {
            Write-Host "  $name removido." -ForegroundColor Green
        } else {
            $failures += "$name não pôde ser removido: $(($result.Output | Select-Object -Last 2) -join ' ')"
        }
    }
    return , $failures
}

function Clear-TestVariables {
    $ownUrl = "jdbc:postgresql://127.0.0.1:$PostgresPort/$database"
    # Só limpa a sessão se as variáveis apontam para esta infraestrutura
    if ($env:TEST_DATASOURCE_URL -eq $ownUrl) {
        foreach ($variable in $testVariables) {
            [Environment]::SetEnvironmentVariable($variable, $null, 'Process')
        }
        Write-Host '  Variáveis TEST_* removidas desta sessão.' -ForegroundColor Green
    }
}

function Wait-Ready {
    $deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
    $pending = [System.Collections.Generic.List[string]]@('postgres', 'redis', 'storage')
    while ($pending.Count -gt 0) {
        foreach ($service in @($pending)) {
            $name = $names[$service]
            $state = (Invoke-Docker -Arguments @('container', 'inspect', '--format', '{{.State.Status}}', $name)).Output -join ''
            if ($state.Trim() -ne 'running') {
                throw "$name parou durante a inicialização (estado: $($state.Trim())). Veja: docker logs $name"
            }
            $ready = $false
            switch ($service) {
                'postgres' {
                    $logs = (Invoke-Docker -Arguments @('logs', $name) -AllowFailure).Output -join "`n"
                    if ($logs -match '02-create-app-role: ERRO') {
                        throw "Provisionamento de $appUser falhou. Veja: docker logs $name"
                    }
                    # O entrypoint sobe um servidor temporário para o init e depois o definitivo
                    if (([regex]::Matches($logs, 'ready to accept connections')).Count -ge 2) {
                        $role = Invoke-Docker -Arguments @('exec', $name, 'psql', '-U', $migrationUser, '-d', $database, '-tAc',
                            "SELECT 1 FROM pg_roles WHERE rolname = '$appUser'") -AllowFailure
                        $ready = $role.ExitCode -eq 0 -and (($role.Output -join '').Trim() -eq '1')
                    }
                }
                'redis' {
                    $ping = Invoke-Docker -Arguments @('exec', $name, 'redis-cli', 'ping') -AllowFailure
                    $ready = $ping.ExitCode -eq 0 -and (($ping.Output -join '').Trim() -eq 'PONG')
                }
                'storage' {
                    try {
                        Invoke-WebRequest -Uri "http://127.0.0.1:$StoragePort/" -UseBasicParsing -TimeoutSec 3 | Out-Null
                        $ready = $true
                    } catch [System.Net.WebException] {
                        # Qualquer resposta HTTP (ex.: 403 sem credencial) indica o S3 de pé
                        $ready = $null -ne $_.Exception.Response
                    }
                }
            }
            if ($ready) {
                Write-Host "  $name pronto." -ForegroundColor Green
                [void]$pending.Remove($service)
            }
        }
        if ($pending.Count -gt 0) {
            if ((Get-Date) -gt $deadline) {
                throw "Tempo esgotado ($ReadyTimeoutSeconds s) aguardando: $(($pending | ForEach-Object { $names[$_] }) -join ', ')"
            }
            Start-Sleep -Seconds 2
        }
    }
}

function Start-TestInfrastructure {
    Assert-Docker

    $ports = @($PostgresPort, $RedisPort, $StoragePort)
    if (($ports | Select-Object -Unique).Count -ne 3) {
        throw 'As portas de PostgreSQL, Redis e storage precisam ser diferentes.'
    }
    $reserved = @($ports | Where-Object { $reservedPorts -contains $_ })
    if ($reserved.Count -gt 0) {
        throw "Porta(s) reservada(s) para desenvolvimento, external-test ou API: $($reserved -join ', '). Escolha outras com -PostgresPort/-RedisPort/-StoragePort."
    }

    # Conflitos antes de criar qualquer recurso
    $conflicts = @()
    foreach ($name in $names.Values) {
        $owner = Get-OwnerLabel $name
        if ($null -ne $owner) {
            if ($owner -eq $Instance) {
                $conflicts += "$name já existe (criado por este script). Rode '.\scripts\test-infra.ps1 down' antes de recriar."
            } else {
                $conflicts += "$name já existe e não pertence a esta infraestrutura. Use outro -Instance."
            }
        }
    }
    foreach ($port in $ports) {
        $publishers = @((Invoke-Docker -Arguments @('ps', '-a', '--filter', "publish=$port", '--format', '{{.Names}}')).Output |
            Where-Object { $_ })
        if ($publishers.Count -gt 0) {
            $conflicts += "Porta $port já publicada pelo(s) container(s): $($publishers -join ', ')."
        } elseif (Test-PortInUse $port) {
            $conflicts += "Porta $port em uso no host."
        }
    }
    if ($conflicts.Count -gt 0) {
        throw "Conflito detectado; nada foi criado.`n - $($conflicts -join "`n - ")"
    }

    $initDir = Join-Path $root 'infrastructure\postgres\init'
    $provisionDir = Join-Path $root 'infrastructure\postgres'
    if (-not (Test-Path (Join-Path $initDir '02-create-app-role.sh'))) {
        throw "Scripts de inicialização do PostgreSQL não encontrados em $initDir"
    }

    # As credenciais passam ao docker pelo ambiente (-e NOME, sem valor na linha de comando) e saem dele em seguida
    $secrets = [ordered]@{
        POSTGRES_PASSWORD     = New-Secret
        APP_DB_PASSWORD       = New-Secret
        AWS_ACCESS_KEY_ID     = New-Secret 12
        AWS_SECRET_ACCESS_KEY = New-Secret
    }
    $previousValues = @{}
    foreach ($key in $secrets.Keys) {
        $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $secrets[$key], 'Process')
    }

    try {
        $labels = @('--label', "$labelKey=$Instance", '--label', 'rewit.test-infra.script=scripts/test-infra.ps1')

        Invoke-Docker -Arguments (@('run', '-d', '--name', $names.postgres) + $labels + @(
            '-p', "127.0.0.1:${PostgresPort}:5432",
            '-e', "POSTGRES_DB=$database", '-e', "POSTGRES_USER=$migrationUser", '-e', 'POSTGRES_PASSWORD',
            '-e', "APP_DB_USER=$appUser", '-e', 'APP_DB_PASSWORD',
            '-v', "${initDir}:/docker-entrypoint-initdb.d:ro",
            '-v', "${provisionDir}:/opt/rewit/postgres:ro",
            $images.postgres)) | Out-Null

        Invoke-Docker -Arguments (@('run', '-d', '--name', $names.redis) + $labels + @(
            '-p', "127.0.0.1:${RedisPort}:6379",
            $images.redis)) | Out-Null

        Invoke-Docker -Arguments (@('run', '-d', '--name', $names.storage) + $labels + @(
            '-p', "127.0.0.1:${StoragePort}:8333",
            '-e', 'AWS_ACCESS_KEY_ID', '-e', 'AWS_SECRET_ACCESS_KEY', '-e', "S3_BUCKET=$bucket",
            $images.storage, 'mini', '-dir=/data', '-s3.port=8333')) | Out-Null

        Write-Host 'Aguardando os serviços...' -ForegroundColor Cyan
        Wait-Ready
    } catch {
        Write-Host "Falha na inicialização: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host 'Removendo somente os containers criados nesta execução...' -ForegroundColor Yellow
        # Todos os nomes desta instância, inclusive o do docker run que falhou: ao falhar na publicação da porta, o
        # container ainda fica no estado Created. A verificação de conflitos garantiu que nenhum desses nomes existia
        # antes, e a remoção continua exigindo o rótulo desta instância.
        $failures = Remove-OwnContainers @($names.Values)
        foreach ($failure in $failures) { Write-Host "  $failure" -ForegroundColor Red }
        throw
    } finally {
        foreach ($key in $secrets.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
        }
    }

    $env:TEST_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$PostgresPort/$database"
    $env:TEST_APP_DB_USER = $appUser
    $env:TEST_APP_DB_PASSWORD = $secrets.APP_DB_PASSWORD
    $env:TEST_MIGRATION_DB_USER = $migrationUser
    $env:TEST_MIGRATION_DB_PASSWORD = $secrets.POSTGRES_PASSWORD
    $env:TEST_REDIS_HOST = '127.0.0.1'
    $env:TEST_REDIS_PORT = "$RedisPort"
    # Redis descartável sem senha: um valor herdado da sessão não pode vazar para os testes
    [Environment]::SetEnvironmentVariable('TEST_REDIS_PASSWORD', $null, 'Process')
    $env:TEST_STORAGE_ENDPOINT = "http://127.0.0.1:$StoragePort"
    $env:TEST_STORAGE_ACCESS_KEY = $secrets.AWS_ACCESS_KEY_ID
    $env:TEST_STORAGE_SECRET_KEY = $secrets.AWS_SECRET_ACCESS_KEY
    $env:TEST_STORAGE_BUCKET = $bucket

    Write-Host "Infraestrutura de teste pronta (instância '$Instance'): PostgreSQL 127.0.0.1:$PostgresPort, Redis 127.0.0.1:$RedisPort, S3 127.0.0.1:$StoragePort." -ForegroundColor Green
    Write-Host 'Variáveis TEST_* definidas somente nesta sessão (valores não exibidos).' -ForegroundColor Green
}

function Stop-TestInfrastructure {
    Assert-Docker
    Write-Host "Removendo a infraestrutura de teste (instância '$Instance')..." -ForegroundColor Cyan
    $failures = Remove-OwnContainers @($names.Values)
    Clear-TestVariables
    if ($failures.Count -gt 0) {
        foreach ($failure in $failures) { Write-Host "  $failure" -ForegroundColor Red }
        throw 'Limpeza incompleta: verifique as mensagens acima.'
    }
}

function Show-TestInfrastructure {
    Assert-Docker
    $result = Invoke-Docker -Arguments @('ps', '-a', '--filter', "label=$labelKey",
        '--format', "{{.Names}}`t{{.Status}}`t{{.Ports}}")
    if ($result.Output.Count -eq 0) {
        Write-Host 'Nenhum container de infraestrutura de teste.'
    } else {
        $result.Output | ForEach-Object { Write-Host $_ }
    }
}

# Sucesso termina com exit 0: o código de saída do último docker consultado (ex.: inspect de container inexistente)
# não pode vazar para $LASTEXITCODE. Erros terminam com exceção (código diferente de zero).
switch ($Action) {
    'up' { Start-TestInfrastructure; exit 0 }
    'down' { Stop-TestInfrastructure; exit 0 }
    'status' { Show-TestInfrastructure; exit 0 }
    'run' {
        Start-TestInfrastructure
        $exitCode = 1
        Push-Location (Join-Path $root 'backend')
        try {
            # Avisos da JVM vão para stderr; com saída redirecionada, o Windows PowerShell 5.1 os transformaria em
            # exceção sob ErrorActionPreference=Stop. O resultado é o código de saída do Maven.
            $ErrorActionPreference = 'Continue'
            & .\mvnw.cmd @MavenArgs
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = 'Stop'
            Pop-Location
            Stop-TestInfrastructure
        }
        exit $exitCode
    }
}
