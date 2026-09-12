# 低频确认窗口的公开上下文识别

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

本组修复只为已有中文按钮选择对应的原英文资源。`PublicDialogSignatures.identify(publicUi)` 是纯函数，只读取已经公开的当前 UI DTO，返回 `steal_warning`、`resurrection_warning`、`reward_confirmation` 三个布尔值。它不持有游戏对象，不访问 Window 的 Java 类型、任务状态、物品类型或鉴定标志，也不缓存前一窗口。

三个标志都要求当前场景为 GameScene、有模态窗口、公开控件树中只有一个顶层窗口。所有参与识别的节点必须属于该树；正文、按钮和按钮子文字不能被标为 clipped 或 partial；窗口只能有两条完整的非按钮文字和两个启用的按钮。重复 ID、断裂或循环父子关系、第二个窗口、额外交互控件、正文或按钮中相互矛盾的 text/label 都被拒绝，单独 hover label 不能代替缺失的已显示 text。

| 标志 | 当前公开证据 | 允许使用的原资源 |
| --- | --- | --- |
| `steal_warning` | 神偷袖章标题、完整的充能不足且失败后关店警告、原 Yes/No 两按钮 | `windows.wndtradeitem.steal_warn_yes`、`steal_warn_no` |
| `resurrection_warning` | 缺少物品标题、完整的未选满两物品复活警告、原 Yes/No 两按钮 | `windows.wndresurrect.warn_yes`、`warn_no` |
| `reward_confirmation` | `inspected_item.control` 指向唯一当前窗口、物品标题与完整正文、恰好 Confirm/Cancel 两按钮 | `windows.wndsadghost.confirm`、`cancel` |

[幽灵奖励](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndSadGhost.java)、[制杖匠奖励](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndWandmaker.java)和[铁匠造物奖励](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java)各自创建一个继承 WndInfoItem 的 RewardWindow；三个实现都直接复用 WndSadGhost 的 Confirm/Cancel 资源。没有依赖一个并不存在的共享 WndConfirmReward 类。

奖励物品的标题和正文可以随当前实际物品变化，因此识别只使用公开检查根与完整控件形状。`level_known` 无论为 true、false 或未提供，都不参与识别。原正文自身仍须通过现有完整文本翻译，不能因为确认窗口匹配而得到占位文字或未知正文的兜底。

消费端只在当前按钮或该按钮的公开子文字上使用相应资源域。“不，我改主意了”在偷窃与复活窗口分别映射为原生 `No, I changed my mind` 与 `No, let me reconsider`，不会全局替换成同一句英文。

`PublicDialogSignaturesTest` 当前 9 项通过，包括从原双语 properties 加载的正例、每个条件缺失、裁剪、跨树拼接、额外交互、隐藏状态变化、旧 UI、按钮/子文字/action 的实际英文投影，以及未知完整正文仍明确失败。六项真实场景复跑结果见下文；之前 22 项基线与 8 项原失败不回写。

本组仅复跑此前失败的 6 个确认用例，**5 项完整通过，1 项在确认之后出现新的对白翻译失败**，共 161 次同版本原 GUI 中文窗口化断言。之前已通过的 14 项没有重跑。使用冻结运行时 `runtime-e059db884557412cbab28be75c4930ee`、版本 `CLI.0.8.7`、构建 ID `f67189df2341300c337703db4dbc30f2521f279044ad5b0aa15a398f837a5e22`。每项请求仍严格检查 English 游戏文案，且所有进程已退出，无清理异常；runner 整体返回 1。

| 用例 | 本轮结果 |
| --- | --- |
| `shop-steal-warning` | 完整通过：原风险警告显示，取消不花费金币或充能。 |
| `shop-steal-failure` | 完整通过：一次原生随机尝试实际失败，店主逃走，未取得物品，未支出金币或充能；没有覆盖 RNG 或重复试到失败。 |
| `ghost-reward` | 完整通过：预览取消、重新确认、获得物品和任务完成。 |
| `blacksmith-smith` | 完整通过：支付、强制奖励窗口、预览取消、确认取得奖励并清空待选列表。 |
| `resurrect` | 完整通过：原中毒死亡、不足物品警告取消、重新选择并复活；保留物品可检查、灰色遗失物品不可打开、拒绝 ID 被消耗。 |
| `wandmaker-reward` | 原 Confirm/Cancel 预览、取消及重新确认已经通过；领取后的实际对白 `老杖匠: "祝你在试炼中好运，战士！" `（尾空格保留）仍为 `no_safe_resource_translation`，整项保留失败。 |

制杖匠的新失败与原“确定”按钮歧义是不同位置，已交给 NPC 引号对白小组。没有为了通过测试跳过这条对白，也没有把操作失败当成未产生任何游戏效果。每项 profile、原始回执及实际效果证据见 本组 JSON（历史 JSON 已删除，可查 Git 历史）。
