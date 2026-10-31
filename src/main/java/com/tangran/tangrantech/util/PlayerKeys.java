package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家数据键：一律用 {@code 玩家 UUID 字符串}，不再用玩家名。
 *
 * <h2>为什么</h2>
 * <p>旧实现（RSC 与本插件 2.1.0 早期）用 {@code 玩家名.json} 当存档键 —— 玩家改名后
 * 旧档变成孤儿、新档从零开始，等于改名丢档。改成 UUID 后名字怎么改都认得出是同一个人。</p>
 *
 * <h2>名字 → UUID 的解析链</h2>
 * <ol>
 *   <li>内存缓存；</li>
 *   <li>库内索引（{@code app_meta} 表的 {@code player_index:&lt;小写名&gt;} 键，玩家进服时写入）；</li>
 *   <li>{@link Bukkit#getOfflinePlayer(String)}：离线服是纯本地计算（名字确定性派生），
 *       在线服会查 Mojang —— <b>主线程只允许走缓存/索引或离线派生</b>，完整查询只在
 *       异步上下文（导入、启动迁移）里做，解析失败原样返回名字并打日志。</li>
 * </ol>
 *
 * <p>反向查询（uuid → 名字，纯给管理指令显示用）走 {@link #nameOf(UUID)}。</p>
 */
public final class PlayerKeys {

    private PlayerKeys() {}

    /** UUID 标准形（8-4-4-4-12 十六进制）。 */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 名字（小写）→ uuid 的内存缓存。 */
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /** 库内索引键前缀（app_meta 表）。 */
    private static final String IDX_PREFIX = "player_index:";

    // ------------------------------------------------------------------
    // 正向：得到数据键
    // ------------------------------------------------------------------

    /** 在线/离线玩家对象 → uuid 键（无解析成本，优先用这个重载）。 */
    public static String key(Player player) {
        if (player == null) return null;
        return player.getUniqueId().toString();
    }

    /**
     * 玩家名（或已经是 uuid 的字符串）→ uuid 键。
     *
     * <p>解析不出 uuid 时<b>原样返回输入</b>（旧名键数据不会因此丢，只是没升级成 uuid 键）。</p>
     */
    public static String key(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return nameOrUuid;
        // 长度快判：UUID 恒 36 字符，玩家名不可能 —— 高频路径上让正则彻底短路
        if (nameOrUuid.length() == 36 && UUID_SHAPE.matcher(nameOrUuid).matches()) {
            return nameOrUuid.toLowerCase(Locale.ROOT);
        }
        String lower = nameOrUuid.toLowerCase(Locale.ROOT);
        String hit = CACHE.get(lower);
        if (hit != null) return hit;

        String idx = Store.meta(IDX_PREFIX + lower);
        if (idx != null && !idx.isBlank()) {
            CACHE.put(lower, idx);
            return idx;
        }

        // 主线程不联网（在线服 getOfflinePlayer(String) 可能走 HTTP）；离线服是本地派生，安全
        boolean onlineMode;
        boolean primaryThread;
        try {
            onlineMode = Bukkit.getOnlineMode();
            primaryThread = Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            onlineMode = false;      // 服务器还没起（工具链 / 测试环境）→ 按离线口径
            primaryThread = true;
        }
        if (primaryThread && onlineMode) {
            OfflinePlayer cached = null;
            try {
                cached = Bukkit.getOfflinePlayerIfCached(nameOrUuid);
            } catch (Throwable ignored) {
                // 老版本 API 没有 → 按 null 处理
            }
            if (cached != null) return remember(nameOrUuid, cached.getUniqueId());
            return nameOrUuid;   // 没缓存过：留给异步迁移/导入去解析
        }
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(nameOrUuid);
            if (op != null) return remember(nameOrUuid, op.getUniqueId());
        } catch (Throwable t) {
            // 服务器未启动 / API 异常 → 离线服直接按 Bukkit 同款算法本地派生
            // （在线服不做本地派生，派生结果与 Mojang 对不上）
            if (!onlineMode) {
                UUID derived = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + nameOrUuid).getBytes(StandardCharsets.UTF_8));
                return remember(nameOrUuid, derived);
            }
            TT.log("[玩家键] 解析 " + nameOrUuid + " 的 UUID 失败（暂用名字当键）: " + t);
        }
        return nameOrUuid;
    }

    /** 记住 name→uuid 并落索引；返回 uuid 键。 */
    private static String remember(String name, UUID uuid) {
        if (name == null || uuid == null) return name;
        String u = uuid.toString();
        CACHE.put(name.toLowerCase(Locale.ROOT), u);
        Store.setMeta(IDX_PREFIX + name.toLowerCase(Locale.ROOT), u);
        return u;
    }

    // ------------------------------------------------------------------
    // 反向：uuid → 名字（仅显示用，解析不出返回 null）
    // ------------------------------------------------------------------

    /** uuid 键 → 最近一次知道的名字；查不到返回 null。 */
    public static String nameOf(String uuidKey) {
        if (uuidKey == null) return null;
        for (Map.Entry<String, String> e : index().entrySet()) {
            if (uuidKey.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** 全部索引（名字小写 → uuid）。 */
    public static Map<String, String> index() {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> raw = Store.metaByPrefix(IDX_PREFIX);
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                out.put(e.getKey().substring(IDX_PREFIX.length()), e.getValue());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 监听：进服即记索引（之后该玩家的所有解析都走缓存，零成本）
    // ------------------------------------------------------------------

    /** 注册进服监听（由主类 onEnable 调一次）。 */
    public static void register() {
        try {
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler(priority = EventPriority.MONITOR)
                public void onJoin(PlayerJoinEvent event) {
                    try {
                        Player p = event.getPlayer();
                        remember(p.getName(), p.getUniqueId());
                    } catch (Throwable ignored) {
                        // 索引失败不影响进服
                    }
                }
            }, TT.plugin);
        } catch (Throwable t) {
            TT.log("[玩家键] 注册进服索引监听失败: " + t);
        }
    }
}
