param(
    [string]$SigningConfigPath = (Join-Path $env:USERPROFILE ".android\easypi-mobile-signing\signing.json"),
    [string]$ChameleonSourceRoot = "",
    [switch]$SkipNative
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$projectRoot = $PSScriptRoot

# A published APK must not reveal where or by whom it was built. Native
# libraries record local module directories, so refuse any entry that
# contains the project directory or the builder's user profile.
function Assert-NoLocalBuildPath([string]$Apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $markers = @($projectRoot, $env:USERPROFILE) | Where-Object { $_ } |
        ForEach-Object { $_.TrimEnd("\"); $_.TrimEnd("\").Replace("\", "/") } |
        Select-Object -Unique
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    $archive = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.Length -eq 0) { continue }
            $memory = [IO.MemoryStream]::new()
            $stream = $entry.Open()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            $text = $latin1.GetString($memory.ToArray())
            foreach ($marker in $markers) {
                if ($text.IndexOf($marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    throw "APK entry $($entry.FullName) contains a local build path. Rebuild the native library without -SkipNative."
                }
            }
        }
    } finally { $archive.Dispose() }
}

if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to the build JDK." }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path -LiteralPath $SigningConfigPath -PathType Leaf)) {
    throw "An external production signing configuration is required. Never store signing keys in this repository."
}
$configPath = (Resolve-Path -LiteralPath $SigningConfigPath).Path
$resolvedProject = (Resolve-Path -LiteralPath $projectRoot).Path
if ($configPath.StartsWith($resolvedProject + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Signing configuration must be outside the source tree."
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not (Test-Path -LiteralPath $config.keystore -PathType Leaf)) { throw "Production keystore is missing." }
$keyPath = (Resolve-Path -LiteralPath $config.keystore).Path
if ($keyPath.StartsWith($resolvedProject + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Production keystore must be outside the source tree."
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME "build-tools") -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat") } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $buildTools) { throw "Android build tools are missing." }
$apksigner = Join-Path $buildTools "apksigner.bat"
$securePassword = ConvertTo-SecureString $config.passwordDpapi
$previousPassword = $env:EASYPI_RELEASE_STORE_PASSWORD
try {
    $env:EASYPI_RELEASE_STORE_PASSWORD = [System.Net.NetworkCredential]::new("", $securePassword).Password
    Push-Location $projectRoot
    try {
        if (-not $SkipNative) {
            & (Join-Path $projectRoot "Build-Libtailscale.ps1") -ChameleonSourceRoot $ChameleonSourceRoot
            if ($LASTEXITCODE -ne 0) { throw "Native build failed." }
        }
        & (Join-Path $projectRoot "gradlew.bat") --offline --no-daemon --console=plain testDebugUnitTest lintRelease assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "Android verification/build failed." }
        $releaseDirectory = Join-Path $projectRoot "app\build\outputs\apk\release"
        $unsigned = Join-Path $releaseDirectory "app-release-unsigned.apk"
        $signed = Join-Path $releaseDirectory "EasyPi-Mobile.apk"
        $legacySigned = Join-Path $releaseDirectory "app-release.apk"
        Assert-NoLocalBuildPath $unsigned
        $arguments = @("sign", "--ks", $config.keystore, "--ks-key-alias", $config.alias,
            "--ks-pass", "env:EASYPI_RELEASE_STORE_PASSWORD", "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            # Include a v2 block for older vendor security scanners while v3
            # remains the platform-preferred signature on Android 9 and newer.
            "--min-sdk-version", "24",
            "--debuggable-apk-permitted", "false", "--out", $signed)
        & $apksigner @arguments $unsigned
        if ($LASTEXITCODE -ne 0) { throw "Production signing failed." }
        & $apksigner verify --verbose --print-certs $signed
        if ($LASTEXITCODE -ne 0) { throw "Production signature verification failed." }
        & $apksigner verify --min-sdk-version 24 --max-sdk-version 27 $signed
        if ($LASTEXITCODE -ne 0) { throw "APK Signature Scheme v2 verification failed." }
        & $apksigner verify --min-sdk-version 28 --max-sdk-version 36 $signed
        if ($LASTEXITCODE -ne 0) { throw "APK Signature Scheme v3 verification failed." }
        if (Test-Path -LiteralPath $legacySigned -PathType Leaf) {
            Remove-Item -LiteralPath $legacySigned -Force
        }
        $digest = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
        # LF only: GNU sha256sum 8.x rejects "EasyPi-Mobile.apk`r" in --check.
        [IO.File]::WriteAllText("$signed.sha256", "$digest  EasyPi-Mobile.apk`n", [Text.Encoding]::ASCII)
        Write-Output "Verified production APK: $signed"
    } finally { Pop-Location }
} finally {
    $env:EASYPI_RELEASE_STORE_PASSWORD = $previousPassword
    $securePassword.Dispose()
}
