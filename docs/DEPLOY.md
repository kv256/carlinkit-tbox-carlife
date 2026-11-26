# 车连易 CPC200-Tbox Root 与定制系统部署指南

本文档面向从**原厂未 Root 的车连易 Carlinkit AI Box（`CPC200-Tbox`，高通 `msm8953` / Android 9）**起步的用户，完整覆盖：
1. 为什么采用 **`fastboot flash boot`（Magisk Root）+ Magisk 模块一键刷入**，而不制作整包 `system.img` 镜像；
2. 如何安全备份原厂 `boot.img`、修补并刷入 Magisk 获取 Root；
3. 如何一键写入本项目定制系统（**推荐：单文件 Magisk ZIP**；**开发者：源码编译 + `deploy.sh`**）；
4. 首次上车配置、日志排查与彻底还原 / 救砖方案。

---

## 0. 准备工作与风险提示

### 硬件与环境确认
- **盒子型号**：Carlinkit AI Box `CPC200-Tbox`（高通 SDM450 / `msm8953`，Android 9 / API 28）。
  - 可在盒子「设置 → 关于设备」或 `adb shell getprop ro.board.platform` 确认输出为 `msm8953`，`adb shell getprop ro.build.version.sdk` 输出为 `28`。
- **电脑端工具**：安装 Google 官方 [`platform-tools`](https://developer.android.com/tools/releases/platform-tools)（含 `adb` 与 `fastboot`）。
  - Windows 用户还需安装高通 USB / Android Bootloader 驱动。
- **线材**：一根支持数据传输的优质 Type-C / USB-A 数据线（用于连接电脑刷机）。

> **重要警告**：
> 1. 不同批次/日期的固件 `boot.img` 存在内核与驱动差异，**严禁直接刷入他人分享的未知版本 `boot.img`**，必须使用与你当前盒子固件完全一致的原厂 `boot.img` 来制作 Magisk 补丁！
> 2. 操作前请完整备份自己的原厂 `boot.img`（下文称 `stock_boot.img`），这是最重要的救砖底牌。

---

## 1. 为什么不制作 `system.img` / `vendor.img` 通过 `fastboot` 整包刷入？

很多同学会问：“能否直接打一个刷机包用 `fastboot flash system` 一次刷完？”在 CPC200-Tbox 上，**不推荐也不提供整包 `system.img`**：

1. **AVB / dm-verity 校验与跨批次变砖风险**：原厂 `/system` 与 `/vendor` 启用了 `dm-verity` 和 `vbmeta` 校验，且 Carlinkit 不同生产批次/OTA 版本的蓝牙固件、屏幕配置、内核模块均有细微差异；跨版本硬刷整包极易卡开机 Logo。
2. **版权合规边界**：原厂 `/system` 包含大量闭源商业固件与 OEM 应用，不能也不应在开源仓库中再分发。
3. **本项目的最佳实践（两步极简、可无损回滚）**：
   - **第 1 步（仅需 1 次）**：通过 `fastboot flash boot magisk_patched.img` 仅修补 `boot` 分区获取 Magisk Root；
   - **第 2 步（一键写入全部定制系统）**：使用本项目提供的 **`tbox_carlife-magisk.zip` 单文件卡刷包**，在 Magisk 里一次安装并重启即可——**完全替代繁琐的手动推文件/改权限流程**，不触碰原厂系统分区，随时可在 Magisk 内一键卸载还原！

---

## 2. 给盒子获取 Magisk Root

### 2.1 获取你当前固件版本的原厂 `boot.img`

按方便程度任选其一（只要能拿到与当前系统版本一致的 `boot.img`）：

- **方法 1（最推荐）：从对应版本的官方 OTA 升级包提取**
  1. 向客服或官方渠道获取与你盒子当前版本号一致的离线升级包（通常为 `update.zip` 或 `AutoKit_xxx.zip`）；
  2. 解压后若直接看到 `boot.img`，复制出来保存为 `stock_boot.img`；
  3. 若内部是 `payload.bin`，使用开源工具 `payload-dumper-go` 提取 `boot.img`：
     ```bash
     payload-dumper-go -p boot payload.bin
     ```
- **方法 2：通过高通 9008 EDL 模式直接从盒子读取（无需固件包，100% 匹配本机）**
  - 使用开源工具 [bkerler/edl](https://github.com/bkerler/edl) 配合 `msm8953` Firehose loader，长按盒子侧边/底部复位孔（或通过 `adb reboot edl`）进入 9008 模式后读取：
    ```bash
    edl r boot stock_boot.img
    ```

### 2.2 开启盒子 USB ADB 连接电脑

1. 先把盒子插到车机（或使用原厂 CarPlay 界面/外接显示）开机；
2. 进入盒子的 **Android 系统设置 → 关于设备**，连续点击 **版本号** 7 次开启「开发者选项」；
3. 进入 **系统 → 开发者选项**，打开 **USB 调试**（部分原厂版本还需要在工厂设置/开发者设置中把 USB 模式切换为 ADB，或在拔掉车机连电脑后确认允许调试弹窗）；
4. 用数据线将盒子连到电脑，执行：
   ```bash
   adb devices
   ```
   确认看到类似 `0123abcd    device` 的输出（若提示 `unauthorized`，请在盒子屏幕上点击允许 USB 调试）。

> **提示**：部分原厂盒子的默认 USB 口上电时走 CarPlay 反向协议（`sdCarplaySvc`），插电脑可能暂时看不到 ADB。可先在原厂「设置 / 工厂设置」里打开 **ADB 调试开关** 或 **反向 USB 切换**；一旦完成本指南并刷入本模块后，Wi-Fi ADB（端口 `5555`）会永久开机常驻，`TboxUsbSwitch` 也可一键切换 USB ADB 模式。

### 2.3 用 Magisk 修补 `boot.img`

1. 下载官方 [Magisk APK](https://github.com/topjohnwu/Magisk/releases)（实测 Magisk v27+ / v31.1 在本机运行稳定）；
2. 安装 Magisk App 并将原厂 `stock_boot.img` 推送到盒子：
   ```bash
   adb install Magisk.apk
   adb push stock_boot.img /sdcard/Download/stock_boot.img
   ```
3. 在盒子上打开 **Magisk App** → 点击右上角/顶部的 **「安装」** → 选择 **「选择并修补一个文件」** → 选中 `/sdcard/Download/stock_boot.img` → 点击 **「开始」**；
4. 修补完成后，把生成的补丁镜像拉回电脑并校验非空：
   ```bash
   adb shell ls -lh /sdcard/Download/magisk_patched*.img
   adb pull /sdcard/Download/magisk_patched-XXXXX.img ./magisk_patched.img
   ```

### 2.4 通过 `fastboot` 安全刷入 `magisk_patched.img`

1. 让盒子重启进入 Fastboot（Bootloader）模式：
   ```bash
   adb reboot bootloader
   ```
2. 确认电脑已识别到 Fastboot 设备，并检查解锁状态：
   ```bash
   fastboot devices
   fastboot oem device-info
   ```
   - CPC200-Tbox 出厂 Bootloader 通常已处于解锁状态（`Device unlocked: true` / `verifiedbootstate=orange`）。
   - 如果显示未解锁，需先执行 `fastboot flashing unlock`（注意：解锁会清空用户数据 `/data`）。
3. **强烈建议先临时启动验证（不写入分区，最安全！）**：
   ```bash
   fastboot boot magisk_patched.img
   ```
   - 盒子会自动以修补后的镜像启动一次；等待约 30 秒开机后，检查 Magisk App 是否显示已安装，或执行 `adb shell su -c id` 确认输出 `uid=0(root)`。
   - 若万一启动异常，只需断电重启即自动回到原厂 `stock_boot.img`，零风险！
4. 确认临时启动完全正常后，再重新进 Fastboot **永久写入 `boot` 分区**：
   ```bash
   adb reboot bootloader
   fastboot flash boot magisk_patched.img
   fastboot reboot
   ```
5. 重启后再次验证 Root：
   ```bash
   adb shell "su -c 'magisk -v && id'"
   ```
   看到 Magisk 版本号和 `uid=0(root)` 即代表 Root 成功！

---

## 3. 写入我们的定制系统（两种方式任选其一）

### 方式 A：一键刷入 Magisk 模块 ZIP（推荐，最简单）

本项目提供脚本把 **CarLife 核心服务端、驾舱桌面、两个 RRO 覆写包、守护脚本、Wi-Fi ADB 与 `TboxUsbSwitch`** 全部打包进**单个标准 Magisk 卡刷包 `tbox_carlife-magisk.zip`**。

#### 1. 获取安装包
- 直接从本仓库 **GitHub Releases** 下载已打包好的 `tbox_carlife-magisk.zip`；
- 或在本地从源码一键构建打包（需 JDK 8+ 与 Android SDK API 28）：
  ```bash
  export ANDROID_SDK_DIR=/path/to/android-sdk   # 若 SDK 不在 ../tools/android-sdk
  bash scripts/package_magisk_zip.sh
  # 输出：build/tbox_carlife-magisk.zip
  ```

#### 2. 刷入模块（任选一种操作）
- **操作 1（纯盒子 UI，刷完即用）**：
  1. 将 `tbox_carlife-magisk.zip` 复制到盒子 `/sdcard/Download/`（可用 U 盘或 `adb push`）；
  2. 打开 **Magisk App → 底部「模块」→「从本地安装」**，选择 `tbox_carlife-magisk.zip`；
  3. 等待安装日志提示 `Done! Reboot the Tbox...`，点击 **重启**。
- **操作 2（电脑端 3 条命令一步到位）**：
  ```bash
  adb push build/tbox_carlife-magisk.zip /sdcard/Download/tbox_carlife-magisk.zip
  adb shell "su -c 'magisk --install-module /sdcard/Download/tbox_carlife-magisk.zip'"
  adb reboot
  ```

#### 3. 首次开机自动完成的配置
刷入重启后，模块内置的 `service.sh` 会在开机完成后自动执行以下初始化（无需手工操作）：
- 安装/升级 `TboxUsbSwitch`（USB 模式与音频分流切换工具）；
- 挂载 `TboxCarLauncher` 为特权桌面并设为默认 Home，禁用原厂 `Launcher3`；
- 激活 `TboxNoNavBarOverlay`（彻底隐藏原厂左侧滑出导航栏）与 `TboxBtPhoneOverlay`；
- 开启 `force_resizable_activities`、`hidden_api_policy` 与音乐卡片通知监听权限；
- 启动开机常驻 Wi-Fi ADB（TCP `5555`）与 16:9 虚拟屏输入桥 `VdInputBridge`。

---

### 方式 B：开发者从源码全量编译 + `deploy.sh` 部署

适合需要二次开发或微调源码的用户。

#### 1. 准备编译环境（Linux / macOS / WSL2）
- **JDK 8+**（`javac`、`keytool`）
- **Android SDK Platform 28**（含 `platforms/android-28/android.jar` 及 `build-tools` 中的 `aapt2`、`d8`、`zipalign`、`apksigner`）
- **`python3`**（用于封装 DEX 到 APK）

设置环境变量（或直接把 SDK 放在仓库同级 `../tools/android-sdk`）：
```bash
export ANDROID_SDK_DIR=/path/to/android-sdk
export ADB_BIN=/path/to/adb
# 可选：自定义 APK 签名库路径（默认自动在 .keystore/usbswitch-release.jks 生成本地签名）
# export TBOX_KEYSTORE=/path/to/my-release.jks
```

#### 2. 编译与部署
```bash
# 全量编译 4 个组件
bash scripts/build_all.sh

# 首次全量部署（推送 JAR + daemon + Magisk 模块 + RRO + TboxUsbSwitch + TboxCarLauncher）
bash scripts/deploy.sh --serial <盒子IP或USB序列号> --all

# 重启盒子使 Magisk 模块与 RRO 覆写生效
adb -s <盒子IP或USB序列号> reboot
```

> **提示**：后续开发调试时，支持增量快速部署：
> - 仅热更 CarLife 服务端与守护脚本（无需重启）：`bash scripts/deploy.sh --serial <IP>:5555`
> - 仅更新车机桌面：`bash scripts/deploy.sh --serial <IP>:5555 --launcher`
> - 仅更新模式切换 App：`bash scripts/deploy.sh --serial <IP>:5555 --app`

---

## 4. 首次上车配置与使用指南

1. **切换到 CarLife 模式**：
   - 在盒子桌面应用抽屉中找到并打开 **`USB模式切换`（TboxUsbSwitch）**；
   - 点击切换为 **CarLife 模式**；
   - 推荐音频与蓝牙配置：
     - **媒体音频**：`USB`（48 kHz 立体声）
     - **导航音频**：`USB`（16 kHz 独立 TTS，不打断音乐）
     - **电话音频**：若手机连盒子打电话选 `USB`（推荐，带回声消除）；若希望电话走原车蓝牙，选 `蓝牙` 并将 **「手机蓝牙」** 设为 **`仅音乐`**（同时在车机蓝牙里让手机仅连电话）。
   - 点击重启盒子，插到车机 CarLife USB 数据口即可开始投屏。
2. **驾舱桌面手势与快捷操作**：
   - **长按左上角时钟**：打开「车机桌面设置」（CPU 温度显示、CarLife 30/60 fps 切换、当前应用 DPI 缩放、开机默认主区应用等）；
   - **长按左侧 Dock 快捷图标**：自定义该槽位固定的应用；
   - **应用抽屉长按任意图标**：单独调节该应用的显示缩放（DPI）、设为开机默认应用、固定到 Dock 或卸载；
   - **左侧栏 `<` 返回键**：短按向当前 16:9 应用发送返回键（退出最后一个应用后自动回到应用抽屉）；**长按**打开 16:9 最近任务切换器；
   - **左侧栏底部应用抽屉键**：短按切换抽屉与主区应用；**长按**唤醒语音助手。

---

## 5. 维护、抓日志与彻底还原 / 救砖

### Wi-Fi ADB 连接与实车日志抓取
盒子连上 Wi-Fi 后，模块会自动开启 `5555` 端口 Wi-Fi ADB，可在电脑上直接连接调试：
```bash
adb connect <盒子IP>:5555
bash scripts/pull_logs.sh --serial <盒子IP>:5555
# 如需连同系统持久化日志 x7_Log 一并拉取：
bash scripts/pull_logs.sh --serial <盒子IP>:5555 --full
```

地址也可以写进仓库根目录的 `.env`（从 `.env.example` 复制，已被 git 忽略）：`TBOX_ADB_IPS="<盒子IP>:5555"`，之后脚本可省略 `--serial`。

日志会保存到仓库下的 `logs/<时间戳>/`（已 `.gitignore`），其中包含：
- `daemon.log`：开机 USB 枚举与 AOA 握手时间线
- `boot_usb_trace.log` / `boot_usb_kmsg.log`：硬件级 `dwc3` 开机前 45 秒 USB EP0 抓包（不受 `x7_Log` 滚动覆盖）
- `md.jsonl` / `md.head.jsonl`：CarLife 协议、音视频与触控日志

### 临时切回原厂 CarPlay 或 USB ADB
无需卸载模块！直接打开盒子里的 **`TboxUsbSwitch`** 点击切换为 **CarPlay** 或 **USB ADB** 并重启即可；也可通过命令切换：
```bash
# 切回原厂 CarPlay
adb shell "su -c 'setprop persist.tbox.carlife 0; setprop persist.sdcarplay.reverse.disable 0; reboot'"

# 切到 USB ADB 调试模式
adb shell "su -c 'setprop persist.sdcarplay.reverse.disable 1; reboot'"
```

### 彻底卸载定制系统（恢复原厂桌面与原厂流程）
1. 在 **Magisk App → 模块** 中点击移除 **`Carlinkit Tbox CarLife AOA + Cockpit Launcher`** 并重启（`uninstall.sh` 会自动清理 `/data/adb/carlife`、`/data/adb/service.d` 脚本并恢复原厂 `Launcher3`）；
2. 若希望连 Magisk Root 一并还原为纯原厂状态，在 Fastboot 下刷回最初备份的原厂 `stock_boot.img`：
   ```bash
   adb reboot bootloader
   fastboot flash boot stock_boot.img
   fastboot reboot
   ```

### 常见异常处理
- **刷入模块后开机黑屏或无法进系统**：
  - 使用 Magisk 安全模式禁用所有模块（或在电脑能连上 ADB 时执行 `adb shell "su -c 'touch /data/adb/modules/tbox_carlife/disable && reboot'"`），或在 Fastboot 刷回 `stock_boot.img` 即可立即恢复。
- **桌面误禁用或需要手动恢复原厂桌面**：
  ```bash
  adb shell "su -c 'pm enable com.android.launcher3; cmd package set-home-activity com.android.launcher3/.Launcher; setprop persist.suding.hasnavibar true'"
  ```
- **如何校验自己电脑编译出的 Magisk 包完整无误**：
  ```bash
  bash scripts/package_magisk_zip.sh --rebuild
  unzip -l build/tbox_carlife-magisk.zip
  ```
