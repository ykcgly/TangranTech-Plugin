package com.tangran.tangrantech.native_scripts.daofa;

import com.tangran.tangrantech.util.PlayerKeys;
import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.native_scripts.NativeLifecycleScript;
import com.tangran.tangrantech.util.AddonPaths;
import com.tangran.tangrantech.util.Colors;
import com.tangran.tangrantech.util.Json;
import com.tangran.tangrantech.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import com.tangran.tangrantech.native_scripts.daofa.RealmScript;
import com.tangran.tangrantech.native_scripts.daofa.boss.BossChallengeState;

/**
 * 「道法/异火选择」的原生实现（对应 {@code scripts/道法/异火选择.js}）。
 * 九种异火的选择界面 + 发射特效 + 被动效果。
 *
 * <p><b>入口</b>：{@code groups.yml} 的按钮（{@code Tr_yhxz}）；
 * 以及手持 {@code TR_KH}（异火核心）时 <b>Shift+左键</b>打开选择界面。</p>
 *
 * <p><b>发射</b>：手持 {@code TR_KH} 长按右键，沿准星方向每 0.2 秒推进 1 格、最多 7 格，
 * 沿途命中生物时按所选异火结算。每次发射消耗 1 点糖之力（需先开辟境界）。</p>
 *
 * <p><b>五行三种被动</b>：</p>
 * <ul>
 *   <li>{@code TR_YH03} 沧溟玄水焱：选中期间常驻潮涌能量 X + 海豚恩惠 I，切换即移除；</li>
 *   <li>{@code TR_YH05} 厚土地心火：物理攻击对你只造成 1 点伤害、免疫魔法伤害、
 *       并免疫「直接 setHealth」类击杀（见下）；</li>
 *   <li>{@code TR_YH06} 曜日净世炎：命中时召唤 3×3 光灵箭雨。</li>
 * </ul>
 *
 * <p><b>厚土地心火的「setHealth 看门狗」</b>（原脚本注释里的完整背景）：有些外部武器的伤害
 * 完全不走 {@code EntityDamageEvent}，而是直接写 {@code setHealth}——例如魔法2 的星屑剑
 * （近战额外伤害 = 伤害×3.8 + 目标最大生命×8%，外加每秒 5% 最大生命的流血），
 * 以及逻辑工艺的激光枪（可通过 {@code AddUtils.damageGeneric} 直接归零）。
 * 事件层拦不住，所以这里用两道机制兜住：</p>
 * <ol>
 *   <li>MONITOR 监听记录「最近一次真正走伤害事件的时间」以及「本刻事件允许的伤害额度」
 *       （生物物理攻击最多放行 1 点，其它按事件最终伤害）；</li>
 *   <li>每刻比对血量：厚土玩家在「额度不够」的情况下掉血 → 判定为 setHealth 类伤害并回滚。
 *       <b>回滚后必须把基准血量也改成回滚后的值</b>，否则基准会一路采用「已被扣过」的血量，
 *       每次伤害都把基准往下拉一截，最终仍会被磨死（原脚本日志记录过
 *       1185→1080→1077→864→554→142 这种一路下滑）。</li>
 * </ol>
 * <p>死亡判定另加一道 150ms 的宽限窗口：最近 150ms 内没有任何伤害事件，就说明是
 * setHealth 类直接击杀（否则 {@code lastDamageCause} 会指向「上一次物理伤害」而漏判）。</p>
 *
 * <p>与原脚本的差异：</p>
 * <ul>
 *   <li><b>去掉自注册的 {@code PlayerInteractEvent}</b>。原脚本同时存在「右侧 {@code onUse}
 *       入口」与「自注册监听」两条通道，靠 300ms 菜单冷却才没双开。本插件统一为
 *       {@code onUse} 单一入口（含 Shift+左键分支），语义等价且不再依赖冷却兜底。</li>
 *   <li>原脚本里的 {@code startKhBeam}（旧版直线光束）<b>没有任何调用点，是死代码</b>，
 *       这里不再复刻。当前生效的是 {@code khParticleTask} + {@code khBeamDamageTask}
 *       + {@code khResidualHitTask} 三件套。</li>
 *   <li>挑战封印判定统一走 {@link BossChallengeState#isPhaseTwoLocked}，
 *       糖之力消费统一走 {@link RealmScript#consumeSugarPower}（不再各写一份读文件逻辑）。</li>
 * </ul>
 */
public final class YanhuoSelectionScript implements NativeLifecycleScript {

    /** 异火核心物品 id。 */
    private static final String CORE_ITEM_ID = "TR_KH";

    private static final String GUI_TITLE =
            Colors.c("{#fca5a5}&l异{#f87171}&l火{#ef4444}&l选{#dc2626}&l择{#b91c1c}&l");

    /** 厚土地心火的 id（"土"）。 */
    private static final String EARTH_ID = "TR_YH05";
    /** 沧溟玄水焱的 id（"水"）。 */
    private static final String WATER_ID = "TR_YH03";

    private static final int[] PRODUCT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };
    private static final int CLOSE_SLOT = 49;
    private static final int MENU_SIZE = 54;

    /** 菜单重复打开的节流（ms）。 */
    private static final long MENU_OPEN_THROTTLE_MS = 300L;
    /** 发射提示的重复周期（刻）。 */
    private static final long SHOOT_MSG_RESET_TICKS = 200L;

    /** 光束推进上限（格）与推进周期（刻）。 */
    private static final int BEAM_MAX_DIST = 7;
    private static final long PARTICLE_PERIOD = 4L;
    private static final long RESIDUAL_PERIOD = 3L;
    /** 松开右键后多久停止（ms）。 */
    private static final long BEAM_ACTIVE_WINDOW_MS = 250L;
    /** 无粒子且超时后停止残留任务（ms）。 */
    private static final long RESIDUAL_IDLE_MS = 1000L;
    /** 连续右键时自动补粒子的间隔（ms）。 */
    private static final long AUTO_SPAWN_INTERVAL_MS = 70L;

    /** 命中冷却（ms）：普通粒子 / 光束。 */
    private static final long HIT_COOLDOWN_MS = 150L;
    private static final long BEAM_HIT_COOLDOWN_MS = 50L;

    /** 看门狗判定「最近有伤害事件」的宽限窗口（ms）。 */
    private static final long WATCHDOG_GRACE_MS = 150L;
    /** 看门狗提示节流（ms）。 */
    private static final long WATCHDOG_MSG_INTERVAL_MS = 2000L;

    /** 一种异火：id / 名称 / 描述。 */
    private record Yanhuo(String id, String name, List<String> desc) {}

    private static final List<Yanhuo> YANHUO_LIST = List.of(
            new Yanhuo("TR_YH01", "玄金焚天炎",
                    List.of("§7额外造成 §e30 + 9% 最大生命值 §7虚空伤害")),
            new Yanhuo("TR_YH02", "青木长生焰",
                    List.of("§7额外造成 §e6% 最大生命值 §7虚空伤害",
                            "§7攻击时回复自身 §a10% 最大生命值")),
            new Yanhuo("TR_YH03", "沧溟玄水焱",
                    List.of("§7使目标 §b窒息 §7（清空氧气值）",
                            "§7【被动】§f选中期间持续获得 §b潮涌能量X §7与 §b海豚恩惠I")),
            new Yanhuo("TR_YH04", "赤霄焚灵炎", List.of("§7使目标 §c燃烧 §73秒")),
            new Yanhuo("TR_YH05", "厚土地心火",
                    List.of("§7使目标 §b窒息 §7并施加 §f缓慢II §75秒",
                            "§7【被动】§f任意生物物理攻击对你仅造成1点伤害",
                            "§7【被动】§f免疫魔法伤害与 setHealth(0) 直接击杀")),
            new Yanhuo("TR_YH06", "曜日净世炎",
                    List.of("§7使目标发光",
                            "§7召唤 §d3×3光灵箭雨§7，每箭造成 §e0.2% 最大生命值 §7虚空伤害")),
            new Yanhuo("TR_YH07", "冥渊噬魂火",
                    List.of("§7使目标 §8黑暗§7、失明并施加 §d反胃II §75秒")),
            new Yanhuo("TR_YH08", "九霄紫雷炎",
                    List.of("§7召唤 §e3道落雷§7（纯特效不引火）",
                            "§7每道落雷造成 §e3% 最大生命值 §7虚空伤害")),
            new Yanhuo("TR_YH09", "霜雪凝神焰",
                    List.of("§7接触期间持续施加 §b极度缓慢§7，脱离后5秒解除"))
    );

    /** 外圈装饰玻璃可选颜色。 */
    private static final String[] GLASS_COLORS = {
            "RED_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE",
            "LIGHT_BLUE_STAINED_GLASS_PANE", "BLUE_STAINED_GLASS_PANE", "PURPLE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE", "PINK_STAINED_GLASS_PANE",
    };

    /** 外圈装饰槽位（底部控制栏保留灰色不变）。 */
    private static final int[] OUTER_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44};

    private static final java.util.Random RANDOM = new java.util.Random();

    // ---------------- 界面状态 ----------------

    private static final Set<Inventory> ACTIVE = ConcurrentHashMap.newKeySet();

    // ---------------- 发射与命中状态 ----------------

    /** UUID → 已发射的光束粒子（原点 + 方向 + 已推进距离）。 */
    private static final Map<UUID, List<Beam>> PARTICLES = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> PARTICLE_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PARTICLE_SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> BEAM_DAMAGE_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BEAM_SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> RESIDUAL_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> RESIDUAL_SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_EVENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SPAWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SOUND_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MENU_OPEN_AT = new ConcurrentHashMap<>();
    private static final Set<UUID> SHOOT_MSG_SENT = ConcurrentHashMap.newKeySet();
    /** 目标 UUID → 上次命中的时间戳（秒伤节流）。 */
    private static final Map<UUID, Long> HIT_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BEAM_HIT_COOLDOWN = new ConcurrentHashMap<>();
    /** 我们生成的光灵箭，用于命中结算。 */
    private static final Set<UUID> LIGHT_ARROWS = ConcurrentHashMap.newKeySet();

    // ---------------- 厚土地心火状态 ----------------

    private static final Map<UUID, Long> WD_EVENT_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> WD_ALLOWED = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> WD_LAST_HP = new ConcurrentHashMap<>();
    private static final Map<UUID, String> WD_SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WD_SELECTED_AT = new ConcurrentHashMap<>();

    /**
     * 「已确认没有选中异火」的哨兵值。
     *
     * <p>{@link ConcurrentHashMap} 不允许 null —— 早期用 {@code remove()} 表示「没有」，
     * 会让下一刻的 {@code sel == null} 再次成立，负结果永远缓存不住：没选异火的玩家
     * 每刻都完整走一遍 SQLite + 两次 {@code File.exists}（spark 实测单人 8.2ms/刻、
     * 占主线程 16.4%）。负结果也进缓存后，这一整类调用归零。
     * {@code EARTH_ID.equals(NONE_SELECTED)} 恒为 false，非厚土玩家照旧跳过护血。</p>
     */
    private static final String NONE_SELECTED = "\u0000NONE";

    /**
     * watchdog 选中状态的兜底 TTL。
     *
     * <p>正常情况下选中状态只在 {@link #setSelected}（玩家自选 / 挑战清空）里变化，
     * 那里会调 {@link #invalidateSelected} 事件式失效；这个长 TTL 只是防漏的保险，
     * 把「没选异火」玩家的重复查询从每刻一次压到 30 秒一次。</p>
     */
    private static final long SELECTED_TTL_MS = 30_000L;

    private static final Map<UUID, Long> WD_MSG_AT = new ConcurrentHashMap<>();

    private static volatile BukkitTask watchdogTask;

    private record Beam(double ox, double oy, double oz,
                        double dx, double dy, double dz, int dist) {}

    @Override
    public Object invoke(String fn, Object... args) {
        try {
            switch (fn) {
                case "onUse" -> {
                    if (!(args.length > 0 && args[0] instanceof PlayerInteractEvent event)) {
                        return Boolean.FALSE;
                    }
                    handleUse(event);
                    return Boolean.FALSE;
                }
                case "onButtonGroupClick" -> {
                    if (args.length > 0 && args[0] instanceof Player player) {
                        openSelect(player);
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            TT.log("[异火选择] invoke(" + fn + ") 异常: " + t);
            return null;
        }
    }

    /** 右键 / Shift+左键的统一入口（原脚本的 onUse）。 */
    private static void handleUse(PlayerInteractEvent event) {
        // 控火道具是 fire_charge 材质：不取消原版交互，右键会「原版点火 + 我们的粒子/提示」双发，
        // 左键也会照常开始挖掘。ScriptItem.onRightClick 不看返回值，必须自己 setCancelled。
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
            // 事件不可取消时忽略
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        SlimefunItem sf = item == null ? null : SlimefunItem.getByItem(item);
        boolean isCore = sf != null && CORE_ITEM_ID.equalsIgnoreCase(sf.getId());

        if (isCore) {
            Action action = event.getAction();
            boolean left = (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK);
            if (left) {
                // Shift+左键：切换异火
                if (player.isSneaking()) {
                    SHOOT_MSG_SENT.remove(player.getUniqueId());
                    openSelect(player);
                }
                return;
            }

            updateCoreLore(player);
            String selected = selectedYanhuo(player.getName());
            if (selected == null) {
                player.sendMessage("§c您还没有选择异火！");
                return;
            }

            // 长按右键时只提示一次发射
            UUID uuid = player.getUniqueId();
            if (SHOOT_MSG_SENT.add(uuid)) {
                player.sendMessage("§a发射" + gradientName(selected));
                com.tangran.tangrantech.native_scripts.NativePluginHelper
                        .runLater(() -> SHOOT_MSG_SENT.remove(uuid), SHOOT_MSG_RESET_TICKS);
            }

            // 右键：异火粒子每 0.2 秒向指针方向蔓延 1 格，最多 7 格，指针变化时尾迹跟随拐弯
            LAST_EVENT.put(uuid, System.currentTimeMillis());
            addParticle(player, selected);
            return;
        }

        // 手里不是异火核心 → 直接开选择界面（原脚本的兜底分支）
        openSelect(player);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /** 异火存档目录（缓存 {@code File} 对象：原实现每刻都 {@code configDir → ensure → exists()}，
     *  白白多一次 stat 系统调用；目录在启动时已建好，运行期不会变）。 */
    private static volatile File YANHUO_DIR;

    private static File yanhuoDir() {
        File d = YANHUO_DIR;
        if (d == null) {
            synchronized (YanhuoSelectionScript.class) {
                d = YANHUO_DIR;
                if (d == null) YANHUO_DIR = d = AddonPaths.configDir(AddonPaths.DIR_YANHUO);
            }
        }
        return d;
    }

    private static File yanhuoFile(String playerName) {
        return new File(yanhuoDir(), PlayerKeys.key(playerName) + ".json");
    }

    /** 玩家已拥有的异火 id 列表。 */
    private static List<String> ownedYanhuo(String playerName) {
        Map<String, Object> data = AddonPaths.readJson(yanhuoFile(playerName));
        if (data == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(data, "owned")) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 玩家当前选中的异火；未选中返回 null。
     *
     * <p>糖然二阶段挑战期间一律视为未选中（含厚土免死等被动全部失效），
     * 挑战结束后自动恢复玩家原本的选择（<b>不改动存档文件</b>）。</p>
     */
    private static String selectedYanhuo(String playerName) {
        try {
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null && BossChallengeState.isPhaseTwoLocked(online)) return null;
        } catch (Throwable ignored) {
            // 继续读存档
        }
        Map<String, Object> data = AddonPaths.readJson(yanhuoFile(playerName));
        if (data == null) return null;
        Object sel = data.get("selected");
        if (sel == null) return null;
        String s = String.valueOf(sel);
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private static void setSelected(String playerName, String id) {
        Map<String, Object> data = AddonPaths.readJson(yanhuoFile(playerName));
        if (data == null) data = new LinkedHashMap<>();
        if (!(data.get("owned") instanceof List)) data.put("owned", new ArrayList<>());
        data.put("selected", id);
        AddonPaths.writeJson(yanhuoFile(playerName), data);
        invalidateSelected(playerName);
    }

    /**
     * 事件式失效 watchdog 的选中缓存（选中状态只在 {@link #setSelected} 里变化，
     * 玩家自选与挑战清空都会走到这里）——这比轮询便宜得多，也让长 TTL 只当保险用。
     */
    private static void invalidateSelected(String playerName) {
        if (playerName == null) return;
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(playerName)) {
                    WD_SELECTED.remove(p.getUniqueId());
                    WD_SELECTED_AT.remove(p.getUniqueId());
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 失效失败只是让缓存多留一会儿，由 TTL 兜底
        }
    }

    /** 供 BOSS战 在挑战期间调用：删掉当前异火选择并收掉异火带来的被动。 */
    public static boolean challengeClear(Player player) {
        try {
            if (player == null) return false;
            clearCangmingBuff(player);
            try {
                player.removePotionEffect(PotionEffectType.CONDUIT_POWER);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                player.removePotionEffect(PotionEffectType.DOLPHINS_GRACE);
            } catch (Throwable ignored) {
                // 忽略
            }
            setSelected(player.getName(), null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 名称
    // ------------------------------------------------------------------

    private static Yanhuo def(String id) {
        for (Yanhuo y : YANHUO_LIST) {
            if (y.id().equals(id)) return y;
        }
        return null;
    }

    /** 异火的显示名：优先取注册物品的渐变名，取不到退回定义里的名字。 */
    private static String gradientName(String id) {
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf != null) {
                ItemStack it = sf.getItem();
                if (it != null && it.hasItemMeta()) {
                    ItemMeta meta = it.getItemMeta();
                    if (meta != null && meta.hasDisplayName()) {
                        return LegacyComponentSerializer.legacySection()
                                .serialize(meta.displayName());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 退到定义名
        }
        Yanhuo d = def(id);
        return d != null ? d.name() : id;
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private static ItemStack glass(String name, String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack randomGlass() {
        String colorName = GLASS_COLORS[RANDOM.nextInt(GLASS_COLORS.length)];
        Material mat = Material.matchMaterial(colorName);
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize("§0"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Inventory buildMenu(String playerName) {
        List<String> owned = ownedYanhuo(playerName);
        String selected = selectedYanhuo(playerName);

        Inventory inv = Bukkit.createInventory(null, MENU_SIZE,
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(GUI_TITLE));

        // 最外围彩色玻璃
        for (int slot : OUTER_SLOTS) {
            inv.setItem(slot, randomGlass());
        }

        // 商品区
        for (int i = 0; i < PRODUCT_SLOTS.length && i < owned.size(); i++) {
            Yanhuo d = def(owned.get(i));
            if (d == null) continue;

            ItemStack stack;
            try {
                SlimefunItem sf = SlimefunItem.getById(d.id());
                stack = (sf != null && sf.getItem() != null)
                        ? sf.getItem().clone() : new ItemStack(Material.BLAZE_POWDER);
            } catch (Throwable ignored) {
                stack = new ItemStack(Material.BLAZE_POWDER);
            }

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection();
                meta.displayName(ser.deserialize(gradientName(d.id())));

                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(ser.deserialize(d.id().equals(selected)
                        ? "§a√" : "§e点击切换为该异火"));
                lore.add(ser.deserialize("§7基础：§f对目标造成 §e6 + 3% 最大生命值 §7虚空伤害"));
                for (String line : d.desc()) lore.add(ser.deserialize(line));
                meta.lore(lore);
                stack.setItemMeta(meta);
            }
            inv.setItem(PRODUCT_SLOTS[i], stack);
        }

        // 底部灰色
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, glass("§0", "GRAY_STAINED_GLASS_PANE"));
        }
        inv.setItem(CLOSE_SLOT, glass("§c§l关闭菜单", "BARRIER"));
        return inv;
    }

    private static void refresh(Inventory inv, String playerName) {
        Inventory fresh = buildMenu(playerName);
        for (int i = 0; i < MENU_SIZE; i++) inv.setItem(i, fresh.getItem(i));
    }

    private static void openSelect(Player player) {
        try {
            if (player == null || !player.isOnline()) return;

            // 糖然二阶段挑战期间禁止选择异火
            if (BossChallengeState.isPhaseTwoLocked(player)) {
                player.sendMessage("§c糖然二阶段挑战期间，异火被封印，无法选择异火！");
                return;
            }

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = MENU_OPEN_AT.get(uuid);
            if (last != null && now - last < MENU_OPEN_THROTTLE_MS) return;
            MENU_OPEN_AT.put(uuid, now);

            if (ownedYanhuo(player.getName()).isEmpty()) {
                player.sendMessage("§c您还没有收服异火！");
                return;
            }

            Inventory inv = buildMenu(player.getName());
            ACTIVE.add(inv);
            player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.5f);
            player.openInventory(inv);
        } catch (Throwable t) {
            TT.log("[异火选择] 打开界面失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 发射
    // ------------------------------------------------------------------

    private static Particle elementParticle(String selectedId) {
        if (selectedId == null) return Particle.FLAME;
        return switch (selectedId) {
            case "TR_YH01" -> Particle.CRIT;
            case "TR_YH02" -> Particle.HAPPY_VILLAGER;
            case "TR_YH03" -> Particle.SPLASH;
            case "TR_YH04" -> Particle.FLAME;
            case "TR_YH05" -> Particle.CLOUD;
            case "TR_YH06" -> Particle.END_ROD;
            case "TR_YH07" -> Particle.ASH;
            case "TR_YH08" -> Particle.ELECTRIC_SPARK;
            case "TR_YH09" -> Particle.SNOWFLAKE;
            default -> Particle.FLAME;
        };
    }

    private static void pushParticle(Player player) {
        UUID uuid = player.getUniqueId();
        org.bukkit.Location eye = player.getEyeLocation();
        Vector dir = player.getLocation().getDirection().clone().normalize();
        if (dir.lengthSquared() < 0.0001) dir.setZ(1);
        PARTICLES.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Beam(eye.getX(), eye.getY(), eye.getZ(),
                        dir.getX(), dir.getY(), dir.getZ(), 0));
    }

    private static void addParticle(Player player, String selectedId) {
        if (!RealmScript.hasRealm(player)) {
            player.sendMessage("§c请先使用洗髓丹开辟境界！");
            return;
        }
        if (!RealmScript.consumeSugarPower(player, 1)) {
            player.sendMessage("§c糖之力不足，无法释放异火！");
            return;
        }
        pushParticle(player);
        playShootSound(player);
        ensureParticleTask(player, selectedId);
        ensureBeamDamageTask(player, selectedId);
        ensureResidualHitTask(player, selectedId);
    }

    private static void playShootSound(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            if (now - SOUND_AT.getOrDefault(uuid, 0L) >= 700) {
                player.playSound(player.getLocation(), "entity.blaze.shoot", 1.0f, 1.0f);
                SOUND_AT.put(uuid, now);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 沿准星推进粒子；推到 7 格后化为云烟。 */
    private static void ensureParticleTask(Player player, String selectedId) {
        UUID uuid = player.getUniqueId();
        PARTICLE_SELECTED.put(uuid, selectedId);
        if (PARTICLE_TASKS.containsKey(uuid)) return;

        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(
                com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(), () -> {
                    try {
                        long now = System.currentTimeMillis();
                        String sid = PARTICLE_SELECTED.getOrDefault(uuid, selectedId);
                        Particle particle = elementParticle(sid);

                        // 持续右键：每 0.07 秒自动生成一个新异火粒子
                        if (now - LAST_EVENT.getOrDefault(uuid, 0L) <= BEAM_ACTIVE_WINDOW_MS
                                && now - LAST_SPAWN.getOrDefault(uuid, 0L) >= AUTO_SPAWN_INTERVAL_MS) {
                            pushParticle(player);
                            LAST_SPAWN.put(uuid, now);
                        }

                        List<Beam> arr = PARTICLES.get(uuid);
                        if (arr == null || arr.isEmpty()) {
                            if (now - LAST_EVENT.getOrDefault(uuid, 0L) > RESIDUAL_IDLE_MS) {
                                cancel(holder[0]);
                                PARTICLE_TASKS.remove(uuid);
                            }
                            return;
                        }

                        List<Beam> still = new ArrayList<>();
                        synchronized (arr) {
                            for (Beam p : arr) {
                                int dist = p.dist() + 1;
                                org.bukkit.Location loc = player.getEyeLocation().clone()
                                        .add(p.dx() * dist, p.dy() * dist, p.dz() * dist);
                                try {
                                    player.getWorld().spawnParticle(particle, loc,
                                            3, 0.15, 0.15, 0.15, 0.05);
                                } catch (Throwable ignored) {
                                    // 忽略
                                }
                                if (dist < BEAM_MAX_DIST) {
                                    still.add(new Beam(p.ox(), p.oy(), p.oz(),
                                            p.dx(), p.dy(), p.dz(), dist));
                                } else {
                                    try {
                                        player.getWorld().spawnParticle(Particle.CLOUD, loc,
                                                4, 0.2, 0.2, 0.2, 0.02);
                                    } catch (Throwable ignored) {
                                        // 忽略
                                    }
                                }
                            }
                        }
                        PARTICLES.put(uuid, Collections.synchronizedList(still));
                    } catch (Throwable ignored) {
                        // 粒子任务不抛异常
                    }
                }, 0L, PARTICLE_PERIOD);
        PARTICLE_TASKS.put(uuid, holder[0]);
    }

    /** 直线光束伤害：只在正长按右键时生效，松开 250ms 后停止。 */
    private static void ensureBeamDamageTask(Player player, String selectedId) {
        UUID uuid = player.getUniqueId();
        BEAM_SELECTED.put(uuid, selectedId);
        if (BEAM_DAMAGE_TASKS.containsKey(uuid)) return;

        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(
                com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(), () -> {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - LAST_EVENT.getOrDefault(uuid, 0L) > BEAM_ACTIVE_WINDOW_MS) {
                            cancel(holder[0]);
                            BEAM_DAMAGE_TASKS.remove(uuid);
                            return;
                        }
                        String sid = BEAM_SELECTED.get(uuid);
                        if (sid != null) checkBeamHit(player, sid);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }, 0L, 1L);
        BEAM_DAMAGE_TASKS.put(uuid, holder[0]);
    }

    /** 残留粒子（已飞出去、玩家可能已经转头）的独立命中判定。 */
    private static void ensureResidualHitTask(Player player, String selectedId) {
        UUID uuid = player.getUniqueId();
        RESIDUAL_SELECTED.put(uuid, selectedId);
        if (RESIDUAL_TASKS.containsKey(uuid)) return;

        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(
                com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(), () -> {
                    try {
                        long now = System.currentTimeMillis();
                        List<Beam> arr = PARTICLES.get(uuid);
                        if ((arr == null || arr.isEmpty())
                                && now - LAST_EVENT.getOrDefault(uuid, 0L) > RESIDUAL_IDLE_MS) {
                            cancel(holder[0]);
                            RESIDUAL_TASKS.remove(uuid);
                            return;
                        }
                        String sid = RESIDUAL_SELECTED.get(uuid);
                        if (sid == null || arr == null) return;
                        List<Beam> snapshot;
                        synchronized (arr) {
                            snapshot = new ArrayList<>(arr);
                        }
                        for (Beam p : snapshot) {
                            org.bukkit.Location loc = player.getEyeLocation().clone()
                                    .add(p.dx() * p.dist(), p.dy() * p.dist(), p.dz() * p.dist());
                            checkParticleHit(player, loc, sid);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }, 0L, RESIDUAL_PERIOD);
        RESIDUAL_TASKS.put(uuid, holder[0]);
    }

    private static void cancel(BukkitTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {
            // 已结束
        }
    }

    // ------------------------------------------------------------------
    // 命中结算
    // ------------------------------------------------------------------

    private static void checkParticleHit(Player player, org.bukkit.Location loc, String selectedId) {
        try {
            for (Entity ent : player.getWorld().getNearbyEntities(loc, 0.6, 0.6, 0.6)) {
                if (!(ent instanceof LivingEntity living)) continue;
                if (living.isInvulnerable()) continue;
                if (living.getUniqueId().equals(player.getUniqueId())) continue;
                applyElementEffect(player, living, selectedId, HIT_COOLDOWN_MS, HIT_COOLDOWN);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void checkBeamHit(Player player, String selectedId) {
        try {
            org.bukkit.Location eye = player.getEyeLocation().clone();
            Vector dir = player.getLocation().getDirection().clone().normalize();
            for (int d = 1; d <= BEAM_MAX_DIST; d++) {
                org.bukkit.Location loc = eye.clone()
                        .add(dir.getX() * d, dir.getY() * d, dir.getZ() * d);
                for (Entity ent : player.getWorld().getNearbyEntities(loc, 0.6, 0.6, 0.6)) {
                    if (!(ent instanceof LivingEntity living)) continue;
                    if (living.isInvulnerable()) continue;
                    if (living.getUniqueId().equals(player.getUniqueId())) continue;
                    applyElementEffect(player, living, selectedId,
                            BEAM_HIT_COOLDOWN_MS, BEAM_HIT_COOLDOWN);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 按所选异火对目标结算伤害与附加效果。 */
    private static void applyElementEffect(Player player, LivingEntity target, String selectedId,
                                           long cooldownMs, Map<UUID, Long> cooldownMap) {
        try {
            long now = System.currentTimeMillis();
            UUID key = target.getUniqueId();
            if (now - cooldownMap.getOrDefault(key, 0L) < cooldownMs) return;
            cooldownMap.put(key, now);

            double maxHp = target.getMaxHealth();

            // 每种元素都有的基础伤害：6 + 3% 最大生命值虚空伤害
            voidDamage(target, player, 6 + maxHp * 0.03);

            if (selectedId == null) return;
            switch (selectedId) {
                case "TR_YH01" -> voidDamage(target, player, 30 + maxHp * 0.09);
                case "TR_YH02" -> {
                    voidDamage(target, player, maxHp * 0.06);
                    try {
                        player.setHealth(Math.min(player.getMaxHealth(),
                                player.getHealth() + player.getMaxHealth() * 0.10));
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "TR_YH03" -> {
                    try {
                        target.setRemainingAir(0);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "TR_YH04" -> {
                    try {
                        target.setFireTicks(60);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                case "TR_YH05" -> {
                    try {
                        target.setRemainingAir(0);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    addEffect(target, PotionEffectType.SLOWNESS, 100, 1);
                }
                case "TR_YH06" -> {
                    try {
                        target.setGlowing(true);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    final LivingEntity t = target;
                    com.tangran.tangrantech.native_scripts.NativePluginHelper.runLater(() -> {
                        try {
                            t.setGlowing(false);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }, 40L);
                    spawnLightArrowRain(target, player);
                }
                case "TR_YH07" -> {
                    addEffect(target, PotionEffectType.DARKNESS, 100, 0);
                    addEffect(target, PotionEffectType.BLINDNESS, 100, 0);
                    addEffect(target, PotionEffectType.NAUSEA, 100, 1);
                }
                case "TR_YH08" -> {
                    for (int i = 0; i < 3; i++) {
                        try {
                            target.getWorld().strikeLightningEffect(target.getLocation());
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        voidDamage(target, player, maxHp * 0.03);
                    }
                }
                case "TR_YH09" -> addEffect(target, PotionEffectType.SLOWNESS, 100, 10);
                default -> { }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 虚空伤害：走 {@code damage(amount, player)}。
     *
     * <p>先清无敌帧保证每 0.05 / 0.15 秒的伤害都能结算，结算后立刻恢复，
     * 脱离特效时不会一直处于无无敌帧状态。</p>
     */
    private static void voidDamage(LivingEntity target, Player player, double amount) {
        try {
            target.setNoDamageTicks(0);
            target.damage(amount, player);
            target.setNoDamageTicks(target.getMaximumNoDamageTicks());
        } catch (Throwable ignored) {
            // 目标已消失
        }
    }

    private static void addEffect(LivingEntity target, PotionEffectType type,
                                  int duration, int amplifier) {
        try {
            target.addPotionEffect(new PotionEffect(type, duration, amplifier));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 曜日净世炎：目标上方 3×3 各落 5 支光灵箭（真实光谱箭，命中造成 0.2% 最大生命值）。 */
    private static void spawnLightArrowRain(LivingEntity target, Player shooter) {
        try {
            org.bukkit.Location base = target.getLocation().clone().add(0, 3, 0);
            for (int gx = -1; gx <= 1; gx++) {
                for (int gz = -1; gz <= 1; gz++) {
                    org.bukkit.Location arrowLoc = base.clone().add(gx, 0, gz);
                    for (int a = 0; a < 5; a++) {
                        SpectralArrow arrow = target.getWorld().spawn(arrowLoc, SpectralArrow.class);
                        arrow.setVelocity(new Vector(0, -0.6, 0));
                        arrow.setShooter(shooter);
                        arrow.setDamage(0);
                        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        LIGHT_ARROWS.add(arrow.getUniqueId());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 被动：沧溟玄水焱
    // ------------------------------------------------------------------

    private static void applyCangmingBuff(Player player) {
        try {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.CONDUIT_POWER, Integer.MAX_VALUE, 9));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DOLPHINS_GRACE, Integer.MAX_VALUE, 0));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void clearCangmingBuff(Player player) {
        try {
            player.removePotionEffect(PotionEffectType.CONDUIT_POWER);
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            player.removePotionEffect(PotionEffectType.DOLPHINS_GRACE);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 切换异火时的被动迁移。 */
    private static void applyPassive(Player player, String selectedId, String prevSelectedId) {
        // 厚土地心火被动已改为监听实现（物理只 1 点 / 免疫魔法与 setHealth 击杀），不再改护甲值
        if (WATER_ID.equals(selectedId)) {
            applyCangmingBuff(player);
        } else if (WATER_ID.equals(prevSelectedId)) {
            clearCangmingBuff(player);
        }
    }

    private static void restoreCangmingIfSelected(Player player) {
        try {
            if (WATER_ID.equals(selectedYanhuo(player.getName()))) {
                applyCangmingBuff(player);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 厚土地心火
    // ------------------------------------------------------------------

    private static void startWatchdog() {
        if (watchdogTask != null) return;
        watchdogTask = Bukkit.getScheduler().runTaskTimer(
                com.tangran.tangrantech.native_scripts.NativePluginHelper.javaPlugin(),
                YanhuoSelectionScript::watchdogTick, 1L, 1L);
    }

    /** 每刻比对血量，回滚「没有对应伤害事件」的掉血。 */
    private static void watchdogTick() {
        try {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    UUID uuid = p.getUniqueId();
                    if (p.isDead()) {
                        WD_LAST_HP.put(uuid, 0.0);
                        WD_ALLOWED.put(uuid, 0.0);
                        continue;
                    }

                    // 二阶段挑战期间一律视为未选中（含厚土免死在内全部被动失效）。
                    // isPhaseTwoLocked 是纯内存查询（metadata + map），每刻调它零成本：
                    // 锁定时不读缓存、也不污染缓存，只把时间戳顶新 —— 解锁后下一刻立即过期重读，
                    // 不会出现「挑战已结束 30 秒异火还没恢复」的窗口。
                    boolean phase2 = false;
                    try {
                        phase2 = BossChallengeState.isPhaseTwoLocked(p);
                    } catch (Throwable ignored) {
                        // 探测失败按未锁定处理（与 selectedYanhuo 内的容错一致）
                    }
                    String sel;
                    if (phase2) {
                        WD_SELECTED_AT.put(uuid, now);
                        sel = NONE_SELECTED;
                    } else {
                        sel = WD_SELECTED.get(uuid);
                        if (sel == null || (now - WD_SELECTED_AT.getOrDefault(uuid, 0L)) > SELECTED_TTL_MS) {
                            // selectedYanhuo 内含「二阶段挑战期间视为未选中」，所以挑战中不会误护
                            String fresh = selectedYanhuo(p.getName());
                            // 负结果也进缓存（哨兵值）：remove() 会让下一刻的 null 判断再次成立
                            sel = (fresh == null) ? NONE_SELECTED : fresh;
                            WD_SELECTED.put(uuid, sel);
                            WD_SELECTED_AT.put(uuid, now);
                        }
                    }

                    double hp = p.getHealth();
                    Double lastBoxed = WD_LAST_HP.get(uuid);
                    double allowed = WD_ALLOWED.getOrDefault(uuid, 0.0);
                    WD_LAST_HP.put(uuid, hp);
                    WD_ALLOWED.put(uuid, 0.0);   // 允许额度只在本刻生效

                    if (!EARTH_ID.equals(sel) || lastBoxed == null) continue;
                    double last = lastBoxed;
                    if (hp >= last - 0.001) continue;   // 没掉血
                    double lost = last - hp;
                    // 只放行「本刻伤害事件算出来的额度」：星屑剑那类紧跟正规命中一起发生的
                    // setHealth 额外伤害会超出这个额度，多出来的部分被补回；没有事件时额度为 0，全额补回
                    if (lost <= allowed + 0.05) continue;

                    double back = Math.min(last - allowed, p.getMaxHealth());
                    if (!(back > 0)) back = 1.0;
                    if (back > hp) p.setHealth(back);
                    // 关键：回滚后把「基准血量」也改成回滚后的值。否则基准会一路采用
                    // 「已被扣过」的血量，每次伤害都把基准往下拉一截，最终仍会被磨死。
                    WD_LAST_HP.put(uuid, back);

                    if ((lost - allowed) >= 1.0
                            && (now - WD_MSG_AT.getOrDefault(uuid, 0L)) > WATCHDOG_MSG_INTERVAL_MS) {
                        WD_MSG_AT.put(uuid, now);
                        p.sendMessage("§a厚土地心火护住了你的生命！");
                    }
                } catch (Throwable ignored) {
                    // 单个玩家失败跳过
                }
            }
        } catch (Throwable ignored) {
            // 看门狗绝不抛异常
        }
    }

    /** 玩家最近是否「真的」受过伤害。 */
    private static boolean hadRecentDamageEvent(Player player) {
        try {
            long t = WD_EVENT_AT.getOrDefault(player.getUniqueId(), 0L);
            return (System.currentTimeMillis() - t) <= WATCHDOG_GRACE_MS;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 是否「生物物理攻击」。
     *
     * <p>原脚本注释说明：有些插件用自定义伤害源（{@code getCause()} 会是 CUSTOM）打人，
     * 所以除了看 Cause，还兜底看「施加者」——只要施加者是生物、或生物射出的弹射物，
     * 就算物理攻击。</p>
     */
    private static boolean isPhysicalHit(EntityDamageEvent event) {
        try {
            EntityDamageEvent.DamageCause cause = event.getCause();
            if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                return true;
            }
        } catch (Throwable ignored) {
            // 继续看施加者
        }
        try {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Entity damager = byEntity.getDamager();
                if (damager instanceof LivingEntity) return true;
                if (damager instanceof Projectile proj) {
                    return proj.getShooter() instanceof LivingEntity;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 异火核心的 lore
    // ------------------------------------------------------------------

    private static void updateCoreLore(Player player) {
        try {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null) return;
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null || !CORE_ITEM_ID.equalsIgnoreCase(sf.getId())) return;

            String selected = selectedYanhuo(player.getName());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer ser =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection();
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ser.deserialize(selected == null
                    ? "§7长按右键发射异火"
                    : "§7长按右键发射" + gradientName(selected)));
            lore.add(ser.deserialize("§7Shift+左键切换异火"));
            meta.lore(lore);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public Iterable<Listener> listeners() {
        startWatchdog();
        return Collections.singletonList(new YanhuoListener());
    }

    /** 异火相关的全部事件。 */
    private static final class YanhuoListener implements Listener {

        // ---- 界面 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClick(InventoryClickEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top == null || !ACTIVE.contains(top)) return;
                if (!(event.getWhoClicked() instanceof Player player)) return;

                Inventory clicked = event.getClickedInventory();
                int slot = event.getRawSlot();

                if (clicked != top) {
                    if (event.isShiftClick()) event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);

                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                    return;
                }

                List<String> owned = ownedYanhuo(player.getName());
                int idx = productIndex(slot);
                if (idx < 0 || idx >= owned.size()) return;

                Yanhuo d = def(owned.get(idx));
                if (d == null) return;

                if (BossChallengeState.isPhaseTwoLocked(player)) {
                    player.sendMessage("§c糖然二阶段挑战期间，异火被封印，无法选择异火！");
                    return;
                }

                String prev = selectedYanhuo(player.getName());
                setSelected(player.getName(), d.id());
                applyPassive(player, d.id(), prev);
                refresh(top, player.getName());
                player.updateInventory();
                player.sendMessage("§a您已切换" + gradientName(d.id()));
                player.playSound(player.getLocation(), "block.note_block.pling", 0.8f, 1.8f);
            } catch (Throwable t) {
                TT.log("[异火选择] 点击处理异常: " + t);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onClose(InventoryCloseEvent event) {
            try {
                ACTIVE.remove(event.getInventory());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDrag(InventoryDragEvent event) {
            try {
                Inventory top = Views.top(event.getView());
                if (top != null && ACTIVE.contains(top)) event.setCancelled(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        private static int productIndex(int slot) {
            for (int i = 0; i < PRODUCT_SLOTS.length; i++) {
                if (PRODUCT_SLOTS[i] == slot) return i;
            }
            return -1;
        }

        // ---- 光灵箭命中 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onProjectileHit(ProjectileHitEvent event) {
            try {
                if (!(event.getEntity() instanceof SpectralArrow arrow)) return;
                UUID id = arrow.getUniqueId();
                if (!LIGHT_ARROWS.remove(id)) return;

                Entity hit = event.getHitEntity();
                if (hit instanceof LivingEntity living && !living.isInvulnerable()) {
                    // damage(double, Entity) 需要一个 Entity 施加者；
                    // ProjectileSource 不一定是 Entity（例如方块/自定义源），因此先做类型收窄。
                    org.bukkit.projectiles.ProjectileSource shooter = arrow.getShooter();
                    try {
                        if (shooter instanceof Entity shooterEntity) {
                            living.damage(living.getMaxHealth() * 0.002, shooterEntity);
                        } else {
                            living.damage(living.getMaxHealth() * 0.002);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                try {
                    arrow.remove();
                } catch (Throwable ignored) {
                    // 忽略
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 厚土地心火：免疫高等级瞬间治疗 ----

        /**
         * 厚土玩家免疫治疗药水：先把他们的强度归零，只有在「受影响的全是厚土玩家」时
         * 才取消整瓶药水。
         *
         * <p>原实现见到第一个厚土玩家就 {@code setCancelled(true)} 并 return ——
         * 队友在同一瓶药水的溅射范围内时会一起被作废（误伤队友）。瞬间治疗的最终
         * 拦截由 {@link #onPotionEffect} 按实体兜底，这里不必靠整瓶取消来兜底。</p>
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPotionSplash(PotionSplashEvent event) {
            try {
                Collection<LivingEntity> affected = event.getAffectedEntities();
                boolean anyEarth = false;
                boolean allEarth = true;
                for (LivingEntity entity : affected) {
                    boolean earth = entity instanceof Player player
                            && EARTH_ID.equals(selectedYanhuo(player.getName()));
                    if (earth) {
                        anyEarth = true;
                        event.setIntensity(entity, 0);
                    } else {
                        allEarth = false;
                    }
                }
                // 只有「没人受益」时才整瓶作废，避免误伤同范围的队友
                if (anyEarth && allEarth) {
                    try {
                        event.setCancelled(true);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /** 兜底：拦截瞬间治疗（1.20.5 起 HEAL 更名为 INSTANT_HEALTH）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPotionEffect(EntityPotionEffectEvent event) {
            try {
                if (!(event.getEntity() instanceof Player player)) return;
                if (!EARTH_ID.equals(selectedYanhuo(player.getName()))) return;
                PotionEffect eff = event.getNewEffect();
                if (eff != null && eff.getType().equals(PotionEffectType.INSTANT_HEALTH)) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 厚土地心火：物理只 1 点、免疫魔法 ----

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamageHighest(EntityDamageEvent event) {
            try {
                if (!(event.getEntity() instanceof Player player)) return;
                if (!EARTH_ID.equals(selectedYanhuo(player.getName()))) return;

                EntityDamageEvent.DamageCause cause = event.getCause();
                boolean physical = cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                        || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;

                if (cause == EntityDamageEvent.DamageCause.PROJECTILE
                        && event instanceof EntityDamageByEntityEvent byEntity
                        && byEntity.getDamager() instanceof Projectile proj
                        && proj.getShooter() instanceof LivingEntity) {
                    physical = true;
                }

                boolean magic = cause == EntityDamageEvent.DamageCause.MAGIC
                        || cause == EntityDamageEvent.DamageCause.POISON
                        || cause == EntityDamageEvent.DamageCause.WITHER
                        || cause == EntityDamageEvent.DamageCause.DRAGON_BREATH;

                if (physical) {
                    event.setDamage(1.0);
                } else if (magic) {
                    event.setCancelled(true);
                    event.setDamage(0);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        /**
         * 看门狗数据源：记录「最近一次伤害事件时间」+「本刻事件允许的伤害额度」。
         *
         * <p>额度规则：生物物理攻击最多放行 1 点（和厚土描述一致，事件层被别的插件改大也照样压回 1），
         * 其它伤害按事件最终伤害放行；超出的部分（星屑剑那类直接 setHealth 的额外伤害）由看门狗补回。</p>
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDamageMonitor(EntityDamageEvent event) {
            try {
                if (!(event.getEntity() instanceof Player player)) return;
                UUID uuid = player.getUniqueId();
                double dmg;
                try {
                    dmg = event.getFinalDamage();
                } catch (Throwable ignored) {
                    try {
                        dmg = event.getDamage();
                    } catch (Throwable ignored2) {
                        dmg = 0;
                    }
                }
                if (!(dmg > 0)) dmg = 0;

                double allowedAdd = dmg;
                try {
                    if (isPhysicalHit(event)) allowedAdd = Math.min(dmg, 1.0);
                } catch (Throwable ignored) {
                    // 用原始伤害
                }
                WD_ALLOWED.merge(uuid, allowedAdd, Double::sum);
                WD_EVENT_AT.put(uuid, System.currentTimeMillis());
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 厚土地心火：免疫 setHealth(0) 直接击杀 ----

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(PlayerDeathEvent event) {
            try {
                Player player = event.getEntity();
                if (!EARTH_ID.equals(selectedYanhuo(player.getName()))) return;

                EntityDamageEvent last = player.getLastDamageCause();
                boolean magicDeath = last != null && (
                        last.getCause() == EntityDamageEvent.DamageCause.MAGIC
                                || last.getCause() == EntityDamageEvent.DamageCause.POISON
                                || last.getCause() == EntityDamageEvent.DamageCause.WITHER
                                || last.getCause() == EntityDamageEvent.DamageCause.DRAGON_BREATH);

                // setHealth(0) 通常没有伤害来源／没有正常伤害事件；魔法伤害也被免疫。
                // 关键补充：最近 150ms 内没有任何伤害事件 → 必是 setHealth 类直接击杀
                // （否则 lastDamageCause 会指向「上一次物理伤害」而漏判 → 秒杀）
                if (last == null
                        || last.getCause() == EntityDamageEvent.DamageCause.CUSTOM
                        || last.getCause() == EntityDamageEvent.DamageCause.SUICIDE
                        || magicDeath
                        || !hadRecentDamageEvent(player)) {
                    event.setCancelled(true);
                    player.setHealth(1.0);
                    player.sendMessage("§a厚土地心火护住了你的生命！");
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // ---- 沧溟玄水焱：上线 / 复活时按需恢复 ----

        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            restoreCangmingIfSelected(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onRespawn(PlayerRespawnEvent event) {
            Player player = event.getPlayer();
            com.tangran.tangrantech.native_scripts.NativePluginHelper
                    .runLater(() -> restoreCangmingIfSelected(player), 1L);
        }
    }

    /** 供调试/卸载使用。 */
    public static void shutdown() {
        if (watchdogTask != null) {
            cancel(watchdogTask);
            watchdogTask = null;
        }
        ACTIVE.clear();
        for (BukkitTask t : PARTICLE_TASKS.values()) cancel(t);
        for (BukkitTask t : BEAM_DAMAGE_TASKS.values()) cancel(t);
        for (BukkitTask t : RESIDUAL_TASKS.values()) cancel(t);
        PARTICLE_TASKS.clear();
        BEAM_DAMAGE_TASKS.clear();
        RESIDUAL_TASKS.clear();
        PARTICLES.clear();
        LIGHT_ARROWS.clear();
    }
}
