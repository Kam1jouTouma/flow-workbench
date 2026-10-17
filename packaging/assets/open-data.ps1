$data = Join-Path $PSScriptRoot 'data'
$p = Join-Path $PSScriptRoot 'install-data.path'
if (Test-Path -LiteralPath $p) {
    $data = [IO.File]::ReadAllText($p).Trim()
}
Start-Process explorer.exe -ArgumentList ('\"' + $data + '\"')
