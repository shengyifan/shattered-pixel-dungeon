# 后台窗口与普通启动器验证

本项使用两个独立测试 profile：受控实例通过 test-only FixtureLauncher 启动真实引擎，并准备已知安全直廊；另一个实例使用实际 `.app` 的普通 `Shattered Pixel Dungeon` 入口，停在主菜单。两者都不计入正式通关。

最新批次已按用户要求使用中文、窗口化，受控实例的实际 metadata 为 `CHI_SMPL`、`fullscreen=false`；普通 GUI 也在启动前获得同样的隔离测试设置。旧展示配置的验证保留在 JSON 的 `previous_validation`，没有改写为中文证据。额外测试 GUI 的清理现在处于独立 finally，即使受控实例收尾报错也会关闭。

测试没有调用窗口聚焦方法、键盘/鼠标注入、截图或 Computer Use。普通新窗口自然取得焦点后，从受控实例原有 LWJGL 窗口读取 `isFocused()` 布尔值，仅写入测试断言文件；这一系统状态不进入公开游戏 DTO，也不用于选择游戏目标。

结果见 [cli-background-validation.json](cli-background-validation.json)：

- 受控窗口已经失去焦点，打开背包和投掷最终响应对应的检查点均记录 `window_focused=false`，期间没有重新取得焦点的转换记录。
- 所有控件、物品和目标仍从公开 NDJSON 获取。真实 Throw 完成后，背包中的 throwing stone 从 3 变为 2；没有通过位置修改或输入模拟达成结果。
- 普通启动器保持运行并完成桌面控制器初始化；在尝试启动竞争 CLI 之前，没有创建任何审计数据库或控制模块目录。
- 普通 GUI 持有该 profile 的实例锁时，竞争 CLI 返回非零退出码、零 stdout，且没有打开 public.sqlite3。普通 GUI 和 CLI 因而确实使用相同的实例锁。
- 受控游戏最后正常退出。普通 GUI 仅位于自建测试菜单，通过其精确子进程句柄发送 SIGTERM 清理，没有修改或终止其他游戏实例。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/background_smoke.py \
  --gui-launcher "desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/Shattered Pixel Dungeon"
```

本项证明真实无焦点操作和普通 GUI 启动/互斥，不将菜单进程的 SIGTERM 清理称作一次普通 GUI 保存验收。正式通关始终使用公开 CLI 数据，完全不读取这些测试专用窗口焦点文件。
