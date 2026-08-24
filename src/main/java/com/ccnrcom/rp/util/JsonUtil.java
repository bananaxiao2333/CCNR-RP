/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** JSON 读写工具：统一格式化、原子写、损坏容错（保留 .bak）。 */
public final class JsonUtil {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonUtil() {}

    /** 原子写：先写 <file>.tmp，再移动覆盖；失败时原文件保持不动。 */
    public static boolean atomicWrite(Path file, JsonElement element) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(element) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.error("[CCNR-RP] 写入失败: {} —— {}", file, e.toString());
            return false;
        }
    }

    /** 读取 JSON 对象；文件缺失返回空；解析失败备份 .bak 并返回空（服务继续）。 */
    public static Optional<JsonObject> readObject(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement el = GSON.fromJson(reader, JsonElement.class);
            if (!el.isJsonObject()) {
                throw new JsonParseException("根节点不是 JSON 对象");
            }
            return Optional.of(el.getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] JSON 解析失败: {} —— {}", file, e.toString());
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyErr) {
                LOGGER.error("[CCNR-RP] 备份失败: {} —— {}", file, copyErr.toString());
            }
            return Optional.empty();
        }
    }

    /** 从 classpath 资源读取 JSON 对象（用于默认配置模板）。 */
    public static Optional<JsonObject> readResource(String path) {
        try (var in = JsonUtil.class.getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
        } catch (IOException e) {
            LOGGER.error("[CCNR-RP] 资源读取失败: {} —— {}", path, e.toString());
            return Optional.empty();
        }
    }
}
