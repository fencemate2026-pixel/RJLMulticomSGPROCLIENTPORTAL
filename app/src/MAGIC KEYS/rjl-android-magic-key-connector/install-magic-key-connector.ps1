$ErrorActionPreference = "Stop"

$projectRoot = "C:\Users\User\AndroidStudioProjects\RJLMulticomSGPROCLIENTPORTAL"
$sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$apiTarget = Join-Path $projectRoot "app\src\main\java\com\example\rjlmulticomsg_proclientportal\data\remote\MagicKeyApi.kt"
$vmTarget = Join-Path $projectRoot "app\src\main\java\com\example\rjlmulticomsg_proclientportal\ui\magickey\MagicKeyViewModel.kt"
$screenTarget = Join-Path $projectRoot "app\src\main\java\com\example\rjlmulticomsg_proclientportal\ui\magickey\MagicKeyScreen.kt"
$gradleTarget = Join-Path $projectRoot "app\build.gradle.kts"

$required = @($gradleTarget)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required project file not found: $path"
    }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
foreach ($path in @($apiTarget, $vmTarget, $screenTarget, $gradleTarget)) {
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Copy-Item -LiteralPath $path -Destination "$path.$stamp.bak" -Force
    }
}

New-Item -ItemType Directory -Path (Split-Path $apiTarget) -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $vmTarget) -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $sourceRoot "MagicKeyApi.kt") -Destination $apiTarget -Force
Copy-Item -LiteralPath (Join-Path $sourceRoot "MagicKeyViewModel.kt") -Destination $vmTarget -Force
Copy-Item -LiteralPath (Join-Path $sourceRoot "MagicKeyScreen.kt") -Destination $screenTarget -Force

$gradle = [IO.File]::ReadAllText($gradleTarget)
$liveUrl = 'https://ifesjmdhlyurswgajslm.supabase.co/functions/v1/'

$pattern = 'buildConfigField\("String",\s*"MAGIC_KEY_BASE_URL",\s*"\\"[^"]*\\""\)'
$replacement = 'buildConfigField("String", "MAGIC_KEY_BASE_URL", "\"' + $liveUrl + '\"")'

if ($gradle -match $pattern) {
    $gradle = [regex]::Replace($gradle, $pattern, $replacement)
} else {
    $defaultConfigPattern = '(defaultConfig\s*\{)'
    if ($gradle -notmatch $defaultConfigPattern) {
        throw "Could not find defaultConfig in app\build.gradle.kts"
    }
    $gradle = [regex]::Replace(
        $gradle,
        $defaultConfigPattern,
        '$1' + [Environment]::NewLine + '        ' + $replacement,
        1
    )
}

[IO.File]::WriteAllText($gradleTarget, $gradle)

Write-Host ""
Write-Host "=== MAGIC KEY CONNECTED ===" -ForegroundColor Green
Write-Host "Endpoint: $liveUrl" -ForegroundColor Cyan
Write-Host "Backups: *.$stamp.bak"
Write-Host ""

$javaHome = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe")) {
    $env:JAVA_HOME = $javaHome
}

Push-Location $projectRoot
try {
    & ".\gradlew.bat" testDebugUnitTest assembleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "BUILD PASSED" -ForegroundColor Green
Write-Host "APK: $projectRoot\app\build\outputs\apk\debug\app-debug.apk"
