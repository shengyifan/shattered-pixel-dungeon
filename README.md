# Shattered Pixel Dungeon CLI 控制版

本仓库基于 [Shattered Pixel Dungeon 上游项目](https://github.com/00-Evan/shattered-pixel-dungeon)，用于 CLI 控制版开发和源码辅助通关复盘。

Shattered Pixel Dungeon 是一款传统回合制地牢探索游戏，具有随机生成的楼层、敌人以及丰富的物品系统，基于 Watabou 的 [Pixel Dungeon 源码](https://github.com/00-Evan/pixel-dungeon-gradle) 开发。上游支持 Android、iOS 和桌面平台。

## 当前状态

- 基础版本为上游 `v3.3.8`，fork 基点为 `7b8b845a7`。
- 本 fork 当前包含开发约定、中文变更日志和战士通关资料归档，尚未实现独立的 CLI 控制入口。
- 阶段版本使用 `CLI.主版本.次版本.修订版本`；文档和归档阶段的版本递增不代表新增了游戏控制功能。
- 详细改动见 [CHANGELOG.md](CHANGELOG.md)。

## 分支与提交约定

- `master` 保留上游基线，CLI 开发和本 fork 的改动在 `feature/cli` 上进行。
- **Commit 标题和正文全部使用英文。**
- **本 fork 编写或维护的 README 和 CHANGELOG 使用中文。** 代码标识符、命令、路径和专有名称可保留原文。
- **每次提交都必须更新根目录 `CHANGELOG.md`**，包括代码、文档、配置、测试、资料归档和目录整理。
- 每个可验收阶段独立提交，提交前核对暂存范围并完成与改动相称的验证。
- 历史归档中的旧提交语言约定仅用于复盘；后续工作遵循本仓库当前约定。
- 完整的后续协作要求见 [AGENTS.md](AGENTS.md)。

## 本地桌面运行

准备适配当前 Gradle 配置的 JDK 后，在项目根目录执行：

```sh
./gradlew desktop:debug
```

生成桌面发布 JAR：

```sh
./gradlew desktop:release
```

输出位于 `desktop/build/libs/`。更多平台说明沿用上游文档：

- [桌面编译指南](docs/getting-started-desktop.md)
- [Android 编译指南](docs/getting-started-android.md)
- [iOS 编译指南](docs/getting-started-ios.md)
- [制作衍生版本的建议](docs/recommended-changes.md)

## 通关资料

[2026-09-07 战士通关归档](ranking/20260907-warrior/README.md) 包含战术笔记、状态快照、只读脚本和项目记忆摘录。归档中的地图、状态和运行路径属于当时的游戏环境，重新使用时需核实当前源码和存档。

## 上游发布与支持

以下链接均指向上游游戏及其作者的发布或支持渠道：

- [Google Play](https://play.google.com/store/apps/details?id=com.shatteredpixel.shatteredpixeldungeon)
- [App Store](https://apps.apple.com/app/shattered-pixel-dungeon/id1563121109)
- [Steam](https://store.steampowered.com/app/1769170/Shattered_Pixel_Dungeon/)
- [GOG](https://www.gog.com/game/shattered_pixel_dungeon)
- [itch.io](https://shattered-pixel.itch.io/shattered-pixel-dungeon)
- [GitHub Releases](https://github.com/00-Evan/shattered-pixel-dungeon/releases)
- [上游博客](https://www.shatteredpixel.com/blog/)
- [支持上游作者](https://www.patreon.com/ShatteredPixel)
- [翻译项目](https://explore.transifex.com/shattered-pixel/shattered-pixel-dungeon/)

上游 README 说明其仓库不接受 Pull Request，并欢迎问题报告；这是上游项目的协作说明。本 fork 的开发约定见本仓库文档。

## 许可证

许可证全文见 [LICENSE.txt](LICENSE.txt)。发布衍生版本前，请同时阅读上游的编译与发行说明。
