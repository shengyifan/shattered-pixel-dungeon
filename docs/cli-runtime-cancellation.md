# 持续操作与取消

当前支持持续休息 `rest`，以及通过地图选择等原生输入启动、已经发生实际移动的连续路径。
休息使用自然 Hero yield；移动使用 Actor 实际等待的 sprite monitor。专用 `move.step`
仍表示一次普通方向输入并等待其原生结果，不被转换成连续路径请求。CLI 只启动原游戏动作一次，
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
   不继续唤醒 Actor 的准备租约。休息和移动分别重新核对并获取 Actor 实际正在等待的
   thread/sprite monitor，不凭一次 volatile 读取就操作游戏。它还没有调用游戏取消回调。
2. 协议层先把取消请求的 EXECUTING 意图及 before 快照持久化进审计数据库。
3. 持久化成功后才调用 `cancelPrepared`，由它调用游戏原有 `GameScene.cancel()`。
4. 游戏回到同一个自然 ready 边界后，原请求 completion 和取消请求都得到同一份新状态。
   原请求终态为 INTERRUPTED，取消请求终态为 COMPLETED。

准备租约通过等待条件阻止下一次 Actor 调度，不是跨数据库事务持有 Java monitor。
冻结快照的 Future 也在退出 handoff monitor 之后才交付。移动动画仍正常更新；取消不会
把已经开始的那一格移动截断或倒退，完成响应必须等当前移动结束和正常回合处理完成。
观察到的活动类别只报告休息/实际移动，不按隐藏房间、地面物品或 HeroAction 私有子类
预先判断路径是否合法或暴露动作类型。

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

`ActorHandoffTest` 还运行真实 Actor 调度线程，验证没有控制租约时动画完成照常恢复调度，
以及租约期间动画可以完成、其他线程可以取得 monitor，但 Actor 不会提前执行下一步。
`InterlevelLoaderTest` 验证共享 loader 槽被清空时，仍必须等该场景实际使用的线程结束。

真实渲染与 Actor 集成验证通过独立测试 profile 和公开 NDJSON 运行：

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/cancellation_smoke.py --mode rest
python3 desktop-control/src/test/python/cancellation_smoke.py --mode travel
python3 desktop-control/src/test/python/cancellation_smoke.py --mode travel-audit-fail
```

travel 模式只从公开、已映射的长廊选目标，确认取消后停在准备快照的位置、未走到远端，
并验证重复和过期取消被拒绝。audit-fail 模式仅在该测试 profile 的内部数据库注入拒绝
取消意图写入的故障，要求不产生成功取消，原请求保留 UNKNOWN。所有此类 fixture 都有
`counts_as_win=false` 标记，不能作为正常通关的证据。
