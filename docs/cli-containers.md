# 地面物品与容器专项：前五组

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09，前五组十二个具名场景通过：八种 Heap 各有一个明确范围内的路径，另有普通铁钥匙门、水晶钥匙门、原 Boss 出口和神器造锁。结果见 cli-containers-validation.json（历史 JSON 已删除，可查 Git 历史）。共 347 个公开响应，279 次实际 GUI 后置检查；GUI 为简体中文、窗口化，CLI 游戏文案为英语。所有本组进程已退出。这不代表各类型的全部变体都已通过。

这些是 test-only 的中间状态专项，不是正常游玩或通关证据。测试在真实生成的第 1 层准备一个安静区域，清理无关怪物、刷新器、地面物品与环境干扰，放置原 Item/Heap 实例。战士等级、力量和生命没有放大。第五组的磨损钥匙使用原生成的第 5 层 SewerBossLevel，保留原出口房间、地形与 custom tiles；神器用例仅准备一个普通 DOOR 和已装备 +2、初始 4 充能的 SkeletonKey。准备结束后，只通过公开 CLI 的原操作改变世界，绝不在成功路径直接赋值 HERO_LKD_DR。

| 场景 | 初态与公开操作 | 已验证结果 |
|---|---|---|
| `containers.heap_multi` | 同一 HEAP 内放置 3 个投石、17 金币、2 份口粮；每次按当前公开堆顶选择拾取。 | 最初只发布堆顶投石及数量，没有展开三层私有列表。原拾取顺序为投石 → 金币 → 口粮，各数量实际增加；最后堆消失。一次相邻对角移动和三次拾取，共花 4 个原回合。 |
| `containers.chest_hidden` | 两个外观相同的 CHEST，分别藏未鉴定的鳞甲 +4 与长剑 +2。按公开 cell 顺序查看、打开和拾取。 | 开前两个 container DTO 除位置外相同；原 examine 的显示文本也相同，`ui.inspected_item=null`，未出现隐藏物品名称。查看/Back 不花回合。原操作直接开箱，没有确认窗口；相邻时开箱花 1 回合，远处另算正常接近步数。开后才出现物品名称，`level_known=false`、`level=null` 仍保留。 |
| `containers.locked_chest` | LOCKED_CHEST 中放置未鉴定长剑，地面另放同层 GoldenKey。先无钥匙尝试，再按公开物品位置拾取钥匙并开箱。 | 无钥匙时不耗回合，箱子、载荷和钥匙状态不变，也没有确认窗口。地面钥匙正常进入 Notes；开启实际消耗一把，再正常拾取长剑，等级仍未知。 |
| `containers.crystal_chest` | 两个 CRYSTAL_CHEST 分别藏未鉴定火焰冲击法杖 +4 与魔弹法杖 +2，地面放两把同层 CrystalKey。 | 两箱开前描述一致且不暴露具体法杖名；无钥匙时拒绝不耗回合。正常拾取两把钥匙后，每开一箱恰消耗一把；开启、拾取及仍未知的等级均核对。原公开响应还保留“能看出里面有一根法杖”的合法大类提示。 |
| `containers.skeleton` | 非诅咒物品组成的 SKELETON，经原 `setHauntedIfCursed` 准备。 | 查看不耗回合且不暴露隐藏名称；原直接开启、正常拾取，装备等级仍未知。本例只覆盖非 haunted 分支。 |
| `containers.remains` | 非诅咒物品组成的 REMAINS，经原 `setHauntedIfCursed` 准备。 | 原英雄遗骸表象、查看、开启和拾取都完成；不冒充跨局 Bones 文件产生历史或 haunted 分支。 |
| `containers.for_sale` | 原 Shopkeeper 与单件正价格口粮待售堆；初始有足够测试金币。 | 原 cell 操作进入 WndTradeItem，Back 取消不扣金币、不取物、不额外耗回合；再次进入后按公开 `Buy for 50g` 购买，金币恰少 50、口粮增一、待售堆移除。 |
| `containers.iron_door` | 原 LOCKED_DOOR 与地面同层 IronKey。 | 无钥匙时拒绝不耗回合，地形和钥匙不变；拾取后原开锁恰消耗一把钥匙，随后实际穿过原门。没有确认窗口。 |
| `containers.crystal_door` | 原 CRYSTAL_DOOR 与地面同层 CrystalKey。 | 无钥匙拒绝、原钥匙拾取、一把钥匙消耗、解除屏障及实际穿过均完成。没有确认窗口。 |
| `containers.tomb` | 原 TOMB 藏未鉴定长剑，普通战士；不控制开墓后的 RNG。 | 原查看不泄漏内容；直接开墓真实生成幽灵。根据实际公开状态先移动到堆格、再同格拾取，物品正常获得且等级仍未知。本次原战斗浮字实际出现 `dodged` 与 `presentation=floating_text`。 |

| `containers.worn_exit` | 原 SewerBossExitRoom 的 LOCKED_EXIT，原地面 WornKey；先无钥匙尝试，再拾取、处理首次支持提示和开锁。 | 无钥匙拒绝不花回合；原 WndSupportPrompt 的 Back 不能关闭，原 Close 关闭且不花回合。开锁恰耗一把当前层 WornKey 和 1 回合，地形变为 UNLOCKED_EXIT。没有击杀 Boss 或进入下一层的声明。 |
| `containers.skeleton_key_door` | 已装备 SkeletonKey +2/4 充能，初态普通 DOOR。通过原 INSERT 造锁、直接 cell 拒绝、再次 INSERT 取消、开锁、重锁、DROP 和强开。 | 两次造锁各耗 2 充能/1 回合；取消和直接点击拒绝不改变地形、回合或充能；神器开自己的锁耗 0 充能/1 回合。原装备 DROP 耗 2 回合，丢弃后原 cell 强开耗 1 回合；无普通钥匙消耗。 |

不同载荷的存在、真实等级与私有物品列表，只在公开动作完成后按同一 `state_version` 作后置断言。它们不提供要打开的箱子、目标 cell 或下一动作。该对照证明的是同一隔离世界中两个不同位置箱子的公开表象一致，不冒充所有世界状态的完整双世界证明。

水晶箱的大类提示另对已保留的原公开响应作了只读正向核验：23 次仍关闭的水晶箱出现都包含 `wand`，首个原句为 `You can see _a wand_ inside, but to open the chest you need a crystal key.`。该核验使用原动作响应，未借用后续游戏查询来补足信息；具体 request ID 记录在 JSON 的 `additional_public_trace_assertion` 中。

曾有一次 HEAP 失败：三个物品已经按原流程全部拾取，测试却错误期待金币下沉。源码 `Item.dropsDownHeap` 默认为 false，本例三种物品均未覆盖它，`Heap.drop` 使用 `addFirst`，实际顺序应为投石 → 金币 → 口粮。已修正测试期待并完整重跑；原失败报告保留，未计入通过。

第三组的墓碑尝试原样保留为失败：原开墓已生成四个幽灵，并让长剑名称变得可见；但移动到物品格后继续拾取时，原战斗浮字“闪避”触发英语歧义，请求返回 `EXECUTION_UNKNOWN`。失败后 EOF 正常退出 0，没有因为已观察到生成幽灵就将那次完整流程标成成功。

第四组使用修复后的冻结运行时完整重跑墓碑，正常开启、移动、拾取和退出均通过。原公开请求 `736ba3701802-22`、`-23`、`-24` 确实记录了 `text=dodged`、`presentation=floating_text`，因此不是没有触发旧问题的偶然通过。修复按实际可见 FloatingText 标记解释已有文字，没有重调用会改变防御状态的 `Hero.defenseVerb`，也没有为得到闪避而筛选或修改 RNG。

Heap 的八种原生类型已经核对，并都有上表中的具体路径证据；这些仍是各类型的部分分支，不能发明取消窗口或扩大到全部变体：

- `HEAP`：逐次拾取堆顶；空堆销毁。
- `FOR_SALE`：单件且 `value()>0` 才进入原 WndTradeItem 购买窗口，可以 Back 取消。其他待售堆形态走原拾取分支。
- `CHEST`：直接操作开启，转为 HEAP。
- `LOCKED_CHEST`：无当前层 GoldenKey 时直接提示并保持关闭；有钥匙时在原 `onOperateComplete` 消耗一把并开启。
- `CRYSTAL_CHEST`：使用当前层 CrystalKey。开前原 UI 已允许知道里面是神器、法杖或戒指这一大类；不能把这部分可见信息错误隐藏，也不能提前给出精确物品或等级。
- `TOMB`：直接开启会调用原 `Wraith.spawnAround(hero.pos)`，在四个正方向尝试生成幽灵，再转为 HEAP。
- `SKELETON`、`REMAINS`：直接开启并播放骨骸效果；诅咒导致的 haunted 分支需要另测，不能由基础非诅咒用例代替。

上述普通容器的成功开启在相邻时花 `Key.TIME_TO_UNLOCK`，当前为 1 个回合。无钥匙拒绝不等于一个待确认窗口。相关源码为 [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java)、[Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) 的 `handle`、`actPickUp`、`actOpenChest` 与 `onOperateComplete`，以及 [Key.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/keys/Key.java)。

四种门的当前原关系均有具名证据：LOCKED_DOOR/当前层 IronKey、CRYSTAL_DOOR/当前层 CrystalKey、原 LOCKED_EXIT/当前层 WornKey，以及原 INSERT 产生的 HERO_LKD_DR/SkeletonKey 特例。错层钥匙、诅咒分心、神器代开其他锁等仍是独立待测分支。

测试 source-set 为 [ContainerScenarioFixtures.java](../desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/ContainerScenarioFixtures.java)，客户端为 [containers_scenario_smoke.py](../desktop-control/src/test/python/containers_scenario_smoke.py)。后置回合时钟使用 `Statistics.duration + Hero` 的原 Actor 时间，因此正常保存的 `Actor.fixTime` 不会造成假回合差异。私有数据不用于行动选择。

复现首组：

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/containers_scenario_smoke.py --cases heap-multi,chest-hidden
```

复现第二组使用 `--cases locked-chest,crystal-chest`。第一组采用两个冻结测试运行时，生产 build ID 同为 `2701ef08b535b467f57fd35febb480042cff8bef80195820c27c7f8dc2a9046c`；第二组为 `runtime-d975294673694d678d55c3f12b2b4bd2`，build ID 为 `e4b889639bcf3b073b426fe7b3bc7df98cd876fb805026bc91bc9677adfcf2e6`。没有把不同编译产物称为同一批。

第三组成功部分可用 `--cases skeleton,remains,for-sale` 重跑。骨骸/遗骸使用 `runtime-454c705126004dda94ca8f904e44aa6f`，build ID 为 `79a9475442be928160b04a7be9116ea93dfd5eb699c47f15fbd36bd7d343da99`；交易使用 `runtime-c540ec459fc6478ba9025329e2dccf85`，build ID 为 `d60337932a6b331cb8232497266a62c488a7d6a3b69fb11ce41e0b33119b3629`。同一 runtime 的墓碑失败仍独立保留。

第四组使用 `--cases iron-door,crystal-door,tomb`，冻结运行时为 `runtime-8d0cacd690df4dffb70ac0d9ffca3101`，build ID 为 `f67189df2341300c337703db4dbc30f2521f279044ad5b0aa15a398f837a5e22`。

错层钥匙、SkeletonKey 替代钥匙与诅咒分心、haunted 骨骸、零价值或含多个物品的待售堆、背包容量等变体仍未被本组代替。没有使用截图、键鼠模拟、Computer Use 或正式 profile；本组结果不代表整个 Heap 类、全部房间或所有诅咒/背包边界都已通过。

第五组使用 `--cases worn-exit,skeleton-key-door`，冻结运行时 `runtime-446622c9d01c4418870fbda60e8ad24d`，build ID `2f08667717dce2361612db55b1dc1b8dd0bab4b6b198a824626e21e758f421cf`；两项共 63 个响应、58 次同版本 GUI 检查。原先 `runtime-296f95cf4a51431aac19cc0362353a81` 中 SkeletonKey 已完整通过，WornKey 因首次支持提示的“关闭”翻译歧义失败，原报告与异常保留。

支持提示修复只读取当前公开 DTO：原完整标题、原 intro + Patreon 正文 + 中文 GUI 才额外显示的英文奖励提示 + Evan 署名，以及两枚原按钮必须完整存在于同一窗口树。只有这棵树中实际 Close 按钮及其已渲染子文字能借用 `wndsupportprompt.close` 英语资源；没有调用原动作、读取 `supportNagged` 或隐藏 key 类型来识别。正文仍保留原中文 GUI 所显示的额外提示。6 个新增正反例与 24 个相关测试通过，实机通过原 Back 保护、原 Close 后才完成开锁，没有点击外部 Patreon 链接。
