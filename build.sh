#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}
platform=$(find "$sdk/platforms" -mindepth 2 -maxdepth 2 -name android.jar | sort -V | tail -n 1)
build_tools=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
if [[ -z "$platform" || -z "$build_tools" ]]; then
    echo "Install an Android SDK platform and build tools, then set ANDROID_HOME." >&2
    exit 1
fi

mkdir -p "$root/build"
work=$(mktemp -d "$root/build/work.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/classes" "$work/generated" "$work/dex"

"$build_tools/aapt2" compile --dir "$root/src/main/res" -o "$work/resources.zip"
"$build_tools/aapt2" link -I "$platform" --manifest "$root/src/main/AndroidManifest.xml" \
    --min-sdk-version 29 --target-sdk-version 36 --version-code 1 --version-name 1.0 \
    --java "$work/generated" -o "$work/unsigned.apk" "$work/resources.zip"

mapfile -d '' sources < <(find "$root/src/main/java" "$work/generated" -name '*.java' -print0)
javac --release 11 -classpath "$platform" -d "$work/classes" "${sources[@]}"
jar --create --file "$work/classes.jar" -C "$work/classes" .
"$build_tools/d8" --min-api 29 --lib "$platform" --output "$work/dex" "$work/classes.jar"
zip -q -j "$work/unsigned.apk" "$work/dex/classes.dex"
"$build_tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

keystore=${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore
if [[ ! -r "$keystore" ]]; then
    keystore="$root/build/debug.keystore"
    if [[ ! -r "$keystore" ]]; then
        keytool -genkeypair -keystore "$keystore" -alias androiddebugkey \
            -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US' \
            -keyalg RSA -keysize 2048 -validity 10000
    fi
fi

"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
    --out "$work/signed.apk" "$work/aligned.apk"
"$build_tools/apksigner" verify "$work/signed.apk"
mv -f -- "$work/signed.apk" "$root/build/save-to-downloads.apk"
printf '%s\n' "$root/build/save-to-downloads.apk"
