# CustomNote 原生窗口与保存恢复验证

2026-09-09，五个独立笔记入口全部完成实际引擎验证，见 [cli-notes-scenarios-validation.json](cli-notes-scenarios-validation.json)。每个入口都经过原窗口的新增、编辑、取消、删除确认，以及正常保存、新 JVM 继续和内容恢复。所有输入内容使用英语，公开 CLI 游戏文案为英语，GUI 为简体中文、窗口化；563 个公开响应中对应的 279 个不同版本有实际 GUI 后置记录。测试进程已全部退出。

这些是隔离 fixture 的专项，不是正式游玩或通关证据。每局仍是正常生成的普通战士，没有放大等级/力量/生命，没有重置或注入地图、物品、笔记。测试 source-set 只增加 `notes` 参数分派与 Notes 后置断言，角色准备在普通战士分支直接返回。

| 具名用例 | 实际原入口 | 记录类型及验证范围 |
|---|---|---|
| `notes.text` | Journal → Adventuring Notes → Add a Custom Note → New Text Note | `TEXT`，独立文本笔记。 |
| `notes.floor` | 相同笔记菜单 → New Dungeon Floor Note → 原楼层按钮 `1` | `DEPTH`，记录关联当前第 1 层。没有冒充对其他楼层或支线的验证。 |
| `notes.inventory` | 笔记菜单 → New Inventory Item Note → 原背包选择装备中的 Worn Shortsword | `SPECIFIC_ITEM`，同版本断言检查该武器的 `customNoteID` 与记录 ID 对应。实际出现 WndBag。 |
| `notes.item_type` | 笔记菜单 → New Item Type Note → 选择公开显示的 `Crimson Potion` | `ITEM_TYPE`，只按当前原窗口中的外观选择，不查询药剂隐藏功能。未遍历全部药水、卷轴或戒指类型。 |
| `notes.item_shortcut` | Worn Shortsword 详情 → 原 Journal 图标 | `SPECIFIC_ITEM`，从物品详情直接新增、重开、编辑和删除；同版本验证武器关联。 |

每例的固定流程如下：先在新增标题窗口填写 `Discarded test note` 并点 Cancel，确认没有新增笔记记录；再次打开原入口，确认创建 `Test route note`。随后分别取消一次标题修改和正文修改，再确认标题及两行正文的修改；已有正文的 Edit Text 也经过取消和确认。实际显示内容和同版本 Notes 记录一起核对。

删除流程先打开原问题并点 Cancel，验证记录仍在。随后使用原 `game.save`，要求本请求的 `saves_during_request` 中出现成功回执，不能用早先的 `last_save` 冒充本次保存。正常 `app.quit` 后重新启动同一 fixture profile，通过原 Continue 恢复同一 run scope；再次核对原记录类型、标题、正文和物品关联，并通过原列表或物品图标打开该笔记。最后再次 Delete → Confirm，确认记录移除，再正常保存和退出。

列表中的笔记图标通过实际广告的 `ui.select` 操作。它不是 `ui.activate` 按钮。当前每局只创建一条笔记，所以选择的是公开 Custom 区域下的首个原条目；没有读取私有记录 ID 来选择控件。标题、正文编辑使用当前公开 `ui.text` 控件和 Confirm/Cancel 按钮，不通过键鼠模拟或 Computer Use。

实际后置窗口类型记录包含 `WndJournal`、`CustomNoteButton.WndNoteTypeSelect`、`WndDepthSelect`、`WndItemtypeSelect`、`CustomNoteWindow`，以及创建、修改标题、修改正文、删除确认对应的匿名窗口。物品详情入口另经过 `ItemJournalButton` 的原标题输入窗口和 `WndUseItem`。精确运行时类名保留在各例验证 JSON 中；这只证明表内已执行分支，不把所有继承类或所有回调标成通过。

有两点原游戏语义需要保留：

- **没有地图 cell 绑定笔记。** [Notes.Record](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Notes.java) 只保存 depth，源码注明当前仅关联 branch 0；`CustomType` 只有文本、楼层、物品类型和具体物品。CustomNote 也没有 WndInfoCell 入口。因此地图坐标关联列为 `not_applicable`，没有新增原游戏不存在的功能。
- **取消不等于所有内部字段不变。** 具体物品笔记在打开标题输入前就会分配 ID 并写入物品关联字段；取消后没有 Notes 记录，但 ID 计数器和无对应记录的物品 ID 可能已经改变。测试遵循原代码，只要求取消不新增记录，确认创建后再核对有效关联。

前三次失败均原样保留。第一例在进入 Journal 时因已绘制的 `_第1层_` 翻译失败；第二例在物品笔记标题输入窗口因 Confirm 的原文“确定”歧义失败；第三例已完成创建、编辑、保存及恢复，但测试错误地寻找 `ui.activate`，未使用实际存在的 `ui.select` 笔记条目。前三次都未计入通过，最终采用新的完整成功运行。对应原 profile、失败类别和结果文件在验证 JSON 中列明。

开发诊断仅查看前两个隔离 fixture 中对应失败请求的 `internal.exceptions` 翻译原文与原因。正常测试只在公开动作完成后，按同一 `state_version` 读取私有 Notes 保存/内容后置断言和 GUI 环境断言；这些信息不提供下一动作、物品或目标。没有读取正式 profile，没有修改保存文件制造结果。

测试入口：

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/notes_scenario_smoke.py --cases text,floor,inventory,item-type,item-shortcut
```

运行器为 [notes_scenario_smoke.py](../desktop-control/src/test/python/notes_scenario_smoke.py)，后置断言为 [NoteScenarioFixtures.java](../desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/NoteScenarioFixtures.java)。最终证据来自两个冻结测试运行时，生产 build ID 均为 `ecbc869926f9fcc0534b8cd84e20483150846cdca82b4e5f97954e866b794a79`。新 build 不能自动继承这一结果。

本组未验证中文自定义内容、空标题与长度边界、剪贴板、五条笔记上限、重复关联、其他装备类别、全部药卷戒类型、不同楼层/支线，以及确认删除后的第三次 JVM 恢复。这些仍应作为后续独立变体记录。
