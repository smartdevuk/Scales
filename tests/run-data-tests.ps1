[CmdletBinding()]
param(
    [string]$Studio = "$env:ProgramFiles\Android\Android Studio",
    [string]$AndroidSdk = "$env:USERPROFILE\AppData\Local\Android\Sdk",
    [string]$Jdk
)
$ErrorActionPreference = 'Stop'
if (!$Jdk) { $Jdk = Join-Path $Studio 'jbr' }
$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root 'app\src\main\java\uk\co\violetscale\probe'
$work = Join-Path $root ('.build\data-test-' + [Guid]::NewGuid().ToString('N'))
$classes = (New-Item -ItemType Directory -Path "$work\classes" -Force).FullName
$normal = (New-Item -ItemType Directory -Path "$work\normal").FullName
$old = (New-Item -ItemType Directory -Path "$work\migration").FullName
$previous = (New-Item -ItemType Directory -Path "$work\migration2").FullName
$framework = Join-Path $AndroidSdk 'platforms\android-34\android.jar'
$layoutLib = Join-Path $Studio 'plugins\design-tools\lib\layoutlib.jar'
$sqlite = Get-ChildItem -Path "$Studio\plugins\android\lib" -Filter 'sqlite-jdbc-*.jar' | Select-Object -First 1
if (!$sqlite -or !(Test-Path $framework) -or !(Test-Path $layoutLib)) {
    throw 'Install Android SDK 34 and Android Studio with its design tools and sqlite-jdbc, or pass the SDK and Studio paths.'
}
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
function Invoke-Checked($exe, $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Test command failed ($LASTEXITCODE)." }
}
try {
    # Compiles production habit validation and its Android references. No resources or reminders run in these tests.
    Set-Content -Encoding Ascii -Path "$work\R.java" -Value 'package uk.co.violetscale.probe; final class R { static final class drawable { static final int ic_reminder=1; } }'
    $flags = @('-encoding','UTF-8','-d',$classes)
    $prod = @('Store','Habits','HabitCalendar','ReminderReceiver','TransferCodec','DataCsv','Transfer') |
        ForEach-Object { "$src\$_.java" }
    Invoke-Checked $javac ($flags + @('-cp',$framework,"$work\R.java") + $prod)
    # Recompile the database boundary with this JVM's JDBC adapters and the Android implementation of org.json.
    $hostSources = @(Get-ChildItem -Recurse -Filter '*.java' -File "$PSScriptRoot\data-host" | ForEach-Object FullName)
    $cp = "$classes;$layoutLib;$framework;$($sqlite.FullName)"
    Invoke-Checked $javac ($flags + @('-cp',$cp,"$src\Store.java","$PSScriptRoot\DataFixtures.java") + $hostSources)
    foreach ($scenario in @('normal','restart','migration','migration2')) {
        $database = if ($scenario -eq 'migration') { $old } elseif ($scenario -eq 'migration2') { $previous } else { $normal }
        Invoke-Checked $java @("-Dorg.sqlite.tmpdir=$work","-Dviolet.testDB=$database",'-cp',$cp,'uk.co.violetscale.probe.DataTestHost',$scenario)
    }
} finally {
    $actual = (Resolve-Path -LiteralPath $work).ProviderPath
    $parent = (Resolve-Path -LiteralPath (Join-Path $root '.build')).ProviderPath
    if ([IO.Path]::GetDirectoryName($actual) -eq $parent -and [IO.Path]::GetFileName($actual) -like 'data-test-*') {
        Remove-Item -LiteralPath $actual -Recurse -Force
    }
}
