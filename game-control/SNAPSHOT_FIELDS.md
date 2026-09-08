# 游戏快照字段边界

`GameSnapshotter.capture()` 仅在协调器持有稳定游戏/渲染边界时调用。不调用
`Dungeon.observe`、FOV 重算、`Actor.id()`、`Char.shielding()`、`Wand.level()`、
`Bundle.storeInBundle`，不实例化物品、怪物、楼层或窗口，也不加载尚未加载的存档。

## 公开投影

公开 JSON 的唯一模型入口是 `PlayerObservation`，只构造 Map/List/JSON 标量；
内部图和原始文件不可作为公开序列化输入。始终提供 `scene` 和 `coverage`；仅 GameScene
提供 `hero`、`map`、`inventory`、`visible_entities`。AlchemyScene 明确标为 alchemy，
通过真实炼金 UI 和选物窗口获取信息，不用空背包伪装不可用数据。非 GameScene 不公开残留 Dungeon 对象。

| 字段 | 可见来源和限制 |
| --- | --- |
| hero.class/subclass/level/experience/hp/max_hp | 英雄页已显示的职业、等级、经验和生命数值 |
| hero.shield | 保留 Char 已有缓存语义；需重算时纯累加有效 ShieldBuff，DivineShield 受 AscendBuff 门槛控制，不写缓存 |
| hero.base_strength / strength | 英雄页的基础力量和当前力量，使用已审计纯计算 |
| hero.gold/energy/depth | HUD 已有的金币、炼金能量和楼层 |
| hero.talents | 英雄已存在的天赋页名称、层级和已分配点数 |
| hero.buffs / character.buffs | 只含 `icon()!=NONE` 的显示名称和图标；不导出内部追踪器或原始计时 |
| inventory.locator | 当前可见槽位路径；equipment.weapon 等或 backpack.0.2；不包含类型/actor ID |
| inventory.name/quantity | 玩家名称与堆叠数量；随机身份物品使用本局 isKnown 门槛 |
| inventory.type_known/level_known/curse_known | 分离类型、强化、诅咒知识；未知值或不适用的知识位为 null，不公开药卷等的无 UI 意义原始鉴定位 |
| inventory.level | 仅适用且 levelKnown；神器按 UI 的 0–10 换算，灵能弓按 hero.lvl/5，法杖避免 lazy getter |
| inventory.cursed | 仅 cursedKnown，不把未知当作 false |
| inventory.description | 药水/卷轴/戒指的带鉴定门槛 description；其他装备完整动态说明通过 details_via 的现有 UI 操作打开真实物品页 |
| inventory.charges | 法杖仅 levelKnown 时有字段；curChargeKnown 为 false 时 current=null |
| map.cells | heroFOV / visited / mapped 的格子；未知格子省略，没有完整地图/房间/通行图 |
| map.terrain | 玩家表象；秘密门→墙、秘密陷阱→地板、等价锁门/自定义地板归一化 |
| visible_entities | 怪物要求 heroFOV；中立 PASSIVE stealthy 拟态保留已见 fog 表象，与 GameScene 一致；heap 要求 seen 且格子已知；trap 要求 visible；植物格子已知 |
| container | 不导出内容；水晶宝箱只沿游戏描述给出物品类别；普通 heap 仅第一件 |
| disguised mimic | 使用游戏现有伪装名称/描述；普通/黄金/水晶拟态是容器表象，Ebony 仅 object + suspicious outline，不能额外暗示容器/敌人类型 |

`coverage.details_via` 是固定的已实现查看路径，不随隐藏对象/类型变化。装备动态数值、
Buff 时长和图鉴通过当前 ui 控件的 `ui.activate` 打开游戏本身的信息页，读取已显示窗口全文；
自定义地形通过 `cell.select` 的 `mode=examine` 查看。调用方从当前 UI 的 action descriptors
选择真实控件，不对这些描述字符串直接执行缺少 target 的动作。查询不自动构造窗口，
查看动作导致的正常图鉴/已读状态变更归属动作。怪物生命条经 UiBridge 读取已经渲染的
像素宽度，不在此投影暴露 HP/HT 或无限精度比例。

## 内部引用图

`InternalGraphSnapshotter.STATIC_ROOTS` 从随包附带的 snapshot-static-roots.txt 读取显式白名单。
当前 322 个根覆盖演员、物品、楼层、植物、机制、日志、场景、窗口、UI 包及指定根类的全部有状态静态
声明，包括子类和内部类的 private counter/cache；增加 Bones、SPDAction、Holiday、DungeonSeed。
运行时不扫描类路径，不接受请求指定 root；白名单按已编译源码声明生成并经测试校验。

`src/main/resources/com/shatteredpixel/shatteredpixeldungeon/control/game/snapshot-static-fields.txt`
列出当前 1353 个非常量静态字段。单元测试比较当前声明，字段新增/删除会要求更新清单，另一个测试扫描模型包的编译声明，使新增有状态类也必须纳入。

对象图对纳入模型遍历所有继承实例字段，包括 private/transient 字段；循环和共享引用
用 `$ref` 与 nodes 表保留。枚举保留名称和自有实例字段，避免丢失枚举中的可变状态。
current_scene、requested_scene、requested_scene_class、scene_change_callback、scene_change_requested
由 Game 已有字段显式提供，不读取 getter。场景/窗口/selector/按钮/文本输入的私有语义字段和
匿名 this$0、val$、lambda arg$ 捕获引用进入同一张图；Group.members 保留完整子控件顺序，
TextInput 的 TextField 保留已有文本/光标/选择状态。选择器静态字段也在显式清单内。
Map/Collection 保留元素/键值引用，数组保留全部元素。GDX 集合读取底层字段而不调用
会修改迭代器缓存的 keys/values/entries 方法。

每次捕获的 coverage 含 included_fields、excluded、unavailable、unobserved，以及
明确范围限制。场景/窗口/UI 对象不会整类排除；noosa 渲染基类字段、native/GL/音频/线程/
缓冲区/外部对象不递归，排除点保留类型与原因。GameController/UiBridge/AuditStore 等控制审计
基础设施不从回调的外层引用递归，以免把此前审计快照重新包进下一份快照；回调自身的选择参数仍保留。
不把“已捕获声明范围”冒称完整全部 JVM 状态；白名单以外的静态根、JDK 集合实现缓存等
未承诺覆盖。该引用图只用于内部审计，不直接反序列化为可执行游戏对象。

## 初始化门槛和运行时约束

先 `Class.forName(name, false, loader)` 获取声明，再通过只读初始化状态探针判断。
只有确认已初始化的类才允许 `Field.get(null)`。不会为了日志初始化尚不存在的静态对象。
当前 Temurin 25.0.4 已核实 `jdk.internal.misc.Unsafe.shouldBeInitialized(Class)` 可用，
CLI 专用启动参数必须包含：

```
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
```

普通 GUI 不需要这个参数。老 JDK 可尝试 sun.misc.Unsafe 的相同只读探针；缺 API 或模块
不可访问时返回 unobserved / initialization_state_unavailable，coverage 标 incomplete。
未初始化类返回 unobserved / not_initialized；此时数据尚不存在，单独此状态不令范围捕获 incomplete。
探针没有公开反射接口，也不接受请求指定类名。

RNG 仅在已初始化时调用同步 `Random.exportState()`，保存原始字节的 Base64；不取样，
不推进随机数，不导出到玩家响应。序列化必须保留生成器栈顺序和 Gaussian cache。

## profile 原始文件

仅对明确传入的 profile 执行只读扫描，无默认用户数据路径。顶层只允许
settings.xml/settings.json/settings.dat、badges.dat、journal.dat、rankings.dat、
bones.dat、keybinds.dat、keybindings.dat、bindings.dat；game[0-9]+ 下只允许 game.dat、depthN.dat、
depthN-branchN.dat。其余目录、audit、tmp、native-cache、.lock、symlink 不跟随。
全部原始字节以 Base64 进入 internalState.profile_files，不解析/加载未当前楼层。
文件失败按路径记录 incomplete，不静默省略；打开文件时再次使用 NOFOLLOW_LINKS 拒绝最终文件的 symlink。

## 已有验证

隔离 fixture 覆盖未知等级/诅咒、不同未鉴定药卷、秘密地形、隐藏怪物和陷阱的
公开等价性；宝箱和堆叠覆盖；100 次查询前后内部图/RNG 不变；引用循环与别名；
profile 排除审计和 symlink；未初始化类不被探针初始化；静态字段/根类清单漂移；
非运行场景不泄漏残留英雄；诅咒刻印的知识门槛；节日食物显示名称不填充 Holiday 缓存；
窗口子控件/private 选择 index/匿名回调捕获对象与模型根共用引用且不执行回调。未读取真实用户数据，测试不启动 native/UI backend。

本轮复核又补充：拟态在 fog 中的持续可见性、Ebony 轮廓分类、无意义消耗品鉴定位、
灵能弓升级公式、DivineShield 有效条件和缓存读取；只读自建 build/smoke 审计样本验证
322 根/完整 UI 语义图的 unavailable 为空，实际 profile 路径与当前源码规则一致。

subclass 为 NONE/null 时 subclass_name=null。WndHero 明确显示原始 STR 与 +/-bonus，
因此基础力量与当前力量都可公开。Pasty ItemSlot 的完整 UiBridge 查询链已有重复读取回归。

当前可见格子的 environment 只提供与 WndInfoCell 一致的效果说明和表象类别，不含浓度/时长。
character.context_action 与真实右键菜单的 attack/interact 文案一致；emotion 仅取已存在可见
EmoIcon，不读 AI state 推测睡眠/警戒。消失或视野外效果不输出。对应双世界及正向揭示测试已添加。
