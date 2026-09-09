# 低频场景：中文界面与英文 CLI 复验

本轮完整执行了既有 22 项低频夹具：**14 项通过，8 项失败**。通过项共完成 376 次原 GUI 的同版本断言；连同失败前已完成的观察，合计 545 次。所有进程均已退出，失败清理没有发生额外异常。整个矩阵返回非零退出码，不能把本轮记为全通过。

使用冻结运行时 `runtime-492eab0194ec44ba900b788f10016cf7`，公开握手版本 `CLI.0.8.5`，构建 ID `fe86ecae80bce2ef1ffbfe0ab5b32d5c8c7b7d0aa8eab5b60dd92d769e162de4`。运行时中的 `python-source` 同时保留了本次执行的 runner 和共享 helper 源码。详细结果、每项 profile、原始响应与失败请求 ID 见 [JSON 证据](cli-p6-english-validation.json)。

既有 [低频基线](cli-p6-low-frequency.md) 及其 JSON 保持原样。本轮只更改测试 runner，没有修改生产翻译、游戏规则或初态夹具。所有 profile 都在 `desktop-control/build/fixtures/p6-…`；没有访问或启动正式实战存档。护符结局属于构造状态测试，不计作正常通关。

此次补强的验收方式：

- 复用共享 `FixtureClient`，每个响应都递归检查游戏文案为英文，包含事件和 `request.get` 内嵌响应；原始审计请求、文件路径等数据不做翻译。
- 打开 `verify_gui=True`。除退出响应之外，每个包含观察的响应都匹配相同 `state_version`、相同 `scope_id` 的原 GUI 断言，核对 `CHI_SMPL` 与 `fullscreen=false`。等待中的操作读取其自身最终回执，并对最终回执再次做这项匹配。
- 同伴武器、防具的转交与返还，及铁匠、炼金、复活的取消不消耗断言，直接使用相应操作返回的版本；不通过追加查询补等来证明这些操作已经完成。
- 成功用例通过原 UI 的正常取消与退出收尾，并要求退出码为 0。失败用例只关闭标准输入；不再追加确认、取消或其他游戏操作。第一条失败、独立清理错误与退出码分别保存。
- 每完成一项即写入矩阵结果，不因中途失败丢失先前证据。

| 场景 | 结果 | 同版本 GUI 断言 |
| --- | --- | ---: |
| `shop-trade` | 通过 | 38 |
| `shop-steal` | 通过 | 21 |
| `shop-steal-warning` | 失败 | 17 |
| `ghost-reward` | 失败 | 15 |
| `wandmaker-reward` | 失败 | 15 |
| `blacksmith-cashout` | 通过 | 25 |
| `blacksmith-pickaxe` | 通过 | 25 |
| `blacksmith-reforge` | 通过 | 33 |
| `companion` | 失败 | 33 |
| `alchemy-energy` | 通过 | 37 |
| `resurrect` | 失败 | 24 |
| `amulet-stay` | 通过 | 19 |
| `amulet-end` | 通过 | 32 |
| `shop-stack` | 通过 | 44 |
| `shop-steal-failure` | 失败 | 15 |
| `blacksmith-harden` | 通过 | 24 |
| `blacksmith-upgrade` | 通过 | 24 |
| `blacksmith-smith` | 失败 | 24 |
| `companion-attack` | 失败 | 26 |
| `companion-resummon` | 通过 | 20 |
| `blessed-ankh` | 通过 | 16 |
| `amulet-pickup` | 通过 | 18 |

8 项失败来自以下 4 个公开文案组：

| 组 | 受影响用例 | 实际已显示文本与阻塞点 |
| --- | --- | --- |
| 风险偷窃确认 | `shop-steal-warning`、`shop-steal-failure` | 点击公开偷窃选项后，“是的，我确定”存在资源歧义。后一项尚未真正执行随机偷窃，不能声称失败偷窃分支已覆盖。 |
| 奖励预览确认 | `ghost-reward`、`wandmaker-reward`、`blacksmith-smith` | 点击公开奖励选项后，“确定”存在资源歧义。铁匠造物已经验证原支付与强制奖励窗口，后续预览确认失败。 |
| 未祝福护符复活确认 | `resurrect` | 原保留物品不足警告中的“是的，我确定”存在资源歧义；本轮不能声称完整复活回路通过。 |
| 同伴实际对白 | `companion`、`companion-attack` | 原召唤后绘制的 `悲伤幽灵: "再次向你问好，战士。" `（含尾空格）不能安全组合为英文。对白同时进入公开日志投影与 UI 投影，公开错误表现为 `AUDIT_UNAVAILABLE`；内部原异常是 `PublicTextUnavailableException`，没有发现 SQLite 物理错误。 |

其他确认文案失败公开表现为 `EXECUTION_UNKNOWN`。测试不假定失败动作未产生效果，也不自动重放或回滚。内部异常只用于离线诊断自身夹具，未作为下一步动作或目标选择的信息。

这些失败均已经交给生产语言模块负责人。修复后只需以新的冻结构建复跑相应失败组；本轮 14 项通过记录无需无故重跑。
