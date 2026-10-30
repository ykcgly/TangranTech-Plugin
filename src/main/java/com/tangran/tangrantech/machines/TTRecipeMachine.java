package com.tangran.tangrantech.machines;

import com.tangran.tangrantech.TT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Bukkit;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 电力配方机器（recipe_machines.yml）。继承 {@link AContainer} 复用能量/进度/GUI 基建，
 * 自定义 {@link #findNextRecipe} 与 {@link #tick} 以支持 {@link TTRecipe} 的
 * 概率产出、noConsume 与工具耐久消耗。
 */
public class TTRecipeMachine extends AContainer implements RecipeDisplayItem, PowerConsumer {

    protected final int[] inputSlots;
    protected final int[] outputSlots;
    /** GUI 槽 → 在 inputSlots 中的位置（-1 = 非输入槽）。构造期预算，避免每 tick 重建 Map。 */
    protected final int[] posBySlot;
    protected final List<TTRecipe> recipes;
    protected final MenuDef menu;
    protected final boolean hideAll;
    protected final ItemStack progressBar;
    /** 解析后的进度槽；与输入/输出槽冲突时为 -1。 */
    protected final int progressSlotResolved;
    /** 进行中配方（按方块），完成时取此处做概率滚动。 */
    protected final Map<org.bukkit.Location, TTRecipe> active = new ConcurrentHashMap<>();

    /** 「0 输入」发生器的上次产出游戏刻（按方块），用于按 tickRate 节流。 */
    private final Map<org.bukkit.Location, Long> lastGen = new ConcurrentHashMap<>();

    /**
     * 配方展示按钮上的索引标记（对齐 RSC 的 {@code rsc_recipe_index}）。
     *
     * <p>「多物品输入 / 多物品输出」按钮本身看不出是哪条配方，所以像 RSC 一样把
     * 配方下标写进 PDC —— 玩家在指南里点击它时，监听器读出下标即可打开该配方详情。</p>
     */
    public static final NamespacedKey RECIPE_INDEX_KEY =
            NamespacedKey.fromString("tangrantech:recipe_index");

    /** 「多物品输入」按钮（对齐 RSC {@code RecipesHolder#RECIPE_INPUT}：绿色染色玻璃板）。 */
    private static final String MULTI_INPUT_NAME = "§a多物品输入";
    /** 「多物品输出」按钮（对齐 RSC {@code RecipesHolder#RECIPE_OUTPUT}）。 */
    private static final String MULTI_OUTPUT_NAME = "§a多物品输出";
    private static final String CLICK_HINT = "§2> §a点击查看";

    /** 全部已注册实例（加载收尾统一剔除引用禁用物品的配方）。 */
    private static final List<TTRecipeMachine> INSTANCES = new ArrayList<>();

    /** 运行期诊断节流：每方块每 100 tick 最多一条。 */
    private final Map<org.bukkit.Location, Long> diagLast = new ConcurrentHashMap<>();

    /** 子类打开运行期诊断（超多方块机器排查用；节流见 {@link #diagThrottle}）。 */
    protected boolean diagEnabled() { return false; }

    /** 诊断节流：通过才允许打点。 */
    protected final boolean diagThrottle(org.bukkit.Location loc) {
        long now = Bukkit.getCurrentTick();
        Long last = diagLast.get(loc);
        if (last != null && now - last < 100) return false;
        diagLast.put(loc, now);
        return true;
    }

    /** 摘要输入槽现状（id x数量），诊断日志用。 */
    protected final String inputSummary(BlockMenu inv) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inputSlots.length; i++) {
            ItemStack it = inv.getItemInSlot(inputSlots[i]);
            if (it == null || it.getType().isAir()) continue;
            SlimefunItem sf = SlimefunItem.getByItem(it);
            if (sb.length() > 1) sb.append(", ");
            sb.append("槽").append(inputSlots[i]).append('=')
              .append(sf != null ? sf.getId() : it.getType().name()).append('x').append(it.getAmount());
        }
        return sb.append("]").toString();
    }

    private static String describe(ItemStack it) {
        if (it == null) return "null";
        SlimefunItem sf = SlimefunItem.getByItem(it);
        return (sf != null ? sf.getId() : it.getType().name()) + "x" + it.getAmount();
    }

    /**
     * 无配方命中时的诊断：调用与生产<b>完全相同</b>的 {@link #matchOne}（带 trace），
     * 逐条给出第一个失败环节。因为共用同一实现，「输入全过但仍未命中」在结构上
     * 已不可分歧——若该分支出现，只能是 tick 内状态被外部（如 Cargo）改动。
     */
    protected final String explainNoMatch(BlockMenu inv) {
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < recipes.size(); idx++) {
            TTRecipe r = recipes.get(idx);
            StringBuilder t = new StringBuilder();
            int[] chosen = matchOne(inv, r, t);
            if (chosen == null) {
                sb.append(" | 配方").append(idx).append(' ').append(t);
                if (!InvUtils.fitAll(inv.toInventory(), r.getOutput(), outputSlots)) {
                    sb.append(" 且输出放不下");
                }
                continue;
            }
            if (!InvUtils.fitAll(inv.toInventory(), r.getOutput(), outputSlots)) {
                sb.append(" | 配方").append(idx).append(" 输入全过但输出放不下");
                continue;
            }
            sb.append(" | 配方").append(idx).append(" 交叉重放全部通过但仍未命中——与生产共用同一实现，此分支不应出现，请反馈");
            break;
        }
        return sb.toString();
    }

    public TTRecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                           int[] inputSlots, int[] outputSlots, List<TTRecipe> recipes,
                           int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.posBySlot = buildPosBySlot(inputSlots);
        // 复制一份可变副本：加载收尾会按「引用禁用物品」剔除条目，入参可能是不可变列表
        this.recipes = (recipes == null) ? new ArrayList<>() : new ArrayList<>(recipes);
        this.menu = menu;
        this.hideAll = hideAll;
        INSTANCES.add(this);
        // 「多物品输入/输出」按钮的点击监听（幂等，只注册一次）
        RecipePreview.registerOnce();
        this.progressBar = (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
        // 进度槽冲突防御：菜单漏配 progressbar 时回退 22，而某些大型工作台输入槽恰好覆盖 22，
        // 默认进度占位会叠进输入格成为「来路不明又点不走的物品」。
        // 冲突时先回退到 9（造物匣系列等布局的空闲展示位，内容侧已查验），9 也被占用才禁用。
        int ps = (menu != null && menu.progressSlot >= 0) ? menu.progressSlot : 22;
        if (overlapsFunctional(ps)) ps = 9;
        if (overlapsFunctional(ps)) {
            TT.log(getId() + " 的进度槽 " + ps + " 与输入/输出槽重叠，无法展示进度，已禁用进度占位");
            ps = -1;
        }
        this.progressSlotResolved = ps;
        setProcessingSpeed(Math.max(1, speed));
        setCapacity(Math.max(1, capacity));
        setEnergyConsumption(Math.max(1, Math.min(consumption, Math.max(1, capacity))));
        // AContainer 在 super() 中已用 this::constructMenu 建过 preset（此时字段尚未赋值），
        // 这里字段就绪后重建 preset（覆盖前一个），并补设进度条。
        createPreset(this, getInventoryTitle(), this::constructMenu);
        getMachineProcessor().setProgressBar(progressBar);
    }

    @Override
    public int getConsumption() { return getEnergyConsumption(); }

    @Override
    public int[] getInputSlots() { return inputSlots; }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    /** 全部配方（供大配方菜单展示与运行期筛选）。 */
    public List<TTRecipe> getRecipes() { return recipes; }

    /** 交给「逻辑工艺（LogiTech）」堆叠机的配方形态（惰性缓存；未装 LogiTech 时为空表）。 */
    private volatile List<me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe>
            logiTechRecipes;

    /**
     * 本机是否允许被「逻辑工艺（LogiTech）」堆叠机对接。
     *
     * <p>默认放行；<b>依赖方块上下文</b>的子类必须返回 {@code false}——
     * 目前只有多方块核心机器（{@link TTSuperMultiBlockMachine}）属于这一类：
     * 它的产能取决于结构是否成形、核心朝向与 {@code SuperBlocks} 的成形缓存，
     * 而堆叠机只认「输入槽 + 配方 + 每 tick 耗电」，登录进去等于绕过结构校验。</p>
     *
     * <p>注意：脚本机器（{@code items/ScriptMachineItem}）继承的是 {@code SlimefunItem}
     * 而非本类，天然不在对接范围，不需要在这里处理。</p>
     */
    public boolean supportsLogiTechStacking() { return true; }

    /**
     * 覆写父类：{@code AContainer} 默认返回空的 {@code machineRecipes}（本类配方自管），
     * 这会让逻辑工艺的堆叠扫描把本机器当成「无配方」而完全忽略。
     * 这里改为返回按语义转换后的配方（详见 {@link com.tangran.tangrantech.compat.LogiTechStackBridge}）：
     * 纯生成语义 → {@code MGeneratorRecipe}；其余 → 保留概率 / noConsume 的堆叠配方。
     *
     * <p>本附属运行期不使用该方法（{@link #tick(Block)} 读的是 {@code recipes} 字段），
     * 因此覆写不影响自身产能与界面。</p>
     *
     * <p>两条排除规则：① {@link #supportsLogiTechStacking()} 为 {@code false} 的机器
     * （多方块核心）直接给空表；② 桥接层尚未就绪（未装 LogiTech，或逻辑工艺还没到它自己的
     * post-register 阶段）时返回空表且<b>不写缓存</b>——这里必须「一票否决」，
     * 因为对方是靠「第 1 tick 才首次读取配方」来保证快照到全部附属的机器，
     * 我们提前触发它的类初始化会让晚加载附属的配方整批消失（详见
     * {@link com.tangran.tangrantech.compat.LogiTechStackBridge} 的第 5 条纪律）。</p>
     */
    @Override
    public List<me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe> getMachineRecipes() {
        if (!supportsLogiTechStacking()) return java.util.Collections.emptyList();
        var cached = logiTechRecipes;
        if (cached != null) return cached;
        // 没装逻辑工艺：探测一次就永久给空表，别让指南每次渲染都跑一遍反射
        if (!com.tangran.tangrantech.compat.LogiTechStackBridge.classPresent()) {
            logiTechRecipes = java.util.Collections.emptyList();
            return logiTechRecipes;
        }
        List<me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe> built =
                com.tangran.tangrantech.compat.LogiTechStackBridge.buildRecipes(recipes);
        if (!com.tangran.tangrantech.compat.LogiTechStackBridge.ready()) return built;
        logiTechRecipes = built;
        return built;
    }


    @Override
    public String getMachineIdentifier() { return getId(); }

    @Override
    public ItemStack getProgressBar() { return progressBar; }

    @Override
    protected void registerDefaultRecipes() { /* 配方由本类自行管理 */ }

    /** 预算「GUI 槽 → 输入位」映射：数组下标即槽号，值为在 inputSlots 中的位置，-1 表示非输入槽。 */
    private static int[] buildPosBySlot(int[] inputSlots) {
        if (inputSlots == null || inputSlots.length == 0) return new int[0];
        int max = -1;
        for (int s : inputSlots) max = Math.max(max, s);
        if (max < 0) return new int[0];
        int[] map = new int[max + 1];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < inputSlots.length; i++) {
            int s = inputSlots[i];
            if (s >= 0 && s < map.length) map[s] = i;
        }
        return map;
    }

    /**
     * 候选进度槽是否与输入/输出槽重叠。
     *
     * <p>只比对输入/输出：进度槽在赋值前调用本方法，此时 {@code progressSlotResolved}
     * 仍是 blank final，读它会触发「可能尚未初始化」编译错误。</p>
     */
    private boolean overlapsFunctional(int slot) {
        if (slot < 0) return false;
        for (int s : inputSlots) if (s == slot) return true;
        for (int s : outputSlots) if (s == slot) return true;
        return false;
    }

    /**
     * 完成一次合成后的产出推送。子类（如模板机器）可覆盖以按模板堆叠数放大产出。
     *
     * <p>{@link #tick(Block)} 必须走这里而不是直接调 {@code recipe.pushOutputs(...)}，
     * 否则子类的放大逻辑不会生效。</p>
     */
    protected void pushRecipeOutputs(Block b, BlockMenu inv, TTRecipe r) {
        if (r != null) r.pushOutputs(inv, outputSlots);
    }

    /** 进度槽（子类可覆盖；返回 -1 表示无进度显示）。 */
    protected int progressSlot() { return progressSlotResolved; }

    /** 额外的可交互槽位（不会被背景填充阻挡），子类可覆盖。 */
    protected Set<Integer> extraFunctionalSlots() { return java.util.Collections.emptySet(); }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // super() 阶段会提前调用一次（字段为 null），此时跳过；由构造器末尾重建 preset 时再真正构建。
        if (inputSlots == null || outputSlots == null) return;

        Set<Integer> placed = new java.util.HashSet<>();
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }

        Set<Integer> functional = new java.util.HashSet<>();
        for (int s : inputSlots) functional.add(s);
        for (int s : outputSlots) functional.add(s);
        int pslot = progressSlot();
        if (pslot >= 0) functional.add(pslot);
        Set<Integer> extra = extraFunctionalSlots();

        // 尺寸必须覆盖所有功能槽与装饰槽：BlockMenuPreset 按「已放置物品」自动定尺寸，
        // 而输出槽只挂 click handler、不放置物品。若某功能槽超出自动尺寸，运行期
        // getItemInSlot/consumeItem 会越界。此处按最大槽位向上取整到 9 的倍数（≤54）。
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
        for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
        if (pslot >= 0) maxSlot = Math.max(maxSlot, pslot);
        for (int s : extra) maxSlot = Math.max(maxSlot, s);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        // ★ 显式 setSize（向上取整到 9 的倍数）：ChestMenu 的真实尺寸只随「实际放置的
        // 物品」自动增长，而输入/输出槽只挂 click handler、不放物品，declared 只约束
        // 上面背景循环的边界——一旦 menu 查空（无装饰可放），最高放置槽可能只有 26，
        // 预设实际 27 格，输出槽 ≥27 的机器在 fitAll/getItemInSlot 时数组越界
        // （Tr_cjzryhj 实机报错 2026-10-25）。显式设一次，保证 inventory 覆盖全部功能槽。
        preset.setSize(Math.min(54, Math.max(9, ((size + 8) / 9) * 9)));

        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i) && !extra.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }

        // 输出槽允许取出：必须用 AdvancedMenuClickHandler——普通 MenuClickHandler 的第三参数是
        // e.getCurrentItem()（槽内物品），左键提起会被误判为放入而取消；Advanced 版本才能拿到
        // 玩家光标 e.getCursor()（空手点击 = AIR → 放行提起）。
        for (int i : outputSlots) {
            preset.addMenuClickHandler(i, new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, org.bukkit.entity.Player player,
                                       int slot, ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }

                @Override
                public boolean onClick(org.bukkit.entity.Player player, int slot, ItemStack cursor,
                                       me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }
            });
        }

        // menus.yml 显式配置的 progressbar 槽位是内容侧的刻意安排，覆盖同槽位的装饰物品；
        // 默认回退槽（22/9）仍让位给装饰
        if (pslot >= 0 && (menu != null && menu.progressFromConfig || !placed.contains(pslot))) {
            preset.addItem(pslot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), inputSlots);
                    inv.dropItems(b.getLocation(), outputSlots);
                    Set<Integer> extra = extraFunctionalSlots();
                    if (!extra.isEmpty()) {
                        int[] extraSlots = extra.stream().mapToInt(Integer::intValue).toArray();
                        inv.dropItems(b.getLocation(), extraSlots);
                    }
                }
                active.remove(b.getLocation());
                getMachineProcessor().endOperation(b);
            }
        };
    }

    @Override
    protected void tick(Block b) {
        BlockMenu inv = BlockStorage.getInventory(b.getLocation());
        if (inv == null) return;
        int pslot = progressSlot();
        CraftingOperation op = getMachineProcessor().getOperation(b);
        if (op == null) {
            MachineRecipe next = findNextRecipe(inv);
            if (next == null && diagEnabled() && diagThrottle(b.getLocation())) {
                boolean any = false;
                for (int s : inputSlots) {
                    ItemStack it = inv.getItemInSlot(s);
                    if (it != null && !it.getType().isAir()) { any = true; break; }
                }
                if (any) {
                    TT.log("[机器诊断v2] " + getId() + " @" + b.getLocation().getBlockX() + ","
                            + b.getLocation().getBlockY() + "," + b.getLocation().getBlockZ()
                            + " 有输入但无配方命中: " + inputSummary(inv)
                            + explainNoMatch(inv)
                            + " 配方数=" + recipes.size() + " 电力=" + getCharge(b.getLocation()));
                }
            }
            if (next instanceof TTRecipe tr) {
                // 造物匣这类「0 输入」发生器：配方没有输入可消耗，input 恒为空数组
                // （MaterialGeneratorsLoader → Recipes.readOutputs → new ItemStack[0]）。
                // Slimefun 的 CraftingOperation 构造时会 Validate.notEmpty(recipe.getInput())，
                // 空配料直接抛 IllegalArgumentException（实机 04-13 / 04-14 报错）。
                // 这类机器本来就不该显示进度条，改为按自身周期直接产出。
                if (tr.getInput().length == 0) {
                    produceWithoutInput(b, inv, tr);
                    return;
                }
                op = new CraftingOperation(next);
                getMachineProcessor().startOperation(b, op);
                active.put(b.getLocation(), tr);
                if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            }
            return;
        }
        if (!takeCharge(b.getLocation())) {
            if (diagEnabled() && diagThrottle(b.getLocation())) {
                TT.log("[机器诊断] " + getId() + " @" + b.getLocation().getBlockX() + ","
                        + b.getLocation().getBlockY() + "," + b.getLocation().getBlockZ()
                        + " 电力不足: 当前" + getCharge(b.getLocation())
                        + " 每次" + getEnergyConsumption() + "（机器需接入能源网络充能）");
            }
            return;
        }
        if (!op.isFinished()) {
            if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            op.addProgress(getSpeed());
            return;
        }
        TTRecipe r = active.remove(b.getLocation());
        // 走 pushRecipeOutputs 而非直接 pushOutputs，保证子类（模板机器）的放大逻辑生效
        pushRecipeOutputs(b, inv, r);
        if (pslot >= 0) inv.replaceExistingItem(pslot, progressBar);
        getMachineProcessor().endOperation(b);
    }

    /**
     * 「0 输入」发生器（造物匣 / 物质生成器）的产出。
     *
     * <p>这类机器配方 {@code input} 恒为空数组，无法交给 {@code CraftingOperation}
     * （它拒绝空配料）。此处按配方自身的周期 {@code ticks}（即 yml 的 {@code tickRate}）
     * 节流：取电成功后记录当前游戏刻，未到周期直接返回。</p>
     */
    private void produceWithoutInput(Block b, BlockMenu inv, TTRecipe r) {
        long now = Bukkit.getCurrentTick();
        long period = Math.max(1, r.getTicks());
        Long last = lastGen.get(b.getLocation());
        if (last != null && now - last < period) return;
        if (!takeCharge(b.getLocation())) return;
        lastGen.put(b.getLocation(), now);
        pushRecipeOutputs(b, inv, r);
    }

    /**
     * 指南里的配方展示（对齐 RSC {@code RecipesHolder#getDisplayRecipes}）。
     *
     * <p>RSC 的语义：
     * <ul>
     *   <li>输入只有 1 项且数量为 1 → 直接显示该物品；否则显示绿色玻璃板「多物品输入」按钮；</li>
     *   <li>输出只有 1 项 → 直接显示该物品（补耗时，概率 &lt;100 时再补概率）；
     *       否则显示绿色玻璃板「多物品输出」按钮；</li>
     *   <li>两种按钮都带 {@link #RECIPE_INDEX_KEY} 索引，点击时由监听器打开该配方详情。</li>
     * </ul>
     *
     * <p><b>为什么必须自己实现</b>：本类覆盖了 {@code registerDefaultRecipes()}（配方由自己管理），
     * AContainer 内置的配方表是空的 —— 沿用父类会让指南里<b>一条配方都不显示</b>。</p>
     */
    @Override
    public List<ItemStack> getDisplayRecipes() {
        List<ItemStack> out = new ArrayList<>();
        if (hideAll) return out;
        int i = 0;
        for (TTRecipe r : recipes) {
            ItemStack in = displayInput(r, i);
            if (in != null) out.add(in);
            ItemStack o = displayOutput(r, i);
            if (o != null) out.add(o);
            i++;
        }
        return out;
    }

    /** 输入展示物：0 输入（造物匣）返回 null；单项且数量 1 直接显示；否则给「多物品输入」按钮。 */
    private static ItemStack displayInput(TTRecipe r, int index) {
        ItemStack[] valid = validStacks(r.getInput());
        if (valid.length == 0) return null;
        if (valid.length == 1 && valid[0].getAmount() == 1) return valid[0].clone();
        return multiButton(MULTI_INPUT_NAME, index);
    }

    /** 输出展示物：单项直接显示（补耗时/概率 lore）；多项给「多物品输出」按钮。 */
    private static ItemStack displayOutput(TTRecipe r, int index) {
        ItemStack[] valid = validStacks(r.getOutput());
        if (valid.length == 0) return null;
        if (valid.length > 1) return multiButton(MULTI_OUTPUT_NAME, index);
        ItemStack out = valid[0].clone();
        int chance = r.outputChance(0);
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.add(legacy("§7耗时: §f" + (r.getTicks() / 2.0d) + " 秒"));
            if (chance < 100) lore.add(legacy("§a有§b " + chance + "% §a的概率产出"));
            meta.lore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 过滤掉 null / 空气的展示项。 */
    private static ItemStack[] validStacks(ItemStack[] arr) {
        if (arr == null) return new ItemStack[0];
        return Arrays.stream(arr)
                .filter(it -> it != null && !it.getType().isAir())
                .toArray(ItemStack[]::new);
    }

    /** 造一个「多物品输入 / 多物品输出」按钮：绿色染色玻璃板 + 配方索引 PDC。 */
    private static ItemStack multiButton(String name, int index) {
        ItemStack it = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            meta.lore(List.of(legacy(""), legacy(CLICK_HINT)));
            meta.getPersistentDataContainer().set(RECIPE_INDEX_KEY, PersistentDataType.INTEGER, index);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) { return matchRecipes(inv, recipes); }

    /** 在给定配方列表中匹配并消耗输入。 */
    protected MachineRecipe matchRecipes(BlockMenu inv, List<TTRecipe> recipeList) {
        Match m = findMatch(inv, recipeList);
        if (m == null) return null;
        consumeMatch(inv, m);
        return m.recipe;
    }

    /** 匹配结果：命中的配方 + 各输入项选中的输入槽位置。 */
    protected static final class Match {
        final TTRecipe recipe;
        final int[] chosen;
        Match(TTRecipe recipe, int[] chosen) { this.recipe = recipe; this.chosen = chosen; }
    }

    /** 仅匹配与校验，不消耗输入。 */
    protected Match findMatch(BlockMenu inv, List<TTRecipe> recipeList) {
        int[] slots = inputSlots;
        int slotCount = slots.length;
        // 优化：机器有输入槽但全空时，非空输入无从落槽，不可能命中任何配方，直接返回。
        // 注意：无输入槽的机器（造物匣等 0 输入发生器）必须放过本优化——它们的配方本身就是
        // 空输入（Recipes.readOutputs → new ItemStack[0]）。若不加 slotCount > 0 守卫，
        // 循环一次都不进、anyInput 恒 false，这 130 台造物匣将永不产出（实机：永不工作）。
        if (slotCount > 0) {
            boolean anyInput = false;
            for (int s = 0; s < slotCount; s++) {
                if (inv.getItemInSlot(slots[s]) != null) { anyInput = true; break; }
            }
            if (!anyInput) return null;
        }
        for (TTRecipe recipe : recipeList) {
            int[] chosen = matchOne(inv, recipe, null);
            if (chosen == null) continue;
            // 输出放不下时跳过本配方尝试下一个，不整体放弃
            if (!InvUtils.fitAll(inv.toInventory(), recipe.getOutput(), outputSlots)) continue;
            // tick 可能异步：消耗前对选中槽位的实时内容再校验，避免竞态吞错物品
            boolean stillValid = true;
            ItemStack[] inputs = recipe.getInput();
            for (int i = 0; i < chosen.length; i++) {
                if (recipe.isNoConsume(i) || chosen[i] < 0) continue;
                ItemStack live = inv.getItemInSlot(slots[chosen[i]]);
                if (!matches(live, inputs[i], recipe, i)) { stillValid = false; break; }
            }
            if (!stillValid) continue;
            return new Match(recipe, chosen);
        }
        return null;
    }

    /**
     * 单配方匹配核心：{@link #findMatch}（生产）与 {@link #explainNoMatch}（诊断）的
     * <b>唯一</b>实现，保证「诊断说通过」与「生产会命中」在结构上不可分歧
     * （TR_YTC/TR_HTY 案的教训：双实现必然漂移——先 isItemSimilar、再 wrapper、
     * 后 AIR 语义，每处分歧都是一次「诊断通过但机器不转」）。
     *
     * <p>输入槽一律读真实物品栈：BlockMenu 的活引用，只读不写，无需快照；
     * 不要包 ItemStackWrapper——运行时 Slimefun 构建（f172239）的 wrapper 丢 PDC。</p>
     *
     * @param trace 非空时把第一个失败环节写进去（诊断日志用）；生产路径传 null
     * @return 各输入选中的「输入槽位置下标」（无需求的输入为 -1）；失败返回 null
     */
    private int[] matchOne(BlockMenu inv, TTRecipe recipe, StringBuilder trace) {
        int[] slots = inputSlots;
        int slotCount = slots.length;
        ItemStack[] inputs = recipe.getInput();
        int n = inputs.length;
        int[] chosen = new int[n];
        int matched = 0;
        boolean[] claimed = new boolean[slotCount];
        for (int i = 0; i < n; i++) {
            chosen[i] = -1;
            ItemStack need = inputs[i];
            // null 与 AIR 都视为「无此输入要求」（旧 findMatch 对 AIR 需求会进入匹配
            // 并必然失败，而诊断把它当无要求跳过——曾是两处实现分歧之一）
            if (need == null || need.getType().isAir()) { matched++; continue; }
            int bound = recipe.inSlot(i);
            int found = -1;
            String why = null;
            if (bound >= 0) {
                int pos = (bound < posBySlot.length) ? posBySlot[bound] : -1;
                if (pos < 0) {
                    why = "绑定槽" + bound + "不在输入槽列表" + java.util.Arrays.toString(slots);
                } else if (claimed[pos]) {
                    why = "绑定槽" + bound + "（输入槽" + slots[pos] + "）已被前一输入占用";
                } else {
                    ItemStack in = inv.getItemInSlot(slots[pos]);
                    if (matches(in, need, recipe, i)) {
                        found = pos;
                    } else {
                        why = "绑定槽" + bound + "（输入槽" + slots[pos] + "）实际="
                                + describe(inv.getItemInSlot(slots[pos])) + " 但需" + describe(need);
                    }
                }
            } else {
                // best-fit：选余量最小的可用槽，把大堆留给需求量更大的输入项
                int best = -1;
                int bestAmount = Integer.MAX_VALUE;
                for (int s = 0; s < slotCount; s++) {
                    if (claimed[s]) continue;
                    ItemStack in = inv.getItemInSlot(slots[s]);
                    if (!matches(in, need, recipe, i)) continue;
                    if (in.getAmount() < bestAmount) {
                        best = s;
                        bestAmount = in.getAmount();
                        if (bestAmount == need.getAmount()) break;
                    }
                }
                if (best >= 0) {
                    found = best;
                } else {
                    StringBuilder w = new StringBuilder();
                    for (int s = 0; s < slotCount; s++) {
                        if (claimed[s]) continue;
                        if (w.length() > 0) w.append("; ");
                        w.append("槽").append(slots[s]).append('=')
                         .append(describe(inv.getItemInSlot(slots[s]))).append(" 但需").append(describe(need));
                    }
                    why = w.length() == 0 ? "输入槽全空或已被占用" : w.toString();
                }
            }
            if (found >= 0) {
                chosen[i] = found;
                claimed[found] = true;
                matched++;
                continue;
            }
            if (trace != null) {
                trace.append("输入").append(i + 1).append(" 需求").append(describe(need))
                     .append(bound >= 0 ? "[绑定槽" + bound + "]" : "")
                     .append(" 失败原因: ").append(why);
            }
            return null;
        }
        // 同一物理槽不允许被多个配方位消耗（总量足够也不共享），防止越量消耗吞物品。
        // 只校验真实占用（chosen >= 0）——null/AIR 需求的哨兵值 -1 不占槽、不参与去重。
        // 此前把 -1 计入 distinct，导致「真实输入数 < 输入槽数」的配方全部被拒
        // （超多方块 loader 曾把 inputSize 硬编码 9：TR_HTY 1 个真实输入 + 8 个 null
        //  → distinct=1 != 9 →「输入槽位被重复占用」——所有配方自诞生起匹配失败）。
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int c : chosen) {
            if (c >= 0 && !seen.add(c)) {
                if (trace != null) trace.append("输入槽位被重复占用");
                return null;
            }
        }
        return chosen;
    }

    /**
     * 单项输入比较：数量足够 + 类型匹配；工具类（damage&gt;0）忽略耐久。
     * 不用 {@code SlimefunUtils.isItemSimilar}——实测它对本机器匹配链路恒 false
     * （TR_YTC/TR_HTY「有输入但无配方命中」案：交叉诊断按原生 id 比对全部通过，
     * isItemSimilar 仍拒绝，配方永远无法开工），改用下方 itemsMatch 的原生比对。
     */
    private static boolean matches(ItemStack in, ItemStack need, TTRecipe recipe, int i) {
        if (in == null || in.getAmount() < need.getAmount()) return false;
        if (recipe.inputDamage(i) > 0) return toolSimilarIgnoreDamage(in, need);
        return itemsMatch(in, need);
    }

    /**
     * 原生比对：粘液物品按 id 一致即匹配（数量充足由调用方保证），原版物品按材质 +
     * 展示名/lore。与 explainNoMatch 的交叉诊断用的是同一套规则，因此诊断说
     * 「原生比对通过」即代表本方法放行。
     */
    private static boolean itemsMatch(ItemStack in, ItemStack need) {
        if (in.getType() != need.getType()) return false;
        SlimefunItem sfNeed = SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            SlimefunItem sfIn = SlimefunItem.getByItem(in);
            return sfIn != null && sfNeed.getId().equals(sfIn.getId());
        }
        // 原版物品：需求带展示名/lore 时要求一致（覆盖药水/附魔书/染色物品等带状态输入）
        if (need.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta nm = need.getItemMeta();
            org.bukkit.inventory.meta.ItemMeta im = in.getItemMeta();
            if (im == null) return !nm.hasDisplayName() && !nm.hasLore();
            if (nm.hasDisplayName() != im.hasDisplayName()) return false;
            if (nm.hasDisplayName() && !nm.getDisplayName().equals(im.getDisplayName())) return false;
            if (nm.hasLore() != im.hasLore()) return false;
            if (nm.hasLore() && !nm.getLore().equals(im.getLore())) return false;
        }
        return true;
    }

    /** 耐久类工具输入比较：忽略损耗值；粘液物品按 id 一致。 */
    private static boolean toolSimilarIgnoreDamage(ItemStack in, ItemStack need) {
        if (in == null || in.getType() != need.getType()) return false;
        SlimefunItem sfNeed = SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            SlimefunItem sfIn = SlimefunItem.getByItem(in);
            return sfIn != null && sfIn.getId().equals(sfNeed.getId());
        }
        if (need.hasItemMeta() && need.getItemMeta().hasDisplayName()) {
            org.bukkit.inventory.meta.ItemMeta m = in.getItemMeta();
            return m != null && m.hasDisplayName()
                    && m.getDisplayName().equals(need.getItemMeta().getDisplayName());
        }
        return true;
    }

    /** 消耗已匹配配方的输入（跳过 noConsume；damage>0 走耐久扣点）。 */
    protected void consumeMatch(BlockMenu inv, Match m) {
        if (m == null) return;
        ItemStack[] inputs = m.recipe.getInput();
        for (int i = 0; i < inputs.length; i++) {
            if (m.recipe.isNoConsume(i) || m.chosen[i] < 0) continue;
            int damage = m.recipe.inputDamage(i);
            int slot = inputSlots[m.chosen[i]];
            if (damage > 0) damageTool(inv, slot, damage);
            else inv.consumeItem(slot, inputs[i].getAmount());
        }
    }

    /**
     * 加载收尾统一清理：剔除「引用了被禁用/未加载物品」的配方。
     *
     * <p>yml 里的配方可以互相引用，若某物品因条件未满足而未注册，直接按引用取物品会拿 null，
     * 运行期匹配时空指针会把整台机器拖死。这里统一在下发之前剔除。</p>
     */
    public static void filterDisabledRecipes() {
        int removed = 0;
        for (TTRecipeMachine m : INSTANCES) {
            if (m.recipes == null) continue;
            int before = m.recipes.size();
            m.recipes.removeIf(TTRecipe::referencesDisabledItem);
            removed += before - m.recipes.size();
        }
        if (removed > 0) {
            TT.log("已剔除 " + removed + " 条引用禁用/缺失物品的机器配方");
        }
    }

    private static void damageTool(BlockMenu inv, int slot, int damage) {
        ItemStack item = inv.getItemInSlot(slot);
        if (item == null || item.getType().isAir()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable damageable)
                || item.getType().getMaxDurability() <= 0 || meta.isUnbreakable()) {
            return;
        }
        int newDamage = damageable.getDamage() + damage;
        if (newDamage >= item.getType().getMaxDurability()) {
            inv.replaceExistingItem(slot, null);
            return;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = item.clone();
        updated.setItemMeta(damageable);
        inv.replaceExistingItem(slot, updated);
    }
}
