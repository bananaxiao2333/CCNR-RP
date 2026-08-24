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
- 网络通道 ccnr_rp:main（SimpleChannel，version=1），P3 起启用；客户端无通道时服务端静默跳过联动（对齐 CCNR-Com ChannelSync 思路）。
- Corpse（modid=corpse）为可选依赖：ModList 探测，缺失走原生死亡降级；编译期依赖走 libs/ fileTree（版本浮动）。

## Gotchas

- **构建环境**：本仓库位于 `MirageV/mod/CCNR-RP`；构建需 JDK 21（系统默认是 Zulu 26，务必 export JAVA_HOME），且需 GRADLE_USER_HOME 指向共享缓存（见上方 Commands）。
- **测试门控**：`onlyIf -PrunTests` 意味着普通 `build` 不执行测试；CI 与验收必须显式 `-PrunTests`。
- **Corpse 依赖**：`compileOnly fileTree(dir: 'libs', include: 'corpse-forge-1.20.1-*.jar')`——版本随
  `scripts/fetch-corpse.sh` 解析到的最新 forge-1.20.1 版本浮动（脚本固定保存名为 corpse-forge-1.20.1-1.0.23.jar）；
  Modrinth API 请求必须带 User-Agent，否则返回空。
- **mods.toml**：corpse 依赖 `mandatory=false`；对 mods.toml 的任何改动同步 `ProjectMetadataTest`。
- **语言包**：zh_cn/en_us 键必须同步，LangFileTest 会拒绝漏翻（值 == 键 = 红字）。
- **不要改动 CCNR-Com / CC-api**；所有变更限本仓库。
- **钩子契约**：player_spawn / player_death / game_end / event_start / level_up 是公共契约，先改 docs/00 §6 与 docs/08 再改代码。
- **判死幂等**：掉线判死有事件 + 轮询兜底两条路径，必须幂等（重复触发只处理一次）。
- 脚本 fetch-corpse.sh 依赖 curl + python3；CI 用 ubuntu-latest + JDK21(temurin)。
