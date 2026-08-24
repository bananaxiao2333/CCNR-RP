/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 工程元数据一致性测试：gradle.properties / build.gradle / mods.toml 三者必须同步。
 * 例如版本号、modid、Corpse 可选依赖声明、spotless/test 设施是否就位（Phase 0 验收 P0-C 的一部分）。
 */
class ProjectMetadataTest {

    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-.][A-Za-z0-9]+)?$");

    private Properties props() throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("gradle.properties"))) {
            p.load(in);
        }
        return p;
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void gradlePropertiesAreConsistent() throws Exception {
        Properties p = props();
        assertEquals("ccnr_rp", p.getProperty("mod_id"));
        assertEquals("1.20.1", p.getProperty("minecraft_version"));
        assertEquals("official", p.getProperty("mapping_channel"));
        assertTrue(SEMVER.matcher(p.getProperty("mod_version")).matches(), "mod_version 必须是语义化版本");
        assertTrue(SEMVER.matcher(p.getProperty("forge_version")).matches(), "forge_version 必须是语义化版本");
    }

    @Test
    void buildGradleHasStyleAndTestInfrastructure() throws Exception {
        String g = read("build.gradle");
        assertTrue(g.contains("com.diffplug.spotless"), "缺少 spotless 插件");
        assertTrue(g.contains("palantirJavaFormat"), "缺少 palantirJavaFormat 风格");
        assertTrue(g.contains("licenseHeaderFile"), "缺少 license 头配置");
        assertTrue(g.contains("junit-jupiter"), "缺少 JUnit5 依赖");
        assertTrue(g.contains("runTests"), "缺少 -PrunTests 测试门控");
        assertTrue(g.contains("-Xlint:all"), "缺少 -Xlint:all 语法检查");
        assertTrue(
                g.contains("compileOnly fileTree(dir: 'libs', include: 'corpse-forge-1.20.1-*.jar')"),
                "缺少 Corpse 编译期依赖（fileTree）");
    }

    @Test
    void modsTomlDeclaresOptionalCorpseDependency() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/mods.toml")) {
            assertNotNull(in, "mods.toml 资源缺失");
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("modId=\"ccnr_rp\""), "mods.toml 缺少 ccnr_rp 声明");
            assertTrue(toml.contains("modId=\"corpse\""), "mods.toml 缺少 Corpse 依赖声明");
            assertTrue(toml.contains("mandatory=false"), "mods.toml 缺少 Corpse 可选依赖（mandatory=false）");
        }
    }
}
