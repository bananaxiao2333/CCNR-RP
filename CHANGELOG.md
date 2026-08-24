# Changelog

## 1.2.1（管理器事件/阶段/刷新波 CRUD）
- 管理器新增三页签：事件（启用/时长/结束后结算/触发器任务沿用）、阶段（顺序/时长分钟）、刷新波（模式 SELF_DEPLOY|RECRUIT|BOTH、部署点 WORLD_SPAWN|POS、数量/等级/招募时限/维度/坐标/队伍·职业·阵营ID列表）。
- CRUD 后热重载：EventManager / SpawnFramework 实时生效（事件/阶段重读 json，刷新波清空队伍触发记录）。
- 管理器数据（事件/阶段/刷新波列表）随 ManagerStateS2C 下发，K 面板与管理器同步刷新。

## 1.2.0（管理器 CRUD + 职业简历配置化 + Corpse 崩服修复）
- 修复：未安装 Corpse 模组时离服判死触发 NoClassDefFoundError（类验证发生在 try 外逃逸）崩服——CorpseBridge 改为类探测（Class.forName 缓存）+ 独立内部类隔离引用 + 兜底捕获；低版本 1.0.8 亦曾崩（同根因现全覆盖）。
- 管理器 v2：三个页签——设置（入服规则 3 项）/ 职业 CRUD（列表+建档：名称/阵营/自部署/出场音乐/项目简历/保存/删除/新建）/ 阵营 CRUD（名称/颜色/图标 8 种/等级 1-3/描述）。全部实时写入 config/ccnr_rp/factions.json。
- 职业配置新增 profile（项目简历）：入场电影「项目简历」行读取职业 profile（优先于角色背景），GUI/管理器可编辑。
- K 面板/管理器数据在 CRUD 后自动刷新（列表+表单同步）。

## 1.1.4（页眉布局修复）
- 修复：「管理」按钮与 CCNR:NET 页眉文字重叠——按钮改按页眉宽度动态定位（置于 CCNR:NET 左侧），任何语言/字号下都不再重叠。

## 1.1.3（动画时自动关闭 K 面板）
- 部署成功（入场电影 CinematicS2C）与任意动画播放（AnimationPlayS2C）到达时，自动关闭角色管理面板，保证开场画面/标题/字幕不被遮挡。

## 1.1.2（崩溃修复 + 创建表单简化 + 名称校验）
- 修复：角色列表 S2C 到达时 reloadData 置空 nameBox 但未重建控件，点击「创建角色」触发 NPE 崩溃（refreshIfOpen 现同步 rebuild + createSubmit 空安全）。
- 创建表单移除「背景输入」，只保留名称 + 阵营/职业切换 + 创建（背景一律为空）。
- 名称规则：只允许文字（含中文/字母）与空格，禁止数字/符号——客户端输入过滤 + 提交校验 + 服务端二次校验（name_chars 错误提示）。

## 1.1.1（职业出场音乐，配置驱动）
- 新：职业配置新增可选 music 字段（相对 config/ccnr_rp/ 的路径，或绝对路径；WAV 格式）——部署入场电影开始时播放，60 秒后 1.5s 淡出；换职业/再次部署自动切歌。
- 默认配置新增「设施主管」（行政总部，selfDeploy=true）；真实配置已设 music: audio/de_mulan.wav（《三角洲行动》德穆兰战斗音乐已转 WAV 放入 config/ccnr_rp/audio/）。
- 音乐不进 jar，全部配置化——管理员换音乐只需替换文件 + 改 professions 的 music 字段。

## 1.1.0（职位划分落地 + CCNR-RP 管理器 + 入服/保留规则）
- 数据：按《CCNR服务器职位划分.pdf》重建默认配置——8 部门（行政总部T0/麦迪逊M1-M5/区域核能运营部/研发与技术部R1-R5/QDF司令部S1-S5/QSA综合处理小组A1-A3/QSO H1-H2/后勤N1-N3）+ 28 职位，徽章 icon/tier 同步（新增 gear 图标）；真实配置已写入。
- 新：CCNR-RP 管理器（管理员）——K 面板页眉「管理」入口，服务端权限节点校验（OP≥2），设置实时生效并写入 config/ccnr_rp/settings.json：
  · 强制观察者入服（入服默认 OB）
  · 入服默认打开角色面板（选择部署，无存活角色时自动弹出）
  · 强制保留角色（转生/弃演或离服 → 角色直接判死并留下遗体，档案不删除；关闭后离服不再自动判死）
- 新：K 面板操作行新增「转生/退役」按钮（强制保留开启时显示）——当前角色判定死亡+遗体落地，档案保留。
- 说明：旧职业 id（qdf_guard 等）被 PDF 编码 id 取代（s4_guard 等），旧档案显示原始 id，管理员可手动删除重建。

## 1.0.10（命令提示全量补齐）
- /rp help 重写：全量列出 9 大子命令完整参数签名（中英文），并新增 /rp help 别名。
- 所有子命令/叶子节点裸输或缺参时直接打印对应命令用法（faction/profession/character/state/xp/level/evac/settle/event/phase/animation/spawn 全部覆盖）。
- profession create 新增可选 [显示名]（支持中文，如 /rp profession create qso_director qso true 设施总监）。
- character create 的 name 参数改为 string 类型，支持中文角色名。

## 1.0.9（入场电影打磨）
- 自部署走电影时不再播放旧 spawn 动画（CCNR-RP 标题不再闪现在黑屏上/被电影覆盖）。
- 主标题（职业打字）移到屏幕正中央并最后绘制（在最上层）；徽标位置保持不变。
- 主标题字号 2.6→3.4，副标题四行放大 1.4 倍、行距加宽。

## 1.0.8（变量注入修复 + 部署入场电影）
- 修复：AnimationEngine.inject 字面量 bug（" + e.getKey() + " 永远不匹配）导致动画里 ${name}/${event}/${level} 全部失效；改为 ${key}/{key} 双写法替换，缺参保留原样（含回归测试）。
- 新：部署入场电影（CinematicS2C）——全屏黑 1s → 阵营大徽章突然出现 3s → 主标题打字显示职业 → 副标题逐行打字（项目名字/项目阵营/阵营关系(图谱推理)/项目简历）→ 停留 3s → 黑屏渐退 1.6s → 2s 后文字图标缓慢淡出；点击可随时跳过。
- 新：阵营关系类型语言键（敌对/中立/友好）。

## 1.0.7（预览旋转限位）
- 修复：3D 预览鼠标拖拽角度无上限（yaw 最高 85°）导致模型前倾时头部“穿出”预览框/屏幕。
- 旋转角钳制 yaw ≤45° / pitch ≤33°，缩放上限 110→95，模型始终完整留在框内。

## 1.0.6（预览放大 + 全透卡片 + 水印增强）
- 右栏空隙全部让给战术装备预览：移除 170px 高度上限，3D 模型按预览框高度动态放大（34~110 缩放）。
- 卡片/按钮/预览框全部改半透明（面板 70%、卡片 60%、按钮 68%），CCNR 竖版图标水印可透出；水印 alpha 0.28→0.38 并铺满面板高度。
- 修复：创建表单与皮肤上传之间的大片闲置区域（现由放大后的预览填充）。

## 1.0.5（UI 修复 + 直角 + 背景水印）
- 修复：角色档案/阵营导航全空的根因——buildRight 误清 navBounds/rowBounds 命中区；中列底衬改为不透明深色。
- 应要求全局去圆角（军事终端直角风格：卡片/按钮/胶囊/装备槽全部方角）。
- 终端背景加入 CCNR 竖版图标半透明水印（等比居中，alpha 0.28），保持面板可读性。

## 1.0.4（UI v3：SCP:NET 机密终端）
- 角色管理界面 v3 重构：严格三栏网格——左「机构分类」圆形徽章导航（盾牌/爪印/风暴/六边形/之眼/靶心，金/蓝/青按机构等级区分）；中「角色档案」列表；右「详细资料 + 真 3D 模型预览 + 战术装备槽（头/胸/腿/背）」。
- 色彩体系：冷暗金属底 + CRT 扫描线 + 四角角标（军事指挥中心/机密终端）；青/亮蓝主色（电子屏发光）；高饱和正红选中态（红底白字+红色高亮线+警戒线）；金色徽章（最高机密等级）。
- 3D 预览：真实玩家模型（上传皮肤自动注入，未上传回退默认），穿戴下界合金战术重装（盔甲+剑盾），跟随鼠标旋转；服装渲染复用原版 InventoryScreen 摄像机。
- 数据：阵营配置新增 icon（徽章图形）与 tier（等级 1-3 → 金/蓝/青）字段，默认与真实配置同步写入；S2C 列表附带 icon/tier/color。
- 页眉：左侧「身份数据库」（版本号）、右侧「CCNR:NET」+ 关闭按钮；底部系统消息红字警示。

- 修复：defaults/phases|events|animations.json 未进产物导致真实配置为空 {} 的问题（5 个默认资源齐全）。
- 默认配置补 5 个样例职业（QDF/QSA/研究所/后勤），职业下拉现开箱可用。
- UI：职业为空时给出管理员指引提示；加载器对空配置自动回退默认。

## 1.0.2（UI v2 + 错误修复）
- 角色管理界面 v2：移植 CCNR-Com 风格（SDF 圆角渲染/深色主题/自定义按钮/头像+状态胶囊/详情卡/创建表单卡）。
- 招募建议窗与右侧 HUD v2 同步重做；皮肤/角色创建职业索引联动修复。
- 修复：创建角色错误提示泄漏原始翻译键（改为结构化键+参数，客户端正确翻译）。

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
