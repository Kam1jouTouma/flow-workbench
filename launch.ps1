$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
try {
    . (Join-Path $PSScriptRoot 'java-runtime.ps1')
    $jarPath = Join-Path $PSScriptRoot 'workflow-ui-v0.16.1.jar'
    if (-not (Test-Path -LiteralPath $jarPath)) {
        & (Join-Path $PSScriptRoot 'build.ps1')
    }
    $javaPath = Find-ProjectJava
    & $javaPath -jar $jarPath
    if ($LASTEXITCODE -ne 0) {
        throw ('Java 启动失败，退出码：' + $LASTEXITCODE)
    }
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host '按 Enter 关闭'
    exit 1
}
