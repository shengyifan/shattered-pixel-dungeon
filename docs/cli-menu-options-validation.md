# 开局选项的中间状态测试

两个独立profile构造既有进度：`menu:locked`未获得胜利，`menu:unlocked`具有此前已确认的胜利徽章。准备代码只存在于test source-set；该徽章不被算作正式通关，全部实际选项、文字输入、确认和取消使用原CLI控件。

中文GUI、窗口化、英文CLI的两组共7条路径通过：

- 未胜利profile：Custom Seed、Daily Run、Challenges分别显示原生需要先获胜的说明，不能越过前置条件；原返回回到Game Options。
- 已解锁profile：原种子输入拒绝超过20字符且不改变原值；通过原Set保存`ABC-DEF-GHI`，重新打开可见同值，通过原Clear清空。后置断言核对实际设置值。
- 原挑战复选框启用后关闭窗口，再打开保持启用；再次关闭恢复原0挑战设置。
- Randomize打开原配置窗口，原Cancel关闭，挑战设置保持0。
- Daily Run显示原确认问题，原No返回且未创建每日游戏。

最新通过目录为`menu-options-locked-dd4f5dd29e274511963dba2b0cb39945`及`menu-options-unlocked-4f801384dd1f45ae87a1dc34af0250e0`，完整构建与逐步证据见[精简JSON](cli-menu-options-validation.json)。测试进程均已退出。

首两轮测试准备曾分别误用大写Warrior标签、遗漏原Game Options入口；公开返回明确指出实际小写warrior和Game Options，修正测试后才得到本次通过。它们不是CLI生产缺陷，也没有被算入成功路径。

此组没有确认种子生成后的实际游戏、每日游戏真正创建或Randomize的Confirm分支，不能据此把全部开局配置标为通过。后续从这些确认之前的状态分别构造并测试。

## 原确认后的游戏创建补充

随后独立补充两项通过：

- `menu-options-unlocked-03bd88ad7ef34edf8b80a442ecce68fc`通过原Set设置`ABC-DEF-GHI`，再用原Start创建实际一级战士，取得新run scope；同版本后置断言确认`Dungeon.customSeedText`为该输入且非daily。正常保存并退出。
- `menu-options-daily-11d1a1e7c54743649c4fcf18eb556899`通过原Daily Run→Yes创建实际一级战士，取得新run scope；后置断言确认daily=true、dailyReplay=false及原日期种子标记，正常保存退出。没有改系统时钟或直接调用初始化作为操作替代。

两项均保持英文CLI、中文窗口化，新增证据保存在原JSON的`new_game_cases`，没有回写此前仅配置验证的结果。种子开局测试曾多加一次Back，按原游戏正确退到TitleScene，测试修正为直接使用当前已广告Start；这不是生产故障。Randomize确认和daily复玩/已有局拒绝仍待独立场景。

`menu-options-random-confirm-006f82002fa348949291efe61724e8fe`随后补充通过原Randomize确认：勾选随机角色与挑战、原滑条选择2、点击原Confirm，得到原只读挑战结果窗，恰好2项已选且不可编辑；后置断言确认原挑战位图有2位、原随机角色为已解锁HUNTRESS，randomizedClass=true，再原Back返回选角。只测试一次真实随机结果，不筛种子或重抽到特定角色；结果追加到`randomize_confirmation`。daily复玩/已有局拒绝仍未验证。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/menu_options_scenario_smoke.py
```
