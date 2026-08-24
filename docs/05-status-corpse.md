# P4 角色状态系统 + Corpse 联动（docs/05）

## 1. 需求
角色状态生命周期统一管理；**玩家掉线直接判定为死亡**，死于原地，成为可搜刮遗体（联动 Corpse）；
状态变更驱动经验（P5）、事件（P6）、动画（P7）、刷新池（P8）。

## 2. 状态机
枚举 `CharacterStatus {ALIVE, DEAD, OBSERVING}`，默认新角色 `OBSERVING`。

| 迁移 | 触发 | 附带动作 |
| --- | --- | --- |
| OBSERVING → ALIVE | 自刷新部署 / 复活波 / 招募接受（P8） | 装备发放+传送+player_spawn 动画 |
| ALIVE → DEAD | 玩家死亡事件 / **掉线、踢出（含断连）直接判死** | 生成遗体、冷却开始、player_death 动画、任务中止 |
| ALIVE → OBSERVING | 玩家主动切换（下班/换角色） | 清空在场状态、状态广播 |
| DEAD → ALIVE | 复活波/招募部署（冷却结束才可被选） | 同 OBSERVING→ALIVE |

非法迁移（如 DEAD→OBSERVING）一律拒绝并日志 WARN。冷却不构成独立状态（由 `cooldownUntil` 派生），
但刷新池选人时作为过滤条件。

## 3. Corpse 联动（可选依赖）
- 探测：`ModList.get().isLoaded("corpse")`；存在 → `CorpseBridge`：
  - 掉线判死：在 `PlayerLoggedOutEvent` 记录离开位置/session 信息，延迟 1~2 tick 生成遗体（CorpseAPI 创建的遗体实体）
    → 可搜刮（Corpse 原生逻辑），同时置 DEAD + 冷却。
  - 正常死亡：忠实记录到 Corpse 遗体（若玩家装 Corpse）。`CorpseApi.getCorpse(level, uuid)` 用于查询/联动任务。
- 缺失 → 降级：原生死亡掉落（不掉线场景）+ 状态机照常记录；控制台 INFO 提示"未检测到 Corpse，使用原生死亡"。
- 1.20.1 API 以实际 jar 为准（`libs/corpse-forge-1.20.1-1.0.5.jar`，scripts/fetch-corpse.sh 获取）；桥接类单测用 Fake。

## 4. 命令
`/rp state <player>`（列出该玩家所有角色状态/冷却）；`/rp kill <player>`（OP≥2 或 ccnnrp.admin.kill：
手动判死，走与掉线一致的完整链路）。

## 5. 配置（serverconfig/ccnr_rp-server.toml）
`character.deathCooldownMinutes=30`；`status.offlineKillTicks=2`（离场到生成遗体的延迟）；`status.pollOfflineSeconds=...`（备用轮询，见 §6）。

## 6. 健壮性
- 掉线判死链路：优先 PlayerLoggedOutEvent；服务端崩溃/异常丢事件场景 → 每 5s 轮询在线玩家与 `alive` 状态角色，
  发现"角色 alive 但玩家离线 >60s" → 补判死（日志 WARN）。二者结合保证不漏判。
- 判死必须幂等（重复触发只处理一次）。

## 7. WBS 小任务
1. 状态机（纯类 `StatusMachine`：迁移表与校验）；2. `StatusManager`：在线/离线/死亡事件接线 + 幂等判死；
3. `CorpseBridge`：探测/生成/查询 + 降级；4. 冷却计算与 GUI/命令展示联动；5. 状态变更事件广播（经验/动画/刷新池订阅）；
6. 命令 /rp state、/rp kill；7. 单测 + 集成演练。

## 8. 验收标准
1. 状态机单测：全迁移路径 + 非法迁移拒绝 + 判死幂等（重复调用只执行一次）—— 全绿。
2. CorpseBridge：存在/缺失两分支均可编译、可运行；Fake 场景下遗体生成调用被记录。
3. 集成演练（dev 环境）：玩家 A 断连 → ≤5s 内服务端判死 → 有遗体（或降级日志）→ 冷却开始。
4. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
