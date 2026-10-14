# 2026-10-15 战士九挑战咕噜死亡：公开证据归档

主报告：[满血抵达咕噜、1HP收尾中断与恢复负债](../../2026-10-15-warrior-nine-challenges-goo-retrospective.md)。
此目录仅用于离线复核。所有坐标、句柄、物品定位符、`s/rev`和死亡揭示的信息都是历史资料，
不授权连接、重放或继续游戏，也不作为下一局的状态绑定。

| 文件 | 内容与边界 |
| --- | --- |
| `send.raw.gz`、`recv.raw.gz` | 1214对完整child wire原始字节的无损gzip：首次握手、`t2.1`至`t2.1213`。未重编码JSON，未裁图或删除UI、描述、实体和警告。 |
| `controller-responses.ndjson.gz` | 990条完整控制器输出，从`t2.2`开始；握手与`t2.1`仅在完整wire归档中。766条普通响应、224个settle wrapper。 |
| `controller-intents.ndjson.gz` | 对应990条本地控制器意图；与child wire分开，不能误当实际线上请求字节。 |
| `events.tsv.gz` | 原始传输时间、偏移、交付与进程状态事件；时间戳和请求序号不是游戏回合。 |
| `final-response.json` | 最后一条child wire的原始完整字节，`t2.1213 / s3 / r897`。 |
| `public-keyframes.json` | 选字段历史索引：每条保留自身身份、原请求、英雄、完整库存、实体、反馈和关联状态节点；部分菜单保留UI。**不是完整观察**，完整地图及其余公开信息在`recv.raw.gz`。 |
| `manifest.json` | 原始及gzip字节数、行数、SHA-256，包身份、源码基线、decoder及所引源码指纹、wire/controller分别计数。 |
| `verify.py`、`validation.txt` | 离线验证脚本及本次结果；只读此目录与仓库源码，不启动游戏，不读profile／audit，不重放输入。 |
| `research-baseline-comparison.json` | 全研究库的旧指纹检查有46项既有差异；将本轮编辑的研究文档与修改前HEAD对照，新增／消除差异均为0。保留旧基线。 |

公开握手为CLI.9.0.6、protocol9、game4.0.2/code922、audit schema12。
复核源码基线为`9be0088fec8a452155e500688f7c31f466acd960`；实际包build ID另存manifest，
文档提交不代表新的游戏二进制。此次没有个人存档或排行榜摘要，也没有私有audit副本。

九项勾选由`t2.6`确认，新战士由`t2.9`确认。`t2.1011`以40/40HP、力量12、短剑+2入5层。
可见、同帧实体绑定的Boss状态文字在`t2.1103`为1/120，在`t2.1190`为78/120，最终为26/120。
中间锁门后Goo不在FOV，面板虽保持1/120，却没有实体subject；验证脚本明确拒绝把它算成实时HP。

两次服务器STALE_STATE分别是`t2.1048`、`t2.1053`，原请求均先由settle确认REJECTED，随后才读新state并作新决定。
统计分层是：2条wire `response/STALE_STATE`，2个wrapper `initial_error/STALE_STATE`，
2个wrapper `settle/ACTION_REJECTED`；它们对应两次拒绝，**不是六次故障**。
224个settle中222个完成、2个明确拒绝，没有pending wrapper或观察解码失败。

最后发送仍是`t2.1213`；之后没有游戏请求，没有quit或显式save请求。
`events.tsv`后来记录`EXITED:0`，说明传输记录中的子进程随后已退出；不推断是谁关闭或为何退出，
也不把停止输入时的“保留进程”延伸为永久仍运行。

从仓库根目录运行：

```sh
python3 memory/evidence/2026-10-15-warrior-nine-challenges-goo-loss/verify.py
```

脚本验证精确字节与偏移、唯一请求配对、987个独立解码观察（961个有英雄表）、990条意图、
各wrapper与原wire一致、拒绝恢复链、关键资源变化、19次近战和投射伤害账本，以及死亡之后没有请求。
使用正式`decode_client_response`和`validate_intent`，不会通过拼接别帧字段来补知识。
这证明记录和报告所引用事实的一致性，不证明替代方案必胜，也不等于重新跑了整局或修复了CLI。
本次相关的23份源码指纹检查通过；全研究库旧基线的46项差异单列，不能把两种验证范围混为一谈。
