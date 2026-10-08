# 外部游玩资料迁入记录

迁入日期：2026-10-08。目的：让接手此项目的AI能够从仓库内获得本局实际用过的有用经验、
skill和只读分析工具，不依赖原机器的全局记忆库或临时中继。
使用入口是[本地memory索引](README.md)，当前协议以[cli-help](../docs/cli-help.md)为准。

## 迁入清单

`MEMORY_ROOT`仅表示历史来源 `~/.codex/memories`，不是环境变量、链接目标或运行依赖。
原文件SHA-256是此次导入时的字节指纹；全局记忆以后变化不改变本次副本的来源。

| 编号 | 原始来源 | 项目内位置 | 复制／适配方式 |
| --- | --- | --- | --- |
| M1 | `MEMORY_ROOT/MEMORY.md` | [project-knowledge.md](references/project-knowledge.md) | 筛选105–133、159–170、189–197行的项目规则，去重并按CLI.8.0.1校准；不复制其他项目、账户信息、旧活动状态与全局索引 |
| S1 | `MEMORY_ROOT/skills/pixel-dungeon-run-assistance/SKILL.md` | [游玩skill](../skills/pixel-dungeon-run-assistance/SKILL.md) | 适配副本：保留战术检查与资源预算；用公开协议替换旧存档／GUI流程，链接当前客户端和本地资料 |
| S2 | `MEMORY_ROOT/skills/shattered-pixel-dungeon-cli-release/SKILL.md` | [维护skill](../skills/shattered-pixel-dungeon-cli-release/SKILL.md) | 适配副本：移除固定checkout位置，按当前恢复和打包约定校准；原显式调用策略保留在`agents/openai.yaml` |
| H1 | `MEMORY_ROOT/extensions/ad_hoc/notes/20260824T174222+0800-spd-v338-warrior-talents-build-guide.md` | [战士天赋笔记](references/warrior-talents-v3.3.8.md) | 原正文逐字节保留，前置历史用途告示；原基线7b8b845a76，未将全部数值重新认证为当前规则 |
| H2 | `MEMORY_ROOT/rollout_summaries/2026-09-19T13-56-21-gY8d-shattered_pixel_dungeon_cli6_clean_rebuild_pty_fix.md` | [PTY历史](references/cli6-controller-pty-history.md) | 原正文逐字节保留，标记CLI5/6、旧协议与当时授权均为历史，不能照旧操作当前游戏 |
| H3 | `MEMORY_ROOT/rollout_summaries/2026-09-27T02-36-21-O1xW-spdctl_warrior_run_health_decoder_fix_and_floor22_pause.md` | [血条与暂停历史](references/health-rounding-and-pause-history.md) | 原正文逐字节保留，明确它结束于22层暂停；最终通关见项目已有复盘 |
| T1 | `/tmp/spd-warrior-20261007-relay/map.py` | [map.py](../skills/pixel-dungeon-run-assistance/scripts/map.py) | 适配副本：显式输入、仓库相对定位、正式解码器、完整已知地图与全部实体／预警输出 |
| T2 | `/tmp/spd-warrior-20261007-relay/cells.py` | [cells.py](../skills/pixel-dungeon-run-assistance/scripts/cells.py) | 适配副本：单帧精确格子／坐标查询，使用正式map_cell，保留证据身份和错误 |
| T3 | `/tmp/spd-warrior-20261007-relay/ray.py` | [ray.py](../skills/pixel-dungeon-run-assistance/scripts/ray.py) | 适配副本：保留整数弹道、源码hash校验与未知边界；去掉旧历史序号搜索和固定中继路径 |

三个工具共用新增的[public_snapshot.py](../skills/pixel-dungeon-run-assistance/scripts/public_snapshot.py)
只读输入层；[离线测试](../skills/pixel-dungeon-run-assistance/tests/test_public_helpers.py)只使用合成公开帧。
不增加游戏传输、进程管理、文件中继或自动策略。

### 原始文件指纹

| 编号 | 原文件SHA-256 |
| --- | --- |
| M1 | `2d7922012305786388b97510b8a5ec66aed93891651d3dffb919afcdc1c84244` |
| S1 | `879359d6600969d764c787c764d3ab4c8675b60766e2bb0f3c1cb229bca192d9` |
| S2 | `f394a367fed4b4784fafb8e97da564bc2f60a0d8b96e9cbe3c967ac7c5284940` |
| H1 | `b02df4407ea1f47e40eaa25f78e6f7aaf65d5832e8e6abf419c18e473c9048b0` |
| H2 | `20f253b08eae35c3fafb5ba0ec2357972618c4bd722933c4b13f0cf8cceb82a6` |
| H3 | `6aaca24fb1610b64c5cdcd48c3b8b84fd4a75dfe606613a16a21ef9e796b7f3f` |
| T1 | `13304f8ce3599fbc4384722e491faf7b0bb27bdcdaa1365ad19a0659aa99e0c4` |
| T2 | `5bb7b79487d6dfc2b48cef858790b8fd3340ff0e7e0fff885d1e5c388d5ff415` |
| T3 | `c932c1c99357c7baaa1df86e92aa6e370a346cbf64c22f2e4a768cbf9d155e96` |

H1–H3的原字节位于`BEGIN IMPORTED SOURCE`与`END IMPORTED SOURCE`标记之间；末尾额外分隔换行
不属于原文件。这些历史正文中的个人路径、临时文件、请求ID、坐标和旧命令只作来源证据，
无需在其他机器上寻找，也不能给新动作提供绑定或权限。

## 历史使用与当前适用性

本局主会话及子代理实际检索了M1，读取了两个skill、两份历史摘要，并使用了三个只读脚本。
H1只确认曾被关键词检索，日志没有返回对应建议；导入它是为了保留有用的版本化构筑参考，
不能据此声称本次通关遵循了它的完整加点表。

游玩skill旧正文含直接读存档、按保存类名判断、最小化保存、AX焦点和GUI键鼠操作。
当时实玩已明确排除这些流程；项目适配版将它们删除，保留源码核对、逐步规划与资源判断。
全局摘录中“STALE_STATE一定是操作错误”的过强概括也被校准为先判定原动作结果、再区分操作和时序问题。

只读脚本的文件输入是冻结证据，始终不证明连接仍在同一帧。预测值有单独的报告类型和适用范围，
不能冒充游戏回复、执行结果或保存回执。弹道覆盖projectile/magicbolt/solidbeam三种几何，
不覆盖Yog的WONT_STOP、全部法杖／物品覆写、范围伤害、射程与未报告的隐形角色。
原helper的地图边缘source退路已按Ballistica构造函数补齐；其他几何保持已审查源码约束。

## 没有迁入的内容

- 全局memory中其他项目、账户用量、机器使用记录及不相关的历史会话。
- 旧`helper.py`、`root-relay.js`等意图写入／轮询中继；会话PID、序号和暂停标记不具备可移植性。
- 本局response/history、raw SEND/RECV、stderr、线程采样及大体积Codex session日志。
  可复用结论已在[通关复盘](2026-10-08-warrior-playthrough-retrospective.md)、本次历史副本和当前修复文档中保留。
- 私人存档、profile和私有audit数据库。本次迁入没有读取它们，也没有操作现有游戏。
- 通关后token审计用的openai-docs skill；它不是游玩通关所需资源。

前三批临时中继目录在此次迁入时已不存在；不从日志重建失效会话。
这些文件通过AGENTS、README和本地memory索引发现，其他AI可直接读取Markdown，
无需安装或改写用户的全局skills/memory。主动入口按需加载，历史副本不充当新的操作规范。

## 本次验证范围

- 三份历史正文与来源逐字节一致，原始SHA-256匹配；主动Markdown的项目内链接已核对。
- 两个skill通过skill-creator格式校验；独立只读复核检查了待收齐回执、用户暂停和未知地形／Yog弹道三个场景。
- 三个工具的21项离线测试通过，覆盖完整地图、墓碑／植物等实体不遗漏、坐标越界、未知格、
  原错误与frame身份、hero/cues空值、输入不变、其它cwd/stdin、墙前碰撞和源码hash失效。
- 未启动或继续游戏；没有把历史天赋表逐项重新认证，也没有宣称迁入工具已经自主完成另一局。
  此次只增加项目资料和外置只读辅助工具，CLI／协议／schema／游戏版本保持不变。

可重跑的工具测试（项目根目录）：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover \
  -s skills/pixel-dungeon-run-assistance/tests -p 'test_public_helpers.py' -v
```
