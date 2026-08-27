# P8 人物刷新框架（docs/09）

## 1. 需求
特定触发时按配置刷新"支援潮"。两种刷新方式（可配置选择）：
- **自刷新**：玩家在人物管理界面自己部署（仅 selfDeploy=true 的职业可自刷）。
- **召唤波（邀请制）**：指定队伍被创建时，按波模式（存活可收到/死亡可收到/皆可收到）从**在线玩家**中选出符合条件者（存活 ALIVE / 观察 OBSERVING，DEAD 不收邀请），**全部发招募邀请**（屏幕右侧招募列表）——**自行选择加入或拒绝**，不再强制复活；**人满即提前部署**，超时按已加入人数部署，无人加入则公告失败；已加入名单实时广播（X 已选择加入：已 n/需要 m）。
- 能否收到邀请由**波模式**决定（存活可收到/死亡可收到/皆可收到），取代原全局「向存活邀约」开关
  （该开关已移除）；存活玩家接受征召后按 §4.3 正式转职部署（不处死）。
- 自部署：玩家在人物管理界面自己部署（K 面板职位选择器，无 selfDeploy 字段区分）。

## 2. 数据模型（config/ccnr_rp/spawn_waves.json）
~~~json
{
  "version": 1,
  "waves": [
    {"id": "qdf_reinforce", "mode": "RESURRECTION", "enabled": true,
     "teamIds": ["qdf_team"], "professionIds": ["s4_guard", "s3_special"],
     "factionIds": [], "count": 4, "minLevel": 1,
     "deployAt": {"type": "WORLD_SPAWN"},
     "recruitTimeoutSeconds": 60,
     "autoEnableByTeam": true}
  ]
}
~~~
- mode: 决定**谁能收到**本波邀请（波模式完全接管，取代全局「向存活邀约」开关）：
  SELF_DEPLOY=存活人员可收到 / RESURRECTION=死亡人员可收到 / BOTH=皆可收到。
  持久化值沿用旧枚举兼容存量配置；三种模式均可作为召唤波触发（含队伍创建轮询、命令、序列 WAVE 步骤）。
  **兼容声明（2.15.x）**：旧版管理面板持久化的 "RECRUIT" 值在加载时自动映射为 RESURRECTION（WARN 提示，波不丢弃）；
  旧版 mode 语义（SELF_DEPLOY=仅自刷）已重定义为「谁能收到邀请」，存量 SELF_DEPLOY 波现在也会被召唤触发——属本版刻意的语义重定义。
- teamIds: 召唤波的触发队伍；deployAt: WORLD_SPAWN 或 POS {x,y,z,dim,yaw}；minLevel/factionIds 为过滤条件。
- 触发检测：**每 20t 轮询 ServerScoreboard 团队集合 diff**（团队创建即时感知，兼容 /team create 等任何来源），
  命中 teamIds 且波 enabled → 触发一次复活波（同一队伍生命周期内只触发一次，world/ccnr_rp/team_wave_done.json 记录）。

## 3. 选人算法（SpawnFramework 内联，纯逻辑配额在 SpawnModels.WaveQuota）
1. 候选池 = 在线玩家（排除已有征召登记的玩家，防重复邀请）：存活（ALIVE）→ 波模式允许存活可收到才入池；
   观察（OBSERVING）→ 波模式允许死亡可收到才入池；DEAD 玩家不进入候选池。职业/阵营过滤见 professionIds/factionIds。
2. 指定类型波（professionIds/factionIds 非空）= 按类型征召：候选随机抽取 count 人，每人分配波次编制职业
   （professionsFor 过滤后的职业池随机），登记临时征召（UID 名，pending 挂起），全部发指定编制邀请（RecruitOfferS2C）。
3. 通用波（无编制）= 双通道：观察者发 pick 邀请（v2 唯一身份：接受即按自己当前职业部署，不再弹选岗菜单）；
   存活玩家按职业池随机分配职业、走指定编制式邀请。
4. 名额分配（SpawnModels.WaveQuota.split）：存活征召取 ceil(count/2)，观察者选岗取剩余；count<=0 或两通道均无候选
   → 整波跳过（与指定类型波一致）；任一通道无候选则该通道不发邀请（名额不转移，防超招）。
5. 招募（RecruitManager）：对每个邀请发 RecruitOfferS2C（offerId、残余倒计时、职业/阵营预览），
   客户端**屏幕右侧**渲染招募列表（立绘/职业/倒计时条/接受/拒绝）；accept 登记已加入并广播（已 n/需要 m）；
   人满即提前结算；超时按已加入部署；拒绝/超时清理征召登记；全员无响应→公告"波次招募失败"。
6. 结算（onConscriptFinish / onWaveFinish）：以**结算时刻当前状态**判别（SpawnModels.ConscriptDeployMode）——
   观察/死亡 → 临时征召部署（TEMP，不进角色库）；存活 → 正式转职部署（ALIVE，不处死）；部署成功后才变更征召登记。

## 4. 部署链路（统一 deploy()）
校验（素材同步/状态/职位）→ LoadoutManager.apply（P2 装备，含 NBT）→ 正式用户：状态 → ALIVE、冷却清零；
临时征召（TEMP）：不改用户状态，仅推送征召身份给客户端。
时序（2.14.0 起「先播后落位」；2.14.5 起电影 HUD/音乐与 CMDCam 解耦）：
- 入场电影 HUD（黑屏/图标/文字）+ 出场音乐【始终播放】（未 SKIP_CINEMATIC / NO_MUSIC）；CMDCam 场景为可选叠加层。
- 已设定 CMDCam 场景（场景名非空且 CMDCam 已装）：部署触发 → 强制旁观者 + 电影 HUD 与 CMDCam 场景【同一时刻开始播放】
  → 全部动画播完（客户端检测 HUD 结束 + CMDCamClient.isPlaying 场景结束）→ 发 DeployLandC2S
  → 移动玩家到部署点（优先级：**职业部署点 → 阵营部署点 → wave deployAt → 世界复活点**）→ 设置生存；
- 未设定 CMDCam（无场景名 / 未装 CMDCam）：**开局直接传送部署点 + 切生存，电影 HUD/音乐与落位同一时刻开始**
  （不再等动画播完；客户端播完后的 DeployLandC2S 因无待落位记录而为空操作）；
  落位兜底（仅 CMDCam 延迟落位路径）：HUD 播完后再等 20s（场景异常）自动落位；服务端 120s 超时；动画期间掉线清理待落位状态。
- SKIP_CINEMATIC：开局直接落位切生存（不播动画、不等待）。
→ 触发 player_spawn 动画（P7）→ 状态广播。

### 4.0 部署人数限制（2.17.0 起，全局性）
- 管理员在管理面板「限制」页配置规则（config/ccnr_rp/limits.json，复用 ConfigCrud 管道）：
  - GLOBAL：通用角色上限（某职业未配置专属规则时兜底）
  - FACTION：阵营上限（该阵营在职总人数）
  - PROFESSION：职业上限（该职业在职人数）
- 部署前在统一 deploy() 检测（服务端权威，客户端仅预览）：职业维度在职数 ≥ 上限，或阵营维度在职数 ≥ 上限 → 拒绝部署。
- **上限 0 = 禁止部署**（该职业/阵营不可部署；"不限" = 不配置该规则，规则删除后即不限）。
- 在职数 = 用户库中 status==ALIVE 且职业/阵营匹配的用户数（TEMP 征召不进用户库不占编制）；
  重新部署（在场换岗）时目标职业/阵营在职数按「不含本人」计算（自己已占旧岗位位，不重复占用目标位）。
- 系统强制操作跳过限制：管理员刷人（onAdminSelfProfession）、强制征召部署（FORCE_DEPLOY+TEMP）均带 LIMIT_SKIP flag。
- K 面板（选岗）显示在职/上限（中栏职位行 + 右栏详情），空位不足时部署按钮禁用并红字提示；
  服务端 deploy() 仍会强校验（客户端提示仅为预览）。
- K 面板标红：中栏职位行无可用部署余额（在职 ≥ 上限或上限=0 禁止）时整行标红；
  左侧阵营行在其全部职业均无可用余额（含无职业）时标红，提示该阵营当前无法复活/部署。

### 4.1 首次入服自动部署（2.15.0 起）
- 玩家**首次进入设施**（本世界无用户档案）时自动部署为配置职业（默认 m5_intern 访客/实习生），
  走统一 deploy()：装备 → 传送落点 → 入场电影 HUD + 出场音乐 → 状态 ALIVE → 广播。
- 配置（config/ccnr_rp/settings.json，管理面板「设置」页全部可编辑）：
  - firstJoinAutoDeploy（bool，默认 true）：总开关（开关行）；
  - firstJoinProfession（string，默认 m5_intern）：自动部署职业 id，空串=关闭（文本输入行）。
- 设置页泛化（2.15.0 起）：settings.json 项按类型渲染——bool=开关行、string=文本输入行，
  serverconfig 数值=数字输入行，统一滚动 + 一个保存按钮；字符串项经 ManagerSetC2S 落盘 settings.json。
- 时序：登录时入队（须在用户档案惰性创建前判定首次）→ 等素材同步完成（60s 超时兜底）且入服稳定
  （≥2s）后部署；已被其他入口部署（管理刷人/复活波/手动）时自动跳过；掉线清理队列。
- 落点：首个启用且允许自部署并匹配该职业的刷新波，否则默认自部署波（世界出生点）；
  落点本身仍走统一优先级：职业部署点（该职业配置了复活点则用）→ 阵营部署点 → wave deployAt → 世界复活点。

### 4.2 重新部署（2.15.x 起，取代处决转职）
- 在场（ALIVE）玩家在 K 面板点「部署」（按钮显示「重新部署」）→ 服务端**直接重新部署**为选定职位：
  **不处死、不留遗体、不结算死亡经验**（旧 2.15.2 处决转职流程已移除）；走统一 deploy()（FORCE_DEPLOY 绕过单在场守卫）：
  清背包 + 重设角色状态（生命回满/饱食度/清效果/灭火/清坠落/补空气）→ 发放新职位装备 → 传送到部署点
  （职业部署点 → 阵营出生点 → 匹配波 deployAt → 世界复活点）→ 入场电影 → ALIVE + 冷却清零。
- 二次确认弹窗暂时隐藏（无处决后果，直接执行；如需恢复可重新启用客户端确认框流程）。
- 校验：素材同步 → 在场状态（ALIVE only）→ 职位存在 → 等级达标；观察者仍走普通部署。

### 4.3 征召/波接受者部署（存活可收到起）
- **判断条件 = 当前角色状态**（SpawnModels.ConscriptDeployMode，结算时刻实时判定）：
  观察（OBSERVING）/ 死亡（DEAD）→ 临时征召兵部署（TEMP，不进角色库，阵亡/结束回到原身份）；
  存活（ALIVE）→ **正式转职部署**：不处死，直接改用户角色为征召职业 + 状态 ALIVE + 冷却清零（走统一 deploy() 非 TEMP 分支）。
  状态在邀请与结算之间漂移（自行部署/死亡等）一律以结算时刻为准；部署失败即清理征召登记（防状态卡死）。
- 存活玩家可被 FORCE_PICK 与「存活可收到/皆可收到」的波邀请（FORCE_PICK 候选池不排除 ALIVE；
  通用波中存活玩家走指定编制式邀请；观察者 pick 邀请在 v2（唯一身份）下接受即按自己当前职业部署，不再弹选岗菜单）。
- 部署完成后服务端向部署者发送 DeployNoticeS2C → 客户端顶部居中常驻横幅「已部署：职位」（30s，下线清理）；
  邀请部署 / 波次完毕 / 人满提前部署 / 正式转职统一走此提示；非 TEMP 部署同时保留 actionbar 消息
  「职位 X 已部署」（老行为，消息 + 横幅并存）。

## 5. 命令（OP≥2 或 ccnnrp.admin.spawn）
/rp spawn list、/rp spawn trigger <id>（强制触发一次，召唤波通道，幂等忽略 RUNNING）、
/rp spawn enable <id> <on|off>（运行中切换生效）。玩家部署入口：GUI 按钮 / /rp character deploy <id>。

## 6. WBS 小任务
1. SpawnWaveDefinition + 配置读写 + 校验（未知 profession/team 报错带行号）；2. WaveSelector 纯类（双池+排序+配额+回退）；
3. SpawnFramework：自刷新执行器 + 召唤波轮询 diff + 触发记录（幂等）；4. RecruitManager：招募 offer/接受/拒绝/超时；
5. 客户端 RecruitOverlayHud（右侧列表 + 倒计时 + 按钮 + 皮肤头像）；6. 命令族 + GUI 部署按钮接线；
7. 单测（选人/配额/排序/超时/接受/回退）+ 集成演练。

## 7. 验收标准
1. 纯逻辑单测（全绿）：WaveQuota.split（count=0 跳过 / 均分 / 奇数存活多一名额 / 单通道空缺不转移 / 防超招）、
   ConscriptDeployMode.of（ALIVE→正式、OBSERVING/DEAD→临时、null→跳过）、Mode 收邀请语义、parseWaves RECRUIT 兼容映射。
2. 集成演练（dev）：建 qdf_team 队伍 → 自动触发 BOTH 波 → 存活与观察玩家各收邀请；存活接受 → 正式转职
   （角色/状态/冷却 + 横幅）；观察接受 → TEMP 部署 + 横幅；拒绝/超时 → 列表消失 + 征召清理；无人 → 公告失败。
3. count=1 的通用波最多招 1 人（存活通道优先，观察通道不发邀请）；同一玩家不被多个波重复邀请。
4. 同一队伍重复创建不重复触发（幂等）；/rp spawn enable off 后触发失效但自刷不受影响；存量 "RECRUIT" 配置加载不丢波。
5. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
