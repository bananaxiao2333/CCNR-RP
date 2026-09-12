# P5 经验系统 v3（事件广播 + 规则引擎）（docs/06）

> v2 已废弃：固定三来源（值班 duty / 任务 task / 疏散 evac）结算在 v2.18.0 被
> **事件广播 + 规则引擎**完全替代。本文件描述 v3 设计（当前生效），旧模型仅保留
> 用户累计 XP（`user_profiles.json` 的 `xp`）与结算触发点。

## 1. 需求

发生什么事情时产生**事件广播**；经验系统接收广播后按**规则**对目标角色求值，
把经验变化并入该角色的**经验变化列表**；结算时列表求和（可为负）计入用户累计 XP。
经验变化列表在**经验结算**与**人物部署**时清空。

## 2. 事件广播（内置注册表，规则按事件 id 订阅）

| 事件 | 触发 | 参数（名+类型，固定） |
| --- | --- | --- |
| `character_alive` | 每 60 秒对每个 **ALIVE** 用户各发一次 | `uuid`String, `playerName`String, `professionId`String, `factionId`String, `aliveSeconds`Long(本回合累计), `intervalSeconds`Long(60) |
| `character_kill` | 玩家击杀**任意生物**（LivingEntity，含玩家）→ 发给击杀者 | `uuid`/playerName``/`professionId`/`factionId`（击杀者）, `victimType`String, `victimName`String, `victimUuid`String(可为空), `victimProfessionId`/`victimFactionId`String(非玩家为空), `victimRelation`String(`hostile`/`neutral`/`friendly`，击杀者↔被击杀者阵营关系；任一方无阵营/未知为空串) |
| `character_death` | 角色死亡/掉线判死，**结算开始前**发给死者 | `uuid`, `playerName`, `professionId`, `factionId`, `reason`String(`death`/`offline`) |

注册表是纯类（`ExperienceEventRegistry`），客户端/服务端共用，管理面板补全与参数面板
直接本地引用，无需网络下发。

## 3. 规则（config/ccnr_rp/experience_rules.json，管理员可编辑，热重载）

```json
{
  "version": 1,
  "rules": [
    { "id": "alive_duty", "enabled": true, "eventId": "character_alive",
      "conditionExpr": "", "valueExpr": "1", "titleExpr": "值班" },
    { "id": "kill_zombie", "enabled": true, "eventId": "character_kill",
      "conditionExpr": "victimType == \"zombie\" && aliveSeconds >= 60",
      "valueExpr": "50", "titleExpr": "击杀 " + victimType }
  ]
}
```

- `id` 唯一；`eventId` 必须存在于注册表；表达式保存/加载时服务端解析校验
  （语法 + 类型），失败拒存/跳过该条并回显原因（不崩服务）。
- `conditionExpr` 为空 = 恒激活。

## 4. 表达式（纯逻辑、无 MC import、可 JUnit 直测）

- **判断表达式**（condition）：参数比较 `== != < <= > >=`（字符串仅 `== !=`）、
  逻辑 `&& || !`、括号；结果 = 布尔，决定本规则是否激活。
- **数值表达式**（value）：数字、参数名、`+ - * / ( )`、一元负号、
  `round/floor/ceil/max/min` 函数；结果可负。
- **标题表达式**（title）：字符串模板，`"文字"` 字面量 + `+` 拼接参数/函数。
- 求值失败（参数缺失/类型不匹配/除零）→ 本条规则本次跳过 + 限频 WARN。

## 5. 经验变化列表（每用户，ALIVE 期间，落盘防掉线丢失）

- 条目 `XpChange(ruleId, title, value)`，value 可负。
- **合并**：同 `ruleId` 已有条目 → `value` 相加、`title` 取后来者；不同规则追加
  （保持插入顺序）。不计合并次数。
- **清空**：结算时（求和计入用户 XP 后清空）；部署时（仅清空、不计 XP）。
- 持久化：并入 `user_profiles.json`（version 3，新增 `pendingXp` 字段；
  读旧档缺字段用默认 WARN，向后兼容）。

## 6. 结算（瞬时、服务端权威；触发点 = 死亡退场或命令）

- 触发：`/rp settle [player|all]`（命令）、退场结算（死亡/判死/退役（SKIP_SETTLE
  除外）/征召结束）。**事件结束与游戏结束不再自动结算**——待结算列表继续累积，
  直到玩家死亡退场或管理员手动 `/rp settle`。
- 死亡路径顺序：**广播 character_death（结算前赋予）→ 规则入列表 →
  结算 = sum(列表)（可为负）→ addXp（累计下限 0）→ 清空列表 →
  推送结算动画数据 → level_up 钩子**。
- 幂等：列表结算即清空，重复 settle 无增量。移除 v2 的 LedgerStore/SettlementCalcs/
  duty/task/evac 逻辑与 `/rp evac set`；保留 `/rp xp`、`/rp level`、`/rp settle`。
  （2.26.5 重新引入 `/rp evac <分值> <标题>`，但**语义完全不同**：不再是 v2 的"设置疏散方式枚举"，
  而是"集体疏散结算"动作，见 §7.6。）

## 7. 管理面板「经验规则」页签

- 规则列表：增/删/改/启停/顺序。
- 单条编辑：事件下拉补全 → **限定高度可滚动参数面板**（参数名+类型，
  点击插入**当前聚焦**表达式框末尾）→ 判断/数值/标题三个表达式输入框（参数补全）。
- **验证器**：三个表达式框实时语法校验徽标（✓/✗+原因+位置）；「试算」区域按事件
  参数表生成样例输入（预填默认值），点试算本地求值展示 condition→true/false
  （提示将激活/跳过）、value→数值、title→字符串。验证器为客户端本地预览；
  服务端保存时仍做权威校验。
- 权限：`ccnrrp.admin.xp`（无节点回退 OP≥2）。

## 7.5 手动记分（/rp xp add）

- `/rp xp add <玩家> <数值> <标题>`（管理命令，OP≥2 或 `ccnrrp.admin.settle`）：
  向该玩家**待结算列表**追加一条自定义记分项目（标题 + 数值，数值可为负），
  随下次结算（死亡退场或 `/rp settle`）计入累计 XP；同标题条目合并（数值相加、标题取后来者）。
- 条目入列表即时生效，结算时以动画形式展示（存活期间不常驻显示）。

## 7.6 疏散结算（`/rp evac` 与序列步骤 `EVACUATE`，2.26.5）

- **用途**：剧本"到时收工"——把**当前在场（ALIVE）**的人集体结算并转回观察者，
  由序列步骤按秒数自动调起（如开局 5 分钟），也可由管理端手动 `/rp evac <分值> <标题>` 触发。
- **与其它结算的区别**：

  | | `/rp settle` | 死亡退场结算 | 疏散结算 |
  | --- | --- | --- | --- |
  | 作用对象 | 指定玩家 / `all`（含观察者） | 当事玩家 | **仅当前 ALIVE** |
  | 触发阵亡规则 | 否 | **是**（先广播 `character_death`） | **否**（非死亡路径） |
  | 附加分数 | 无 | 无 | **追加一条疏散分** |
  | 状态变化 | 无 | 转观察者 | **转观察者**（并卸下阵营属性） |

- **不扣死亡分**：疏散不广播 `character_death`，故 `death_penalty` 一类阵亡规则不会被触发；
  已经阵亡过的人，其扣分在死亡当时就已随死亡结算入账，疏散不追溯、不回冲。
- **其他分数照算**：疏散只是往待结算列表追加一条，值班/击杀等既有条目原样保留，按整份列表求和
  （纯逻辑 `EvacSettlement`，可脱机单测）。
- **边界**：`title` 为空或经验服务未就绪 → 序列步骤跳过 + WARN（不中断整条序列）。
  详细参数与配置示例见 [docs/15 §4.8](15-模式编排与多模式设计.md)。

## 8. 客户端 HUD（经验展示 + 结算动画）

- 右下角**内收**（不贴角）、整体水平居中、文字中心对齐。
- **仅结算时显示**（存活期间不常驻）：结算动画逐项吸入 → 播完后**总数字停留 5 秒**再消失；
  无变化的结算直接显示总数字 5 秒。
- 底部一行 = **白色经验数字**（当前总经验）；其上 = **经验变化项目列**，
  每行格式严格为「**符号数值 标题**」（如 `+100 击杀奖金`；正=绿、负=红）。
- **结算动画**（纯视觉；服务端结算瞬时完成）：结算后**先等 3 秒**（列表静止展示）
  → 最底一项**缓慢**移入数字并消失（单项约 0.7s，先快后慢缓动）→ 数字更新 →
  列表下移补齐 → 项目间停顿约 0.15s → 循环至全部吸入 → 数字停在新总值 →
  **停留 5 秒**后隐藏。
- 死亡结算动画（含死亡界面）同样可见；离线结算仍走挂起通知补发（PendingNoticeStore）。
  动画取代 v2 的 XpLinesS2C 逐行红/绿弹层。

## 9. 网络（SimpleChannel ccnr_rp:main）

- S2C：`XpListS2C`（列表更新推送）、`XpSettleAnimS2C`（结算动画：有序条目+新总值）、
  `UserXpS2C`（保留：总经验/等级）、`RulesStateS2C`（规则集同步管理面板）。
- C2S：`RuleEditC2S`（规则增删改，服务端校验回执）。

## 10. 权限与容错

- 权限节点 `ccnrrp.admin.xp`（沿用 ccnrrp.admin.* 模式，回退 OP≥2）。
- 规则 JSON 损坏 → ERROR + 保留 `.bak` + 跳过（docs/01 容错）；校验错误消息带 文件:行号。
- 表达式求值失败限频 WARN，不崩服务、不影响其他规则/玩家。

## 11. 测试与验收

1. 单测：表达式解析/求值（数值/判断/标题，正常+非法+边界、优先级、函数、
   类型不匹配跳过）；合并语义（同规则累加、标题取后来者、顺序、负值）；
   结算求和（正负混合、负总值）；结算后幂等清空；事件注册表参数完整性；
   规则校验（坏 eventId/坏表达式拒载）。
2. dev 演练：值班 10 分钟（alive 规则累计）+ 击杀生物 + 死亡 → 结算动画逐项吸入
   （放慢可见）、死亡界面（DeathScreen）上动画可见；账目正确；部署清空列表。
3. 事件结束/游戏结束**不再**自动结算；结算仅死亡退场与 `/rp settle` 触发。
4. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
