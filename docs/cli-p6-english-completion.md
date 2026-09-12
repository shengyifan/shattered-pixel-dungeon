# 低频 22 项：英文 CLI 与中文界面完成索引

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

**22 个既定低频用例均已有通过证据，剩余失败为 0。** 这是三份冻结构建的分组证据，不是同一构建一次执行全部 22 项：原矩阵通过 14 项，确认窗口修复新增通过 5 项，NPC 引号对白修复新增通过 3 项。已经通过的 19 项没有在最后一轮重跑。

选定的 22 份通过记录合计完成 **642 次**同版本原 GUI 断言，均为中文 `CHI_SMPL`、实际窗口化 `fullscreen=false`。所有公开响应中的游戏文案都按 English 校验；退出前的观察与等待中操作的最终回执均按其自身 `state_version` 和 `scope_id` 对照原 GUI 测试记录。所有选定用例正常退出码为 0。

| 证据轮次 | 贡献通过数 | 版本 | 构建 ID |
| --- | ---: | --- | --- |
| 1 | 14 | `CLI.0.8.5` | `fe86ecae80bce2ef1ffbfe0ab5b32d5c8c7b7d0aa8eab5b60dd92d769e162de4` |
| 2 | 5 | `CLI.0.8.7` | `f67189df2341300c337703db4dbc30f2521f279044ad5b0aa15a398f837a5e22` |
| 3 | 3 | `CLI.0.8.8` | `2abdfe4ce29beeff6da747d2a87ed4dbbecc2a9ee1d6c7630eebca0194c09586` |

原 [22 项基线 14/8](cli-p6-english-validation.md) 和 [确认修复 5/6](cli-p6-dialog-signatures.md) 保持不变，其中的原失败、原错误码和对应 profile 未被覆盖。逐项版本、构建、原始轨迹和实际效果见 最终 JSON 索引（历史 JSON 已删除，可查 Git 历史）。

| 用例 | 通过证据轮次 | GUI 断言次数 |
| --- | ---: | ---: |
| `shop-trade` | 1 | 38 |
| `shop-steal` | 1 | 21 |
| `shop-steal-warning` | 2 | 23 |
| `ghost-reward` | 2 | 24 |
| `wandmaker-reward` | 3 | 24 |
| `blacksmith-cashout` | 1 | 25 |
| `blacksmith-pickaxe` | 1 | 25 |
| `blacksmith-reforge` | 1 | 33 |
| `companion` | 3 | 60 |
| `alchemy-energy` | 1 | 37 |
| `resurrect` | 2 | 40 |
| `amulet-stay` | 1 | 19 |
| `amulet-end` | 1 | 32 |
| `shop-stack` | 1 | 44 |
| `shop-steal-failure` | 2 | 20 |
| `blacksmith-harden` | 1 | 24 |
| `blacksmith-upgrade` | 1 | 24 |
| `blacksmith-smith` | 2 | 33 |
| `companion-attack` | 3 | 42 |
| `companion-resummon` | 1 | 20 |
| `blessed-ankh` | 1 | 16 |
| `amulet-pickup` | 1 | 18 |

最后三项的实际回归补足了：

- `wandmaker-reward`：完整预览、取消、重新确认、取得法杖并消耗任务物品；公开响应中出现完整 `Good luck in your quest, warrior!` 告别。
- `companion`：武器与护甲转交的自身操作回执、原召唤、目标选择取消、驻守、跟随和装备返还；公开响应中出现完整 `Hello again warrior.` 问候。
- `companion-attack`：给实际召唤同伴装备武器，通过公开目标指令指定可见敌人，固定原等待序列后验证真实伤害；同样包含完整英文问候。

NPC 文本修复只解析原 `Mob.yell` 已显示的说话者、引号内完整资源或模板对白及尾空格，不从隐藏对象或前一次消息补全；未知说话者、未知正文与未知尾文仍拒绝。该解析器的 6 项边界测试由语言模块小组完成。最后一轮运行时是 `runtime-27f9bc4e4cdf45deb484fcf8576fdbcd`，原结果保存在该目录的 `low-frequency-results.json`。

这些都是隔离的中间状态夹具。准备结束之后的动作与判断仅使用原公开 CLI；没有截图、系统输入、正式实战存档或强制随机结果。护符结束属于场景覆盖，不计作正常通关。
