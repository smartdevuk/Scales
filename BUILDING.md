# Building Scales 1.3

Scales is a native Android app for API 23 and newer. Version 1.3 uses version code 5 and application ID `uk.co.violetscale.probe`. The ID and original signing certificate preserve the Android update path from the diagnostic APK, Violet 1.0 and Scales 1.2.

## Install the published APK

Download `Scales-1.3.apk` to the phone, open it and accept Android’s install or update prompt. For USB installation with Android platform tools, run the following command from the directory containing the APK.

```sh
adb install -r Scales-1.3.apk
```

If Android reports a conflicting signature, keep the installed app and its history. Export a full backup from **Settings → Import, export and backup**, then obtain an update signed with the certificate that signed the installed version.

## Build on Windows with the installed Android SDK

Install JDK 17, Android SDK Platform 34 and Android SDK Build Tools 34.0.0. The PowerShell builder discovers Android Studio’s bundled JDK and the standard SDK location, or accepts `-Jdk` and `-AndroidSdk`. It uses the Android build tools and platform classes directly, so an installed toolchain can build without network access.

```powershell
.\test.ps1
.\tests\run-data-tests.ps1
.\build.ps1
```

The last command compiles Java, links resources, builds DEX, aligns and signs the package, verifies its certificate and checks its manifest. The signed output is `dist/Scales-1.3.apk`. The release manifest has debugging disabled.

## Build with Gradle

The wrapper pins Gradle 8.0 and its distribution checksum. The Android Gradle Plugin is pinned to 8.1.3. With JDK 17, Android SDK 34 and the signing configuration below, run `./gradlew :app:assembleRelease` on Linux/macOS or `.\gradlew.bat :app:assembleRelease` on Windows. Gradle downloads missing build dependencies on first use. The output is `app/build/outputs/apk/release/app-release.apk`. Use `:app:assembleDebug` for local development with an Android SDK development certificate.

## Sign updates

The existing sideload release uses its original Android development certificate for update continuity. Its public SHA-256 fingerprint is:

```text
0383b4120a42f88f2a5bcd9cfc110d37d50e4aa70862f09c3906ab1650e27adb
```

With no custom signing variables, both release builders require the retained keystore outside the checkout and check this fingerprint. They look in the Android SDK’s standard per-user development-keystore location. To use a securely retained copy elsewhere, pass `-CompatibilityKeystore` to PowerShell or `-PvioletKeystore=/absolute/path/to/keystore` to Gradle. Store an encrypted offline backup of the keystore, and keep recovery credentials separately. The published fingerprint lets anyone verify the signed file. It cannot sign an update.

For your own distribution, create a keystore outside the source checkout using Android Studio’s **Generate Signed Bundle or APK** wizard or an interactive JDK `keytool` session, and keep the same key for future releases. Supply all four environment variables to the build process.

| Environment variable | Meaning |
| --- | --- |
| `SCALES_KEYSTORE` | Absolute path to the private keystore |
| `SCALES_KEY_ALIAS` | Signing key alias |
| `SCALES_STORE_PASSWORD` | Keystore password from a secret store or masked prompt |
| `SCALES_KEY_PASSWORD` | Private key password from a secret store or masked prompt |
| `SCALES_EXPECTED_CERT_SHA256` | Optional public certificate fingerprint to pin for that distribution |

For CI, inject credentials as masked secrets for the signing job. A local PowerShell session can read them interactively and clear them after the build.

```powershell
$env:SCALES_KEYSTORE = (Resolve-Path -LiteralPath (Read-Host 'External keystore path')).Path
$env:SCALES_KEY_ALIAS = Read-Host 'Signing key alias'
$env:SCALES_STORE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Keystore password' -AsSecureString)).Password
$env:SCALES_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Key password' -AsSecureString)).Password
try {
    .\build.ps1
} finally {
    Remove-Item -LiteralPath Env:SCALES_STORE_PASSWORD,Env:SCALES_KEY_PASSWORD
    Remove-Item -LiteralPath Env:SCALES_KEYSTORE,Env:SCALES_KEY_ALIAS
}
```

A build using a different private key has a different Android signing identity. For a new independently distributed fork, choose an application ID you control and preserve the signing identity from its first install.

## Verify an APK

Run Android Build Tools’ `apksigner verify --verbose --print-certs Scales-1.3.apk` and compare the signer SHA-256 digest with the expected fingerprint. A file SHA-256 checksum identifies the exact APK bytes and changes when the build changes. A certificate SHA-256 fingerprint identifies the signing certificate across updates. [APK signature verification](https://developer.android.com/tools/apksigner) · [Android app signing](https://developer.android.com/studio/publish/app-signing)
