/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 招募管理：offer 生命周期（发送/接受/拒绝/超时顺位）。纯内存；超时由 tick 驱动。 */
public final class RecruitManager {
    private static final Logger LOGGER = LogManager.getLogger();

    public interface Listener {
        void onRecruitAccepted(String charId, String waveId);

        void onRecruitFailed(String waveId);
    }

    record Offer(String id, String charId, String playerUuid, String waveId, long deadlineMs, boolean done) {}

    private final Listener listener;
    private final Map<String, Offer> offers = new HashMap<>();
    private int counter = 0;

    public RecruitManager(Listener listener) {
        this.listener = listener;
    }

    public void offer(String waveId, List<Candidate> candidates, List<ServerPlayer> players) {
        long timeoutSec = 60;
        for (int i = 0; i < candidates.size() && i < players.size(); i++) {
            Candidate c = candidates.get(i);
            ServerPlayer p = players.get(i);
            String id = "offer-" + (++counter);
            long deadline = System.currentTimeMillis() + 60_000L;
            offers.put(id, new Offer(id, c.charId(), c.playerUuid(), waveId, deadline, false));
            RpChannels.sendTo(
                    p,
                    new RpPackets.RecruitOfferS2C(
                            id,
                            c.charId(),
                            c.name(),
                            c.professionId(),
                            (int) (deadline - System.currentTimeMillis()) / 50,
                            waveId));
            RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.spawn.recruit.you", c.name(), waveId));
        }
    }

    /** 由 SpawnFramework tick 驱动：超时移除并通知失败（幂等：一次 offer 只失败一次）。 */
    public void onTick() {
        List<String> toRemove = new ArrayList<>();
        for (Offer o : offers.values()) {
            if (o.done() || System.currentTimeMillis() > o.deadlineMs()) {
                toRemove.add(o.id());
                if (!o.done()) {
                    listener.onRecruitFailed(o.waveId());
                }
            }
        }
        toRemove.forEach(offers::remove);
    }

    public void accept(String offerId, ServerPlayer player) {
        Offer o = offers.get(offerId);
        if (o == null || o.done() || !o.playerUuid().equals(player.getUUID().toString())) {
            return;
        }
        offers.put(offerId, new Offer(o.id(), o.charId(), o.playerUuid(), o.waveId(), o.deadlineMs(), true));
        listener.onRecruitAccepted(o.charId(), o.waveId());
    }

    public void decline(String offerId, ServerPlayer player) {
        Offer o = offers.get(offerId);
        if (o == null || !o.playerUuid().equals(player.getUUID().toString())) {
            return;
        }
        offers.remove(offerId);
        listener.onRecruitFailed(o.waveId());
    }
}
