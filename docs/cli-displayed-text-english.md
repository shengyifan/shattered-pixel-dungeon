# 已显示文字的确定性英文投影

`DisplayedTextEnglish` 接收调用方已经选出的可见字符串，只读取包内九组中文/英文 message resources、`Languages` 的公开语言名称，以及下列审定的保守显示词汇。它不读取 Hero、Item、Trap、窗口实例或其他游戏对象，不调用 `Messages.get`、`Messages.setup`，也不切换语言、重建窗口、测量字体或重绘英文文字。

## 接口和失败行为

- `translate(String)`：返回英文；原本的英文保持原样，已知语言标签如 `français`、`русский`、`한국어` 先按公开枚举转换为 `French`、`Russian`、`Korean`。
- `translateVisible(String, boolean clipped)`：对传入片段独立翻译。若该片段无法安全翻译且 `clipped=true`，返回 `VisibleText{text="Partially displayed text", partial=true}`。不会查找片段所在整句并输出未显示的英文尾部。
- `translateInScene(String, String publicScene)`、`translateVisibleInScene(String, boolean, String)`：只在调用方已经公开的 scene 名称或已发布 alias 对应的 `scenes.<scope>.*` 资源范围里，查找完整相同的中文与唯一英文。范围内不唯一、未知 scene 或没有完整匹配时，继续采用严格普通规则。
- `translateInContext(String, Map<String,Object>)`、`translateVisibleInContext(String, boolean, Map<String,Object>)`：接受协议层从已公开 DTO 构造的有限上下文。没有访问实际控件或模型的后门；未知 context 字段被忽略。

完整未知中文或无法消歧的文字抛出 `PublicTextUnavailableException`，`code=PUBLIC_TEXT_UNAVAILABLE`，公开异常 message 固定英文。原字符串和具体诊断原因只通过 `diagnosticOriginalText()` / `diagnosticReason()` 提供给内部诊断；调用方不得把这些值拼入公开错误。未知的其他非拉丁文字当前也严格拒绝，不猜测译者、开发者或玩家名字。用户原文的字段分类和保留策略由协议层单独确定，本类不会从字形猜测名字。

## 匹配规则

完整资源字串优先。相同中文对应的英文仅有大小写差异时，可以确定性归为同一词形；除下列两种已审定规范化外，具有不同词义、词形或限定词时保留歧义，不能因最后访问了某个对象而任选一个译文。

1. **单个可选终止句点。** 多个英文候选仅在正常词或数字后的单个最终 `.` 上不同时，采用无句点形式。实际支持“选择要释放魔法的位置”→`Choose a location to zap`，以及同样符合规则的“我将稍后决定”→`I'll decide later`。不会合并省略号、问号、其他混合标点或尾空白，也不会改写本来就是英文的输入。只有一个英文候选时仍保留其原句点。
2. **显式保守显示词汇。** 完整中文“凝神”统一显示为 `Focus`。这忠实于中文当前提供的词义和区分精度，不猜它是 Monk ability、玩家 buff 还是怪物 buff；不同公开 scene、role 或隐藏对象也不改变结果。它是规范化后的英文显示词，不能宣称原来的英文资源或实际 getter 本来就统一为这个字符串。已有纯英文 getter 输出的 `focused` 保持不变。

规范化只能处理完整可见单元，不能吞掉未知尾文；裁掉一部分的“凝”或“选择位”仍返回 partial 占位，不补全未显示部分。

格式模板只提取当前字符串已经显示的参数，再递归翻译参数。支持本资源集实际使用的 `%s`、`%d`、显式参数序号、`%,d`、`%.2f`、`%1$.0f` / `%2$.0f` 和 `%%`。输出使用已显示的数值字面量，不通过解析隐藏数值重新取整或补充精度。源和目标参数集合必须相同，重复位置参数的已显示值必须一致。普通文字中的 `25% more` 不会被误判为 `%m` 之类的格式指令。

拼接的段落、标题、数量和物品名称可以按完整资源单元组合，包括本身以括号、markup 或 ASCII 前缀开始的完整资源。完整资源与模板匹配失败后，同段中以实际中文句末标点分隔的完整句子也可分别匹配，原有空白保持不变；模板自带的前导换行会先完整匹配，不被提前 trim。无法解释的中文后缀不会被删除。可见输入长度、递归层数、组合段数和工作量均有界，超限同样返回稳定的不可用错误。

公开场景消歧覆盖当前公开 scene 类名白名单和 `GameSnapshotter.sceneName` 已有别名，不接受任意内部对象类名。例子：

| 相同可见中文 | 已公开上下文 | 结果 |
| --- | --- | --- |
| 开始 | `HeroSelectScene` / `hero_select` | Start |
| 开始 | `TitleScene` / `title` | Play |
| 游戏新闻 | `TitleScene` / `title` | News |
| 游戏新闻 | `NewsScene` | Game News |
| 指南 | `GameScene` / `game` | Guidebook |
| 指南 | `AlchemyScene` / `alchemy` | Guide |

相同可见字符串和相同公开 scene 的结果，与隐藏对象种类及之前翻译过哪个场景无关。公开 scene 内部仍有多个不同候选时也拒绝；只匹配前缀不能补全未显示的尾文。

“指南”的 GameScene 分支是一项窄的公开场景例外：只对完整同词查 `items.journal.guidebook.hint_status`。原资源表只有这项与独立 AlchemyScene 的 `scenes.alchemyscene.guide` 两个来源。该规则不检查是否持有指南、不读取物品对象，也不因后来进入过炼金场景而改变 GameScene 的译文；未知 scene、隐藏 scene 提示或多出来的尾文仍不提供此消歧。

其他获准的上下文规则如下。布尔签名由协议层从现有公开字段构造；本类只消费它们并再次检查要求的 scene，不读取窗口类、存档对象或模型类型。

| 公开证据 | 完整可见中文 | 对应英文资源 |
| --- | --- | --- |
| `shortcut_action=back` | 返回 | `windows.wndkeybindings.back` → Back |
| `slider=true` 或 `checkbox=true`，源自公开 slider role/父关系或 checked 字段存在 | 关闭；也支持完整多行 slider label 中的此行 | `windows.wndsettings$displaytab.off` → Off |
| StartScene 且 `save_details=true` | 删除 | `windows.wndgameinprogress.erase` → Erase |
| GameScene 且 `game_menu=true` | 设置 | `windows.wndgame.settings` → Settings |
| GameScene 且 `chasm_prompt=true` | 不，我改主意了 | `levels.features.chasm.no` → No, I changed my mind |
| JournalScene 且完整 heading 严格匹配 `_名称_ (数字/数字)`，可带末尾冒号 | 装备、投掷武器、法杖、饰物等 | 只搜索 `journal.catalog.*.title` 与 `windows.wndjournal$catalogtab.title_*`，保留已经显示的计数和格式 |

`save_details` 的实际公开签名是 StartScene + modal，同时出现 Continue/继续、Erase/删除按钮，以及 Strength/力量、Health/生命、Gold Collected/金币收集数、Maximum Depth/最高层数标签；不存在此前假设的 Info/Enter 按钮。`game_menu` 要求 GameScene + modal 且同时出现 Settings/设置和 Main Menu/主菜单。`chasm_prompt` 要求 GameScene + modal，且实际显示完整原生跳崖确认问题或其对应英文；不从近似问题或隐藏 Window 类推断。

每项策略都验证 false、缺 scene、错误 scene 或缺少相应公开标记时不能获得该策略的译文。隐藏对象不同但可见 context 相同仍返回相同结果。context 本身不自动批准任意相邻文案；完整未知尾部仍拒绝，裁剪后的不完整单字仍保留 partial 行为。

## 覆盖与缓存

实际 Java Properties 读取并逐项测试的结果：

| 项目 | 结果 |
| --- | --- |
| 含中文的可配对资源条目 | 4,817 |
| 不同中文完整字符串 | 4,413 |
| 无歧义完整字符串 | 4,358，逐条返回对应英文 |
| 原始有歧义的完整字符串 | 55，原候选目录不变；其中 3 个可用上述 2 种规范化支持，剩余 52 个在无公开消歧上下文时仍拒绝；另 18 组仅英文大小写不同已合并 |
| 格式模板 | 584 对全部成功解析，参数集合没有缺项 |
| 实际带参模板样本 | 584 个，每模板使用一组确定性的可见名称/数值；578 个逐字匹配原英文 `String.format`，6 个安全拒绝 |
| 公开语言名称 | 23 种全部测试，含中文以外脚本和 Latin 名称 |
| 独立单测 | 37 项通过 |

6 个带参拒绝来自三组真实资源碰撞：`神圣%s` 对应 `%s of light` / `holy %s`；`死于：%s` 对应 `Slain by: %s` / `Killed by: %s`；矛的实际能力说明和典型能力说明具有完全相同中文，英文却有无 `typically` 的差别。这些不能通过读取隐藏模型来区分。模板样本没有穷举所有真实参数组合，不能据此宣称整个游戏所有显示文本都已覆盖。

实机投石详情发现的同段拼接缺口已作为单独回归：输入仅是已经显示的完整中文，包含 1 阶、2–5 伤害、9 力量、0–1 额外伤害和 5/5 剩余次数；转换结果精确对应资源中各句的英文组合。测试改变输入为 4/5 时输出只随可见输入变化，没有 Item 或 Hero 对象参与。含未知后半句的完整输入仍拒绝，被裁输入仍使用明确的 partial 占位。

Supporter 页的 intro、跨多行 Patreon 说明、以括号开头的英文回报提示及 `- Evan` 签名，也用原已显示组合文本进行了精确回归。584 模板样本的允许拒绝项现在锁定为上述六个资源 key；未预期的新拒绝会使测试失败，不能在“多数模板通过”的统计中被掩盖。

全部无上下文歧义来源列在 [cli-displayed-text-english.json](cli-displayed-text-english.json)，原始 55 组及原英文候选保持不变，另列规范化支持项，不会因少数公开 context 或规范化已可处理就把整个目录标为通过。`ambiguous_chinese_strings=55` 仍表示原始候选差异；新增 `normalized_ambiguous_strings=3` 表示受上述规则支持的子集。通用 `button` 角色不能解决所有碰撞；实际关闭窗口的 `Close` 仍需要足够公开证据，不能因为它不是 slider 就反向猜测。`暴雨` 的 terrain 表现与 Combo 动作等也需要已公开且足以区分的上下文。`神圣%s` 和矛说明中 `typically` 的差异没有放宽。尚未接入的上下文不会被假定存在。

默认构造器通过 static Holder 对每个 classloader 只建一次不可变索引；一次隔离测试测得完整资源加载与建索引约 54 毫秒，这不是平台性能承诺。完整字串查询使用预计算结果；模板正则和资源前缀也预先构造。查询仅有局部临时 memo，不维护跨请求的“最后对象”或“最后译文”缓存，不打开额外文件。

这份索引应归类为 `immutable_software_resource_cache`：它完全由软件版本中的资源、公开语言枚举和代码内审定规范化策略决定，不是存档或局内模型状态。资源及代码版本由现有 BuildCatalog 目录记录。构造应在 CLI bootstrap 的正常资源准备阶段完成，之后复用；完整诊断若记录该缓存，可以引用对应软件/资源目录，不能将其误当作玩家已经获知的物品数据库向外导出。

默认加载仅使用 classloader 包内资源，不回退到 cwd、Gdx 文件接口或用户目录。独立 game-control 测试需要显式加入 `core/src/main/assets` 到测试 classpath，或使用 `fromClassLoader` 注入测试资源加载器；生产包使用已有资产目录。

本类的独立验证没有替代 GUI 中文、CLI 英文的真实协议验收。UiBridge、公开事件、操作响应和审计的集成由协议投影层完成；正式实机覆盖以其单独报告为准。
