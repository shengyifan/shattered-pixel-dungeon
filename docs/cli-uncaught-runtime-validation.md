# Actor 未捕获异常的会话关闭验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

这项回归验证后台 Actor 线程崩溃后，即使渲染循环正常返回，会话也必须记录为 `FAILED`，并在提交会话终态、关闭资源之后以退出码 `1` 返回。启动器用独立的 uncaught 标记判断这种情况；普通可恢复错误记录不会自动把整次会话标成失败，正常游戏输赢仍不属于进程崩溃。

测试专用 `UncaughtRuntimeAgent` 只对明确标注 `counts_as_win=false` 的隔离 fixture 工作。它在指定请求登记后，于原 `Actor.process` 调用 `acting.act()` 之前抛出一个固定的 `RuntimeException`。没有强杀进程，也没有改变生产源码；异常沿真正的默认 uncaught handler 退出路径处理。

第一次真实回归已经确认会话正确标为 `FAILED`，但当时进程仍返回 `0`。这会使只检查退出码的脚本把崩溃当成正常退出。后续补齐了退出码语义，并将测试收紧为必须返回 `1`；旧结果保留在配套 JSON 的 `previous_validation` 中。

最新实际结果见 cli-uncaught-runtime-validation.json（历史 JSON 已删除，可查 Git 历史）：

- 抛出位置确认是 `SHPD Actor Thread → Actor.process → acting.act` 调用点。
- GUI 循环返回后，启动器退出码为 `1`；两库的会话均为 `FAILED`，原因为 `uncaught_runtime_failure`。
- 原始异常类型、消息和 Actor 堆栈进入内部异常表，并关联正确的 session；公开响应与事件中没有原异常消息。
- 目标请求只有一个 exchange，stdout 恰有一个对应响应，没有额外主动消息。
- 两库 `integrity_check` 均通过。

所有菜单与游戏操作均由公开 NDJSON 完成。模板与目标目录都位于测试 fixture 根目录，未读取个人存档；注入后的测试不计作通关证据。

复跑命令：

```sh
./gradlew :desktop-control:crashAgentJar :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/uncaught_runtime_smoke.py
```

脚本会固定运行时副本，并把独立的 `test-only-uncaught-runtime.jar` 放在本轮 runtime 目录。它只从现有测试 agent artifact 读取固定版本 ASM，不修改 `EngineBoundaryAgent` 或 11 个强杀边界测试。
