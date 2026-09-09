# 占卜卷轴：未知类型与全部已知两种原流程

两项均已完整通过，合计 **37 次**与响应同 `state_version`、同 `scope_id` 的原 GUI 中文窗口化核验。公开 CLI 游戏文案逐响应检查为英文，两项正常退出码均为 0，无清理异常。详细请求、版本、原窗口类与 profile 见 [JSON 证据](cli-divination-validation.json)。

这组只增加测试初态和测试断言，没有修改生产代码、翻译规则或正式存档。运行时 `runtime-c0369aec996a4859b9d4b2471fef1a04`，版本 `CLI.0.8.10`，构建 ID `f96ddbb8c5f6e7512546144dba45fa695f6fc1bfb325bad2deaee026979a0ebe`。两项及补跑使用相同冻结二进制。

| 用例 | 触发前准备 | 原 CLI 操作与实际结果 |
| --- | --- | --- |
| `itemui:divination-unknown` | 正常新战士、安静测试区域、两张已知占卜卷轴；保留其余原始知识集合，本次有 33 类未知身份。 | 先打开详情并 Back，数量和知识均不变；再次打开并执行原 READ，消耗一张、原随机过程鉴定 4 类，显示真实 WndDivination；Back 关闭原结果窗，回到同局且不返还卷轴。 |
| `itemui:divination-known` | 同样两张卷轴；仅在初态用原 setKnown 将占卜可选的药水、普通卷轴、戒指身份设为已知。 | 详情 Back 不消耗；原 READ 消耗一张，知识集合保持不变，没有 WndDivination，显示原 `There is nothing left to identify!` 并返回正常游戏状态。 |

未知用例本次实际显示 `ring of elements`、`scroll of recharging`、`potion of paralytic gas`、`scroll of teleportation`。客户端根据已显示窗口读取这些名字；测试内部只在原 READ 的返回版本，对照已经发生的四个已知身份增量、未知数量减少和卷轴消耗。没有查询未来随机抽选，也没有固定、重置或覆盖 Random。原结果窗的 Java 类只用作动作之后的测试断言，不用于选择动作。

原实现 [ScrollOfDivination.doRead](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfDivination.java) 仅在有新鉴定结果时创建 WndDivination。全部已知用例核验的是原无窗口日志分支，没有为满足测试名称而手工构造结果窗。

首次未知用例已正确打开原结果窗并显示四个英文名字，但测试把英文标题错误地固定为 `Scroll of Divination`；实际公开资源呈现为 `scroll of divination`。这一失败及原轨迹保留。修复只将完整同名标题做大小写无关比较，随后在同一冻结构建的新隔离 profile 补跑未知项；已通过的全部已知项没有重跑。没有借此跳过正文、删除字段或放宽非英文检查。
