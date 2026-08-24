# P5 经验系统（docs/06）

## 1. 需求
角色经验由三来源构成：**值班时间**、**任务行为**、**疏散方式**；均在**命令结算**（/rp settle）时结算，
事件结束/游戏结束也自动结算；等级由经验计算。

## 2. 模型（纯逻辑）
- `LevelCurve {base, pow}`：`xpForLevel(n) = base * n^pow`；`level(xp) = floor((xp/base)^(1/pow))`（默认 base=100, pow=2）。
- `SettlementRecord {playerUuid, charId, dutySeconds, taskXps: {taskId→xp}, evacMethod, evacXp,
  totalXp, levelBefore, levelAfter, settledAt}`。
- 权重（serverconfig）：`xp.dutyXpPerSecond=0.1`、`xp.taskDefaultXp=50`、`xp.evacSafe=200`、
  `xp.evacDied=50`、`xp.evacObserving=0`、`xp.evacStayBehind=150`（疏散方式：
  `SAFE_RESCUE / DIED / OBSERVING_END / STAY_BEHIND`）。
- `EvacuationMethod` 判定（游戏结束自动）：结束时 alive→SAFE_RESCUE；期间死亡→DIED；全程 observing→OBSERVING_END；
  存活但在撤离点前滞留→STAY_BEHIND（管理可 `/rp evac set` 覆盖）。

## 3. 结算触发器与幂等
- `/rp settle [player|all]`（OP≥2 或 ccnnrp.admin.settle）：结算自上次结算以来的**增量**（duty 增量、新增任务、本次疏散方式）。
- 事件结束（P6）自动结算该事件任务；游戏结束（game_end 事件）自动结算全员（疏散方式自动判定）。
- **幂等**：结算记录记账本 `world/ccnr_rp/xp_ledger.json`（记录 `lastSettleAt` 与每任务最后结算戳）；
  重复 `/rp settle` 只计增量，结果可复算（`settleFromLedger + delta == 新 ledger`）。

## 4. 广播与钩子
升级（levelBefore < levelAfter）→ 全服广播 + 触发 `level_up` 动画（参数=新等级）；`XpUpdateS2C {charId,xp,level}` 推送持有角色者。

## 5. 命令
`/rp settle [player|all]`、`/rp xp <player>`、`/rp level <player>`、`/rp evac set <player> <method>`。

## 6. WBS 小任务
1. `LevelCurve`（纯类）；2. 结算器 `SettlementCalculator`（纯类：输入增量+权重 → 记录）；3. `ExperienceService`：
   值班 tick 累加、markTask、疏散裁定、/rp settle、自动结算挂 P6 钩子；4. 账本读写（原子写）；5. XpUpdate 广播 + level_up 钩子；
6. 命令族；7. 单测。

## 7. 验收标准
1. 单测：曲线边界（level 0/阈值/WARN 溢出）、各来源加权、混合结算、重复 settle 幂等（增量一致）、
   本回合"分两次 settle 之和 == 一次 settle"，账本可复算 —— 全绿。
2. dev 演练：值班 10 分钟 + 完成 1 任务 + 存活疏散 → settle 得到预期 XP 与等级，账本追加 1 条。
3. 事件结束钩子：`event end` 后自动产出结算记录；游戏结束全员自动结算。
4. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
