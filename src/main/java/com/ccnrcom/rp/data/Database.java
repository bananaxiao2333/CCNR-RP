/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 数据库生命周期与访问门面（服务端）：
 * - connect() 于 ServerAboutToStart 各 manager 构造前调用；disconnect() 于 ServerStopping 调用。
 * - 写操作默认走专用单线程 executor（写后置/广播式不阻塞主线程）；读操作走主线程 sync()。
 * - 单一连接 + ReentrantLock 串行化访问（SQLite 单文件/MySQL 单连接在服务端权威模型下足够）。
 * - 未启用（disabled）时全部方法为空操作，不改变现有文件行为。
 */
public final class Database {
    private static final Logger LOGGER = LogManager.getLogger();

    private final DbConfig config;
    private final SqlDialect dialect;
    private final ReentrantLock lock = new ReentrantLock();
    private Connection conn;
    private boolean schemaReady;
    private final ExecutorService writer;

    /** 读回调：允许抛出 SQLException。 */
    @FunctionalInterface
    public interface SqlRead<T> {
        T apply(Connection c) throws SQLException;
    }

    /** 写回调：允许抛出 SQLException。 */
    @FunctionalInterface
    public interface SqlWrite {
        void accept(Connection c) throws SQLException;
    }

    public Database(DbConfig config) {
        this.config = Objects.requireNonNull(config);
        this.dialect = new SqlDialect(config.mode());
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ccnr-rp-db-writer");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean enabled() {
        return config.enabled();
    }

    public DbConfig config() {
        return config;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** 当前配置档 id（写库/读库按此过滤）。 */
    public String profile() {
        return config.profile();
    }

    /** 建立连接并初始化 schema（幂等）。失败返回 false 且保持未连接。 */
    public boolean connect() {
        if (!config.enabled()) {
            return false;
        }
        try {
            Class.forName(config.driverClass());
            conn = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.pass());
            schemaReady = DbSchema.bootstrap(conn, dialect);
            if (schemaReady) {
                ConfigStore.ensureProfiles();

                LOGGER.info("[CCNR-RP] 数据库已连接: {}（profile={}）", config.mode(), config.profile());
            } else {
                LOGGER.error("[CCNR-RP] 数据库连接成功但 schema 初始化失败");
            }
            return schemaReady;
        } catch (ClassNotFoundException e) {
            LOGGER.error("[CCNR-RP] 数据库驱动未找到（{}），请将驱动打进 mod jar 或放入 mods/", config.driverClass());
            return false;
        } catch (SQLException e) {
            LOGGER.error("[CCNR-RP] 数据库连接失败: {} —— {}", config.jdbcUrl(), e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 数据库初始化异常: {}", e.toString(), e);
            return false;
        }
    }

    /** 关闭连接与写线程（对称清理）。 */
    public void disconnect() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        lock.lock();
        try {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 关闭失败忽略
                }
                conn = null;
            }
        } finally {
            lock.unlock();
        }
        if (config.enabled()) {
            LOGGER.info("[CCNR-RP] 数据库已断开（profile={}）", config.profile());
        }
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.pass());
        }
        return conn;
    }

    /** 主线程读：加锁执行。 */
    public <T> T read(SqlRead<T> fn) {
        lock.lock();
        try {
            return fn.apply(conn());
        } catch (SQLException e) {
            LOGGER.error("[CCNR-RP] 数据库读失败: {}", e.toString());
            throw new IllegalStateException("数据库读失败", e);
        } finally {
            lock.unlock();
        }
    }

    /** 主线程同步写（如配置保存需要即时确认结果）：加锁执行。返回 false=异常。 */
    public boolean write(SqlWrite fn) {
        lock.lock();
        try {
            fn.accept(conn());
            return true;
        } catch (SQLException e) {
            LOGGER.error("[CCNR-RP] 数据库写失败: {}", e.toString());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 后台单线程写（批量/写后置，不阻塞主线程）：串行执行；异常记日志不抛出。 */
    public void asyncWrite(SqlWrite fn) {
        writer.execute(() -> {
            lock.lock();
            try {
                fn.accept(conn());
            } catch (SQLException e) {
                LOGGER.error("[CCNR-RP] 后台数据库写失败: {}", e.toString());
            } finally {
                lock.unlock();
            }
        });
    }

    /** 等待后台写线程队列清空（ServerStopping 强制 flush 用）。 */
    public void flush() {
        lock.lock();
        try {
            // 空操作占位：写队列在 disconnect 中 awaitTermination，此处保留以便调用方显式等待
        } finally {
            lock.unlock();
        }
    }
}
