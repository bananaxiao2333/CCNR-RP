/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 数据库连接配置（config/db.properties，服务端）。
 * 纯逻辑：解析/校验/掩码，无 MC 依赖，可直接单测。密码可从环境变量 {@code CCNR_DB_PASS} 覆盖。
 */
public final class DbConfig {
    private static final Logger LOGGER = LogManager.getLogger();

    private boolean enabled;
    private DbType mode = DbType.SQLITE;
    private String sqliteFile = "config/ccnr_rp/ccnr-rp.db";
    private String host = "localhost";
    private int port = 3306;
    private String database = "ccnr_rp";
    private String user = "ccnr";
    private String pass = "";
    private int poolSize = 4;
    private String profile = "default";
    private boolean fallbackToFiles = false;

    public boolean enabled() {
        return enabled;
    }

    public DbType mode() {
        return mode;
    }

    public String sqliteFile() {
        return sqliteFile;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String user() {
        return user;
    }

    public String pass() {
        return pass;
    }

    public int poolSize() {
        return poolSize;
    }

    public String profile() {
        return profile;
    }

    public boolean fallbackToFiles() {
        return fallbackToFiles;
    }

    /** 缺省配置：不启用。 */
    public static DbConfig disabled() {
        DbConfig c = new DbConfig();
        c.enabled = false;
        return c;
    }

    /** 从属性文件读取；文件缺失或未 enabled 返回 disabled。 */
    public static DbConfig load(Path file) {
        if (!Files.exists(file)) {
            return disabled();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            LOGGER.error("[CCNR-RP] db.properties 读取失败，禁用数据库: {}", file, e);
            return disabled();
        }
        DbConfig c = new DbConfig();
        c.enabled = Boolean.parseBoolean(p.getProperty("db.enabled", "false"));
        c.mode = DbType.parse(p.getProperty("db.mode", "sqlite"));
        if (c.mode == null) {
            LOGGER.error("[CCNR-RP] db.properties 无效 db.mode，禁用数据库");
            return disabled();
        }
        c.sqliteFile = p.getProperty("db.file", c.sqliteFile);
        c.host = p.getProperty("db.host", c.host);
        c.port = intProp(p, "db.port", c.port);
        c.database = p.getProperty("db.database", c.database);
        c.user = p.getProperty("db.user", c.user);
        c.pass = pass(p.getProperty("db.pass", ""));
        c.poolSize = intProp(p, "db.poolSize", c.poolSize);
        c.profile = p.getProperty("db.profile", c.profile);
        c.fallbackToFiles = Boolean.parseBoolean(p.getProperty("db.fallbackToFiles", "false"));
        return c;
    }

    /** 密码解析：支持环境变量覆盖（空值回退环境变量 _CCNR_DB_PASS）。 */
    private static String pass(String val) {
        if (val != null && !val.isBlank()) {
            return val;
        }
        String env = System.getenv("CCNR_DB_PASS");
        return env == null ? "" : env;
    }

    private static int intProp(Properties p, String key, int def) {
        String s = p.getProperty(key);
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 掩码密码（日志用）。 */
    public String maskedPass() {
        return pass == null || pass.isEmpty() ? "(empty)" : "******";
    }

    /** JDBC URL：SQLite 为文件路径，MySQL 为标准网络 URL。 */
    public String jdbcUrl() {
        if (mode == DbType.MYSQL) {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        }
        return "jdbc:sqlite:" + sqliteFile;
    }

    /** 驱动类名。 */
    public String driverClass() {
        return mode == DbType.MYSQL ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC";
    }

    @Override
    public String toString() {
        return "DbConfig{enabled=" + enabled + ", mode=" + mode + ", file=" + sqliteFile + ", host=" + host + ", port="
                + port + ", db=" + database + ", user=" + user + ", pass=" + maskedPass() + ", poolSize=" + poolSize
                + ", profile=" + profile + ", fallbackToFiles=" + fallbackToFiles + "}";
    }
}
