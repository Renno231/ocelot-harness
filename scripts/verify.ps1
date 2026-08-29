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
    $jarEntries = @(& jar tf $assemblyJar)
    if ($LASTEXITCODE -ne 0 -or
        $jarEntries -notcontains 'META-INF/ocelot-harness/THIRD_PARTY_NOTICES.md' -or
        $jarEntries -notcontains 'META-INF/ocelot-harness/sbom.cdx.json' -or
        $jarEntries -notcontains 'META-INF/ocelot-harness/licenses/unifont-OFL-1.1.txt') {
        throw 'Packaged release metadata or license notices are incomplete'
    }
    Write-Host 'PASS: packaged release metadata and license notices'

    $viewerHelp = (& java -cp $assemblyJar ocelot.harness.app.OcelotViewer --help | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $viewerHelp -notmatch '^usage: ocelot-viewer ') {
        throw 'Packaged viewer entrypoint is unavailable'
    }
    Write-Host 'PASS: packaged viewer entrypoint'

    $smokeDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "ocelot-harness-smoke-$PID-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $smokeDirectory | Out-Null
    try {
        $projectDirectory = Join-Path $smokeDirectory 'project with spaces'
        New-Item -ItemType Directory -Path $projectDirectory | Out-Null
        Copy-Item -Path (Join-Path $repositoryRoot 'fixtures\vertical-spike\*') -Destination $projectDirectory -Recurse
        $protocolInput = @'
{"jsonrpc":"2.0","id":1,"method":"harness.version","params":{"protocolMajor":1}}
{"jsonrpc":"2.0","id":2,"method":"workspace.describe"}
{"jsonrpc":"2.0","id":3,"method":"machine.start","params":{"computerId":"main"}}
{"jsonrpc":"2.0","id":4,"method":"simulation.run","params":{"condition":{"type":"screen_contains","screenId":"main","text":"READY"},"maxTicks":2000,"timeoutMillis":10000}}
{"jsonrpc":"2.0","id":5,"method":"screen.capture","params":{"screenId":"main","path":"screens/packaged-protocol.png","format":"png"}}
{"jsonrpc":"2.0","id":6,"method":"snapshot.save","params":{"name":"packaged-ready"}}
{"jsonrpc":"2.0","id":7,"method":"screen.input","params":{"screenId":"main","input":{"type":"touch","x":1,"y":1}}}
{"jsonrpc":"2.0","id":8,"method":"simulation.run","params":{"condition":{"type":"screen_contains","screenId":"main","text":"TOUCHED"},"maxTicks":2000,"timeoutMillis":10000}}
{"jsonrpc":"2.0","id":9,"method":"snapshot.load","params":{"name":"packaged-ready"}}
{"jsonrpc":"2.0","id":10,"method":"screen.input","params":{"screenId":"main","input":{"type":"paste","text":"PACKAGED-SMOKE"}}}
{"jsonrpc":"2.0","id":11,"method":"simulation.run","params":{"condition":{"type":"screen_contains","screenId":"main","text":"PACKAGED-SMOKE"},"maxTicks":2000,"timeoutMillis":10000}}
{"jsonrpc":"2.0","id":12,"method":"diagnostics.collect","params":{"path":"diagnostics/packaged-protocol.zip"}}
{"jsonrpc":"2.0","id":13,"method":"service.shutdown"}
'@
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = 'java'
        $startInfo.Arguments = "-jar `"$assemblyJar`" serve --stdio --project `"$projectDirectory`""
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        if (-not $process.Start()) {
            throw 'Unable to start packaged protocol smoke process'
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($protocolInput)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill()
            throw 'Packaged protocol smoke process exceeded 60 seconds'
        }
        $protocolOutput = $stdoutTask.Result.Trim()
        $serviceLog = $stderrTask.Result.Trim()
        if ($serviceLog) {
            [Console]::Error.WriteLine($serviceLog)
        }
        if ($process.ExitCode -ne 0) {
            throw "Packaged protocol smoke failed with exit code $($process.ExitCode)"
        }
        $frames = @($protocolOutput -split "`r?`n" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
        if ($frames.Count -ne 13) {
            throw "Packaged protocol smoke returned $($frames.Count) frames instead of 13"
        }
        foreach ($frame in $frames) {
            if ($frame.jsonrpc -ne '2.0' -or $frame.PSObject.Properties.Name -contains 'error') {
                throw "Invalid packaged protocol response: $($frame | ConvertTo-Json -Compress)"
            }
        }
        $expectedHarnessCommit = (& git rev-parse HEAD).Trim()
        $versionFrame = ($frames | Where-Object { $_.id -eq 1 }).result
        if ($versionFrame.harnessCommit -ne $expectedHarnessCommit -or
            $versionFrame.brainCommit -ne 'bec1cc6b1e9e588692f753e9c617063c74967fed' -or
            ($frames | Where-Object { $_.id -eq 2 }).result.projectId -ne 'vertical-spike' -or
            ($frames | Where-Object { $_.id -eq 4 }).result.stopReason.type -ne 'condition_satisfied' -or
            ($frames | Where-Object { $_.id -eq 5 }).result.relativePath -ne 'screens/packaged-protocol.png' -or
            ($frames | Where-Object { $_.id -eq 12 }).result.artifact.relativePath -ne 'diagnostics/packaged-protocol.zip' -or
            -not ($frames | Where-Object { $_.id -eq 13 }).result.shuttingDown) {
            throw 'Packaged protocol smoke responses are incomplete'
        }
        Write-Host 'PASS: packaged stdio protocol vertical smoke test'
    } finally {
        Remove-Item -LiteralPath $smokeDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
