# 待复活存档在新 JVM 中继续游戏

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09，已通过隔离的中文、窗口化实机测试复现并修复。测试只构造初始条件，不计为正式通关；未读取或改写正式游戏存档，未使用截图、键盘、鼠标或 computer use。

## 实际故障

未祝福重生十字架的复活选择尚未完成时，游戏允许原生暂停保存：`Dungeon.saveAll()` 的条件包括 `WndResurrect.instance != null`。新 JVM 继续该存档后，`GameScene.create()` 会重新显示复活窗口，但死亡英雄不会启动 Actor 线程。

此前 CLI 仅在 Actor 线程交接点执行稳定观察，因此窗口已经实际显示，Continue 请求仍无法结算。修复前冻结运行时的实测为：

- 公开 `wait` 触发原生 Poison 伤害，英雄 HP 变为 0，出现中文“保留这些物品”按钮。
- 在真实渲染帧结束后调用原 `ApplicationListener.pause()`，经 `GameScene.onPause()` 保存，再调用原 `Application.exit()` 正常退出。
- 退出后的原始游戏文件确实包含 HP=0、一个未祝福 Ankh 和原 run UUID；公开数据库记录了原生成功保存回执。
- 新 JVM 使用正式 `SpdctlLauncher`，经公开菜单选择原存档。
- 真实渲染断言已经存在 `WndResurrect`，但 `actor_thread=null`、`actor_yielded=false`、`actor_processing=false`。Continue 请求在 30.483 秒后首次返回 `in_progress`；用新 ID 查询原请求，状态仍为 `EXECUTING`。

这次失败证据保留在 `desktop-control/build/fixtures/resurrection-resume-f4b803b1199d4a25aad5b33f125d2b8e`，冻结运行时为 `runtime-e4aabc591da24fc8b60dc0b27928344b`，公开 build ID 为 `1743ebb8868b895c891c69cc13b6051b65fc686af523ef513735afa2dc2e3af5`。它是修复前冻结的开发运行时，不作为某个已发布 app 包的验证结论。

## 最小修复及边界

`GameScene` 增加纯读的 `actorThreadNotStarted()`：仅当 Actor 线程对象从未创建、且 `Actor.processing()` 为 false 时成立。`atActorHandoff()` 在这个前置状态允许渲染线程执行短任务；已经创建的线程仍必须走其实际等待监视器。已终止的线程不被当作这个例外，以免把异常执行后的状态误判为安全。

`GameController.stable()` 进一步要求纯读判定英雄确已死亡，才接受这个“线程尚未创建”的边界。活着的英雄，包括刚进入游戏的英雄，继续等待原生 Actor 调度。既有的场景切换、待执行回调、移动动画、稳定后实际绘制确认条件均保留。

修复不伪造 Actor 的 yielded 标志，不推进回合，不修改随机数，不重新调用死亡或复活逻辑，也不自动确认或取消复活选择。仍需调用方通过公开 UI 操作选择和确认。

## 修复后验证

修复后重新创建隔离初态，再完整执行原生死亡、原生暂停保存和新 JVM Continue：

- Continue 直接取得 `awaiting_input` 的复活窗口，公开 HP=0，scope 与原存档一致。
- 原线程仍未创建，未把测试成功建立在后台执行了隐藏回合上。
- 连续稳定查询保持相同 state_version。
- `app.quit`、`game.save`、`wait` 仍不在允许操作列表中，主动请求均被拒绝为 `ACTION_UNAVAILABLE`。
- 原 `ui.back` 不绕过强制复活选择。
- 能从复活窗口打开短剑选择，随后通过原返回操作取消选择。
- 点击原“保留这些物品”后，在同一 scope 中通过原生复活流程恢复 HP，并消耗 Ankh。
- 复活后的正常保存得到本请求的成功保存回执，随后正常退出。

通过证据位于 `desktop-control/build/fixtures/resurrection-resume-216942ffc188497ebc15852e4f8e031e`，冻结运行时 `runtime-83972fbe6b3c4590baeb4673e1d5bfd1`，公开 build ID `2a10e28123af207570a7e2f4bad19ed7193ca38d593f0936d50c9a5d9d7eadb0`。可提交的精简前后结果见 cli-resurrection-resume-validation.json（历史 JSON 已删除，可查 Git 历史）。

新增 5 项 `UnstartedActorBoundaryTest` 分别验证：死亡且线程从未创建时可安全读取、活英雄仍需原调度器、存在 current actor 时拒绝、线程对象已创建但尚未启动时拒绝、线程已终止时拒绝。既有绘制确认、退出限制、连续行动取消、请求审计与保存回执测试一并回归。

## 复跑方式与测试限制

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar
python3 desktop-control/src/test/python/resurrection_resume_smoke.py --expect ready
```

`--expect blocked` 仅用于保留的修复前实现，当前修复后的源码不应满足该预期。

测试 agent 仅加入测试源集，不进入正式 launcher 或 app 包。它在 `Game.render()` 返回前触发原有生命周期方法，模拟平台暂停和退出回调；此项不是物理窗口最小化或焦点丢失事件的端到端测试。另一段只读断言记录实际显示窗口与线程状态。存档没有经过测试代码编辑、覆盖或回滚；读取原始保存内容只用来核对保存结果，未用于选择游戏操作。

首次试跑曾遇到测试 agent 对 metadata 空白格式检查过严、以及中文存档按钮匹配缺项；修复测试胶水后才得到上述生产失败证据。这两次测试准备失败未作为生产故障或修复成功记录。
