# 已显示文字的确定性英文投影

`DisplayedTextEnglish` 接收调用方已经选出的可见字符串，只读取包内九组中文/英文 message resources 和 `Languages` 的公开语言名称。它不读取 Hero、Item、Trap、窗口实例或其他游戏对象，不调用 `Messages.get`、`Messages.setup`，也不切换语言、重建窗口、测量字体或重绘英文文字。

## 接口和失败行为

- `translate(String)`：返回英文；原本的英文保持原样，已知语言标签如 `français`、`русский`、`한국어` 先按公开枚举转换为 `French`、`Russian`、`Korean`。
- `translateVisible(String, boolean clipped)`：对传入片段独立翻译。若该片段无法安全翻译且 `clipped=true`，返回 `VisibleText{text="Partially displayed text", partial=true}`。不会查找片段所在整句并输出未显示的英文尾部。
- `translateInScene(String, String publicScene)`、`translateVisibleInScene(String, boolean, String)`：只在调用方已经公开的 scene 名称或已发布 alias 对应的 `scenes.<scope>.*` 资源范围里，查找完整相同的中文与唯一英文。范围内不唯一、未知 scene 或没有完整匹配时，继续采用严格普通规则。

完整未知中文或无法消歧的文字抛出 `PublicTextUnavailableException`，`code=PUBLIC_TEXT_UNAVAILABLE`，公开异常 message 固定英文。原字符串和具体诊断原因只通过 `diagnosticOriginalText()` / `diagnosticReason()` 提供给内部诊断；调用方不得把这些值拼入公开错误。未知的其他非拉丁文字当前也严格拒绝，不猜测译者、开发者或玩家名字。用户原文的字段分类和保留策略由协议层单独确定，本类不会从字形猜测名字。

## 匹配规则

完整资源字串优先。相同中文对应的英文仅有大小写差异时，可以确定性归为同一词形；具有不同词义、词形或限定词时，保留歧义，不能因最后访问了某个对象而任选一个译文。

格式模板只提取当前字符串已经显示的参数，再递归翻译参数。支持本资源集实际使用的 `%s`、`%d`、显式参数序号、`%,d`、`%.2f`、`%1$.0f` / `%2$.0f` 和 `%%`。输出使用已显示的数值字面量，不通过解析隐藏数值重新取整或补充精度。源和目标参数集合必须相同，重复位置参数的已显示值必须一致。普通文字中的 `25% more` 不会被误判为 `%m` 之类的格式指令。

拼接的段落、标题、数量和物品名称可以按完整资源单元组合；无法解释的中文后缀不会被删除。可见输入长度、递归层数、组合段数和工作量均有界，超限同样返回稳定的不可用错误。

公开场景消歧覆盖当前公开 scene 类名白名单和 `GameSnapshotter.sceneName` 已有别名，不接受任意内部对象类名。例子：

| 相同可见中文 | 已公开上下文 | 结果 |
| --- | --- | --- |
| 开始 | `HeroSelectScene` / `hero_select` | Start |
| 开始 | `TitleScene` / `title` | Play |
| 游戏新闻 | `TitleScene` / `title` | News |
| 游戏新闻 | `NewsScene` | Game News |

相同可见字符串和相同公开 scene 的结果，与隐藏对象种类及之前翻译过哪个场景无关。公开 scene 内部仍有多个不同候选时也拒绝；只匹配前缀不能补全未显示的尾文。

## 覆盖与缓存

实际 Java Properties 读取并逐项测试的结果：

| 项目 | 结果 |
| --- | --- |
| 含中文的可配对资源条目 | 4,817 |
| 不同中文完整字符串 | 4,413 |
| 无歧义完整字符串 | 4,358，逐条返回对应英文 |
| 有真实歧义的完整字符串 | 55，全部稳定拒绝；另 18 组仅英文大小写不同已合并 |
| 格式模板 | 584 对全部成功解析，参数集合没有缺项 |
| 实际带参模板样本 | 584 个，每模板使用一组确定性的可见名称/数值；578 个逐字匹配原英文 `String.format`，6 个安全拒绝 |
| 公开语言名称 | 23 种全部测试，含中文以外脚本和 Latin 名称 |
| 独立单测 | 20 项通过 |

6 个带参拒绝来自三组真实资源碰撞：`神圣%s` 对应 `%s of light` / `holy %s`；`死于：%s` 对应 `Slain by: %s` / `Killed by: %s`；矛的实际能力说明和典型能力说明具有完全相同中文，英文却有无 `typically` 的差别。这些不能通过读取隐藏模型来区分。模板样本没有穷举所有真实参数组合，不能据此宣称整个游戏所有显示文本都已覆盖。

全部歧义来源列在 [cli-displayed-text-english.json](cli-displayed-text-english.json)。后续可使用已经公开的语义信息继续区分，例如 slider 端点/checkbox 的“关闭”是 `Off`，实际关闭窗口的按钮是 `Close`；但通用 `button` 角色不能解决所有碰撞。`暴雨` 的 terrain 表现与 Combo 动作、`卷轴` 的物品单体与图鉴分类等，也需要已公开且足以区分的上下文。尚未接入的上下文不会被假定存在。

默认构造器通过 static Holder 对每个 classloader 只建一次不可变索引；一次隔离测试测得完整资源加载与建索引约 54 毫秒，这不是平台性能承诺。完整字串查询使用预计算结果；模板正则和资源前缀也预先构造。查询仅有局部临时 memo，不维护跨请求的“最后对象”或“最后译文”缓存，不打开额外文件。

这份索引应归类为 `immutable_software_resource_cache`：它完全由软件版本中的资源和公开语言枚举决定，不是存档或局内模型状态。资源版本由现有 BuildCatalog 资源目录记录。构造应在 CLI bootstrap 的正常资源准备阶段完成，之后复用；完整诊断若记录该缓存，可以引用对应软件/资源目录，不能将其误当作玩家已经获知的物品数据库向外导出。

默认加载仅使用 classloader 包内资源，不回退到 cwd、Gdx 文件接口或用户目录。独立 game-control 测试需要显式加入 `core/src/main/assets` 到测试 classpath，或使用 `fromClassLoader` 注入测试资源加载器；生产包使用已有资产目录。

本类的独立验证没有替代 GUI 中文、CLI 英文的真实协议验收。UiBridge、公开事件、操作响应和审计的集成由协议投影层完成；正式实机覆盖以其单独报告为准。
