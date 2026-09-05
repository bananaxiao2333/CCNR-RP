/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 招募管理：复活波候选（阴间池 + 阳间池）全部以邀请形式发送，玩家自行选择加不加入；
 * 已加入名单广播显示；人满即提前部署，超时按已加入部署，无人加入则失败。纯内存；超时由 tick 驱动。
 *
 * <p>每次触发（事件/征召/波次）都分配独立 UUID 分组：同一次事件的多次触发互不干扰，
 * 拒绝/超时/结算后再次触发照常发新邀请；waveId 仅用于展示（弹窗/广播）。
 */
public final class RecruitManager {
    private static final Logger LOGGER = LogManager.getLogger();

    public interface Listener {
        /** 有人选择加入（广播已加入名单用）；waveId 为展示用 id。 */
        void onRecruitAccepted(String charName, String waveId, int acceptedCount, int target);

        /** 波次结算（人满提前部署或全员超时/拒绝）：full=true 表示人满提前部署。 */
        void onWaveFinish(String waveId, List<String> acceptedCharIds, boolean full);

        /** 征召邀请结算：接受者（征召兵角色）按编制部署。 */
        void onConscriptFinish(String id, List<String> acceptedCharIds, boolean full);

        /** 邀请作废（拒绝/超时未加入）：征召兵角色需清理删除。 */
        void onOfferDiscarded(String charId, String id);
    }

    /** groupId = 本次触发实例的 UUID 分组；waveId = 展示用 id（复活波 id / 征召标签）；kind = wave|conscript。 */
    record Offer(
            String id,
            String groupId,
            String waveId,
            String kind,
            String charId,
            String charName,
            String professionId,
            String playerUuid,
            long deadlineMs,
            boolean done) {}

    private final Listener listener;
    private final Map<String, Offer> offers = new HashMap<>();
    private final Map<String, List<String>> accepted = new HashMap<>(); // groupId -> 已加入 charId
    private final Map<String, Integer> targets = new HashMap<>(); // groupId -> 需要人数
    private final Map<String, String> displayIds = new HashMap<>(); // groupId -> 展示 id
    private final Map<String, List<RpPackets.RecruitRosterS2C.RosterEntry>> rosters =
            new HashMap<>(); // groupId -> 已加入名单（展示）
    private final Map<String, List<ServerPlayer>> groupPlayers = new HashMap<>(); // groupId -> 收到邀请的候选（名单广播目标）
    private final Set<String> conscriptGroups = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private int counter = 0;

    public RecruitManager(Listener listener) {
        this.listener = listener;
    }

    /** 发送一批邀请：所有候选都可选择加不加入；target=波次目标人数；kind=wave。 */
    public void offer(
            String waveId, int target, List<Candidate> candidates, List<ServerPlayer> players, long timeoutSec) {
        sendOffers(UUID.randomUUID().toString(), "wave", waveId, target, candidates, players, timeoutSec);
    }

    /** 征召邀请：候选为已创建的征召兵角色（UID 名），接受后部署，作废则删除。kind=conscript（强制征召步骤）|typed（指定编制复活波）。 */
    public void offerConscript(
            String id,
            String kind,
            int target,
            List<Candidate> candidates,
            List<ServerPlayer> players,
            long timeoutSec) {
        String groupId = UUID.randomUUID().toString();
        conscriptGroups.add(groupId);
        sendOffers(groupId, kind, id, target, candidates, players, timeoutSec);
    }

    /** 通用波邀请（kind=pick）：v2（唯一身份）起观察者接受即按自己当前职业部署（RecruitAnswerC2S 即可，无选岗）。 */
    public void offerPick(
            String id, int target, List<Candidate> candidates, List<ServerPlayer> players, long timeoutSec) {
        sendOffers(UUID.randomUUID().toString(), "pick", id, target, candidates, players, timeoutSec);
    }

    /** 通用波选岗：校验邀请并记录所选角色（接受语义），人满提前部署。 */
    public void pick(String offerId, ServerPlayer player, String charId) {
        Offer o = offers.get(offerId);
        if (o == null
                || o.done()
                || !"pick".equals(o.kind())
                || System.currentTimeMillis() > o.deadlineMs()
                || !o.playerUuid().equals(player.getUUID().toString())) {
            return;
        }
        String identity = o.charId(); // 候选本人身份（user-<uuid>），名单皮肤/立绘用
        String prof = o.professionId();
        String nm = o.charName();
        offers.put(
                offerId,
                new Offer(
                        o.id(),
                        o.groupId(),
                        o.waveId(),
                        o.kind(),
                        charId,
                        nm,
                        prof,
                        o.playerUuid(),
                        o.deadlineMs(),
                        true));
        List<String> list = accepted.computeIfAbsent(o.groupId(), k -> new ArrayList<>());
        list.add(charId);
        int target = targets.getOrDefault(o.groupId(), 1);
        String displayName = charNameOf(player, charId);
        addRoster(o.groupId(), identity, nm, prof);
        broadcastRoster(o.groupId());
        listener.onRecruitAccepted(displayName, o.waveId(), list.size(), target);
        if (list.size() >= target) {
            finishWave(o.groupId(), true); // 人满 → 提前部署
        }
    }

    private String charNameOf(ServerPlayer player, String charId) {
        // 角色库已删除（v2）：统一使用玩家名下显示
        return player.getName().getString();
    }

    private void sendOffers(
            String groupId,
            String kind,
            String displayId,
            int target,
            List<Candidate> candidates,
            List<ServerPlayer> players,
            long timeoutSec) {
        targets.put(groupId, Math.max(1, target));
        accepted.put(groupId, new ArrayList<>());
        rosters.put(groupId, new ArrayList<>());
        displayIds.put(groupId, displayId);
        long timeoutMs = Math.max(10, timeoutSec) * 1000L;
        int sent = 0;
        for (int i = 0; i < candidates.size() && i < players.size(); i++) {
            Candidate c = candidates.get(i);
            ServerPlayer p = players.get(i);
            String oid = "offer-" + (++counter);
            long deadline = System.currentTimeMillis() + timeoutMs;
            offers.put(
                    oid,
                    new Offer(
                            oid,
                            groupId,
                            displayId,
                            kind,
                            c.charId(),
                            c.name(),
                            c.professionId(),
                            c.playerUuid(),
                            deadline,
                            false));
            RpChannels.sendTo(
                    p,
                    new RpPackets.RecruitOfferS2C(
                            oid,
                            groupId,
                            c.charId(),
                            c.name(),
                            c.professionId(),
                            (int) (deadline - System.currentTimeMillis()) / 50,
                            displayId,
                            kind));
            RpChannels.sendTo(
                    p,
                    new RpPackets.ErrorS2C(
                            "conscript".equals(kind)
                                    ? "ccnr_rp.spawn.recruit.you_conscript"
                                    : "ccnr_rp.spawn.recruit.you",
                            professionDisplayName(c.professionId()),
                            factionDisplayName(c.professionId())));
            sent++;
        }
        // 记录实际收到邀请的候选玩家（名单广播目标）；名单为空时不推（等首位接受者加入再推送）。
        groupPlayers.put(groupId, new ArrayList<>(players.subList(0, sent)));
        LOGGER.info("[CCNR-RP] 邀请 {} 人（需要 {} 人）: {} [{}] kind={}", sent, target, displayId, groupId, kind);
    }

    /** 把某候选人加入「已加入名单」名单（按 charId 去重，防止重复接受时叠加）。 */
    private void addRoster(String groupId, String charId, String charName, String professionId) {
        List<RpPackets.RecruitRosterS2C.RosterEntry> rr = rosters.computeIfAbsent(groupId, k -> new ArrayList<>());
        if (rr.stream().noneMatch(e -> e.charId().equals(charId))) {
            rr.add(new RpPackets.RecruitRosterS2C.RosterEntry(charId, charName, professionId));
        }
    }

    /** 向该分组全部候选推送当前已加入名单（服务端权威；客户端据此显示背包右上角已加入玩家列表）。 */
    private void broadcastRoster(String groupId) {
        List<ServerPlayer> candidates = groupPlayers.get(groupId);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        List<RpPackets.RecruitRosterS2C.RosterEntry> entries = rosters.getOrDefault(groupId, List.of());
        String display = displayIds.getOrDefault(groupId, groupId);
        int targetN = targets.getOrDefault(groupId, 1);
        RpPackets.RecruitRosterS2C msg =
                new RpPackets.RecruitRosterS2C(groupId, display, targetN, List.copyOf(entries));
        for (ServerPlayer p : candidates) {
            RpChannels.sendTo(p, msg);
        }
    }

    /** 推送清空名单（结算/取消时用），客户端据此移除该分组的已加入列表。 */
    private void clearRoster(String groupId, String displayId, List<ServerPlayer> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        RpPackets.RecruitRosterS2C msg = new RpPackets.RecruitRosterS2C(groupId, displayId, 0, List.of());
        for (ServerPlayer p : candidates) {
            RpChannels.sendTo(p, msg);
        }
    }

    /** 职位显示名（服务端解析，避免邀请广播露出内部 ID；无则回退原始 id）。 */
    private String professionDisplayName(String professionId) {
        if (CCNRRPMod.factions != null) {
            var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
            if (def != null && def.has("name") && !def.get("name").isJsonNull()) {
                String n = def.get("name").getAsString();
                if (!n.isBlank()) {
                    return n;
                }
            }
        }
        return professionId;
    }

    /** 职位所属阵营显示名（无则回退阵营 id；再回退空串）。 */
    private String factionDisplayName(String professionId) {
        if (CCNRRPMod.factions != null) {
            var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
            if (def != null) {
                String fid = com.ccnrcom.rp.faction.FactionProfessions.factionId(def);
                var f = CCNRRPMod.factions.graph().factions().get(fid);
                if (f != null && f.name() != null && !f.name().isBlank()) {
                    return f.name();
                }
                return fid;
            }
        }
        return "";
    }

    /** 由 SpawnFramework tick 驱动：超时/全员处理完后结算波次（幂等：一次分组只结算一次）。 */
    public void onTick() {
        List<String> toRemove = new ArrayList<>();
        for (Offer o : offers.values()) {
            if (System.currentTimeMillis() > o.deadlineMs() && !o.done()) {
                listener.onOfferDiscarded(o.charId(), o.waveId()); // 超时未加入：征召兵角色清理
            }
            if (o.done() || System.currentTimeMillis() > o.deadlineMs()) {
                toRemove.add(o.id());
            }
        }
        toRemove.forEach(offers::remove);
        // 无剩余未处理邀请的分组 → 结算（人满在 accept 中已提前结算）
        Set<String> groups = new HashSet<>(targets.keySet());
        for (String groupId : groups) {
            if (finished.contains(groupId)) {
                continue;
            }
            boolean pending = offers.values().stream().anyMatch(o -> o.groupId().equals(groupId));
            if (!pending) {
                finishWave(groupId, false);
            }
        }
    }

    /** 接受邀请：登记已加入并广播；人满 → 提前部署。pick 邀请接受即按玩家自己职业部署（v2 唯一身份，无选岗）。 */
    public void accept(String offerId, ServerPlayer player) {
        Offer o = offers.get(offerId);
        if (o == null
                || o.done()
                || System.currentTimeMillis() > o.deadlineMs() // 超时后不可再接受
                || !o.playerUuid().equals(player.getUUID().toString())) {
            return;
        }
        offers.put(
                offerId,
                new Offer(
                        o.id(),
                        o.groupId(),
                        o.waveId(),
                        o.kind(),
                        o.charId(),
                        o.charName(),
                        o.professionId(),
                        o.playerUuid(),
                        o.deadlineMs(),
                        true));
        List<String> list = accepted.computeIfAbsent(o.groupId(), k -> new ArrayList<>());
        list.add(o.charId());
        int target = targets.getOrDefault(o.groupId(), 1);
        addRoster(o.groupId(), o.charId(), o.charName(), o.professionId());
        broadcastRoster(o.groupId());
        listener.onRecruitAccepted(o.charName(), o.waveId(), list.size(), target);
        if (list.size() >= target) {
            finishWave(o.groupId(), true); // 人满 → 提前部署
        }
    }

    public void decline(String offerId, ServerPlayer player) {
        Offer o = offers.get(offerId);
        if (o == null || !o.playerUuid().equals(player.getUUID().toString())) {
            return;
        }
        offers.remove(offerId);
        listener.onOfferDiscarded(o.charId(), o.waveId()); // 拒绝：征召兵角色清理
        // 全员拒绝 → 该分组无剩余邀请，onTick 结算为空失败
    }

    /** 玩家离服：取消其全部邀请（含已接受的），同一次离服视同「取消接受 + 拒绝邀请」。 */
    public void onPlayerDisconnect(String playerUuid) {
        List<Offer> mine = new ArrayList<>();
        for (Offer o : offers.values()) {
            if (o.playerUuid().equals(playerUuid)) {
                mine.add(o);
            }
        }
        Set<String> touched = new HashSet<>();
        for (Offer o : mine) {
            offers.remove(o.id());
            if (o.done() && o.kind() != null && !"conscript".equals(o.kind())) {
                // 取消接受：从该分组已加入名单移除（部署用 + 展示用名单纯净）
                accepted.getOrDefault(o.groupId(), new ArrayList<>()).remove(o.charId());
                List<RpPackets.RecruitRosterS2C.RosterEntry> rr = rosters.get(o.groupId());
                if (rr != null) {
                    rr.removeIf(e -> e.charId().equals(o.charId()));
                    touched.add(o.groupId());
                }
            }
            listener.onOfferDiscarded(o.charId(), o.waveId()); // 视同拒绝：征召兵角色清理
        }
        // 从各分组名单广播目标中移除该玩家（离服不再收名单更新）
        for (List<ServerPlayer> ps : groupPlayers.values()) {
            ps.removeIf(p -> p.getUUID().toString().equals(playerUuid));
        }
        // 重新推送受影响分组的最新名单，让其余候选看到人数回落
        for (String g : touched) {
            broadcastRoster(g);
        }
        // 无剩余邀请的分组由 onTick 结算（无人加入 → 失败）
    }

    private void finishWave(String groupId, boolean full) {
        if (!finished.add(groupId)) {
            return;
        }
        try {
            String displayId = displayIds.getOrDefault(groupId, groupId);
            List<String> ids = List.copyOf(accepted.getOrDefault(groupId, List.of()));
            List<ServerPlayer> candidates = groupPlayers.get(groupId);
            // 结算：先向候选推送清空名单（客户端据此移除背包已加入列表），再清理本分组 bookkeeping。
            clearRoster(groupId, displayId, candidates);
            offers.values().removeIf(o -> o.groupId().equals(groupId));
            targets.remove(groupId);
            accepted.remove(groupId);
            rosters.remove(groupId);
            groupPlayers.remove(groupId);
            displayIds.remove(groupId);
            if (conscriptGroups.remove(groupId)) {
                LOGGER.info("[CCNR-RP] 征召 {} 结算：已加入 {} 人（人满={}）", displayId, ids.size(), full);
                listener.onConscriptFinish(displayId, ids, full);
            } else {
                LOGGER.info("[CCNR-RP] 复活波 {} 结算：已加入 {} 人（人满={}）", displayId, ids.size(), full);
                listener.onWaveFinish(displayId, ids, full);
            }
        } finally {
            finished.remove(groupId); // 分组结算后释放，防无界增长
        }
    }
}
