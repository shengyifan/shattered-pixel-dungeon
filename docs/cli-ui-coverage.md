# CLI.9.0.5 UI 覆盖清单

清单通过 `./gradlew :game-control:generateUiCoverage` 用 javac AST 解析当前 `core/src/main/java` 与 `SPD-classes/src/main/java`。它扫描命名类、匿名类、嵌套窗口、继承关系、输入回调、物品 actions/execute、职业动作、护甲能力与法术；不加载、初始化或构造任何游戏类。

完整条目、源码位置、语义路线与输入方法的源码校验标识保存在 [cli-ui-coverage.json](../game-control/src/test/resources/cli-ui-coverage.json)。每项明确标为 `static_only`，汇总字段 `runtime_verified_by_inventory` 永远为 `false`。**找到静态路线不等于实际操作验证，更不等于通关。**

## 生成、检查与更新

```sh
./gradlew :game-control:generateUiCoverage
./gradlew :game-control:test --tests '*UiCoverageInventoryTest'
```

检查会发现新增或修改的声明、输入方法、未分类的输入与清单过期。它也用一个全新且无法初始化的测试类型确认：未知 `onSignal(KeyEvent)` 会成为 `UNMAPPED`，不会自动标记已覆盖。更新基线后必须审查差异；不要仅重新生成文件来消除真实功能缺口。

同名输入方法仅按语义入口归类；鼠标事件监听器、背景交互和复合控件要有各自的明确路线。数字包含抽象基类、匿名类、接口和包装器，不能直接作为独立玩家操作数量。

## v4.0.2 合入后的静态审查

CLI.9.0.5 / protocol 9 的当前清单解析 **2,881 个类型、1,917 条记录**，其中
**858 条输入方法、160 个窗口、258 个按钮、33 个物品选择器、372 个物品类型**；
`unmapped_inputs=0`。职业、子职业、护甲能力、法术和武僧招式的类型范围保持下文所列口径。

CLI.9.0.1 新增的一个解析类型是只读 `PublicTerrain`，没有新增或修改输入路由。
CLI.9.0.3 新增无状态的 `StartOptions` 资格策略；该批 4 条既有输入方法更新开局资格条件，19 条仅更新行号，没有新增或删除输入路由。
CLI.9.0.4 adds source-bound journal/category/grid icons and catalog state, plus
native QuickRecipe material-sufficiency facts. Grid entries retain `entry/select`;
adding a status interface must not remove the original action route. That batch's
inventory has one changed document-click body (recording its displayed read
state), 49 line-only updates, and no added/removed input route. Runtime coverage
belongs to the [CLI.9.0.4 validation report](cli-9.0.4-player-info-validation.md),
not this static inventory.

CLI.9.0.5 的类型数新增 1，来自只保存作用域与展示就绪元数据的私有嵌套类
`VisualCueCollector.DropObservation`。全部 **1,917 条既有记录未变**：新增、删除、
方法体、行号与输入路线变化均为 0，`unmapped_inputs=0`。静态审查产物为
`game-control/build/ui-coverage-cli905-review.json`；它不证明实际运行覆盖。
本批实际测试和安装包验证见 [CLI.9.0.5 验证记录](cli-9.0.5-revision-validation.md)。

下列变更统计属于前一批 CLI.9.0.0 的 v4.0.2 合入：与 `ff05ece42` 的 CLI.8.0.2 基线比较，记录新增 25 条、删除 18 条、方法体或其他
语义字段改变 28 条；另有 233 条仅清单中的源码行号变化。变动涉及 65 个文件，
其中 31 个文件含新增、删除或非行号变化，已经按实际原生入口逐项审查。
“仅行号变化”仅说明该条记录的保留字段和方法体摘要相同，不表示该文件中的其他构造器或游戏逻辑未改变。

上述 v4.0.2 合入审查直接运行已编译的 `UiCoverageInventory` 主类生成清单，仍使用同一 javac 源码解析器；
没有放宽分类器，也没有通过新建游戏对象来补足清单。生成与审查产物位于忽略目录
`desktop-control/build/cli9-review/ui-review.json`，包含所有变动记录的前后值、源码指纹和逐文件判定。
这些是静态证据；CLI.9.0.3 的后续开局资格测试和安装包专项见其[历史验证记录](cli-9.0.3-unlock-validation.md)。

| 已审查原生入口 | CLI 9 的访问与约束 |
|---|---|
| Imp 对话、CityLevel 宝库入口、Hero 宝库受伤提醒 | 由原生对象交互或切层触发现有窗口；受伤提醒必须点确认，`back` 保留原生无操作行为；不能在确认前假定切层已完成。 |
| VaultMirror、VaultTokenDoor | 从公开对象的当前格交互进入真实选项；镜子领取、背包满时落地，以及代币门的不足/确认/消费分支由原 callback 执行。对象不会被导出为拥有血条和 Buff 的生物。 |
| EscapeCrystal、VaultBeacon、ImpStatue | 当前物品菜单是唯一动作来源；保留离开确认、按原生条件启用的带出物品选择、最终确认、取消和过场。进出宝库后重新观察背包及所有 locator。 |
| WndRanking 的宝库物品按钮 | 仅点击原生图标才打开 `WndVaultItems`；查询不能执行其历史角色物品恢复 callback，也不能把非 GameScene 中残留的 Dungeon 对象当作当前人物公开状态。 |
| WndInfoCell 自定义地形 | 保留原生 `customTiles` / `customTerrain` 优先顺序和 `alwaysVisible` 描述门槛；完整动态描述通过 `cell` 的 examine 操作获得。地图查询只返回公开基础表象和已知格环境，不调用 `image()` 或构造窗口。 |
| 法杖灌注、强化石、召唤元素物品选择 | 空法杖也需原生最终确认；强化石取消可能重新打开物品选择，不能假定返回游戏；召唤物图标来自已选中的原生表象。 |
| Trinity、Feint、WarpBeacon、WildMagic、Radiance、神器与戒指动作 | 当前按钮/目标选择器继续调用原能力；新费用、装备门槛、宝库禁止装配、伤害或目标变化不在 CLI 中重复实现。 |
| 桌面背包、右键菜单、工具栏径向菜单 | 原生 callback 保留下一窗口偏移；径向物品列表包含第二把武器，按当前 option 和顺序操作。 |
| 更新记录、每日挑战、WndImpOld | 更新记录按钮由 helper 动态创建；每日日期边界取原生结果；WndImpOld 是上游类重命名，不是 CLI 旧协议或旧 profile 的兼容入口。 |

## 语义路线

下表的 `ui.activate`、`cell.select` 等名称是静态清单与内部适配层的语义路线，
不是可以直接发送的协议 9 命令。公开操作使用本帧广告的 `click`、`cell`、`back`、
`untarget` 等短命令，具体参数以[当前英文手册](cli-help.md)为准。


| 输入族 | 通过现有语义访问 | 关键边界 |
|---|---|---|
| Scene / Window / 嵌套 Window | 当前控件树中的 `ui.activate`、`ui.back` | 只操作最上层有效窗口；保留原生强制通知/取消门槛。 |
| Button 及子类 | `ui.activate` 的 click/right/middle/long | 只列实际覆盖的回调；按当前 visible/active/父树/作用域检查。未处理的长按不补 click、不当作执行异常。 |
| ItemButton 复合控件 | 激活其现有子 ItemSlot | 子 ItemSlot 原回调转发到 ItemButton；不直接反射其物品。 |
| RightClickMenu | 顶层 `context_menu` 下的真实按钮、`ui.back` | 它实际继承 Component，不能只查 Window。 |
| RadialMenu | `ui.choose` 的当前 option 和 alternate | 从已创建菜单中获取现有文本，不靠指针位置或隐藏对象清单。 |
| TextInput | `ui.text`，或真实确认/取消按钮 | 保留最大长度、单行/多行与原 change callback；多行提交用按钮。 |
| OptionSlider | `ui.value` | 检查原范围，设置后调用原 `onChange`。 |
| ScrollPane、列表、网格、日志、图鉴 | `ui.scroll` / `ui.select`，或子按钮 | 条目来自现有树；选中已有条目后仍由原 pane 分派；不构造新窗口作查询。 |
| 页签 / Cycle | 激活对应真实 tab 按钮 | “下一页签”可表达为选择当前树中的下一 tab。 |
| CellSelector.Listener | `cell.select` / `cell.cancel` | 仅使用当前 listener；允许原游戏合法盲瞄；不预枚举隐藏目标。 |
| WndBag.ItemSelector | 当前启用背包槽、`ui.back` | 支持 WndBag 和桌面 InventoryPane 两种呈现；取消清理与确认回调保留。 |
| Item.actions / execute | 已持有物品槽 → 真实物品菜单 → 当前动作按钮 | 不开放任意 execute 字符串，不把任意内部动作当成已合法取得的能力。 |
| ActionIndicator.Action | 当前可见的 ActionIndicator 按钮 | Berserk / Combo / Preparation / Momentum / SnipersMark / MonkEnergy / Charger / TomeRecharge 使用相同入口。 |
| ArmorAbility | ClassArmor 当前动作 → 原能力菜单 / 目标选择 | 19 种能力逐项列入专项清单，不能只测战士。 |
| ClericSpell / MonkAbility | 当前法术/招式菜单中的启用按钮 | 按角色、子职业、天赋、费用生成的当前合法列表。 |
| 改键 | `ui.binding_slot` / `ui.binding_key` → 原确认按钮 | 不构造 KeyEvent；不能用 keycode 0 绕过原取消绑定按钮的限制。 |
| 主菜单 / 选角隐藏界面 | `ui.reveal` | 与原背景释放指针共用恢复 callback，避免隐藏 UI 后无法继续。 |
| 地图拖动 / 缩放 | `view.pan` / `view.zoom` | 对应原 camera shift/zoom；不改变角色位置或生成可达路径。 |
| Alt+Enter 全屏 | 设置窗口的全屏 checkbox | 使用相同设置操作，无需模拟组合键。 |
| 区域过场故事 | 原 Continue / Hide-story 按钮 | coordinator 必须允许等待 Continue 的稳定输入点；不能一律屏蔽 InterlevelScene。 |
| About 链接 / 结局宠物 | 现有 ActionArea 的 `ui.activate` | 只调用原已有动作。 |
| 血条读取 | 原生显示来源的 `health_estimate.samples` | 每个样本保留 `total`、`filled`、`with_shield`、`incoming_dot` 和 `basis:displayed`；DOT 是已显示遮罩段数。隐藏或未观察到的血条不补零，敌人数据不推算精确 HP 或未来伤害；Boss 数字取已显示文字。 |

完整观察与操作意图版本的区别，以及静态清单与运行证据的边界，见[当前架构](cli-architecture.md)。长按返回无动作时不追加普通点击；只执行当前广告的手势。

按钮的按下/抬起表现回调目前用于声音、按压色彩和已有图标色彩恢复；点击、右击、中击、长按的游戏操作回调由语义入口调用。清单保留这些表现回调的方法体校验标识，后续向其中加入游戏逻辑时必须重新审查，不得延续“仅表现”的旧结论。

## 角色与专项验收

| 职业 | 子职业 | 常规护甲能力 |
|---|---|---|
| WARRIOR | BERSERKER / GLADIATOR | HeroicLeap / Shockwave / Endure |
| MAGE | BATTLEMAGE / WARLOCK | ElementalBlast / WildMagic / WarpBeacon |
| ROGUE | ASSASSIN / FREERUNNER | SmokeBomb / DeathMark / ShadowClone |
| HUNTRESS | SNIPER / WARDEN | SpectralBlades / NaturesPower / SpiritHawk |
| DUELIST | CHAMPION / MONK | Challenge / ElementalStrike / Feint |
| CLERIC | PRIEST / PALADIN | AscendedForm / Trinity / PowerOfMany |

额外能力是 Ratmogrify；因此是 18 个常规能力加 1 个鼠王能力。ClericSpell 源码有 27 个具体法术，另有 3 个基类；MonkAbility 有 5 个具体招式。清单中的类型总数包含这些基类。

以下是独立于静态清单的验收门槛，不能由本文件自动宣称通过：

- 实机专项使用新建且明确标记的隔离 profile。实际游玩、暂停和继续均取决于当前用户授权；历史任务状态不构成当前操作许可或限制。
- 12 子职业各验证专属资源、启用/禁用动作、目标、取消和结果。
- 19 种护甲能力各有真实运行专项，覆盖目标或多阶段窗口；Trinity 的 3 个嵌套窗口和召唤物指挥不可遗漏。
- 27 个 ClericSpell、5 个 MonkAbility 与各决斗武器能力分别验证合法/非法/取消/费用不足。
- 所有交互形态测试顶层遮挡、旧控件 ID、旧 `rev`、错误类型、滚动条目、选物取消确认、死亡复活和切层故事。
- 测试夹具只能存在于 test source-set，允许内部布置可复现场景；之后操作必须走同一个 UiBridge 和实际原生窗口。夹具初始化与诊断信息不能出现在公开游戏操作协议。
- 每个专项证据记录 fixture ID、构建版本、角色/能力、命令、公开前后观察、预期/实际结果；不得将只检查 enum 个数的测试命名成能力可用或通关测试。

当前测试入口见[架构与验证](cli-architecture.md)，本批实际执行范围见[CLI.9.0.5 验证记录](cli-9.0.5-revision-validation.md)。以上是审查维度，不宣称全部组合已执行。
