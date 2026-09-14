# CLI.4.0.0：自包含观察与轻量回执客户端

2026-09-14，基于 `aa4b222d5` 实施。基础游戏仍为 3.3.8；CLI 升级为 4.0.0，协议为 4，审计 schema 为 7。完整英文接口见 [help](cli-help.md)，项目操作约定见根目录 `AGENTS.md`。

## 协议与数据边界

- 新请求只接受 `v:4`，不提供 v3 输入兼容；默认使用独立的 `~/Library/Application Support/Shattered Pixel Dungeon CLI v4/`。
- schema 1–6、缺失或不匹配的审计对在写入前拒绝，不迁移、不删除。原 v3 目录及战士实战未被打开或改变。
- 保持一请求一条完整 NDJSON 响应，原始 transport 格式仍为 `SPDCTL_TRACE 1`。全部收发原文与索引继续记录，查看器与游戏生命周期分离。
- 每条默认观察自包含，没有 delta、地图引用、跨响应字典或观察缓存。游戏规则、可执行操作、原生稳定边界和 `STALE_STATE` 校验仍由原控制器负责。

## 默认 play 和 full

`state`、`actions` 接受 `view:"play"|"full"`，默认 play；普通动作回复使用 play。`state src:true` 自动使用 full。历史 before/after 采用 full 投影，raw/reply 保留不可变内容。

两种模式都使用完整行地图：`w/h/types/rows/env`，每段为 `[y,x_start,tiles,visibility]`。常规 tiles 用 64 字符 alphabet；超过 64 个完整 descriptor 时，该地图所有行使用整数数组。已知间隙切段、环境完整更新、未知地形仍省略。full 采用同一新格式，不是旧协议通道。

play 采用明确的物品与 UI 默认值，只裁剪严格空白的文字叶节点。物品等级和诅咒三态分别为“缺失不适用、null 未知、显式值已知”；已知 0 和 false 保留，独立的物品详情知识标志保留。可执行手势只由原先实际存在的 ops 提供，disabled 控件的手势不转为能力。

元数据边界额外检查了受保护的知识标志、零点天赋条目和地图聚合来源。知识 pair 带特殊来源或 partial 时保留原值与显式标志，以显式标志解释知识；地图 descriptor/env 的聚合注解在排序、去重前分发，无法等价迁移的整体／坐标注解保留到 `map.preserved_cells` 诊断块并重基路径。普通地图仍只由 rows 定义，不用诊断块补图。

普通物品长说明延后到原生详情或 full；容器、拟态提示、陷阱、环境和当前窗口文字保留。水量、力量需求、估计问号、图标操作及有意义的禁用项不按库存名称去重。带 partial、裁剪、用户／外部来源或特殊来源的候选字段保守保留，避免丢失诊断及来源。

默认天赋列表只保留已投入条目，新增 `talent_points_available` 按 tier 1 起依次记录各阶可投入点数，调用与 GUI 相同的只读规则，包含奖励和门槛。full 保留完整目录，原生天赋窗口在 play 中仍显示当前选择。

## 客户端的原动作结果与当前观察

共享 `ActionResult` 为明确的本地派生对象，分别保存 initial_response、receipt_response、observation_response 和 failure，不伪装成原始 wire 回复。

- 同步成功直接使用动作观察，不加固定的前后 state 查询。
- 连续活动只轮询原请求的小回执。成功终态后获取一次当前观察，正常路径不再获取历史 reply 全文。
- 捕获发送请求时的原 scope，用它查询原请求；实时回复可以切换到新 scope。历史回复不更新当前版本。
- 有限操作初始为 resolving/cancelling 时，成功终态后先用一次 info 发现当前 scope，再读 state，避免仍以旧菜单 scope 读取新局；普通 continuous_activity 不增加查询，发现失败保持失败。
- 原动作保存回执、等待输入与中断状态保留；REJECTED、UNKNOWN、错误或超时不被后续观察覆盖，也不重发动作。
- 退出成功后等待进程结束；专门验证保存、历史与故障的测试仍可显式读取历史全文。

help 的完整示例和根目录 AGENTS.md 同步说明这一流程。普通场景驱动复用共享逻辑；autoplay 仍是有界开发客户端，其结果不代表正式通关。

## 实际原文的生产编码基准

输入是上次到第 6 层的完整公开 `send.raw/recv.raw`，共 1,312 对消息。通过显式的历史测试解码器转换为 canonical 公开数据，再调用本轮实际 Java `CompactProtocol`，没有读取存档或审计数据库。历史 reply 作为不透明内容保持原样。

统一使用 tiktoken 0.14.0 的 o200k_base，每条 compact UTF-8 JSON 单独编码。结果：

| 项目 | tokens | 减少 |
| --- | ---: | ---: |
| 原始完整回复流 | 8,067,900 | — |
| 新生产投影，仍计入原样历史全文 | 4,889,829 | 39.39% |
| 新投影并省去正常路径的 195 份历史全文 | 3,441,604 | 57.34% |
| 再计入新天赋点字段的保守预算 | 3,469,732 | **56.99%** |
| 879 帧原地图 | 2,450,556 | — |
| 879 帧新行地图 | 570,171 | **76.73%** |

879/879 帧地图解码后与原始公开地形、可见性、尺寸和环境语义完全相同。新加的可用天赋点字段不在旧原文中，因此不猜测该实战的值，额外按每帧 hero 32 tokens 计入 28,128 tokens 预算。真实天赋规则另由单元和引擎观察验证。

达到地图部分减少至少 70%、调用流程与默认回复组合减少至少 50% 的门槛。这些仅为原始协议文本预算，不是实际模型计费、缓存命中或新一轮通关数据。

可复算命令（显式输入这次保留的原始日志）：

```sh
mkdir -p desktop-control/build/cli4-validation
python3 -m venv desktop-control/build/cli4-validation/token-venv
desktop-control/build/cli4-validation/token-venv/bin/python -m pip install 'tiktoken==0.14.0'
git show aa4b222d5:desktop-control/src/test/python/protocol3.py \
  > desktop-control/build/cli4-validation/legacy_protocol3_fixture.py
./gradlew :desktop-control:writeTestRuntimeClasspath --offline --no-daemon --console=plain
desktop-control/build/cli4-validation/token-venv/bin/python \
  desktop-control/src/test/python/cli4_token_benchmark.py \
  --trace-dir "$HOME/Library/Logs/Shattered Pixel Dungeon CLI/transport/session-1789307027689168000-5381-Y3Sk6D" \
  --legacy-adapter desktop-control/build/cli4-validation/legacy_protocol3_fixture.py \
  --classpath-file desktop-control/build/test-runtime-classpath.txt \
  --output desktop-control/build/cli4-validation/tokens
```

历史解码器只供这个离线基准使用，没有进入应用或新客户端。tiktoken 仍为 build 目录内的测试依赖。`tokens/report.json` 和逐消息结果记录输入 SHA-256；原始 send/recv 字节未变化。

## 验证与产物

验证日志集中于 `desktop-control/build/cli4-validation/`，最终运行清单位于 `delivery/suite.json`。

| 检查 | 通过 | 失败／错误／跳过 |
| --- | ---: | ---: |
| control-protocol Java | 18 | 0 |
| game-control Java | 282 | 0 |
| desktop-control Java | 129 | 0 |
| Python（含 native transport 与客户端生命周期） | 100 | 0 |

autoplay 离线自测通过，包括同步摘要不重复完整观察、有限等待不调用连续活动钩子、作用域发现失败不继续 state 等。help 示例经过语法和生命周期检查，并使用最终包在独立目录真实执行 info/state/EOF。

最终真实引擎回归共 18 例：金币／能量／交易淡出六例、两界面击杀四例、持续移动和休息取消两例、已知／未知卷轴升级四例、炼金一例、保存回执及重启一例。均通过，采用独立测试目录；没有恢复正式战士存档，也不计通关。

一次较早的重跑在 WelcomeScene 被 STALE_STATE 拒绝。保留的测试 UI 记录显示原生输入代次从 0 变为 7，而 public_ui、语言和场景完全相同；请求正确使用之前的控件和旧版本，拒绝符合输入保护。记录不能识别事件种类或操作者。这次被干扰的实例未冒充成功，未重放被拒动作；重新创建独立实例后的最终全组通过。证据在 `engine-final/currency.log` 及其对应 fixture 中。

原路径 ARM64 应用已重建。最终 build_id：

```text
3ddbbdcbd203b42633d5defd979ab12b9b7fe4082fdd60545d1ea9ebb330bd0d
```

实际包报告 `CLI.4.0.0 (protocol 4, game 3.3.8)`；27,292 字节帮助与当前 Markdown、包内资源一致，help/version stderr 为空。构建清单显式记录 protocol 4、schema 7；3,301 个清单条目与编译输入的大小/SHA-256 匹配，2,836 个项目生产类与包内类逐字节一致，测试类没有进入应用。ARM64、plist 和 `codesign --verify --deep --strict` 通过。

实际包管道、非法输入、重复 ID、profile 锁、EOF、中文及空格路径验证通过。缺失／空目录两种新 profile 的正常开局及保存重启共四次 JVM 会话，55 次请求、25 次来源检查通过，SEND/RECV/DELIVERED 原文逐字节一致；被动查看器关闭不改变游戏。schema 1–6 的旧目录拒绝测试全部通过，目录字节保持不变。

最后一次强制 Java/ARM64 构建执行 34 个任务并通过；客户端和 help 收尾后再次构建、运行全部 Python 和最终引擎／实际包检查。既有 deprecated/unchecked、LWJGL Unsafe 提示及 Python 测试连接 ResourceWarning 保留在日志中，未将它们算作新增功能失败。未推送、打 tag、发布或进行 Apple 公证。
