# P6 事件系统（docs/07）

## 1. 需求
在游戏**不同阶段时间点**或**满足特定条件**时触发事件；事件带任务（供 P5 结算）、可挂起刷新波（P8）与动画（P7）。

## 2. 领域模型
- `GamePhase {id, order, durationMinutes}`（`config/ccnr_rp/phases.json`；阶段推进 = 到时长自动 / `/rp phase set` 手动）。
- `EventDefinition {id, enabled, triggers[], tasks[], hooks[]}`；
  `EventState {SCHEDULED, RUNNING, SETTLED}`；`EventInstance {id, state, triggersFired, startedAt, endedAt}`。
- 触发器（`TriggerEvaluator` 纯类）：
  - `ON_PHASE_START {phase}` / `ON_PHASE_END {phase}`
  - `ON_TIME {day, tickOfDay}`
  - `PERIODIC {seconds}`
  - `CONDITION {cond: DEAD_COUNT|ALIVE_COUNT|SCOREBOARD, objective?, op, value}`（子类型字段为 cond，避免与触发器外层 type 冲突）
- 钩子（公共契约，签名冻结）：`hooks.startAnimation`（P7）、`hooks.spawnWave`（P8，预留接口）、
  `hooks.notify`（标题/动作条提示）。

## 3. 配置（config/ccnr_rp/events.json）
```json
{
  "version": 1,
  "events": [
    {"id": "evac_alert", "enabled": true,
     "triggers": [{"type": "ON_PHASE_START", "phase": "danger"}],
     "tasks": [{"id": "evacuate_zone", "xp": 50}],
     "hooks": {"startAnimation": "event_start_alarm", "spawnWave": "wave_qdf_reinforce", "notify": {"titleKey": "ccnr_rp.event.evac.title"}}}
  ]
}
```
- 可选 sequence 数组：事件触发时的行为序列（步骤 WAIT/WAVE/COMMAND/FORCE_PICK/RULECHANGE/SWITCHPHASE/EVACUATE，
  字段见 docs/09-spawn.md / SequenceEngine；阶段 phase 与刷新波 wave 同样支持）。
- **hooks 段的解析优先级**（v2.26.5 起代码与本节示例对齐）：
  `startAnimation` / `spawnWave` 可写在顶层或 `hooks` 段（**顶层优先**）；
  通报键别名有两种写法——顶层 `notifyTitleKey`，或 `hooks.notify.titleKey`（**顶层优先**）。
  此前 `hooks.notify.titleKey` 不被读取（docs 有、实现无），现已补上；两种写法都生效。
- 管理面板可编辑（2.15.3 起）：事件/阶段/刷新波表单的「编辑行为序列…」按钮打开流程编辑器弹窗
  （仿出生点编辑器：步骤列表点选/上移/下移/删除 + 类型切换 + 按类型参数输入框），保存走主表单 CRUD。

## 4. 生命周期与节流
- tick 评估器每 20t 跑一次（serverconfig `event.evalIntervalTicks=20`，可配）。
- START：状态 RUNNING + 通知玩家 + 触发 hooks.startAnimation + 尝试拉起 hooks.spawnWave（P8 未实现时记 INFO 跳过）。
- END：状态 SETTLED + 触发 hooks。**事件结束不再自动结算经验**（v2.18.4 起：
  结算仅死亡退场与 `/rp settle` 触发，见 docs/06 §6）。
- 事件可由 `/rp event trigger <id>` 手动强制（幂等：RUNNING 中不重复触发，WARN 提示）。

## 5. 命令
`/rp event list|info <id>`（玩家可看）、`/rp event trigger <id>`、`/rp event enable <id> <on|off>`、
`/rp phase list|set <id>|advance`（OP≥2 或 ccnnrp.admin.event / ccnnrp.admin.phase）。

## 6. WBS 小任务
1. 模型 + 配置读写（默认示例含"疏散警报"）；2. `TriggerEvaluator` 纯类（含阶段时钟）；3. `EventManager` 生命周期 +
   tick 节流；4. hooks 分发（动画/刷新波/通知 + P5 结算回调）；5. 命令族；6. 单测 + 集成演练。

## 7. 验收标准
1. `TriggerEvaluator` 单测：五类触发器正/反例（阶段切换、时间点边界、周期对齐、DEAD_COUNT/ALIVE_COUNT/SCOREBOARD 比较）。
2. 状态机单测：SCHEDULED→RUNNING→SETTLED；RUNNING 中重复触发被拒；禁用事件不触发。
3. 阶段时钟：无干预自动推进（durationMinutes 到点）；`/rp phase set` 立即生效并触发 ON_PHASE_*。
4. 集成：事件开始 → 玩家收到通知 + 动画钩子调用记录；结束 → 不自动结算（见 docs/06 验收 3）。
5. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
