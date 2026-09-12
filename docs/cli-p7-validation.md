# P7 真实引擎专项验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09 完成的完整批次为 **188 / 188 通过**。测试使用独立 test source-set 的 `FixtureLauncher`，在新建隔离 profile 中布置条件，之后通过生产 `MachineSession`、`GameController`、`UiBridge` 和原生游戏窗口执行公开 NDJSON 操作。没有截图、OS 键鼠输入或 Computer Use。

这些 profile 设置了测试用属性、资源、装备、天赋或场景条件。全部标记 `test_fixture=true`、`counts_as_win=false`，不计作正常通关，也不能替代六职业从主界面开始的完整胜利验收。

完整的逐例结果和证据字段保存在 cli-p7-validation.json（历史 JSON 已删除，可查 Git 历史）。文件只包含测试夹具结果和仓库相对路径，不包含存档、内部诊断数据库或个人 profile。原始运行输出位于 `desktop-control/build/fixtures/`，未纳入源码。

## 已运行的矩阵

| 族 | 正常条件 | 资源不足 | 已验证内容 |
|---|---:|---:|---|
| 初始职业 | 6 | — | 通过公开主菜单选择各职业、开局，读取其库存并打开一个代表性初始物品的真实窗口。 |
| 子职业 | 12 | — | 9 个现有 ActionIndicator 入口；战斗法师、术士、守望者走其原生法杖或灵能短弓操作。检查实际效果或资源变化。 |
| 护甲能力 | 19 | 19 | 18 个职业能力及 Ratmogrify；12 个目标型能力验证取消，WarpBeacon 另验证付费传送与实际位置变化。 |
| 牧师法术 | 27 | 27 | 每个具体法术通过真实 Holy Tome 菜单完成施放并扣费；12 个目标型法术先取消再施放；Body/Mind/Spirit Form 完成原有嵌套选项和确认。 |
| 决斗者武器能力 | 32 | 32 | 31 个近战目录中的具体能力加任务物品 Pickaxe；25 个目标型能力验证取消，全部正常施放检查真实充能消耗。 |
| 武僧招式 | 5 | 5 | Flurry、Focus、Dash、DragonKick、Meditate 实际操作与能量消耗；四项不足时按钮禁用，零能量 Flurry 无动作指示器。 |
| 低频 UI | 4 | — | 鉴定、升级、未知卷轴选物取消的强制确认，以及三枚种子炼金；炼金保持同一局 scope。 |
| 合计 | 105 | 83 | 188 个独立 profile。 |

资源不足用例遵循原生 UI 行为。牧师法术按钮会变暗但仍可点击，因此验证的是已有控件的 `dimmed` 表现、原生拒绝、无目标选择及无扣费；没有把它们错误地要求为 disabled。

法术、武器和武僧招式用例由当前 [静态输入清单](../game-control/src/test/resources/cli-ui-coverage.json) 的具体类型生成。抽象基类、继承空实现的类型不冒充可施放能力。完整清单的静态覆盖与本次运行结果分别记录。

## 操作完成的断言

施放类用例使用最终操作响应的 `state_version` 查询 **test-only 断言检查点**，确认资源或效果已在该响应中完成；不会再发一次 `state.get` 来等待尚未完成的动画。内部检查点不参与目标或命令的选择，目标只来自公开地图、可见实体和现有控件。

完整矩阵的固定运行时为 `runtime-5b463766c51d4c2096c0a3ddc46d251e`。报告复核后，BERSERKER、FREERUNNER、CHAMPION 的即时效果断言进一步提前至操作的直接响应；这三项在 `runtime-df5bee27df5e4cbb9ad31ea8f307b53c` 中 **3 / 3 通过**。补跑不是额外能力数量，也不掩盖原批次断言位置的差别。

单项 Spear 回归曾实际发现：原生动画回调尚未执行，CLI 已宣称 `player_ready`。引擎加入待执行游戏回调的完成检查后，严格同响应回归通过：返回 v11 的同版检查点已从 8 充能降至约 7.034，目标 HP 已从 10000 降至 9908。此修复也包含在上述完整矩阵的固定运行时中。

BodyForm 的真实 ItemButton 长按专项在 `runtime-ce21afbe69174559894d6f27dcf929e4` 中 **1 / 1 通过**：当前控件支持的 long 回调返回 false 时，UI 和充能不变且正常完成；随后普通点击和确认成功施放，充能从 10 降到 8。有关这一原生行为及 UI 意图版本的说明见 [cli-ui-intent-version.md](cli-ui-intent-version.md)。

## 复现

```sh
./gradlew :game-control:generateUiCoverage :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/fixture_smoke.py --families base,spell,weapon,monk,ui --include-empty
```

运行器先冻结项目运行时 jars/classes/resources，避免运行中的 JVM 遭遇并行构建覆盖 jar；依赖缓存中的版本化 jar 保持原路径。每例使用新的唯一 profile，保存 `test_fixture.json`、公开 `public-trace.jsonl`、测试断言及 `fixture-result.json`。汇总 `results.json` 位于对应 `runtime-*` 目录。任何失败都会使整个运行器以非零状态退出。

`ui:travel` 是另一个供长距离移动/取消测试使用的独立夹具：有至少 12 格的已映射安全直廊，保留真实调度器和精灵回调；它不计入此处 188 项，也不能通过通用四项 UI runner 的结果推断已验收。

## 本记录的边界

这批测试逐一验证了上述能力的可访问性与一次合法操作，以及对应的资源不足条件。它没有穷尽每件装备、天赋组合、所有非法目标、敌方状态、复活道具、召唤物多轮指挥或全部商店/任务变体。被动子职业的代表性攻击也不等于其所有被动机制组合已经验证。静态路线检查不会把这些缺口自动转为通过。

固定运行时证据描述的是这些批次实际执行的内容；源代码以后修改时，需要根据改动范围选择重跑。六职业正常通关、发行包启动/重启和长动作取消分别需要各自的真实运行证据。
