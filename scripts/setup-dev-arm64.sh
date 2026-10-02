#!/usr/bin/env bash
# Install the DiPlay build environment on an Ubuntu 24.04 aarch64 (arm64) host.
# No Android emulator is installed. See docs/BUILD_ARM64.md.
# Usage: sudo bash scripts/setup-dev-arm64.sh
#
# Google ships the Linux NDK, build-tools, platform-tools and the AGP aapt2 artifact for x86_64
# only, so this script installs the official SDK packages and then substitutes arm64 builds:
#   - JDK 25: Eclipse Temurin from the Adoptium apt repository
#   - Android SDK in /opt/android-sdk: cmdline-tools 20.0 (newer releases ship an x86_64
#     `android` binary that sdkmanager calls), platforms;android-37.0, build-tools;36.0.0,
#     platform-tools
#   - build-tools / platform-tools native programs -> lzhiyong/android-sdk-tools 35.0.2 (static aarch64)
#   - ndk;28.2.13676358 -> HomuHomu833/android-ndk-custom r28c (aarch64-linux-gnu)
#   - aapt2 used by AGP -> android.aapt2FromMavenOverride in ~/.gradle/gradle.properties
set -euo pipefail

TARGET_USER="${SUDO_USER:-}"
TARGET_HOME="$(getent passwd "$TARGET_USER" | cut -d: -f6 || true)"
SDK_ROOT=/opt/android-sdk
JAVA_HOME_DIR=/usr/lib/jvm/temurin-25-jdk-arm64

CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip
ARM64_TOOLS_URL=https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
NDK_VERSION=28.2.13676358
NDK_URL=https://github.com/HomuHomu833/android-ndk-custom/releases/download/r28/android-ndk-r28c-aarch64-linux-gnu.tar.xz
BUILD_TOOLS=36.0.0

log() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }

[[ $EUID -eq 0 ]] || { echo "Run as root: sudo bash $0" >&2; exit 1; }
[[ -n "$TARGET_USER" && "$TARGET_USER" != root ]] || { echo "Run through sudo from the developer account so SUDO_USER is set" >&2; exit 1; }
[[ "$(uname -m)" == aarch64 ]] || { echo "This script is for aarch64 hosts only" >&2; exit 1; }
[[ -n "$TARGET_HOME" && -d "$TARGET_HOME" ]] || { echo "Home directory of $TARGET_USER not found" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log "Installing system packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y curl wget ca-certificates gnupg unzip zip xz-utils git python3 file

log "Installing Temurin JDK 25 (Adoptium apt repository)"
install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
  > /etc/apt/sources.list.d/adoptium.list
apt-get update
apt-get install -y temurin-25-jdk
[[ -x "$JAVA_HOME_DIR/bin/java" ]] || { echo "$JAVA_HOME_DIR not found" >&2; exit 1; }
export JAVA_HOME="$JAVA_HOME_DIR" PATH="$JAVA_HOME_DIR/bin:$PATH"

log "Installing Android cmdline-tools 20.0 into $SDK_ROOT"
mkdir -p "$SDK_ROOT/cmdline-tools"
rm -rf "$SDK_ROOT/cmdline-tools/latest"
curl -fL -o "$WORK/clt.zip" "$CMDLINE_TOOLS_URL"
unzip -q "$WORK/clt.zip" -d "$WORK/clt"
mv "$WORK/clt/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

log "Accepting licenses; installing platforms;android-37.0, build-tools;$BUILD_TOOLS, platform-tools"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "platforms;android-37.0" "build-tools;$BUILD_TOOLS" "platform-tools"

log "Replacing x86_64 programs in build-tools and platform-tools with arm64 builds"
curl -fL -o "$WORK/arm64-tools.zip" "$ARM64_TOOLS_URL"
unzip -q "$WORK/arm64-tools.zip" -d "$WORK/arm64-tools"
for sub in build-tools platform-tools; do
  if [[ $sub == build-tools ]]; then dest="$SDK_ROOT/build-tools/$BUILD_TOOLS"; else dest="$SDK_ROOT/platform-tools"; fi
  for f in "$WORK/arm64-tools/$sub"/*; do
    name="$(basename "$f")"
    # Keep the original x86_64 program as a backup.
    if [[ -f "$dest/$name" && ! -e "$dest/$name.x86_64" ]]; then mv "$dest/$name" "$dest/$name.x86_64"; fi
    install -m 0755 "$f" "$dest/$name"
  done
done

log "Installing the arm64 NDK r28c as ndk;$NDK_VERSION"
NDK_DIR="$SDK_ROOT/ndk/$NDK_VERSION"
rm -rf "$NDK_DIR"
mkdir -p "$SDK_ROOT/ndk"
curl -fL -o "$WORK/ndk.tar.xz" "$NDK_URL"
tar -xJf "$WORK/ndk.tar.xz" -C "$WORK"
mv "$WORK/android-ndk-r28c" "$NDK_DIR"
# AGP identifies the NDK by Pkg.Revision in source.properties.
if ! grep -q "^Pkg.Revision *= *$NDK_VERSION" "$NDK_DIR/source.properties"; then
  sed -i "s/^Pkg.Revision *=.*/Pkg.Revision = $NDK_VERSION/" "$NDK_DIR/source.properties"
fi

log "Writing environment variables (/etc/profile.d and the shell rc files of $TARGET_USER)"
cat > /etc/profile.d/android-sdk.sh <<EOF
export JAVA_HOME=$JAVA_HOME_DIR
export ANDROID_HOME=$SDK_ROOT
export ANDROID_SDK_ROOT=$SDK_ROOT
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH
EOF
for rc in "$TARGET_HOME/.zshrc" "$TARGET_HOME/.bashrc"; do
  [[ -f "$rc" ]] || continue
  sed -i '/# >>> diplay android env >>>/,/# <<< diplay android env <<</d' "$rc"
  printf '# >>> diplay android env >>>\n. /etc/profile.d/android-sdk.sh\n# <<< diplay android env <<<\n' >> "$rc"
done

log "Pointing Gradle at the arm64 aapt2"
GRADLE_PROPS="$TARGET_HOME/.gradle/gradle.properties"
install -d -o "$TARGET_USER" -g "$TARGET_USER" "$TARGET_HOME/.gradle"
touch "$GRADLE_PROPS"
sed -i '/^android.aapt2FromMavenOverride=/d' "$GRADLE_PROPS"
echo "android.aapt2FromMavenOverride=$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt2" >> "$GRADLE_PROPS"
chown "$TARGET_USER:$TARGET_USER" "$GRADLE_PROPS"

# Let the developer account own the SDK so Gradle and sdkmanager can update it.
chown -R "$TARGET_USER:$TARGET_USER" "$SDK_ROOT"

log "Verifying"
java -version 2>&1 | head -1
"$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt2" version
"$SDK_ROOT/platform-tools/adb" version | head -1
"$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version | head -1
"$NDK_DIR/prebuilt/linux-x86_64/bin/make" --version | head -1
grep Pkg.Revision "$NDK_DIR/source.properties"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --list_installed 2>/dev/null | sed -n '1,20p'

log "Done. Open a new terminal (or run: . /etc/profile.d/android-sdk.sh) before building."
