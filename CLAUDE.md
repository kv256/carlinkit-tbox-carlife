# CLAUDE.md

AI 编码助手（Claude Code、Codex 等）在本仓库工作时的说明。`AGENTS.md`、`AGENT.md` 均为指向本文件的软链接，只改这一份。

## 项目概览

在车连易 Carlinkit **CPC200-Tbox**（Qualcomm msm8953，Android 9 / API 28，Magisk Root）上实现原生百度 **CarLife USB AOA** 投屏，对接 2022 款丰田威飒车机（DesaySV，原厂 `com.baidu.carlifevehicle`），并附带 CarPlay 风格 16:9 驾舱桌面。与原厂 CarPlay、USB ADB 三模式共存。

| 目录 | 内容 |
| --- | --- |
| `carlife-md-api28/` | CarLife 手机端协议服务 `MdMain`（Java，`app_process` 运行，打包为 jar）：投屏编码、触控、音频分流、车机麦回传与回声消除 |
| `magisk-module/` | Magisk 模块 `tbox_carlife`：`carlife-daemon.sh`（USB gadget / AOA 状态机，init 托管）、`post-fs-data.sh`（开机早期发布 CarLife USB 身份）、`system/bin/sd-reverse.sh`（模式切换）、`service.d/`、`post-fs-data.d/` 全局脚本 |
| `tbox-car-launcher/` | `com.tbox.carlauncher` 驾舱桌面 + `VdInputBridge`（uid 1000 的虚拟显示/输入桥）+ `TboxNoNavBarOverlay` |
| `tbox-usb-switch/` | `com.tbox.usbswitch` 模式与音频通道切换 App |
| `bt-phone-overlay/` | `com.tbox.overlay.bluetooth.phone` 静态 RRO |
| `scripts/` | `build_all.sh`、`deploy.sh`、`pull_logs.sh`、`package_magisk_zip.sh` |
| `docs/` | `CUSTOMIZATIONS.md`（根因与设计）、`HANDOFF.md`（逐轮交接）、`DEPLOY.md`（部署） |

**开发进度与待办统一记录在 [DEVELOPMENT_PROGRESS.md](DEVELOPMENT_PROGRESS.md)**：每轮改动、部署或上车测试后更新其「当前迭代」「盒子部署状态」「上车验证清单」。

## 构建

没有 Gradle，各子项目用 `build.sh` 直接调用 `javac` / `d8` / `aapt2`：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS；d8 需要 JDK 17，JDK 8 会失败
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_DIR=~/Library/Android/sdk         # 需 platforms/android-28 与 build-tools
bash scripts/build_all.sh                            # jar + 两个 overlay + 两个 APK
```

- APK 用 `.keystore/usbswitch-release.jks` 签名（已 gitignore）。**不得删除、替换或提交**该密钥库，换签名会导致盒子上的 App 无法覆盖升级。
- 没有单元测试；改完至少保证 `build_all.sh` 通过、shell 脚本 `bash -n` 通过（盒子上是 mksh / toybox / Magisk busybox，避免 bash 专有语法）。

## 部署与调试

```bash
export ADB_BIN=$(which adb)                          # 否则默认 ../tools/linux/platform-tools/adb
bash scripts/deploy.sh --serial <盒子IP>:5555          # jar + daemon，热更新免重启
bash scripts/deploy.sh --serial <盒子IP>:5555 --module # 另加 Magisk 模块（需重启生效）
bash scripts/deploy.sh --serial <盒子IP>:5555 --launcher
bash scripts/pull_logs.sh                            # 拉日志到 logs/<时间戳>/
```

- 盒子地址用 `--serial` 传入，或写在 `.env`（git 忽略，模板 `.env.example`）的 `TBOX_ADB_IPS` 里；**代码、注释和文档里都不写具体 IP**，也不按使用地点区分地址。
- `--launcher` 会把 `DO_JAR` 置 0，参数顺序有影响；要同时部署时分两次执行或用 `--all`。
- `deploy.sh` 在子项目现编失败时会**静默退出**（`set -e`），务必确认输出里有 `Respawning init-managed carlife-daemon...` 和模块文件 push 记录，必要时在盒子上 `sha256sum` 核对。
- 部署前先备份盒子上将被覆盖的文件（惯例：`/data/adb/carlife/*.<轮次>.bak`）。
- 关键日志：`/cache/carlife/logs/daemon.log`（开机时间线 `boot up=…`、`EARLY_USB_*`、`AOA_START_MATCHED`）、`service.log`（service.d 脚本）、MdMain 结构化日志同目录；`logcat -s sd` 为 goc 蓝牙栈（sdsdk816）。
- 只连接用户指定的盒子；同网段可能还有其他 adb 设备，不要误操作。

## 红线

- **不直接修改盒子 `/system`、`/vendor`、`/boot`**，一律经 `/data/adb/modules/tbox_carlife` 与 `/data/adb/carlife` 部署。
- **不改兼容性标识**：模块 ID `tbox_carlife`、包名 `com.tbox.usbswitch` / `com.tbox.overlay.bluetooth.phone` / `com.tbox.carlauncher`、属性前缀 `persist.tbox.carlife.*`。
- 未经用户要求不提交、不推送；在 `main` 上需要提交时先开分支。
- 不把个人信息（邮箱、手机/车机 MAC、设备名、家庭网络 IP）写进新代码或文档；仓库准备开源。

## 平台要点（易踩坑）

- **USB / AOA 时序**：车机只在自身开机时做一次 USB 扫描（约盒子 uptime 10–11 s），所以 CarLife 身份在 `post-fs-data.sh` 中约 7 s 发布并交接给 daemon（`$RUN/usb.daemon`）。原厂 init 的 `sys.usb.config=none && sys.usb.configfs=1` 会解绑 gadget，CarLife 模式必须用 `sys.usb.config=carlife`（无触发器匹配）。AOA START 以内核日志 `mtp_ctrlrequest:1421` 为准（uevent 会因车机 11 ms 后复位而丢失）。
- **post-fs-data 阶段**持久属性尚未加载，用 `magisk resetprop -p` 读取；Magisk 在此阶段之后才做 magic mount。
- **静态 RRO**：Android 9 的 `/data/resource-cache/overlays.list` 与模块内 overlay APK 不一致会导致 system_server 开机循环；改动 overlay 后必须清理该缓存（`deploy.sh` 与 `tbox_rro_guard.sh` 已处理）。
- **系统 App 隐藏**：不要用 Magisk `.replace` 移除系统 APK——Android 9 会清空该包数据。**persistent 应用**（如 MainAiBox `com.carsyso.main`，会弹「设备未激活」）`pm disable-user` 后开机仍会被拉起，`force-stop` 也杀不掉；用 `pm hide`（数据保留，**下次开机**起不再启动）。它必须在重启进 CarPlay **之前** `pm unhide`：system_server 很早就启动其 `MainService`，各 SDK 客户端的 `MainSDKService` 只在 `onCreate` 绑定一次。TboxUsbSwitch 切模式时处理，`service.d/tbox_vd_input.sh` 兜底（归属标记 `/cache/carlife/.mainaibox_hidden`）。隐藏的包不在 `pm list packages` / `pm path` 中，需加 `-u`；隐藏会丢弃其 appops 设置。
- **goc 蓝牙栈**：命令写 `/dev/goc_serial`（`AT#xx`，见盒子 `/system/config.ini`），配对序号 1 起；`SE` 会断开全部 profile。
- 盒子开机时钟可能是 2009 年，不要按时间戳排序/清理文件，用序号。
- **PackageManager 解析缓存**（`/data/system/package_cache/*/<APK名或包目录名>-<flags>`）只要比 APK 的 mtime 新就会复用；时钟未对时部署的文件 mtime 是 1970，改了 APK 也不会重新解析（曾导致去导航栏 overlay 一直按 static 处理、拿不到 idmap）。模块 `post-fs-data.sh` 每次开机会清掉本模块 APK 的缓存；排查 overlay/priv-app 不生效时先看 `dumpsys overlay` 的 `mState`。
- 盒子 busybox awk 不能直接读 `/dev/kmsg`，需经 `cat` + FIFO。

## 约定

- 与用户交流和 `docs/`、进度文档使用中文；代码注释与提交信息使用英文。
- 提交信息用 Conventional Commits：`feat(scope): …`、`fix(scope): …`、`docs: …`、`chore(scripts): …`，scope 如 `daemon`、`usb`、`aec`、`launcher`、`usbswitch`、`boot`。
- 根因分析写进 `docs/CUSTOMIZATIONS.md`，部署与上车结果写进 `docs/HANDOFF.md` 和 `DEVELOPMENT_PROGRESS.md`。
