# CCNR-RP（Forge 1.20.1）— 角色扮演模组

CCNR 服务器 RolePlay 模组：围绕"量子科学"设施世界观，提供 阵营关系 / 职业装备 / 角色管理 / 状态与遗体 /
经验结算 / 事件 / 动画 / 人物刷新 八大系统。当前处于分阶段开发（见 [docs/00-overview.md](docs/00-overview.md)）。

> 🔧 **玩家向：** 安装、K 键终端界面、全部指令、配置与常见问题 → [docs/10-使用指南.md](docs/10-使用指南.md)

## 技术栈

- Forge 1.20.1（47.2.0+），Java 17 字节码 / JDK 21 构建，Mojang 官方映射
- 运行时零第三方强制依赖；**Corpse（henkelmax）可选联动**：玩家掉线判死 → 可搜刮遗体（未安装则降级原生死亡）
- 国内镜像：Gradle 发行版腾讯云 / Maven Central 阿里云；JSON（Gson）配置与存档

## 功能模块（分阶段开发中）

| 系统 | 说明 | 设计文档 |
| --- | --- | --- |
| P1 阵营关系 | 敌对/中立/友好；阵营组批量声明 | [docs/02](docs/02-faction.md) |
| P2 人物刷新配置 | 管理员保存背包+装备(含 NBT)；职业挂在阵营下 | [docs/03](docs/03-profession.md) |
| P3 角色管理 | 游戏内界面创建角色/上传皮肤/死亡冷却 | [docs/04](docs/04-character.md) |
| P4 状态+Corpse | 掉线判死、遗体、冷却、状态机 | [docs/05](docs/05-status-corpse.md) |
| P5 经验系统 | 值班时间/任务行为/疏散方式 → 命令结算 → 等级 | [docs/06](docs/06-experience.md) |
| P6 事件系统 | 阶段时间点/条件触发；事件生命周期 | [docs/07](docs/07-event.md) |
| P7 动画系统 | 数据驱动表现层动画；出生/死亡/结局/事件钩子 | [docs/08](docs/08-animation.md) |
| P8 刷新框架 | 自刷新 + 复活波 + 屏幕右侧招募列表 | [docs/09](docs/09-spawn.md) |

当前状态：**P0–P9 全部完成（v1.0.0）**——八大系统与全流程闭环（事件→刷新波/招募→部署→判死→冷却→复活→结算）均已实现；详见 [CHANGELOG.md](CHANGELOG.md)。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/bananaxiao/Documents/MirageV/mod/CCNR-Com/.gradle-home   # CCNR 系列共享缓存（.gradle-home 惯例不提交）
./scripts/fetch-corpse.sh   # 首次构建前取 Corpse 编译期依赖（libs/，已存在则跳过）
./gradlew build             # 语法(-Xlint:all) + 风格(spotlessCheck) 门禁
./gradlew test -PrunTests   # 单元测试（离线默认不跑；CI 强制）
./gradlew runServer / runClient
```

产物：`build/libs/ccnr_rp-<版本>.jar`。工程规范见 [docs/01](docs/01-工程规范.md)，开发注意见 [AGENTS.md](AGENTS.md)。

## 持续集成

[GitHub Actions](.github/workflows/build.yml)：main push / PR / v* 标签时执行 构建 + spotlessCheck + 单测，上传产物；标签自动挂 Release。

## 目录

- `src/main/java/com/ccnrcom/rp/`——模组逻辑（按系统分包）
- `src/test/java/com/ccnrcom/rp/`——JUnit5 单元测试
- `config/ccnr_rp/`——管理配置（阵营/职业/事件/阶段/动画/刷新波，JSON）
- `world/ccnr_rp/`——运行时数据（角色/账本/皮肤）
- `docs/`——设计文档与任务分解

## License

MIT
