param(
    [switch]$SkipBuild,
    [switch]$SkipStatic
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$targets = @(
    @{ Version = '1.20.1'; Loader = 'forge'; Jdk = 'C:\Program Files\Java\jdk-17'; Wrapper = '1.20.1\zstdnet-forge' },
    @{ Version = '1.20.1'; Loader = 'neoforge'; Jdk = 'C:\Program Files\Java\jdk-17'; Wrapper = '1.20.1\zstdnet-forge' },
    @{ Version = '1.20.1'; Loader = 'fabric'; Jdk = 'C:\Program Files\Java\jdk-17'; Wrapper = '1.20.1\zstdnet-fabric' },
    @{ Version = '1.21.1'; Loader = 'neoforge'; Jdk = 'C:\Program Files\Java\jdk-21'; Wrapper = '1.21.1\zstdnet-neoforge' },
    @{ Version = '1.21.1'; Loader = 'fabric'; Jdk = 'C:\Program Files\Java\jdk-21'; Wrapper = '1.21.1\zstdnet-fabric' }
)

$originalJavaHome = $env:JAVA_HOME
try {
    if (-not $SkipBuild) {
        foreach ($target in $targets) {
            $project = Join-Path $repoRoot "mods\$($target.Version)\zstdnet-$($target.Loader)"
            $wrapper = Join-Path $repoRoot "mods\$($target.Wrapper)\gradle\wrapper\gradle-wrapper.jar"
            $java = Join-Path $target.Jdk 'bin\java.exe'
            if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $wrapper)) {
                throw "Missing JDK or Gradle wrapper for $($target.Version)-$($target.Loader)"
            }
            Write-Host "==> Building $($target.Version)-$($target.Loader)"
            $env:JAVA_HOME = $target.Jdk
            Push-Location $project
            try {
                & $java -cp $wrapper org.gradle.wrapper.GradleWrapperMain --no-daemon build
                if ($LASTEXITCODE -ne 0) {
                    throw "Build failed for $($target.Version)-$($target.Loader)"
                }
            } finally {
                Pop-Location
            }
        }
    }
    if (-not $SkipStatic) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify-lan-regression.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw 'Static architecture verification failed.'
        }
    }
} finally {
    $env:JAVA_HOME = $originalJavaHome
}

Write-Host 'Regression suite passed.'
