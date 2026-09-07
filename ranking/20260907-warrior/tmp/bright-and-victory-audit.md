# 明拳半血与护符结算：当前只读审计

当前仍在铁拳战：`bc686c64c2d7` H22,18水 HP95，铁拳21,17 HP292、延期池37，Yog400 phase3。以下明拳位置只能在实际传送后确定，不能预定随机目的格；不操作UI、不写游戏数据。

## 明拳半血前先准备，传走后按新位置行动

第一半明拳约170–190HP、Hero已贴住且当前不站Yog旧光线时，可先喝MindVision20T，再用普通攻击跨150。与等传送后才准备相比，这能立即看见其新位置并减少被远射的空档。不要刚高于150用Fury10：第一段触发置150+传走后，其余段会因不能近战而中止。

半血判定是`beforeHP>150 && HP<=150 && isAlive()`，回置HP150，再随机找当前heroFOV之外、非solid、无人、与出口可连通的格，并变WANDERING。它可能传回Yog四格保护圈；也可能位于墙另一边，**存档可见不等于Leap路径可通**。

传送后优先流程：

1. 新存档读取明拳pos、distance_yog、Yog全部固定旧线和HeroHP；不要向旧敌格点攻击或默认退某个固定格。
2. 若MindVision已开、新拳距Yog≥5、存在可达且不在Yog线上的空邻接落点，**直接一次HeroicLeap贴近**。单次Leap位置先生效再计1T，不要求Yog.delta≥1，不必无理由先Freeze。
3. 若未开MindVision、需要Heal清盲/补血或需多步走到能Leap的角度，可Freeze→MindVision→必要Heal→一次已核Leap。两瓶药共2T，只消耗一充能的冻结窗口；Leap在落地后解冻，再恢复世界，保证准备期间不先吃远射。若药喝完要普通走很多步，按剩余沙漏预算核，不把它当无期限冻结。
4. 若新拳距Yog≤4，不输出，利用对应柱墙遮其普通MAGIC_BOLT诱它离圈。当前东侧可回21,17柱后，让它绕至x21；x21本身距Yog5。若已在远侧安全区，不必为追固定东站位多走全图，按新图找同样的“圈外+遮射线”位置。
5. 每个动作仍读实际激光与新拳位置。可以一次直接换位躲Yog旧线，但明拳没有蓄光预警、会按新位置普通远射；避免在开阔地一路顶射追。贴近后它改物理，当前甲/HF/Evasion保护显著。

MindVision不会清Blind，但Level会把每只怪及周围3×3 OR进heroFOV，所以失明时也可揭示怪和可选落点。它不保证中间所有走廊都亮，不代替读地形。Torch不清Blind；本局是Bright，不会在半血/死亡移除Light，火把只用于普通照明到期。

## 最终30回合失明

明拳死亡给Blind30，紧接Yog最后100HP、3束光、2–3幼虫爆发。剩余Healing/Cleansing都能立即清Blind；有HP缺口优先Heal，满血也可用Cleansing保持操作视野。若已站预警线上，先一次真正脱线，再喝；或Freeze→喝→移位/取消。

不要因保存资源而让最后阶段长期看不清。MindVision若还有剩余时间可以帮助看Yog/幼虫，但不能把将过期的20T buff当全程照明。必要时提前刷新Light后再交最后一击，时机仍看当轮旧线。

## 助手新增明拳几何字段

`neighbors[].bright_ranged_geometry`按明拳当前位置、假设Hero移动到该邻格、其它怪占用、solid与MAGIC_BOLT路线，输出它是否具有普通远射直线；距离≤1则不视为远射，因为Bright邻接改物理。`fists[].ranged_ray_to_hero_clear`提供当前射线事实。

这不是FOV/AI状态，也不预测它之后移动；特别是较长动作里它可能先走一步再射，仍需下一次读回。原6个键保持不变。`a4eef0ba9f12`实铁拳存档验证结构正常且当前射线字段可用，真正明拳出现后再核对应输出。

## Yog死后：必须拿护符并点击实际完成按钮

Yog死只会清普通召唤并解封D25出口，还没登记胜利。走当前出口(16,9)进D26，等过层动画完成再存档。

D26 LastLevel固定16×64，护符位置 **(8,12)**，入口约(8,54)，中间x7–9为南北走廊，且createMobs为空、没有respawner。当前D26仍未生成，进入后读实际入口/地图确认，再沿中线北上，勿把装饰边缘当可走格。

首次拾Amulet成功时：Statistics.amuletObtained=true，VFX actor自动转AmuletScene，显示胜利徽章并saveAll；**这一步还没有Dungeon.win**。画面上两个按钮实际文本：

- 上方 **“现在就到此为止吧”**：调用Dungeon.win(Amulet.class)，Statistics.gameWon=true、Rankings.submit(win=true)，然后正常deleteGame当前slot并转排行榜。
- 下方 **“这一切还不该结束”**：回到游戏。AmuletScene的Escape也走这个返回逻辑，不用于关闭结算。

用户目标是正常通关，直接选上方按钮。若误回游戏，背包护符有 **“结束游戏”** 动作可重开该场景，前提尚未携护符向上触发AscensionChallenge。无需重走护符路线，也不默认做登临。

点击完成后game1被游戏删除是正常收档，不是失败。最终证据应换为排行榜：`rankings.dat`中`records[latest]`的`win=true`、`class=WARRIOR`、`cause=...Amulet`，以及`gameData.stats.won=true`、`amuletObtained=true`、`spawnersAlive=0`、`ankhsUsed`、`ascended=false`。其中score/level也可直接用于最终报告。

排行榜会裁剪不在快捷栏的背包项，因此若要报告完整剩余物资，应在点击上方完成之前读一次D26护符存档；胜利后仅根据排行榜能保留的装备/快捷物品与stats核验，不把裁剪当丢失。若出现首次“获胜！”窗口，可点击 **“关闭”** 展示排行榜；该窗口Escape不生效。

源码：YogFist.BrightFist.damage/zap；Level.updateFieldOfView；HeroicLeap.activate；Item.cast/ScrollOfTeleportation；LastLevel.build/createItems/createMobs；Amulet.doPickUp/showAmuletScene；AmuletScene按钮和onBackPressed；Dungeon.win；Rankings.submit/saveGameData/Record；Statistics.storeInBundle；中文scenes/windows/items资源。
