# 本局铁拳：延期池、Combo与移动时序修正

首草拳已死，`718ae2090676` Hero17,16 HP115/115，Yog700 phase2。当前L20、STR20、Crossbow4、WarriorArmor6、HF3、Evasion3/Haste3，Heal9、HG4、Leap100、Aqua59。随后`dfacabae15ab`本体677，无拳；以下给400出铁拳后的规则，不假设已生成的拳坐标。

## 先纠正先前错误的移动时间推论

root实测多次在Yog.delta=.032/.507、步耗.5246时单步成功，源码完全支持：Hero.getCloser先sprite.move、move(step)，Char.move立即pos=step并occupyCell，然后才spend(delay/speed)，恢复Actor队列。因此**单次合法移动到完整固定射线之外，不要求步耗小于Yog.delta**。

Leap回调同样先move再spend1T；Blink即使空格投掷先记spend，也在user.next恢复Actor前完成teleportToLocation。因此单次Leap/Blink到已核线外也不因delta<1而自动失败。墙/占用/Vertigo/Roots造成的落点失败、落点火毒/拳击、新一轮预警另算，不是无敌。

如果需两步且第一格仍在线，真正条件是**第一步耗时 < Yog.delta**，第二次位置变化在第二步自身耗时之前发生。不是比较两步总时长。残废时一步1.049T，若当前delta只有.5且第一格仍在线，就必须用别的直接落点或Freeze；但若第一格已脱线，一步照样成功，不能因此浪费冻结。

`yog-final-plan.md`、`yog-d25-positions.md`和`yog-lines.py`的相应说明已修正。

## 真实延期结算

RustedFist正常受伤时，把抗性处理后的伤害加到Viscosity.DeferedDamage.damage；不立刻扣HP。全新的池第一tick延期1T。每次buff独立行动：

`d = max(1, floor(pool*0.1)); HP -= d; pool -= d`

没有随机范围；每tick按**剩余池**重新算，所以会逐渐变小。Chill减慢拳的动作，不减慢此独立buff的tick，这正适合寒冷武器。铁拳INORGANIC免流血，Thorns不会提供流血输出；它不会像腐拳站水自疗。

若拳回到距Yog≤4，延期tick会被无敌挡住，但池仍减小，因此不要一边向内圈退一边指望它自动流完。池≥HP只代表保持可受伤且时间足够时最终会死，接近相等时尾段可能较久；看助手下一5tick与预计死亡tick再决定是否继续加伤。

## Combo比较（本套装、无额外buff，数值是期望入池量）

武器8–36，加STR20超过需求14的0–6，平均25；HF时英雄DR6–32+3–6，平均23.5；拳DR0–15。

|动作|期望入池|
|---|---:|
|Slam4|36.3|
|Slam8|55.1|
|Slam10|64.5|
|Crush8主目标|42.5|
|Fury10总10段|76.2|

三种战技均无限命中。Fury每段0.6武伤、各自扣一次拳DR，整段只花一次攻击时间；铁拳不立即掉血，通常不会中途死亡中止，所以已经10连且池还不够时Fury优于Slam10。没有10连时正常Slam4稳健，不必长时间憋点；Crush8单体不如Slam8，只有需要清附近幼虫时考虑AOE。上述均为期望，不保证每次实投数值；移动后无HF时Slam加成略低。

## 东水区站位与残废

400出拳后由本体东侧走18,14→19,14→20,14→21,15→21,16→21,17→**21,18水格**。柱墙19–20×15–16迫使拳向东绕；它到x21已经出Yog四格保护。让它21,17邻接英雄21,18，HF抗物理，Aqua逐tick回复（先确认Levitation已结束）。

躲光优先当轮不在线且空的东/南池格，例如22,18/22,19/21,19/20,19，以便拳继续保持x≥21或y≥17。不要向19,16/20,16带回圈；若拳20,17，也别从东南用Clobber把它推回19,16。

Rusted远招没有hit判定，直接Cripple4T，近战22–44。残废会增加后续动作耗时和可遭拳攻击数，**不会阻止一次即时换位躲固定旧光**。正常可承受它而保留Haste药给明拳/终盘；已有HP损失时Heal兼清残废。如果所有安全落点需要穿过仍在线的中间格，或被小怪堵路，再Freeze/已核单次Leap/Blink。不要满血仅因为步速变慢就交Cleansing。

## 助手新字段

`fists[]`新增`pending_deferred_damage`、`deferred_next_tick_damage`、`deferred_next_tick_delta`、`deferred_next_5_ticks`、`deferred_ticks_to_death_if_no_new_damage`及保护圈标志。预测条件是没有新伤害/回血且拳位置不变；若移动入保护圈，预测将失效。原snapshot/hero/yog/targeted_xy/neighbors/fists键全部保留，当前无拳存档已验证结构不变，真实延期池字段待铁拳实际受伤后核回。

依据：Hero.getCloser、Char.move、HeroicLeap.activate、Item.cast/StoneOfBlink/ScrollOfTeleportation.teleportToLocation；YogFist.RustedFist.damage/zap；Viscosity.DeferedDamage.act/extend；Combo.doAttack；MeleeWeapon/Weapon/Crossbow、Armor与HoldFast。
