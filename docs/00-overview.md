# CCNR-RP 项目总览与任务分解（docs/00）

## 1. 项目定位

CCNR 服务器 RolePlay 模组（Forge 1.20.1）。世界观为"量子科学"设施（见《CCNR服务器职位划分》），
实现 **三大数据支柱**（阵营 / 职业 / 角色）与 **五大运行时系统**（状态 / 经验 / 事件 / 刷新 / 动画），
并在游戏结束时汇入结算与全流程闭环。

## 2. 技术栈

- Forge 1.20.1（47.2.0+），Java 17 字节码 / JDK 21 构建，Mojang 官方映射。
- 运行时零第三方强制依赖；**Corpse（henkelmax，modid=corpse）为可选联动**，未安装时降级为原生死亡。
- 配置与存档：JSON（Gson），位于 `config/ccnr_rp/`（管理配置）与 `world/ccnr_rp/`（运行时数据）。
- 风格：spotless（palantirJavaFormat + license 头）；语法检查：compileJava -Xlint:all；
  测试：JUnit5（`-PrunTests` 门控）；CI：GitHub Actions（build.yml）。

## 3. 包布局（com.ccnrcom.rp）

| 包 | 职责 | 阶段 |
| --- | --- | --- |
| `com.ccnrcom.rp` | 入口 `CCNRRPMod`，装配服务 | P0 |
| `config` | Forge serverconfig（调参） + JsonFileStore / 路径 / 原子写 | P0/P1 |
| `faction` | 阵营、关系、阵营组（`FactionGraph` 纯逻辑） | P1 |
| `profession` | 职业定义、装备 Loadout（NBT 存取）、`LoadoutManager` | P2 |
| `character` | `CharacterData`、`CharacterStore`（JSON 存档）、皮肤上传校验 | P3 |
| `status` | `CharacterStatus` 状态机、`StatusManager`（掉线判死等） | P4 |
| `corpse` | Corpse 可选联动桥（`CorpseBridge`，探测缺失降级） | P4 |
| `experience` | `LevelCurve`/结算器（纯逻辑）、`ExperienceService`（命令结算） | P5 |
| `event` | 事件定义、`TriggerEvaluator`（纯逻辑）、`EventManager` 生命周期 | P6 |
| `animation` | 动画序列解析（纯逻辑）、`AnimationEngine`（服务端决策+分发） | P7 |
| `spawn` | 刷新波定义、`WaveSelector`（纯逻辑）、`SpawnFramework`（自刷新/复活波/招募） | P8 |
| `command` | /rp 命令树与权限节点 | 各阶段 |
| `network` | SimpleChannel（ccnr_rp:main）与全部 C2S/S2C 包 | P3 起 |
| `client` | 客户端：角色管理界面、右侧招募列表 HUD、皮肤缓存、动画播放 | P3/P7/P8 |
| `util` | JsonUtils（原子写）、PermissionHelper、Lang 键 | P0 起 |

**分层原则**：涉及 MC 类型的代码收敛为薄适配层；规则类逻辑（曲线、图解析、评分、选人、触发器求值、动画
解析）必须是无 MC 依赖的纯类，便于 JUnit 直测（见 docs/01 测试规范）。

## 4. 文件与路径约定

| 路径 | 内容 | 管理员可编辑 |
| --- | --- | --- |
| `serverconfig/ccnr_rp-server.toml` | 调参（冷却时长、招募超时、经验权重、等级曲线、tick 间隔） | 是 |
| `config/ccnr_rp/factions.json` | 阵营 + 阵营组 + 关系 + 职业 + 装备 Loadout | 是 |
| `config/ccnr_rp/phases.json` | 游戏阶段表 | 是 |
| `config/ccnr_rp/events.json` | 事件定义与触发器 | 是 |
| `config/ccnr_rp/animations.json` | 动画序列 | 是 |
| `config/ccnr_rp/spawn_waves.json` | 刷新波定义 | 是 |
| `world/ccnr_rp/characters.json` | 角色数据（运行时） | 否 |
| `world/ccnr_rp/xp_ledger.json` | 结算账本 | 否 |
| `world/ccnr_rp/skins/<charId>.png` | 角色皮肤 | 否 |
| `config/ccnr_rp/skins-cache/（客户端）` | 皮肤缓存 | 否 |

- 启动时若 `config/ccnr_rp/*.json` 缺失，从 `assets/ccnr_rp/defaults/*` 写入精简样板默认版本（3 个样例阵营 + 4 个样例职业，供开箱演示与改造）。
- 所有运行时 JSON 采用"临时文件 + rename"原子写；解析失败写日志错误并跳过该文件，服务不崩溃。
- **数据库后端（v2.19.0+）**：启用后上述配置/运行时数据/serverconfig 调参/音乐图标改存数据库（表结构与命令见 [docs/12](docs/12-数据库设计.md)）；未启用时维持本表文件行为，零破坏。

## 5. 大任务 → 小任务分解（WBS 总表）

> 每阶段验收标准详见 docs/02~09 对应文档；提交信息用 `feat: Pn 系统名（阶段门）`。

### P0 工程初始化（本阶段）— docs/01
| # | 小任务 |
| --- | --- |
| 1 | build.gradle：spotless / JUnit5 / -Xlint:all / flatDir corpse |
| 2 | .editorconfig + license-header + .gitignore（libs/） |
| 3 | scripts/fetch-corpse.sh + .github/workflows/build.yml |
| 4 | mods.toml 可选 corpse 依赖 |
| 5 | 测试基线：LangFileTest + ProjectMetadataTest |
| 6 | docs/00~09 + AGENTS.md + README + 语言包扩充 |

### P1 阵营关系系统 — docs/02
| # | 小任务 |
| --- | --- |
| 1 | Faction/FactionGroup/RelationType/Relation 模型 + 默认配置 |
| 2 | FactionGraph：装载/校验/组批量解析/单点优先（纯类）|
| 3 | FactionManager：JSON 读写 + 重载 |
| 4 | /rp faction 命令族 + 权限节点 |
| 5 | 单元测试（解析/批量/优先级/非法输入）|

### P2 人物刷新配置 — docs/03
| # | 小任务 |
| --- | --- |
| 1 | ProfessionDefinition + 阵营归属 + selfDeploy |
| 2 | 装备序列化：ItemStack ↔ base64 NBT JSON（含物品槽位/护甲/副手）|
| 3 | /rp profession save/list/load + LoadoutManager 发放 |
| 4 | 配置校验（未知阵营/缺字段带行号报错）|
| 5 | 单元测试（NBT roundtrip / 校验失败路径）|

### P3 角色数据与角色管理界面 — docs/04
| # | 小任务 |
| --- | --- |
| 1 | CharacterData/CharacterStore（CRUD+原子写+损坏恢复 .bak）|
| 2 | 网络通道与全部角色 C2S/S2C 包 |
| 3 | 角色管理界面：列表/创建/编辑/冷却徽标 |
| 4 | 皮肤上传：本地选择→分包上传→校验→存储→广播→客户端缓存 |
| 5 | /rp character 命令族 |
| 6 | 单元测试 + GUI 手工演练 |

### P4 角色状态系统 + Corpse — docs/05
| # | 小任务 |
| --- | --- |
| 1 | 状态机（alive/dead/observing）+ 迁移表 |
| 2 | 掉线/踢出 → 判死（含日志与钩子）|
| 3 | CorpseBridge：探测→生成/定位遗体→可搜刮；缺失→原生死亡降级 |
| 4 | 死亡冷却（serverconfig）+ /rp state 、/rp kill |
| 5 | 状态事件广播（动画/经验/刷新池）|
| 6 | 状态机单测 + 掉线判死集成演练 |

### P5 经验系统 — docs/06
| # | 小任务 |
| --- | --- |
| 1 | LevelCurve（xpForLevel/level(xp) 纯类）|
| 2 | 登记源：值班时间（tick 累加）、任务行为（markTask）、疏散方式 |
| 3 | 结算器：/rp settle + 死亡退场结算 + 幂等 |
| 4 | 账本 xp_ledger.json + /rp xp/level/evac set |
| 5 | 升级广播 + level_up 动画钩子 |
| 6 | 曲线/加权/幂等单测 |

### P6 事件系统 — docs/07
| # | 小任务 |
| --- | --- |
| 1 | EventDefinition/Triggers/阶段表（phases.json）|
| 2 | TriggerEvaluator 纯类（阶段/时间点/周期/条件）|
| 3 | EventManager 生命周期（SCHEDULED→RUNNING→SETTLED）|
| 4 | 事件开始/结束钩子（动画、刷新波接口、经验结算）|
| 5 | /rp event、/rp phase 命令族 |
| 6 | 触发器与状态机单测 |

### P7 动画系统 — docs/08
| # | 小任务 |
| --- | --- |
| 1 | 序列/步骤模型解析（纯类）+ 校验（未知类型拒载）|
| 2 | 步骤执行器：TITLE/ACTIONBAR/FADE/CAMERA/PARTICLE/SOUND |
| 3 | 钩子注册：player_spawn/player_death/game_end/event_start/level_up |
| 4 | AnimationEngine 服务端决策 + AnimationPlayS2C + 客户端执行 |
| 5 | /rp animation play 调试命令 |
| 6 | 解析/步进单测 + 各钩子手工演练 |

### P8 人物刷新框架 — docs/09
| # | 小任务 |
| --- | --- |
| 1 | SpawnWaveDefinition + 配置 + 校验 |
| 2 | WaveSelector 纯类：阴间池优先→等级→冷却→随机；数量不足回退 |
| 3 | 自刷新：GUI 部署 → 校验（selfDeploy/状态/冷却）→ 发放装备+传送 |
| 4 | 复活波：20t 轮询 scoreboard 团队 diff → 选取 → 复活 |
| 5 | 招募兜底：RecruitOfferS2C + 右侧列表 HUD + 超时/接受/拒绝 |
| 6 | /rp spawn 命令族 |
| 7 | 选人/超时/接受流程单测 + 全链路演练 |

### P9 集成验收与收尾 — docs/00 §7
| # | 小任务 |
| --- | --- |
| 1 | 全流程演练（测试服，P9 手册）|
| 2 | 命令树/权限/语言包对齐回归 |
| 3 | README 用户与管理手册、CHANGELOG.md、版本号 1.0.0 |
| 4 | clean build + test -PrunTests + CI 全绿 |

## 6. 依赖与并行性

- 硬顺序：P1 → P2 → P3 → P4 → P5 → P6 → P8（P7 动画机制可在 P4 完成后并行开做，钩子签名先冻结在 docs/08）。
- 接口冻结：P6 的"事件开始 → 刷新波"仅预留接口；P7 的钩子签名是所有系统的公共契约，改动须先改 docs。
- 每阶段结束跑该阶段验收项，失败不进入下一阶段（见 docs/01 §6 闸门流程）。

## 7. 分阶段验收总闸门（P0–P9）

| 阶段 | 闸门要点（详版见 docs/02~09） | 版本 |
| --- | --- | --- |
| P0 | clean build ✓；spotlessCheck ✓；test -PrunTests ✓；docs/00~09 + AGENTS.md 齐备 | 0.1.0-alpha |
| P1 | 阵营/组/关系解析与命令落盘重载，单测绿 | 0.2.0 |
| P2 | 装备 NBT 存取 roundtrip，save/load 一致 | 0.3.0 |
| P3 | 角色 CRUD+皮肤上传+GUI 全流程 | 0.4.0 |
| P4 | 状态机全迁移+掉线判死+Corpse 可选降级 | 0.5.0 |
| P5 | 三来源结算+幂等+自动结算 | 0.6.0 |
| P6 | 触发器求值+事件生命周期+阶段推进 | 0.7.0 |
| P7 | 五种钩子动画播放全通路 | 0.8.0 |
| P8 | 自刷新/复活波/招募三通路 | 0.9.0 |
| P9 | 全流程演练 100% → 1.0.0 | **1.0.0 ✅（已交付）** |

## 8. 命令树总览（分阶段启用）

`/rp help`、`/rp character …`、`/rp state <player>`、`/rp kill <player>`、
`/rp settle …`、`/rp evac set …`、`/rp xp <player>`、`/rp level <player>`、
`/rp faction …`、`/rp profession …`、`/rp event …`、`/rp phase …`、
`/rp animation play …`、`/rp spawn …`。玩家命令无需权限；管理命令 OP≥2 或 `ccnrrp.admin.*` 节点。
