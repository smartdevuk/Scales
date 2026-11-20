[CmdletBinding()]
param(
    [string]$AndroidSdk,
    [string]$Jdk,
    [Alias('DebugKeystore')]
    [string]$CompatibilityKeystore = "$env:USERPROFILE\.android\debug.keystore"
)

$ErrorActionPreference = 'Stop'

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Build tool failed ($LASTEXITCODE): $Executable"
    }
}

if (!$AndroidSdk) {
    $AndroidSdk = @(
        $env:ANDROID_HOME
        $env:ANDROID_SDK_ROOT
        "$env:LOCALAPPDATA\Android\Sdk"
        "$env:USERPROFILE\AppData\Local\Android\Sdk"
    ) | Where-Object { $_ -and (Test-Path -LiteralPath "$_\platforms\android-34\android.jar") } |
        Select-Object -First 1
}
if (!$Jdk) {
    $Jdk = @($env:JAVA_HOME, "$env:ProgramFiles\Android\Android Studio\jbr") |
        Where-Object { $_ -and (Test-Path -LiteralPath "$_\bin\javac.exe") } |
        Select-Object -First 1
}
if (!$AndroidSdk -or !$Jdk) {
    throw 'Supply -AndroidSdk (with platform 34 and build-tools 34.0.0) and -Jdk (JDK 17).'
}

$tools = Join-Path $AndroidSdk 'build-tools\34.0.0'
$platform = Join-Path $AndroidSdk 'platforms\android-34\android.jar'
$java = Join-Path $Jdk 'bin\java.exe'
$main = Join-Path $PSScriptRoot 'app\src\main'
$buildRoot = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot '.build')).FullName
$work = [IO.Directory]::CreateDirectory((Join-Path $buildRoot ([Guid]::NewGuid().ToString('N')))).FullName
$out = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot 'dist')).FullName
$manifest = [xml](Get-Content -LiteralPath "$main\AndroidManifest.xml" -Raw)
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$versionCode = $manifest.DocumentElement.GetAttribute('versionCode', $androidNamespace)
$versionName = $manifest.DocumentElement.GetAttribute('versionName', $androidNamespace)
$outputApk = Join-Path $out "Scales-$versionName.apk"
$legacyCert = '0383b4120a42f88f2a5bcd9cfc110d37d50e4aa70862f09c3906ab1650e27adb'
try {
    $customKeys = @('SCALES_KEYSTORE','SCALES_KEY_ALIAS','SCALES_STORE_PASSWORD','SCALES_KEY_PASSWORD')
    $configured = @($customKeys | Where-Object { [Environment]::GetEnvironmentVariable($_) })
    if ($configured.Count -ne 0 -and $configured.Count -ne $customKeys.Count) {
        throw 'Set every SCALES signing variable or clear all four to use the existing compatibility signer. See BUILDING.md.'
    }
    if ($configured.Count -eq $customKeys.Count) {
        $keyFile = $env:SCALES_KEYSTORE
        $keyAlias = $env:SCALES_KEY_ALIAS
        $storePass = 'env:SCALES_STORE_PASSWORD'
        $keyPass = 'env:SCALES_KEY_PASSWORD'
        $expectedDigest = $env:SCALES_EXPECTED_CERT_SHA256
    } else {
        $keyFile = $CompatibilityKeystore
        $keyAlias = 'androiddebugkey'
        # These are the Android development keystore defaults used by the original release.
        $storePass = 'pass:android'
        $keyPass = 'pass:android'
        $expectedDigest = $legacyCert
    }
    if (!(Test-Path -LiteralPath $keyFile -PathType Leaf)) {
        throw 'Signing keystore missing. Provide the retained external keystore or your own release credentials. See BUILDING.md.'
    }
    $keyFile = (Resolve-Path -LiteralPath $keyFile).ProviderPath
    $sourcePrefix = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
    if ($keyFile.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Keep the private signing keystore outside the source checkout.'
    }
    if ($expectedDigest) {
        $expectedDigest = ($expectedDigest -replace '[:\s]', '').ToLowerInvariant()
        if ($expectedDigest -notmatch '^[0-9a-f]{64}$') { throw 'Expected signer digest must be 64 hex digits.' }
    }
    $generated = [IO.Directory]::CreateDirectory((Join-Path $work 'generated')).FullName
    $classes = [IO.Directory]::CreateDirectory((Join-Path $work 'classes')).FullName
    $dex = [IO.Directory]::CreateDirectory((Join-Path $work 'dex')).FullName
    $unsigned = Join-Path $work 'unsigned.apk'
    $aligned = Join-Path $work 'aligned.apk'
    $signed = Join-Path $work 'signed.apk'
    $aapt2 = Join-Path $tools 'aapt2.exe'
    $link = @('link', '-o', $unsigned, '--manifest', "$main\AndroidManifest.xml",
              '-I', $platform, '--java', $generated, '--min-sdk-version', '23',
              '--target-sdk-version', '34', '--version-code', $versionCode, '--version-name', $versionName)
    if (Test-Path -LiteralPath "$main\res") {
        $resources = Join-Path $work 'resources.zip'
        Invoke-BuildTool $aapt2 @('compile', '--dir', "$main\res", '-o', $resources)
        $link += $resources
    }
    Invoke-BuildTool $aapt2 $link
    $sources = @(Get-ChildItem -LiteralPath "$main\java", $generated -Filter '*.java' -File -Recurse |
        ForEach-Object FullName)
    $bootClasspath = "$platform;$tools\core-lambda-stubs.jar"
    $javac = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', $bootClasspath,
               '-d', $classes) + $sources
    Invoke-BuildTool "$Jdk\bin\javac.exe" $javac
    $classJar = Join-Path $work 'classes.jar'
    Invoke-BuildTool "$Jdk\bin\jar.exe" @("-J-Djava.io.tmpdir=$work", 'cf', $classJar, '-C', $classes, '.')
    Invoke-BuildTool $java @('-cp', "$tools\lib\d8.jar", 'com.android.tools.r8.D8',
        '--lib', $platform, '--min-api', '23', '--output', $dex, $classJar)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $dex -Filter '*.dex' -File)) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $file.Name,
                [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }
    Invoke-BuildTool "$tools\zipalign.exe" @('4', $unsigned, $aligned)

    $signer = "$tools\lib\apksigner.jar"
    Invoke-BuildTool $java @('-jar', $signer, 'sign', '--ks', $keyFile,
        '--ks-key-alias', $keyAlias, '--ks-pass', $storePass, '--key-pass', $keyPass,
        '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
    $verified = @(Invoke-BuildTool $java @('-jar', $signer, 'verify', '--verbose', '--print-certs', $signed))
    $digest = @($verified | Select-String '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$')
    if ($digest.Count -ne 1) { throw 'Could not verify the signing certificate.' }
    $actualDigest = $digest[0].Matches[0].Groups[1].Value.ToLowerInvariant()
    if ($expectedDigest -and $expectedDigest -ne $actualDigest) {
        throw 'Signing certificate does not match the expected SHA-256 digest. Existing output was preserved.'
    }
    Invoke-BuildTool "$tools\zipalign.exe" @('-c', '4', $signed)
    $badging = @(Invoke-BuildTool "$tools\aapt.exe" @('dump','badging',$signed))
    if ($badging -match '^application-debuggable' -or !($badging -match "^application-label:'Scales'$") -or
        !($badging -match "^package: name='uk\.co\.violetscale\.probe' versionCode='$versionCode' versionName='$([Regex]::Escape($versionName))'")) {
        throw 'The release APK failed its manifest identity or non-debuggable check.'
    }
    Copy-Item -LiteralPath $signed -Destination $outputApk -Force
    Write-Output "Signer certificate SHA-256: $actualDigest"
    Write-Output "Built $outputApk"
} finally {
    $resolvedWork = (Resolve-Path -LiteralPath $work).ProviderPath
    if ([IO.Path]::GetDirectoryName($resolvedWork) -eq $buildRoot) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
