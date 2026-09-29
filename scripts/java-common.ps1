param(
    [string] $JavaSelectionRoot,
    [ValidateSet('runtime', 'build')] [string] $JavaSelectionMode = 'runtime'
)

function Resolve-OcelotJava([string] $Root, [string] $SelectionMode) {
    if ($env:OCELOT_JAVA) {
        $candidate = $env:OCELOT_JAVA
        $source = 'OCELOT_JAVA'
    } elseif ($SelectionMode -eq 'runtime' -and (Test-Path -LiteralPath (Join-Path $Root 'runtime') -PathType Container)) {
        $candidate = Join-Path $Root 'runtime\bin\java.exe'
        $source = 'bundled runtime'
    } elseif ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $source = 'JAVA_HOME'
    } else {
        $command = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        $candidate = if ($command) { $command.Source } else { '' }
        $source = 'PATH'
    }
    if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Java 8 or newer is required; invalid Java executable selected through $($source): $candidate"
    }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $version = (& $candidate -version 2>&1 | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($code -ne 0) { throw "Java 8 or newer is required; selected java -version failed: $candidate" }
    $major = 0
    if ($version -match 'version "(?:1\.)?([0-9]+)') { $major = [int] $Matches[1] }
    if ($major -lt 8) {
        throw "Java 8 or newer is required; selected through $($source): $candidate. $version. Use a bundled download, JAVA_HOME or OCELOT_JAVA. Verified Java versions are documented in the download guide."
    }
    return $candidate
}

if ($MyInvocation.InvocationName -ne '.') {
    try { Resolve-OcelotJava $JavaSelectionRoot $JavaSelectionMode }
    catch {
        [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
        exit 1
    }
}
