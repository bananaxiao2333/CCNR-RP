/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.data.Database;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.sql.SQLException;
import java.sql.Statement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp db：数据库后端状态与连通性测试（管理命令）。 */
final class DbCommand {

    private DbCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("db")
                .requires(RpCommand.admin(Permissions.ADMIN_DB))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("test").executes(ctx -> test(ctx.getSource())))
                .build());
    }

    private static int status(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.status.disabled"), false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.db.status.enabled",
                        db.config().mode().name(),
                        db.config().profile(),
                        db.config().maskedPass()),
                false);
        return 1;
    }

    private static int test(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        try {
            boolean ok = db.read(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT 1");
                } catch (SQLException e) {
                    return false;
                }
                return true;
            });
            if (ok) {
                source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.ok"), false);
            } else {
                source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.fail"), false);
            }
        } catch (Exception e) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.fail"), false);
        }
        return 1;
    }
}
