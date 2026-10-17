$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'java-runtime.ps1')
$javaPath = Find-ProjectJava -RequireCompiler
$javaBin = Split-Path -Parent $javaPath
$compilerPath = Join-Path $javaBin 'javac.exe'
$jarTool = Join-Path $javaBin 'jar.exe'
$buildRoot = Join-Path $PSScriptRoot 'build'
$logDirectory = Join-Path $PSScriptRoot 'development-logs'
New-Item -ItemType Directory -Path $buildRoot, $logDirectory -Force | Out-Null
$logPath = Join-Path $logDirectory ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-build.log')

function Reset-BuildDirectory([string]$relative) {
    $target = [IO.Path]::GetFullPath((Join-Path $buildRoot $relative))
    $prefix = [IO.Path]::GetFullPath($buildRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build directory escaped workspace.'
    }
    # Only generated classes are removed. / 只清理构建目录，避免旧类残留进新包。
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target | Out-Null
    return $target
}

function Check-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw ($step + ' failed. See ' + $logPath) }
}

@('Flow build / Java 17 target', ('Time: ' + (Get-Date -Format o)), ('JDK: ' + $javaPath)) |
    Set-Content -LiteralPath $logPath -Encoding UTF8
$pdfLibrary = Join-Path $PSScriptRoot 'lib\pdfbox-app-3.0.8.jar'
if (-not (Test-Path -LiteralPath $pdfLibrary)) { throw 'Missing lib/pdfbox-app-3.0.8.jar' }
$classes = Reset-BuildDirectory 'classes'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName })
& $compilerPath '-J-Duser.language=en' --release 17 -encoding UTF-8 -Xlint:all -cp $pdfLibrary -d $classes @sources 2>&1 |
    Tee-Object -FilePath $logPath -Append
Check-Exit 'Application compilation'

# Tools compile without host sources/classes. / 每个工具独立编译，不能隐式依赖主程序。
$sharedSource = Join-Path $PSScriptRoot 'src\shared\java\course\workflow'
$common = @('FileCli', 'Json', 'ToolCapabilities', 'PdfFormats')
$toolSource = Join-Path $PSScriptRoot 'src\tools\java\course\workflow'
$tools = @(
    @{ Jar = 'file-input-tool-v1.jar'; Main = 'FileInputTool'; Helpers = @('BatchFiles'); Shared = @('FileTypeDetector') },
    @{ Jar = 'file-output-tool-v1.jar'; Main = 'FileOutputTool'; Helpers = @(); Shared = @() },
    @{ Jar = 'rename-tool-v1.jar'; Main = 'RenameTool'; Helpers = @('BatchFiles', 'CsvTable'); Shared = @() },
    @{ Jar = 'pdf-tool-v1.jar'; Main = 'PdfTool'; Helpers = @('BatchFiles'); Shared = @('FileTypeDetector', 'ProcessCommands', 'ToolPaths') },
    @{ Jar = 'ai-text-tool-v1.jar'; Main = 'AiTextTool'; Helpers = @(); Shared = @('AiServices', 'ToolPaths') }
)
foreach ($tool in $tools) {
    $toolClasses = Reset-BuildDirectory ('tools\' + $tool.Main)
    $shared = @(($common + $tool.Shared) | ForEach-Object { Join-Path $sharedSource ($_ + '.java') })
    $toolSources = @($shared) + @(($tool.Helpers + @($tool.Main)) | ForEach-Object {
        Join-Path $toolSource ($_ + '.java')
    })
    & $compilerPath '-J-Duser.language=en' --release 17 -encoding UTF-8 -Xlint:all -sourcepath $toolClasses -cp $pdfLibrary -d $toolClasses @toolSources 2>&1 |
        Tee-Object -FilePath $logPath -Append
    Check-Exit ($tool.Main + ' standalone compilation')
    $jarArgs = @('--create', '--file', (Join-Path $PSScriptRoot $tool.Jar), '--main-class', ('course.workflow.' + $tool.Main))
    if ($tool.Main -eq 'PdfTool') {
        $manifest = Join-Path $buildRoot 'pdf-manifest.mf'
        [IO.File]::WriteAllText($manifest, "Class-Path: lib/pdfbox-app-3.0.8.jar`r`n`r`n", [Text.Encoding]::ASCII)
        $jarArgs += @('--manifest', $manifest)
    }
    & $jarTool ("-J-Djava.io.tmpdir=" + $buildRoot) @jarArgs -C $toolClasses . 2>&1 | Tee-Object -FilePath $logPath -Append
    Check-Exit ($tool.Main + ' packaging')
}

# The UI links external tools instead of embedding them. / 主程序通过外部 JAR 引用工具。
$appClasses = Reset-BuildDirectory 'app-classes'
$toolOnly = @('FileInputTool', 'FileOutputTool', 'RenameTool', 'PdfTool', 'AiTextTool', 'BatchFiles', 'CsvTable')
foreach ($file in Get-ChildItem -LiteralPath $classes -Recurse -File -Filter '*.class') {
    $topLevel = ($file.BaseName -split '\$')[0]
    if ($topLevel -in $toolOnly) { continue }
    $relative = $file.FullName.Substring($classes.Length + 1)
    $destination = Join-Path $appClasses $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}
$appManifest = Join-Path $buildRoot 'app-manifest.mf'
$classPath = 'lib/pdfbox-app-3.0.8.jar ' + (($tools | ForEach-Object { $_.Jar }) -join ' ')
[IO.File]::WriteAllText($appManifest, ('Class-Path: ' + $classPath + "`r`n`r`n"), [Text.Encoding]::ASCII)
& $jarTool ("-J-Djava.io.tmpdir=" + $buildRoot) --create --file (Join-Path $PSScriptRoot 'workflow-ui-v0.16.1.jar') --manifest $appManifest --main-class course.workflow.WorkflowApp -C $appClasses . 2>&1 |
    Tee-Object -FilePath $logPath -Append
Check-Exit 'Application packaging'

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw 'Missing .NET Framework compiler.' }
& $csc /nologo /optimize+ /target:exe /reference:System.Web.Extensions.dll ('/out:' + (Join-Path $PSScriptRoot 'tools\audio-extract-tool.exe')) (Join-Path $PSScriptRoot 'tools\audio-extract-tool.cs') 2>&1 |
    Tee-Object -FilePath $logPath -Append
Check-Exit 'Audio tool compilation'
& $csc /nologo /optimize+ /target:exe /reference:System.Security.dll ('/out:' + (Join-Path $PSScriptRoot 'tools\key-protector.exe')) (Join-Path $PSScriptRoot 'tools\key-protector.cs') 2>&1 |
    Tee-Object -FilePath $logPath -Append
Check-Exit 'Key helper compilation'
'Build successful: application + 5 independent Java tools.' | Tee-Object -FilePath $logPath -Append
