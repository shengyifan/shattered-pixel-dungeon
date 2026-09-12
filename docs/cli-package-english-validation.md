# 实际 ARM64 app 包的英文协议验收

> 后续状态：用户随后授权清空全部本地生成数据，本文当时保留的应用/运行数据现也已移除；Git中的验证结果保留。参见[数据重置记录](cli-runtime-reset-20260912.md)。

2026-09-12，实际 **CLI.0.8.12 ARM64应用包验收通过**。直接运行包内原生spdctl，在受限PATH且无外部Java/classpath/agent下重新执行原始管道与完整新局/保存重启两项，不复用旧版raw结果。GUI为简体中文、窗口化，公开CLI游戏文案为英语。

当前源包：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`。构建标识为 `0843377cce445854462f092faef076ab24c6669cdd85a261ef1d965b3542a031`，与本轮真实浮字pan回归一致。

本轮完整报告：`desktop-control/build/package-check/english-dfcdae533f584239a6a71b1083b6c65c/result.json`，提交索引见 [验证记录](cli-package-english-validation.json)。本轮应用副本、两份隔离profile及其完整审计均保留。此前0.7.x旧应用和原始测试目录已按用户9月12日指令清理；下文旧版本部分是历史说明，不代表那些原路径仍可打开。

## 执行边界

- 仅调用交付包的 `Contents/MacOS/spdctl`，包复制到 `desktop-control/build/package-check/english-*/中文 应用目录 with spaces/`，原交付包不修改。
- 两个独立 profile 都使用中文和空格路径。只预置 `language=zh`、`fullscreen=false`；没有设置教程完成，没有注入装备、角色或存档。
- 子进程 `PATH=/usr/bin:/bin`，移除 `JAVA_HOME`、外部 classpath 和 Java agent 环境参数。没有添加 source/test Java classpath、javaagent、截图或 OS 键鼠输入。
- 读取进程实际加载的 `libjvm.dylib` 路径，必须对应此次复制包的 `Contents/runtime`；同时确认实际加载了 SQLite JNI。Python 自己用于只读检查的 SQLite 版本单独标为 inspection 版本，不冒充包内 SQLite 版本。
- GUI 通过包内 `ui.display.language=zh` 和真实 `fullscreen=false` 检查，另外读取原 Settings 的 Fullscreen checkbox。原食物日志完成后，在私有审计中以 `event_sequence`、scope 和显示时间配对原中文实际显示与公开英文事件。

正式 profile 不允许在本脚本中指定或启动。各 profile 都明确为 test fixture，不计通关。

## 已通过的两个用例

| case_id | 检查 |
| --- | --- |
| `package.english_raw_pipe` | ARM64 入口与 JVM、签名与 plist、包内 build ID/版本、测试类未进入包、英文调用方 ID、中文路径、CRLF 和无换行尾帧原字节、无效 UTF-8 后恢复、重复 ID 拒绝、同 profile 第二进程被锁拒绝、EOF 不主动输出、原失败诊断导入、双库完整性。 |
| `package.english_game_and_restart` | 真正全新游戏通过原 CLI 完成必要教程；英文 state/actions 和严格角色装备名；原 Settings 模式确认；原库存 THROW 后取消且数量不变；原 EAT 的英文已显示日志与中文原文按事件序号配对；正常保存有成功回执、退出后由第二个包内 JVM 继续同局，位置、HP、等级、金币与完整 locator/name/quantity/equipped 库存一致，原历史仍可读取。 |

不会把源码 classpath 的成功算成包验收，也不会只看到开始过渡就认定游戏动作完成。

本次实际加载的 JVM 为包内 Java 25.0.4，三个采样进程的 libjvm 路径都属于各自的 app 副本；同时记录了实际加载的 SQLite JDBC 原生库。所有原生入口和 JVM 动态库均为 arm64，签名在完整游戏流程结束后再次通过 `codesign --verify --deep --strict`。public/internal 数据库完整性均为 `ok`。

食物事件 `sequence=7` 在 scope `run:f902b148-a28b-4cd7-9c8a-d17c2b19d48a` 中，公开英文为 `That food tasted delicious!`，同序号、同显示时间的原实际中文为“吃起来不错！”。这是包内原食物行为的显示证据，没有注入日志文本。

## 历史失败与测试修正（原始目录已按用户要求清理）

### CLI.0.7.1 首轮

源包：`desktop-control/build/fixture-runtime/CLI.0.7.1-3ba8b17b037d/Shattered Pixel Dungeon.app`。

公开 build ID：`3ba8b17b037d1c85a021b9518042588e7c88e8ccbc1831bfc0eac68d1202e7ae`。

本轮输出目录：`desktop-control/build/package-check/english-d898af7180944b5a9a650b7d00f4e3b6`。

包的四个原生二进制、签名、无测试 Java 类和 raw pipe 用例中的全部断言已经先完成，随后脚本进入全新游戏用例。在原 `cell.select` 走到并拾取公开可见指南后，实际显示片段“指南”存在资源歧义：`scenes.alchemyscene.guide=Guide` 与 `items.journal.guidebook.hint_status=Guidebook`。公开场景为 GameScene，原请求最终为 `EXECUTION_UNKNOWN`；内部异常记录 `ambiguous_resource_translation`。

测试没有切换到源码 JVM，没有设置 `intro=false` 或跳过拾取。失败进程已清理；全新游戏的库存、食物配对与重启部分尚未执行，因此不作为已通过。首轮完整报告为失败；后续脚本已改为每个成功子用例立即落盘，以便后续用例失败时仍保留其独立结果。

### CLI.0.7.2 测试胶水

重跑时先遇到 Python 公共 fixture 客户端新增字段与包客户端自有构造不兼容。包客户端现直接基于原始管道 Client，并自行执行完整英文和每次观察的 GUI display 强断言，不依赖测试专用 UiSceneAssertions。

随后原 Fullscreen checkbox 已真实输出 `{text: "Fullscreen", checked: false}`，但测试只匹配了 `label`，产生误报。定位器已同时接受公开的 `label` 或 `text`；准确英文内容与 `checked=false` 断言没有放宽。完整新 profile 重跑后通过。两项属于测试错误，未认定为 app 包或游戏故障。

## 复跑

先由集成任务构建并给出新的实际包路径，再执行：

```sh
python3 desktop-control/src/test/python/package_english_smoke.py \
  --bundle '/absolute/path/Shattered Pixel Dungeon.app' \
  --expected-cli CLI.0.8.12
```

参数版本必须与待验收的实际包相符。默认重新执行两个用例；仅在确认仍是同一未改变的冻结包时，才可用 `--reuse-raw-result` 引用其已成功的 raw-pipe-result.json，脚本同时要求 build ID 一致并在结果中保留来源路径。本验收不声称真实 Intel 硬件、Gatekeeper 公证或全部游戏场景均已覆盖。
