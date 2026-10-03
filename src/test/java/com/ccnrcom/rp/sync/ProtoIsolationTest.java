/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「可选依赖不许被必然加载的类引用」的隔离门禁。
 *
 * <h2>它守的是什么（2.27.0 真实事故）</h2>
 * 用户实机装上**普通 jar**（不含嵌套的 protobuf / gRPC）后，服务器启动约 3 秒崩在 tick 循环里：
 *
 * <pre>
 * java.lang.NoClassDefFoundError: com/google/protobuf/ProtocolMessageEnum
 *   at com.ccnrcom.rp.event.EventManager.evaluateAll(EventManager.java:361)
 *   at com.ccnrcom.rp.event.EventManager.onServerTick(EventManager.java:329)
 * </pre>
 *
 * 探测本身没错（日志明确报了「未找到 gRPC 运行时」），错在**上报 API 的签名里带着生成类型**
 * （{@code EventKind} / {@code RecordEventRequest.Builder}）：调用点必须解析 protobuf 类
 * 才能执行到「{@code data == null} 就跳过」那一行，于是短路永远轮不到。
 *
 * <p>修法是把通信类型收敛进 {@link DataLink}（见 {@link EventKinds} 与 {@link EventPayload} 的类注释）。
 * 但「收敛」这件事光靠注释守不住 —— 下一个给事件加字段的人很自然就会把 {@code EventKind}
 * 再写回调用点。因此这里用两条**能在编译期跑、不需要起服务器**的断言把它钉死。
 *
 * <h2>两条断言分别挡什么</h2>
 * <ol>
 *   <li>{@link #wireTypesAreConfinedToDataLink()}：扫编译产物，除 {@code sync/DataLink*} 与生成包之外，
 *       任何类都不许引用 {@code com/ccnrcom/rp/data/grpc/v1} 或 {@code io/grpc}；</li>
 *   <li>{@link #reportingWorksWithoutProtobufOnClasspath()}：把 protobuf 与 gRPC 从类路径里**真的拿掉**，
 *       在一个隔离的类加载器里调用上报入口 —— 这正是崩溃现场的那次调用，必须不抛异常。</li>
 * </ol>
 * 第二条同时自检「过滤器真的生效了」（否则它会因为类路径里还有 protobuf 而永远通过 = 假门禁）。
 */
class ProtoIsolationTest {

    /** 只允许这两个位置引用通信类型。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("sync/DataLink", "data/grpc/v1/");

    private static final String WIRE_PROTOBUF = "com/ccnrcom/rp/data/grpc/v1";
    private static final String WIRE_GRPC = "io/grpc";

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties")) && Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("找不到项目根目录：user.dir=" + System.getProperty("user.dir"));
    }

    // ------------------------------------------------------------------
    // ① 编译产物扫描
    // ------------------------------------------------------------------

    @Test
    @DisplayName("通信类型只许出现在 sync.DataLink 与生成包里（否则普通 jar 一走到那行就崩）")
    void wireTypesAreConfinedToDataLink() throws IOException {
        Path classes = projectRoot().resolve("build/classes/java/main/com/ccnrcom/rp");
        if (!Files.isDirectory(classes)) {
            fail("找不到编译产物 " + classes + "（门禁要求先 compileJava；静默跳过等于没有门禁）");
            return;
        }

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = classes.relativize(f).toString().replace(File.separatorChar, '/');
                if (ALLOWED_PREFIXES.stream().anyMatch(rel::startsWith)) continue;

                byte[] bytes = Files.readAllBytes(f);
                scanned++;
                // 常量池里的类名是原样的 UTF-8 字节，直接找即可（不需要字节码库）
                if (containsAscii(bytes, WIRE_PROTOBUF) || containsAscii(bytes, WIRE_GRPC)) {
                    offenders.add("com.ccnrcom.rp." + rel.replace('/', '.').replace(".class", ""));
                }
            }
        }
        assertTrue(scanned > 0, "没有扫到任何 class 文件，门禁失效");

        assertTrue(
                offenders.isEmpty(),
                "以下类引用了 protobuf/gRPC 类型。它们**必然会被加载**，于是普通 jar（不含嵌套依赖）"
                        + "一走到这些引用就抛 NoClassDefFoundError —— 这正是 2.27.0 崩掉整个服务器 tick 循环的原因。\n"
                        + "请改用 sync.EventKinds 的字符串种类与 sync.EventPayload 接口（见 DataReports 的类注释）："
                        + offenders);
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // ② 真的把 protobuf 拿掉再调用一次上报
    // ------------------------------------------------------------------

    @Test
    @DisplayName("拿掉 protobuf/gRPC 后调用上报入口不抛异常（复现崩溃现场的那次调用）")
    void reportingWorksWithoutProtobufOnClasspath() throws Exception {
        ClassLoader isolated = isolatedLoader();

        // 自检：过滤器必须真的把 protobuf 拿掉了，否则本测试永远通过 = 假门禁
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.google.protobuf.ProtocolMessageEnum", false, isolated),
                "隔离类加载器里仍然能找到 protobuf —— 过滤器失效，本测试无法证明任何事");

        Class<?> reports = Class.forName("com.ccnrcom.rp.sync.DataReports", true, isolated);

        // 崩溃现场的同形状调用：无载荷重载（阶段推进）
        Method plain = reports.getMethod("event", String.class, String.class);
        plain.invoke(null, EventKinds.PHASE_ADVANCED, "阶段推进：(首幕) → dusk");

        // 带载荷重载：消费 EventPayload（绝不接触 protobuf）
        Method withPayload = reports.getMethod("event", String.class, String.class, Consumer.class);
        withPayload.invoke(null, EventKinds.CHARACTER_KILL, "A 击杀了 B", (Consumer<Object>) p -> {});

        // 结算入口同理（它原先由调用方自己 build protobuf 消息）。
        // 注意：这里的参数类型必须**从隔离加载器里取**。用本测试自己的 SettlementDraft.class
        // 会得到另一个 Class 对象（两个加载器各有一份），getMethod 只会抛 NoSuchMethodException ——
        // 那是反射的失败，不是被测代码的失败，会掩盖真正的回归。
        Class<?> draftType = Class.forName(SettlementDraft.class.getName(), true, isolated);
        Object draft = draftType
                .getConstructor(
                        String.class,
                        String.class,
                        String.class,
                        long.class,
                        long.class,
                        int.class,
                        int.class,
                        long.class,
                        List.class)
                .newInstance("uuid", "Alice", "death", 0L, 100L, 0, 1, 100L, List.of());

        Method settlement = reports.getMethod("settlement", draftType, Runnable.class);
        settlement.invoke(null, draft, (Runnable) () -> {});
    }

    /**
     * 用当前类路径**去掉 protobuf / gRPC** 后构造的隔离类加载器。
     *
     * <p>父加载器传 {@code null}（bootstrap）：否则父加载器会顺着自己的类路径把 protobuf 找回来，
     * 那样这个测试就变成自欺欺人了。
     */
    private static ClassLoader isolatedLoader() throws Exception {
        List<URL> urls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            String lower = entry.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("protobuf") || lower.contains("grpc") || lower.contains("perfmark")) {
                continue; // 这就是「普通 jar」缺的那部分
            }
            if (seen.add(entry)) {
                urls.add(new File(entry).toURI().toURL());
            }
        }
        Path classes = projectRoot().resolve("build/classes/java/main");
        if (!Files.isDirectory(classes)) {
            fail("找不到编译产物 " + classes);
        }
        urls.add(classes.toUri().toURL());
        assertTrue(urls.size() > 1, "类路径过滤后只剩编译产物，MC/Forge 依赖丢了，测试无意义");
        return new URLClassLoader(urls.toArray(new URL[0]), null);
    }
}
