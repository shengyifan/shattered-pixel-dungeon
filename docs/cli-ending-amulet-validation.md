# 护符、地表结束与首次 Ascension 验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

2026-09-09，两个独立 trigger-before 实机用例全部通过。CLI 游戏文案为英文，GUI 为中文、窗口化，合计 52 次 GUI 环境断言。测试只准备触发之前的初态；之后的拾取、选择、转层、buff、胜负与窗口均由原 CLI 操作触发。

完整结果见 cli-ending-amulet-validation.json（历史 JSON 已删除，可查 Git 历史）。两个用例的生产 build ID 相同：`b59ec2d8fb195dfcd6088ae40d226eb41e79ee4a841cd63c781b7c5836d6003e`。

| case_id | 实际原流程 | 结果 |
| --- | --- | --- |
| `ending.amulet_pickup_surface` | 二层地面真实 Amulet → 原拾取及首次故事 → `I'm not done yet` → 原 2→1 上楼 → 原 SURFACE 出口 → SurfaceScene → `Game Over` → RankingsScene/WndVictoryCongrats → 原 Back/Close。 | 护符与 scope 保留到原上楼；实际 SurfaceScene 和单一 won 事件；恭喜窗不能被 Back 跳过，原 Close 返回无模态排行榜。27 次 GUI 断言。 |
| `ending.ascension_start` | 真实 HallsBossLevel 25 的清场初态，地面 Amulet → 原拾取/留下 → 原入口确认，先 `Stop for Now`，再 `Continue!`。 | 取消不附加挑战；确认由原逻辑附加 AscensionChallenge 并抵达 24 层 HallsLevel 的 player_ready。真实 buff、highestAscent=24、公开 amulet's curse 与原已显示 beckon 日志均验证；没有 run.ended。25 次 GUI 断言。 |

低层用例的 profile 为 `ending-amulet-surface-9f8242a95ca745d08607fd51c337dcc1`，冻结运行时 `runtime-0c02dfe4c49742bb9a1705257a7dfd03`；25 层用例为 `ending-ascension-start-b54735400bc0402c81c26f76443a99d4`，运行时 `runtime-cf3cfbc6c8334161930b35c4a092b008`。数据位于 `desktop-control/build/fixtures/`，均为隔离测试，不计正式胜利。

## 初态与验证边界

低层用例预先准备并保存原 SewerLevel 的一、二层清场布局。只将已经存在的入口/出口布置成短可见路线，保留原 transition 对象、类型和目的地，因此被测转换仍是原 2→1→SURFACE。护符在地面上，未事先收入库存、设置获得标志或调用任何胜利方法。初态准备后不再改地图或游戏状态。

25 层用例使用原 HallsBossLevel 及其原入口。仅准备首领已清场所对应的开放入口/出口，邻接地面放真实护符。测试代码没有调用 `Buff.affect(...AscensionChallenge...)`、修改 highestAscent 或直接切换到目标 24 层；只有原 HallsBossLevel 入口确认的 Continue! 才附加 buff 和执行原上楼。初次取消及随后的确认分别验证。

实际反馈包含 `The amulet begins calling out to distant enemies.`，来自原 GameScene/AscensionChallenge 的已显示日志。窗口类、实际 Level 类型和 buff 的私有断言只在原操作之后读取，不用于选择输入。

**这两项不是一局 26→1 的完整返程。** 低层地表路线不声称开启过 AscensionChallenge；25→24 用例不声称抵达地表或完成胜利。游戏的真实 won 事件来自人为准备的隔离 fixture，不是正式通关证据。

## 保留的失败与修正

- 首次低层用例把原地形英文名误写为 Level Entrance，实际资源为 `Depth entrance`。只修正测试对公开原名字的匹配，未修改生产或存档。
- `ending-amulet-surface-d79b97c90e084dfa904bd7f45e4cf8de` 已到原 Surface won，随后恭喜窗口的“关闭”发生 Close/Off 歧义。该整条用例当时仍为失败。
- `ending-amulet-surface-5ca29f3b23d4448c8a929ea803a3beea` 实际确认恭喜窗 Close 和 Back 限制，但关闭后露出的胜利排行组合行又出现 `Obtain`/`Obtained` 资源歧义，也未当作完整通过。
- 生产翻译修复由其所有者按公开场景、窗口文字和排行行签名完成。本测试没有按隐藏 record.win/物品类型选译，没有隐藏模态或跳过 Close。最终在新 profile 重跑全部低层链条通过；原失败 profile 与诊断未改写。

## 复跑

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/ending_scenario_smoke.py --cases amulet-surface
python3 desktop-control/src/test/python/ending_scenario_smoke.py --cases ascension-start
```

每项成功结果单独落盘。测试没有截图、OS 键鼠输入、computer use 或正式 profile 访问。本组未修改生产语言文件或游戏规则；先前死亡/重开报告保持独立。
