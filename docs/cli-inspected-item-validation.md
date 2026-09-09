# 物品正文知识时点与 Spear 英语投影

这一组将原生 `WndInfoItem` 正文使用的等级知识位关联到当前公开窗口，解决简体中文 Spear 能力说明中 actual / typical 两条资源完全相同、英文却有无 `typically` 的差别。CLI 不读取隐藏升级值来判断这两种说明。

公开 `ui.inspected_item` 只有 `control` 和 `level_known`。`control` 指向当前顶层窗口；`level_known` 是该窗口调用原始 `item.info()` 或卖品 `heap.info()` 前记录的布尔值。它描述当前缓存正文的知识时点，不代表背包中该物品后来是否已鉴定。这里没有物品类、对象标识、实际等级、伪造的背包 locator 或 equipped 字段。

`Item` 窗口及地面 `HEAP` 关联原本已展示的物品；`FOR_SALE` 关联原本已展示名称、价格和说明的顶层卖品。宝箱、上锁宝箱、水晶宝箱、墓穴、骸骨和遗骸均不关联隐藏物品。水晶宝箱原生已显示的“大类为法杖”提示仍可正常呈现，不扩大到实际法杖类别。

英语投影只在公开 UI 自带 `modal=true`、关联指向当前根 `window`、知识字段确为 Boolean，并且文本节点属于该窗口的公开父链时使用此上下文。缺失、null、字符串 `"false"`、错误控件、旧响应或另一个窗口都不能默认为未知，也不能借用当前背包中的同名物品。未带关联的历史 Spear 中文说明仍保守拒绝猜译。

转换器仅过滤两个明确 Spear 资源模板中的一个；伤害数字继续来自完整已显示字符串。全文拼接先按已显示的双换行分段，从而保留 Spear `stats_desc` 内部的单换行。该组合规则不会生成任何资源段落，不补回未显示的文本，未知尾文仍拒绝。

## 定向测试

`InspectedItemKnowledgeTest` 的 11 项测试覆盖最小 DTO、双隐藏世界、等级知识与诅咒知识相互独立、空知识不默认 false、实际/典型英文模板、未显示数值和尾文、完整原生正文、窗口父链绑定、错误或旧关联、重复查询不调用 `info()`、旧正文知识位不变、覆盖或移除窗口后清除关联。

该组连同现有 `DisplayedTextEnglishTest` 46 项、`PublicEnglishProjectionTest` 15 项、`UiBridgeTest` 12 项，共 84 项通过。另有 `PublicCellPromptEnglishTest` 8 项检查本轮实机发现的目标提示关联，定向合计 92 项。它们不启动游戏，不访问用户存档，不作为实机覆盖计数。

```sh
./gradlew :game-control:test --tests '*InspectedItemKnowledgeTest' --tests '*PublicCellPromptEnglishTest' --tests '*DisplayedTextEnglishTest' --tests '*PublicEnglishProjectionTest' --tests '*UiBridgeTest'
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/inspected_item_smoke.py
```

## 实机边界

夹具入口和后置断言均在 `desktop-control/src/test`，不会进入 release。全部 profile 位于 `desktop-control/build/fixtures`，标记 `test_fixture=true`、`counts_as_win=false`。GUI 使用简体中文和窗口模式，每个公开响应检查英文游戏文案；窗口的创建、查看、取消、目标选择和技能施放只能经公开 NDJSON。

- `inspect:known`：等级已知、诅咒未知时，打开窗口的同完成响应含 actual 英文和对应知识位。
- `inspect:unknown`：隐藏 +7 Spear 仍只有 typical 正文和 false 知识位。
- `inspect:old-body`：原始窗口建立后，测试夹具在安全边界调用原生 `identify(false)`；新的背包查询已知，旧正文及其位仍 unknown / typical；原 CLI 关闭后重开才变为 actual。
- `inspect:floor-sale`：同名地面物品和卖品分别依据各自正文知识位呈现，正常关闭。
- `inspect:containers-a/b`：六种容器装入不同法杖类别、隐藏等级、诅咒状态，两世界公开详情逐项相同且关联均为 null。
- `weapon:Spear`、`weapon:Spear:empty`：复用原生能力、目标取消与资源不足路径；实际耗能必须在完成动作自身返回的 state_version 对应断言中成立，不能通过后续 state.get 补等。

最终 8 个分支全部获得通过证据，见 [精简验证记录](cli-inspected-item-validation.json)。第二轮六个 inspection 场景和资源不足共 7 项通过；正常 Spear 在最后一次新运行中完成打开说明、目标取消、重新选择与真实施放，完成响应对应的充能从 8 变为 7.0341883。修复目标提示后没有重复运行其余已通过的七项，JSON 分别保留两次冻结 runtime。

首轮完整正文组合失败、第二轮正常能力目标提示失败的原始报告均保留。没有将失败局部状态或夹具伪造胜利计为通过。

目标提示修复仅复用同一公开 UI 中已存在的英文 `cell_prompt`，并要求 `cell_input=true`、`modal=false`。已显示中文必须完整匹配资源或模板，且该候选英文必须与公开 prompt 完全一致。不会从隐藏 selector 或武器对象选择，不接受旧 UI、中文 prompt、任意英文覆盖、缺失门槛、partial 前缀或未知尾文。`Choose a target` 与 `Select a Target` 的实际大小写也要求完全一致。
