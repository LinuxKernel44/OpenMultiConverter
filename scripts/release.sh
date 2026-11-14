#!/usr/bin/env bash
# Maintainer release script (no CI): builds a signed release APK and publishes it as a GitHub release.
#
#   scripts/release.sh            # release the versionName found in app/build.gradle.kts
#
# Requirements: gh (authenticated), JDK 17, Android SDK + NDK, a keystore properties file at
# $OMC_KEYSTORE_PROPERTIES (default: ~/.android-keys/omc-keystore.properties) containing
# storeFile / storePassword / keyAlias / keyPassword. Release notes: docs/release-notes/v<version>.md
set -euo pipefail
cd "$(dirname "$0")/.."

KEYPROPS="${OMC_KEYSTORE_PROPERTIES:-$HOME/.android-keys/omc-keystore.properties}"
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
TAG="v$VERSION"
NOTES="docs/release-notes/$TAG.md"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)

[ -f "$KEYPROPS" ] || { echo "Missing keystore properties: $KEYPROPS"; exit 1; }
[ -f "$NOTES" ] || { echo "Missing release notes: $NOTES"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean"; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Tag $TAG already exists"; exit 1; }

for abi in arm64-v8a armeabi-v7a x86_64; do
  [ -f "app/src/main/jniLibs/$abi/libffmpeg.so" ] || { native/build-ffmpeg.sh; break; }
done

./gradlew --no-daemon clean testDebugUnitTest assembleRelease -PkeystoreProperties="$KEYPROPS"

OUT=release-out; mkdir -p "$OUT"
APK="$OUT/OpenMultiConverter-$TAG.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
"$APKSIGNER" verify --verbose --print-certs "$APK" | head -8
(cd "$OUT" && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256" && cat "$(basename "$APK").sha256")

git push origin HEAD
gh release create "$TAG" "$APK" "$APK.sha256" --target "$(git rev-parse HEAD)" \
  --title "OpenMultiConverter $VERSION" --notes-file "$NOTES"
echo "Released $TAG"
