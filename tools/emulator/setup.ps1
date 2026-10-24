# Installs the build toolchain and the emulators into user folders, without administrator rights.
# Safe to run again: each step is skipped when its result is already there.
# Usage: pwsh tools/emulator/setup.ps1 [-ToolsRoot <dir>] [-SdkRoot <dir>] [-AvdHome <dir>]

param(
    [string]$ToolsRoot = (Join-Path $env:LOCALAPPDATA 'Programs'),
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$AvdHome = $(if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $HOME '.android\avd' })
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$jdkVersion = 'jdk-21.0.12.1+1'
$gradleVersion = '9.8.2'
$commandLineTools = 'commandlinetools-win-16111833_latest.zip'
$packages = @(
    'platform-tools',
    'emulator',
    'platforms/android-37.0',
    'build-tools/37.0.0',
    'system-images/android-37.0/google_apis/x86_64',
    'system-images/android-29/google_apis/x86_64',
    'system-images/android-36/android-tv/x86_64'
)
$avds = @(
    @{ Name = 'ag37'; Image = 'system-images;android-37.0;google_apis;x86_64'; Device = 'pixel_8' },
    @{ Name = 'ag29'; Image = 'system-images;android-29;google_apis;x86_64'; Device = 'pixel_4' },
    @{ Name = 'agtv'; Image = 'system-images;android-36;android-tv;x86_64'; Device = 'tv_1080p' }
)

function Expand-Download([string]$url, [string]$destination) {
    $zip = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName() + '.zip')
    try {
        Invoke-WebRequest $url -OutFile $zip
        Expand-Archive $zip -DestinationPath $destination -Force
    } finally {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Force $ToolsRoot, $SdkRoot, $AvdHome | Out-Null

$jdk = Join-Path $ToolsRoot $jdkVersion
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host "Installing $jdkVersion"
    Expand-Download "https://api.adoptium.net/v3/binary/version/$([uri]::EscapeDataString($jdkVersion))/windows/x64/jdk/hotspot/normal/eclipse" $ToolsRoot
}

$gradle = Join-Path $ToolsRoot "gradle-$gradleVersion"
if (-not (Test-Path (Join-Path $gradle 'bin\gradle.bat'))) {
    Write-Host "Installing Gradle $gradleVersion"
    Expand-Download "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" $ToolsRoot
}

$latest = Join-Path $SdkRoot 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $latest 'bin\android.exe'))) {
    Write-Host 'Installing the Android command-line tools'
    $staging = Join-Path $SdkRoot 'cmdline-tools\staging'
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Download "https://dl.google.com/android/repository/$commandLineTools" $staging
    Remove-Item $latest -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item (Join-Path $staging 'cmdline-tools') $latest
    Remove-Item $staging -Recurse -Force
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = $AvdHome
$env:Path = "$jdk\bin;$latest\bin;$SdkRoot\platform-tools;$SdkRoot\emulator;$env:Path"

# sdkmanager splits package names on ';' when PowerShell runs it, so packages go through the android CLI.
$missing = $packages | Where-Object { -not (Test-Path (Join-Path $SdkRoot $_)) }
if ($missing) {
    Write-Host "Installing $($missing -join ', ')"
    & android sdk install "--sdk=$SdkRoot" @missing
    if ($LASTEXITCODE -ne 0) { throw 'android sdk install failed' }
}

$existing = & emulator -list-avds
foreach ($avd in $avds) {
    if ($existing -contains $avd.Name) { continue }
    Write-Host "Creating the $($avd.Name) emulator"
    # Through cmd, so the image's ';' separators reach avdmanager intact.
    cmd /c "echo no| avdmanager create avd -n $($avd.Name) -k `"$($avd.Image)`" -d $($avd.Device)"
    if ($LASTEXITCODE -ne 0) { throw "creating $($avd.Name) failed" }
}

& emulator -accel-check
