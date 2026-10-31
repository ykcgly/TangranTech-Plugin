package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据文档库（SQLite）。
 *
 * <h2>模型：命名空间 + 键 → 一份 JSON 文档</h2>
 * <p>旧实现把每个玩家的数据写成
 * {@code addon_configs/糖然科技/<命名空间>/<玩家名>.json}；这里保留同样的语义，
 * 只是把「文件」换成「表里的一行」：</p>
 * <pre>
 * player_doc(ns TEXT, key TEXT, doc TEXT, updated_at INTEGER, PRIMARY KEY(ns, key))
 * </pre>
 * <p>业务代码不需要知道后端换了 —— {@link AddonPaths#readJson}/{@link AddonPaths#writeJson}
 * 对玩家数据目录下的 JSON 路径会转到这里，其余（{@code addons/} 里的附属资源、{@code .txt} 标记文件）
 * 仍是真实文件。</p>
 *
 * <h2>旧档迁移</h2>
 * <p>首次启动（{@code app_meta} 里没有 {@code legacy_json_import} 标记）会把
 * {@code addon_configs/糖然科技/&#42;&#42;} 下的所有 JSON 一次性导入，已存在的键不覆盖；
 * 单条读取时若库里没有而文件在，也会顺手导入（{@link AddonPaths#readJson} 里做）。
 * 也就是说：<b>玩家数据只可能从文件迁到库里，不会反过来丢数据</b>。</p>
 *
 * <h2>降级</h2>
 * <p>{@link Sqlite} 打不开（驱动缺失 / 冷门平台原生库加载失败 / 目录不可写）时，
 * {@link #available()} 恒 false，调用方全部回落文件后端，功能不受影响。</p>
 */
public final class Store {

    private Store() {}

    /** 玩家数据文档表。 */
    private static final String TABLE = "player_doc";

    /** 键值元数据表（迁移标记、库结构版本）。 */
    private static final String META = "app_meta";

    /** 旧文件导入完成的标记。 */
    private static final String META_LEGACY = "legacy_json_import";

    private static boolean schemaReady;

    // ==================================================================
    // 写后缓存 + 读缓存：save() 先进内存，每 5 分钟统一落库；读永远先走内存
    // ==================================================================

    /**
     * 待落库的文档（组合键 {@code ns + '\u0000' + key} → 序列化后的 JSON）。
     *
     * <p>高频写入（灵圃每秒刷新、倒计时推进等）不再逐条打 SQLite，而是先攒在这里；
     * 每 5 分钟由 {@link #flush()} 一次性写库并在后台打印一条提示。内存里的最新值
     * 对本进程的读取永远可见（{@link #load}/{@link #exists} 都先查这里），
     * 所以业务逻辑感知不到延迟。</p>
     *
     * <p><b>代价</b>：服务器进程被强杀 / 崩溃时最多丢最近 5 分钟的进度；
     * 正常停服走 {@link #shutdown()} 会先落库再关。组合键用 {@code '\u0000'} 分隔
     * —— 命名空间与键都是玩家数据里的目录名 / 文件名，不可能含 NUL。</p>
     */
    private static final ConcurrentHashMap<String, String> PENDING = new ConcurrentHashMap<>();

    /**
     * 读缓存：组合键 → 库里的原始 JSON 文本；{@link #ABSENT} 表示「确认库里没有」的负缓存。
     *
     * <p>写入路径（{@link #save}/{@link #delete}）与落库（{@link #flush}）都会同步维护它，
     * 所以本进程内它永远是库内容的超集视图。<b>缓存字符串而不是解析后的 Map</b>：
     * {@code load()} 每次返回新解析的副本，调用方（如异火的 {@code setSelected}）
     * 就地修改返回值不会污染缓存 —— 这是有意为之的防脏设计。
     * 解析一份几 KB 的 JSON 约 5µs，远低于一次 {@code prepareStatement}（毫秒级）。</p>
     *
     * <p>规模：每文档几 KB × 玩家数 × 命名空间数，测试服全库才 40KB，不设上限；
     * {@link #shutdown()} 时清空。前提是本插件<b>独占</b> {@code tangran.db}
     * —— 若有外部工具直接写库，读到的会是旧值（目前没有这样的工具；/tr import 也走本类）。</p>
     */
    private static final ConcurrentHashMap<String, String> DOC_CACHE = new ConcurrentHashMap<>();

    /** 「库里确认没有这份文档」的负缓存标记（哨兵值，不会与合法 JSON 冲突）。 */
    private static final String ABSENT = "\u0000ABSENT";

    /** {@link #load}/{@link #exists} 回库探测的内部哨兵：「正常查询但无此行」。 */
    private static final String ROW_MISSING = "\u0000MISSING";

    /** 回库探测的内部哨兵：查询本身异常（fallback）。此时<b>不写缓存</b>，免得把瞬时故障固化成「没有」。 */
    private static final String QUERY_ERROR = "\u0000ERROR";


    /** 落库周期：5 分钟 = 6000 刻。 */
    private static final long FLUSH_PERIOD_TICKS = 6000L;

    /** 防止定时任务与停服同时 flush 造成重复写（SQLite 本身有锁，这里只是省一遍空转）。 */
    private static final Object FLUSH_LOCK = new Object();

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 打开数据库并建表；随后尝试把旧 JSON 一次性导入。
     *
     * <p>由 {@code Setup#loadAll()} 在所有内容加载<b>之前</b>调用 —— 加载期有些脚本会读玩家数据。</p>
     */
    public static void init() {
        try {
            File db = databaseFile();
            if (!Sqlite.open(db)) return;
            if (!ensureSchema()) return;
            schemaReady = true;
            TT.log("[存储] SQLite 就绪: " + db.getAbsolutePath());
            autoImportLegacyOnce();
            startFlushTask();
            startKeyMigration();
        } catch (Throwable t) {
            TT.log("[存储] 初始化失败，回落文件存储: " + t);
        }
    }

    /**
     * 把「玩家名当键」的旧文档迁到「UUID 当键」（异步）。
     *
     * <p>2.1.0 早期用玩家名当数据键，玩家改名会丢档；这里把还能解析出 UUID 的旧键
     * 平移过去。规则：<b>只在 uuid 键不存在时复制</b>，复制成功才删旧键 —— 两边都有
     * 时保留 uuid 键（它是新数据），旧名键原样留下（下次启动还会尝试，不会重复写）。</p>
     */
    private static void startKeyMigration() {
        try {
            if (TT.plugin == null) return;
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(TT.plugin, () -> {
                try {
                    int moved = migrateNameKeysToUuid();
                    if (moved > 0) {
                        TT.log("[存储] 旧「玩家名键」文档已迁移为 UUID 键：" + moved + " 份");
                    }
                } catch (Throwable t) {
                    TT.log("[存储] 玩家名键 → UUID 键迁移失败（不影响运行，可下次启动重试）: " + t);
                }
            });
        } catch (Throwable ignored) {
            // 调度器不可用（极端时序）→ 跳过，下次启动再迁
        }
    }

    /** 执行迁移，返回迁移的文档数。公开给 /tr（想手动触发时）。 */
    public static int migrateNameKeysToUuid() {
        if (!available()) return 0;
        int moved = 0;
        for (Map.Entry<String, Integer> nsEntry : namespaces().entrySet()) {
            String ns = nsEntry.getKey();
            for (String key : keys(ns)) {
                if (key == null || key.isEmpty()) continue;
                if (key.matches("[0-9a-fA-F-]{36}")) continue;   // 已经是 uuid 键
                String uuidKey = PlayerKeys.key(key);
                if (uuidKey == null || uuidKey.equals(key)) continue;   // 解析不出，保留原名键
                if (exists(ns, uuidKey)) continue;   // uuid 键已有数据（新数据优先），旧键留着
                Map<String, Object> doc = load(ns, key);
                if (doc == null) continue;
                if (save(ns, uuidKey, doc)) {
                    delete(ns, key);
                    moved++;
                }
            }
        }
        flush();
        return moved;
    }

    /**
     * 启动「每 5 分钟落库一次」的异步定时任务，每次落库后在后台打印提示。
     *
     * <p>异步跑：SQLite 有全局锁，主线程读档只会在 flush 瞬间短暂等待。</p>
     */
    private static void startFlushTask() {
        try {
            if (TT.plugin == null) return;
            org.bukkit.Bukkit.getScheduler().runTaskTimerAsynchronously(TT.plugin, () -> {
                try {
                    int n = flush();
                    TT.log("[存储] 定时保存完成：写入 " + n + " 份文档"
                            + (n == 0 ? "（无变更）" : ""));
                } catch (Throwable t) {
                    TT.log("[存储] 定时保存失败（文档仍在缓存中，下个周期重试）: " + t);
                }
            }, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
        } catch (Throwable t) {
            TT.log("[存储] 定时保存任务启动失败（改为每次写都直接落库）: " + t);
        }
    }

    /**
     * 把缓存里的待写文档一次性落库。
     *
     * @return 实际写入的文档数
     */
    public static int flush() {
        if (!available() || PENDING.isEmpty()) return 0;
        synchronized (FLUSH_LOCK) {
            if (PENDING.isEmpty()) return 0;
            int n = 0;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, String> e : PENDING.entrySet()) {
                int sep = e.getKey().indexOf('\u0000');
                if (sep <= 0) {
                    PENDING.remove(e.getKey());
                    continue;
                }
                String ns = e.getKey().substring(0, sep);
                String key = e.getKey().substring(sep + 1);
                int r = Sqlite.exec("INSERT INTO " + TABLE + " (ns, key, doc, updated_at) "
                                + "VALUES (?,?,?,?) ON CONFLICT(ns, key) DO UPDATE "
                                + "SET doc=excluded.doc, updated_at=excluded.updated_at",
                        ns, key, e.getValue(), now);
                if (r >= 0) {
                    // 只在文档仍是这一版时移除：flush 期间又写了一笔的话，留给下一轮
                    PENDING.remove(e.getKey(), e.getValue());
                    n++;
                }
            }
            return n;
        }
    }

    /** 卸载时先落库再关库。 */
    public static void shutdown() {
        try {
            flush();
        } catch (Throwable ignored) {
            // 关闭阶段尽力而为
        }
        DOC_CACHE.clear();
        schemaReady = false;
        Sqlite.close();
    }

    /** 库是否可用（false → 调用方必须走文件后端）。 */
    public static boolean available() {
        return schemaReady && Sqlite.available();
    }

    /** 数据库文件：{@code plugins/TangranTech/data/tangran.db}。 */
    public static File databaseFile() {
        return new File(AddonPaths.dataFolder(), "data/tangran.db");
    }

    private static boolean ensureSchema() {
        Boolean ok = Sqlite.within(c -> {
            try (var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "ns TEXT NOT NULL,"
                        + "key TEXT NOT NULL,"
                        + "doc TEXT NOT NULL,"
                        + "updated_at INTEGER NOT NULL,"
                        + "PRIMARY KEY (ns, key))");
                st.execute("CREATE INDEX IF NOT EXISTS idx_" + TABLE + "_ns ON " + TABLE + "(ns)");
                st.execute("CREATE TABLE IF NOT EXISTS " + META + " ("
                        + "k TEXT PRIMARY KEY, v TEXT)");
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
        return Boolean.TRUE.equals(ok);
    }

    // ==================================================================
    // 文档读写
    // ==================================================================

    /** 读一份文档；不存在或解析失败返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String ns, String key) {
        if (!available() || ns == null || key == null) return null;
        String pk = pendingKey(ns, key);
        // ① 先查写后缓存：save() 之后、flush() 之前，读取必须看到最新值
        String text = PENDING.get(pk);
        // ② 再查读缓存（含负缓存）：命中就完全不碰 SQLite
        if (text == null) {
            String cached = DOC_CACHE.get(pk);
            if (cached != null) {
                text = ABSENT.equals(cached) ? null : cached;
            } else {
                // ③ 都没有才回库。三种结果要区分开：
                //    正常无行 → 负缓存 ABSENT；查到 → 缓存文本；
                //    查询异常（fallback）→ 什么都不缓存，否则一次瞬时故障会把「有档」永久固化成「没档」
                String fromDb = Sqlite.select("SELECT doc FROM " + TABLE + " WHERE ns=? AND key=?",
                        rs -> rs.next() ? rs.getString(1) : ROW_MISSING, QUERY_ERROR, ns, key);
                if (QUERY_ERROR.equals(fromDb)) return null;
                DOC_CACHE.put(pk, fromDb == null || fromDb.isBlank() || ROW_MISSING.equals(fromDb)
                        ? ABSENT : fromDb);
                if (ROW_MISSING.equals(fromDb)) {
                    text = null;
                } else {
                    text = fromDb;
                }
            }
        }
        if (text == null || text.isBlank()) return null;
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            // 旧档里极少见（顶层数组）→ 包一层，保证读取方拿到的仍是对象
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("value", parsed);
            return wrapped;
        } catch (Throwable t) {
            TT.log("[存储] 文档解析失败 " + ns + "/" + key + ": " + t);
            return null;
        }
    }

    /**
     * 写一份文档。
     *
     * <p><b>写后缓存语义</b>：只进内存（{@link #PENDING}），由每 5 分钟的定时任务
     * {@link #flush()} 统一落库；本进程的读取即时可见（{@link #load} 先查缓存）。
     * 返回 true 表示「已安全接下这份文档」，不代表已写盘。</p>
     */
    public static boolean save(String ns, String key, Object doc) {
        if (!available() || ns == null || key == null) return false;
        String text;
        try {
            text = Json.writePretty(doc == null ? new LinkedHashMap<>() : doc);
        } catch (Throwable t) {
            TT.log("[存储] 文档序列化失败 " + ns + "/" + key + ": " + t);
            return false;
        }
        PENDING.put(pendingKey(ns, key), text);
        DOC_CACHE.put(pendingKey(ns, key), text);   // 读缓存同步最新值，flush 落库后依然命中
        return true;
    }

    /** 删一份文档（缓存里的待写版本一并作废，读缓存记为「确认没有」）。 */
    public static boolean delete(String ns, String key) {
        if (!available() || ns == null || key == null) return false;
        String pk = pendingKey(ns, key);
        PENDING.remove(pk);
        DOC_CACHE.put(pk, ABSENT);
        return Sqlite.exec("DELETE FROM " + TABLE + " WHERE ns=? AND key=?", ns, key) >= 0;
    }

    /** 文档是否存在（含缓存中尚未落库的）。 */
    public static boolean exists(String ns, String key) {
        if (!available() || ns == null || key == null) return false;
        String pk = pendingKey(ns, key);
        if (PENDING.containsKey(pk)) return true;
        String cached = DOC_CACHE.get(pk);
        if (cached != null) return !ABSENT.equals(cached);
        Integer one = Sqlite.select("SELECT 1 FROM " + TABLE + " WHERE ns=? AND key=? LIMIT 1",
                rs -> rs.next() ? 1 : 0, -1, ns, key);
        // 异常（fallback -1）不写缓存；只有「确认不存在」才写 —— DOC_CACHE 的正向值必须是
        // load/save 放进去的完整文本，塞个空串会让 load 把它当空白文档返回 null
        if (one == null || one != 1) {
            if (one == null || one < 0) return false;   // 查询异常：不缓存
            DOC_CACHE.put(pk, ABSENT);
        }
        return one != null && one == 1;
    }

    /** 某命名空间下的全部键（字典序）。先落库，保证统计看到的是全量。 */
    public static List<String> keys(String ns) {
        flush();
        List<String> out = Sqlite.select("SELECT key FROM " + TABLE + " WHERE ns=? ORDER BY key",
                rs -> {
                    List<String> list = new ArrayList<>();
                    while (rs.next()) list.add(rs.getString(1));
                    return list;
                }, new ArrayList<>(), ns);
        return out == null ? new ArrayList<>() : out;
    }

    /** 某命名空间的文档数。 */
    public static int count(String ns) {
        flush();
        Integer n = Sqlite.select("SELECT COUNT(*) FROM " + TABLE + " WHERE ns=?",
                rs -> rs.next() ? rs.getInt(1) : 0, 0, ns);
        return n == null ? 0 : n;
    }

    /** 某份文档的最后写入时间（毫秒）；不存在返回 0。 */
    public static long updatedAt(String ns, String key) {
        Long v = Sqlite.select("SELECT updated_at FROM " + TABLE + " WHERE ns=? AND key=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, 0L, ns, key);
        return v == null ? 0L : v;
    }

    /** 全部命名空间（按文档数降序）。 */
    public static Map<String, Integer> namespaces() {
        flush();
        Map<String, Integer> out = Sqlite.select(
                "SELECT ns, COUNT(*) FROM " + TABLE + " GROUP BY ns ORDER BY COUNT(*) DESC",
                rs -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
                    return m;
                }, new LinkedHashMap<>());
        return out == null ? new LinkedHashMap<>() : out;
    }

    // ==================================================================
    // 元数据
    // ==================================================================

    /** 读一项元数据；不存在返回 null。 */
    public static String meta(String k) {
        if (!available()) return null;
        return Sqlite.select("SELECT v FROM " + META + " WHERE k=?", rs -> rs.next() ? rs.getString(1) : null, null, k);
    }

    /** 写一项元数据。 */
    public static void setMeta(String k, String v) {
        if (!available()) return;
        Sqlite.exec("INSERT INTO " + META + " (k, v) VALUES (?,?) "
                + "ON CONFLICT(k) DO UPDATE SET v=excluded.v", k, v);
    }

    /** 按前缀取一批元数据（k → v，含前缀全名）。 */
    public static Map<String, String> metaByPrefix(String prefix) {
        if (!available() || prefix == null) return new LinkedHashMap<>();
        flush();
        Map<String, String> out = Sqlite.select("SELECT k, v FROM " + META + " WHERE k LIKE ?",
                rs -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    while (rs.next()) m.put(rs.getString(1), rs.getString(2));
                    return m;
                }, new LinkedHashMap<>(), prefix + "%");
        return out == null ? new LinkedHashMap<>() : out;
    }

    // ==================================================================
    // 旧 JSON 导入
    // ==================================================================

    /**
     * 导入结果。
     *
     * @param dryRun      是不是预览（true 时什么都没写）
     * @param perNs       命名空间 → <b>扫到的文件数</b>（含跳过的；没用 --only 时这就是
     *                    源目录里该命名空间的全部玩家档数）
     * @param created     新建的文档数
     * @param overwritten 覆盖的文档数
     * @param skipped     库里已有、本次跳过的文档数
     * @param failed      解析/写入失败的文档数
     * @param samples     每个命名空间挑 1 个键做样例（最多 12 条）
     */
    public record ImportReport(boolean dryRun, Map<String, Integer> perNs,
                               int created, int overwritten, int skipped, int failed,
                               List<String> samples) {

        public int total() {
            return created + overwritten + skipped + failed;
        }

        /** 面向指令输出的一行摘要。 */
        public String summary() {
            return (dryRun ? "预览" : "导入完成") + "：共 " + total() + " 份（新建 " + created
                    + " / 覆盖 " + overwritten + " / 跳过·库内已有 " + skipped
                    + " / 失败 " + failed + "）";
        }
    }

    /** 首次启动自动导入（只填空缺，不覆盖已有）。 */
    private static void autoImportLegacyOnce() {
        try {
            if (meta(META_LEGACY) != null) return;
            File root = AddonPaths.configRoot();
            if (root == null || !root.isDirectory()) {
                setMeta(META_LEGACY, "skip:no-legacy-dir");
                return;
            }
            ImportReport rep = importLegacy(root, null, false, false);
            if (rep.total() == 0) {
                setMeta(META_LEGACY, "skip:no-file");
                return;
            }
            // 导入的文档此刻还在写后缓存里：先落库再写标记，
            // 否则启动后立刻崩溃会导致「标记已打、数据没进库」且不再重导。
            flush();
            setMeta(META_LEGACY, "done:" + rep.created() + "/" + rep.skipped() + "/" + rep.failed());
            TT.log("[存储] 旧 JSON 导入 " + rep.summary());
            for (Map.Entry<String, Integer> e : rep.perNs().entrySet()) {
                TT.log("[存储]   " + e.getKey() + " ← " + e.getValue() + " 份");
            }
            if (rep.failed() > 0) {
                TT.log("[存储] 有 " + rep.failed() + " 份文件解析失败，仍按文件后端保留（未删除）");
            }
        } catch (Throwable t) {
            TT.log("[存储] 旧 JSON 自动导入失败（不影响运行，可稍后用 /tr import 重跑）: " + t);
        }
    }

    /**
     * 从旧目录导入玩家数据。
     *
     * @param root      旧数据根目录（RSC 的 {@code addon_configs/糖然科技}，或本插件同名目录）
     * @param onlyNs    只导这些命名空间；null / 空 = 全部
     * @param overwrite 库里已有同键时是否覆盖
     * @param dryRun    true = 只统计不写库
     */
    public static ImportReport importLegacy(File root, Collection<String> onlyNs,
                                           boolean overwrite, boolean dryRun) {
        Map<String, Integer> perNs = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int created = 0, overwritten = 0, skipped = 0, failed = 0;
        if (root == null || !root.isDirectory()) {
            return new ImportReport(dryRun, perNs, 0, 0, 0, 0, samples);
        }
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return new ImportReport(dryRun, perNs, 0, 0, 0, 0, samples);
        for (File dir : dirs) {
            String ns = dir.getName();
            if (onlyNs != null && !onlyNs.isEmpty() && !onlyNs.contains(ns)) continue;
            File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files == null || files.length == 0) continue;
            for (File f : files) {
                String rawKey = f.getName().substring(0, f.getName().length() - 5);
                // RSC 旧档以「玩家名」为键 → 统一升级成 UUID 键（离线服本地派生，不联网）。
                // 特例：<玩家名>_编译器.json（终刻合成台编译器草稿）→ uuid(<玩家名>) + 后缀
                String key;
                if (rawKey.endsWith("_编译器")) {
                    key = PlayerKeys.key(rawKey.substring(0, rawKey.length() - "_编译器".length()))
                            + "_编译器";
                } else {
                    key = PlayerKeys.key(rawKey);
                }
                try {
                    boolean exists = available() && exists(ns, key);
                    if (exists && !overwrite) {
                        skipped++;
                        // 记「扫到了几个文件」而不是 0 —— 否则报告里 ×0 看起来像没扫到，
                        // 用户会误以为「数据没被导入」（2026-10-27 实测踩过）
                        perNs.merge(ns, 1, Integer::sum);
                        continue;
                    }
                    Map<String, Object> doc = readJsonFile(f);
                    if (doc == null) {
                        failed++;
                        continue;
                    }
                    if (!dryRun && available()) {
                        if (!save(ns, key, doc)) {
                            failed++;
                            continue;
                        }
                    }
                    if (exists) overwritten++;
                    else created++;
                    perNs.merge(ns, 1, Integer::sum);
                    if (samples.size() < 12 && rawKey.length() > 0) {
                        String line = ns + "/" + rawKey
                                + (key.equals(rawKey) ? "" : " → " + key);
                        if (!samples.contains(line)) samples.add(line);
                    }
                } catch (Throwable t) {
                    failed++;
                }
            }
        }
        return new ImportReport(dryRun, perNs, created, overwritten, skipped, failed, samples);
    }

    /** 读一个真实 JSON 文件（导入用；不经过 {@link AddonPaths} 以免被拦回库里）。 */
    public static Map<String, Object> readJsonFile(File file) {
        try {
            if (file == null || !file.isFile()) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> map = Json.parseObject(text);
            return (map == null || map.isEmpty()) ? null : map;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写后缓存的组合键。 */
    private static String pendingKey(String ns, String key) {
        return ns + "\u0000" + key;
    }
}
