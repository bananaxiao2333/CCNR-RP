# P4 角色状态系统 + Corpse 联动（docs/05）

## 1. 需求
角色状态生命周期统一管理；**玩家掉线直接判定为死亡**，死于原地，成为可搜刮遗体（联动 Corpse）；
状态变更驱动经验（P5）、事件（P6）、动画（P7）、刷新池（P8）。

## 2. 状态机
枚举 `CharacterStatus {ALIVE, DEAD, OBSERVING}`，默认新角色 `OBSERVING`。

| 迁移 | 触发 | 附带动作 |
| --- | --- | --- |
| OBSERVING → ALIVE | 自刷新部署（K 面板）/ 召唤波接受 / FORCE_PICK 征召正式转职（P8，观察中需冷却结束才可自部署） | 装备发放+传送+player_spawn 动画 |
| ALIVE → DEAD | 玩家死亡事件 / **掉线、踢出（含断连）直接判死**（事件主路径） | 生成遗体、复活冷却开始、player_death 动画、任务中止 |
| ALIVE → OBSERVING | 玩家死亡/判死（事件主路径）/ 主动切换（下班/换角色） | 状态写观察模式（OBSERVING）+ 复活冷却标记；在线死亡强制旁观者模式，复活后传送回死亡地点（尸体旁旁观，不改变出生点）；右下角 HUD 套用「观察者/观察模式/不适用」 |
| DEAD → ALIVE | 召唤波/征召接受：死亡玩家以**临时征召兵**身份部署（TEMP，不改用户状态；阵亡结算后回到 DEAD/观察） | 临时编制装备+传送（不进角色库）；正式转职（ALIVE 玩家接受）才写用户角色为征召职业 |

非法迁移（如 DEAD→OBSERVING）一律拒绝并日志 WARN。冷却不构成独立状态（由 `cooldownUntil` 派生），
但刷新池选人时作为过滤条件。

**死亡 → 观察模式（P4 运行时主路径，不走 StatusMachine 的 DEAD 分支）**：死亡事件（handle）直接把 ALIVE 写为
OBSERVING + 复活冷却标记（不可自部署，等冷却结束或复活波/FORCE_PICK 强制复活）；轮询兜底（每 5 秒）把旧存档残留的
DEAD 归一化为观察者（保留冷却标记）。两路双保险保证死亡后角色一定是观察模式。结算（死亡/断联/退役）完成后
再次强制刷成观察者身份；观察者身份自动刷成旁观者模式（每 2 秒轮询兜底：未部署玩家强制旁观，防漂移回生存）。

### 2.1 进入观察者的统一清理（2.26.5）

**契约：任何一条"变成观察者"的路径都必须清背包 + 卸下阵营属性**，且**只有死亡路径把物品爆到地上**。

| 路径 | 背包处置 | 阵营属性 |
| --- | --- | --- |
| 自然死亡（`reason=death`） | **爆到地上**（原版掉落 / 遗体模组收纳 / `DeathInventoryPolicy` 补位） | 卸下 |
| 掉线判死（`RetireFlag.OFFLINE`） | **爆到地上**（同上；离线无实体时由策略判定） | 卸下 |
| 上面两条死亡路径**且世界规则 `keepInventory=true`** | **直接删除**（不爆出、不生成遗体，见 §2.2） | 卸下 |
| `/rp kill`、`/rp retire`（退役/下班） | **直接删除**，不在脚下掉一地 | 卸下 |
| 疏散结算（`EVACUATE` / `/rp evac`） | **直接删除** | 卸下 |
| 旁观者兜底轮询（每 2s，漂移纠正时） | **直接删除** | 卸下 |
| DEAD → OBSERVING 归一化轮询（每 5s） | **直接删除** | 卸下 |
| 登录归一化（DEAD→OBSERVING / 冷却期强制旁观） | **直接删除** | 卸下 |

- **唯一入口**：`StatusManager.purgeOnObserving(player, dropInventory)`；背包删除统一走
  `DeathDrops.clearAll`（部署前清理与退场清理共用同一实现，不再各写一份）。
- **为什么收成一个入口**：切观察者一共有七条路径，历史上只有 `retire` 卸了阵营属性、**没有任何一条清背包**，
  于是观察者身上会残留上一局的加成（血量/护甲）与装备（docs/01 §9.4 对称清理）。
- **判定纯逻辑化**：是不是"死亡路径"由 `DeathInventoryPolicy.disposalOnObserving(naturalDeath, offlineDeath)`
  决定（无 MC import，可脱机单测）——`DROP` 只给自然死亡与掉线判死，其余一律 `DELETE`。
  这条规则刻意把 `/rp kill` 划在"非死亡"一侧：它的 reason 是 `command`，语义是"退场"，
  与原本就存在的"/rp kill 不掉落"一致（docs/05 §3）。
- **只清自己的**：属性按 `ccnr_rp:attr:` 前缀清该玩家自己的修饰，**不触碰其他玩家**（docs/16 §2.2）。
- **幂等 + 不误伤部署**：背包清空后再清无副作用；旁观者轮询只在**模式真的被改**的那一刻清理，
  而部署流程在同一个 tick 内同步跑完（`applyDeployCore` → `setStatus(ALIVE)` 之间没有 tick 边界），
  所以轮询不会插进部署中间态把刚发放的装备清掉。

### 2.2 世界规则 `keepInventory` 优先（2.26.10）

**规则：世界规则 `keepInventory=true`（"死亡不掉落"）时，本 mod 对死亡路径既不爆出背包、也不生成遗体，
而是把背包直接删除。**

- **修的是什么**：2.24.0 把 `keepInventory=true` 记成了"原版不会掉 → 所以本 mod 必须替它爆出来"。
  补位逻辑本身没错（它要修的是"死亡后背包不被清理"），但把 `keepInventory` 也算进补位条件，
  等于**用一个补丁推翻了玩家/管理员显式设置的世界规则**——规则写着"死亡不掉落"，结果死一次满地装备。
  现在规则优先，补位只保留另外两种原版确实不会掉的情况（旁观者死亡、离线判死）。
- **为什么是"删除"而不是"留在身上"**：不爆出 ≠ 留着。本 mod 的观察者契约要求"切观察者必须空背包"，
  且 `keepInventory` 在本 mod 的流程里不可能意味着"留着下一局接着用"——重新部署时
  `SpawnFramework.clearInventory` 一定会清空背包并按岗位发装备。若只"不爆出"而把物品留在观察者身上，
  结果是玩家死后仍抱着上一局的枪、到下次部署时才无声消失，比直接删除更难预期。
- **判据同源**：三个决定（背包处置 / 是否显式爆出 / 是否抑制遗体）由 `DeathInventoryPolicy.forDeath(...)`
  一次给出（纯逻辑、可脱机单测），调用方不各写一套判据。
- **遗体也一并抑制**（用户定调"一点装备都不留"）：装了 Corpse 时它**无条件**为每次玩家死亡生成遗体——
  它的 `PlayerDeathEvent` 没有 `@Cancelable`（只有单向的 `storeDeath()`/`removeDrops()`，读的 getter 还是包私有），
  `ServerConfig` 里也没有"是否生成遗体"的开关。因此唯一可拦点是遗体**加入世界**的那一刻：
  死亡时打一个带 TTL（10s、命中即消费）的抑制标记，`EntityJoinLevelEvent` 里 `setCanceled(true)`。
  - **顺序为什么成立**：本 mod 的 `LivingDeathEvent` 处理器是默认优先级 `NORMAL`，Corpse 的两个处理器是
    `LOWEST`（最后跑）；我们在它读取背包与 `event.getDrops()` 之前就清空了背包，所以即使漏掉抑制，
    造出来的遗体也一定是**空的**（双保险）。
  - **抑制只作用于真正的死亡**（自然死亡 / 掉线判死）：`/rp kill`、`/rp retire` 是管理端强制退场，
    不是"死亡"，其 `SPAWN_CORPSE` 行为不因世界规则而改变。
- **边界 / 如何失效**：① 判定取的是 `player.level().getGameRules()`，若以后改成按世界/维度取规则，
  要确认读到的仍是该玩家所在世界的规则；② 抑制标记的 TTL 调得过长会影响该玩家**下一次**正常死亡的遗体，
  调得过短则压不住"死亡→遗体入世界"的间隔——标记命中即消费，且任何异常都返回 false
  （宁可留下遗体，也不误删正常遗体）；③ 世界规则是**读时判定**的，管理员中途改规则立即生效，
  不需要重载配置（也没有缓存，故不存在"改了不生效"）。

## 3. Corpse 联动（可选依赖）
- 探测：`ModList.get().isLoaded("corpse")`；存在 → `CorpseBridge`：
  - 掉线判死：在 `PlayerLoggedOutEvent` 记录离开位置/session 信息，延迟 1~2 tick 生成遗体（CorpseAPI 创建的遗体实体）
    → 可搜刮（Corpse 原生逻辑），同时置 DEAD + 冷却。
  - 正常死亡：忠实记录到 Corpse 遗体（若玩家装 Corpse）。`CorpseApi.getCorpse(level, uuid)` 用于查询/联动任务。
- 缺失 → 降级：原生死亡掉落（不掉线场景）+ 状态机照常记录；控制台 INFO 提示"未检测到 Corpse，使用原生死亡"。
  - **2.24.0 补正（issue #1）**：仅"依赖原版掉落"是不够的——原版在两条路径上**不会**爆出背包：
    死亡瞬间处于旁观者模式（`ServerPlayer.die` 里 `if (!isSpectator()) dropAllDeathLoot` 跳过；
    而本 mod 会把未部署玩家强制切成旁观者）、离线判死（没有原版掉落流程）。过去"死亡即清空背包"只是
    Corpse 模组 `removeDrops()` 的副作用，所以遗体 mod 被移除后就出现"死亡不清理背包"。
    现在由 `DeathInventoryPolicy`（纯逻辑）+ `DeathDrops`（薄适配）**显式爆出并清空背包**（0-35 + 护甲 4 + 副手 1，
    消失诅咒按原版销毁），且在死亡事件内先于原版掉落执行（先清空 → 原版找不到物品，不重复掉落）；
    装了遗体模组仍由它收纳，本 mod 不插手。管理员 `/rp kill`、`/rp retire` 属于**非死亡退场**：
   **不爆落地**，但自 2.26.5 起会**直接删除**背包（此前是"物品留在玩家身上"，随后也会在重新部署时被
   `clearInventory` 清掉，最终结果相同）——见 §2.1 的统一清理契约。
  - **2.26.10 更正**：上面这条补位逻辑最初把 `keepInventory=true` 也算作"原版不会掉"的一种，
    于是本 mod 会替它把整包装备爆到地上——**等于推翻了世界规则**。现已改为规则优先：`keepInventory=true`
    时既不爆出、也不生成遗体（连 Corpse 自己造的那具也会被拦下），背包直接删除。详见 §2.2。
  - **边界（为什么做不到的部分要写清）**：爆出需要玩家实体，因此"掉线判死主路径"（`PlayerLoggedOutEvent`，
    事件时实体与背包仍可用）能爆出；而"轮询兜底判死"（服务端崩溃丢事件后补判，玩家早已离线、无实体）
    无法爆出——该路径只写状态与冷却，物品仍留在玩家存档里。这条差异是刻意的：宁可漏爆一次，
    也不能在没有实体的位置凭空造掉落物。
- **死亡记账范围（2.24.0）**：死亡退场不再只对"在场（ALIVE）"生效——有档案但当时不在场者
  （观察者 / 残留 DEAD / 征召兵）的死亡同样写冷却并记录死亡地点；死亡地点在**任何在线死亡**时记录，
  重生时由 `onPlayerRespawn` 传回死亡地点并切旁观；若地点缺失（死亡瞬间掉线、服务端重启清表、
  死亡前就不是在场身份），重生时也不退回床边裸复活，而是强制进入观察流程并立即同步档案（K 面板部署入口可用）。
- 遗体身份（非入侵，不改 corpse 模组本体/jar）：PlayerDeathEvent 保持玩家真实 UUID（客户端按 tab 列表解析玩家皮肤，LittleSkin 纹理）
  并改写 playerName 为「职位 + 玩家名」；遗体加入世界时（EntityJoinLevelEvent）把 corpseName 移到 customName + 置可见——
  1.20.1 名字牌仅 customNameVisible 渲染，头顶名字与搜尸 GUI 标题一致显示「职位 + 玩家名」（无 "Corpse of " 前缀）。
  限制：玩家离线后旧遗体因 tab 列表无其档案而回退默认皮肤（死亡当场观察正常）。
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
