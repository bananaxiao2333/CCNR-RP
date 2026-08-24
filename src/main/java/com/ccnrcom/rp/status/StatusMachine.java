/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import java.util.Optional;

/**
 * 角色状态机（纯逻辑，无 MC 依赖）。
 *
 * <p>合法迁移：
 * <ul>
 *   <li>OBSERVING → ALIVE：部署（自刷新 / 复活波 / 招募接受）</li>
 *   <li>ALIVE → DEAD：死亡事件 / 掉线判死（含踢出与断连）</li>
 *   <li>ALIVE → OBSERVING：玩家主动下班（切换观察）</li>
 *   <li>DEAD → ALIVE：复活（复活波 / 招募部署，需冷却结束）</li>
 * </ul>
 * 同状态迁移视为成功（幂等）；其他迁移拒绝并返回原因。冷却为派生状态（由 cooldownUntil 判定），不在此模型内。
 */
public final class StatusMachine {

    private StatusMachine() {}

    /** 尝试迁移；返回空的 Optional 表示成功，否则为拒绝原因。 */
    public static Optional<String> transition(CharacterStatus from, CharacterStatus to) {
        if (from == to) {
            return Optional.empty();
        }
        return switch (from) {
            case OBSERVING -> to == CharacterStatus.ALIVE
                    ? Optional.empty()
                    : Optional.of("OBSERVING 仅能迁移到 ALIVE（部署激活）");
            case ALIVE -> to == CharacterStatus.DEAD || to == CharacterStatus.OBSERVING
                    ? Optional.empty()
                    : Optional.of("ALIVE 仅能迁移到 DEAD（死亡/判死）或 OBSERVING（下班）");
            case DEAD -> to == CharacterStatus.ALIVE ? Optional.empty() : Optional.of("DEAD 仅能迁移到 ALIVE（复活部署，且需冷却结束）");
        };
    }

    /** 自部署资格：仅观察状态的自部署类型可随时从人物管理界面部署（在场/已死不可自刷）。 */
    public static boolean selfDeployable(CharacterStatus status, boolean professionSelfDeploy) {
        return status == CharacterStatus.OBSERVING && professionSelfDeploy;
    }

    /**
     * 复活波/招募资格：阴间（DEAD，冷却结束由选人层过滤）优先；
     * 阳间池仅接受非自部署类型的观察者（自部署类型走自刷通道）。
     */
    public static boolean waveEligible(CharacterStatus status, boolean professionSelfDeploy) {
        return status == CharacterStatus.DEAD || status == CharacterStatus.OBSERVING && !professionSelfDeploy;
    }
}
