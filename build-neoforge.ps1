param(
    [ValidateSet('1.20.1', '1.21.1')]
    [string]$MinecraftVersion = '1.21.1'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleHome = Join-Path $buildRoot 'tools\gradle-8.8'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
$projectCacheDir = Join-Path $buildRoot "cache\project-cache\zstdnet-neoforge-$MinecraftVersion"
$projectDir = Join-Path $repoRoot "mods\$MinecraftVersion\zstdnet-neoforge"

switch ($MinecraftVersion) {
    '1.20.1' { $javaHome = 'C:\Program Files\Java\jdk-17' }
    '1.21.1' { $javaHome = 'C:\Program Files\Java\jdk-21' }
    default { throw "Unsupported NeoForge target: $MinecraftVersion" }
}

$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'

if (-not (Test-Path $projectDir)) {
    throw "Project directory not found: $projectDir"
}

if (-not (Test-Path $javaHome)) {
    throw "Required JDK not found: $javaHome"
}

if (-not (Test-Path $gradleBat)) {
    throw "Gradle 8.8 not found: $gradleBat"
}

New-Item -ItemType Directory -Force -Path $gradleUserHome, $projectCacheDir | Out-Null

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
$env:GRADLE_USER_HOME = $gradleUserHome

Push-Location $projectDir
try {
    & $gradleBat '--project-cache-dir' $projectCacheDir 'build'
} finally {
    Pop-Location
}
