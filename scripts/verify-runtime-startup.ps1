param(
    [switch]$KeepArtifacts
)

$ErrorActionPreference = 'Stop'

function Wait-ForPattern {
    param(
        [string]$LogPath,
        [string[]]$Patterns,
        [int]$TimeoutSeconds = 180,
        [int]$ProcessId = 0
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $LogPath) {
            $text = Get-Content -Path $LogPath -Raw -ErrorAction SilentlyContinue
            foreach ($pattern in $Patterns) {
                if ($text -match [regex]::Escape($pattern)) {
                    return $text
                }
            }
        }

        if ($ProcessId -gt 0) {
            $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
            if ($null -eq $process -or $process.HasExited) {
                break
            }
        }

        Start-Sleep -Milliseconds 500
    }

    if (Test-Path $LogPath) {
        return (Get-Content -Path $LogPath -Raw -ErrorAction SilentlyContinue)
    }
    return ''
}

function Stop-ProcessTree {
    param(
        [int]$Id
    )

    try {
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$Id" -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            Stop-ProcessTree -Id $child.ProcessId
        }
    } catch {
    }

    try {
        Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
    } catch {
    }
}

function Ensure-LineValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$Value
    )

    $content = if (Test-Path $Path) { Get-Content -Path $Path -Raw } else { '' }
    $line = "$Key=$Value"
    if ($content -match "(?m)^$([regex]::Escape($Key))=") {
        $content = [regex]::Replace($content, "(?m)^$([regex]::Escape($Key))=.*$", $line)
    } else {
        if ($content -and -not $content.EndsWith("`n") -and -not $content.EndsWith("`r")) {
            $content += [Environment]::NewLine
        }
        $content += $line + [Environment]::NewLine
    }
    Set-Content -Path $Path -Value $content -NoNewline
}

function Parse-Properties {
    param(
        [string]$Path
    )

    $map = @{}
    if (-not (Test-Path $Path)) {
        return $map
    }

    foreach ($line in Get-Content -Path $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or $trimmed.StartsWith('!')) {
            continue
        }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 0) {
            continue
        }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        $map[$key] = $value
    }
    return $map
}

function Invoke-TargetCheck {
    param(
        [hashtable]$Target
    )

    $runDir = $Target.runDir
    $configDir = Join-Path $runDir 'config'
    $logsDir = Join-Path $runDir 'logs'
    $logPath = Join-Path $logsDir 'zstdnet-startup-check.log'
    $serverPropsPath = Join-Path $runDir 'server.properties'
    $zstdPropsPath = Join-Path $configDir 'zstdnet-server.properties'

    New-Item -ItemType Directory -Force -Path $runDir, $configDir, $logsDir | Out-Null

    if (-not $KeepArtifacts) {
        Remove-Item -LiteralPath $logPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $zstdPropsPath -Force -ErrorAction SilentlyContinue
    }

    Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true'
    $onlineMode = if ($Target.ContainsKey('onlineMode')) { [bool]$Target.onlineMode } else { $false }
    $expectedThreshold = if ($onlineMode) { '256' } else { '1048576' }
    $expectedLog = if ($onlineMode) {
        'keeping dedicated network-compression-threshold unchanged because server.properties has online-mode=true.'
    } else {
        'forced dedicated network-compression-threshold=1048576'
    }
    @(
        'motd=ZstdNet startup check'
        "server-port=$($Target.publicPort)"
        "online-mode=$($onlineMode.ToString().ToLowerInvariant())"
        'enable-rcon=false'
        'enable-query=false'
        'broadcast-console-to-ops=false'
        'sync-chunk-writes=false'
        'spawn-protection=0'
        'difficulty=easy'
        'gamemode=survival'
        'max-players=5'
        'view-distance=2'
        'simulation-distance=2'
        'white-list=false'
        'network-compression-threshold=256'
    ) | Set-Content -Path $serverPropsPath

    $stdoutPath = Join-Path $logsDir 'zstdnet-startup-check.stdout.log'
    $stderrPath = Join-Path $logsDir 'zstdnet-startup-check.stderr.log'
    if (-not $KeepArtifacts) {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }

    Write-Host "==> Starting $($Target.name)"
    $process = Start-Process -FilePath $Target.gradleBat `
        -ArgumentList @('--project-cache-dir', $Target.projectCacheDir, 'runServer') `
        -WorkingDirectory $Target.projectDir `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    try {
        $readyPatterns = @(
            '[zstdnet-server] started: mode=dedicated'
        )
        $text = Wait-ForPattern -LogPath $stdoutPath -Patterns $readyPatterns -TimeoutSeconds 300 -ProcessId $process.Id
        Start-Sleep -Seconds 2
    } finally {
        Stop-ProcessTree -Id $process.Id
        Start-Sleep -Seconds 2
    }

    $stdout = if (Test-Path $stdoutPath) { Get-Content -Path $stdoutPath -Raw } else { '' }
    $stderr = if (Test-Path $stderrPath) { Get-Content -Path $stderrPath -Raw } else { '' }
    $serverProps = Parse-Properties -Path $serverPropsPath
    $zstdProps = Parse-Properties -Path $zstdPropsPath
    $backendBindPort = $null
    $backendBindMatches = [regex]::Matches($stdout, 'Starting Minecraft server on \*:(\d+)')
    if ($backendBindMatches.Count -gt 0) {
        $backendBindPort = $backendBindMatches[$backendBindMatches.Count - 1].Groups[1].Value
    }

    $failures = New-Object System.Collections.Generic.List[string]

    foreach ($needle in @(
        'auto takeover armed',
        'public entry is',
        'server.properties port is now the public entry; backend port was reassigned automatically.',
        $expectedLog
    )) {
        if ($stdout -notmatch [regex]::Escape($needle)) {
            $failures.Add("missing log: $needle")
        }
    }

    if (-not $backendBindPort) {
        $failures.Add('missing log: Starting Minecraft server on *:<backend-port>')
    } elseif ($backendBindPort -eq [string]$Target.publicPort) {
        $failures.Add("backend bind port should differ from public port $($Target.publicPort), actual $backendBindPort")
    }

    if (-not $serverProps.ContainsKey('server-port')) {
        $failures.Add('server.properties missing server-port')
    } elseif ($serverProps['server-port'] -ne [string]$Target.publicPort) {
        $failures.Add("server.properties server-port should stay on public port $($Target.publicPort), actual $($serverProps['server-port'])")
    }

    if (($serverProps['network-compression-threshold']) -ne $expectedThreshold) {
        $failures.Add("server.properties network-compression-threshold should be $expectedThreshold, actual $($serverProps['network-compression-threshold'])")
    }

    foreach ($required in @('enabled', 'auto_takeover', 'listen', 'target')) {
        if (-not $zstdProps.ContainsKey($required)) {
            $failures.Add("zstdnet-server.properties missing $required")
        }
    }

    if ($zstdProps.ContainsKey('listen') -and $zstdProps['listen'] -ne "0.0.0.0:$($Target.publicPort)") {
        $failures.Add("zstdnet listen mismatch: expected 0.0.0.0:$($Target.publicPort), actual $($zstdProps['listen'])")
    }

    if ($zstdProps.ContainsKey('target') -and $backendBindPort) {
        $expectedTarget = "127.0.0.1:$backendBindPort"
        if ($zstdProps['target'] -ne $expectedTarget) {
            $failures.Add("zstdnet target mismatch: expected $expectedTarget, actual $($zstdProps['target'])")
        }
    }

    [pscustomobject]@{
        Name                = $Target.name
        OnlineMode          = $onlineMode
        PublicPort          = $Target.publicPort
        BackendPort         = $backendBindPort
        CompressionThreshold= $serverProps['network-compression-threshold']
        Listen              = $zstdProps['listen']
        Target              = $zstdProps['target']
        Passed              = ($failures.Count -eq 0)
        Failures            = @($failures)
        StdoutLog           = $stdoutPath
        StderrLog           = $stderrPath
        ServerPropsPath     = $serverPropsPath
        ZstdPropsPath       = $zstdPropsPath
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleHome = Join-Path $buildRoot 'tools\gradle-8.8\bin\gradle.bat'

$targets = @(
    @{
        name = 'forge-1.20.1 offline'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-forge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-forge\run'
        publicPort = 25565
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $false
    },
    @{
        name = 'neoforge-1.20.1 offline'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.20.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-neoforge\run'
        publicPort = 25575
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $false
    },
    @{
        name = 'neoforge-1.21.1 offline'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.21.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.21.1\zstdnet-neoforge\run'
        publicPort = 25585
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-21'
        onlineMode = $false
    },
    @{
        name = 'fabric-1.20.1 offline'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.20.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-fabric\run'
        publicPort = 25595
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $false
    },
    @{
        name = 'fabric-1.21.1 offline'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.21.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.21.1\zstdnet-fabric\run'
        publicPort = 25605
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-21'
        onlineMode = $false
    },
    @{
        name = 'forge-1.20.1 online'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-forge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-forge\run'
        publicPort = 25565
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $true
    },
    @{
        name = 'neoforge-1.20.1 online'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.20.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-neoforge\run'
        publicPort = 25575
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $true
    },
    @{
        name = 'neoforge-1.21.1 online'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.21.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.21.1\zstdnet-neoforge\run'
        publicPort = 25585
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-21'
        onlineMode = $true
    },
    @{
        name = 'fabric-1.20.1 online'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.20.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.20.1\zstdnet-fabric\run'
        publicPort = 25595
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-17'
        onlineMode = $true
    },
    @{
        name = 'fabric-1.21.1 online'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.21.1-startup-check'
        runDir = Join-Path $buildRoot 'mods\1.21.1\zstdnet-fabric\run'
        publicPort = 25605
        gradleBat = $gradleHome
        javaHome = 'C:\Program Files\Java\jdk-21'
        onlineMode = $true
    }
)

$results = @()
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
$env:GRADLE_USER_HOME = $gradleUserHome

foreach ($target in $targets) {
    if (-not (Test-Path $target.javaHome)) {
        throw "Missing JDK: $($target.javaHome)"
    }
    $env:JAVA_HOME = $target.javaHome
    $env:Path = (Join-Path $target.javaHome 'bin') + ';' + $originalPath
    $results += Invoke-TargetCheck -Target $target
}

$env:JAVA_HOME = $originalJavaHome
$env:Path = $originalPath

$failed = $results | Where-Object { -not $_.Passed }
$results | Format-Table Name, OnlineMode, Passed, PublicPort, BackendPort, CompressionThreshold, Listen, Target -AutoSize

if ($failed) {
    foreach ($item in $failed) {
        Write-Host ""
        Write-Host "[$($item.Name)] failures:"
        foreach ($failure in $item.Failures) {
            Write-Host "  - $failure"
        }
        Write-Host "  stdout: $($item.StdoutLog)"
        Write-Host "  stderr: $($item.StderrLog)"
        Write-Host "  server.properties: $($item.ServerPropsPath)"
        Write-Host "  zstd config: $($item.ZstdPropsPath)"
    }
    throw "Runtime startup verification failed."
}
