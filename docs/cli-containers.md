# 地面物品与容器专项：前三组

2026-09-09，前三组七个具名场景通过：地面多层拾取、普通双箱、锁箱、水晶双箱、普通骨骸、英雄遗骸及待售物品。结果见 [cli-containers-validation.json](cli-containers-validation.json)。共 202 个公开响应，155 次实际 GUI 后置检查；GUI 为简体中文、窗口化，CLI 游戏文案为英语。墓碑的完整测试仍失败/待修。所有本组进程已退出。

这些是 test-only 的中间状态专项，不是正常游玩或通关证据。测试在真实生成的第 1 层准备一个安静区域，清理无关怪物、刷新器、地面物品与环境干扰，放置原 Item/Heap 实例。战士等级、力量和生命没有放大。准备结束后，只通过公开 CLI 的原 `cell.select`、examine、Back 和拾取流程改变世界。

| 场景 | 初态与公开操作 | 已验证结果 |
|---|---|---|
| `containers.heap_multi` | 同一 HEAP 内放置 3 个投石、17 金币、2 份口粮；每次按当前公开堆顶选择拾取。 | 最初只发布堆顶投石及数量，没有展开三层私有列表。原拾取顺序为投石 → 金币 → 口粮，各数量实际增加；最后堆消失。一次相邻对角移动和三次拾取，共花 4 个原回合。 |
| `containers.chest_hidden` | 两个外观相同的 CHEST，分别藏未鉴定的鳞甲 +4 与长剑 +2。按公开 cell 顺序查看、打开和拾取。 | 开前两个 container DTO 除位置外相同；原 examine 的显示文本也相同，`ui.inspected_item=null`，未出现隐藏物品名称。查看/Back 不花回合。原操作直接开箱，没有确认窗口；相邻时开箱花 1 回合，远处另算正常接近步数。开后才出现物品名称，`level_known=false`、`level=null` 仍保留。 |
| `containers.locked_chest` | LOCKED_CHEST 中放置未鉴定长剑，地面另放同层 GoldenKey。先无钥匙尝试，再按公开物品位置拾取钥匙并开箱。 | 无钥匙时不耗回合，箱子、载荷和钥匙状态不变，也没有确认窗口。地面钥匙正常进入 Notes；开启实际消耗一把，再正常拾取长剑，等级仍未知。 |
| `containers.crystal_chest` | 两个 CRYSTAL_CHEST 分别藏未鉴定火焰冲击法杖 +4 与魔弹法杖 +2，地面放两把同层 CrystalKey。 | 两箱开前描述一致且不暴露具体法杖名；无钥匙时拒绝不耗回合。正常拾取两把钥匙后，每开一箱恰消耗一把；开启、拾取及仍未知的等级均核对。原公开响应还保留“能看出里面有一根法杖”的合法大类提示。 |
| `containers.skeleton` | 非诅咒物品组成的 SKELETON，经原 `setHauntedIfCursed` 准备。 | 查看不耗回合且不暴露隐藏名称；原直接开启、正常拾取，装备等级仍未知。本例只覆盖非 haunted 分支。 |
| `containers.remains` | 非诅咒物品组成的 REMAINS，经原 `setHauntedIfCursed` 准备。 | 原英雄遗骸表象、查看、开启和拾取都完成；不冒充跨局 Bones 文件产生历史或 haunted 分支。 |
| `containers.for_sale` | 原 Shopkeeper 与单件正价格口粮待售堆；初始有足够测试金币。 | 原 cell 操作进入 WndTradeItem，Back 取消不扣金币、不取物、不额外耗回合；再次进入后按公开 `Buy for 50g` 购买，金币恰少 50、口粮增一、待售堆移除。 |

不同载荷的存在、真实等级与私有物品列表，只在公开动作完成后按同一 `state_version` 作后置断言。它们不提供要打开的箱子、目标 cell 或下一动作。该对照证明的是同一隔离世界中两个不同位置箱子的公开表象一致，不冒充所有世界状态的完整双世界证明。

水晶箱的大类提示另对已保留的原公开响应作了只读正向核验：23 次仍关闭的水晶箱出现都包含 `wand`，首个原句为 `You can see _a wand_ inside, but to open the chest you need a crystal key.`。该核验使用原动作响应，未借用后续游戏查询来补足信息；具体 request ID 记录在 JSON 的 `additional_public_trace_assertion` 中。

曾有一次 HEAP 失败：三个物品已经按原流程全部拾取，测试却错误期待金币下沉。源码 `Item.dropsDownHeap` 默认为 false，本例三种物品均未覆盖它，`Heap.drop` 使用 `addFirst`，实际顺序应为投石 → 金币 → 口粮。已修正测试期待并完整重跑；原失败报告保留，未计入通过。

第三组的墓碑尝试保留为失败：原开墓已生成四个幽灵，并让长剑名称变得可见；但移动到物品格后继续拾取时，原战斗浮字“闪避”触发英语歧义，请求返回 `EXECUTION_UNKNOWN`。整项没有通过，失败后 EOF 正常退出 0。没有以隐藏数据选择动作，也没有因为已观察到生成幽灵就将完整墓碑流程标成成功。

Heap 的八种原生类型已经核对。除 TOMB 外，其他七类已有上表中的具体路径证据；这些仍是各类型的部分分支，不能发明取消窗口或扩大到全部变体：

- `HEAP`：逐次拾取堆顶；空堆销毁。
- `FOR_SALE`：单件且 `value()>0` 才进入原 WndTradeItem 购买窗口，可以 Back 取消。其他待售堆形态走原拾取分支。
- `CHEST`：直接操作开启，转为 HEAP。
- `LOCKED_CHEST`：无当前层 GoldenKey 时直接提示并保持关闭；有钥匙时在原 `onOperateComplete` 消耗一把并开启。
- `CRYSTAL_CHEST`：使用当前层 CrystalKey。开前原 UI 已允许知道里面是神器、法杖或戒指这一大类；不能把这部分可见信息错误隐藏，也不能提前给出精确物品或等级。
- `TOMB`：直接开启会调用原 `Wraith.spawnAround(hero.pos)`，在四个正方向尝试生成幽灵，再转为 HEAP。
- `SKELETON`、`REMAINS`：直接开启并播放骨骸效果；诅咒导致的 haunted 分支需要另测，不能由基础非诅咒用例代替。

上述普通容器的成功开启在相邻时花 `Key.TIME_TO_UNLOCK`，当前为 1 个回合。无钥匙拒绝不等于一个待确认窗口。相关源码为 [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java)、[Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) 的 `handle`、`actPickUp`、`actOpenChest` 与 `onOperateComplete`，以及 [Key.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/keys/Key.java)。

锁门计划也单独记录在 JSON 中：LOCKED_DOOR 使用当前层 IronKey，CRYSTAL_DOOR 使用当前层 CrystalKey；LOCKED_EXIT/WornKey 需要另一个真实出口或 Boss 层初态，HERO_LKD_DR 则属于 SkeletonKey 神器特例。这些尚未计入本组通过。

测试 source-set 为 [ContainerScenarioFixtures.java](../desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/ContainerScenarioFixtures.java)，客户端为 [containers_scenario_smoke.py](../desktop-control/src/test/python/containers_scenario_smoke.py)。后置回合时钟使用 `Statistics.duration + Hero` 的原 Actor 时间，因此正常保存的 `Actor.fixTime` 不会造成假回合差异。私有数据不用于行动选择。

复现首组：

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/containers_scenario_smoke.py --cases heap-multi,chest-hidden
```

复现第二组使用 `--cases locked-chest,crystal-chest`。第一组采用两个冻结测试运行时，生产 build ID 同为 `2701ef08b535b467f57fd35febb480042cff8bef80195820c27c7f8dc2a9046c`；第二组为 `runtime-d975294673694d678d55c3f12b2b4bd2`，build ID 为 `e4b889639bcf3b073b426fe7b3bc7df98cd876fb805026bc91bc9677adfcf2e6`。没有把不同编译产物称为同一批。

第三组成功部分可用 `--cases skeleton,remains,for-sale` 重跑。骨骸/遗骸使用 `runtime-454c705126004dda94ca8f904e44aa6f`，build ID 为 `79a9475442be928160b04a7be9116ea93dfd5eb699c47f15fbd36bd7d343da99`；交易使用 `runtime-c540ec459fc6478ba9025329e2dccf85`，build ID 为 `d60337932a6b331cb8232497266a62c488a7d6a3b69fb11ce41e0b33119b3629`。同一 runtime 的墓碑失败仍独立保留。

墓碑完整流程与锁门入口仍标为 pending；错层钥匙、SkeletonKey 替代钥匙与诅咒分心、haunted 骨骸、零价值或含多个物品的待售堆、背包容量等变体也未被本组代替。没有使用截图、键鼠模拟、Computer Use 或正式 profile；本组结果不代表整个 Heap 类、全部房间或所有诅咒/背包边界都已通过。
