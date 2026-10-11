# 2026-10-11 本局复盘证据

主报告：[战士九挑战复盘](../../2026-10-11-warrior-nine-challenges-retrospective.md)。
本目录只作离线复盘，旧 `s/rev`、句柄、坐标、颜色及死亡揭示的信息均不得绑定下一局动作。

- `send.raw.gz`、`recv.raw.gz`：本局 child wire 原始字节的无损 gzip，分别678行，含首次握手及 `t1.1`–`t1.677`。没有重新编码原始JSON，没有截地图、去描述或混入控制器诊断。
- `controller-responses.ndjson.gz`：本次游玩的552条公开控制器记录，包含133个 `settle` wrapper，保存原 `rid/outcome` 与新观察的区别；不是child wire的替代品。
- `events.tsv.gz`：本局传输字节偏移与现实时间记录。现实时间不是游戏回合。
- `manifest.json`：原始字节长度、行数、SHA-256、归档SHA-256、公开握手版本及复盘源码基线。完整原始传输仍留在本机原日志目录，文件夹basename见manifest。
- `public-keyframes.json`：76个关键观察的**选字段索引**，保留自身id/s/rev、原请求、英雄、完整库存及反馈。不是完整状态；地图、实体、动作、UI和其他公开事实在完整 `recv.raw.gz` 中。
- `native-postmortem-summary.json`：用户在死亡后明确授权只读原生存档后提取的本局结算摘要。原 `game1/game.dat` 已是1字节死亡标记，楼层文件已删除；实际可读信息来自匹配的 `rankings.dat` 记录。仅保留本局所需统计、装备与天赋，不复制整份个人存档、种子、隐藏地图、跨局记录或私有audit。
- `source-manifest.json`：本次报告引用的源码及SHA-256。文档提交不是游戏包build ID，二者分开记录。

排行榜背包和buff经过原生裁剪，不能从缺项推断死亡前没有某物或某状态。
`statistics.score` 是原生 `goldCollected` 的历史序列化键；总分取 `record.score/total_score`。
`duration:620` 是 `Actor.fixTime` 已累计归一化的整时间，不能把它写成精确最终游戏时长，
更不能把677个编号当677回合。隐藏存档信息只属事后核查，不回填行动前知识。

在项目任意工作目录使用实际路径运行：

```sh
python3 memory/evidence/2026-10-11-warrior-nine-challenges/verify.py
```

脚本仅解压本目录冻结证据并调用仓库正式解码器，不读用户profile、不连接游戏、不重放动作。
它核对678对收发、518个英雄帧、552个controller记录、9次预期服务器 `STALE_STATE` 拒绝、
关键索引、五餐前后变化、楼层计时和死亡/结算摘要一致性。服务器拒绝不是观察解码失败。
冻结记录通过检查不表示替代路线已经实测或可以通关。
