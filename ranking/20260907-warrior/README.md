# 2026-09-07 战士通关临时文件归档

本局为 Shattered Pixel Dungeon v3.3.8 的战士／角斗士通关：20 级，第 26 层取得 Yendor 护符并正式结束冒险，得分 128,424，种子 SFN-DXK-XDG。

[tmp/](tmp/) 保存从 `/tmp/spd-run-20260905/` 复制的全部 **44 个临时工作文件**：28 份 Markdown 战术／交接笔记、14 份 JSON 状态快照、2 个 Python 只读脚本。移动时保留文件内容和时间戳，原临时目录中的文件也继续保留。只读解析器统一保存在 `memory/` 中。

## 目录结构

```text
20260907-warrior/
├── README.md          # 归档说明与完整清单
├── tmp/               # 44 个临时工作文件
└── memory/            # 2 份项目记忆摘录、1 份解析器、1 份清单
```

## 完整文件清单

### 只读脚本（2 个）

| 文件 | 用途 | 大小（字节） |
|---|---|---:|
| [checkpoint.py](tmp/checkpoint.py) | 读取存档，输出人物、物品、地图与实体状态 | 6,874 |
| [yog-lines.py](tmp/yog-lines.py) | 读取尤格状态，计算激光全线、邻格与拳头状态 | 10,693 |

### 战术与交接笔记（28 份）

| 文件 | 大小（字节） |
|---|---:|
| [acid23-final-audit.md](tmp/acid23-final-audit.md) | 5,267 |
| [alchemy19-plan.md](tmp/alchemy19-plan.md) | 6,228 |
| [bright-and-victory-audit.md](tmp/bright-and-victory-audit.md) | 5,965 |
| [caves-notes.md](tmp/caves-notes.md) | 5,341 |
| [caves12-notes.md](tmp/caves12-notes.md) | 9,186 |
| [caves13-notes.md](tmp/caves13-notes.md) | 4,821 |
| [caves14-notes.md](tmp/caves14-notes.md) | 5,289 |
| [caves15-notes.md](tmp/caves15-notes.md) | 6,305 |
| [city16-notes.md](tmp/city16-notes.md) | 6,812 |
| [city17-notes.md](tmp/city17-notes.md) | 5,908 |
| [city18-notes.md](tmp/city18-notes.md) | 10,412 |
| [city19-notes.md](tmp/city19-notes.md) | 11,216 |
| [d23-finish-plan.md](tmp/d23-finish-plan.md) | 8,777 |
| [demon-halls-plan.md](tmp/demon-halls-plan.md) | 10,498 |
| [dm300-plan.md](tmp/dm300-plan.md) | 6,181 |
| [dwarf-king-plan.md](tmp/dwarf-king-plan.md) | 10,709 |
| [gladiator-notes.md](tmp/gladiator-notes.md) | 5,571 |
| [halls21-notes.md](tmp/halls21-notes.md) | 7,727 |
| [halls22-notes.md](tmp/halls22-notes.md) | 10,127 |
| [halls23-notes.md](tmp/halls23-notes.md) | 10,490 |
| [halls24-notes.md](tmp/halls24-notes.md) | 6,220 |
| [resume-d15-notes.md](tmp/resume-d15-notes.md) | 9,101 |
| [resume-d22-notes.md](tmp/resume-d22-notes.md) | 9,365 |
| [run-notes.md](tmp/run-notes.md) | 23,426 |
| [rusted-current-plan.md](tmp/rusted-current-plan.md) | 4,721 |
| [shop20-notes.md](tmp/shop20-notes.md) | 3,662 |
| [yog-d25-positions.md](tmp/yog-d25-positions.md) | 8,137 |
| [yog-final-plan.md](tmp/yog-final-plan.md) | 12,941 |

### 状态快照（14 份）

| 文件 | 大小（字节） |
|---|---:|
| [d13-snapshot-full.json](tmp/d13-snapshot-full.json) | 58,356 |
| [d14-snapshot-full.json](tmp/d14-snapshot-full.json) | 49,517 |
| [d15-snapshot-full.json](tmp/d15-snapshot-full.json) | 42,813 |
| [d16-snapshot-full.json](tmp/d16-snapshot-full.json) | 69,593 |
| [d17-snapshot-full.json](tmp/d17-snapshot-full.json) | 50,057 |
| [d18-snapshot-full.json](tmp/d18-snapshot-full.json) | 51,891 |
| [d19-snapshot-full.json](tmp/d19-snapshot-full.json) | 51,689 |
| [d21-snapshot-full.json](tmp/d21-snapshot-full.json) | 53,329 |
| [d22-snapshot-full.json](tmp/d22-snapshot-full.json) | 55,361 |
| [d23-acid-live.json](tmp/d23-acid-live.json) | 59,754 |
| [d23-return-live.json](tmp/d23-return-live.json) | 60,420 |
| [d23-route-snapshot.json](tmp/d23-route-snapshot.json) | 61,819 |
| [d23-snapshot-full.json](tmp/d23-snapshot-full.json) | 62,586 |
| [d24-plan-snapshot.json](tmp/d24-plan-snapshot.json) | 57,037 |

### 只读解析器（唯一副本）

- [只读解析器原文件副本](memory/extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md)
- 原路径：`/Users/shengyifan/.codex/memories/extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md`
- 这是通关时被 `checkpoint.py` 实际导入的依赖，归档中只保留 `memory/` 下这一份。

## 补充 memory 文件

[memory/](memory/README.md) 保存本次使用的项目记忆：从索引与系统摘要中整理出的 2 份 Shattered Pixel Dungeon 项目摘录，以及 1 份完整的只读解析器。公开归档仅保留本项目内容；清单区分读取、执行、系统注入和修改操作，并给出会话证据。

## 使用说明

- `tmp/` 中的历史脚本和笔记内容保持原样。`checkpoint.py` 记录的解析器路径仍是通关时的原记忆目录，`yog-lines.py` 仍引用当时的 `/tmp/spd-run-20260905`。这些是历史运行环境引用；若要脱离原环境执行归档脚本，需要另行适配路径。
- JSON 是当时读取存档生成的状态报告；笔记含当时的地图、坐标、阶段性方案与后来更正，不能作为新游戏的当前状态。

## 清点范围

除完整枚举本局临时目录外，还核对了本局主会话及 8 个子／孙会话中的临时路径，并检查脚本依赖、笔记互引和本局可视化目录（为空）。没有发现专用临时目录以外遗漏的本局临时工作文件。

会话中的屏幕图像与一次性工具调用没有另外保存为本局工作文件，因此不在原始 44 个文件中。正式游戏存档、排行榜、游戏源码、Codex 会话日志和通用运行时缓存也不是本次临时工作文件归档的内容。错误栈曾提及的 CUA `kernel.js`、`trusted-worker.js` 属于工具运行时，清点时已不存在。

本归档共 **49 个文件**：根目录 1 份 `README.md`，`tmp/` 中 44 个临时工作文件，`memory/` 中 2 份项目摘录、1 份解析器和 1 份清单。
