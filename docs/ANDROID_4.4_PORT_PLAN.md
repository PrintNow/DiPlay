# Android 4.4（API 19）适配计划

状态：调研完成，未开始实施（2026-10-02）。当前 `shared`、`common`、`mobile` 的 minSdk 为 28，`automotive` 为 28，两个 sample 为 30。

## 目标与范围

- **目标产物**：`mobile` 一个 APK，minSdk 降到 19，同一包在新系统上功能不减（新 API 用运行时版本判断保留）。
- **4.4 上保证可用**：有线 CarPlay（USBMUX → Lockdown TLS → NCM/VPN → iAP2 → 视频/音频/触控/麦克风），无线 CarPlay 的"车机自带热点 + 蓝牙 RFCOMM"模式。
- **不在范围**：`automotive`（AAOS 本身 ≥ API 28，保持现状）、`samples/home`、`samples/maphost`（依赖 API 30 的 `SurfaceControlViewHost`，保持 30）。
- **4.4 上明确降级/关闭**的功能见"功能降级表"。

不另建 flavor：降 minSdk 不影响新设备，维护两套变体的成本高于运行时判断。若后续发现某依赖无法在 19 上共存，再考虑拆出 `legacy` flavor。

## 功能降级表（4.4 上）

| 功能 | 原因 | 4.4 行为 |
|---|---|---|
| Wi-Fi Direct 无线（自定凭据/频率） | `WifiP2pConfig.Builder`、3 参数 `createGroup`、`WifiP2pGroup.frequency` 均为 API 29 | 隐藏入口；`WifiP2pGroupManager.kt:73` 已拒绝 < Q |
| LocalOnlyHotspot | API 26（`MacAddress` 28、`SoftApConfiguration` 30） | 隐藏入口；**修 `CarPlayController.kt:1656` 的自动回退**。可选：反射 `setWifiApEnabled` 方案，单列评估 |
| 地图嵌入 `MapEmbedService` | `SurfaceControlViewHost` API 30 | 服务拒绝；先修 `MapEmbedService.kt:73` 在版本判断前调用 `sendingUid`（API 22） |
| Android Auto Car App Library | `androidx.car.app:app` minSdk ≥ 23（待构建确认） | `mobile` 移除依赖与 `MyCarAppService`（mobile 清单已 remove 该服务） |
| Usage Access（DiLink 5.1 仪表、主屏跟随） | `UsageStatsManager` API 21 | 关闭；可选 `getRunningTasks` 替代 |
| Opus 音频/麦克风 | 4.4 无 Opus 解码器；编码器 API 29 | 不在 `AirPlayInfoPlist` 中声明 Opus，只报 PCM/AAC |
| HEVC、低延迟解码、软解选择 | 4.4 实际无 HEVC 硬解；`KEY_LOW_LATENCY` 30；`isSoftwareOnly` 29 | 强制 H.264；软解改为按名称 `OMX.google.h264.decoder` |
| BYD HUD / 仪表 | 面向 Android 13 DiLink 固件 | 自然失效，不额外处理（调用失败不会崩） |
| 悬浮窗圆角、Ripple、着色等外观 | API 21/23 | 降级为无圆角、StateListDrawable、ColorFilter |

## 阶段 0：验证构建可行性（先做，结论决定后续选型）

在独立 worktree 中把 `shared`、`common`、`mobile` 的 minSdk 改为 19，执行：

1. `:mobile:processDebugMainManifest`：列出 minSdk 冲突的依赖。
2. `:mobile:lintDebug`（只看 `NewApi`、`InlinedApi`）：得到权威的 API 调用清单，与本计划核对。
3. `:mobile:assembleDebug`：确认 AGP 9.3 / D8 是否接受 minSdk 19，以及 64K 方法数是否超限。

需确认的问题：

- AGP 9.3 是否仍支持 minSdk 19 与 legacy multidex。
- 支持 19 的最后版本：`core-ktx`（预计 1.12–1.13）、`activity`、`lifecycle`、`desugar_jdk_libs`。
- `jmdns` 3.6.3 在 D8 minSdk 19 下是否有缺失 API；不行则退 3.4.1。
- **NDK**：r26 起最低 API 21。本机是 aarch64，官方 r25c 只有 linux-x86_64 宿主。两种选择：
  - (a) 继续 r28，32 位 ABI 以 API 21 编译，在 4.4 真机上验证 `.so` 能否加载（当前 C 代码只用 `open`/`ioctl`/`socket`，风险较低）；
  - (b) r25c + `APP_PLATFORM=android-19`，在 x86_64 机器或 CI 上构建。
  - 先试 (a)。

注意：本机 shell 有 proxychains 的 `LD_PRELOAD`，会劫持 Gradle 客户端与守护进程的本地连接；构建前需 `unset LD_PRELOAD` 并通过 `JAVA_TOOL_OPTIONS` 设置 SOCKS 代理。另一个 agent 同时跑 Gradle 时守护进程会被停掉，需错开。

## 阶段 1：修复现有潜在崩溃（与 4.4 无关也该修，可先单独提交）

| 位置 | 问题 |
|---|---|
| `shared/.../media/AndroidMediaSink.kt:563` | `setOutputSurface`（23）无版本判断，`catch (Exception)` 接不住 `NoSuchMethodError` |
| `shared/.../media/AndroidMediaSink.kt:908` | `AudioTrack.audioAttributes`（28）无判断 |
| `shared/.../airplay/CarPlayMediaEngine.kt:319,334` | 变量类型为 `ConcurrentHashMap`，`.keys` 编译为 `KeySetView`（24）；改声明为 `ConcurrentMap`/`MutableMap` |
| `shared/.../media/OpusEncoder.kt`、`AirPlayInfoPlist.kt:136` | API 28 上也会声明 Opus 麦克风输入，但编码器要 29；按版本声明 |
| `common/.../MapEmbedService.kt:73` | `sendingUid`（22）早于 R 判断 |
| `shared/.../network/ManualHotspotManager.kt:207-208,268` | `activeNetwork`/`getLinkProperties`/`WifiInfo.frequency` 不在异常保护内 |

## 阶段 2：构建配置

- `shared/build.gradle`、`common/build.gradle.kts`、`mobile/build.gradle.kts`：`minSdk = 19`；`shared` 的 `APP_PLATFORM` 与 `jni/Application.mk` 按阶段 0 结论调整。
- **去掉 Compose**：删除未被引用的 `common` 的 `MainActivity`（I2C 自检，122 行）和 `ui/theme/*`，移除 compose BOM、material3、activity-compose、ui-tooling 依赖及 `kotlin.compose` 插件。如需保留 I2C 自检，用 View 重写（很小）。
- **AndroidX**：降级 `core-ktx`；移除未使用的 `appcompat`（automotive 不受影响）；`mobile` 移除 `app-projected`；`shared` 中的 `androidx.car.app:app` 与 `MyCarApp*` 迁到 `automotive`，或改为 `compileOnly` + 运行时判断（阶段 0 后定）。
- **方法数**：开启 R8（目前 `optimization.enable = false`）并为 BouncyCastle/jmdns 写 keep 规则；不行再加 `androidx.multidex` + `MultiDexApplication`。另一选项：BC 只用到 ASN.1（`mfi/LocalMfiAuthenticationClient.kt:91-95`），可手写 ECDSA r/s 的 DER 转换后移除 bcprov。
- **主题**：`common/res/values/themes.xml` 的 `Theme.Material.NoActionBar`（21）、`windowLightStatusBar`（23）拆到 `values-v21`/`values-v23`，`values` 用 `Theme.Holo`/`DeviceDefault`。
- **矢量图**：`common/res/drawable` 下 21 个 vector，依赖 AGP 为 < 21 生成 PNG（通知、RemoteViews 不能用 `VectorDrawableCompat`）。确认 `vectorDrawables.useSupportLibrary` 未开启。

## 阶段 3：Java 8+ 库 API 替换

| API | 处理 | 位置 |
|---|---|---|
| `java.util.Base64`（26） | 换 `android.util.Base64`（`NO_WRAP`） | `VideoInCar.kt`、`AdbKeys.kt`、`BydClusterSong.kt`、`LockdownTlsEngineFactory.kt`、`LockdownPlistChannel.kt`、`LockdownPairRecord.kt`、`RemoteMfiAuthenticationClient.kt` |
| `java.time`（26） | UTC `Calendar` 或开 desugaring | `iap2/.../Iap2LocationClient.kt:122-123` |
| `ConcurrentHashMap.computeIfAbsent`（24） | `getOrPut`/`putIfAbsent` | `AndroidMediaSink.kt:284,317` |
| `ConcurrentHashMap.newKeySet`（24） | `Collections.newSetFromMap(ConcurrentHashMap())` | `network/CarPlayBonjour.kt:134` |
| `AtomicLong.accumulateAndGet`（24） | CAS 循环 | `AndroidMediaSink.kt:776` |

倾向手工替换，不依赖 `coreLibraryDesugaring`（只有 `java.time` 一处真正需要它）。

## 阶段 4：系统 API 兼容层（机械修改，约 60–80 处）

建议新增一个 `shared/.../compat/` 包集中处理，避免散落的版本判断：

- `getSystemService(Class)`（23）：约 30 处（shared 13 处、common 17 处），改为 `ContextCompat.getSystemService` 或字符串常量。
- `checkSelfPermission`（23）：约 10 处，改为 `ContextCompat.checkSelfPermission`。
- 通知：`DiPlaySessionService.kt:26-33` 的 `NotificationChannel`（26）、`Notification.Builder(ctx, id)`（26）、`Action.Builder(Icon…)`（23），改用 `NotificationCompat`（`androidx.core` 已是依赖）。
- `startForegroundService`（26）：`CarPlayHostActivity.kt:3297` → `ContextCompat.startForegroundService`。
- 悬浮窗：`CenterMapOverlay.kt:45` 的 `canDrawOverlays`（23），< 23 视为已授权；`:87` 的 `TYPE_APPLICATION_OVERLAY`（26），< 26 用 `TYPE_PHONE`；`:123-127` 的 `ViewOutlineProvider`（21）去掉圆角。`DiPlayActivity.kt:127` 的悬浮窗授权入口在 < 23 隐藏。
- 视图外观：`thumbTintList`/`trackTintList`（23）、`progressTintList`/`backgroundTintList`/`buttonTintList`/`imageTintList`（21）约 20 处（集中在 `CarPlayHostActivity.kt`、`DiPlayActivity.kt`）；`RippleDrawable`（21，`DiPlayActivity.kt:1131,1235`）；`letterSpacing`（21）；`statusBarColor`（21，`DiPlayActivity.kt:98`）。
- 其他：`MediaPlayer.seekTo(long, mode)`（26，`CarPlayVideoActivity.kt:149`）；`noBackupFilesDir`（21，`adb/AdbKeys.kt:35`）；`NsdServiceInfo.setAttribute`（21，`CarPlayBonjour.kt:321`，生产路径用 jmdns，仅加判断）；`PendingIntent.FLAG_IMMUTABLE`（23，内联常量，按版本拼）。

## 阶段 5：媒体管线

`shared/.../media/AndroidMediaSink.kt`、`MicrophoneUplink.kt`、`common/.../CarPlayMediaKeys.kt`、`AudioChannelPreview.kt`：

- **MediaCodec**：`getInputBuffer`/`getOutputBuffer`（21，`AndroidMediaSink.kt:598,1122,1164`）改用 `getInputBuffers()[i]`；`:646`、`:1176` 的出队循环处理 `INFO_OUTPUT_BUFFERS_CHANGED`。`MediaCodecList(REGULAR_CODECS)`（21，`CarPlayHostActivity.kt:2797`、`AndroidMediaSink.kt:528,538`）改用 `getCodecCount/getCodecInfoAt`；`VideoCapabilities`（21，`CarPlayHostActivity.kt:2805-2807`）< 21 跳过。
- **Surface 切换**：`setOutputSurface`（23）< 23 走已有的"释放并重新配置"路径（`:570-571`）。
- **AudioTrack**：`AudioTrack.Builder`（23）/`AudioFormat.Builder`（21）回退到旧构造函数（`AndroidMediaSink.kt:890` 已有）；`AudioAttributes`（21）目前是 `Entry` 数据类字段，需抽象为"属性或 streamType"；`bufferSizeInFrames`（23）自行换算；`write(…, WRITE_BLOCKING)`（23）改 3 参数版本。
- **欠载检测**：`underrunCount`（24，`:1233-1261`）没有替代 API；< 24 用"队列为空且 `playbackHeadPosition` 停滞"判定，或关闭重新缓冲。`routedDevice`（23）诊断项 < 23 记 -1。
- **音频焦点**：`AudioFocusRequest`（26，`AndroidMediaSink.kt:73-92`、`CarPlayMediaKeys.kt:89-118`）回退到 `requestAudioFocus(listener, stream, hint)`。
- **媒体按键**：`MediaSession`（21，`CarPlayMediaKeys.kt:105-141`）改 `androidx.media` 的 `MediaSessionCompat`。
- **麦克风**：`AudioRecord.Builder`（23）、`read(…, READ_BLOCKING)`（23）回退旧接口。
- **多路解码器**：`AndroidMediaSink.kt:200-218` 的镜像解码器在 4.4 硬件上可能受实例数限制，需真机验证。

## 阶段 6：有线链路（工作量最大）

### 6.1 USB host（`shared/.../transport/`）

| 位置 | API | 级别 |
|---|---|---|
| `IphoneCarPlayConfiguration.kt:30-31`、`NcmFunctionDiscovery.kt`、`IphoneUsbHost.kt:8,246` | `UsbConfiguration`、`configurationCount`/`getConfiguration` | 21 |
| `IphoneUsbHost.kt:246` | `setConfiguration` | 21 |
| `NcmUsbBridge.kt:339` | `setInterface`（NCM 数据接口 alt 1） | 21 |
| `NcmFunctionDiscovery.kt:41`、`IphoneUsbHost.kt:258`、`NcmUsbBridge.kt:316,329,342`、`CarPlayController.kt:1477` | `alternateSetting` | 21 |
| `IphoneUsbHost.kt:371`、`NcmUsbBridge.kt:235` | `UsbRequest.queue(ByteBuffer)` | 26 |
| `IphoneUsbHost.kt:377,429`、`NcmUsbBridge.kt:245` | `requestWait(timeout)` | 26 |

方案：

- 新增 JNI `usbdevfs`（加到 `shared/src/main/jni`），基于 `connection.fileDescriptor` 实现 `SETCONFIGURATION`/`SETINTERFACE`/`CLAIMINTERFACE`/bulk 传输。只发 `controlTransfer` 形式的 SET_INTERFACE 不够，内核不会启用端点。
- 配置与 alt-setting 从 `connection.rawDescriptors`（13）自行解析，抽象出与 `UsbConfiguration`/`UsbInterface` 等价的描述符模型，供 < 21 使用。
- `queue(ByteBuffer)` → `queue(buf, len)`；带超时的 `requestWait` 改为看门狗线程 `cancel()`。
- **传输大小**：< 26 的 `UsbRequest`、< 28 的 `bulkTransfer` 单次上限 16 KB。`IphoneUsbHost.kt:454`（64 KB）和 `NcmUsbBridge.kt:292`（32 KB）改为按版本取值，NCM 的 NTB 输入大小相应协商下调。

### 6.2 TLS（`transport/TlsDuplexChannel.kt`、`LockdownTlsEngineFactory.kt`）

- 4.4 平台 `SSLEngine` 不支持 TLS 1.2（API 20 起），`TlsDuplexChannel.kt:392,409-416` 会抛"supports neither TLSv1.2 nor TLSv1.3"。
- 捆绑 TLS provider：优先评估 `conscrypt-android`（核实其最低 API 与体积），备选 BouncyCastle `bctls-jdk18on`（纯 Java，与现有 bcprov 同源）。仅在 < 20 时从该 provider 创建 `SSLContext`。
- `LockdownTlsEngineFactory.kt:51` 的 `setEndpointIdentificationAlgorithm`（24）加判断。
- `mfi/RemoteMfiAuthenticationClient.kt`：HTTPS 在 4.4 需显式启用 TLS 1.2，且系统 CA 缺 ISRG Root X1 等新根证书。使用同一 provider 并内置信任锚，或要求该路径使用 http。

### 6.3 VPN（`network/CarPlayVpnService.kt:95`）

- `Builder.setBlocking`（21）：< 21 在 JNI 中 `fcntl` 清除 tun fd 的 `O_NONBLOCK`；否则 `Ipv6NcmBridge.kt:96` 会收到 EAGAIN。
- 仅链路本地 IPv6 的 tun 在 4.4 VPN 栈上的行为需真机验证。

## 阶段 7：无线（车机热点模式）

- `ManualHotspotManager.kt`：阶段 1 的崩溃修复之外，< 21 用 `getActiveNetworkInfo()` + 接口名推断代替 `activeNetwork`/`LinkProperties`；频率未知时广播为 0，确认 iPhone 能接受。
- `CarHotspotStatus.kt` 的 `getWifiApState` 反射在 4.0+ 正确，无需改。
- 蓝牙 RFCOMM、`getProfileProxy` 在 4.4 可用；`BluetoothDevice.isConnected` 反射可能缺失，已有回退。
- 4.4 上的后端选择逻辑：只提供"车机热点"，不再自动回退到 P2P/LOHS。

## 阶段 8：验证

- 单元测试：Robolectric 4.17 是否还能以 `@Config(sdk = 19)` 运行需确认；不能则至少在 21 上跑兼容分支，19 仅做真机测试。
- 真机矩阵：一台 4.4 车机（32 位 ARM）完成有线 CarPlay 的完整会话（画面、触控、媒体音频、导航提示音、通话/Siri 麦克风），再测热点无线；同时回归一台当前主力 DiLink（API 28+），确认无功能退化。
- 更新 `docs/COMPATIBILITY.md` 与 `docs/BUILD.md`（NDK/代理说明）。

## 风险与待决问题

1. **目标车机信息**：CPU ABI、是否支持 USB host/OTG、是否带可配置热点、是否需要无线。这些决定阶段 6/7 的优先级。
2. **NDK 选型**：r28 编译的 API 21 `.so` 能否在 4.4 加载，未验证（阶段 0）。
3. **TLS provider 体积**：Conscrypt 带 native 库，约数 MB；bctls 纯 Java 但会进一步增加方法数。
4. **USB 吞吐**：16 KB 单次传输加上 4.4 的 usbfs 性能，60 fps 或高码率可能吃紧，默认 30 fps。
5. 另一个 agent 正在处理运行时依赖，阶段 2 的依赖版本调整需与其对齐。

## 工作量粗估

| 阶段 | 规模 |
|---|---|
| 0 验证 | 0.5–1 天 |
| 1 崩溃修复 | 0.5 天 |
| 2 构建配置 | 1 天 |
| 3 Java API 替换 | 0.5 天 |
| 4 系统 API 兼容层 | 2 天 |
| 5 媒体管线 | 2–3 天 |
| 6 有线链路（USB JNI、TLS、VPN） | 5–8 天 |
| 7 无线热点模式 | 1–2 天 |
| 8 真机验证与修复 | 视设备而定，≥ 3 天 |
