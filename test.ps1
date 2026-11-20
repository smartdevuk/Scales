[CmdletBinding()]
param(
    [string]$AndroidSdk,
    [string]$Jdk
)

$ErrorActionPreference = 'Stop'

if (!$AndroidSdk) {
    $AndroidSdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk",
                    "$env:USERPROFILE\AppData\Local\Android\Sdk") |
        Where-Object { $_ -and (Test-Path -LiteralPath "$_\platforms\android-34\android.jar") } |
        Select-Object -First 1
}
if (!$Jdk) {
    $Jdk = @($env:JAVA_HOME, "$env:ProgramFiles\Android\Android Studio\jbr") |
        Where-Object { $_ -and (Test-Path -LiteralPath "$_\bin\javac.exe") } |
        Select-Object -First 1
}
if (!$AndroidSdk -or !$Jdk) {
    throw 'Supply -AndroidSdk (Android platform 34) and -Jdk (JDK 17 or newer).'
}

$platform = Join-Path $AndroidSdk 'platforms\android-34\android.jar'
$source = Join-Path $PSScriptRoot 'app\src\main\java'
# DataFixtures runs with its own SQLite/Android JSON dependencies in tests/run-data-tests.ps1.
$fixtures = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*Fixtures.java' -File |
              Where-Object { $_.Name -ne 'DataFixtures.java' } | Sort-Object Name)
if (!$fixtures.Count) { throw 'No JVM fixture suites were found.' }
$buildRoot = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot '.build')).FullName
$work = [IO.Directory]::CreateDirectory((Join-Path $buildRoot ('tests-' + [guid]::NewGuid()))).FullName

try {
    # Javac resolves production sources transitively. android.jar supplies type signatures only.
    $compileArgs = @('-encoding', 'UTF-8', '--release', '8', '-classpath', $platform,
                     '-sourcepath', $source, '-d', $work) + @($fixtures | ForEach-Object FullName)
    & "$Jdk\bin\javac.exe" @compileArgs
    if ($LASTEXITCODE -ne 0) { throw 'JVM fixture compilation failed.' }
    foreach ($suite in $fixtures) {
        & "$Jdk\bin\java.exe" '-ea' '-Dfile.encoding=UTF-8' '-classpath' "$work;$platform" "uk.co.violetscale.probe.$($suite.BaseName)"
        if ($LASTEXITCODE -ne 0) { throw "JVM fixture suite failed: $($suite.Name)" }
    }
    Write-Output "Passed all $($fixtures.Count) JVM fixture suites."
} finally {
    $resolved = (Resolve-Path -LiteralPath $work).ProviderPath
    if ([IO.Path]::GetDirectoryName($resolved) -eq $buildRoot) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
