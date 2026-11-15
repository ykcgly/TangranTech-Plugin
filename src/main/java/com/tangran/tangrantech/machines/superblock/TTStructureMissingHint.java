package com.tangran.tangrantech.machines.superblock;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.machines.TTSuperMultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 超多方块「缺失方块」提示 —— 口木科技 {@code StructureMissingHint} 同款。
 *
 * <p>右键未成形的核心时，由 {@link SuperBlocks} 传入 {@link SuperStructure#collectMissing}
 * 的全量缺块清单；每个缺块生成一套展示实体：<b>红色发光</b>的 {@link ItemDisplay}
 * （scale 0.5）+ 上方 0.8 格的 {@link TextDisplay} 名称牌，并在聊天栏给出
 * <b>世界绝对坐标</b>与方块名（原版走 {@link Material#getBlockTranslationKey()} 交给客户端翻译）。
 * 玩家把正确方块放上去后，对应提示自动消失；全部补齐会给一条完成提示。</p>
 *
 * <p>与糖然原有「分层投影」是两套独立实体：分层投影由 {@code SuperBlocks} 按
 * {@code displayProjectiles / allowSwitchDisplayLayer} 管理，这里只负责红色缺块提示，
 * 两者互不干扰（PDC 命名空间不同）。</p>
 *
 * <p><b>实体一律 {@code setPersistent(false)}</b>：Display 默认会被写进世界存档，
 * 服务器重启后内存里的实例表已空、没人再管它们 —— 这正是「投影实体残留」的根因之一。
 * 关掉持久化后，重启即由服务端自然回收。</p>
 */
public final class TTStructureMissingHint {

    private TTStructureMissingHint() {}

    private static final NamespacedKey HINT_KEY =
            new NamespacedKey("tangrantech", "superblock_missing_hint");
    private static final int MAX_MESSAGE_LINES = 12;
    /**
     * 单次最多生成的缺块标记数。口木的结构只有几十格所以没设上限，糖然最大的
     * 浑天仪有 535 个结构格，全标出来就是 1000+ 个 Display 实体（每个缺块还要配
     * 一个名称牌）——右键一次就能把主线程和实体列表打满。这里只标「离核心最近」
     * 的一批，聊天栏仍如实报总数与前 12 处坐标。
     */
    private static final int MAX_HINTS = 48;
    private static final float BODY_SCALE = 0.5F;
    private static final double LABEL_HEIGHT = 0.8;
    private static final double SCAN_RADIUS = 24.0;
    /** 粘液方块的数据可能放置后隔几 tick 才写入，这里延迟再核对一次。 */
    private static final long SFID_RECHECK_DELAY = 3L;
    /** 同一玩家对同一核心的聊天播报节流，防止反复右键刷屏。 */
    private static final long NOTIFY_COOLDOWN_MS = 3000L;

    private static final Map<String, Map<String, Hint>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> INDEX = new ConcurrentHashMap<>();
    private static final Map<String, UUID> OWNERS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NOTIFY_AT = new ConcurrentHashMap<>();
    private static volatile boolean listenerRegistered;

    private record Hint(Material material, String sfId, List<Display> displays) {}

    /**
     * 展示缺块提示。会先清掉该核心上一次的提示，保证不叠加。
     *
     * @param core    核心位置（用于分组）
     * @param missing 缺块清单（世界绝对坐标，已按朝向旋转）
     */
    public static void showMissing(Player player, Location core, List<SuperStructure.MissingBlock> missing) {
        ensureListener();
        clear(core);
        if (player == null || core == null || missing == null || missing.isEmpty()) return;

        String ck = key(core);
        // 大结构只标最近的一批（按核心距离，同世界必定可比），防止实体爆炸
        List<SuperStructure.MissingBlock> marked = missing;
        if (missing.size() > MAX_HINTS) {
            marked = new ArrayList<>(missing);
            marked.sort(java.util.Comparator.comparingDouble(
                    m -> m.location().distanceSquared(core)));
            marked = marked.subList(0, MAX_HINTS);
        }

        Map<String, Hint> hints = new ConcurrentHashMap<>();
        for (SuperStructure.MissingBlock m : marked) {
            Location loc = m.location();
            if (loc == null || loc.getWorld() == null) continue;
            String bk = key(loc);
            Hint hint = spawnHint(ck, loc, m.material(), m.sfId());
            if (hint != null) {
                hints.put(bk, hint);
                INDEX.computeIfAbsent(bk, k -> ConcurrentHashMap.newKeySet()).add(ck);
            }
        }
        if (!hints.isEmpty()) {
            ACTIVE.put(ck, hints);
            OWNERS.put(ck, player.getUniqueId());
            notifyMissing(player, ck, missing, marked.size());
        }
    }

    /** 清除某个核心的全部缺块提示（重新检测、结构成形、核心被拆、切换朝向时都要调）。 */
    public static void clear(Location core) {
        if (core == null) return;
        String ck = key(core);
        Map<String, Hint> hints = ACTIVE.remove(ck);
        if (hints != null) {
            for (Map.Entry<String, Hint> e : hints.entrySet()) {
                removeDisplays(e.getValue());
                unindex(e.getKey(), ck);
            }
        }
        OWNERS.remove(ck);
        clearNotify(ck);
        removeTaggedEntities(core, ck);
    }

    /** 插件停用：清掉全部提示实体并清空索引（主线程执行）。 */
    public static void shutdown() {
        for (World w : Bukkit.getWorlds()) {
            try {
                for (Entity e : w.getEntities()) {
                    if (e instanceof Display d && hasTag(d)) removeQuietly(d);
                }
            } catch (Throwable ignored) {
                // 卸载阶段尽力而为
            }
        }
        ACTIVE.clear();
        INDEX.clear();
        OWNERS.clear();
        NOTIFY_AT.clear();
    }

    // ------------------------------------------------------------------
    // 实体生成
    // ------------------------------------------------------------------

    private static Hint spawnHint(String coreKey, Location loc, Material material, String sfId) {
        World w = loc.getWorld();
        if (w == null) return null;
        Location center = new Location(w, loc.getBlockX() + 0.5, loc.getBlockY() + 0.5, loc.getBlockZ() + 0.5);
        Component name = nameComponent(material, sfId);
        List<Display> displays = new ArrayList<>(2);
        try {
            ItemDisplay body = (ItemDisplay) w.spawn(center, ItemDisplay.class, d -> {
                d.setItemStack(displayStack(material, sfId));
                d.setGlowing(true);
                d.setGlowColorOverride(Color.RED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(100.0F);
                d.setGravity(false);
                d.setInvulnerable(true);
                d.setPersistent(false);
                d.setTransformation(scale(BODY_SCALE));
            });
            tag(body, coreKey);
            displays.add(body);

            TextDisplay label = (TextDisplay) w.spawn(center.clone().add(0, LABEL_HEIGHT, 0), TextDisplay.class, d -> {
                d.text(name);
                d.setSeeThrough(true);
                d.setDefaultBackground(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setViewRange(50.0F);
                d.setGravity(false);
                d.setInvulnerable(true);
                d.setPersistent(false);
                d.setTransformation(scale(1.0F));
            });
            tag(label, coreKey);
            displays.add(label);
            return new Hint(material, sfId, displays);
        } catch (Throwable t) {
            for (Display d : displays) removeQuietly(d);
            return null;
        }
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0),
                new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 0));
    }

    /** 名称牌：粘液物品用显示名，原版方块交给客户端翻译（中文客户端自然显示中文）。 */
    private static Component nameComponent(Material material, String sfId) {
        SlimefunItem sf = sfId == null ? null : resolveSlimefunItem(sfId);
        if (sf != null) {
            String raw = sf.getItemName().replace('§', '&');
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        }
        if (material != null) {
            return Component.translatable(material.getBlockTranslationKey());
        }
        return Component.text("未知方块");
    }

    /** 展示堆：粘液物品用它的展示堆；原版非物品材质（水/岩浆）退化成对应的桶。 */
    private static ItemStack displayStack(Material material, String sfId) {
        SlimefunItem sf = sfId == null ? null : resolveSlimefunItem(sfId);
        if (sf != null) return sf.getItem().clone();
        if (material == null) return new ItemStack(Material.BARRIER);
        if (material == Material.WATER) return new ItemStack(Material.WATER_BUCKET);
        if (material == Material.LAVA) return new ItemStack(Material.LAVA_BUCKET);
        return material.isItem() ? new ItemStack(material) : new ItemStack(Material.BARRIER);
    }

    private static SlimefunItem resolveSlimefunItem(String id) {
        SlimefunItem item = SlimefunItem.getById(id);
        if (item != null) return item;
        for (SlimefunItem other : Slimefun.getRegistry().getAllSlimefunItems()) {
            if (other != null && other.getId() != null && other.getId().equalsIgnoreCase(id)) return other;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 自动消失
    // ------------------------------------------------------------------

    private static void ensureListener() {
        if (listenerRegistered || TT.plugin == null) return;
        synchronized (TTStructureMissingHint.class) {
            if (listenerRegistered) return;
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
                public void onPlace(BlockPlaceEvent e) { handlePlace(e.getBlock()); }
            }, TT.plugin);
            listenerRegistered = true;
        }
    }

    private static void handlePlace(Block block) {
        if (block == null || INDEX.isEmpty()) return;
        String bk = key(block.getLocation());
        Set<String> cores = INDEX.get(bk);
        if (cores == null || cores.isEmpty()) return;
        for (String ck : new ArrayList<>(cores)) {
            Map<String, Hint> hints = ACTIVE.get(ck);
            if (hints == null) { cores.remove(ck); continue; }
            Hint h = hints.get(bk);
            if (h == null) { cores.remove(ck); continue; }
            if (matches(block, h)) {
                resolveHint(ck, bk);
            } else if (h.sfId() != null && block.getType() == h.material()) {
                // 材质对了但粘液数据还没落盘，几 tick 后再核对
                delayRecheck(ck, bk, block);
            }
        }
        if (cores.isEmpty()) INDEX.remove(bk);
    }

    private static void delayRecheck(String ck, String bk, Block block) {
        if (TT.plugin == null) return;
        Bukkit.getScheduler().runTaskLater(TT.plugin, () -> {
            Map<String, Hint> hints = ACTIVE.get(ck);
            if (hints == null) return;
            Hint h = hints.get(bk);
            if (h != null && matches(block.getWorld().getBlockAt(block.getX(), block.getY(), block.getZ()), h)) {
                resolveHint(ck, bk);
            }
        }, SFID_RECHECK_DELAY);
    }

    private static void resolveHint(String ck, String bk) {
        Map<String, Hint> hints = ACTIVE.get(ck);
        if (hints == null) return;
        Hint h = hints.remove(bk);
        if (h != null) removeDisplays(h);
        unindex(bk, ck);
        if (hints.isEmpty()) {
            ACTIVE.remove(ck);
            UUID owner = OWNERS.remove(ck);
            clearNotify(ck);
            if (owner != null) {
                Player p = Bukkit.getPlayer(owner);
                if (p != null) p.sendMessage("§a缺失方块已全部补齐，结构提示已清除。");
            }
        }
    }

    /** 复用糖然的结构比对规则（含液体 / waterlogged 特判），避免与成形判定口径不一致。 */
    private static boolean matches(Block block, Hint h) {
        return TTSuperMultiBlockMachine.matchesBlock(block,
                new TTSuperMultiBlockMachine.Expect(h.material(), h.sfId()));
    }

    // ------------------------------------------------------------------
    // 聊天播报
    // ------------------------------------------------------------------

    private static void notifyMissing(Player p, String ck,
                                      List<SuperStructure.MissingBlock> missing, int marked) {
        String nk = ck + ":" + p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = NOTIFY_AT.get(nk);
        if (last != null && now - last < NOTIFY_COOLDOWN_MS) return;   // 实体照常刷新，只节流聊天
        NOTIFY_AT.put(nk, now);

        p.sendMessage("§c结构检测未完成：" + missing.size()
                + " 处缺失，已在对应位置生成红色投影，放对方块后会自动消失。");
        if (marked < missing.size()) {
            p.sendMessage("§7  结构较大，已只标出离核心最近的 " + marked
                    + " 处；补齐后再次右键核心即可查看剩余部分。");
        }
        int lines = Math.min(missing.size(), MAX_MESSAGE_LINES);
        for (int i = 0; i < lines; i++) {
            SuperStructure.MissingBlock m = missing.get(i);
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("§c● §7需放置 §f")
                    .append(nameComponent(m.material(), m.sfId()))
                    .append(LegacyComponentSerializer.legacyAmpersand()
                            .deserialize(" §7坐标 §f" + formatCoords(m.location()))));
        }
        if (missing.size() > lines) {
            p.sendMessage("§7  … 其余 " + (missing.size() - lines) + " 处已用红色投影标出");
        }
    }

    private static String formatCoords(Location loc) {
        String xyz = loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
        return loc.getWorld() == null ? xyz : loc.getWorld().getName() + " §8/§f " + xyz;
    }

    private static void clearNotify(String ck) {
        for (String k : new ArrayList<>(NOTIFY_AT.keySet())) {
            if (k.startsWith(ck + ":")) NOTIFY_AT.remove(k);
        }
    }

    // ------------------------------------------------------------------
    // 清理与索引
    // ------------------------------------------------------------------

    private static void removeDisplays(Hint h) {
        if (h == null) return;
        for (Display d : h.displays()) removeQuietly(d);
    }

    private static void removeQuietly(Display d) {
        try {
            if (d == null || !d.isValid()) return;
            // 发光队伍条目不会随实体移除自动消失，顺手摘掉
            org.bukkit.scoreboard.Team team = Bukkit.getScoreboardManager()
                    .getMainScoreboard().getTeam("tt_superblock_wrong");
            if (team != null) team.removeEntry(d.getUniqueId().toString());
            d.remove();
        } catch (Throwable ignored) {
            // 卸载阶段失败无所谓
        }
    }

    /** 兜底：扫核心附近，把带本命名空间标记却已不在索引里的孤儿实体一并清掉。 */
    private static void removeTaggedEntities(Location core, String ck) {
        World w = core.getWorld();
        if (w == null) return;
        try {
            Location center = core.clone().add(0.5, 0.5, 0.5);
            for (Entity e : w.getNearbyEntities(center, SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                if (!(e instanceof Display d) || !hasTag(d)) continue;
                String owner = d.getPersistentDataContainer().get(HINT_KEY, PersistentDataType.STRING);
                if (ck.equals(owner)) removeQuietly(d);
            }
        } catch (Throwable ignored) {
            // 世界卸载等极端情况忽略
        }
    }

    private static boolean hasTag(Display d) {
        return d.getPersistentDataContainer().has(HINT_KEY, PersistentDataType.STRING);
    }

    private static void tag(Display d, String ck) {
        d.getPersistentDataContainer().set(HINT_KEY, PersistentDataType.STRING, ck);
    }

    private static void unindex(String bk, String ck) {
        Set<String> cores = INDEX.get(bk);
        if (cores != null) {
            cores.remove(ck);
            if (cores.isEmpty()) INDEX.remove(bk);
        }
    }

    private static String key(Location loc) {
        World w = loc.getWorld();
        return (w == null ? "?" : w.getName()) + "," + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
