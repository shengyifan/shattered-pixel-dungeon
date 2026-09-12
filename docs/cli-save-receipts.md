# 保存回执与动作完成

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

动作响应的 `status` 表示该动作的内存执行状态。`result.persistence` 单独说明原生保存流程是否在该请求执行期间发出了确认。两者必须分别判断；没有保存回执时，CLI 不确认保存。

成功或进行中的 `action.execute` 响应在 `result` 中包含：

```json
{
  "persistence": {
    "last_save": {
      "receipt_id": "opaque-save-receipt",
      "scope_id": "run:opaque-run-id",
      "slot": 1,
      "success": true,
      "occurred_at": "2026-09-09T00:00:00Z",
      "origin_scope_id": "run:opaque-run-id",
      "origin_request_id": "caller-save-id"
    },
    "saves_during_request": []
  }
}
```

- `last_save` 是当前观察作用域中最近一次已记录的原生保存回调，可能来自更早的操作；不存在时为 `null`。它也可能是 `success=false` 的失败尝试。
- `saves_during_request` 只包含本进程 session 中、回调发生时关联到这个原始作用域和请求 ID 的回执，按记录顺序排列。没有回调时为空数组；一次操作可以有多个回执。
- `receipt_id` 在原生回调发生时独立生成，`occurred_at` 是该回调的时间。原始异常对象和消息仅进入内部审计，公开回执没有异常消息。
- 开局动作可能从 `menu:` 切到 `run:`：回执的 `scope_id` 指向实际保存的 run，`origin_scope_id` 和 `origin_request_id` 仍指向发起开局的 menu 请求。
- EOF 等系统生命周期保存没有伪造的调用方请求 ID，其 `origin_scope_id` 和 `origin_request_id` 为 `null`。

例子中的空数组表示本次操作没有收到保存确认，尽管 `last_save` 可以显示以前保存成功。不能只检查 `last_save.success` 就断言当前动作已经保存。

`state.get` 和 `actions.list` 在 `result.last_save` 返回当前作用域最近的回执。它们从公开审计库附加这项元数据，不改变游戏观察内容，也不会仅因为查询或审计记录而推进 `state_version`。

## 持久化与历史

审计 schema 4 将 `sessions`、`runs`、`run_slots`、`save_checkpoints` 与原请求/交换记录放入同一双库事务体系。`requests` 保留原 session；重复到达的 `exchanges` 使用新到达所属 session，不改写原请求归属。session 的正常结束、已知失败及下次启动观察到的中断分别记录；中断只记录 recovered_at，不捏造崩溃发生时刻。

完整帧的 `received_at`、协议处理开始的 `processing_started_at`、执行意图的 `started_at`、观察到结算的 `settled_at`、准备响应和输出尝试均分别保留。无法知道的旧历史字段保持 null。旧 schema 1/2/3 升级为 4 不重建旧回执，不将旧的粗粒度 save 事件追认为具备请求/session 归属的新回执。

MachineSession 在持久记录响应之前先清空原生保存回调队列，将回执写入 public/internal 两库。只有这一步成功后，回执才会出现在 `complete`、`respondPending` 或 `settle` 的结果中。回执写入失败会关闭本次会话的正常处理路径，不能先宣称保存确认再尝试写审计。

已经实际进入执行、随后失败的操作可以返回 `result.persistence`，用于说明失败之前或期间确实观察到的保存回调；这不会附带一个猜测的错误时点游戏状态。重复 ID、参数错误、以及证明尚未执行的过期状态拒绝仍然仅返回错误，不重放旧回执，也不附加最新世界状态。

持续动作的首次 `in_progress` 响应保存当时已经观察到的回执。随后出现的回执只进入该原请求的最终逻辑结果；首次交换记录和已发送的响应不变。查询最终结果仍须使用一个新的请求 ID 调用 `request.get`。以前的查询响应、已经完成的动作响应以及其它历史内容不会用后来的保存结果补写。

保存确认只对应原保存流程实际保存的文件及其成功/失败回调。它不证明全部内存、当前窗口、动画或任意 UI 状态都能从文件重建。数据库中的 `following_snapshot` 只是保存之后取得的稳定观察，不能当作保存瞬间的完整内存快照。

## EOF

公开动作列表只在现有退出前置条件允许时提供 `app.quit`。在 GameScene 中有窗口、选物或选目标交互时，这个动作不出现在 allowed actions；手工提交也会被同一项只读检查拒绝。CLI 不会为退出自动确认或取消这些交互。

EOF 是系统生命周期事件，不产生伪造请求或主动 stdout 消息。正常退出如果被 `NotExecuted(STALE_STATE)` 拒绝，会重新观察，再尝试原生 `app.quit`，最多五次。它不会确认或取消一个游戏提示，也不会在已经执行但结果不确定时重试保存/退出。

因此，退出进程本身不等于保存成功。可以在下次连接后，通过该 run 的公开 `events.read` 检查是否确实存在 `origin_request_id=null`、`success=true` 的对应原生回执。

## 验证

真实进程结果与本机证据索引见 cli-save-receipts-validation.json（历史 JSON 已删除，可查 Git 历史）。

- `SaveReceiptSessionTest`：11 项 fake runtime + 真实 SQLite 回归，包括写入先于响应、跨 menu/run 关联、进行中与历史响应不可修改、多次回调、失败与会话过滤，以及回执 SQL 写入失败。
- `EofSaveRetryTest`：一次 GUI 意图变化导致的过期拒绝会重新观察并最终产生持久保存回执；不重试未知执行，不操作不可用提示，不输出主动响应。
- `save_receipt_smoke.py`：普通 SpdctlLauncher、新建隔离 profile、仅公开 NDJSON，验证原生开局/显式保存、没有保存的 UI 操作、状态版本、历史不后填、EOF 与重启。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/save_receipt_smoke.py
```
