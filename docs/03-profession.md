# P2 人物刷新配置（docs/03）

## 1. 需求
管理员用命令把**自己的背包/装备（含 NBT）保存**为配置；职业挂在阵营之下；
部署角色（P8）时按配置发放装备。

## 2. 数据模型
- `ProfessionDefinition {id, name, factionId, selfDeploy:boolean, loadout}`
- `Loadout {inventory: SlotItem[], armor: SlotItem[], offhand: SlotItem}`
- `SlotItem {slot:int, item:ResourceLocation, count:int, nbt:base64}`（nbt = NbtUtils.base64 化的 CompoundTag；
  库存放兼容 `net.minecraft.nbt` 的任意内容：附魔、组件、自定义键）。

## 3. 配置位置
`config/ccnr_rp/factions.json` 的 `"professions": [...]` 段（职业挂在阵营下，与阵营同文件、同重载）。
```json
"professions": [
  {"id": "qdf_guard", "name": "QDF队员", "factionId": "qdf", "selfDeploy": false,
   "loadout": {
     "inventory": [{"slot": 0, "item": "minecraft:iron_sword", "count": 1, "nbt": "H4sIAA..."}],
     "armor": [
       {"slot": "feet", "item": "minecraft:leather_boots", "count": 1, "nbt": null},
       {"slot": "head", "item": "minecraft:iron_helmet", "count": 1, "nbt": null}
     ],
     "offhand": {"slot": 40, "item": "minecraft:shield", "count": 1, "nbt": null}
   }
  }
]
```

## 4. 命令（OP≥2 或 ccnnrp.admin.profession）
- `/rp profession save <id> [--hotbar|--full]`：把执行者背包/护甲/副手保存到该职业（每槽全覆盖；--full 含全背包，默认 --hotbar）。
- `/rp profession list`；`/rp profession load <id>`（调试：给自己套用，验证 roundtrip）。

## 5. WBS 小任务
1. ProfessionDefinition + Loadout 模型；2. `ItemStackCodec`：ItemStack ↔ base64 NBT JSON（含槽位语义）；
3. 命令 save/list/load + `LoadoutManager.apply(player, loadout)`；4. 校验器（未知阵营、缺字段、非法槽位 → 文件:行号）；
5. 单测（roundtrip 含自定义 NBT、失败路径、--hotbar/--full 差异）。

## 6. 验收标准
1. roundtrip 单测：附魔+自定义 NBT 的剑 save→load 后与原始 ItemStack bit 级一致（NBT 深度相等）。
2. 校验器：未知 factionId 报错且不写盘；缺失 `selfDeploy` 视为 false 并 WARN。
3. `/rp profession save qdf_guard` 后 `list` 可见且 `load` 还原一致（dev 环境演练）。
4. 单测 + spotlessCheck + clean build 全绿；LangFileTest 通过。
