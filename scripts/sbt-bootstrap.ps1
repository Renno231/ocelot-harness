$ErrorActionPreference = 'Stop'

$sbtVersion = '1.10.11'
$launcherSha256 = 'e988d533a020e5b60ec22c3b5df4cd3e3df465f4fdc3951c63036e21483978e4'
$launcherUrl = if ($env:SBT_LAUNCHER_URL) {
    $env:SBT_LAUNCHER_URL
} else {
    "https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/$sbtVersion/sbt-launch-$sbtVersion.jar"
}
$cacheRoot = if ($env:SBT_BOOTSTRAP_CACHE) {
    $env:SBT_BOOTSTRAP_CACHE
} else {
    Join-Path $env:LOCALAPPDATA 'OcelotHarness\sbt'
}
$launcher = Join-Path $cacheRoot "sbt-launch-$sbtVersion.jar"

function Assert-LauncherChecksum([string] $Path) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $launcherSha256) {
        throw "SBT launcher checksum mismatch: expected $launcherSha256, got $actual"
    }
}

try {
    $sbtArguments = $args
    . (Join-Path $PSScriptRoot 'java-common.ps1')
    $java = Resolve-OcelotJava (Split-Path -Parent $PSScriptRoot) 'build'

    New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        $temporary = "$launcher.tmp.$PID"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $launcherUrl -OutFile $temporary
            Assert-LauncherChecksum $temporary
            Move-Item -LiteralPath $temporary -Destination $launcher
        } finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    } else {
        Assert-LauncherChecksum $launcher
    }

    & $java -jar $launcher @sbtArguments
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
