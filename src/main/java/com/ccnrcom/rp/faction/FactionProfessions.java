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

    /** 校验并写入（upsert），返回错误列表（空=成功）。music/profile 为空串时从定义中移除。 */
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
        return List.of();
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

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
