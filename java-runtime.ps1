$ErrorActionPreference = 'Stop'
function Find-ProjectJava {
    param([switch]$RequireCompiler)
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))
    }
    $candidates.Add((Join-Path $PSScriptRoot 'runtime\java\bin\java.exe'))
    foreach ($command in @(Get-Command java.exe -All -ErrorAction SilentlyContinue)) {
        if ($command.Source -and $command.Source -notlike '*\Oracle\Java\javapath\*') {
            $candidates.Add($command.Source)
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $bin = Split-Path -Parent $candidate
            if (-not $RequireCompiler -or (Test-Path -LiteralPath (Join-Path $bin 'javac.exe'))) {
                return $candidate
            }
        }
    }
    throw '未找到可用的 Java。请安装 JDK 17 或更高版本，并设置 JAVA_HOME。'
}
