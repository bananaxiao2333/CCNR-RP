# CCNR-RP 数据库 Web 管理后端 · 项目计划与工程规范（docs/13）

> 目标：为 CCNR-RP 的数据库后端（docs/12 的 `com.ccnrcom.rp.data` 表结构）做一个 **uv 管理的 Python 管理后台（面向管理员的可交互 Web 面板）**——
> 管理员通过面板**实时**读写数据库、管理配置档/配置/素材/运行时数据/调参；真实数据、无假代码/占位，并以「版本一致性」为中心防止因版本管理不当导致数据错乱。
> 说明：此处「后端」＝管理员的管理入口，交付物是**管理后台面板**（FastAPI 服务 API + 静态前端），而非无界面的后端服务。
> 本文档整合既有工程规范（docs/00/01/11/12、AGENTS.md）与历次开发经验教训，作为本新项目的准绳。

---

## 1. 目标 / 范围 / 不做的事

**目标**：提供一个网页（后端为主，前端服务于管理）：
- 连接并**实时**读取/写入 CCNR-RP 数据库（默认 SQLite，兼容 MySQL）。
- 管理**配置档**（多套命名配置，启用/切换）、**配置文档**（factions/settings/phases/events/spawn_waves/sequences/experience_rules/limits/animations）、**素材**（音乐/图标 BLOB）、**运行时数据**（users/pending_notices/team_waves_done）、**调参**（server_settings）。
- 所有读写落到**真实数据库**；界面显示真实数据，无 Mock/占位/假数据。
- **保证版本一致性**：写操作绝不让数据因版本管理错误而损坏（见 §6）。

**范围**：服务端 API + 管理 UI；与 CCNR-RP 模组共用同一数据库。**服务端为配置权威**，模组为运行时权威。

**不做（边界）**：
- 不重写模组的游戏内管理面板/网络/渲染；不改 CCNR-Com / CC-api。
- 不替代模组的运行时权威决策（部署/状态机/结算等）；Web 只管**数据**，不越权改运行时逻辑。
- 不做客户端（MC 端）改动。
- Web 端不「猜」运行时状态持久化为权威——运行时数据只有模组能产生权威值，Web 端读写需遵循 §6.4 的归属约定。

---

## 2. 技术栈（uv 依赖管理）

- **Python 3.11+**，项目根用 `pyproject.toml` + `uv`（`uv sync` / `uv run <cmd>` 唯一入口）。
- **FastAPI**（ASGI）+ **Pydantic v2**（请求/响应 schema 与校验，`model_config` 用 strict/extra=forbid 防错字段）。
- **SQLAlchemy 2.0**（`Dataclass`/`Mapped` 映射；默认同步 + `sqlite://`；MySQL 用 `pymysql`）。连接字符串由环境变量/配置文件提供，密码从环境变量读、日志掩码。
- **WebSocket**（FastAPI `/ws`）或 **SSE** 做实时推送；服务端变更 → 广播；外部（模组）改写 → 轮询/通知后推送。
- **Alembic**（迁移）——管理 `schema_version` 阶梯，**不改动已存在的 v1 结构**（兼容模组 `DbSchema`）。
- **ruff**（lint + format，替代 Java 的 spotless）+ **mypy**（类型）+ **pytest** + **httpx**（API 测试）。
- 后端依赖：fastapi、uvicorn、sqlalchemy、pydantic、pymysql、alembic；测试：pytest、httpx、pytest-asyncio。

### 2.1 目录结构（规划，执行期创建）
```
ccnr-rp-web/
  pyproject.toml            # uv 管理；[tool.uv] 运行入口
  alembic.ini  alembic/     # 迁移脚本（版本阶梯，只进不改）
  app/
    __init__.py
    main.py                 # FastAPI 装配 + lifespan（启动校验 schema_version）
    settings.py             # DB 连接 + 期望 schema 版本 + 密钥（环境变量）
    db.py                   # engine/session + 版本护栏
    rp/
      models.py             # ORM 映射（对齐 DbSchema）
      schemas.py            # Pydantic 请求/响应
      config.py             # 配置文档 CRUD + 配置档 + 校验对齐 + CAS
      assets.py             # 素材 BLOB 读/传
      runtime.py            # users/pending_notices/team_waves_done/server_settings
      validators.py         # 镜像模组纯逻辑校验（FactionGraph/EventModels/DeployLimits/ExperienceRule/ItemStackCodec/SpawnModels）
      version.py            # 版本一致性（schema + config version + CAS + 引擎契约）
    api/                    # 路由（rest + ws）
    ws/                     # 实时广播（变更 → 推送；外部变更检测）
  tests/                    # pytest；用临时 SQLite + 模组 schema fixture；禁 Mock
  docs/                     # 本项目的 why/边界/如何失效
```

---

## 3. 工程规范（Python 侧，对齐 CCNR-RP 纪律）

- **唯一格式化/风格入口**：`ruff format --check` + `ruff check`（`uv run`），CI 强制；类型 `mypy`。
- **类型安全**：Pydantic schema + SQLAlchemy `Mapped` + 类型标注；禁止裸 `dict[str, Any]` 横穿系统边界；JSON 字段限定结构（`JsonObject`/`Literal`）。
- **服务端权威**：API 校验一切（权限/参数/归属/边界）；不信任客户端提交的最终状态；写回前重新校验。
- **无假代码/占位/伪异步**：禁止空壳 `pass`、`NotImplemented`、永远是 `True` 的分支、超时兜底掩盖状态错误、只为过编译的假逻辑；新增公共接口必须有真实调用方。
- **对称清理**：连接池/生命周期/后台任务/连接对称创建释放（FastAPI `lifespan`）。
- **文档写「为什么/边界/如何失效」**，不重复代码表面。
- **版本纪律**：每次改动（功能/修复/文档）→ 测试 → `bump` 版本（pyproject `version`）→ CHANGELOG 顶部补条目 → git 提交（单一职责，信息 `feat|fix|docs: vX.Y.Z <描述>`，Git 操作限任务授权）。
- **验证>声称**：编译/测试通过≠正确；未能在环境验证的必须明说（docs/01 §9.6）。提交前查 `git diff`/status，无临时文件/构建产物/无关改动混入。

---

## 4. 架构

```
[浏览器 UI] ⇄ REST/WS（FastAPI）→ rp/（config/assets/runtime/version）→ SQLAlchemy → SQLite/MySQL
        ↑ 实时推送(WS)                                    ↑ 外部改动检测(轮询/通知)
```

- **只读**：配置文档（按配置档）、素材清单/预览、运行时数据、调参。
- **写**：配置文档（带校验 + CAS 乐观锁）、素材（上传/删除）、配置档（切换/新建）、运行时数据（按 §6.4 归属）、调参。
- **实时**：Web 端自己的变更 → 立即回推所有 WS 客户端；外部（模组）对 DB 的改动 → 周期性（或 WAL/触发器通知）检测 `config_documents.max(updated_at)` / `meta.config_reload_seq` 变化 → 推送全量刷新。

---

## 5. 与模组的数据契约（共用同一 DB）

- 表结构 = 模组 `DbSchema`（docs/12 §3），**Web 端不改列/不改 v1 结构**；新能力走 **Alembic 新增列 + schema_version 前置增长**，并需模组端同步（升级契约先改 docs 再改两端）。
- **配置文档**：`config_documents(profile, config_key, json, updated_at)`，`json` 为完整 JSON 文本（Gson 序列化，含顶层 `"version"`）。Web 端读写以「整份文档」为单位（REST 粒度 = config_key），REST 内部的字段级编辑在 service 层做「读-改-写」合并。
- **素材**：`assets(name, kind(music|icon), data BLOB, size, sha256, updated_at)`。
- **运行时**：`users`/`user_pending_xp`/`pending_notices`/`team_waves_done`（规范化）+ `server_settings(profile,key,value,type)`。
- **配置档**：`config_profiles` + `meta.active_profile`。

---

## 6. 版本一致性（核心护栏：别让版本管理导致数据错乱）

> 数据错乱的来源：① 模组/Web 用不同结构写同一配置；② 两方并发写导致后写覆盖先写；③ Web 端把未知/不认识的字段或版本号改掉；④ Web 端写入模组无法解析的结构使模组回退空数据；⑤ 用了不匹配的 schema 迁移。

### 6.1 Schema 版本护栏
- Web 声明 `EXPECTED_SCHEMA_VERSION=1`。启动在校验 `schema_version`；缺失或不匹配 → 只读模式 + 明确报错，**绝不自动改写一个它不认识的库**。
- 参与有既有 DB：显示「schema_version=1（匹配）」或「≠1（拒绝写，提示迁移）」。

### 6.2 配置内容版本与结构保留
- 配置 JSON 顶层 `"version"` 由 Web **只读保留、绝不改写**（模组 loader 依赖它做后向兼容）。
- 写入采用**结构性保留**：把现有文档反序列化 → 仅更新被编辑字段 → 重新序列化；**未编辑字段、未知字段（如 music/profile/cmdcamScene/radio/spawn/radioDisabled）原样保留**（对应 docs/01 §11.1 与 docs/11 §9.1「单字段接管，勿吞字段」）。
- 禁用「空串/缺省参数做全量 upsert 清字段」这类静默吞字段写法（唯一例外字段需显式声明）。

### 6.3 并发防护（乐观锁 CAS）
- Web 读取返回 `updated_at`；写时：`UPDATE config_documents SET json=?, updated_at=? WHERE profile=? AND config_key=? AND updated_at=?`。
  - 影响行数 = 0 → **409 Conflict**（内容已被模组/其它 Web 会话修改），返回最新版本供重新合并，**拒绝静默覆盖**。
- 配置档切换写 `meta.active_profile` 用条件更新 + 事务；profile 内容天然按 PK 隔离，切换不混文档。
- 多文档写（如导入整套配置）用单事务 + 全有或全无。

### 6.4 运行时数据归属（谁写谁为权威）
- 运行时权威在**模组**（状态机/部署/结算）。Web 端原则**只读为主**；确需写（手动调参、开发/迁移）走受控写 + 明确标注，且不得绕过模组的幂等/状态约束。
- `server_settings` 写后：建议模组端 `applyDbOverrides()` 热更新（或重启生效），Web 端提示「写入即改库；模组运行时值按 docs/12 §7 applyDbOverrides 生效」。

### 6.5 变更同步（web ↔ 模组 ↔ web）
- Web 写配置文档 → 成功即回推 WS 客户端 + `meta.config_reload_seq += 1`（模组若实现轮询则据此重载；未实现则提示「重启/手动 reload 生效」）。不在本文档承诺改动模组——此为**可选握手**，默认 Web 端只对 DB 负责。
- 模组/外部写 → Web 端定时（如 2s）或基于 SQLite 变更检测，刷新「文档最后修改时间/内容」并推送。

---

## 7. 校验逻辑对齐（关键：别写坏模组读不了的配置）

Web 端保存/导入前，必须镜像模组的**纯逻辑校验器**（docs/02~09 的纯类，已在模组内 JUnit 覆盖）：
- **factions.json**：id 合法性（小写/数字/下划线 ≤32）、faction/group 存在性、relation 方向与类型（hostile/neutral/friendly）、组不可嵌套组、关系 inside 语义、profession 的 factionId 存在。
- **spawn_waves.json**：wave id、mode(SELF_DEPLOY/RESURRECTION/BOTH，兼容 RECRUIT 映射)、teamIds/professionIds/factionIds 存在性与引用、count/minLevel 范围、deployAt 类型。
- **events.json / phases.json**：event 触发器类型/字段、stage 引用、hooks.startAnimation/spawnWave 引用、phase order/durationMinutes。
- **experience_rules.json**：eventId ∈ 注册表、表达式语法+类型（condition/value/title，避免除零/类型不匹配）、id 唯一。
- **limits.json**：type(GLOBAL/FACTION/PROFESSION)、limit≥0、target 引用。
- **sequences.json / animations.json**：步骤类型注册、duration 越界钳制、lanng 键存在性。
- **professions 装备 loadout**：槽位/物品 ResourceLocation/count/NBT base64 结构。

实现：`rp/validators.py` 用**与模组相同规则**做结构校验（复用模组语义，不重造新 schema），输出「字段:原因」；校验失败拒绝写库（返回 422 + 精确原因），**绝不写一个模组解析不了的结构**。

---

## 8. 实时读写的 API 设计（示例，非假代码）

- `GET /api/profiles`（列表+active）、`POST /api/profiles/{id}/activate`（事务切换 + 全量回推）
- `GET /api/config/{key}`（按 active profile 读整份 JSON + updated_at）、`PUT /api/config/{key}`（service 层读-改-写 + CAS + 校验）、`GET /api/config`（清单）
- `GET /api/assets`（清单：name/kind/size/hash）、`GET /api/assets/{name}`（BLOB），`POST /api/assets/{name}`（上传，校验类型/大小/格式：OGG 魔数 / PNG 规格）
- `GET /api/runtime/users`、`PATCH /api/runtime/users/{uuid}`（受控写 + 标注）、`GET /api/runtime/notices`、`GET /api/runtime/teams`
- `GET/PUT /api/server-settings`（按 profile，写后提示 applyDbOverrides）
- `WS /ws`（推送：config_updated / profile_switched / assets_updated / runtime_updated）
- `GET /api/health`（DB 连通 + schema_version 匹配 + 只读/读写模式）

---

## 9. 测试策略（无假代码，真实校验）

- **pytest**（`uv run pytest`）：不 Mock 业务逻辑；集成用**临时 SQLite + 模组 schema fixture**（运行后即得真实结构）。
- **校验契约测试**：同一输入同时喂 Web `validators.py` 与模组纯逻辑（跨语言以「同一批样例数据等价通过/拒绝」为准），确保 Web 写出的能被模组解析。
- **版本一致性测试**：① schema_version=1 时放行写、≠1 时拒绝写；② 改 non-version 字段后 `"version"` 与未知字段仍保留；③ 并发 CAS 冲突 → 409 且不覆盖；④ invalid 结构 → 422 且库不变。
- **实时测试**：Web 写 → WS 收到推送；外部改 DB → 检测到并推送（如需）。
- 门禁：`ruff check` + `ruff format --check` + `mypy` + `pytest` 全绿。

---

## 10. 分阶段里程碑（看板任务已对应）

| 阶段 | 内容 | 验收（DoD） |
|---|---|---|
| **WP0 项目初始化** | uv + FastAPI 骨架 + lifespan + DB 连接与 schema 护栏 + `/api/health` + 配置/素材/运行时**只读**接口 | 连上真实库（模组 DbSchema），只读展示真实数据；schema 不匹配 → 拒写；ruff/pytest 入口可用 |
| **WP1 配置文档 CRUD + 配置档** | REST 读-改-写 + CAS + `validators.py` 对齐 + `profile activate/create` | 编辑真实配置保存可被模组解析；未知字段/version 保留；CAS 冲突返回 409；切换事务原子 |
| **WP2 运行时数据读写** | users/notices/teams/server_settings 受控读写 | 读真实数据；受控写带标注；server_settings 写后提示 applyDbOverrides |
| **WP3 素材管理** | assets 清单/BLOB/上传/删除 + 格式校验 | 真实 OGG/PNG 上传入库（sha256+size），清单/预览为真实字节 |
| **WP4 实时推送** | WS + 外部变更检测；`meta.config_reload_seq` 握手 | Web 写→WS 实时刷新；外部改→检测推送；无轮询假数据 |
| **WP5 安全/收尾** | 认证（API key/管理登录）+ 导出/导入 + 迁移工具 + 文档/CHANGELOG/版本号 + 全量测试 | 权限护栏；导入导出幂等；ruff/mypy/pytest 全绿；README/CHANGELOG 更新 |

---

## 11. 风险与边界

- **模组内存缓存与 Web 改库的时差**：Web 改 doc 不即时反映到模组；用 `config_reload_seq` 握手（当前文档只对 DB 负责，模组侧握手为可选项并在 UI 明示「需 reload/重启」）。
- **并发**：CAS 防止丢更新；若模组写频率高，冲突会频发 → 前端做「冲突则合并重试」引导，不静默覆盖。
- **BLOB 大**：素材上传限流/分片；清单不携带 data 防大响应。
- **SQLite vs MySQL**：统一 SQLAlchemy + 事务；方言差异最小化（TEXT/INTEGER/BLOB、布尔 0/1）。
- **迁移**：Alembic 只对后续版本生效；**绝不**改动 v1 列，避免与模组 DbSchema 冲突。
- **不做**：不实现模组运行时逻辑、不改客户端、不越权持久化运行时权威状态。

---

## 12. 一条硬性约定（避免数据错乱的底线）

> **任何写库前，先校验 schema_version 匹配 + 该配置能被「模组的同规则校验器」通过；写入用「结构性保留 + CAS 乐观锁 + 原子事务」；未知字段与 config `version` 永不触碰。做不到这三条的任何一条，就拒绝写并明确报错——绝不静默覆盖或落一个读不回来的结构。**

---

## 13. 管理后台（面板）界面设计

> 面板由 FastAPI 托管，**无 node 构建**：前端为静态单页（vanilla JS + Alpine.js + 轻量 CSS，第三方库随服务静态托管或 vendored），依赖全部走 `uv`；与 API 走 fetch + WebSocket。**禁止前端假数据/占位渲染**——空数据展示「空」态、加载态反映真实请求、错误态给出可操作提示。

### 13.1 全局框架
- **顶部**：DB 状态（sqlite/mysql、schema_version 匹配？、连接掩码）、**当前配置档**（active + 切换/新建）、健康/错误提示。
- **版本护栏横幅**：schema_version ≠ EXPECTED → 顶部红横幅「数据库版本不匹配，仅只读」，禁用一切写按钮；配置 JSON 顶层 `version` 与未知字段在编辑器中**只读展示**（灰显、不可改），从源头防止误改。
- **实时**：连上 `/ws`；任何写成功或外部变更 → 列表/表单自动刷新；写遇 **409** → 弹「内容已被他人修改」，加载最新版让管理员重新合并，**绝不静默覆盖**。

### 13.2 面板页面
1. **登录/鉴权**：管理登录 / API key；未授权跳转登录页。
2. **仪表盘**：各表计数（config/assets/users…）、active 配置档、schema/健康、最近变更时间。
3. **配置档管理**：list / create / activate / 标记 active；切换后所有配置页随之切换。
4. **配置编辑**（每 config_key 一页，表单 + JSON 双视图并可切换）：
   - 阵营/职业（factions.json：职业、阵营、关系、阵营组、装备/复活点/音乐/CMDCam/无线电——按 §7 校验）。
   - 事件/阶段/刷新波/限制（events / phases / spawn_waves / limits）。
   - 经验规则（experience_rules）、序列（sequences）、动画（animations）。
   - 设置（settings.json）。
   - 校验失败 → 表单内联「字段:原因」，不保存。
5. **素材管理**：音乐(.ogg) 列表/播放/上传/删除；图标(.png) 预览/上传/删除；上传带格式/大小校验与 sha256。
6. **运行时数据**：users（含 pendingXp）只读 + 受控写标注；pending_notices / team_waves_done 查看；server_settings 表格编辑（写后提示 applyDbOverrides）。
7. **导出/导入/迁移**：导出当前库、导入（幂等）、本地→库迁移（对应 `/rp db migrate` 语义）；全程事务 + CAS。

### 13.3 前端工程约束
- 无 node/npm；前端 JS/CSS 静态随服务托管；第三方（Alpine、CSS）vendored 或走系统静态资源。
- 与 API 契约以 Pydantic schema + OpenAPI 为准；WS 事件类型化；不在前端伪造数据源。


---

## 14. 完整控制台：全数据面映射（完整 CRUD + 特殊操作）

> 面板不是「改一个配置字段的修改器」，而是**完整、可操控所有数据的管理控制台**——直接对应模组内建管理面板 `RpAdminScreen` 的 9 个页签与 `CharacterService.onManagerCrud`/《FactionManager》等全量 CRUD 面。下面按域列出**必须**提供的操作（每项都要能 增/删/改/查，不只是改一栏）。

### 14.1 配置域（每项 = 一个 config_documents 内的实体，需 列表 + 新建 + 编辑 + 删除）
- **阵营（factions.json）**：阵营 CRUD（id/name/color/description/icon/tier/music/cmdcamScene/radio{ speaker,lines[{text,wait}] }/spawn{ rule,points[{x,y,z,dim}] }）；字段保存遵循「结构性保留」。
- **阵营组（factions.json groups）**：id + memberIds[]；创建/删除；组不可嵌套组。
- **关系（relations + relationRules）**：from[]/to[]/type(hostile|neutral|friendly)/内部关系（省略 to）；set/upsert/update（按 original 定位原位替换）/remove；顺序=优先级。
- **职业（professions）**：CRUD（id/name/factionId/selfDeploy/unlockLevel/loadout{ inventory[{slot,item,count,nbt}], armor[...], offhand{...} }/music/profile/cmdcamScene/radio/radioDisabled/spawn）。
- **设置（settings.json）**：forceObserving/openPanelOnJoin/forceRetain/hudEnabled/hudProfessionText/hudFactionText/hudHealthText/firstJoinAutoDeploy/firstJoinProfession。
- **事件（events.json）**：CRUD（id/enabled/durationSeconds/triggers[]/tasks[{id,xp}]/hooks{startAnimation,spawnWave,notify{titleKey}}/sequence[]）。
- **阶段（phases.json）**：CRUD（id/order/durationMinutes/steps[]）。
- **刷新波（spawn_waves.json）**：CRUD（id/mode/enabled/teamIds/professionIds/factionIds/count/minLevel/deployAt/recruitTimeoutSeconds/autoEnableByTeam/sequence[]）。
- **限制（limits.json）**：CRUD（id/type[GLOBAL|FACTION|PROFESSION]/target/limit）。
- **经验规则（experience_rules.json）**：CRUD（id/enabled/eventId/conditionExpr/valueExpr/titleExpr/order）——表达式语法校验与「试算」。
- **序列（sequences.json）**：行为序列步骤编辑器（TRIGGER/WAIT/WAVE/COMMAND/FORCE_PICK）。
- **动画（animations.json）**：序列步骤（TITLE/SUBTITLE/ACTIONBAR/FADE/CAMERA/PARTICLE/SOUND）与钩子绑定——至少 JSON 编辑 + 结构校验。

### 14.2 特殊操作（不只改数据，还要触发/预览/同步）
- **保存职业装备**：loadout 只替换 loadout，**不吞**其它字段（`setProfessionLoadout`）。
- **部署点/复活点**：阵营/职业 spawn（rule + points）+ 管理端「传送到部署点」（跨维）。
- **无线电**：阵营/职业 radio 编辑（`setFactionRadio` / `upsertProfession` radio）。
- **影响预检**：删除/改名前（`onManagerImpact`）列出引用该实体的 users/waves/events/phases/professions。
- **全服广播同步**：任何配置变更后 `broadcastConfigAll`（编辑者立即 + 其余异步）；面板保存后体现「已同步」。
- **运行时动作（可选、受控）**：管理刷身（`onAdminSelfProfession`）、保存装备（`onAdminSaveProfessionFull`）——标注「运行时权威在模组」。

### 14.3 资源域
- 音乐（audio/*.ogg）：列表/播放/上传/删除；图标（textures/*.png）：预览/上传/删除；格式（OGG 魔数/PNG 规格）+ 大小 + sha256。

### 14.4 面板操作与一致性护栏（沿用 §6/§13）
- 每域每操作：先 schema 匹配 + 模组同规则校验；写用「结构性保留 + CAS + 原子事务」；`version`/未知字段只读；409 冲突加载最新版重合并；保存后回推 + `config_reload_seq` 握手。

