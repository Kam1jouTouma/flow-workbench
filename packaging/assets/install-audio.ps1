$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression
$target = Join-Path $PSScriptRoot 'tools\ffmpeg'
New-Item -ItemType Directory -Path $target -Force | Out-Null
if ((Test-Path (Join-Path $target 'ffmpeg.exe')) -and (Test-Path (Join-Path $target 'ffprobe.exe'))) {
    Write-Output 'Audio components already installed.'
    exit 0
}
$zip = Join-Path ([IO.Path]::GetTempPath()) ('flow-audio-' + [guid]::NewGuid().ToString() + '.zip')
try {
    $uri = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
    $expected = [regex]::Match((Invoke-WebRequest ($uri + '.sha256') -UseBasicParsing).Content, '[a-fA-F0-9]{64}').Value
    if (-not $expected) {
        throw 'Missing download checksum.'
    }
    Invoke-WebRequest $uri -OutFile $zip -UseBasicParsing
    if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $expected) {
        throw 'Audio download checksum mismatch.'
    }
    $stream = [IO.File]::OpenRead($zip)
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        foreach ($name in @('ffmpeg.exe', 'ffprobe.exe', 'LICENSE', 'README.txt')) {
            $entry = $archive.Entries | Where-Object {
                $_.Name -eq $name
            } | Select-Object -First 1
            if (-not $entry) {
                throw ('Missing ' + $name + ' in downloaded archive.')
            }
            $path = Join-Path $target $name
            if (-not(Test-Path -LiteralPath $path)) {
                $input = $entry.Open()
                $output = [IO.File]::Create($path)
                try {
                    $input.CopyTo($output)
                }
                finally {
                    $input.Dispose()
                    $output.Dispose()
                }
            }
        }
    }
    finally {
        $archive.Dispose()
        $stream.Dispose()
    }
    Write-Output 'Audio components installed.'
}
finally {
    if (Test-Path -LiteralPath $zip) {
        Remove-Item -LiteralPath $zip -Force
    }
}
