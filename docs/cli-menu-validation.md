# 主菜单场景基线

本组从隔离新profile的原WelcomeScene进入选角，再通过原返回进入TitleScene。使用原按钮进入并返回AboutScene、ChangesScene、JournalScene、RankingsScene、SupporterScene、NewsScene，六条路线通过。同一菜单scope保持不变，场景文字与动作可由公开CLI读取。

JournalScene已实际切换徽章、图鉴、炼金指南三个页签；地牢指南与更深详情窗口仍待补测。NewsScene只验证禁用新闻的状态，未请求在线文章。About和SupporterScene外部链接未点击，未进行赞助或任何账户操作。ChangesScene详情条目未逐项验证。

独立测试源`UiSceneAssertions`按公开状态版本记录实际Scene/Window类、中文语言及窗口化状态。这些记录只用于操作之后的断言，不能为客户端选择动作提供隐藏信息。最新profile为`desktop-control/build/fixtures/menu-scenes-d6a2a70396224dbd80ec09e5fb0cc0d1`，19条记录实际包含9种场景类；所列全部断言均为CHI_SMPL且fullscreen=false。测试完成后正常退出。

本报告保留的是**中文GUI、旧中文CLI响应**的基线，英文协议文本分离后的客户端必须更新并重新执行，不能回写旧结果宣称已通过新约定。逐例构建与结果见[JSON记录](cli-menu-validation.json)。新源夹具设置明确的“CLI 场景测试”原生窗口标题，帮助区分测试窗口与正式游戏。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/menu_scenario_smoke.py
```

这份报告证明的是六条原导航路线，不证明每个目标场景内部的全部交互。逐项待测记录见[场景覆盖清单](cli-scenario-coverage.json)。
