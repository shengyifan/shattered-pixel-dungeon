# 2026-10-11 战士九挑战第三层豺狼人死亡：公开证据

主报告：[战士九挑战豺狼人死亡复盘](../../2026-10-11-warrior-nine-challenges-gnoll-retrospective.md)。
本目录只作离线复盘，所有 `s/rev`、句柄、坐标、药水颜色和死亡揭示的装备信息都是历史证据，
不得绑定下一局动作，也不得把死亡后鉴定出的诅咒和等级回填为生前已知。

- `send.raw.gz`、`recv.raw.gz`：488 对完整 child wire 原始字节的无损 gzip，含首次握手及 `t2.1`–`t2.487`。没有重新编码 JSON、裁剪地图、删除描述或混入 controller 本地诊断。
- `controller-responses.ndjson.gz`：414 条公开控制器输出，含 328 条普通响应、86 个 `settle` wrapper。该副本从 `t2.5` 开始；初始握手和 `t2.1`–`t2.4` 未包含在此副本中，但完整保存在 wire 文件中。
- `events.tsv.gz`：完整传输偏移、现实时间和进程状态记录。时间戳和请求编号均不是游戏回合。
- `manifest.json`：原始及 gzip 文件字节数、行数、SHA-256，公开握手版本与包 build ID、源码提交、正式 decoder 哈希、请求统计、每次拒绝及恢复链。
- `public-keyframes.json`：**选字段索引**，每条保留自身 `id/s/rev`、原请求、英雄、完整库存、可见实体与反馈，并在选定菜单和检查帧保留 UI。它不是完整观察；完整地图、动作、UI 和其他公开内容在 `recv.raw.gz`。
- `verify.py`、`validation.txt`：仅离线调用仓库正式 decoder 的验证脚本及其结果。

本次没有读取或复制个人游戏存档、排行榜或私有审计数据库，也没有旧局的
`native-postmortem-summary.json`。源码基线与游戏包 build ID 分别记录，文档提交不代表重新打包游戏。

公开握手为 CLI.9.0.4、protocol 9、游戏 4.0.2 / code 922、audit schema 12。
`t2.6` 显示九项挑战全部勾选，`t2.9` 为一级零经验战士。
最后一对 wire 为 `t2.487 / s3 / rkd`：第三层、0/25 HP、`phase:ended`、`result:lost`，
日志显示 `Gnoll scout killed you...`。此后没有任何游戏请求，也没有 `quit` 请求。
传输状态稍后记录 `EXITED:0`；据此不能声称进程仍保持打开，也不能推断退出原因。

86 个 `settle` 输出包括 72 个完成、11 个明确拒绝，以及随后恢复完成的 3 个 `pending`。
11 次 `STALE_STATE` 均先查询原 `rid` 收到 `REJECTED`，再读取新状态并以新的请求 ID 和该帧版本继续。
这些是明确的服务器拒绝，不是解码故障；全部 488 个 wire 响应和 414 个 controller 记录通过正式 decoder。
索引保留 shield/cooldown 的原始语义，窗口内没有 `shown` 时不借用别帧补值。

从项目任意工作目录按实际路径运行：

```sh
python3 memory/evidence/2026-10-11-warrior-nine-challenges-gnoll-loss/verify.py
```

验证会核对精确字节哈希、488 对唯一请求及响应、385 个英雄帧、全部 wrapper 的原 receipt 和当前观察、
九项勾选、资源变化、冰霜与冻肉、最后死亡以及死亡后没有请求。脚本不连接游戏、不重放动作、
不读取 profile；通过检查不表示替代路线已经实测或保证通关。
