#!/usr/bin/env bash
# Run from the directory containing both signed APKs on a disposable booted CI AVD.
set -Eeuo pipefail
export LC_ALL=C

die() { echo "Upgrade check: $*" >&2; exit 1; }
[[ "$(uname -s)" == Linux ]] || die 'A Linux x86 Android emulator runner is required.'
for tool in adb python3; do command -v "$tool" >/dev/null || die "Missing $tool."; done
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ -s Scales-1.2.apk && -s Scales-1.3.apk ]] || die 'Both signed versioned APKs must be present.'
pkg=uk.co.violetscale.probe
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    serial=$ANDROID_SERIAL
else
    mapfile -t devices < <(adb devices | awk '$2 == "device" { print $1 }')
    (( ${#devices[@]} == 1 )) || die 'Specify ANDROID_SERIAL for exactly one disposable emulator.'
    serial=${devices[0]}
fi
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || die 'Refusing a non-emulator ADB target.'
device() { adb -s "$serial" "$@"; }
prop() { device shell getprop "$1" | tr -d '\r'; }
[[ "$(prop ro.kernel.qemu)" == 1 || "$(prop ro.boot.qemu)" == 1 ]] || die 'QEMU properties are absent.'
[[ "$(prop ro.build.type)" == userdebug && "$(prop ro.debuggable)" == 1 ]] || die 'Use a userdebug system image (AOSP or Google APIs).'
[[ "$(prop ro.product.cpu.abi)" =~ ^x86(_64)?$ ]] || die 'Unexpected emulator CPU ABI.'
[[ "$(prop sys.boot_completed)" == 1 ]] || die 'Wait for the emulator to finish booting.'
[[ "$(device shell am get-current-user | tr -d '\r')" == 0 ]] || die 'Use the fresh primary emulator user.'
installed="$(device shell pm list packages -u "$pkg" | tr -d '\r')"
[[ "$installed" != *"package:$pkg"* ]] || die 'An existing Scales installation or retained package was found. Use a new AVD.'

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$sdk/build-tools" ]] || die 'Set ANDROID_HOME to the Android SDK.'
mapfile -t available < <(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V)
(( ${#available[@]} )) || die 'Android SDK build-tools are missing.'
aapt=${available[-1]}
signer="$(dirname "$aapt")/apksigner"
[[ -x "$signer" ]] || die 'apksigner is missing from build-tools.'
cert() {
    "$signer" verify "$1" >/dev/null || return 1
    # Both releases carry a v1 RSA signature in addition to v2/v3. Hash the DER
    # certificate directly to avoid parsing version-specific apksigner labels.
    unzip -p "$1" 'META-INF/*.RSA' | openssl pkcs7 -inform DER -print_certs | openssl x509 -outform DER | sha256sum | cut -d ' ' -f 1
}
base_cert="$(cert Scales-1.2.apk)"
target_cert="$(cert Scales-1.3.apk)"
[[ "$base_cert" == 0383b4120a42f88f2a5bcd9cfc110d37d50e4aa70862f09c3906ab1650e27adb && "$base_cert" == "$target_cert" ]] || die 'Failed to verify the expected signing identity for both APKs.'
for version in 1.2 1.3; do
    badging="$("$aapt" dump badging "Scales-$version.apk")"
    line="${badging%%$'\n'*}"
    [[ "$line" == "package: name='$pkg' "* && "$line" == *" versionName='$version'"* ]] || die "Wrong package or version in $version APK."
    code="$(sed -n "s/.* versionCode='\([0-9][0-9]*\)'.*/\1/p" <<<"$line")"
    [[ "$code" =~ ^[0-9]+$ ]] || die 'Could not verify the manifest version code.'
    if [[ "$version" == 1.2 ]]; then baseline_code=$code
    else (( code > baseline_code )) || die 'The upgrade must have a higher version code.'; fi
done

device root >/dev/null
timeout 45 adb -s "$serial" wait-for-device
[[ "$(device shell id -u | tr -d '\r')" == 0 ]] || die 'The emulator must allow adb root for read-only database snapshots.'
device shell wm dismiss-keyguard >/dev/null
device shell wm size 1080x2400 >/dev/null
device shell wm density 320 >/dev/null
device shell settings put secure show_ime_with_hard_keyboard 0
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

installed_version() {
    local version=$1
    local info
    info="$(device shell dumpsys package "$pkg" | tr -d '\r')"
    grep -Fqx "    versionName=$version" <<<"$info" || grep -Eq "^[[:space:]]*versionName=${version//./\\.}[[:space:]]*$" <<<"$info" || die "Failed to verify installed version $version."
}
launch() {
    local result
    result="$(device shell am start -W -n "$pkg/.HomeActivity" | tr -d '\r')"
    grep -Fxq 'Status: ok' <<<"$result" || die 'HomeActivity failed to start.'
}
check() { python3 "$script_dir/upgrade_check.py" "$serial" "$work" "$@"; }

device install Scales-1.2.apk >/dev/null
installed_version 1.2
launch
check seed
device shell am force-stop "$pkg"
check snapshot 1.2

device install -r Scales-1.3.apk >/dev/null
installed_version 1.3
launch
check history
device shell am force-stop "$pkg"
check snapshot 1.3
launch
check history
device shell am force-stop "$pkg"
check snapshot restart
check compare
echo 'PASS: Scales 1.2 to 1.3 in-place update preserved both measurements, every original field and saved settings through a second cold start.'
