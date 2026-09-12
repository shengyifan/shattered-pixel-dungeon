# P8 真实游戏进程精确强杀

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09 的完整批次 **11 / 11 通过**。每个场景启动实际 `SpdctlLauncher`、GUI、Actor 与生产 `MachineSession`，通过公开 NDJSON 操作游戏，在指定代码边界停住后由父进程发出 SIGKILL。重启使用未安装测试 agent 的生产入口，通过公开 `request.get` 检查结果和重复 ID。

这些场景使用隔离的测试存档，均标记 `test_fixture=true`、`counts_as_win=false`。没有从正式游玩 profile 读取数据，没有截图、键鼠模拟或 Computer Use。它们补充 [先前 P8 保存与账本测试](cli-p8-failure-validation.md) 中明确留下的真实游戏强杀缺口；并非把原来的 fake-effect 文件改名成游戏证据。

完整逐例报告见 cli-p8-engine-crash-validation.json（历史 JSON 已删除，可查 Git 历史），固定运行时为 `runtime-0ea261b4cf3345e0b0f417e0aedf1c07`。每例保留原请求、真实 barrier 调用栈、公开记录、两库和恢复结果。

## 精确暂停如何实现

独立 `crashAgent` 测试 source set 使用 ASM，在类加载时仅给 AuditStore、GameController 和 Dungeon 的指定方法边界插入暂停回调。ASM 版本固定为 9.10.1，版本来源为 [ASM 官方发布记录](https://asm.ow2.io/versions.html)。生产 Java 类、代理代码均编译为 Java 11 字节码。

agent 只有在路径位于 `desktop-control/build/fixtures/`、存在明确的非通关夹具声明，并且收到与 `barrier.armed` 中 ID 完全一致的目标请求后，才允许暂停。初始化和其他日志事务不会误触发目标请求的 barrier。暂停点把阶段、线程和调用栈写入并 force 到文件后等待；父进程确认该文件后才发 SIGKILL，不通过随机 sleep 猜执行位置。

正常代码仍负责请求校验、真实回调、Actor 与动画结算、保存、SQLite 事务和协议响应。测试没有替换游戏动作、伪造完成 Future 或直接改账本结果。每条恢复结果来自正常生产 `recoverInterrupted()`。

测试 agent 不进入 `desktop-control` 的普通 runtimeClasspath。实际 release JAR 已核查：不包含 EngineBoundaryAgent、ASM 类、FixtureLauncher 或低频/性能测试启动器。正式六职业游玩不加载 agent，也不访问这些 barrier 文件。

## 结果

| 暂停后强杀的位置 | SQLite 自身恢复后的请求 | 生产程序恢复后的状态 | 目标响应已收到 |
|---|---|---|---|
| begin 登记前 | 不存在 | 不存在 | 否 |
| begin 已提交返回前 | RECEIVED | NOT_EXECUTED | 否 |
| markExecuting 执行意图前 | RECEIVED | NOT_EXECUTED | 否 |
| markExecuting 提交后 | EXECUTING | UNKNOWN | 否 |
| 渲染线程 perform 游戏回调前 | EXECUTING | UNKNOWN | 否 |
| perform 原生回调返回后 | EXECUTING | UNKNOWN | 否 |
| 真实完成 Future 已结算，结果事务前 | EXECUTING | UNKNOWN | 否 |
| 完整结果已提交，stdout 输出前 | COMPLETED | COMPLETED | 否 |
| game.dat 保存后，saveLevel 前 | EXECUTING | UNKNOWN | 否 |
| 两库事务更新后、Connection.commit 前 | 不存在，登记事务已回滚 | 不存在 | 否 |
| 新局身份登记后、楼层线程 Dungeon.init 前 | EXECUTING，目标 scope 为 planned | UNKNOWN，planned 保留 | 否 |

每次强杀后先让 SQLite 恢复 hot rollback journals，逐一核对两库的原始请求行、response、快照引用、配对 generation 和完整性，再启动正常应用恢复。已登记的 ID 重发一律得到 DUPLICATE_REQUEST_ID；完成结果可以用新查询 ID 读取，执行状态不明的结果不会重放。

两个保存文件之间的场景实际观察到 `game1/game.dat` 字节改变、楼层文件字节完全不变。重启后的 UNKNOWN 如实保留这一事实，没有把保存文件补齐或报告整局保存成功。新局初始化前的场景确认重启后没有自动生成 game.dat，planned scope 也不会自动成为已创建的一局。

这里 `perform` 返回表示原生输入回调已经返回，不代表异步 Actor 或动画已经结算；`after-settlement-before-result` 另行验证正常 MachineSession 等到完成 Future 返回之后的边界。这两种时点分别记录，避免把“调用成功”当成“动作完成”。

## 与原计划八点对应

1. 请求登记前后：before-register / after-register。
2. 执行意图事务提交前后：before-intent / after-intent；先前账本测试另覆盖事务更新途中。
3. 游戏回调前后：before-callback / after-callback，调用栈位于真实渲染线程及 Actor handoff 内。
4. 动作结算后、结果事务前：after-settlement-before-result，调用栈来自实际 MachineSession。
5. 结果提交后、响应输出前：after-result-before-output；旧账本测试另有父管道已收到响应但输出标记未提交的对照。
6. 游戏文件与楼层文件保存之间：between-save-files，在实际 Dungeon.saveAll 中的两次保存调用之间。
7. 双库事务中途：inside-dual-transaction；先前四类账本事务 SIGKILL 仍保留其更细的 SQL 中段证据。
8. 新局身份登记与游戏创建之间：before-new-run-init，位于实际 Interlevel loader 调用 Dungeon.init 的入口。

```sh
./gradlew :desktop-control:crashAgentJar :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/engine_crash_smoke.py
```

本批覆盖上述代表性游戏与持久化临界点，不等同于所有能力、所有引擎分支均经历强杀，也不模拟物理断电、磁盘固件或损坏硬件。低频操作覆盖、性能、普通包运行及正式通关仍有各自独立的验收记录。
