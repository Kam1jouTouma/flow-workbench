$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
function Write-JsonValue($value, $path) {
    $json = ConvertTo-Json -InputObject $value -Depth 30 -Compress
    if ($path) {
        [IO.File]::WriteAllText($path, $json, [Text.UTF8Encoding]::new($false))
    }
    else {
        [Console]::WriteLine($json)
    }
}
function File-Hash($path) {
    $digest = [Security.Cryptography.SHA256]::Create()
    $nativePath = if ($path.Length -ge 248 -and $path -notlike '\\?\*') {
        '\\?\' + $path
    }
    else {
        $path
    }
    $stream = [IO.File]::OpenRead($nativePath)
    try {
        return [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '').ToLower()
    }
    finally {
        $stream.Dispose()
        $digest.Dispose()
    }
}
function Description-Port($name, $type, $count) {
    return @{
        name        =$name
        label       =$name
        types       =@($type)
        cardinality =$count
        required    =$true
    }
}
function Capability-Port($name, $type, $count, $formats) {
    return @{
        name        =$name
        dataTypes   =@($type)
        cardinality =$count
        formats     =@($formats)
        validation  ='IDENTIFY'
    }
}
$description = @{
    protocolVersion =1
    id              ='build.java_jar'
    name            ='Java编译打包'
    version         ='1.0.0'
    description     ='Compile UTF-8 Java source files with javac, then package project.jar with jar. No Maven/Gradle or external dependency resolution. mainClass may be empty for a library JAR.'
    inputs          =@((Description-Port 'data' 'FILE' 'COLLECTION'), (Description-Port 'source' 'JSON' 'SINGLE'))
    outputs         =@((Description-Port 'data' 'FILE' 'COLLECTION'), (Description-Port 'source' 'JSON' 'SINGLE'))
    parameters      =@(@{name = 'mainClass'; label = '主类'; type = 'string'; required = $false; default = 'Main' }, @{name = 'targetRelease'; label = '目标Java版本'; type = 'string'; required = $false; default = '17' }, @{name = 'jdkPath'; label = 'JDK目录'; type = 'string'; required = $false; default = '' })
}
$capability = @{
    capabilityVersion =1
    toolId            ='build.java_jar'
    toolVersion       ='1.0.0'
    implementation    ='AVAILABLE'
    operation         ='CONVERT'
    inputs            =@((Capability-Port 'data' 'FILE' 'COLLECTION' @('java')), (Capability-Port 'source' 'JSON' 'SINGLE' @('json')))
    outputs           =@((Capability-Port 'data' 'FILE' 'COLLECTION' @('jar')), (Capability-Port 'source' 'JSON' 'SINGLE' @('json')))
    knownExtensions   =@('java')
}
try {
    $toolArguments = @($args)
    if ($toolArguments.Count -eq 1) {
        switch ($toolArguments[0]) {
            '--help' {
                [Console]::WriteLine("Java build BAT tool`n--describe | --capabilities | --version | --input JSON --config JSON --output empty-directory`n" + $description.description)
                exit 0
            }
            '--describe' {
                Write-JsonValue $description
                exit 0
            }
            '--capabilities' {
                Write-JsonValue $capability
                exit 0
            }
            '--version' {
                [Console]::WriteLine('1.0.0')
                exit 0
            }
        }
    }
    if ($toolArguments.Count -ne 6) {
        throw 'Expected input/config/output exactly once'
    }
    $options = @{
    }
    for ($index = 0; $index -lt 6; $index += 2) {
        $key = $toolArguments[$index]
        if ($key -notin @('--input', '--config', '--output') -or $options.ContainsKey($key)) {
            throw 'Unknown or duplicate option'
        }
        $options[$key] = $toolArguments[$index + 1]
    }
    foreach ($path in $options.Values) {
        if (-not [IO.Path]::IsPathRooted($path)) {
            throw 'Paths must be absolute'
        }
    }
    $outputDir = $options['--output']
    if (-not (Test-Path -LiteralPath $outputDir -PathType Container) -or @(Get-ChildItem -LiteralPath $outputDir -Force).Count -ne 0) {
        throw 'Output directory must exist and be empty'
    }
    $request = [IO.File]::ReadAllText($options['--input']) | ConvertFrom-Json
    $config = [IO.File]::ReadAllText($options['--config']) | ConvertFrom-Json
    if ($request.protocolVersion -ne 1 -or $config.protocolVersion -ne 1) {
        throw 'Protocol version unsupported'
    }
    if ($request.inputs.data.type -ne 'FILE' -or $request.inputs.source.type -ne 'JSON') {
        throw 'Expected data and source ports'
    }
    $items = @($request.inputs.data.items)
    $manifests = @($request.inputs.source.items)
    if ($items.Count -lt 1 -or $items.Count -gt 1000 -or $manifests.Count -ne 1) {
        throw 'Invalid file count (1..1000)'
    }
    $sources = @(([IO.File]::ReadAllText($manifests[0].path) | ConvertFrom-Json).files)
    if ($sources.Count -ne $items.Count) {
        throw 'Source count mismatch'
    }
    foreach ($property in $config.parameters.PSObject.Properties.Name) {
        if ($property -notin @('mainClass', 'targetRelease', 'jdkPath')) {
            throw 'Unknown build parameter'
        }
    }
    $mainClass = if ($null -ne $config.parameters.mainClass) {
        [string]$config.parameters.mainClass
    }
    else {
        'Main'
    }
    if ($mainClass -ne '' -and $mainClass -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$') {
        throw 'Invalid Java main class'
    }
    $release = if ($config.parameters.targetRelease) {
        [string]$config.parameters.targetRelease
    }
    else {
        '17'
    }
    if ($release -notin @('17', '21', '25')) {
        throw 'Target release must be 17, 21 or 25'
    }
    $jdk = [string]$config.parameters.jdkPath
    if (-not $jdk) {
        $jdk = $env:JAVA_HOME
    }
    if (-not $jdk) {
        . (Join-Path (Split-Path -Parent $PSScriptRoot) 'java-runtime.ps1')
        $jdk = Split-Path -Parent (Split-Path -Parent (Find-ProjectJava -RequireCompiler))
    }
    $compiler = Join-Path $jdk 'bin\javac.exe'
    $jar = Join-Path $jdk 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $compiler) -or -not (Test-Path -LiteralPath $jar)) {
        throw 'JDK compiler or jar tool missing'
    }
    $argumentFile = Join-Path $outputDir 'sources.args'
    $lines = @()
    $originals = @()
    for ($index = 0; $index -lt $items.Count; $index++) {
        $path = [string]$items[$index].path
        if (-not [IO.Path]::IsPathRooted($path) -or [IO.Path]::GetExtension($path).ToLower() -ne '.java') {
            throw 'Only absolute .java files are accepted'
        }
        if ((File-Hash $path) -ne $sources[$index].sha256) {
            throw 'Input snapshot hash mismatch'
        }
        $lines += '"' + $path.Replace('\', '/') + '"'
        if ($sources[$index].originals) {
            $originals += @($sources[$index].originals)
        }
        else {
            $originals += @{
                path   =$sources[$index].originalPath
                sha256 =$sources[$index].sha256
            }
        }
    }
    [IO.File]::WriteAllLines($argumentFile, [string[]]$lines, [Text.UTF8Encoding]::new($false))
    $classes = Join-Path $outputDir 'classes'
    $payload = Join-Path $outputDir 'payloads'
    New-Item -ItemType Directory -Path $classes, $payload -Force | Out-Null
    $resultJar = Join-Path $payload 'project.jar'
    $env:FLOW_JAVAC = $compiler
    $env:FLOW_JAR = $jar
    $env:FLOW_BUILD_RELEASE = $release
    $env:FLOW_BUILD_ARGFILE = $argumentFile
    $env:FLOW_BUILD_CLASSES = $classes
    $env:FLOW_BUILD_OUTPUT = $resultJar
    $env:FLOW_BUILD_MAINCLASS = $mainClass
    $env:FLOW_BUILD_TMP = $outputDir
    $ErrorActionPreference = 'Continue'
    $messages = & (Join-Path $PSScriptRoot 'java-build-worker.bat') 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    [IO.File]::WriteAllText((Join-Path $outputDir 'build.log'), ($messages | Out-String), [Text.UTF8Encoding]::new($false))
    if ($code -ne 0) {
        throw ('Java compile/package failed: ' + ($messages | Out-String))
    }
    $list = @(& $jar ("-J-Djava.io.tmpdir=" + $outputDir) --list --file $resultJar)
    if (-not ($list | Where-Object { $_ -like '*.class' })) {
        throw 'No compiled class in JAR'
    }
    if ($mainClass -and ($list -notcontains ($mainClass.Replace('.', '/') + '.class'))) {
        throw 'Configured main class not found in JAR'
    }
    $first = @{
    }
    foreach ($property in $sources[0].PSObject.Properties) {
        $first[$property.Name] = $property.Value
    }
    $first.relativePath = 'project.jar'
    $first.sha256 = File-Hash $resultJar
    $first.originals = @($originals)
    Write-JsonValue @{
        files=@($first)
    } (Join-Path $outputDir 'source.json')
    Write-JsonValue @{
        warnings=@()
    } (Join-Path $outputDir 'warnings.json')
    Write-JsonValue @{
        protocolVersion =1
        runId           =$request.runId
        nodeId          =$request.nodeId
        outputs         =@{
            data   =@{
                type  ='FILE'
                items =@(@{path = 'payloads/project.jar'; mediaType = 'application/java-archive' })
            }
            source =@{
                type  ='JSON'
                items =@(@{path = 'source.json'; mediaType = 'application/json' })
            }
        }
    } (Join-Path $outputDir 'result.json')
    [Console]::WriteLine('Completed: build.java_jar')
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 4
}
