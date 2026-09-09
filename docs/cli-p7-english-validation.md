# P7 英文 CLI、中文窗口化实机矩阵

本轮 188 个专项现在均有通过证据。完整冻结基线实际是 **148 / 188 通过，40 失败**；随后只重跑受相关修复影响的失败项，逐步补齐这 40 项。没有把原始失败改写为通过，也没有宣称后来在单个运行时重新执行了一次 188 项全通过批次。

逐项对应的运行时、build ID、隔离 profile、公开 trace、后置断言与原始批次报告见 [完整验证索引](cli-p7-english-validation.json)。[补验索引](cli-p7-english-remediation.json) 单列从失败中补齐的 40 项。原有 [P7 验证记录](cli-p7-validation.md) 保留其历史批次事实；本文件专门验证后续新增的英文 CLI 与简体中文 GUI 组合。

所有游戏文案字段在每个响应（含嵌套历史和事件）检查英文；GUI 通过同一个 `state_version` 的 test-only 后置断言确认 `CHI_SMPL`、`fullscreen=false`。选入最终索引的成功用例合计 **4,337 个公开响应、3,961 次 GUI 状态检查**。这些检查读取引擎和已有控件数据，不使用截图、OS 键鼠或 Computer Use。

| 类别 | 用例数 | 实际边界 |
|---|---:|---|
| 六个初始职业 | 6 | 原主菜单选角、开局、库存读取、代表初始物品的真实窗口 |
| 十二个子职业 | 12 | 原 ActionIndicator 或代表性法杖/灵能短弓操作，验证即时效果 |
| 十九个护甲能力 | 38 | 正常施放及零资源；目标取消、WarpBeacon 付费传送包含在原方案中 |
| 二十七个牧师法术 | 54 | 正常施放与零资源；选择器、嵌套 Form 选项和真实耗能 |
| 三十二个决斗者武器能力 | 64 | 三十一种近战武器加任务 Pickaxe；正常施放、取消、零充能 |
| 五种武僧招式 | 10 | 原菜单、正常效果与耗能、零内力时原生不可用表现 |
| 四个 UI 流程 | 4 | 鉴定、升级、未知卷轴强制取消确认、三种子炼金 |
| 合计 | 188 | 每例独立测试 profile，不计正式胜利 |

原基线在 `runtime-8ed9a6fc8be247fa8f9041183ab40c10` 一次完整执行，失败时以 exit 1 结束。补验按以下顺序保留：

| 固定运行时 | 本批结果 | 用途 |
|---|---:|---|
| `runtime-96551553ca2a4d1fb60019fb00082495` | 2 / 2 | 完整多句资源与显示分隔符拼接；守望者和震地冲击 |
| `runtime-c3ede86ce840487a8cef94607f83a4b5` | 4 / 4 | 连击/武僧完整行，以及披风与匕首的已知物品菜单 |
| `runtime-bfb2dbc90a1748989a0c0435669430a2` | 5 / 5 | 拳套、暗影映像、升级返回、卷轴取消确认、三位一体 |
| `runtime-0a28aed0ccac4779a3eacc5b11364e17` | 27 / 29 | 与前 11 项取差集后重跑；两项 DeathMark 暴露下一层名称歧义 |
| `runtime-76f3e5ccfc5c42099991281b65cc463d` | 1 / 2 | DeathMark 菜单修复后零资源通过；正常施放浮字仍拒绝 |
| `runtime-7c78f3e8a26f49198f1fbd3a371b670f` | 1 / 1 | 最后正常 DeathMark，完成响应已含充能 100 → 85 |

这些修复只使用已显示文本、公开当前窗口/控件关系或已显示的完整浮字类型。完整资源及模板拼接必须消费全部输入；物品菜单需要当前公开 inspected 根及原显示标题/按钮；角斗士和武僧行需要对应完整标题、成本和说明；普通名称、旧 DTO、隐藏对象、缺失标记、未知尾文不能据此推断。

施放的完成断言使用动作自身终态响应的 `state_version`。若首响应为 `in_progress`，运行器仅用原请求的 `request.get` 等待该请求的终态，不重执行，不借助后续 `state.get` 等待尚未落实的效果。菜单和目标选择本身不冒充实际施放。即使正常效果发生在引擎中，只要公开响应失败，该批用例仍保留为失败。

## 复现与诊断

```sh
./gradlew :game-control:generateUiCoverage :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/fixture_smoke.py --families base,spell,weapon,monk,ui --include-empty
```

`--frozen-runtime <runtime-directory>` 可复制此前的固定运行产物。仓库内 jars/classes/resources 会再次复制到新目录，后续并行构建不会覆盖正在使用的文件；外部版本化依赖缓存保持原路径。每例的 `test_fixture.json`、`public-trace.jsonl`、`fixture-result.json` 与断言保留在 build 下。

离线 `fixture_failure_diagnostics.py <runtime-id>` 只接受已经完成的 runtime `results.json` 清单，不遍历或打开其他 profile 的报告来推测所属批次。它验证 report/runtime 一致，拒绝越界路径，以及 runtime、profile、report、数据库、SQLite sidecar、输出文件中的符号链接。7 项离线回归验证这些边界。诊断原文保留在 build 中，只用于开发定位，不参与游戏动作选择，也不进入 CLI 公开 DTO。

本矩阵仍是测试夹具：部分属性、天赋、装备和资源由独立 test launcher 准备，全部 `test_fixture=true`、`counts_as_win=false`。它证明这些原生动作与文案路径的一次有效操作及指定不足条件，不穷举所有装备组合、随机战局和全部 153 个窗口，也不算正式通关。
