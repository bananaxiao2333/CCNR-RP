# Changelog

## 2.26.10（死亡掉落改由世界规则 keepInventory 决定）

- **修正一处"补丁推翻世界规则"的反向缺陷**（用户要求"死亡掉了那里要获取游戏规则，
  如果游戏规则是开了死亡不掉落的就不会爆出任何装备"）。
  - **原行为**：2.24.0 修 issue #1 时，把 `keepInventory=true` 也归进了"原版不会掉 → 所以本 mod 必须替它爆出来"，
    于是规则写着"死亡不掉落"，死一次却满地装备。补位逻辑本身没错（要修的是"死亡后背包不被清理"），
    错在把**世界规则**当成了需要被补丁覆盖的"原版缺陷"。**规则是玩家/管理员的显式意图，优先级高于本 mod 的补位。**
  - **现行为**：`keepInventory=true` 时本 mod 对死亡路径**既不爆出背包、也不生成遗体**，背包**直接删除**。
    补位只保留另外两种原版确实不会掉的情况：死亡瞬间是旁观者（本 mod 会把未部署玩家强制切成旁观者，
    所以这在实战里很常见）、离线判死。
- **为什么是"删除"而不是"留在身上"**：不爆出 ≠ 留着。本 mod 的观察者契约要求"切观察者必须空背包"
  （docs/05 §2.1），且 `keepInventory` 在本 mod 的流程里不可能意味着"留着下一局接着用"——重新部署时
  `SpawnFramework.clearInventory` 一定会清空背包并按岗位发装备。若只"不爆出"而把物品留在观察者身上，
  结果是玩家死后仍抱着上一局的枪、到下次部署时才无声消失，比直接删除更难预期。
- **连带抑制遗体**（用户定调"一点装备都不留"）：装了 Corpse 时它**无条件**为每次玩家死亡生成遗体——
  其 `PlayerDeathEvent` 没有 `@Cancelable`（只有单向的 `storeDeath()`/`removeDrops()`，读的 getter 还是包私有），
  `ServerConfig` 里也没有"是否生成遗体"的开关。唯一可拦点是遗体**加入世界**的那一刻：死亡时打一个带 TTL 的
  抑制标记，`EntityJoinLevelEvent` 里 `setCanceled(true)` 即"不生成"。标记命中即消费、10 秒过期、
  任何异常都返回 false（宁可留下遗体，也不误删正常遗体）。
  - **双保险**：本 mod 的 `LivingDeathEvent` 处理器是默认优先级 `NORMAL`，Corpse 的两个处理器是 `LOWEST`（最后跑）；
    我们在它读取背包与 `event.getDrops()` 之前就清空了背包，所以即使抑制漏掉，造出来的遗体也一定是**空的**。
  - **抑制只作用于真正的死亡**（自然死亡 / 掉线判死）：`/rp kill`、`/rp retire` 是管理端强制退场而非"死亡"，
    其 `SPAWN_CORPSE` 行为不因世界规则改变（保留原行为，不在本次需求范围）。
- **判据同源、纯逻辑可单测**：三个决定（背包处置 / 是否显式爆出 / 是否抑制遗体）收敛为一次判定
  `DeathInventoryPolicy.forDeath(corpseAvailable, offlineDeath, spectatorAtDeath, keepInventory)`，
  返回 `DeathHandling(disposal, dropExplicitly, suppressCorpse)`；调用方（`StatusManager.retire`）不再各写一套判据。
  原有的 `dropExplicitly(...)` 四参布尔方法被它取代（同一件事只留一个判据）。
- **测试**：`DeathInventoryPolicyTest` 重写并扩充到 12 例 —— 新增"keepInventory=true 时**任意组合**都不得爆出"
  （离线×旁观×遗体 三重循环的反向断言）、"keepInventory=true 时处置为 DELETE"、"keepInventory=true 时抑制遗体"、
  以及"keepInventory=false 时三个决定与 2.26.9 之前逐字一致"（防止改动波及默认行为）。
  `test -PrunTests` 全绿（**266 例 0 失败**，较 2.26.9 的 263 例新增 3 例）。
- **文档**：docs/05 新增 §2.2（规则优先的完整说明、为什么删除而非保留、遗体抑制的实现与为什么只能在实体入世界时拦、
  时序双保险、抑制范围、边界与如何失效）；§2.1 契约表新增 `keepInventory=true` 行；§3 更正原文把
  `keepInventory` 列为"原版不会掉"的叙述。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.10**。

## 2.26.9（语言包反查守卫：以代码为基准）

- **事故**：2.26.5 / 2.26.6 的语言键在开发过程中被**一次性整体回退**（一次 `git checkout -- lang/*.json`
  把两份语言包退回 2.26.4 的提交），`usage.wave` / `usage.evac` / `wave.spawned` / `evac.done` /
  `match.*` / `animation.*` 等键全部消失。实机表现是背包左侧面板把**原始键当文案画出来**
  （截图可见 `[ ccnr_rp.match.label.mode ]`、`ccnr_rp.match.phase.pos`）。
- **根因（不是"忘了加"，是"守卫看不见"）**：`LangFileTest` 当时只做**两份语言包的键集合互相比对**。
  这次误操作让 zh 与 en **同时**丢失同一批键，两边依然"一致"，`zhAndEnHaveIdenticalKeys` 全绿通过——
  漏翻能拦住，**"一起漏"拦不住**。这是判据选错了：一致性 ≠ 完整性。
- **修复：新增反查用例 `LangFileTest.everyKeyReferencedByCodeExists()`**，把基准从"另一份语言包"
  换成**源码本身**：
  1. 扫描 `src/main/java` 全部 .java，先剥掉块注释与行注释（否则文档里举例的键会被误当成硬引用），
     再匹配 `"ccnr_rp\.[A-Za-z0-9_.\-]+"` 字面量；以 `.` 结尾的是拼接前缀，跳过。
  2. 白名单 `NON_LANG_LITERALS` 只登记**经确认非语言键**的字面量，当前仅
     `ccnr_rp.entrance_music`（`ClientAudio` 的 JVM 属性名，只是恰好同前缀）。不做宽泛豁免。
  3. 任一被引用的键在 zh_cn 或 en_us 缺失即失败，报错**点名键名 + 引用它的源文件**，可直接定位。
  4. **反向断言**：扫描到的键数必须 > 400。否则一旦扫描路径写错（例如测试工作目录变化），
     用例会"匹配不到任何键 = 全部通过"——那是比原事故更危险的假阳性。
- **验证守卫真的有效**（不是"写完就算"，两种场景都实测）：
  1. 只删 zh_cn 一处的 `ccnr_rp.match.label.mode` → 新旧用例**同时**变红；
  2. **复现原事故**：从 zh_cn 与 en_us **同时**删除同一个键 → `zhAndEnHaveIdenticalKeys` 依旧通过，
     只有新用例失败并打印 `ccnr_rp.match.label.mode (com/ccnrcom/rp/client/MatchStatusPanel.java)`。
     即：新用例补上的正是旧判据的空洞。
- **顺带修掉 3 个"从没进过仓库"的老键**：`ccnr_rp.animation.count` / `.played` / `.missing`。
  它们被 `AnimationCommand` 引用，但 `git show HEAD:.../zh_cn.json` 里**从来就没有**——
  即漏翻自动画命令上线起就存在，与本次回退**根因不同**（一个是判据失明，一个是从未登记），故分开陈述。
  反查守卫上线后第一次运行就把这 3 个报了出现。
- **复位被误删的键**：`usage.wave` / `usage.evac` / `wave.spawned` / `evac.done`（属 2.26.5）与
  `match.*`(9)（属 2.26.6）已全部补回，两份语言包各 626 键、键集合一致。这批键分别落回各自版本的提交，
  因此**逐版本构建时每一版的语言包都是完整的**。
  **就地插入，不重排文件**——该文件不是按字母序排的，整体重写会产出上千行噪声 diff，正是这类噪声
  诱发了当初那记"回退重来"。
- **纪律（写进 AGENTS.md）**：语言包只允许**就地锚点插入**；禁止用脚本重排；禁止对语言包做
  整文件级的 `git checkout` 回退（要撤就撤具体改动）。通用版：**任何存在未提交改动的工作区，都不允许
  `git checkout -- .` / `git checkout -- <目录>`**——它静默丢弃全部未提交改动（本次开发中该动作真实发生
  过一次，靠事先快照才恢复）；要回到已知状态，用"先快照、再逐文件恢复"。
- **测试**：`test -PrunTests` 全绿（**263 例 0 失败**，较 2.26.8 的 262 例新增 1 例守卫）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.9**。

## 2.26.8（角色（职业）层属性：同 id 覆盖阵营层）

- **新增：属性分两层，角色=职业层覆盖阵营层**（用户定调"颗粒度下到角色"，并明确 **角色就是职业**）。
  1. **数据**：`factions.json` 的职业对象新增可选 `attributes`（与阵营层**同一格式**：
     `[{id, amount, operation}]`，同样 16 条上限、同样走 `AttributeProfile` 校验）。
  2. **覆盖语义 = 判定键为属性 id**：只要职业层声明了某个 id，阵营层该 id 的**全部运算条目**都被取代
     （不是按 `(id+运算)` 逐条覆盖，也不是叠加）。职业层没声明的照旧继承；职业层新写的 id 属于**新增**，
     不计入"覆盖"集合。规则收在纯逻辑 `AttributeProfile.layer(faction, profession)`，
     返回生效条目 + **被覆盖的 id 集合**（界面/命令的标记与生效逻辑同源，不另写判据）。
  3. **为什么按 id 整体覆盖**：把两层不同运算叠在一起（阵营 `add 40` + 职业 `multiply_base 0.1`）
     既没有直觉意义也不好解释；"同 id 整体接管"是管理员最容易预期的行为。
  4. **套用时机与阵营层完全一致**（部署 / 登录 / 保存即时生效），取值改为
     `AttributeService.effectiveFor(factionId, professionId)`；`applyTo` 因此多带一个 `professionId`。
     在线即时生效按层选范围：改阵营层 → 该阵营在线成员；改职业层 → 以该职业在场的成员
     （重套时带的是玩家**自己的阵营**，不是职业所属阵营）。
  5. **命令**：新增 `/rp attribute prof get|set|remove|clear <职业>`（与阵营层同一套实现与同一权限节点
     `ccnrp.admin.attribute`；原 `/rp attribute set <阵营> …` 语法**保持不变**）。
     `prof get` 额外列出"正在覆盖阵营层的同 id 属性"。
  6. **界面**：职业页签新增「属性」按钮，打开**同一个**属性弹窗（标题带职业 id），
     正在覆盖阵营层的行右侧标红字 **「覆盖」**。职业表单已顶到面板最小高度（340px），
     故按钮**并入既有快捷操作行**（不新增纵向行）；同时给 `RpButton.draw` 的标签加了共享裁剪
     （`RpTheme.clip`）——按钮行随数量变窄时，长标签不会再画到按钮外面（与 2.26.7 的"出框"同一类问题）。
  7. **投影**：`professions[].attributes` 已加入 `sendList` 投影，避免"保存成功但界面看不到"（docs/01 §11.2）。
  8. 管理 CRUD 契约扩展：`kind="attribute"` 的载荷新增 `target`（`faction`|`profession`，缺省 `faction`
     **向后兼容旧客户端**）与 `professionId`。
- **文档**：docs/16 新增 §2.4（层级、覆盖语义、为什么、套用时机、命令、界面、投影、边界与如何失效）；
  §3 界面补职业页签入口；docs/10 补职业层命令与 `factions.json` 字段说明。
- **测试**：`AttributeProfileTest` 补 6 例分层用例——职业层为空全继承 / 同 id 覆盖（且未声明的 id 照旧继承）/
  **含该 id 的全部运算一并让位**（关键断言：阵营层同 id 的 add+两条乘算全部失效）/ 职业层新 id 属新增不标覆盖 /
  null 与空边界 / 多 id 覆盖集合。`test -PrunTests` 全绿（262 例 0 失败）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.8**。

## 2.26.7（阵营属性档案弹窗修复：占位符糊字 / 提示出框 / 属性 id 补全）

- **修复：属性行占位符不消失、与输入内容糊在一起**（实机截图反馈：出现 `adadadad属性 id`）。
  根因是两处输入框把 **`setSuggestion` 当成占位符**在用 —— 它是"补全剩余串"API，原版会把它
  **追加在已输入文本之后**绘制；真正的占位符 API 是 `setHint`（原版仅在 `value.isEmpty() && !isFocused()`
  时绘制，判据来自 `EditBox.renderWidget` 字节码）。`attrBox`（属性档案弹窗）与 `seqBox`（流程编辑器参数）
  统一改用 `setHint`，并加自检纪律：`grep -rn "\.setSuggestion(" src/main/java/com/ccnrcom/rp/client/` 应为 0 处。
- **修复：属性弹窗提示行"出框"**（实测拖到屏幕右缘）。该行是整句长文案，此前直接单行 `drawString`，
  没有裁剪也没有断行。现改为 `RpTheme.wrapText` 最多两行、仍放不下时末行用 `RpTheme.clip` 裁出省略号；
  弹窗标题同样走 `clip`（阵营 id/显示名可能很长）。提示文案也精简到两行内可读完（zh/en 同步）。
- **新增：属性 id 输入框的补全提示**（此前完全没有）。新增 `SugSource.ATTRIBUTE`，候选是**已注册属性 id**
  （原版 + 已装 mod 的全部注册名，直接抄进配置即可用）：
  1. 数据源：`ManagerStateS2C` 新增 `attributeIds` 数组（服务端 `AttributeService.registeredIds()`）；
     **主线程只扫一次注册表**，再按玩家复制（不在逐玩家的字段函数里重复扫，docs/01 §9.6）。
  2. 聚焦识别：`resolveIdSugSource()` 新增 `attributeModalOpen` 分支，只给"属性 id"那一列补全（数值列不补）。
  3. 顺序约束（写错就点不到）：补全浮层**绘制在弹窗内容之后**（置顶），**命中在 `attributeModalOpen`
     吞点击之前**（该分支原本无条件 `return true`，会把候选项点击一并吞掉）。
  4. 键盘上下/回车沿用既有通用处理（已在 `keyPressed` 里按 `idSugBox`/`idSugItems` 工作）。
- **文档**：docs/14 新增 §5.9（三条缺陷的根因、`setHint` vs `setSuggestion` 契约表、两个顺序约束、如何失效）。
- **测试**：`ClientCharacterStateTest` 补 2 例（已注册属性 id 按序镜像；旧服务端载荷缺 `attributeIds` 时
  清空而不是残留上一次的值）。`test -PrunTests` 全绿（256 例 0 失败）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.7**。

## 2.26.6（背包左侧「对局状态」面板 + 模式/阶段/事件的展示三件套）

- **新增：背包界面左侧「对局状态」面板**（`MatchStatusPanel`）。打开背包（E）即可看到这一局进行到哪了：
  1. **游戏模式卡**：`[ 游戏模式 ]` + 显示名 + 描述；未激活模式时回退本地化文案
     "未启用模式"（而不是露空串或内部 id）。
  2. **回合阶段卡**：`[ 回合阶段 ]` + 显示名 + 描述；带红色状态条与警示底（本局正在推进）。
     阶段描述缺省时回退"第 n / m 幕"读数，比空白有用。
  3. **两种计时都支持**（用户要求"如有"）——有几种画几种，条件驱动幕不占位：

     | 读数 | 数据源 | 倒数时钟 |
     | --- | --- | --- |
     | 本幕剩余 | 时长驱动幕的 `durationMinutes`（新增纯逻辑 `PhaseClock.ticksRemaining`） | **tick**（暂停时停住） |
     | 下一步 | 行为序列下一个待执行步骤（新增 `SequenceEngine.nextStepInMs/nextStepLabel`） | **墙钟**（服务端 `dueAtMs` 即墙钟） |

     两种计时刻意用两种时钟：阶段由 tick 推进、序列按墙钟到期，用错会出现"暂停后倒计时对不上"。
     剩余 ≤10 秒转红；`/rp end` 后的空窗期显示"本局已结束 · 等待开局条件"。
  4. **位置与降级**：背包界面左侧、纵向居中；卡宽上限 150px 按可用空间收缩，
     **可用宽度 < 86px 整块不画**（绝不压背包本体）；入场电影期间不画。
  5. 本面板当前**纯只读**（不注册鼠标/键盘处理）——管理员快速操作经用户决定暂缓，方案见 `mod/TODO.md §1`。
- **新增：模式 / 阶段 / 事件的「展示三件套」**（显示名 · 描述 · 图标）——界面不再只显示内部 id：
  1. 新纯逻辑值类型 `DisplayInfo`（`util`，无 MC import，可脱机单测）：`name`/`desc`/`icon`，
     **`name` 空则回退实体 id**（界面永不露空串），`parse` 对 null/畸形输入安全。
  2. `ModeDef` 改为 `(id, display)`（`name()` 保留为回退 id 的便捷访问器，旧调用点与 `modes.json`
     的 `name` 键**完全兼容**）；`GamePhase` / `EventDefinition` 各加一个 `display` 组件，
     旧构造器保留（默认 `EMPTY`），旧配置零影响、不需要迁移。
  3. **顶部事件横幅改造**：不再 `Component.literal(id)` 直接画 id，改为显示名 + 描述（最多两行、按像素断行）
     + 图标；显示名为空才回退 id（旧配置照常工作）。
  4. **图标画法（用户指定"半遮挡在卡片左下角，出卡片部分裁剪"）**：圆心落在卡片左下角顶点 +
     `enableScissor` 裁到卡片矩形 → 伸出卡片的一半被裁掉；卡片底部预留 14px 空白带，文字不压图标。
     图标改走新增的 `RpIcons.icon`（**无底盘、无等级环**的裸图形）——用带盘/环的 `badge` 在裁剪下会露脏边。
     未知名安全回退六边形（`iconPolygon` 的 default 分支），不会崩。
  5. 取值：内置矢量图形名（`hex/shield/claw/storm/eye/target/cross/gear/helm/chest/back/heart`）
     或 `img:<名>`（服务器素材库图片）。**管理面板编辑入口暂缓**，当前手改 JSON（见 `mod/TODO.md §2`）。
- **新增 `MatchStateS2C`**：一次给全「模式 / 当前幕（含 index/total）/ 两种剩余毫秒 / 激活事件三件套」。
  **只传剩余毫秒、客户端本地倒数**——不每秒发包、也不受两端时钟偏差影响（docs/01 §9.2）。
  推送时机：登录补发（晚加入/重连必须拿到当前有效状态）、切幕、事件开始/结束与 `clear`、`/rp end` 复位、
  模式切换、序列启动与每步骤落地。客户端镜像与 `EventStateS2C` 的 id 列表保持一致（横幅点位/滚动逻辑不变）。
- 配置示例：`modes/evac_round` 的三份配置已补上 `name`/`desc`/`icon`（可直接照抄）。
- **文档**：docs/14 新增 §5.8（面板与三件套的渲染契约 + 图标裁剪画法 + 两种计时用两种时钟 + 如何失效）；
  docs/15 新增 §4.9（三件套字段、兼容性、同步时机）；docs/07 §3 补事件的展示字段；README 版本与产物名同步。
- **测试**：新增 `DisplayInfoTest`（6 例：三字段解析、空名回退、配置名优先、null/缺字段/显式 null 边界、
  标量按 Gson 语义转文本、roundtrip 与空键保留），`PhaseClockTest` 补 3 例
  （时长驱动倒数、条件驱动返回 -1、无阶段返回 -1），`ModeManagerTest` 补 2 例
  （三件套解析、空白名回退 id）。`test -PrunTests` 全绿。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.6**。

## 2.26.5（进入观察者统一清理 + 疏散结算能力 + 修两处 docs/实现偏差）

- **新增：进入观察者时统一清背包 + 卸下阵营属性（七条路径全覆盖）**。此前切观察者一共有七条路径，
  但只有 `retire` 卸了阵营属性、**没有任何一条路径清背包**，于是观察者身上会残留上一局的加成
  （血量/护甲）与装备（docs/01 §9.4 对称清理）。现在全部收口到唯一入口
  `StatusManager.purgeOnObserving(player, dropInventory)`：
  1. **只有死亡把物品爆到地上**——自然死亡（`reason=death`）与掉线判死（`RetireFlag.OFFLINE`）：
     物品留在世界里（原版掉落 / 遗体模组收纳 / `DeathInventoryPolicy` 补位），玩家能找回。
  2. **其余一切路径直接删除**，不在脚下掉一地：`/rp kill`、`/rp retire`（reason 是 `command`/`retire`，
     语义是"退场"而非"死在场上"）、疏散结算、旁观者兜底轮询、DEAD 归一化轮询、登录归一化。
  3. **判定纯逻辑化**：由 `DeathInventoryPolicy.disposalOnObserving(naturalDeath, offlineDeath)`
     返回 `DROP`/`DELETE`（无 MC import，可脱机单测），把"哪些算死亡路径"钉死成可测规则。
  4. **背包删除只有一份实现**：新增 `DeathDrops.clearAll`（静默删除，与 `dropAll` 的"爆一地"相对），
     `SpawnFramework` 部署前那份重复的 `clearInventory` 转为转调它。
  5. **只清自己的**：属性按 `ccnr_rp:attr:` 前缀清该玩家自己的修饰，不触碰其他玩家（docs/16 §2.2）。
  6. **幂等 + 不误伤部署**：旁观者轮询只在**模式真的被改**的那一刻清理；部署在同一 tick 内同步跑完
     （`applyDeployCore` → `setStatus(ALIVE)` 之间无 tick 边界），轮询不会插进中间态清掉刚发放的装备。
- **新增「疏散结算」能力（`EVACUATE` 序列步骤 + `/rp evac` 命令）**：剧本"到时收工"的收尾动作——
  把**当前在场（ALIVE）**的人集体结算并转回观察者，可由序列按秒数自动调起，也可管理端手动触发。
  1. **作用范围 = 当前 `status == ALIVE` 的用户**：观察者/已死亡用户不在范围内（他们本回合的分数已在死亡当时
     结算过，不应再拿一次疏散分）。
  2. **其他分数照算**：疏散只往待结算列表**追加一条**，值班/击杀等既有条目原样保留，最终按整份列表求和。
  3. **不扣死亡分**：走**非死亡路径**——不广播 `character_death`、不落遗体，故 `death_penalty` 这类阵亡规则
     不会被触发；已经阵亡过的人，其扣分在死亡当时就已入账，疏散不追溯、不回冲。
  4. **转观察者**：结算后状态置 `OBSERVING`，并走上面的统一清理（清背包 + 卸属性 + 刷新档案）。
  5. **参数**：`{"type":"EVACUATE","xp":200,"title":"疏散"}`——`xp` 缺省 0（只疏散不加分），
     `title` **必填**（同时是合并键与结算动画里显示在数值后的文案）；缺 title / 经验服务未就绪 → 跳过该步 + WARN，
     **不中断整条序列**。命令入口 `/rp evac <分值> <标题>`（权限 `ccnrrp.admin.settle`，同一实现）。
  6. **纯逻辑外提**：条目语义收在 `EvacSettlement`（无 MC import，可脱机单测）——其他分数照算、不引入负分条目、
     同标题按 `evac:` 前缀合并（与规则 id、`manual:` 手动记分互不串台）。
  7. 单个用户异常隔离（`settleAll` 同款纪律），不阻塞整批。
- **修复：`hooks.notify.titleKey` 写了不生效**（docs/07 §3 有、实现无——通报静默丢失）。
  `EventModels.parseEvent` 现在按 **顶层 `notifyTitleKey` → `hooks.notify.titleKey`** 的优先级解析
  （与 `startAnimation`/`spawnWave` 的"顶层优先"一致）；两种写法都生效，旧配置零影响。
- **修复：`/rp wave spawn <id>` 不存在**（docs/15 §5 已记载但未实现）。新增该命令，
  从**波次库**显式召一波，供剧本/管理端手动调兵；权限节点独立为 `ccnrrp.admin.wave`
  （与 `ccnrrp.admin.spawn` 分开授权），并登记进 `Permissions.onGatherNodes`。
  与 `/rp spawn trigger` 同源（都进 `SpawnFramework.triggerWave`），语义分工见 docs/15 §4.4。
- **文档同步（docs 与实现一致，docs/01 §9.6）**：docs/05 新增 §2.1（进入观察者的统一清理契约表 +
  为什么收成一个入口 + 边界）并修正 `/rp kill`/`/rp retire` 的掉落描述；docs/16 §2.2 套用时机表补"统一入口 +
  同时清背包"；docs/07 §3 补 hooks 解析优先级；docs/15 新增 §4.8（EVACUATE 契约/边界）、
  §2 与 §7 的步骤清单补齐（含 `RULECHANGE`/`SWITCHPHASE`）、§5 补 `/rp evac`；
  docs/06 新增 §7.6（疏散结算与 `/rp settle`/死亡结算的区别表）并说明 `/rp evac` 与 v2 同名命令**语义完全不同**；
  docs/09 §5、docs/10 补 `/rp wave spawn` 与新的 `/rp evac`（顺手删掉 docs/10 里早已失效的
  `/rp evac <SAFE_RESCUE|DIED|…>` 那行）；docs/01 §7 补 `wave` 权限节点；
  README 版本与产物名同步（并修掉"产物 ccnr_rp-2.25.2.jar"与正文版本不符的陈旧描述）。
- **测试**：新增 `EvacSettlementTest`（6 例：既有条目保留、不注入负分、重复疏散合并、不同标题独立、
  null/0 分边界、前缀隔离）、`EventModelsTest`（5 例：hooks.notify 读取、顶层优先、
  startAnimation/spawnWave 兼容、缺失/非对象/空对象边界），`DeathInventoryPolicyTest` 补 4 例
  （自然死亡/掉线判死爆一地、其余路径直接删、死亡路径判定反向断言）。`test -PrunTests` 全绿。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.26.5**。

## 2.26.4（本 mod 音乐音量条"拖到 0 也不静音"：按 MC 原版音量模型重写）

- **修复：本 mod 音乐音量条"拖到 0 也不静音"——按 MC 原版音量模型重写**（实机反馈）：
  1. **对齐原版语义（线性 0..1，0 = 不发声）**：读原版字节码确认音量模型是
     `SoundEngine.calculateVolume = Mth.clamp(instanceVolume * options.getSoundSourceVolume(source), 0, 1)`，
     该值**原样**交给 OpenAL（`Channel.setVolume → AL10.alSourcef(AL_GAIN, v)`，**线性**增益）；
     `SoundEngine.play` 里若算出 0 则**直接不播**（日志 `Skipped playing sound, volume was zero.`）；
     拖滑块时 `SoundEngine.updateCategoryVolume(source, v)` 逐个更新**正在播放**的声道（即时生效）。
     原版音量选项本身是 `OptionInstance<Double>` + `UnitDouble.INSTANCE`、**默认 1.0**。
     本 mod 现在完全照这套：线性 0..1、0 → 不播/静音、拖动即时生效、默认 100%。
  2. **根因是那次"线性↔分贝"换算**：Java Sound 的 `MASTER_GAIN` 是**分贝**控件（macOS 实测量程
     −80..+6.02dB），旧代码写成 `gain.setValue(gain.getMaximum() * volume)` —— `maximum` 就是 +6.02dB，
     于是**任何滑块位置都被算成 +6dB（比原声还响）**：拖到 0 不静音、拖到 30% 也不变轻，这个滑块等于没有用。
     现按 `20·log10(v)` 换算并钳到量程（1.0→0dB 原声、0.5→−6dB、0.1→−20dB、0→−80dB）。
  3. **音量 0 = 真静音**：优先用 Java Sound 的 **Mute 布尔控件**（与原版"把增益置 0"等价，是精确静音），
     同时把 Master Gain 压到量程下限；两条控件都不存在的平台才回退为"不起播/停播"。
     起播前也照原版规则判定：`musicVolume <= 0` 直接跳过播放（并记 debug 日志）。
  4. **淡出重写**：改在**线性域**从当前音量降到 0（与滑块同一套语义）；旧实现 `max*volume*(1-(i+1)/steps)`
     既混用了 dB/线性，方向也反了（越"淡"越接近 0dB = 越响），最后硬停。
  5. **默认值改为 `1.0`（= 原声，与原版音量选项默认一致）**：旧默认 `0.55` 是在"换算错误、滑块无效"的前提下定的；
     新生成的 `config/ccnr_rp-client.toml` 默认写 `1.0`，**存量配置不动**（老文件里的 0.55 仍是 0.55，
     想要旧响度把滑块拉到 100% 或手改成 1.0）。`ClientAudio.musicVolume` 兜底初值同步为 1.0。
  6. **回归测试**：`ClientAudioVolumeTest`（5 用例）钉死换算——满音量=0dB（不放大）、半幅=−6.02dB、1/10=−20dB、
     **只有 0 才静音（1% 是"很轻"而不是静音，与原版一致）**、0→1 单调且永不超过 0dB。
     另在本机对真实 `DirectClip` 控件跑过一遍映射验证（100%→0dB、50%→−6.02dB、1%→−40dB、0%→Mute=true）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（228 用例 0 失败）；版本号 **2.26.4**。

## 2.26.3（K 面板滚动改为像素级平滑滚动：有中间态，不再逐格跳变）

  3. **滚动"没有中间态"（逐格跳变，没有滚动感）**：三处滚动区（席位卡网格 / 机构导轨 / 抽屉画像）从"整数行·整数格"
     改为**像素级平滑滚动**——保存「当前值/目标值」两档浮点量，滚轮与方向键只改目标值，`render()` 每帧按
     `k = 1 − e^(−dt/55ms)` 缓动逼近（有中间态、会滑过去）；拖滚动条则当前值直接跟随光标（1:1 跟手）。
     滚动条游标与命中判定都改用"当前位移"，半行滚动时点哪张卡就是哪张卡；网格带整体裁剪，卡片被干净裁切
     而不压到标题/抽屉；物品词条移到裁剪之外绘制。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（223 用例 0 失败）；版本号 **2.26.3**。

## 2.26.2（K 面板实机反馈修复：滚轮步进 / 拖拽卡顿 / "很多空行"）

  1. **滚轮"没反应"**：滚轮步进此前写成 `delta / 10`，而 `delta` 本身≈±1，`int` 截断后位移恒为 0（单方向彻底不动）。
     现改为**每格一动**（`delta > 0 ? -1 : 1`），与管理面板「设置」页签的 `scroll - delta` 行为一致；
     导轨横移 / 网格翻行 / 抽屉画像三处同步。
  2. **滚动条"卡卡的"**：拖拽路径此前每个鼠标事件都走 `rebuild()`（`clearWidgets()` + 新建部署按钮 widget），
     一次拖动就是几十~上百次 widget 重建。现拆分为 `relayout()`（只重算命中区，滚动/拖拽专用）与 `rebuild()`
     （换人/换筛选项才用）；并在位移未变化时直接跳过重算。命中区改为缓存的可见列表，不再每个事件流式过滤一遍。
  3. **"很多空行"**：网格高度改为**只占整数行**（行数由可用高度定，卡片高 44~62），网格下缘不再留空白带，
     多出的高度全部给底部抽屉；另外职位 loadout 一件装备都没有时，装备条不再铺 5 个空槽框（一排"空行"观感），
     改为一条暗刻度 + 「无装备 / NO LOADOUT」（新增键 `ccnr_rp.gui.character.equip.none`，zh/en 同步）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（223 用例 0 失败）；版本号 **2.26.2**。

## 2.26.1（滚动交互统一「可滚 = 可拖」：补齐横向拖拽 + 页签/浮层/横幅滚动条）

- **滚动交互统一「可滚 = 可拖」**（docs/14 §5.7 登记契约）：此前多处滚动区只能滚轮、看不见也拖不动，
  事件横幅更是**画出了滚动条却拖不动**（假供能）。本轮把"任何会溢出的滚动区，滚轮能滚就必须能拖、
  且必须画出滚动条"写成契约并逐处补齐：
  - **`RpScrollbar` 补齐横向拖拽**：新增 `clickH` / `dragH` / `offsetFromDragH`（此前横向只有 `drawH`，
    所以画出来的横向条无法拖动）；竖直与横向共用一套拖拽状态，按 `dragHorizontal` 区分轴向，
    `dragV`/`dragH` 在轴向不符时返回 -1，互不误吞。
  - **K 面板三处**：席位卡网格（纵向，id=1）、机构导轨（横向，id=2，滚动条取代原来的只读进度线）、
    抽屉职业画像（纵向，id=3，过长简历可滚可拖；换人自动回到首行）；滚轮在导轨上横移、在网格/抽屉上翻动。
  - **管理面板页签**（此前均为"仅滚轮"）：经验规则页签的规则列表与参数面板（id=31/32，行右缘让出 8px 槽位）、
    自定义设定页签的预设值·方案胶囊带（横向，id=33，右侧保留「‹ n/N ›」读数）、
    关系页签的来源·目标阵营下拉与阵营组页签的阵营下拉（id=34/35/36，浮层内同样"可滚 = 可拖"）。
    管理面板 `mouseDragged` 新增按 `dragId >= TAB_SB_BASE(30)` 的页签路由——否则页签内拖拽会落到面板自身的
    `scroll` 上（表现为"拖了但列表不动、面板在滚"）。
  - **事件横幅**（背包界面之上的浮层，非 Screen）：新增 `mousePressed/mouseDragged/mouseReleased`，
    经 `ScreenEvent.MouseButtonPressed/MouseDragged/MouseButtonReleased` 转发；**只在命中滚动条时取消事件**，
    其余点击照常落到背包界面，不抢原生交互。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（223 用例 0 失败）；版本号 **2.26.1**。

## 2.26.0（K 面板结构重设计：三栏 →「顶部机构导轨 + 席位卡网格 + 底部席位终端抽屉」）

- **换结构，不只是换配色**：K 面板自 v1.0.4 起是「左导航列｜中列表列｜右预览列」的三栏网格，与同类
  RP/SCP 题材终端的**通用三栏范式**（左＝机构/阵营清单、中＝角色/职位清单、右＝身份预览＋装备槽＋属性文本块）
  在信息架构层面同形，容易被误认为照搬界面。本轮把信息架构整体换掉，**三竖列不再存在**：
  1. **顶部机构导轨**：机构过滤由一整列改为 22px 横向芯片带（`[ 全部 ]` + 各机构圆形徽章芯片），
     滚轮横移、溢出时下缘 3px 进度条；点击芯片即过滤。
  2. **中部席位卡网格**：职位由单列文本行改为 **1~4 列自适应席位卡**——每卡含序号码块（结构红底反白）、
     职位名（字距放大）、`LV.n` 等级门控标签（未达标红底反白）、机构徽章＋机构名＋`在职/编制` 读数、
     **紧凑装备图标条**（头/胸/腿/靴/枪；旧版装备只在右栏出现），左缘状态条 白=可部署 / 红=编制满 /
     灰=等级未达；选中整卡反白；右侧纵向滚动条。
  3. **底部席位终端抽屉**：详情由常驻右栏改为**随选中即时更新的底部抽屉**，横向三段——
     3D 立绘（`// PREVIEW` 标签 + 机构徽章底标 + 全息光柱）/ 身份读数（`ID // FACTION`、
     `[ LV.REQ | CURRENT ]`、`PERSONNEL // FACTION`）+ 职业画像（`02 PROFILE`）/
     装备槽（`01 EQUIPMENT`，槽下带槽名）+ `[ DEPLOY ]`。
- **交互**：点击席位卡选中；点击导轨芯片切换过滤（清空选中并回到首行）；滚轮在导轨上横移、在网格上翻行；
  **方向键在网格内移动选中**（自动滚入可视区）；`Esc` / 右上 `✕` 关闭。部署按钮的判定条件与旧版**逐条一致**
  （等级门控 / 观察或在场可部署 / 阴间禁用 / 复活冷却 / 职业与阵营在职上限 / 在场换岗按"不含本人"计），
  网络包与服务端强校验未变——本轮为**纯客户端呈现层重构**。
- **降级不溢出**：卡片高度随网格高度自适应（0~62）；窗口很矮时先收起机构导轨那一行、再压缩抽屉；
  网格被压到 20px 以下整块不画（只留抽屉）；网格与抽屉间恒留 8px；内容层整体裁剪到终端框内；
  抽屉三段各自独立判空间（窄窗口先让位给读数与部署按钮，绝不互相压字）。空态给出可执行的补救指令
  （无阵营 → `/rp faction list`；无职业 → `/rp profession list`）。
- **语言键随结构清理**：随三栏结构删除 `ccnr_rp.gui.character.nav` / `.position_list` / `.db_header` /
  `.net_header`（后两个是历史遗留、指向外部参考界面窗体名的死键），并登记进 `LangFileTest.REMOVED_KEYS`
  专挡"删结构留死键"；新增 `ccnr_rp.gui.character.muster`（编制席位 / ROSTER）与 `.dock`
  （席位终端 / SEAT TERMINAL），zh_cn 与 en_us 同步。
- **文档**：docs/14 新增 §5.6（结构对照表 + 交互 + 降级 + 边界 + **如何失效**：若把机构过滤改回竖列、
  详情改回常驻右栏，或席位卡去掉装备图标条，本节的差异化前提即失效）；§7 补验收条目；
  README（界面总述、特色亮点、版本与产物名）、docs/10 §3（终端操作详解改为三段式，徽章等级改灰阶、选中态改反白）、
  `RpTheme` Javadoc 的布局描述一并同步。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（223 用例 0 失败）；版本号 **2.26.0**。

## 2.25.3（入场电影只保留「简洁版式」：删除标准版式与切换开关，旧配置自动走简洁版式）

- **删除入场电影的第二种版式**：入场电影此前按阵营配置 `cinematicCompact` 在两种版式间切换——「完整版式」
  （图标居中于屏幕中上部，标题居中 3.4x / 副标题 1.4x）与「简洁版式」（图标居左下方、文字靠左对齐，标题 2.0x /
  副标题 0.9x）。同一画面留两条渲染路径没有真实使用方，每次改版式都要同步两处，故**整体删除完整版式分支**：
  `CinematicController` 里 `compact()` 判定、居中绘制（`drawCenteredString` / `renderRow` 的 `centered` 参数）
  与只为完整版式存在的 `ROW_H` 一并移除，入场恒为简洁版式。
- **切换按钮删除**：管理面板阵营表单里的「简洁电影」开关删除，该行只留「入场全屏黑」一个**整行**开关
  （`cinematicBlackScreen` 字段与行为不变，仍可关闭全屏黑只留文字/图标，故该行不新增纵向行、表单高度不变）。
  语言包死键 `ccnr_rp.gui.admin.faction.cinematic_compact` 从 zh_cn / en_us 一并清理，并登记进
  `LangFileTest.REMOVED_KEYS`（该用例专挡「删功能留死键」，见其 Javadoc）。
- **向下兼容（配置格式不变）**：`Faction` record / `FactionManager` 不再读取与写入 `cinematicCompact`，
  但**不动老文件里已有的这个键**——存量 `factions.json` 照常加载（不报错、不丢弃、不需要迁移），
  该键降级为孤儿键：不读、不写、也不被表单保存或单字段写清除（与 2.25.0 删除的 `warheadEnabled` 同一处理方式）。
  换言之：**老配置里写 true 还是 false 都不影响渲染结果，入场一律简洁版式**；这也是本次刻意的语义收敛，
  不是「开关失效」缺陷。
- **同步链路收口**：`CharacterService.buildSharedListRoot` 的客户端镜像投影与 `SpawnFramework` 的 CinematicS2C
  部署载荷都不再携带该字段，避免留下「服务端发了、客户端不读」的死荷载（docs/01 §11.2）。
- **回归测试**：`FactionExtrasSaveTest` 新增 `legacyCinematicCompactKeyIsAcceptedButNeverReadNorCleared`——
  老配置（`cinematicCompact=false`）照常解析成功、同阵营的「入场全屏黑」仍按配置解析、`Faction` 不得再暴露
  `cinematicCompact` 访问器（防字段回归）、表单保存（`updateFaction`）后孤儿键原样保留；`setFactionAttributes…`
  用例补一条孤儿键不被单字段清除的断言。
- **规范沉淀**：docs/14 §6 登记「入场电影版式只有一种」的契约、孤儿键兼容边界，以及日后若要恢复可切换版式
  必须同时补齐的四段（阵营字段 / sendList 投影 / 部署载荷 / 面板开关）；§7 补 2.25.3 验收条目；
  docs/16 §3 修正「入场电影两个开关那行」的过期描述；docs/02 阵营字段表补 `cmdcamScene` / `cinematicBlackScreen`
  并登记孤儿键。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（223 用例 0 失败，含 `LangFileTest`）；版本号 **2.25.3**。

## 2.25.2（删除确认框实机修复：中文断行 + 隐藏下层 + 可读目标）

- **缺陷（实机截图反馈）**：删除关系规则的确认框里，正文挤成一行糊在弹窗上、还压住了下方的按钮，且下层关系面板
  的文字透过来与弹窗内容重叠。三个独立成因：
  1. **正文断行用了"按空格分词"**：中文句子没有空格 → 整句被当作一个"词"→ 永不换行、整行溢出弹窗；
     弹窗高度又是按 `font.width(msg)/(w-32)` 估的行数，于是高度与内容对不上。改为**逐字符累计 `font.width`**
     贪心断行（docs/11 §8.4 早就写明这个做法），并用**真实行数**算弹窗高度。
  2. **确认框没有参与"隐藏下层"**：`modalScrim`/`terminalPanel` 都是半透明底，只盖一层遮罩时下层表单会透出来
     （docs/01 §10-1 要求"弹窗打开时上层界面**完全不绘制**"）。已把 `dangerOpen()` 加进 `render` 的 `modal` 判定。
  3. **确认目标是一段数据转储**：关系规则的 from/to 被 `toString()` 成
     `[a, b, c, …] -> [a, b, c, …]`（几十个字符），对使用者毫无意义。改为**与列表行同一文案**
     （`内部: a, b, c 友好` / `a, b × c 敌对`），并统一按像素裁剪兜底。
- **同源缺陷一并修**：影响预检弹窗的引用行（`用户(12): a, b, c…`）同样未裁剪，长引用会溢出面板 → 走 `clip`。
- **文本原语收口（消除"各写一套"）**：新增共享入口 `RpTheme.wrapText` / `RpTheme.clip`（各带一个纯逻辑重载，
  宽度函数由调用方给，可脱机单测）；client 包内 **6 份同构实现**（`CharacterManagementScreen`/`RecruitPopupScreen`
  的 `wrapText` ×2，`RpGroupsTab`/`RpRelationTab`/`RpRulesTab`/`RpVariablesTab` 的 `clip` ×4）全部转调共享实现，
  行为对齐（`RpRulesTab` 原来在 `maxW-8` 处硬断且无省略号，现统一为带省略号的真裁剪）。docs/14 §2.1 共享入口表登记。
- **回归测试**：新增 `RpThemeTextTest`（9 用例）：无空格中文长句必须断成多行且拼接回原文不丢字、显式 `\n` 强制换行、
  空/空白/null → 单行空串、极窄列不吞字、`clip` 超宽带省略号且不超宽、极窄列不返回纯省略号、
  确认框行数随长目标增长（弹窗高度依赖它）。`test -PrunTests` 222 用例 0 失败。
- **规范沉淀**：docs/01 §10 弹窗表登记「危险操作二次确认」句柄；§10 条件 1 补"只画遮罩不够，必须跳过下层绘制"；
  §10.2 新增第 7 条（确认文案必须可读且不溢出：正文按像素断行、目标走 `clip`、禁止数据转储）
  与两项核对清单；docs/11 §8.4 补"按空格断行对中文失效"的实测教训；docs/14 §2.1 登记文本原语入口；
  docs/17 §5.1 修正"页签自绘确认框"的过期描述（2.25.1 已合并到共享实现）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿；版本号 **2.25.2**。

## 2.25.1（缺陷修复：预设删除被整条覆盖 + 删除交互统一为「右键 + 二次确认」）

- **缺陷修复（本轮 review 发现，根因值得记一笔）**：面板「删除预设」当时**把该变量的预设全删了**，而且类型/取值/显示名/说明一起被清空。根因不是删除逻辑，而是
  `CharacterService` 的 action `switch` 用 `default` 兜底"整条 upsert"：面板发的 `presetRemove` 没有对应 `case`，
  于是带着 `{id, presetId}` 落进了覆盖分支 —— 类型回退成 `text`、取值回退成空、预设被写成空数组。
  修法有两层：① 补上 `presetRemove` / `presetSave`（并支持 `presetIds` 批量）；②**把兜底去掉**——
  `var`/`scheme` 的全部 action 显式列出，未知 action 直接报错（`未知操作: xxx`），
  杜绝"漏写一个 case 就变成整条覆盖"这类静默数据损坏。
- **删除交互统一为「右键 + 二次确认」（按验收反馈，全管理面板）**：入口绑定到"用户点中的那一条"——
  列表项/胶囊**右键即删除该条目**，删除按钮与右键是同一入口的两个触发方式，命中后都先进确认框
  （显示"类型 + id"，Esc/取消丢弃不落盘）。覆盖：阵营/职业/事件/阶段/刷新波/限制、阵营组、关系规则、
  经验规则、自定义设定（变量/预设值/预设方案）、以及弹窗内的行删除（属性行/部署点行/无线电行）；
  「清空全部限制」按整批给一条确认（含条数）。自定义设定页签额外提供勾选框做多选批量删。
  原「选中条目 → 点删除按钮」的问题：按钮删的是表单里的 id，与"刚才点中的那一条"没有视觉绑定，容易删错对象。
  实现上确认框只有一份（`RpAdminScreen.confirmDelete`），打开期间在 `mouseClicked`/`keyPressed`/`charTyped`
  三处吞掉输入（否则弹窗背后的输入框仍会接收键入）；右键提示行在各定义页签底部常显。
  **规范已沉淀到 docs/01 §10.2**（含"新增删除入口"的逐条核对清单）。

- **规范沉淀**：docs/01 新增 **§10.2 破坏性操作交互规范**（入口绑定条目 / 删除按钮走同一确认 / 任何删除都要二次确认 /
  批量整体确认 / 模态吞输入 / 实现只有一份），附"新增删除入口"的逐条核对清单；docs/14、docs/17 与本节互链。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（213 用例 0 失败）；版本号 **2.25.1**。

## 2.25.0（自定义设定：全局变量系统替代区域/弹头许可；命令与 GUI 双通道 CRUD）

- **需求来源**：维护者要求把"授权方式"改成**自定义设定区**——可创建变量、命令或 GUI 做 CRUD、
  命令读取**直接返回值**、可预览值、可建预设值**点击直接切换**（预设值也要 CRUD）。
  上一版的"弹头许可 + 目标区域"做法（2.24.0）属于为单一需求硬编码字段，本次整体删除，由变量系统替代。
- **新增 `variable` 包（纯逻辑 + 薄服务）**：
  - `VariableType`（`bool`/`number`/`text` 解析与**归一化**：布尔接受 `true/false/on/off/yes/no/1/0`；
    数值必须有限且整数写成 `1` 而非 `1.0`；文本去首尾空白、≤256 字符）。
  - `Variable`（`id/type/name/desc/value/presets[]`）与 `VariableScheme`（整套变量值的命名快照）。
  - `VariableRegistry`：`parse` **全量校验、任何一条非法即整体拒绝**（不做部分提交）；上限变量 128 / 方案 32 /
    每变量预设 32；id 规则 `[a-z0-9_.-]{1,64}`；`applyScheme` 只覆盖方案里列出的变量，未列出的保持原值（跳过项记 WARN）。
  - `VariableService`（`CCNRRPMod.variables`，`ServerAboutToStart` 构造 / `ServerStopping` 清空）：唯一读写入口，
    写入一律"读整份 → deepCopy → 改 → 全量校验 → `ConfigStore.save` → 换缓存"；**删除变量会自动摘掉所有方案里
    对它的引用**（否则整份配置会因悬挂引用被拒绝落盘，管理员会陷入"删不掉也改不了"的死局）。
- **对外只读取值接口（单向依赖：外部 → 本 mod）**：`raw(id,def)` / `bool(id,def)` / `number(id,def)` / `text(id,def)`；
  取不到或类型不符时返回调用方给的默认值，不抛异常；外部**只能读**，写入只经命令/面板（同一份校验）。
- **命令 `/rp var`（权限 `ccnrrp.admin.var`）**：`list` / `get <id>` / `info <id>` / `set <id> <值>` /
  `create <id> <bool|number|text> <值>` / `remove <id>` / `preset list|apply|set|remove` /
  `scheme list|apply|save|remove` / `reload`。
  **`get` 只发 `Component.literal(值)`**（无前缀、无翻译、无装饰），供命令方块与 RCON 直接消费；找不到变量则不输出且返回 0
  （要看元信息用 `info`）。`scheme save <id> [名称]` = 把当前所有变量值快照成一套方案，免去逐个填 `values`。
  补全：`RpSuggest.variables()/schemes()/presets(id)` 实时读镜像缓存。
- **管理面板「自定义设定」页签（替换原「拓展设定」页签）**：左侧列表（随子页签在"变量/预设方案"间切换，
  行文字 `名称 (id) = 当前值` 即**取值预览**）＋右侧编辑器三个子页签分段承载全部字段——
  「变量」（id/显示名/**类型点击循环** /当前值/说明 + 新建·保存·删除）、
  「预设值」（预设**胶囊点击即切换取值** + 预设 id/显示名/取值 + 新增·保存·删除）、
  「方案」（方案**胶囊点击即整套套用** + 方案 id/显示名 + 新建·保存·删除）。
  分三段而非纵向堆叠的原因：面板最小高度仅 340px，堆叠溢出可视区（docs/01 §10.1）。
  预设/方案胶囊悬停走白底反白（文字用 `ACCENT_TEXT`），遵守可读性硬约束。
- **删除区域系统**：`area` 包（`Area`/`AreaRegistry`/`AreaService`）、`/rp area`（含 `AreaCommand`）、
  `config/ccnr_rp/areas.json` 内嵌默认、`gui.admin.area.*` 语言键、`AreaRegistryTest`；
  `CharacterListS2C` 的 `areas` 投影、`ClientCharacterState.areas()` 镜像、影响预检的"弹头目标引用"行一并移除。
- **删除阵营弹头许可**：`warheadEnabled` / `warheadArea` 字段与 `FactionManager.warheadEnabled/warheadArea/setFactionWarhead`、
  `/rp faction warhead`（`command.usage.faction` 尾巴同步收缩）、阵营页签第三连开关（入场电影那行由三连退回两连，
  按钮宽 `bw2` 由 `(w-8)/3` 改 `(w-4)/2`）、`gui.admin.warhead.*` 语言键。
  **老 `factions.json` 里遗留的 `warheadEnabled`/`warheadArea` 键不再被读取**，也不做迁移，
  且单字段写不会清除它们（`FactionExtrasSaveTest` 已把"历史遗留键原样保留"断言下来）。
- **替换权限节点**：`ccnrrp.admin.area` → `ccnrrp.admin.var`（`CharacterService.crudNode` 按 `kind ∈ {var, scheme}` 选中；
  服务端权威判定，客户端说什么不算）。
- **测试**：新增 `VariableRegistryTest`（解析 / 类型归一化含 `1.0`→`1`、`on`→`true` / 非法 id·类型·数值·超长 /
  重复 id / 预设上限 / 方案引用不存在的变量 / 方案套用跳过未列出项 / roundtrip）；删掉 `AreaRegistryTest` 与
  `FactionExtrasSaveTest` 的弹头用例；`LangFileTest` 增加两条断言（自定义设定必需键在两个语言包中齐备 /
  已删功能的键不得残留）。`test -PrunTests` 213 用例 0 失败。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（`LangFileTest` zh_cn/en_us 键数一致）；版本号 **2.25.0**。
- 文档：新增 docs/17（自定义设定：数据模型 / 两级预设粒度 / 对外接口 / 命令 / 界面 / 取舍）；
  docs/16 删掉区域章节并更名为「玩家属性」（新增变更记录）；docs/00 包表与文件表、docs/01 权限节点、
  docs/02 阵营字段、docs/10 命令与配置文件表、docs/14 §7 追加 2.25.0 条目；README 同步。

## 2.24.1（界面整备：全量 GUI 样式收口到 RpTheme 令牌体系 + 修掉白底白字等可读性缺陷）

- **主题令牌收口（RpTheme v4.1）**：设计语言与调色板不变，把此前散落在各界面里的裸色值登记为语义令牌——
  底板明度阶梯（`SURFACE_CARD`/`SURFACE_CARD_DIM`/`SURFACE_ALERT`/`SURFACE_POPUP`/`SURFACE_CONTROL`/
  `_HOVER`/`_ON`/`_OFF`/`SURFACE_INSET`/`SURFACE_SUNKEN`/`SURFACE_DISC`/`SURFACE_SCREEN`）、遮罩
  （`SCRIM`/`SCRIM_LIGHT`/`VEIL`/`SHADOW`/`TRANSPARENT`/`BLACK`）、列表（`ROW_STRIPE`/`ROW_SEL`/`ROW_SEL_HOVER`/
  `ROW_IDLE`）、滚动条（`SCROLL_TRACK`/`SCROLL_TRACK_ACTIVE`/`TAB_TRACK`）、徽章槽位（`BADGE_DISC`/`BADGE_PUNCH`/
  `SLOT_BG`/`SLOT_PUNCH`）、立体预览全息层（`PREVIEW_*`）、文字补充（`TEXT_BRIGHT`/`TEXT_DISABLED`）、
  主操作填充（`ACCENT_FILL`/`ACCENT_FILL_HOVER`）、危险填充（`RED_BG_SOFT`/`RED_BG_HOVER`/`RED_BG_FILL`）、
  关系语义色（`NEUTRAL`/`HOSTILE` + `relationColor(String)`）。`com.ccnrcom.rp.client` 内的样式裸色值**清零**
  （仅余 `#RRGGBB` 解析用的 alpha 掩码）。
- **共享绘制入口（同类构件只有一种画法）**：新增 `listRow`（斑马纹+悬停，含鼠标命中重载）、`listPanel`、
  `sectionCard`、`listHeaderRule`、`hudCard`（浮层卡片，可指定底/边）、`accentBar`（左侧状态条）、
  `popupPanel`/`popupRow`/`popupRowText`（下拉与浮层）、`suggestionPopup`/`suggestionRow`（输入补全）、
  `controlBox`（输入/下拉控件框）、`modalScrim`/`lightScrim`（模态遮罩）。
- **列表行统一**：管理面板五个页签（经验规则/关系/阵营组/拓展设定/设置）与 K 面板此前各有 4 套列表行画法
  （有的无悬停、有的用白色条纹、有的整列同色）；现全部走 `RpTheme.listRow`——斑马纹 + 统一悬停高亮 +
  选中反白（`selectedBar` + `ACCENT_TEXT`）。
- **浮层统一**：部署横幅/击杀友好提示/招募悬浮卡片/事件横幅此前有的无描边、底色三种深浅不一；
  现统一 `hudCard`（近黑实心底 + 细灰边），警示类统一 `SURFACE_ALERT` 底 + 红系边 + 红色状态条。
  四个补全浮层（音乐/相机场景/限制目标/通用 id/事件）与三处页签阵营下拉此前各写一份同构绘制代码，
  现收敛到 `suggestionPopup`/`suggestionRow` 与 `popupPanel`/`popupRow`，候选行新增键盘当前项反白 + 鼠标悬停高亮。
- **控件统一**：`RpButton.draw` 增加悬停重载，次级按钮底色统一到 `SURFACE_CONTROL`；经验规则页签的
  「试算/保存/新增/删除/启停」与 K 面板确认框按钮改用同一配方；开关/分段控件/页签滚动轨道走令牌。
- **可读性修复（缺陷）**：K 面板部署确认「确认」按钮此前是**白底白字**（白底填充 + 白色文字，实测不可读）
  → 改走主按钮配方（白底 + `ACCENT_TEXT` 深色墨字）；职业列表的 `Lv x` / 在职上限标签同样改为
  「亮底深字 / 红底白字」；`StatusHud` 进度条百分比文字改 `ACCENT_TEXT`（亮色填充条上不再白字）。
- **语义一致性修复**：击杀者名字的「友好」关系色此前是亮灰，与关系图 / 关系页签 / 入场电影的
  「友好=蓝」不一致 → 统一走 `RpTheme.relationColor()`（中立白 / 敌对红 / 友好蓝），四处同源；
  关系页签与阵营组页签下拉底色的 v3 冷色残留（`0xF01B1E23`）、关系图节点遮罩（`0xA80E1014`）、
  经验 HUD 的异色红（`0xFFFF4C4C`）与玩家名牌的红（`0xFFFF5555`）一并归一到令牌。
  玩家名牌颜色常量改为引用 `RpTheme`（世界渲染取 RGB），`RadioPlayer`/`XpHudOverlay` 等白字改 `TEXT_PRIMARY`。
- **K 面板构成主义硬边化（仅 `CharacterManagementScreen`）**：只改这一个界面，管理面板 / HUD / 世界渲染均不动。
  - **删掉「糊掉的灰斑」**：预览区阵营水印此前用 `RpIcons.bigBadge(alpha=36)`——多层同心 alpha 圆在低透明度下
    糊成一团灰，观感像污渍而不是水印。改为硬边构成主义标记 `emblemWatermark`：双层 1px 细环 + 两段红色断弧
    （粗/细）+ 四向超出环外的刻度 + 中心十字 + 单扇区 45° 排线。
  - **全息底座由「柔光」改「工程投影」**：去掉宽幅 `fillGradient` 光柱与多层同心圆 → 2px 核心竖线 +
    三对虚线侧柱 + 顶部定位刻度 + 等距透视线与两条汇聚斜线 + 基线刻度尺 + 2px 硬环与八向刻度 + 左下红色断弧。
  - **档案卡改「机器读数」**：左缘 3px 结构红条 + 四角刻度 + 右下斜切楔形 + 底缘刻度尺；职业名改字距放大白字 +
    硬边标题线；`ID // FACTION`、`LV.REQ`、`PERSONNEL` 三行由裸灰字改为**内陷读数框**（左红条、右缘刻度、
    左右两端对齐；窄窗口先裁右侧字段再裁左段，避免关键读数被挤掉）；`LV.REQ` 左条按是否达标显示白/红。
  - **小节头带序号**：`01 PREVIEW` / `02 EQUIPMENT` / `03 PROFILE`——红底反白序号块 + 字距放大标题 +
    尾部细线（线首 3px 红），替代原来的暗灰 `EQUIPMENT` / `// PROFILE` 裸标签；宽度不足时自动省略序号或截断标题。
  - **装备轨改造**：贯穿导轨改为「细线 + 上下端帽 + 每槽刻度」，槽位与导轨用刻度连成一体；空槽改 45° 排线 +
    `--`，已装槽加四角刻度，武器槽右上角加红色楔形（不再只靠红框）；每槽前缀 `01..05` 条目编号（窄列自动省略）。
  - **实现边界**：新增原语全部是 `CharacterManagementScreen` 内的私有方法（`ringOutline`/`arcOutline`/`hatch`/
    `wedge`/`cornerTicks`/`tickScale`/`tracked`/`sectionHead`/`readout`/`splitPair`/`emblemWatermark`），
    **未改动 `RpTheme`/`RpIcons` 等共享文件**，因此不存在波及其它界面的可能；文案全部沿用既有语言包键
    （`term_id` 只按既有 `" // "` 分隔符拆左右两段，未增删任何 lang 键）；卡片高度 80px、装备行距、
    部署按钮位置等布局常量一律未动。
- **边界（不变的部分）**：只改视觉层——布局 / 几何 / 行为 / 配置 / 网络 / 存档 / 运行时逻辑均未改动；
  唯一保留的彩色仍是红色警戒与关系语义蓝（友好），其余全程黑白灰（docs/14 §6）。
- **颜色守护测试（新增 `RpThemeTest`，10 用例）**：docs/14 一直写着「CI 无颜色守护（无快照测试），一致性靠人工 review」，
  本次把设计契约变成可执行断言——反射遍历 `RpTheme` 全部颜色令牌，非中性灰的必须在「刻意保留清单」
  （红族 + 关系友好蓝 + 观察态弱灰青）内；反白填充 + `ACCENT_TEXT` 对比度 ≥ 4.5（WCAG AA）；
  浮层卡片比面板更不透明、内陷槽比卡片底更亮、列表悬停比常态亮、滚动条轨道按溢出分档；
  等级灰阶单调且越界钳制；关系三态两两不同、未知/空/null 回退中立；`alphaBlend` 保 RGB 只改 alpha；
  `tag`/`section` 标签格式。改色违反契约时 `build -PrunTests` 直接红。
- **顺手清理（零引用死代码 + 一处潜在 NPE）**：删除从未被引用的几何常量 `RpTheme.PAD`（`HEAD` 中同样无引用）；
  `RpTheme.statusColor(null)` 此前会抛 NPE（`switch` 直接作用在可空字符串上），改为按文档语义回退「观察态」弱灰。
- **边界（不变的部分）**：只改视觉层——布局 / 几何 / 行为 / 配置 / 网络 / 存档 / 运行时逻辑均未改动；
  唯一保留的彩色仍是红色警戒与关系语义蓝（友好），其余全程黑白灰（docs/14 §6）。
- 构建：`spotlessApply` / `build` / `test -PrunTests` 全绿（195 用例 0 失败）；版本号 **2.24.1**。
- 文档：docs/14 新增 §2.1 令牌全表、§5.4 整备清单、§6 边界与可读性约束/颜色守护更新、§7 验收清单追加 2.24.1 条目；
  docs/01 新增 §10.1 GUI 视觉约束（禁止裸色值 + 自检命令 + 亮底深字）；README 同步。

## 2.24.0（issue 收口：#1 死亡背包清理 + #2 玩家属性编辑器（含 FirstAid 解耦适配）+ #3 特殊按钮落到拓展设定）

- **死亡背包清理（issue #1 修复）**：新增 `DeathInventoryPolicy`（纯逻辑）+ `DeathDrops`（薄适配）。
  原版在三条路径上**不会**爆出背包：`keepInventory=true`、死亡瞬间处于旁观者模式
  （`ServerPlayer.die` 的 `if (!isSpectator()) dropAllDeathLoot` 直接跳过）、离线判死（没有原版掉落流程）；
  而"死亡即清空背包"过去只是 Corpse 模组的副作用（它 `removeDrops()` 把物品收进遗体），遗体 mod 一移除就失效。
  现在：**未装遗体模组时由本 mod 显式爆出并清空背包**（0-35 背包 + 36-39 护甲 + 40 副手；消失诅咒按原版销毁），
  在死亡事件内先于原版掉落执行 → 原版随后找不到物品（不重复掉落）；装了遗体模组仍由它收纳（本 mod 不插手）。
  管理员 `/rp kill`、`/rp retire` 保持原行为。
- **死亡记账范围修正（issue #1 第二症状）**：死亡退场从"仅在场（ALIVE）玩家"放宽到"有档案但当时不在场"
  （观察者 / 残留 DEAD / 征召兵）——这类死亡过去整段跳过，导致既不写复活冷却、也不记死亡地点，
  重生直接退回床边且不进观察流程。现在一律记账；死亡地点在**任何在线死亡**时记录，
  重生由 `onPlayerRespawn` 传回死亡地点并切旁观；地点缺失（死亡瞬间掉线、服务端重启清表）时也不再裸复活，
  而是强制进入观察流程 + 立即同步档案（K 面板部署入口可用）。
- **玩家属性编辑器（issue #2）**：新增 `attribute` 包——`AttributeProfile`（纯逻辑：解析/校验/结算，无 MC import）、
  `PlayerAttributeBridge`（薄适配，MC 类型只在此）、`AttributeService`（套用时机编排）。
  阵营配置新增可选 `attributes` 字段；管理面板「阵营」页签新增**属性**弹窗编辑器（行编辑 id/数值/运算 + 增删）；
  部署时在 `resetPlayerState` **之前**套用（改血量后出门仍是满状态），登录与配置保存即时重套，退场自动卸下。
  命令：`/rp attribute list|get|set|remove|clear`（权限 `ccnrrp.admin.attribute`）。
  - 属性 id 用**注册名**寻址：原版属性与任意 mod 属性走同一条路径，未注册 id 执行期跳过并 WARN
    （"装对应 mod 后生效"）——不写死任何 mod，这是"原版优先、mod 自然扩展"的解耦边界。
  - **FirstAid 适配**：FirstAid 把血量换成 8 个部位血量（原版 `generic.max_health` 仅用于显示钳制），
    因此"改血量"按倍率翻译为"缩放部位血量上限并补满"；适配层为私有内部类 + 纯反射 + `ModList` 探测，
    **不引入任何编译期依赖**（对齐 `CamSceneBridge`），未安装或反射失败只记一次 WARN 并保持 FirstAid 原血量。
- **特殊按钮 → 拓展设定（issue #3）**：阵营新增 `warheadEnabled`（阵营管理界面第三连开关「可否启动弹头」）
  与 `warheadArea` 目标区域；新增 `area` 包（`Area`/`AreaRegistry` 纯逻辑 + `AreaService`）与**拓展设定**页签，
  管理区域（id/名称/维度/两角点，退化区域与非法 id 拒绝、上限 64）；命令 `/rp area list|info|at|add|remove`
  （权限 `ccnrrp.admin.area`）。核弹**功能本体不属于本 mod**：本仓库只发布"哪个阵营有权、目标区域在哪、
  某点属于哪个区域"这些事实（配置文件 + `AreaService` 只读查询 + 单向依赖：外部 → 本 mod），执行由外部功能负责。
  按维护者定调，安全/突袭的"类原版按钮触发机制"**未实现**（等对局状态机完善后再评估）。
- 测试：新增 `DeathInventoryPolicyTest`（issue #1 回归：三条原版不掉路径 + 正常路径不重复处理）、
  `AttributeProfileTest`（正常/非法/边界/结算顺序/去重/上限/roundtrip）、`AreaRegistryTest`（归一化/维度/退化/上限）、
  `FactionExtrasSaveTest`（单字段接管不吞其它字段 + 非法拒绝不留半份改动）。LangFileTest zh/en 同步通过。
- 文档：新增 docs/16（属性与区域）；docs/00 包布局与文件表、docs/01 权限节点与弹窗表、docs/02 阵营字段、
  docs/05 死亡背包语义、docs/09 部署套用顺序、docs/14 验收清单同步。
- 构建：spotlessApply / build / test -PrunTests 全绿；版本号 **2.24.0**。

## 2.23.0（P15 补全：规则变更 action + switchPhase + 纯脚本波次 + /rp end 结局闭环 + 开场自动）

- **规则变更 action（`ruleChange`）**：新增 `RuleService`（纯逻辑，幕作用域状态层），事件/序列可发出
  `{"type":"ruleChange","rule":"..."}`。子类型：`limitProfessions`/`limitFactions`（本幕可部署/可征召职业、
  阵营，作用于 `SpawnFramework` 波次编制池与自部署校验）、`zoneToggle`（目标区开关）、`recruitMode`
  （本幕谁能收到邀请：SELF_DEPLOY/RESURRECTION/BOTH）、`buff`/`nerf`（对 target 即时施加药水效果）。
  规则以幕为作用域，幕切换时 `EventManager` 自动解除该幕规则（`onPhaseEnd`）；同幕冲突后者覆盖。
- **`switchPhase` 序列/事件步骤**：`{"type":"switchPhase","phase":"<id>"}`（空 phase = 下一幕），强制切幕并
  触发 `ON_PHASE_END/START` 各一次；`/rp phase set|advance` 同走该通道（先于推进发「第 0 幕开始」信号）。
- **阶段开始信号修复**：首幕 `ON_PHASE_START` 与首幕内嵌序列现在会在开演时触发（此前阶段 0 从不发开始信号）。
- **波次库纯脚本/命令召**：移除 `SpawnFramework` 队伍创建自动轮询（不再按 `teamIds` 新增 diff 自动召波），
  波次仅由事件 `spawnWave`、序列 `WAVE`、`/rp spawn trigger` 显式召。
- **结局闭环（`ending.json` + `/rp end`）**：新增 `EndingScript` 模型解析结局剧本（`animation`/`notify`/
  `resetToPhase`/`reward`，按模式路由 `modes/<id>/ending.json`）；`/rp end [reason]`（幂等：已结束不重复处理）
  播结局动画/通报、追加结算 XP、复位阶段回第 0 幕（或 `resetToPhase`）、清事件运行时并置为空窗期。
- **开场自动（场景内触发）**：事件可声明 `"start": true` 作为「开局事件」；定义了开局事件时剧本进入「待启」
  （`running=false`，阶段/事件不自动跑），仅开局事件触发器命中才开局；未定义开局事件则激活即开演（兼容旧行为）。
  `/rp end` 后空窗期内同样只由开局事件重启。
- **事件 `hooks` 兼容解析**：`startAnimation`/`spawnWave` 可在事件顶层或 `hooks` 段声明；事件自带
  `startAnimation` 优先于 `event_start` 钩子播放。
- **参考模式补齐结局**：`config/ccnr_rp/modes/{scpsl,tac_comp}/{ending.json}` 各写入结局剧本（复位到首幕 +
  全员 XP 结算 + `game_end` 动画序列），模式 `animations.json` 增补 `game_end` 序列与结尾 lang 键。
- 测试：新增 `RuleServiceTest`（幕作用域应用/解除/冲突覆盖）+ `EndingScriptTest`（解析与缺省）。
  LangFileTest zh/en 键同步通过。构建：spotlessApply / build / test -PrunTests 全绿。

## 2.22.0（多模式编排：模式文件夹化 + 热切换 + 条件驱动阶段）

- **多模式（P15）**：`config/ccnr_rp/modes.json` 登记模式并标记激活；每个模式一整套剧本配置
  （`modes/<modeId>/{phases,events,spawn_waves,animations}.json`）。新增 `ModeManager` 负责登记/激活/配置键路由；
  `/rp mode list|set <id>|clear`（权限 `ccnnrp.admin.mode`）。热切换 = 重读新模式剧本配置 + 重置剧本运行时
  （阶段回第 0 幕 / 事件/波次/动画重载），不重置玩家状态/档案/经验（公共部分全局共享）；模式未激活回退顶层剧本（兼容旧行为）。
- **阶段改事件/条件驱动**：阶段可声明 `advanceOn`（复用触发器：`CONDITION` 的 DEAD/ALIVE_COUNT、`ON_TIME`），命中即推进到下一幕；
  `durationMinutes` 仅在未声明 `advanceOn` 时生效（向后兼容）。`/rp phase set|advance` 仍可手动。
- **剧本配置按模式路由**：EventManager(phases/events)、SpawnFramework(waves)、AnimationEngine(animations) 读当前模式配置；
  模式激活时按 `modes/<id>/` 路由，未激活回退顶层。
- **参考模式写入模组配置目录**（不并入源码资源，随服务器实例运行）：`config/ccnr_rp` 下新增 `scpsl`
  （收容失效：警报→MTF 进场→封锁）与 `tac_comp`（战术团竞：准备→交火→结束）；波次以空 `teamIds`
  （不经队伍创建自动轮询，仅由事件 `spawnWave`/序列 `WAVE`/命令召）。
- 测试：新增 `ModeManagerTest`（登记解析）+ `PhaseClockTest` 条件驱动用例。构建：spotlessApply / build / test -PrunTests 全绿。

## 2.21.5（复活波已加入名单实时同步 + 名单立绘显示玩家本人皮肤）

- **背包「已加入玩家」名单实时刷新**：原背包右上角列表只显示「本玩家自己」已同意的邀请（本地 `OFFERS` 过滤），
  其他人加入时不变，表现为「不刷新」。现改为服务端权威的整波名单：`RecruitManager` 维护每个触发实例（groupId）的
  已加入名单，有人接受 / 离服（取消接受）/ 结算时向该波全部候选推送 `RecruitRosterS2C`（展示 id + 目标数 + 已加入条目），
  客户端 `RecruitOverlayHud` 按 `groupId` 存名单并在背包右上角绘制，头部显示「已加入 x / 需要 y」。
  名单按触发实例增量推送（不每 tick 全量广播）；结算 / 取消时推空名单清除，离服从广播目标移除后重推受影响组，
  客户端登出清空全部名单（对称清理）。
- **名单立绘显示对应玩家的本人皮肤**：`CharacterPreview.renderPlayerSkin`（新增）识别 `user-<uuid>` 取该玩家
  `PlayerInfo.getProfile()`（带 skin textures 的 GameProfile），渲染其本人外观 + 职位装备，不再清一色用本地玩家皮肤；
  非玩家身份（征召兵 UID）回退通用立绘。
- 网络：`RecruitOfferS2C` 增加 `groupId`（名单归属）；新增 `RecruitRosterS2C`（整波已加入名单整表推送）。
- 文档：docs/09 补充「已加入名单实时同步」说明。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.21.4（原版音乐和音效设置注入本 mod 音乐音量滑块）

- **在原版「音乐和音效设置」（SoundOptionsScreen）注入本 mod 音乐音量滑块**：独立于原版「音乐」音量，
  经 `ScreenEvent.Init.Post` 找到改屏幕的 `OptionsList` 追加一条 `addBig` 滑块（`ccnr_rp.audio.music_volume`）；
  拖动即写客户端配置 `config/ccnr_rp-client.toml` 的 `audio.musicVolume`（范围 0..1，默认 0.55 保持原响度），
  并实时更新正在播放的出场音乐增益（`ClientAudio.setVolume`）。
- **本 mod 音乐音量改由该独立滑块控制**：新增 `CCNRRPClientConfig`（CLIENT 配置）存储音量，出场音乐
  播放前在客户端主线程读取；替代 2.21.3 的「跟随原版音乐音量」方案（改为真正独立的音量控制）。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.21.3（列表表头布局统一 + 本 mod 音乐跟随原版音量）

- **统一修复管理面板列表表头与首行重叠**：阵营/职业/事件/阶段/波次/限制列表、阵营组、经验规则、关系管理等
  页签的列表表头原本画在 `listY1-4` 而首行从 `listY1` 开始，表头文字下沿会压到首行文字。现统一为「表头带」：
  表头占独立一行（表头下方加分隔线），行列表从表头带下方开始，滚动区/点击命中/滚动条一并下移，
  各页签列表表头不再与首行重叠。
- **本 mod 音乐音量跟随原版「音乐」音量**：出场音乐原先以固定 0.55 增益播放，不受原版音量控制；
  现改为在客户端主线程读取原版「音乐」音量（`SoundSource.MUSIC`），按 `0.55 × 音乐音量` 设定增益，
  玩家在「音乐和音效设置」里用「音乐」滑块即可调节本 mod 的出场音乐（0=静音，默认不变）。
  *（该方案已被 2.21.4 的独立音量滑块取代。）*
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.21.2（阵营组外显名称 + 页签排序）

- **阵营组「外显名称」可编辑**：`groups[]` 新增可选 `name` 字段（缺省回退 id，仅展示用，不参与 id 引用/关系解析）。
  「阵营组」页签新增「名称」输入框（列表按外显名称显示，id 与名称不同时并列展示）；服务端
  `FactionGroup`/`parse`/`createGroup`/`updateGroup`/`commitGroups` 全链路透传 `name` 并落盘；
  `buildSharedListRoot` 下发 `name` 供客户端镜像。入场电影「阵营关系」行按组合并时展示该外显名称。
- **管理面板页签排序**：将「阵营组」页签移到「阵营」旁（原在末尾）；同步重排 `TAB_*` 常量与 `TABS` 数组。
- 文档：docs/02 补充 `groups[].name` 说明。
- 构建：spotlessApply / build / test -PrunTests（新增 `CinematicRelationsTest` 外显名称用例）全绿。

## 2.21.1（入场电影阵营关系按组合并）

- **入场电影「阵营关系」行按「阵营组 + 关系」合并可合并项**：原来逐阵营罗列所有非中立关系（阵营多时一行过长）；
  现在同组所有非中立成员对玩家阵营关系一致时整组合并为一条「组名 + 关系」，组内关系混杂（敌对/友好并存）或无组的
  阵营仍逐一按阵营名显示。纯逻辑抽到 `CinematicRelations`（无 MC import），`SpawnFramework` 组装部署载荷时调用
  （服务端权威推导，客户端渲染不变）。
- 构建：spotlessApply / build / test -PrunTests（新增 `CinematicRelationsTest`）全绿。

## 2.21.0（去 Emoji + 国际化 + 阵营组编辑 + 入场电影可配置）

- **阵营图标改为弹窗选择**：原「循环切换」按钮在 rebuild 时被服务端快照覆盖（`syncFactionIconTier` 每帧同步 `iconIdx`）导致看似切不动；改为点击弹出图标选择界面（网格徽章 + 点选即设），并仅在 `selectFaction` 时同步图标/等级，保存不再被覆盖。图标多时网格带滚动条（可拖拽游标 / 滚轮滚动）。
- **移除阵营「等级」按钮**：删除阵营表单的等级循环按钮（`tier` 字段仍从选中阵营保留并按 `tierIdx` 保存）。
- **入场电影「简洁模式」图标左移缩小**：简洁模式下图标从顶端移到左下方、缩小，并与左对齐文字（标题/副标题）锚点对齐，文字右移避免重叠。
- **修复：阵营/事件表单「开关类按钮点不动」**：按钮的 `onPress` 翻转本地状态后调用 `rebuild()`，但 `rebuild()` 又会从服务端快照重新同步该状态（`syncFactionIconTier` 覆盖 `facCinBlack/facCinCompact`；`buildEventForm` 覆盖 `evState`），导致开关看似无响应。修复：入场电影开关仅在 `selectFaction` 时同步一次；事件 `enabled` 仅在 `selectEvent` 时同步一次；事件保存改用 `evState`（不再读服务端旧值）。
- **删 Emoji + 国际化（i18n）**：删除色彩 Emoji（`🔒` 等）与豆腐块类字形，保留终端单色字符（`✕`/`●`/`✓`/`✗`/`↑↓`）；所有客户端硬编码中文 UI 文案改为语言包键（`Component.translatable`），zh_cn / en_us 同步（LangFileTest 通过）。系统提示前缀 `[ 系统 ]` 改为可翻译键（zh=系统 / en=SYSTEM）。
- **阵营组编辑入口**：新增「阵营组」页签（TAB_GROUPS），列表 + 编辑器（id / 成员阵营逗号分隔 + 阵营下拉注入），新增/保存/删除走 `ManagerCrudC2S(kind="group")` → 服务端权威校验落盘。服务端 `buildSharedListRoot` 下发 `groups` 数组供客户端枚举。
- **入场全屏黑可开关**：阵营配置 `cinematicBlackScreen`（缺省 true）；关闭后入场电影只保留文字/图标，无全屏黑覆盖。
- **入场电影简洁模式（可配置）**：阵营配置 `cinematicCompact`（缺省 false）；打开后信息缩小移到左下方、靠左对齐（标题 2.0x / 副标题 0.9x），图标仍居于顶端。
- **管理面板阵营表单**：新增「入场全屏黑 / 简洁电影」per-faction 开关（保存进阵营定义）；`saveFaction`/`syncFactionIconTier` 同步。
- **阵营组页签交互对齐**：修复点击阵营组页签崩溃（`mouseClicked` 通用列表滚动条分支对自包含页签未排除 `TAB_GROUPS`，`rowHeight()` 返回 0 触发除零）；对齐 XP / 关系页签行为。
- **关系管理 — 阵营组智能缩短**：规则列表展示时若一组的成员恰好匹配已定义阵营组，自动折叠为 `group:{id}` 引用，避免罗列全部成员。
- **友好关系色改为蓝**：新增 `RpTheme.FRIENDLY`（蓝 #4FA6FF），用于关系类型标签 / 类型三选 / 关系测定图边与图例 / 入场电影盟友关系段，与中立白 / 敌对红明显区分（原用灰绿 `GREEN`，与中立几乎不可分）。
- 构建：spotlessApply / build / test -PrunTests（含 LangFileTest、ProjectMetadataTest）全绿。

## 2.20.3（恢复模组图标 + 修正 JAR 元数据描述）

- **恢复 `src/main/resources/icon.png`**：项目专属图标（CCNR-Com 笑脸 + 右下角蓝色 RP 标，128×128 RGBA），
  此前在 `ec391ae`「移除内嵌多媒体资源」中被一并移除；现从 git 历史 `46f9c01` 还原，供 JAR 卡牌 / 模组列表展示。
- **`mods.toml` 修正**：新增 `logoFile="icon.png"`；`description` 由过期的「当前仅为项目骨架，功能开发中」改为真实项目简介
  （量子科学设施 CCNR 机构世界观、八大系统、CCNR:NET 机密终端风格三栏界面），与 README / docs 一致（docs/01 §9.1 文档-代码-实际一致）。
- **`ProjectMetadataTest` 加固**：新增断言 `logoFile="icon.png"`、描述非过期文案；新增 `modIconBundled`（校验 `/icon.png` 存在且为有效 PNG 魔数）。
- 构建：spotlessApply / build / test -PrunTests（含 LangFileTest、ProjectMetadataTest）全绿。

## 2.20.2（K 面板 / 管理面板结构对齐参考界面：终端英文标签 + 分区布局）

- **K 面板（CharacterManagementScreen）结构与标签对齐 ccnr-rp-gui Terminal**（非仅配色，属格式/细节/布局层）：
  - 顶部栏：`[CCNR] TERMINAL` + `LV.x // STATUS: y` + `[ ADMIN ]` / `X`（原为图标 + 身份数据库 + Lv+状态）。
  - 列头：`[ NAV ]` / `[ POSITIONS ] [n]` / `[ DETAIL ]`（原为 阵营分组/职业列表(n)/详细资料）。
  - 导航项 `[ ALL ]`、`[ 阵营名 ]` 统一方括号格式。
  - 详情面板改为参考分区结构：`ID: x // FACTION: y`、`[ LV.REQ: x | CURRENT: y ]`、`PERSONNEL: a/b // FACTION: c/d`、`// PREVIEW`、`EQUIPMENT`、`// PROFILE`。
  - 部署按钮：`[ DEPLOY ]` / `[ REDEPLOY ]` / `[ REQ LV.x ]` / `[ FULL occ/lim ]`（原为「部署/重新部署/需要等级/该职业已满员…」）。
- **管理面板（RpAdminScreen）**：页签标签统一方括号 `[ 设置 ]`；标题加 `[CCNR] ` 前缀；页签盒加宽防括号溢出。
- **标签语言选择**：结构标签用参考的英文终端式（ID/FACTION/PREVIEW/EQUIPMENT/DEPLOY…），内容（职业名/阵营名/画像文本）仍用当前本地化文本；zh_cn 与 en_us 语言包键集一致（LangFileTest 通过）。
- **纯视觉层**：不改布局几何/行为/配置/网络/存档。构建：spotlessApply / build / test -PrunTests / LangFileTest 全绿。

## 2.20.1（终端容框完整视觉层：补齐参考设计语言）

- **补齐终端「完整视觉层」**（对齐 ccnr-rp-gui `App.css` 的 `.terminal-panel` / `body::before` / `::after` 装饰；v4 只搬了调色板，此为格式/细节层）：
  - 新增 `RpTheme.terminalFrame()`：素版面板（近黑底+灰描边）之上叠加**四角 L 型角标** + **顶部高光 rail**，用于顶层面板；内嵌弹窗/小卡片仍走 `terminalPanel`（无角标/网格）。
  - 新增 `RpTheme.gridOverlay()`：面板细灰网格叠层（对齐前端 `.terminal-panel::after`，「军用终端底格」质感）。
  - 接入此前**已定义但从未调用**的 `RpTheme.cornerBrackets()`。
  - `CharacterManagementScreen`（K 面板）与 `RpAdminScreen`（管理面板）顶层主面板改用 `terminalFrame` + `gridOverlay`，并在内容层之上（`super.render` 之后）叠加 `RpTheme.scanlines()` CRT 扫描线——此前仅 `FactionGraphScreen` 有扫描线。
- **纯视觉层**：不触碰布局/几何/行为/配置/网络/存档；扫描线/网格均为低透明度，保证文字可读性。
- 构建：spotlessApply / build / test -PrunTests / LangFileTest 全绿。

## 2.20.0（界面主题迁移：黑白灰军用终端）

- **界面主题 v4**：in-game UI 视觉统一为「黑白灰军用终端」，与 Web 管理面板（ccnr-rp-gui）对齐——近黑底 / 白·灰等宽文字 / 细灰边 / 白=强调（选中反白）/ 红=警示危险 / 灰阶徽章（机构等级）。替换原 v3「CCNR:NET 机密终端」（冷暗金属底 / 青色主色 / 金·蓝徽章）。
- **设计系统**：`RpTheme` 调色板 v3→v4（OVERLAY/BG/PANEL/BORDER/TEXT/CYAN/GREEN/GOLD/STATUS 全量中性化→白灰+红）；tierColor/statusColor/selectedBar(反白)/scanline(白) 同步；`RpButton` primary=白底黑字、secondary=暗底灰边白字、danger=红边红字；`RpIcons`/`RpScrollbar` 经 CYAN→白 自动跟随。
- **非中性色清理**：各屏幕散落的青 #45D8F2、蓝 #3D7BFF、金 #FFC84C、橙 #FF8C42、暗绿 #1F4D33、近黑蓝 #10181E/#15181E/#3A3F4A → 白/灰/红（RpAdminScreen / RpRulesTab / RpRelationTab / CharacterManagementScreen / StatusHud / XpHudOverlay / DeployNoticeBanner / RecruitOverlayHud / FactionGraphScreen / PlayerNametagRenderer / ClientPacketHandlers）；选中行文字白→深（`sel ? ACCENT_TEXT`）保证反白可读。
- **边界**：仅改视觉层（颜色/描边/选中态/文字层级），不触碰布局/几何/行为/配置/网络/存档。
- **文档**：新增 `docs/14-界面主题设计.md`（设计语言/令牌/前端映射/组件/边界/验收）；README、`RpTheme` Javadoc 同步为黑白灰军用终端。
- 构建：spotlessApply / build / test -PrunTests / LangFileTest 全绿。

## 2.19.5（数据库后端 Phase 5：全链路收尾）

- **命令补全**：新增 `/rp db connect (sqlite <file>|mysql <host,port,db,user,pass>)`（写 db.properties，重启生效）、`/rp db export <dir>`（库→本地备份：配置/调参/用户档案/素材）、`/rp db flush`（强制写后置落库）。zh/en 语言包键成对。
- **文档**：新增 `docs/12-数据库设计.md`（架构/表结构/配置档/命令/迁移/失败模式）；更新 `docs/00` §4 路径表、`README`、`AGENTS.md`（数据存储说明）。
- **测试**：新增 `ConfigStoreTest`（DB 路径往返 + 配置档切换隔离 + copyProfile）。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿；全部 DB 仓储以临时 SQLite 头less 往返验证（配置/用户/素材/调参/配置档）。
## 2.19.4（数据库后端 Phase 4：资源文件（音乐/图标）入库）

- **素材 BLOB 入库**：`assets(name,kind,data,size,sha256,updated_at)` 表 + `AssetRepository`（list/read/save/delete）。`AssetLibrary` 在 DB 启用时清单发自 `assets` 表、素材下发读 BLOB（`streamFromDb`）；`MusicStore` 上传/列表改走库（校验逻辑保留纯逻辑）。客户端 audio/ 本地缓存与 AssetManifest/AssetPart 网络包不变。
- **迁移**：`/rp db migrate` 新增导入 `config/ccnr_rp/audio/*.ogg` 与 `textures/*.png`（含 sha256，幂等）。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿；新增 `AssetRepositoryTest`（临时 SQLite 往返）。
## 2.19.3（数据库后端 Phase 3：serverconfig 调参入库）

- **调参入库**：`server_settings(profile,key,value,type)` 表 + `ServerSettingsStore`。`CCNRRPConfig.applyDbOverrides()` 于启动（各 manager 构造前）从当前配置档覆盖各 `ConfigValue` 运行时值；`set()` 于 DB 启用时写库（保留 Forge SPEC 为运行时持有者，避免与 ModConfig 机制冲突）。
- **迁移**：`/rp db migrate` 把当前（toml 种子）调参值写入 `server_settings`（幂等）。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿（新增 `ServerSettingsStore.enabled/save/loadAll`，CCNRRPConfig 拆分 applyValue 供复用）。
## 2.19.2（数据库后端 Phase 2：玩家/运行时数据规范化）

- **用户档案关系表**：`users` + `user_pending_xp`；新增 `UserRepository`（事务批量：users upsert + pendingXp 重建）。`UserService` 保持内存 Map 工作状态，DB 启用时 load 从库读回（库空回退磁盘文件，防误清空）、save 经仓储落库；对外 API 不变。
- **挂起通知/队伍触发**：`PendingNoticeStore`、`SpawnFramework.persistTeamState` 在 DB 启用时改存 `pending_notices` / `team_waves_done` 表（未启用回退磁盘）。
- **迁移**：`/rp db migrate` 现同时导入 config JSON + `user_profiles.json`（含 pendingXp）/ `pending_notices.json` / `team_wave_done.json`（`RuntimeMigrator`，行级幂等）。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿；新增 `UserRepositoryTest`（临时 SQLite 往返）。zh/en 语言包键成对。
## 2.19.1（数据库后端 Phase 1：配置文档入库 + 配置档）

- **配置持久化抽象**：新增 `com.ccnrcom.rp.data.ConfigStore`，把 config/ccnr_rp/*.json 的「读文件/原子写」抽象为「读配置档文档/upsert 配置文档」；DB 启用时源在 `config_documents(profile,config_key,json,updated_at)`，未启用回退磁盘（迁移期兼容）。
- **配置档**：`config_profiles` + `meta.active_profile`；新增 `/rp db profile list|create <id>|select <id>`（运行中热切换并重载全部管理模块、`broadcastConfigAll` 刷新客户端）与 `/rp db migrate`（本地配置 JSON 幂等迁入当前配置档）。
- **管理器切库**：`ConfigCrud` 与 FactionManager / ManagerSettings / EventManager / SpawnFramework / SequenceEngine / AnimationEngine / ExperienceService 的读写改经 ConfigStore；为 ExperienceService / AnimationEngine 补公开 `reload()`。
- **热重载钩子**：`ConfigReloader.reloadAll()` 统一调用各管理器 reload/load；`Database.connect()` 播种 `config_profiles`（ensureProfiles）。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿；FactionManagerSaveTest 适配（移除已删除的 file 字段注入）。zh/en 语言包键成对。
## 2.19.0（数据库后端 Phase 0：基础设施与依赖）

- **数据库后端接入（P0 打底）**：引入 SQLite（默认，内嵌，jarJar 打进 mod jar）与 MySQL（可选）JDBC 驱动；新增 `com.ccnrcom.rp.data` 包骨架。
- **核心类**：`DbConfig`（config/db.properties 读取/校验/密码掩码）、`DbType`、`SqlDialect`（占位符/upsert/标识符引用，SQLite 与 MySQL 差异化）、`DbSchema`（CREATE TABLE IF NOT EXISTS + schema_version，全部表跨库类型一致）、`Database`（connect/disconnect 生命周期、写后置单线程 executor、主线程读）、注解微 ORM（`@Table/@Id/@Column/@JsonColumn/@Blob` + `SqlMapper<T>`，仅支持 record）。
- **生命周期接线**：`CCNRRPMod` 于 ServerAboutToStart 连接数据库（各 manager 构造前）、ServerStopping 断开（对称清理）；未启用（db.enabled=false）时全部为空操作，不影响现有文件存储。
- **命令与权限**：新增 `/rp db status`、`/rp db test`（OP≥2 或 `ccnrrp.admin.db`），zh/en 语言包键成对。
- 构建：spotlessApply / build -PrunTests / LangFileTest 全绿；新增 SqlDialectTest / DbConfigTest / SqlMapperTest（内存 SQLite roundtrip）。
## 2.18.43（国际化文本整理：统一 CCNR 机构语境 + 术语对齐）

- **统一世界语境为「CCNR 机构」**：消除历史遗留的「项目/设施→机构」不一致（含 `RpTheme` 主题注释 SCP:NET → CCNR:NET）。
- **入场电影副标题迁入语言包并改标签**：硬编码中文「项目名字/项目阵营/项目简历」→ lang 键（`ccnr_rp.cinematic.*`），标签改为成员姓名/所属阵营/阵营关系/职业画像（与原绑定语义对齐：首行实为玩家名）。
- **职业/职位统一为「职业」**：`职位列表`→`职业列表`、`职位画像`→`职业画像`、部署/等级提示等 7 处职位→职业，zh/en 同步（en Position→Profession）。
- **导航头对齐**：`团队分类`→`阵营分组`（TEAMS→FACTION GROUPS，填的是阵营）。
- **状态三态对齐**：zh/en 状态词（在场/ACTIVE、阴间/DEAD、观察/OBSERVING；系统 存活/已死亡/观察中）确认无跨语言错位，维持现状。
- 构建：spotlessApply / build / test -PrunTests 全绿（含 LangFileTest 键集一致性、键值守护）。

## 2.18.42（战术预览区全息化：投影底座 + 装备负载轨）

- **K 面板「详细资料」战术预览区视觉升级**（纯绘制层增强，不改模型渲染/装备读取/悬停词条逻辑）：
- **A 档·全息投影底座**：人物背后一条青光柱 + 脚下透视地格 + 发光底座圆环，立绘呈现悬浮全息投影质感。
- **B 档·装备负载轨**：五槽改为终端式 HUD 卡片，按状态着色（空槽暗灰 / 已装备青 / 武器红），左缘贯穿连接线 + 已装备辉光 + 顶部门闩色条，武器槽一眼可辨。
- 负责方法：`CharacterManagementScreen` 新增 `renderHoloBase`，升级 `renderEquipList`；均复用 `RpTheme`/`RpRoundRect`/`RpIcons` 现成原语，无每帧分配。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.18.41（缩短入场电影：开场黑屏提速 + 打完即淡出）

- **缩短部署入场电影时间轴**：开场全屏黑停留 1s → 0.5s；移除打字动画完成后额外的 3s 停顿（`T_AFTER_ALL`），打字一结束即开始黑屏渐退，整体收尾更快。
- 改动：`CinematicController` 时间轴常量（`T_BLACK_HOLD=1000→500`、删除 `T_AFTER_ALL`），同步更新类 Javadoc 时间轴描述；影响面不变。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.18.40（修复人物3D预览模型扭曲/抽搐）

- **修复 K 面板「详细资料」人物 3D 预览与招募立绘模型扭曲/抽搐**：`CharacterPreview.render` 与 `renderPortrait` 把绘制中心 `cx/cy`（几百像素的大数）当作「鼠标相对模型锚点的像素增量」传入原版 `InventoryScreen.renderEntityInInventoryFollowsMouse`，该方法按 `atan(v/40)` 求角并乘 20°/40° 写入 yaw/pitch，导致模型被放大成近 90° 的俯仰 + 任意 yaw，缩成一团/间续抽搐。
- 修正：锁定正面视角应传 **0 增量**（`0.0F, 0.0F`），即原版「鼠标居中」的默认正面姿态；同步修正类注释与两个方法的误导性注释。
- 涉及界面：K 面板人物立绘主预览、招募卡片/已同意列表立绘（`RecruitOverlayHud` / `RecruitPopupScreen` 复用同一 `renderPortrait`）。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.18.39（部署重设角色状态）

- **部署前重设角色状态**：统一部署磨子（普通/征召/重新部署）在清空背包的同时新增 `resetPlayerState`——
  生命回满、饱食度/饱和度/消耗回满、清空全部药水效果、灭火、清坠落距离、补满空气、清除吸收值，
  防止死亡/观察期间遗留的状态带进新岗位。
- docs/09 部署链路描述同步更新。
- 构建：spotlessApply / build / test -PrunTests 全绿。

## 2.18.38（保存装备二次确认弹窗）

- **管理面板「保存装备」加二次确认**：点击后先弹确认框（隐藏下层界面、Esc=取消不发包、点「确认保存」才发
  `AdminProfessionSaveFullC2S`），提示将用当前背包/护甲/副手（含 NBT）覆盖目标职业装备、立即同步全服且无法撤销。
- 弹窗行为对齐 docs/01 §10「GUI 弹出窗口行为约束」：影响确认同款手动绘制模式，新增 `saveLoadoutOpen` 句柄
  （modal 判定 / Esc 拦截 / 无 widget 无需清理），登记表同步补行；语言包 zh/en 新增 `ccnr_rp.gui.admin.save_loadout.*`。
- 构建：spotlessApply / build / test -PrunTests 全绿（含 LangFileTest、ProjectMetadataTest）。

## 2.18.37（文档：经验教训沉淀 + 工程规范提炼）

- **docs/11 开发经验教训**：新增 §10「配置字段投影与编辑者同步」——「保存后看不到」的诊断顺序
  （先验证服务端落盘、再查客户端镜像投影，职业 spawn 漏投影案例）、编辑者同步刷新 vs 其余玩家异步广播的设计取舍。
- **docs/01 工程规范**：新增 §11「配置保存与客户端同步规范」——多字段实体保存入口的字段职责
  （表单缺省继承 / 单字段操作 deepCopy 单字段接管 / 禁止空串全量 upsert 吞字段 / create 防误覆盖）、
  配置变更后编辑者立即同步全量刷新 + 其余玩家异步广播、客户端镜像投影随配置字段同步补全、诊断顺序。
- 纯文档改动（docs + 版本号 + CHANGELOG），无代码变更。

## 2.18.36（职业部署点回显修复 + 编辑者保存后立即全量刷新）

- **修复「职业部署点无法保存」（实为无法回显）**：服务端 setProfessionSpawn 落盘正常，但 sendList 下发的
  职业 JSON 缺 `spawn` 字段（阵营有、职业漏了）→ 客户端镜像无职业部署点 → 复活点弹窗永远显示空，
  保存后重开也看不到点，表现为「无法保存」。现补 `professionSpawnJson`，职业 `spawn` 随全量列表下发，
  弹窗正确回显已保存的点。
- **编辑者保存后立即同步全量刷新**：所有配置保存入口（CRUD/设置/serverconfig/部署点/装备/关系）改为
  `broadcastConfigAll(player)` —— 编辑者（有编辑权限的管理员）**立即同步拿到全量数据**
  （sendList+sendManagerState，所见即所得，不依赖异步广播/不被 last-wins 合并），其余玩家走异步广播
  （跳过编辑者避免重复）。
- 测试：FactionManagerSaveTest 新增 setProfessionSpawn 落盘保留其它字段路径。
- 构建：spotlessApply / build -PrunTests / test -PrunTests 全绿。

## 2.18.35（保存装备不再吞基础配置 + 复活点管理传送按钮）

- **保存装备（loadout）单字段接管**：`/rp profession save` 与管理面板「保存装备」此前走全量 upsert（空串覆盖
  music/profile/cmdcamScene、false 覆盖 radioDisabled），**每次保存装备都会把音乐、项目简历、CMDCam 场景、
  无线电禁用开关吞掉**。现新增 `FactionManager.setProfessionLoadout`：只替换职业的 `loadout` 字段，
  其余基础配置原样保留（与部署点管理同款「单字段全量接管」）；`/rp profession create` 增加已存在校验，
  防对已有职业的误覆盖。
- **复活点/部署点管理每行「传送」按钮**：弹窗内每个出生点行新增「传送」按钮，点击直接传送到该坐标
  （按维度解析 Level 跨维传送），便于管理员就地检查部署点/复活点。
- 新增 `AdminTeleportC2S` 包 + 服务端 `onAdminTeleport`（权限校验后传送）。
- 测试：FactionManagerSaveTest 新增 setProfessionLoadout 保留基础字段 / 未知 id 拒绝两路径。
- 构建：spotlessApply / build -PrunTests / test -PrunTests 全绿。

## 2.18.34（人物 3D 立绘/预览 XYZ 锁定：移除鼠标追踪）

- **所有人物立体展示取消鼠标追踪**：K 面板 3D 预览、招募弹窗立绘、右侧悬浮招募卡与已同意列表的立绘
  全部 XYZ 锁定为正面视角（原版 `renderEntityInInventoryFollowsMouse` 鼠标参数传绘制中心 → 旋转角为 0），
  不再随鼠标位置旋转/倾斜。
- `CharacterPreview.render` / `renderPortrait` 签名删除 mouseX/mouseY 参数（不再有“跟随鼠标”接口），
  4 处调用点同步清理；注释同步更新。
- 构建：spotlessApply / build -PrunTests / test -PrunTests 全绿。

## 2.18.33（配置变更全服广播异步化：所有配置数据即时同步全员，多人不卡服）

- **广播异步化（核心）**：管理端 CRUD/设置变更后的全服同步不再在主线程逐玩家构建大 JSON 与读配置文件——
  改为「主线程快照 → 后台线程构建纯数据（各配置文件只读一次）→ 回主线程发包」三段式
  （复用头顶标签异步广播同款模式，遵守 docs/01 §9.4 线程纪律：后台只碰纯数据、发包回主线程）。
- **配置数据全量覆盖**：阵营/职业/事件/阶段/波/限制/无线电/设置（settings.json 与 serverconfig）本就广播全员；
  本次补齐三处缺口——部署点（阵营/职业）、职业装备 loadout 保存、serverconfig 变更原先只同步操作者或不同步，
  现全部并入全服异步广播；关系规则编辑（此前只操作者回拉）现成功落盘后同样全服广播。
- **性能收益**：N 人服配置变更时，配置文件读取由 N×4 次降为各 1 次，逐玩家 JSON 构建/序列化移出主线程；
  头顶标签 payload 由每玩家构建一次改为每广播一次。连续快速变更自动合并为最新一次（overload 保护）。
- **生命周期**：新增 `CharacterService.shutdownBroadcaster()`，ServerStopping 关闭广播线程（docs/01 §9.4 对称清理）。
- 验证：spotlessApply / build -PrunTests / test -PrunTests 全绿；多人在线（Dedicated Server）运行路径
  未在本环境实机验证，需进服多人场景确认（见 docs/01 §9.6）。

## 2.18.32（修复：管理面板职业/阵营表单保存清空其他数据）

- **职业表单保存清空装备（loadout）/ 无线电禁用 / 自部署开关**：管理面板「职业」表单只含基础字段，
  服务端 onManagerCrud 把 `loadout` 恒传 `null`（upsert 落空装备）、`selfDeploy`/`radioDisabled` 恒传 `false`，
  每次点保存都会把职业的**战术装备、无线电禁用开关、自部署开关**重置为空——正是「保存后其他数据全部清空」。
  现改为 `FactionProfessions.resolveSave(payload, existing)`：表单未携带的字段从现有定义**继承**，
  保存 = 表单输出覆盖到原数据上，不再整条重建（新建职业仍落空装备、开关默认关）。
- **阵营表单图标/等级同步加固**：表单重建（数据刷新广播后）也会从选中阵营同步图标/等级，
  不再依赖点选时机；阵营的自定义图标（`img:` 素材未同步到可选列表）保存时**保留原值**，不再被默认图标覆盖。
  阵营保存链路（updateFaction = deepCopy + 仅覆盖表单 8 字段）本已保留 radio/spawn/professions，补测试固化。
- 测试：FactionProfessionsTest 新增 resolveSave 继承/新建两路径 + 端到端 upsert 保留验证；
  新增 FactionManagerSaveTest（updateFaction 保留 radio/spawn/professions）。
- 构建：spotlessApply / build -PrunTests / test -PrunTests 全绿。

## 2.18.31（职业简历（profile）展示：K 面板 + 部署入场电影 + 招募弹窗）

- **K 面板（职位选择终端）**：右栏在「战术装备预览」之下、部署按钮之上直接展示该职位的**项目简历**
  （职业 profile，可选字段；未配置简历的职位不显示、不占位，最多 4 行超长裁剪，窗口过矮时自动减行）。
- **部署入场电影**：副标题「项目简历」行改为读取**职业 profile**（优先于角色背景，与 v1.2.0 文档语义一致）——
  此前部署路径背景恒为空导致该行实际空白；未配置简历的职位不再显示空「项目简历：」行。
- **招募弹窗（RecruitPopupScreen）**：邀请卡片在类型描述下追加职业简历（最多 2 行超长裁剪）；卡片加高、
  弹窗窗口同步加高，仍可容纳 3 条邀请；右侧悬浮 HUD 卡片不展示（按需求）。
- 客户端新增 `ClientCharacterState.professionProfile()` 读取助手（K 面板/招募弹窗共用）；
  新增 `ClientCharacterStateTest`（存在/缺失/空串/未知职业 4 路径）；zh/en 新增 `ccnr_rp.gui.character.profile`。
- 构建：spotlessApply / build -PrunTests / test -PrunTests 全绿。

## 2.18.30（修复：K 面板标红判定对齐部署按钮——含阵营维度与 GLOBAL 兜底）

- **标红判定与部署按钮一致**：此前职业行标红只判职业维度上限，阵营已达上限部署被禁但行不红；
  现改为职业或阵营任一满即标红，且在场换岗时在职数按「不含本人」计算（与部署按钮同条件），
  左栏阵营行判定（全部职业均无余额）随之一致。
- **右栏详情一致化**：详情「在职…」行改用同一套不含本人的计数做红绿判定（顺带修正本人在该阵营
  且阵营恰满时换岗仍可部署却误标红的问题）。
- **GLOBAL 兜底显式化**：职业维度上限 professionLimit() 本就含 GLOBAL 兜底（未配置专属规则的职业
  按 GLOBAL 计），标红/部署按钮/服务端校验四处一致，注释显式说明防误判。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.29（部署限制：上限 0=禁止部署；K 面板职业/阵营无余额标红）

- **上限 0 = 禁止部署**：限制编辑器标签由「0=不限」更正为「0=禁止部署」（不限=不配置该规则）；
  服务端/客户端逻辑本就按「在职 ≥ 上限拒绝」，0 在职也命中，仅文案误导，本次连同文档（docs/09 §4.0）
  与回归测试（DeployLimitsTest.zeroLimitForbidsDeployment：职业/阵营/GLOBAL 三类 0 上限均拒绝）一并固化。
- **K 面板标红**：中栏职位行无可用部署余额（在职 ≥ 上限或上限=0 禁止）整行标红（红描边+暗红底+红字）；
  左侧阵营行在该阵营全部职业均无可用余额（含无职业）时标红，提示该阵营当前无法复活/部署。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.28（修复：阵营表单保存选中自定义图标时数组越界崩溃）

- **修复保存阵营崩溃**：阵营表单保存时用固定长度内置图标数组 `ICONS[iconIdx]` 取值，而图标选择
  循环/定位按「内置 + 服务器素材库自定义图标」全量列表（iconOptions()），选中自定义图标（索引
  ≥8）后点保存即抛 ArrayIndexOutOfBoundsException；现改为与选择器一致使用 `iconOptions().get(iconIdx)`，
  并正确保留自定义图标值。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.27（无线电管理：句子行内编辑 + 拖拽调序 + 新句子追加尾部）

- **句子行内直接编辑**：无线电管理弹窗句子列表每行内置「文本 + 停留秒数」输入框，点哪行改哪行，
  移除原底部编辑区；输入框稳定复用（仅可见范围变化时重建），焦点与已输入内容不丢。
- **拖拽调序**：按住行首「≡」手柄上下拖动即可实时调换句子顺序（mouseDragged 移动行，松开结束）。
- **新建追加尾部**：「+ 添加句子」仍在列表尾部新增空句（维持原行为），提示文案同步更新。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.26（修复：玩家头顶悬浮标签整体上移一行，改为「阵营图标+职业名」一行 + 血量/血量上限）

- **悬浮标签整体上移一个文本行高**：原版自带玩家名字牌，本标签上移一行避免与系统名称标签重叠；
  同时删除冗余的玩家名行（系统已显示）与等级行。
- **阵营图标在左、职业名在右同一行**：徽章与职业名水平排成一行、整行居中，共用一条半透明底衬
  （badgeSize=0 时仍仅显示职业名居中）；血量读取实体同步数据 getHealth()/getMaxHealth() 仅本地展示。
- 构建：spotlessApply / build -PrunTests 全绿。

- **修复无线电管理弹窗输入框失效**：弹窗输入框（说话人 / 每句 text / wait）此前每帧销毁重建
  （removeWidget + mkBox），导致屏幕焦点每帧被清空、输入无法路由，且重建的输入框会重置为旧值；
  现改为打开时创建、跨帧稳定复用（仅编辑行范围变化/关闭时重建），焦点与已输入内容不再丢失。
- **保存前先回收输入框内容**：radioModalClick 顶部统一 collectRadioFields() 把输入框当前值刷回
  radioLines/radioWaits/radioSpeaker，再做增删行/保存等结构操作，避免丢输入；修正输入框点击焦点
  处理（去掉重复 mouseClicked 调用）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.24（弹出窗口统一行为对齐 + 工程规范 §10）

- **影响确认弹窗 Esc 返回上层**：CRUD 影响确认弹窗（impactOpen）按 Esc 时取消（等同「否」，不执行 CRUD）
  并返回表单，不再关闭整个管理面板。
- **全部弹出窗口行为对齐**：管理面板 4 类弹窗（影响确认/部署点/无线电/行为序列）统一满足——打开时隐藏下层界面、
  Esc 返回上层、关闭时清理专属输入框 widget；关系测定图（FactionGraphScreen）与管理面板保留 parent 并在关闭时返回上层。
- **工程规范**：docs/01 新增 §10「GUI 弹出窗口行为约束」，固化三条行为条件 + 弹窗句柄对照表。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.23（管理面板按钮压缩 + 无线电弹窗 Esc + 击杀提示详情与点击复制）

- **管理面板按钮压缩**：管理面板各页签（设置/职业/阵营/事件/阶段/波次/限制）整行"长按钮"压成一行
  等宽短按钮并排——新增记录 `ActButton` 与 `buttonRow()`；职业（无线电/刷给自己/保存装备/职业复活点）、
  阵营（无线电/管理部署点）、事件（启用/行为序列/触发事件）、限制（说明/清空全部限制）、
  波次（行为序列/召唤复活波）等改为并排短按钮，减少纵向占用。
- **无线电弹窗 Esc 返回上层**：无线电管理弹窗打开时按 Esc 仅关闭弹窗、返回表单（与行为序列弹窗一致），
  不再直接关闭整个管理面板。
- **弹窗隐藏下层界面**：无线电管理弹窗打开时纳入 modal 判定，隐藏下层管理器界面（与部署点/职业复活点/行为序列弹窗一致），弹窗为最上层、下层不可交互；Esc/取消/保存统一走 closeRadioModal() 清理弹窗专属输入框 widget。
- **部署点/职业复活点弹窗 Esc 返回上层**：管理部署点（阵营）/职业复活点弹窗打开时按 Esc 仅关闭弹窗、返回表单，不再关闭整个管理面板。
- **死亡通知阵营后追加职业名**：死者聊天击杀提示在击杀者阵营之后追加职业名（同一阵营色）；
  `DeathNoticeS2C` 新增 `killerProfessionId`，服务端填充、客户端渲染。
- **击杀队友红字显示详情**：击杀者聊天红字由「你击杀了队友！」扩展为「你击杀了队友！玩家名（阵营 · 职业）」，
  阵营/职业按阵营色着色。
- **击杀提示可点击复制**：死亡通知与击杀队友红字整条消息可点击复制到剪贴板（COPY_TO_CLIPBOARD，含环境伤害死亡消息）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.22（无线电管理：阵营/职业入场无线电，action bar 打字机逐句播放）

- **无线电管理（阵营 + 职业）**：管理面板阵营/职业表单新增「无线电管理…」弹窗——配置说话人
  （默认「指挥官」，按阵营颜色渲染）+ 多句无线电文本，每句可配打字完成后停留秒数（wait，停留期间
  上一句保持完整显示）。
- **入场播放**：部署入场动画播完（CinematicS2C 载荷带 radio 字段）后，客户端 action bar 打字机
  逐句展示「说话人：内容」；全部播完自动消失；登出/重复部署清理。
- **优先级**：职业无线电 > 阵营无线电；职业新增「禁用无线电」开关（radioDisabled=true 时该职业
  不播任何无线电，含阵营默认）。
- 服务端：factions.json 阵营/职业 radio 字段读写（FactionProfessions/FactionManager）+ 部署时
  解析注入阵营色；管理面板 CRUD 新增 radio-faction/radio-profession 操作；sendList 下发 radio 配置。
- 测试：FactionProfessionsTest 新增无线电 upsert/回读/清空/禁用用例。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.21（队友击杀聊天红字 + 死亡通知：死者聊天显示被谁以什么击杀）

- **队友击杀聊天红字**：击杀者击杀友好阵营玩家时，击杀者本地聊天红字提示「你击杀了队友！」
  （原有左下角 toast 保留）。
- **死亡通知**：角色死亡时，死者本地聊天显示被谁以什么击杀——击杀者阵营名（按阵营颜色）、
  击杀者名字（按关系着色：友好=绿/敌对=红/中立=白）、武器名；环境伤害（摔落/岩浆等）显示本地化
  死亡消息；若被队友击杀追加红字「你被队友杀死了，如有疑问向管理员举报」。
- 新增 S2C 包 DeathNoticeS2C（死者定向，击杀者名/阵营/关系/武器/环境伤害源）；zh/en 语言包新增 4 键。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.20（击杀友好提示挪至聊天区上方 + 头顶标记仅旁观者渲染）

- **击杀友好提示位置改为聊天区上方**：锚点 = 聊天区底部 + 聊天区高度 + 可配置偏移
  （serverconfig kill.noticeOffset，服务端权威下发，客户端遵从），提示始终贴在聊天区上方、
  不再与聊天框重叠；ChatScreen 打开时聊天区变高，提示自动随之上移。
- **头顶人物标记仅旁观者渲染**：PlayerNametagRenderer 客户端渲染加游戏模式判断——
  当前玩家为旁观者（观察视角）时渲染其他玩家头顶标记，其余模式（在场/普通玩家）不渲染，避免信息暴露。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.19（击杀友好提示：聊天界面之上重绘，防 ChatScreen 遮挡）

- **修复击杀友好提示被聊天框遮挡**：ChatScreen 是 Screen，渲染在 HUD 覆盖层之上，左下角聊天历史面板
  会盖住左下角的提示；现在聊天界面打开时（ScreenEvent.Render.Post）把提示重绘在聊天框之上，
  与 HUD 覆盖层共同渲染（内部按显示时间门控，不重复出现）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.18（击杀友好提示 + 击杀事件广播关系参数）

- **击杀友好提示**：玩家击杀友好阵营玩家时，左下角弹出提示（约 6 秒），展示被击杀者的阵营、职业、玩家名与玩家 UUID；
  服务端权威判定（FactionGraph.resolve == FRIENDLY 且双方有档案/阵营）后定向发包 KillFriendlyNoticeS2C；
  可配置开关 serverconfig kill.friendlyNotice（默认 true，关闭不计算不发包）。
- **击杀事件广播拓展**：character_kill 事件新增 victimRelation 参数（击杀者↔被击杀者阵营关系：hostile/neutral/friendly，
  任一方无阵营/未知为空串），供经验规则表达式消费；ExperienceEventRegistry 参数表与 docs/06 同步。
- 新增客户端 HUD：KillFriendlyNoticeHud（左下角 toast，登出清理）；zh/en 语言包新增 3 键。
- 测试：新增 KillRelationTest（关系解析 5 例）+ ExperienceEventRegistryTest 补 victimRelation 断言；LangFileTest 守护键集一致。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.17（精简默认配置为样板）

- **默认配置精简为样板**：factions.json 由 8 阵营/28 职业 → **3 阵营/4 职业**（行政总部/麦迪逊/QDF，图标改用内置向量 shield/claw/cross，不再引用已删除的内嵌图片）+ 1 组 + 2 关系；
  phases.json → 2 阶段（prep/danger）；events.json → 1 事件（evac_alert）；animations.json → 3 序列（spawn_intro/player_death/event_start_alarm）；
  spawn_waves.json 保持 2 波。默认配置总大小 ~16KB → ~4.9KB。
- 修复原默认配置悬空引用：evac_alert 的 hooks.spawnWave 原指向不存在的 wave_qdf_reinforce → 改为 general_reinforce。
- 阵营图标方案同步：默认配置 img: 引用全部移除（上一版已删内嵌图片），改用向量图形；docs 同步样例数量说明。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.16（移除内嵌多媒体资源，jar 瘦身）

- **删除打包进 jar 的多媒体资源**：7 张阵营图标（textures/faction/*.png）、界面背景 logo（textures/gui/bg_logo.png，代码无引用）、
  模组图标（icon.png）——共约 452KB，jar 由 ~1.0MB 降至 ~0.55MB。
- 阵营图标改为完全由服务器素材库下发（config/ccnr_rp/textures/，客户端进服自动缓存），移除代码中的内嵌回退分支：
  AssetLibrary 内嵌默认图标拷贝、RpIcons/PlayerNametagRenderer 的 jar 内嵌回退、RpAdminScreen 写死的 img:admin_hq/img:madison 预设；
  mods.toml 移除 logoFile（模组图标）。
- docs/10 同步：img: 徽章未上传/未配置时仅显示底色徽章盘（无内嵌回退）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.15（开发文档：管理面板 GUI / 载荷链路 / 发布流程经验沉淀）

- docs/11 新增「管理面板 GUI / 载荷链路 / 发布流程经验」章节：clearWidgets 控件生命周期陷阱、
  嵌套载荷必须解包、自定义弹层命中/绘制顺序、文本像素裁剪与贪心换行、按选中原始规则定位的编辑语义、
  版本号 + CHANGELOG + 提交发布流程约定。
- 纯文档改动，无功能变化；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.14（关系测定图悬停高亮 + 入场电影副标题自动换行与关系分色）

- **关系测定图悬停高亮**：鼠标放在某阵营图标上时，保留该阵营 + 其连线 + 直接相连阵营
  （连线/图标），其余连线和图标全部变暗（连线低透明度、图标深色罩 + 标签变灰），便于聚焦单个阵营；
  提示文案同步更新（zh/en）。
- **入场电影副标题自动换行**：四行副标题（含阵营关系）按像素宽度贪心换行，屏幕宽度不足时自动断行，
  打字动画跨行连续推进、后续行按总行数堆叠不重叠。
- **阵营关系按类型着色**：入场电影「阵营关系」条目按关系类型上色（敌对红 / 友好绿 / 中立白，
  与关系图配色一致），换行后逐段正确着色。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.13（关系管理页签 from/to 新增阵营下拉 + 注入按钮）

- from/to 输入框上方各新增**阵营下拉框 + 注入按钮**：下拉选择阵营（显示「名字(id)」），
  点「注入」把该阵营 id 去重追加到对应输入框的逗号列表（便于从现有阵营快速组规则）。
- 下拉弹层最多显示 8 项、弹层内滚轮滚动；弹层命中先于 widget 分发（可盖住输入框），
  弹层绘制置顶于 widget 之上，点弹层外自动收起。
- zh/en 新增 relation.inject / relation.pick_faction 键；docs/02 §4.5 同步。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.12（修复关系管理页签：布局重叠、无法编辑、保存/删除定位错误、服务端必现报错）

- **修复 GUI 重叠混乱**：关系页签内容区起点与其它页签对齐（避开页签栏/分隔线），
  列表补标准面板边框与标题，长规则名按宽度裁剪不再溢出；from/to 标签与输入框重排。
- **修复无法编辑**：rebuild 会先清空全部控件，关系页签的 from/to 输入框现每次重建都重新注册
  （此前首次构造后即从控件列表消失，不渲染也不接收输入）；数据刷新时保留已选规则的编辑内容。
- **修复保存/删除操作逻辑**：保存携带选中规则的原始 from/to（original），服务端按此原位替换/删除，
  修改 from/to 不再误增新规则；删除针对选中规则本身而非输入框当前内容。
- **修复「from/to 不能为空」必现报错**：RelationEditC2S 载荷为 {action, rule{from,to,type}, original?}，
  服务端此前把整个请求当 rule 传入 CRUD（读顶层 from = 空），新增/保存/删除一律失败；
  现正确解包嵌套 rule（带缺省防护），无 original 的旧载荷回退按新值 upsert。
- 客户端新增 from 空值本地校验（明确提示，不再发空载荷）；zh/en 同步新增 from_required 键。
- docs/02 §4.5 同步载荷契约；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.11（结算动画：开始前等待 3 秒，结束后停留 5 秒再消失）
- 结算动画**开始前先等 3 秒**（期间显示待吸入列表静止 + 旧数字），再开始逐项吸入；
- 动画播完后总数字**停留 5 秒**再消失（无变化结算直接显示 5 秒）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.10（关系管理改为管理面板独立页签，与经验规则并列；关系图并入页签）
- 管理面板新增「关系管理」页签（TAB_RELATION，与「经验规则」并列）：规则列表 + 编辑器
  （from/to 逗号分隔多值、类型三选、留空 to = 内部关系）、新增/保存/删除走 RelationEditC2S。
- 「打开关系测定图」入口并入关系管理页签内（全屏图，关闭返回管理面板）；
  移除阵营页签里的「关系管理…/关系测定图…」按钮与旧的独立关系管理弹窗。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.9（新增独立关系管理面板 + 全屏子面板关闭返回上层）
- 新增**关系管理面板**（全屏独立）：管理面板「阵营」页签「关系管理…」打开；
  左侧关系规则列表（从上到下优先级），右侧编辑 from/to（多阵营/组、逗号分隔）、类型三选，
  留空 to = **内部关系**；新增/保存/删除走 RelationEditC2S → 服务端权威校验+落盘+回执。
- 面板内提供「打开关系测定图」入口（测定图仍可从管理面板直接打开）。
- **修复：全屏子面板关闭时不再直接回游戏，而是返回上层**——关系管理面板/关系测定图关闭
  返回管理面板，管理面板（Esc/✕）返回 K 面板；命令打开的测定图仍回游戏。
- 数据链路：CharacterListS2C 新增 `relationRules`（原始规则，含内部关系/组引用）→
  客户端 `ClientCharacterState.relationRules()` → 关系管理面板。
- docs/02 同步；构建：spotlessApply / build -PrunTests 全绿。

## 2.18.8（修复：K 面板 ✕ 悬停即关闭——改为点击才关闭）
- 修复：角色管理面板（K）标题栏 ✕ 悬停即触发 onClose（误写在每帧 render 的 hover 判定里），
  鼠标移到 ✕ 上不点击也会关闭面板；已改为与管理面板一致：渲染只高亮、mouseClicked 点击才关闭。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.7（关系系统重写：多对多 + 从上到下优先级 + 关系测定图）
- 关系声明改为**多对多**：`RelationRule {from[], to?, type}`，from/to 各为一个 id 列表
  （阵营或组，组自动展开），生效范围 = from × to 笛卡尔积、关系双向对称；兼容旧格式单字符串。
- **内部关系**：省略 `to`（或 from=to 同一列表）= 该列表内所有阵营两两互设该关系
  （如 from 为 [qdf,qsa,qso] 不写 to → 三者两两友好），一行声明全组互连。
- 优先级改为**从上到下**：关系列表先声明（靠前）的规则命中即生效，重复声明后者被忽略并 WARN。
- 新增**关系测定图**（全屏）：管理面板「阵营」页签按钮 或 `/rp faction graph`（管理员）；
  每个阵营 = 徽章 + 名称标签，有关系的阵营对之间连线（白=中立 / 红=敌对 / 绿=友好），
  可拖动平移、滚轮缩放、Esc 关闭；打开时下层管理面板暂时隐藏（同部署点弹窗逻辑）。
- 数据链路：`FactionGraph.edges()`（a<b 去重、先命中类型）→ CharacterListS2C `relations` →
  客户端 `ClientCharacterState.relations()` → `FactionGraphScreen`。
- 单测更新：多对多笛卡尔积 / 从上到下优先级 / 组展开 / 兼容数组与旧格式 / edges 断言。
- docs/02 同步（模型、优先级、图、命令）。
- 构建：spotlessApply / build -PrunTests（含 LangFileTest）全绿。

## 2.18.6（经验 HUD 改为仅结算时显示：动画 + 总数字停留 5 秒）
- 不再在存活期间常驻显示经验 HUD（列表/总数字平时不出现）。
- 结算时播放逐项吸入动画；动画播完后总数字**停留 5 秒**再消失；
  无变化的结算直接显示总数字 5 秒。
- 项目行格式严格改为「**符号数值 标题**」（如 `+100 击杀奖金` / `-50 违规扣分`），
  不再用「标题 符号数值」。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.5（新增 /rp xp add 手动记分命令）
- 新增命令 `/rp xp add <玩家> <数值> <标题>`（管理命令，OP≥2 或 ccnnrrp.admin.settle）：
  向玩家待结算列表添加自定义记分项目（标题 + 数值，数值可为负），HUD 立即更新，
  随下次结算（死亡退场或 /rp settle）计入累计 XP；同标题条目合并（数值相加、标题取后来者）。
- 语言包：新增 ccnr_rp.xp.add.ok / add.invalid（zh/en 同步）；usage 键同步。
- docs/06 §7.5 与 docs/10 命令表同步。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.4（修复结算时机 + 结算动画放慢/死亡界面可见）
- 修复：结算不再在「事件结束」与「游戏结束」时自动触发——结算仅发生在**死亡退场**
  （死亡/判死/退役/征召结束）与 **`/rp settle [player|all]`** 命令；待结算列表持续累积，
  直到玩家死亡或管理员手动结算。
- 清理：移除 `events.json` 的 `settleOnEnd` 死配置（EventModels/EventManager/管理面板按钮/
  默认事件定义同步删除；旧配置多余字段忽略兼容）。
- 修复：HUD 结算动画**放慢**——单项飞入 0.7s（先快后慢缓动）+ 项间停顿 0.15s，
  不再瞬间完成。
- 修复：死亡瞬间结算动画此前被**死亡界面（DeathScreen）遮挡**不可见（HUD 覆盖层在
  Screen 打开时不渲染），表现为“动画瞬间完成/看不到”——现在结算动画在死亡界面上方
  最上层绘制，逐项吸入可见；动画结束后隐藏。
- 同步：docs/06（触发点/动画）、docs/07（事件结束不再自动结算）、docs/10（gameover 说明）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.3（经验规则编辑器：事件改文字输入+补全，修复展开失败与文字重叠）
- 修复：事件选择下拉无法展开——原下拉框与规则 ID 输入框叠在同一位置（widget 吃掉点击），
  且两处文字重叠。
- 事件选择改为「文字输入 + 补全下拉」（参考限制页目标补全）：输入过滤 character_alive/kill/death，
  鼠标点击或 ↑↓+回车选择，Esc 关闭；输入框内容为合法事件时参数面板/验证器实时跟随。
- 参数面板行内防重叠：参数名按可用宽度裁剪（超宽加省略号），类型与「插入」提示不再挤在一起。
- 布局修正：规则 ID 输入框移到 ID 行（ey1+2），事件输入框位于事件行（ey1+24）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.2（修复经验规则 UI 语言包缺失：按钮显示原始键）
- 修复：经验规则页签/命令引用的 ccnr_rp.xp.rules.* 语言键此前从未写入 lang 文件
  （旧编辑静默失败且 LangFileTest 仅校验 zh/en 键集对等），导致按钮/提示全部显示原始键。
  已补全全部规则键 + 验证器文案键（zh_cn/en_us 同步，284 键对等）。
- 移除废弃的 ccnr_rp.xp.line.* 疏散逐行键（evac 系统已删除）。
- 校验：JSON 解析 + zh/en 键集对等 + 代码引用键全量存在。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.1（修复管理面板点击崩溃 + 经验规则编辑器国际化）
- 修复：管理面板点击时崩溃（ArithmeticException: / by zero）——「经验规则」页签的 rowHeight()==0
  触发了通用列表滚动条除零（TAB_XP 从该路径排除）。
- 国际化：经验规则编辑器全部可见文案改为 lang 键（判断/数值/标题标签、试算结果、参数面板插入提示、
  保存失败等），zh_cn/en_us 同步；表达式技术性错误消息保持原样（诊断细节）。
- 构建：spotlessApply / build -PrunTests 全绿。

## 2.18.0（经验规则系统 v3：事件广播 + 规则引擎 + 结算动画 HUD）
- 经验系统重写：移除固定三来源（值班/任务/疏散）结算，改为事件广播 + 规则引擎：
  - 事件广播：character_alive（每 60 秒对每个在场角色）、character_kill（玩家击杀任意生物，击杀者收）、
    character_death（死亡/掉线判死，结算开始前赋予死者）。
  - 规则（config/ccnr_rp/experience_rules.json，管理面板热编辑）：订阅事件 + 判断表达式（布尔，空=恒激活）+
    数值表达式 + 标题表达式；表达式支持参数引用、算术、比较、逻辑与 round/floor/ceil/max/min 函数、字符串拼接。
  - 经验变化列表（每用户，落盘 user_profiles.json v3）：同规则条目合并（数值相加、标题取后来者、不计次数）；
    结算时列表求和（可为负）计入用户累计 XP 并清空；部署时清空列表（不计 XP）。
  - 结算触发点不变：/rp settle [player|all]、死亡/断联退场、事件结束（settleOnEnd）、游戏结束全员。
- 客户端：
  - 经验 HUD（右下角内收、水平居中、文字中心对齐）：底部一行白色经验数字 + 上方经验变化项目列
    （正=绿、负=红、带符号）；结算动画最底一项移入数字并消失 → 数字更新 → 列表下移补齐 → 循环至全部吸入
    （纯视觉，服务端结算瞬时完成）。
  - 管理面板新增「经验规则」页签：规则列表增/删/改/启停、事件下拉补全、限定高度可滚动参数面板
    （参数名+类型，点击插入到聚焦表达式末尾）、判断/数值/标题三个表达式输入框实时语法校验徽标、
    「试算」验证器（按事件默认样例本地求值展示成不成立）；保存 C2S → 服务端权威校验 + 落盘 + 热重载。
- 移除：SettlementCalcs / LedgerStore / 疏散方式结算 / /rp evac set / 旧逐行红绿结算弹层（由 HUD 动画取代）。
- 权限：新增 ccnrrp.admin.xp（回退 OP≥2）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿。

## 2.17.13（悬浮标签状态变化即时刷新 + 异步广播；管理面板配置中文标签补全）
- 悬浮标签刷新时机：UserService.setStatus/setRole 值真正变化时（部署/死亡/复活/换岗/下班）自动触发
  全服标签刷新，其他玩家头顶标签立即同步（不再残留「人死了头顶还挂标签」的过期数据）；幂等去重。
- 广播异步化：独立线程构建 payload（人多不阻塞主线程），构建完成后回主线程发网络包；合并去重。
- 管理面板「设定」页：新增 nametag 配置（enabled/badgeSize/offset）补全中文标签
  （头顶标签开关 / 头顶标签徽章大小 / 头顶标签高度(格)）。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.13.jar。

## 2.17.12（头顶标签可配置化：显示开关/徽章大小/标签高度，服务端权威同步；清理硬编码）
- 新增服务端配置（serverconfig/ccnr_rp-server.toml → nametag 段，随 CharacterListS2C 同步全员）：
  - enabled：头顶悬浮标签总开关（false=完全关闭）。
  - badgeSize：阵营徽章大小（世界单位，0=不显示徽章只显示文字）。
  - offset：标签离头顶高度（格，越大越高）。
- PlayerNametagRenderer 全部硬编码提为命名常量（布局坐标/缩放/颜色复用 RpTheme/徽章默认值），
  颜色统一走 RpTheme，消除散落魔法数字。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.12.jar。

## 2.17.11（头顶标签：阵营徽章置顶单独一行，世界空间绘制矢量/图片徽章）
- 标签顶部新增阵营徽章行：环(等级色) + 盘(深色) + 中央图形 + 右下角等级刻度。
- 徽章世界空间绘制：矢量图形用扫描线填充（复用 RpIcons.iconPolygon，视觉与 GUI 一致）；
  img: 图片徽章用 entityTranslucent 纹理 quad（服务器下发或内嵌回退）。
- 布局：徽章最顶一行，往下职业名(阵营色) / 玩家名 / 等级。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.11.jar。

## 2.17.10（头顶标签：改为世界空间 billboard 悬浮标签，客户端本地渲染、只有自己可见、始终面向相机）
- 放弃 HUD 屏幕投影方案（屏幕坐标换算受 FOV/距离影响，易出位置漂移问题）。
- 改为仿原版名字牌的世界空间渲染：RenderLevelStageEvent.AFTER_ENTITIES 阶段在玩家头顶上方
  mulPose(cameraOrientation) 使标签始终面向相机 + scale(-0.025,-0.025,0.025) + font.drawInBatch 绘制
  职业名（阵营色）/ 玩家名 / 等级 三行（带半透明底衬）。
- 只有本地客户端渲染，其他玩家看不到；自带透视（远小近大）；渲染距离跟随游戏设置；
  服务端仍只下发非观察者（已部署）玩家数据（v2.17.9）。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.10.jar。

## 2.17.9（头顶标签：改为服务端过滤数据，仅下发非观察者（已部署）玩家；客户端直接渲染）
- 修复 2.17.8 在客户端用 isDeployed() 门控导致旁观者视角完全看不到其他玩家标签。
- 改为服务端过滤：CharacterService.playerTagsJson() 只下发 ALIVE（非观察者/已部署）玩家的
  {name, professionId, factionId, level}，观察者（未部署）玩家不下发数据；
  客户端 PlayerNametagRenderer 移除 isDeployed() 限制，收到什么渲染什么——已部署玩家标签始终可见。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.9.jar。

## 2.17.8（头顶标签：改为部署/存活视角显示，旁观者模式不显示；标签上移不挡头）
- 显示条件反转：仅部署（ALIVE/存活在玩）状态显示其他玩家头顶标签，旁观者/观察者模式不显示（原为旁观者视角显示）。
- 标签锚点上移（头顶上方 0.45 -> 0.9 格），三行标签不再遮挡玩家头部。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.8.jar。

## 2.17.7（头顶标签：透视缩放远小近大 + 渲染距离跟随游戏设置）
- 标签尺寸按透视距离缩放（6 格处 1.0，越远越小，同原版名字牌），整体缩放（徽章/文字/底衬）用 PoseStack scale。
- 可见距离改为动态读取游戏渲染距离（GameRenderer.getRenderDistance）：人物在渲染距离内才显示标签，超出不渲染，与实体渲染一致。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.7.jar。

## 2.17.6（修复：2.17.5 误把 partialTick 当 FOV 传入 getProjectionMatrix，头顶标签完全不可见）
- 根因：GameRenderer.getProjectionMatrix(double) 的参数是 FOV 度数（内部 x0.017453292 转弧度后 setPerspective），
  2.17.5 误传 partialTick（0~1 小数），FOV 变成约 0.5 度，投影尺度异常放大，标签全部被视口剔除 → 旁观者视角完全看不到头顶标签。
- 修复：改用 mc.options.fov().get()（静态 FOV 设置值，与 v2.17.4 一致）计算 tan(fov/2) 做像素缩放；
  保留官方相机正交基投影（Camera.getLookVector/getUpVector/getLeftVector，解决乱飘）+ Mth.lerp partialTick 插值。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.6.jar。

## 2.17.5（修复：旁观者视角玩家头顶标签位置乱飘）
- 根因：PlayerNametagRenderer 手写三角函数基向量符号错误（fwdY/fwdZ/rightX 与 1.20.1 相机朝向相反），
  且用静态 FOV 投影（mc.options.fov），而游戏实际渲染 FOV 随疾跑动态变化，标签位置随视角/疾跑漂移。
- 修复：改用 Minecraft 官方相机正交基（Camera.getLookVector/getUpVector/getLeftVector）做点积投影，
  从游戏实际投影矩阵（GameRenderer.getProjectionMatrix(partialTick)）提取动态 FOV（含疾跑加成）做像素缩放；
  头顶位置用 Mth.lerp(partialTick, ...) 插值，标签稳定钉在玩家头顶，与游戏渲染完全一致。
  （注：getProjectionMatrix 参数为 FOV 度数，此方案有误，见 2.17.6。）
- 根因：PlayerNametagRenderer 手写三角函数基向量符号错误（fwdY/fwdZ/rightX 与 1.20.1 相机朝向相反），
  且用静态 FOV 投影（mc.options.fov），而游戏实际渲染 FOV 随疾跑动态变化，标签位置随视角/疾跑漂移。
- 修复：改用 Minecraft 官方相机正交基（Camera.getLookVector/getUpVector/getLeftVector）做点积投影，
  从游戏实际投影矩阵（GameRenderer.getProjectionMatrix(partialTick)）提取动态 FOV（含疾跑加成）做像素缩放；
  头顶位置用 Mth.lerp(partialTick, ...) 插值，标签稳定钉在玩家头顶，与游戏渲染完全一致。
- 构建：spotlessApply / build / test -PrunTests 全绿；jar 已部署 .minecraft/mods/ccnr_rp-2.17.5.jar。

## 2.17.4（身份数据库：装备预览区背景显示阵营图标）
- K 面板（身份数据库）装备预览区（右侧 3D 模型 + 头/胸/腿/靴/枪装备槽区域）背景绘制当前职位所属阵营徽章：
  大号半透明水印徽章（RpIcons.bigBadge alpha 水印），置于装备槽区右侧空白背景，先画背景再画内容，不遮挡模型与装备槽。
- 复用统一徽章封装（t-mt8dmt3a）：职位 → factionId → factionMeta（icon/tier）→ factionBadge/bigBadge；未知阵营跳过。
- scissor 限定在预览区内（不溢出到详情卡片外）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿（83 tests）。

## 2.17.3（管理面板补全增强：限制目标/刷新波职业与阵营/维度/设置职业/序列弹窗波与职业与阵营补全）
- 限制页「目标」输入框补全：按当前类型（FACTION/PROFESSION）过滤对应阵营/职业，显示「名字(id)」，点击/回车填入原始 id；GLOBAL 无目标不触发。
- 刷新波表单：维度（固定三主维度）、职业ID（逗号多值，追加/替换末尾词）、阵营ID（逗号多值）补全；
  设置页「首次入服自动部署职业」补全职业 id。
- 行为序列弹窗：WAVE 步骤「刷新波 ID」补全波 id；FORCE_PICK 步骤「职业ID(逗号)」「阵营ID」补全职业/阵营 id。
- 通用补全引擎：SugSource 枚举 + resolveIdSugSource 按聚焦框推断数据源 + 统一渲染/点击/键盘（↑↓/Enter/Esc），
  多值输入框用 applyIdSug 追加替换、单值框整体替换；补全下拉置顶渲染（widget 之后绘制）。
- 构建：spotlessApply / clean build / test -PrunTests 全绿（83 tests）。

## 2.17.2（修复：管理面板输入补全框被其他控件遮挡，改为置顶渲染）
- 根因：音乐补全 / CMDCam 场景补全的下拉框在 render() 中先于 super.render（widget 渲染：输入框/按钮）
  绘制，导致下拉框被输入框等 widget 盖住（补全内容显示不全/不可见）。
- 修复：renderMusicSuggestions / renderCamSceneSuggestions 移到 super.render 之后绘制，
  补全下拉始终在最上层（弹窗遮罩仍在最外层，弹窗打开时输入框失焦不触发补全，无冲突）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.17.1（修复：管理面板所有列表点击偏移 1 位）
- 根因：新增「限制」页后 tab 数从 6 增至 7，列表点击命中循环已改为从 TABS.length 起，
  但 visibleItem(i - 6) 仍用硬编码 6（未随 TABS.length 同步），导致所有列表（职业/阵营/事件/阶段/刷新波/限制）
  的点击命中偏移 1 行（点第 N 行实际选中第 N+1 行）。
- 修复：visibleItem(i - TABS.length) 与渲染基准 rowBounds.get(TABS.length + i) 对齐；
  全量核对列表索引基准已统一为 TABS.length（渲染/点击/滚动）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.17.0（部署人数限制：限制编辑器 + 选岗显示在职/上限 + 部署前统一检测）
- 数据模型：新增 config/ccnr_rp/limits.json 规则列表（复用 ConfigCrud 管道），规则 = 类型 + 目标 + 人数上限：
  GLOBAL（通用角色上限，职业未配置专属时兜底）/ FACTION（阵营上限）/ PROFESSION（职业上限）。
- 部署检测（全局性，统一 deploy() 入口）：职业维度在职数 ≥ 上限，或阵营维度在职数 ≥ 上限 → 拒绝部署；
  在职数 = 用户库 ALIVE 且职业/阵营匹配的用户（TEMP 征召不进库不占编制）；重新部署（在场换岗）目标维按「不含本人」计。
- 系统强制操作跳过限制：管理员刷人 / 强制征召部署带新 DeployFlag.LIMIT_SKIP。
- 管理面板：新增「限制」页（TAB_LIMITS），规则列表 + 表单（类型循环按钮 / 目标 id / 人数上限）+ 清空全部按钮。
- K 面板选岗：中栏职位行显示在职/上限小标签（满员红色），右栏详情显示「在职 x/y 职业 · 阵营 a/b」，
  空位不足部署按钮禁用并红字提示（服务端仍强校验）。
- 服务端下发：CharacterListS2C 增加 limits 规则 + occupancy 在职统计（职业/阵营），客户端 ClientCharacterState 缓存供展示。
- 测试：新增 DeployLimitsTest（规则解析/职业优先 GLOBAL 兜底/阵营专属/满员拒绝）；构建 compileJava / spotlessCheck /
  test -PrunTests 全绿（83 tests）。
- 文档：docs/09-spawn.md §4.0 部署人数限制（配置/检测/跳过/展示）。

## 2.16.0（职业复活点管理：每个职业可配置部署点，优先级 职业 > 阵营 > 世界复活点）
- 数据模型：职业定义（factions.json professions[]）新增可选 `spawn` 字段（结构与阵营出生点一致：
  rule SPREAD/SINGLE + points[{x,y,z,dim}]），即职业专属部署点/复活点；未配置回退阵营部署点/世界复活点。
- 部署链路：落点优先级改为 **职业部署点 → 阵营部署点 → wave deployAt → 世界复活点**（原为 阵营 → wave → 世界），
  覆盖自部署 / 重新部署 / 首次入服自动部署 / 召唤波 / 征召部署全部入口（统一 teleport/resolveDeployLevel）；
  职业部署点维度同样参与 CMDCam 场景维度解析。SPREAD/SINGLE 规则与阵营一致（分摊随机 / 集中稳定取点）。
- 管理面板：职业表单新增「管理职业复活点…」按钮，弹窗管理坐标列表 + 分布规则（仿阵营部署点编辑器，
  含「+ 添加当前坐标」/移除/规则切换）；阵营按钮文案改「管理部署点…」；弹窗按目标类型发不同网络包。
- 网络：新增 AdminProfessionSpawnC2S（职业 id + 规则 + 坐标列表），服务端 CharacterService.onAdminProfessionSpawn
  写 factions.json（权限 ccnnrp.admin.faction，仿阵营部署点）；保存职业 CRUD 不清除 spawn 字段。
- 测试：FactionProfessionsTest 新增 spawn 解析用例（rule/points/缺省 null）；构建 compileJava / spotlessCheck /
  test -PrunTests 全绿。
- 文档：docs/09-spawn.md 部署优先级三处同步；docs/03-profession.md 数据模型补 spawn 字段说明。

## 2.15.6（召唤波 mode 语义重定义 + 部署完成常驻横幅 + 招募审计修复）
- 波模式重定义：mode 从「部署通道」（SELF_DEPLOY=仅自刷/RESURRECTION=仅复活波/BOTH=双通道）改为「谁能收到邀请」
  （SELF_DEPLOY=存活可收到 / RESURRECTION=死亡可收到 / BOTH=皆可收到），并取代全局「向存活邀约」设置开关（已移除）；
  三种模式均可作为召唤波触发（队伍创建轮询、命令、序列 WAVE 步骤）。存量配置 "RECRUIT" 值自动映射为 RESURRECTION（WARN）。
- 无 CMDCam 场景/未装 CMDCam 时部署时序改为「开局直接落位切生存，电影 HUD/音乐与落位同一时刻开始」
  （不再等动画播完；客户端播完后的 DeployLandC2S 为空操作）；CMDCam 延迟落位路径保持原「先播后落位」。
- 部署完成常驻横幅：服务端 DeployNoticeS2C（部署者定向）→ 客户端顶部居中「已部署：职位」30s（下线清理、电影黑屏隐藏）；
  邀请部署/波次完毕/人满提前部署/正式转职统一提示；非 TEMP 部署保留原 actionbar 消息（双提示并存）。
- 存活玩家可被征召：FORCE_PICK/指定编制/通用波的存活拆分结算时按当前角色状态判别——
  观察/死亡 → 临时征召部署（TEMP）；存活 → 正式转职部署（不处死，改用户角色 + ALIVE + 冷却清零）。
- 招募审计修复：通用波名额分配防超招（count<=0 整波跳过、单通道空缺名额不转移、count=1 不再超招）；
  征召结算先部署成功再标记在场（防「以征召在场」状态卡死）；结算时刻重新校验状态（已自行部署/漂移则跳过）；
  候选池排除已有征召登记玩家（防重复邀请/重复部署）；v2（唯一身份）起 pick 邀请接受即按自己职业部署（移除选岗菜单）。
- 死代码清理：删除误入 java 树的重复 defaults 资源、孤儿角色更新 handler、选岗屏幕与语言键。
- 流程编辑器修复：关闭/保存/Esc 时统一移除参数字段输入框并释放屏幕焦点（不再残留 GUI）；字段描述改为输入框灰色占位提示
  （不再画在框内与输入文本重叠）；点击输入框同步设置屏幕焦点（弹窗内可直接键盘输入）。
- 处决转职 → 重新部署：在场（ALIVE）玩家点「部署」直接重新部署为选定职位——不处死、不留遗体、不结算死亡经验
  （移除处死+遗体延迟队列，走统一 deploy() FORCE_DEPLOY：清背包 → 新职位装备 → 传送部署点 → 入场电影 → ALIVE）；
  二次确认弹窗暂时隐藏（客户端直接发请求）；按钮与确认文案改「重新部署」。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（含新增 WaveQuota/ConscriptDeployMode/RECRUIT 兼容用例）。

## 2.15.5（行为序列「触发事件」锚点：序列内只读锚点，不可删/不可改类型/不可编辑参数，可上移下移）
- 数据模型：序列新增 TRIGGER 锚点步骤（{"type":"TRIGGER","source":"<kind>:<id>","label":"..."}），
  代表触发本序列的真实事件/环境（如事件 qdf_support 即「征召」上下文）；保存时自动写入/对齐，旧数据缺失自动补插。
- 编辑器：行为序列弹窗中锚点行为🔒只读行（青色锁定样式，无「删」按钮，点选仅展示触发来源），
  不可改类型/不可编辑参数，但**可上移下移**调整位置；其余步骤保持点选/上移/下移/删/改类型/参数编辑。
- 执行引擎：SequenceEngine.runSteps 跳过 TRIGGER 步骤（不执行、不占时间线），并把锚点 source 注入
  {{trigger}} 变量（COMMAND 步骤可引用触发来源）；execute 加 TRIGGER 安全兜底。
- 默认配置：defaults events.json / phases.json 补 TRIGGER 锚点示例；新增 SpawnModelsTest 锚点解析用例。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.4（流程编辑器空态优化：打开空序列自动加 WAIT 起始步骤）
- 修复「行为序列点进去都是空的」困惑：事件/阶段/刷新波配置没有内嵌 sequence 时（v1.4.4 起序列嵌入模型，
  独立 sequences.json 已废弃且从未迁移；现有配置大多无 sequence 字段），编辑器打开即空。
- 现在打开空序列自动加一个 WAIT 起始步骤（直接可编辑；只点「关闭」不保存，原配置保持无 sequence）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.3（流程编辑器回归：事件/阶段/刷新波行为序列弹窗编辑，界面仿出生点编辑器）
- 管理面板事件/阶段/刷新波表单新增「编辑行为序列…」按钮，弹出流程编辑器（仿出生点管理弹窗）：
  步骤列表（点选/↑↓ 上移下移/删）+「+ 添加步骤」+ 选中步骤类型切换（WAIT/WAVE/COMMAND/FORCE_PICK）
  + 按类型参数输入框（WAIT=秒数 / WAVE=波ID / COMMAND=命令文本({{event}} {{phase}} {{seq}} 变量) /
  FORCE_PICK=数量+职业ID+阵营ID），保存即走主表单 CRUD 落盘（sequence 字段）。
- 保存链路：弹窗保存写 editedSequence → buildPayload 的 addSequenceField() 优先用编辑结果、否则透传原 sequence；
  切换条目/新建时清空编辑缓存防串条。步骤引擎/执行逻辑不变（SequenceEngine 原有 WAIT/WAVE/COMMAND/FORCE_PICK）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.2（处决转职：在场玩家可点击部署，确认后服务端处死旧角色再部署为选定职位）
- K 面板「部署」按钮对在场（ALIVE）玩家可用（按钮文案变「处决转职」），点击弹出确认框；
  确认后客户端发 KillDeployC2S → 服务端先统一退场处死旧角色（状态→观察+复活冷却+遗体+死亡结算，
  遗体 2 tick 后生成、复制旧背包与旧职位名），再延迟 1s 走统一 deploy()（清背包 → 新职位装备 → 传送 →
  入场电影 → ALIVE，冷却清零）。
- 服务端：CharacterService.onKillDeploy（校验在场/职位/等级）+ SpawnFramework.queueRedeploy/checkRedeploys
  （延迟等遗体生成完再清背包，防遗体复制到空背包/新职位名）；SpawnFramework 波次选择统一为 selfDeployWave()。
- 客户端：CharacterManagementScreen 弹窗（确认/取消，遮罩吞点击）；语言包新增 deploy_kill / kill_confirm_* /
  spawn.error.alive_only / spawn.redeploy.started（zh/en 同步）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.1（管理面板「设置」页泛化：bool 开关 / string 文本输入 / 数值输入框全部可编辑）
- 设置页按类型渲染所有配置项：settings.json 项 bool=开关行、string=文本输入行（如 firstJoinProfession），
  与 serverconfig 数值行统一滚动 + 一个保存按钮；保存按 key 分流（settings.json → ManagerSetC2S，serverconfig → ServerConfigSetC2S）。
- ManagerSettings 类型化：新增 type(key)（bool/string），set() 按类型分流校验（bool 需 true/false，string 直接写入）。
- ManagerSetC2S 值长度上限 16 → 128（支持任意字符串设置项）；firstJoinProfession 改为管理面板可编辑（不再只改 settings.json 文件）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.15.0（首次入服自动部署：新玩家自动部署为可配置职业，默认 m5_intern 访客/实习生）
- 首次进入设施（本世界无用户档案）的玩家自动部署为配置职业：登录时入队 → 等素材同步完成（60s 超时兜底）
  且入服稳定（≥2s）后走统一 deploy()（装备 → 传送落点 → 入场电影 HUD + 出场音乐 → 状态 ALIVE → 广播）。
- 可配置（config/ccnr_rp/settings.json）：firstJoinAutoDeploy（bool，默认 true）总开关；
  firstJoinProfession（string，默认 m5_intern）自动部署职业 id，空串=关闭（2.15.1 起管理面板可编辑）。
- 细节：首次判定用 UserService.hasProfile()（不惰性创建档案，登录处理须先于档案创建调用）；
  已被其他入口部署（管理刷人/复活波/手动）时自动跳过；掉线清理队列；落点=首个匹配刷新波否则世界出生点。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.5（部署入场电影/音乐与 CMDCam 解耦：无 CMDCam 场景也播电影 HUD 与出场音乐）
- 修复「未配置 CMDCam 场景（或未装 CMDCam）时入场音乐与电影式 HUD 开场消失」：
  2.14.0 起 CinematicS2C（电影 HUD + 出场音乐）仅在「场景名非空且 CMDCam 已装」时才下发；
  本版改为电影 HUD + 音乐始终播放（未 SKIP_CINEMATIC / NO_MUSIC），CMDCam 场景降为可选叠加层。
- 时序：有场景 → 强制旁观者 + 电影与场景同刻播放 → 全部播完落位（不变）；
  无场景 → 强制旁观者 + 电影 HUD/音乐播放 → HUD 播完客户端即发 DeployLandC2S 落位（不等待场景）。
- 客户端无改动（空白 cmdcamScene 分支本就支持「HUD 播完即落位」），纯服务端 SpawnFramework 解耦。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.4（修复 OGG 播放电流声：改用 Minecraft 原生 OggAudioStream 解码）
- 2.14.3 内嵌的 jorbis（googlecode soundlibs 0.0.17.4 fork）解码立体声时左右声道塌缩为同一值（解码器 bug），
  叠加字节序错配 → 播放电流声。
- 改用 Minecraft 自带 com.mojang.blaze3d.audio.OggAudioStream（原生 STB Vorbis 解码，立体声/字节序由 getFormat 提供），
  移除 jorbis 内嵌（build.gradle/OggPcm/libs）。播放逻辑（Clip/淡出）不变。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.3（音乐格式全面切换 OGG：WAV 体积过大弃用）
- 上传/存储/清单：config/ccnr_rp/audio/*.wav → *.ogg（OggS 魔数校验；AssetLibrary 清单同切 .ogg）。
- 播放：javax.sound 不原生支持 OGG——内嵌 jorbis（纯 Java Vorbis 解码）转为 PCM 后走同一 Clip 播放
  （淡出/音量逻辑不变；兼容 WAV/AIFF 走 AudioSystem）。
- 管理面板文案/语言包/单测同步 .ogg；新增 scripts/convert-music-to-ogg.sh（oggenc/libvorbis 自动探测）
  供服务端把既有 WAV 批量转 OGG；转换后客户端按清单哈希自动下载 OGG。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.14.2（观察者拾取真正禁止：Inventory.add mixin）
- 上版（2.14.1）用 PlayerEvent.ItemPickupEvent 事后取回——better_looting 忽略 add 返回值、批拾取每次都会
  触发物理化模组（ItemPhysic）动画，反复拾取/掉落导致物品在地上「跳舞」。
- 本版改为【入口拦截】：新增 org.spongepowered.mixin（0.7.38）+ InventoryObserverMixin，在
  Inventory.add(ILnet/minecraft/world/item/ItemStack;)Z 入口拦截——观察者（无在场身份的用户/征召兵）
  一律返回 false 拒绝入包；better_looting 忽略返回值 → 按「未添加」处理 → 原物品实体保持完整原地不动
  （不消失、不重复、无物理动画）；移除 2.14.1 的事后取回逻辑。
- 构建：compileJava（含 Mixin 注解处理器校验）/ spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.14.1（入服 5 秒状态栏 + 观察者拾取兜底）
- 刚入服 5 秒：右下角三状态栏（职位/阵营/血量）常驻显示（不管背包是否打开）；其余时间仅背包界面显示。
  （客户端：ClientPlayerNetworkEvent.LoggingIn 记录入服时刻 → StatusHud.renderJoinOverlay 覆盖层按 5s 门控）
- 观察者拾取兜底：better_looting 等模组的批拾取直接 Inventory.add（绕过可取消的 EntityItemPickupEvent），
  观察者仍能拾取物品——在不可取消的 PlayerEvent.ItemPickupEvent（入包后才发）把观察者背包全部丢回地上
  （含拾取延迟防被立即再次吸走），观察者无法持有任何物品。
- 版本号 2.14.0 → 2.14.1。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.14.0（部署流程：先播 CMDCam 入场动画，动画完毕后再传送到出生点）
- 时序反转（applyDeployCore，用户确认）：部署触发 → 强制旁观者 + 电影 HUD（黑屏/图标/文字）与 CMDCam 场景
  【同一时刻开始播放】→ 全部动画播完（客户端检测 HUD 结束 + CMDCamClient.isPlaying 场景结束）→ 发 DeployLandC2S（新包）
  → 移动玩家到部署点（优先级：阵营出生点 → wave deployAt → 世界出生点）→ 设置生存。
- 降级：未设定 CMDCam（无场景名 / 未装 CMDCam）或 SKIP_CINEMATIC → 开局直接落位切生存（不播动画、不等待）。
- 兜底：HUD 播完后再等 20s（场景缺失/异常）自动落位；服务端 120s 超时；动画期间掉线清理待落位。
- 修复「动画全程不显示电影 HUD」：CMDCam 场景播放时每帧设 options.hideGui=true（CamRun.tick）→ GameRenderer 跳过整个
  gui.render（含电影覆盖层）；电影播放期间在 RenderTickEvent.Pre（LOWEST 优先级）强制恢复 hideGui=false，电影结束即停止
  强制（场景余下部分仍隐藏 HUD，场景结束按 CMDCam 缓存恢复）；落位时再确保 hideGui=false。
- 兜底：落位超时 30s（掉线/动画中断）自动传送，防卡暂存点；动画期间掉线清理待落位状态。
- 全入口复用：自部署（deployPosition）/ 复活波（onWaveFinish）/ 征召（onConscriptFinish/deployConscript）/ 管理刷人统一走 deploy()。
- 注：本版本含 2.13.2 的尸体修复（尸体显示玩家皮肤 + 「职位 + 玩家名」名字牌）。
- 版本号 2.13.2 → 2.14.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.13.2（修复：尸体无皮肤 + 尸体名字改为「职位 + 玩家名」——非入侵方案）
- 根因：此前 CorpseBridge 把遗体身份 UUID 改写为 ccnr-char 哈希派生 UUID——客户端按该 UUID 在 tab 列表
  查不到玩家档案（不在线）→ 尸体渲染默认史蒂夫纹理。
- 修复（全部在 CCNR-RP 内，不改 corpse 模组本体/jar，不重打尸体）：
  - 死亡身份注入：PlayerDeathEvent 保持玩家真实 UUID（客户端按 UUID 解析到 LittleSkin 纹理 → 尸体显示玩家本人皮肤），
    playerName 改写为「职位 + 玩家名」（如「警察 小明」；无职位时仅玩家名）；
  - 遗体名字牌：EntityJoinLevelEvent 把 corpseName 移到 customName + 置可见（1.20.1 名字牌仅 customNameVisible 渲染），
    corpseName 置空后 vanilla getDisplayName() 回落 customName——头顶名字与搜尸 GUI 标题一致显示「职位 + 玩家名」，无 "Corpse of " 前缀；
  - 保护：未安装 Corpse 模组时跳过遗体生成，物品按原版正常爆出（日志提示）；
  - 清理：删除哈希派生 UUID/DeathChar 无用代码；_corpse_src 中未部署的魔改源码已还原。
- 版本号 2.13.1 → 2.13.2。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿（66 用例 0 失败）。

## 2.13.1（修复：CMDCam 场景部署时播不出来——CreativeNetwork.sendToClient 反射签名匹配失败）
- 根因：CamSceneBridge.playScene 用 getMethod("sendToClient", StartPathPacket.class, ServerPlayer.class) 精确匹配，
  而 CreativeCore 实际声明 sendToClient(CreativePacket, ServerPlayer)（参数为基类），getMethod 按声明类型精确匹配必然
  NoSuchMethodException，被 catch 静默跳过 → 部署电影播完黑屏转场不播 CMDCam 场景（日志：CMDCam 场景播放失败（跳过，不阻断））。
- 修复：改为按方法名 + 参数可赋值性扫描（findSendToClient），兼容基类/具体类两种签名；场景存在性与包构造逻辑不变。
- 版本号 2.13.0 → 2.13.1。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.13.0（刷新波与职业支持自定义 cmdcamScene，部署覆盖优先级：阵营 < 刷新波 < 职业）
- 职业（factions.json professions）与刷新波（spawn_waves.json waves）数据模型新增 cmdcamScene 字段：FactionProfessions.upsert / SpawnModels.Wave 解析回读，管理面板职业/刷新波表单新增输入项（复用 CMDCam 场景补全提示）。
- SpawnFramework.applyDeployCore 部署时按「阵营 → 刷新波 → 职业」低到高覆盖取最终 cmdcamScene 下发入场电影：职业最高，空值回退刷新波，再回退阵营；自部署/刷人/复活波/征召路径一致生效。
- 服务端 sendList 职业 JSON 补 cmdcamScene 字段，客户端编辑职业时回显保留。
- 新增 SpawnModelsTest / FactionProfessionsTest 覆盖 cmdcamScene 解析与 upsert 回读。
- 版本号 2.12.0 → 2.13.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.12.0（CMDCam 场景保存回显修复 + 管理面板场景名补全提示）
- 修复：阵营编辑器 CMDCam 出场场景（cmdcamScene）保存后消失——根因是服务端下发角色列表（sendList）的阵营 JSON 漏掉 cmdcamScene 字段，客户端回显永远读到空；现补上该字段，保存后输入框保留值。
- 新增：服务端反射读取 CMDCam 已保存场景名（CMDCamServer.getSavedPaths）随 ManagerStateS2C 下发（camScenes），管理面板 CMDCam 场景输入项聚焦时按输入过滤下拉补全（点击/上下键/回车选中，Esc 关闭）；CMDCam 未装或读取失败时安全降级为无提示。
- 版本号升至 2.12.0。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.11.0（管理器设置页横向溢出修复：开关右对齐面板内边界，无多余横向滚动条）
- 设置内容区右边界改为 px2-18（滚动条 px2-14 左侧留 4px 间隙），开关右对齐到面板内边界（此前画到 px2-24 起点、46 宽开关越过滚动条导致右侧横向溢出/多余竖向条）。
- 输入框/开关行/rowBounds 宽度统一以 settingsRight() 为右缘，标签避让开关区。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.10.0（旁观者视角玩家头顶标签：阵营徽章 + 职业名(阵营色) + 玩家名 + 等级）
- 新增 PlayerTagsS2C：服务端在登录/登出/部署变更时广播全玩家档案摘要（uuid → 名字/职位/阵营/等级）。
- 客户端 PlayerNametagRenderer：观察者/旁观者视角下，把每个其他玩家的 3D 头顶位置投影到屏幕，
  在 HUD 层绘制真实阵营徽章图标（RpIcons，非文本）+ 职业名(阵营色) + 玩家名 + Lv.等级；距离缩放 + 视口剔除。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.9.0（管理面板设置标签统一滚动区修复 + 阵营编辑器 CMDCam 场景配置项）
- 设置标签：开关 + serverconfig 数值统一进一个滚动区（此前数值区起点过高导致内容飞出去、滚动条失效）；
  开关行按滚动偏移生成（可点切换），数值输入框与绘制行严格对齐。
- 阵营编辑器新增「CMDCam 出场场景」输入项（cmdcamScene 字段）：部署入场电影播完黑屏转场播放该场景。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.8.0（CMDCam 联动：阵营/事件/结算可配 SCENE 摄像机出场，黑屏转场）
- 阵营数据模型增加 cmdcamScene 字段（可编辑），部署入场电影播完 → 渐变黑屏转场 → CMDCam 播放该阵营 SCENE，摄像机从部署点视角走路径，播完回位。
- 动画序列新增 CAMS 步骤类型（param=scene）：player_spawn/event_start/game_end 等钩子序列可直接引用 CMDCam 场景——事件开局/结算通用。
- 新增 CamSceneBridge（反射调用，CMDCam/CreativeCore 缺失时安全降级不崩服）+ CamScenePlayC2S 网络包。
- 默认 animations.json 的 game_end / event_start_alarm 序列加入 CAMS 步骤示例。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.7.0（修复管理面板「设置」标签布局：内容飞出面板 + 数字输入框不可编辑）
- 设置标签拆成两个独立区域：settings.json 开关行固定顶部（可点切换），serverconfig 数值行在下方独立滚动。
- 修复 renderSettings 与 buildSettingsForm 对滚动偏移的解读不一致导致的坐标错位（内容飞出面板）；
- 数字输入框与数值行对齐（mkBox 改为按行定位，值不再双绘），可正常点击编辑。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.6.0（修复管理面板「刷给自己」不赋予身份 + 清理角色库/皮肤/角色上限死代码）
- **修复管理面板「刷给自己：当前角色改为所选职业」**：原实现只发装备+切生存，不写用户身份，
  导致「资源给予了但人物身份没有被赋予」。改为走统一部署入口 deploy()（FORCE_DEPLOY + SKIP_CINEMATIC + NO_MUSIC + QUIET），
  完整赋予职位/阵营/ALIVE 状态/冷却清零/疏散重置，并同步客户端档案。
- **清理角色库系统残留**：删除废弃 CreateCharacterModal；移除 UserService.createRemainingMs/markCreated、
  ClientCharacterState.createRemainingMs/createCooldownUntil/maxCharacters（v2 无角色上限/创建冷却概念）；
  CorpseBridge/StatusManager 移除 skinHash（皮肤系统已删，恒空串）；CCNRRPConfig 移除 maxCharactersPerPlayer/createCooldownSeconds 死配置。
- **清理无用语言键**：admin.profession.gear / count_limit / create_cooldown / character.error.create_cooldown（zh/en 同步移除）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.5.0（管理面板设置全量程序化：settings.json 全部开关可改，含招募邀请/右下角状态栏）
- 管理面板「设置」标签重构：settings.json 的全部开关（forceObserving / openPanelOnJoin / forceRetain /
  recruitInviteAlive / hudEnabled / hudProfessionText / hudFactionText / hudHealthText）程序化生成开关行（点按切换），
  不再硬编码 6 个；补齐此前缺失的 recruitInviteAlive 与 hudEnabled 两个开关。
- 开关行与 serverconfig 数值设定合并为统一滚动区；开关默认值按 ManagerSettings 逐键取值（与服务端一致）。
- ManagerSettings.keys() 补全 hudEnabled；语言包新增 recruit_invite_alive 键（zh/en 同步）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.4.0（K 面板 3D 人物预览关闭名字板：只显示人物模型 + 职位装备）
- CharacterPreview 的 PreviewPlayer 在创建时 setCustomNameVisible(false) + setCustomName(null)，
  K 面板职位详情的 3D 预览不再显示玩家名（bananaxiao2333）名字板，只显示人物模型 + 职位装备。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.3.0（观察者拾取拦截：better_looting 等模组绕过旁观模式拾取的修复）
- StatusManager 新增 EntityItemPickupEvent 监听：观察者（无在场身份的用户）一律取消拾取事件。
  原版旁观者模式本身不能拾取，但 better_looting-1.20.1-forge-2.1.1-hotfix 会绕过游戏模式判断直接给物品；
  Forge 拾取事件层统一取消，覆盖所有拾取来源。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.2.0（部署前清空背包：防止死亡/观察期间遗留物品带进新岗位）
- 统一部署核心 applyDeployCore 在发放职位装备前先清空玩家背包/护甲/副手（0-40 槽），
  覆盖自部署/复活波/强制征召/管理员部署全部路径（都汇入 deploy()）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.1.0（统一部署/退场/结算管道：一个函数 + 行为 flag，删除征召分支文案）
- **唯一部署入口 deploy()**：自部署 / 管理员刷人 / 复活波 / 强制征召 / 手动部署全部收敛为 SpawnFramework.deploy(player, professionId, wave, Set<DeployFlag>)；
  行为差异由 DeployFlag 控制（SKIP_CINEMATIC 取消开局黑屏 / FORCE_DEPLOY 强制部署不论存活 / NO_MUSIC 关闭部署音乐 / QUIET 不刷提示 / TEMP 临时征召身份），
  流程固定：读职位→门控(按 FORCE)→loadout→传送→cinematic(按 SKIP)→音乐(按 NO_MUSIC)→状态/角色(按 TEMP)→广播(按 QUIET)→evac 重置。
- **唯一退场入口 retire()**：普通死亡 / 判死(命令/掉线/轮询兜底) / 下班(退役) / 征召结束全部收敛为 StatusManager.retire(uuid, player, reason, Set<RetireFlag>)；
  行为差异由 RetireFlag 控制（SPAWN_CORPSE 生成遗体 / OFFLINE 离线结算挂起 / SKIP_SETTLE 不结算），共用同一状态迁移 + 同一结算函数（settleUserDown）+ 同一逐行绿/红。
- **删除征召分支文案**：ccnr_rp.spawn.conscript.kia（征召兵阵亡，编制结束）与 ccnr_rp.xp.settle.conscript 从语言包移除；
  settleConscriptDeath 独立分支删除，征召执勤时长并入用户档案后走统一结算。
- 命令 /rp state kill 改为走统一 retire（原来手动置 DEAD 不结算）。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 2.0.0（v2 重构：删除角色实体，改为职位选择 + 等级解锁门控）
- 删除角色实体/角色库：玩家身份（在场/阴间/观察）+ 当前职位 + 复活冷却 + 执勤/任务/疏散全部上移到用户层。
- K 面板改为职位选择器：左机构过滤 / 中职位列表（需求等级达标绿 Lv N、未达标红）/ 右详情 + 部署按钮；未达等级/非观察/冷却中置红禁用并显示「需要等级 Lv N」；选中即部署。
- 部署门控改为「用户等级 ≥ 职位 unlockLevel」，取消 selfDeploy 自选。
- 取消 K 面板打开限制，任意时刻可开。
- 经验结算右下角逐行渲染：绿色加分（+20 值班）/ 红色减分（-10 死亡）；结算器支持负值，evacDiedXp 默认 -10。
- 管理面板职位编辑器新增「解锁等级」字段。
- 构建：compileJava / spotlessCheck / test -PrunTests 全绿。

## 1.5.29（全 GUI 灰色半透明底重做 + 同步完成自动开面板 + 观察者常驻提示）
- **全 GUI 灰色半透明底重做**：RpTheme 面板/边框/文字全部改为中性灰阶半透明底（功能色仅保留选中红、警示、等级徽章、青色强调），19 处硬编码青蓝底色（按钮/滚动条/悬停/徽章/事件横幅/招募卡片等）同步改灰；
  管理面板删除黄色「● ADMIN」徽章（非管理员保留红色无权限提示）。
- **同步完成自动开 K 面板**：入服自动开面板改为素材同步完成（AssetSyncDoneC2S 回执）后触发，走与按键完全相同的统一入口 openCharacterPanel（受 openPanelOnJoin 设置控制）。
- **观察者常驻提示**：观察者身份时 actionbar 常驻显示「观察中，按 <绑定按键> 进行部署」（约 1.5s 刷新，按键名随绑定实时显示）。

## 1.5.28（素材下载异步化 + 同步数据中提示 + 同步前禁用部署 + 状态栏改背包内显示）
- **素材下载异步化（修复进服卡顿）**：客户端分片到达在网络线程仅 O(1) 累积（不再主线程 O(n²) 拷贝/大文件写盘），
  清单解析、分片拼接、磁盘写入全部移到后台线程；服务端清单哈希按 size+mtime 缓存（登录不再全量 SHA-256），
  素材流式下发放到后台线程；单个素材请求 15s 超时自动跳过，防卡死。
- **「正在同步数据…」提示**：同步期间左上角显示小标签（客户端提示与服务端拒绝部署共用 lang 键）。
- **同步完成前禁用复活/部署**：服务端登录时标记待同步（有通道时），客户端处理完清单/下载完毕回执
  AssetSyncDoneC2S 后放行；自部署/复活波/征召/强制抽取全部前置校验（60s 超时兜底放行，防老客户端锁死）；
  登出清理待定标记。
- **右下角状态栏改为背包内显示**：与事件横幅一致，仅 InventoryScreen 打开时绘制，不再常驻游戏内。

## 1.5.27（素材中央下发 + 管理面板创建修复 + 界面素化）
- **素材中央下发（服务器控制全部素材）**：新增素材库（config/ccnr_rp/audio/*.wav 音乐 + config/ccnr_rp/textures/*.png 阵营图标），服务器按清单（AssetManifestS2C，名称+大小+SHA-256）下发，
  客户端对比本地缓存（config/ccnr_rp/assets-cache/，文件+hash 边车）自动请求缺失/变更素材（AssetRequestC2S → AssetPartS2C 32KB 分片，逐个下载）；音乐播放与 img: 阵营图标渲染一律使用服务器下发版本——
  客户端不再依赖本地/内嵌副本，也无法用本地文件替换；管理面板图标选择自动并入服务器素材库图标。
  服务器首次启动自动把内嵌默认图标（admin_hq/madison）写入素材库；音乐上传/管理端 CRUD 后全服清单即时刷新。
- **管理面板创建修复**：ID 输入框在「创建模式」下被误锁（!edit 反转），无法输入 ID 导致无法创建——
  改为仅编辑模式锁定 ID（创建模式可输入）。
- **界面素化**：去掉面板水印背景图（RpBg）、扫描线、四角角标、顶部光泽等装饰元素，选中行保留红色高亮线，
  界面更朴素克制，仅保留功能性元素。

## 1.5.26（配置服务器侧化：等级曲线跟随服务端同步 + 修复进服自动打开面板）
- **配置服务器侧化**：等级曲线（levelBase/levelPow）由服务端在角色列表包（CharacterListS2C）中下发，
  客户端等级/进度展示一律使用服务端同步值，不再读取本地 serverconfig/ccnr_rp-server.toml——
  客户端调参不再可能影响任何设定，杜绝本地篡改与服务端不一致（其余管理器设置/阵营/职业/事件/阶段/波
  本已由服务端下发同步）。
- **修复进服自动打开面板**：原实现「一次性消费标记」在进服瞬间玩家实体/界面未就绪或已有其他界面
  （如招募弹窗）时会把标记提前消耗掉，导致面板永远不自动打开；现改为仅在真正打开面板时消费标记，
  条件未就绪时下一 tick 重试，招募弹窗打开期间等待其关闭，玩家打开其他界面或状态变为非观察者
  （在场/阴间/征召在场）时取消，绝不强抢界面。

## 1.5.25（尸体皮肤按哈希独立请求渲染：与角色档案解耦）
- 根因：客户端 SkinCache 按「皮肤哈希派生 UUID / 角色名」查找尸体皮肤时，只遍历本地玩家自己的角色列表（ClientCharacterState.list()）；别人客户端里没有死者的角色条目，尸体皮肤查不到，回退原版皮肤。
- 尸体自包含身份：尸体带名字 + 哈希派生 UUID（CorpseBridge 写入），皮肤按哈希值从服务端请求，与角色档案解耦。
- 客户端新增 `HASH_UUID_CHARS`（皮肤哈希派生 UUID → 角色 id）映射：`SkinCache.store` 时按与 CorpseBridge 一致规则建立，任何客户端都能按尸体的哈希派生 UUID 直接取到皮肤（换肤时清理该角色旧哈希映射，防旧尸体被误指向新皮肤）。
- 新增 `SkinRequestC2S`（按「皮肤哈希派生 UUID」请求皮肤）：客户端渲染尸体时若本地未缓存则按哈希向服务端请求（每个 UUID 仅请求一次，防刷屏；`isLikelyCorpse` 用「UUID 不在 playerInfoMap 且非本地玩家」判定假玩家/尸体）；服务端 `CharacterService.onSkinRequest` 扫角色找到该哈希的皮肤并下发。
- `SkinSpec.derivedUuid(hash)` 统一派生公式（服务端/客户端共用），补 SkinSpecTest 单测。
- 已在 `RpChannels` 末尾追加消息注册（不重排既有消息 id，保持通道兼容）。

## 1.5.24（兼容旧版 64x32 皮肤：自动归一化为现代 64x64）
- 原来：旧版 64x32 皮肤在现代玩家模型（64x64 布局）下，左臂/左腿对应纹理区（y 48-63）不存在，越界采样取到底部行导致肢体错色、左右不对称。
- 现在：新增 SkinSpec.toModern（64x32 转 64x64，右臂/右腿水平镜像补到左区；幂等，64x64/非 PNG 原样返回），在上传存储、全服广播、部署/登录重新读取三个入口统一归一化。
- 客户端 SkinCache.store 再加兜底：任何到达客户端的旧式皮肤先转现代再注册纹理。
- 补 SkinSpecTest 单测：64x32 转换正确性 / 64x64 幂等 / 非 PNG 原样返回。

## 1.5.23（皮肤系统增强 + 音乐管理 + 图片徽章）
- **图片徽章**：阵营图标支持 `img:<名>` 指向 mod 内嵌图片（assets/ccnr_rp/textures/faction/）；已部署「行政总部」「麦迪逊研究所」两张手绘图标（管理面板图标选择新增 img: 选项）。
- 皮肤上传支持 **URL 直链**（本地路径或 http(s) 均可，客户端后台拉取后走同一分片协议）；服务端校验升级为 **MC 皮肤规格**（PNG、64×64 或 64×32、≤256KB，SkinSpec 纯类可测）。
- 皮肤存储改为 **sha256 哈希去重**：`world/ccnr_rp/skins/<hash>.png`，同图只存一份；旧 `<charId>.png` 自动清理并在读取时回退兼容。
- **扮演换肤**：部署/登录时服务端重播皮肤（SkinSyncS2C 携带 playerUuid），客户端将正在扮演角色的玩家渲染为目标角色皮肤——全服看到"分毫不差"的角色外观。
- 角色查看界面 3D 预览改用**真实上传皮肤 + 职位 loadout 装备**（替换硬编码下界合金套；同步广播补充 loadout 字段）。
- **皮肤回收**：没有角色引用的皮肤文件自动清理（角色删除/换肤后 + 服务启动时各一次；旧式 `<charId>.png` 在迁移期视为被引用不误删）。
- **音乐管理**：管理员可上传音乐（本地 .wav 或 URL，服务端校验 RIFF/WAVE + ≤20MB，存 `config/ccnr_rp/audio/`）；管理面板出场音乐框带**补全提示**（过滤下拉/↑↓+回车/点选），上传后全服列表即时更新。

## 1.5.22（音乐传递：启动程序指定 > 职业音乐 > 阵营音乐）
- 入场电影音乐解析链（高→低）：**启动程序（启动器）指定音乐** > **职业音乐**（professions.music，已有）> **阵营音乐**（factions.music，新增）。
- 启动器指定：客户端 JVM 参数 `-Dccnr_rp.entrance_music=<路径>` 或环境变量 `CCNR_RP_ENTRANCE_MUSIC`，优先级最高；未指定时回落到职业/阵营配置。
- 阵营新增 `music` 字段：factions.json 可配（相对 config/ccnr_rp/ 或绝对路径，WAV），管理器阵营表单可编辑；空串=不设阵营音乐。
- 入场电影载荷携带 `factionMusic`，客户端按优先级解析；音乐不进 jar，全部配置化。

## 1.5.21（滚动条可拖拽 + 刷给自己接入统一装备流程）
- 滚动条支持鼠标拖拽：按住游标拖动即滚动（角色面板、管理器、创建角色弹窗两栏），点击轨道空白跳转到该位置；同一界面多条滚动条按 id 分发。
- 管理面板「刷给自己」接入统一流程：改职业（含阵营）的同时套用该职业的装备（LoadoutManager，含 NBT），与部署磨子同一套逻辑。

## 1.5.20（/rp 全命令 Tab 补全）
- 所有 /rp 子命令参数支持 Tab 补全列表：职业 id、阵营 id、事件 id、刷新波 id、阶段 id、在线玩家名、本人角色 id（创建角色的阵营/职业、save/load/trigger/enable/phase set/state/kill/xp/evac/select/activate/observe/delete/cooldown 等）。

## 1.5.19（职业装备保存默认全量：物品栏+盔甲+副手+NBT）
- /rp profession save <id> 默认全量保存当前装备到指定职业：物品栏 0-35 + 盔甲栏 + 副手 + 物品 NBT（原默认只存快捷栏，需 --full）；--hotbar 保留为"仅快捷栏"选项。
- 保存成功提示区分全量/快捷栏文案。

## 1.5.18（游戏模式轮询只保留观察者兜底）
- 轮询不再强制把非观察者（有在场角色/征召）切回生存模式——不干预玩家/管理员的游戏模式选择；仅保留「观察者 → 旁观者」兜底（死亡界面中的玩家仍跳过）。

## 1.5.17（修复死亡瞬间切旁观导致无尸体/无掉落）
- 修复：死亡瞬间（LivingDeathEvent/markDead 内）不再切换旁观者模式——此前立即 setGameMode(SPECTATOR) 会打断原版死亡掉落与 Corpse mod 尸体生成，导致死亡后无尸体、物品不掉落。
- 现在：死亡瞬间保持正常死亡流程（掉落 + Corpse 尸体生成），点重生时由 onPlayerRespawn 切旁观者并传回尸体旁；游戏模式轮询跳过死亡界面中的玩家（isDeadOrDying），不再打断死亡流程。

## 1.5.16（断联立即判死结算 + 遗体保留在地上）
- 确认并增强断联/掉线判死链路：断联立即结算数据并判死（观察模式+复活冷却），经验结算服务端立即执行（离线明细挂起、上线补发）。
- 遗体生成改为延迟 2 tick（玩家实体移除完成后在最后位置生成，Corpse mod 尸体保留在地上），避免掉线事件触发时的实体移除时序导致尸体丢失。

## 1.5.15（玩家提示文案全面精简：去掉解释性内容）
- 全面审计玩家可见提示：移除括号/破折号后的机制解释（如"你的角色不受影响"、"无角色档案，角色结算跳过"、"需观察状态且职业允许自部署"等），只保留结论性短句（知道被拒绝/结果即可）。

## 1.5.14（K 面板仅观察者可开：客户端 + 服务端双重强制）
- 面板锁规则明确为「仅观察者身份可打开 K 面板」：在场（ALIVE）/阴间（DEAD）/征召身份在场一律锁定。
- 客户端面板锁同步该规则（征召在场也锁定）；服务端新增统一校验 isObserver，创建/删除/部署/激活/皮肤上传等 K 面板操作全部前置校验（非观察者直接拒绝），不再只依赖客户端标志。

## 1.5.13（统一部署/死亡/结算流程：征召兵只是临时名字）
- 统一部署磨子 applyDeployCore（普通角色与征召兵共用）：装备→传送→生存→入场电影（阵营关系从图谱推导）→ 各自的身份状态推送。
- 统一死亡流程：死亡一律 切旁观者模式 + 记录死亡地点（复活后传回尸体旁）；征召身份清理只是流程第一步，不再单独分支。
- 统一结算顺序：先给玩家加分（用户经验，升级提示按用户等级），再给角色结算写盘；角色不存在（征召兵无档案）则跳过角色结算，仅按征召值班时长给玩家加分。
- 征召兵记录部署时刻，死亡时按执勤秒数折算玩家 XP。

## 1.5.12（修复征召兵阵亡后不立即切旁观者模式）
- 征召兵阵亡时立即切换旁观者模式（此前只清除征召登记，需等 2 秒轮询兜底才切旁观），并记录死亡地点——复活后传送回尸体旁旁观，与正式角色死亡体验一致。

## 1.5.11（邀请界面：类型标签 + 颜色区分 + 描述更新）
- 邀请弹窗与右侧卡片按邀请类型区分颜色：强制征召=红 / 指定编制复活=金 / 通用复活·选岗=青（左侧色条 + 类型标签）。
- 每张邀请卡片显示类型描述（强制征召=按编制分配角色 / 指定编制=按波次类型分配角色 / 通用=选择自己可复活的职业上岗）。

## 1.5.10（新增默认通用复活波 general_reinforce）
- 默认 spawn_waves.json 新增通用波 general_reinforce（未指定职业/阵营）：触发后向有可复活角色的玩家发邀请，接受后弹出选岗菜单，从自己可复活的观察角色中选职业上岗。
- 服务器 config/spawn_waves.json 同步新增；可用管理面板「召唤复活波」或 /rp spawn trigger general_reinforce 触发。

## 1.5.9（复活波按类型征召 + 通用波选岗菜单 + 移除管理面板行为序列编辑）
- 复活波语义重构：指定类型波（配置了职业/阵营）= 按类型征召——接受后被分配波次编制角色（临时、不进角色库、用完即删），不再部署玩家自己的角色；通用波（未指定类型）= 选岗——接受后弹出「选择上岗职业」菜单（人物渲染 + 名字/职业/部门），从自己可复活的观察角色中选一个上岗。
- 移除管理面板的「行为序列」编辑功能（事件/阶段/波表单中的序列编辑按钮与弹窗）；序列仍会照常执行（配置内嵌 sequence 字段保存时透传保留），需要调整序列请直接编辑 config/ccnr_rp 下的 JSON。
- 删除 WaveSelector 复杂选人逻辑（阴间/阳间/支援优先级），触发逻辑内联简化。

## 1.5.8（修复征召应征却部署了自己角色：邀请类型区分 + 单在场守卫）
- 修复：QDF支援事件同时触发征召邀请（FORCE_PICK）与复活波邀请（WAVE qdf_reinforce），弹窗里两张卡片都显示"复活波"，玩家容易误点——接受复活波邀请即部署自己的角色。现在邀请卡片明确区分「征召」与「复活波」（RecruitOfferS2C 携带 kind），服务端提示也分别使用「征召邀请」「复活波邀请」文案。
- 单在场守卫：已有在场角色（或已部署征召）时接受征召 → 部署取消并清理征召记录，防止先复活波后征召造成双身份。

## 1.5.7（复活波选人统一为自部署式观察者池）
- 复活波选人重写为与自部署一致的简单语义：统一"观察者"一个池（不再分阴间/阳间两池）。
  1) 按玩家去重（同一玩家保留优先级最高的候选：开支援 > 职业匹配）。
  2) 优先级 1：所有开通「以任何支援身份复活」的观察者 → 全部邀请（无视职业/阵营/自部署/冷却，等级高优先）。
  3) 优先级 2：没开支援但有匹配职业（非自部署）的观察者 → 随机补足到波次人数。
- 接受邀请后统一走 deployCharacter 部署封装（装备/传送/状态/入场电影与自部署一致）。
- 修复：同玩家多角色时不再"第一个角色不匹配就收不到"（候选去重移入选人算法按优先级）。

## 1.5.6（复活波叫不到人修复 + 征召兵 HUD 显示 + 阵营关系从图谱推导）
- 修复：复活波叫不到人——阳间池此前排除自部署职业（selfDeploy=true 的观察者不参加复活波），开启「以任何支援身份复活」的用户也收不到；现在开通支援复活的用户绕过全部限制（职业/阵营/自部署过滤），死亡/观察状态都能收到复活波邀请。
- 征召兵部署后右下角 HUD 实时显示征召编制（职业/阵营/血量真实更新），阵亡后自动回到观察模式——征召兵身份由服务端推送（ConscriptStateS2C），不再依赖角色库。
- 修复：征召兵入场电影的阵营关系不再是硬编码空数组——与正常部署一致，从阵营图谱 resolve 推导非中立关系。
- 服务器 spawn_waves.json 无需改动（qdf_reinforce 仍限定 QDF 编制；未开支援复活的非 QDF 玩家默认收不到，符合开关语义）。

## 1.5.5（全代码审计修复：阶段触发器/结算幂等/邀请与征召生命周期等 30+ 项）
- 征召邀请挂起中（弹窗未接受）不再被切生存（待定征召不算在场）；接受部署后才视为在场并播放入场动画（征召兵无角色档案，直接构造电影数据）。
- 修复阶段触发器失效：EventManager 重复调用 clock.tick() 导致阶段时长偏短、ON_PHASE_START/END 几乎不触发 → 改为按节流周期单次推进；ON_TIME 的 day 改为真实游戏天数；PhaseClock 时长≤0 兜底。
- 修复 CONDITION 触发器完全失效（type 字段被外层覆盖）→ 子类型改用 cond 字段；畸形配置（非数字/非对象）不再崩服（EventModels/SpawnModels/CharacterData/UserService 解析加固）。
- 结算幂等：先落 ledger 基线再写角色 XP（崩溃窗口最多少发一次，绝不重复发放）；settleAll 单条坏档异常隔离；疏散 XP 每局（部署时）重置重新发放；任务 XP 只登记给在场角色且事件结束/清空时清除（防无限累加）；删除角色同步清理结算基线。
- 复活波：候选与在线玩家 1:1 配对（防邀请错发/漏发）；同一玩家每波仅一个候选；阴间池加在线过滤；count≤0 兜底；超时后不可再接受邀请；分组结算后释放 finished（防内存泄漏）。
- 征召兵：接受部署后才视为在场（轮询/部署守卫一致）；部署播放入场动画。
- 角色/存档：禁止删除在场(ALIVE)角色；皮肤分片累积上限；离线判死不再依赖冷却标记（onActivate 复活角色也能兜底判死）；坏 UUID 不打断轮询/结算；起服崩溃防护（坏值回退默认）。
- 网络：皮肤分片/同步字节上限修正（32768/262144）；ErrorS2C 参数编解码一致（≤16）。
- 客户端：邀请过期清理（防弹窗死锁）；角色面板冷却字段缺失不再 NPE；自动开面板每登录一次（防反复弹）；非管理员拦截管理器操作；序列编辑器 ESC 返回上层；输入框安全读取。
- 其他：删除被组/关系引用的阵营被拒绝（防图谱整体失效）；装备槽位越界/坏 NBT 跳过；armor 槽位上界修正；皮肤缓存纹理释放与 charId 净化；/rp character create 的 background 不再被丢弃；FactionCommand 空白切分正则修正。

## 1.5.4（事件/征召/复活波触发实例带 UUID 分组）
- 每次触发（复活波/事件征召/命令召唤）都分配独立 UUID 分组：同 id 的多次触发互不干扰，可同时存在多轮邀请；结算/拒绝/超时后再次触发照常发新邀请（弹窗与广播仍显示原波次 id）。

## 1.5.3（修复波次/征召结算后无法再次邀请）
- 修复：同一复活波/征召结算一次后即被永久标记"已完成"，之后再次触发（管理面板召唤/事件钩子/命令）不再发任何邀请——表现为拒绝邀请后一段时间内收不到任何邀请（直到重启）。现在每次触发都会重新开启邀请，拒绝/超时过的玩家下次仍能收到；上一轮未结算的实例先结算、已加入记录重置，避免重复部署。

## 1.5.2（角色库为空时右下角 HUD 仍显示观察模式）
- 修复：角色库为空（无角色/被征召兵占用外全部删除等）时右下角状态栏不再消失——统一显示观察模式（观察者 / 观察模式 / 不适用）。

## 1.5.1（征召兵完全临时化：不进角色库 + UID 编制 + 无角色也能征召）
- 征召兵改为**完全临时内容**：不再写入角色库——不占角色上限（5 个）、不显示在 K 面板、不遵守创建冷却等角色库规则；阵亡/拒绝/超时/下线即消失，玩家自己的角色完全不受影响。
- 接受征召邀请 → 生成独立的 **UID 征召兵**（如 QDF-A1B2-3C，按编制职业/阵营），装备+传送+生存部署；不再把你现有的角色变成征召兵。
- **无可用角色也能收到征召邀请**：开启「以任何支援身份复活」时，只要在线且未在场（哪怕角色库为空）都会被征召；关闭时仍需有观察角色。
- 正在以征召兵在场的玩家：不会被二次征召、不会收到复活波邀请、不能自部署其他角色（单在场身份约束）；游戏模式轮询将其视为「在场（生存）」。
- 旧版（1.5.0）误写入角色库的征召兵角色会在服务启动时自动清理。

## 1.5.0（用户体系大重构：经验随用户走 + 双向观察模式 + 邀请制全面化）
- **用户体系**：一个玩家 UUID = 一个用户；角色/阵营挂在用户下，K 面板仅管理本人角色；等级与经验随用户走（结算归入用户，角色身上也记录各自获得的经验），HUD 等级进度显示用户等级。
- **观察类型双向强制**（每 2 秒轮询）：观察者（无在场角色）→ 旁观者模式；非观察者（有在场角色）→ 生存模式。
- **角色上限 5 个/用户**（可配置 maxCharactersPerPlayer）；**创建冷却**（可配置 createCooldownSeconds，防利用新角色跳过复活冷却），K 面板与创建弹窗显示剩余冷却/角色数。
- **「以任何支援身份复活」开关**（K 面板页眉）：开启后未匹配职业/阵营也能收到复活波与征召邀请；关闭只收匹配职业邀请。
- **强制征召改为邀请制**：FORCE_PICK 步骤改为向候选发征召邀请，自选加入；接受后按征召编制换职业部署（保留原名字，用完即删）；序列编辑器移除「随机名/刷新」开关。

## 1.4.13（强制征召不再改名：保留原角色名）
- 修复：强制征召（FORCE_PICK）不再把征召兵改成随机 UID 名称（QDF-XXXX）——征召兵用完即删，改名只会把角色列表搞乱；现在仅更换职业编制（如 QDF 队员/特工），保留玩家原名字。
- 已登记在册的征召兵不会重复被征召；序列编辑器移除「随机名」开关（旧配置里的 randomName 字段被忽略）。

## 1.4.12（征召兵用完即删 + 复活波自选加入 + 管理面板快捷操作）
- 征召兵（强制征召/强制抽取产生）死亡/判死/退役后直接删除角色，不再留在角色列表；删除时通知拥有者。
- 复活波改为「邀请制」：阴间池 + 阳间池候选都会收到邀请，**自行选择加入或拒绝**（不再强制复活）；**活着（有在场角色）的人不会收到邀请**。
- 已加入名单实时广播：「X 已选择加入（复活波 N：已 x 人 / 需要 y 人）」；**人满即提前部署**，超时按已加入人数部署，无人加入则公告失败。
- 管理面板新增快捷操作：职业页「刷给自己」（自己的角色直接改成所选职业含阵营）、事件页「触发事件」（手动启动事件）、刷新波页「召唤复活波」（手动发邀请）。

## 1.4.11（单在场约束：修复征召兵死亡状态不清）
- 修复：强制征召（FORCE_PICK）不再征召已有在场（ALIVE）角色的玩家——此前可造成玩家同时拥有 2 个存活角色，死亡时 findAlive 按顺序误杀另一个角色，导致征召兵死亡后状态不被清掉（或主角色被误杀）。
- 部署链路（自部署/复活波/强制抽取/招募）统一增加服务端硬校验：拥有者已有其他存活角色时拒绝部署（单在场约束）。
- 修复：已征召玩家死亡 → 状态正确清为观察模式 + 旁观者模式。

## 1.4.10（行为序列编辑器修复：回填真实值 + 输入框标签 + 滚轮滚动）
- 修复：序列编辑器打开/点选步骤时输入框不再显示默认值——现在回填该步骤已保存的真实值（等待秒数/刷新波ID/命令/数量/职业/阵营），修复"未改动直接保存就把原数据覆盖成默认值"的数据丢失。
- 输入框上方增加用途标签（数量/职业ID/阵营ID/等待秒数/刷新波ID/命令文本等），不再是无说明的空白框。
- 上移/下移/删步骤/类型切换/随机名/刷新开关切换前自动保存当前输入，不再丢字。
- 步骤列表支持滚轮滚动（超过 6 步仍可编辑查看），并显示滚动提示。

## 1.4.9（死亡旁观者：复活后传送回尸体旁 + 结算强制观察者 + 观察者自动旁观）
- 死亡时记录死亡地点；复活（点击重生/自动重生）后传送回死亡地点，确保进旁观者模式时就在尸体旁边（不改变出生点/床点）。
- 结算（死亡/断联/退役）完成后强制刷成观察者身份。
- 观察者身份自动刷成旁观者模式：每 2 秒轮询兜底，未部署（无在场角色）玩家强制旁观者模式，防漂移回生存。
- 掉线/退出时清除死亡地点记录，避免误传。

## 1.4.8（死亡强制旁观者模式：不传送）
- 死亡/判死（在线）→ 玩家强制切换为旁观者模式（SPECTATOR，不传送）；复活波/自部署时部署链路自动切回生存。
- 登录补强：角色处于复活冷却（近期死亡/判死）且无在场角色时，登录即强制旁观者模式。
- 「激活」为在场角色时恢复生存模式，避免旁观者状态下卡死。

## 1.4.7（死亡即观察模式：状态机直接转观察者 + HUD 套用一个状态）
- 修复：死亡/判死/退役不再停留在 DEAD（阴间）——handle（死亡事件）直接写观察模式（OBSERVING）+ 复活冷却标记；轮询兜底（每 5 秒）把旧存档残留 DEAD 归一化为观察者（保留冷却标记）。双保险保证死亡后角色一定是观察模式。
- 右下角状态栏套用一个状态：非存活（观察模式）时三行统一显示——职业=「观察者」、阵营=「观察模式」、血量=「不适用」（灰显），死亡后状态栏不再消失。
- 语义不变：复活冷却只锁「自己职业自部署」；复活波/强制抽取（FORCE_PICK）无视冷却强制复活。

## 1.4.6（阴间等待复活 + 事件横幅仅背包可见 + QDF支援事件）
- 修复：死亡/判死恢复阴间流程——角色进入 DEAD（阴间）等待复活，显示复活冷却倒计时；冷却结束自动回观察者池，等待刷新波/FORCE_PICK 重新部署（绝不自动复活）。
- 修复：右下角状态栏死亡后不再消失——死亡时第三行显示「阴间 · 复活冷却 X 分/秒」；角色列表无选中角色时回退显示首个角色档案。
- 事件横幅改为仅背包（InventoryScreen）打开时绘制；游戏内 HUD 与其他界面（角色面板/管理器等）不再显示。
- 移除横幅下方「活动事件（/rp event clear 清空）」提示文字，以及 /rp event clear 的「已清空 N 个事件」文字反馈（命令仍生效）。
- 新增默认事件 qdf_support（QDF支援）：危险阶段开始（或 /rp event trigger qdf_support 手动触发）→ 公告 → 10 秒后强制征召 5 名在线角色转为 QDF 编制并部署（FORCE_PICK，随机 QDF 名称）→ 触发 qdf_reinforce 复活波。
- 修正默认 qdf_reinforce 复活波职业 ID 为现行配置（qdf_guard/qdf_special → s4_guard/s3_special），QDF 推荐波不再因职业不匹配而空拉。

## 1.4.5（死亡即观察者 + 创建角色双栏滚动选择 + 滚动条）
- 死亡/判死不再停留在 DEAD 状态：立刻回观察者池（状态显示「观察中」），并打上复活冷却标记。
- 冷却语义：只锁「自己职业自部署」（K 面板激活/部署按钮需冷却结束）；复活波/强制抽取（FORCE_PICK）无视冷却强制复活。
- 创建角色弹窗重构：左侧阵营列表（行背景=阵营主题色，选中/悬停加亮）+ 右侧职业列表（随阵营联动滚动），弃用循环按钮。
- 新增 RpScrollbar：所有滚动物（K 面板角色列表/机构导航、管理器列表、创建弹窗两栏、事件横幅横向）显示滚动条（比例游标：灰色槽 + 青色方块）。
- 阴间循环保留旧存档 DEAD → 观察者归一化（保留冷却标记）。

## 1.4.4（统一部署入场电影 + 序列并入实体 + 弹窗与确认）
- 部署统一：所有部署路径（自部署/复活波/招募/强制抽取）一律播放入场电影（黑屏→阵营图标→打字档案→淡出），取消旧的 player_spawn 即时动画。
- 序列不再是独立实体：行为序列（WAIT/WAVE/COMMAND/FORCE_PICK 步骤）内嵌到事件 / 阶段 / 刷新波定义（events.json / phases.json / spawn_waves.json 的 sequence 字段）——事件开始、阶段开始、波触发时自动执行；管理器「序列」页签移除，改在对应表单内点「行为序列」弹窗编辑。旧 sequences.json 的 startSequence 引用仍兼容。
- 创建角色改为弹窗（名字 + 阵营/职业循环选择 + 创建/取消），不再挤在面板底部表单。
- 管理端 CRUD 影响预检：保存/删除阵营/职业/事件/阶段/波之前，服务端计算波及清单（引用该条目的角色/职业/波/事件/阶段），无波及直接执行，有波及弹确认框（确认执行/取消）。
- 自动同步：管理端任何设置/CRUD 变更后立即向全服在线玩家推送角色列表 + 管理器状态（阵营/职业/事件/阶段/波实时刷新）。

## 1.4.3（死亡/断联自动结算 + 输入框用途标签）
- 新：死亡/断联自动结算管线——判定死亡或断联时立即：① 同步角色状态（服务器存储+面板/锁刷新）② 尸体生成（Corpse 模组存在时）③ 角色转 DEAD（阴间）④ 服务器侧 XP 结算（值班/任务/疏散增量结算，幂等）⑤ 玩家侧显示结算明细（聊天消息）。
- 玩家在线 → 死亡/断联结算明细直接推送；离线（断联判死/后台掉线）→ 挂起通知落盘 pending_notices.json，上线时自动补发。
- 新：面板（K）与管理器所有输入框上方显示用途说明标签（名字/皮肤路径/ID/名称/颜色/描述/音乐/简历/数量/坐标/维度/职业/阵营等），行距加宽避免重叠。
- 阴间循环保持：DEAD 冷却结束自动回观察者且仅有被部署才会 ALIVE；死亡后 K 面板立即解锁。

## 1.4.2（阴间循环：死人不复活 + 死亡后面板解锁）
- 新：阴间循环检测（5 秒周期）——DEAD 且冷却结束的角色自动回到观察者（OBSERVING，阴间），等待刷新波/FORCE_PICK 重新部署；绝不自动复活。
- 登录归一化：上线时 DEAD+冷却结束的角色立即回观察者并同步面板。
- 冷却中的死者保持 DEAD（阴间）；只有被部署（复活波/强制抽取）才会变 ALIVE。
- 修复：死亡后 K 面板仍打不开——客户端面板锁改为由角色列表实时推导（CharacterUpdateS2C 到达即解锁）；服务端判死/回观察者时立即下发全量列表同步锁状态。

## 1.4.1（存活角色面板/自部署封锁）
- 服务端：存活（非观察者）角色 → CharacterListS2C 下发 panelLocked=true；K 面板拒绝打开（客户端提示）；自部署服务端硬校验拒绝（防自杀逃逸——碰到人不能自爆回城重置）。
- 入服自动开面板同样跳过锁定状态；死亡后经复活波/判死回观察者状态即可恢复。

## 1.4.0（序列编辑器 + 事件栏横向滚动）
- 新：序列系统（sequences.json + 管理器「序列」页签）——步骤编排：WAIT（等待秒数）/ WAVE（触发刷新波）/ COMMAND（执行控制台命令，支持 {{event}} {{phase}} {{seq}} 变量）/ FORCE_PICK（强制抽取观察者：≤N 名在线、随机附职业、随机 UID 名字、可选刷新生效）。
- 事件 hooks 新增 startSequence：事件开始自动运行序列（默认示例 qdf_support：等 10s → 抽 5 名 QDF → 触发 qdf_reinforce 波）。
- 命令：/rp sequence list|run <id>（管理员）；序列 CRUD 热重载即时生效。
- 事件横幅横向滚动：事件过多时鼠标悬停横幅滚轮横向滚动（背包界面同样生效），双侧箭头指示。

## 1.3.0（激活事件横幅 + 清空命令）
- 新：激活事件横幅（EventStateS2C）——顶部居中 4:3 横向红色警戒长方形排开（进行中标记），正常 HUD 与任意界面（背包等 ScreenEvent.Render.Post）上层都可见；入场电影期间隐藏。
- 事件开始/结束/清空/热重载自动广播横幅。
- 新命令：/rp event clear（管理员）——全部 RUNNING 事件立刻结束并重置为 SCHEDULED，横幅同时清空。

## 1.2.5（HUD 层级与外观微调）
- 入场电影（黑屏）期间右下角 HUD 自动隐藏（场景感知，不依赖覆盖层顺序）——黑屏完整遮住右下角。
- 去除 HUD 整体外框背景（仅保留每行独立槽位）。

## 1.2.4（管理器设置页布局修复）
- 修复：6 页签化后 renderSettings 仍使用旧偏移（3），前三个设置行渲染在第 4-6 个页签按钮区域（与页签重叠）——改为偏移 6；事件/阶段/刷新波页签恢复可见。

## 1.2.3（崩溃修复 + 设置行精简）
- 修复：管理器「刷新波」页签未选中任何波时打开表单，csv/posStr/num 读取 null 配置 NPE 崩客户端（已空安全）。
- 设置页签移除冗余的「HUD 总开关」行（HUD 默认显示，三行模式开关可单独控制），6 行紧凑布局。

## 1.2.2（右下角状态栏 HUD）
- 新 HUD（客户端覆盖层，右下角）：三行——职位（等级进度条/文字）/ 阵营（背景=阵营颜色徽章色环）/ 血量（百分比条，绿>50 黄>25 红；文字模式同源）。
- 每行=方形图标（阵营徽章/心形）+ 等宽长方形；长方形文字或进度条由管理器设置切换。
- 管理器「设置」页签新增 4 项：HUD 总开关 / 职位条模式 / 阵营条模式(文字|阵营色全条) / 血量条模式；实时生效并写入 settings.json。

## 1.2.1（管理器事件/阶段/刷新波 CRUD）
- 管理器新增三页签：事件（启用/时长/结束后结算/触发器任务沿用）、阶段（顺序/时长分钟）、刷新波（模式 SELF_DEPLOY|RECRUIT|BOTH、部署点 WORLD_SPAWN|POS、数量/等级/招募时限/维度/坐标/队伍·职业·阵营ID列表）。
- CRUD 后热重载：EventManager / SpawnFramework 实时生效（事件/阶段重读 json，刷新波清空队伍触发记录）。
- 管理器数据（事件/阶段/刷新波列表）随 ManagerStateS2C 下发，K 面板与管理器同步刷新。

## 1.2.0（管理器 CRUD + 职业简历配置化 + Corpse 崩服修复）
- 修复：未安装 Corpse 模组时离服判死触发 NoClassDefFoundError（类验证发生在 try 外逃逸）崩服——CorpseBridge 改为类探测（Class.forName 缓存）+ 独立内部类隔离引用 + 兜底捕获；低版本 1.0.8 亦曾崩（同根因现全覆盖）。
- 管理器 v2：三个页签——设置（入服规则 3 项）/ 职业 CRUD（列表+建档：名称/阵营/自部署/出场音乐/项目简历/保存/删除/新建）/ 阵营 CRUD（名称/颜色/图标 8 种/等级 1-3/描述）。全部实时写入 config/ccnr_rp/factions.json。
- 职业配置新增 profile（项目简历）：入场电影「项目简历」行读取职业 profile（优先于角色背景），GUI/管理器可编辑。
- K 面板/管理器数据在 CRUD 后自动刷新（列表+表单同步）。

## 1.1.4（页眉布局修复）
- 修复：「管理」按钮与 CCNR:NET 页眉文字重叠——按钮改按页眉宽度动态定位（置于 CCNR:NET 左侧），任何语言/字号下都不再重叠。

## 1.1.3（动画时自动关闭 K 面板）
- 部署成功（入场电影 CinematicS2C）与任意动画播放（AnimationPlayS2C）到达时，自动关闭角色管理面板，保证开场画面/标题/字幕不被遮挡。

## 1.1.2（崩溃修复 + 创建表单简化 + 名称校验）
- 修复：角色列表 S2C 到达时 reloadData 置空 nameBox 但未重建控件，点击「创建角色」触发 NPE 崩溃（refreshIfOpen 现同步 rebuild + createSubmit 空安全）。
- 创建表单移除「背景输入」，只保留名称 + 阵营/职业切换 + 创建（背景一律为空）。
- 名称规则：只允许文字（含中文/字母）与空格，禁止数字/符号——客户端输入过滤 + 提交校验 + 服务端二次校验（name_chars 错误提示）。

## 1.1.1（职业出场音乐，配置驱动）
- 新：职业配置新增可选 music 字段（相对 config/ccnr_rp/ 的路径，或绝对路径；WAV 格式）——部署入场电影开始时播放，60 秒后 1.5s 淡出；换职业/再次部署自动切歌。
- 默认配置新增「设施主管」（行政总部，selfDeploy=true）；真实配置已设 music: audio/de_mulan.wav（《三角洲行动》德穆兰战斗音乐已转 WAV 放入 config/ccnr_rp/audio/）。
- 音乐不进 jar，全部配置化——管理员换音乐只需替换文件 + 改 professions 的 music 字段。

## 1.1.0（职位划分落地 + CCNR-RP 管理器 + 入服/保留规则）
- 数据：按《CCNR服务器职位划分.pdf》重建默认配置——8 部门（行政总部T0/麦迪逊M1-M5/区域核能运营部/研发与技术部R1-R5/QDF司令部S1-S5/QSA综合处理小组A1-A3/QSO H1-H2/后勤N1-N3）+ 28 职位，徽章 icon/tier 同步（新增 gear 图标）；真实配置已写入。
- 新：CCNR-RP 管理器（管理员）——K 面板页眉「管理」入口，服务端权限节点校验（OP≥2），设置实时生效并写入 config/ccnr_rp/settings.json：
  · 强制观察者入服（入服默认 OB）
  · 入服默认打开角色面板（选择部署，无存活角色时自动弹出）
  · 强制保留角色（转生/弃演或离服 → 角色直接判死并留下遗体，档案不删除；关闭后离服不再自动判死）
- 新：K 面板操作行新增「转生/退役」按钮（强制保留开启时显示）——当前角色判定死亡+遗体落地，档案保留。
- 说明：旧职业 id（qdf_guard 等）被 PDF 编码 id 取代（s4_guard 等），旧档案显示原始 id，管理员可手动删除重建。

## 1.0.10（命令提示全量补齐）
- /rp help 重写：全量列出 9 大子命令完整参数签名（中英文），并新增 /rp help 别名。
- 所有子命令/叶子节点裸输或缺参时直接打印对应命令用法（faction/profession/character/state/xp/level/evac/settle/event/phase/animation/spawn 全部覆盖）。
- profession create 新增可选 [显示名]（支持中文，如 /rp profession create qso_director qso true 设施总监）。
- character create 的 name 参数改为 string 类型，支持中文角色名。

## 1.0.9（入场电影打磨）
- 自部署走电影时不再播放旧 spawn 动画（CCNR-RP 标题不再闪现在黑屏上/被电影覆盖）。
- 主标题（职业打字）移到屏幕正中央并最后绘制（在最上层）；徽标位置保持不变。
- 主标题字号 2.6→3.4，副标题四行放大 1.4 倍、行距加宽。

## 1.0.8（变量注入修复 + 部署入场电影）
- 修复：AnimationEngine.inject 字面量 bug（" + e.getKey() + " 永远不匹配）导致动画里 ${name}/${event}/${level} 全部失效；改为 ${key}/{key} 双写法替换，缺参保留原样（含回归测试）。
- 新：部署入场电影（CinematicS2C）——全屏黑 1s → 阵营大徽章突然出现 3s → 主标题打字显示职业 → 副标题逐行打字（项目名字/项目阵营/阵营关系(图谱推理)/项目简历）→ 停留 3s → 黑屏渐退 1.6s → 2s 后文字图标缓慢淡出；点击可随时跳过。
- 新：阵营关系类型语言键（敌对/中立/友好）。

## 1.0.7（预览旋转限位）
- 修复：3D 预览鼠标拖拽角度无上限（yaw 最高 85°）导致模型前倾时头部“穿出”预览框/屏幕。
- 旋转角钳制 yaw ≤45° / pitch ≤33°，缩放上限 110→95，模型始终完整留在框内。

## 1.0.6（预览放大 + 全透卡片 + 水印增强）
- 右栏空隙全部让给战术装备预览：移除 170px 高度上限，3D 模型按预览框高度动态放大（34~110 缩放）。
- 卡片/按钮/预览框全部改半透明（面板 70%、卡片 60%、按钮 68%），CCNR 竖版图标水印可透出；水印 alpha 0.28→0.38 并铺满面板高度。
- 修复：创建表单与皮肤上传之间的大片闲置区域（现由放大后的预览填充）。

## 1.0.5（UI 修复 + 直角 + 背景水印）
- 修复：角色档案/阵营导航全空的根因——buildRight 误清 navBounds/rowBounds 命中区；中列底衬改为不透明深色。
- 应要求全局去圆角（军事终端直角风格：卡片/按钮/胶囊/装备槽全部方角）。
- 终端背景加入 CCNR 竖版图标半透明水印（等比居中，alpha 0.28），保持面板可读性。

## 1.0.4（UI v3：SCP:NET 机密终端）
- 角色管理界面 v3 重构：严格三栏网格——左「机构分类」圆形徽章导航（盾牌/爪印/风暴/六边形/之眼/靶心，金/蓝/青按机构等级区分）；中「角色档案」列表；右「详细资料 + 真 3D 模型预览 + 战术装备槽（头/胸/腿/背）」。
- 色彩体系：冷暗金属底 + CRT 扫描线 + 四角角标（军事指挥中心/机密终端）；青/亮蓝主色（电子屏发光）；高饱和正红选中态（红底白字+红色高亮线+警戒线）；金色徽章（最高机密等级）。
- 3D 预览：真实玩家模型（上传皮肤自动注入，未上传回退默认），穿戴下界合金战术重装（盔甲+剑盾），跟随鼠标旋转；服装渲染复用原版 InventoryScreen 摄像机。
- 数据：阵营配置新增 icon（徽章图形）与 tier（等级 1-3 → 金/蓝/青）字段，默认与真实配置同步写入；S2C 列表附带 icon/tier/color。
- 页眉：左侧「身份数据库」（版本号）、右侧「CCNR:NET」+ 关闭按钮；底部系统消息红字警示。

- 修复：defaults/phases|events|animations.json 未进产物导致真实配置为空 {} 的问题（5 个默认资源齐全）。
- 默认配置补 5 个样例职业（QDF/QSA/研究所/后勤），职业下拉现开箱可用。
- UI：职业为空时给出管理员指引提示；加载器对空配置自动回退默认。

## 1.0.2（UI v2 + 错误修复）
- 角色管理界面 v2：移植 CCNR-Com 风格（SDF 圆角渲染/深色主题/自定义按钮/头像+状态胶囊/详情卡/创建表单卡）。
- 招募建议窗与右侧 HUD v2 同步重做；皮肤/角色创建职业索引联动修复。
- 修复：创建角色错误提示泄漏原始翻译键（改为结构化键+参数，客户端正确翻译）。

## 1.0.1（修复迭代）
- 客户端 MOD 总线拆分修复（ClientSetup 与 ClientForgeEvents 分开），真实客户端可正常加载。
- 补充 pack.mcmeta（消除 ResourcePackInfo 提示）与 CCNR-Com 风格图标（RP 右下角无背景）。
- 阵营×组批量关系修复（服务端验证：默认配置 6 阵营/2 组加载成功）。

## 1.0.0（P9 集成验收）

- 全流程串联：事件开始 → 刷新波/招募 → 部署装备+动画 → 判死/遗体 → 冷却 → 复活波 → 游戏结束疏散裁定 → 自动结算。
- 工程：spotless(palantirJavaFormat) + -Xlint:all + JUnit5(-PrunTests) + GitHub Actions CI（构建/测试/产物/Release）。
- 文档：docs/00~09（架构/工程规范/八大系统：数据模型、配置格式、命令、协议、WBS 与分阶段验收标准）。

## 0.9.0（P8 人物刷新框架）
- 阴间池/阳间池选人算法（等级→冷却→随机；不足回退观察者）。
- 自刷新（GUI 部署，selfDeploy 职业）与复活波（队伍创建 20t 轮询触发）。
- 招募兜底：RecruitOfferS2C → 屏幕右侧列表 + 弹出接受/拒绝窗（超时自动移除）。
- 部署链路：装备发放（含 NBT）→ 传送 deployAt → 状态 ALIVE → player_spawn 动画。
- /rp spawn list|trigger|enable。

## 0.8.0（P7 动画系统）
- 数据驱动序列：TITLE/ACTIONBAR/FADE/CAMERA/PARTICLE/SOUND/GROUP，参数注入，时长钳制。
- 服务端执行粒子/音效/ActionBar；客户端执行标题/遮罩；钩子 player_spawn|player_death|game_end|event_start|level_up。

## 0.7.0（P6 事件系统）
- phases.json 阶段表 + PhaseClock 自动/手动推进。
- 五类触发器（ON_PHASE_START/END、ON_TIME、PERIODIC、CONDITION）。
- 事件生命周期 SCHEDULED→RUNNING→SETTLED；结束自动结算（P5）；gameover 疏散裁定。

## 0.6.0（P5 经验系统）
- LevelCurve（xpForLevel/level）；三来源（值班时间/任务行为/疏散方式）增量结算，账本幂等。
- /rp settle、xp、level、evac set；升级广播 + level_up 钩子。

## 0.5.0（P4 状态+Corpse）
- 状态机（ALIVE/DEAD/OBSERVING），掉线判死（事件主路径+轮询兜底，幂等），冷却。
- Corpse 可选联动（生成可搜刮遗体），未安装降级原生死亡；/rp state、kill。

## 0.4.0（P3 角色管理）
- 角色数据（world/ccnr_rp/characters.json，原子写+损坏 .bak）。
- ccnr_rp:main 网络通道；角色管理界面（列表/创建/皮肤上传/操作按钮）；/rp character。

## 0.3.0（P2 人物刷新配置）
- 职业定义（挂在阵营下）+ selfDeploy；装备 NBT base64 存取；loadout 采集/发放。
- /rp profession create|save|list|load。

## 0.2.0（P1 阵营关系）
- 阵营/组/关系（hostile|neutral|friendly），组×组批量、单点优先、重复覆盖 WARN。
- /rp faction list|relation|group；默认配置预置量子科学组织。

## 0.1.0-alpha（工程初始化）
- 骨架、构建、任务分解与验收体系。
