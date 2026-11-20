# Tbox CarLife for Toyota Venza (CPC200-Tbox / Android 9)

基于上游 [`Carlife_on_box` (`v2026.10.17` / `d9397d9`)](https://github.com/SadykovIV/Carlife_on_box) 面向 **车连易 Carlinkit AI Box（CPC200-Tbox，Qualcomm `msm8953` / Android 9 / API 28）** 与 **2022 款丰田威飒（DesaySV `G6SA_r8a7796` / Android 8.1 原厂 CarLife）** 的原生 CarLife USB AOA 移植与定制项目。

全部改动均采用 **非破坏性 Magisk 模块 + `/data/adb/carlife` 部署**，不修改 `/system`、`/vendor`、`/boot`（除 Magisk boot 补丁外），随时可通过 `TboxUsbSwitch` 切回原厂 CarPlay 或 USB ADB 模式。

## 目录结构

```text
tbox-carlife-venza/
├── carlife-md-api28/        # Android 9 (API 28) CarLife USB AOA 核心服务端 (carlife-md-api28.jar)
│   ├── build.sh             # 编译脚本 (javac 8 + d8 --min-api 28)
│   ├── compile-stubs/       # 编译期隐藏 API stub (不打包进 DEX)
│   └── src/org/carlife/hulab/
├── magisk-module/           # 非破坏性 Magisk 挂载与守护脚本
│   ├── module.prop          # /data/adb/modules/tbox_carlife/module.prop
│   ├── system.prop          # bluetooth.hfp.client=0
│   ├── carlife-daemon.sh    # /data/adb/carlife/carlife-daemon.sh (单实例 AOA 监管)
│   ├── service.d/wifi_adb.sh# /data/adb/service.d/wifi_adb.sh (开机常驻 Wi-Fi ADB 5555)
│   └── system/bin/sd-reverse.sh # USB 模式分发 (CarLife / CarPlay / USB ADB)
├── bt-phone-overlay/        # 静态 RRO 覆写包 TboxBtPhoneOverlay.apk (切换 QCOM-BTD 为 HFP AG 手机角色)
│   ├── AndroidManifest.xml
│   ├── build.sh
│   └── res/values/config.xml
├── tbox-usb-switch/         # 盒子端模式 & 音频路由切换 App (v1.3)
│   ├── build.sh
│   └── app/
├── scripts/
│   ├── build_all.sh         # 一键编译 JAR + RRO Overlay + TboxUsbSwitch APK
│   ├── deploy.sh            # 自动识别盒子 ADB IP，原子推送并由 init 重拉单实例守护进程
│   └── pull_logs.sh         # 拉取 /cache/carlife/logs/ 与设备运行状态到 logs/
└── docs/
    └── CUSTOMIZATIONS.md    # 详细定制清单、与上游差异及实车状态记录
```

## 快速构建与部署

依赖：`javac`（JDK 8+）、Android SDK API 28（默认位于 `../tools/android-sdk`，可通过 `ANDROID_SDK_DIR` 覆盖）、`adb`（默认 `../tools/linux/platform-tools/adb`，可通过 `ADB_BIN` 覆盖）。

```bash
# 1. 全量构建 (输出在各子目录 build/ 下，均已加入 .gitignore)
bash scripts/build_all.sh

# 2. 仅部署最新 carlife-md-api28.jar 与 carlife-daemon.sh（无需重启）
bash scripts/deploy.sh

# 3. 完整部署（含 Magisk 模块、RRO Overlay 与 TboxUsbSwitch App，模块更新需重启生效）
bash scripts/deploy.sh --all

# 4. 拉取实车落盘日志 (/cache/carlife/logs -> logs/<时间戳>/)
bash scripts/pull_logs.sh
```

> **查看与上游 `Carlife_on_box` 的全部代码差异**：
> 首次提交 (`chore: import upstream Carlife_on_box v2026.10.17`) 为纯净上游基线，执行 `git diff --stat HEAD~1 -- carlife-md-api28/src/` 或 `git log -p` 即可清晰审查每一处定制修改。

## 运行期属性（可热切换，无需重启）

| 属性 | 可选值 | 说明 |
| --- | --- | --- |
| `persist.tbox.carlife` | `1` / `0` | `1` = CarLife AOA 模式；`0` = 原厂 CarPlay（需重启切换 USB 模式） |
| `persist.sdcarplay.reverse.disable` | `1` / `0` | `1` = USB ADB 调试模式（需重启） |
| `persist.tbox.carlife.audio.media` | `usb` / `bt` | 媒体音频走 CarLife USB (48kHz 立体声) 或蓝牙 |
| `persist.tbox.carlife.audio.navi` | `usb` / `bt` | 导航播报（含高德/百度/腾讯导航 UID 隔离）走 CarLife TTS (16kHz) 或蓝牙 |
| `persist.tbox.carlife.audio.call` | `usb` / `bt` | 电话音频走 CarLife USB 或蓝牙 HFP SCO |
| `persist.tbox.carlife.navi.pkgs` | 逗号分隔包名 | 额外需要路由到导航 TTS 通道的导航 App 包名 |
