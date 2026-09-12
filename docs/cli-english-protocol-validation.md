# 中文 GUI 与英文 CLI 的独立验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09，第五轮两个独立实机用例完整通过。GUI 始终为中文、窗口化；公开游戏文案为英文；同一局正常重启后原响应和已显示日志仍可用英文读取。真实旧中文审计副本的英文查询没有回写旧记录。前四轮发现的问题保留在下文，旧中文实测记录没有改写为英文证据。

本次冻结运行时为 `runtime-f3db7a7224104683bcd95fea61bdfd61`，公开 build ID 为 `299fbe9a52df08b8a61b4ebbf8b603621ee61b4e4014cecfa92e0e979a947a86`。逐项证据见 cli-english-protocol-validation.json（历史 JSON 已删除，可查 Git 历史）。

| 用例 | 完整结果 |
| --- | --- |
| `language.english_protocol_chinese_gui` | 原 Welcome → 原 Back 明确访问 Title → 选角 → 游戏 → 原库存详情 → THROW 后取消 → EAT；原已绘制日志含 `That food tasted delicious!`；state/actions/history/request.get 游戏文案为英文；正常保存和退出后继续同一 scope，原响应及事件仍英文。私有 GUI 记录为 `CHI_SMPL`、非全屏，并覆盖新 JVM 的实际当前版本。 |
| `language.legacy_chinese_audit_presentation` | 对真实旧中文菜单测试基线的锁定副本检查 8 个历史请求、120 个游戏文案字段；完整地牢导言精确匹配原英文资源；字段、数组、ID、数字、空值、raw_request 均按既定边界保留；public/internal 两库原 requests/exchanges/events 全列及 snapshots/snapshot_blobs 字节不变。 |

通过的 live profile 为 `desktop-control/build/fixtures/english-protocol-7c11f2b46dfa45d4ae6131f1d0592333`；旧记录副本为 `desktop-control/build/fixtures/english-migration-c4b2d3421f9a4624af26870824b060c9`。这些均为人为准备的测试 profile，不计正式通关。测试进程均已正常退出；没有使用截图、键盘、鼠标或 computer use。

## 首轮失败记录

隔离 profile `desktop-control/build/fixtures/english-protocol-8162504f68644816814acb5ae2fd8489` 中，Welcome 到选角的英文公开观察成功。选择战士后，实际 GUI 的“开始”文本没有可确定的英文映射，内部记录 `PublicTextUnavailableException` 与 `no_safe_resource_translation`，原操作公开返回 `EXECUTION_UNKNOWN`。未放宽断言；进程已通过 EOF 清理退出。该 profile 的 `english-protocol-failure.json` 保留精简失败原因，原 trace 和内部异常仍保留。

同时修正两处测试胶水：Welcome 的原按钮直接进入选角，因此测试改为显式通过原 Back 访问 Title，避免把不存在的导航阶段算作覆盖；UNKNOWN 后的失败清理改走 EOF，不再尝试新 mutation，也不让退出拒绝掩盖最初失败。这些修正不改变生产代码或英文验收条件。

第二轮隔离 profile `desktop-control/build/fixtures/english-protocol-39db53a3773f4eb0b43639787cbd0d84` 在原 Back 进入 TitleScene 时发现“游戏新闻”歧义：标题按钮资源的英文是 `News`，新闻场景标题的英文是 `Game News`。严格投影拒绝后原操作为 `EXECUTION_UNKNOWN`；进程已通过 EOF 关闭。此轮没有跳过标题页，也没有把字段删除、空文本或任意同词译文当作成功。

第三轮隔离 profile `desktop-control/build/fixtures/english-protocol-bcc2f959db914cd09376672e431fae8f` 已通过 Welcome、Title、选角、游戏以及严格英文名称与原短剑详情检查。在公开库存操作打开 `throwing stone` 详情时，含原已显示阶数、伤害、力量需求和耐久次数的组合段落尚不能安全翻译，严格投影再次拒绝。完整片段保存在该测试的内部异常中，进程已通过 EOF 关闭。未改用别的物品绕过投石描述，也未从隐藏物品数据重建译文。

第四轮隔离 profile `desktop-control/build/fixtures/english-protocol-f8d72fbabb85477db676907468cb27f5` 已完成首个 JVM 的菜单、严格名称与描述、投掷取消、原食物英文显示事件、history/request.get 和中文窗口化 GUI 检查，并正常保存退出。重启后公开存档列表已为英文，但点击该列表行打开详情时，“删除”发生 `Erase` 与笔记 `Delete` 的资源歧义，原操作被拒绝；第二个 JVM 已通过 EOF 关闭。因此整项同 scope 重启验证仍未通过。

## 验收内容

1. 在全新隔离 profile 中，使用实际 FixtureLauncher 与原公开控件经过 Welcome、Title、英雄选择和游戏场景。
2. 私有 `ui-assertions.jsonl` 必须记录 GUI 始终为 `CHI_SMPL`，且 `fullscreen=false`；实际出现 `WndUseItem`。这些信息仅作操作完成后的断言，不用于选择游戏操作。
3. `state.get`、`actions.list` 以及各操作响应中的游戏文案字段必须为英文。严格核对 `Warrior`、`worn shortsword`、`cloth armor`，并核对原短剑描述中的 `quite short sword`，不能靠删掉中文字段通过。
4. 通过原库存操作打开投石，选择原 `THROW`，再 `cell.cancel`；数量保持不变。再通过原 `EAT` 消耗初始口粮。
5. 原食物行为实际绘制的 `game.log` 显示快照应包含 `That food tasted delicious!`。测试不注入日志文本，不用控制台输出充当游戏消息。
6. `history.list` 能找到食物操作；`request.get` 的原响应保持英文且与当时响应一致。正常保存、退出并重启后，继续同一 scope，原响应和原显示事件仍可用英文读取。

游戏文案检查按字段递归，包括 `name`、`class_name`、`subclass_name`、`label`、`text`、`description`、`prompt`、`cell_prompt`、`item_prompt`、`options`、`message`、`title`、`hint`、`tooltip`、`disabled_reason`。同时拒绝文案中的非拉丁字母，保留英文可用的标点、符号和带重音拉丁字母。UUID、文件路径及 `raw_request` 等原始数据不作为游戏文案翻译目标。关于玩家输入的中文自定义内容，应遵循用户后续确定的规则；本测试不预先把这些内容强制改写为英文。

## 真实旧审计记录

使用已正常关闭的隔离中文基线 `desktop-control/build/fixtures/menu-scenes-d6a2a70396224dbd80ec09e5fb0cc0d1`，复制到新的 `english-migration-*` profile。复制期间持有与 Java FileChannel 互通的原 profile 排他记录锁；不复制实例锁文件本身，数据库与游戏数据原样复制。测试不会手工构造或修改 SQL 行，也不读取正式游戏 profile。

- 从真实旧记录选择含有已知中文游戏文案的历史请求，用新 ID 调用 `request.get`。
- 返回中的文案应为英文，且保留原对象字段、数组长度和非空文本。`进入地牢`、`继续`、`关于` 等分别严格匹配原英文资源的 `Enter the Dungeon`、`Continue`、`About`。ID、数字、空值和原始请求必须保持原值。
- 未裁剪的完整原文不能被 `Partially displayed text` 等占位内容替代；另要求真实旧 Journal 的完整地牢导言精确等于对应原英文资源，避免仅靠“没有中文”或“存在英文字母”假通过。
- 同一历史 scope 的 `history.list` 与 `events.read` 也接受英文游戏文案检查。
- 查询并正常关闭后，按主键核对 public/internal 两库原有 requests、exchanges、events 的全部列，以及 snapshots 元数据与 snapshot_blobs 原始字节。原始请求、响应、状态、时间和输出回执都不能改写。新增查询可以追加审计行，因此不比较整个数据库文件的字节。

旧菜单 profile 用于历史界面的呈现验证；原生游戏日志的英文路径由前述食物用例及同 scope 重启覆盖。本测试没有声称旧菜单 profile 含有未经检查的战斗日志。

## 运行方式

复跑命令：

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/english_protocol_smoke.py
```

脚本输出两个独立 case：`language.english_protocol_chinese_gui` 与 `language.legacy_chinese_audit_presentation`。通过报告只在完整断言完成后写入；失败时记录已经通过的阶段并清理隔离进程。第五轮已完成两个 case 的全部断言。此结论限于列出的场景和真实历史样本，不将其扩写为全部窗口、装备、日志模板或任意用户自定义内容都已实机验证。
