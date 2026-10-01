param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$retiredClasses = @(
    'cn/tohsaka/factory/zstdnet/proxy/LocalZstdNet',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort',
    'cn/tohsaka/factory/zstdnet/coremod/ConnectScreenHooks',
    'cn/tohsaka/factory/zstdnet/mixin/ConnectScreenMixin',
    'cn/tohsaka/factory/zstdnet/mixin/ClientIntentionPacketMixin'
)
$requiredClasses = @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class'
)
$targets = @(
    @{ Version = '1.20.1'; Loader = 'forge'; SourceLoader = 'forge' },
    @{ Version = '1.20.1'; Loader = 'neoforge'; SourceLoader = 'forge' },
    @{ Version = '1.20.1'; Loader = 'fabric'; SourceLoader = 'fabric' },
    @{ Version = '1.21.1'; Loader = 'neoforge'; SourceLoader = 'neoforge' },
    @{ Version = '1.21.1'; Loader = 'fabric'; SourceLoader = 'fabric' }
)

foreach ($target in $targets) {
    $name = "$($target.Version)-$($target.Loader)"
    $project = Join-Path $repoRoot "mods\$($target.Version)\zstdnet-$($target.Loader)"
    $source = Join-Path $repoRoot "mods\$($target.Version)\zstdnet-$($target.SourceLoader)"
    $versionLine = Get-Content (Join-Path $project 'gradle.properties') |
        Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
    if (-not $versionLine) {
        throw "$name is missing mod_version"
    }
    $modVersion = $versionLine.Substring('mod_version='.Length)
    $jarPath = Join-Path $buildRoot "mods\$($target.Version)\zstdnet-$($target.Loader)\libs\zstdnet-$name-$modVersion.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "$name jar is missing: $jarPath"
    }

    $publisher = Get-Content -Raw (Join-Path $source 'src\main\java\cn\tohsaka\factory\zstdnet\client\ClientProxyPublisher.java')
    if ($publisher -notmatch 'zstdnet.hud.integrated.title') {
        throw "$name HUD title is missing"
    }
    $bootstrap = Get-Content -Raw (Join-Path $source 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyBootstrap.java')
    if ($bootstrap -match 'setUsesAuthentication\s*\(') {
        throw "$name must leave Minecraft authentication to the server and LAN settings"
    }
    foreach ($lang in @('en_us.json', 'zh_cn.json')) {
        $file = Join-Path $source "src\main\resources\assets\zstdnet\lang\$lang"
        $translation = Get-Content -Raw -Encoding UTF8 $file | ConvertFrom-Json
        $title = $translation.'zstdnet.hud.integrated.title'
        if (-not $title -or $title -match '内置|外置|Integrated|External') {
            throw "$name $lang HUD still exposes a proxy mode"
        }
    }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entries = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            [void]$entries.Add($entry.FullName)
        }
        foreach ($entry in $requiredClasses) {
            if (-not $entries.Contains($entry)) {
                throw "$name jar is missing $entry"
            }
        }
        foreach ($class in $retiredClasses) {
            if ($entries.Contains("$class.class") -or
                    @($entries | Where-Object { $_.StartsWith("$class" + '$') }).Count -gt 0) {
                throw "$name jar still contains $class"
            }
        }
        if (-not $entries.Contains('assets/zstdnet/lang/en_us.json') -or
                -not $entries.Contains('assets/zstdnet/lang/zh_cn.json')) {
            throw "$name jar is missing HUD translations"
        }
    } finally {
        $zip.Dispose()
    }
    Write-Host "$name OK: $jarPath"
}

Write-Host 'Static architecture verification passed.'
