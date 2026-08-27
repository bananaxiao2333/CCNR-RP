/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.FactionGroup;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 阵营配置管理器：config/ccnr_rp/factions.json 的加载/校验/保存/重载。
 * 首次启动缺失时从 assets/ccnr_rp/defaults/factions.json 生成默认（含《职位划分》示例组织）。
 * 校验失败：写 ERROR 日志并继续用空图运行（服务不崩溃）。
 */
public final class FactionManager {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Path file;
    private JsonObject root;
    private FactionGraph graph =
            FactionGraph.parse(List.of(), List.of(), List.of()).graph();

    public FactionManager() {
        this.file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("factions.json");
    }

    /** 加载（文件缺失→生成默认；解析失败→空图+日志）。 */
    public void load() {
        Optional<JsonObject> cached = JsonUtil.readObject(file);
        if (cached.isPresent()) {
            this.root = cached.get();
        } else {
            this.root = JsonUtil.readResource("/assets/ccnr_rp/defaults/factions.json")
                    .orElseGet(this::emptyRoot);
            JsonUtil.atomicWrite(file, root);
            LOGGER.info("[CCNR-RP] 已生成默认阵营配置: {}", file);
        }
        reloadFromRoot();
    }

    private void reloadFromRoot() {
        ParseResult result = parse(root);
        if (!result.success()) {
            LOGGER.error("[CCNR-RP] factions.json 校验失败，使用空阵容运行: {}", result.errors());
            this.graph = FactionGraph.parse(List.of(), List.of(), List.of()).graph();
            return;
        }
        result.warnings().forEach(w -> LOGGER.warn("[CCNR-RP] factions.json: {}", w));
        this.graph = result.graph();
    }

    private JsonObject emptyRoot() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.add("factions", new JsonArray());
        o.add("groups", new JsonArray());
        o.add("relations", new JsonArray());
        o.add("professions", new JsonArray());
        return o;
    }

    public static ParseResult parse(JsonObject root) {
        List<Faction> factions = new ArrayList<>();
        JsonArray fa = root.has("factions") ? root.getAsJsonArray("factions") : new JsonArray();
        for (int i = 0; i < fa.size(); i++) {
            JsonObject o = fa.get(i).getAsJsonObject();
            if (!o.has("id")) {
                return ParseResult.failure(List.of("factions[" + i + "]: 缺少 id"));
            }
            factions.add(new Faction(
                    o.get("id").getAsString(),
                    str(o, "name", o.get("id").getAsString()),
                    str(o, "color", "#FFFFFF"),
                    str(o, "description", ""),
                    str(o, "icon", "hex"),
                    Math.max(1, Math.min(3, intOf(o, "tier", 2))),
                    str(o, "music", ""),
                    str(o, "cmdcamScene", "")));
        }
        List<FactionGroup> groups = new ArrayList<>();
        JsonArray ga = root.has("groups") ? root.getAsJsonArray("groups") : new JsonArray();
        for (int i = 0; i < ga.size(); i++) {
            JsonObject o = ga.get(i).getAsJsonObject();
            if (!o.has("id")) {
                return ParseResult.failure(List.of("groups[" + i + "]: 缺少 id"));
            }
            List<String> members = new ArrayList<>();
            if (o.has("memberIds")) {
                for (JsonElement e : o.getAsJsonArray("memberIds")) {
                    members.add(e.getAsString());
                }
            }
            groups.add(new FactionGroup(o.get("id").getAsString(), members));
        }
        List<RelationRule> rules = new ArrayList<>();
        JsonArray ra = root.has("relations") ? root.getAsJsonArray("relations") : new JsonArray();
        for (int i = 0; i < ra.size(); i++) {
            JsonObject o = ra.get(i).getAsJsonObject();
            if (!o.has("from")) {
                return ParseResult.failure(List.of("relations[" + i + "]: 缺少 from"));
            }
            RelationType type = RelationType.parse(str(o, "type", ""));
            if (type == null) {
                return ParseResult.failure(List.of("relations[" + i + "]: 无效类型 '" + str(o, "type", "") + "'"));
            }
            // from/to 支持数组（多对多）或单字符串（兼容旧配置）；省略 to = 内部关系（单列表内两两互设）
            List<String> from = idList(o.get("from"));
            List<String> to = o.has("to") ? idList(o.get("to")) : from;
            if (from.isEmpty() || to.isEmpty()) {
                return ParseResult.failure(List.of("relations[" + i + "]: from/to 为空"));
            }
            rules.add(new RelationRule(from, to, type));
        }
        return FactionGraph.parse(factions, groups, rules);
    }

    /** 写入阵营音乐：空串=移除字段（与职业 music 语义一致）。 */
    private static void putMusic(JsonObject o, String music) {
        if (music != null && !music.isBlank()) {
            o.addProperty("music", music);
        } else {
            o.remove("music");
        }
    }

    /** 写入阵营 CMDCam 出场场景：空串=移除字段。 */
    private static void putCamScene(JsonObject o, String scene) {
        if (scene != null && !scene.isBlank()) {
            o.addProperty("cmdcamScene", scene);
        } else {
            o.remove("cmdcamScene");
        }
    }

    /** from/to 项：数组 = 多对多列表；单字符串 = 兼容旧格式单元素。 */
    private static List<String> idList(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el == null || el.isJsonNull()) {
            return out;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    out.add(e.getAsString());
                }
            }
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            out.add(el.getAsString());
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int intOf(JsonObject o, String key, int def) {
        return o.has(key)
                        && o.get(key).isJsonPrimitive()
                        && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsInt()
                : def;
    }

    public FactionGraph graph() {
        return graph;
    }

    public boolean hasFactionOrGroup(String id) {
        return graph.factions().containsKey(id) || graph.groups().containsKey(id);
    }

    /** 设置单对关系并落盘（兼容命令入口，写为单元素数组）；校验失败时回滚（不写盘）并返回错误消息列表。 */
    public List<String> setRelation(String a, String b, RelationType type) {
        if (!hasFactionOrGroup(a) || !hasFactionOrGroup(b)) {
            return List.of("未找到阵营或组: " + a + " / " + b);
        }
        JsonObject candidate = root.deepCopy();
        JsonArray relations = candidate.has("relations") ? candidate.getAsJsonArray("relations") : new JsonArray();
        candidate.add("relations", relations);
        boolean replaced = false;
        for (int i = 0; i < relations.size(); i++) {
            JsonObject o = relations.get(i).getAsJsonObject();
            if (samePair(o, a, b)) {
                o.remove("from");
                o.remove("to");
                JsonArray fa = new JsonArray();
                fa.add(a);
                JsonArray ta = new JsonArray();
                ta.add(b);
                o.add("from", fa);
                o.add("to", ta);
                o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            JsonObject o = new JsonObject();
            JsonArray fa = new JsonArray();
            fa.add(a);
            JsonArray ta = new JsonArray();
            ta.add(b);
            o.add("from", fa);
            o.add("to", ta);
            o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
            relations.add(o);
        }
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        JsonUtil.atomicWrite(file, candidate);
        this.root = candidate;
        this.graph = result.graph();
        return List.of();
    }

    /** 关系条目是否恰好声明该单对（兼容数组与旧字符串两种格式）。 */
    private static boolean samePair(JsonObject o, String a, String b) {
        List<String> from = idList(o.get("from"));
        List<String> to = idList(o.get("to"));
        return (from.size() == 1
                        && to.size() == 1
                        && from.get(0).equals(a)
                        && to.get(0).equals(b))
                || (from.size() == 1
                        && to.size() == 1
                        && from.get(0).equals(b)
                        && to.get(0).equals(a));
    }

    /**
     * 关系规则 CRUD（管理面板 RelationEditC2S 服务端入口）：payload = {action, rule?{from,to,type}, original?{from,to}}。
     * action: add / update / remove。update 携带 original 时按原始 from/to 定位原位替换（方向对称）；
     * 无 original 回退按新值 upsert；remove 按 rule 的 from/to 匹配定位。返回错误列表（空 = 成功）。
     */
    public static void onRelationEdit(net.minecraft.server.level.ServerPlayer player, String payload) {
        if (player == null || com.ccnrcom.rp.CCNRRPMod.factions == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            com.ccnrcom.rp.network.RpChannels.sendTo(
                    player, new com.ccnrcom.rp.network.RpPackets.ErrorS2C("ccnr_rp.command.no_permission"));
            return;
        }
        JsonObject req = null;
        try {
            req = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        } catch (Exception ignored) {
            // 解析失败按空载荷处理
        }
        if (req == null) {
            com.ccnrcom.rp.network.RpChannels.sendTo(
                    player, new com.ccnrcom.rp.network.RpPackets.ErrorS2C("ccnr_rp.error.invalid_argument", "载荷为空"));
            return;
        }
        String action = str(req, "action", "");
        // 载荷结构为 {action, rule{from,to,type}, original?}：必须先取出嵌套的 rule，否则 CRUD 读到空 from
        JsonObject rule = req.has("rule") && req.get("rule").isJsonObject() ? req.getAsJsonObject("rule") : null;
        List<String> errors;
        if ("remove".equals(action)) {
            errors = rule == null ? List.of("缺少 rule") : com.ccnrcom.rp.CCNRRPMod.factions.removeRelation(rule);
        } else if ("add".equals(action)) {
            errors = rule == null ? List.of("缺少 rule") : com.ccnrcom.rp.CCNRRPMod.factions.upsertRelation(rule);
        } else if ("update".equals(action)) {
            // 管理面板「保存」携带 original：按选中规则的原始 from/to 定位原位替换（支持修改 from/to）；
            // 旧载荷无 original 时回退按新值 upsert（兼容命令/旧客户端）
            JsonObject original =
                    req.has("original") && req.get("original").isJsonObject() ? req.getAsJsonObject("original") : null;
            if (rule == null) {
                errors = List.of("缺少 rule");
            } else if (original != null && original.has("from")) {
                errors = com.ccnrcom.rp.CCNRRPMod.factions.updateRelation(rule, original);
            } else {
                errors = com.ccnrcom.rp.CCNRRPMod.factions.upsertRelation(rule);
            }
        } else {
            errors = List.of("未知操作: " + action);
        }
        if (errors.isEmpty()) {
            com.ccnrcom.rp.network.RpChannels.sendTo(
                    player, new com.ccnrcom.rp.network.RpPackets.ErrorS2C("ccnr_rp.faction.relation.saved"));
        } else {
            for (String e2 : errors) {
                com.ccnrcom.rp.network.RpChannels.sendTo(
                        player, new com.ccnrcom.rp.network.RpPackets.ErrorS2C("ccnr_rp.faction.error.config", e2));
            }
        }
    }

    /** 新增或更新一条多对多关系规则（按 from/to 列表集合匹配，方向对称）；返回错误列表（空=成功）。 */
    public List<String> upsertRelation(JsonObject rule) {
        List<String> errors = new ArrayList<>();
        List<String> from = idList(rule.get("from"));
        List<String> to = rule.has("to") ? idList(rule.get("to")) : from;
        RelationType type = RelationType.parse(str(rule, "type", ""));
        if (from.isEmpty() || to.isEmpty()) {
            return List.of("from/to 不能为空");
        }
        if (type == null) {
            return List.of("无效关系类型");
        }
        for (String s : from) {
            if (!hasFactionOrGroup(s)) {
                errors.add("未知的 from 项: " + s);
            }
        }
        for (String s : to) {
            if (!hasFactionOrGroup(s)) {
                errors.add("未知的 to 项: " + s);
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        JsonObject candidate = root.deepCopy();
        JsonArray relations = candidate.has("relations") ? candidate.getAsJsonArray("relations") : new JsonArray();
        candidate.add("relations", relations);
        boolean replaced = false;
        for (int i = 0; i < relations.size(); i++) {
            if (matchesLists(relations.get(i).getAsJsonObject(), from, to)) {
                JsonObject o = relations.get(i).getAsJsonObject();
                o.remove("from");
                o.remove("to");
                JsonArray fa = new JsonArray();
                from.forEach(fa::add);
                JsonArray ta = new JsonArray();
                to.forEach(ta::add);
                o.add("from", fa);
                o.add("to", ta);
                o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            JsonObject o = new JsonObject();
            JsonArray fa = new JsonArray();
            from.forEach(fa::add);
            JsonArray ta = new JsonArray();
            to.forEach(ta::add);
            o.add("from", fa);
            o.add("to", ta);
            o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
            relations.add(o);
        }
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        JsonUtil.atomicWrite(file, candidate);
        this.root = candidate;
        this.graph = result.graph();
        return List.of();
    }

    /**
     * 更新关系规则：按 original 的 from/to 列表集合定位（方向对称），原位替换为 rule 的新内容并保持优先级位置；
     * 未找到返回错误。支持修改 from/to（不再按新值误增一条新规则）。
     */
    public List<String> updateRelation(JsonObject rule, JsonObject original) {
        List<String> from = idList(rule.get("from"));
        List<String> to = rule.has("to") ? idList(rule.get("to")) : from;
        RelationType type = RelationType.parse(str(rule, "type", ""));
        if (from.isEmpty() || to.isEmpty()) {
            return List.of("from/to 不能为空");
        }
        if (type == null) {
            return List.of("无效关系类型");
        }
        List<String> errors = new ArrayList<>();
        for (String s : from) {
            if (!hasFactionOrGroup(s)) {
                errors.add("未知的 from 项: " + s);
            }
        }
        for (String s : to) {
            if (!hasFactionOrGroup(s)) {
                errors.add("未知的 to 项: " + s);
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        List<String> of = idList(original.get("from"));
        List<String> ot = original.has("to") ? idList(original.get("to")) : of;
        JsonObject candidate = root.deepCopy();
        JsonArray relations = candidate.has("relations") ? candidate.getAsJsonArray("relations") : new JsonArray();
        boolean replaced = false;
        for (int i = 0; i < relations.size(); i++) {
            if (matchesLists(relations.get(i).getAsJsonObject(), of, ot)) {
                JsonObject o = relations.get(i).getAsJsonObject();
                o.remove("from");
                o.remove("to");
                o.remove("type");
                JsonArray fa = new JsonArray();
                from.forEach(fa::add);
                JsonArray ta = new JsonArray();
                to.forEach(ta::add);
                o.add("from", fa);
                o.add("to", ta);
                o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            return List.of("未找到该关系规则");
        }
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        JsonUtil.atomicWrite(file, candidate);
        this.root = candidate;
        this.graph = result.graph();
        return List.of();
    }

    /** 删除关系规则（按 from/to 列表集合匹配，方向对称）；返回错误列表（空=成功）。 */
    public List<String> removeRelation(JsonObject rule) {
        List<String> from = idList(rule.get("from"));
        List<String> to = rule.has("to") ? idList(rule.get("to")) : from;
        JsonObject candidate = root.deepCopy();
        JsonArray relations = candidate.has("relations") ? candidate.getAsJsonArray("relations") : new JsonArray();
        boolean removed = false;
        for (int i = 0; i < relations.size(); i++) {
            if (matchesLists(relations.get(i).getAsJsonObject(), from, to)) {
                relations.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) {
            return List.of("未找到该关系规则");
        }
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        JsonUtil.atomicWrite(file, candidate);
        this.root = candidate;
        this.graph = result.graph();
        return List.of();
    }

    /** 关系条目是否声明了相同的 from/to 列表集合（方向对称）。 */
    private static boolean matchesLists(JsonObject o, List<String> from, List<String> to) {
        List<String> of = idList(o.get("from"));
        List<String> ot = idList(o.get("to"));
        return sameSet(of, from) && sameSet(ot, to) || sameSet(of, to) && sameSet(ot, from);
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> sa = new ArrayList<>(a);
        List<String> sb = new ArrayList<>(b);
        java.util.Collections.sort(sa);
        java.util.Collections.sort(sb);
        return sa.equals(sb);
    }

    /** 创建阵营组并落盘。 */
    public List<String> createGroup(String id, List<String> members) {
        if (graph.factions().containsKey(id) || graph.groups().containsKey(id)) {
            return List.of("阵营或组已存在: " + id);
        }
        JsonObject candidate = root.deepCopy();
        JsonArray groups = candidate.has("groups") ? candidate.getAsJsonArray("groups") : new JsonArray();
        candidate.add("groups", groups);
        for (String m : members) {
            if (!graph.factions().containsKey(m)) {
                return List.of("成员不是已知阵营: " + m);
            }
        }
        JsonObject g = new JsonObject();
        g.addProperty("id", id);
        JsonArray memberIds = new JsonArray();
        members.forEach(memberIds::add);
        g.add("memberIds", memberIds);
        groups.add(g);
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        JsonUtil.atomicWrite(file, candidate);
        this.root = candidate;
        this.graph = result.graph();
        return List.of();
    }

    /** 职业定义管理（P2，读写同一个 factions.json）。 */
    public List<String> upsertProfession(
            String id,
            String name,
            String factionId,
            boolean selfDeploy,
            int unlockLevel,
            com.google.gson.JsonObject loadout) {
        return upsertProfession(id, name, factionId, selfDeploy, unlockLevel, loadout, "", "", "");
    }

    /** 职业定义管理（含出场音乐、项目简历与 CMDCam 出场场景）。 */
    public List<String> upsertProfession(
            String id,
            String name,
            String factionId,
            boolean selfDeploy,
            int unlockLevel,
            com.google.gson.JsonObject loadout,
            String music,
            String profile,
            String cmdcamScene) {
        JsonObject candidate = root.deepCopy();
        List<String> errors = FactionProfessions.upsert(
                candidate,
                id,
                name,
                factionId,
                selfDeploy,
                unlockLevel,
                loadout,
                music,
                profile,
                cmdcamScene,
                f -> graph.factions().containsKey(f));
        if (!errors.isEmpty()) {
            return errors;
        }
        if (!JsonUtil.atomicWrite(file, candidate)) {
            return List.of("配置文件写入失败");
        }
        this.root = candidate;
        return List.of();
    }

    /** 删除职业定义。 */
    public List<String> deleteProfession(String id) {
        JsonObject candidate = root.deepCopy();
        if (!FactionProfessions.delete(candidate, id)) {
            return List.of("未找到职业: " + id);
        }
        if (!JsonUtil.atomicWrite(file, candidate)) {
            return List.of("配置文件写入失败");
        }
        this.root = candidate;
        return List.of();
    }

    // ---------- 阵营 CRUD（管理器） ----------

    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("[a-z0-9_]{1,32}");

    /** 创建阵营。music 为空串时不写入（不设阵营音乐）；cmdcamScene 空串不写入。 */
    public List<String> createFaction(
            String id,
            String name,
            String color,
            String description,
            String icon,
            int tier,
            String music,
            String cmdcamScene) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            return List.of("阵营 id 仅允许小写字母/数字/下划线，1-32 字符");
        }
        if (graph.factions().containsKey(id) || graph.groups().containsKey(id)) {
            return List.of("阵营或组已存在: " + id);
        }
        JsonObject candidate = root.deepCopy();
        JsonArray fa = candidate.has("factions") ? candidate.getAsJsonArray("factions") : new JsonArray();
        candidate.add("factions", fa);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name == null || name.isBlank() ? id : name);
        o.addProperty("color", color == null || color.isBlank() ? "#FFFFFF" : color);
        o.addProperty("description", description == null ? "" : description);
        o.addProperty("icon", icon == null || icon.isBlank() ? "hex" : icon);
        o.addProperty("tier", Math.max(1, Math.min(3, tier)));
        putMusic(o, music);
        putCamScene(o, cmdcamScene);
        fa.add(o);
        return commit(candidate);
    }

    /** 更新阵营。music 为空串时移除阵营音乐字段；cmdcamScene 空串移除该字段。 */
    public List<String> updateFaction(
            String id,
            String name,
            String color,
            String description,
            String icon,
            int tier,
            String music,
            String cmdcamScene) {
        if (!graph.factions().containsKey(id)) {
            return List.of("未找到阵营: " + id);
        }
        JsonObject candidate = root.deepCopy();
        JsonArray fa = candidate.getAsJsonArray("factions");
        for (int i = 0; i < fa.size(); i++) {
            JsonObject o = fa.get(i).getAsJsonObject();
            if (str(o, "id", "").equals(id)) {
                o.addProperty("name", name == null || name.isBlank() ? id : name);
                o.addProperty("color", color == null || color.isBlank() ? "#FFFFFF" : color);
                o.addProperty("description", description == null ? "" : description);
                o.addProperty("icon", icon == null || icon.isBlank() ? "hex" : icon);
                o.addProperty("tier", Math.max(1, Math.min(3, tier)));
                putMusic(o, music);
                putCamScene(o, cmdcamScene);
                return commit(candidate);
            }
        }
        return List.of("未找到阵营: " + id);
    }

    /** 删除阵营（存在下属职业/组/关系引用时拒绝，防悬空引用毁掉整份图谱）。 */
    public List<String> deleteFaction(String id) {
        if (!graph.factions().containsKey(id)) {
            return List.of("未找到阵营: " + id);
        }
        for (String pid : professionIds()) {
            var def = findProfession(pid).orElse(null);
            if (def != null && str(def, "factionId", "").equals(id)) {
                return List.of("阵营仍有职业引用: " + pid + "，请先删除职业");
            }
        }
        // 组引用检查
        if (graph.groups().values().stream().anyMatch(g -> g.memberIds().contains(id))) {
            return List.of("阵营仍被阵营组引用，请先从组中移除");
        }
        // 关系引用检查（读配置根对象）
        if (root.has("relations") && root.get("relations").isJsonArray()) {
            for (var el : root.getAsJsonArray("relations")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject rel = el.getAsJsonObject();
                String from = str(rel, "from", "");
                String to = str(rel, "to", "");
                if (from.equals(id) || to.equals(id)) {
                    return List.of("阵营仍被关系引用（" + from + " ↔ " + to + "），请先删除关系");
                }
            }
        }
        JsonObject candidate = root.deepCopy();
        JsonArray fa = candidate.getAsJsonArray("factions");
        for (int i = 0; i < fa.size(); i++) {
            if (str(fa.get(i).getAsJsonObject(), "id", "").equals(id)) {
                fa.remove(i);
                return commit(candidate);
            }
        }
        return List.of("未找到阵营: " + id);
    }

    /** 写盘 + 重载图谱。 */
    private List<String> commit(JsonObject candidate) {
        if (!JsonUtil.atomicWrite(file, candidate)) {
            return List.of("配置文件写入失败");
        }
        this.root = candidate;
        ParseResult result = parse(candidate);
        if (!result.success()) {
            return result.errors();
        }
        this.graph = result.graph();
        return List.of();
    }

    public java.util.Optional<com.google.gson.JsonObject> findProfession(String id) {
        return FactionProfessions.find(root, id);
    }

    public List<String> professionIds() {
        return FactionProfessions.ids(root);
    }

    /** 重新从盘读取。 */
    public void reload() {
        JsonUtil.readObject(file).ifPresent(o -> {
            this.root = o;
            reloadFromRoot();
        });
        if (this.root == null) {
            load();
        }
    }

    // ---------- 阵营出生点（P9 管理面板增强） ----------

    /** 出生点规则：SPREAD=多地随机分摊；SINGLE=随机一点集中部署。 */
    public static final String SPAWN_RULE_SPREAD = "SPREAD";

    public static final String SPAWN_RULE_SINGLE = "SINGLE";

    /** 单个出生点（纯数据，无 MC 依赖）。 */
    public record SpawnPoint(double x, double y, double z, String dim) {}

    /** 阵营出生点配置：points 列表 + rule（SPREAD/SINGLE）。 */
    public record FactionSpawn(List<SpawnPoint> points, String rule) {
        public FactionSpawn {
            points = points == null ? List.of() : List.copyOf(points);
            rule = rule == null || rule.isBlank() ? SPAWN_RULE_SPREAD : rule;
        }
    }

    /** 读取阵营出生点配置；未配置返回 null（调用方回退部署点/世界出生点）。 */
    public FactionSpawn factionSpawn(String factionId) {
        if (root == null || factionId == null || factionId.isBlank()) {
            return null;
        }
        JsonArray fa = root.has("factions") ? root.getAsJsonArray("factions") : new JsonArray();
        for (int i = 0; i < fa.size(); i++) {
            JsonObject o = fa.get(i).getAsJsonObject();
            if (str(o, "id", "").equals(factionId)
                    && o.has("spawn")
                    && o.get("spawn").isJsonObject()) {
                JsonObject sp = o.getAsJsonObject("spawn");
                String rule =
                        SPAWN_RULE_SINGLE.equalsIgnoreCase(str(sp, "rule", "")) ? SPAWN_RULE_SINGLE : SPAWN_RULE_SPREAD;
                List<SpawnPoint> pts = new ArrayList<>();
                if (sp.has("points") && sp.get("points").isJsonArray()) {
                    for (JsonElement e : sp.getAsJsonArray("points")) {
                        if (e.isJsonObject()) {
                            JsonObject pp = e.getAsJsonObject();
                            pts.add(new SpawnPoint(
                                    dbl(pp, "x", 0),
                                    dbl(pp, "y", 64),
                                    dbl(pp, "z", 0),
                                    str(pp, "dim", "minecraft:overworld")));
                        }
                    }
                }
                return new FactionSpawn(pts, rule);
            }
        }
        return null;
    }

    /** 写入阵营出生点配置；校验阵营存在，失败回滚不写盘。 */
    public List<String> setFactionSpawn(String factionId, String rule, List<SpawnPoint> points) {
        if (!graph.factions().containsKey(factionId)) {
            return List.of("未找到阵营: " + factionId);
        }
        String normRule = SPAWN_RULE_SINGLE.equalsIgnoreCase(rule) ? SPAWN_RULE_SINGLE : SPAWN_RULE_SPREAD;
        JsonObject candidate = root.deepCopy();
        JsonArray fa = candidate.has("factions") ? candidate.getAsJsonArray("factions") : new JsonArray();
        for (int i = 0; i < fa.size(); i++) {
            JsonObject o = fa.get(i).getAsJsonObject();
            if (!str(o, "id", "").equals(factionId)) {
                continue;
            }
            JsonObject spawn = new JsonObject();
            spawn.addProperty("rule", normRule);
            JsonArray pts = new JsonArray();
            for (SpawnPoint sp : points) {
                JsonObject p = new JsonObject();
                p.addProperty("x", sp.x());
                p.addProperty("y", sp.y());
                p.addProperty("z", sp.z());
                p.addProperty("dim", sp.dim());
                pts.add(p);
            }
            spawn.add("points", pts);
            o.add("spawn", spawn);
            return commit(candidate);
        }
        return List.of("未找到阵营: " + factionId);
    }

    /** 读取职业部署点配置；未配置返回 null（调用方回退阵营部署点/世界复活点）。 */
    public FactionSpawn professionSpawn(String professionId) {
        if (root == null || professionId == null || professionId.isBlank()) {
            return null;
        }
        return FactionProfessions.find(root, professionId)
                .map(FactionProfessions::spawn)
                .orElse(null);
    }

    /** 写入职业部署点配置；校验职业存在，失败回滚不写盘。 */
    public List<String> setProfessionSpawn(String professionId, String rule, List<SpawnPoint> points) {
        if (FactionProfessions.find(root, professionId).isEmpty()) {
            return List.of("未找到职业: " + professionId);
        }
        String normRule = SPAWN_RULE_SINGLE.equalsIgnoreCase(rule) ? SPAWN_RULE_SINGLE : SPAWN_RULE_SPREAD;
        JsonObject candidate = root.deepCopy();
        JsonArray pa = candidate.has("professions") ? candidate.getAsJsonArray("professions") : new JsonArray();
        for (int i = 0; i < pa.size(); i++) {
            JsonObject o = pa.get(i).getAsJsonObject();
            if (!str(o, "id", "").equals(professionId)) {
                continue;
            }
            JsonObject spawn = new JsonObject();
            spawn.addProperty("rule", normRule);
            JsonArray pts = new JsonArray();
            for (SpawnPoint sp : points) {
                JsonObject p = new JsonObject();
                p.addProperty("x", sp.x());
                p.addProperty("y", sp.y());
                p.addProperty("z", sp.z());
                p.addProperty("dim", sp.dim());
                pts.add(p);
            }
            spawn.add("points", pts);
            o.add("spawn", spawn);
            return commit(candidate);
        }
        return List.of("未找到职业: " + professionId);
    }

    private static double dbl(JsonObject o, String key, double def) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def; // 畸形配置（非数字）不崩服
        }
    }
}
