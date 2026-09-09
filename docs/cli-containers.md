# 地面物品与容器专项：第一组

2026-09-09，首组两个具名场景通过：`containers.heap_multi` 和 `containers.chest_hidden`。结果见 [cli-containers-validation.json](cli-containers-validation.json)。共 57 个公开响应，43 次实际 GUI 后置检查；GUI 为简体中文、窗口化，CLI 游戏文案为英语。所有本组进程已退出。

这些是 test-only 的中间状态专项，不是正常游玩或通关证据。测试在真实生成的第 1 层准备一个安静区域，清理无关怪物、刷新器、地面物品与环境干扰，放置原 Item/Heap 实例。战士等级、力量和生命没有放大。准备结束后，只通过公开 CLI 的原 `cell.select`、examine、Back 和拾取流程改变世界。

| 场景 | 初态与公开操作 | 已验证结果 |
|---|---|---|
| `containers.heap_multi` | 同一 HEAP 内放置 3 个投石、17 金币、2 份口粮；每次按当前公开堆顶选择拾取。 | 最初只发布堆顶投石及数量，没有展开三层私有列表。原拾取顺序为投石 → 金币 → 口粮，各数量实际增加；最后堆消失。一次相邻对角移动和三次拾取，共花 4 个原回合。 |
| `containers.chest_hidden` | 两个外观相同的 CHEST，分别藏未鉴定的鳞甲 +4 与长剑 +2。按公开 cell 顺序查看、打开和拾取。 | 开前两个 container DTO 除位置外相同；原 examine 的显示文本也相同，`ui.inspected_item=null`，未出现隐藏物品名称。查看/Back 不花回合。原操作直接开箱，没有确认窗口；相邻时开箱花 1 回合，远处另算正常接近步数。开后才出现物品名称，`level_known=false`、`level=null` 仍保留。 |

不同载荷的存在、真实等级与私有物品列表，只在公开动作完成后按同一 `state_version` 作后置断言。它们不提供要打开的箱子、目标 cell 或下一动作。该对照证明的是同一隔离世界中两个不同位置箱子的公开表象一致，不冒充所有世界状态的完整双世界证明。

曾有一次 HEAP 失败：三个物品已经按原流程全部拾取，测试却错误期待金币下沉。源码 `Item.dropsDownHeap` 默认为 false，本例三种物品均未覆盖它，`Heap.drop` 使用 `addFirst`，实际顺序应为投石 → 金币 → 口粮。已修正测试期待并完整重跑；原失败报告保留，未计入通过。

Heap 的八种原生类型已经核对，后续按照以下语义分组，不能发明取消窗口：

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

本组采用两个冻结测试运行时，生产 build ID 同为 `2701ef08b535b467f57fd35febb480042cff8bef80195820c27c7f8dc2a9046c`。其他已列出的容器、钥匙与交易入口目前仅有测试准备或计划，仍标为 pending。没有使用截图、键鼠模拟、Computer Use 或正式 profile；本组结果不代表整个 Heap 类、全部房间或所有诅咒/背包边界都已通过。
