# Changelog

## 2.18.20（击杀友好提示挪至聊天区上方 + 头顶标记仅旁观者渲染）

- **击杀友好提示位置改为聊天区上方**：锚点 = 聊天区底部 + 聊天区高度 + 可配置偏移
  （serverconfig kill.noticeOffset，服务端权威下发，客户端遵从），提示始终贴在聊天区上方、
  不再与聊天框重叠；ChatScreen 打开时聊天区变高，提示自动随之上移。
- **头顶人物标记仅旁观者渲染**：PlayerNametagRenderer 客户端渲染加游戏模式判断——
  当前玩家为旁观者（观察视角）时渲染其他玩家头顶标记，其余模式（在场/普通玩家）不渲染，避免信息暴露。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.19（击杀友好提示：聊天界面之上重绘，防 ChatScreen 遮挡）

- **修复击杀友好提示被聊天框遮挡**：ChatScreen 是 Screen，渲染在 HUD 覆盖层之上，左下角聊天历史面板
  会盖住左下角的提示；现在聊天界面打开时（ScreenEvent.Render.Post）把提示重绘在聊天框之上，
  与 HUD 覆盖层共同渲染（内部按显示时间门控，不重复出现）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.18（击杀友好提示 + 击杀事件广播关系参数）

- **击杀友好提示**：玩家击杀友好阵营玩家时，左下角弹出提示（约 6 秒），展示被击杀者的阵营、职业、玩家名与玩家 UUID；
  服务端权威判定（FactionGraph.resolve == FRIENDLY 且双方有档案/阵营）后定向发包 KillFriendlyNoticeS2C；
  可配置开关 serverconfig kill.friendlyNotice（默认 true，关闭不计算不发包）。
- **击杀事件广播拓展**：character_kill 事件新增 victimRelation 参数（击杀者↔被击杀者阵营关系：hostile/neutral/friendly，
  任一方无阵营/未知为空串），供经验规则表达式消费；ExperienceEventRegistry 参数表与 docs/06 同步。
- 新增客户端 HUD：KillFriendlyNoticeHud（左下角 toast，登出清理）；zh/en 语言包新增 3 键。
- 测试：新增 KillRelationTest（关系解析 5 例）+ ExperienceEventRegistryTest 补 victimRelation 断言；LangFileTest 守护键集一致。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.17（精简默认配置为样板）

- **默认配置精简为样板**：factions.json 由 8 阵营/28 职业 → **3 阵营/4 职业**（行政总部/麦迪逊/QDF，图标改用内置向量 shield/claw/cross，不再引用已删除的内嵌图片）+ 1 组 + 2 关系；
  phases.json → 2 阶段（prep/danger）；events.json → 1 事件（evac_alert）；animations.json → 3 序列（spawn_intro/player_death/event_start_alarm）；
  spawn_waves.json 保持 2 波。默认配置总大小 ~16KB → ~4.9KB。
- 修复原默认配置悬空引用：evac_alert 的 hooks.spawnWave 原指向不存在的 wave_qdf_reinforce → 改为 general_reinforce。
- 阵营图标方案同步：默认配置 img: 引用全部移除（上一版已删内嵌图片），改用向量图形；docs 同步样例数量说明。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.16（移除内嵌多媒体资源，jar 瘦身）

- **删除打包进 jar 的多媒体资源**：7 张阵营图标（textures/faction/*.png）、界面背景 logo（textures/gui/bg_logo.png，代码无引用）、
  模组图标（icon.png）——共约 452KB，jar 由 ~1.0MB 降至 ~0.55MB。
- 阵营图标改为完全由服务器素材库下发（config/ccnr_rp/textures/，客户端进服自动缓存），移除代码中的内嵌回退分支：
  AssetLibrary 内嵌默认图标拷贝、RpIcons/PlayerNametagRenderer 的 jar 内嵌回退、RpAdminScreen 写死的 img:admin_hq/img:madison 预设；
  mods.toml 移除 logoFile（模组图标）。
- docs/10 同步：img: 徽章未上传/未配置时仅显示底色徽章盘（无内嵌回退）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.15（开发文档：管理面板 GUI / 载荷链路 / 发布流程经验沉淀）

- docs/11 新增「管理面板 GUI / 载荷链路 / 发布流程经验」章节：clearWidgets 控件生命周期陷阱、
  嵌套载荷必须解包、自定义弹层命中/绘制顺序、文本像素裁剪与贪心换行、按选中原始规则定位的编辑语义、
  版本号 + CHANGELOG + 提交发布流程约定。
- 纯文档改动，无功能变化；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.14（关系测定图悬停高亮 + 入场电影副标题自动换行与关系分色）

- **关系测定图悬停高亮**：鼠标放在某阵营图标上时，保留该阵营 + 其连线 + 直接相连阵营
  （连线/图标），其余连线和图标全部变暗（连线低透明度、图标深色罩 + 标签变灰），便于聚焦单个阵营；
  提示文案同步更新（zh/en）。
- **入场电影副标题自动换行**：四行副标题（含阵营关系）按像素宽度贪心换行，屏幕宽度不足时自动断行，
  打字动画跨行连续推进、后续行按总行数堆叠不重叠。
- **阵营关系按类型着色**：入场电影「阵营关系」条目按关系类型上色（敌对红 / 友好绿 / 中立白，
  与关系图配色一致），换行后逐段正确着色。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.13（关系管理页签 from/to 新增阵营下拉 + 注入按钮）

- from/to 输入框上方各新增**阵营下拉框 + 注入按钮**：下拉选择阵营（显示「名字(id)」），
  点「注入」把该阵营 id 去重追加到对应输入框的逗号列表（便于从现有阵营快速组规则）。
- 下拉弹层最多显示 8 项、弹层内滚轮滚动；弹层命中先于 widget 分发（可盖住输入框），
  弹层绘制置顶于 widget 之上，点弹层外自动收起。
- zh/en 新增 relation.inject / relation.pick_faction 键；docs/02 §4.5 同步。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.12（修复关系管理页签：布局重叠、无法编辑、保存/删除定位错误、服务端必现报错）

- **修复 GUI 重叠混乱**：关系页签内容区起点与其它页签对齐（避开页签栏/分隔线），
  列表补标准面板边框与标题，长规则名按宽度裁剪不再溢出；from/to 标签与输入框重排。
- **修复无法编辑**：rebuild 会先清空全部控件，关系页签的 from/to 输入框现每次重建都重新注册
  （此前首次构造后即从控件列表消失，不渲染也不接收输入）；数据刷新时保留已选规则的编辑内容。
- **修复保存/删除操作逻辑**：保存携带选中规则的原始 from/to（original），服务端按此原位替换/删除，
  修改 from/to 不再误增新规则；删除针对选中规则本身而非输入框当前内容。
- **修复「from/to 不能为空」必现报错**：RelationEditC2S 载荷为 {action, rule{from,to,type}, original?}，
  服务端此前把整个请求当 rule 传入 CRUD（读顶层 from = 空），新增/保存/删除一律失败；
  现正确解包嵌套 rule（带缺省防护），无 original 的旧载荷回退按新值 upsert。
- 客户端新增 from 空值本地校验（明确提示，不再发空载荷）；zh/en 同步新增 from_required 键。
- docs/02 §4.5 同步载荷契约；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.11（结算动画：开始前等待 3 秒，结束后停留 5 秒再消失）
- 结算动画**开始前先等 3 秒**（期间显示待吸入列表静止 + 旧数字），再开始逐项吸入；
- 动画播完后总数字**停留 5 秒**再消失（无变化结算直接显示 5 秒）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.10（关系管理改为管理面板独立页签，与经验规则并列；关系图并入页签）
- 管理面板新增「关系管理」页签（TAB_RELATION，与「经验规则」并列）：规则列表 + 编辑器
  （from/to 逗号分隔多值、类型三选、留空 to = 内部关系）、新增/保存/删除走 RelationEditC2S。
- 「打开关系测定图」入口并入关系管理页签内（全屏图，关闭返回管理面板）；
  移除阵营页签里的「关系管理…/关系测定图…」按钮与旧的独立关系管理弹窗。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.9（新增独立关系管理面板 + 全屏子面板关闭返回上层）
- 新增**关系管理面板**（全屏独立）：管理面板「阵营」页签「关系管理…」打开；
  左侧关系规则列表（从上到下优先级），右侧编辑 from/to（多阵营/组、逗号分隔）、类型三选，
  留空 to = **内部关系**；新增/保存/删除走 RelationEditC2S → 服务端权威校验+落盘+回执。
- 面板内提供「打开关系测定图」入口（测定图仍可从管理面板直接打开）。
- **修复：全屏子面板关闭时不再直接回游戏，而是返回上层**——关系管理面板/关系测定图关闭
  返回管理面板，管理面板（Esc/✕）返回 K 面板；命令打开的测定图仍回游戏。
- 数据链路：CharacterListS2C 新增 `relationRules`（原始规则，含内部关系/组引用）→
  客户端 `ClientCharacterState.relationRules()` → 关系管理面板。
- docs/02 同步；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.8（修复：K 面板 ✕ 悬停即关闭——改为点击才关闭）
- 修复：角色管理面板（K）标题栏 ✕ 悬停即触发 onClose（误写在每帧 render 的 hover 判定里），
  鼠标移到 ✕ 上不点击也会关闭面板；已改为与管理面板一致：渲染只高亮、mouseClicked 点击才关闭。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.7（关系系统重写：多对多 + 从上到下优先级 + 关系测定图）
- 关系声明改为**多对多**：`RelationRule {from[], to?, type}`，from/to 各为一个 id 列表
  （阵营或组，组自动展开），生效范围 = from × to 笛卡尔积、关系双向对称；兼容旧格式单字符串。
- **内部关系**：省略 `to`（或 from=to 同一列表）= 该列表内所有阵营两两互设该关系
  （如 from 为 [qdf,qsa,qso] 不写 to → 三者两两友好），一行声明全组互连。
- 优先级改为**从上到下**：关系列表先声明（靠前）的规则命中即生效，重复声明后者被忽略并 WARN。
- 新增**关系测定图**（全屏）：管理面板「阵营」页签按钮 或 `/rp faction graph`（管理员）；
  每个阵营 = 徽章 + 名称标签，有关系的阵营对之间连线（白=中立 / 红=敌对 / 绿=友好），
  可拖动平移、滚轮缩放、Esc 关闭；打开时下层管理面板暂时隐藏（同部署点弹窗逻辑）。
- 数据链路：`FactionGraph.edges()`（a<b 去重、先命中类型）→ CharacterListS2C `relations` →
  客户端 `ClientCharacterState.relations()` → `FactionGraphScreen`。
- 单测更新：多对多笛卡尔积 / 从上到下优先级 / 组展开 / 兼容数组与旧格式 / edges 断言。
- docs/02 同步（模型、优先级、图、命令）。
- 构建：spotlessApply / build -PrunTests（含 LangFileTest）全绿。

## 2.18.6（经验 HUD 改为仅结算时显示：动画 + 总数字停留 5 秒）
- 不再在存活期间常驻显示经验 HUD（列表/总数字平时不出现）。
- 结算时播放逐项吸入动画；动画播完后总数字**停留 5 秒**再消失；
  无变化的结算直接显示总数字 5 秒。
- 项目行格式严格改为「**符号数值 标题**」（如 `+100 击杀奖金` / `-50 违规扣分`），
  不再用「标题 符号数值」。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.5（新增 /rp xp add 手动记分命令）
- 新增命令 `/rp xp add <玩家> <数值> <标题>`（管理命令，OP≥2 或 ccnnrrp.admin.settle）：
  向玩家待结算列表添加自定义记分项目（标题 + 数值，数值可为负），HUD 立即更新，
  随下次结算（死亡退场或 /rp settle）计入累计 XP；同标题条目合并（数值相加、标题取后来者）。
- 语言包：新增 ccnr_rp.xp.add.ok / add.invalid（zh/en 同步）；usage 键同步。
- docs/06 §7.5 与 docs/10 命令表同步。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.4（修复结算时机 + 结算动画放慢/死亡界面可见）
- 修复：结算不再在「事件结束」与「游戏结束」时自动触发——结算仅发生在**死亡退场**
  （死亡/判死/退役/征召结束）与 **`/rp settle [player|all]`** 命令；待结算列表持续累积，
  直到玩家死亡或管理员手动结算。
- 清理：移除 `events.json` 的 `settleOnEnd` 死配置（EventModels/EventManager/管理面板按钮/
  默认事件定义同步删除；旧配置多余字段忽略兼容）。
- 修复：HUD 结算动画**放慢**——单项飞入 0.7s（先快后慢缓动）+ 项间停顿 0.15s，
  不再瞬间完成。
- 修复：死亡瞬间结算动画此前被**死亡界面（DeathScreen）遮挡**不可见（HUD 覆盖层在
  Screen 打开时不渲染），表现为“动画瞬间完成/看不到”——现在结算动画在死亡界面上方
  最上层绘制，逐项吸入可见；动画结束后隐藏。
- 同步：docs/06（触发点/动画）、docs/07（事件结束不再自动结算）、docs/10（gameover 说明）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.3（经验规则编辑器：事件改文字输入+补全，修复展开失败与文字重叠）
- 修复：事件选择下拉无法展开——原下拉框与规则 ID 输入框叠在同一位置（widget 吃掉点击），
  且两处文字重叠。
- 事件选择改为「文字输入 + 补全下拉」（参考限制页目标补全）：输入过滤 character_alive/kill/death，
  鼠标点击或 ↑↓+回车选择，Esc 关闭；输入框内容为合法事件时参数面板/验证器实时跟随。
- 参数面板行内防重叠：参数名按可用宽度裁剪（超宽加省略号），类型与「插入」提示不再挤在一起。
- 布局修正：规则 ID 输入框移到 ID 行（ey1+2），事件输入框位于事件行（ey1+24）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.2（修复经验规则 UI 语言包缺失：按钮显示原始键）
- 修复：经验规则页签/命令引用的 ccnr_rp.xp.rules.* 语言键此前从未写入 lang 文件
  （旧编辑静默失败且 LangFileTest 仅校验 zh/en 键集对等），导致按钮/提示全部显示原始键。
  已补全全部规则键 + 验证器文案键（zh_cn/en_us 同步，284 键对等）。
- 移除废弃的 ccnr_rp.xp.line.* 疏散逐行键（evac 系统已删除）。
- 校验：JSON 解析 + zh/en 键集对等 + 代码引用键全量存在。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.1（修复管理面板点击崩溃 + 经验规则编辑器国际化）
- 修复：管理面板点击时崩溃（ArithmeticException: / by zero）——「经验规则」页签的 rowHeight()==0
  触发了通用列表滚动条除零（TAB_XP 从该路径排除）。
- 国际化：经验规则编辑器全部可见文案改为 lang 键（判断/数值/标题标签、试算结果、参数面板插入提示、
  保存失败等），zh_cn/en_us 同步；表达式技术性错误消息保持原样（诊断细节）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.0（经验规则系统 v3：事件广播 + 规则引擎 + 结算动画 HUD）
- 经验系统重写：移除固定三来源（值班/任务/疏散）结算，改为事件广播 + 规则引擎：
  - 事件广播：character_alive（每 60 秒对每个在场角色）、character_kill（玩家击杀任意生物，击杀者收）、
    character_death（死亡/掉线判死，结算开始前赋予死者）。
  - 规则（config/ccnr_rp/experience_rules.json，管理面板热编辑）：订阅事件 + 判断表达式（布尔，空=恒激活）+
    数值表达式 + 标题表达式；表达式支持参数引用、算术、比较、逻辑与 round/floor/ceil/max/min 函数、字符串拼接。
  - 经验变化列表（每用户，落盘 user_profiles.json v3）：同规则条目合并（数值相加、标题取后来者、不计次数）；
    结算时列表求和（可为负）计入用户累计 XP 并清空；部署时清空列表（不计 XP）。
  - 结算触发点不变：/rp settle [player|all]、死亡/断联退场、事件结束（settleOnEnd）、游戏结束全员。
- 客户端：
  - 经验 HUD（右下角内收、水平居中、文字中心对齐）：底部一行白色经验数字 + 上方经验变化项目列
    （正=绿、负=红、带符号）；结算动画最底一项移入数字并消失 → 数字更新 → 列表下移补齐 → 循环至全部吸入
    （纯视觉，服务端结算瞬时完成）。
  - 管理面板新增「经验规则」页签：规则列表增/删/改/启停、事件下拉补全、限定高度可滚动参数面板
    （参数名+类型，点击插入到聚焦表达式末尾）、判断/数值/标题三个表达式输入框实时语法校验徽标、
    「试算」验证器（按事件默认样例本地求值展示成不成立）；保存 C2S → 服务端权威校验 + 落盘 + 热重载。
- 移除：SettlementCalcs / LedgerStore / 疏散方式结算 / /rp evac set / 旧逐行红绿结算弹层（由 HUD 动画取代）。
- 权限：新增 ccnrrp.admin.xp（回退 OP≥2）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿。

## 2.17.13（悬浮标签状态变化即时刷新 + 异步广播；管理面板配置中文标签补全）
- 悬浮标签刷新时机：UserService.setStatus/setRole 值真正变化时（部署/死亡/复活/换岗/下班）自动触发
  全服标签刷新，其他玩家头顶标签立即同步（不再残留「人死了头顶还挂标签」的过期数据）；幂等去重。
- 广播异步化：独立线程构建 payload（人多不阻塞主线程），构建完成后回主线程发网络包；合并去重。
- 管理面板「设定」页：新增 nametag 配置（enabled/badgeSize/offset）补全中文标签
  （头顶标签开关 / 头顶标签徽章大小 / 头顶标签高度(格)）。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.13.jar。

## 2.17.12（头顶标签可配置化：显示开关/徽章大小/标签高度，服务端权威同步；清理硬编码）
- 新增服务端配置（serverconfig/ccnr_rp-server.toml → nametag 段，随 CharacterListS2C 同步全员）：
  - enabled：头顶悬浮标签总开关（false=完全关闭）。
  - badgeSize：阵营徽章大小（世界单位，0=不显示徽章只显示文字）。
  - offset：标签离头顶高度（格，越大越高）。
- PlayerNametagRenderer 全部硬编码提为命名常量（布局坐标/缩放/颜色复用 RpTheme/徽章默认值），
  颜色统一走 RpTheme，消除散落魔法数字。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.12.jar。

## 2.17.11（头顶标签：阵营徽章置顶单独一行，世界空间绘制矢量/图片徽章）
- 标签顶部新增阵营徽章行：环(等级色) + 盘(深色) + 中央图形 + 右下角等级刻度。
- 徽章世界空间绘制：矢量图形用扫描线填充（复用 RpIcons.iconPolygon，视觉与 GUI 一致）；
  img: 图片徽章用 entityTranslucent 纹理 quad（服务器下发或内嵌回退）。
- 布局：徽章最顶一行，往下职业名(阵营色) / 玩家名 / 等级。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.11.jar。

## 2.17.10（头顶标签：改为世界空间 billboard 悬浮标签，客户端本地渲染、只有自己可见、始终面向相机）
- 放弃 HUD 屏幕投影方案（屏幕坐标换算受 FOV/距离影响，易出位置漂移问题）。
- 改为仿原版名字牌的世界空间渲染：RenderLevelStageEvent.AFTER_ENTITIES 阶段在玩家头顶上方
  mulPose(cameraOrientation) 使标签始终面向相机 + scale(-0.025,-0.025,0.025) + font.drawInBatch 绘制
  职业名（阵营色）/ 玩家名 / 等级 三行（带半透明底衬）。
- 只有本地客户端渲染，其他玩家看不到；自带透视（远小近大）；渲染距离跟随游戏设置；
  服务端仍只下发非观察者（已部署）玩家数据（v2.17.9）。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.10.jar。

## 2.17.9（头顶标签：改为服务端过滤数据，仅下发非观察者（已部署）玩家；客户端直接渲染）
- 修复 2.17.8 在客户端用 isDeployed() 门控导致旁观者视角完全看不到其他玩家标签。
- 改为服务端过滤：CharacterService.playerTagsJson() 只下发 ALIVE（非观察者/已部署）玩家的
  {name, professionId, factionId, level}，观察者（未部署）玩家不下发数据；
  客户端 PlayerNametagRenderer 移除 isDeployed() 限制，收到什么渲染什么——已部署玩家标签始终可见。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.9.jar。

## 2.17.8（头顶标签：改为部署/存活视角显示，旁观者模式不显示；标签上移不挡头）
- 显示条件反转：仅部署（ALIVE/存活在玩）状态显示其他玩家头顶标签，旁观者/观察者模式不显示（原为旁观者视角显示）。
- 标签锚点上移（头顶上方 0.45 -> 0.9 格），三行标签不再遮挡玩家头部。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.8.jar。

## 2.17.7（头顶标签：透视缩放远小近大 + 渲染距离跟随游戏设置）
- 标签尺寸按透视距离缩放（6 格处 1.0，越远越小，同原版名字牌），整体缩放（徽章/文字/底衬）用 PoseStack scale。
- 可见距离改为动态读取游戏渲染距离（GameRenderer.getRenderDistance）：人物在渲染距离内才显示标签，超出不渲染，与实体渲染一致。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.7.jar。

## 2.17.6（修复：2.17.5 误把 partialTick 当 FOV 传入 getProjectionMatrix，头顶标签完全不可见）
- 根因：GameRenderer.getProjectionMatrix(double) 的参数是 FOV 度数（内部 x0.017453292 转弧度后 setPerspective），
  2.17.5 误传 partialTick（0~1 小数），FOV 变成约 0.5 度，投影尺度异常放大，标签全部被视口剔除 → 旁观者视角完全看不到头顶标签。
- 修复：改用 mc.options.fov().get()（静态 FOV 设置值，与 v2.17.4 一致）计算 tan(fov/2) 做像素缩放；
  保留官方相机正交基投影（Camera.getLookVector/getUpVector/getLeftVector，解决乱飘）+ Mth.lerp partialTick 插值。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.6.jar。

## 2.17.5（修复：旁观者视角玩家头顶标签位置乱飘）
- 根因：PlayerNametagRenderer 手写三角函数基向量符号错误（fwdY/fwdZ/rightX 与 1.20.1 相机朝向相反），
  且用静态 FOV 投影（mc.options.fov），而游戏实际渲染 FOV 随疾跑动态变化，标签位置随视角/疾跑漂移。
- 修复：改用 Minecraft 官方相机正交基（Camera.getLookVector/getUpVector/getLeftVector）做点积投影，
  从游戏实际投影矩阵（GameRenderer.getProjectionMatrix(partialTick)）提取动态 FOV（含疾跑加成）做像素缩放；
  头顶位置用 Mth.lerp(partialTick, ...) 插值，标签稳定钉在玩家头顶，与游戏渲染完全一致。
  （注：getProjectionMatrix 参数为 FOV 度数，此方案有误，见 2.17.6。）
- 根因：PlayerNametagRenderer 手写三角函数基向量符号错误（fwdY/fwdZ/rightX 与 1.20.1 相机朝向相反），
  且用静态 FOV 投影（mc.options.fov），而游戏实际渲染 FOV 随疾跑动态变化，标签位置随视角/疾跑漂移。
- 修复：改用 Minecraft 官方相机正交基（Camera.getLookVector/getUpVector/getLeftVector）做点积投影，
  从游戏实际投影矩阵（GameRenderer.getProjectionMatrix(partialTick)）提取动态 FOV（含疾跑加成）做像素缩放；
  头顶位置用 Mth.lerp(partialTick, ...) 插值，标签稳定钉在玩家头顶，与游戏渲染完全一致。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.5.jar。

## 2.17.4（身份数据库：装备预览区背景显示阵营图标）
- K 面板（身份数据库）装备预览区（右侧 3D 模型 + 头/胸/腿/靴/枪装备槽区域）背景绘制当前职位所属阵营徽章：
  大号半透明水印徽章（RpIcons.bigBadge alpha 水印），置于装备槽区右侧空白背景，先画背景再画内容，不遮挡模型与装备槽。
- 复用统一徽章封装（t-mt8dmt3a）：职位 → factionId → factionMeta（icon/tier）→ factionBadge/bigBadge；未知阵营跳过。
- scissor 限定在预览区内（不溢出到详情卡片外）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿（83 tests）。

## 2.17.3（管理面板补全增强：限制目标/刷新波职业与阵营/维度/设置职业/序列弹窗波与职业与阵营补全）
- 限制页「目标」输入框补全：按当前类型（FACTION/PROFESSION）过滤对应阵营/职业，显示「名字(id)」，点击/回车填入原始 id；GLOBAL 无目标不触发。
- 刷新波表单：维度（固定三主维度）、职业ID（逗号多值，追加/替换末尾词）、阵营ID（逗号多值）补全；
  设置页「首次入服自动部署职业」补全职业 id。
- 行为序列弹窗：WAVE 步骤「刷新波 ID」补全波 id；FORCE_PICK 步骤「职业ID(逗号)」「阵营ID」补全职业/阵营 id。
- 通用补全引擎：SugSource 枚举 + resolveIdSugSource 按聚焦框推断数据源 + 统一渲染/点击/键盘（↑↓/Enter/Esc），
  多值输入框用 applyIdSug 追加替换、单值框整体替换；补全下拉置顶渲染（widget 之后绘制）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿（83 tests）。

## 2.17.2（修复：管理面板输入补全框被其他控件遮挡，改为置顶渲染）
- 根因：音乐补全 / CMDCam 场景补全的下拉框在 render() 中先于 super.render（widget 渲染：输入框/按钮）
  绘制，导致下拉框被输入框等 widget 盖住（补全内容显示不全/不可见）。
- 修复：renderMusicSuggestions / renderCamSceneSuggestions 移到 super.render 之后绘制，
  补全下拉始终在最上层（弹窗遮罩仍在最外层，弹窗打开时输入框失焦不触发补全，无冲突）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.17.1（修复：管理面板所有列表点击偏移 1 位）
- 根因：新增「限制」页后 tab 数从 6 增至 7，列表点击命中循环已改为从 TABS.length 起，
  但 visibleItem(i - 6) 仍用硬编码 6（未随 TABS.length 同步），导致所有列表（职业/阵营/事件/阶段/刷新波/限制）
  的点击命中偏移 1 行（点第 N 行实际选中第 N+1 行）。
- 修复：visibleItem(i - TABS.length) 与渲染基准 rowBounds.get(TABS.length + i) 对齐；
  全量核对列表索引基准已统一为 TABS.length（渲染/点击/滚动）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.17.0（部署人数限制：限制编辑器 + 选岗显示在职/上限 + 部署前统一检测）
- 数据模型：新增 config/ccnr_rp/limits.json 规则列表（复用 ConfigCrud 管道），规则 = 类型 + 目标 + 人数上限：
  GLOBAL（通用角色上限，职业未配置专属时兜底）/ FACTION（阵营上限）/ PROFESSION（职业上限）。
- 部署检测（全局性，统一 deploy() 入口）：职业维度在职数 ≥ 上限，或阵营维度在职数 ≥ 上限 → 拒绝部署；
  在职数 = 用户库 ALIVE 且职业/阵营匹配的用户（TEMP 征召不进库不占编制）；重新部署（在场换岗）目标维按「不含本人」计。
- 系统强制操作跳过限制：管理员刷人 / 强制征召部署带新 DeployFlag.LIMIT_SKIP。
- 管理面板：新增「限制」页（TAB_LIMITS），规则列表 + 表单（类型循环按钮 / 目标 id / 人数上限）+ 清空全部按钮。
- K 面板选岗：中栏职位行显示在职/上限小标签（满员红色），右栏详情显示「在职 x/y 职业 · 阵营 a/b」，
  空位不足部署按钮禁用并红字提示（服务端仍强校验）。
- 服务端下发：CharacterListS2C 增加 limits 规则 + occupancy 在职统计（职业/阵营），客户端 ClientCharacterState 缓存供展示。
- 测试：新增 DeployLimitsTest（规则解析/职业优先 GLOBAL 兜底/阵营专属/满员拒绝）；构建 compileJava / spotlessCheck /
  test -PrunTests 全绿（83 tests）。
- 文档：docs/09-spawn.md §4.0 部署人数限制（配置/检测/跳过/展示）。

## 2.16.0（职业复活点管理：每个职业可配置部署点，优先级 职业 > 阵营 > 世界复活点）
- 数据模型：职业定义（factions.json professions[]）新增可选 `spawn` 字段（结构与阵营出生点一致：
  rule SPREAD/SINGLE + points[{x,y,z,dim}]），即职业专属部署点/复活点；未配置回退阵营部署点/世界复活点。
- 部署链路：落点优先级改为 **职业部署点 → 阵营部署点 → wave deployAt → 世界复活点**（原为 阵营 → wave → 世界），
  覆盖自部署 / 重新部署 / 首次入服自动部署 / 召唤波 / 征召部署全部入口（统一 teleport/resolveDeployLevel）；
  职业部署点维度同样参与 CMDCam 场景维度解析。SPREAD/SINGLE 规则与阵营一致（分摊随机 / 集中稳定取点）。
- 管理面板：职业表单新增「管理职业复活点…」按钮，弹窗管理坐标列表 + 分布规则（仿阵营部署点编辑器，
  含「+ 添加当前坐标」/移除/规则切换）；阵营按钮文案改「管理部署点…」；弹窗按目标类型发不同网络包。
- 网络：新增 AdminProfessionSpawnC2S（职业 id + 规则 + 坐标列表），服务端 CharacterService.onAdminProfessionSpawn
  写 factions.json（权限 ccnnrp.admin.faction，仿阵营部署点）；保存职业 CRUD 不清除 spawn 字段。
- 测试：FactionProfessionsTest 新增 spawn 解析用例（rule/points/缺省 null）；构建 compileJava / spotlessCheck /
  test -PrunTests 全绿。
- 文档：docs/09-spawn.md 部署优先级三处同步；docs/03-profession.md 数据模型补 spawn 字段说明。

## 2.15.6（召唤波 mode 语义重定义 + 部署完成常驻横幅 + 招募审计修复）
- 波模式重定义：mode 从「部署通道」（SELF_DEPLOY=仅自刷/RESURRECTION=仅复活波/BOTH=双通道）改为「谁能收到邀请」
  （SELF_DEPLOY=存活可收到 / RESURRECTION=死亡可收到 / BOTH=皆可收到），并取代全局「向存活邀约」设置开关（已移除）；
  三种模式均可作为召唤波触发（队伍创建轮询、命令、序列 WAVE 步骤）。存量配置 "RECRUIT" 值自动映射为 RESURRECTION（WARN）。
- 无 CMDCam 场景/未装 CMDCam 时部署时序改为「开局直接落位切生存，电影 HUD/音乐与落位同一时刻开始」
  （不再等动画播完；客户端播完后的 DeployLandC2S 为空操作）；CMDCam 延迟落位路径保持原「先播后落位」。
- 部署完成常驻横幅：服务端 DeployNoticeS2C（部署者定向）→ 客户端顶部居中「已部署：职位」30s（下线清理、电影黑屏隐藏）；
  邀请部署/波次完毕/人满提前部署/正式转职统一提示；非 TEMP 部署保留原 actionbar 消息（双提示并存）。
- 存活玩家可被征召：FORCE_PICK/指定编制/通用波的存活拆分结算时按当前角色状态判别——
  观察/死亡 → 临时征召部署（TEMP）；存活 → 正式转职部署（不处死，改用户角色 + ALIVE + 冷却清零）。
- 招募审计修复：通用波名额分配防超招（count<=0 整波跳过、单通道空缺名额不转移、count=1 不再超招）；
  征召结算先部署成功再标记在场（防「以征召在场」状态卡死）；结算时刻重新校验状态（已自行部署/漂移则跳过）；
  候选池排除已有征召登记玩家（防重复邀请/重复部署）；v2（唯一身份）起 pick 邀请接受即按自己职业部署（移除选岗菜单）。
- 死代码清理：删除误入 java 树的重复 defaults 资源、孤儿角色更新 handler、选岗屏幕与语言键。
- 流程编辑器修复：关闭/保存/Esc 时统一移除参数字段输入框并释放屏幕焦点（不再残留 GUI）；字段描述改为输入框灰色占位提示
  （不再画在框内与输入文本重叠）；点击输入框同步设置屏幕焦点（弹窗内可直接键盘输入）。
- 处决转职 → 重新部署：在场（ALIVE）玩家点「部署」直接重新部署为选定职位——不处死、不留遗体、不结算死亡经验
  （移除处死+遗体延迟队列，走统一 deploy() FORCE_DEPLOY：清背包 → 新职位装备 → 传送部署点 → 入场电影 → ALIVE）；
  二次确认弹窗暂时隐藏（客户端直接发请求）；按钮与确认文案改「重新部署」。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（含新增 WaveQuota/ConscriptDeployMode/RECRUIT 兼容用例）。

## 2.15.5（行为序列「触发事件」锚点：序列内只读锚点，不可删/不可改类型/不可编辑参数，可上移下移）
- 数据模型：序列新增 TRIGGER 锚点步骤（{"type":"TRIGGER","source":"<kind>:<id>","label":"..."}），
  代表触发本序列的真实事件/环境（如事件 qdf_support 即「征召」上下文）；保存时自动写入/对齐，旧数据缺失自动补插。
- 编辑器：行为序列弹窗中锚点行为🔒只读行（青色锁定样式，无「删」按钮，点选仅展示触发来源），
  不可改类型/不可编辑参数，但**可上移下移**调整位置；其余步骤保持点选/上移/下移/删/改类型/参数编辑。
- 执行引擎：SequenceEngine.runSteps 跳过 TRIGGER 步骤（不执行、不占时间线），并把锚点 source 注入
  {{trigger}} 变量（COMMAND 步骤可引用触发来源）；execute 加 TRIGGER 安全兜底。
- 默认配置：defaults events.json / phases.json 补 TRIGGER 锚点示例；新增 SpawnModelsTest 锚点解析用例。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.4（流程编辑器空态优化：打开空序列自动加 WAIT 起始步骤）
- 修复「行为序列点进去都是空的」困惑：事件/阶段/刷新波配置没有内嵌 sequence 时（v1.4.4 起序列嵌入模型，
  独立 sequences.json 已废弃且从未迁移；现有配置大多无 sequence 字段），编辑器打开即空。
- 现在打开空序列自动加一个 WAIT 起始步骤（直接可编辑；只点「关闭」不保存，原配置保持无 sequence）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.3（流程编辑器回归：事件/阶段/刷新波行为序列弹窗编辑，界面仿出生点编辑器）
- 管理面板事件/阶段/刷新波表单新增「编辑行为序列…」按钮，弹出流程编辑器（仿出生点管理弹窗）：
  步骤列表（点选/↑↓ 上移下移/删）+「+ 添加步骤」+ 选中步骤类型切换（WAIT/WAVE/COMMAND/FORCE_PICK）
  + 按类型参数输入框（WAIT=秒数 / WAVE=波ID / COMMAND=命令文本({{event}} {{phase}} {{seq}} 变量) /
  FORCE_PICK=数量+职业ID+阵营ID），保存即走主表单 CRUD 落盘（sequence 字段）。
- 保存链路：弹窗保存写 editedSequence → buildPayload 的 addSequenceField() 优先用编辑结果、否则透传原 sequence；
  切换条目/新建时清空编辑缓存防串条。步骤引擎/执行逻辑不变（SequenceEngine 原有 WAIT/WAVE/COMMAND/FORCE_PICK）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.2（处决转职：在场玩家可点击部署，确认后服务端处死旧角色再部署为选定职位）
- K 面板「部署」按钮对在场（ALIVE）玩家可用（按钮文案变「处决转职」），点击弹出确认框；
  确认后客户端发 KillDeployC2S → 服务端先统一退场处死旧角色（状态→观察+复活冷却+遗体+死亡结算，
  遗体 2 tick 后生成、复制旧背包与旧职位名），再延迟 1s 走统一 deploy()（清背包 → 新职位装备 → 传送 →
  入场电影 → ALIVE，冷却清零）。
- 服务端：CharacterService.onKillDeploy（校验在场/职位/等级）+ SpawnFramework.queueRedeploy/checkRedeploys
  （延迟等遗体生成完再清背包，防遗体复制到空背包/新职位名）；SpawnFramework 波次选择统一为 selfDeployWave()。
- 客户端：CharacterManagementScreen 弹窗（确认/取消，遮罩吞点击）；语言包新增 deploy_kill / kill_confirm_* /
  spawn.error.alive_only / spawn.redeploy.started（zh/en 同步）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.1（管理面板「设置」页泛化：bool 开关 / string 文本输入 / 数值输入框全部可编辑）
- 设置页按类型渲染所有配置项：settings.json 项 bool=开关行、string=文本输入行（如 firstJoinProfession），
  与 serverconfig 数值行统一滚动 + 一个保存按钮；保存按 key 分流（settings.json → ManagerSetC2S，serverconfig → ServerConfigSetC2S）。
- ManagerSettings 类型化：新增 type(key)（bool/string），set() 按类型分流校验（bool 需 true/false，string 直接写入）。
- ManagerSetC2S 值长度上限 16 → 128（支持任意字符串设置项）；firstJoinProfession 改为管理面板可编辑（不再只改 settings.json 文件）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.0（首次入服自动部署：新玩家自动部署为可配置职业，默认 m5_intern 访客/实习生）
- 首次进入设施（本世界无用户档案）的玩家自动部署为配置职业：登录时入队 → 等素材同步完成（60s 超时兜底）
  且入服稳定（≥2s）后走统一 deploy()（装备 → 传送落点 → 入场电影 HUD + 出场音乐 → 状态 ALIVE → 广播）。
- 可配置（config/ccnr_rp/settings.json）：firstJoinAutoDeploy（bool，默认 true）总开关；
  firstJoinProfession（string，默认 m5_intern）自动部署职业 id，空串=关闭（2.15.1 起管理面板可编辑）。
- 细节：首次判定用 UserService.hasProfile()（不惰性创建档案，登录处理须先于档案创建调用）；
  已被其他入口部署（管理刷人/复活波/手动）时自动跳过；掉线清理队列；落点=首个匹配刷新波否则世界出生点。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.5（部署入场电影/音乐与 CMDCam 解耦：无 CMDCam 场景也播电影 HUD 与出场音乐）
- 修复「未配置 CMDCam 场景（或未装 CMDCam）时入场音乐与电影式 HUD 开场消失」：
  2.14.0 起 CinematicS2C（电影 HUD + 出场音乐）仅在「场景名非空且 CMDCam 已装」时才下发；
  本版改为电影 HUD + 音乐始终播放（未 SKIP_CINEMATIC / NO_MUSIC），CMDCam 场景降为可选叠加层。
- 时序：有场景 → 强制旁观者 + 电影与场景同刻播放 → 全部播完落位（不变）；
  无场景 → 强制旁观者 + 电影 HUD/音乐播放 → HUD 播完客户端即发 DeployLandC2S 落位（不等待场景）。
- 客户端无改动（空白 cmdcamScene 分支本就支持「HUD 播完即落位」），纯服务端 SpawnFramework 解耦。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.4（修复 OGG 播放电流声：改用 Minecraft 原生 OggAudioStream 解码）
- 2.14.3 内嵌的 jorbis（googlecode soundlibs 0.0.17.4 fork）解码立体声时左右声道塌缩为同一值（解码器 bug），
  叠加字节序错配 → 播放电流声。
- 改用 Minecraft 自带 com.mojang.blaze3d.audio.OggAudioStream（原生 STB Vorbis 解码，立体声/字节序由 getFormat 提供），
  移除 jorbis 内嵌（build.gradle/OggPcm/libs）。播放逻辑（Clip/淡出）不变。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.3（音乐格式全面切换 OGG：WAV 体积过大弃用）
- 上传/存储/清单：config/ccnr_rp/audio/*.wav → *.ogg（OggS 魔数校验；AssetLibrary 清单同切 .ogg）。
- 播放：javax.sound 不原生支持 OGG——内嵌 jorbis（纯 Java Vorbis 解码）转为 PCM 后走同一 Clip 播放
  （淡出/音量逻辑不变；兼容 WAV/AIFF 走 AudioSystem）。
- 管理面板文案/语言包/单测同步 .ogg；新增 scripts/convert-music-to-ogg.sh（oggenc/libvorbis 自动探测）
  供服务端把既有 WAV 批量转 OGG；转换后客户端按清单哈希自动下载 OGG。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.2（观察者拾取真正禁止：Inventory.add mixin）
- 上版（2.14.1）用 PlayerEvent.ItemPickupEvent 事后取回——better_looting 忽略 add 返回值、批拾取每次都会
  触发物理化模组（ItemPhysic）动画，反复拾取/掉落导致物品在地上「跳舞」。
- 本版改为【入口拦截】：新增 org.spongepowered.mixin（0.7.38）+ InventoryObserverMixin，在
  Inventory.add(ILnet/minecraft/world/item/ItemStack;)Z 入口拦截——观察者（无在场身份的用户/征召兵）
  一律返回 false 拒绝入包；better_looting 忽略返回值 → 按「未添加」处理 → 原物品实体保持完整原地不动
  （不消失、不重复、无物理动画）；移除 2.14.1 的事后取回逻辑。
- 构建：compileJava（含 Mixin 注解处理器校验）/ spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.14.1（入服 5 秒状态栏 + 观察者拾取兜底）
- 刚入服 5 秒：右下角三状态栏（职位/阵营/血量）常驻显示（不管背包是否打开）；其余时间仅背包界面显示。
  （客户端：ClientPlayerNetworkEvent.LoggingIn 记录入服时刻 → StatusHud.renderJoinOverlay 覆盖层按 5s 门控）
- 观察者拾取兜底：better_looting 等模组的批拾取直接 Inventory.add（绕过可取消的 EntityItemPickupEvent），
  观察者仍能拾取物品——在不可取消的 PlayerEvent.ItemPickupEvent（入包后才发）把观察者背包全部丢回地上
  （含拾取延迟防被立即再次吸走），观察者无法持有任何物品。
- 版本号 2.14.0 → 2.14.1。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.14.0（部署流程：先播 CMDCam 入场动画，动画完毕后再传送到出生点）
- 时序反转（applyDeployCore，用户确认）：部署触发 → 强制旁观者 + 电影 HUD（黑屏/图标/文字）与 CMDCam 场景
  【同一时刻开始播放】→ 全部动画播完（客户端检测 HUD 结束 + CMDCamClient.isPlaying 场景结束）→ 发 DeployLandC2S（新包）
  → 移动玩家到部署点（优先级：阵营出生点 → wave deployAt → 世界出生点）→ 设置生存。
- 降级：未设定 CMDCam（无场景名 / 未装 CMDCam）或 SKIP_CINEMATIC → 开局直接落位切生存（不播动画、不等待）。
- 兜底：HUD 播完后再等 20s（场景缺失/异常）自动落位；服务端 120s 超时；动画期间掉线清理待落位。
- 修复「动画全程不显示电影 HUD」：CMDCam 场景播放时每帧设 options.hideGui=true（CamRun.tick）→ GameRenderer 跳过整个
  gui.render（含电影覆盖层）；电影播放期间在 RenderTickEvent.Pre（LOWEST 优先级）强制恢复 hideGui=false，电影结束即停止
  强制（场景余下部分仍隐藏 HUD，场景结束按 CMDCam 缓存恢复）；落位时再确保 hideGui=false。
- 兜底：落位超时 30s（掉线/动画中断）自动传送，防卡暂存点；动画期间掉线清理待落位状态。
- 全入口复用：自部署（deployPosition）/ 复活波（onWaveFinish）/ 征召（onConscriptFinish/deployConscript）/ 管理刷人统一走 deploy()。
- 注：本版本含 2.13.2 的尸体修复（尸体显示玩家皮肤 + 「职位 + 玩家名」名字牌）。
- 版本号 2.13.2 → 2.14.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.13.2（修复：尸体无皮肤 + 尸体名字改为「职位 + 玩家名」——非入侵方案）
- 根因：此前 CorpseBridge 把遗体身份 UUID 改写为 ccnr-char 哈希派生 UUID——客户端按该 UUID 在 tab 列表
  查不到玩家档案（不在线）→ 尸体渲染默认史蒂夫纹理。
- 修复（全部在 CCNR-RP 内，不改 corpse 模组本体/jar，不重打尸体）：
  - 死亡身份注入：PlayerDeathEvent 保持玩家真实 UUID（客户端按 UUID 解析到 LittleSkin 纹理 → 尸体显示玩家本人皮肤），
    playerName 改写为「职位 + 玩家名」（如「警察 小明」；无职位时仅玩家名）；
  - 遗体名字牌：EntityJoinLevelEvent 把 corpseName 移到 customName + 置可见（1.20.1 名字牌仅 customNameVisible 渲染），
    corpseName 置空后 vanilla getDisplayName() 回落 customName——头顶名字与搜尸 GUI 标题一致显示「职位 + 玩家名」，无 "Corpse of " 前缀；
  - 保护：未安装 Corpse 模组时跳过遗体生成，物品按原版正常爆出（日志提示）；
  - 清理：删除哈希派生 UUID/DeathChar 无用代码；_corpse_src 中未部署的魔改源码已还原。
- 版本号 2.13.1 → 2.13.2。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.13.1（修复：CMDCam 场景部署时播不出来——CreativeNetwork.sendToClient 反射签名匹配失败）
- 根因：CamSceneBridge.playScene 用 getMethod("sendToClient", StartPathPacket.class, ServerPlayer.class) 精确匹配，
  而 CreativeCore 实际声明 sendToClient(CreativePacket, ServerPlayer)（参数为基类），getMethod 按声明类型精确匹配必然
  NoSuchMethodException，被 catch 静默跳过 → 部署电影播完黑屏转场不播 CMDCam 场景（日志：CMDCam 场景播放失败（跳过，不阻断））。
- 修复：改为按方法名 + 参数可赋值性扫描（findSendToClient），兼容基类/具体类两种签名；场景存在性与包构造逻辑不变。
- 版本号 2.13.0 → 2.13.1。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.13.0（刷新波与职业支持自定义 cmdcamScene，部署覆盖优先级：阵营 < 刷新波 < 职业）
- 职业（factions.json professions）与刷新波（spawn_waves.json waves）数据模型新增 cmdcamScene 字段：FactionProfessions.upsert / SpawnModels.Wave 解析回读，管理面板职业/刷新波表单新增输入项（复用 CMDCam 场景补全提示）。
- SpawnFramework.applyDeployCore 部署时按「阵营 → 刷新波 → 职业」低到高覆盖取最终 cmdcamScene 下发入场电影：职业最高，空值回退刷新波，再回退阵营；自部署/刷人/复活波/征召路径一致生效。
- 服务端 sendList 职业 JSON 补 cmdcamScene 字段，客户端编辑职业时回显保留。
- 新增 SpawnModelsTest / FactionProfessionsTest 覆盖 cmdcamScene 解析与 upsert 回读。
- 版本号 2.12.0 → 2.13.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.12.0（CMDCam 场景保存回显修复 + 管理面板场景名补全提示）
- 修复：阵营编辑器 CMDCam 出场场景（cmdcamScene）保存后消失——根因是服务端下发角色列表（sendList）的阵营 JSON 漏掉 cmdcamScene 字段，客户端回显永远读到空；现补上该字段，保存后输入框保留值。
- 新增：服务端反射读取 CMDCam 已保存场景名（CMDCamServer.getSavedPaths）随 ManagerStateS2C 下发（camScenes），管理面板 CMDCam 场景输入项聚焦时按输入过滤下拉补全（点击/上下键/回车选中，Esc 关闭）；CMDCam 未装或读取失败时安全降级为无提示。
- 版本号升至 2.12.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.11.0（管理器设置页横向溢出修复：开关右对齐面板内边界，无多余横向滚动条）
- 设置内容区右边界改为 px2-18（滚动条 px2-14 左侧留 4px 间隙），开关右对齐到面板内边界（此前画到 px2-24 起点、46 宽开关越过滚动条导致右侧横向溢出/多余竖向条）。
- 输入框/开关行/rowBounds 宽度统一以 settingsRight() 为右缘，标签避让开关区。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.10.0（旁观者视角玩家头顶标签：阵营徽章 + 职业名(阵营色) + 玩家名 + 等级）
- 新增 PlayerTagsS2C：服务端在登录/登出/部署变更时广播全玩家档案摘要（uuid → 名字/职位/阵营/等级）。
- 客户端 PlayerNametagRenderer：观察者/旁观者视角下，把每个其他玩家的 3D 头顶位置投影到屏幕，
  在 HUD 层绘制真实阵营徽章图标（RpIcons，非文本）+ 职业名(阵营色) + 玩家名 + Lv.等级；距离缩放 + 视口剔除。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.9.0（管理面板设置标签统一滚动区修复 + 阵营编辑器 CMDCam 场景配置项）
- 设置标签：开关 + serverconfig 数值统一进一个滚动区（此前数值区起点过高导致内容飞出去、滚动条失效）；
  开关行按滚动偏移生成（可点切换），数值输入框与绘制行严格对齐。
- 阵营编辑器新增「CMDCam 出场场景」输入项（cmdcamScene 字段）：部署入场电影播完黑屏转场播放该场景。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.8.0（CMDCam 联动：阵营/事件/结算可配 SCENE 摄像机出场，黑屏转场）
- 阵营数据模型增加 cmdcamScene 字段（可编辑），部署入场电影播完 → 渐变黑屏转场 → CMDCam 播放该阵营 SCENE，摄像机从部署点视角走路径，播完回位。
- 动画序列新增 CAMS 步骤类型（param=scene）：player_spawn/event_start/game_end 等钩子序列可直接引用 CMDCam 场景——事件开局/结算通用。
- 新增 CamSceneBridge（反射调用，CMDCam/CreativeCore 缺失时安全降级不崩服）+ CamScenePlayC2S 网络包。
- 默认 animations.json 的 game_end / event_start_alarm 序列加入 CAMS 步骤示例。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.7.0（修复管理面板「设置」标签布局：内容飞出面板 + 数字输入框不可编辑）
- 设置标签拆成两个独立区域：settings.json 开关行固定顶部（可点切换），serverconfig 数值行在下方独立滚动。
- 修复 renderSettings 与 buildSettingsForm 对滚动偏移的解读不一致导致的坐标错位（内容飞出面板）；
- 数字输入框与数值行对齐（mkBox 改为按行定位，值不再双绘），可正常点击编辑。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.6.0（修复管理面板「刷给自己」不赋予身份 + 清理角色库/皮肤/角色上限死代码）
- **修复管理面板「刷给自己：当前角色改为所选职业」**：原实现只发装备+切生存，不写用户身份，
  导致「资源给予了但人物身份没有被赋予」。改为走统一部署入口 deploy()（FORCE_DEPLOY + SKIP_CINEMATIC + NO_MUSIC + QUIET），
  完整赋予职位/阵营/ALIVE 状态/冷却清零/疏散重置，并同步客户端档案。
- **清理角色库系统残留**：删除废弃 CreateCharacterModal；移除 UserService.createRemainingMs/markCreated、
  ClientCharacterState.createRemainingMs/createCooldownUntil/maxCharacters（v2 无角色上限/创建冷却概念）；
  CorpseBridge/StatusManager 移除 skinHash（皮肤系统已删，恒空串）；CCNRRPConfig 移除 maxCharactersPerPlayer/createCooldownSeconds 死配置。
- **清理无用语言键**：admin.profession.gear / count_limit / create_cooldown / character.error.create_cooldown（zh/en 同步移除）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.5.0（管理面板设置全量程序化：settings.json 全部开关可改，含招募邀请/右下角状态栏）
- 管理面板「设置」标签重构：settings.json 的全部开关（forceObserving / openPanelOnJoin / forceRetain /
  recruitInviteAlive / hudEnabled / hudProfessionText / hudFactionText / hudHealthText）程序化生成开关行（点按切换），
  不再硬编码 6 个；补齐此前缺失的 recruitInviteAlive 与 hudEnabled 两个开关。
- 开关行与 serverconfig 数值设定合并为统一滚动区；开关默认值按 ManagerSettings 逐键取值（与服务端一致）。
- ManagerSettings.keys() 补全 hudEnabled；语言包新增 recruit_invite_alive 键（zh/en 同步）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.4.0（K 面板 3D 人物预览关闭名字板：只显示人物模型 + 职位装备）
- CharacterPreview 的 PreviewPlayer 在创建时 setCustomNameVisible(false) + setCustomName(null)，
  K 面板职位详情的 3D 预览不再显示玩家名（bananaxiao2333）名字板，只显示人物模型 + 职位装备。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.3.0（观察者拾取拦截：better_looting 等模组绕过旁观模式拾取的修复）
- StatusManager 新增 EntityItemPickupEvent 监听：观察者（无在场身份的用户）一律取消拾取事件。
  原版旁观者模式本身不能拾取，但 better_looting-1.20.1-forge-2.1.1-hotfix 会绕过游戏模式判断直接给物品；
  Forge 拾取事件层统一取消，覆盖所有拾取来源。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.2.0（部署前清空背包：防止死亡/观察期间遗留物品带进新岗位）
- 统一部署核心 applyDeployCore 在发放职位装备前先清空玩家背包/护甲/副手（0-40 槽），
  覆盖自部署/复活波/强制征召/管理员部署全部路径（都汇入 deploy()）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.1.0（统一部署/退场/结算管道：一个函数 + 行为 flag，删除征召分支文案）
- **唯一部署入口 deploy()**：自部署 / 管理员刷人 / 复活波 / 强制征召 / 手动部署全部收敛为 SpawnFramework.deploy(player, professionId, wave, Set<DeployFlag>)；
  行为差异由 DeployFlag 控制（SKIP_CINEMATIC 取消开局黑屏 / FORCE_DEPLOY 强制部署不论存活 / NO_MUSIC 关闭部署音乐 / QUIET 不刷提示 / TEMP 临时征召身份），
  流程固定：读职位→门控(按 FORCE)→loadout→传送→cinematic(按 SKIP)→音乐(按 NO_MUSIC)→状态/角色(按 TEMP)→广播(按 QUIET)→evac 重置。
- **唯一退场入口 retire()**：普通死亡 / 判死(命令/掉线/轮询兜底) / 下班(退役) / 征召结束全部收敛为 StatusManager.retire(uuid, player, reason, Set<RetireFlag>)；
  行为差异由 RetireFlag 控制（SPAWN_CORPSE 生成遗体 / OFFLINE 离线结算挂起 / SKIP_SETTLE 不结算），共用同一状态迁移 + 同一结算函数（settleUserDown）+ 同一逐行绿/红。
- **删除征召分支文案**：ccnr_rp.spawn.conscript.kia（征召兵阵亡，编制结束）与 ccnr_rp.xp.settle.conscript 从语言包移除；
  settleConscriptDeath 独立分支删除，征召执勤时长并入用户档案后走统一结算。
- 命令 /rp state kill 改为走统一 retire（原来手动置 DEAD 不结算）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.0.0（v2 重构：删除角色实体，改为职位选择 + 等级解锁门控）
- 删除角色实体/角色库：玩家身份（在场/阴间/观察）+ 当前职位 + 复活冷却 + 执勤/任务/疏散全部上移到用户层。
- K 面板改为职位选择器：左机构过滤 / 中职位列表（需求等级达标绿 Lv N、未达标红）/ 右详情 + 部署按钮；未达等级/非观察/冷却中置红禁用并显示「需要等级 Lv N」；选中即部署。
- 部署门控改为「用户等级 ≥ 职位 unlockLevel」，取消 selfDeploy 自选。
- 取消 K 面板打开限制，任意时刻可开。
- 经验结算右下角逐行渲染：绿色加分（+20 值班）/ 红色减分（-10 死亡）；结算器支持负值，evacDiedXp 默认 -10。
- 管理面板职位编辑器新增「解锁等级」字段。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 1.5.29（全 GUI 灰色半透明底重做 + 同步完成自动开面板 + 观察者常驻提示）
- **全 GUI 灰色半透明底重做**：RpTheme 面板/边框/文字全部改为中性灰阶半透明底（功能色仅保留选中红、警示、等级徽章、青色强调），19 处硬编码青蓝底色（按钮/滚动条/悬停/徽章/事件横幅/招募卡片等）同步改灰；
  管理面板删除黄色「● ADMIN」徽章（非管理员保留红色无权限提示）。
- **同步完成自动开 K 面板**：入服自动开面板改为素材同步完成（AssetSyncDoneC2S 回执）后触发，走与按键完全相同的统一入口 openCharacterPanel（受 openPanelOnJoin 设置控制）。
- **观察者常驻提示**：观察者身份时 actionbar 常驻显示「观察中，按 <绑定按键> 进行部署」（约 1.5s 刷新，按键名随绑定实时显示）。

## 1.5.28（素材下载异步化 + 同步数据中提示 + 同步前禁用部署 + 状态栏改背包内显示）
- **素材下载异步化（修复进服卡顿）**：客户端分片到达在网络线程仅 O(1) 累积（不再主线程 O(n²) 拷贝/大文件写盘），
  清单解析、分片拼接、磁盘写入全部移到后台线程；服务端清单哈希按 size+mtime 缓存（登录不再全量 SHA-256），
  素材流式下发放到后台线程；单个素材请求 15s 超时自动跳过，防卡死。
- **「正在同步数据…」提示**：同步期间左上角显示小标签（客户端提示与服务端拒绝部署共用 lang 键）。
- **同步完成前禁用复活/部署**：服务端登录时标记待同步（有通道时），客户端处理完清单/下载完毕回执
  AssetSyncDoneC2S 后放行；自部署/复活波/征召/强制抽取全部前置校验（60s 超时兜底放行，防老客户端锁死）；
  登出清理待定标记。
- **右下角状态栏改为背包内显示**：与事件横幅一致，仅 InventoryScreen 打开时绘制，不再常驻游戏内。

## 1.5.27（素材中央下发 + 管理面板创建修复 + 界面素化）
- **素材中央下发（服务器控制全部素材）**：新增素材库（config/ccnr_rp/audio/*.wav 音乐 + config/ccnr_rp/textures/*.png 阵营图标），服务器按清单（AssetManifestS2C，名称+大小+SHA-256）下发，
  客户端对比本地缓存（config/ccnr_rp/assets-cache/，文件+hash 边车）自动请求缺失/变更素材（AssetRequestC2S → AssetPartS2C 32KB 分片，逐个下载）；音乐播放与 img: 阵营图标渲染一律使用服务器下发版本——
  客户端不再依赖本地/内嵌副本，也无法用本地文件替换；管理面板图标选择自动并入服务器素材库图标。
  服务器首次启动自动把内嵌默认图标（admin_hq/madison）写入素材库；音乐上传/管理端 CRUD 后全服清单即时刷新。
- **管理面板创建修复**：ID 输入框在「创建模式」下被误锁（!edit 反转），无法输入 ID 导致无法创建——
  改为仅编辑模式锁定 ID（创建模式可输入）。
- **界面素化**：去掉面板水印背景图（RpBg）、扫描线、四角角标、顶部光泽等装饰元素，选中行保留红色高亮线，
  界面更朴素克制，仅保留功能性元素。

## 1.5.26（配置服务器侧化：等级曲线跟随服务端同步 + 修复进服自动打开面板）
- **配置服务器侧化**：等级曲线（levelBase/levelPow）由服务端在角色列表包（CharacterListS2C）中下发，
  客户端等级/进度展示一律使用服务端同步值，不再读取本地 serverconfig/ccnr_rp-server.toml——
  客户端调参不再可能影响任何设定，杜绝本地篡改与服务端不一致（其余管理器设置/阵营/职业/事件/阶段/波
  本已由服务端下发同步）。
- **修复进服自动打开面板**：原实现「一次性消费标记」在进服瞬间玩家实体/界面未就绪或已有其他界面
  （如招募弹窗）时会把标记提前消耗掉，导致面板永远不自动打开；现改为仅在真正打开面板时消费标记，
  条件未就绪时下一 tick 重试，招募弹窗打开期间等待其关闭，玩家打开其他界面或状态变为非观察者
  （在场/阴间/征召在场）时取消，绝不强抢界面。

## 1.5.25（尸体皮肤按哈希独立请求渲染：与角色档案解耦）
- 根因：客户端 SkinCache 按「皮肤哈希派生 UUID / 角色名」查找尸体皮肤时，只遍历本地玩家自己的角色列表（ClientCharacterState.list()）；别人客户端里没有死者的角色条目，尸体皮肤查不到，回退原版皮肤。
- 尸体自包含身份：尸体带名字 + 哈希派生 UUID（CorpseBridge 写入），皮肤按哈希值从服务端请求，与角色档案解耦。
- 客户端新增 `HASH_UUID_CHARS`（皮肤哈希派生 UUID → 角色 id）映射：`SkinCache.store` 时按与 CorpseBridge 一致规则建立，任何客户端都能按尸体的哈希派生 UUID 直接取到皮肤（换肤时清理该角色旧哈希映射，防旧尸体被误指向新皮肤）。
- 新增 `SkinRequestC2S`（按「皮肤哈希派生 UUID」请求皮肤）：客户端渲染尸体时若本地未缓存则按哈希向服务端请求（每个 UUID 仅请求一次，防刷屏；`isLikelyCorpse` 用「UUID 不在 playerInfoMap 且非本地玩家」判定假玩家/尸体）；服务端 `CharacterService.onSkinRequest` 扫角色找到该哈希的皮肤并下发。
- `SkinSpec.derivedUuid(hash)` 统一派生公式（服务端/客户端共用），补 SkinSpecTest 单测。
- 已在 `RpChannels` 末尾追加消息注册（不重排既有消息 id，保持通道兼容）。

## 1.5.24（兼容旧版 64x32 皮肤：自动归一化为现代 64x64）
- 原来：旧版 64x32 皮肤在现代玩家模型（64x64 布局）下，左臂/左腿对应纹理区（y 48-63）不存在，越界采样取到底部行导致肢体错色、左右不对称。
- 现在：新增 SkinSpec.toModern（64x32 转 64x64，右臂/右腿水平镜像补到左区；幂等，64x64/非 PNG 原样返回），在上传存储、全服广播、部署/登录重新读取三个入口统一归一化。
- 客户端 SkinCache.store 再加兜底：任何到达客户端的旧式皮肤先转现代再注册纹理。
- 补 SkinSpecTest 单测：64x32 转换正确性 / 64x64 幂等 / 非 PNG 原样返回。

## 1.5.23（皮肤系统增强 + 音乐管理 + 图片徽章）
- **图片徽章**：阵营图标支持 `img:<名>` 指向 mod 内嵌图片（assets/ccnr_rp/textures/faction/）；已部署「行政总部」「麦迪逊研究所」两张手绘图标（管理面板图标选择新增 img: 选项）。
- 皮肤上传支持 **URL 直链**（本地路径或 http(s) 均可，客户端后台拉取后走同一分片协议）；服务端校验升级为 **MC 皮肤规格**（PNG、64×64 或 64×32、≤256KB，SkinSpec 纯类可测）。
- 皮肤存储改为 **sha256 哈希去重**：`world/ccnr_rp/skins/<hash>.png`，同图只存一份；旧 `<charId>.png` 自动清理并在读取时回退兼容。
- **扮演换肤**：部署/登录时服务端重播皮肤（SkinSyncS2C 携带 playerUuid），客户端将正在扮演角色的玩家渲染为目标角色皮肤——全服看到"分毫不差"的角色外观。
- 角色查看界面 3D 预览改用**真实上传皮肤 + 职位 loadout 装备**（替换硬编码下界合金套；同步广播补充 loadout 字段）。
- **皮肤回收**：没有角色引用的皮肤文件自动清理（角色删除/换肤后 + 服务启动时各一次；旧式 `<charId>.png` 在迁移期视为被引用不误删）。
- **音乐管理**：管理员可上传音乐（本地 .wav 或 URL，服务端校验 RIFF/WAVE + ≤20MB，存 `config/ccnr_rp/audio/`）；管理面板出场音乐框带**补全提示**（过滤下拉/↑↓+回车/点选），上传后全服列表即时更新。

## 1.5.22（音乐传递：启动程序指定 > 职业音乐 > 阵营音乐）
- 入场电影音乐解析链（高→低）：**启动程序（启动器）指定音乐** > **职业音乐**（professions.music，已有）> **阵营音乐**（factions.music，新增）。
- 启动器指定：客户端 JVM 参数 `-Dccnr_rp.entrance_music=<路径>` 或环境变量 `CCNR_RP_ENTRANCE_MUSIC`，优先级最高；未指定时回落到职业/阵营配置。
- 阵营新增 `music` 字段：factions.json 可配（相对 config/ccnr_rp/ 或绝对路径，WAV），管理器阵营表单可编辑；空串=不设阵营音乐。
- 入场电影载荷携带 `factionMusic`，客户端按优先级解析；音乐不进 jar，全部配置化。

## 1.5.21（滚动条可拖拽 + 刷给自己接入统一装备流程）
- 滚动条支持鼠标拖拽：按住游标拖动即滚动（角色面板、管理器、创建角色弹窗两栏），点击轨道空白跳转到该位置；同一界面多条滚动条按 id 分发。
- 管理面板「刷给自己」接入统一流程：改职业（含阵营）的同时套用该职业的装备（LoadoutManager，含 NBT），与部署磨子同一套逻辑。

## 1.5.20（/rp 全命令 Tab 补全）
- 所有 /rp 子命令参数支持 Tab 补全列表：职业 id、阵营 id、事件 id、刷新波 id、阶段 id、在线玩家名、本人角色 id（创建角色的阵营/职业、save/load/trigger/enable/phase set/state/kill/xp/evac/select/activate/observe/delete/cooldown 等）。

## 1.5.19（职业装备保存默认全量：物品栏+盔甲+副手+NBT）
- /rp profession save <id> 默认全量保存当前装备到指定职业：物品栏 0-35 + 盔甲栏 + 副手 + 物品 NBT（原默认只存快捷栏，需 --full）；--hotbar 保留为"仅快捷栏"选项。
- 保存成功提示区分全量/快捷栏文案。

## 1.5.18（游戏模式轮询只保留观察者兜底）
- 轮询不再强制把非观察者（有在场角色/征召）切回生存模式——不干预玩家/管理员的游戏模式选择；仅保留「观察者 → 旁观者」兜底（死亡界面中的玩家仍跳过）。

## 1.5.17（修复死亡瞬间切旁观导致无尸体/无掉落）
- 修复：死亡瞬间（LivingDeathEvent/markDead 内）不再切换旁观者模式——此前立即 setGameMode(SPECTATOR) 会打断原版死亡掉落与 Corpse mod 尸体生成，导致死亡后无尸体、物品不掉落。
- 现在：死亡瞬间保持正常死亡流程（掉落 + Corpse 尸体生成），点重生时由 onPlayerRespawn 切旁观者并传回尸体旁；游戏模式轮询跳过死亡界面中的玩家（isDeadOrDying），不再打断死亡流程。

## 1.5.16（断联立即判死结算 + 遗体保留在地上）
- 确认并增强断联/掉线判死链路：断联立即结算数据并判死（观察模式+复活冷却），经验结算服务端立即执行（离线明细挂起、上线补发）。
- 遗体生成改为延迟 2 tick（玩家实体移除完成后在最后位置生成，Corpse mod 尸体保留在地上），避免掉线事件触发时的实体移除时序导致尸体丢失。

## 1.5.15（玩家提示文案全面精简：去掉解释性内容）
- 全面审计玩家可见提示：移除括号/破折号后的机制解释（如"你的角色不受影响"、"无角色档案，角色结算跳过"、"需观察状态且职业允许自部署"等），只保留结论性短句（知道被拒绝/结果即可）。

## 1.5.14（K 面板仅观察者可开：客户端 + 服务端双重强制）
- 面板锁规则明确为「仅观察者身份可打开 K 面板」：在场（ALIVE）/阴间（DEAD）/征召身份在场一律锁定。
- 客户端面板锁同步该规则（征召在场也锁定）；服务端新增统一校验 isObserver，创建/删除/部署/激活/皮肤上传等 K 面板操作全部前置校验（非观察者直接拒绝），不再只依赖客户端标志。

## 1.5.13（统一部署/死亡/结算流程：征召兵只是临时名字）
- 统一部署磨子 applyDeployCore（普通角色与征召兵共用）：装备→传送→生存→入场电影（阵营关系从图谱推导）→ 各自的身份状态推送。
- 统一死亡流程：死亡一律 切旁观者模式 + 记录死亡地点（复活后传回尸体旁）；征召身份清理只是流程第一步，不再单独分支。
- 统一结算顺序：先给玩家加分（用户经验，升级提示按用户等级），再给角色结算写盘；角色不存在（征召兵无档案）则跳过角色结算，仅按征召值班时长给玩家加分。
- 征召兵记录部署时刻，死亡时按执勤秒数折算玩家 XP。

## 1.5.12（修复征召兵阵亡后不立即切旁观者模式）
- 征召兵阵亡时立即切换旁观者模式（此前只清除征召登记，需等 2 秒轮询兜底才切旁观），并记录死亡地点——复活后传送回尸体旁旁观，与正式角色死亡体验一致。

## 1.5.11（邀请界面：类型标签 + 颜色区分 + 描述更新）
- 邀请弹窗与右侧卡片按邀请类型区分颜色：强制征召=红 / 指定编制复活=金 / 通用复活·选岗=青（左侧色条 + 类型标签）。
- 每张邀请卡片显示类型描述（强制征召=按编制分配角色 / 指定编制=按波次类型分配角色 / 通用=选择自己可复活的职业上岗）。

## 1.5.10（新增默认通用复活波 general_reinforce）
- 默认 spawn_waves.json 新增通用波 general_reinforce（未指定职业/阵营）：触发后向有可复活角色的玩家发邀请，接受后弹出选岗菜单，从自己可复活的观察角色中选职业上岗。
- 服务器 config/spawn_waves.json 同步新增；可用管理面板「召唤复活波」或 /rp spawn trigger general_reinforce 触发。

## 1.5.9（复活波按类型征召 + 通用波选岗菜单 + 移除管理面板行为序列编辑）
- 复活波语义重构：指定类型波（配置了职业/阵营）= 按类型征召——接受后被分配波次编制角色（临时、不进角色库、用完即删），不再部署玩家自己的角色；通用波（未指定类型）= 选岗——接受后弹出「选择上岗职业」菜单（人物渲染 + 名字/职业/部门），从自己可复活的观察角色中选一个上岗。
- 移除管理面板的「行为序列」编辑功能（事件/阶段/波表单中的序列编辑按钮与弹窗）；序列仍会照常执行（配置内嵌 sequence 字段保存时透传保留），需要调整序列请直接编辑 config/ccnr_rp 下的 JSON。
- 删除 WaveSelector 复杂选人逻辑（阴间/阳间/支援优先级），触发逻辑内联简化。

## 1.5.8（修复征召应征却部署了自己角色：邀请类型区分 + 单在场守卫）
- 修复：QDF支援事件同时触发征召邀请（FORCE_PICK）与复活波邀请（WAVE qdf_reinforce），弹窗里两张卡片都显示"复活波"，玩家容易误点——接受复活波邀请即部署自己的角色。现在邀请卡片明确区分「征召」与「复活波」（RecruitOfferS2C 携带 kind），服务端提示也分别使用「征召邀请」「复活波邀请」文案。
- 单在场守卫：已有在场角色（或已部署征召）时接受征召 → 部署取消并清理征召记录，防止先复活波后征召造成双身份。

## 1.5.7（复活波选人统一为自部署式观察者池）
- 复活波选人重写为与自部署一致的简单语义：统一"观察者"一个池（不再分阴间/阳间两池）。
  1) 按玩家去重（同一玩家保留优先级最高的候选：开支援 > 职业匹配）。
  2) 优先级 1：所有开通「以任何支援身份复活」的观察者 → 全部邀请（无视职业/阵营/自部署/冷却，等级高优先）。
  3) 优先级 2：没开支援但有匹配职业（非自部署）的观察者 → 随机补足到波次人数。
- 接受邀请后统一走 deployCharacter 部署封装（装备/传送/状态/入场电影与自部署一致）。
- 修复：同玩家多角色时不再"第一个角色不匹配就收不到"（候选去重移入选人算法按优先级）。

## 1.5.6（复活波叫不到人修复 + 征召兵 HUD 显示 + 阵营关系从图谱推导）
- 修复：复活波叫不到人——阳间池此前排除自部署职业（selfDeploy=true 的观察者不参加复活波），开启「以任何支援身份复活」的用户也收不到；现在开通支援复活的用户绕过全部限制（职业/阵营/自部署过滤），死亡/观察状态都能收到复活波邀请。
- 征召兵部署后右下角 HUD 实时显示征召编制（职业/阵营/血量真实更新），阵亡后自动回到观察模式——征召兵身份由服务端推送（ConscriptStateS2C），不再依赖角色库。
- 修复：征召兵入场电影的阵营关系不再是硬编码空数组——与正常部署一致，从阵营图谱 resolve 推导非中立关系。
- 服务器 spawn_waves.json 无需改动（qdf_reinforce 仍限定 QDF 编制；未开支援复活的非 QDF 玩家默认收不到，符合开关语义）。

## 1.5.5（全代码审计修复：阶段触发器/结算幂等/邀请与征召生命周期等 30+ 项）
- 征召邀请挂起中（弹窗未接受）不再被切生存（待定征召不算在场）；接受部署后才视为在场并播放入场动画（征召兵无角色档案，直接构造电影数据）。
- 修复阶段触发器失效：EventManager 重复调用 clock.tick() 导致阶段时长偏短、ON_PHASE_START/END 几乎不触发 → 改为按节流周期单次推进；ON_TIME 的 day 改为真实游戏天数；PhaseClock 时长≤0 兜底。
- 修复 CONDITION 触发器完全失效（type 字段被外层覆盖）→ 子类型改用 cond 字段；畸形配置（非数字/非对象）不再崩服（EventModels/SpawnModels/CharacterData/UserService 解析加固）。
- 结算幂等：先落 ledger 基线再写角色 XP（崩溃窗口最多少发一次，绝不重复发放）；settleAll 单条坏档异常隔离；疏散 XP 每局（部署时）重置重新发放；任务 XP 只登记给在场角色且事件结束/清空时清除（防无限累加）；删除角色同步清理结算基线。
- 复活波：候选与在线玩家 1:1 配对（防邀请错发/漏发）；同一玩家每波仅一个候选；阴间池加在线过滤；count≤0 兜底；超时后不可再接受邀请；分组结算后释放 finished（防内存泄漏）。
- 征召兵：接受部署后才视为在场（轮询/部署守卫一致）；部署播放入场动画。
- 角色/存档：禁止删除在场(ALIVE)角色；皮肤分片累积上限；离线判死不再依赖冷却标记（onActivate 复活角色也能兜底判死）；坏 UUID 不打断轮询/结算；起服崩溃防护（坏值回退默认）。
- 网络：皮肤分片/同步字节上限修正（32768/262144）；ErrorS2C 参数编解码一致（≤16）。
- 客户端：邀请过期清理（防弹窗死锁）；角色面板冷却字段缺失不再 NPE；自动开面板每登录一次（防反复弹）；非管理员拦截管理器操作；序列编辑器 ESC 返回上层；输入框安全读取。
- 其他：删除被组/关系引用的阵营被拒绝（防图谱整体失效）；装备槽位越界/坏 NBT 跳过；armor 槽位上界修正；皮肤缓存纹理释放与 charId 净化；/rp character create 的 background 不再被丢弃；FactionCommand 空白切分正则修正。

## 1.5.4（事件/征召/复活波触发实例带 UUID 分组）
- 每次触发（复活波/事件征召/命令召唤）都分配独立 UUID 分组：同 id 的多次触发互不干扰，可同时存在多轮邀请；结算/拒绝/超时后再次触发照常发新邀请（弹窗与广播仍显示原波次 id）。

## 1.5.3（修复波次/征召结算后无法再次邀请）
- 修复：同一复活波/征召结算一次后即被永久标记"已完成"，之后再次触发（管理面板召唤/事件钩子/命令）不再发任何邀请——表现为拒绝邀请后一段时间内收不到任何邀请（直到重启）。现在每次触发都会重新开启邀请，拒绝/超时过的玩家下次仍能收到；上一轮未结算的实例先结算、已加入记录重置，避免重复部署。

## 1.5.2（角色库为空时右下角 HUD 仍显示观察模式）
- 修复：角色库为空（无角色/被征召兵占用外全部删除等）时右下角状态栏不再消失——统一显示观察模式（观察者 / 观察模式 / 不适用）。

## 1.5.1（征召兵完全临时化：不进角色库 + UID 编制 + 无角色也能征召）
- 征召兵改为**完全临时内容**：不再写入角色库——不占角色上限（5 个）、不显示在 K 面板、不遵守创建冷却等角色库规则；阵亡/拒绝/超时/下线即消失，玩家自己的角色完全不受影响。
- 接受征召邀请 → 生成独立的 **UID 征召兵**（如 QDF-A1B2-3C，按编制职业/阵营），装备+传送+生存部署；不再把你现有的角色变成征召兵。
- **无可用角色也能收到征召邀请**：开启「以任何支援身份复活」时，只要在线且未在场（哪怕角色库为空）都会被征召；关闭时仍需有观察角色。
- 正在以征召兵在场的玩家：不会被二次征召、不会收到复活波邀请、不能自部署其他角色（单在场身份约束）；游戏模式轮询将其视为「在场（生存）」。
- 旧版（1.5.0）误写入角色库的征召兵角色会在服务启动时自动清理。

## 1.5.0（用户体系大重构：经验随用户走 + 双向观察模式 + 邀请制全面化）
- **用户体系**：一个玩家 UUID = 一个用户；角色/阵营挂在用户下，K 面板仅管理本人角色；等级与经验随用户走（结算归入用户，角色身上也记录各自获得的经验），HUD 等级进度显示用户等级。
- **观察类型双向强制**（每 2 秒轮询）：观察者（无在场角色）→ 旁观者模式；非观察者（有在场角色）→ 生存模式。
- **角色上限 5 个/用户**（可配置 maxCharactersPerPlayer）；**创建冷却**（可配置 createCooldownSeconds，防利用新角色跳过复活冷却），K 面板与创建弹窗显示剩余冷却/角色数。
- **「以任何支援身份复活」开关**（K 面板页眉）：开启后未匹配职业/阵营也能收到复活波与征召邀请；关闭只收匹配职业邀请。
- **强制征召改为邀请制**：FORCE_PICK 步骤改为向候选发征召邀请，自选加入；接受后按征召编制换职业部署（保留原名字，用完即删）；序列编辑器移除「随机名/刷新」开关。

## 1.4.13（强制征召不再改名：保留原角色名）
- 修复：强制征召（FORCE_PICK）不再把征召兵改成随机 UID 名称（QDF-XXXX）——征召兵用完即删，改名只会把角色列表搞乱；现在仅更换职业编制（如 QDF 队员/特工），保留玩家原名字。
- 已登记在册的征召兵不会重复被征召；序列编辑器移除「随机名」开关（旧配置里的 randomName 字段被忽略）。

## 1.4.12（征召兵用完即删 + 复活波自选加入 + 管理面板快捷操作）
- 征召兵（强制征召/强制抽取产生）死亡/判死/退役后直接删除角色，不再留在角色列表；删除时通知拥有者。
- 复活波改为「邀请制」：阴间池 + 阳间池候选都会收到邀请，**自行选择加入或拒绝**（不再强制复活）；**活着（有在场角色）的人不会收到邀请**。
- 已加入名单实时广播：「X 已选择加入（复活波 N：已 x 人 / 需要 y 人）」；**人满即提前部署**，超时按已加入人数部署，无人加入则公告失败。
- 管理面板新增快捷操作：职业页「刷给自己」（自己的角色直接改成所选职业含阵营）、事件页「触发事件」（手动启动事件）、刷新波页「召唤复活波」（手动发邀请）。

## 1.4.11（单在场约束：修复征召兵死亡状态不清）
- 修复：强制征召（FORCE_PICK）不再征召已有在场（ALIVE）角色的玩家——此前可造成玩家同时拥有 2 个存活角色，死亡时 findAlive 按顺序误杀另一个角色，导致征召兵死亡后状态不被清掉（或主角色被误杀）。
- 部署链路（自部署/复活波/强制抽取/招募）统一增加服务端硬校验：拥有者已有其他存活角色时拒绝部署（单在场约束）。
- 修复：已征召玩家死亡 → 状态正确清为观察模式 + 旁观者模式。

## 1.4.10（行为序列编辑器修复：回填真实值 + 输入框标签 + 滚轮滚动）
- 修复：序列编辑器打开/点选步骤时输入框不再显示默认值——现在回填该步骤已保存的真实值（等待秒数/刷新波ID/命令/数量/职业/阵营），修复"未改动直接保存就把原数据覆盖成默认值"的数据丢失。
- 输入框上方增加用途标签（数量/职业ID/阵营ID/等待秒数/刷新波ID/命令文本等），不再是无说明的空白框。
- 上移/下移/删步骤/类型切换/随机名/刷新开关切换前自动保存当前输入，不再丢字。
- 步骤列表支持滚轮滚动（超过 6 步仍可编辑查看），并显示滚动提示。

## 1.4.9（死亡旁观者：复活后传送回尸体旁 + 结算强制观察者 + 观察者自动旁观）
- 死亡时记录死亡地点；复活（点击重生/自动重生）后传送回死亡地点，确保进旁观者模式时就在尸体旁边（不改变出生点/床点）。
- 结算（死亡/断联/退役）完成后强制刷成观察者身份。
- 观察者身份自动刷成旁观者模式：每 2 秒轮询兜底，未部署（无在场角色）玩家强制旁观者模式，防漂移回生存。
- 掉线/退出时清除死亡地点记录，避免误传。

## 1.4.8（死亡强制旁观者模式：不传送）
- 死亡/判死（在线）→ 玩家强制切换为旁观者模式（SPECTATOR，不传送）；复活波/自部署时部署链路自动切回生存。
- 登录补强：角色处于复活冷却（近期死亡/判死）且无在场角色时，登录即强制旁观者模式。
- 「激活」为在场角色时恢复生存模式，避免旁观者状态下卡死。

## 1.4.7（死亡即观察模式：状态机直接转观察者 + HUD 套用一个状态）
- 修复：死亡/判死/退役不再停留在 DEAD（阴间）——handle（死亡事件）直接写观察模式（OBSERVING）+ 复活冷却标记；轮询兜底（每 5 秒）把旧存档残留 DEAD 归一化为观察者（保留冷却标记）。双保险保证死亡后角色一定是观察模式。
- 右下角状态栏套用一个状态：非存活（观察模式）时三行统一显示——职业=「观察者」、阵营=「观察模式」、血量=「不适用」（灰显），死亡后状态栏不再消失。
- 语义不变：复活冷却只锁「自己职业自部署」；复活波/强制抽取（FORCE_PICK）无视冷却强制复活。

## 1.4.6（阴间等待复活 + 事件横幅仅背包可见 + QDF支援事件）
- 修复：死亡/判死恢复阴间流程——角色进入 DEAD（阴间）等待复活，显示复活冷却倒计时；冷却结束自动回观察者池，等待刷新波/FORCE_PICK 重新部署（绝不自动复活）。
- 修复：右下角状态栏死亡后不再消失——死亡时第三行显示「阴间 · 复活冷却 X 分/秒」；角色列表无选中角色时回退显示首个角色档案。
- 事件横幅改为仅背包（InventoryScreen）打开时绘制；游戏内 HUD 与其他界面（角色面板/管理器等）不再显示。
- 移除横幅下方「活动事件（/rp event clear 清空）」提示文字，以及 /rp event clear 的「已清空 N 个事件」文字反馈（命令仍生效）。
- 新增默认事件 qdf_support（QDF支援）：危险阶段开始（或 /rp event trigger qdf_support 手动触发）→ 公告 → 10 秒后强制征召 5 名在线角色转为 QDF 编制并部署（FORCE_PICK，随机 QDF 名称）→ 触发 qdf_reinforce 复活波。
- 修正默认 qdf_reinforce 复活波职业 ID 为现行配置（qdf_guard/qdf_special → s4_guard/s3_special），QDF 推荐波不再因职业不匹配而空拉。

## 1.4.5（死亡即观察者 + 创建角色双栏滚动选择 + 滚动条）
- 死亡/判死不再停留在 DEAD 状态：立刻回观察者池（状态显示「观察中」），并打上复活冷却标记。
- 冷却语义：只锁「自己职业自部署」（K 面板激活/部署按钮需冷却结束）；复活波/强制抽取（FORCE_PICK）无视冷却强制复活。
- 创建角色弹窗重构：左侧阵营列表（行背景=阵营主题色，选中/悬停加亮）+ 右侧职业列表（随阵营联动滚动），弃用循环按钮。
- 新增 RpScrollbar：所有滚动物（K 面板角色列表/机构导航、管理器列表、创建弹窗两栏、事件横幅横向）显示滚动条（比例游标：灰色槽 + 青色方块）。
- 阴间循环保留旧存档 DEAD → 观察者归一化（保留冷却标记）。

## 1.4.4（统一部署入场电影 + 序列并入实体 + 弹窗与确认）
- 部署统一：所有部署路径（自部署/复活波/招募/强制抽取）一律播放入场电影（黑屏→阵营图标→打字档案→淡出），取消旧的 player_spawn 即时动画。
- 序列不再是独立实体：行为序列（WAIT/WAVE/COMMAND/FORCE_PICK 步骤）内嵌到事件 / 阶段 / 刷新波定义（events.json / phases.json / spawn_waves.json 的 sequence 字段）——事件开始、阶段开始、波触发时自动执行；管理器「序列」页签移除，改在对应表单内点「行为序列」弹窗编辑。旧 sequences.json 的 startSequence 引用仍兼容。
- 创建角色改为弹窗（名字 + 阵营/职业循环选择 + 创建/取消），不再挤在面板底部表单。
- 管理端 CRUD 影响预检：保存/删除阵营/职业/事件/阶段/波之前，服务端计算波及清单（引用该条目的角色/职业/波/事件/阶段），无波及直接执行，有波及弹确认框（确认执行/取消）。
- 自动同步：管理端任何设置/CRUD 变更后立即向全服在线玩家推送角色列表 + 管理器状态（阵营/职业/事件/阶段/波实时刷新）。

## 1.4.3（死亡/断联自动结算 + 输入框用途标签）
- 新：死亡/断联自动结算管线——判定死亡或断联时立即：① 同步角色状态（服务器存储+面板/锁刷新）② 尸体生成（Corpse 模组存在时）③ 角色转 DEAD（阴间）④ 服务器侧 XP 结算（值班/任务/疏散增量结算，幂等）⑤ 玩家侧显示结算明细（聊天消息）。
- 玩家在线 → 死亡/断联结算明细直接推送；离线（断联判死/后台掉线）→ 挂起通知落盘 pending_notices.json，上线时自动补发。
- 新：面板（K）与管理器所有输入框上方显示用途说明标签（名字/皮肤路径/ID/名称/颜色/描述/音乐/简历/数量/坐标/维度/职业/阵营等），行距加宽避免重叠。
- 阴间循环保持：DEAD 冷却结束自动回观察者且仅有被部署才会 ALIVE；死亡后 K 面板立即解锁。

## 1.4.2（阴间循环：死人不复活 + 死亡后面板解锁）
- 新：阴间循环检测（5 秒周期）——DEAD 且冷却结束的角色自动回到观察者（OBSERVING，阴间），等待刷新波/FORCE_PICK 重新部署；绝不自动复活。
- 登录归一化：上线时 DEAD+冷却结束的角色立即回观察者并同步面板。
- 冷却中的死者保持 DEAD（阴间）；只有被部署（复活波/强制抽取）才会变 ALIVE。
- 修复：死亡后 K 面板仍打不开——客户端面板锁改为由角色列表实时推导（CharacterUpdateS2C 到达即解锁）；服务端判死/回观察者时立即下发全量列表同步锁状态。

## 1.4.1（存活角色面板/自部署封锁）
- 服务端：存活（非观察者）角色 → CharacterListS2C 下发 panelLocked=true；K 面板拒绝打开（客户端提示）；自部署服务端硬校验拒绝（防自杀逃逸——碰到人不能自爆回城重置）。
- 入服自动开面板同样跳过锁定状态；死亡后经复活波/判死回观察者状态即可恢复。

## 1.4.0（序列编辑器 + 事件栏横向滚动）
- 新：序列系统（sequences.json + 管理器「序列」页签）——步骤编排：WAIT（等待秒数）/ WAVE（触发刷新波）/ COMMAND（执行控制台命令，支持 {{event}} {{phase}} {{seq}} 变量）/ FORCE_PICK（强制抽取观察者：≤N 名在线、随机附职业、随机 UID 名字、可选刷新生效）。
- 事件 hooks 新增 startSequence：事件开始自动运行序列（默认示例 qdf_support：等 10s → 抽 5 名 QDF → 触发 qdf_reinforce 波）。
- 命令：/rp sequence list|run <id>（管理员）；序列 CRUD 热重载即时生效。
- 事件横幅横向滚动：事件过多时鼠标悬停横幅滚轮横向滚动（背包界面同样生效），双侧箭头指示。

## 1.3.0（激活事件横幅 + 清空命令）
- 新：激活事件横幅（EventStateS2C）——顶部居中 4:3 横向红色警戒长方形排开（进行中标记），正常 HUD 与任意界面（背包等 ScreenEvent.Render.Post）上层都可见；入场电影期间隐藏。
- 事件开始/结束/清空/热重载自动广播横幅。
- 新命令：/rp event clear（管理员）——全部 RUNNING 事件立刻结束并重置为 SCHEDULED，横幅同时清空。

## 1.2.5（HUD 层级与外观微调）
- 入场电影（黑屏）期间右下角 HUD 自动隐藏（场景感知，不依赖覆盖层顺序）——黑屏完整遮住右下角。
- 去除 HUD 整体外框背景（仅保留每行独立槽位）。

## 1.2.4（管理器设置页布局修复）
- 修复：6 页签化后 renderSettings 仍使用旧偏移（3），前三个设置行渲染在第 4-6 个页签按钮区域（与页签重叠）——改为偏移 6；事件/阶段/刷新波页签恢复可见。

## 1.2.3（崩溃修复 + 设置行精简）
- 修复：管理器「刷新波」页签未选中任何波时打开表单，csv/posStr/num 读取 null 配置 NPE 崩客户端（已空安全）。
- 设置页签移除冗余的「HUD 总开关」行（HUD 默认显示，三行模式开关可单独控制），6 行紧凑布局。

## 1.2.2（右下角状态栏 HUD）
- 新 HUD（客户端覆盖层，右下角）：三行——职位（等级进度条/文字）/ 阵营（背景=阵营颜色徽章色环）/ 血量（百分比条，绿>50 黄>25 红；文字模式同源）。
- 每行=方形图标（阵营徽章/心形）+ 等宽长方形；长方形文字或进度条由管理器设置切换。
- 管理器「设置」页签新增 4 项：HUD 总开关 / 职位条模式 / 阵营条模式(文字|阵营色全条) / 血量条模式；实时生效并写入 settings.json。

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
