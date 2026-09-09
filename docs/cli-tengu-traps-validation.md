# 天狗第一阶段：实际绘制的陷阱图案

新增 `fading_trap_pattern` 视觉提示，仍使用既有 `{kind, cell}`、`visual_cues` 和 `game.visual` 事件结构。它表示当前真正画出的陷阱图案格，不表示所有隐藏陷阱的位置、危险是否仍生效、未来图案或剩余显示时间。`RuntimeObserver`、`GameController` 和 `MachineSession` 无需更改。

## 来源与边界

原 `PrisonBossLevel.FadingTraps.create()` 在游戏本来创建图像之后，为同一个 Tilemap 安装可选的中立 afterDraw 观察器。原游戏生成图案、选择纹理、设置透明度、延迟淡出和移除图层的代码保持原样。

Tilemap 在原 `drawQuadSet` 完成后提供已有四边形缓冲中的非零几何。读取使用绝对 `FloatBuffer.get(index)`，不改缓冲位置或标记，不调用 `needsRender`、不扫描 `data[]`，更不查询 `Dungeon.level.traps`。只有已有上传缓冲、有限的非零四边形才进入候选；零几何正是原渲染器用来省略空纹理格的方法。观察器在 revive 时清除，默认未订阅的游戏不分配观察器。

collector 要求当前 scene/run/level、附着关系、可见且非零透明度、地图相机和原尺寸无旋转的图层；已有局部四边形加实际图层位置后，必须精确对应一个完整地图格子。越界列不会折算到下一行。之后仍使用现有 FOV、完整格子视口和 HUD/窗口遮挡过滤，不输出被过滤的格子或数量。

淡出完成后没有活跃绘制对象，当前集合自然清除。曾经实际显示的图案留在公开事件历史，之后的原生跳跃会产生新的当前集合。collector 不从仍存在的隐藏陷阱模型重建已经淡出的图案。调用方必须区分“当前/曾经看到的图案”和“当前仍然危险”，空集合不代表安全。

## 实际验证

**本次隔离批次使用中文 GUI、中文 CLI 和窗口化显示。** 它完成于统一英文 CLI 投影之前；这是历史实测事实，不表示已满足后续英文 CLI 要求。没有截图、键鼠模拟或 Computer Use，也没有访问正式 profile。所有结果均为 `test_fixture=true`、`counts_as_win=false`。

可见场景通过原 SceneChangeCallback 准备深度 10 的 `PrisonBossLevel`、第一阶段天狗及已鉴定测试法杖。准备结束后，只使用公开库存、真实“释放”按钮和公开可见敌人格子。两次普通法杖目标回调触发原 `Tengu.damage → jump → placeTrapsInTenguCell`，测试不在测量过程中替换图案。

| 检查 | 实测结果 |
| --- | --- |
| 首次原生图案 | 第一次释放的最终响应包含 11 个实际图案格；没有依赖之后的 state 查询才算通过。 |
| 原生逐格查看 | 从公开提示选格，通过现有 examine 打开实际“毒镖陷阱”说明；没有在视觉 DTO 中自动导出隐藏陷阱类型。 |
| 窗口与视口 | 说明窗口、游戏菜单出现时当前视觉集合为空，关闭后实际图案恢复；公开 pan 移出视口时消失，移回后恢复。 |
| 原生淡出与历史 | 两个真实 wait 推进原显示延迟，再经过原 AlphaTweener 的正常渲染时间，当前图案为空；此前显示的图案和清空事件仍保留。 |
| 跨回合替换 | 第二次公开法杖操作触发天狗再次跳跃，同一最终响应出现 16 格新图案。部分旧格不再出现，map_context 保持相同。 |
| 隐藏图案 | 墙后准备原生 FadingTraps 图层，wait 约 0.335 秒完成；当前 cue 和事件历史都没有该隐藏图案。此例只证明可见性边界。 |

保守投影可能在不同实际 draw 中包含不同子集：本批第一次最终响应有 11 格，原说明窗口关闭后的实际 draw 有 15 格。测试要求新图案在原操作最终响应中出现，并验证遮挡解除后的实际内容恢复；不会为了重现早先子集而抹掉后来真正可见的格子，也不会用内部模型补齐最早集合。早期“关闭窗口后必须与第一次集合完全相等”的过严断言及其失败 profile 保留在机器记录中，没有改写成通过。

19 项视觉定向单测通过，其中新增四项针对上传几何与模型数据不一致时只取几何、无上传或零几何不导出、Tilemap 池复用清除，以及完整格子和越界列投影；其余为既有 FOV、附着、文字、粒子、冻结与尾粒子回归。

两例最终实机均在冻结运行时 `runtime-3b0830ec63244f5d8e5877fef2514fc9`、构建 `ea06531e9e6700bf1aa57023b15f3d51e9f1eb9127562fba41bd9c74a1f63022` 中完成。逐例 profile 和实际格子集合见 [cli-tengu-traps-validation.json](cli-tengu-traps-validation.json)。

```sh
./gradlew :game-control:test --tests '*TilemapDrawObserverTest' --tests '*FloatingTextCueTest' --tests '*EmitterDrawObserverTest' --tests '*VisualCue*Test' :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/tengu_traps_smoke.py
```

本组没有新增电塔方向、准星关系或其他视觉类型，也不把第一阶段夹具记为正常天狗战斗通关。
