# D21–25 主线只读计划（本地v3.3.8）

仅查本地源码/允许的存档，不操作UI、不改游戏。依据code896、HEAD4a272100b，普通模式。当前主代理正在D20收尾，角色约L16，Crossbow4 Chilling、Scale6 Thorns/Seal1、Accuracy0、Evasion3、HoldFast3/Cleave1、Heal4、Hourglass5、Fireblast1、Frost0。以后每层生成后必须新读地图与怪物；本文件不给尚未生成楼层虚构坐标。

**D25实际执行后的时序纠正：**Hero普通移动先改变pos再spend；Leap也先落地再spend，Blink在恢复Actor前完成落点。因此单次合法移到Yog固定旧线外，不要求该步/跳动作耗时小于Yog.delta。若多步的中间格仍在线，只比较最终脱线那次位置变化之前已经耗掉的时间。例如两步看第一步耗时是否小于delta，不看两步总耗时。后续`yog-final-plan.md`和`rusted-current-plan.md`对此有完整源码依据；任何先前把动作耗时误当作位置生效前等待的推论均作废。

## 皇冠立即能用，不需要L20

KingsCrown.execute只检查是否穿着护甲，没有等级条件；WndChooseAbility也不按等级禁选，ClassArmor.execute只检查已装备/充能足够。**L16拿到皇冠就可选能力并用。**第四层天赋点是L21开始，这与能力本体解锁不同。

转换成WarriorArmor时保留原tier、真实强化等级、刻印、印章、增强与硬化等字段；当前Scale6 Thorns/Seal1不丢，初始armor.charge=50。皇冠使用花1回合，王死后安全处完成。

推荐HeroicLeap：无T4点也只耗35充能，跳跃花1回合，距离按STOP_TARGET|STOP_SOLID弹道到实际落点，途中可越过角色，落点有人则向后退到空格。可用于贴近Scorpio、跳出激光/危险区，与Hourglass提供两种逃生资源。**rooted时不能跳，落点照样occupyCell：会触发陷阱、踩植物或掉坑；不能跳到CHASM把它当飞行。**跳后解除隐身/冻结，危险不会因动画而免除。当前无天赋不带落地伤害或击退。Endure无天赋耗50、需站3回合，当前不优先；Shockwave基础可用但更偏输出，无法提供跳跃机动。

ClassArmor自然0.2充能/世界回合，0→满约500回合，50→再够第二跳只需积累20（约100回合）；受Regeneration.regenOn开关。不能当每十来回合可跳一次。

## 先完成D20王后商店，再入D21

本局Imp任务已完成，CityBossLevel.unseal会在**D20上方出口大厅**生成Imp商店，不必等D21。优先查看/买治疗、两份口粮、火把，视价格买沙袋扩Hourglass，再补MagicMapping/实用药。现主武器和鳞甲已有强化，商店普通+0武器/+0Plate不是自动升级，不花大钱替换。

源ShopRoom在D20/21固定有Healing1、SmallRation2、Torch3、RemoveCurse/Identify/MagicMapping各1，其他随机物品和沙袋数量须读实际商店。现有没用的BattleAxe2、Might0可卖以补资源；BattleAxe未ID售价80，勿误报240。商店库存已在D20生成时决定，但死王才摆出，届时用当前实体核坐标/价格。

## D21–24 每层优先顺序

1. **火把→查入口短路与躲射线位置→DemonSpawner→Strength/SoU/Healing/食物→出口。**能量/好装备只顺路，不做巨额时间支线。
2. HallsLevel每层强制一个DemonSpawnerRoom，每层生成两支Torch。自然视距上限D21=5、D22=4、D23=3、D24=2；火把提供Light250回合、视距至少6。敌人常有6格视距，先照明再暴露开门；存档“全图已知”不能代替角色FOV。
3. 正常四层合计还应生成**3张SoU、2瓶Strength**（21/22一瓶，23/24一瓶）。不跳过所在层的主线强化。优先补输出而不是继续堆已+6的物理甲，但每张具体投入按当时武器/控制资源决定。
4. 当前Chilling弩从+4开始普通升级会有洗附魔概率：+4→5为10%，+5→6为20%，+6→7为40%。想保证保留可把SoU炼成MagicalInfusion，每张另耗12能量；不是免费。不可一口气升级后才发现寒冷丢失，WndUpgrade确认后逐张读实际enchantment。
5. 强化甲+闪避能处理近战，仍不能替代射线/跳扑规避。按照已验证的一动作→存档→截图流程，门格就可能警觉；静态路径上的某个“预估警戒点”不证明其前面安全。

## 四个Spawner都拆

120HP、防御技能0、DR0–12、IMMOVABLE/MINIBOSS/STATIC，EXP15，必掉Healing。四只共60EXP和4瓶治疗，既帮助当前低等级，也使Yog召唤更轻。

Spawner每残留一个，Yog的四只一轮小怪中就有一只Larva替换成YogRipper；普通模式不因此增加拳头数量或王的HP。**推荐四层全部拆，不为了赶路跳过。**

产Ripper基础冷却D21/22/23/24约60/53.33/46.67/40回合；受到伤害会按伤害推进spawn_cooldown，大伤还被压缩：20以上输入转为19+floor((sqrt(8×(dmg−19)+1)−1)/2)。因此全力打它会加速生怪，不能假设“迅速砸掉就不会孵化”。在有退路的位置打，Ripper出现/跳扑预警立即切换优先级。普通Ripper不提供杀怪经验，别围Spawner刷它们升级。

## 四类怪物的实际边界

| 怪物 | 关键数值 | 安全原则 |
|---|---|---|
| RipperDemon | HP60，15–25物伤，攻30/防22，DR0–4，0.5回合攻击，击杀无EXP | 跳扑目标leap_pos预警，可能预测当前移动方向的下一格；离开实际落点，别沿预测方向连续走。跳中用无限命中判定并施0.75×伤害的Bleeding，物理甲不能减这份流血。近战寒冷有效，但不赌双拳。 |
| Succubus | HP80，25–30物伤，攻40/防25，DR0–10，EXP12 | 距离>2且看到目标可闪现近身，不保证有空走一回合；成功近战约1/3施魅惑。被其魅惑后不能正常攻击它，它再打你会回血/加盾。用法杖/移动度过魅惑并读Charm.object；不要一直撞击魅惑者。 |
| EvilEye / Eye | HP100，近战20–30，攻30/防20，DR0–10，EXP13 | 蓄光受伤÷4；光束30–50魔法伤害，普通甲不减。退实体墙/关门断LOS后让它发旧线，勿正面赌秒杀。 |
| Scorpio | HP110，30–40物理投射，攻36/防24，DR0–16，EXP14，命中50%Cripple | 相邻不能攻击，会后退；别在无遮蔽长通道追，利用墙角接近或HeroicLeap贴身，再寒冷压住。投射是物理，现甲有效，但群体射击仍不可承受。 |

**Eye特别核实：**本版Eye.canAttack在beamCooldown=0、目标仍可见且在FOV时会更新beamTarget；Eye.Hunting在已蓄光时也先调用canAttack再doAttack。因此**只横移离开画面上原来的线、但仍被Eye看到，不能保证躲掉**。需要断FOV（实体墙、真正关门；普通草/装饰不当作可靠墙），然后确认beam_target是否仍旧、展开光束路径；已蓄光失去目标也会发射旧位置。常规蓄光动作耗attackDelay×2，发射后冷却4–6次自身行动。隐身也可能阻止重新瞄准，但仍需离开旧线。

危险陷阱包括Grim、Disintegration、Corrosion、Rockfall、Pitfall、Disarming、Warping等；未生成地图不猜位置。高强化弩/甲尤其避免Cursing、Disarming。Leap落点与Hourglass解冻后的延迟press也必须避开。

## Yog 普通模式核心

本体1000HP，700/400/100触发三次拳头；三对Burning/Soiled、Rotting/Rusted、Bright/Dark各随机选一只，顺序打乱。任意拳存活时Yog本体无敌；拳头在距Yog锚点≤4时也无敌，必须引到距离≥5再输出。最后一拳死仍有最后100HP，不是立即胜利，会加速激光和小怪召唤。

Yog激光按必中处理：attackSkill=INFINITE_ACCURACY，20–30魔法伤害，普通Evasion3不能靠概率躲，物理甲不减。Parry无限闪避优先级更高，理论能挡时间吻合的一发，但可能先被小怪用掉；默认真正走出射线。targeted_cells存的是瞄准点，实际要用Ballistica.WONT_STOP展开**穿墙且越过目标点的整条直线**，不是只避红点。预警花clamp(ceil(hero.cooldown),1,3)时间，常见1回合，下一act发射；每动作重读全部预警线。后续具体拳型/布局以D25新存档为准。

六拳按实际出现选择控制，不把所有拳当成同类近战：

| 拳 | 关键区别 | 当前装备的应对 |
|---|---|---|
| Burning | 免疫Burning和Frost冻结，但不免疫Chill；Fireblast伤害减半 | 寒冷弩/Frost法杖仍可减速，不用火杖对它求点燃；站位优先避其火地。 |
| Soiled | 身边3×3每个高草格减伤1/6，6格时接近无伤 | 先用Fireblast烧掉它周围草、留英雄安全落脚格，再打；别盯着无伤硬砍。 |
| Rotting | 直接伤害转为0.6×hit的Bleeding，新的仅取较大值而不叠加；站水每次act回血6 | 必须拉离水，单次高伤比快速叠小伤更有价值；此战不要为了Aqua站位把拳留水中。 |
| Rusted | 所有伤害累计进Viscosity延期池；物理22–44；流血免疫 | Thorns不输出，弩伤看延期池累积而非立即血条；控制/走位等结算，留足Heals别硬吃重拳。 |
| Bright | 远程10–20魔法，贴近转普攻；跨半血时若未被直接杀，回到150并自传走，施15回合盲；死亡30回合盲 | 准备法杖/跳跃贴近与后续断视野期，变相两段追击；盲眼期间不凭旧截图走激光。 |
| Dark | 同类远程/半血自传；跨半血及死亡移除Light | 备用Torch尤其重要，仍需一动作后校验当前激光与拳位置；移除光本身不扣HP。 |

最后第三拳死后进入phase5，激光冷却上限2、召唤冷却上限3，且还剩王100HP。此段把Leap/Hourglass/Haste留作脱离交叉线的资源，先躲全部线再补伤；不要以为“最后拳死”能自动结算。四个Spawner已全杀会使普通小怪全是较弱Larva，显著降低这段走位压力。

## 终点与胜利

Yog击杀后按出口进入下一层取Amulet。Amulet首次拾取会进入AmuletScene并触发胜利徽章判定；用户目标是通关，不额外默认开启上行挑战。看实际结算选择结束冒险，保存胜利结果与最后存档证据。不要只看到Yog倒下就漏取护符。

## 源码索引

KingsCrown.java:64/90；ClassArmor.java:97/185/321；Talent.java:435；HeroicLeap.java:46/69；HallsLevel.java:63/89/122；Dungeon.java:527/545；DemonSpawner.java:41/80/136；RipperDemon.java:147/232；Succubus.java:88/121；Eye.java:91/143/171/290；Scorpio.java:86；Weapon.java:379；MagicalInfusion.java:126；Amulet.java:73/101。
