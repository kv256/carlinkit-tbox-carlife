# carlinkit-tbox-carlife

将**车连易 Carlinkit AI Box（`CPC200-Tbox`，高通 `msm8953` / Android 9 / API 28）**改造为支持**原生百度 CarLife USB AOA 投屏**与 **CarPlay 风格 `1920×720` 驾舱桌面**的非破坏性开源系统定制方案。

基于上游 [`Carlife_on_box` (`v2026.10.17` / `d9397d9`)](https://github.com/SadykovIV/Carlife_on_box) 深度移植至 Android 9（API 28），并在 **2022 款丰田威飒（DesaySV `G6SA_r8a7796` / 原厂 CarLife 车机）** 上完成全链路实车打磨；同时保持对原厂 **CarPlay** 与 **USB ADB** 模式的无损切换。

> **非破坏性设计**：所有定制组件均通过 **Magisk 模块 + `/data/adb/carlife`** 挂载运行，**不修改原厂 `/system` 与 `/vendor` 分区**。随时可通过内置的 `TboxUsbSwitch` 切回原厂 CarPlay / USB ADB，或在 Magisk 中一键卸载还原。

---

## 核心特性

### 1. 原生 CarLife USB AOA 投屏与音频栈 (`carlife-md-api28`)
- **高清流畅投屏**：`1920×720` 原生比例 H.264 硬编（`OMX.qcom.video.encoder.avc` + EGL 零拷贝），支持桌面设置内一键切换 **30 fps / 60 fps**，并支持完整多点触控与方向盘语音键唤醒盒子语音助手。
- **双路音频分流**：媒体音频（48 kHz 立体声）与导航播报（16 kHz 独立 TTS，自动识别高德/百度/腾讯地图 UID）分离路由，避免导航打断音乐或卡顿。
- **车机麦克风回传与通话回声消除（AEC）**：
  - 绕过盒子原厂 `AudioTrack.play()` 通话拦截（`startImpl()`），避免 `audioserver` 崩溃；
  - 纯 Java 实现的 16 kHz 分块频域 NLMS + 远端门控残余回声抑制（`EchoCanceller`，关/弱/中/强可调），解决 `call=usb` 长链路回音问题。
- **蓝牙音频与手机双连优化**：
  - 自动抑制 `QCOM-BTD` 6 秒 A2DP 闪断循环；
  - 支持「手机蓝牙 = 仅音乐」模式（`persist.tbox.carlife.bt.phone=media`），让手机音乐走盒子、通话直连车机原车蓝牙，并自动解除盒子蓝牙 App 静音拦截。
- **三模式无损切换 (`TboxUsbSwitch`)**：大屏触控一键切换 **CarLife / CarPlay / USB ADB**，热切换媒体/导航/电话音频通道与回声消除强度；CarLife 模式下自动隐藏无用的 CarPlay 中间件（`MainAiBox`）消除误弹窗。

### 2. CarPlay 风格 `1920×720` 驾舱桌面 (`TboxCarLauncher`)
专为宽屏车机（`1920×720`）设计的三栏浮岛布局，彻底消除原厂左侧滑入导航栏遮挡：
- **左侧 Dock（`112×702`）**：时钟（长按进桌面设置）、Wi-Fi/蓝牙状态、可选实时 CPU 温度显示、4 个可长按自定义快捷应用、返回键（**短按返回 / 长按打开 16:9 最近任务切换器**）、应用抽屉键（**短按切换 / 长按唤醒语音助手**）。
- **中间严格 16:9 主显示区（`1248×702`）**：
  - 通过 `uid=1000` 伴生桥 `VdInputBridge` 维护多槽位虚拟屏池，**强约束所有第三方 App 运行在 16:9 视口内**；
  - **单应用独立缩放（DPI）**：通过虚拟分辨率 + `SurfaceFlinger` 硬件等比缩放实现，支持 `Bilibili Things` 等 TV/车机 App 单独调缩放（不互相影响、切换零重载）；
  - **零焦点冲突体验**：通过原生 `IWindowSession.updateTapExcludeRegion` 消除 Android 9 跨屏“点两下才生效”的焦点乒乓，完整兼容**微信输入法**等悬浮键盘全屏输入，应用退出时自动回到应用抽屉不黑屏；
  - 自动将 `PackageInstaller` 安装界面提升至主屏原生渲染，确保安装按钮单次点击即生效。
- **右侧双卡片（`512×345` × 2）**：
  - **高德车机版实时导航卡片**：空闲态显示日期 + 一键「回家 / 去公司 / 搜目的地」，导航中实时渲染矢量转向箭头、路名、剩余里程与 ETA；
  - **双源音乐卡片**：实时解析 `sdsdk816` 蓝牙串口曲目/进度与本机媒体通知，支持专辑封面动态取色渐变与直接 AVRCP 播控。

---

## 系统架构概览

```text
┌───────────────────────────┐         USB AOA (18d1:4ee1 -> 18d1:2d00)         ┌─────────────────────────────────────────┐
│    车机端 (CarLife HU)     │ ◄─────────────────────────────────────────────► │         Carlinkit CPC200-Tbox           │
│  H.264 解码 / 触控 / 麦    │   Video(H.264) + Media(48k) + TTS(16k) + Mic    │        (msm8953 / Android 9 / Magisk)   │
└───────────────────────────┘                                                  │                                         │
                                                                               │  [post-fs-data.sh / carlife-daemon.sh]  │
                                                                               │   └─ USB configfs + AOA 监听 + MdMain   │
                                                                               │                                         │
                                                                               │  [TboxCarLauncher + VdInputBridge]      │
                                                                               │   ├─ Display 0: 1920x720 驾舱桌面        │
                                                                               │   └─ VirtualDisplays: 16:9 多DPI应用池  │
                                                                               └─────────────────────────────────────────┘
```

---

## 适配环境

| 项目 | 已验证配置 |
| --- | --- |
| 盒子硬件 | 车连易 Carlinkit AI Box (`CPC200-Tbox`, Qualcomm `msm8953`) |
| 盒子固件 | Android 9 / API 28（2025.12.18 OTA），Magisk v27+ / v31.1 Root |
| 已实车验证车机 | 2022 款丰田威飒（DesaySV `G6SA_r8a7796`，Android 8.1 原厂 CarLife） |
| 其它 CarLife 车机 | 协议层遵循标准百度 CarLife USB AOA；非 1920×720 车机桌面布局会按比例自适应 |

---

## 快速开始

详细的新手从零 Root（含 `boot.img` 提取、`fastboot flash boot` 刷入 Magisk）到写入定制系统的完整图文步骤，请阅读 **[完整 Root 与部署指南 (`docs/DEPLOY.md`)](docs/DEPLOY.md)**。

### 方式 A：一键刷入 Magisk 模块包（最简单，推荐）
1. 完成盒子 Root（参见 [docs/DEPLOY.md](docs/DEPLOY.md) 第 2 节，通过 `fastboot flash boot magisk_patched.img` 刷入）；
2. 获取或本地生成单文件安装包 `tbox_carlife-magisk.zip`：
   ```bash
   # 如自行构建打包（需 JDK 8+ 与 Android SDK API 28）：
   bash scripts/package_magisk_zip.sh
   # 生成路径：build/tbox_carlife-magisk.zip
   ```
3. 将 `tbox_carlife-magisk.zip` 传至盒子，在 **Magisk App → 模块 → 从本地安装** 中刷入并重启（或电脑执行）：
   ```bash
   adb push build/tbox_carlife-magisk.zip /sdcard/Download/
   adb shell "su -c 'magisk --install-module /sdcard/Download/tbox_carlife-magisk.zip'"
   adb reboot
   ```
4. 重启后打开桌面中的 **`USB模式切换`（TboxUsbSwitch）**，切换到 **CarLife 模式** 并重启即可插车使用。

### 方式 B：开发者源码编译与 ADB 原子部署
依赖：`javac`（JDK 8+）、Android SDK API 28（可通过 `ANDROID_SDK_DIR` 指定，默认 `../tools/android-sdk`）、`adb`（可通过 `ADB_BIN` 指定）。

```bash
# 1. 全量编译 JAR + RRO Overlays + TboxUsbSwitch + TboxCarLauncher
bash scripts/build_all.sh

# 2. 完整部署到已 Root 的盒子（Wi-Fi ADB 或 USB ADB）
bash scripts/deploy.sh --serial <盒子IP>:5555 --all

# 3. 日常热更新（仅更新 CarLife 核心服务，无需重启）
bash scripts/deploy.sh --serial <盒子IP>:5555

# 4. 仅更新车机桌面
bash scripts/deploy.sh --serial <盒子IP>:5555 --launcher

# 5. 拉取实车运行与 USB 诊断日志到 logs/<时间戳>/
bash scripts/pull_logs.sh --serial <盒子IP>:5555
```

---

## 目录结构

```text
carlinkit-tbox-carlife/
├── carlife-md-api28/            # Android 9 (API 28) CarLife USB AOA 服务端 (carlife-md-api28.jar)
├── tbox-car-launcher/           # CarPlay 风格 1920x720 驾舱桌面 + 无导航栏静态 RRO 覆写包
├── tbox-usb-switch/             # 盒子端模式切换 / 音频路由 / AEC / 蓝牙模式设置 App
├── bt-phone-overlay/            # 静态 RRO 覆写包 (切换 QCOM-BTD 为 HFP AG 手机角色)
├── magisk-module/               # Magisk 非破坏性挂载、开机早期 USB 发布与守护脚本
│   ├── post-fs-data.sh          # 开机早期发布 CarLife USB 标识 + dwc3 诊断抓包
│   ├── carlife-daemon.sh        # 单实例 USB AOA / 蓝牙 / MdMain 守护进程
│   ├── service.d/wifi_adb.sh    # 开机常驻 Wi-Fi ADB (TCP 5555)
│   ├── service.d/tbox_vd_input.sh # 16:9 虚拟屏与输入桥守护进程 (uid=1000)
│   └── system/bin/sd-reverse.sh # CarLife / CarPlay / USB ADB 三模式分发入口
├── scripts/
│   ├── build_all.sh             # 一键编译全部组件
│   ├── package_magisk_zip.sh    # 一键打包标准 Magisk 卡刷包 (build/tbox_carlife-magisk.zip)
│   ├── deploy.sh                # 开发者 ADB 原子热部署脚本
│   └── pull_logs.sh             # 拉取实车日志与内核转储
└── docs/
    ├── DEPLOY.md                # 完整 Root (fastboot) + Magisk 刷入 / 源码部署 / 还原指南
    ├── CUSTOMIZATIONS.md        # 详细定制清单与上游差异技术记录
    └── HANDOFF.md               # 阶段实车排查记录与验收备忘
```

> **审查与上游差异**：首次提交 `28b5865` 为纯净上游基线 `Carlife_on_box v2026.10.17`，执行 `git diff --stat 28b5865 -- carlife-md-api28/src/` 即可清晰查看对核心服务端的全部改动。

---

## 运行期属性配置

多数常用选项已集成在 **`TboxUsbSwitch`** 与 **桌面设置（长按左上角时钟）** 中，也可通过 `setprop` 手动调整：

| 属性 | 可选值 | 说明 |
| --- | --- | --- |
| `persist.tbox.carlife` | `1` / `0` | `1` = CarLife AOA 模式；`0` = 原厂 CarPlay（重启生效） |
| `persist.sdcarplay.reverse.disable` | `1` / `0` | `1` = USB ADB 调试模式（重启生效） |
| `persist.tbox.carlife.fps` | `30` / `60` | CarLife 投屏目标帧率（默认 `30`，桌面设置可切，下次重连生效） |
| `persist.tbox.carlife.audio.media` | `usb` / `bt` | 媒体音频走 CarLife USB (48 kHz) 或蓝牙 |
| `persist.tbox.carlife.audio.navi` | `usb` / `bt` | 导航播报走 CarLife 独立 TTS (16 kHz) 或蓝牙 |
| `persist.tbox.carlife.audio.call` | `usb` / `bt` | 电话音频走 CarLife USB（推荐）或交由手机直连车机原车蓝牙 |
| `persist.tbox.carlife.navi.pkgs` | 逗号分隔包名 | 额外需要路由到导航 TTS 通道的导航 App 包名 |
| `persist.tbox.carlife.aec` | `off` / `1` / `2` / `3` / `linear` | `call=usb` 通话回声消除强度（默认 `2` 中档），下一通电话生效 |
| `persist.tbox.carlife.aec.dump` | `0` / `1` | `1` = 保存最近 3 通电话的 AEC PCM 诊断音频至 `/cache/carlife/aec/` |
| `persist.tbox.carlife.bt.phone` | `all` / `media` | 手机连盒子蓝牙模式：`all` = 音乐+电话；`media` = 仅音乐（电话留给原车蓝牙） |

---

## 已知限制与说明

1. **开机自动拉起 CarLife**：部分原厂车机（如威飒）仅在开机瞬间做一次 USB 扫描，若未能自动弹起，手动点击车机 CarLife 图标即可秒连；项目已内置 `dwc3` 开机 USB 抓包日志（`/cache/carlife/logs/boot_usb_trace.log`）用于持续优化各车型开机时序。
2. **`call=bt` 经盒子蓝牙转发通话受硬件限制**：原厂音频 HAL（`audio.primary.msm8953.so`）硬编码采集通路且不走 `BT_SCO_MIC`；手机连盒子通话请使用 `call=usb`（已带 AEC），如需使用原车蓝牙通话请将 `手机蓝牙` 设为 `仅音乐` 并让手机电话直连车机。
3. **不包含原厂固件与签名密钥**：仓库内不包含任何 OEM 固件镜像、APK、Android SDK 或签名密钥（`.keystore/` 已加入 `.gitignore`，首次本地构建会自动生成本地自签证书）。

---

## 致谢、许可与免责声明

- **上游与参考项目**：感谢 [`Carlife_on_box`](https://github.com/SadykovIV/Carlife_on_box) 提供的优秀 CarLife MD 实现基线，以及 `CarLife-MD-Lib`、`Apollo DuerOS`、AOSP 等开源社区资料（详见 [carlife-md-api28/THIRD_PARTY_NOTICES.md](carlife-md-api28/THIRD_PARTY_NOTICES.md)）。
- **开源协议**：本项目原创及衍生源码基于 **[Apache License 2.0](LICENSE)** 开源。第三方协议参考与外部工具保留其原有许可协议。
- **安全与免责声明**：本工具仅供个人学习与折腾交流。刷写 `boot` 分区与系统模块存在一定风险，请务必先备份原厂 `boot.img`；行车过程中请严格遵守交通法规，切勿在驾驶时操作屏幕或观看视频。
