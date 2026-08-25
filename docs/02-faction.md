# P1 阵营关系系统（docs/02）

## 1. 需求
配置文件声明阵营之间关系（敌对/中立/友好）；支持**阵营组**：批量声明"组 × 组 / 组内"关系；
供角色创建（阵营下拉）、冲突判定、事件与刷新波筛选复用。

## 2. 领域模型
- `Faction {id, name, color(hex), description, icon, tier, music?}`（music 为阵营出场音乐，可选；音乐传递优先级：启动程序指定 > 职业 music > 阵营 music）
- `RelationType {HOSTILE, NEUTRAL, FRIENDLY}`
- `FactionGroup {id, memberIds[]}`
- `RelationRule {from, to, type}`（from/to 可单阵营或组；组×组 = 任意成员对；组内规则 from=to=组）
- **解析优先级**：单点 > 组×组 > 组内；同优先级重复声明 → WARN 且后者覆盖。

## 3. 配置格式（config/ccnr_rp/factions.json）
```json
{
  "version": 1,
  "factions": [
    {"id": "quantum_science", "name": "量子科学", "color": "#2F6BFF", "description": "CCNR 全资子公司",
     "music": "audio/faction_quantum.wav" },
    {"id": "qdf", "name": "QDF司令部", "color": "#4CAF50", "description": "设施保全与武装指挥" },
    {"id": "qsa", "name": "QSA综合处理小组", "color": "#FF9800", "description": "高级安全保障部队" },
    {"id": "qso", "name": "QSO", "color": "#9C27B0", "description": "直属最高层私人武装" },
    {"id": "madison", "name": "麦迪逊研究所", "color": "#00BCD4", "description": "科技研发" },
    {"id": "logistics", "name": "后勤与供应链", "color": "#795548", "description": "物资保障" }
  ],
  "groups": [
    {"id": "qsec_group", "memberIds": ["quantum_science", "madison"]},
    {"id": "sec_force", "memberIds": ["qdf", "qsa", "qso"]}
  ],
  "relations": [
    {"from": "sec_force", "to": "qsec_group", "type": "friendly"},
    {"from": "qso", "to": "qdf", "type": "friendly"},
    {"from": "logistics", "to": "qsec_group", "type": "friendly"}
  ]
}
```
首次启动若缺失默认写入以上示例（含《职位划分》组织）。

## 4. 命令（OP≥2 或 ccnnrp.admin.faction）
`/rp faction list`、`/rp faction relation <a> <b>`、`/rp faction relation set <a> <b> <hostile|neutral|friendly>`、
`/rp faction group list`、`/rp faction group create <id> <ids...>`、`/rp faction group relation <g1> <g2> <type>`。
所有 set 写回配置并立即重载；写前做防循环/自引用校验（组不可嵌套组）。

## 5. WBS 小任务
1. 模型 + 默认配置资源；2. `FactionGraph` 纯类：装载/解析/校验/resolve(a,b)；3. `FactionManager`：读写/重载/默认生成；
4. /rp faction 命令族 + PermissionHelper；5. 单测。

## 6. 验收标准
1. `FactionGraph` 单测：组×组全组合、组内、单点>组、重复声明 WARN、非法 id 报错（含行号）、自引用拒绝 —— 全绿。
2. `/rp faction relation set qdf qsa neutral` 后配置落盘且 `resolve` 即时生效。
3. `spotlessCheck`、`clean build`、`test -PrunTests` 全绿；zh/en 键齐备且 LangFileTest 通过。
4. delete 后重载配置可恢复（默认文件生成逻辑）。
