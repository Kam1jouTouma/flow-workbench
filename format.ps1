param([switch]$Check)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'java-runtime.ps1')
$javaPath = Find-ProjectJava -RequireCompiler
$cache = Join-Path $PSScriptRoot 'work\format-tools'
$formatter = Join-Path $cache 'google-java-format-1.37.0-all-deps.jar'
$expected = '834B2A0C38CB774953322A84B5CA3F2F40DD3156650B3CD44D3B744345962F7A'
# Fixed version and digest keep formatting reproducible. / 固定版本和摘要保证格式一致。
if (-not (Test-Path -LiteralPath $formatter)) {
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.37.0/google-java-format-1.37.0-all-deps.jar' -OutFile $formatter
}
if ((Get-FileHash -LiteralPath $formatter -Algorithm SHA256).Hash -ne $expected) {
    throw 'Formatter checksum mismatch.'
}
$roots = @('src', 'tests', 'packaging\assets\script-templates')
$sources = @($roots | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $_) -Filter '*.java' -File -Recurse
    } | ForEach-Object { $_.FullName })
$options = @('--aosp', '--skip-reflowing-long-strings')
if ($Check) {
    $options += @('--dry-run', '--set-exit-if-changed')
}
else {
    $options += '--replace'
}
& $javaPath -jar $formatter @options @sources
if ($LASTEXITCODE -ne 0) {
    throw 'Java formatting check failed.'
}
