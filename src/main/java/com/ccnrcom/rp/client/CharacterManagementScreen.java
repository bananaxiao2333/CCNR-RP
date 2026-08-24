/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 角色管理界面：列表 / 详情 / 创建表单 / 皮肤上传 / 操作按钮（数据全部经服务端校验）。 */
public class CharacterManagementScreen extends Screen {

    private static CharacterManagementScreen open;

    private final List<JsonObject> chars = new ArrayList<>();
    private final List<String> factionIds = new ArrayList<>();
    private final List<String> professionIds = new ArrayList<>();
    private String selectedId = "";
    private int scroll = 0;
    private int factionIndex = 0;
    private int professionIndex = 0;
    private EditBox nameBox;
    private EditBox backgroundBox;
    private EditBox skinPathBox;
    private String notice = "";
    private long noticeUntil = 0;
    private int listWidth = 0;

    public CharacterManagementScreen() {
        super(Component.translatable("ccnr_rp.gui.character.title"));
    }

    public static void refreshIfOpen() {
        if (open != null && MinecraftHolder.minecraft() != null) {
            open.reloadData();
        }
    }

    @Override
    protected void init() {
        open = this;
        reloadData();
        listWidth = Math.max(120, width * 2 / 5);
        rebuild();
    }

    private void reloadData() {
        chars.clear();
        chars.addAll(ClientCharacterState.list());
        factionIds.clear();
        ClientCharacterState.factions().forEach(f -> factionIds.add(f.get("id").getAsString()));
        professionIds.clear();
        ClientCharacterState.professions()
                .forEach(p -> professionIds.add(p.get("id").getAsString()));
        if (selectedId.isEmpty() || ClientCharacterState.find(selectedId) == null) {
            selectedId = ClientCharacterState.selected();
        }
        nameBox = null;
        backgroundBox = null;
        skinPathBox = null;
    }

    private void rebuild() {
        clearWidgets();
        int left = 8;
        int top = 28;
        int rowH = 20;
        int maxVisible = Math.max(1, (height - top - 30) / rowH);
        List<JsonObject> visible = chars.subList(
                Math.min(scroll, Math.max(0, chars.size() - maxVisible)),
                Math.min(chars.size(), Math.min(scroll, Math.max(0, chars.size() - maxVisible)) + maxVisible));
        for (int i = 0; i < visible.size(); i++) {
            JsonObject c = visible.get(i);
            String id = c.get("id").getAsString();
            String label = str(c, "name") + " [" + statusText(c) + "]";
            int y = top + i * rowH;
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                        selectedId = id;
                        notice = "";
                        rebuild();
                    })
                    .bounds(left, y, listWidth - 4, rowH - 2)
                    .build());
        }
        int right = left + listWidth + 8;
        int rw = width - right - 8;
        drawDetails(right, 28, rw);
    }

    private void drawDetails(int x, int y, int w) {
        JsonObject c = ClientCharacterState.find(selectedId);
        int ry = y;
        // 详情 + 操作
        if (c != null) {
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.select"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterSelectC2S(selectedId));
                        ClientCharacterState.select(selectedId);
                        notice();
                    })
                    .bounds(x, ry, 56, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.activate"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterActivateC2S(selectedId));
                        notice();
                    })
                    .bounds(x + 60, ry, 56, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.observe"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterObserveC2S(selectedId));
                        notice();
                    })
                    .bounds(x + 120, ry, 56, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.delete"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterDeleteC2S(selectedId));
                        notice();
                    })
                    .bounds(x + 180, ry, 56, 20)
                    .build());
            ry += 26;
            // 皮肤上传
            skinPathBox = new EditBox(
                    font, x, ry, Math.max(90, w - 130), 20, Component.translatable("ccnr_rp.gui.character.skin.path"));
            skinPathBox.setMaxLength(512);
            addRenderableWidget(skinPathBox);
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.skin.upload"), b -> {
                        uploadSkin();
                    })
                    .bounds(x + Math.max(90, w - 130) + 6, ry, 110, 20)
                    .build());
            ry += 30;
        }
        // 创建表单
        ry += 10;
        addRenderableWidget(new EditBox(font, x, ry, w, 20, Component.literal(""))).visible = false;
        nameBox = new EditBox(font, x, ry, w, 20, Component.translatable("ccnr_rp.gui.character.name"));
        nameBox.setMaxLength(32);
        addRenderableWidget(nameBox);
        ry += 24;
        addRenderableWidget(Button.builder(Component.literal(factionLabel()), b -> {
                    if (!factionIds.isEmpty()) {
                        factionIndex = (factionIndex + 1) % factionIds.size();
                        professionIndex = Math.min(
                                professionIndex,
                                Math.max(0, matchingProfessions().size() - 1));
                        rebuild();
                    }
                })
                .bounds(x, ry, w / 2, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal(professionLabel()), b -> {
                    List<String> list = matchingProfessions();
                    if (!list.isEmpty()) {
                        professionIndex = (professionIndex + 1) % list.size();
                        rebuild();
                    }
                })
                .bounds(x + w / 2, ry, w / 2, 20)
                .build());
        ry += 24;
        backgroundBox = new EditBox(font, x, ry, w, 40, Component.translatable("ccnr_rp.gui.character.background"));
        backgroundBox.setMaxLength(256);
        addRenderableWidget(backgroundBox);
        ry += 46;
        addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.gui.character.create"), b -> {
                    String f = factionIds.isEmpty() ? "" : factionIds.get(factionIndex);
                    String p = matchingProfessions().isEmpty()
                            ? ""
                            : matchingProfessions().get(professionIndex);
                    RpChannels.sendToServer(
                            new RpPackets.CharacterCreateC2S(nameBox.getValue(), f, p, backgroundBox.getValue()));
                    notice();
                })
                .bounds(x, ry, 110, 20)
                .build());
    }

    private List<String> matchingProfessions() {
        if (factionIds.isEmpty()) {
            return List.of();
        }
        String fid = factionIds.get(factionIndex);
        return ClientCharacterState.professionsOf(fid).stream()
                .map(p -> p.get("id").getAsString())
                .toList();
    }

    private String factionLabel() {
        return factionIds.isEmpty()
                ? "?"
                : Component.translatable("ccnr_rp.gui.character.faction", factionIds.get(factionIndex))
                        .getString();
    }

    private String professionLabel() {
        List<String> list = matchingProfessions();
        return list.isEmpty()
                ? "?"
                : Component.translatable(
                                "ccnr_rp.gui.character.profession",
                                list.get(Math.min(professionIndex, list.size() - 1)))
                        .getString();
    }

    private void uploadSkin() {
        String path = skinPathBox.getValue();
        if (path == null || path.isBlank()) {
            notice("ccnr_rp.gui.character.skin.need_path");
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(Path.of(path));
        } catch (Exception e) {
            notice("ccnr_rp.gui.character.skin.read_fail");
            return;
        }
        if (data.length > 256 * 1024) {
            notice("ccnr_rp.gui.character.skin.too_big");
            return;
        }
        int part = 32 * 1024;
        int total = (data.length + part - 1) / part;
        for (int i = 0; i < total; i++) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, i * part, Math.min((i + 1) * part, data.length));
            RpChannels.sendToServer(new RpPackets.SkinUploadPartC2S(selectedId, i, total, chunk));
        }
        RpChannels.sendToServer(new RpPackets.SkinUploadCommitC2S(selectedId, data.length, total));
        notice("ccnr_rp.gui.character.skin.uploading");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawString(font, title, 8, 8, 0xFFFFFF);
        graphics.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.list.hint", String.valueOf(chars.size())),
                8,
                14,
                0xAAAAAA);
        // 选中角色详情文本
        JsonObject c = ClientCharacterState.find(selectedId);
        if (c != null) {
            int x = 8 + listWidth + 8;
            int y = 28;
            graphics.drawString(font, str(c, "name"), x, y, 0xFFFF55);
            graphics.drawString(font, str(c, "factionId") + " / " + str(c, "professionId"), x, y + 12, 0xAAAAAA);
            graphics.drawString(font, statusText(c), x, y + 24, 0x7FD8FF);
            String cooldown = str(c, "cooldownUntil").equals("0")
                    ? ""
                    : " " + Component.translatable("ccnr_rp.status.cooldown").getString();
            graphics.drawString(font, cooldown, x, y + 36, 0xFF5555);
            String background = str(c, "background");
            if (!background.isBlank()) {
                graphics.drawString(font, background, x, y + 50, 0xCCCCCC);
            }
            // 皮肤预览
            ResourceLocation tex = SkinCache.texture(str(c, "id"));
            if (tex != null) {
                graphics.blit(tex, x, y + 70, 64, 64, 0, 0, 64, 64, 64, 64);
            } else {
                graphics.fill(x, y + 70, x + 64, y + 134, 0x88444444);
            }
        }
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            graphics.drawCenteredString(font, notice, width / 2, height - 14, 0xFFFF55);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String statusText(JsonObject c) {
        String status = str(c, "status");
        return switch (status) {
            case "alive" -> Component.translatable("ccnr_rp.status.alive").getString();
            case "dead" -> Component.translatable("ccnr_rp.status.dead").getString();
            default -> Component.translatable("ccnr_rp.status.observing").getString();
        };
    }

    private void notice() {
        notice = "";
    }

    private void notice(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2500;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = (int) Math.max(0, scroll - delta / 10);
        if (chars.size() > (height - 28 - 30) / 20) {
            rebuild();
        }
        return true;
    }

    @Override
    public void onClose() {
        open = null;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** Holder 避免静态引用 Minecraft 造成类加载问题。 */
    private static final class MinecraftHolder {
        static net.minecraft.client.Minecraft minecraft() {
            return net.minecraft.client.Minecraft.getInstance();
        }
    }
}
