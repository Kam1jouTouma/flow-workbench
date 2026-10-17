# 使用独立 Office 实例并在 finally 中释放 COM。 / Release owned Office COM objects in finally.
param([Parameter(Mandatory = $true)][string]$Source, [Parameter(Mandatory = $true)][string]$Target, [Parameter(Mandatory = $true)][ValidateSet('WORD', 'EXCEL', 'POWERPOINT')][string]$Family)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$officeApp = $null
$officeDocument = $null
$officeMutex = [Threading.Mutex]::new($false, ("Local\FlowOfficePdf-" + $Family))
$ownsMutex = $false
try {
    try {
        $ownsMutex = $officeMutex.WaitOne(180000)
    }
    catch [Threading.AbandonedMutexException] {
        $ownsMutex = $true
    }
    if (-not $ownsMutex) {
        throw 'Timed out waiting for the Office converter.'
    }
    $processName = switch ($Family) {
        'WORD' {
            'WINWORD'
        } 'EXCEL' {
            'EXCEL'
        } 'POWERPOINT' {
            'POWERPNT'
        }
    }
    for ($exitWait = 0; $exitWait -lt 50 -and (Get-Process -Name $processName -ErrorAction SilentlyContinue); $exitWait++) {
        Start-Sleep -Milliseconds 100
    }
    if (Get-Process -Name $processName -ErrorAction SilentlyContinue) {
        throw 'The Office application is already running. Close it before batch conversion, or use LibreOffice.'
    }
    switch ($Family) {
        'WORD' {
            $officeApp = New-Object -ComObject Word.Application
            $officeApp.Visible = $false
            $officeApp.DisplayAlerts = 0
            $officeApp.AutomationSecurity = 3
            $officeDocument = $officeApp.Documents.Open($Source, $false, $true)
            $officeDocument.ExportAsFixedFormat($Target, 17)
        }
        'EXCEL' {
            $officeApp = New-Object -ComObject Excel.Application
            $officeApp.Visible = $false
            $officeApp.DisplayAlerts = $false
            $officeApp.AutomationSecurity = 3
            $officeDocument = $officeApp.Workbooks.Open($Source, 0, $true)
            $officeDocument.ExportAsFixedFormat(0, $Target)
        }
        'POWERPOINT' {
            $officeApp = New-Object -ComObject PowerPoint.Application
            $officeApp.AutomationSecurity = 3
            $officeDocument = $officeApp.Presentations.Open($Source, -1, 0, 0)
            $officeDocument.SaveAs($Target, 32)
        }
    }
    Write-Output 'Office PDF export completed'
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 4
}
finally {
    try {
        if ($null -ne $officeDocument) {
            if ($Family -eq 'WORD') {
                $officeDocument.Close(0)
            }
            elseif ($Family -eq 'EXCEL') {
                $officeDocument.Close($false)
            }
            else {
                $officeDocument.Close()
            }
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($officeDocument)
        }
        if ($null -ne $officeApp) {
            $officeApp.Quit()
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($officeApp)
        }
        [GC]::Collect()
        [GC]::WaitForPendingFinalizers()
    }
    finally {
        if ($ownsMutex) {
            $officeMutex.ReleaseMutex()
        }
        $officeMutex.Dispose()
    }
}
