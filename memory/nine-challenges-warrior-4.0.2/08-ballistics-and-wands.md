# 08　弹道、偏点瞄准与十三种法杖

研究日期：2026-10-10。源码基线：`8405ae82f206e965d14b4a3b9d4fb282f2230e47`，游戏 4.0.2 / code 922，CLI.9.0.3。适用于准备中的九挑战战士；没有执行真实游戏施法，没有读取个人存档或私有审计。

下文源码路径的 `P/` 指 `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/`。源码中的 `L` 在法杖效果段通常代表 `buffedLvl()`；涉及容量、再生预算、哨卫容量时另行标明 `level()`。装备级数、充能、敌人位置均须由实战当前公开观察确认。

证据分三层：**源码规则**由明确实现给出；**原生几何验证**是直接编译当前未修改的 `Ballistica.java`，外部依赖仅使用合成地图和占位 stub；**战术推导**还没有整场景动态验证。几何测试不证明特定实战敌人可见、可受伤、能被推动或当前有安全的施法时机。

2026-10-14补充：[21法杖主动使用](21-active-wand-play.md)把本章机制转为先手、控制、收尾、解谜及充能循环的执行表；按用户要求，不把法杖长期满充留到濒死。投武与全背包组合见[20](20-resource-conversion-fieldcraft.md)。

## 一、最值得记住的结论

1. 点某个敌人不一定得到唯一可行射线。**改点敌人前后或侧后的另一格，可以改变离散射线，在仍经过敌人的情况下绕开原先命中的墙格。** 原生 Java 合成例 A 已验证。
2. `Ballistica` 的正反路径不保证相同。玩家能直接射敌人，敌人反向的普通法术射线可能被挡；这种优势会随双方走位消失。例 B 已验证。
3. 大多数法杖 `MAGIC_BOLT` 不在点击格停；冲击和注魂 `PROJECTILE` 会停；腐蚀穿过角色、在目标或地形停；解离穿墙但有自己的射程。焰浪、再生实际伤害/控制是重新构建的锥形，不能套普通单线。
4. 九挑战下法杖仍不要求力量，控制、远程输出、地形改造和额外视野能节省真实 HP。自然充能**不检查饥饿**，但等待要支付饥饿、追兵和刷怪代价；锁层还可能暂停自然充能。
5. 再生没有绕过荒芜大地：不能种植物、不能产露；仍可在合适地形缠绕、造草和召莲花。活土守护者也受信仰护体削弱。棱光的黑暗挑战光照仅 `2+L` 回合。
6. 注魂再次打已魅惑目标，会进入“治疗对方并伤自己”分支。雷霆和冲击必须把英雄、盟友与整个后续范围算进去。哨卫的穿障碍部署，不等于炮台射击穿墙。

## 二、逐行理解 Ballistica

### 2.1 坐标与离散线

格编号 `cell=x+y×w`，x/y 均零起始；不要把历史地图宽度套进新楼层。`build` 取两端差，选择绝对值较大的方向为主轴；**只有 `dx>dy` 才以 x 为主轴，否则以 y 为主轴**。主轴长度 `dA`，次轴长度 `dB`，初始误差为整数除法 `err=dA/2`。每步执行：

```text
记录并检查当前格
cell += stepA
err += dB
若 err >= dA：err -= dA；cell += stepB
```

这是该版本的精确整数算法，不是“从中心画直线后凭视觉猜触碰哪个格”，也不是检查线段接触到的所有方格的 supercover。恰好一半时的 `>=` 会选择特定一侧；反向出发时会出现不同中间格。`dx==dy` 一步同时跨两轴；不会额外检查斜线两侧的正交格。坐标相同的退化输入会向西北继续构线，但 `Wand.zapper` 明确拒绝普通自选目标，不能利用这一几何退化当作合法施法。

来源：[P/mechanics/Ballistica.java:74–164](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/mechanics/Ballistica.java#L74) (`build`)；[P/items/wands/Wand.java:679–712](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L679) (`zapper.onSelect`)。当前仓库只有 `Ballistica.java`，没有 `PreciseBallistica.java`；不要套用其他版本/衍生版的类或算法。

### 2.2 四个位标志与碰撞顺序

| 标志 | 值 | 含义 |
| --- | ---: | --- |
| `STOP_TARGET` | 1 | 走到所点目标格时记为碰撞 |
| `STOP_CHARS` | 2 | 第一个 `Actor.findChar` 返回非空的非起点格碰撞，敌我无区别 |
| `STOP_SOLID` | 4 | 启用地形检查，包含下述“停在前格”特判 |
| `IGNORE_SOFT_SOLID` | 8 | 仅在启用地形检查时，忽略同时可通行/可避让的 solid 格 |
| `PROJECTILE` | 7 | 目标、角色、地形都停止 |
| `MAGIC_BOLT` | 6 | 角色、地形停止；点击点本身不结束射线 |
| `WONT_STOP` | 0 | 构线不因目标、角色、地形停止；调用者可另限射程 |

当前格的检查顺序不能交换：

1. **加入 `path` 前**：启用地形检查，当前非起点，而且 `!passable && !avoid && 没有角色`，则碰撞位置记为**上一个格**。
2. 把当前格加入完整 `path`。
3. 启用地形检查且当前非起点且 `solid`，则停当前格；只有 `IGNORE_SOFT_SOLID && (passable || avoid)` 可以跳过。
4. 启用角色检查且当前非起点且有角色，停当前格。
5. 启用目标检查且到达点击格，停当前格。

只记录第一次碰撞，随后仍继续生成整个几何 `path`，直到 `insideMap` 不接受该格。因此 `path` 中出现敌人格，不等于弹丸击中了它；应检查 `collisionPos/dist` 或 `subPath(0,dist)`。`dist` 是碰撞格在完整路径的索引。没有碰撞则取最后一个内部格；`insideMap` 排除地图最外一圈。完整碰撞后的路径也不是废数据：冲击的中心击退方向会读取 `path[dist+1]`。

来源：[P/mechanics/Ballistica.java:33–71,114–184](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/mechanics/Ballistica.java#L33)；[P/levels/Level.java:1564–1568](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1564)；[P/items/wands/WandOfBlastWave.java:105–116](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L105)。

### 2.3 不能按外观猜碰撞

| 情形 | 已审查源码规则 | 实战影响 |
| --- | --- | --- |
| 普通墙、锁门、路障，无角色 | 无 passable/avoid，先停前格 | “打到墙”通常是落到墙前，腐蚀/冲击的中心要据此重算 |
| 普通关闭门 | `PASSABLE` 且 `SOLID` | 普通法杖停在门格；IGNORE_SOFT_SOLID 可过 |
| 高草、犁草 | `PASSABLE | LOS_BLOCKING | FLAMABLE`，无 SOLID | 挡视线但不按这组地形位阻挡普通弹道 |
| 陷阱、井、悬崖 | 可以是 AVOID 而非 PASSABLE | 不能用“不能正常站立”推断挡法术 |
| 实心格上原生角色/物件 | 第一步的“无角色”不成立，可能停该格 | 要纳入所有公开碰撞占位者，不只敌人列表 |
| 蛛网、光墙、钥匙墙、永燃火 | 动态公开 `collision` 部分覆盖可能改变布尔值 | 按同帧明确字段叠加；不从名称或描述文字猜 |
| 铁匠铺水上装饰 | 当前默认观测保留自定义 solid 地形身份 | 普通水29与水基装饰39不同，不能见水图像就判通透 |

地形来源：[P/levels/Terrain.java:78–127](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Terrain.java#L78)。动态覆盖及公开分析边界见项目 [memory/README.md](../README.md) 和 [ray.py:117–148](../../skills/pixel-dungeon-run-assistance/scripts/ray.py#L117)；这些只是公开预测所需输入，不授权读取运行实例的私有 `level.solid` 数组。

## 三、经原生 Java 验证的瞄准技巧

全部例子使用**合成**宽12、高10地图，默认内部格 `passable=true, avoid=false, solid=false`，列出的墙覆盖为 `false,false,true`，角色占位明确给定。各例之间不共享地图。没有推断真实存档中的任何格子。

### 例 A：直接点敌人被挡，改点更远处命中

- 英雄 H=`(2,2)`，cell26；敌人 E=`(5,3)`，cell41；墙 W=`(4,3)`，cell40。
- 直接点 E，`MAGIC_BOLT` 生成 `(2,2)→(3,2)→(4,3)→(5,3)…`；碰到 W 时停在前格 `(3,2)`，cell27。完整 `path` 仍含敌人，但实际没到敌人。
- 改点 X=`(7,3)`，cell43：`(2,2)→(3,2)→(4,2)→(5,3)…`；绕过 W，首个角色正是 E，所以 `collisionPos=41`。

```text
       x=2  3  4  5  6  7
y=2     H   ·  a  .  .  .
y=3     .   .  W  E  .  X
```

`a` 是改点后的 `(4,2)`，直接瞄准没有经过它。改变的是整数射线斜率和取整，不是弹丸拐弯、无视墙或敌人隐身。足够靠后的另一目标格能改变路径，但不是所有障碍都有解。

验证用例：`direct_aim_blocked` / `farther_aim_bypasses_wall`。战术推导：先枚举安全可选目标，再用同一公开帧条件性预测；找到满足 `collisionPos==敌人格` 的候选后，还要评估施法回合及附加效果。

### 例 B：同一对端点的正反线不同

- H=`(2,2)`，E=`(6,4)`，墙=`(5,3)`。
- H→E：`(2,2),(3,3),(4,3),(5,4),(6,4)`，命中 E。
- E→H：`(6,4),(5,3),(4,3),(3,2),(2,2)`，第一步墙使碰撞停在 E 自己。

验证用例：`forward_tie_clear` / `reverse_tie_blocked`。DM-100、萨满、术士的非近身攻击条件使用其自身位置发射的 `MAGIC_BOLT`；蝎子使用 `PROJECTILE`。因此有机会利用单向射线先打而不承受同类型直射。**不能据此保证敌人不会移动、换目标或使用不同技能**；是否观察到英雄、是否近身、是否已有蓄力必须另看公开状态与敌人源码。

来源：[P/actors/mobs/DM100.java:75–88](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM100.java#L75)、[Shaman.java:72–94](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Shaman.java#L72)、[Warlock.java:77–86](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Warlock.java#L77)、[Scorpio.java:72–75](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L72)。邪眼用 `STOP_SOLID`，Yog 主体光线用 `WONT_STOP`，不能照搬普通魔弹掩体结论（[Eye.java:94,117,128](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L94)；[YogDzewa.java:199,265,285](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L199)）。

### 例 C：斜角两侧墙不自动挡住对角线

H=`(2,2)`，E=`(3,3)`，两面墙位于 `(3,2)`、`(2,3)`。原生 `MAGIC_BOLT` 只访问 H→E 的对角格，不检查这两面侧墙，结果命中 E。用例：`diagonal_corner_does_not_probe_side_cells`。这只证明弹道，不证明地形寻路、可见性或普通近战也允许同样动作。

### 例 D：击中同一敌人，但改变冲击的中心击退方向

H=`(2,2)`，E=`(3,3)`，`PROJECTILE`：

- 点 E：路径前三格 H→E→`(4,4)`，中心击退按向东南延长的轨迹生成。
- 点 `(4,3)`：路径前三格 H→E→`(4,3)`，仍先撞 E，却改成向东延长。

用例：`blast_direct_aim_next_cell_southeast` / `blast_offset_aim_next_cell_east`；中心逻辑见 [P/items/wands/WandOfBlastWave.java:113–116](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L113)。这提供把敌人推向悬崖、避开盟友、推离通路的调节手段。**还需对新的击退路径做第二次 Ballistica 判断**，检查 root/IMMOVABLE/BOSS/LARGE、落点占位、飞行和途中机关，不能把上面的方向变化当作保证击落。

### 复现与结果

```sh
python3 memory/nine-challenges-warrior-4.0.2/evidence/run_ballistica_examples.py
```

- [复现脚本](evidence/run_ballistica_examples.py)：临时目录编译当前未修改原生源码；不启动游戏、不写个人 profile，临时编译目录自动回收。
- [完整结果](evidence/ballistica-native-results.json)：**21/21** 合成用例通过；包括偏点、方向不对称、点击后延伸、角色阻挡、软/硬地形、IGNORE_SOFT_SOLID、完整路径、边界、对角角落和冲击方向。
- 被测 `Ballistica.java` SHA-256：`3cce12645f1bf662481887df0fca19f0d68130741ff58a66557995ba3a359427`；Java 版本在 JSON 中记录。
- Stub 只代替 `Dungeon.level` 三布尔数组/边界、`Actor.findChar` 合成占位及异常报告。**没有验证完整 `ConeAOE`、onZap 动画回调、伤害/免疫、实际 AI 或控制协议。** 源码改变后需重跑并重新审查策略，不只看历史绿色结果。

## 四、用公开地图选择目标的流程

1. 从本帧完整解码的 `hero.cell`、`map.w/h`、全部已知格、所有角色/物件、`map.env.collision` 取得输入。旧帧、旧句柄和旧 revision 都不能作为当前施法绑定。
2. 查这根法杖此刻的 `collisionProperties(target)`，包括诅咒和哨卫的动态规则；再查 onZap / fx 是否重建锥形或有射程。不能因为名称含“光束”就选 solidbeam。
3. 先算直接瞄准；若失败，在**当前允许选择且已知的格**中枚举候选。对 `MAGIC_BOLT/PROJECTILE` 找第一个碰撞恰为目标的候选；对解离需找射程内完整线经过目标；对腐蚀需找云能安全覆盖的落点；对冲击还算中心后的方向。
4. 逐格验证候选，遇未知地形停止给出确定预测。当前没列出角色不证明绝对无隐藏占位；已探索而非可见的格保留这个假设。公开物件若声明 `projectile_collision:"character"`，必须按阻挡处理。不要从影子、声音、旧敌人格推导未观察角色。
5. 同时比较自己耗时1回合后可能受到的攻击、盟友误伤、地面补给、陷阱、火/气扩散、退路和剩余充能。几何更优但额外跑数步的站位不一定资源更优。
6. 只执行一个当前 advertised operation，精确使用本帧 rev；收全响应，若中途敌人移动/开门/长草/换层，重新规划。控制器 `settle` 不推进玩家等待回合，不能用游戏 wait 替代收敛。

有限搜索的明确算法（分析流程，**不是现成CLI参数或自动执行器**）：令H为本帧英雄格、E为当前公开目标角色格，令C为“本帧已知、符合当前目标operation约束、且不等于H”的候选格集合，最多`w×h−1`项。每个候选独立按本章原生步进计算，复杂度约`O(|C|×max(w,h))`；只在同一个冻结公开帧内比较：

```text
results = []
for aim in C:
    flags = 本根杖本次面向aim的实际碰撞规则
    ray = 按原生Ballistica计算；遇未知必要地形/动态覆盖即标记不确定
    if 不确定: 不计为已找到可命中解
    else if 普通MAGIC_BOLT或PROJECTILE:
        if ray.collisionPos == E: 保存aim、实际行进格、假设
    else if 解离:
        if E在ray.path[1 : min(ray.dist, 6+2L)+1]: 保存候选
    # 腐蚀、冲击、焰浪、再生、哨卫须分别追加其落点/范围规则，
    # 不能把“中心线包含E”作为统一命中判据。
```

先尝试E本身能减少计算；失败再枚举C，或先查看E附近/后方的小范围作为加速，但只有完整枚举C后才能说“在这个候选集合与公开假设内没找到解”。同样命中E的候选可能具有不同的冲击方向、墙前落点或后续延长线；保留这些差异再做安全排序。这个流程不要求、也不授权读取未探索格或隐藏角色。若未来实现自动候选工具，必须延续现有公开输入、源码hash审查和未知停止规则。

项目现成工具可条件性核对三类射线：

```sh
python3 skills/pixel-dungeon-run-assistance/scripts/ray.py --response /path/to/complete-public-response.json --target 43 --op magicbolt
python3 skills/pixel-dungeon-run-assistance/scripts/ray.py --response /path/to/complete-public-response.json --target 43 --op projectile
python3 skills/pixel-dungeon-run-assistance/scripts/ray.py --response /path/to/complete-public-response.json --target 43 --op solidbeam
```

这里43仅为合成说明，实战需换为当前候选 cell。该工具**不包含自动搜索、不计算所有法杖覆写/锥形/射程/范围，也不控制游戏**。此研究没有修改它。不要把 `solidbeam` 当作解离：前者会挡墙，后者 `WONT_STOP`。工具输出中的 `conditional` 和 `first_unknown_stops_prediction` 是决策必须保留的边界。

## 五、十三种法杖的完整碰撞与效果表

以下全部指**未诅咒施法**。普通施法耗时1，通常一充能；焰浪、再生的 `c=clamp(ceil(curCharges×0.3),1,3)`，所以当前1–3充能用1、4–6用2、7–10用3。表中基础伤害不是最终承诺：多数直接 `ch.damage(...)`，不进行普通武器命中检定/DR流程，但仍可能受魔抗、免疫、冠军减伤、护盾等改变。源码：[P/items/wands/Wand.java:80,437–439,489,540–543](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L80)；[DamageWand.java:47–62](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/DamageWand.java#L47)；[P/actors/Char.java:929–951](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L929)。

| 法杖 | 碰撞/射程 | 原生主要效果 | 九挑战战士的判断 |
| --- | --- | --- | --- |
| 魔弹 MagicMissile | `MAGIC_BOLT` | `2+L…8+2L`；命中角色后，若有更低级杖，给4回合 MagicCharge，让较低级杖借其等级一次；初始容量3 | 高等级魔弹可带动多根控制杖；前置一发耗回合和充能，空地不触发加持 |
| 霜冻 Frost | `MAGIC_BOLT` | `2+L…8+5L`；存活者 Chill，陆地`2+L`、水中`4+L`；已有 Chill 时伤害乘`0.9333^min(10,剩余时长)`；已有 Frost 则不伤害 | 减速后拉开；连发冻缓目标的边际输出下降，换杖或投掷补刀；可清火、冻物堆 |
| 雷霆 Lightning | 首段`MAGIC_BOLT`，随后递归电弧 | `5+L…10+5L`；N个受伤目标各乘`.4+.6/N`，初始落点在水中则全倍率1；链节点水中传播2格，否则1格，经非solid路径 | 水中群怪高效；英雄若邻接链节点可能吃半倍率自伤。盟友能参与传播，不能当断路器 |
| 解离 Disintegration | `WONT_STOP`，有效距离`6+2L`个路径步 | `2+L…8+4L`；穿墙穿角色，命中人数和之前穿过的solid格提高全体伤害有效等级；销毁可燃地形 | 排线、多目标、借墙增伤；同线盟友也危险。穿墙能力不提供未知敌人情报 |
| 焰浪 Fireblast | 选向`WONT_STOP`；真正锥形每线`STOP_TARGET|STOP_SOLID|IGNORE_SOFT_SOLID` | c=1/2/3时半径5/7/9、角度50/70/90°；伤害`(1+L)c…c(2c+2L)`；点燃；c2残废4，c3麻痹4 | 群体压制、烧路障、开门；检查草、物堆、后续环境火及撤退路线，主线可穿过软solid不等于穿硬墙 |
| 冲击 BlastWave | `PROJECTILE`；落点九宫格 | `1+L…3+3L`；中心推力`L+3`，邻格`round(1.5+L/2f)`；先按压九宫格，天狗飞镖陷阱除外 | 击落、脱离包围、推向已知陷阱；先算英雄/盟友和第二条击退轨迹 |
| 棱光 PrismaticLight | `MAGIC_BOLT` | `1+L…5+3L`；恶魔/亡灵伤害约×4/3；概率`1−3/(5+L)`致盲`2+L×.333`；沿线九宫格测绘/揭密 | 控制、探陷阱、侦查很重要；黑暗挑战Light只有`2+L`回合，不能当长效火把 |
| 腐蚀 Corrosion | `STOP_TARGET|STOP_SOLID`，不在角色停 | 落点播种`50+10L`气体量，初始强度`2+L`；实际伤害来自扩散气体与递增Corrosion | 可越过前排投到后方，卡口后撤；无阵营豁免，不要站云旁硬打 |
| 腐化 Corruption | `MAGIC_BOLT` | 力量`3+L/3f`与基于敌种、血比、debuff的抗性比较；够强转盟友，否则负面状态；免疫腐化则尝试Doom | 削血/叠状态后更省充能；满血硬刷高阶冠军通常昂贵；转换并非永久随从 |
| 再生 Regrowth | 选向`WONT_STOP`；真正锥形`STOP_TARGET|STOP_SOLID` | c=1/2/3时半径4/6/8、角度30/40/50°；合适地形上的角色Roots `4c`；造草，c3召莲花 | 荒芜下仍有控制/遮视线；水、门、陷阱上不保证缠绕，不能把它当产露治疗手段 |
| 活土 LivingEarth | `MAGIC_BOLT` | `4…6+2L`；击中合适对象产生岩甲/为守护者补血；岩甲门槛`8+4L`可转守护者 | 降低部分攻击伤害、转移敌人仇恨；守护者NO_ARMOR减伤也被削，不能站桩硬扛 |
| 注魂 Transfusion | `PROJECTILE` | 敌人分支给英雄`5+L`盾；非亡灵魅惑，亡灵伤`3+L…6+2L`；盟友/已魅惑目标分支治疗对方并伤自己 | 第一发控敌兼护盾；第二发打已魅惑同目标会反转收益，必须看其当前buff |
| 哨卫 Warding | 目标在heroFOV时仅`STOP_TARGET`；否则`PROJECTILE`；炮台射击仍`MAGIC_BOLT` | 布置/升级固定炮台；总容量`Σ(2+level())`，单位占tier；炮台必中魔伤`2+L…8+4L` | 可见空格可越障部署、提供视野，利于黑暗和预设阵地；部署仍检查目标通行、占位和容量，并触发所在格 |

逐项源码定位均在 `P/items/wands/`：[WandOfMagicMissile.java:48–103](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfMagicMissile.java#L48)；[WandOfFrost.java:51–110](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFrost.java#L51)；[WandOfLightning.java:63–103,144–198](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLightning.java#L63)；[WandOfDisintegration.java:47–139](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfDisintegration.java#L47)；[WandOfFireblast.java:57–155,219–258](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L57)；[WandOfBlastWave.java:58–180](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L58)；[WandOfPrismaticLight.java:53–153](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfPrismaticLight.java#L53)；[WandOfCorrosion.java:49–78](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorrosion.java#L49)；[WandOfCorruption.java:127–235](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorruption.java#L127)；[WandOfRegrowth.java:61–215,244–284](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L61)；[WandOfLivingEarth.java:57–199,262–305,387–424](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L57)；[WandOfTransfusion.java:54–152](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfTransfusion.java#L54)；[WandOfWarding.java:57–168,233–409](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L57)。

### 5.1 伤害之外的关键细节

**霜冻**：空落点会 `pressCell`；普通火清落点，永燃火额外清 `path[dist+1]`，因为其碰撞可能停在火前。对已Frost的目标提前返回仍已消耗施法，不要把“冷冻敌人”当重复伤害靶。冻物堆和清火先于角色Frost返回（[WandOfFrost.java:62–109](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFrost.java#L62)）。

**雷霆**：传播表在 `fx` 先完成，`onZap` 再移除非初始目标的同阵营角色和带 LightningCharge 者，因此某角色最终不吃伤害并不意味着它没有帮助继续链电。英雄只在距某链节点寻路距离≤1时加入，水中2格传播不扩大英雄这一自伤门槛；仍须检查所有连锁节点，不能只看初始敌人与英雄距离（[WandOfLightning.java:74–85,144–169](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLightning.java#L74)）。

**解离**：`terrainPassed` 初始2，每遇一个角色先把 `terrainPassed/3` 累入地形奖励、再保留余数；solid格随后使计数+1。最后一次命中以后的墙不贡献奖励。最后全部命中角色使用 `L+(chars.size−1)+terrainBonus`，不是每个角色只按到自身之前的累计独立算。代码有不伤害未发现区域被动角色的特判，却没有普遍盟友豁免。射程按路径步，不能用欧几里得距离替代（[WandOfDisintegration.java:80–127,138–139](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfDisintegration.java#L80)）。

**焰浪/再生锥形**：`ConeAOE` 以coreRay实际终点形成朝向，按真坐标距离裁半径，每0.5°扫描外弧格，必要时加内圈射线；最后各线取 `subPath(1,dist)` 的并集。点击格不是承诺的固定锥形终点，一条安全中央线也不能证明侧翼安全。焰浪每线忽略软solid并开普通门，再生不忽略；不能把`WONT_STOP`选向字段误解成真实锥形穿墙（[P/mechanics/ConeAOE.java:46–112](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/mechanics/ConeAOE.java#L46)）。

**冲击**：九宫格触发机关早于伤害/推动；周边盟友免直接魔伤，仍会被推动，中心角色不享受该豁免。Boss推力 `(power+1)/2`；rooted、IMMOVABLE不移动，LARGE需沿途openSpace；占用的最终格会后退一格。撞击可追加 `distance…2×distance`伤害与`1+distance/2f`麻痹。英雄也是需要计算的角色；邻近爆心可能不受直接魔伤但依然撞墙、落坑或碰机关（[WandOfBlastWave.java:79–180](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L79)）。

**腐蚀**：`CorrosiveGas` 在整层使用一个共享强度，`setStrength`只升不降、volume归零才清零。身处气体每tick给Corrosion续2回合并抬最低强度；现有DOT伤害不会因较弱云回降。DOT初始为`2+L`，每tick先打整型damage，低于`floor(scalingDepth/2)+2`时增长1，否则增长0.5。源码推导：魔弹加持一次高强度云可抬高同层未消散的既有腐蚀气体，而低级续云不会降强度；同样会扩大对英雄的风险，未运行整场景验证（[P/actors/blobs/CorrosiveGas.java:34–74](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/CorrosiveGas.java#L34)；[P/actors/buffs/Corrosion.java:71–75,104–115](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Corrosion.java#L71)）。

**腐化**：通常基础抗性来自`1+AscensionCorruptResist(enemy)`，特殊敌种另算；乘血量比系数`1+4(HP/HT)^2`，满血5倍、半血2倍、四分之一血1.25倍。每个重大debuff乘0.5，每个轻微/其他负面buff乘0.75，乘法累计；Chill、Roots、Burning等虽不由杖随机施加，也可降低抗性。力量严格大于最终抗性才直接转换，否则力量/抗性是尝试重大debuff的概率；已有/免疫debuff不重复选，池耗尽可升档。真实HP未知时只作区间估计，不得读取敌人隐藏数值。腐化会治疗目标并清负面，随后每tick累计约1%HT自耗，宜趁存活窗口交战（[WandOfCorruption.java:89–235](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorruption.java#L89)；[P/actors/buffs/Corruption.java:39–73](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Corruption.java#L39)）。

### 5.2 九挑战会改写的法杖价值

**再生**：允许生长集合为EMPTY、EMBERS、EMPTY_DECO、GRASS、HIGH_GRASS、FURROWED_GRASS；IMMOVABLE或已有植物格剔除，剩下的格才缠绕角色。`Level.plant`在NO_HERBALISM下立即返回null，因此露珠草/种子荚/随机植物均不能落成，莲花也不能绕过先执行的禁种检查。造草、Roots、直接GameScene.add的莲花不走plant禁用路径，仍存在；莲花自身视距1可贡献公开视野。普通高草仍可能掉种子，可为其他机制/炼金留用，但不能种植。来源：[WandOfRegrowth.java:99–174,417–453](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L99)；[P/levels/Level.java:1068–1070,1494–1519](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1068)；[P/levels/features/HighGrass.java:127–144](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L127)；[P/Challenges.java:71–75](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)。

再生的有限预算使用**真实level()**，不是魔弹临时buffedLvl；+10才无限。超量会提高犁草概率，不取消Roots。该版本`chargeLimit`表达式中的`wndLvl/(50-5*wndLvl)`是Java整数除法，不能直接抄旁边注释的浮点数列：0…8级时该商为0，9级为1；实际预算为`round(20+heroLvl×(2+wndLvl)×(1f+整数商))`。`totChrgUsed`与`chargesOverLimit`持久化，普通退出重进不会把它们清零（[WandOfRegrowth.java:190–215,323–338](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L190)）。此处为语言/源码求值结论，未另做完整再生法杖实验。

**活土**：英雄RockArmor每次在`Hero.defenseProc`最多吸收剩余伤害的向上取整一半，并消耗岩甲；总量上限`2×(8+4L)`，不能泛化为毒、气体、饥饿等所有伤害半减。守护者HT=`16+8L`，NO_ARMOR下额外DR仅`L…2+L`，普通局则`L…3+3L`；打到敌人会使其aggro守护者，无可见敌人时可回归英雄岩甲并转入剩余HP。可靠用途是吸引/分担有限攻击，空射不产护甲（[WandOfLivingEarth.java:74–199,286–305,387–424,464–476](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L74)；[P/actors/hero/Hero.java:1606–1609](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1606)）。

**注魂**：英雄没有原生战斗法师法杖近战`onHit(MagesStaff,...)`的免费施法机制。敌人分支给盾，不要求花HP；但`ally || Charm!=null`优先于敌人分支，因而反复点同一被魅惑敌人会先治疗/护盾它，然后向英雄造成`round(HT×.05)`伤害。魅惑不等于永久盟友，后续普通攻击与其他伤害可能改变它；每发先重查。对亡灵是给自己盾兼伤害，某些对象免疫/抵抗仍须逐敌判断（[WandOfTransfusion.java:90–143,149–152](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfTransfusion.java#L90)）。

**哨卫**：已在heroFOV中的目的地只按STOP_TARGET，确实可越过沿途墙/角色部署。终点仍要passable且无非Ward角色；撞到角色时可能退到射线前格，若仍占用则bad_location且可能pressCell，所以应点明确空格。总占用按tier；tier1/2/3累计射击1/3/5次后消失，tier4/5/6每发自损5/6/7；4/5/6阶的杖治疗量9/12/16。视距4起，每次升级+1至9，Level会把Ward视野并入英雄，提供Darkness下真正可使用的观察，而非读取隐藏地图。提前布好后把群怪引入火网，比战斗中花多回合堆等级稳妥；容量不足的≤3阶不能升级，>3阶仍可治疗（[WandOfWarding.java:65–168,233–335,392–409](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L65)；[P/levels/Level.java:1494–1519](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1494)）。

## 六、升级、树脂、充能与法杖鉴定

### 6.1 别把普通战士当战斗法师

每个法杖类的 `onHit(MagesStaff staff,...)` 是灌注法师法杖的近战效果，不是“持有这根法杖时战士每次挥剑的被动”。例如魔弹近战给其他杖回充、注魂近战给freeCharge、再生近战草地治疗，不能当战士基础资源。跨职业天赋仅在本局实际获得对应变形天赋且公开可确认时考虑。

### 6.2 有限升级卷轴下的资源分配

- 普通容量`min(initialCharges+level(),10)`；魔弹初始3，其余2。升级通常提高直接输出/控制/容量；树脂也进入level。提高容量还使同样当前余量时的missingCharges变大，间接加快自然回充，但并不瞬间填满全部新容量。
- 原生升级卷轴调用`Wand.upgrade`，若有树脂奖励则**先消耗1点树脂奖励**；基础等级+1与树脂−1抵消，当前有效等级这一发可能不变。不要先树脂堆到+3，再误以为下一张升级必到+4。
- 施用树脂要求目标已鉴定；总`level()<3`，每升一级花`当前level+1`树脂，0→3共1+2+3=6；增加1点当前充能并花1回合。它不执行驱邪，不保证清诅咒。
- 炼树脂要求明确知道非诅咒，不一定已知等级；消耗整根杖和5炼金能量，产量`2×(level−resinBonus+1)`，因此树脂强化不会回收成额外树脂。非Mage的实际WAND_PRESERVATION天赋另加点数，不是战士固有奖励。
- 多根法杖各自独立充能。熔掉“低级重复杖”会永久少一个储能池；先比较每次控制价值、独立充能、用途缺口，再决定牺牲。九挑战卷轴稀少时，树脂到关键控制阈值通常值得考虑，但不预先承诺某种随机掉落。

来源：[P/items/wands/Wand.java:346–375,428–439](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L346)；[P/items/ArcaneResin.java:105–145,151–197](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ArcaneResin.java#L105)。

### 6.3 回充的真实条件

持有时每根Charger每tick行动。设缺失充能数m，自然部分为：

```text
turnsToCharge = 10 + 40 × scalingFactor^m
每tick partialCharge += RingOfEnergy.wandChargeMultiplier / turnsToCharge
普通 scalingFactor=.875；魔法枪套=.85
每个有效Recharging buff另加 .25 × remainder()
```

普通无戒指，缺1/2/3点时单个tick的充能速率分别相当于45/40.625/36.796875回合一充能；这不是从空到满的固定时间，因为每补一充能m都会变小。最后一充能最慢。达到上限时partialCharge清零，长期背着满杖是在放弃自然恢复机会；有安全用途时适时使用更好。

`MagicImmune`停止Charger.recharge；自然项另受`Regeneration.regenOn()`限制，而后者只检查LockedFloor是否允许。**没有饥饿检查**：饥饿禁自然HP回复不等于禁法杖回充。Recharging额外项在recharge内、自然regenOn判断外，因此锁层停止自然回充时仍可能靠明确充能buff恢复；魔法免疫则整个recharge跳过。实际自然/额外tick由actor调度，临界回合不要许诺已经多一发。

source：[P/items/wands/Wand.java:805–862](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L805)；[P/actors/buffs/Regeneration.java:113–118](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L113)；[P/actors/buffs/Recharging.java:50–56](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Recharging.java#L50)；[P/items/bags/MagicalHolster.java:36,58](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bags/MagicalHolster.java#L36)；[P/items/rings/RingOfEnergy.java:64–71](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/RingOfEnergy.java#L64)。退出重进会保存`curCharges/partialCharge`；`Charger.attachTo`对英雄已花部分回合的恢复专门延迟一tick，不能假设重载免费加充能（[Wand.java:603–627,815–824](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L603)）。

### 6.4 未知法杖与诅咒

普通随机法杖自身生成等级为+0概率2/3、+1概率4/15、+2概率1/15，独立有30%诅咒概率；特殊奖励/生成调用可能不同，不能把这个先验当具体物品身份。通常需要10次可计鉴定用途，但初始可用鉴定进度仅5；获得经验补进度，所以安全房里反复空射并不必然迅速鉴定。`Wand.wandUsed`、天赋与遗忘碎片饰物还会改鉴定流程（[Wand.java:93–95,283–288,458–478,546–564](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L93)）。

诅咒会把真正碰撞强制换成`MAGIC_BOLT`，并调用`CursedWand.cursedZap`，不执行表中的常规效果；已知/未知诅咒还可能使目标预览不同。因而“这是一根霜冻，能保证灭火救命”只有在诅咒状态已明确时才成立。施法后才把cursedKnown置true。原生普通升级仅1/3概率清诅咒，不能当确定驱邪；树脂没有该清诅咒调用（[Wand.java:129–135,359–369,637–639,757–792](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L129)）。九挑战HP缺乏时，测试未知杖要预留可撤退空间和应急资源，不应在被围时寄希望于未知随机施法。

## 七、实战前检索清单

拿到法杖时核对类型/等级是否已知、诅咒、当前/最大充能、升级来源、范围风险；打新敌人前核对免疫和冠军类型。准备通过偏点瞄准节省回合时，至少留下：本帧身份、英雄/敌人/候选cell、实际选择的碰撞模式、到碰撞格的路径、未知输入与假设、完整范围对英雄的影响。成功施法后以当前公开状态验结果，不能把合成图、源码预测或“请求completed”替代实际命中/击退证据。

本章完成的是源码审查、21项原生几何合成验证以及可供后续实战调用的策略索引。锥形边缘、复杂气体传播、怪物决策和载入时序尚不能用这份几何结果宣称实战验证；真实游玩需按项目skill逐次观察。
