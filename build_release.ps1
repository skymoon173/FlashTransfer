$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$release = Join-Path $root "release"
$licenses = Join-Path $root "licenses"
$androidApk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
$windowsExe = Join-Path $root "dist\FlashTransfer.exe"

Set-Location $root

python build_exe.py
Push-Location (Join-Path $root "android")
try {
    gradle assembleDebug lintDebug
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force $release | Out-Null
Copy-Item -LiteralPath $windowsExe -Destination (Join-Path $release "FlashTransfer-Windows.exe") -Force
Copy-Item -LiteralPath $androidApk -Destination (Join-Path $release "FlashTransfer-Android-All-in-One.apk") -Force
Copy-Item -LiteralPath (Join-Path $root "LICENSE") -Destination $release -Force
Copy-Item -LiteralPath (Join-Path $root "THIRD_PARTY_NOTICES.md") -Destination $release -Force
Copy-Item -LiteralPath $licenses -Destination $release -Recurse -Force

$zip = Join-Path $root "FlashTransfer-release.zip"
Compress-Archive -Path (Join-Path $release "*") -DestinationPath $zip -Force
Write-Host "Release package created: $zip"
