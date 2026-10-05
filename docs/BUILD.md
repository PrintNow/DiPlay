# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 25.2.9519653 and the included Gradle wrapper.

`mobile`, `common` and `shared` target Android 4.4 (minSdk 19). NDK r25 is the last NDK release
that supports API 19, so the 32-bit ARM JNI libraries are built with `APP_PLATFORM=android-19`.
The 64-bit ABIs have a platform minimum of API 21 by definition. Do not upgrade the NDK without
dropping KitKat support or supplying separately built API 19 libraries. The APK uses legacy
multidex, and bundles Conscrypt 2.5.3 for TLS 1.2 on Android 4.4; unit tests run Robolectric with
`robolectric.conscryptMode=OFF`.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## GitHub tag release builds

Pushing a tag matching `v*` (for example `v0.2.13`) runs the **Build and publish APK** workflow.
It tests, lints and builds the signed release APK, uploads it as a workflow artifact, then attaches
it and `SHA256SUMS.txt` to the GitHub Release for that tag. It never runs from a pull request.
You can also use **Actions → Build and publish APK → Run workflow** to rebuild an existing release:
enter that exact `v*` tag, not a branch name or commit SHA.

Create these repository or protected-environment **Actions secrets**. Store file values as a single
base64 string with no wrapping; on macOS, use `base64 -i FILE | tr -d '\n'`.

| Secret | Value |
| --- | --- |
| `DIPLAY_AUTH_IDENTITY_PK8_B64` | `offline-mfi/identity.pk8`, base64 encoded |
| `DIPLAY_AUTH_CERTIFICATE_P7B_B64` | `offline-mfi/certificate.p7b`, base64 encoded |
| `ANDROID_KEYSTORE_B64` | The Android release `.jks`/`.keystore`, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing-key alias |
| `ANDROID_KEY_PASSWORD` | Signing-key password |

The workflow writes these only under the runner's temporary directory, passes their paths via the
existing build environment variables, and does not upload the keystore or raw credential files.
The APK necessarily contains the runtime CarPlay identity, so anyone receiving the APK can extract
it; GitHub Secrets prevent source-repository disclosure, not extraction from a published APK.

## Standalone car-test APK

An iPhone only accepts DiPlay after accessory authentication. `assembleDebug` contains no
accessory identity, so the iPhone rejects it; use `:mobile:assembleStandaloneDebug` for any APK
that must connect to an iPhone. The app module is `:mobile` (there is no `:app`).

1. Check out the source you want to test, for example the Android 4.4 work:

   ```sh
   git fetch origin
   git checkout feat/android-4.4
   ```

2. Prepare a runtime asset directory **outside the repository**. It must contain exactly these
   two non-empty files, which never belong in Git:

   ```text
   /absolute/path/to/runtime-assets/
   └── offline-mfi/
       ├── identity.pk8
       └── certificate.p7b
   ```

   Any other key or certificate file (`*.pem`, `*.key`, `*.p12`, `*.jks`, …) in an APK asset
   directory fails the build.

3. Build with an absolute `DIPLAY_AUTH_ASSETS_DIR` (a relative path is resolved against
   `mobile/`):

   ```sh
   DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
   ```

   The task fails if the variable is unset or either file is missing or empty. Output:
   `mobile/build/outputs/apk/debug/mobile-debug.apk`, package `com.shihab.diplay.hudtest`
   (version name `…-hud-test`), which installs beside a release DiPlay.

4. Verify that the APK carries the selected files before installing or sharing it:

   ```sh
   unzip -l mobile/build/outputs/apk/debug/mobile-debug.apk | grep offline-mfi
   unzip -p mobile/build/outputs/apk/debug/mobile-debug.apk assets/offline-mfi/identity.pk8 | shasum -a 256
   shasum -a 256 /absolute/path/to/runtime-assets/offline-mfi/identity.pk8
   ```

   Repeat the comparison for `certificate.p7b`. The APK contains an extractable identity: do not
   publish it.

5. Install on the head unit (or any Android device acting as one), updating in place so settings
   and pairing records are kept:

   ```sh
   adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
   ```

6. On Android 4.4–5.1, run the platform probe first and check its results (native libraries,
   H.264 decoder, Conscrypt TLSv1.2, DiPlay hotspot); see the
   [Android 4.4 notes](ANDROID_4.4_PORT_PLAN.md):

   ```sh
   adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.probe.LegacyPlatformProbeActivity
   adb logcat -s DiPlayProbe
   ```

7. Connect as described in the [installation guide](INSTALL.md): pair the iPhone over Bluetooth
   and use **Connect phone** (on Android 4.4–5.1 the default wireless link is the DiPlay hotspot),
   or **Connect with USB** through a data port or USB OTG adapter. After reproducing a problem,
   save a diagnostic report and collect `adb logcat`.
