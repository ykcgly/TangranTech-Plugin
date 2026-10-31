package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 极简 SQLite 连接层：<b>单连接 + 全局锁</b>。
 *
 * <h2>为什么不是连接池</h2>
 * <p>本插件的存档写入都是「一次操作写一份文档」的量级（秒级几次到几十次），
 * 用连接池只会引入额外的生命周期问题。SQLite 的写入本身在库级串行，
 * 单连接 + 锁既省资源，也彻底避免「多线程同时写导致 database is locked」。
 * 启用了 {@code WAL} + {@code synchronous=NORMAL}：写不阻塞读，且崩溃后不会坏库。</p>
 *
 * <h2>失败即降级</h2>
 * <p>驱动缺失 / 原生库加载失败（冷门平台）/ 文件不可写时，{@link #open(File)} 返回 false，
 * {@link #available()} 持续为 false —— {@link Store} 与 {@link AddonPaths} 会因此回落到
 * 原来的「按玩家分 JSON 文件」后端。降级只影响性能与可维护性，不影响任何玩法数据。</p>
 */
public final class Sqlite {

    private Sqlite() {}

    /** 全局锁：SQLite 的写是库级串行，这里也用同一把锁包住所有访问。 */
    private static final Object LOCK = new Object();

    private static Connection conn;
    private static File dbFile;

    /** 打开（并在文件不存在时创建）数据库；重复调用幂等。 */
    public static boolean open(File file) {
        synchronized (LOCK) {
            if (conn != null) return true;
            if (file == null) return false;
            try {
                // 驱动打在插件 jar 里，Class.forName 才能让 DriverManager 认出 jdbc:sqlite:
                Class.forName("org.sqlite.JDBC");
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    TT.log("[存储] 无法创建数据库目录: " + parent.getAbsolutePath());
                    return false;
                }
                Connection c = DriverManager.getConnection(
                        "jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/'));
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                conn = c;
                dbFile = file;
                return true;
            } catch (Throwable t) {
                TT.log("[存储] SQLite 不可用，已回落为按玩家分文件存储: " + t);
                conn = null;
                return false;
            }
        }
    }

    /** 驱动 + 连接是否可用（false 时调用方必须走文件后端）。 */
    public static boolean available() {
        return conn != null;
    }

    /** 当前数据库文件；未打开时为 null。 */
    public static File file() {
        return dbFile;
    }

    /** 在一次加锁的连接访问里执行任意 SQL 逻辑；不可用或异常时返回 {@code fallback}。 */
    public static <T> T within(SqlFn<T> fn, T fallback) {
        synchronized (LOCK) {
            if (conn == null) return fallback;
            try {
                return fn.apply(conn);
            } catch (Throwable t) {
                TT.log("[存储] SQLite 访问失败: " + t);
                return fallback;
            }
        }
    }

    /** 加锁执行一条写语句，返回受影响行数（失败 -1）。 */
    public static int exec(String sql, Object... args) {
        return within(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        }, -1);
    }

    /**
     * 按 SQL 文本复用的预编译语句表。
     *
     * <p>只在 {@link #within} 的全局锁内访问，天然串行，普通 {@code HashMap} 即可。
     * 原实现每次 {@code prepareStatement} 都让 SQLite 重新校验 schema，
     * 单次实测毫秒级（spark: prepare_utf8 1.45ms/次）—— 复用后降到微秒级。
     * 语句总数有限（各查询一个），不会膨胀。</p>
     */
    private static final java.util.HashMap<String, PreparedStatement> STMTS = new java.util.HashMap<>();

    /** 加锁执行一条查询，{@code reader} 消费结果集（失败返回 {@code fallback}）。 */
    public static <T> T select(String sql, RowReader<T> reader, T fallback, Object... args) {
        return within(c -> {
            PreparedStatement ps = STMTS.get(sql);
            if (ps == null) {
                ps = c.prepareStatement(sql);
                STMTS.put(sql, ps);
            }
            try {
                ps.clearParameters();
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return reader.read(rs);
                }
            } catch (SQLException e) {
                // 语句可能已处于坏状态（schema 变更 / 句柄失效）：踢出缓存下次重建
                STMTS.remove(sql);
                try {
                    ps.close();
                } catch (Throwable ignored) {
                    // 尽力而为
                }
                throw e;
            }
        }, fallback);
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    /** 关闭连接（插件卸载时调用）。 */
    public static void close() {
        synchronized (LOCK) {
            for (PreparedStatement ps : STMTS.values()) {
                try {
                    ps.close();
                } catch (Throwable ignored) {
                    // 卸载阶段失败无所谓
                }
            }
            STMTS.clear();
            try {
                if (conn != null) conn.close();
            } catch (Throwable ignored) {
                // 卸载阶段失败无所谓
            }
            conn = null;
            dbFile = null;
        }
    }

    /** 连接上的任意操作。 */
    @FunctionalInterface
    public interface SqlFn<T> {
        T apply(Connection connection) throws Exception;
    }

    /** 结果集消费器。 */
    @FunctionalInterface
    public interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }
}
