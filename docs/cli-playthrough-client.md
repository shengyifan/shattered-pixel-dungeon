# Public CLI clients and per-frame decoding

## Reusable CLI.7.0.1 reference helper

`desktop-control/client/spdctl_client.py` is an optional, dependency-free Python
module for consumers of the packaged controller. It does not launch the game,
read profiles, perform I/O, choose actions, retry requests or replace revisions.
The packaged `spdctl control --machine` remains the normal game-control entry point
and does not require Python.

- `decode_wire_response(value)` validates one complete protocol-7 child response.
- `decode_client_response(value)` also understands controller response/error,
  settle and exit wrappers. Inspect `problems` before assuming success. Its
  `current_frame` is only a current observation, never a receipt, historical reply
  or late response; original evidence remains separately accessible and in `raw`.
- `validate_intent(current_frame, intent)` checks an explicit proposed controller
  intent against that frame's exact revision, scope and advertised operations. It
  does not modify or send the intent. This rejects a `select` for a click-only
  button and a `move` while only a modal choice is available.
- `map_cell(current_frame, cell)` returns the decoded known cell or `None` for an
  unknown gap. Coordinates use that frame's `w`, rows and terrain dictionary.
  It does not decide whether a cell is passable or promise successful travel.

Read transport through LF and parse the complete JSON object before using these
functions. Preserve the transport bytes independently of any display limit. A
packed node starts with a template index, not a control ID; the helper first expands
the current observation's `act_templates` and `inv_templates`, restores applicable
activity/cancel bindings before copying referenced operations, then expands
`ui.node_templates` and node operation references into `acts`, before resolving
item labels. All views use this representation. Null, false, zero,
unknown fields, protected metadata and literal labels remain distinct.

CLI.7.0.1 validates static parameters for every action, query and local `settle`,
including required/extra fields and JSON integer versus boolean/float distinctions.
An omitted settle rid can select the latest pending action; an explicit invalid
rid is never treated as omitted. Node/action constraints additionally cover choices,
slider/zoom ranges, binding slots and text submission. Text length is measured in
UTF-16 code units, matching the game. A proposed operation must satisfy one complete
advertised descriptor; constraints from different descriptors are not combined.
Scroll retains native clamping and omitted-axis behavior. Unknown map cells remain
legal targets where the native operation permits them. Actual key acceptance,
collision, damage, costs and completion still belong to the game response.

Rendered appearance and sampled display history are retained as data, not inferred
operations. Current measurements never borrow previous-frame values. Invalid env
cell conversion, duplicate decimal cell identities and invalid saved references
raise evidence-preserving DecodeError; they cannot silently overwrite data.

If `decode_wire_response()` or `decode_client_response()` fails, `DecodeError.raw`
retains a deep copy of the parsed failing wire JSON, not the original transport
bytes. `identity` contains its valid `id/s`, with `response_id/scope` convenience
fields. This is the failed reply's identity: a bad settle observation may identify
the state query, not the original action. Keep the original action's `rid`, outcome
and request from the wrapper separately. `stage` is the failing stage or None;
`contexts` holds `{controller,stage,raw}` wrappers, and `context` returns the
outermost recorded one. Preserve actual transport bytes independently. This
evidence never authorizes retry.

`expand_structures()` is a structure-only research/test helper. Its default does
not restore activity bindings, item/UI defaults or map cells; it is not a substitute
for `decode_wire_response()` when choosing actions and does not attach envelope
identity to exceptions. Its optional `bindings=True` is for explicit test adapters,
not a promise of complete semantic decoding.

```python
# Run from the repository root; no game operation occurs in this example.
import json
import sys
sys.path.insert(0, "desktop-control/client")
from spdctl_client import decode_client_response, validate_intent

result = decode_client_response(json.loads(complete_response_line))
# Retain/display the original outcome and errors, not only selected data fields.
print(json.dumps(result.raw, ensure_ascii=False))
if result.problems:
    for problem in result.problems:
        print(problem.stage, problem.code, file=sys.stderr)
else:
    frame = result.current_frame
    if frame is not None:
        checked = validate_intent(frame, explicitly_chosen_intent)
        # A caller may now send checked.intent once on its existing connection.
```

An initial `in_progress` must be presented before explicit settling. A successful
state query does not clear controller pending bookkeeping. After any definite
game rejection, `settle` establishes its original outcome; `ACTION_REJECTED` is
that confirmation, not a second gameplay error. Reobserve and make a fresh
decision only after the original outcome is known. Never repeat an uncertain,
pending or completed action. A blocked wand target closes the targeting prompt,
so sending another cell without reopening ZAP can become ordinary movement.

`acts` retains its full original order, including duplicate occurrences. Node `ops`
references the same capabilities by index, or retains protected inline operations.
Do not execute both representations. Invalid references or control mismatches are
decoding errors, not a reason to infer another operation or retry an action.
Frozen `before/after` snapshots have independent tables and never become a live
frame. `raw/reply` and source ASTs remain untouched. Full/src keep their field/source
semantics but do not promise uncompressed records on the wire.

## Historical paused gameplay driver

正式的模型控制入口现为包内 `spdctl control --machine`，由包内 JVM 维护唯一的机器子进程，不需要此开发脚本或外部 Python。它要求动作携带已经展示的 revision，补全短请求 ID 与作用域，并提供显式 `settle`；不包含战斗或探索策略。

`desktop-control/src/test/python/autoplay.py` 保留此前正式战士尝试所用的开发客户端。用户于 2026-09-09 要求先停止实战、改用中间状态覆盖全部场景；当时正式局已正常保存退出，开发工作不会自动重新启动它。

该脚本不是“已经能自主通关”的交付物。它有有界探索策略和接受 JSON 意图的持续连接模式，遇到未知生物、重要选择、首领或策略不支持的情况需要重新决策。已发现但未实现的例子是按探险手册钥匙记录优先处理锁门。实际失败和资源消耗没有回滚。

当前开发驱动使用协议 7（CLI.7.0.1 / schema 10），自动请求序号只使用十进制数字。同帧操作引用、记录模板、可见性简写和局部默认值由 `protocol7.py` 按当前手册展开，历史 v4 适配器仅用于显式离线基准。共享 `ActionResult` 将原动作 outcome/receipt 与当前 observation 分开；同步成功直接使用原回复，连续活动只轮询小回执并在成功终态后读一次当前 state，正常路径不读历史 reply。历史诊断不会覆盖当前 scope/revision，成功 quit 后不再查询。

客户端只使用公开协议、自己的公开响应日志和公开状态文件，不读取 `game.dat`、楼层文件或审计数据库。每个动作均使用会话前缀及发送前分配的短计数 ID 和当前版本；进行中的原动作通过新 ID 查询结果，不重发执行。待选物品窗口中的输入异常保留机器连接，避免因开发脚本结束而丢掉已消耗物品的选择机会。

`current-state-public.json` 在每次收到完整公开观察后原子替换，供调试者查看最新返回状态；历史 `checkpoint-game-public.json` 只代表其当时的检查点，不能代替最新观察。文件更新时间不代表游戏另行保存，保存仍以公开回执为准。

以下命令只运行离线策略回归，不启动游戏、不读取真实进度：

```sh
python3 desktop-control/src/test/python/autoplay.py --self-test
```

旧批次曾通过离线检查和 Python 编译；协议实施结果见 [CLI 7 实施与验收](cli7-implementation.md)，修复实现及原生夹具结果见 [CLI.7.0.1 验收记录](cli-rebuild-7.0.1-20260923.md)，当前实际包验证与最新清理范围见 [CLI.7.0.1 清理重建](cli-rebuild-7.0.1-clean-20260923.md)。它们验证有限策略分支、取消和保存退出错误处理，不能当作真实规则、全场景覆盖或通关证明。原验收的生成附件已在后续清理中移除，文字结论保留为历史记录。正式 profile 和公开游玩日志曾使用忽略的 `desktop-control/build/playthroughs`，没有随源码提交；这是历史位置，2026-09-19 清理前该目录已不存在。

面向模型的控制器必须及时暴露进行中的可取消观察，不得等动作全部结束后才统一输出。展示应使用同一个已解析响应对象；禁止按固定英文标签过滤、无标记裁图、统一删除危险说明或省略视觉提示。
