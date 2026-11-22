# `carlinkit-tbox-carlife` 定制与修改总清单（CPC200-Tbox / Android 9）

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

5. `EchoCanceller.java`（Round 5）— `call=usb` 通话的车机麦克风回声消除（纯 Java，16 kHz）：
   - 参考信号取自 `MdMain` 写入 USB 后的 MEDIA 通道 PCM（48 kHz 立体声 → 16 kHz 单声道），按 USB 写入时间 / 麦克风到达时间对齐到同一时间轴；
   - 二值谱匹配自动估计回声延迟（0–800 ms，跨通话记忆）+ 24×8 ms 分块频域 NLMS（前景/背景双滤波器抗双讲）+ AEC2 式相干性残余回声抑制（WOLA）；
   - 仿真（真实语音 + 车内脉冲响应 + 260 ms 延迟）：远端单讲抑制 32.6 dB，近端单讲无损，盒子实测每 8 ms 块约 0.7 ms CPU；
   - 原因：盒子 `sdsdk816` 内置的是 WebRTC 移动版 AECM（`EchoControlMobile`），延迟提示按本地扬声器设计，无法覆盖 USB → 车机 → 扬声器 → 车机麦 → USB 的长链路（CarPlay 模式同样有回音也是同一原因，CarPlay 链路不经过本项目代码）。
   - Round 6（第一次上车数据修正）：车机 CarLife 麦克风本身已做回声抑制（远端开口时麦克风会先降到底噪以下约 10 dB），残留回声与车内底噪同级，线性滤波器无从收敛，旧版延迟估计从未锁定（`estimated_delay_ms=-1`）、抑制率约 0%；`sdsdk816` 随后对很小的麦克风电平做 WebRTC AGC + 降噪，去掉路噪后把这点残留回声放大给了对方。实测回声在 USB 写入后约 520–527 ms 到达，另有偶发的强相关回声突发（车机抑制漏过，相关系数 0.4–0.6）。
   - 新增远端门控残余回声抑制：远端在滤波窗口内活跃时，逐频点判定近端语音（同时高于麦克风底噪、学习到的残余回声电平，且与远端在最相关延迟上的相干性低），其余频点压低（弱/中/强 = −10/−20/−30 dB）并补舒适噪声；初始延迟改为 520 ms（窗口 496–688 ms）；24 个延迟上的远/近相干性同时给出运行期延迟读数 `coh_lag_ms`。
   - 用三通车上录音离线回放（`EchoCanceller` 原样运行，盒子 ART 上输出与桌面逐样点一致，每 8 ms 块约 0.9 ms CPU）：中档残留回声相关性降低 14.5–18 dB、强档 17–24 dB；无远端时近端语音变化 ≤ 1.3 dB，真实双讲中档约 −2 dB、强档约 −4 dB。

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
- 开机自动启动 CarLife（Round 5）：车机 CarLife 只在自身开机时扫描一次 USB（`ConnectService.handleBootReconnect`，按上次连接设备的序列号匹配），之后插入的安卓设备不会自动启动（手机实测同样如此；CarPlay 走设备管理器 `start_carplay` 的独立流程）。原流程中盒子开机约 9.9 s 先以高通调试复合设备（`diag,serial_cdev,rmnet,dpl,adb`）出现，约 19.2 s 才断开并以 CarLife 设备出现，错过车机扫描。现：
  - `sd-reverse.sh` 在脚本最前面判断 CarLife 模式并立即 `setprop sys.usb.configfs 2`，原厂 `iptables`/CPU 限频放到后台执行；USB ADB / CarPlay 模式流程不变；
  - `carlife-daemon.sh` 在 init 创建 gadget 功能后立即接管（开机 120 s 内守护 `sys.usb.configfs=2`，使 `init.msm.usb.configfs.rc:685` 永不绑定调试配置），用 Magisk busybox `uevent` 原生监听 `ACCESSORY=START`（Java `UEventWatch` 作后备），随即发布 CarLife MTP（`18d1:4ee1`，`ro.serialno`），`MdMain` 并行启动且重启不再触碰 USB；收到 AOA 后最多等 4 s 让 `MdMain` 持有 `/dev/usb_accessory` 再切换（车机重试窗口 10×500 ms）；
  - 实测（家中两次重启）：CarLife 设备在开机 10.5–10.9 s 出现，全程无高通调试配置、无断连；日志 `daemon.log` 开机阶段以 `boot up=<秒>` 记录时间线。
  - Round 6：上车内核日志证实车机**确实**在开机后（盒子 13.2–19.7 s）按序列号匹配到盒子并连发 10 次 AOA 握手，但全部丢失：本内核的 `ACCESSORY=START` uevent 由延迟工作队列重新读取 USB 状态后发出，车机在 START 后 10–16 ms 即复位总线，开机时 CPU 繁忙导致工作项晚于复位执行，只看到 `mtp_start_work disconnected`，uevent 从未发出（开机后手动点击时恰好赶在复位前，所以能连上）。守护进程新增内核日志监听：`/dev/kmsg` 中 START 控制请求（`40.35 v0000 i0000 l0` / `mtp_ctrlrequest:1421`）是同步打印且读取前不会丢失；读取经 `cat` + FIFO 交给 busybox `awk`（busybox awk 直接读 `/dev/kmsg` 报 EINVAL），以写入内核日志的标记行区分新旧记录。用 Mac 上 libusb 模拟车机（START 后 2 ms 复位、每 0.7 s 重试 10 次）：旧守护进程 10 次全失败（与车上一致），新守护进程首次即在 START 后约 0.55 s 切到 accessory，会话结束后的下一轮同样有效。
- `magisk-module/carlife-daemon.sh` — 管理 GPIO38 / `7000000.dwc3` configfs（`mtp.gs0` → `accessory.gs2`）、拉起 `MdMain` 与 `UEventWatch`、同步 A2DP 优先级、将 `sdsdk816` 调至 `renice -16` 防切 App 音频欠载、拉起悬浮球 `com.sd.ball`。
- `magisk-module/system/etc/bluetooth/interop_database.conf`（Round 5 实验）— 原厂高通蓝牙兼容性名单 + 针对威飒车机（`88:bd:78` OUI / `VENZA`）的 `INTEROP_DISABLE_CODEC_NEGOTIATION`（强制 CVSD）与 `INTEROP_DISABLE_SNIFF_DURING_SCO`，用于排查 QCOM-BTD 作为 HFP AG 时车机 12–24 ms 断开 eSCO 的问题（iPhone 直连车机通话正常，说明差异在盒子一侧）。
  - Round 6：上车 HCI 日志只有 HFP 建链（`+BRSF: 2407`，已无编解码协商位，覆盖生效），**没有任何 SCO 建链尝试**。`call=bt` 按设计只是不采集通话、交给手机直连车机，盒子侧没有任何组件会经 `QCOM-BTD` 向车机发起通话音频，因此这组覆盖在本次测试中并未被用到。
- 手机蓝牙连接配置（Round 6，`persist.tbox.carlife.bt.phone`）：`sdsdk816` 是盒子第二颗蓝牙芯片（`/dev/ttyHS0`，名称 `AutoKit-TBox`）的完整 goc 蓝牙栈，开机总是以电话 + 音乐 + AVRCP（+ iPhone iAP）回连上次的手机，占用手机的免提通道，车机便无法再连手机电话。它的命令集记录在 `/system/config.ini`（`AT#xx`，写入其命令 pty `/dev/goc_serial`，读端归 `com.carsyso.bluetooth`）。实测（iPhone）：`SE` 断开整台手机（没有只断电话的命令）、`DC<n>` 只连音乐（iPhone 保持 60 s 以上未补连电话，也不连 iAP）、`SC<n>` 在已有音乐连接上补连电话且不断开，`n` 为 `/data/goc/bt_conf.ini` 中 `addrN` 的 N+1。守护进程跟读 `sdsdk816` 的 logcat：`media` 模式下一旦电话连上（`MG3`）就 `SE` + `DC<n>`（每 10 分钟最多 3 次，通话中 `MG4/5/6` 不动作），从 `media` 切回 `all` 时 `SC<n>`（无连接时 `CC<addr>`）。开机实测：`sdsdk816` 回连后 40 ms 内释放电话，3.5 s 后音乐恢复。仅 CarLife 模式生效，CarPlay 模式不受影响。
  - 仅音乐时的静音（Round 6c）：盒子蓝牙应用 `com.carsyso.bluetooth`（`/system/app/XQBluetooth`）在电话连接断开时发送 `AT#VA`（禁止蓝牙音乐），之后只在获得音乐焦点、或「A2DP 播放」时其蓝牙音乐界面可见 / 允许后台播放时才 `AT#VB`；仅音乐模式下它认为手机未连接，于是手机音乐流照常到达但被 `sdsdk816` 丢弃（日志 `A2dp mute!!!!`），表现为只有第一次有声。守护进程在仅音乐模式下，看到 `a2dp_player_start ... start succ` 或 `A2dp mute!!!!` 时补发 `AT#VB`（每 4 s 至多一次）。该应用界面仍会显示「蓝牙已断开」（其连接状态只看电话连接），不影响音乐经 CarLife 播放。
- 开机更早出现 CarLife 设备（Round 6c）：4 次上车开机，盒子在 10.8–11.3 s 被枚举，车机均未发起 AOA；唯一一次车机主动发起时盒子在 10.63 s 被枚举——车机开机扫描恰在盒子 ~10.6–11 s 附近，属时序竞争。现改为：
  - `magisk-module/post-fs-data.sh`（Magisk post-fs-data，~6.6–7.4 s）：CarLife 模式下（持久属性此时尚未加载，用 `magisk resetprop -p` 从存储读取）自建 `g1`、发布 CarLife MTP（`18d1:4ee1`，`ro.serialno`），后台守护至守护进程接管（`$RUN/usb.daemon`）：init 把 `sys.usb.configfs` 置 1 时立即改回 2 并设 `sys.usb.config=carlife`，恢复被 init「on boot」改写的序列号/厂商/产品字符串，必要时重绑；端口未连接时每 250 ms 重写一次 `ssusb/mode=peripheral`（早写入时 VBUS/充电检测未就绪，控制器不会开始会话）；
  - `sd-reverse.sh` / 守护进程：CarLife 模式改用 `sys.usb.config=carlife` 与 `persist.vendor.usb.config=carlife`（不匹配任何 init 触发器；`none` 会在 init 把 `configfs` 置 1 时触发 `UDC none` 并删除 `configs/b.1/f*`）；
  - 守护进程发现早期设备完好时不再重绑（`kept=early_gadget`），只重写字符串；开机后第一个内核日志监听器从缓冲区起点接收 START（车机可能在守护进程 ~9.3 s 启动前就已发起）。
  - 家中实测：设备 8.1–8.3 s 被枚举（原 10.6–11.3 s），全程无断开；模拟车机在 8.4 s 起发 AOA，首个 START 被捕获，`MdMain` 就绪后 12.7 s 切换成功（车机 10 次重试窗口内）。
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

## 4.5 CarPlay 风格车机桌面（`tbox-car-launcher` v1.2）

- 包名：`com.tbox.carlauncher`（通过 Magisk 非破坏性挂载至 `/system/priv-app/TboxCarLauncher/TboxCarLauncher.apk`，配套 `/system/etc/permissions/privapp-permissions-tboxcarlauncher.xml`、静态 RRO 覆盖包 `/system/vendor/overlay/TboxNoNavBarOverlay.apk` 及开机伴生桥 `/data/adb/service.d/tbox_vd_input.sh`；停用系统原生 `com.android.launcher3` 并通过 RRO 将 `config_showNavigationBar=false` 且 `navigation_bar_width/height=0dp`，彻底根治原厂 `SystemUI` `refleshNavigationBar()` 在左边缘滑入时重建 `SdNavigationBarView` 的问题）。
- **方案 B 精确 `1920 × 720` 浮岛分区（弱化边框 / 矢量图标）**：
  - 左侧 Dock：`112 × 702`（数字时钟长按进设置、三弧同心矢量 Wi-Fi 与蓝牙连接图标、可选 CPU 实时温度胶囊、4 个长按可自定义快捷应用与激活指示条、底部精简为返回键「短按返回 / 长按打开 16:9 最近任务切换器」+ 应用抽屉键「短按切换抽屉 / 长按唤醒语音助手」）；
  - 中间严格 16:9 主显示区：**`1248 × 702`**（`20px` 圆角微光边框；应用抽屉采用不透明底色遮罩彻底隔离底层 `SurfaceView` 防止亮色应用透出白边，顶栏极简为「全部应用 + 齿轮设置图标」，纯图标 `< / >` 翻页；内置 16:9 最近任务切换器 `RecentsView`，支持 `TaskSnapshot` 实时缩略图预览、点选直达主区、单任务关闭与一键全部清理；全套长按菜单、显示缩放 DPI 网格、桌面设置、Dock 槽位选择与卸载确认弹窗统一采用深色玻璃驾舱弹窗 `CockpitDialog`）；
  - 右上导航卡片：`512 × 345`（空闲态展示日期 + 极简居中「回家 / 去公司」按钮 + 打开地图/搜目的地；支持在桌面设置中选择将 CPU 实时温度显示在导航卡片右上角、左侧 Dock 或关闭；自动读取高德车机版内已保存的家/公司地址 `KEY_TYPE=10045/10040`，若高德未设置则通过 `10046/12005` 反馈并提示在高德中设置；导航开始后自动切换为矢量转向箭头 + 距离 + 路名 + 剩余里程/时间/ETA + 进度条）；
  - 右下音乐卡片：`512 × 345`（封面动态取色渐变背景、歌名/歌手/来源角标、进度条与大尺寸播控；`VdInputBridge` 实时监听 `sdsdk816` 串口 AT 日志 `[MU0..4]/[MY]/[MA]/[MB]/[MI...]/[MT...]` 获取真实 A2DP 连接与曲目/进度状态，断连立即清空过期曲目并禁止假进度条推进；切歌/播放/暂停直接向 `/dev/goc_serial` 发送 `AT#MS/AT#MB/AT#MD/AT#ME`，彻底解决仅音乐模式 `HFP=0` 下原厂 `TechBTMusicManager` 拦截播控的问题，同时兼顾盒子本机媒体应用）。
- **16:9 强约束、每应用独立缩放（DPI）与多点触控机制**：
  - 由 `uid=1000 (system)` 伴生 Binder 服务 `tbox_vd_bridge`（`VdInputBridge`）按目标缩放档位维护多槽位 `VirtualDisplay` 池（默认 `238 dpi` 为 `1248 × 702`，如 `160 dpi` 档位自动扩展虚拟分辨率至 `1856 × 1044 @ 238 dpi`），切换前先解绑旧 `VirtualDisplay` 并通过 `SurfaceHolder.setFixedSize(vdW, vdH)` 调整缓冲尺寸，再绑定目标虚拟屏由 `SurfaceFlinger` 硬件等比缩放至 `1248 × 702` 视口，同时在 `AppViewportManager` 按 `vdW/1248` 与 `vdH/702` 等比映射多点触控坐标；
  - 采用虚拟分辨率缩放方案而非仅修改 `densityDpi`，因此对读取主屏 `Application` 密度或硬编码 `1080p` 像素布局的车机/TV 应用（如 `Bilibili Things` `com.bilibili.bilithings`，已默认预设 `160 dpi` 紧凑模式）同样生效，且切回高德导航（`238 dpi`）时原虚拟屏常驻后台零重载；
  - 应用抽屉长按任意图标 →「调整显示缩放 / DPI」，或长按左上角时钟 →「调整当前应用显示缩放」，可在 `260 / 238(默认) / 220 / 200 / 180 / 160 / 140 / 120 dpi` 间单独调节并立即重载生效；
  - 全局 `TaskStackListener` 监听 Display 0，任何外部拉起或请求全屏的 App 都会被即时 `moveStackToDisplay` 迁入对应缩放档位的 16:9 虚拟屏；同时对在虚拟屏内拉起的独立 `PackageInstaller` 安装任务自动通过 `moveTaskToStack` 提升到 Display 0 原生渲染（并在转发触摸时剥离 `FLAG_WINDOW_IS_OBSCURED/PARTIALLY_OBSCURED`），彻底避免 Android 9 安装器防劫持机制在 `ACTION_DOWN` 焦点切换触发 `onPause()` 禁用「安装」按钮的问题。

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
