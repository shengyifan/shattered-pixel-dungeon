# CLI 变更记录

本项目基于 **Shattered Pixel Dungeon 3.3.8** 扩展 CLI，上游基线为 `7b8b845a7`，游戏版本码为 `896`。CLI 使用 `CLI.主版本.次版本.修订版本`，与游戏版本、协议版本分别记录。

以下验证结论仅代表各版本当时的范围，测试夹具不计正式通关。详细说明见 [CLI 文档](docs/cli.md)与[实施记录](docs/cli-implementation.md)；旧运行数据和历史 JSON 已按用户要求清理。

## CLI.6.0.0

- Introduces protocol 6 and schema 9 with durable profile-local typed handles and an independent CLI v6 profile; previous formats are rejected without migration.
- Adds packaged `control --machine`: short request IDs, exact displayed-revision binding, complete-response validation, interruptible initial observations, bounded receipt settling and uncertainty recovery without action replay.
- Compacts same-frame UI shapes/operations, verified empty inventory backgrounds, captured item names, passive text identities, repeated characters and redundant activity/save bindings while retaining current decision evidence and protected diagnostics.
- Uses normal-weight terminal payloads and errors; only numbered SEND/RECV headings are bold, with shared bright-cyan coloring. The child remains the sole raw recorder and viewer owner.
- Current implementation and executed validation are recorded in [CLI 6 implementation and validation](docs/cli6-implementation.md); map/log incrementality remains a separate evaluation.

## CLI.5.0.0

- Introduces protocol 5 and schema 8 with a separate CLI v5 profile; previous profiles are refused before writes and are never migrated automatically.
- Unifies ordinary public text-source classification, adds semantic UI bindings/display fields and conservative deduplication, per-observation hazard/effect dictionaries, scoped defaults, uniform visibility and measured field aliases. Full/source and immutable history retain their diagnostic boundaries.
- Opens independent SEND and RECV + ERROR Terminal windows with selectable streams, shared bold bright syntax colors and unchanged backgrounds. Raw trace format/bytes remain unchanged; opening and viewer lifetimes stay independent of gameplay.
- Updates authoritative CLI help, agent agreements, current documentation, decoders and isolated tests. See [CLI 5 implementation and validation](docs/cli5-implementation.md) for measured results and acceptance limits.
- Fixes the visual observer's uncached scene-camera lookup so a newly created visible particle emitter participates in the existing first-draw readiness check; this preserves the action's immediate bomb warning without advancing particles or changing the waiting protocol.

## CLI.4.0.1

- 独立 Terminal 查看器的自动打开、`trace open` 和新生成的 `open-viewer.command` 显式使用 `--color always`，避免颜色被查看器继承的 `NO_COLOR`／`TERM=dumb` 自动判定关闭；直接 `trace view` 的 auto/always/never 语义保持。
- 新增两种实际生成脚本的 PTY 回归，覆盖 `NO_COLOR=1` 和空值、正文去色一致性、原文不变、路径安全与临时脚本自清理。详见 [独立终端颜色修复](docs/cli-issues/2026-09-14-cli-4.0.1-terminal-color.md)。

## CLI.4.0.0

- 协议升级为 4、审计 schema 升为 7，默认使用独立 CLI v4 目录；拒绝旧协议请求，旧审计格式在写入前拒绝，保留旧目录与原始历史。
- 默认自包含 play 观察采用完整行地图、明确字段默认值、物品知识三态、空文字裁剪和单一操作能力表示；full／来源详情保留完整诊断，危险与额外库存状态不省略。
- 共享客户端分离原动作结果与当前观察；异步成功使用小回执后的一次 fresh state，正常路径省去历史回复全文，同步成功和退出不额外查询。
- 新增根目录 AGENTS.md，更新英文 help 与项目文档；生产投影对上轮 1,312 条原始回复测得组合预算减少 56.99%，879 帧地图语义核对通过且地图 tokens 减少 76.73%。详见 [CLI 4.0 记录](docs/cli4-implementation.md)。
- 429 项 Java、100 项 Python、18 例真实引擎场景通过；实际 ARM64 包的版本、帮助、类内容、签名、独立开局／保存重启、完整收发及 schema 1–6 写入前拒绝验证通过。

## CLI.3.0.1

- 修复进入楼层、关闭交易窗口后金币提示自然淡出导致的 `STALE_STATE`：保留公开金币／能量提示，仅将其纯显示文字从操作意图签名中排除；实际货币、背包、按钮及回调变化仍使旧版本失效。
- 修复新场景空文字由 `null` 更新为空字符串时的无界面变化误拒，使内部文字签名与公开可见文字规则一致；非空原文和裁剪保护保留。
- 新增原生货币计时、完整控制器版本和真实引擎回归，保留旧版复现、两次实战原始证据及根因分析。详见 [CLI.3.0.1 修复记录](docs/cli-issues/2026-09-14-cli-3.0.1-currency-stale-state.md)。
- 410 项 Java、82 项 Python、六例真实引擎场景通过；ARM64 包版本、帮助、修复类与签名验证通过，实际包完成独立开局／保存重启的四次 JVM 会话和 65 次公开请求，完整收发逐字节一致。

## CLI.3.0.0

- 协议升级为 3、审计 schema 升为 6；平铺短命令和短字段替代旧请求格式，默认使用独立 CLI v3 profile，旧 schema 1–5 写入前拒绝，不迁移或清空旧目录。
- 默认完整决策状态采用地形字典/表格与控件 ops 去重，文本来源及历史快照按需；保留部分呈现、裁剪、用户/外部来源和原有状态/UNKNOWN 保护。历史分页用固定 until 上界，避免查询自身被审计时 limit=1 无法结束。
- Terminal 只显示 SEND/RECV/ERROR，隐藏 DELIVERED 和正常生命周期；支持 auto/always/never 与流式 JSON 类型高亮。原始字节和索引不变，控制字符安全转义，颜色不进入机器输出。
- 不增加整条消息字节上限，保留 64 KiB 背压缓冲；单条 16/64 MiB JSON、慢读、UTF-8 分片和无 LF 均完整验证。手册示例完整收发后再选择显示字段，区分外层工具截断。
- 403 项 Java、82 项 Python 回归通过；12 个真实引擎场景涵盖教程、击杀、持续取消、保存重启和升级确认。固定地牢样本减少 77.66% 字节、70.53% o200k_base tokens；不是所有模型的费用估计。
- 已重建 ARM64 应用；实际包版本、20,078 字节帮助、签名、管道及颜色验证通过。缺失/空测试 profile 开局/重启共 4 次 JVM 会话，65 次请求、35 次来源检查，SEND/RECV 逐字节一致。详见 [CLI 3.0 实施记录](docs/cli3-implementation.md)。

## CLI.2.1.1

- 修复击杀最后一个可攻击敌人后下一条动作误遇 `STALE_STATE`：攻击指示器隐藏后的有限延迟停用现在进入稳定边界，等待原生更新完成再返回；保留 0.75 秒时长、点击语义及版本校验。
- 同构建旧版在两种背包界面复现问题；修复后“最后目标/仍有目标”四组合和原教程两种界面共六个真实引擎场景通过。立即响应已包含最终按钮状态，跨过原延迟后的旧观察版本可执行下一动作；真正陈旧的版本仍拒绝且不移动，所有场景均无动作重试。
- 380 项 Java 测试、60 项 Python 测试通过；新增三项原生指示器生命周期测试。静态 UI 基线仅更新一处行号，回调摘要和入口集合不变。
- 升级运行入口、握手、构建清单、包内英文帮助及当前文档到 `CLI.2.1.1`；明确仅在确认拒绝、重新观察和重新决策后允许客户端有界恢复，服务端不替换版本重放，UNKNOWN 与进行中的动作不可据此重试。
- 重建 macOS ARM64 应用；实际包及开发入口版本正确，30,271 字节帮助与源码一致，签名、plist、内置 JVM/SQLite、独立管道与协议错误保护通过。修复相关编译类与已验证运行时一致，测试夹具未进入应用包。
- 复现、修复及产物验证边界见[两次 STALE_STATE 分析与修复](docs/cli-issues/2026-09-13-cli-2.1.1-stale-state-fixes.md)。

## CLI.2.1.0

- 机器入口增加共用 native 原文记录器，默认打开独立 Terminal 查看完整 SEND / RECV；关闭查看器不结束游戏，可通过 `trace open/view` 重新查看。`--no-terminal` 保留记录，`--trace-dir` 指定独立根目录。
- 记录实际字节、时间、方向与转发状态，stderr 分流；记录写入故障停止新请求并走原 EOF 退出，不重放动作或伪造响应。查看器对异常控制字节作明确转义，原始文件保持原字节。
- 仅不存在或为空的新 profile 默认窗口化、简体中文、跳过教程和首次前言；已有设置、职业解锁、成就与旧版本迁移保持原生行为。
- 协议仍为 2、审计 schema 仍为 5；构建标识新增 native 传输源码、开发入口和构建参数。验证范围与产物记录见[传输记录与验收](docs/cli-transport.md)。
- 377 项 Java、41 项原有 Python、19 项 native 传输测试和 2 项原文核对器自测通过。缺失／空 profile 实际新局及重启共 4 个 JVM 会话、65 次来源检查；11,276 字节 SEND 和 1,309,373 字节 RECV 逐字节一致。实际包原始管道、原教程、单次消耗、保存历史与启动失败保护通过。
- 从空构建目录全量重建 macOS ARM64 包，33 个任务全部执行；实际包版本、28,783 字节帮助、签名与内置运行时核验通过。
- 实际包拒绝旧 schema 1–4 且 profile 内容、修改时间与目录不变。自动 Terminal 原生查看器进程已验证；工具禁止访问 Terminal，真实窗口关闭按钮的人工确认未完成，已在验收记录明确区分。验证结束清除约 197.19 MB 测试数据及复制包。

## CLI.2.0.0

- 签名提交实现后清除 13 处旧生成路径，约 4.05 GB；从空 build 全量重建 ARM64 包，32 个任务全部执行、367 项 Java 测试再次通过。包内版本/25,800 字节英文帮助与仓库一致。
- 实际包通过独立 JVM/SQLite、ARM64 与签名、中文窗口化、新手教程、投掷取消、进食英文来源、保存重启、历史原样返回及五帧原始字节审计核验。锁竞争不写入他人 profile 的测试契约已同步；真实应急归档失败仍保留原文件并正确结束审计。
- 最终 Python 断言复测 41 项通过，测试进程退出后清除约 326.68 MB 新测试数据及复制包；保留一份当前应用与新开发运行时。

- 367 项 Java、41 项 Python 测试通过；4,830 个资源键 × 23 种语言共 111,090 项检查无格式/投影缺陷，3 项使用安全 key 可见性降级。23 语言基础流程、九语言 36 个深场景、同 JVM 九次语言切换和两种原始教程均通过；夹具不计正式通关。

- 协议升级为 2，所有请求必须显式携带 `protocol_version: 2`；审计升级为 schema 5，默认目录改为 `Shattered Pixel Dungeon CLI v2`。旧 schema 在写入前拒绝，不迁移或删除。
- 保留原版 String 接口，通过中性中央钩子、弱引用身份注册表和不可变来源树追踪最终资源 key；CLI 使用冻结的官方英文模板，移除中文反查与窗口文案签名推断。
- 文本字段新增 `text_sources`，用户/外部原文保留来源。正文、key 与参数一起经过可见性筛选；裁切与不支持的来源不能泄漏完整模板或参数。
- 呈现状态和字段诊断独立于动作完成状态，纯文案失败返回安全 key 或英文占位。日志、保存回执和实际发送响应独立落库，历史按原样返回，不依赖当前观察或再次翻译。
- 原版改动集中于观测钩子、基础控件生命周期和实际丢失来源的局部表达式；不改变游戏规则或原生存档格式。完整验证与构建记录见 [CLI 2.0 实施记录](docs/cli2-implementation.md)。

## CLI.1.0.1

- 2026-09-13 按要求清除 13 处旧构建/生成路径后，从 `7373c07a3` 完整重建 macOS ARM64 CLI；禁用构建缓存和任务复用，31 个任务全部执行、380 项单元测试通过。新包及开发运行时均为 CLI.1.0.1，产物核验和清理范围见[完整重建记录](docs/cli-rebuild-20260913.md)。本次未修改功能源码，CLI 版本不再递增。
- 2026-09-13 按用户约定，每批修复完成后升级 CLI 版本；本批补升修订号为 `CLI.1.0.1`，同步 `--version`、`protocol.info`、构建清单和当前文档。
- 修复首次教程结束时提前返回稳定状态：原有 2 秒渐显现在声明其尚未完成的交互变化，CLI 等待工具栏、背包及后续绘制就绪后返回；真实陈旧版本仍被拒绝。
- 修复升级卷轴选择护甲时的 `EXECUTION_UNKNOWN`：完整升级预览中的“防御”按 `Blocking` 解释，同时支持窗口聚合文本，保留强化界面的 `Defense` 及未知文案拒绝。未修改原生升级回调或 UNKNOWN 隔离规则。
- 380 项单元测试与 6 个隔离真实引擎场景通过：教程两种界面、已/未鉴定卷轴与固定/弹出背包四组合。确认预览不升级、返回和取消遵循原规则、护甲与纹章仅升一级、卷轴只消耗一次，并取得保存回执。
- 修复功能的 380 项单元测试与六场景验证先在 `CLI.1.0.0` 的修复构建完成；随后补升 `CLI.1.0.1` 并重建本地 macOS ARM64 应用和 `bin/spdctl` 开发运行时，协议仍为 1。各次构建标识和验证范围见[问题修复与验证](docs/cli-issues/2026-09-13-fixes-and-validation.md)。未发布或恢复原故障会话。

## CLI.1.0.0

- 按要求清除全部旧构建目录、项目缓存、测试应用与审计后，从最新源码重新构建macOS ARM64 CLI。30个任务全部执行、374项测试通过；完整英文帮助和签名验证通过。新测试账本夹具也已清理，仓库只保留一份当前应用。
- 补全 `--help`：从新增英文 `docs/cli-help.md` 输出游戏操作手册，包含协议、开局、22类动作、18个JSON示例、取消和保存退出；帮助随应用打包并纳入构建标识。96项桌面控制测试通过，重打ARM64包的帮助输出与Markdown逐字节一致，无需源码目录、不创建profile。
- 按用户要求直接升级至 CLI.1.0.0，统一 `--version`、`protocol.info` 和构建清单中的版本标识；基础游戏仍为 3.3.8，协议 1、审计 schema 4 不变。
- 从 clean 重建 macOS ARM64 应用；374 项 JUnit、20 项 Python 测试通过。实际包通过英文协议、中文窗口化、原始字节审计、物品操作、保存重启、内置 JVM/SQLite 与本地签名验证。
- 更新当前使用说明与实施状态，保留历史测试报告的原版本；正式战士通关仍为 0/1。

## CLI.0.8.12

- 修复未绘制或视口外浮字的全文泄漏：只公开完整绘制帧中的可见片段，在节点和 ID 分配前过滤；不补齐被裁内容，不调用战斗 getter。
- 374 项测试任务通过；实际 CLI 平移验证文字与节点随视口正确出现、消失。ARM64 包通过英文管道、中文窗口化、物品操作、保存重启、数据库完整性与本地签名验证。
- 为计划中的 CLI.0.9.0 新实战清空旧存档、审计、策略轨迹、应用包和构建缓存。旧原始路径已失效，源码与历史说明保留。
- 删除 43 份历史 JSON；静态 UI 基线迁至测试资源，场景计划改为 Markdown，语料工具改用显式指定的轨迹。相关 5 项 Java、4 项 Python 检查通过。
- 整理变更记录、明确基础游戏版本，并忽略 macOS `.DS_Store` 元数据。

## CLI.0.8.11

- 支持首次赞助提示的英文 Close，依据完整公开窗口识别，保留原 Back 保护，不跳过提示或点击外链。
- 验证 WornKey 的原 Boss 出口，以及 SkeletonKey 的造锁、取消、开锁、丢弃和强开流程；容器与门累计覆盖 12 个具名场景。
- 验证占卜卷轴的随机鉴定与全部已知分支：原消耗、结果窗口、返回和提示均符合游戏规则。

## CLI.0.8.10

- 支持 NPC 已显示的说话者与引号对白组合；未知正文、尾文及裁剪内容仍保守拒绝。
- 制杖匠与幽灵相关补验通过，22 个低频场景全部获得通过证据；各批构建和原失败分别记录。
- 增加受限的离线失败诊断工具，只读取明确列出的已结束测试目录，拒绝越界和符号链接；8 项回归通过。

## CLI.0.8.9

- 为实际可见战斗浮字添加公开标记，区分 dodged、marked for death 与菜单或属性文案，不重新调用战斗判定。
- 188 个职业与能力场景全部获得通过证据：原批 148 项通过，加 40 项修复补验；不等同于同一构建全量重跑。
- 墓碑、铁门和水晶门专项通过，容器累计覆盖 10 个具名场景。

## CLI.0.8.8

- 通过当前完整已显示正文区分能力和天赋名称。六职业详情覆盖 12 个子职业、18 个护甲能力和 132 个天赋说明。
- 新增纯公开对话签名，区分偷窃、复活和奖励确认。确认补验 5 项完整通过，制杖匠后续对白仍待修复。
- 平衡符石的武器、护甲两条路径通过原取消、应用和移除验证，消耗与可选项正确。

## CLI.0.8.7

- 修复连续翻译失败覆盖原请求错误的问题，保留原错误类别、保存回执、私有原文与异常；32 项相关测试通过。
- 完成英文低频矩阵首轮（14/22）及嵌套详情首轮（4/6），明确保留未通过项。
- 普通骨骸、英雄遗骸和待售物品专项通过；墓碑遭遇战斗浮字歧义，整项仍记为失败。

## CLI.0.8.6

- 补齐拳套连击、暗影映像、升级返回和未知卷轴取消确认的英文语义，只依据当前公开菜单。
- 支持完整多段资源与换行组合，覆盖 Shockwave 和 Trinity 文案；124 项定向测试及 5 项实机补验通过。
- 锁箱和水晶箱通过钥匙、消耗与知识边界验证，保留水晶箱合法的大类线索。

## CLI.0.8.5

- 按完整名称、消耗和说明翻译角斗士、武僧招式列表。
- 根据当前物品窗口区分斗篷 STEALTH 与匕首系 sneak，拒绝借用隐藏类型或其他窗口的信息。
- 113 项定向测试及盗贼、角斗士、武僧、匕首 4 项实机补验通过。

## CLI.0.8.4

- 支持完整资源与参数模板的拼接，保留显示数字，拒绝歧义和未知尾文。
- 补齐专精页和胜利排行文案；六职业说明四页通过，更多内嵌详情仍单独验证。
- 地面物品堆、普通宝箱及两条护符结局路径通过。低层地表结束与独立 25→24 层返程确认不合并称为完整返程。

## CLI.0.8.3

- 支持六职业介绍的 22 个完整独立段落，以及胜利窗口的原 Close 语义。
- 54 项相关测试通过；完整四页和胜利排行仍有未译文字，保留为待修项。
- 测试运行器复用冻结产物，长动作只查询原请求终态，不用后续观察替代缺失结果。

## CLI.0.8.2

- 将物品正文采用的等级知识关联到当前窗口，区分 Spear 的 actual/typical 说明；容器不暴露隐藏物品。
- 目标提示只复用同一公开 UI 中完整的英文语义，不读取隐藏选择器或补齐裁剪尾文。
- 92 项定向测试和 8 项物品/武器实机分支通过。

## CLI.0.8.1

- 补齐笔记和键位输入窗口的英文语义，保留原确认、取消和禁用规则。
- 五类笔记的增改删、取消、保存重启，以及键位确认、重复保护和默认恢复通过验证。
- 欢迎/更新提示、永久死亡、排行和新局重开专项通过；新增 Hero Info 文案缺口继续跟踪。

## CLI.0.8.0

- 验证自定义种子、每日局、随机角色/挑战、冲突拒绝和原删除确认流程。
- 完成跌落、矿井往返及四个区域故事的英文 CLI 回归；仅修正测试初态，不改变游戏切层规则。
- 支持完整高亮模板与公开键位上下文；设置六页签、滑条恢复和键位面板退出规则通过验证。

## CLI.0.7.2

- 实际 ARM64 包通过英文 CLI、中文窗口化、原首次教程、物品操作、日志原文配对及保存重启验证。
- 依据公开场景区分 Guidebook/Guide，修复首次教程的“指南”歧义。
- 开局选项的锁定说明、种子编辑、挑战及取消分支通过；实际开局和更多确认分支继续测试。

## CLI.0.7.1

- 公开实际 GUI 语言与窗口模式；英文日志和内部原文通过事件序号关联。
- 加强测试：先保存响应再检查英文，按相同状态版本核验中文窗口化，清理或退出失败不能计为成功。
- 语言、日志原文配对与事务回滚回归通过，实际应用包继续验证。

## CLI.0.7.0

- 接通中文 GUI 与英文 CLI；原游戏回调保持 GUI 语言，避免改变规则和 RNG。未知完整文案明确报错，裁剪内容不补全。
- 状态、动作、事件和历史统一英文呈现；原始标识、路径和审计数据不改写，原文与异常保留内部记录。
- 新局/保存重启、旧中文历史呈现、主菜单与图鉴页签通过。固定 5,874 条公开响应语料回归通过，不把它算作新的实机场景验证。

## CLI.0.6.0

- 建立线程隔离的英文纯读与独立资源投影器，开始处理已显示文字的安全翻译；完整接线尚未完成。
- 建立场景、窗口和输入覆盖清单，区分静态可适配与实际验证。
- 增加天狗实际陷阱图案、主菜单和跨层专项；191 项单测通过，个别初态与语言问题继续处理。

## CLI.0.5.0

- 按用户要求暂停正式实战，转向构造中间状态的场景测试；保留公开协议策略客户端，未宣称能够自主通关。
- 修复新 JVM 继续待复活存档时的稳定边界，保留原强制选择、取消和保存规则。
- 增加天狗炸弹烟雾/倒计时和矮人国王召唤粒子观察；187 项单测及相关实机回归通过。

## CLI.0.4.0

- 将正式通关目标调整为战士一局，全角色功能与专项要求保留；实机统一中文、窗口化。
- 新增实际已显示日志、红色目标标记和粘咕粒子观察；隐藏或暂停的视觉源不阻塞输入。
- 补充构建/会话信息、恢复文件与精确数值快照。175 项既有测试及新增定向回归通过，验证后台操作、实例锁与测试窗口清理。

## CLI.0.3.0

- 审计升级至 schema 4，记录会话、运行生命周期、存档槽与保存回执；区分内存完成和保存成功，不伪造旧记录字段。
- 修复 EOF、未捕获异常和遗失物品的控制边界；未知结果不重放，强制选择不自动确认。
- 146 项单测、22 项低频交互、11 个真实游戏强杀边界及包内验证通过，并建立性能基线；正式通关尚未完成。

## CLI.0.2.0

- 加入连续移动/休息的交接屏障与可审计取消，修正切层和真实 GUI 输入的过期检查。
- 审计升级至 schema 3，保留原始分帧字节；修复空锁文件被删除导致的多实例风险。
- 增加 ARM64 原生启动前置入口与诊断路径。120 项单测、真实保存/崩溃与包内管道验证通过，建立 188 项隔离能力矩阵。

## CLI.0.1.0

- 新增三个控制模块、同 JVM 串行 NDJSON 启动器和 ARM64 应用双入口；开发运行使用冻结 classpath。
- 实现请求去重、状态过期拒绝、历史查询、跨重启身份，以及成对 SQLite 审计和完整声明范围快照。
- 接通通用 UI 与目标选择，保留原回调和玩家知识边界。92 项单测及实际包内开局、保存重启验证通过；完整玩法与长期验收继续开发。

## CLI.0.0.0

- 确立同 JVM 串行协议、每局请求去重、状态过期拒绝、玩家信息过滤和完整审计契约。
- 建立分阶段验收要求，未完成的功能不得作为通关能力宣称。
