# 定制与修改总清单（CPC200-Tbox + 2022 丰田威飒）

本文档记录在 `Carlife_on_box v2026.10.17 (d9397d9)` 与 CPC200-Tbox 原厂固件（2025.12.18 OTA / Android 9 / Magisk v31.1）之上实施的全部软件、系统配置与脚本定制。

---

## 1. 核心服务端 `carlife-md-api28` 相对上游的定制

### 新增文件（4 个）
1. `Api28DisplayMirror.java` — 适配 Android 9（API 28）无 `DisplayControl` / `createDisplay` 签名的虚拟屏镜像捕获层（`SurfaceControl.createDisplay` + `setDisplayProjection` + `openTransaction/closeTransaction`）。
2. `Api28MicBridge.java` — Android 9 车机麦克风回传桥接：
   - 注册 `AudioPolicy` 录音注入 Mix（16kHz 单声道），覆盖 `MIC(1)` / `VOICE_RECOGNITION(6)` / `VOICE_COMMUNICATION(7)` / `UNPROCESSED(9)`；
   - 按会话延迟创建/释放 `AudioTrack`（`lazy_per_session`）+ `headPos` 停滞自动恢复（仅重建 `AudioTrack`，绝不在外部录音器存活期间注销 `AudioPolicy`，根治 Android 9 `AudioPolicyManager::stopInput()` UAF `SIGSEGV`）；
   - 通过反射直接调用 `AudioTrack.startImpl()` 启动虚拟麦注入轨道，绕过盒子原厂 `framework.jar` 中 `DesonAudioTrack` 在 `AudioTrack.play()` 内的拦截逻辑（当 `sys.phonecall.state=1` 通话期间非 `com.suding.speedplay` 进程的 `play()` 会被直接静默 `return`），同时避免污染 `SudingMediaFocusControl.rawAudioWriter`；
   - 记录活跃录音包名（`activeRecorderPkg`）供方向盘语音键切换判断；
   - `call=bt` 时排除 `uid=0` 与 `preset 7`，交由手机直连车机原车蓝牙处理（不再触发无效的 `QCOM-BTD` 虚拟通话 SCO）。
3. `BtAudioPolicy.java` — 协调 `QCOM-BTD` A2DP 自动连接优先级：
   - 当 `media=usb && navi=usb` 时将 `bluetooth_a2dp_sink_priority_<MAC>` 设为 `0`（保留 `bluetooth_headset_priority_<MAC>=100`），根治威飒每 6 秒 A2DP 闪断重试导致的音频卡顿与 `audioserver` 崩溃；
   - 当 `media=bt || navi=bt` 时自动恢复为 `100` 并清除 `FOR_MEDIA=10 -> 0`，并带 `<4s` 连续闪断熔断机制。
4. `UEventWatch.java` — 内核 `ACCESSORY=START` uevent 监听器（基于编译期 stub `android.os.UEventObserver`），供 `carlife-daemon.sh` 在 `mtp.gs0` 阶段精准切换至 `accessory.gs2`。

### 修改的上游文件（10 个）
1. `AndroidAccessoryIo.java` — 适配 `msm8953` Android 9 `/dev/usb_accessory` 读写行为。
2. `AndroidRuntimeContext.java` — 适配 API 28 `ActivityThread.systemMain()` 上下文获取。
3. `EglDisplayPipe.java` — 适配 API 28 EGL / `MediaCodec` 零拷贝渲染管线。
4. `MediaCodecDisplaySource.java` — 适配 `OMX.qcom.video.encoder.avc`（1920x720@30fps）及线程优先级调优。
5. `AudioUsage.java` — 新增动态属性热读取（`persist.tbox.carlife.audio.{media,navi,call}`）以及中国主流导航 App UID 自动解析（高德/百度/腾讯地图）。
6. `AudioPolicySources.java` — 适配 API 28 `AudioMixingRule`（无 `setTargetMixRole`，基于 `unregisterAudioPolicyAsync`），增加高德等导航 UID 独立 TTS Mix 与 `DualPolicyRecord` 等速实时混音器。
7. `CarLifeAudio.java` / `PcmAudioSession.java` — 提升音频线程优先级（`THREAD_PRIORITY_URGENT_AUDIO`），增大音频队列年龄预算（`350ms`），适配高并发应用切换抗抖动。
8. `PlaybackActivity.java` — 适配 API 28 播放监听与导航 UID 区分，排除 `uid=0` / 导航静音误触发。
9. `MdMain.java` — 集成全部威飒适配逻辑：
   - 启动 `Api28MicBridge`、实时热重载音视频路由属性、接入 `BtAudioPolicy`、`audioserver` 崩溃自动重建；
   - `0x1801d`（`LAUNCH_MODE_NORMAL`）与 `0x18025`（`GO_TO_FOREGROUND`）仅唤醒屏幕不注入 `KEYCODE_HOME`，修复从车机弹窗/蓝牙配对返回后被切回桌面的问题；
   - `0x68008`（`KEYCODE_VR_START=33` / `KEYCODE_VR_STOP=34`）与 `0x18028 (module=4)` 接入 `triggerVoiceAssistant()`，通过 `com.texustek.main.ext.voice.assist` / `com.aispeech.lyra.action.TOGGLE_SPEECH` 实现方向盘语音键一键唤醒/关闭盒子语音助手（或前台 CarPlay Siri）。

---

## 2. Magisk 非破坏性系统层定制

- `magisk-module/system/bin/sd-reverse.sh` — 覆写 `/system/bin/sd-reverse.sh`，在原厂 CarPlay / USB ADB 之间插入 `persist.tbox.carlife=1` 分支，`exec /data/adb/carlife/carlife-daemon.sh`（由 Android `init` 单实例托管）。
- `magisk-module/carlife-daemon.sh` — 管理 GPIO38 / `7000000.dwc3` configfs（`mtp.gs0` → `accessory.gs2`）、拉起 `MdMain` 与 `UEventWatch`、同步 A2DP 优先级、将 `sdsdk816` 调至 `renice -16` 防切 App 音频欠载、拉起悬浮球 `com.sd.ball`。
- `magisk-module/system.prop` — 设置 `bluetooth.hfp.client=0`，配合 RRO 覆写包将 `QCOM-BTD` 切换为手机侧角色。
- `magisk-module/service.d/wifi_adb.sh` — 开机自动在 TCP `5555` 启用 Wi-Fi ADB，与 CarLife / CarPlay 共存。

---

## 3. 蓝牙手机角色 RRO 覆写包（`bt-phone-overlay`）

- 包名：`com.tbox.overlay.bluetooth.phone`（安装于 `/data/adb/modules/tbox_carlife/system/vendor/overlay/TboxBtPhoneOverlay.apk`）
- 目标：`com.android.bluetooth`（`isStatic=true, priority=999`）
- 将 `QCOM-BTD` 从车机 Sink 角色切换为标准手机角色（开启 HFP AG / A2DP Source / AVRCP Target / PBAP PSE，关闭 A2DP Sink / AVRCP Controller / HFP Client），使威飒车机可与其配对蓝牙电话。

---

## 4. 盒子端切换工具（`tbox-usb-switch` v1.4）

- 包名：`com.tbox.usbswitch`
- 提供大屏触控 UI：一键切换 **CarPlay / CarLife / USB ADB** 模式、查看/切换 Wi-Fi ADB、实时热切换 **媒体 / 导航 / 通话** 音频路由（USB vs BT），并在“电话音频”项标明推荐 USB（蓝牙模式适用于手机直连车机）。

---

## 5. 设备侧一次性系统调整（非文件型）

- 停用不需要且会弹出“设备未激活”水印的翼卡/SOS 助手：`pm disable-user --user 0 com.ecar.assistantnew`（可随时 `pm enable` 还原）。

---

## 6. 当前实车验证进度与根因结论（2026-11-20 Round 3 → Round 4）

- **已实车通过**：
  - CarLife USB AOA 投屏（1920x720@30fps）、多点触控、切回界面不自动按桌面键；
  - 媒体 48kHz + 导航高德 UID 独立 16kHz TTS 双通道分离，音频全程无卡顿；
  - 方向盘语音键一键唤醒/关闭盒子语音助手（思必驰天琴，555KB 实车麦 PCM、`dropped_bytes=0`、`track_recoveries=0`）；
  - `QCOM-BTD` 零闪断。
- **Round 3 剩余问题根因与 Round 4 修复**：
  - **`call=usb` 对方听不到声音（已根治）**：反编译盒子 `/system/framework/framework.jar` 证实原厂 `AudioTrack.play()` 在通话期间（`sys.phonecall.state=1`）对非 `com.suding.speedplay` 进程直接静默 `return`，导致 `Api28MicBridge` 的车机麦注入 `AudioTrack` 从未真正启动，并诱发 3 次重试后注销活跃 `AudioPolicy` 触发 `audioserver` `SIGSEGV`（`tombstone_02`）。Round 4 改为反射调用 `AudioTrack.startImpl()` 并禁止活跃期注销 `AudioPolicy`，已在桌面实机模拟 `sys.phonecall.state=1` + `uid=0` 录音闭环验证通过。
  - **`call=bt` 经 `QCOM-BTD` 双方无声（已证实硬件/固件级双限制）**：
    1. 反编译盒子 `/vendor/lib/hw/audio.primary.msm8953.so` 证实 `start_input_stream()`（`0xe248`）被原厂硬编码为无条件 `pcm_open(1, 1, PCM_IN)`（`/dev/snd/pcmC1D1c`，CarPlay 虚拟回环），完全不走 `select_devices()` / `BT_SCO_MIC`，因此盒子侧无法经 `QCOM-BTD` HFP SCO 采集车机麦克风；
    2. 威飒车机对 `QCOM-BTD` 发起的虚拟通话 eSCO 在建链 12–24ms 后均主动发送 `HCI_Disconnect (0x13 Remote User Terminated Connection)`。
    - 结论：使用盒子蓝牙电话（手机连盒子）时固定使用 **`call=usb`**（下行 USB + 上行车机麦 USB + 盒子 `sdsdk816` 内置 WebRTC APM 回声消除）；若需原车蓝牙通话，直接将手机连接威飒原车蓝牙并把 `电话音频` 设为 `蓝牙`（免 CarLife 接管）。
