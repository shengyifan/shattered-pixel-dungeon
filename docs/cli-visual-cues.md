# 可见视觉提示：第一组

第一组公开两种已经实际绘制的提示：`red_target` 红色目标标记，以及 `black_goo_droplets` 粘咕蓄力时的黑色粒子格子。它没有扫描 Boss AI 的目标、阶段或计时字段。

**本页记录第一组的两种表现，并保守省略迷雾外、视口边缘和被 UI 遮挡的格子。空集合不代表整张地图安全。** 后续增加的天狗烟雾、倒计时与通用尾粒子语义见 [天狗炸弹验证](cli-tengu-bomb-validation.md)。其他 Boss 预兆、图像提示和声音不由这一组自动代表。

## 公开协议

GameScene 的公开观察新增：

```json
{
  "visual_cues": {
    "status": "last_rendered",
    "depth": 5,
    "map_context": "opaque-context-id",
    "cues": [
      {"kind": "black_goo_droplets", "cell": 123}
    ]
  }
}
```

`map_context` 是当前地图对象的 opaque 身份，UI 重建仍使用同一地图时保持一致；加载另一个地图对象则重新分配。它不是内部对象地址、Java identityHash 或隐藏地图数据。

还没有匹配当前 run、地图与深度的完整绘制时，状态明确为 `not_rendered`，`map_context=null`、`cues=[]`。可操作的稳定终态会先等待这个世界完成真实绘制。

提示集合变化时，`events.read` 中有 `kind=game.visual`：

```json
{
  "format": "display_snapshot_v1",
  "depth": 5,
  "map_context": "opaque-context-id",
  "occurred_at": "observed-time",
  "cues": [{"kind": "red_target", "cell": 123}]
}
```

集合消失后保留空集合事件，先前显示过的提示仍在公开历史中。渲染淡出和粒子闪烁不会单独使操作意图变成 STALE。历史事件说明“曾经显示过什么”，不能被解释成当前仍存在的攻击目标。

## 数据从哪里来

- TargetedCell 保存其本来就用于绘图的格子和颜色。只有红色标记在自身 `super.draw()` 已绘制且存在实际绘图缓冲后，才向当前 GameScene 的 collector 提交。
- 粘咕沿用原来的发射器创建、粒子生成和 RNG 流程，仅在已经创建的发射器上安装可选的 afterDraw 观察器。观察器要求有真正存在、非零 alpha、可绘制的 GooParticle 子对象。后续通用化修正保留攻击后仍在画面上的尾粒子；原 factory/on 仅约束首次等待，不作为粒子已经消失的依据。
- collector 位于当前 GameScene。draw 开始清本帧候选，整个场景 draw 结束后统一检查当前 scene/run/level、附着关系、视口和 UI 遮挡，再提交不可变语义集合。
- 没有订阅视觉提示的 observer 时，普通游戏跳过 collector 和观察器分配。通用 Emitter 的观察器在 revive 时清除，重新绑定会重置首绘制记录，避免对象池复用错误归属。

生产投影不读取 `YogDzewa.targetedCells`、`Goo.pumpedUp`、爆炸计时或其他 AI 决策。它不调用 `GooSprite.updateEmitters()`、视野刷新、Ballistica 或强制粒子生成。测试夹具可以准备相应 Boss 初态，但这些准备不参与公开投影。

## 可见性与保守边界

提示的源格必须处于当前 FOV，整个格子必须落在实际地图相机视口内。当前场景中可见的 UI Component / UI Visual 的屏幕矩形作为保守遮挡；部分相交也不输出。存在可见 Window 或 RightClickMenu 时，第一版直接抑制整批地图提示。

这些条件会少报实际还能从边缘或半透明 UI 看见的一部分内容，但不会把被过滤的格子、数量、坐标或内部原因输出给调用方。过滤状态也不能使控制器等待隐藏对象。

## 首粒子与完成时序

引擎每帧实际顺序是 `draw → step → afterFrame`。只有“动作逻辑结束”还不保证新预兆已经被画出来，因此运行时等待首次稳定边界之后的匹配完整 draw，确认仍稳定后再返回终态。

真实测试还发现了第二层时序：粘咕新发射器的原生首次发射有短暂随机延迟，一次完整 draw 仍可能没有粒子。最初英语测试的操作终态返回 `last_rendered` 但 `cues=[]`，0.2 秒后的诊断查询才看见黑色粒子；该批次明确保留为失败，没有用后续查询补成通过。

修正后，只有已经通过全部可见性门槛、确实 active/on 且正在显示 Goo factory 的发射器，在首次实际可绘制粒子出现前，会通过内部 readiness 标志阻止提前结束。该标志不公开。每观察源只等待首次可见绘制；之后粒子闪烁不重新等待。隐藏、屏幕外、HUD/窗口遮挡、已经停止、自身或父组停用，以及原生更新路径被冻结的发射器不会拖延响应。等待期间只继续正常绘制/视觉更新，不额外推进 Actor，不生成或刷新任何效果。

`Emitter.canProgress()` 只读原有 `exists/active/isFrozen()` 更新门槛，冻结期间已经画出的粒子仍可进入公开结果。这个防御条件避免等待当前不能自然进展的源。注意场景重建会把 `Game.timeTotal` 清零，而原冻结谓词要求该时间超过一秒；不能因此声称普通界面重建已被证明会死锁。单测保留并覆盖这一秒原生例外，没有通过改动游戏时间构造实机结论。

## 验证

10 项定向单测通过：FOV 和地图范围、完整视口裁剪、HUD 重叠、非有限几何、只读输入、隐藏/脱离/旧 scene、零 alpha/无面积、afterDraw 顺序、对象池复用、首绘制 readiness，以及冻结/停用源无粒子不等待和原生冻结覆盖方法。

新的真实回归全部为**中文、窗口化**，使用独立 test fixture profile，`counts_as_win=false`：

| 场景 | 实际结果 |
|---|---|
| 真实 Yog 原攻击 | 同一个公开 wait 的最终响应已包含红标格子。没有再做游戏动作，标记按原生渲染时间淡出后当前集合为空，但历史仍保留已见提示和后续清空事件。 |
| 真实粘咕原蓄力 | 从合法第一蓄力初态开始，公开 wait 执行原下一阶段；最终响应包含新外圈黑粒子格子。随后打开实际菜单时集合被抑制，关闭菜单后恢复，原下一次攻击结束后清空。 |
| 隐藏无 drawable 源 | 人为测试用发射器在墙后、on/Goo factory、原生首次发射延迟 60 秒；公开 wait 约 0.328 秒完成，没有等待隐藏源，也没有发布其格子。此例只验证渲染边界，不冒充真实 Boss 战斗。 |
| 冻结与界面重建 | `TimeBubble` 和蓄力粘咕的测试初态，等待正常冻结生效后，通过中文设置中的“界面模式”重建场景，约 0.357 秒完成且时间气泡仍在；随后公开攻击动作在同一终态解除时间气泡。未改游戏时钟、未强制生成粒子。此例验证操作可完成，不声称复现旧版重建死锁。 |

逐例 profile、构建 ID、冻结运行时和旧失败证据见 [cli-visual-cues-validation.json](cli-visual-cues-validation.json)。主要中文批次是 `runtime-b33d2f8f4b80448a8778636772cad8f2`；粘咕模态遮挡/恢复补充是 `runtime-cc58c13125854c8a8d8e42d68d087dfc`，使用同一个生产构建。旧英语失败批次仍保留原语言事实，没有改写成中文测试。

冻结条件的防御修正另以 `runtime-a1ed2d5bf34f45019b8dcf55748bc2e8`（生产构建 `c53e01270bc8cc430f8add47a1e44b7bbf90393bc562ac869de08a6f6adcfb0c`）完成中文窗口化回归；前三例没有被改写成使用这一新构建执行的结果。

```sh
./gradlew :game-control:test --tests '*VisualCueProjectionTest' \
  --tests '*VisualCueAttachmentTest' --tests '*EmitterDrawObserverTest' \
  :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/visual_cue_smoke.py
```

真实用例由 `VisualCueFixtures` 准备独立初态，之后只用公开 CLI。任何“最终响应没有新警示、后续查询才有”的情况仍判失败。没有读取正式 P9 的内部数据，没有截图、键鼠模拟或 Computer Use，也不计作正式通关。
