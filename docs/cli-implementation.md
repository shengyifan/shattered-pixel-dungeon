# CLI 实施与验收

本文件是实现状态记录。目标是仅使用包内 `spdctl run --machine` 从主菜单控制六个职业通关。普通游戏规则、消耗、解锁、随机数和存档流程保持不变。

## 固定契约

- Java 11 源码，同 JVM GUI，stdin/stdout UTF-8 NDJSON；每请求一响应，无主动推送。
- 调用方为操作和查询提供 `id`；逻辑唯一键为 `(scope_id,id)`。重复一律 `DUPLICATE_REQUEST_ID`，不执行、不查询、不回放。
- 首次合法登记后，即使参数错误或状态过期也占用 ID。重复输入另外留档，不覆盖首次请求。
- 操作要求 `state_version`；查询不要求。需要旧结果时，以新 ID 调用 `request.get`。
- 菜单作用域为持久 profile UUID，游戏作用域为持久 run UUID。新局新 UUID，继续/复活同 UUID；旧进程状态版本无效。
- 正常接口只有 `protocol.info/state.get/actions.list/action.execute/request.get/events.read/history.list`，不提供内部视图、任意 Java 调用、SQL 或诊断导出。
- 公开状态、动作、事件、错误及日志仅含玩家实际可知信息。内部数据仅进入独立诊断库。
- 查询对游戏纯读，允许新增审计记录；禁止为查询调用保存、加载、刷新视野或有副作用的 getter。
- 每请求关联同一稳定时点的完整内部与公开快照；操作关联 before/after。不稳定或中断明确标记，不能伪造完整性。
- SQLite `public.sqlite3` 与 `internal.sqlite3`，单写连接 ATTACH、DELETE、EXTRA、macOS fullfsync，短事务共同提交。公开历史单独只读连接。
- 执行意图先落盘，后调游戏，结算与响应落盘后才输出。恢复未决操作为 unknown，绝不重放。
- 游戏结算完成不等于保存成功，SQLite 事务不涵盖游戏文件。
- 所有开发/专项测试使用独立 profile；正式通关只使用公开 CLI，不读取内部诊断与真实存档。

## 模块

`control-protocol`：公开 DTO/JSON/错误；`game-control`：快照/观察/调度适配；`desktop-control`：stdio/SQLite/启动/打包。
`desktop-control -> desktop -> core -> SPD-classes`，`desktop-control -> game-control -> core,control-protocol`。本体只保留中立同步、交互、身份、保存结果、只读 RNG 接口。

## 阶段状态

| 阶段 | 交付 | 状态 |
| --- | --- | --- |
| P0 | 契约、模块和验收基线 | 已建立文档 |
| P1 | 启动、profile、双库、打包 | CLI.0.1.0 已通过实际包内管道验收 |
| P2 | 去重、身份、恢复、历史 | 核心单测与同局重启通过；旧档/更多恢复边界继续验证 |
| P3 | 稳定完整快照与无损重建 | 声明范围/纯读/编码已验证；复杂长局与性能继续验证 |
| P4 | 主菜单至开局/选物/目标/取消/保存闭环 | 基础闭环及原生休息取消通过 |
| P5 | 玩家知识与双世界测试 | 已有针对性双世界/副作用回归；持续补交互边界 |
| P6 | 通用玩法与复杂交互 | 已接通通用语义；长路径中断与低频覆盖继续实施 |
| P7 | 六职业、十二子职、十九护甲能力与完整 UI | 真实隔离矩阵进行中，静态清单不等于全部实测 |
| P8 | 故障、保存、断流、包与性能 | 双库/进程故障与包基本检查通过；完整门槛未完成 |
| P9 | 六职业分别通关，至少一局完整返程 | 0/6；正式公开 CLI 探索已开始，未报告通关 |

## 覆盖门槛

交互清单涵盖所有场景、窗口（包括内嵌与匿名类型）、桌面背包、物品 actions、ActionIndicator、目标/选物/文本/滚动/数值控件；无法适配时明确停止，不能用鼠标兜底。
状态字段分类为公开、内部、构建常量、纯渲染/原生排除，新增字段需要审查。全快照保留引用、类型、集合顺序和实际 RNG 状态，不生成未来楼层或初始化尚不存在对象。

## 验收

1. 成功/失败/过期/重复/换档/重启 ID 测试，查询也占用 ID。
2. 采集不改变世界、RNG、ID 计数、鉴定、图鉴、窗口或存档；任意请求快照可无损重建。
3. 玩家视图与菜单相同、隐藏世界不同的双世界比较；公开输出、历史、错误均不得增加信息。
4. UI 原有回调与 CLI 语义动作对照消耗、结果和后续提示，覆盖取消与不足条件。
5. 执行前后事务、callback、stdout、游戏文件保存、新局身份各临界点崩溃注入；未知不重放。
6. 实际 ARM64 .app、真实管道、中文空格路径、独立目录与锁、背景焦点、布局变化、保存重启验证。
7. 六个职业真实通关、十二子职/全部技能专项。夹具记录不计为真实通关。

每个已验证阶段更新中文 CHANGELOG 并独立提交。未获得明确指令不 push、不发布，不纳入用户原有无关修改。

## CLI.0.1.0 验证快照

- `:control-protocol:test` 5 项，`:game-control:test` 40 项，`:desktop-control:test` 47 项，总计 92 项通过。
- `:desktop-control:packageMacArm64` 成功；两个启动器和 libjvm 均为 ARM64，plist 与 codesign deep/strict 检查成功。
- `machine_smoke.py --launcher <包内 spdctl>` 已验证全新随机地牢的正常教程（允许高草挡住书时仅按公开地形探索）、物品窗口/投掷/取消、重复 ID、过期版本、保存退出、原 scope 恢复与历史查询。
- 最近成功的包内 smoke：`desktop-control/build/smoke/bd5db159-4fde-4730-9db4-592b3ba0734f`。这些是开发测试数据，未进入 Git，也不算通关。
- 原生休息取消说明见 `cli-runtime-cancellation.md`；当前没有宣称长路径移动中断已实现。
- 实现中的第一个必要协议澄清：pending 期间允许新 ID 取消明确绑定的当前持续活动，其余游戏变更仍 BUSY；详见取消契约。
