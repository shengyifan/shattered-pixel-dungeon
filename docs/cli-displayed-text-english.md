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

完整外层游戏高亮包装 `_..._` / `**...**` 可以在完整资源和模板匹配之后递归处理：只翻译实际提供的完整 inner，再把原标记包回。例如 `_第1层_` → `_Floor 1_`，数字直接来自可见字串。未闭合的动态模板、未知 inner 或未知尾文不会被补全；裁剪双世界测试不会因未显示的后半段不同而产生不同答案。已有完整词的普通组合仍可原样保留开放标记，例如 `_战士` → `_warrior`，绝不制造一个原输入没有的 closing marker。

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
| 当前 node、公开 parent 链或 action.control 所指行具有 `binding_slots:[1,2,3]` | 键位行的完整名称或完整多行文本，例如 `选择快捷栏\nNone\nNone\nNone` | `key_binding=true` 时只在 `windows.wndkeybindings.*` 中查唯一完整资源单元，得到 Quickslot Selector；不把普通 Toolbar 文案归为键位行 |
| 同一公开 UI 同时存在至少一个精确三槽绑定行、Action/行动、Key 1/按键1、Key 2/按键2、Key 3/按键3 表头和 Default Bindings/恢复默认键位按钮 | 键位主面板文案，包括完整“确定” | `key_binding_panel=true`，仍限定 `windows.wndkeybindings.*` 完整资源域，得到 Confirm |
| 当前公开 UI 中有 `binding_input=true` 节点，且当前 node 或公开 parent 链是 button | 键位编辑按钮的完整文字 | `key_binding_input` + `button`，只用 `windows.wndkeybindings$wndchangebinding.*`；无按键 → Unbind Key |
| 同一编辑输入标记存在，文本完整匹配键位编辑子域的已显示模板 | 当前键位、按下某键以修改某动作等说明 | 外层只用编辑子域，已显示参数只用主 `windows.wndkeybindings.*`，所以 Current binding 的无按键参数 → None |
| GameScene + modal，标准 Note 标题、Confirm/Cancel 及匹配的公开输入规格全部存在 | 自定义 Note 输入文案 | `custom_note_input=true`，只限定 `ui.customnotebutton$customnotewindow.*` |
| GameScene + modal，同时有 Edit Title、Add Text 或 Edit Text、Delete 三类按钮 | 当前自定义 Note 视图文案 | `custom_note_view=true`，同 Note 资源域将删除译为 Delete |
| GameScene + modal，完整原生删除 Note 问题及 Confirm/Cancel 同时显示 | Note 删除确认文案 | `custom_note_delete=true`，同 Note 资源域将确定译为 Confirm |

`save_details` 的实际公开签名是 StartScene + modal，同时出现 Continue/继续、Erase/删除按钮，以及 Strength/力量、Health/生命、Gold Collected/金币收集数、Maximum Depth/最高层数标签；不存在此前假设的 Info/Enter 按钮。`game_menu` 要求 GameScene + modal 且同时出现 Settings/设置和 Main Menu/主菜单。`chasm_prompt` 要求 GameScene + modal，且实际显示完整原生跳崖确认问题或其对应英文；不从近似问题或隐藏 Window 类推断。

每项策略都验证 false、缺 scene、错误 scene 或缺少相应公开标记时不能获得该策略的译文。隐藏对象不同但可见 context 相同仍返回相同结果。context 本身不自动批准任意相邻文案；完整未知尾部仍拒绝，裁剪后的不完整单字仍保留 partial 行为。

`binding_slots` 是现有 `ui.binding_slot.slots` 已公布的同一个三槽事实，不公开 Java 类名或隐藏键位。关闭或不可操作的行仍可描述其槽位，但不会因此新增可执行动作。上下文只沿已公开关联传播；缺失 parent、没有该 node 的 action.control、槽位为字符串或不是精确的 1/2/3，都不能获得键位语境。过去响应没有这些公开关联时不会借用后来响应的 UI 补上。

主面板签名中的每个要素都不可缺少；只有“确定”按钮或隐藏的 Window 类名不够。键位编辑子窗不会从旧主面板自动沿用上下文，只有当前可见输入节点发布 `binding_input=true` 时，才采用上表的编辑规则。这个标记与原 `ui.binding_key` 是同一事实，不导出 Java 类型；停用输入仍不会因此新增可执行动作。

“无按键”在当前键位说明中是 None，在可点击的解除绑定按钮上是 Unbind Key，不能整窗统一替换。button 布尔仅来自已公开 role 链；孤立且非 button 的同词仍拒绝。子域完整模板的参数必须是已经显示的主键位域名称或现成英文键名，不能因为全局其他资源能译一个词就擅自把它当作动作/键名。未知参数、尾文和被裁一半的模板继续拒绝或返回 partial，其他任意 `$hidden` 子域没有开放。

Note 标题输入限 50 字符、单行，并要求完整标题匹配 New Text Note、New Dungeon Floor Note、New Inventory Item Note、New Item Type Note 或 Edit Title 的中英文资源；正文输入限 500 字符、多行，并要求 Add Text 或 Edit Text。两类规格不能交换。签名不会读取 `text_input.value` 中的用户标题来猜窗口类型。删除确认问题必须完整等于 `Are you sure you want to delete this custom note?` 或原中文“你确定要删除这个备注吗？”。缺少按钮、输入、标准标题、对应场景或 modal 标志时不获得此消歧。

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
| 独立单测 | 46 项通过 |

6 个带参拒绝来自三组真实资源碰撞：`神圣%s` 对应 `%s of light` / `holy %s`；`死于：%s` 对应 `Slain by: %s` / `Killed by: %s`；矛的实际能力说明和典型能力说明具有完全相同中文，英文却有无 `typically` 的差别。这些不能通过读取隐藏模型来区分。模板样本没有穷举所有真实参数组合，不能据此宣称整个游戏所有显示文本都已覆盖。

实机投石详情发现的同段拼接缺口已作为单独回归：输入仅是已经显示的完整中文，包含 1 阶、2–5 伤害、9 力量、0–1 额外伤害和 5/5 剩余次数；转换结果精确对应资源中各句的英文组合。测试改变输入为 4/5 时输出只随可见输入变化，没有 Item 或 Hero 对象参与。含未知后半句的完整输入仍拒绝，被裁输入仍使用明确的 partial 占位。

Supporter 页的 intro、跨多行 Patreon 说明、以括号开头的英文回报提示及 `- Evan` 签名，也用原已显示组合文本进行了精确回归。584 模板样本的允许拒绝项现在锁定为上述六个资源 key；未预期的新拒绝会使测试失败，不能在“多数模板通过”的统计中被掩盖。

键位、完整高亮包装和 Note 公开签名补充同时通过 15 项 `PublicEnglishProjectionTest` 和 12 项 `UiBridgeTest`，与 46 项转换器测试合计 73 项定向检查；真实操作由单独的 settings / notes 场景报告验收，不把这些单测计为实机通过。

目标选择的已画文案可在同一公开 UI 的 `cell_input=true` 且 `modal=false` 时，用已有英文 `cell_prompt` 消除完全资源匹配的歧义。该英文必须是已显示中文资源或完整模板的确切候选；旧 prompt、中文 prompt、缺失标志、局部前缀和未知尾文均不能提供上下文。8 项 `PublicCellPromptEnglishTest` 检查该限制；连同物品知识时点专项，当前相关定向测试为 92 项。

全部无上下文歧义来源列在 [cli-displayed-text-english.json](cli-displayed-text-english.json)，原始 55 组及原英文候选保持不变，另列规范化支持项，不会因少数公开 context 或规范化已可处理就把整个目录标为通过。`ambiguous_chinese_strings=55` 仍表示原始候选差异；新增 `normalized_ambiguous_strings=3` 表示受上述规则支持的子集。通用 `button` 角色不能解决所有碰撞；实际关闭窗口的 `Close` 仍需要足够公开证据，不能因为它不是 slider 就反向猜测。`暴雨` 的 terrain 表现与 Combo 动作等也需要已公开且足以区分的上下文。`神圣%s` 没有放宽。矛说明现在只在当前公开窗口提供同正文知识时点的 `ui.inspected_item.level_known` 时可区分 actual / typical；缺少该关联的历史文案仍拒绝，详见 [物品正文知识验证](cli-inspected-item-validation.md)。新增 11 项知识时点专项与原 73 项定向检查共 84 项通过，原 55 组歧义及无上下文模板样本统计不变。尚未接入的上下文不会被假定存在。

默认构造器通过 static Holder 对每个 classloader 只建一次不可变索引；一次隔离测试测得完整资源加载与建索引约 54 毫秒，这不是平台性能承诺。完整字串查询使用预计算结果；模板正则和资源前缀也预先构造。查询仅有局部临时 memo，不维护跨请求的“最后对象”或“最后译文”缓存，不打开额外文件。

这份索引应归类为 `immutable_software_resource_cache`：它完全由软件版本中的资源、公开语言枚举和代码内审定规范化策略决定，不是存档或局内模型状态。资源及代码版本由现有 BuildCatalog 目录记录。构造应在 CLI bootstrap 的正常资源准备阶段完成，之后复用；完整诊断若记录该缓存，可以引用对应软件/资源目录，不能将其误当作玩家已经获知的物品数据库向外导出。

默认加载仅使用 classloader 包内资源，不回退到 cwd、Gdx 文件接口或用户目录。独立 game-control 测试需要显式加入 `core/src/main/assets` 到测试 classpath，或使用 `fromClassLoader` 注入测试资源加载器；生产包使用已有资产目录。

本类的独立验证没有替代 GUI 中文、CLI 英文的真实协议验收。UiBridge、公开事件、操作响应和审计的集成由协议投影层完成；正式实机覆盖以其单独报告为准。

## 完整显示单元的拼接

当原UI用空格拼接多条资源时，翻译先对完整资源或模板尝试边界，再完整翻译剩余已显示文字。多句模板保持整体，所有分解结果必须唯一，未知尾部和歧义都会拒绝；原数字与可见分隔符保留。7项正反例和WARDEN、Shockwave两项原生施放专项通过，结果保存在 `desktop-control/build/fixtures/runtime-96551553ca2a4d1fb60019fb00082495/results.json`。

Hero Info专精标题和胜利排行过去时分别受完整公开页签说明、非模态排行按钮完整三行显示签名约束。4项边界测试覆盖缺失场景、窗口、父链、尾文和历史上下文；不读取隐藏职业或rec.win。六职业4页与原胜利窗口完整流程的证据分别见 `cli-hero-info-validation.json`、`cli-ending-amulet-validation.json`。

## 招式列表与物品菜单

角斗士与武僧的原列表把完整招式名、完整消耗模板及完整说明组合为一行。转换器仅在三者配对且结果唯一时输出对应原英文；不能用裸露“暴雨”等名称猜测上下文。暗影斗篷和匕首系菜单仅在当前inspected_item所指公开窗口、完整物品标题、原DROP/THROW/EQUIP或UNEQUIP按钮齐全时区分STEALTH和sneak，并限制到该根窗口内的按钮父链。

两组新增10项正反例通过，相关定向合计113项。真实盗贼、角斗士、武僧、匕首4项均通过，82次GUI后置核验；详见 `cli-p7-english-remediation.json`。原188项冻结批次的失败保留，补验与原报告分开。

## 更多当前菜单与完整换行组合

拳套系combo strike使用当前已知物品完整标题与原菜单签名；Shadow Clone还要求原职业护甲说明和完整已显示技能/充能段。升级Back与未知卷轴取消Yes/No分别要求原完整提示和按钮组合。这些上下文不引用隐藏装备、AI或鉴定状态。

组合解析支持原本已显示的空白分隔符，完整跨段资源仍作为一个候选单位，修复Shockwave详情末尾追加充能和Trinity的实际显示组合；三项跨段及一项Trinity样本测试保留未知尾文/前缀拒绝。该组连七项菜单测试及现有测试共124项通过。五项新的实机补验见 `cli-p7-english-remediation.json`，共113次GUI核验。

## 静态正文与窗口确认

同中文的能力或天赋名称，只能由当前模态窗口中完整显示且可安全翻译的静态full.desc配对消歧；所有候选必须唯一，不能由旧按钮、隐藏对象或任意短说明推断。平衡符石按完整问题/取消/两项当前属性按钮确定窗口资源。独立PublicDialogSignatures只接收公开DTO树，严格检查当前完整两按钮对话，分别区分偷窃、复活与奖励预览；不为未知物品正文提供兜底。20项新增边界测试与职业详情、符石和确认窗口各组实际结果见相应validation文档。

## 已显示战斗浮字

仅对已存在FloatingText，且原纯visibleCueText返回的完整可见串与当前节点text完全一致时，UI增加presentation=floating_text。英文投影只凭该公开标记把完整“闪避”译为dodged、完整“夺命印记”反馈译为marked for death；菜单death mark与属性Evasion仍各用原窗口上下文。缺标记、普通文本、不可见或模态覆盖、截断与未知尾文均不能借用此规则。

该逻辑不调用Hero.defenseVerb或任何buff getter，避免原格挡/随机分支的副作用。6项独立回归及实际墓碑战斗、DeathMark施放通过。P7的188项完整证据索引与原失败批次见 `cli-p7-english-validation.md/json`；容器10项分支见 `cli-containers.md/json`。

## NPC已显示引号对白

Mob.yell的公开说话者与引号正文分别按完整资源/模板翻译，并保留显示的标点、实际参数和尾空格；支持两个已经完整显示的招呼相连。没有可安全翻译的说话者、正文、尾文或裁剪片段均明确拒绝。6项边界回归和制杖匠告别/幽灵问候3项实际流程通过；完整22项低频成功索引见 `cli-p6-english-completion.md/json`，原14/8与确认5/6报告仍保留。

## 首次支持提示

WndSupportPrompt的标题、intro、Patreon说明、中文GUI追加的英文奖励提示及署名，按包内原资源完整组合匹配，并要求同一公开根窗口与原两按钮。只有真实Close按钮/其子文字可使用wndsupportprompt.close；无关文本、Patreon按钮、未知尾部或clipped节点不能借用。30项相关定向测试及原WornKey触发/Back保护/Close/开锁完整流程通过，未使用supportNagged跳过弹窗。
