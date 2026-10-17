$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:FLOW_APP_DIR = $PSScriptRoot
try {
    $java = Join-Path $PSScriptRoot 'runtime\java\bin\java.exe'
    if (-not(Test-Path -LiteralPath $java)) {
        throw 'Bundled Java is missing. Please extract or reinstall the complete package.'
    }
    if (-not $env:FLOW_PYTHON) {
        $env:FLOW_PYTHON = Join-Path $PSScriptRoot 'runtime\python\python.exe'
    }
    $data = Join-Path $PSScriptRoot 'data'
    $location = Join-Path $PSScriptRoot 'install-data.path'
    if (Test-Path -LiteralPath $location) {
        $data = [IO.File]::ReadAllText($location).Trim()
    }
    if (-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot "installation.json"))) {
        @{
            app        =$PSScriptRoot
            data       =$data
            version    ="0.16.1"
            registryId =("FlowWorkbench-0.16.1-" + [guid]::NewGuid().ToString("N"))
            shortcut   =""
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot "installation.json") -Encoding UTF8
    }
    $jar = Join-Path $PSScriptRoot 'workflow-ui-v0.16.1.jar'
    & $java '-Djava.awt.headless=true' -jar $jar --initialize-package $PSScriptRoot $data
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to initialize sample workflows.'
    }
    & $java -jar $jar
    if ($LASTEXITCODE -ne 0) {
        throw ('Java exit code ' + $LASTEXITCODE)
    }
}
catch {
    Add-Type -AssemblyName PresentationFramework
    [void][System.Windows.MessageBox]::Show($_.Exception.Message, 'Flow startup failed')
}
