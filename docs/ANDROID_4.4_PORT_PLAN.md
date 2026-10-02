# Android 4.4（API 19）适配

状态（2026-10-02）：代码改造已完成，`mobile` 能以 minSdk 19 编出 debug APK，单元测试和 `mobile` 的 lint 都通过。**尚未在 Android 4.4 真机上验证。**

目标车机：Android 4.4，32 位 ARM（armeabi-v7a），有 USB 口、系统热点、Wi-Fi Direct，蓝牙能配对 iPhone，以普通 App 侧载安装（无 root，非系统应用）。优先保证无线 CarPlay。

## 范围

- `mobile`、`common`、`shared` 的 minSdk 为 19，仍是同一个 APK：新 API 都加了运行时版本判断，新设备上的功能不变。
- `automotive`（AAOS，minSdk 28）和两个 sample（minSdk 30）不变。

## 4.4 上的行为

| 功能 | 4.4 上的做法 |
|---|---|
| 无线 CarPlay | 默认使用新增的 **DiPlay 热点**（`WirelessHotspotMode.APP_HOTSPOT`）：通过隐藏的 `setWifiApEnabled` 用自己生成的 WPA2 名称和密码开启车机热点，结束后恢复用户原来的热点配置和 Wi-Fi 开关。仍可选择"车机热点"模式。仅 Android 5.1 及以下提供，6.0 起这个调用需要额外权限。 |
| 有线 CarPlay | 从 `rawDescriptors` 解析 USB 配置；通过 usbfs ioctl 选择配置和 alternate setting；用常驻读线程代替 `requestWait(timeout)`；单次传输按 16 KB 分块；Lockdown TLS 使用捆绑的 Conscrypt。 |
| VPN（有线 NCM） | 用 JNI `fcntl` 清除 tun fd 的 `O_NONBLOCK`。 |
| 视频 | 只用 H.264（HEVC 开关置灰），软解回退为 `OMX.google.h264.decoder`，切换 Surface 时重建解码器。 |
| 音频 | 使用旧的 `AudioTrack`/`AudioRecord` 构造函数和 stream type；厂商私有 stream 初始化失败时回退到 `STREAM_MUSIC`；使用旧版音频焦点 API；不声明 Opus。 |
| 方向盘媒体键 | 用 `registerMediaButtonEventReceiver` 加 `CarPlayMediaButtonReceiver` 代替 `MediaSession`。 |
| 界面 | 用 Holo 作为基础主题；着色、Ripple、悬浮窗类型都有回退；悬浮窗地图没有圆角。 |
| 不可用 | 启动器地图嵌入（API 30）、Usage Access 相关监控（API 22）、Android Auto 服务（移到了 `automotive`）、Wi-Fi Direct（需要 API 29）、LocalOnlyHotspot。 |

## 主要改动位置

- **构建**：
  - `gradle/libs.versions.toml`：`core-ktx` 1.13.1、multidex、Conscrypt 2.5.3；
  - 各模块的 `build.gradle*`：JNI 按 API 21 编译，并为 Robolectric 设置 `conscryptMode=OFF`；
  - 删除了没人引用的 Compose `MainActivity`。
- **兼容层**：
  - `shared/.../compat/`：`Base64Compat`、`LegacyPlatformNative`（对应 `jni/legacy_platform.c`）、`TlsCompat`；
  - `common/.../ViewTintCompat.kt`。
- **无线**：`network/LegacyAppHotspotManager.kt`、`LegacySoftApSession.kt`、`LocalHotspotInterfaces.kt`，以及 `WirelessHotspotMode.supported()`。
- **有线**：`transport/UsbDescriptorParser.kt`、`UsbTopology.kt`、`QueuedUsbReader.kt`。
- **媒体**：`media/MediaCodecCompat.kt`、`AndroidMediaSink.kt`、`MicrophoneUplink.kt`，以及 `common/.../CarPlayMediaKeys.kt`。

## 真机验证步骤

1. **先跑探针**。安装 debug APK，然后执行：
   ```sh
   adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.probe.LegacyPlatformProbeActivity
   ```
   日志过滤 `DiPlayProbe` 标签。需要确认：
   - 4 个 native 库都能加载（含 `conscrypt_jni`）；
   - 有 H.264 解码器；
   - Conscrypt 支持的协议里包含 TLSv1.2；
   - "Start DiPlay hotspot" 能开起热点，iPhone 能用显示的密码加入，结束后原来的设置被恢复；
   - 按需测试 Wi-Fi Direct 建组，确认能否拿到 SSID、密码和接口名。
2. **测无线**：蓝牙配对 iPhone，选择"DiPlay 热点"后连接。检查画面、触控、媒体音频、导航提示音、Siri 和通话麦克风，持续 30 分钟。断开后检查热点和 Wi-Fi 状态是否恢复。然后用"车机热点"模式再测一遍。
3. **测有线**：同样的内容，重点看日志里的 `usbfs setConfiguration`、`setInterface` 和 NCM 读写。
4. **回归**：在一台 API 28 及以上的 DiLink 上各测一次无线（P2P）和有线。

## 已知风险和待办

- **热点权限**：部分 ROM 可能改过 `setWifiApEnabled` 的权限检查。如果不允许，就退回"车机热点"模式。
- **传统 Wi-Fi Direct**：要等探针确认能拿到组信息后才考虑实现。4.4 上无法控制 SSID、密码和信道，价值有限。
- **远程 MFi 的 HTTPS**：4.4 的系统 CA 缺少较新的根证书（如 ISRG Root X1），可能需要内置信任锚，目前未做。
- **频段**：4.4 的热点多为 2.4 GHz，建议 30 fps 和较低分辨率。
- **体积**：Conscrypt 让 APK 增大约 7 MB（包含所有 ABI）。如果只发给 32 位车机，可以单独打一个 armeabi-v7a 包。
- **原有 lint 错误**：`:shared:lintDebug` 在 `main` 上就有这些 Error，不是这次引入的：
  - MissingPermission；
  - `BydClusterBridge` 的 WrongConstant；
  - `LocalOnlyHotspotManager` 里 `SoftApConfiguration.Builder` 的 NewApi。
- **单元测试**：`AirPlayIapTunnelStreamTest.ipv6ListenerAcceptsIpv6Loopback` 在本机环境下（proxychains）`main` 上也失败。
