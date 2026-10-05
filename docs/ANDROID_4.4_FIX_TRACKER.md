# Android 4.4 compatibility fix tracker

Baseline: `origin/main` at `2fc876e578eba3905a5b873e3c2dbd74f498433a`.

Goal: keep current `main` behavior on supported Android versions, while API 19 uses explicit compatibility paths, hidden entries, or clear unsupported messages.

API 19 acceptance scope: reliably start and sustain the basic CarPlay projection/navigation path (wired USB or the supported legacy wireless path, video, navigation audio/media controls, required microphone use, and clean disconnect/restore). Modern-only extras such as advanced HUD integration, rich media metadata, animations, Wi-Fi Direct, and modern hotspot APIs may be hidden or report an explicit unsupported state on KitKat, but must remain intact on newer Android versions.

## Priority and ownership

| Priority | Workstream | Owner | Status | Exit criteria |
| --- | --- | --- | --- | --- |
| P0 | `CarPlayHostActivity` API 19 startup, permissions, foreground service and UI compatibility | `host-api19` | Complete | Compat system services/UI properties; API 19 H.264 effective configuration; focused tests/build pass |
| P0 | `CarPlayController`, VPN, hotspot mode/session, Opus capability negotiation | `transport-api19` | Complete | API 19 controller/VPN avoid missing APIs; legacy WPA2 hotspot is selected and restoration is retryable |
| P0 | Restore `main` media/video/UI behavior with API 19-specific fallbacks | `media-regression` | Complete | Media3/now-playing remain on newer Android; legacy audio and media paths are isolated from post-19 types |
| P0 | Restore the full `main` `DiPlayActivity` feature surface | `media-regression` | Complete | High-version settings/features remain present; unsupported KitKat entries are guarded, hidden, or explained |
| P1 | Integration, residual lint/NewApi repairs, and regression verification | `root` | Complete | Compatibility lint IDs are clear; debug APK, module tests, shared lint and app lint pass |
| P2 | Android 4.4 hardware validation | Device owner | Pending hardware | Probe, wireless, wired USB/NCM, media/mic and restore checks pass on a real API 19 head unit |

## Agreed test seams

The implementation uses the behavior seams already named in the review request and existing suite:

- `WirelessHotspotMode.supported/default` for SDK capability selection.
- `LegacySoftApSession` for hotspot transition and restoration behavior.
- `UsbDescriptorParser`/`UsbTopology` and queued USB reader behavior.
- `CarPlayMediaKeys`/media callback behavior and telephony microphone lifecycle.
- Activity-level Robolectric tests where an Android API call must be proven safe on API 19.

## Validation log

Before fixes:

- `:mobile:assembleDebug`: passed.
- `:mobile:lintDebug`: passed, but did not expose all library findings.
- `:common:lintDebug`: failed with 179 errors and 177 warnings.
- `:shared:lintDebug`: failed with 77 errors and 104 warnings.
- `:common:testDebugUnitTest`: test compilation failed after media helpers were removed.
- `:shared:testDebugUnitTest`: 8 telephony microphone tests failed.
- Focused hotspot mode/session, USB descriptor and Base64 tests: passed.

After fixes:

- `:mobile:assembleDebug`: passed with NDK r25c (`25.2.9519653`). The APK's three in-house `armeabi-v7a` native libraries carry Android ELF note API 19; their 64-bit variants correctly carry API 21, the minimum for those ABIs.
- `:shared:testDebugUnitTest`: passed, including hotspot transition/restore, USB descriptor parsing, VPN fallback, codec capability, audio focus, microphone and media-button coverage.
- `:common:testDebugUnitTest`: passed, including settings migration, media callbacks, Bonjour and authentication-without-assets coverage. Robolectric 4.17 does not execute an actual SDK 19 runtime image.
- `:shared:lintDebug` and `:mobile:lintDebug`: passed.
- Forced lint reports for `shared`, `common` and `mobile` contain no `NewApi`, `InlinedApi`, `MissingPermission`, or `ForegroundServicePermission` findings.
- `:common:lintDebug`: still fails on the repository's broader pre-existing lint debt (75 errors, 182 warnings), led by `RestrictedApi`, string-format/resource and translation findings unrelated to the API 19 compatibility paths. No baseline was added to hide them.
- Independent Standards and Spec reviews found and verified fixes for API 19 homepage `letterSpacing`, effective H.264 selection, legacy audio-focus routing, hotspot `DISABLING` handling, start/close races, retryable owner-state restoration, and preservation of modern hotspot/Media3 paths.

## Required Android 4.4 hardware validation

- Cold-start the APK on an API 19 ARMv7 device and exercise all three JNI loads/probes; compilation and ELF inspection cannot prove the device linker, SELinux policy or vendor kernel behavior.
- Verify wired attach, raw USB descriptors, queued `UsbRequest`, usbfs `SETCONFIGURATION`/`SETINTERFACE`, Conscrypt TLS and VPN blocking `fcntl` behavior.
- Verify the hidden-API WPA2 hotspot starts, exposes the discovered interface/address to wireless iAP2, survives cancellation, and restores the exact owner Wi-Fi/hotspot configuration after disconnect and failure.
- Run a navigation session long enough to cover H.264 video, navigation audio over media, microphone/Siri and steering-wheel media keys. Validate vendor `MediaCodec`/`AudioTrack` routing and focus behavior.
- Confirm the API 19 video-in-car entry reports Android 5.0 as required without loading Media3; confirm Media3 video, rich metadata/HUD and modern P2P/LOHS paths still work on supported newer devices.
