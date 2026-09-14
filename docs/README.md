# 文档与测试资料

`docs` 只保留人读的 Markdown 说明。2026-09-12按用户要求删除43份历史 JSON 报告/索引，并把唯一仍被测试代码直接读取的静态基线迁到测试资源目录。旧报告可从Git历史查看；旧原始运行数据已经清空，不能从这些说明恢复存档或审计，也不能将旧结论自动算成CLI.0.9.0的新验证。

## 当前入口

- [CLI使用与协议](cli.md)：启动方式、请求ID、状态版本和公开接口。
- [英文CLI操作手册](cli-help.md)：`spdctl --help`的完整内容来源，随应用打包，包含具体游戏操作示例。
- [CLI.4.0.1 清理重建记录](cli-rebuild-4.0.1-20260914.md)：删除此前产物及实战故障 JSON 后的全量 ARM64 构建和当前包验证。
- [CLI.4.0.1 独立终端颜色修复](cli-issues/2026-09-14-cli-4.0.1-terminal-color.md)：专用窗口显式彩色、实际脚本 PTY 与环境回归。
- [CLI.4.0.0 实现与验收](cli4-implementation.md)：自包含 play/full 回复、行地图、轻量回执流程与严格 v4 目录隔离。
- [CLI.3.0.1 状态版本修复](cli-issues/2026-09-14-cli-3.0.1-currency-stale-state.md)：两次实战金币提示淡出误拒、空文本签名、旧版复现及回归验证。
- [CLI.3.0.0 实现与验收](cli3-implementation.md)：短协议、彩色查看器、完整收发、token 基准与实际包验证。
- [CLI.3.0.0 清理重建记录](cli-rebuild-3.0.0-20260913.md)：该批次删除旧产物及实战故障 JSON、全量 ARM64 构建和包验证。
- [历史实施记录](cli-implementation.md)：旧版模块、规则边界及验证说明。
- [CLI.2.1.1 状态边界修复](cli-issues/2026-09-13-cli-2.1.1-stale-state-fixes.md)：击杀后的延迟停用、原教程复验、陈旧状态恢复规则及包验证。
- [CLI.2.1.0 传输记录与验收](cli-transport.md)：独立终端原文展示、新 profile 默认设置和实际包验证。
- [CLI.2.0.0 来源改造与验收](cli2-implementation.md)：String 接口、全语言来源与协议 2 / schema 5 的历史验收。
- [CLI.1.0.1 历史完整重建](cli-rebuild-20260913.md)：该版本的清理范围、全量构建和产物核验。
- [场景工作计划](cli-scenario-coverage.md)：35个计划场景、11个场景族和146成员的范围/检查方法，不携带旧结果指针。
- [静态UI覆盖说明](cli-ui-coverage.md)：测试清单的生成方式与使用边界。
- [CLI.1.0.1 实战异常修复](cli-issues/2026-09-13-fixes-and-validation.md)：教程稳定边界、升级预览和隔离验证结果。
- [桌面构建说明](getting-started-desktop.md)。

## 测试输入和输出

静态源码基线保存在 [game-control/src/test/resources/cli-ui-coverage.json](../game-control/src/test/resources/cli-ui-coverage.json)，由Java清单生成器、回归检查和Python职业矩阵读取。它描述源代码输入入口，不是游戏存档或某次运行结果；基线内容本次没有改变。

新测试的原始JSON/NDJSON、profile、数据库和结构化报告应写入被忽略的build输出目录，不再作为历史结果JSON平铺提交到docs。需要提交的验证结论使用简洁Markdown，明确版本、范围、结果和限制。已有专项Markdown保留为历史说明，配套旧JSON引用已解除。

`docs/cli-issues/` 随 Git 提交 Markdown 问题说明与修复结论，本地原始证据由 `.gitignore` 排除。2026-09-13 的 CLI.3.0.0 清理重建按用户要求删除了此前 build 产物，以及两份 `*-public-evidence.json` 和 `evidence/` 下的一份故障 JSON。2026-09-14 的 CLI.4.0.1 清理重建再次删除此前全部 build 产物，并删除后续提取的金币提示故障 JSON 及其临时副本。历史 Markdown 的结果保留，旧 build 路径、冻结运行时及故障附件路径仅作历史记录，不代表本地仍有对应文件。用户日志目录中的完整公开传输原文不属于这些故障 JSON 附件，本次没有删除。

英文语料工具只接受显式指定的当前公开trace或已有输入清单，不再依赖已删除的历史报告。

2026-09-12 整理时的验证：5项Java静态清单检查、4项Python显式轨迹选择回归通过；基线迁移前后字节完全相同，法术/武器/武僧枚举仍为27/32/5。当时文档中的JSON链接全部指向有效测试基线，验证生成的本地构建缓存随后清理。这些是该历史批次的结果，当前重建验证见上方 CLI.4.0.1 清理重建记录。
