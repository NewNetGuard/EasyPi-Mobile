param(
    [string]$ChameleonSourceRoot = "",
    [string]$Target = "android"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildParent = Join-Path $projectRoot ".build"
$sourceRoot = Join-Path $buildParent "easypi-native"
$mobileVersion = "v0.0.0-20261011034528-197770444867"
$nativeSources = Join-Path $projectRoot "third_party\easypi-native"
$chameleonSource = Join-Path (Split-Path $projectRoot -Parent) "EasyPi\chameleon"
if ($ChameleonSourceRoot) { $chameleonSource = (Resolve-Path -LiteralPath $ChameleonSourceRoot).Path }
$output = Join-Path $projectRoot "app\libs\easypinative.aar"

if (-not $env:JAVA_HOME) { throw "JAVA_HOME must point to a compatible JDK." }
$androidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path -LiteralPath $androidHome -PathType Container)) { throw "Android SDK was not found." }
$ndkRoot = Get-ChildItem -LiteralPath (Join-Path $androidHome "ndk") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $ndkRoot) { throw "Android NDK was not found." }
if (-not (Test-Path -LiteralPath (Join-Path $nativeSources "chameleon_tun.go") -PathType Leaf)) {
    throw "EasyPi native binding sources are incomplete."
}
if (-not (Test-Path -LiteralPath (Join-Path $chameleonSource "go.mod") -PathType Leaf)) {
    throw "EasyPi Chameleon source was not found next to EasyPi-Mobile."
}

New-Item -ItemType Directory -Force -Path $buildParent, (Split-Path $output) | Out-Null
if (Test-Path -LiteralPath $sourceRoot) {
    $resolvedBuild = (Resolve-Path -LiteralPath $buildParent).Path
    $resolvedNative = (Resolve-Path -LiteralPath $sourceRoot).Path
    if (-not $resolvedNative.StartsWith($resolvedBuild + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a native build directory outside EasyPi Mobile's .build directory."
    }
    Remove-Item -LiteralPath $resolvedNative -Recurse -Force
}

$bindingRoot = Join-Path $sourceRoot "easypinative"
$embeddedChameleon = Join-Path $sourceRoot "third_party\chameleon"
New-Item -ItemType Directory -Force -Path $bindingRoot, $embeddedChameleon | Out-Null
Get-ChildItem -LiteralPath $nativeSources -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $bindingRoot -Force
}
Get-ChildItem -LiteralPath $chameleonSource -Force |
    Where-Object { $_.Name -notin @(".build", "bin") } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $embeddedChameleon -Recurse -Force }

$goBin = Join-Path $sourceRoot "bin"
New-Item -ItemType Directory -Force -Path $goBin | Out-Null
$env:GOBIN = $goBin
$env:ANDROID_HOME = $androidHome
$env:ANDROID_NDK_HOME = $ndkRoot
$env:GOTOOLCHAIN = "go1.27.4"
$env:GOFLAGS = "-trimpath"
$env:PATH = "$env:JAVA_HOME\bin;$goBin;$env:PATH"

go install "golang.org/x/mobile/cmd/gomobile@$mobileVersion"
if ($LASTEXITCODE -ne 0) { throw "Could not install pinned gomobile." }
go install "golang.org/x/mobile/cmd/gobind@$mobileVersion"
if ($LASTEXITCODE -ne 0) { throw "Could not install pinned gobind." }

# gomobile records the absolute directories of local modules in the library.
# Build through a temporary drive mapping so the APK carries only a neutral
# path such as "R:\easypi-native" instead of the builder's user profile.
$drive = @("R", "S", "T", "U", "V", "W", "X", "Y", "Z") |
    Where-Object { -not (Test-Path -LiteralPath ($_ + ":\")) } |
    Select-Object -First 1
if (-not $drive) { throw "No free drive letter is available for the neutral native build root." }
& subst.exe ($drive + ":") $buildParent
if ($LASTEXITCODE -ne 0) { throw "Could not map the neutral native build drive." }
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
try {
    $neutralTemp = $drive + ":\tmp"
    New-Item -ItemType Directory -Force -Path $neutralTemp | Out-Null
    $env:TEMP = $neutralTemp
    $env:TMP = $neutralTemp
    Push-Location ($drive + ":\easypi-native")
    try {
        go mod init easypi-native
        go mod edit "-require=chameleon@v0.0.0"
        go mod edit "-replace=chameleon=./third_party/chameleon"
        go get -tool "golang.org/x/mobile/cmd/gobind@$mobileVersion"
        if ($LASTEXITCODE -ne 0) { throw "Could not register the pinned gomobile binding tool." }
        go mod tidy
        if ($LASTEXITCODE -ne 0) { throw "Could not resolve native dependencies." }
        go test .\easypinative
        if ($LASTEXITCODE -ne 0) { throw "EasyPi native binding tests failed." }
        gomobile bind -target $Target -androidapi 30 -ldflags "-s -w" -o $output .\easypinative
        if ($LASTEXITCODE -ne 0) { throw "EasyPi native Android build failed." }
    } finally {
        Pop-Location
    }
} finally {
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    & subst.exe ($drive + ":") /D | Out-Null
}
$generatedSources = Join-Path (Split-Path $output) "easypinative-sources.jar"
if (Test-Path -LiteralPath $generatedSources -PathType Leaf) {
    Remove-Item -LiteralPath $generatedSources -Force
}

Write-Host "Built EasyPi PRISM + Chameleon native binding: $output"
