function Find-ProjectJava {
    param([switch]$RequireCompiler)
    $path = Join-Path $PSScriptRoot 'runtime\java\bin\java.exe'
    if (Test-Path -LiteralPath $path) {
        return $path
    }
    throw 'Bundled Java is missing.'
}
