$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent $scriptDirectory
Set-Location $repositoryRoot

function Invoke-Checked([string] $Description, [scriptblock] $Command) {
    Write-Host "--- $Description ---"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

try {
    $expectedBrainCommit = 'bec1cc6b1e9e588692f753e9c617063c74967fed'
    $actualBrainCommit = (& git -C lib/ocelot-brain rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the ocelot-brain submodule commit'
    }
    if ($actualBrainCommit -ne $expectedBrainCommit) {
        throw "ocelot-brain must be pinned to $expectedBrainCommit, found $actualBrainCommit"
    }
    $brainStatus = (& git -C lib/ocelot-brain status --short | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect the ocelot-brain submodule status'
    }
    if ($brainStatus) {
        throw 'lib/ocelot-brain contains uncommitted changes'
    }
    Write-Host "PASS: clean ocelot-brain pin $actualBrainCommit"

    Invoke-Checked 'SBT bootstrap contract' { & "$scriptDirectory\test-sbt-bootstrap.cmd" }
    Invoke-Checked 'format, strict compile, tests, and assembly' {
        & "$scriptDirectory\sbtw.cmd" ';scalafmtCheckAll;harnessCore/clean;harnessApp/clean;harnessCore/compile;harnessApp/compile;harnessCore/test;harnessApp/test;harnessApp/assembly'
    }

    $assemblyJar = Join-Path $repositoryRoot 'modules\app\target\ocelot-harness.jar'
    if (-not (Test-Path -LiteralPath $assemblyJar -PathType Leaf)) {
        throw "Expected assembly was not created at $assemblyJar"
    }

    $smokeDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "ocelot-harness-smoke-$PID-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $smokeDirectory | Out-Null
    try {
        $nativeDirectory = Join-Path $smokeDirectory 'native-libraries'
        $runtimeDirectory = Join-Path $smokeDirectory 'runtime'
        $projectDirectory = Join-Path $smokeDirectory 'project'
        New-Item -ItemType Directory -Path $projectDirectory | Out-Null
        Copy-Item -Path (Join-Path $repositoryRoot 'fixtures\vertical-spike\*') -Destination $projectDirectory -Recurse
        $savedErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $smokeOutput = (& java -jar $assemblyJar $nativeDirectory $runtimeDirectory $projectDirectory 2>&1 | Out-String).Trim()
        $smokeExitCode = $LASTEXITCODE
        $ErrorActionPreference = $savedErrorActionPreference
        Write-Host $smokeOutput
        if ($smokeExitCode -ne 0) {
            throw "Packaged smoke test failed with exit code $smokeExitCode"
        }
        foreach ($marker in @(
            'BRAIN_LIFECYCLE_INITIALIZED version=0.24.2',
            'BRAIN_NATIVE_LUA_AVAILABLE=true',
            'BRAIN_PROJECT_SESSION_OPENED',
            'BRAIN_VERTICAL_READY=true',
            'BRAIN_VERTICAL_PNG=true',
            'BRAIN_VERTICAL_SNAPSHOT=true',
            'BRAIN_VERTICAL_TOUCH=true',
            'BRAIN_VERTICAL_RESTORE=true',
            'BRAIN_VERTICAL_PASTE=true',
            'BRAIN_VERTICAL_HOST_EDIT=true',
            'BRAIN_VERTICAL_DIAGNOSTICS=true',
            'BRAIN_PROJECT_SESSION_CLOSED',
            'BRAIN_LIFECYCLE_SHUTDOWN',
            'HARNESS_NON_DAEMON_THREADS=0'
        )) {
            if (-not $smokeOutput.Contains($marker)) {
                throw "Packaged smoke test output is missing marker: $marker"
            }
        }
        Write-Host 'PASS: packaged vertical brain smoke test'
    } finally {
        Remove-Item -LiteralPath $smokeDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
