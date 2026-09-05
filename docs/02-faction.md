# P1 阵营关系系统（docs/02）

## 1. 需求
配置文件声明阵营之间关系（敌对/中立/友好）；支持**阵营组**：批量声明"组 × 组 / 组内"关系；
供角色创建（阵营下拉）、冲突判定、事件与刷新波筛选复用。

## 2. 领域模型
- `Faction {id, name, color(hex), description, icon, tier, music?}`（music 为阵营出场音乐，可选；音乐传递优先级：启动程序指定 > 职业 music > 阵营 music）
- `RelationType {HOSTILE, NEUTRAL, FRIENDLY}`
- `FactionGroup {id, memberIds[]}`
- `RelationRule {from[], to?, type}`（**多对多**：from/to 各为一个 id 列表，列表项可为阵营或组，
  组自动展开为成员；生效范围 = from × to 的笛卡尔积，关系双向对称；兼容旧格式单字符串）
- **内部关系**：省略 `to`（或 from=to 同一列表）= 该列表内所有阵营**两两互设**该关系
  （如 from 为 [qdf, qsa, qso] 且不写 to → 三者两两友好，一行搞定全组互连）
- **解析优先级**：关系列表**从上到下**，先声明（靠前）的规则命中即生效；
  重复声明（后者被前者覆盖）→ WARN 且后者被忽略。

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
    {"id": "qsec_group", "name": "量子科学组", "memberIds": ["quantum_science", "madison"]},
    {"id": "sec_force", "name": "安全部队", "memberIds": ["qdf", "qsa", "qso"]}
  ],
  "relations": [
    {"from": ["sec_force"], "to": ["qsec_group"], "type": "friendly"},
    {"from": ["qso", "qdf"], "to": ["logistics"], "type": "friendly"},
    {"from": ["qdf", "qsa", "qso"], "type": "friendly"},
    {"from": ["quantum_science"], "to": ["madison"], "type": "neutral"}
  ]
}
```
首次启动若缺失默认写入精简样板示例（3 个样例阵营 + 4 个样例职业，字段结构同上）。

- `groups[]` 的 `name` 为可选**外显名称**（管理面板「阵营组」页签可编辑；缺省回退 `id`，仅用于展示，
  不参与 id 引用与关系解析）。入场电影「阵营关系」行按组合并时展示该外显名称。

## 4. 命令（OP≥2 或 ccnnrp.admin.faction）
`/rp faction list`、`/rp faction relation <a> <b>`、`/rp faction relation set <a> <b> <hostile|neutral|friendly>`、
`/rp faction group list`、`/rp faction group create <id> <ids...>`、`/rp faction group relation <g1> <g2> <type>`、
`/rp faction graph`（管理员，打开**关系测定图**全屏界面）。
所有 set 写回配置并立即重载；写前做防循环/自引用校验（组不可嵌套组）。

## 4.5 关系管理页签与关系测定图

- **关系管理页签**（v2.18.10 起，管理面板「关系管理」页签，与「经验规则」并列）：
  左侧关系规则列表（从上到下优先级）；右侧编辑 from/to（多阵营或组、逗号分隔）、类型三选，
  **留空 to = 内部关系**（列表内两两互设）；新增/保存/删除走 RelationEditC2S（服务端权威校验+落盘）。
  - 载荷 {action, rule{from,to,type}, original?{from,to}}：**保存/删除携带选中规则的 original**，
    服务端按 original 的 from/to 定位（方向对称）原位替换/删除，支持修改 from/to 且不误增新规则；
    无 original 的旧载荷回退按新值 upsert（兼容旧客户端/命令）。
  - 列表规则按声明顺序（从上到下优先级）展示；保存仅改类型时原位更新，修改 from/to 时原位替换并保持优先级位置。
  - from/to 输入框上方各有一个**阵营下拉框 + 注入按钮**：下拉选择阵营（显示「名字(id)」），
    点「注入」把该阵营 id 去重追加到对应输入框的逗号列表（便于从现有阵营快速组规则）。
- **关系测定图**：关系管理页签内「打开关系测定图」按钮，或 `/rp faction graph`（管理员）；
  全屏显示阵营徽章+连线（白中立/红敌对/绿友好），可拖动缩放，关闭返回管理面板。
- 内容：每个阵营 = **徽章 + 名称标签**；有关系的阵营对之间**连线**——
  **白 = 中立 / 红 = 敌对 / 绿 = 友好**（按从上到下优先级的生效类型）。
- 交互：**左键拖动平移 · 滚轮缩放 · Esc 关闭**；数据来自 CharacterListS2C 的 `relations` 边（服务端 `FactionGraph.edges()`）。

## 5. WBS 小任务
1. 模型 + 默认配置资源；2. `FactionGraph` 纯类：装载/解析/校验/resolve(a,b)；3. `FactionManager`：读写/重载/默认生成；
4. /rp faction 命令族 + PermissionHelper；5. 单测。

## 6. 验收标准
1. `FactionGraph` 单测：组×组全组合、组内、单点>组、重复声明 WARN、非法 id 报错（含行号）、自引用拒绝 —— 全绿。
2. `/rp faction relation set qdf qsa neutral` 后配置落盘且 `resolve` 即时生效。
3. `spotlessCheck`、`clean build`、`test -PrunTests` 全绿；zh/en 键齐备且 LangFileTest 通过。
4. delete 后重载配置可恢复（默认文件生成逻辑）。
