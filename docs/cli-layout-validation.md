# 真实设置、布局与窗口验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

`layout_smoke.py` 使用公开 NDJSON 验证游戏现有的界面设置、背包呈现、控件重建和地图视图操作。没有截图、OS 键鼠输入、Computer Use 或固定屏幕坐标；没有改动生产代码。

冻结源码运行时与 **ARM64 包内 CLI.0.3.0 的相同流程均已通过**。逐项结果见 cli-layout-validation.json（历史 JSON 已删除，可查 Git 历史）。

- 源码批次：`runtime-7edb48cb0a8742709ef58792d1351e33`；profile 为 `desktop-control/build/fixtures/layout-a3a068fd4ed94063b7b09d61812887f2`。
- 包内入口：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`；profile 为 `desktop-control/build/fixtures/layout-1c409475f57c4d5690d3844cecac886d`。握手明确检查 `cli_version=CLI.0.3.0`，包在本次测试期间保持冻结。

包内结果中的 `runtime-c6cfea8d28904fb4bb1016e7d072b9c6` 是准备隔离战士存档时使用的源码 classpath 冻结编号，不是包内 Java 代码的编号。实际测量过程由上述原生 spdctl 入口启动，结果以 `measured_launcher=packaged` 和对应入口路径区分。

## 已验证的真实操作

| 操作 | 通过现有公开接口完成 | 断言 |
|---|---|---|
| Interface Mode | Settings 原滑块：2 → 0 → 1 → 2。 | 原 seamlessResetScene 重建场景和设置窗口，新滑块 ID 与旧 ID 不同，显示值正确，scope 不变。 |
| Interface Scale | Settings 原滑块：3 → 2 → 3。 | 重建后从当前 actions 找到新控件，显示值与选择一致。 |
| 旧意图 | 每次重建后提交实际旧 slider 的 control 和旧 state_version。 | 返回 STALE_STATE；使用当前版本仍不能操作旧 control；被拒绝请求的 ID 继续占用。拒绝前后公开游戏世界不变。 |
| Mobile / Mixed 背包 | 通过真实 Inventory 按钮打开 WndBag，Back 关闭，再重新打开。 | 新 actions 中的物品可打开原物品窗口。 |
| Full 背包 | 通过真实 Inventory 按钮隐藏/显示 InventoryPane。 | 常驻物品控件确实从公开树消失/出现，没有误当作模态背包窗口。 |
| 重建后取消 | 每种布局都通过当前食物槽 → Throw → cell.cancel。 | 原目标选择器可以取消，库存数量与英雄格子不变。 |
| view.zoom / view.pan | 缩放取 actions 提供的最小/最大值；平移使用声明的 map_view 相对单位。 | 操作正常完成，英雄格子不变；之后背包和选物仍可操作。 |
| 重建后实际用物 | 通过当前物品控件 → Eat。 | 食物数量从 1 降到 0，英雄存活。 |

原设置没有名为“背包布局”的独立选项。现有 Interface Mode 区分 mobile、mixed 和 full；其中 full 有常驻 InventoryPane，其余使用背包窗口。测试以这些实际选项为准。

设置窗口的图标页签目前没有公开文字标签。运行器枚举当前已公开的无文字按钮，逐个激活并读取实际显示的面板标题，找到 Interface Settings；没有根据屏幕位置或隐藏类名猜测页签。这个可访问性限制保留在结果中。

公开观察目前也没有相机的当前位置或 zoom 值。因此视图测试证明原语义操作被接受、没有移动英雄且后续交互可用，**不独立证明相机具体数值改变了多少**。结果明确记录这一观察限制，没有用内部诊断或截图补充控制信息。

## 隔离准备与复现

脚本先使用 test-only `class:WARRIOR` 启动器准备普通战士存档：仅设置测试 profile 的教程偏好/菜单解锁，不修改局内角色属性。保存并退出后，实际测量流程使用生产 SpdctlLauncher 或指定的包内 spdctl 恢复同一存档，再操作 Settings。两种启动模式复用完全相同的布局步骤。

每次新建 `desktop-control/build/fixtures/layout-<uuid>`，标记 `test_fixture=true`、`counts_as_win=false`。测试不读取游戏存档或内部审计来选命令；只读取公开协议。保留 `public-trace.jsonl`、`test_fixture.json` 和 `layout-result.json`。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/layout_smoke.py --expected-cli CLI.0.3.0
python3 desktop-control/src/test/python/layout_smoke.py \
  --launcher '/path/to/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl' \
  --expected-cli CLI.0.3.0
```

如果当前窗口/设备不提供 Interface Mode，测试明确失败并报告该选项缺失。如果仅没有可切换的 Interface Scale，结果明确记录未提供该选项，不虚构一个缩放操作。测试覆盖已有设置导致的界面重建，没有声称覆盖任意 OS 窗口尺寸、显示器或 DPI 组合。
