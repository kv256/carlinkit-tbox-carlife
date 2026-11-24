# 开发进度跟踪

> 最后更新：2026-11-24 · 分支 `main`（`f8cc04a` + 未提交的 Round 7 修复）
>
> 本文件只记录**当前状态与待办**：每轮改动后更新「当前迭代」「盒子部署状态」「上车验证清单」，完成的事项移入「历史里程碑」。
> 根因分析与设计细节写在 [docs/CUSTOMIZATIONS.md](docs/CUSTOMIZATIONS.md)，逐轮交接记录在 [docs/HANDOFF.md](docs/HANDOFF.md)，部署方法见 [docs/DEPLOY.md](docs/DEPLOY.md)。

状态图例：✅ 实车通过 · 🏠 仅家中验证 · 🟡 已部署待车测 · 🔧 开发中 · 📋 待办 · ⚫ 搁置

---

## 1. 功能状态总览

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| CarLife USB 投屏 1920×720@30fps + 多点触控 | ✅ | AOA 建链稳定 |
| 60fps 投屏开关（`persist.tbox.carlife.fps`） | 🟡 | bddf138 引入；盒子当前为 60，未记录车测结果 |
| 媒体 48kHz + 导航 16kHz TTS 分流 | ✅ | 长时间多场景复核未做 |
| `call=usb` 电话双向通话 | ✅ | |
| `call=usb` 回声消除（`EchoCanceller` 远端门控） | ✅ | Round 6b 用户确认听不到回音；通话质量待继续测 |
| `call=bt` 经 `QCOM-BTD` 通话 | ⚫ | 需在 HFP AG 模拟真实通话 + 绕过音频 HAL，属独立新工作 |
| 手机蓝牙「仅音乐 / 音乐+电话」（`bt.phone`） | ✅ | iPhone 电话走原车蓝牙、音乐走盒子，已可分别连接 |
| 仅音乐模式下 BT 音乐被静音 | 🏠 | Round 6c 修复，家中暂停/继续均恢复有声；XQBluetooth 界面仍显示"蓝牙已断开"（仅显示问题） |
| 开机自动拉起 CarLife | 🟡 | 盒子已提前到 ~7–8 s 以 CarLife 设备出现；此前 4 次车上开机车机均未发起 AOA，待按受控步骤复测 |
| TboxCarLauncher 16:9 驾舱桌面（v1.2+） | 🟡 | 9da347e…f8cc04a 引入，未记录完整车测结果 |
| TboxUsbSwitch v1.6 | ✅ | 三模式切换、音频通道、回声消除档位、手机蓝牙选项 |
| CarLife 模式隐藏 MainAiBox | 🏠 | Round 7 改为 `pm disable-user`，切模式不再丢数据 |
| 开源发布准备 | 📋 | 见第 5 节「开源前清理」 |

---

## 2. 当前迭代：Round 7（代码审查修复）

来源：对 `9da347e..f8cc04a`（18 个提交）的代码审查，用户要求修复第 1–6 项；Codex 复审追加 3 项已一并修复。**尚未提交。**

| # | 问题 | 修复 | 状态 |
| --- | --- | --- | --- |
| 1 | `.replace` 隐藏 MainAiBox 导致每次切模式清空其数据 | `tbox_vd_input.sh` 开机完成后按模式 `pm disable-user` / `pm enable`；带归属标记，卸载时只恢复自己停用的 | 🏠 |
| 2 | 自愈逻辑会撤销用户在 Magisk 里的手动停用 | `tbox_rro_guard.sh` 写开机标记，仅"上次开机未完成"才一次性重新启用；按本次开机的挂载状态判断 | 🏠 |
| 3 | 停用 launcher3 后无备用桌面 | 开机后观察 TboxCarLauncher 60 s，缺失/停用/崩溃循环时启用 launcher3 并设为桌面 | 🏠（未触发，正常路径已验证） |
| 4 | 30fps 下拥塞降码率多耗约 2 s | `VideoRateControl` 上限 = 当前帧率编码器上限（3M/5M） | 🟡 |
| 5 | 无歌手曲目进度条反复归零 | 换歌判断改用回退后的歌手名 | 🟡 |
| 6 | BT 进度条周期性回跳 | 桥接记录位置有效时刻（暂停冻结、继续重锚），Launcher 按该时刻推算 | 🟡 |
| — | `tbox-car-launcher/build.sh` 不支持标准 SDK 目录 / macOS | 同 `tbox-usb-switch` 的处理 | ✅ |

---

## 3. 盒子部署状态

部署时间 2026-11-24 20:51，已重启验证。部署前文件备份于 `/data/adb/carlife/pre-review.bak/`。

| 文件 | SHA-256 |
| --- | --- |
| `/data/adb/carlife/carlife-md-api28.jar` | `0243648eb924c5c07e9332647941dcb94d935d9e4b1a13cf9245929b2f147ad1` |
| `/data/adb/carlife/carlife-daemon.sh` | `2a7b7fe4788a4109ac6f3b3098d6ac41f2163230232610f562ea2b677c97d8b5` |
| `/data/adb/modules/tbox_carlife/post-fs-data.sh` | `8352db9e275e4dfee7a5b781938c1e75e353e68ee9e1d89fb3810cdaa610c647` |
| `/data/adb/service.d/tbox_vd_input.sh` | `d58126732172c6830d68724607dfcc34668b73869845fda3e4033a5af16010bc` |
| `/data/adb/post-fs-data.d/tbox_rro_guard.sh` | `018b80188fd77f11027116bdc01761fb49656830a0be1fe3274e528bc787d66a` |
| `…/priv-app/TboxCarLauncher/TboxCarLauncher.apk` | `b3477bbf515e8de34e1ff0bb6ee98adefa335be1582c06b6f88bf91a6a091257` |

运行期属性：`persist.tbox.carlife=1`、`audio.media=usb`、`audio.navi=usb`、`audio.call=bt`、`aec=3`、`aec.dump=1`、`bt.phone=media`、`fps=60`。

> 注意：`audio.call=bt` 时电话不经过盒子（由车机原车蓝牙处理），这与 `bt.phone=media` 的用法一致；如需测 `call=usb` 回声，先在 TboxUsbSwitch 改回 USB。

---

## 4. 上车验证清单

- [ ] **开机自动启动**：同一趟内用盒子连上 CarLife → 保持连接熄火 → 等约 1 分钟 → 启动。`daemon.log` 应有 `EARLY_USB_STATE … now=configured`（~8 s）与无人操作的 `AOA_START_MATCHED KMSG`。
- [ ] **码率（Round 7 #4）**：30fps 投屏，网络/USB 拥塞时画面约 1 s 内恢复。
- [ ] **BT 进度条（#5 #6）**：播放无歌手曲目不归零；暂停、继续后不回跳。
- [ ] **仅音乐模式**：放歌 → 暂停 → 再放，一直有声，日志有 `BT_PHONE_MUSIC_UNMUTED`。
- [ ] **MainAiBox（#1）**：切到 CarPlay 并重启后 `com.carsyso.main` 重新启用；再切回 CarLife 后其设置仍在。
- [ ] **60fps 投屏**：画面流畅、无花屏，`MD_STARTED … fps=60`。
- [ ] **`call=usb` 通话质量**：双方同时说话不吞字。

之后：`bash scripts/pull_logs.sh`，重点看 `/cache/carlife/logs/daemon.log`、`service.log`。

---

## 5. 待办 Backlog

### 代码审查剩余问题（9da347e..f8cc04a）

- [ ] **#7** `AMapBridge.java:411`：高德未运行时「回家/公司」常无效（删除了 1.2 s 重发，回退仅 350 ms）。
- [ ] **#8** `AppViewportManager.java:1068`：非桥接显示屏上的可见 App 会让 16:9 视口一直黑屏。
- [ ] **#9** `LauncherPrefs.java:44`：首次 Direct Boot 启动时设置迁移会回滚用户改动。
- [ ] **#10** `VdInputBridge.java:132`：Display 0 密度不是 238 时触摸偏移（`baseDpi` 写死）。
- [ ] `LauncherActivity.java:672`：改 DPI / 帧率时在主线程同步等待 `am force-stop`，界面卡顿。
- [ ] `capture_boot_usb_trace`：ftrace 未开启时也每次开机轮转，会挤掉有用的 trace；并全局关闭 `tracing_on`。
- [ ] `carlife-daemon.sh` `start_md`：每次启动 MdMain 都执行 `settings put`，可改为仅在帧率变化时写。

### 工程与发布

- [ ] `scripts/deploy.sh`：子项目编译失败（如未设 `ANDROID_SDK_DIR`）时静默退出，模块未安装、守护进程未重启，需报错提示。
- [ ] 开源前清理：git 历史含车机 MAC（972fd22、6b29fc0）与手机设备名；`docs/HANDOFF.md`、`scripts/deploy.sh`、`scripts/pull_logs.sh` 含家庭网络 IP；三个 `build.sh` 含 `$HOME/…` 回退路径。需新历史发布或清理。
- [ ] 确认 CarLife 模式下是否有功能依赖 `com.carsyso.main`（`TechBtMusicClient` 以其 `MainService` 作为次要数据源）。
- [ ] RRO 缓存清理逻辑分散在三处（`post-fs-data.sh`、`tbox_rro_guard.sh`、`deploy.sh`），可合并。

### 搁置

- ⚫ `call=bt` 经 `QCOM-BTD`：需 HFP AG 模拟通话（`+CIEV` / `AT+CLCC`）+ `tinymix INTERNAL_BT_SCO` 采集，独立项目。

---

## 6. 历史里程碑

| 轮次 | 主要提交 | 内容 | 结果 |
| --- | --- | --- | --- |
| 基线 | `28b5865` `78b282d` | 导入上游 `Carlife_on_box v2026.10.17`；Tbox / 威飒定制 | ✅ |
| Round 4 | `f0bf84a` | 绕过原厂 `sys.phonecall.state=1` 拦截，修复 `stopInput` SIGSEGV | ✅ |
| Round 5 | `261765a` `36a4c6a` `972fd22` `027bfab` | 通话回声消除；TboxUsbSwitch v1.5；QCOM-BTD interop 实验；开机提前出现 CarLife 设备 | 回声未消、开机未拉起 |
| Round 6 / 6b / 6c | `d699fb5` `f278f4e` `c477f21` `3818ca5` | 远端门控残余回声抑制；开机内核日志 AOA 监听；post-fs-data 早期 USB；手机蓝牙仅音乐；仅音乐静音修复；TboxUsbSwitch v1.6 | 回声 ✅、蓝牙分连 ✅、开机拉起待测 |
| Launcher | `9da347e` … `f8cc04a` | TboxCarLauncher 驾舱桌面、BT 媒体桥、AMap HUD、60fps 开关、USB 字符串屏蔽 + os_desc、RRO 防开机循环、开源发布准备 | 🟡 |
| Round 7 | 未提交 | 代码审查修复 #1–#6 + Codex 复审 3 项 | 🏠 / 🟡 |
