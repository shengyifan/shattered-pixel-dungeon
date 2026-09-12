# CLI UI 覆盖清单

清单通过 `./gradlew :game-control:generateUiCoverage` 用 javac AST 解析当前 `core/src/main/java` 与 `SPD-classes/src/main/java`。它扫描命名类、匿名类、嵌套窗口、继承关系、输入回调、物品 actions/execute、职业动作、护甲能力与法术；不加载、初始化或构造任何游戏类。

完整条目、源码位置、语义路线与输入方法的源码校验标识保存在 [cli-ui-coverage.json](../game-control/src/test/resources/cli-ui-coverage.json)。每项明确标为 `static_only`，汇总字段 `runtime_verified_by_inventory` 永远为 `false`。**找到静态路线不等于实际操作验证，更不等于通关。**

## 生成、检查与更新

```sh
./gradlew :game-control:generateUiCoverage
./gradlew :game-control:test --tests '*UiCoverageInventoryTest'
```

检查会发现新增或修改的声明、输入方法、未分类的输入与清单过期。它也用一个全新且无法初始化的测试类型确认：未知 `onSignal(KeyEvent)` 会成为 `UNMAPPED`，不会自动标记已覆盖。更新基线后必须审查差异；不要仅重新生成文件来消除真实功能缺口。

同名输入方法仅按语义入口归类；鼠标事件监听器、背景交互和复合控件要有各自的明确路线。数字包含抽象基类、匿名类、接口和包装器，不能直接作为独立玩家操作数量。

## 语义路线

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
| 血条读取 | 已 layout 的 `health_bar` 像素宽度 | 不返回 Char 的真实 HP/HT；格子关联要求当下 FOV、位置范围和 sprite.visible。Boss 数字取已显示文字。 |

完整观察与操作意图版本的区别、浮字导致过期的公开证据，以及长按无动作语义，见 [CLI UI 意图版本](cli-ui-intent-version.md)。

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

- 按最新指令，正式实战已停止；以直接构造中间状态的真实引擎专项优先覆盖全场景。原战士通关验收暂停，六职业功能与专项仍保留。
- 12 子职业各验证专属资源、启用/禁用动作、目标、取消和结果。
- 19 种护甲能力各有真实运行专项，覆盖目标或多阶段窗口；Trinity 的 3 个嵌套窗口和召唤物指挥不可遗漏。
- 27 个 ClericSpell、5 个 MonkAbility 与各决斗武器能力分别验证合法/非法/取消/费用不足。
- 所有交互形态测试顶层遮挡、旧控件 ID、旧 state_version、错误类型、滚动条目、选物取消确认、死亡复活和切层故事。
- 测试夹具只能存在于 test source-set，允许内部布置可复现场景；之后操作必须走同一个 UiBridge 和实际原生窗口。夹具初始化与诊断信息不能出现在公开游戏操作协议。
- 每个专项证据记录 fixture ID、构建版本、角色/能力、命令、公开前后观察、预期/实际结果；不得将只检查 enum 个数的测试命名成能力可用或通关测试。

已经执行的真实引擎专项、逐例结果和明确未穷尽的范围，见 [P7 真实引擎专项验证](cli-p7-validation.md)。
