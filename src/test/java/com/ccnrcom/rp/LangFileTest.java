/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 语言包一致性回归测试：防止"只更新 zh_cn 漏掉 en_us"（红字原始键）或键漂移。
 * 镜像自 CCNR-Com 的 LangFileTest 思路。键命名空间允许 ccnr_rp.* 与 MC 键位约定 key.*。
 *
 * <p><b>为什么需要 {@link #everyKeyReferencedByCodeExists()}（2.26.9 教训）</b>：上面几个用例只比对
 * zh/en 的**键集合是否互相一致**，所以当一次误操作（{@code git checkout -- lang/}）把两份文件同时退回旧版，
 * 双方一起丢失同一批键时，它们全部通过，而客户端把原始键渲染成红字（{@code [ ccnr_rp.match.label.mode ]}）。
 * 该类问题只有"以代码为基准反查"才拦得住，因此本用例成为语言包的第二道（也是真正的）防线。
 */
class LangFileTest {

    /**
     * 扫描到的"形如语言键、但本就不是语言键"的白名单——只登记经过确认的例外，不做宽泛豁免。
     *
     * <p>以 {@code .} 结尾的字符串是**拼接前缀**（如 {@code "ccnr_rp.status." + id}），键在运行时才成形，
     * 静态扫描无法还原，故在扫描阶段整体排除，此处不再重复登记。
     */
    private static final Set<String> NON_LANG_LITERALS = Set.of(
            // JVM 属性名（client/ClientAudio.java: System.getProperty），只是恰好同前缀，不是翻译键。
            "ccnr_rp.entrance_music");

    /** 代码里出现的 ccnr_rp.* 字面量：匹配 [A-Za-z0-9_.-]，不含引号。 */
    private static final Pattern LANG_LITERAL = Pattern.compile("\"(ccnr_rp\\.[A-Za-z0-9_.\\-]+)\"");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private static final Set<String> REQUIRED_KEYS =
            Set.of("ccnr_rp.mod.name", "ccnr_rp.command.help", "ccnr_rp.status.alive", "ccnr_rp.status.dead");

    /**
     * 自定义设定（docs/17）的必需键：命令输出与页签标签漏掉任何一个都会在游戏里显示成原始键。
     * 新增页签/命令时在这里登记，漏翻会被本用例挡下。
     */
    private static final Set<String> VARIABLE_KEYS = Set.of(
            "ccnr_rp.command.usage.var",
            "ccnr_rp.command.var.list_header",
            "ccnr_rp.command.var.unknown",
            "ccnr_rp.command.var.saved",
            "ccnr_rp.command.var.preset_applied",
            "ccnr_rp.command.var.scheme_applied",
            "ccnr_rp.gui.admin.tab.variables",
            "ccnr_rp.gui.admin.var.list",
            "ccnr_rp.gui.admin.var.id",
            "ccnr_rp.gui.admin.var.preset_value",
            "ccnr_rp.gui.admin.var.scheme_id");

    /** 已删除功能（区域 / 弹头许可，docs/16 §5；入场电影版式切换开关，docs/14 §6）的键必须清理干净，不得留死键。 */
    private static final Set<String> REMOVED_KEYS = Set.of(
            "ccnr_rp.command.usage.area",
            "ccnr_rp.command.area.list_header",
            "ccnr_rp.command.area.info",
            "ccnr_rp.command.faction.warhead",
            "ccnr_rp.gui.admin.area.list",
            "ccnr_rp.gui.admin.warhead.list",
            "ccnr_rp.gui.admin.faction.warhead",
            "ccnr_rp.gui.admin.tab.extension",
            "ccnr_rp.gui.admin.faction.cinematic_compact",
            // K 面板三栏结构重设计（docs/14 §5.6）：旧三栏列头与外部参考界面的窗体名不再存在，
            // 对应键必须随结构一起清掉，避免"删结构留死键"。
            "ccnr_rp.gui.character.nav",
            "ccnr_rp.gui.character.position_list",
            "ccnr_rp.gui.character.db_header",
            "ccnr_rp.gui.character.net_header");

    private Map<String, String> load(String path) {
        try (InputStream in = LangFileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源不存在: " + path);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), (Type) Map.class);
        } catch (Exception e) {
            throw new AssertionError("语言包解析失败: " + path, e);
        }
    }

    @Test
    void zhAndEnHaveIdenticalKeys() {
        Map<String, String> zh = load("/assets/ccnr_rp/lang/zh_cn.json");
        Map<String, String> en = load("/assets/ccnr_rp/lang/en_us.json");
        assertEquals(new TreeSet<>(zh.keySet()), new TreeSet<>(en.keySet()), "zh_cn 与 en_us 的键集合不一致");
    }

    @Test
    void allKeysFollowNamespaceAndRequiredKeysExist() {
        Map<String, String> zh = load("/assets/ccnr_rp/lang/zh_cn.json");
        for (String key : zh.keySet()) {
            assertTrue(key.startsWith("ccnr_rp.") || key.startsWith("key."), "键未遵循命名空间: " + key);
            assertFalse(key.equals(zh.get(key)), "值渲染成了原始键（漏翻译）: " + key);
        }
        assertTrue(
                zh.keySet().containsAll(REQUIRED_KEYS),
                "缺少必需键，差异: "
                        + new TreeSet<>(REQUIRED_KEYS)
                                .stream().filter(k -> !zh.containsKey(k)).toList());
    }

    @Test
    void variableFeatureKeysExistInBothLanguages() {
        for (String path : new String[] {"/assets/ccnr_rp/lang/zh_cn.json", "/assets/ccnr_rp/lang/en_us.json"}) {
            Map<String, String> lang = load(path);
            assertTrue(
                    lang.keySet().containsAll(VARIABLE_KEYS),
                    path + " 缺少自定义设定键: "
                            + new TreeSet<>(VARIABLE_KEYS)
                                    .stream().filter(k -> !lang.containsKey(k)).toList());
        }
    }

    @Test
    void removedFeatureKeysAreGone() {
        for (String path : new String[] {"/assets/ccnr_rp/lang/zh_cn.json", "/assets/ccnr_rp/lang/en_us.json"}) {
            Map<String, String> lang = load(path);
            Set<String> leftovers = new TreeSet<>(REMOVED_KEYS);
            leftovers.retainAll(lang.keySet());
            assertTrue(leftovers.isEmpty(), path + " 残留已删除功能的死键: " + leftovers);
        }
    }

    /**
     * 以代码为基准的反查：源码里每一个 {@code "ccnr_rp.*"} 字面量都必须在 zh_cn 与 en_us 中同时存在。
     *
     * <p>扫描范围是 {@code src/main/java} 下的 .java（测试代码不面向玩家，不参与）。为避免把文档注释里
     * 举的例子也当成硬引用，先剥掉块注释与行注释再匹配。以 {@code .} 结尾的字面量是拼接前缀，跳过。
     */
    @Test
    void everyKeyReferencedByCodeExists() {
        Map<String, String> zh = load("/assets/ccnr_rp/lang/zh_cn.json");
        Map<String, String> en = load("/assets/ccnr_rp/lang/en_us.json");

        Map<String, TreeSet<String>> referenced = scanLangLiterals(Path.of("src", "main", "java"));
        // 反向断言：扫描本身要能匹配到足量键，否则路径写错时本用例会"静默全绿"（最危险的假阳性）。
        assertTrue(
                referenced.size() > 400,
                "源码扫描结果过少（" + referenced.size() + " 个键），扫描路径或正则可能已失效；" + "当前工作目录: "
                        + Path.of("").toAbsolutePath());

        List<String> missingZh = new ArrayList<>();
        List<String> missingEn = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : referenced.entrySet()) {
            String key = e.getKey();
            if (!zh.containsKey(key)) {
                missingZh.add(key + "  (" + String.join(", ", e.getValue()) + ")");
            }
            if (!en.containsKey(key)) {
                missingEn.add(key);
            }
        }
        assertTrue(missingZh.isEmpty(), "以下语言键被代码引用但 zh_cn.json 中不存在（游戏内会显示原始键）:\n  " + String.join("\n  ", missingZh));
        assertTrue(missingEn.isEmpty(), "以下语言键被代码引用但 en_us.json 中不存在:\n  " + String.join("\n  ", missingEn));
    }

    /**
     * 收集 {@code root} 下所有 .java 源码中被引用（去注释后）的语言键，值记录引用它的文件（用于报错定位）。
     *
     * <p>键名按字典序返回，保证报错信息稳定可比对。
     */
    private static Map<String, TreeSet<String>> scanLangLiterals(Path root) {
        assertTrue(Files.isDirectory(root), "源码目录不存在: " + root.toAbsolutePath());
        Map<String, TreeSet<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text;
                try {
                    text = BLOCK_COMMENT
                            .matcher(Files.readString(file, StandardCharsets.UTF_8))
                            .replaceAll("");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                text = LINE_COMMENT.matcher(text).replaceAll("");
                Matcher m = LANG_LITERAL.matcher(text);
                while (m.find()) {
                    String key = m.group(1);
                    // 拼接前缀（"ccnr_rp.status." + id）在运行时才成形，静态扫描无从校验，跳过。
                    if (key.endsWith(".") || NON_LANG_LITERALS.contains(key)) {
                        continue;
                    }
                    found.computeIfAbsent(key, k -> new TreeSet<>())
                            .add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LinkedHashMap<>(found);
    }
}
