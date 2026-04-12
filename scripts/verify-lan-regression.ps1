param()

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-FileExists {
    param(
        [string]$Path,
        [System.Collections.Generic.List[string]]$Failures,
        [string]$Label
    )

    if (-not (Test-Path $Path)) {
        $Failures.Add("missing ${Label}: $Path")
        return $false
    }
    return $true
}

function Assert-Contains {
    param(
        [string]$Path,
        [string[]]$Patterns,
        [System.Collections.Generic.List[string]]$Failures,
        [string]$Label
    )

    if (-not (Assert-FileExists -Path $Path -Failures $Failures -Label $Label)) {
        return
    }

    $text = Get-Content -Path $Path -Raw
    foreach ($pattern in $Patterns) {
        if ($text -notmatch [regex]::Escape($pattern)) {
            $Failures.Add("$Label missing pattern: $pattern")
        }
    }
}

function Get-JarEntries {
    param(
        [string]$JarPath
    )

    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        return @($zip.Entries | ForEach-Object { $_.FullName })
    } finally {
        $zip.Dispose()
    }
}

function Assert-JarEntries {
    param(
        [string]$JarPath,
        [string[]]$Entries,
        [System.Collections.Generic.List[string]]$Failures,
        [string]$Label
    )

    if (-not (Assert-FileExists -Path $JarPath -Failures $Failures -Label "$Label jar")) {
        return
    }

    $entrySet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($entry in Get-JarEntries -JarPath $JarPath) {
        [void]$entrySet.Add($entry)
    }

    foreach ($entry in $Entries) {
        if (-not $entrySet.Contains($entry)) {
            $Failures.Add("$Label jar missing entry: $entry")
        }
    }
}

function Resolve-LatestJar {
    param(
        [string]$Directory,
        [string]$Pattern
    )

    $match = Get-ChildItem -Path $Directory -Filter $Pattern -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $match) {
        throw "No jar matched pattern '$Pattern' in '$Directory'"
    }
    return $match.FullName
}

function New-Result {
    param(
        [string]$Name,
        [System.Collections.Generic.List[string]]$Failures,
        [string]$JarPath
    )

    [pscustomobject]@{
        Name    = $Name
        Passed  = ($Failures.Count -eq 0)
        JarPath = $JarPath
        Failures = @($Failures)
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'

$translationKeys = @(
    'zstdnet.share_to_lan.port_help',
    'zstdnet.singleplayer.lan_hint',
    'zstdnet.singleplayer.lan_command_hint',
    'zstdnet.command.port.game_set_reopen'
)

$results = @()

# forge 1.20.1
$forgeFailures = New-Object System.Collections.Generic.List[string]
$forgeRoot = Join-Path $repoRoot 'mods\1.20.1\zstdnet-forge'
$forgeJar = Resolve-LatestJar -Directory (Join-Path $buildRoot 'mods\1.20.1\zstdnet-forge\libs') -Pattern '*1.3.6-all.jar'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\java\cn\tohsaka\factory\zstdnet\client\ClientProxyPublisher.java') -Patterns @(
    'Commands.literal("zstdport")',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_hint"))',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_command_hint"))',
    'ServerProxyConfigFile.readListenPort()',
    'zstdnet.command.port.game_set_reopen'
) -Failures $forgeFailures -Label 'forge client publisher'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyBootstrap.java') -Patterns @(
    'config/LAN state changed, reloading proxy.',
    'LAN world published on {}, zstd proxy armed.',
    'LAN mode detected, disabled online authentication by default.'
) -Failures $forgeFailures -Label 'forge server bootstrap'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyRuntime.java') -Patterns @(
    'start(lanPort, RuntimeMode.LAN);',
    'LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.',
    'return running && runtimeMode == RuntimeMode.LAN;'
) -Failures $forgeFailures -Label 'forge server runtime'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyConfigFile.java') -Patterns @(
    'public static int readListenPort()',
    'public static void writePorts(Integer listenPort, Integer targetPort) throws IOException',
    'auto_takeover=false'
) -Failures $forgeFailures -Label 'forge server config file'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\java\cn\tohsaka\factory\zstdnet\coremod\LanCompressionHooks.java') -Patterns @(
    'LAN_THRESHOLD = 1048576',
    'return LAN_THRESHOLD;'
) -Failures $forgeFailures -Label 'forge LAN compression hook'
Assert-Contains -Path (Join-Path $forgeRoot 'src\main\resources\coremods\zstdnet_lan_compression_threshold.js') -Patterns @(
    'patched MinecraftServer#getCompressionThreshold for LAN mode.',
    'DedicatedServerAutoPort'
) -Failures $forgeFailures -Label 'forge LAN coremod'
foreach ($lang in @('en_us.json', 'zh_cn.json')) {
    Assert-Contains -Path (Join-Path $forgeRoot "src\main\resources\assets\zstdnet\lang\$lang") -Patterns $translationKeys -Failures $forgeFailures -Label "forge $lang"
}
Assert-JarEntries -JarPath $forgeJar -Entries @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime.class',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class',
    'assets/zstdnet/lang/en_us.json',
    'assets/zstdnet/lang/zh_cn.json',
    'coremods/zstdnet_lan_compression_threshold.js'
) -Failures $forgeFailures -Label 'forge-1.20.1'
$results += New-Result -Name 'forge-1.20.1' -Failures $forgeFailures -JarPath $forgeJar

# neoforge 1.20.1
$neo1201Failures = New-Object System.Collections.Generic.List[string]
$neo1201Root = Join-Path $repoRoot 'mods\1.20.1\zstdnet-neoforge'
$neo1201Jar = Resolve-LatestJar -Directory (Join-Path $buildRoot 'mods\1.20.1\zstdnet-neoforge\libs') -Pattern '*1.3.6-all.jar'
Assert-Contains -Path (Join-Path $neo1201Root 'build.gradle') -Patterns @(
    "srcDir '../zstdnet-forge/src/main/java'",
    "from('../zstdnet-forge/src/main/resources')",
    'archivesName = "zstdnet-${minecraft_version}-neoforge"'
) -Failures $neo1201Failures -Label 'neoforge 1.20.1 build.gradle'
Assert-JarEntries -JarPath $neo1201Jar -Entries @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime.class',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class',
    'assets/zstdnet/lang/en_us.json',
    'assets/zstdnet/lang/zh_cn.json',
    'coremods/zstdnet_lan_compression_threshold.js'
) -Failures $neo1201Failures -Label 'neoforge-1.20.1'
$results += New-Result -Name 'neoforge-1.20.1' -Failures $neo1201Failures -JarPath $neo1201Jar

# neoforge 1.21.1
$neo1211Failures = New-Object System.Collections.Generic.List[string]
$neo1211Root = Join-Path $repoRoot 'mods\1.21.1\zstdnet-neoforge'
$neo1211Jar = Resolve-LatestJar -Directory (Join-Path $buildRoot 'mods\1.21.1\zstdnet-neoforge\libs') -Pattern '*1.3.6-all.jar'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\client\ClientProxyPublisher.java') -Patterns @(
    'Commands.literal("zstdport")',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_hint"))',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_command_hint"))',
    'ServerProxyConfigFile.readListenPort()',
    'zstdnet.command.port.game_set_reopen'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 client publisher'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyBootstrap.java') -Patterns @(
    'config/LAN state changed, reloading proxy.',
    'LAN world published on {}, zstd proxy armed.',
    'LAN mode detected, disabled online authentication by default.'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 server bootstrap'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyRuntime.java') -Patterns @(
    'start(lanPort, RuntimeMode.LAN);',
    'LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.',
    'return running && runtimeMode == RuntimeMode.LAN;'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 server runtime'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyConfigFile.java') -Patterns @(
    'public static int readListenPort()',
    'public static void writePorts(Integer listenPort, Integer targetPort) throws IOException',
    'auto_takeover=false'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 server config file'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\coremod\LanCompressionHooks.java') -Patterns @(
    'LAN_THRESHOLD = 1048576',
    'return LAN_THRESHOLD;'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 LAN compression hook'
Assert-Contains -Path (Join-Path $neo1211Root 'src\main\resources\coremods\zstdnet_lan_compression_threshold.js') -Patterns @(
    'patched MinecraftServer#getCompressionThreshold for LAN mode.',
    'DedicatedServerAutoPort'
) -Failures $neo1211Failures -Label 'neoforge 1.21.1 LAN coremod'
foreach ($lang in @('en_us.json', 'zh_cn.json')) {
    Assert-Contains -Path (Join-Path $neo1211Root "src\main\resources\assets\zstdnet\lang\$lang") -Patterns $translationKeys -Failures $neo1211Failures -Label "neoforge 1.21.1 $lang"
}
Assert-JarEntries -JarPath $neo1211Jar -Entries @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime.class',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class',
    'assets/zstdnet/lang/en_us.json',
    'assets/zstdnet/lang/zh_cn.json',
    'coremods/zstdnet_lan_compression_threshold.js'
) -Failures $neo1211Failures -Label 'neoforge-1.21.1'
$results += New-Result -Name 'neoforge-1.21.1' -Failures $neo1211Failures -JarPath $neo1211Jar

# fabric 1.20.1
$fabricFailures = New-Object System.Collections.Generic.List[string]
$fabricRoot = Join-Path $repoRoot 'mods\1.20.1\zstdnet-fabric'
$fabricJar = Resolve-LatestJar -Directory (Join-Path $buildRoot 'mods\1.20.1\zstdnet-fabric\libs') -Pattern '*1.3.6-all.jar'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\client\ClientProxyPublisher.java') -Patterns @(
    'ClientCommandManager.literal("zstdport")',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_hint"))',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_command_hint"))',
    'ServerProxyConfigFile.readListenPort()',
    'zstdnet.command.port.game_set_reopen'
) -Failures $fabricFailures -Label 'fabric client publisher'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyBootstrap.java') -Patterns @(
    'config/LAN state changed, reloading proxy.',
    'LAN world published on {}, zstd proxy armed.',
    'LAN mode detected, disabled online authentication by default.'
) -Failures $fabricFailures -Label 'fabric server bootstrap'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyRuntime.java') -Patterns @(
    'start(lanPort, RuntimeMode.LAN);',
    'LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.',
    'return running && runtimeMode == RuntimeMode.LAN;'
) -Failures $fabricFailures -Label 'fabric server runtime'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyConfigFile.java') -Patterns @(
    'public static int readListenPort()',
    'public static void writePorts(Integer listenPort, Integer targetPort) throws IOException',
    'auto_takeover=false'
) -Failures $fabricFailures -Label 'fabric server config file'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\resources\zstdnet.mixins.json') -Patterns @(
    '"DedicatedServerMixin"',
    '"MinecraftServerMixin"',
    '"ShareToLanScreenMixin"'
) -Failures $fabricFailures -Label 'fabric mixins manifest'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\mixin\DedicatedServerMixin.java') -Patterns @(
    'DedicatedServerAutoPort.prepareDedicatedServerProperties'
) -Failures $fabricFailures -Label 'fabric dedicated server mixin'
Assert-Contains -Path (Join-Path $fabricRoot 'src\main\java\cn\tohsaka\factory\zstdnet\mixin\MinecraftServerMixin.java') -Patterns @(
    'LanCompressionHooks.LAN_THRESHOLD',
    'shouldOverrideCompressionThreshold'
) -Failures $fabricFailures -Label 'fabric minecraft server mixin'
foreach ($lang in @('en_us.json', 'zh_cn.json')) {
    Assert-Contains -Path (Join-Path $fabricRoot "src\main\resources\assets\zstdnet\lang\$lang") -Patterns $translationKeys -Failures $fabricFailures -Label "fabric $lang"
}
Assert-JarEntries -JarPath $fabricJar -Entries @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime.class',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class',
    'cn/tohsaka/factory/zstdnet/mixin/DedicatedServerMixin.class',
    'cn/tohsaka/factory/zstdnet/mixin/MinecraftServerMixin.class',
    'cn/tohsaka/factory/zstdnet/mixin/ShareToLanScreenMixin.class',
    'assets/zstdnet/lang/en_us.json',
    'assets/zstdnet/lang/zh_cn.json',
    'zstdnet.mixins.json'
) -Failures $fabricFailures -Label 'fabric-1.20.1'
$results += New-Result -Name 'fabric-1.20.1' -Failures $fabricFailures -JarPath $fabricJar

# fabric 1.21.1
$fabric1211Failures = New-Object System.Collections.Generic.List[string]
$fabric1211Root = Join-Path $repoRoot 'mods\1.21.1\zstdnet-fabric'
$fabric1211Jar = Resolve-LatestJar -Directory (Join-Path $buildRoot 'mods\1.21.1\zstdnet-fabric\libs') -Pattern '*1.3.6-all.jar'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\client\ClientProxyPublisher.java') -Patterns @(
    'ClientCommandManager.literal("zstdport")',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_hint"))',
    'sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_command_hint"))',
    'ServerProxyConfigFile.readListenPort()',
    'zstdnet.command.port.game_set_reopen',
    'ServerData.Type.LAN',
    'ServerData.Type.OTHER'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 client publisher'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyBootstrap.java') -Patterns @(
    'config/LAN state changed, reloading proxy.',
    'LAN world published on {}, zstd proxy armed.',
    'LAN mode detected, disabled online authentication by default.'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 server bootstrap'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyRuntime.java') -Patterns @(
    'start(lanPort, RuntimeMode.LAN);',
    'LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.',
    'return running && runtimeMode == RuntimeMode.LAN;'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 server runtime'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\server\ServerProxyConfigFile.java') -Patterns @(
    'public static int readListenPort()',
    'public static void writePorts(Integer listenPort, Integer targetPort) throws IOException',
    'auto_takeover=false'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 server config file'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\network\LanCompressionSync.java') -Patterns @(
    'PayloadTypeRegistry.playS2C().register',
    'PayloadTypeRegistry.playC2S().register',
    'ClientPlayNetworking.send(new ReadyMessage',
    'ServerPlayNetworking.send(player, new PrepareMessage'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 LAN sync'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\resources\zstdnet.mixins.json') -Patterns @(
    '"compatibilityLevel": "JAVA_21"',
    '"DedicatedServerMixin"',
    '"MinecraftServerMixin"',
    '"ShareToLanScreenMixin"'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 mixins manifest'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\mixin\DedicatedServerMixin.java') -Patterns @(
    'DedicatedServerAutoPort.prepareDedicatedServerProperties'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 dedicated server mixin'
Assert-Contains -Path (Join-Path $fabric1211Root 'src\main\java\cn\tohsaka\factory\zstdnet\mixin\MinecraftServerMixin.java') -Patterns @(
    'LanCompressionHooks.LAN_THRESHOLD',
    'shouldOverrideCompressionThreshold'
) -Failures $fabric1211Failures -Label 'fabric 1.21.1 minecraft server mixin'
foreach ($lang in @('en_us.json', 'zh_cn.json')) {
    Assert-Contains -Path (Join-Path $fabric1211Root "src\main\resources\assets\zstdnet\lang\$lang") -Patterns $translationKeys -Failures $fabric1211Failures -Label "fabric 1.21.1 $lang"
}
Assert-JarEntries -JarPath $fabric1211Jar -Entries @(
    'cn/tohsaka/factory/zstdnet/client/ClientProxyPublisher.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyBootstrap.class',
    'cn/tohsaka/factory/zstdnet/server/ServerProxyRuntime.class',
    'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort.class',
    'cn/tohsaka/factory/zstdnet/network/LanCompressionSync.class',
    'cn/tohsaka/factory/zstdnet/mixin/DedicatedServerMixin.class',
    'cn/tohsaka/factory/zstdnet/mixin/MinecraftServerMixin.class',
    'cn/tohsaka/factory/zstdnet/mixin/ShareToLanScreenMixin.class',
    'assets/zstdnet/lang/en_us.json',
    'assets/zstdnet/lang/zh_cn.json',
    'zstdnet.mixins.json'
) -Failures $fabric1211Failures -Label 'fabric-1.21.1'
$results += New-Result -Name 'fabric-1.21.1' -Failures $fabric1211Failures -JarPath $fabric1211Jar

$results | Format-Table Name, Passed, JarPath -AutoSize

$failed = $results | Where-Object { -not $_.Passed }
if ($failed) {
    foreach ($item in $failed) {
        Write-Host ""
        Write-Host "[$($item.Name)] failures:"
        foreach ($failure in $item.Failures) {
            Write-Host "  - $failure"
        }
        Write-Host "  jar: $($item.JarPath)"
    }
    throw 'LAN regression verification failed.'
}
