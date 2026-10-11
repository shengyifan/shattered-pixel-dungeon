# CLI 公共信息审计 A：地图、环境、实体、房间线索与声音

> 2026-10-11 后续状态：本页保留CLI.9.0.3的原始审计和修复前证据。确认的魔晶、图标/配方、仪式标记、节点校验问题已在CLI.9.0.4实现修复，并新增经平台验证的警报音事件。当前实现与实际包验收请读[CLI.9.0.4验证记录](../../docs/cli-9.0.4-player-info-validation.md)。未确认候选和全场景覆盖边界仍保留，不把以下历史“尚未修复”叙述当作当前状态。

日期：2026-10-11。源码基线 `d04c8f862b2f90418a6375f0b3ffc5c4325d0953`；CLI.9.0.3 / protocol 9 / schema 12 / game 4.0.2（code 922）。本报告检查**玩家通过当前原生呈现或原生检查界面能掌握的公开事实，是否有对应 CLI 路径**。不把私有 room 类、生成统计、隐藏敌位、未来冷却、未识别道具真名当成应该输出的信息。

已读 `AGENTS.md`、`docs/cli-help.md` 与 CLI maintenance skill。本次只读生产源码；只在本研究目录新增隔离夹具/报告，编译结果置于独立忽略目录 `game-control/build/research-cli-world-audit`。没有启动真实游戏、读取个人 save/profile/audit、修改生产代码或执行 gameplay。新夹具没有 GL 上下文，也没有 actor step；一个自定义地形检查仅使用合成纹理元数据和未启动的原生场景树。

## 结论与证据等级

不能据现有“有 map/entities/cues”和测试源码清单就断言已覆盖全部玩家信息。本轮确认一个现有公开输出路径的**静态可达听觉缺口**，以及一个已隔离复现的**默认地图可发现性缺口**。另外复现了商店售价按需检查的设计边界；因原生地面贴图同样不显示售价，该项不计为视觉等价性缺陷。默认标记缺口有 `cell examine` 补救，不能扩大成整条 CLI 都无法查询。

| ID | 结果 | 证据等级 | 当前可用替代路径 |
| --- | --- | --- | --- |
| W1 | SFX 开启、非静音且实际播放时，原生视野外警报没有对应声音事件 | 原生触发→播放与 observer 接口静态核查；没有实际音频播放复现 | 后续可见状态变化可能提示，但不能保证恢复刚才听觉事实 |
| W2 | 按需检查边界，非已确认缺陷：地面免费物品与待售物品默认同形，售价在检查时提供 | 隔离原生 `Heap.title` 与 `PlayerObservation.capture` 对照通过；原生 ItemSprite 也同形 | 对该格 examine/context；购买窗口 |
| W3 | 已显示的仪式标记在默认地图被折成普通地面，无标记身份/说明 | 合成附着原生 RitualMarker + 生产 capture 隔离对照通过 | 对已知无其他对象干扰的标记格 examine |
| W4 | Notes 网格图标的地标/感受/钥匙身份在打开详情前缺语义 | 本文静态核查；16b 另有隔离复现，按同一问题计数 | 顺次选择条目打开原生详情 |

这里“默认”指尚未额外打开对应检查窗口的 `PlayerObservation` 世界摘要；压缩编码不能凭空补回采集器没有提供的内容。没有以本夹具证明实际打包程序的完整 wire/renderer 覆盖；包身份和仓库测试由总审计统一验证。

## 1. 当前生产路径与正确边界

世界投影入口是 [PlayerObservation.capture](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L81)，依次取 hero/inventory、`terrain(level)`、`entities(hero,level)`。之后 `GameplayObservation` 合并同帧语义 UI，协议层编码地图行/类型表和实体模板。`coverage.status=observation_with_inspection` 明确提供原生检查补充，而不是承诺默认帧有每个物品/地形的全部百科详情。[coverage](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L82)、[GameplayObservation.merge](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L33)、[帮助中的地图范围](../../docs/cli-help.md#L265)。

- 地图遍历全层，只有 `heroFOV || visited || mapped` 的格进入投影；按当帧 flags 标 visible/visited/mapped。[terrain](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L284)。完整已知地图不是屏幕裁剪，也不是未知全图。
- 暗门投影为墙，暗陷阱为普通地面，英雄专用锁门显示成普通锁门；未审查的水背装饰先保留原生水底，而不是私读 solid 标记补真身。[terrainAppearance](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L326)。
- 普通怪须当前 FOV，并检查原生 sprite exists/visible/alpha；不会仅因读到 `level.mobs` 就全部报告。已探索迷雾中的高级伪装宝箱怪及特定 OBJECT 有原生例外，应逐项核对，不能一律把例外当泄漏。[entities](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L336)、[原生 updateFog 可见性规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L1645)。
- 地面 heap 要求已知格、`heap.seen` 和非空；可见陷阱用 `trap.visible`；植物只在已知格投影。普通 gas/fire 要 FOV；`alwaysVisible` 环境可在已知格出现，其描述采用与 WndInfoCell 相同 gate。[entities/environment](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L370)、[WndInfoCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoCell.java#L175)。环境数量、剩余时间没有借由 `cur/volume` 直接输出。
- `light_wall/key_wall/eternal_fire/web` 的 collision 是逐来源审查的部分覆盖，未读取隐藏合成 flag 数组来反推障碍。普通 effect 名可以较宽泛（gas/fire），但原生公开 `description` 仍保留，不应仅因为没有 Java 类名就报信息丢失。[publicEnvironmentCollision](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L427)。

## 2. W1：玩家能听见的视野外警报缺等价事件

**成立前提。** SFX 开启、全局和调用音量非零、资源已加载并成功播放，而且用户实际能听到声音。本次未操作用户音量或音频设备，不能声称当前用户一定听到了；以下“无条件”仅指不受 `heroFOV` 条件限制，不代表绕过 Sample 的开关、音量与加载 gate。

**原生公开信号。** `AlarmTrap.activate()` 只有当 `heroFOV[pos]` 为真才打印 GLog 和生成 scream 粒子，但 `Sample.INSTANCE.play(ALERT)` 位于该条件之外。`GuardianTrap` 同样如此。怪物或其他非飞行角色可以在英雄视野外占据已显示的 TRAP 格；`occupyCell` 的 soft press 会触发该类已显示陷阱。因此“英雄未见触发格，但在上述音频前提成立时可听到全局音效”的代码路径可达，不需要推测隐藏陷阱被发现。[AlarmTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/AlarmTrap.java#L43)、[GuardianTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GuardianTrap.java#L48)、[Level.occupyCell/pressCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1213)。

`Noisemaker` 也无条件反复播放 ALERT；视野内已有 `noisemaker_alarm` 的视觉粒子语义，不能报“整个 Noisemaker 状态没有表达”。视野外粒子被正确的公共 FOV gate 剪掉，此时声音仍是独立公开信号。[Noisemaker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Noisemaker.java#L102)、[VisualCueCollector.known](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/VisualCueCollector.java#L118)、[finishParticleDraws](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/VisualCueCollector.java#L234)。

**采集差异。** [Sample.play](../../SPD-classes/src/main/java/com/watabou/noosa/audio/Sample.java#L108) 只接音频播放，没有声音 observer 回调；[RuntimeObserver](../../SPD-classes/src/main/java/com/watabou/noosa/RuntimeObserver.java#L30) 有日志、文字、浮字、banner、visual cues 等回调，没有对等音频通道。视觉源 review 清单把调用登记为 audio-only，不构成听觉等价性证明。[review 样例](../../game-control/src/test/resources/combat-visual-reviews.json#L24180)。

**实际风险。** 原生玩家能知道刚才响了警报，因而保守评估即将涌来的敌人；仅依 CLI 的执行者可能继续休息/探索而不知道这个公开警示。后续地图没有变化或变化未见时，也无须然可恢复的文字。

**修补边界。** 若以后实施，应从真实公开声音的播放入口冻结经过审查的语义，例如“听到警报”；不能因为能访问调用者就额外发布未知触发坐标、具体陷阱类型、引来了多少敌人或 AI 目标。ALERT 被多种来源共用，音效本身不证明唯一来源。关闭音效/未播放成功时是否应提供替代可访问性提示，需要单独定义公开契约，不能声称玩家已听见。当前仅为静态可达链，尚未做音频设备或完整机器事件运行复现。

## 3. W2：HEAP/FOR_SALE 的价格属于按需检查，不计视觉缺陷

[PlayerObservation.entities:374](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L374) 将 `HEAP` 和 `FOR_SALE` 放在同一分支，只输出 `kind:item` 与 `item(first,"floor.cell",false)`；[item 字段](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L214) 不含出售状态、原生 heap 标题或价格。

原生 [Heap.title](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L373) 对单件 FOR_SALE 用 `Shopkeeper.sellPrice` 产生 `100g: ration of food` 一类标题；原生 context 菜单同时把“Pick up”和“Purchase”区分开。[GameScene.getObjectNames](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L1930)、[context actions](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L2023)。这是玩家本来能检查的购物事实，不是私有商品价格泄漏。

隔离夹具在同一已知 cell、同一 Food 实例上只切换 heap 类型，得到：

```text
native_free_title = ration of food
native_sale_title = 100g: ration of food
free_entities == sale_entities = true
```

原生地面 [ItemSprite.view(Heap)](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java#L214) 也把 HEAP/FOR_SALE 放进同一分支，只画 `heap.peek()`，不显示价格；售价与 Purchase 语义来自打开检查/context 窗口之后。因此这项不能直接算作“CLI 漏掉原生同屏可见信息”，更不能以获得完整世界摘要为由泄漏未检查的商品真值。

实战不能仅凭 `kind:item` 把货物计作免费战利品，也无法仅依默认实体表安排金币支出。**不会无确认自动扣钱，现有 CLI 有完整的按需查询方向**：examine 可进入 [WndInfoItem 的 heap 分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoItem.java#L62)，context 也有原生价格标题/购买操作；抵达待售物会打开交易窗。[Hero.handle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1056)。

若以后为操作效率增加默认出售/价格语义，应明确是按原生可检查知识提供的便利扩展，而非本轮已证明必须修复的同屏视觉遗漏。当前按契约先 examine/context 确认，不根据 `kind:item` 推断免费。

## 4. W3：可见仪式标记没有进入默认地形语义

`RitualSiteRoom.paint` 在地面附加 RitualMarker，并把中间 3×3 改成 `CUSTOM_DECO_EMPTY`；原生选定图块显示仪式纹样，`name/desc` 明确给出“Ritual marker”和蜡烛摆放四角的提示。[paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/RitualSiteRoom.java#L117)、[RitualMarker.create/image/name/desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/RitualSiteRoom.java#L153)。

生产投影先把 `CUSTOM_DECO_EMPTY` 折成 EMPTY。自定义补充只接受 `publicTerrain(selectedFrame)`，但基类默认返回 null；本次检索实际 override 只有 SmithyVisuals。RitualMarker 也没有 TerrainVisualCue 提供 marker 语义。[terrainAppearance](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L326)、[publicCustomTerrain](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L308)、[CustomTilemap.publicTerrain](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/tiles/CustomTilemap.java#L176)、[TerrainVisualCue 当前注册](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/TerrainVisualCue.java#L23)。

隔离夹具不是在“没有创建图层”的情况下误报：它创建原生 RitualMarker、附着 Tilemap 到未启动 GameScene，并确认 `presentationFrameAt(2,2)>=0`。对照输出为：

```text
native_inspection_name = Ritual marker
native_inspection_description = A painted marker for some dark ritual. Candles are usually placed on the four corners.
default_cell = { visibility: visible, terrain: 1, name: Floor, environment: [] }
```

这证明默认世界摘要没有把眼前明显的仪式图案作为线索提出；在九挑战中会增加遗漏任务场地、错判蜡烛使用位置的可能。**仍可通过原生 examine 获取说明**：[UiBridge.cell.select](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L750) 调 [GameScene.examineCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L1874)，在无其他对象时打开 WndInfoCell，后者使用 native custom image/name/desc。[WndInfoCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoCell.java#L125)。本夹具只调用原生名称/描述供给与生产世界 capture，没有实际构造字体窗口验证整条 UI wire。

可按 Smithy 的方式增补**实际已选定呈现**的语义 provider，保留图案/格子和原生公开文字；禁止改成读取 `RitualSiteRoom.ritualPos`、私有房间对象或生成器统计来补坐标。类似 HiddenWell、部分王座/召唤基座和 vault 标记还有 native inspect 描述，属于下一批 source-review 候选；本轮没有逐个复现，不能把它们全部列为已证实缺陷。

## 5. W4：Notes 图标身份的 UI 交叉发现

本项已转主 UI 审计归并，避免把同一缺口重复计数。

原生 Notes 按楼层添加 header，每个记录构造 `new ScrollingGridPane.GridItem(rec.icon())`；名字与说明只在点选后进入 WndJournalItem。GridItem 把图标复制为普通 Image/ItemSprite，自身不实现 RenderedStatus。UiBridge 对普通 Image 不提供地图/日记图标语义，Component 文本读取也不能从无文字的图标恢复身份。[WndJournal.NotesTab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L524)、[GridItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ScrollingGridPane.java#L168)、[UiBridge capture 判定](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L146)。

地标、七种楼层感受和钥匙有不同原生图标；数量大于一时的 BitmapText 仍可留下，所以不能说“钥匙数量一定丢失”。`ui.select` 仍能打开详情；缺口是同一层多个条目打开前缺乏公开身份，执行者需逐项点开才分辨。[Notes 图标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Notes.java#L161)、[钥匙数量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Notes.java#L312)、[UiBridge grid actions](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L946)。本文对该路径做静态核查；[16b UI 审计](16b-cli-ui-alchemy-audit.md)另以 GridItem GOLD→ENERGY 而 UiBridge 输出相同的隔离夹具确认图标缺口。条目 select 动作仍存在。主/次 Image 与 BitmapText 数量须区别处理，不据此私读 Notes.records/档案数据库。

## 6. 本轮明确保留的公开事实与排除的误报

| 项目 | 核查结论 | 证据 |
| --- | --- | --- |
| 楼层感受 | 七种特殊 feeling 已经由原生 GLog 输出，且进入 Notes；不应笼统报遗漏 | [GameScene:767](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L767) |
| 冠军/上层血巢提醒 | 已有 GLog 路径 | [GameScene:737](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L737)、[GameScene:798](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L798) |
| 实际显示日志 | GameLog draw → onGameLog → game.log → events；当前 UI 还有 feedback。不等于每个原始 GLog 信号都永久捕获 | [GameLog:158](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameLog.java#L158)、[GameController:356](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameController.java#L356)、[MachineSession:465](../../desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/MachineSession.java#L465) |
| 普通门开/闭 | terrain 5/6 和 Closed/Open door 保留；隔离复现通过 | [PlayerObservation.terrain](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L284)、[结果](evidence/cli-world/result.txt) |
| 已失效陷阱 | 没有独立 active 布尔不等于丢失；`Trap.desc` 包含 inactive 说明，默认实体保留 | [Trap.desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/Trap.java#L125)、[结果](evidence/cli-world/result.txt) |
| 不可搜索陷阱 | `canBeSearched=false` 不是原生检查面板普遍公开的布尔；不应扫描隐藏 traps 输出 | [TenguDartTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TenguDartTrap.java#L31)、[WndInfoTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoTrap.java#L31) |
| 宝箱类型/打开后结果 | container 使用 title/info，普通/锁/水晶/墓碑等身份和水晶箱公开内容类别保留；开后 type→HEAP 变 item | [Heap.open](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L110)、[title/info](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L382) |
| 宝箱怪伪装 | 原生 name/description 在牙齿隐匿规则下有不同公开提示，CLI沿用；不应加私有 is_mimic | [Mimic:121](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mimic.java#L121)、[PlayerObservation:341](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L341) |
| 乌木拟态 | 中立时 object + suspicious outline 与原生近透明轮廓一致，不直接泄漏实际怪名 | [EbonyMimic:50](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/EbonyMimic.java#L50)、[MimicSprite:157](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/MimicSprite.java#L157) |
| 动态宝箱提示 | animated_container 根据原生 hiding 动画实际已观察帧变化产生；静态高级伪装不借该提示泄漏 | [MimicSprite:52](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/MimicSprite.java#L52) |
| 特殊可见地形 | smithy、矿井出口、vault 入口/屏障、DM 场地裸线/损毁平台等有明确 source-reviewed provider；不能把全部 customTiles 都报漏 | [SmithyVisuals](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/BlacksmithRoom.java#L219)、[TerrainVisualCue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/TerrainVisualCue.java#L23) |
| 视野/迷雾环境 | 普通火离 FOV 消失，known keywall 以 alwaysVisible 保留 collision；未从未知格造地图 | [visibleEnvironment](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L392)、[结果](evidence/cli-world/result.txt) |
| 蓄力/危险格 | 邪眼、哨卫、Goo 等已有特定视觉 cue；声音没有文字化不自动构成同一事实再次漏报 | [EyeSprite:105](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/EyeSprite.java#L105)、[SentrySprite:79](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/SentrySprite.java#L79)、[GooSprite:113](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/GooSprite.java#L113) |

本轮没有确认新的隐藏信息泄漏；这是所读路径和有限夹具的结论，不是对所有敌人/地形/窗口的全局证明。

## 7. 复现资料、覆盖边界与实战措施

- [WorldObservationProbe.java](evidence/cli-world/WorldObservationProbe.java)：纯隔离状态与生产方法对照。
- [run_probe.py](evidence/cli-world/run_probe.py)：使用已有 `game-control/build/combat-visual/source-classpath.txt` 编译独立 research 输出；不运行 Gradle、不启动 launcher。
- [result.txt](evidence/cli-world/result.txt)：5 项检查全部 PASS，保留默认差异与正向 gates 的原始输出。

运行：

```sh
python3 memory/nine-challenges-warrior-4.0.2/evidence/cli-world/run_probe.py
```

上述测试依赖当前 checkout 已构建的 classpath，不能拿它代替发行包端到端验证；重新编译后应重跑并核对 source/package 身份。初次搭建夹具时 `Level.heaps` 未初始化导致一次 fixture NPE，补齐合成容器后所有检查通过；这不是生产缺陷。

未来九挑战实战的临时约束：商店物品先检查价格/出售状态；任务场地不能只按默认 Floor 标签判定无意义，结合 NPC 公开文字、已知特殊地形与必要的 cell examine；日记图标条目未知时顺次打开；不把“没有声音事件”当作确实没有警报发生。当前尚不能从 CLI 输出补造刚才的未知声音来源。

后续需单独审查的范围：实际音频播放开关/失败与事件时序、其他 custom terrain 的可见形状和检查说明、地图图形细节中独立的任务线索、所有特殊分支/首领环境、Notes UI 实际渲染 wire。背景音乐切换已有状态相关性，但本轮未证明它在已公开首领/任务/护符信息以外增加独立必要事实，未列为第四个听觉缺陷。
