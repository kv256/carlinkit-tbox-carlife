# 工作交接单：`carlinkit-tbox-carlife`

> 更新日期：2026-11-20（对应 Round 4 部署状态）

## 1. 项目与环境概览

- **项目名称**：`carlinkit-tbox-carlife`（主分支 `main`）
- **目标平台**：
  - **盒子**：车连易 Carlinkit AI Box（`CPC200-Tbox`，Qualcomm `msm8953`，Android 9 / API 28，2025.12.18 OTA，Magisk v31.1 Root，SELinux `Permissive`）
  - **车机**：2022 款丰田威飒（DesaySV `G6SA_r8a7796`，Android 8.1.0，原厂 `com.baidu.carlifevehicle`）
  - **上游基线**：`Carlife_on_box v2026.10.17 (d9397d9)`（首个提交 `28b5865`）
- **工具链与密钥**：
  - Android SDK API 28 与 `adb` 分别默认位于仓库同级 `../tools/android-sdk` 与 `../tools/linux/platform-tools/adb`（可通过 `ANDROID_SDK_DIR` / `ADB_BIN` 覆盖）
  - APK 签名库位于仓库内 `.keystore/usbswitch-release.jks`（已 `.gitignore`，切勿删除或提交到版本库）
  - 盒子 Wi-Fi ADB 地址：用 `--serial <盒子IP>:5555` 传入

---

## 2. 仓库结构与 Git 历史

关键提交历史：
- `28b5865` — `chore: import upstream Carlife_on_box v2026.10.17 (d9397d9) baseline`（纯净上游基线，执行 `git diff 28b5865 -- carlife-md-api28/src/` 可审阅全部定制）
- `78b282d` — `feat: CPC200-Tbox (Android 9) & Toyota Venza CarLife customizations`
- `f0bf84a` — `fix(mic): bypass OEM sys.phonecall.state=1 AudioTrack.play() block and prevent stopInput SIGSEGV`
- `aaddabe` — `chore: rename project to carlinkit-tbox-carlife`

核心文件索引：
- 项目说明与定制清单：[README.md](../README.md)、[CUSTOMIZATIONS.md](./CUSTOMIZATIONS.md)
- CarLife 核心服务端：
  - [Api28MicBridge.java](../carlife-md-api28/src/org/carlife/hulab/Api28MicBridge.java)（车机麦克风回传、`startImpl()` 绕过原厂拦截、防 `stopInput` SIGSEGV）
  - [MdMain.java](../carlife-md-api28/src/org/carlife/hulab/MdMain.java)（协议分发、方向盘语音键、防误按桌面键、属性热重载）
  - [BtAudioPolicy.java](../carlife-md-api28/src/org/carlife/hulab/BtAudioPolicy.java)（`QCOM-BTD` A2DP 自动回连抑制与熔断）
  - [AudioPolicySources.java](../carlife-md-api28/src/org/carlife/hulab/AudioPolicySources.java)（媒体 48kHz + 高德/百度/腾讯导航 UID 独立 16kHz TTS）
- Magisk 守护与切换脚本：[carlife-daemon.sh](../magisk-module/carlife-daemon.sh)、[sd-reverse.sh](../magisk-module/system/bin/sd-reverse.sh)
- 构建 / 部署 / 采日志脚本：[build_all.sh](../scripts/build_all.sh)、[deploy.sh](../scripts/deploy.sh)、[pull_logs.sh](../scripts/pull_logs.sh)

---

## 3. 盒子当前部署状态（可直接上车测试）

已完成 **Round 4 最新版部署**（实机 Wi-Fi ADB），当前无需重推或重启即可直接插车实测：
- `/data/adb/carlife/carlife-md-api28.jar`：SHA-256 `b86183ca8f8cbfd1799749fef7db847505c4b99b5e4c7488e7993ed696a67262`
- `/data/adb/modules/tbox_carlife/`：Magisk 模块与静态 RRO `TboxBtPhoneOverlay.apk`（SHA-256 `ecec803f52c89f06129a4089b24fa1464ee2f7274824c73662c493f80675522d`）
- `TboxUsbSwitch` App：已更新至 **v1.4**（`versionCode=5`，电话音频项标明“推荐 USB；蓝牙需手机直连车机”）
- 运行期属性：`persist.tbox.carlife=1`、`media=usb`、`navi=usb`、`call=usb`
- 进程状态：Android `init` 托管的单实例 `carlife-daemon.sh` + `MdMain` + `UEventWatch` 正常待命

---

## 4. 功能与问题状态矩阵

| 模块 / 问题 | 当前状态 | 根因与说明 |
| --- | --- | --- |
| CarLife USB 投屏 (1920x720@30fps) & 多点触控 | ✅ 已实车通过 | AOA 建链稳定 |
| 切回界面误触发桌面键 | ✅ 已实车通过 | `0x1801d`/`0x18025` 仅唤醒不注入 `KEYCODE_HOME` |
| 方向盘语音键唤醒盒子语音助手 | ✅ 已实车通过 | 映射至思必驰天琴，车机麦 555KB 零丢包 |
| `QCOM-BTD` 蓝牙连接稳定性 | ✅ 已实车通过 | USB 模式下关闭 A2DP Sink 自动回连，零闪断 |
| 媒体 48kHz + 导航高德 UID 独立 16kHz TTS | ✅ 本轮无卡顿（待长测） | 需后续多场景长时间听歌/导航/切 App 复核 |
| **`call=usb` 电话对方听不到声音 + 挂断偶发 `audioserver` 崩溃** | 🟡 **已根治并桌面实测通过，待上车实测** | 反编译盒子 `/system/framework/framework.jar` 证实原厂 `AudioTrack.play()` 在 `sys.phonecall.state=1` 时对非 `com.suding.speedplay` 进程直接静默 `return`；Round 4 改为反射调用 `AudioTrack.startImpl()` 并禁止活跃期注销 `AudioPolicy`（根治 Android 9 `AudioPolicyManager::stopInput()` UAF `SIGSEGV`）。桌面实机模拟 `sys.phonecall.state=1` + `uid=0` 录音闭环已 100% 通过且 `audioserver` 零崩溃 |
| **`call=bt` 经 `QCOM-BTD` 双方无声** | ⚫ **确认为硬件/固件级双限制（不再追查）** | 1) 反编译 `/vendor/lib/hw/audio.primary.msm8953.so` 证实 `start_input_stream()`（`0xe248`）硬编码 `pcm_open(1,1,PCM_IN)`，完全不走 `BT_SCO_MIC`；2) 威飒车机对 `QCOM-BTD` 虚拟通话 eSCO 在建链 12–24ms 后均主动发送 `0x13` 断开。**定论**：手机连盒子通话固定使用 `call=usb`；如需原车蓝牙通话，将手机直连威飒车机蓝牙并把 `电话音频` 设为 `蓝牙` |

---

## 5. 下一步待办（Round 4 实车验收）

1. **直接上车测试（无需带电脑）**：
   - 保持 `TboxUsbSwitch` 中 `媒体=USB`、`导航=USB`、`电话=USB`，手机蓝牙连接盒子拨打/接听电话，验证：**双向语音清晰、无回声、挂断后音乐与导航自动恢复且无崩溃**。
   - 长时间播放音乐 + 高德导航播报 + 频繁切换 App，复核是否彻底无音频卡顿。
2. **拉取完整落盘日志复盘**（盒子内置 `savelog` 会跨重启持久保存 Android/内核日志至 `/storage/emulated/0/x7_Log/`）：
   ```bash
   bash scripts/pull_logs.sh --full
   ```
   重点核查：
   - `/cache/carlife/logs/` 中出现 `api28_mic_track_started ... start_mode=startImpl`，且 `dropped_bytes=0`、`track_recoveries=0`；
   - `/data/tombstones/` 无新增 `audioserver` 崩溃记录。
3. **常用开发命令**：
   ```bash
   bash scripts/build_all.sh          # 全量编译 JAR + RRO + TboxUsbSwitch APK
   bash scripts/deploy.sh             # 热更新 JAR + daemon（无需重启）
   bash scripts/deploy.sh --all       # 全量部署含 Magisk 模块与 App（模块更新需重启生效）
   ```

---

## 6. 交接注意事项与红线

- **非破坏性原则**：严禁直接修改盒子 `/system`、`/vendor`、`/boot` 分区，一律通过 `/data/adb/modules/tbox_carlife` 与 `/data/adb/carlife` 部署。
- **兼容性边界**：不要更改既有 Magisk 模块 ID（`tbox_carlife`）、App 包名（`com.tbox.usbswitch` / `com.tbox.overlay.bluetooth.phone`）或持久化属性前缀（`persist.tbox.carlife.*`），避免破坏盒子既有配置与热升级。
- **设备侧一次性状态**：已通过 `pm disable-user --user 0 com.ecar.assistantnew` 停用无用的翼卡/SOS 水印弹窗应用（可随时 `pm enable` 还原）。
