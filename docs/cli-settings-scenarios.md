# 设置与键位面板的真实场景验证

`settings-scenes-39b9b22a5c6349f0ba837054c823e3e1`已通过六个原设置页签：Display、Interface、Input、Connectivity、Audio、Language。每页均保持真实GUI为CHI_SMPL和窗口化，公开控件为英文。精确构建和逐项后置证据见[JSON](cli-settings-scenarios-validation.json)。

通过项目包括原亮度、网格和音乐音量滑条修改再恢复；Fullscreen原checkbox保持false；Key Bindings原面板的全部可见行能读取及操作；原Back被面板忽略以保护修改，再用原Cancel回到Input Settings；语种列表公开包含Simplified Chinese。全部测试进程正常退出。

第一次进入键位面板时，公开的`选择快捷栏`与`确定`文字具有不同资源含义，英文投影明确拒绝。现将已经公开的三键位槽事实也放入UI节点的`binding_slots`，以同一公开节点/父节点/动作control关系辨认键位行；主面板还必须具有完整公开表头和Default Bindings按钮，才用对应英文资源。没有读取隐藏的Row/Window对象类型来决定译文。

一次测试曾误以为Back会退出键位编辑器。原`WndKeyBindings.onBackPressed()`刻意不执行任何动作，所以测试改为先断言编辑器仍在，再点击原Cancel；这不是修改游戏行为，也没有跳过取消验证。

本组没有编辑实际按键、选择其他GUI语言、点击译者署名、切换全屏或穷举所有设置值，不能标记整个设置类全部通过。后续需要针对这些适用分支分别构造中间状态。当前窗口化是用户指定约束，测试不会为了覆盖全屏值而擅自切换。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/settings_scenario_smoke.py
```
