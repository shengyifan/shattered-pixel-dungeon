# 欢迎与更新提示的实际场景测试

四项隔离初态通过生产`SpdctlLauncher`真实运行，CLI文案英文、GUI中文且窗口化；没有测试状态注入器、截图或OS输入。

| 初态 | 原路径和结果 |
|---|---|
| profile已保存版本882，intro=false | 原更新说明→Changes→实际ChangesScene |
| 已保存版本895 | 原补丁说明→Continue→TitleScene |
| 已保存版本897（当前程序896） | 原未来版本警告→Continue→TitleScene |
| 当前版本896，存在非空无效game.dat.spdtmp | 原清理删去无效临时文件并出现强制保存中断提示；初始Continue禁用、Back不绕过，原5秒计时后Continue可用，点击后进入TitleScene |

初态仅写入自建测试profile的界面偏好/版本，最后一种额外准备无效临时保存文件，不涉及正式存档。它验证原警告与无效文件清理，不冒充有效存档恢复或真实断电的数据完整性测试。GUI的语言与实际窗口模式由公开`ui.display`核验。

具体profile、冻结构建与结果见[精简JSON](cli-welcome-scenarios-validation.json)。未来版本首轮曾因测试期望“newer version”而非原资源“future version”失败，修正测试文字后新profile通过；没有修改原消息或回写旧失败。所有测试进程正常退出。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/welcome_scenario_smoke.py
```
