$ErrorActionPreference = 'Stop'

$sbtVersion = '1.8.3'
$launcherSha256 = 'be5b810207403aece1449ce19b979c9c4cb36ea42e2e75b282464822535410da'
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
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Java 8 is required, but java was not found on PATH'
    }
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& java -version 2>&1 | Out-String).Trim()
    $javaExitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorActionPreference
    if ($javaExitCode -ne 0) {
        throw 'Java 8 is required, but java -version failed'
    }
    if ($javaVersion -notmatch 'version "1\.8\.') {
        throw "Java 8 is required; java -version reported: $javaVersion"
    }

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

    & java -jar $launcher @args
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
