# CLI 实施与验收

> 当前开发迁移：CLI.2.0.0 / 协议 2 / 审计 schema 5，采用 String 对象身份来源追踪，详见 [CLI 2.0 实施记录](cli2-implementation.md)。本页后续批次记录保留当时版本和验证范围，旧反向翻译、审计迁移及旧 profile 说明不适用于 v2。


本文件是实现状态记录。2026-09-09 用户进一步要求先停止正式实战，改为直接构造中间状态覆盖全部场景；当时战士已正常保存退出。2026-09-12用户进一步授权清空全部本地运行数据，旧战士profile现已删除，随后要求直接升级至CLI.1.0.0并重新构建，取代先前CLI.0.9.0计划，并明确只需macOS版本。最终实战目标是仅使用包内 `spdctl run --machine` 从主菜单控制战士通关，并覆盖完整返程到地表。用户于 2026-09-09 将六职业分别通关的验收缩减为战士一局；全角色功能和专项覆盖要求继续保留。本次版本升级不修改游戏规则、协议版本或审计schema。

## 2026-09-13 当前 macOS 完整重建

按用户要求，清除全部项目旧 build 目录及相关生成缓存后，从源码提交 `7373c07a3` 离线完整重建 CLI.1.0.1。31 个任务全部执行，380 项单元测试通过，macOS ARM64 应用和当前开发运行时均重新生成。新包实测构建标识为 `83ba4a3dbd3239d214d0ad23f4c0479da1cfac3dbeba6088f7ca6029aa13868a`；`--version`、帮助、plist、本地签名和 2,838 个项目生产类核验通过。此次未启动游戏，没有重新执行六个游玩场景。

此前 build 内的测试 profile、原始报告、冻结运行时及应用副本已删除；测试此次生成的 12 个账本 profile/24 个 SQLite 文件和 iOS 配置也已清理。保留新编译产物、一份 macOS 应用、一份当前开发运行时及本次构建报告。个人游戏目录与 docs 中两份本地故障证据保留。旧 CLI 工具会话在清理前已确认退出码 0，本次未关闭或恢复游戏。详细范围和命令见[完整重建记录](cli-rebuild-20260913.md)。

## 2026-09-13 实战异常修复

当前工作区已修复首次教程的提前稳定响应与护甲升级预览的英文歧义。380 项单元回归、教程两种界面及升级四组合真实引擎验证先在 CLI.1.0.0 修复构建 `b82522d66daa8b5991f82a33a0766b940d07e2f9e2783b9a9a860e6df6a70a41` 通过。随后按用户“每次修复后升级 CLI 版本”的约定补升为 CLI.1.0.1，同步重建本地 macOS ARM64 应用和开发运行时；游戏版本及协议 1 不变。最新构建标识和版本一致性验证见下方修复记录。以下 2026-09-12 清理重建内容属于较早构建记录。

修复验证时曾保留原正式战士会话的 `execution_unknown` 现场，没有直接读取其存档、内部诊断或强制恢复；该工具会话在随后清理重建前已确认退出。隔离测试不计通关。根因、改动、复现证据、测试命令和产物边界见[两次问题的修复与验证](cli-issues/2026-09-13-fixes-and-validation.md)。

## 2026-09-12 macOS清理重建

2026-09-12按用户要求，删除13个旧build目录、项目`.gradle`缓存和生成的`ios/robovm.properties`，共约975 MB，包含此前所有测试应用副本、存档、审计和报告。源码基于`b444e8e94`，使用以下明确限定的任务从空构建目录重新编译：

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test :desktop-control:packageMacArm64 --no-build-cache --rerun-tasks --console=plain
```

30个任务全部执行；374项JUnit通过（8+270+96），无失败或跳过。实际包的`--version`为CLI.1.0.0（协议1、游戏3.3.8），仓库外运行`--help`与495行英文Markdown逐字节一致；ARM64入口、内置JVM、plist和本地签名检查通过。本次不启动游戏，不创建新游戏存档；JUnit产生的12个账本故障夹具及24个SQLite文件已清理，生成的iOS配置也再次移除。

仓库只剩一份`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`，没有测试SQLite或旧runtime-images。当前生产内容未改动，构建ID仍为`04aa7e744fdb197b7623e0b74ed028a29ffbbf1ce1cac83a3f6f0a5a3bcbc742`；相同标识表示内容一致，不代表复用了旧产物。新清理清单、构建日志和验证结果位于`desktop-control/build/rebuild-validation/`。下方较早验证中的build原始路径均已清空，文字结论保留为历史记录。

## CLI.1.0.0 英文帮助补充

新增`docs/cli-help.md`作为`--help`唯一内容来源：495行英文手册，覆盖七个协议入口、22类动作、18个JSON请求示例、主菜单到开局、多步交互、持续取消、历史和保存退出，并附Python管道示例。构建时将原文打入JAR资源并计入构建标识，不依赖运行时工作目录或源码文件。

本批`:desktop-control:test`的96项测试通过，重新生成macOS ARM64应用。实际包在仓库外运行`--help`时，stdout与Markdown的24,027字节完全一致，stderr为空；`--version`保持CLI.1.0.0，未创建默认profile或审计库。包内资源、构建清单指纹和本地签名验证通过。当前构建ID为`04aa7e744fdb197b7623e0b74ed028a29ffbbf1ce1cac83a3f6f0a5a3bcbc742`，验证记录位于`desktop-control/build/help-validation/`。

本批只验证帮助入口和打包，不启动游戏窗口；下方较早的完整游戏包测试保留其原构建身份和范围。

## CLI.1.0.0 macOS 构建验证

2026-09-12使用原生ARM64 Temurin 25.0.4从clean重建；本次交付范围为macOS，未生成Android安装包或iOS IPA。初次根构建因无Android SDK停止后，排除Android完成构建和`:desktop-control:packageMacArm64`。该批生产版本提交为`2edd28393`，验证文档提交为`244cba999`。

- 应用：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`，包含普通GUI、`spdctl`和内部`spdctl-jvm`启动器，均为ARM64；内置JVM和SQLite JDBC 3.53.4.0。
- `--version`、`protocol.info`和包内`control-build.json`均为CLI.1.0.0；基础游戏3.3.8、协议1、审计schema 4。构建ID为`0b5333b87afabdcf9eefdef15b2141978f56dc128f115152b57c6ba9904c05b9`。
- JUnit共374项通过：协议8、游戏控制270、桌面控制96，零失败、零跳过；Python辅助工具20项通过。
- `package_english_smoke.py --expected-cli CLI.1.0.0`使用实际包和全新隔离profile通过：英文协议、中文窗口、原始帧和非法UTF-8、重复ID、目录锁、EOF、内置运行时、物品投掷取消、真实显示日志、保存回执和重启继续同一局。
- 两库完整性和`codesign --verify --deep --strict`通过。2,838个项目生产类均为Java 11字节码，包内没有测试类或注入代理。当前仅验证Apple Silicon本机，不将本地签名等同于Apple公证；原生CLI入口最低编译目标为macOS 12。
- 构建日志：`desktop-control/build/release-validation/CLI.1.0.0/`；本次包测试及审计：`desktop-control/build/package-check/english-8e9d06f1cd364aa8bf2d1614056b926f/`。这些是新构建数据，未进入Git；旧数据仍已删除。所有测试进程已退出，默认个人profile未使用。

本轮属于版本升级和包验证，不计正式战士通关，也不替代所有场景的专项验收。

## 开发测试产物清理

2026-09-12已完成旧产物和未纳入Git的运行/构建数据清理，包括旧build、战士存档、audit和策略轨迹；此前的保留决定已被后续清理指令取代。随后CLI.1.0.0重建产生的文件属于新产物，不恢复旧数据。源码和Git报告保留，历史原始build路径不可再读。详见[本地数据重置](cli-runtime-reset-20260912.md)；[较早清理记录](cli-test-cleanup-20260912.md)作为历史记录保留。CLI本身的默认审计保留策略未改变。

## 文档与测试基线

历史JSON报告已从docs移除，当前文档入口见[docs说明](README.md)。静态UI基线移至game-control/src/test/resources，活动场景计划改为Markdown；原验证结论保留在历史Markdown和Git历史中。

## 固定契约

- Java 11 源码，同 JVM GUI，stdin/stdout UTF-8 NDJSON；每请求一响应，无主动推送。
- 当前实机GUI使用简体中文、窗口化；公开CLI游戏文案使用英文。原始请求身份、路径与原始审计帧保持字节语义，不从内部数据补译玩家未知信息。
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
| P2 | 去重、身份、恢复、历史 | 去重、同局重启和真实旧档首次身份持久化已通过；更多游戏强杀边界继续验证 |
| P3 | 稳定完整快照与无损重建 | 声明范围/纯读/编码已验证；复杂长局与性能继续验证 |
| P4 | 主菜单至开局/选物/目标/取消/保存闭环 | 基础闭环、原生休息和连续移动取消通过 |
| P5 | 玩家知识与双世界测试 | 已有针对性双世界/副作用回归；持续补交互边界 |
| P6 | 通用玩法与复杂交互 | 新英文22个具名低频场景均有通过证据（原14+确认5+对白3），见 cli-p6-english-completion.md；容器、菜单、笔记、结局与更多物品/门交互仍按独立清单继续覆盖 |
| P7 | 六职业、十二子职、十九护甲能力与完整 UI | 新英文188个具名矩阵场景已有通过证据（原148+40补验），各构建分开记录，见 cli-p7-english-validation.md。六职业说明四页及嵌套详情通过；其余UI场景和组合继续验证 |
| P8 | 故障、保存、断流、包与性能 | 保存/恢复/强杀/未捕获异常/布局及性能各有分项证据；CLI.0.8.12及CLI.1.0.0的macOS包验收保留为历史记录。CLI.1.0.1补升已通过96项桌面测试、版本一致性、菜单握手/退出及包核验，范围见修复记录 |
| P9 | 战士纯 CLI 通关，并覆盖完整返程 | 0/1。CLI.1.0.0 实战曾因本页记录的问题暂停，后续使用 CLI.1.0.1；本次修复、升级与重建不计通关 |

## 覆盖门槛

交互清单涵盖所有场景、窗口（包括内嵌与匿名类型）、桌面背包、物品 actions、ActionIndicator、目标/选物/文本/滚动/数值控件；无法适配时明确停止，不能用鼠标兜底。
状态字段分类为公开、内部、构建常量、纯渲染/原生排除，新增字段需要审查。全快照保留引用、类型、集合顺序和实际 RNG 状态，不生成未来楼层或初始化尚不存在对象。

## 验收

1. 成功/失败/过期/重复/换档/重启 ID 测试，查询也占用 ID。
2. 采集不改变世界、RNG、ID 计数、鉴定、图鉴、窗口或存档；任意请求快照可无损重建。
3. 玩家视图与菜单相同、隐藏世界不同的双世界比较；公开输出、历史、错误均不得增加信息。
4. UI 原有回调与 CLI 语义动作对照消耗、结果和后续提示，覆盖取消与不足条件。
5. 执行前后事务、callback、stdout、游戏文件保存、新局身份各临界点崩溃注入；未知不重放。
6. 实际 ARM64 .app、真实管道、中文空格路径、独立目录与锁、背景焦点、布局变化、保存重启验证。自 2026-09-09 用户补充要求起，新实机批次保持窗口化并使用简体中文。
7. 战士真实通关并完整返程；六职业、十二子职及全部技能仍保留专项。夹具记录不计为真实通关。

每个已验证阶段更新中文 CHANGELOG 并独立提交。未获得明确指令不 push、不发布，不纳入用户原有无关修改。

## CLI.0.1.0 验证快照

- `:control-protocol:test` 5 项，`:game-control:test` 40 项，`:desktop-control:test` 47 项，总计 92 项通过。
- `:desktop-control:packageMacArm64` 成功；两个启动器和 libjvm 均为 ARM64，plist 与 codesign deep/strict 检查成功。
- `machine_smoke.py --launcher <包内 spdctl>` 已验证全新随机地牢的正常教程（允许高草挡住书时仅按公开地形探索）、物品窗口/投掷/取消、重复 ID、过期版本、保存退出、原 scope 恢复与历史查询。
- 最近成功的包内 smoke：`desktop-control/build/smoke/bd5db159-4fde-4730-9db4-592b3ba0734f`。这些是开发测试数据，未进入 Git，也不算通关。
- 原生休息取消说明见 `cli-runtime-cancellation.md`；当前没有宣称长路径移动中断已实现。
- 实现中的第一个必要协议澄清：pending 期间允许新 ID 取消明确绑定的当前持续活动，其余游戏变更仍 BUSY；详见取消契约。

## CLI.0.2.0 验证快照

- `:control-protocol:test` 8 项，`:game-control:test` 47 项，`:desktop-control:test` 65 项，共 120 项通过。已审查并更新欢迎页/交接边界对应的静态清单。
- 188 个能力矩阵、真实原生长按与同响应结算回归见 [P7 验证](cli-p7-validation.md)。三项真实保存/迁移场景、12 项生产账本 SIGKILL 及未覆盖部分见 [P8 故障验证](cli-p8-failure-validation.md)。
- 真实休息和长路径取消已通过；`travel-audit-fail` 证明取消意图无法保存时不会执行取消，不能报告成功。
- 最新完整包内游玩 smoke：`desktop-control/build/smoke/06341c6a-a983-4e1e-9c18-68420cf84a35`。随后多实例测试发现欢迎页删除空锁 inode，已修复并通过单测及重新打包的真实第二进程争锁测试。
- `package_smoke.py` 的最新结果为 `desktop-control/build/package-check/e53671ccbc6d4c1f943ec8861166ae6e/result.json`：复制后的中文应用路径、中文 profile、ARM64 三个原生可执行文件与 JVM、实际 SQLite、严格字节审计、多实例拒绝、EOF/末帧、诊断归档和签名全部通过。构建标识 `821137cb7de5a6e41281f4ad61434378b5446178180e5983b2a54f49a6f5fc51`。
- `spdctl` 是建立诊断路径的原生前置入口，`spdctl-jvm` 是包内实现用启动器；真正的游戏和控制器仍在同一个 JVM 内。没有加入网络服务或外部运行时依赖。
- 审计 schema 3 保留旧 schema 1/2 的历史；原先未记录的线缆分帧字节标记 `legacy-text`，不假称旧字节完整。原请求 ID 的唯一键及重复规则未改变。
- 以上记录是分项验收，不表示 P6–P9 的所有门槛已通过，尤其不把失败的正式战士尝试或夹具胜利算成通关。

## CLI.0.3.0 验证快照

- 全套 JUnit：协议 8 项、游戏控制 54 项、桌面控制 84 项，共 146 项通过。新增 LostInventory、退出前置条件、保存回执、EOF 重试、session/迁移和共享列一致性回归。
- [22 项低频交互](cli-p6-low-frequency.md)、[真实保存回执](cli-save-receipts.md)、[源码与包内布局](cli-layout-validation.md)、[性能基线](cli-performance-baseline.md) 分别记录实际范围及未覆盖组合。
- 原 11 个真实游戏精确强杀在 schema 4/CLI.0.3.0 下重跑 11/11，通过运行时 `runtime-0a46c1563b1a426eb1e16720bf1527c8`。它补充此前 schema 3 批次，不抹去各批次具体构建的区别。
- [Actor 未捕获异常](cli-uncaught-runtime-validation.md) 证明后台失败不会因渲染循环正常返回而记成正常 session。包内 `startup_failure_smoke.py` 另外在 session 建立后、GameController/MachineSession 创建前触发实际归档错误，退出码 1、零 stdout、两库 session=FAILED/launcher_failure；原记录位于 `desktop-control/build/fixtures/startup-failure-554d114cb61e49e9a9ce2f969ef73220`。
- 中文路径包验证位于 `desktop-control/build/package-check/03f01960bd8a4e02a470894ddaa3241c/result.json`，实际构建标识 `157aa415bda9a861500d4db4730235966739bc6b040b400e52855b3a7664c635`。同版本包完成 mode 2→0→1→2、scale 3→2→3、旧控件拒绝和新控件投掷取消/吃食物。
- 审计 schema 4 的新字段是控制和保存来源信息，不能据此声称所有内存、动画或窗口都已被游戏保存。旧历史保留缺失字段，较新 schema 拒绝打开写入，不降级。
- 正式 P9 控制仅使用公开 CLI 和公开历史；真实游戏中的失败和丢失资源会继续计入原局经历。当前 0/6，不使用夹具替代胜利。

## CLI.0.4.0 验证快照

- 用户最新范围：最终实战只要求战士一局纯 CLI 通关，原至少一次完整返程由该局覆盖；六职业功能与专项保留。当前 0/1。新实机统一简体中文和窗口化，正式游戏通过公开设置切换，不改写存档或测试状态。
- 新增 [已显示日志/视觉观察](cli-rendered-observation.md)、[红标与粘咕视觉证据](cli-visual-cues.md)。全套175项通过，随后暂停源 canProgress 防护追加2项定向通过；冻结 UI 重建的中文实机补充通过，但不声称旧版正常重建死锁已被复现。
- 中文窗口化实际 `.app` 完成完整基础 smoke，profile `desktop-control/build/smoke/ea041f64-32c9-4659-9e47-fdede7948b99`；真实后台投掷及普通GUI/CLI实例锁同样完成中文复跑，见 [后台验证](cli-background-validation.md)。
- 私有文件快照包含已存在的正常存档恢复 `.spdtmp` 文件；不调用加载或恢复，未跟随的已知游戏 symlink 标不完整。IEEE特殊值保留原始位型，避免 JSON 将负零或 NaN 细节丢失。
- 当前世界视觉源仍是红色目标标记与粘咕黑粒子，Tengu/召唤等继续分组完成；空数组不能当安全结论。测试进程和正式战士进程使用不同目录，临时多窗口测试结束立即清理。

## CLI.0.5.0 验证快照与当前工作

- 用户要求先停止正式实战，改为直接构造中间状态覆盖全部场景。正式战士原局已经成功保存和退出；当前工作只运行隔离 fixture，所有测试保持中文、窗口化，结束立即清理游戏进程。
- 全套 JUnit 为协议8、游戏控制91、桌面控制88，共187项通过。静态清单只增加一个粒子观察器类型及源码行号位移，没有新输入路线或方法内容被自动当作已验证。
- [待复活存档跨进程继续](cli-resurrection-resume.md) 保留修复前30秒仍在执行的真实证据与修复后完整原流程回归。
- [天狗炸弹](cli-tengu-bomb-validation.md)、[矮人国王召唤](cli-king-visual-validation.md) 分别覆盖实际已绘制预兆、隐藏、遮挡、历史与自然消退。此类测试从行为发生之前准备状态，再由原CLI动作触发；不是直接构造最终窗口来代替规则验收。
- 当前进一步建立场景/窗口到真实 fixture 的逐项映射，补齐跨层、支线、跌落、结束流程与其余首领视觉。已有188能力矩阵及低频测试保留其原构建和具体断言，不能把一组成功标签复制给未测试的分支。

## CLI.0.7.0：语言分离与当前优先级

- 按用户新增约定，GUI继续中文窗口化，CLI游戏文案和操作使用英文。正式实战维持停止，当前优先完成中间状态真实场景矩阵。
- [英文协议真实验证](cli-english-protocol-validation.md)记录两项严格通过：原菜单到游戏、库存操作、已画日志、正常保存重启，以及旧中文审计的英文呈现且原完整行/块不变。完整Gradle套件通过；显示翻译36项、语言8项、公开上下文5项、审计事务1项、语料探针6项新增回归均有独立证据。
- [固定公开语料回归](cli-english-corpus.md)对同238个关闭fixture、5874条历史公开响应完成0拒绝/0partial复查，没有启动引擎、读取存档或使用内部快照，也不替代当前引擎逐场景测试。
- [场景清单](cli-scenario-coverage.md)继续保留未覆盖分支。尚未宣称全部自定义文字或所有资源歧义都已解决；仍未知的完整文案返回明确错误，用户原始数据不假称为本地化资源。
