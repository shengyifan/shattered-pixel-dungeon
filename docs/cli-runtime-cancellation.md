# 持续操作与取消

当前对持续休息 `rest` 支持在自然 Hero yield 边界报告持续活动和受理取消；长路径移动的
sprite-wait 边界尚待另行接入，不能据此声明已经支持。持续休息可以跨越多个自然回合。CLI 只启动原游戏动作一次，
不会把持续操作实现成多次 wait，也不会重复调用动作以推动它继续执行。

游戏进入可受理取消的自然边界后，原请求先返回一次 `status=in_progress`，其中
`phase=continuous_activity` 和动作描述给出 `action.cancel`、原始 `target_id` 及
专用的 `activity:...` 状态版本。此首响应不等待普通操作的 30 秒超时。
这并不代表原操作已经完成，其审计请求仍为 EXECUTING。

## 活动版本与普通世界版本

`activity:...` 只绑定同一局内、同一次持续操作的活动代数。它不等于某个世界快照，
不会授权用旧 HP、旧 FOV 或旧实体位置执行任意新动作。停止后该活动代数失效。
普通世界状态仍使用正常的 `state_version`，之后的动作必须取新的正常状态版本。

所有请求仍要求调用方 ID，并串行验证和响应。活动运行期间普通变更请求仍返回 BUSY；
携带当前活动 token 和原请求 ID 的取消，是保留玩家通过游戏界面停止持续操作能力所
必需的唯一例外。错目标、错代数和无效取消一样消耗自己的请求 ID，不能修正参数后重用。

## 取消的执行顺序

取消本身是新的 `action.execute` 请求：

```json
{
  "scope_id": "run:当前局标识",
  "id": "调用方的新请求ID",
  "op": "action.execute",
  "state_version": "activity:当前活动代数",
  "args": {
    "action": "action.cancel",
    "target_id": "原持续操作请求ID"
  }
}
```

1. `prepareCancellation` 在自然边界确认目标与代数，取得新鲜 before 快照，并短暂持有
   不继续唤醒 Actor 的准备租约。它还没有调用游戏取消回调。
2. 协议层先把取消请求的 EXECUTING 意图及 before 快照持久化进审计数据库。
3. 持久化成功后才调用 `cancelPrepared`，由它调用游戏原有 `GameScene.cancel()`。
4. 游戏回到同一个自然 ready 边界后，原请求 completion 和取消请求都得到同一份新状态。
   原请求终态为 INTERRUPTED，取消请求终态为 COMPLETED。

若取消意图无法持久化，调用 `abortCancellation` 释放准备租约，不能执行游戏取消回调。
重复的取消 ID 按已有去重规则拒绝，不会再调用游戏。

取消不使用 `Thread.interrupt`，不直接调整回合或随机数，不自动保存游戏，也不跳过
当前已经开始执行的原游戏回调。所有实际回调仍在已有的串行执行与自然边界规则内运行。

## 响应与审计

每个请求仍只有一条线上的响应。原操作已经发出的 in_progress 响应及对应 exchange
记录不会被改写，也不会在完成或取消后自动推送第二条响应。
调用方可用新的 `request.get` 请求查看原请求的最新 INTERRUPTED 结果；取消请求有自己的
独立请求 ID、before/after 快照和完成记录。

`ContinuousCancellationTest` 使用受控 GamePort Future 和临时 SQLite 数据库验证上述顺序、
首响应、拒绝与去重、原响应不变、其他动作 BUSY、审计写入失败时释放租约且不取消。
该验证不启动 GUI、触摸真实存档或模拟键鼠事件。
