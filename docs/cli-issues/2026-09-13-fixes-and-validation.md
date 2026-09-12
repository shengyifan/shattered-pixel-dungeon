# 两次 spdctl 实战异常的修复与验证

2026-09-13，基于 `feature/mac-cli` 的 `da53ba6c054d084ba138af8f19be7b05e921593b` 整理本批修复。修复先在 CLI.1.0.0 验证，随后按用户约定补升为 CLI.1.0.1；游戏 3.3.8、协议 1、审计 schema 4 不变。本批修复未推送或发布。

两个根因均已修复。380 项单元回归和六个独立真实引擎场景通过，开发运行时与本地 macOS ARM64 应用已重建。

## 教程：有限交互动画没有进入稳定边界

[原问题记录](2026-09-12-warrior-tutorial-stale-state.md)显示，关闭教程书页后 CLI 返回 `player_ready`，当时只有 12 个控件。随后工具栏和背包出现，控件增加到 93 个，原版本被拒绝。

`GameScene.endIntro()` 的原有 2 秒 `Tweener` 前半段显示英雄状态栏，后半段才启用工具栏及背包。该动画继承的 `hasPendingCallback()` 默认返回 false，控制器因而在交互尚会改变时返回了“稳定”状态。`STALE_STATE` 的保护本身正常。

修复仅为这一个教程动画声明尚未完成的交互。现有控制器会等待它自然结束，再通过后续绘制确认；没有改变 2 秒时长、GUI 动画、输入语义或版本签名，也没有增加固定睡眠/自动重试。

相关代码：

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java`：`endIntro()` 中的 `hasPendingCallback()`。
- `game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/TutorialBoundaryTest.java`：直接推进真实教程 Tweener 的生命周期，覆盖有/无固定背包、自然完成和后续绘制、停用/移除及装饰动画不阻塞。
- `desktop-control/src/test/python/tutorial_boundary_smoke.py`：生产 `SpdctlLauncher`、全新隔离 profile、原生教程和公开协议。

真实验证：

| 原界面设置 full_ui | ui.back 耗时 | 返回时控件数 | 跨过原动画时长后原版本仍可移动 | 真正旧版本拒绝且无移动 |
| --- | ---: | ---: | --- | --- |
| 2：固定背包 | 2.550 秒 | 93 | 通过 | 通过 |
| 0：无固定背包 | 2.512 秒 | 41 | 通过 | 通过 |

脚本先立即断言返回时 Wait / Inventory 已可用，再等待 2.2 秒检查控件和版本不变；等待不能掩盖提前完成。动作失败不会刷新版本后重试。

## 升级预览：“防御”的英文歧义

[原问题记录](2026-09-13-warrior-upgrade-preview-execution-unknown.md)中，升级卷轴已由直觉符石鉴定，READ 后选择可用的 Cloth Armor 即进入 `EXECUTION_UNKNOWN`，没有最终升级确认动作。

在修复前冻结的独立 `ui:upgrade` 引擎中，选择布甲复现相同错误。仅读取这个新建测试 profile 的异常记录，得到：

```text
DisplayedTextEnglish$PublicTextUnavailableException
  PublicEnglishProjection.value
  UiBridge.describeUi
  GameController.capture
  GameController.atBoundary

Translation diagnostic: no_safe_resource_translation
```

失败的聚合窗口文本为：

```text
升级这件物品会永久提升其如下属性：
+1
防御
0~2
1~3
重量
10
9
```

资源 `windows.wndupgrade.blocking` 和 `items.stones.stoneofaugmentation$wndaugment.defense` 的中文均为“防御”，英文分别为 `Blocking` 和 `Defense`。原投影仅对升级预览的“返回”做上下文区分，缺少这个属性行。原生目标选择后捕获新 UI 时翻译抛错，机器会话按既有规则锁定未知执行状态。

修复在已有完整升级预览签名下，仅对 `window` / `text` 节点中完整的“防御”行采用 `Blocking`。它同时处理独立属性文本和窗口聚合文本；其他行继续走原安全翻译。强化界面仍是 `Defense`，缺少签名、错误角色或未知前后文仍被拒绝。

原生升级回调、装备和卷轴消耗规则、`MachineSession` 的 UNKNOWN 隔离均未修改。隔离复现证实了这条触发路径；没有读取原正式局的存档或内部异常记录。

相关代码与回归：

- `game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/DisplayedTextEnglish.java`。
- `game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/AdditionalPublicMenuEnglishTest.java`：聚合/独立属性、原 DTO 不变、二次投影、歧义场景与未知文案拒绝。
- `desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/ItemWindowFixtures.java` 与 `desktop-control/src/test/python/upgrade_preview_smoke.py`。

四个真实引擎场景全部通过：

| 卷轴知识 | 界面 | 预览、返回及取消 | 最终升级及保存 |
| --- | --- | --- | --- |
| 原直觉符石 UI 鉴定 | 固定 InventoryPane | +0 保持，真正取消不消耗 | 护甲/纹章各 +1，单张卷轴消耗一次，保存成功 |
| 原直觉符石 UI 鉴定 | 弹出 WndBag | 同上 | 同上 |
| READ 时原生鉴定 | 固定 InventoryPane | 首次 READ 取出一次卷轴，取消警告选 No 后恢复选择 | 同上 |
| READ 时原生鉴定 | 弹出 WndBag | 同上 | 同上 |

每组执行三次预览，覆盖 Back 按钮和原返回操作；最终确认后仍能查询、等待、保存和正常退出。内部夹具断言只用于动作后的等级、纹章、数量和升级计数核验；所有操作选择来自公开状态。没有用自动陈旧版本重试掩盖错误。未知卷轴取消警告中的 Yes 分支不属于这四个最终升级用例。

## 统一回归与产物

- 单元测试：协议 8、游戏控制 276、桌面控制 96，共 380，失败/错误/跳过均为 0。
- 新增六项单元测试；真实引擎为教程 2 组、升级 4 组，全部保持中文窗口 GUI 和英文 CLI，所有测试进程正常退出。
- 静态 UI 基线重新生成并核对：仅 `GameScene.java` 后续 12 个条目的行号加 7，类型、路线、方法摘要及汇总不变；这份静态清单不被当作运行验证。
- 初次沙箱内 GUI 测试因 macOS 窗口服务不可用而停止，没有进入复现步骤；随后用获准的本地窗口服务访问重新运行。该基础设施失败没有计入通过证据。

复现和验证命令：

```sh
./gradlew :game-control:generateUiCoverage --offline --console=plain
./gradlew :control-protocol:test :game-control:test :desktop-control:test :desktop-control:writeTestRuntimeClasspath :desktop-control:writeRuntimeClasspath --offline --console=plain
python3 desktop-control/src/test/python/tutorial_boundary_smoke.py
python3 desktop-control/src/test/python/upgrade_preview_smoke.py
./gradlew :desktop-control:packageMacArm64 --offline --console=plain
```

本地原始证据保留在忽略的 build 输出中：

- `desktop-control/build/fixtures/upgrade-baseline-a9b9b47d0f3445d39b06b778724146f5/baseline-diagnostic-summary.json`：旧引擎复现及测试异常栈。
- `desktop-control/build/tutorial-boundary-validation.json`：教程两种界面。
- `desktop-control/build/fixtures/runtime-81d8e19eb7df48e9b0541c3f582fb698/upgrade-preview-results.json`：升级四组合。
- `desktop-control/build/bugfix-validation/package-verification.json`：本地应用包核验。
- 各模块 `build/test-results/test/`：JUnit 结果。

上述修复功能验证的 CLI.1.0.0 构建标识为 `b82522d66daa8b5991f82a33a0766b940d07e2f9e2783b9a9a860e6df6a70a41`。应用位置为 `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`，包内 `Contents/MacOS/spdctl` 是机器入口。随后版本补升 CLI.1.0.1；旧的测试结果和故障证据保留原版本及构建标识，不回写成新版本实测。

包验证涵盖 ARM64 启动器、plist、本地签名、帮助内容、构建标识及修复相关编译类一致性，测试夹具类不进入包。六个 GUI 场景运行于生产类的冻结运行时，未把它们表述成逐场景的包内 GUI 复测。应用为本地 ad-hoc 签名，没有提交 Apple 公证。

## CLI.1.0.1 版本补升验证

2026-09-13 用户明确要求每次修复后升级 CLI 版本。本批据此补升修订号为 CLI.1.0.1，同步三个生产版本入口、当前文档与 CHANGELOG，重新生成开发运行时和本地 macOS ARM64 应用。基础游戏、协议及审计 schema 不变。

新构建标识：`c70decaf4fe33124f06aee9782b3a4b65637d07f1a6027a87e4e0be965373ce9`。

- `:desktop-control:test` 的 96 项测试通过，失败/错误/跳过为 0。
- `bin/spdctl --version`、实际包内 `spdctl --version`、新测试进程 `protocol.info.cli_version`、包内与构建目录的 `control-build.json.cli_version` 均为 CLI.1.0.1。
- 握手的 build_id 与包内构建清单一致；测试只到中文窗口菜单，随后通过 `app.quit` 正常退出，未创建地牢新局，也未接触原暂停会话。
- 包内帮助与源码文档一致，plist 和本地签名通过，包内无测试夹具类。
- 验证记录：`desktop-control/build/bugfix-validation/package-verification-CLI.1.0.1.json`；隔离 profile：`desktop-control/build/package-check/version-1.0.1-f2566332f4d34732aec8ea434ad7d517`。

此前六个真实修复场景仍对应上文 CLI.1.0.0 修复构建。此次只补升版本元数据，因此没有把旧场景报告改写成 CLI.1.0.1 的实测，也未重复所有游玩场景。

## 原故障现场

原会话 22490 仍运行旧的冻结代码，并保留 `execution_unknown`。本次修复没有操作、重启、强制清除不确定状态或直接读取这局的存档/内部诊断；也没有以测试夹具替换正式进度。新代码不会热替换进旧 JVM，后续恢复游玩需要使用新运行时启动，并重新从公开状态确认可恢复进度。本轮不计正式通关。
