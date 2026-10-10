# 项目本地 memory

这里保存本项目的实玩复盘和可复用经验。当前用户指令、`AGENTS.md` 和
[`docs/cli-help.md`](../docs/cli-help.md) 优先；历史 memory 不替代当前观测。

## 给接手游戏的 AI

1. 先读当前用户要求、[AGENTS](../AGENTS.md)和[英文协议手册](../docs/cli-help.md)。
2. 按[项目内游玩 skill](../skills/pixel-dungeon-run-assistance/SKILL.md)建立当前楼层、资源和战术计划。
   它已适配当前协议9的公开观察与控制流程，替换了原skill的存档读取和GUI键鼠流程。
3. 按需读取下表资料；不用加载所有历史记录。继续游戏必须从授权连接获取当前观察，
   文件、旧坐标或旧会话句柄不能代替当前 `s/rev`。

准备 **4.0.2 战士全九挑战** 时，先读 [九挑战研究总索引](nine-challenges-warrior-4.0.2/README.md)
和 [实战决策速查](nine-challenges-warrior-4.0.2/00-run-plan.md)。这是基于 `8405ae82f` 的当前源码研究，
涵盖九项挑战、战士全部天赋与构筑、五区敌人与首领、物品鉴定炼金、弹道和正常退菜单重载；
附原生弹道与内存序列化的隔离验证。研究尚不等于九挑战通关经验，正文区分源码事实、验证范围和战术推导。
下一局还须读 [2026-10-10 九挑战失败复盘](2026-10-10-warrior-nine-challenges-retrospective.md)：
它记录第4层饥饿死亡的公开证据，以及食物、火把、诡术师与未知药剂的止损规则；不是通关证明。

| 资料 | 何时读取 |
| --- | --- |
| [4.0.2 战士九挑战研究库](nine-challenges-warrior-4.0.2/README.md) | 九挑战开局/区域/首领规划、当前战士机制、装备与鉴定、弹道和重载技巧；优先于旧版数值笔记 |
| [2026-10-10 九挑战失败复盘](2026-10-10-warrior-nine-challenges-retrospective.md) | 下一局资源与止损规划；核查四餐、三支火把、连续休息、诡术师伏击及未知饮药的实际代价 |
| [项目全局记忆摘录](references/project-knowledge.md) | 需要控制边界、恢复原则和已有证据习惯时 |
| [战士天赋与构筑笔记](references/warrior-talents-v3.3.8.md) | 分配天赋、选子职业／护甲能力时；历史数值须对当前源码与装备核实 |
| [2026-10-08 战士通关复盘](2026-10-08-warrior-playthrough-retrospective.md) | 3.3.8历史路线、弹道、首领阶段及恢复纪律；数值须对当前源码复核 |
| [CLI维护 skill](../skills/shattered-pixel-dungeon-cli-release/SKILL.md) | 用户要求排障或修复控制器／客户端时 |
| [PTY启动历史](references/cli6-controller-pty-history.md) | 研究终端管道问题时；CLI6的旧版本与临时方案不能替代当前手册 |
| [血条修复与暂停历史](references/health-rounding-and-pause-history.md) | 理解解码失败、原动作结果、保留进程与暂停之间的边界 |

## 只读公开观察工具

以下脚本从本局用过的临时辅助脚本迁入，入口和依赖都在项目内。只读取显式提供的完整公开
JSON响应文件或stdin，不启动游戏、不发送意图、不读取存档／audit，也不自动刷新输入文件。
支持单个wire回复、控制器wrapper，以及旧中继的 `{seq,response}` 公开记录；`seq`仅保留为历史元数据。
代码仍调用正式[Python客户端](../desktop-control/client/spdctl_client.py)独立解码。

在项目根目录运行（`/path/to/public-response.json`由调用者提供）：

```sh
python3 skills/pixel-dungeon-run-assistance/scripts/map.py --response /path/to/public-response.json
python3 skills/pixel-dungeon-run-assistance/scripts/cells.py --response /path/to/public-response.json 123 4,5
python3 skills/pixel-dungeon-run-assistance/scripts/ray.py --response /path/to/public-response.json --target 123 --op projectile
```

- [map.py](../skills/pixel-dungeon-run-assistance/scripts/map.py)：完整已知地图、图例、全部实体／环境／公开预警；`--json`保留完整解码观察。
- [cells.py](../skills/pixel-dungeon-run-assistance/scripts/cells.py)：按cell或零起始x,y精查同帧地形、全部实体和预警，避免手工把门墙错算一格。
- [ray.py](../skills/pixel-dungeon-run-assistance/scripts/ray.py)：按仓库Ballistica源码计算三种有条件几何；未知格停止预测，源码hash变化要求重新审查。

`--response -`从stdin读取；脚本可以从其他工作目录以其实际路径启动，不需要本机用户名或安装全局skill。
文件只是冻结观察，输出不会产生新绑定或证明动作已执行。弹道工具不覆盖Yog穿墙射线、所有物品覆写、
射程、范围伤害或隐形占位；几何可达不等于命中、有效或安全。CLI.9.0.1 的默认地图已按原生公开显示区分普通水格（29）与铁匠铺固体装饰（39，其他部件为23），
并保留装饰的原生名称和描述；`ray.py`不再把所有水格一律判为不确定。它按同帧公开地形进行条件性预测，
未知格仍停止预测，未报告的阻挡物也不能假定绝对不存在。同帧公开角色或标有
`projectile_collision:"character"`的原生物件仍可证明STOP_CHARS在该格停止，物件不能作为假设攻击发射源。
工具不读取隐藏地形，也不按名字或NPC邻接关系猜测装饰类型。CLI.9.0.2 的公开环境可带
`collision` 部分布尔覆盖；弹道工具在原生碰撞判断前叠加光墙、钥匙墙、永燃火和蛛网的明确来源语义，
不从描述文字猜测规则，缺失或冲突的必要覆盖保留为未知。

来源、复制方式、原文件SHA-256及未迁入内容见[迁入清单](IMPORTS.md)。
历史副本中的外部路径仅为出处，使用这些资料不要求那些文件存在。

## 已保存复盘

- [2026-10-10：战士全九挑战，第4层饥饿死亡](2026-10-10-warrior-nine-challenges-retrospective.md)
  — 4.0.2 / CLI.9.0.3；以公开响应与源码区分资源透支、有效伏击、最后饮药风险和事后鉴定。
  可执行止损入口见[决策速查3.1](nine-challenges-warrior-4.0.2/00-run-plan.md#31-资源与遭遇的止损规则)。
- [2026-10-08：战士通关的决策失误与经验](2026-10-08-warrior-playthrough-retrospective.md)
  — 覆盖本 session 的早期实玩、控制异常、22–24 层和 Yog 首领战；区分实际失误、
  有风险的选择、执行前纠正、工具故障及成功策略。

## 下次实玩的优先检查

1. **恢复连接先重新观察。** 只使用用户授权的 `spdctl` 连接；不读个人存档或私有审计状态。
   本次历史的 `t1`–`t4`、`s2`、坐标、背包和敌人位置均不能直接用于新决策。
2. **有限资源使用前核实格子和弹道。** 用本帧地图宽度换算 cell/x/y，检查完整实体集合，
   包括容器、墓碑、植物、地面物品、敌人和机关；可见、可走、可命中是不同条件。
3. **限时增益按实际通路预算。** 不把几何距离当成寻路距离；被墙分隔后的绕行可能耗光隐身、加速、照明。
   危险区、拐角、交互物和换层边界分段决策，保留退出余量。
4. **首领阶段变化先核结束条件。** 锁层下不以持续休息代替阶段推进；回血来源和休息终止条件
   必须成立。`settle` 是控制器收敛原请求，游戏 `wait/rest` 会推进游戏，二者不可替换。
5. **把自己也纳入技能范围。** Blast 同时检查自身受推路径、墙体、植物与末端落点；
   主动自推只有在落点与代价已核实后才成立。扎根免推仅在该状态实际存在时有效。
6. **动态改变后重做局部计划。** 强制位移、护盾耗尽、怪物移动、草重生、召唤物出现后，
   重查射线、阻挡、敌人减伤和退出方向；不要沿用上一帧的“安全”结论。
7. **按机制选择资源。** DM-100/蝎子不能只凭武器射程硬换血；Yog 拳头先离保护圈再打；
   锈拳检查延迟伤害池；草拳按当前高草/犁草数重估；光拳分别准备半血、死亡两次清盲。
8. **警告淡出不等于攻击取消。** 记住已经公开、尚未确认发射的 Yog 射线。
   它穿墙；不能套用恶魔之眼或光拳普通光束的遮挡规则。
9. **异常先定原结果，禁止盲重发。** 原 `rid`、原 outcome、当前 frame、解码问题分开保存。
   `STALE_STATE` 经拒绝回执确认后，取新帧重新决定；未知执行结果或不可恢复异常按用户政策停止。
10. **结束条件以游戏确认为准。** 击败 Yog、拿到护符和选择正常结束是三个不同节点。
    2026-10-08复盘以 `run_outcome.result = won` 确认胜利；2026-10-10九挑战以
    `result = lost` 和 `phase = ended` 确认失败。按用户停止政策停止输入和查询，保留打开的进程。

## 协作与证据纪律

- 保留一个实际写入控制连接的执行者。地图/源码顾问只读，按新楼层、新机制或具体问题参与。
- 原始公开响应完整保留，每帧独立解码；不要用裁图、删描述、漏实体或旧正文配新 rev 来节省 tokens。
- 减少重复读取不变源码、重复汇报同一状态和无必要的常驻顾问轮询；正常同步成功后不额外查询 state。
- 复盘写“发生了什么、实际付出什么、下次如何改变”。没有反事实验证，不把所有掉血都说成可避免，
  不把成功通关等同于每一步都最优，也不把随机闪避、隐藏陷阱或协议故障一概归为战术失误。

详细记录中的坐标是复核线索，不是可直接执行的路线。版本、能力和机制变化后，应重新读当前源码与原生说明。
