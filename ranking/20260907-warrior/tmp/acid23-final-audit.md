# D23 酸蝎西门与 L20 天赋复核

只读源码和当前存档；root操作UI。两只Eye已死。`c2080abc2f76`拾取途中H16,15 HP93、L18exp81；最后`4744821b5546` H16,22 HP94，正等新Succ828。酸蝎仍19,21睡。最新库存：Hourglass4+0.167、Heal9、Blindweed2、Flame2、Cleansing1、Blast1、Hammer3、Tomahawk1、Fireblast2/4、Frost2/2、Swiftthistle2、Flock1。Eye另掉Swiftthistle+Flock，不要把Blast误报两颗。

## 先清新魅魔

Succ828从出口房13,26附近向24,13游荡，可能走15,23→16,22→17,22酸房。先打开非酸门16,21，使它在15,23/16,22看见英雄改追，保持酸门17,20/17,22关闭；不要闭着16,21在上侧久等，放它先开酸门。

HF迎接普通近战。魅惑时可Frost/等一次；谨慎朝南Fireblast，尤其Hero16,20时满充4的2-charge宽锥可把17,22酸门烧开。root当前已到16,22HF，按实际相邻方向打即可，勿把旧火锥说明套当前方向。

## 西北门双投：几何与时序

Hero16,20→门17,20；酸蝎仍19,21时，投射线为17,20→18,21→19,21。开门会推进0.525T，立刻读酸蝎状态。冻结后Blindweed投实际怪格（只种，不立即触发），Flame同格（hard press被延期），W退16,20后解冻。两投1T+退0.525T约耗2充能。必须在每步确认artifact.buff尚在。

TimeFreeze的延期press是VFX优先级，先触发Blindweed，随后Fire才演化，植物不会先被火烧掉而漏盲。Blindness10T+Cripple10T，盲期间蝎只能看到邻格而Scorpio禁止邻接远射。它仍会以WANDERING移动，不能重复瞄旧19,21。

Flame初始只铺中心3×3的非solid格，原位19,21时x18..20/y20..22有火，17,20门不在初始范围，下一演化会被18,20的火传染，约5T后烧毁。**Hero退格16,20是EMPTY不可燃，门着火不会把火传给这个地格。**16,20→酸蝎19,21射线经过17,20门；门未烧毁前不能穿门投射。

门烧完约消耗5T盲，剩约5T，单轮未必杀110HP。Burning每tick1–8、持续8T且站火刷新；Blind临近结束时不要为了多一击站在酸射线上。Fire也会烧17,22的另一扇酸门，之后它不能再当关闭的遮挡物。

## 输出与可靠退格

保持≥2格避免物理接触反酸。优先保Burning的Tomahawk/Hammer/Blast，不急用寒冷Dart/Frost灭火。Hammer0为10–20伤，STR需求19当前正好满足，但酸蝎DR0–16，平均净伤约7，别指望三锤足够。

**Blast1可作这一战的大段输出**：即时炸弹、半径1，D23原伤27–81再减DR，保证范围11–81（实际随机），平均约46；不走相邻defenseProc反酸、不熄火。从16,20向距离≥2的实际酸蝎落点投，先确认半径不包括自己。Runestone会解冻，因此只在双投已经完成解冻之后用。爆炸先处理旧物品堆，再结算角色受伤，所以此次击杀才新掉落的经验药不会被本次爆炸毁掉；怪已死时则不要炸其掉落。

**酸蝎仍在原房内18..23×20..22时，Hero16,20的可靠单步退格是S16,21**：从原房向16,21的弹道被17,21实墙阻断。N16,19/W15,20常仍在酸射线上，不能当遮挡。

可在16,21掩体里先开第二轮Freeze，再N16,20→Blindweed→Flame→S16,21，总3.049T、2充能，避免在非盲敌前先暴露再准备。**这只在酸蝎仍在原房内、落火和退格仍安全时成立。**如果它游荡上17,20或17,22等门格，火圈可能包含英雄或退路，须按新坐标重算；不要套19,21的旧火圈。

若已中Ooze：D23每tick5，持续20T；普通Heal不解酸。进水最多仍先吃一次5再洗掉。Cleansing立即清Ooze/Cripple/Blindness等负面，并5T防新负面，还清满当前饥饿，保作实际中酸或不得不近身的保险，不必预喝浪费持续时间。

## 两个T3点都投以战养战

若先杀Succ：L18exp81+12=93；酸蝎14→L19exp12、HT110；再喝保证掉落的Exp，加当前100EXP→L20exp12、HT115。若没杀Succ，则酸蝎使L19exp0，再喝到L20exp0。每升一级给一个剩余T3点。

推荐 **LETHAL_DEFENSE1→3**（以战养战），最终HF3/Cleave2/LD3。战技击杀每只敌减纹章CD150而非50；Yog的小幼虫可用Slam收掉，重置一轮盾。当前Tier4甲+IronWill1，纹章每次盾12；当HP≤HT/2或此次伤害会降到半时自动触发，随后才用盾吸伤，魔法/酸等有效伤害也能触发（饥饿除外）。当前−150是预存，激活两次后仍需重新减CD，LD3比LD1更适于持续收幼虫。

其它选择：Strongman2当前STR19加2，D24 base20仍加2，平均物伤约+1；Cleave3只45→60T而HF已冻结连击衰减；Enhanced2只强化7连冲击与9连招架，现打法主要Slam4，且只剩2点不能拿到Enhanced3位移。LD3更符合本局以控位和Slam收尾保生存的Yog打法。

依据：Acidic.java attackProc/defenseProc/createLoot；Scorpio.java canAttack/getCloser；PotionOfLiquidFlame.java shatter；Fire.java evolve/burn；Blindweed.java activate；Ooze.java act；PotionOfCleansing.java cleanse；Bomb.java explode/explosionRange；MissileWeapon.java min/STRReq；ThrowingHammer.java max；Char.java 937起纹章触发；BrokenSeal.java maxShield/activate/reduceCooldown；Combo.java战技击杀减CD；Hero.java STR与talentPointsAvailable。
