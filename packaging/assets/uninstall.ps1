# 只移除安装清单中的程序文件，保留独立用户数据。 / Remove registered application files, retain user data.
param([switch]$Silent, [switch]$Preview)
$ErrorActionPreference = 'Stop'
$app = [IO.Path]::GetFullPath($PSScriptRoot)
$marker = Join-Path $app 'installation.json'
if (-not(Test-Path -LiteralPath $marker)) {
    throw 'Installation record missing. No files removed.'
}
$record = Get-Content -LiteralPath $marker -Raw -Encoding UTF8 | ConvertFrom-Json
if ([IO.Path]::GetFullPath([string]$record.app) -ne $app) {
    throw 'Installation directory mismatch. No files removed.'
}
if ($record.registryId -notmatch '^FlowWorkbench-[0-9.]+-[a-f0-9]+$') {
    throw 'Invalid installation identity'
}
if ($record.shortcut) {
    $desktop = [Environment]::GetFolderPath('Desktop')
    $link = [IO.Path]::GetFullPath([string]$record.shortcut)
    if ([IO.Path]::GetDirectoryName($link) -ne $desktop -or [IO.Path]::GetFileName($link) -notlike 'Flow 工作流预览版 *.lnk') {
        throw 'Invalid shortcut path'
    }
}
$jar = Join-Path $app ('workflow-ui-v' + $record.version + '.jar')
if (-not(Test-Path -LiteralPath $jar)) {
    throw 'Application marker missing. No files removed.'
}
# Remove only shipped names; preserve data, user scripts, runs and unrecognized files.
$names = @('runtime', 'lib', 'fonts', 'licenses', 'samples', 'script-templates', 'docs', 'tools', 'launch.cmd', 'launch.ps1', 'java-runtime.ps1', 'open-data.ps1', 'install-audio.ps1', 'manual.html', '先读我.txt', '打开数据文件夹.cmd', '阅读使用说明.cmd', '安装音频组件.cmd', '卸载程序.cmd', 'uninstall.ps1', 'README.md', 'RELEASE_NOTES.md', 'LICENSE.txt', 'SECURITY.md', 'installation.json', 'install-data.path', 'user-settings.properties', ('workflow-ui-v' + $record.version + '.jar'), 'ai-text-tool-v1.jar', 'file-input-tool-v1.jar', 'file-output-tool-v1.jar', 'pdf-tool-v1.jar', 'rename-tool-v1.jar')
$targets = @()
foreach ($name in $names) {
    $target = [IO.Path]::GetFullPath((Join-Path $app $name))
    if (-not $target.StartsWith($app + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe removal target'
    }
    if (Test-Path -LiteralPath $target) {
        $targets += $target
    }
}
if ($Preview) {
    $targets
    exit 0
}
if (-not $Silent) {
    Add-Type -AssemblyName PresentationFramework
    $answer = [Windows.MessageBox]::Show('卸载当前版本？请先关闭程序。流程、用户脚本、输出和运行记录会保留。AI 服务配置将随此版本移除。', '卸载 Flow 工作流', 'YesNo', 'Question')
    if ($answer -ne 'Yes') {
        exit 0
    }
}
try {
    # Check for running application before removing anything.
    $running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($jar)
    }
    if ($running) {
        throw '请关闭当前版本的程序后再卸载。'
    }
    # Check every shipped file before the first deletion, including relative-path launches.
    $files = @()
    foreach ($target in $targets) {
        $item = Get-Item -LiteralPath $target -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw '程序目录包含链接，请先移除链接后再卸载。'
        }
        if ($item.PSIsContainer) {
            $children = @(Get-ChildItem -LiteralPath $target -Recurse -Force)
            if ($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
                throw '程序目录包含链接，请先移除链接后再卸载。'
            }
            $files += @($children | Where-Object { -not $_.PSIsContainer })
        }
        else {
            $files += $item
        }
    }
    foreach ($file in $files) {
        if (-not $file.FullName.StartsWith($app + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unsafe preflight target'
        }
        try {
            $stream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
            $stream.Dispose()
        }
        catch {
            throw '程序文件仍被占用，请关闭当前版本及其任务后再卸载。未删除程序文件。'
        }
    }
    foreach ($target in $targets) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    if ($record.shortcut -and (Test-Path -LiteralPath $record.shortcut)) {
        Remove-Item -LiteralPath $record.shortcut -Force
    }
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\' + $record.registryId
    if (Test-Path -LiteralPath $key) {
        Remove-Item -LiteralPath $key
    }
    if (-not(Get-ChildItem -LiteralPath $app -Force)) {
        Remove-Item -LiteralPath $app
    }
    if (-not $Silent) {
        [Windows.MessageBox]::Show('当前程序版本已卸载。用户数据已保留。', 'Flow 工作流') | Out-Null
    }
}
catch {
    if ($Silent) {
        throw
    }
    [Windows.MessageBox]::Show($_.Exception.Message, '卸载未完成') | Out-Null
    exit 1
}
