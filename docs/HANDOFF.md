# 工作交接单：`carlinkit-tbox-carlife`

> 更新日期：2026-11-20（对应 Round 6 部署状态：开机 AOA 内核日志监听 + 远端门控残余回声抑制）

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

已完成 **Round 5 部署**（实机 Wi-Fi ADB），当前无需重推或重启即可直接插车实测：

- `/data/adb/carlife/carlife-md-api28.jar`：SHA-256 `784fcb99e73a54f0c65819d89961463a8ea57c339325c8279d1d899ed339fbe7`（新增 `EchoCanceller`；Round 4 版本备份于同目录 `carlife-md-api28.jar.round4.bak`）
- 回声消除：`persist.tbox.carlife.aec` 未设置（= 默认 `2` 中档），`persist.tbox.carlife.aec.dump=1`（首轮上车采集 PCM，验收后可关）
- `/data/adb/modules/tbox_carlife/`：Magisk 模块与静态 RRO `TboxBtPhoneOverlay.apk`（SHA-256 `ecec803f52c89f06129a4089b24fa1464ee2f7274824c73662c493f80675522d`）
- `TboxUsbSwitch` App：已更新至 **v1.5**（`versionCode=6`，新增“通话回声消除 关/弱/中/强”；使用原签名库 `.keystore/usbswitch-release.jks` 签名，证书 SHA-256 `e2386dc6…8dd1`）
- 运行期属性：`persist.tbox.carlife=1`、`media=usb`、`navi=usb`、`call=usb`
- 进程状态：Android `init` 托管的单实例 `carlife-daemon.sh` + `MdMain` + `UEventWatch` 正常待命

---

## 4. 功能与问题状态矩阵

| 模块 / 问题                                                     | 当前状态                                   | 根因与说明                                                                                                                                                                                                                                                                                                                                                                                           |
| --------------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CarLife USB 投屏 (1920x720@30fps) & 多点触控                    | ✅ 已实车通过                              | AOA 建链稳定                                                                                                                                                                                                                                                                                                                                                                                         |
| 切回界面误触发桌面键                                            | ✅ 已实车通过                              | `0x1801d`/`0x18025` 仅唤醒不注入 `KEYCODE_HOME`                                                                                                                                                                                                                                                                                                                                                      |
| 方向盘语音键唤醒盒子语音助手                                    | ✅ 已实车通过                              | 映射至思必驰天琴，车机麦 555KB 零丢包                                                                                                                                                                                                                                                                                                                                                                |
| `QCOM-BTD` 蓝牙连接稳定性                                       | ✅ 已实车通过                              | USB 模式下关闭 A2DP Sink 自动回连，零闪断                                                                                                                                                                                                                                                                                                                                                            |
| 媒体 48kHz + 导航高德 UID 独立 16kHz TTS                        | ✅ 本轮无卡顿（待长测）                    | 需后续多场景长时间听歌/导航/切 App 复核                                                                                                                                                                                                                                                                                                                                                              |
| **`call=usb` 电话对方听不到声音 + 挂断偶发 `audioserver` 崩溃** | 🟡 **已根治并桌面实测通过，待上车实测**    | 反编译盒子 `/system/framework/framework.jar` 证实原厂 `AudioTrack.play()` 在 `sys.phonecall.state=1` 时对非 `com.suding.speedplay` 进程直接静默 `return`；Round 4 改为反射调用 `AudioTrack.startImpl()` 并禁止活跃期注销 `AudioPolicy`（根治 Android 9 `AudioPolicyManager::stopInput()` UAF `SIGSEGV`）。桌面实机模拟 `sys.phonecall.state=1` + `uid=0` 录音闭环已 100% 通过且 `audioserver` 零崩溃 |
| **`call=usb` 对方听到回音（CarPlay 模式同样存在）**              | 🟡 **Round 5 已实现回声消除，待上车实测** | `sdsdk816` 内置 WebRTC AECM 延迟提示固定且算法偏弱，无法消除车机扬声器 → 车机麦的长链路回声。Round 5 在 `Api28MicBridge` 注入车机麦之前用 `EchoCanceller` 消除（参考信号 = 实际写给车机的 MEDIA PCM）。CarPlay 链路不经过本项目，不受影响 |
| **`call=bt` 经 `QCOM-BTD` 双方无声**                            | ⚫ **确认为硬件/固件级双限制（不再追查）** | 1) 反编译 `/vendor/lib/hw/audio.primary.msm8953.so` 证实 `start_input_stream()`（`0xe248`）硬编码 `pcm_open(1,1,PCM_IN)`，完全不走 `BT_SCO_MIC`；2) 威飒车机对 `QCOM-BTD` 虚拟通话 eSCO 在建链 12–24ms 后均主动发送 `0x13` 断开。**定论**：手机连盒子通话固定使用 `call=usb`；如需原车蓝牙通话，将手机直连威飒车机蓝牙并把 `电话音频` 设为 `蓝牙`                                                    |

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

### Round 5 回声消除验收

1. 拨打电话后请对方说话、自己保持安静，确认对方不再听到回音；再双方同时说话，确认自己声音不被吞字。
2. 若仍有回音，在 TboxUsbSwitch 把“通话回声消除”调到“强”；若自己声音断续，调到“弱”。均为下一通电话生效。
3. 拉日志：`bash scripts/pull_logs.sh`，并拉取 PCM：`adb pull /cache/carlife/aec/`。日志中 `api28_aec` 事件包含 `estimated_delay_ms`、`erle_db`、`cpu_us_per_block` 等指标。
4. 回滚：`su -c 'cp /data/adb/carlife/carlife-md-api28.jar.round4.bak /data/adb/carlife/carlife-md-api28.jar'` 后重拉守护进程，或直接 `setprop persist.tbox.carlife.aec off`。

### Round 5 QCOM-BTD 蓝牙通话排查（实验）

- 已证实车机可同时处理 CarLife 与蓝牙电话（iPhone 蓝牙电话 + S20 FE CarLife 正常），因此 eSCO 被断开的原因在盒子侧（QCOM-BTD 作为 HFP AG 的行为与 iPhone 不同），并非车机能力限制。
- 已部署：Magisk 覆盖 `interop_database.conf`（对威飒强制 CVSD、SCO 期间禁用 sniff），并开启 `persist.bluetooth.btsnoopenable=true`（HCI 日志在 `/data/misc/bluetooth/logs/`，蓝牙重启会轮换为 `.last`）。
- 测试：TboxUsbSwitch 把“电话音频”设为“蓝牙”，手机连接盒子拨打电话；观察车机是否进入通话界面、连接是否保持（即使暂时无声也属预期，盒子侧采集通路尚未实现）。测完不要开关蓝牙，之后执行 `bash scripts/pull_logs.sh`（已包含 HCI 日志与 AEC PCM）。
- 还原：删除 `/data/adb/modules/tbox_carlife/system/etc/` 后重启；`setprop persist.bluetooth.btsnoopenable false`。

### Round 5 开机自动启动 CarLife（待上车验证）

- 根因与改动见 CUSTOMIZATIONS.md「开机自动启动 CarLife」。盒子现在开机约 10.5–10.9 s 以 CarLife 设备出现（原 19.2 s），且不再出现高通调试设备。
- 验证：上次以盒子连接 CarLife 后熄火，再次启动车辆，观察 CarLife 是否自动弹出；之后 `bash scripts/pull_logs.sh`，查看 `daemon.log` 中 `boot up=` 时间线：`AOA_START_MATCHED` 出现在 ~11–15 s 且无人操作，即为赶上了车机开机扫描。若未赶上，说明车机扫描早于盒子能出现的最早时间（约 10 s）。
- 回滚：`/data/adb/carlife/carlife-daemon.sh.round5.bak` 与 `/data/adb/carlife/sd-reverse.sh.round5.bak`（后者覆盖回 `/data/adb/modules/tbox_carlife/system/bin/sd-reverse.sh`）后重启。

### Round 6（第一次上车结果与修正，待上车验证）

上车结果：蓝牙通话不通；`call=usb` 中/强档仍有回音；开机不自动启动 CarLife。分析与改动见 CUSTOMIZATIONS.md 各条「Round 6」。

- 已部署（盒子已处于 CarLife 模式）：
  - `/data/adb/carlife/carlife-daemon.sh` SHA-256 `ebd04951be3e9eb5ace9b33a1c55d10b31b0e2329ab3d3f8ea332892ea338908`（备份 `carlife-daemon.sh.round5b.bak`）；
  - `/data/adb/carlife/carlife-md-api28.jar` SHA-256 `1d93576497754c7c51d3b954ed42b48d7c0f104ee8d0f555bdc9616688ecfaa0`（备份 `carlife-md-api28.jar.round5b.bak`）；
  - `persist.tbox.carlife.aec=3`（用户上次设置）、`aec.dump=1` 保持；AEC 转储目录改为 `<序号>-<时间>`（盒子开机时钟为 2009 年，旧的按时间命名会误删新转储）。
- 验证开机自动启动：以盒子连 CarLife 后熄火，再启动车辆。`daemon.log` 应出现 `AOA_START_MATCHED KMSG uptime_s=13.x`（无人操作）。
- 验证回声：先用中档（TboxUsbSwitch“通话回声消除=中”），请对方说话、自己安静，再双方同时说话；`api28_aec` 日志新增 `gate_pct`（远端门控时间占比）、`gate_pass_pct`（门控期间判为近端语音的频点占比）、`coh_lag_ms`/`coh_max`（最相关延迟，正常应在 ~520 ms 附近，`coh_max` ≥ 0.2 才有意义）。
- 蓝牙通话：结论见上；要让“手机连盒子、盒子用 QCOM-BTD 把通话送到车机”成立，需要在 `QCOM-BTD` 的 HFP AG 上模拟一路真实通话（`+CIEV` 通话指示 + `AT+CLCC` 列出通话；车机对虚拟通话 `AT+CLCC` 返回空列表的连接会在 12–24 ms 内断开 eSCO）并绕过音频 HAL 采集（`tinymix INTERNAL_BT_SCO` + `tinycap`/`tinyplay`），属于独立的新工作。目前请继续用 `call=usb`。
- 回滚：`cp /data/adb/carlife/carlife-daemon.sh.round5b.bak /data/adb/carlife/carlife-daemon.sh`、`cp /data/adb/carlife/carlife-md-api28.jar.round5b.bak /data/adb/carlife/carlife-md-api28.jar` 后重启。

### Round 6b（第二次上车结果与修正，待上车验证）

- 上车结果：`call=usb` 回音已听不到（通话质量待继续测）；蓝牙经盒子通话仍不可用（预期）；开机自动启动未成功——本次车机在 11.0 s 识别盒子后**没有发起任何 AOA 握手**（与上次连发 10 次不同），属于车机侧"上次连接"记录不满足条件，盒子侧无从触发。iPhone 蓝牙通话 + 三星 CarLife 开机自动启动可共存（用户确认），因此不是 iPhone 直连车机导致。
- 新增「手机蓝牙」连接配置（见 CUSTOMIZATIONS.md）：`persist.tbox.carlife.bt.phone=media` 时盒子只连手机音乐，电话留给车机；已在家用 iPhone 验证 `all ↔ media` 双向切换与开机生效。当前盒子已设为 `media`。
- 已部署：`carlife-daemon.sh` SHA-256 `fa2cfd4b5c38f958c1f62d8bba37ebec6b8d3eb4126dc94862d408393f523482`（备份 `carlife-daemon.sh.round6.bak`）；TboxUsbSwitch **v1.6**（`versionCode=7`，新增「手机蓝牙 音乐+电话 / 仅音乐」）。
- 上车验证：
  1. 车机蓝牙里把 iPhone 设为只连电话；盒子保持「手机蓝牙=仅音乐」。启动后确认音乐经 CarLife 播放、电话由车机原车蓝牙接打。`daemon.log` 应有 `BT_PHONE_MEDIA_ONLY`。若车机首次没连上 iPhone 电话，在车机上手动点一次连接。
  2. 开机自动启动（受控测试，同一趟内完成）：用盒子连上 CarLife（手动即可）→ 盒子不拔、CarLife 保持连接时熄火 → 等车机完全关闭约 1 分钟 → 再启动。`daemon.log` 中该次开机应出现 `AOA_START_MATCHED KMSG uptime_s≈13`；若车机依旧未发起握手（内核日志无 `mtp_ctrlrequest:1421`），则为车机侧条件问题。
- 回滚：`cp /data/adb/carlife/carlife-daemon.sh.round6.bak /data/adb/carlife/carlife-daemon.sh` 后重启；或仅 `setprop persist.tbox.carlife.bt.phone all`。

### Round 6c（第三次上车结果与修正，待上车验证）

- 上车结果：音乐与电话已可分别连接；开机仍未自动拉起 CarLife（4 次开机车机均未发起 AOA）；仅音乐模式下盒子蓝牙音乐界面显示已断开且只有第一次有声。分析与改动见 CUSTOMIZATIONS.md「开机更早出现 CarLife 设备」「仅音乐时的静音」。
- 已部署：`carlife-daemon.sh` SHA-256 `9a669ccb57c99e747093315b614e9c3e6059f124b396f37f99befecba42ed581`；Magisk 模块新增 `post-fs-data.sh`（SHA-256 `257a2ea74d831ad9d07a2ad5e29204a354b3b9e8cbd4f56ce5864ad03b8d900a`）与新版 `sd-reverse.sh`（`0fa1463ecd7f819eca15b2209bddc643800e06a37ccd40a5ca2112086a214184`）。备份：`/data/adb/carlife/carlife-daemon.sh.round6b.bak`、`/data/adb/carlife/sd-reverse.sh.round6b.bak`。
- 上车验证：同一趟内用盒子连 CarLife → 保持连接熄火 → 等约 1 分钟 → 启动。`daemon.log` 应有 `EARLY_USB_ATTACHED`（~8 s）与 `AOA_START_MATCHED KMSG uptime_s≈10–13`（无人操作）。仅音乐模式下放歌、暂停、再放，确认一直有声，日志有 `BT_PHONE_MUSIC_UNMUTED`。
- 回滚：删除 `/data/adb/modules/tbox_carlife/post-fs-data.sh`，并把两个 `.round6b.bak` 拷回（`sd-reverse.sh` 拷到 `/data/adb/modules/tbox_carlife/system/bin/`）后重启。

## 6. 交接注意事项与红线

- **非破坏性原则**：严禁直接修改盒子 `/system`、`/vendor`、`/boot` 分区，一律通过 `/data/adb/modules/tbox_carlife` 与 `/data/adb/carlife` 部署。
- **兼容性边界**：不要更改既有 Magisk 模块 ID（`tbox_carlife`）、App 包名（`com.tbox.usbswitch` / `com.tbox.overlay.bluetooth.phone`）或持久化属性前缀（`persist.tbox.carlife.*`），避免破坏盒子既有配置与热升级。
- **设备侧一次性状态**：已通过 `pm disable-user --user 0 com.ecar.assistantnew` 停用无用的翼卡/SOS 水印弹窗应用（可随时 `pm enable` 还原）。
