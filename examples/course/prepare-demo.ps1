param([string]$OutputDirectory = '')

$ErrorActionPreference = 'Stop'

# 模板只替换明确的路径占位符，不读取服务设置或密钥。
# Replace explicit path tokens only; never load service settings or API keys.
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 6)
    $OutputDirectory = Join-Path $PSScriptRoot "prepared\$stamp-$suffix"
}
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) {
    throw "准备目录已存在，请使用一个新目录：$destination"
}

$csv = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'data\expenses.csv')).ProviderPath
$reference = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'expected-report.txt')).ProviderPath
$tokens = [ordered]@{
    '@@CSV_SOURCE@@' = $csv
    '@@AI_OUTPUT@@' = (Join-Path $destination 'results\ai')
    '@@TXT_SOURCE@@' = $reference
    '@@PDF_OUTPUT@@' = (Join-Path $destination 'results\pdf')
}
$templates = [ordered]@{
    'csv-ai-report.flow.xml.template' = '01-csv-ai-report.flow.xml'
    'offline-report-pdf.flow.xml.template' = '02-offline-report-pdf.flow.xml'
}

# 先验证并生成全部内容，再创建输出目录；XML实体转义支持路径中的&等字符。
# Prepare all content first; XML escaping preserves special characters in local paths.
$prepared = [ordered]@{}
foreach ($template in $templates.Keys) {
    $source = Join-Path $PSScriptRoot (Join-Path 'templates' $template)
    $content = [IO.File]::ReadAllText($source, [Text.Encoding]::UTF8)
    foreach ($token in $tokens.Keys) {
        $content = $content.Replace($token, [Security.SecurityElement]::Escape($tokens[$token]))
    }
    if ($content -match '@@[A-Z_]+@@') { throw "模板存在未替换的占位符：$template" }
    $prepared[$templates[$template]] = $content
}
New-Item -ItemType Directory -Path $destination | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
foreach ($name in $prepared.Keys) {
    [IO.File]::WriteAllText((Join-Path $destination $name), $prepared[$name], $utf8)
}
Write-Host '已生成两个可导入流程：'
foreach ($name in $prepared.Keys) { Write-Host (Join-Path $destination $name) }
Write-Host 'AI流程：导入后选中AI元素，在侧边栏选择你自己的服务，再运行。'
Write-Host '离线流程：读取人工基准并生成PDF；不调用AI，也不执行CSV统计。'
Write-Host '两个流程均保留输入原件。结果写入准备目录下的results子目录。'
