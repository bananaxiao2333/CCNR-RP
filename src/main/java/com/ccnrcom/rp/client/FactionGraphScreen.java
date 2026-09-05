/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.faction.RelationType;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 关系测定图（全屏）：每个阵营 = 徽章 + 名称标签；有关系的阵营对之间连线
 * （白=中立 / 红=敌对 / 绿=友好）。可拖动平移、滚轮缩放，Esc 关闭返回。
 * 打开时作为独立全屏 Screen 显示（下层管理面板暂时隐藏，与部署点弹窗同逻辑）。
 */
public final class FactionGraphScreen extends Screen {

    /** 节点：阵营 JSON + 世界坐标（缩放前）。 */
    private record Node(JsonObject faction, double x, double y) {}

    private final List<Node> nodes = new ArrayList<>();
    /** 边：aId|bId|type。 */
    private final List<String[]> edges = new ArrayList<>();

    private double offsetX = 0;
    private double offsetY = 0;
    private double scale = 1.0;
    private static final double MIN_SCALE = 0.4;
    private static final double MAX_SCALE = 3.0;
    private boolean dragging = false;

    private static final int CLOSE_SIZE = 20;
    private static final int CLOSE_X = 8;
    private static final int CLOSE_Y = 8;

    private final Screen parent;

    public FactionGraphScreen() {
        this(Minecraft.getInstance().screen);
    }

    /** 从上层界面打开（关闭时返回该界面）；命令打开时上层为 null → 关闭回游戏。 */
    public FactionGraphScreen(Screen parent) {
        super(Component.translatable("ccnr_rp.gui.admin.graph.title"));
        this.parent = parent;
        buildNodes();
    }

    private void buildNodes() {
        List<JsonObject> facs = ClientCharacterState.factions();
        nodes.clear();
        edges.clear();
        if (facs.isEmpty()) {
            return;
        }
        // 环形布局：圆心 (0,0)，半径随数量缩放，均匀分布
        int n = facs.size();
        double radius = 150 + 40.0 * Math.sqrt(n);
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n - Math.PI / 2;
            nodes.add(new Node(facs.get(i), radius * Math.cos(ang), radius * Math.sin(ang)));
        }
        Map<String, String> byPair = new LinkedHashMap<>();
        for (JsonObject r : ClientCharacterState.relations()) {
            String a = str(r, "a");
            String b = str(r, "b");
            String type = str(r, "type");
            if (a.isBlank() || b.isBlank() || a.equals(b)) {
                continue;
            }
            String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            byPair.putIfAbsent(key, type);
        }
        for (Map.Entry<String, String> e : byPair.entrySet()) {
            String[] ids = e.getKey().split("\\|", 2);
            edges.add(new String[] {ids[0], ids[1], e.getValue()});
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private Node nodeOf(String id) {
        for (Node n : nodes) {
            if (str(n.faction(), "id").equals(id)) {
                return n;
            }
        }
        return null;
    }

    /** 鼠标所在的节点（徽章圆内，含少量余量）；不在任何节点上返回 null。 */
    private Node hoveredNode(double mouseX, double mouseY) {
        int cx = width / 2;
        int cy = height / 2;
        for (Node n : nodes) {
            int x = cx + (int) Math.round(offsetX + n.x() * scale);
            int y = cy + (int) Math.round(offsetY + n.y() * scale);
            int r = Math.max(10, (int) Math.round(14 * scale));
            double dx = mouseX - x;
            double dy = mouseY - y;
            if (dx * dx + dy * dy <= (r + 4.0) * (r + 4.0)) {
                return n;
            }
        }
        return null;
    }

    // ---------- 拖动 / 缩放 ----------

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0) {
            dragging = true;
            offsetX += dx;
            offsetY += dy;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 以光标为锚点缩放
        double prev = scale;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * (delta > 0 ? 1.15 : 1 / 1.15)));
        double k = scale / prev - 1;
        offsetX -= (mouseX - width / 2.0 - offsetX) * k;
        offsetY -= (mouseY - height / 2.0 - offsetY) * k;
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= CLOSE_X
                && mouseX <= CLOSE_X + CLOSE_SIZE
                && mouseY >= CLOSE_Y
                && mouseY <= CLOSE_Y + CLOSE_SIZE) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent); // 返回上层（管理面板/关系管理面板）；无上层则回游戏
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 深色全屏底
        g.fill(0, 0, width, height, 0xEE161616);
        RpTheme.scanlines(g, 0, 0, width, height);

        var font = Minecraft.getInstance().font;
        int cx = width / 2;
        int cy = height / 2;

        // 悬停高亮：鼠标放在某阵营图标上时，保留该阵营 + 其连线 + 直接相连阵营（连线/图标），其余全部变暗
        Node hover = hoveredNode(mouseX, mouseY);
        Set<String> keep = new HashSet<>();
        if (hover != null) {
            keep.add(str(hover.faction(), "id"));
            for (String[] e : edges) {
                if (e[0].equals(str(hover.faction(), "id")) || e[1].equals(str(hover.faction(), "id"))) {
                    keep.add(e[0]);
                    keep.add(e[1]);
                }
            }
        }

        // 连线（画在节点下层）：悬停时只保留与悬停阵营相连的边
        for (String[] e : edges) {
            Node na = nodeOf(e[0]);
            Node nb = nodeOf(e[1]);
            if (na == null || nb == null) {
                continue;
            }
            boolean dim = hover != null
                    && !e[0].equals(str(hover.faction(), "id"))
                    && !e[1].equals(str(hover.faction(), "id"));
            int color = edgeColor(e[2]);
            if (dim) {
                color = (color & 0x00FFFFFF) | 0x32000000; // 低透明度 → 变暗
            }
            int x1 = cx + (int) Math.round(offsetX + na.x() * scale);
            int y1 = cy + (int) Math.round(offsetY + na.y() * scale);
            int x2 = cx + (int) Math.round(offsetX + nb.x() * scale);
            int y2 = cy + (int) Math.round(offsetY + nb.y() * scale);
            drawLine(g, x1, y1, x2, y2, color);
        }

        // 节点：徽章 + 名称（悬停时保留悬停阵营与直接相连阵营，其余盖半透明深色罩变暗）
        for (Node n : nodes) {
            int x = cx + (int) Math.round(offsetX + n.x() * scale);
            int y = cy + (int) Math.round(offsetY + n.y() * scale);
            int r = Math.max(10, (int) Math.round(14 * scale));
            boolean dim = hover != null && !keep.contains(str(n.faction(), "id"));
            RpIcons.factionBadge(g, x, y, r, n.faction(), false);
            if (dim) {
                RpIcons.circle(g, x, y, r + 1, 0xA80E1014);
            }
            String name = str(n.faction(), "name");
            if (name.isBlank()) {
                name = str(n.faction(), "id");
            }
            g.drawCenteredString(font, Component.literal(name), x, y + r + 3, dim ? 0xFF666666 : RpTheme.TEXT_PRIMARY);
        }

        // 图例 + 操作提示（左上角）
        int ly = 30;
        g.drawString(font, Component.translatable("ccnr_rp.gui.admin.graph.legend"), 10, ly, RpTheme.TEXT_SECONDARY);
        ly += 12;
        legendLine(g, 10, ly, 0xFFFFFFFF, "ccnr_rp.gui.admin.graph.neutral");
        ly += 12;
        legendLine(g, 10, ly, RpTheme.RED, "ccnr_rp.gui.admin.graph.hostile");
        ly += 12;
        legendLine(g, 10, ly, RpTheme.GREEN, "ccnr_rp.gui.admin.graph.friendly");
        ly += 16;
        g.drawString(font, Component.translatable("ccnr_rp.gui.admin.graph.hint"), 10, ly, RpTheme.TEXT_DIM);

        // 标题 + 关闭按钮
        g.drawCenteredString(
                font, Component.translatable("ccnr_rp.gui.admin.graph.title"), width / 2, 12, RpTheme.CYAN);
        RpRoundRect.outlined(
                g, CLOSE_X, CLOSE_Y, CLOSE_X + CLOSE_SIZE, CLOSE_Y + CLOSE_SIZE, 2, RpTheme.PANEL_BORDER, 0x00);
        g.drawCenteredString(font, Component.literal("✕"), CLOSE_X + CLOSE_SIZE / 2, CLOSE_Y + 4, RpTheme.TEXT_PRIMARY);
    }

    private void legendLine(GuiGraphics g, int x, int y, int color, String key) {
        g.fill(x, y + 4, x + 14, y + 6, color);
        g.drawString(Minecraft.getInstance().font, Component.translatable(key), x + 20, y, RpTheme.TEXT_SECONDARY);
    }

    private static int edgeColor(String type) {
        RelationType t = RelationType.parse(type);
        if (t == RelationType.HOSTILE) {
            return RpTheme.RED;
        }
        if (t == RelationType.FRIENDLY) {
            return RpTheme.GREEN;
        }
        return 0xFFFFFFFF; // 中立 = 白
    }

    /** 屏幕空间直线（逐点填充，1px）。 */
    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        int steps = (int) Math.ceil(len / 2.0);
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (int) Math.round(dx * i / steps);
            int y = y1 + (int) Math.round(dy * i / steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
