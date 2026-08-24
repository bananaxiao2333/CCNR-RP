# P7 动画系统（docs/08）

## 1. 需求
被调用时向玩家播放**数据驱动表现层动画**，参数化传入数据；在 玩家出生 / 玩家死亡 / 游戏结局 / 事件开始
（与升级 bonus）等钩子上播放。不涉及 3D 骨骼动画。

## 2. 数据模型（config/ccnr_rp/animations.json）
```json
{
  "version": 1,
  "sequences": {
    "spawn_intro": {"steps": [
      {"type": "FADE", "color": "#000000", "from": 1.0, "to": 0.0, "durationTicks": 30},
      {"type": "TITLE", "titleKey": "ccnr_rp.anim.spawn.title", "subtitleKey": "ccnr_rp.anim.spawn.subtitle",
       "fadeInTicks": 20, "stayTicks": 60, "fadeOutTicks": 20},
      {"type": "SOUND", "sound": "minecraft:entity.experience_orb.pickup", "volume": 1.0, "pitch": 1.0},
      {"type": "PARTICLE", "particle": "minecraft:campfire_cosy_smoke", "pos": "PLAYER", "count": 40, "spread": 1.5},
      {"type": "ACTIONBAR", "textKey": "ccnr_rp.anim.spawn.actionbar"},
      {"type": "CAMERA", "path": [{"x": 0, "y": 1.5, "z": 0}, {"x": 3, "y": 2.5, "z": 3}],
       "durationTicks": 60, "relative": true}
    ]},
    "player_death": {"steps": [{"type": "FADE", "color": "#7F0000", "from": 0, "to": 0.6, "durationTicks": 40}]},
    "game_end":  {"steps": [{"type": "TITLE", "titleKey": "ccnr_rp.anim.end.title", "subtitleKey": "ccnr_rp.anim.end.subtitle", "stayTicks": 80}]},
    "event_start_alarm": {"steps": [{"type": "SOUND", "sound": "minecraft:block.note_block.alarm", "volume": 1.0, "pitch": 1.0},
                                    {"type": "TITLE", "titleKey": "ccnr_rp.anim.event.alarm.title", "stayTicks": 60}]},
    "level_up": {"steps": [{"type": "TITLE", "titleKey": "ccnr_rp.anim.levelup.title", "params": ["DOLLAR_LEVEL_DOLLAR"]}]}
  }
}
```
- 步骤类型：`TITLE / SUBTITLE / ACTIONBAR / FADE / CAMERA / PARTICLE / SOUND`（新增类型须注册解析器 + 客户端执行器再启用）。
- 参数化：文本用 lang 键 + `DOLLAR_param_DOLLAR` 占位；步骤可含 `params` 由调用方传入（玩家名/等级/事件 id 等）。
- 序列步进：顺序执行，`durationTicks` 自然等待；`parallel` 步骤组（P7 实现 `{"type":"GROUP","parallel":true}`）。

## 3. 执行模型
- 服务端 `AnimationEngine`：序列解析校验 → 决策（目标集合、参数填充）→ 发 `AnimationPlayS2C {sequenceId, params, durationTicks}`。
- 客户端：`ClientAnimationPlayer` 按步骤类型执行（TITLE/ACTIONBAR/FADE/CAMERA 客户端；SOUND/PARTICLE 可服务端代发）。
- 钩子：`player_spawn`（P8 部署时）、`player_death`（P4）、`game_end`（P6 结束事件）、`event_start`（P6）、`level_up`（P5）。
- 未装客户端（纯服务端）不崩溃：无 S2C 通道者直接跳过。

## 4. 命令
`/rp animation play <id> [selector]`（OP≥2 或 ccnnrp.admin.animation；selector 支持 @a/@p/玩家名）。

## 5. WBS 小任务
1. `AnimationParser`（纯类：JSON→序列、未知类型拒载、参数校验）；2. `ClientAnimationPlayer` 六类执行器；
3. `AnimationEngine` 服务端决策 + 分发 + 钩子注册表；4. 命令；5. 单测 + 各钩子手工演练。

## 6. 验收标准
1. 解析器单测：合法序列解析、未知类型拒载带错误信息、DOLLAR 参数替换（含缺失参数 WARN+原样保留）、duration 越界钳制。
2. 手工（dev 客户端）：刷新部署→spawn_intro 播放；死亡→player_death；赛事开始→event_start_alarm；
   /rp animation play game_end @a → 全员 TITLE/FADE。
3. 服务端没有对应客户端时（无插件客户端）不发崩、无异常日志。
4. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
