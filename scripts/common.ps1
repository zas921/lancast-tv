# Shared by the standalone SDK builder and JVM tests (PowerShell 5.1 / 7+).
$LanCastWindowsHost = $env:OS -eq 'Windows_NT'
function Resolve-JavaTool {
    param([string]$Name, [string]$JavaPath)
    $fileName = $Name
    if ($LanCastWindowsHost) { $fileName += '.exe' }
    if ($JavaPath) {
        $candidate = Join-Path $JavaPath "bin/$fileName"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
        throw "JDK tool not found: $candidate. Use JDK 17 and set JAVA_HOME or -JavaPath."
    }
    $command = Get-Command $fileName -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "JDK tool '$Name' not found. Install JDK 17 and set JAVA_HOME or -JavaPath."
}
function Invoke-Tool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}
