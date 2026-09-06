/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/** CCNR-RP 管理权限节点（默认拒绝；OP 始终可用）。模式对齐 CCNR-Com Permissions。 */
public final class Permissions {
    public static final PermissionNode<Boolean> ADMIN_FACTION =
            new PermissionNode<>("ccnrrp", "admin.faction", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_PROFESSION = new PermissionNode<>(
            "ccnrrp", "admin.profession", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_SETTLE =
            new PermissionNode<>("ccnrrp", "admin.settle", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_EVENT =
            new PermissionNode<>("ccnrrp", "admin.event", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_PHASE =
            new PermissionNode<>("ccnrrp", "admin.phase", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_MODE =
            new PermissionNode<>("ccnrrp", "admin.mode", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_ANIMATION = new PermissionNode<>(
            "ccnrrp", "admin.animation", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_SPAWN =
            new PermissionNode<>("ccnrrp", "admin.spawn", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_KILL =
            new PermissionNode<>("ccnrrp", "admin.kill", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_XP =
            new PermissionNode<>("ccnrrp", "admin.xp", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);
    public static final PermissionNode<Boolean> ADMIN_DB =
            new PermissionNode<>("ccnrrp", "admin.db", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);

    private Permissions() {}

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(
                ADMIN_FACTION,
                ADMIN_PROFESSION,
                ADMIN_SETTLE,
                ADMIN_EVENT,
                ADMIN_PHASE,
                ADMIN_MODE,
                ADMIN_ANIMATION,
                ADMIN_SPAWN,
                ADMIN_KILL,
                ADMIN_XP,
                ADMIN_DB);
    }

    /** 管理资格：OP（>= 2 级）或拥有对应权限节点。 */
    public static boolean canAdmin(ServerPlayer player, PermissionNode<Boolean> node) {
        return player.hasPermissions(2) || PermissionAPI.getPermission(player, node);
    }
}
