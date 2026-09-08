# P8 保存与崩溃边界验证

本记录区分三类证据：真实游戏引擎通过公开 CLI 的保存/恢复测试；真实子进程执行生产 AuditStore 后被 SIGKILL 的账本测试；仅在同一测试进程内模拟行为的单元测试。它们证明的范围不同。

后续补充：下文“尚未证明”的真实引擎临界点现已另行执行 11 项精确 SIGKILL 并通过，见 [真实游戏进程强杀报告](cli-p8-engine-crash-validation.md)。本文保留原批次的证据层级，不将账本 fake-effect 测试追认成游戏回调测试。

2026-09-09 的结果为 **3 个真实引擎保存场景通过、12 个账本子进程 SIGKILL 场景通过**。测试创建的每个 profile 均位于 `desktop-control/build/fixtures/`，标记 `test_fixture=true`、`counts_as_win=false`。没有读取用户个人存档，没有游戏截图、键鼠模拟或 Computer Use。

逐例结果见 [cli-p8-failure-validation.json](cli-p8-failure-validation.json)。其中仅有测试结果、scope/状态判据及仓库相对路径；原始公开协议记录、内部诊断库、隔离存档均留在忽略的 build 目录。

## 真实游戏保存与旧档

`legacy_save_smoke.py` 先通过 test-only `FixtureLauncher` 的 `class:WARRIOR` 新建模板。此夹具仅设置测试 profile 的菜单偏好/职业解锁，游戏中的角色、库存与初始楼层由正常开局生成。之后复制自建模板，使用生产 `SpdctlLauncher` 进行恢复和保存；没有再次注入运行中的角色数据。

| 场景 | 故障准备 | 实际结果 |
|---|---|---|
| 老档无 run_uuid | 关闭测试游戏后解压自建 `game.dat` 的 gzip JSON，仅删除顶层 `run_uuid`；其余解析值完全相同。 | 首次公开受控加载分配合法新 UUID，并在进入可操作状态时已持久化。文件检查发生在显式 `game.save`、退出之前。再次重启保持相同 scope，之前的查询 ID 仍拒绝重复。 |
| 主文件写失败 | 在已加载的自建 profile 中创建空目录 `game.dat.spdtmp`，阻止临时文件写入。 | `game.save` 返回 `EXECUTION_UNKNOWN`，主文件和楼层文件字节均未变化。 |
| 第二文件写失败 | 在已加载的自建 profile 中创建空目录 `depth1.dat.spdtmp`。 | `game.save` 返回 `EXECUTION_UNKNOWN`；主 `game.dat` 已写入本次状态，`depth1.dat` 保留之前字节。两个游戏文件没有共同原子提交。 |

两种失败均确认以下行为：

- `request.get` 保留 `UNKNOWN`，没有伪造 after 快照，原请求 ID 永久占用。
- 公开 `events.read` 提供 `kind=save`、`data.success=false`；当前契约使用 success 布尔，不要求单独 `save.failed` 名称。对应时间段没有保存成功事件。
- 内部异常记录保留本次请求的 IOException、堆栈和测试失败路径；公开事件不含堆栈与异常消息。
- 同进程 `state.get` 返回 `execution_unknown`、空 actions；新 ID 尝试游戏操作返回 `EXECUTION_UNCERTAIN`。
- 测试只移除自己创建的空阻断目录，使用 EOF 退出；再次启动后 scope 不变、历史失败仍为 UNKNOWN、旧 ID 仍拒绝复用。新 ID 的显式保存成功，并记录 COMPLETED。

旧档场景使用固定运行时 `runtime-8876d99b67af462089819d68d6569525`；两种故障及重启验证使用 `runtime-51f966acdb9041d59d2774d008d43079`。每个运行时都先冻结项目 jars/classes/resources，两个审计数据库均核对 schema 3 与 `integrity_check=ok`。所有游戏命令、物品/目标选择都来自公开接口；文件内容与内部数据库仅用于准备规定的测试故障或事后断言。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/legacy_save_smoke.py
```

## 真实账本子进程强杀

新增 `AuditBoundaryChild` 和 `AuditBoundaryProcessTest` 均只存在于 test source-set。每例启动独立 Java 子进程，调用生产 AuditStore；子进程把到达指定边界的 marker 完整写入并刷盘后等待。父测试确认准确 marker，再使用 SIGKILL，检查退出码 137。不会用一个随机延时猜测进程停在哪一行。

事务内部的四个场景使用测试代码反射取得该 AuditStore 的 JDBC writer，在此连接注册 `fixture_barrier()`，并在内部库安装仅测试用 trigger。真实的登记、执行意图、结果或输出标记事务执行到内部库时等待，父进程再强杀。生产构造器、命令协议及游戏代码没有增加故障开关。

重启检查分成两个阶段：

1. 打开 SQLite，让 SQLite 自身恢复 hot rollback journals。随后删除测试 trigger，避免已经不存在的连接内函数影响正常恢复。此时先核对两个库的原始请求状态、快照/请求行数、相同 pair_generation、完整性，确认没有半份事务。
2. 再创建正常 AuditStore，调用 `recoverInterrupted()`。RECEIVED 转为 NOT_EXECUTED，EXECUTING 转为 UNKNOWN，已提交 COMPLETED 保持不变；再次恢复无变化。最后核对 ID 是否应已占用及 fake-effect 标记是否仍恰好一次。

| 子进程边界 | SQLite 回滚后原始状态 | 应用语义恢复后 | 已收到 stdout 响应 | 已记录输出标记 |
|---|---|---|---|---|
| before-register | 无请求 | 无请求 | 否 | 否 |
| inside-register | 无请求 | 无请求 | 否 | 否 |
| after-register | RECEIVED | NOT_EXECUTED | 否 | 否 |
| inside-intent | RECEIVED | NOT_EXECUTED | 否 | 否 |
| after-intent | EXECUTING | UNKNOWN | 否 | 否 |
| after-fake-effect | EXECUTING | UNKNOWN | 否 | 否 |
| inside-result | EXECUTING | UNKNOWN | 否 | 否 |
| after-result | COMPLETED | COMPLETED | 否 | 否 |
| after-output | COMPLETED | COMPLETED | 是 | 否 |
| inside-output-mark | COMPLETED | COMPLETED | 是 | 否 |
| after-output-mark | COMPLETED | COMPLETED | 是 | 是 |
| new-run-planned | EXECUTING，target scope 为 planned | UNKNOWN，scope 仍 planned | 否 | 否 |

stdout 场景由父进程的真实管道读线程先收到完整响应字节，再发出 SIGKILL。输出成功记录和调用方是否接收仍是不同事实：输出后标记前强杀时，父进程已收到响应，但账本保持 `output_attempted=false`、`output_succeeded=null`；不会补造一条“已送达”记录。结果事务提交后、输出前强杀时，历史中有完整结果，但 stdout 为空。

```sh
./gradlew :desktop-control:test --tests '*AuditBoundaryProcessTest'
```

这 12 个参数化测试均通过。各例保留 `test_fixture.json`、`barrier.json`、`captured-stdout.txt`、`ledger-boundary-result.json` 及两库。`fake-effect.txt` 只是替代副作用的文件标记，没有调用真实游戏 handler，也没有伪装成正常存档。

## 原计划八个故障点的对应范围

| 原计划点 | 已有证据 | 尚未证明的部分 |
|---|---|---|
| 1. 请求登记前后 | before/inside/after-register 的真实子进程 SIGKILL，确认提交前无 ID、提交后 ID 已占用。 | 尚未把强杀定位到真实游戏进程接收 NDJSON 的同一边界。 |
| 2. 执行意图事务提交前后 | inside-intent 的实际双库事务 SIGKILL，与 after-intent 对照。 | 真实游戏 coordinator 与 handler 之间同一时点的强杀仍需专项。 |
| 3. 游戏回调调用前后 | after-intent 与 after-fake-effect 核对 UNKNOWN 和不可重放。 | 这里是模拟副作用，不是游戏回调强杀证据。 |
| 4. 动作结算后、结果事务提交前 | fake-effect 后与 inside-result 证明账本保守恢复、不造 after 快照。 | 尚未在真实 Actor/动画结算回调完成后精确强杀。 |
| 5. 结果提交后、响应写出前 | after-result；另有真实 stdout 管道收到结果但标记尚未提交的对照。 | 子进程直接调用生产账本，不覆盖真实 MachineSession 该行的强杀。 |
| 6. 游戏文件与楼层文件保存之间 | 真实引擎阻断第二文件写入，已观察主文件更新、楼层文件保留以及错误传播。 | 这是 IOException 故障路径；尚未在两次文件写入之间 SIGKILL。 |
| 7. 双库事务执行期间 | 登记、意图、结果、输出标记四种生产 AuditStore 事务中途 SIGKILL；两库均回滚且 generation 相同。 | 没有模拟整个操作系统断电或底层存储硬件故障。 |
| 8. 新局身份登记与实际创建之间 | new-run-planned 子进程保存 linked target，恢复后 planned 不自动变成 run。 | 未强杀真实游戏的新局生成过程；旧存档 UUID 迁移/重启另已有真实引擎证据。 |

之前的 `AuditCrashChild` 两项 Runtime.halt 测试仍是有效的进程账本证据，但其中同样使用 fake-effect 文件。现有同进程异常/SQL rollback 单元测试也继续保留。以上任何一类都不能自动代表尚未实测的真实游戏强杀窗口已经通过，P8 的打包、断流、长局性能等其他门槛也不由本文替代。
