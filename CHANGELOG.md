# Changelog

## 1.0.1（修复迭代）
- 客户端 MOD 总线拆分修复（ClientSetup 与 ClientForgeEvents 分开），真实客户端可正常加载。
- 补充 pack.mcmeta（消除 ResourcePackInfo 提示）与 CCNR-Com 风格图标（RP 右下角无背景）。
- 阵营×组批量关系修复（服务端验证：默认配置 6 阵营/2 组加载成功）。

## 1.0.0（P9 集成验收）

- 全流程串联：事件开始 → 刷新波/招募 → 部署装备+动画 → 判死/遗体 → 冷却 → 复活波 → 游戏结束疏散裁定 → 自动结算。
- 工程：spotless(palantirJavaFormat) + -Xlint:all + JUnit5(-PrunTests) + GitHub Actions CI（构建/测试/产物/Release）。
- 文档：docs/00~09（架构/工程规范/八大系统：数据模型、配置格式、命令、协议、WBS 与分阶段验收标准）。

## 0.9.0（P8 人物刷新框架）
- 阴间池/阳间池选人算法（等级→冷却→随机；不足回退观察者）。
- 自刷新（GUI 部署，selfDeploy 职业）与复活波（队伍创建 20t 轮询触发）。
- 招募兜底：RecruitOfferS2C → 屏幕右侧列表 + 弹出接受/拒绝窗（超时自动移除）。
- 部署链路：装备发放（含 NBT）→ 传送 deployAt → 状态 ALIVE → player_spawn 动画。
- /rp spawn list|trigger|enable。

## 0.8.0（P7 动画系统）
- 数据驱动序列：TITLE/ACTIONBAR/FADE/CAMERA/PARTICLE/SOUND/GROUP，参数注入，时长钳制。
- 服务端执行粒子/音效/ActionBar；客户端执行标题/遮罩；钩子 player_spawn|player_death|game_end|event_start|level_up。

## 0.7.0（P6 事件系统）
- phases.json 阶段表 + PhaseClock 自动/手动推进。
- 五类触发器（ON_PHASE_START/END、ON_TIME、PERIODIC、CONDITION）。
- 事件生命周期 SCHEDULED→RUNNING→SETTLED；结束自动结算（P5）；gameover 疏散裁定。

## 0.6.0（P5 经验系统）
- LevelCurve（xpForLevel/level）；三来源（值班时间/任务行为/疏散方式）增量结算，账本幂等。
- /rp settle、xp、level、evac set；升级广播 + level_up 钩子。

## 0.5.0（P4 状态+Corpse）
- 状态机（ALIVE/DEAD/OBSERVING），掉线判死（事件主路径+轮询兜底，幂等），冷却。
- Corpse 可选联动（生成可搜刮遗体），未安装降级原生死亡；/rp state、kill。

## 0.4.0（P3 角色管理）
- 角色数据（world/ccnr_rp/characters.json，原子写+损坏 .bak）。
- ccnr_rp:main 网络通道；角色管理界面（列表/创建/皮肤上传/操作按钮）；/rp character。

## 0.3.0（P2 人物刷新配置）
- 职业定义（挂在阵营下）+ selfDeploy；装备 NBT base64 存取；loadout 采集/发放。
- /rp profession create|save|list|load。

## 0.2.0（P1 阵营关系）
- 阵营/组/关系（hostile|neutral|friendly），组×组批量、单点优先、重复覆盖 WARN。
- /rp faction list|relation|group；默认配置预置量子科学组织。

## 0.1.0-alpha（工程初始化）
- 骨架、构建、任务分解与验收体系。
