# 永久死亡与原菜单重开验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09，两个独立实机用例通过。公开 CLI 游戏文案为英文，GUI 为中文、窗口化，共完成 47 次 GUI 环境断言。本组只构造死亡之前的初态，之后全部通过原 CLI 控件执行，没有调用测试版 die/fail、创建终局窗口或读写正式 profile；不计正式通关。

完整结果见 cli-ending-death-validation.json（历史 JSON 已删除，可查 Git 历史）。

| case_id | 实际通过的原流程 | GUI 断言 |
| --- | --- | --- |
| `ending.permanent_death` | 无 Ankh、1 HP、原 Poison。公开 wait → HP 0 / ended → 唯一 run.ended lost → 原死亡菜单 → 原英文 Succumbed to Poison 行 → 实际 WndRanking 完整 Stats → Back 回排行榜 → Back 回 Title。 | 24 |
| `ending.restart_after_death` | 相同死亡前条件，公开 wait 完成原死亡后，原菜单 Start New Game → 选角页当前已经公开可用的 Start → 必要的原 Continue → 原生健康新英雄和新 run scope。 | 23 |

两条均确认没有 Ankh、原排名记录增加为一且没有胜利记录。排行详情确认实际 `WndRanking` 类型，并读取 Strength、Game Duration、Maximum Depth，排除无法加载附加信息的错误退化页。

重开后确认新英雄满血、一级、没有重复注入 Poison；旧 run_outcome 不串入新局。旧 `death-trigger` ID 在新 scope 可用于新查询，旧 scope 的原死亡响应完全不变，原 lost 事件保持唯一，新局没有结束事件。

排行用例冻结运行时为 `runtime-32d037654eb84dcd9a1626ca4d9d37fd`，build ID `ecbc869926f9fcc0534b8cd84e20483150846cdca82b4e5f97954e866b794a79`；重开用例为 `runtime-dd4b1abc9e054d97bb508214b534fb46`，build ID `f55e5d93369bbf96cd8623f321cb99cdcd450ea75c9a33644ed50e6fb43c32c4`。两个独立用例分别保留准确构建身份，没有把未复跑的案例归给另一构建。

## 初态和可审查边界

`EndingScenarioFixtures` 保留原一级 Warrior、库存和地图，只清除可能竞争伤害来源的怪物/生成器，将 HP 设为 1，附加原 Poison。它不调用 `Hero.die`、`Dungeon.fail`、`Rankings.submit`，也不直接创建 WndGame、RankingsScene 或 WndRanking。死亡、公开结果事件、排行归档和窗口全部来自原行为。

每个 profile 仅注入一次。第二局由原 Start 流程创建，fixture 不再改动它；满血和无 Poison 断言验证了这一点。私有 ending/GUI checkpoint 仅用于操作完成后的核验，不提供下一输入目标。

## 保留的测试修正和独立未译缺口

- 死亡界面保留普通 HUD 菜单及死亡 banner 菜单，公开 actions 中存在两个合法的 Menu。最初测试错误地要求标签全局唯一；现从公开列表选择一个原 Menu control，再强验实际 WndGame 及死亡专有的 Start New Game/Rankings 等内容。这不是生产缺陷。
- 原死亡菜单已经选定旧职业。通用新局 helper 再点已选 Warrior 会打开独立 Hero Info，而非直接开始新局。在 `ending-death-restart-479b94aa0cce4ce885c4b3f003596786` 中，该窗口的破损纹章说明段落出现 `no_safe_resource_translation`。原 profile、失败和内部可见文本诊断均保留，此 Hero Info 缺口仍未在本组修复或标为通过。
- 本组重开的目标流程按实际公开控件收窄：只有 Start 当前已 advertised 且不存在模态窗口时，才点击原 Start。没有修改数据、隐藏模态或取消一个已打开的 Hero Info 来跳过失败。若实际出现区域 Continue，仍通过原 Continue 完成。

## 复跑

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/ending_scenario_smoke.py
```

可用 `--cases death-ranking` 或 `--cases death-restart` 单独执行。通过结果分别落盘，失败保留诊断并清理测试进程。本组没有修改生产语言或 UiBridge；Amulet/Surface、25 层开启 Ascension 及其他结局分支仍属于后续独立覆盖。
