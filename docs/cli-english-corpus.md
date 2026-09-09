# CLI 历史公开文本的离线英语投影验证

2026-09-09，当前英语投影对固定历史语料的第三轮扫描通过：**238 个已关闭 fixture 目录、5,874 个公开响应，完整文本拒绝 0 次，截断文本保守回退 0 次，跳过输入 0 个。** 精简结果见 [cli-english-corpus-validation.json](cli-english-corpus-validation.json)。

这项验证只把历史公开 JSON 输入当前文本投影器。它没有启动游戏引擎或窗口，没有执行游戏动作，也没有构造 Hero、Level、Item 或 Window。**238 是输入目录数，不是“238 个场景在当前引擎全部通过”。** 该结果也不代表正式通关、所有游戏机制覆盖或所有潜在语言资源歧义已解决。

语料来自本地已有的主菜单、King、Tengu 陷阱、区域过渡、game.log、Boss 可视提示及 P6/P7 测试的 `public-trace.jsonl`。其中旧 P7 多数原本使用英语，保留它们用于检查现有英文内容的兼容性。总输入为 95,283,999 字节，包含 2,898,434 次字符串出现；使用上下文去重后，本轮有 21,987 个独立翻译输入，40,673 次字段出现得到转换。

三轮都使用完全相同的有序输入清单。已逐项比较每个 trace 的路径、字节数、行数和响应数；前两轮报告保持原样。

| 轮次 | 投影方式 | 完整文本拒绝次数 | 场景/字段/原文问题组 | 截断回退 | 跳过 |
|---|---|---:|---:|---:|---:|
| 初始诊断 | 仅公开 scene 与 clipped，缺少控件上下文 | 533 | 36 | 0 | 0 |
| 上下文诊断 | 完整公开 UI 与叶节点元数据 | 12 | 4 | 0 | 0 |
| 最终验证 | 相同上下文，增加经过审查的词义与单句点规范化 | 0 | 0 | 0 | 0 |

初始 36 组不能全部称为生产缺陷：旧探针丢失 Back、滑条、父控件和存档详情签名等已公开信息，会产生额外歧义。第二轮修正探针后，只剩“凝神”和“选择要释放魔法的位置”分布在四个字段路径中。最终规则将前者保守表达为 `Focus`，不猜测隐藏 buff/ability 类型；后者只归一化英文候选之间的单个末尾句点差异。未知后缀、半词、问号、省略号或不同语义仍不能据此放宽。

测试工具与数据边界：

- [EnglishCorpusProbe.java](../desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/EnglishCorpusProbe.java) 仅位于 test source-set。它读取每行已公开的 response，对字符串叶节点分别调用当前 `PublicEnglishProjection`，单个字段失败后继续检查同一响应中的其他字段。
- 每个叶节点保留其公开 `id`、`role`、`parent`、`control`、`shortcut_action`、`checked`、滑条范围等非文案元数据。同一响应中对应的完整公开 UI 通过 `copyWithUi` 作为上下文，其他叶节点的文案不会在这次单叶投影中递归执行。历史记录没有自己的 UI 时，不借用前一响应或当前游戏的 UI。
- 缓存绑定完整公开 UI 内容及节点元数据。改变父控件或移除存档详情签名后，先前成功的翻译不能继续命中旧上下文。截断文本的保守回退单独统计；本轮未用这种回退消除失败。
- [english_corpus_probe.py](../desktop-control/src/test/python/english_corpus_probe.py) 和 Java 入口共同限制路径为 `desktop-control/build/fixtures/.../public-trace.jsonl`，拒绝符号链接、其他文件和正式 playthrough 目录。Java 还检查已完成的测试报告、运行中进程引用，并在扫描后确认输入没有变化。
- 探针不读取 `game.dat`、楼层存档、SQLite、内部快照或私有断言流。测试报告仅用于选择 fixture 路径和确认关闭依据，翻译输入完全来自公开 response。
- 原文诊断、频次、profile/request 样例和冻结运行时清单只写入 `desktop-control/build/english-corpus`。stdout 只给统计与报告路径。此文档及精简 JSON 不包含逐节点诊断或 95 MB 原始语料。

[EnglishCorpusProbeTest.java](../desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/EnglishCorpusProbeTest.java) 的 6 项隔离测试通过，覆盖同帧多个失败、频次聚合、输入不变、clipped 分流、路径与符号链接拒绝、公开父节点/Back/滑条的正反例、完整存档签名及缓存失效，以及不跨响应借用 UI。Python 语法检查和 `git diff --check` 也通过。

本地复现最终同语料扫描：

```sh
./gradlew :desktop-control:test --tests '*EnglishCorpusProbeTest' :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/english_corpus_probe.py --no-build --baseline-inputs desktop-control/build/english-corpus/64a7f2ffe2de4083852642f42fa27921/inputs.json
```

不指定 `--baseline-inputs` 时，工具从当前已有测试报告重新选择语料，并创建新的输入清单；这不保证与上表目录数相同。`--without-p7` 可缩小诊断范围，但不能用于声称完整固定语料通过。干净 checkout 不包含被忽略的本地 fixture 产物，需要先产生对应测试日志，不能用空输入替代验收。

最终本地报告位于 `desktop-control/build/english-corpus/f7287a8e24264eac8fe043e67d2926bf/report.json`，冻结运行时为 `runtime-49fea25878f24cc0bba5dca4af4ce3c8`，生产 build ID 为 `41def3e7f71312977931f40c5c51d3efc5edbbbc4b791c15f248c2cebeca2876`。这些引用只绑定本次已执行的编译产物，不自动覆盖后续源码改动。
