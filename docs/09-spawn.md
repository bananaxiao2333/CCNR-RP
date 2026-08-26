# P8 人物刷新框架（docs/09）

## 1. 需求
特定触发时按配置刷新"支援潮"。两种刷新方式（可配置选择）：
- **自刷新**：玩家在人物管理界面自己部署（仅 selfDeploy=true 的职业可自刷）。
- **复活波（邀请制）**：指定队伍被创建时，从**阴间池**（DEAD / 观察中带复活冷却标记）+ **阳间池**（OBSERVING 无冷却）选出符合条件玩家，**全部发招募邀请**（屏幕右侧招募列表）——**自行选择加入或拒绝**，不再强制复活；**人满即提前部署**，超时按已加入人数部署，无人加入则公告失败。**活着（已有在场角色）的玩家不会收到邀请**；已加入名单实时广播（X 已选择加入：已 n/需要 m）。
- 非自部署类型必须切换到**观察状态**后才可能被复活波选中；自部署类型可随时从界面部署。

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
- mode: SELF_DEPLOY（仅自刷）/ RESURRECTION（仅复活波）/ BOTH（两通道都开）。
- teamIds: 复活波的触发队伍；deployAt: WORLD_SPAWN 或 POS {x,y,z,dim,yaw}；minLevel/factionIds 为过滤条件。
- 触发检测：**每 20t 轮询 ServerScoreboard 团队集合 diff**（团队创建即时感知，兼容 /team create 等任何来源），
  命中 teamIds 且波 enabled → 触发一次复活波（同一队伍生命周期内只触发一次，world/ccnr_rp/team_wave_done.json 记录）。

## 3. 选人算法（WaveSelector 纯类）
1. 候选池：阴间池 = status==DEAD（阴间等待复活，含冷却中）或 OBSERVING 带复活冷却标记 → 复活波强制抽取、无视冷却；职业/阵营/等级匹配。
   阳间池 = status==OBSERVING 且无冷却标记 && 职业非 selfDeploy（selfDeploy 类型的观察者不参加复活波，走自刷）。
2. 排序：阴间池按 等级↓ → 冷却早↑ → 随机；不足 count 时按 阳间池随机 补齐并进入招募流程。
3. 名额分配：先按波内 professionIds 配额（均分），不足则跨职业补足。
4. 招募（RecruitmentManager）：对每个缺口向阳间池玩家发 RecruitOfferS2C（offerId、残余倒计时、角色预览），
   客户端**屏幕右侧**渲染招募列表（头像/名字/职业/倒计时条/接受/拒绝）；
   recruitTimeoutSeconds 内接受→部署；拒绝或超时→移除并尝试顺位下一位；全员无响应→公告"波次招募失败"。

## 4. 部署链路（两通道共用）
校验（角色状态/冷却/波开关）→ LoadoutManager.apply（P2 装备，含 NBT）→ 状态 OBSERVING/DEAD → ALIVE。
时序（2.14.0 起「先播后落位」；2.14.5 起电影 HUD/音乐与 CMDCam 解耦）：
- 入场电影 HUD（黑屏/图标/文字）+ 出场音乐【始终播放】（未 SKIP_CINEMATIC / NO_MUSIC）；CMDCam 场景为可选叠加层。
- 已设定 CMDCam 场景（场景名非空且 CMDCam 已装）：部署触发 → 强制旁观者 + 电影 HUD 与 CMDCam 场景【同一时刻开始播放】
  → 全部动画播完（客户端检测 HUD 结束 + CMDCamClient.isPlaying 场景结束）→ 发 DeployLandC2S
  → 移动玩家到部署点（优先级：阵营出生点 → wave deployAt → 世界出生点）→ 设置生存；
- 未设定 CMDCam（无场景名 / 未装 CMDCam）：强制旁观者 + 电影 HUD/音乐播放 → HUD 播完客户端即发 DeployLandC2S
  落位（不等待场景）；
  落位兜底：HUD 播完后再等 20s（场景异常）自动落位；服务端 120s 超时；动画期间掉线清理待落位状态。
- SKIP_CINEMATIC：开局直接落位切生存（不播动画、不等待）。
→ 触发 player_spawn 动画（P7）→ 状态广播。

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
- 落点：首个启用且允许自部署并匹配该职业的刷新波，否则默认自部署波（世界出生点）。

### 4.2 处决转职（2.15.2 起）
- 在场（ALIVE）玩家在 K 面板点「部署」（按钮显示「处决转职」）→ 弹确认框 → 确认后服务端**处死旧角色**
  再部署为选定职位：统一退场（状态→观察+复活冷却+遗体+死亡结算，遗体 2 tick 后生成、复制旧背包与旧职位名）
  → 延迟 1s（等遗体生成完）走统一 deploy()（清背包 → 新职位装备 → 传送 → 入场电影 → ALIVE，冷却清零）。
- 校验：素材同步 → 在场状态（ALIVE only）→ 职位存在 → 等级达标；观察者仍走普通部署（不处死）。

## 5. 命令（OP≥2 或 ccnnrp.admin.spawn）
/rp spawn list、/rp spawn trigger <id>（强制触发一次，复活波通道，幂等忽略 RUNNING）、
/rp spawn enable <id> <on|off>（运行中切换生效）。玩家部署入口：GUI 按钮 / /rp character deploy <id>。

## 6. WBS 小任务
1. SpawnWaveDefinition + 配置读写 + 校验（未知 profession/team 报错带行号）；2. WaveSelector 纯类（双池+排序+配额+回退）；
3. SpawnFramework：自刷新执行器 + 复活波轮询 diff + 触发记录（幂等）；4. RecruitmentManager：招募 offer/接受/拒绝/超时；
5. 客户端 RecruitOverlayHud（右侧列表 + 倒计时 + 按钮 + 皮肤头像）；6. 命令族 + GUI 部署按钮接线；
7. 单测（选人/配额/排序/超时/接受/回退）+ 集成演练。

## 7. 验收标准
1. WaveSelector 单测：阴间优先、等级排序、冷却过滤、配额不足跨职业、count 不足回退阳间池、
   recruitTimeout 到期移除、接受即部署（纯逻辑全绿）。
2. 集成演练（dev）：建 qdf_team 队伍 → 自动触发 → 阴间足额则全复活；不足 → 右侧出现招募列表 →
   玩家接受 → 传送+装备+spawn 动画；拒绝/超时 → 列表消失 + 顺位；无人 → 公告。
3. 自刷新：selfDeploy=true 角色 GUI 部署成功；selfDeploy=false 部署按钮禁用/拒绝并回显。
4. 同一队伍重复创建不重复触发（幂等）；/rp spawn enable off 后触发失效但自刷不受影响。
5. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
