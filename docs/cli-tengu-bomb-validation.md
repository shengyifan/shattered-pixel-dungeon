# 天狗炸弹：实际绘制提示

这一小组在既有 `visual_cues` / `game.visual` 管线上增加四个 `kind`，不改 `RuntimeObserver`、`GameController` 或 `MachineSession` 协议。所有格子都经过当前 scene/run/level、附着关系、FOV、完整格子视口和 UI 遮挡检查。

| kind | 只表示什么 | 来源 |
| --- | --- | --- |
| `bomb_smoke` | 这个格子中，原炸弹烟雾发射器有实际绘出的烟雾粒子 | 原 `Tengu.BombAbility.fx` 创建的 CellEmitter 和实际 SmokeParticle 子对象 |
| `bomb_countdown_3` | 这个原始格子上方已经完整绘出字面量 `3...` | 带格子标注的原 FloatingText |
| `bomb_countdown_2` | 已经完整绘出 `2...` | 同上 |
| `bomb_countdown_1` | 已经完整绘出 `1...` | 同上 |

数字不是从 `BombAbility.timer` 读取的剩余回合数。`3...` 消失后，即使游戏逻辑还在同一回合，公开当前集合也不补回 `3`；只有之前确实显示过的内容留在事件历史。其他文字，包括不同的省略号、额外空格、`4...` 或未知语言文案，都不会猜测成数字。现有 UI 字符串不被改写。

`FloatingText.showOnCell` 仍使用原显示位置、颜色、文字和叠放 key，只增加原始格子标签。自身 draw 完成后读取已布局、具有字体、非零透明度的实际文字；文字本身必须完整落在相机内，且其屏幕矩形不能与 HUD 重叠。标签在 revive 时清除，普通伤害浮字不会继承池中旧炸弹的格子关系。此处没有读取寿命或倒计时。

烟雾使用通用 `CellParticleCue`，沿用粘咕的首绘制等待条件。只要有实际匹配的粒子子对象绘出，就可以记录尾粒子；`on` 和 factory 仅约束首次等待，不冒充画面存活标志。爆炸或粘咕攻击完成后仍可看见的旧烟雾/黑粒子因此会继续出现，直到自然消退。调用方不能据此推断逻辑攻击仍未发生。停用、冻结、FOV 外或被遮挡的源不会拖延响应。

这里不观察 `bombPos` 的私有逻辑快照、`timer`、未来爆炸伤害或 AI 目标来补全公开集合。Tengu 原视效创建方法本来就使用格子和文字，这两个渲染输入被标注；collector 只在实际 draw 后使用标注，并不会重新执行范围计算。空集合或某个数字消失都不表示安全。

## 验证

15 项定向单测通过：原 FOV/视口/HUD/附着/冻结/池复用边界，加上精确字面量白名单、现有字体与非零 alpha、裁剪文字拒绝、查询不改文字/位置/透明度、浮字池标签清除、文字矩形遮挡，以及停止后的实际尾粒子类型匹配。

全部实机测试使用新建隔离 profile、中文 UI、窗口化显示，通过公开 NDJSON 操作，没有截图、键鼠模拟或 Computer Use，也不读取正式游戏 profile。它们都是 `test_fixture=true`、`counts_as_win=false`。

| 实机用例 | 结果 |
| --- | --- |
| 原生天狗投弹 | 第一次 wait 最终响应包含 20 个已画烟雾格及原格子的 `3...`；随后原 wait 的最终响应依次包含 `2...`、`1...`，并与 UI 原字面量一致。 |
| 消退和历史 | `3...` 自然淡出后不从逻辑计时补回；全部已显示数字保留在公开历史。爆炸终态仍存在 20 个真实尾烟格，随后只经过渲染时间便自然消失。 |
| 遮挡与视口 | 真实菜单打开时当前集合为空；关闭后烟雾恢复。公开 view.pan 移出视口时没有炸弹 cue，移回后恢复。 |
| 隐藏原生 BombAbility 夹具 | 在墙后准备原生炸弹 buff，真实 wait 约 0.318 秒完成；烟雾和数字均未进入当前 cue 或事件历史，也没有因隐藏首粒子而等待。此例是可见性对抗夹具，不是一次合法玩家投弹的证明。 |
| 粘咕通用观察器回归 | 真实扩圈警告仍在原 wait 最终响应中，菜单遮挡/恢复正常。攻击结束后保留实际黑色尾粒子，随后正常渲染自然清除。 |

炸弹两例使用 `runtime-d0c0dfa701a34937b2342ad1611cd65b`，生产构建 `cc0aea3ba61e9ac8a4d4870ea83601d6f8856ffddbbf7c5dbdb2ae0e4f7bacd0`。粘咕回归使用 `runtime-130a2c9d50344730917e3a80d422b46e`，构建 `2a10e28123af207570a7e2f4bad19ed7193ca38d593f0936d50c9a5d9d7eadb0`。逐例公开 trace、profile 与机器结果记录在 [cli-tengu-bomb-validation.json](cli-tengu-bomb-validation.json)。旧粘咕测试的“攻击后立即清空”断言失败证据保留，没有改写成通过；验收已按真实尾粒子语义调整后重新实测。

**历史覆盖范围更正：** 上表的隐藏、视口和遮挡断言只检查 `observation.visual_cues` 及 `game.visual` 事件，不能证明整个 `ui.controls` 都经过相同过滤。后续只读复核发现，旧隐藏夹具的公开 trace 在请求 `2bbc24fce5bc-15`、`-17`、`-18`、`-19` 中仍含 `ui-128` 的完整 `text="3..."`，同时 cue 为空。原始报告保留；这里不再把旧断言概括为整个 CLI 没有数字。原引擎的浮字层位于 fog 之上，单凭 FOV 外也不能断言屏幕完全没画。通用 UI 浮字的未绘制、视口裁剪及后续 HUD 遮挡边界，需要独立的实际绘制缓存与公开 pan 夹具验收。

```sh
./gradlew :game-control:test --tests '*FloatingTextCueTest' --tests '*EmitterDrawObserverTest' --tests '*VisualCue*Test' :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/visual_cue_smoke.py --cases bomb,bomb-hidden,goo
```

本组不包括天狗第一阶段 `FadingTraps` 或其他 Boss 的全部图像、音效与状态表现，后续以独立夹具补齐。
