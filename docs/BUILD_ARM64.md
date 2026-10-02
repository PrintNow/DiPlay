# Building on an arm64 Linux host

Google publishes the Linux NDK, build-tools, platform-tools and the aapt2 artifact that the
Android Gradle plugin downloads for x86_64 only. On an aarch64 Linux machine (for example an
Ubuntu VM on Apple silicon) those programs fail with `Exec format error`. This page describes a
working setup that keeps the official SDK layout and substitutes community arm64 builds of the
native programs. It was verified on Ubuntu 24.04 aarch64 with the CI command from
[Building DiPlay](BUILD.md). No Android emulator is installed.

## Install

```sh
sudo bash scripts/setup-dev-arm64.sh
```

Run it through `sudo` from your own account: the SDK and Gradle settings are written for
`$SUDO_USER`. Open a new terminal afterwards, or run `. /etc/profile.d/android-sdk.sh`.

| Component | Source | Location |
|---|---|---|
| JDK 25 | Eclipse Temurin, Adoptium apt repository (native arm64) | `/usr/lib/jvm/temurin-25-jdk-arm64` |
| cmdline-tools 20.0 | Google (Java only) | `/opt/android-sdk/cmdline-tools/latest` |
| `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools` | Google, through sdkmanager | `/opt/android-sdk` |
| aapt, aapt2, aidl, zipalign, adb, fastboot, … | [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) 35.0.2, static aarch64 | replace the x86_64 programs; originals kept as `*.x86_64` |
| `ndk;28.2.13676358` | [HomuHomu833/android-ndk-custom](https://github.com/HomuHomu833/android-ndk-custom) r28c, aarch64-linux-gnu | `/opt/android-sdk/ndk/28.2.13676358` |

The script also:

- writes `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `PATH` to
  `/etc/profile.d/android-sdk.sh` and sources it from `~/.zshrc` and `~/.bashrc`;
- adds `android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.0.0/aapt2` to
  `~/.gradle/gradle.properties`, so AGP uses the arm64 aapt2 instead of the x86_64 Maven artifact.
  Gradle prints a warning that this option is experimental; it is expected.

The substituted programs are third-party builds, not Google releases. Their output, native
libraries from the NDK compiler and resources linked by aapt2, goes into the APK. Decide whether
that is acceptable before using this setup for release builds.

Then point the project at the SDK. `local.properties` is ignored by Git:

```sh
echo "sdk.dir=/opt/android-sdk" > local.properties
```

## Build and test

Use the CI command from [Building DiPlay](BUILD.md), without `:home:testDebugUnitTest`:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :home:lintDebug \
  :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

`HomeBackNavigationTest` in `:home` uses Robolectric's native graphics runtime, which does not
support Linux aarch64 (`The Robolectric native runtime is not supported on Linux (aarch64)`).
Those tests fail on this host regardless of the code; GitHub's x86_64 CI runs them.

## Known issues

- **sdkmanager fails with `Exec format error`.** cmdline-tools releases newer than 20.0
  (`commandlinetools-linux-14742923`) make `sdkmanager` call an x86_64 `android` binary. Keep
  version 20.0.
- **`A new daemon was started but could not be connected to`.** A proxy injected with
  `LD_PRELOAD`, such as proxychains, also redirects the Gradle client's connection to its daemon
  on 127.0.0.1. Exclude local networks in `/etc/proxychains4.conf`, before `[ProxyList]`:

  ```
  localnet 127.0.0.0/255.0.0.0
  localnet 192.168.0.0/255.255.0.0
  ```

  The second line keeps wireless ADB to a head unit on the LAN off the proxy.
- **A running daemon fails a new download** (connection refused to an address in `224.0.0.0/8`,
  the proxychains fake-DNS range). Run that build once with `./gradlew --no-daemon`.
- **The arm64 `dexdump` aborts** (`libartpalette`: "libdl.a is a stub"). Inspect compiled classes
  with `javap` instead.
