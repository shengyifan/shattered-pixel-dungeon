# 设置与键位面板的真实场景验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

`settings-scenes-39b9b22a5c6349f0ba837054c823e3e1`已通过六个原设置页签：Display、Interface、Input、Connectivity、Audio、Language。每页均保持真实GUI为CHI_SMPL和窗口化，公开控件为英文。精确构建和逐项后置证据见JSON（历史 JSON 已删除，可查 Git 历史）。

通过项目包括原亮度、网格和音乐音量滑条修改再恢复；Fullscreen原checkbox保持false；Key Bindings原面板的全部可见行能读取及操作；原Back被面板忽略以保护修改，再用原Cancel回到Input Settings；语种列表公开包含Simplified Chinese。全部测试进程正常退出。

第一次进入键位面板时，公开的`选择快捷栏`与`确定`文字具有不同资源含义，英文投影明确拒绝。现将已经公开的三键位槽事实也放入UI节点的`binding_slots`，以同一公开节点/父节点/动作control关系辨认键位行；主面板还必须具有完整公开表头和Default Bindings按钮，才用对应英文资源。没有读取隐藏的Row/Window对象类型来决定译文。

一次测试曾误以为Back会退出键位编辑器。原`WndKeyBindings.onBackPressed()`刻意不执行任何动作，所以测试改为先断言编辑器仍在，再点击原Cancel；这不是修改游戏行为，也没有跳过取消验证。

本组没有编辑实际按键、选择其他GUI语言、点击译者署名、切换全屏或穷举所有设置值，不能标记整个设置类全部通过。后续需要针对这些适用分支分别构造中间状态。当前窗口化是用户指定约束，测试不会为了覆盖全屏值而擅自切换。

补充`settings-scenes-648e328acae247be97d9649a3a0adcfb`已通过原Wait第一键位输入：经ui.binding_slot打开原编辑窗，使用LibGDX公开键码142选择F12，原文确实显示F12，再原Cancel返回，Wait绑定保持原值。完整设置流程仍通过。当前binding_input语义只解释原公开输入控件，并在按钮和模板参数中分别使用原Unbind Key与None含义；它没有生成OS键盘事件。新结果作为`key_input_cancel`追加，原失败不回写。

`settings-scenes-562de3390c8c467e8ec0a9e0723d6e32`进一步通过原确认：给Wait第一槽选F12并在子窗Confirm，在第二槽尝试同键时原窗口显示“This key is already bound to this action.”且没有可执行Confirm；原Cancel返回后，主面板Confirm应用绑定，重新打开仍见F12。随后原Default Bindings→Confirm恢复，重新打开Wait不再有F12。六页签及原Back保护仍通过。新证据为`key_confirm_duplicate_and_default`；本项没有声称重启JVM后的键位持久化或所有冲突分支均已验证。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/settings_scenario_smoke.py
```
