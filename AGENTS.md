# AGENTS.md

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/bananaxiao/Documents/MirageV/mod/CCNR-Com/.gradle-home   # 共享 2GB 缓存（.gradle-home 惯例，勿提交）
./gradlew build                    # 语法(-Xlint:all) + 风格(spotlessCheck) 门禁，产出 build/libs/ccnr_rp-*.jar
./gradlew test -PrunTests          # 单元测试（离线默认不自动跑，CI 强制）
./gradlew spotlessApply            # 唯一格式化入口（提交前必跑）
./gradlew runServer / runClient    # 开发环境启动
./scripts/fetch-corpse.sh          # 下载 Corpse 编译期 jar → libs/（勿提交）
```

## Architecture

- `com.ccnrcom.rp` 入口 CCNRRPMod；子包按系统划分（见 docs/00 §3）。规则逻辑为**无 MC import 的纯类**，可直接 JUnit 测。
- 存储分层：serverconfig toml = 调参；config/ccnr_rp/*.json = 管理员可编辑定义（factions/professions/phases/events/animations/spawn_waves）；
  world/ccnr_rp/*.json = 运行时数据（characters/xp_ledger/...）。全部 JSON 原子写（tmp + rename），损坏保留 .bak。
- **数据库后端（v2.19.0+）**：`com.ccnrcom.rp.data` 提供 JDBC 仓储/微 ORM（SQLite 默认，MySQL 可选）。`config/db.properties` 的 `db.enabled=true` 时，配置文档（config_documents）、用户档案（users/user_pending_xp）、调参（server_settings）、素材（assets）改存库；支持多配置档（config_profiles/meta.active_profile）与 `/rp db` 命令（status/test/migrate/export/flush/connect/profile）。未启用时维持文件行为。详见 docs/12。
- 网络通道 ccnr_rp:main（SimpleChannel，version=1），P3 起启用；客户端无通道时服务端静默跳过联动（对齐 CCNR-Com ChannelSync 思路）。
- Corpse（modid=corpse）为可选依赖：ModList 探测，缺失走原生死亡降级；编译期依赖走 libs/ fileTree（版本浮动）。

## 开发纪律（提炼自 GFBS-Main 规范；完整版见 docs/01 §9）

- **先读后写、修根因**：动工前先读目标代码及相邻注册/事件/网络/配置/生命周期代码；按"现象→调用链→根因→影响面→最小改动→实现→验证"推进；禁止用强制刷新、重复发包、每帧重建、延时重试掩盖状态错误（兜底须有边界+去重+限频）。
- **服务端权威**：服务端是状态最终权威，客户端只发意图；服务端必须重新校验距离/权限/目标/参数/世界有效性；同步用增量+追踪（晚加入、重进、重连、切维度都要拿到当前有效状态），避免全量广播与每 tick 全量同步。
- **线程边界**：独立线程只碰纯数据/不可变快照；Level/NBT/网络/渲染必须回主线程；禁止每实例一线程；结果提交前校验世界纪元/版本新鲜度，旧结果丢弃。
- **缓存与状态**：缓存声明键/所有者/生命周期/失效/上限，不做第二权威源；逻辑/存档/同步/渲染状态单向转换，正确性不得依赖"重新放块"或偶然全量刷新。
- **对称清理**：资源/线程/监听器/网络追踪/缓存对称创建释放；世界卸载、维度切换、配置重载、服务端停止、断线都是必做清理路径。
- **配置与通用**：数据驱动优先；配置开关必须真正改执行路径（关闭了就不该继续算）；通用考虑复用但不造无边界大框架。
- **质量与交付**：编译通过≠正确，未验证的必须明说；无占位/空壳/伪异步/只为过编译的假逻辑；保留未被需求否定的原行为；提交前查 git diff，单一职责，Git 操作只限任务授权；文档写"为什么/边界/如何失效"。


## Gotchas

- **构建环境**：本仓库位于 `MirageV/mod/CCNR-RP`；构建需 JDK 21（系统默认是 Zulu 26，务必 export JAVA_HOME），且需 GRADLE_USER_HOME 指向共享缓存（见上方 Commands）。
- **测试门控**：`onlyIf -PrunTests` 意味着普通 `build` 不执行测试；CI 与验收必须显式 `-PrunTests`。
- **Corpse 依赖**：`compileOnly fileTree(dir: 'libs', include: 'corpse-forge-1.20.1-*.jar')`——版本随
  `scripts/fetch-corpse.sh` 解析到的最新 forge-1.20.1 版本浮动（脚本固定保存名为 corpse-forge-1.20.1-1.0.23.jar）；
  Modrinth API 请求必须带 User-Agent，否则返回空。
- **mods.toml**：corpse 依赖 `mandatory=false`；对 mods.toml 的任何改动同步 `ProjectMetadataTest`。
- **语言包**：zh_cn/en_us 键必须同步，LangFileTest 会拒绝漏翻（值 == 键 = 红字）。
  **另有反查守卫 `everyKeyReferencedByCodeExists`**：以 `src/main/java` 的 `"ccnr_rp.*"` 字面量为基准，
  任一被引用的键在任一份语言包缺失即失败——因为"两侧一起丢"时同步性检查是绿的（2.26.9 真实事故）。
  两条硬纪律：**① 只许就地锚点插入，禁止用脚本重排整个文件**（它不是字母序排的，重排=上千行噪声 diff）；
  **② 禁止对语言包做整文件级 `git checkout -- lang/` 回退**（要撤就撤具体那几行，否则会静默吞掉新键）。
- **有未提交改动时禁用 `git checkout --` 还原**：本仓库常年积累跨多版本、上千行的未提交改动，
  `git checkout -- .` / `git checkout -- <目录>` 会**静默丢弃全部**（2.26.9 开发中真实发生过一次，
  靠事先快照才恢复）。要回到已知状态：先把受影响文件整体快照到仓库外（如 `/tmp`），再逐文件恢复；
  需要"只看不改工作区"的操作，改用 `git apply --cached` 只动 index。
- **不要改动 CCNR-Com / CC-api**；所有变更限本仓库。
- **钩子契约**：player_spawn / player_death / game_end / event_start / level_up 是公共契约，先改 docs/00 §6 与 docs/08 再改代码。
- **判死幂等**：掉线判死有事件 + 轮询兜底两条路径，必须幂等（重复触发只处理一次）。
- 脚本 fetch-corpse.sh 依赖 curl + python3；CI 用 ubuntu-latest + JDK21(temurin)。
