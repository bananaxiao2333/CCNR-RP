/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import com.ccnrcom.rp.profession.ProfessionJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** factions.json 中"professions"段的读写（P2）。 */
public final class FactionProfessions {

    private FactionProfessions() {}

    /** 找职业定义。 */
    public static Optional<JsonObject> find(JsonObject root, String id) {
        for (JsonObject o : all(root)) {
            if (str(o, "id", "").equals(id)) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    public static List<String> ids(JsonObject root) {
        List<String> out = new ArrayList<>();
        for (JsonObject o : all(root)) {
            out.add(str(o, "id", "?"));
        }
        return out;
    }

    /** 校验并写入（upsert），返回错误列表（空=成功）。music/profile 为空串时从定义中移除。
     *  radio 为 null 表示不修改无线电；非 null 时整体替换（含清空）。 */
    public static List<String> upsert(
            JsonObject root,
            String id,
            String name,
            String factionId,
            boolean selfDeploy,
            int unlockLevel,
            JsonObject loadout,
            String music,
            String profile,
            String cmdcamScene,
            JsonObject radio,
            boolean radioDisabled,
            java.util.function.Predicate<String> factionExists) {
        List<String> errors = new ArrayList<>();
        if (id == null || id.isBlank()) {
            errors.add("职业 id 不能为空");
        }
        if (!factionExists.test(factionId)) {
            errors.add("未知阵营: " + factionId);
        }
        if (loadout != null) {
            errors.addAll(ProfessionJson.validate(loadout));
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        JsonArray profs = root.has("professions") ? root.getAsJsonArray("professions") : new JsonArray();
        root.add("professions", profs);
        JsonObject picked = null;
        for (int i = 0; i < profs.size(); i++) {
            JsonObject o = profs.get(i).getAsJsonObject();
            if (str(o, "id", "").equals(id)) {
                picked = o;
                break;
            }
        }
        if (picked == null) {
            picked = new JsonObject();
            picked.addProperty("id", id);
            profs.add(picked);
        }
        picked.addProperty("name", name == null || name.isBlank() ? id : name);
        picked.addProperty("factionId", factionId);
        picked.addProperty("selfDeploy", selfDeploy);
        picked.addProperty("unlockLevel", Math.max(0, unlockLevel));
        picked.add("loadout", loadout == null ? ProfessionJson.emptyLoadout() : loadout);
        if (music != null && !music.isBlank()) {
            picked.addProperty("music", music);
        } else {
            picked.remove("music");
        }
        if (profile != null && !profile.isBlank()) {
            picked.addProperty("profile", profile);
        } else {
            picked.remove("profile");
        }
        if (cmdcamScene != null && !cmdcamScene.isBlank()) {
            picked.addProperty("cmdcamScene", cmdcamScene);
        } else {
            picked.remove("cmdcamScene");
        }
        // 无线电：null=不修改；非 null 整体替换（空对象/空 lines = 移除无线电）
        if (radio != null) {
            if (hasRadioLines(radio)) {
                picked.add("radio", radio.deepCopy());
            } else {
                picked.remove("radio");
            }
        }
        // 禁用无线电开关（职业级；true=该职业不播任何无线电，含阵营默认）
        picked.addProperty("radioDisabled", radioDisabled);
        return List.of();
    }

    /** 无线电是否含有效句子（lines 非空数组）。 */
    public static boolean hasRadioLines(JsonObject radio) {
        return radio != null
                && radio.has("lines")
                && radio.get("lines").isJsonArray()
                && radio.getAsJsonArray("lines").size() > 0;
    }

    /** 无线电配置（职业级，可选）：{speaker, lines:[{text, wait}]}；空=未配置。 */
    public static JsonObject radio(JsonObject def) {
        return def != null && def.has("radio") && def.get("radio").isJsonObject() ? def.getAsJsonObject("radio") : null;
    }

    /** 职业禁用无线电开关（true=该职业不播任何无线电）。 */
    public static boolean radioDisabled(JsonObject def) {
        return def != null
                && def.has("radioDisabled")
                && def.get("radioDisabled").getAsBoolean();
    }

    /** 删除职业定义（root 就地修改）；返回是否找到并删除。 */
    public static boolean delete(JsonObject root, String id) {
        JsonArray profs = root.has("professions") ? root.getAsJsonArray("professions") : null;
        if (profs == null) {
            return false;
        }
        for (int i = 0; i < profs.size(); i++) {
            if (profs.get(i).isJsonObject()
                    && str(profs.get(i).getAsJsonObject(), "id", "").equals(id)) {
                profs.remove(i);
                return true;
            }
        }
        return false;
    }

    /** 常见于命令回显的稳定名称。 */
    public static String idsSafeName(JsonObject def) {
        return str(def, "name", str(def, "id", "?"));
    }

    public static boolean selfDeploy(JsonObject def) {
        return def.has("selfDeploy") && def.get("selfDeploy").getAsBoolean();
    }

    /** 职位解锁等级：玩家等级达到该值才能该职位部署；缺省 0（无门槛）。 */
    public static int unlockLevel(JsonObject def) {
        try {
            return def.has("unlockLevel") ? Math.max(0, def.get("unlockLevel").getAsInt()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String factionId(JsonObject def) {
        return str(def, "factionId", "");
    }

    /** 出场音乐（可选）：配置内相对路径（相对 config/ccnr_rp/）或绝对路径；空串=无。 */
    public static String music(JsonObject def) {
        return str(def, "music", "");
    }

    /** 项目简历（可选）：职业档案简介，入场电影/档案卡展示用。 */
    public static String profile(JsonObject def) {
        return str(def, "profile", "");
    }

    /** CMDCam 出场场景（可选）：部署入场电影播完黑屏转场播放该摄像机场景；空串=无（阵营/刷新波默认值回退）。 */
    public static String cmdcamScene(JsonObject def) {
        return str(def, "cmdcamScene", "");
    }

    /** 职业部署点（复活点）：从职业定义解析 spawn 字段（rule + points）；未配置返回 null。 */
    public static com.ccnrcom.rp.faction.FactionManager.FactionSpawn spawn(JsonObject def) {
        if (def == null || !def.has("spawn") || !def.get("spawn").isJsonObject()) {
            return null;
        }
        JsonObject sp = def.getAsJsonObject("spawn");
        String rule = com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SINGLE.equalsIgnoreCase(str(sp, "rule", ""))
                ? com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SINGLE
                : com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SPREAD;
        List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = new ArrayList<>();
        if (sp.has("points") && sp.get("points").isJsonArray()) {
            for (com.google.gson.JsonElement e : sp.getAsJsonArray("points")) {
                if (e.isJsonObject()) {
                    JsonObject pp = e.getAsJsonObject();
                    pts.add(new com.ccnrcom.rp.faction.FactionManager.SpawnPoint(
                            dbl(pp, "x", 0), dbl(pp, "y", 64), dbl(pp, "z", 0), str(pp, "dim", "minecraft:overworld")));
                }
            }
        }
        return new com.ccnrcom.rp.faction.FactionManager.FactionSpawn(pts, rule);
    }

    private static double dbl(JsonObject o, String key, double def) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def; // 畸形配置（非数字）不崩服
        }
    }

    public static JsonObject loadout(JsonObject def) {
        return def.has("loadout") && def.get("loadout").isJsonObject()
                ? def.getAsJsonObject("loadout")
                : ProfessionJson.emptyLoadout();
    }

    public static List<JsonObject> all(JsonObject root) {
        List<JsonObject> out = new ArrayList<>();
        if (root.has("professions")) {
            JsonArray a = root.getAsJsonArray("professions");
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i).isJsonObject()) {
                    out.add(a.get(i).getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** 管理面板职业表单保存的完整参数（resolveSave 输出，可直接喂 upsert）。 */
    public record ProfessionSave(
            String id,
            String name,
            String factionId,
            boolean selfDeploy,
            int unlockLevel,
            JsonObject loadout,
            String music,
            String profile,
            String cmdcamScene,
            JsonObject radio,
            boolean radioDisabled) {}

    /**
     * 解析管理面板职业表单的保存参数：payload（表单输出）未携带的字段从现有定义继承——
     * 自部署开关 / 装备 loadout / 无线电禁用不在表单内，缺省继承原值，防止保存把原数据清空
     * （保存 = 表单输出覆盖到原数据上，而不是整条重建）。existing 为 null（新建）时
     * loadout 返回 null（upsert 落空装备），开关回退 false。
     */
    public static ProfessionSave resolveSave(JsonObject payload, JsonObject existing) {
        boolean selfDeploy = payload.has("selfDeploy")
                ? payload.get("selfDeploy").getAsBoolean()
                : existing != null && selfDeploy(existing);
        JsonObject loadout = existing != null ? loadout(existing) : null;
        JsonObject radio =
                payload.has("radio") && payload.get("radio").isJsonObject() ? payload.getAsJsonObject("radio") : null;
        boolean radioDisabled = payload.has("radioDisabled")
                ? payload.get("radioDisabled").getAsBoolean()
                : existing != null && radioDisabled(existing);
        return new ProfessionSave(
                str(payload, "id", ""),
                str(payload, "name", existing == null ? str(payload, "id", "") : str(existing, "name", "")),
                str(payload, "factionId", existing == null ? "" : factionId(existing)),
                selfDeploy,
                unlockLevel(payload),
                loadout,
                str(payload, "music", ""),
                str(payload, "profile", ""),
                str(payload, "cmdcamScene", ""),
                radio,
                radioDisabled);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
