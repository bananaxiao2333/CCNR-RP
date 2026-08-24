# P8 人物刷新框架（docs/09）

## 1. 需求
特定触发时按配置刷新"支援潮"。两种刷新方式（可配置选择）：
- **自刷新**：玩家在人物管理界面自己部署（仅 selfDeploy=true 的职业可自刷）。
- **复活波**：指定队伍被创建时，自动从**阴间**（DEAD 池）选择符合条件玩家部署；人数不足时向合格**阳间**
  （OBSERVING 池）玩家发招募申请——**屏幕右侧招募列表**，限时接受。
- 非自部署类型必须切换到**观察状态**后才可能被复活波选中；自部署类型可随时从界面部署。

## 2. 数据模型（config/ccnr_rp/spawn_waves.json）
~~~json
{
  "version": 1,
  "waves": [
    {"id": "qdf_reinforce", "mode": "RESURRECTION", "enabled": true,
     "teamIds": ["qdf_team"], "professionIds": ["qdf_guard", "qdf_special"],
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
1. 候选池：阴间池 = status==DEAD && cooldownUntil<=now && 职业/阵营/等级匹配；
   阳间池 = status==OBSERVING && 职业非 selfDeploy（selfDeploy 类型的观察者不参加复活波，走自刷）。
2. 排序：阴间池按 等级↓ → 冷却早↑ → 随机；不足 count 时按 阳间池随机 补齐并进入招募流程。
3. 名额分配：先按波内 professionIds 配额（均分），不足则跨职业补足。
4. 招募（RecruitmentManager）：对每个缺口向阳间池玩家发 RecruitOfferS2C（offerId、残余倒计时、角色预览），
   客户端**屏幕右侧**渲染招募列表（头像/名字/职业/倒计时条/接受/拒绝）；
   recruitTimeoutSeconds 内接受→部署；拒绝或超时→移除并尝试顺位下一位；全员无响应→公告"波次招募失败"。

## 4. 部署链路（两通道共用）
校验（角色状态/冷却/波开关）→ LoadoutManager.apply（P2 装备，含 NBT）→ 传送 deployAt →
状态 OBSERVING/DEAD → ALIVE → 触发 player_spawn 动画（P7）→ 状态广播。

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
