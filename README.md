<div align="center">

# CCNR-RP 角色扮演模组（Forge 1.20.1）

CCNR 服务器 RolePlay 模组：围绕 **量子科学设施（CCNR 机构）** 世界观，提供
阵营关系 / 职业与装备 / 角色管理 / 状态与遗体 / 经验结算 / 阶段事件 / 动画演出 / 人物刷新 八大系统，
并以 **"CCNR:NET 编制席位"终端**（黑白灰军用终端，对齐在线管理面板）作为核心界面：
顶部机构导轨 + 席位卡网格 + 底部席位终端抽屉。

[![Release](https://img.shields.io/github/v/release/bananaxiao2333/CCNR-RP?label=Release&color=brightgreen)](https://github.com/bananaxiao2333/CCNR-RP/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/bananaxiao2333/CCNR-RP/build.yml?branch=main&label=Build)](https://github.com/bananaxiao2333/CCNR-RP/actions)
[![License](https://img.shields.io/github/license/bananaxiao2333/CCNR-RP?label=License)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-important)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.2.0%2B-orange)](https://files.minecraftforge.net)

</div>

## 功能模块

当前版本（`2.26.11`）已实现 **P0–P9 全流程闭环**：事件 → 刷新波/招募 → 部署 → 判死 → 冷却 → 复活 → 结算。

| 系统 | 说明 | 设计文档 |
| --- | --- | --- |
| P1 阵营关系 | 敌对/中立/友好；阵营组批量声明 | [docs/02](docs/02-faction.md) |
| P2 人物刷新配置 | 管理员保存背包+装备(含 NBT)；职业挂在阵营下 | [docs/03](docs/03-profession.md) |
| P3 角色管理 | 游戏内界面创建角色/上传皮肤/死亡冷却 | [docs/04](docs/04-character.md) |
| P4 状态+遗体 | 掉线判死、遗体、冷却、状态机 | [docs/05](docs/05-status-corpse.md) |
| P5 经验系统 | 值班时间/任务行为/疏散方式 → 命令结算 → 等级 | [docs/06](docs/06-experience.md) |
| P6 事件系统 | 阶段时间点/条件触发；事件生命周期 | [docs/07](docs/07-event.md) |
| P7 动画系统 | 数据驱动表现层动画；出生/死亡/结局/事件钩子 | [docs/08](docs/08-animation.md) |
| P8 刷新框架 | 自刷新 + 复活波 + 屏幕右侧招募列表 | [docs/09](docs/09-spawn.md) |
| P15 模式编排 | 模式文件夹化 + 热切换 + 条件驱动阶段 | [docs/15](docs/15-模式编排与多模式设计.md) |
| P16 玩家属性 | 阵营属性（含 FirstAid 解耦适配），按阵营在管理面板编辑 | [docs/16](docs/16-玩家属性.md) |
| P17 自定义设定 | 全局变量（bool/number/text）+ 每变量预设值 + 整套预设方案；命令/GUI 双通道 CRUD | [docs/17](docs/17-自定义设定.md) |

> ▶ 完整玩家指南（安装 / K 键终端 / 全部指令 / 配置 / 常见问题）：[docs/10-使用指南.md](docs/10-使用指南.md)

## 特色亮点

- **「编制席位」终端**（按 `K` 打开）：顶部**横向机构导轨**（圆形徽章芯片，滚轮横移，按 tier 灰阶——⬜ 亮=1 级 / 🌫 中灰=2 级 / ⬛ 暗灰=3 级）→ 中部**席位卡网格**（每张卡＝一个可部署职位：序号码块 / 机构徽章 / 等级门控标签 / 在职·编制读数 / 紧凑装备图标条，左缘状态条白=可部署·红=编制满·灰=等级未达）→ 底部**席位终端抽屉**（选中职位的 3D 立绘 + 身份读数 + 装备槽 + 职业画像 + 部署按钮）。方向键可在网格内移动选中，滚轮翻行。
  结构与旧版"左导航｜中列表｜右预览"三栏布局彻底脱钩，规范见 [docs/14](docs/14-界面主题设计.md) §5.6。
- **完整状态机**：观察 → 激活 → 部署 → 存活 → 死亡 → 冷却 → 复活；掉线判死、复活后强制旁观者、冷却锁定，均由服务端强制。
- **服务器素材中央下发**：阵营出场音乐与徽章图标全部由服务器控制，客户端进服按哈希自动增量下载缓存，无需手动分发。
- **部署入场电影**：先播 CMDCam 标题文字电影，动画完毕后再传送出生点；出场音乐按 **启动器指定 > 职业 > 阵营** 优先级解析。
- **数据库后端（v2.19.0+）**：数据可持久化到 SQLite（默认内嵌）或 MySQL（可选）——配置文档 / 用户档案（挂起经验）/ 调参 / 素材（音乐·图标）均可入库，支持多配置档热切换；未启用时维持原有文件存储。详见 [docs/12-数据库设计.md](docs/12-数据库设计.md)。
- **Corpse 可选联动**：掉线判死 → 可搜刮遗体（未安装则降级原生死亡）；**死亡背包处置自洽**——原版在 `keepInventory=true`、死亡瞬间旁观、离线判死三类路径上都不会爆出背包，本 mod 显式补位清空（2.24.0）。
- **阵营属性（2.24.0）**：管理面板按阵营配置血量/护甲等属性（原版与 mod 属性同注册名同路径），部署时套用（出门即满状态）；FirstAid 等 mod 走**零编译依赖**的可选适配，未装不影响任何路径。
- **删除交互（2.25.1）**：管理面板所有删除统一为「右键条目 + 二次确认」（Esc/取消不落盘），删除按钮与右键同一入口；规范见 [docs/01-工程规范.md](docs/01-工程规范.md) §10.2。
- **自定义设定与外部功能接口（2.25.0）**：管理面板「自定义设定」页签 / `/rp var` 定义变量与预设，外部功能按 id 只读取值（`VariableService.raw/bool/number/text`），依赖方向单向（外部 → 本 mod）；`/rp var get <id>` 只输出裸值，命令方块可直接消费。
- **界面视觉整备（2.24.1）**：全部 GUI 收敛到 `RpTheme` 单一令牌体系——浮层卡片 / 控件 / 遮罩 / 滚动条 / 徽章 / 列表行 / 下拉 / 补全浮层各有唯一画法，散落裸色值清零；同时修掉 K 面板部署确认「白底白字」不可读、各页签列表行悬停/斑马纹不一致、击杀者关系色与关系图不同源等问题。详见 [docs/14-界面主题设计.md](docs/14-界面主题设计.md)。
- 支持中英文界面语言（游戏语言自动切换）。

## 安装（客户端 + 服务端）

1. 使用 Forge `47.2.0+`（Minecraft **1.20.1**）启动器（如 HMCL / 官方启动器），把 `ccnr_rp-2.26.11.jar` 放入 `mods` 目录。
2. 首次启动自动生成默认配置：`config/ccnr_rp/` 下的 `factions.json` / `phases.json` / `events.json` / `animations.json` / `spawn_waves.json` / `variables.json`（含精简样板：3 个样例阵营、4 个样例职业、3 个样例变量）。
3. 按 `K`（可在按键设置里改键，类别 **CCNR-RP → 角色管理界面**）打开角色管理终端。
4. 连远程服务器时，**服务器与客户端都要装同一版本**，阵营/职业/阶段/事件等以**服务器**配置为准。

> **依赖**：`corpse` 为可选依赖（`mandatory=false`），缺失时自动使用原生死亡降级；除 Forge / Minecraft 外运行时零强制第三方依赖。

## 配置

| 层 | 路径 | 内容 | 可编辑 |
| --- | --- | --- | --- |
| 调参 | `serverconfig/ccnr_rp-server.toml` | 冷却时长、招募超时、经验权重、等级曲线、tick 间隔 | 是 |
| 管理定义 | `config/ccnr_rp/factions.json` | 阵营 / 阵营组 / 关系 / 职业 / 装备 Loadout | 是 |
| 管理定义 | `config/ccnr_rp/phases.json` | 游戏阶段表 | 是 |
| 管理定义 | `config/ccnr_rp/events.json` | 事件定义与触发器 | 是 |
| 管理定义 | `config/ccnr_rp/animations.json` | 动画序列 | 是 |
| 管理定义 | `config/ccnr_rp/spawn_waves.json` | 刷新波定义 | 是 |
| 运行时 | `world/ccnr_rp/` | 角色数据 / 经验账本（原子写，损坏保留 .bak） | 否 |
| 数据库 | `config/db.properties` | `db.enabled=true` 时启用数据库后端（`db.type=sqlite\|mysql`） | 是 |

缺失或为空时自动回退内置默认值（`assets/ccnr_rp/defaults/`）。

## 指令（`/rp`）

管理类子命令需要管理员权限（op）。完整清单见 [docs/10-使用指南.md](docs/10-使用指南.md)。

| 分类 | 示例 |
| --- | --- |
| 阵营与关系 | `/rp faction relation <a> <b> set <hostile\|neutral\|friendly>`、`/rp faction group relation <g1> <g2> <type>` |
| 职业 | `/rp profession list`、`/rp profession save <id> [--hotbar\|--full]`、`/rp profession load <id>` |
| 角色 | `/rp character create <name> <faction> <profession> [background]`、`/rp character select\|delete\|observe\|activate <id>` |
| 状态与经验 | `/rp state`、`/rp kill <玩家>`、`/rp xp add <玩家> <值> <标题>`、`/rp settle all` |
| 事件与阶段 | `/rp event trigger <id>`、`/rp phase set <id>`、`/rp gameover` |
| 动画与刷新 | `/rp animation play <id>`、`/rp spawn trigger <id>` |
| 数据库 | `/rp db status\|test\|migrate\|export\|flush\|connect\|profile` |

## 构建

本地构建已配置国内镜像：Gradle 发行版走腾讯云，Maven Central 走阿里云。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/bananaxiao/Documents/MirageV/mod/CCNR-Com/.gradle-home   # CCNR 系列共享缓存（.gradle-home 惯例不提交）
./scripts/fetch-corpse.sh   # 首次构建前取 Corpse 编译期依赖（libs/，已存在则跳过）
./gradlew build             # 语法(-Xlint:all) + 风格(spotlessCheck) 门禁
./gradlew test -PrunTests   # 单元测试（离线默认不跑；CI 强制）
./gradlew runServer / runClient
```

产物：`build/libs/ccnr_rp-2.26.11.jar`。工程规范见 [docs/01](docs/01-工程规范.md)，开发注意见 [AGENTS.md](AGENTS.md)。

## 持续集成

[GitHub Actions](.github/workflows/build.yml)：`main` push / PR / `v*` 标签时执行 构建 + spotlessCheck + 单测并上传产物；打 `v*` 标签时自动把 jar 附加到对应 GitHub Release。

## 技术栈

- Forge 1.20.1（47.2.0+），Java 17 字节码 / JDK 21 构建，Mojang 官方映射
- 运行时零第三方强制依赖；**Corpse（henkelmax，modid=`corpse`）** 为可选联动
- 配置与存档：JSON（Gson），`config/ccnr_rp/`（管理配置）与 `world/ccnr_rp/`（运行时数据）
- 数据库（v2.19.0+）：JDBC 微 ORM（SQLite 默认 / MySQL 可选），`com.ccnrcom.rp.data`（见 docs/12）
- 风格：spotless（palantirJavaFormat + license 头）；测试：JUnit5（`-PrunTests` 门控）；CI：GitHub Actions

## 目录

- `src/main/java/com/ccnrcom/rp/`——模组逻辑（按系统分包）
- `src/test/java/com/ccnrcom/rp/`——JUnit5 单元测试
- `config/ccnr_rp/`——管理配置（阵营/职业/事件/阶段/动画/刷新波，JSON）
- `world/ccnr_rp/`——运行时数据（角色/账本/皮肤）
- `docs/`——设计文档与任务分解

## License

MIT
