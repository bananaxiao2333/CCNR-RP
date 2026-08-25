# P3 角色数据与角色管理界面（docs/04）

## 1. 需求
玩家创建角色（名字 / 阵营 / 职业 / 背景），每个角色可单独上传一张皮肤图片；
角色死亡后进入冷却（时长 serverconfig）；创建/部署在**游戏内角色管理界面**完成。

## 2. 数据模型（world/ccnr_rp/characters.json）
```json
{
  "version": 1,
  "characters": [
    {"id": "1a2b...", "playerUuid": "uuid", "name": "陆佐", "factionId": "qdf",
     "professionId": "qdf_guard", "background": "前量子安保，现驻守 C-3 区",
     "skin": "1a2b....png", "skinHash": "sha256hex",
     "status": "observing", "xp": 0, "dutySeconds": 0, "cooldownUntil": 0,
     "tasks": {}, "evacuation": "none", "createdAt": 1700000000000}
  ]
}
```
- 一个玩家可建多个角色；同一时刻只有一个角色为 `alive`，其余 `observing`/`dead`。
- 皮肤规则：PNG、MC 皮肤规格（64×64 或 64×32）、≤256KB；服务端校验（SkinSpec：PNG 魔数/尺寸）→ 按 sha256 哈希去重存 `world/ccnr_rp/skins/<hash>.png`（旧 `<charId>.png` 自动清理/回退兼容）；
  S2C 广播（含 playerUuid）→ 客户端缓存 `config/ccnr_rp/skins-cache/` 供头像渲染与全服换肤；非法图片拒绝并回显原因。
- 上传来源：本地文件路径或 http(s) URL（客户端后台拉取后走同一分片协议）。
- 皮肤回收：无角色引用的皮肤在角色删除/换肤后与服务启动时自动清理（哈希去重 + 引用计数语义，旧式文件迁移期不误删）。
- 扮演换肤：角色部署/登录时服务端重播皮肤，客户端把正在扮演该角色的玩家渲染为目标角色皮肤（分毫不差）。
- 冷却：死亡时 `cooldownUntil = now + deathCooldownMinutes`（serverconfig，默认 30）；GUI/命令可查倒计时。

## 3. 角色管理界面（客户端）
- 入口：`/rp ui` 或按键（`key.ccnr_rp.character_menu`，默认 K；分类 `key.categories.ccnr_rp`）。
- 布局：左侧角色列表（名称/阵营徽标/状态/冷却倒计时）；右侧详情与操作：
  - 创建表单（名字、阵营下拉、职业下拉[按所选阵营过滤]、背景文本域）
  - 皮肤区：列出可用皮肤缩略图 + "上传新皮肤"（本地文件路径输入或客户端 `config/ccnr_rp/skins/` 文件列表）
  - 操作按钮：选择、部署（selfDeploy 校验在 P8 生效）、切换观察、删除（二次确认）
- 服务端校验所有增删改（客户端仅 UI，权力在服务端）。

## 4. 网络（ccnr_rp:main，SimpleChannel，version=1）
C2S：`RequestCharacterListC2S`、`CharacterCreateC2S`、`CharacterSelectC2S`、`CharacterDeleteC2S`、
`CharacterObserveC2S`、`CharacterDeployC2S`、`SkinUploadPartC2S {charId,index,total,data}`、`SkinUploadCommitC2S`。
S2C：`CharacterListS2C`（登录/请求全量）、`CharacterUpdateS2C`、`CharacterRemoveS2C`、`SkinSyncS2C {charId,data,hash}`、
`SkinRemoveS2C`、`ErrorS2C {code,messageKey,args}`。
- 皮肤上传分包容量 32KB/包；服务端重组后校验并落盘，成功后广播；失败回 `ErrorS2C`。

## 5. 命令（玩家层）
`/rp character create|list|info <id>|select <id>|delete <id>|cooldown <id>|observe <id>|activate <id>|deploy <id>`。
（deploy/observe 的服务端规则见 docs/05 状态机与 docs/09 刷新框架。）

## 6. WBS 小任务
1. CharacterData + `CharacterStore`（CRUD/原子写/损坏 .bak 恢复/贪心"每玩家单 alive"约束）；2. 网络通道 + 包注册；
3. 服务端角色服务（创建/选择/删除/观察/激活的规则与校验）；4. 角色管理界面（列表/表单/皮肤选择/操作按钮）；
5. 皮肤上传链路（客户端读取+分包+服务端校验+广播+缓存）；6. 命令族；7. 单测 + 手工演练。

## 7. 验收标准
1. `CharacterStore` 单测：CRUD、并发"单 alive"约束、原子写中断恢复（模拟坏 JSON → .bak 保留且服务可继续）、
   非法皮肤（非 PNG/超尺寸/超 512×512）拒绝并回显原因 —— 全绿。
2. 手工演练：创建 3 个角色 → 切换 → 上传皮肤 → 重启服务器后数据与皮肤均在。
3. `/rp character cooldown` 正确显示倒计时；冷却结束自动清除显示。
4. spotlessCheck / clean build / test -PrunTests / LangFileTest 全绿。
