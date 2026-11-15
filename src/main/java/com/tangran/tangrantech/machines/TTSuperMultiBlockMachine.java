package com.tangran.tangrantech.machines;

import com.tangran.tangrantech.load.Read;
import com.tangran.tangrantech.machines.superblock.SuperStructure;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/**
 * 超多方块机器（super_multi_block_machines.yml）。
 *
 * <p>结构为「多层 × 多行 × 多列」的字符矩阵，字符经 {@code mapping} 映射到材质/粘液物品，
 * 其中 {@code core: true} 者为机器核心。机器只有在结构完整成形时才工作。</p>
 *
 * <p>本实现对齐 RSC {@code CustomSuperMultiBlockMachine} 的可观察行为：
 * 逐格比对结构，成形后按 {@code recipes} 消耗输入、产出输出，并按 {@code speed} 缩放耗时。</p>
 */
public class TTSuperMultiBlockMachine extends TTRecipeMachine {

    /** RSC 行为开关（super_multi_block_machines.yml 的六个布尔字段，默认值对齐 RSC 读取器）。 */
    public record SuperFlags(boolean displayProjectiles, boolean checkFormed,
                             boolean openMenuWhenClickedParts, boolean noMenuWhenNotFormed,
                             boolean allowSwitchDisplayLayer, boolean defaultNotice) {}

    /** 结构：层 → 行 → 列 的字符 token。 */
    private final List<List<List<String>>> structure;
    /** 字符 → 期望方块；null 表示通配（structure 里的 "__"）。 */
    private final java.util.Map<String, Expect> mapping;
    /** 核心相对位置：{层偏移, 行偏移, 列偏移}。 */
    private final int[] coreOffset;
    /** RSC 行为开关。 */
    private final SuperFlags flags;
    /**
     * 口木同款「结构构造器」：由上面三份 yml 数据在注册期一次性编译出的坐标表。
     * 结构判定 / 缺块收集 / 投影建档全部只读它，字符矩阵本身不再参与运行期比对。
     */
    private final SuperStructure structureModel;

    public TTSuperMultiBlockMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                                    int[] input, int[] output, List<TTRecipe> recipes,
                    int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                    List<List<List<String>>> structure, java.util.Map<String, Expect> mapping,
                    int[] coreOffset, SuperFlags flags) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        this.structure = structure;
        this.mapping = mapping;
        this.coreOffset = coreOffset;
        this.flags = flags;
        this.structureModel = SuperStructure.compile(structure, mapping, coreOffset);
        // 放置核心时记录玩家朝向（对齐 RSC BlockPlaceHandler + setHorizonDirection），
        // 结构匹配按该朝向旋转（SuperBlocks.onCorePlaced 持久化，重启不丢）
        addItemHandler(new io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(org.bukkit.event.block.BlockPlaceEvent e) {
                com.tangran.tangrantech.machines.superblock.SuperBlocks.onCorePlaced(
                        e.getBlock().getLocation(),
                        com.tangran.tangrantech.machines.superblock.SuperBlocks.Dir.fromYaw(
                                e.getPlayer().getLocation().getYaw()));
            }
        });
    }

    /** RSC 行为开关。 */
    public SuperFlags flags() { return flags; }

    /** 超多方块机器打开运行期诊断（排查成形/匹配/电力）。 */
    @Override
    protected boolean diagEnabled() { return true; }

    /** 结构（层/行/列字符 token）。 */
    public List<List<List<String>>> getStructure() { return structure; }

    /** 字符映射表。 */
    public java.util.Map<String, Expect> getMapping() { return mapping; }

    /** 核心在结构中的相对偏移 {层, 行, 列}。 */
    public int[] getCoreOffset() { return coreOffset; }

    /** 编译后的坐标表构造器（口木同款模型）；运行期所有结构判定都走它。 */
    public SuperStructure structureModel() { return structureModel; }

    /**
     * 结构不完整就暂停生产：不扣电、不推进、不出货。
     *
     * <p>{@link #isFormed(Block)} 早年<b>零调用方</b>——yml 里四台机器都写着
     * {@code checkFormed: true}，loader 却不读这个标记，isFormed 也没人调，
     * 结果机器退化成普通配方机：结构拆了照样按菜单里的输入继续生产。
     * 现在每次 tick 前校验一次（{@code checkFormed} 语义对全部四台都是 true，
     * 无需按条目区分）。</p>
     *
     * <p>进行中的操作选择「暂停」而非「作废」：输入在开机时就已消耗，
     * 此时 {@code endOperation} 会让这批输入凭空蒸发；结构修好后自然续跑。</p>
     */
    /**
     * 生产前先走超多方块管理器（首刻建档 / 500 tick 自愈），成形与否以管理器缓存的
     * 逐格状态为准（dirty 事件驱动，结构被拆即刻停机）。
     *
     * <p>历史：本方法早年曾<b>每 tick 全结构扫描</b>（浑天仪约六百格 × 每刻 × 每台），
     * 现改为 {@link com.tangran.tangrantech.machines.superblock.SuperBlocks} 的缓存判定，
     * 语义不变、开销降为 O(1)。</p>
     */
    @Override
    protected void tick(Block b) {
        com.tangran.tangrantech.machines.superblock.SuperBlocks.onMachineTick(this, b);
        if (flags.checkFormed() && !com.tangran.tangrantech.machines.superblock.SuperBlocks.isFormed(b.getLocation())) {
            return;
        }
        super.tick(b);
    }

    /**
     * 多方块核心<b>不参与</b>「逻辑工艺」堆叠机对接。
     *
     * <p>堆叠机的工作方式是「把一台机器原地复制成 N 台并行」——它只认
     * 「输入槽 + 配方 + 每 tick 耗电」，完全不知道结构成形、核心朝向、投影与
     * {@code SuperBlocks} 的成形缓存。若把核心机器登记进去，玩家可以在核心未成形
     * 的情況下用堆叠机凭空产出（结构校验被完全绕过），或让 {@code SuperBlocks}
     * 里那份「一处成形状态」被 N 份并行产出共用而错乱。</p>
     *
     * <p>因此这里返回 {@code false}：{@code getMachineRecipes()} 会给逻辑工艺
     * 返回空列表，既不会进入堆叠表，也不会被其自动扫描收编。</p>
     */
    @Override
    public boolean supportsLogiTechStacking() {
        return false;
    }

    /**
     * 核心被破坏：先销毁超多方块实例与投影（对齐 RSC destroySuperMultiBlock），
     * 再走基类的掉落/停机清理。
     */
    @Override
    protected io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler onBlockBreak() {
        io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler base =
                (io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler) super.onBlockBreak();
        return new io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                com.tangran.tangrantech.machines.superblock.SuperBlocks.destroyAt(b.getLocation());
                base.onBlockBreak(b);
            }
        };
    }

    /**
     * 判定以 {@code core} 为核心的结构是否完整成形。
     *
     * <p>现在委托 {@link SuperStructure#isFormed}：坐标表在注册期编译一次，
     * 比对规则仍是 {@link #matchesBlock}（粘液按 id、原版按材质、液体含 waterlogged），
     * 但<b>会带上核心持久化朝向</b>旋转 —— 旧实现这里固定 NORTH，与
     * {@code SuperBlocks} 的方向感知判定口径不一致，斜着搭的机器会出现
     * 「管理器说成形、本方法说没成形」的分歧。</p>
     */
    public boolean isFormed(Block core) {
        if (structureModel == null || structureModel.isEmpty()) return false;
        Location loc = core.getLocation();
        return structureModel.isFormed(loc, SuperStructure.dirOf(loc));
    }

    /**
     * 结构格的期望：原版方块按材质比对（材质可以不是物品，如水），粘液方块按 id 比对。
     *
     * <p>以前这里直接放 ItemStack，而 Paper 的 ItemStack 构造器拒绝非物品材质
     * （mapping 里的 {@code a6: water} 抛 "WATER isn't an item"，整台机器注册失败）——
     * 结构比对本来只需要材质与粘液 id，不需要一个真实存在的物品。</p>
     */
    public record Expect(Material material, String sfId) {}

    /** 单格比对：粘液物品按 id，原版按材质（SuperBlocks 逐格检测复用同一规则）。 */
    public static boolean matchesBlock(Block actual, Expect expect) {
        if (expect.sfId() != null) {
            String got = com.tangran.tangrantech.native_scripts.SlimefunBridge.blockId(actual);
            return expect.sfId().equalsIgnoreCase(got);
        }
        Material want = expect.material();
        // 液体格（结构里的 a6: material: water）：要的是「这个位置有水」，不是「放了水方块物品」。
        // 水源与流动水都算（浇筑时先落下的往往是流动水），含水方块（水logged）同样算有水。
        if (want == Material.WATER || want == Material.LAVA) {
            return isLiquidAt(actual, want);
        }
        return actual.getType() == want;
    }

    /** 该格是否含期望的液体。 */
    private static boolean isLiquidAt(Block block, Material want) {
        if (block == null) return false;
        if (block.getType() == want) return true;
        if (want == Material.WATER) {
            try {
                return block.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl
                        && wl.isWaterlogged();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * 一个结构格的现状快照。供「大型结构填充棒」统计缺失与补齐。
     *
     * @param block        该格的实际方块
     * @param wantMaterial 期望摆成的材质（补齐时用这个 setType）
     * @param expectSfId   期望的粘液 id；null 表示期望的是原版方块
     * @param ok           当前是否已符合
     */
    public record Cell(Block block, Material wantMaterial, String expectSfId, boolean ok) {}

    /**
     * 扫一遍结构，逐格给出「期望物品 + 现状」。
     *
     * <p>委托 {@link SuperStructure#scanCells}：与 {@link #isFormed(Block)} 用同一张坐标表、
     * 同一朝向与同一比对规则，区别只是不早退。大型结构填充棒仍拿到原来的
     * {@link Cell} 形状（四个字段完全不变），因此那边无需改动。</p>
     */
    public java.util.List<Cell> scanCells(Block core) {
        java.util.List<Cell> out = new ArrayList<>();
        if (structureModel == null) return out;
        Location loc = core.getLocation();
        for (SuperStructure.Cell c : structureModel.scanCells(loc, SuperStructure.dirOf(loc))) {
            out.add(new Cell(c.location().getBlock(), c.wantMaterial(), c.expectSfId(), c.ok()));
        }
        return out;
    }

    /** 取展示物品可能携带的粘液 id（PDC）。 */
    private static String slipId(ItemStack it) {
        return com.tangran.tangrantech.native_scripts.SlimefunBridge.itemId(it);
    }

    /**
     * 解析结构段。yml 中 {@code structure} 是「层 → 行 → 空格分隔的列 token」的嵌套列表，
     * 因此用 {@code getList} 读取而非 {@code getConfigurationSection}（列表不是 section）。
     */
    @SuppressWarnings("unchecked")
    public static List<List<List<String>>> parseStructure(ConfigurationSection section) {
        List<List<List<String>>> out = new ArrayList<>();
        if (section == null) return out;
        Object raw = section.get("structure");
        if (!(raw instanceof List<?> layers)) return out;
        for (Object layerObj : layers) {
            List<List<String>> rows = new ArrayList<>();
            if (layerObj instanceof List<?> layerRows) {
                for (Object rowObj : layerRows) {
                    List<String> cols = new ArrayList<>();
                    String row = String.valueOf(rowObj);
                    for (String token : row.trim().split("\\s+")) {
                        if (!token.isEmpty()) cols.add(token);
                    }
                    rows.add(cols);
                }
            }
            out.add(rows);
        }
        return out;
    }

    /**
     * 解析 mapping 段：字符 → 期望方块；并定位核心偏移。
     *
     * <p>原版条目直接按材质解析——mapping 里可以有「非物品」的纯方块（如水），
     * 走 {@link Read#item} 会抛 "WATER isn't an item"；粘液条目仍按物品解析取 id。</p>
     */
    @SuppressWarnings("unchecked")
    public static MappingResult parseMapping(ConfigurationSection mappingSec,
                                              List<List<List<String>>> structure) {
        java.util.Map<String, Expect> map = new java.util.HashMap<>();
        int[] core = new int[] {0, 0, 0};
        if (mappingSec != null) {
            for (String ch : mappingSec.getKeys(false)) {
                ConfigurationSection itemSec = mappingSec.getConfigurationSection(ch);
                if (itemSec == null) continue;
                if ("slimefun".equalsIgnoreCase(itemSec.getString("material_type", "mc"))) {
                    ItemStack it = Read.item(itemSec, false);
                    if (it != null) map.put(ch, new Expect(it.getType(), slipId(it)));
                } else {
                    Material m = Material.matchMaterial(itemSec.getString("material", ""));
                    if (m != null) map.put(ch, new Expect(m, null));
                }
                if (itemSec.getBoolean("core", false) && structure != null) {
                    int[] found = findToken(structure, ch);
                    if (found != null) core = found;
                }
            }
        }
        return new MappingResult(map, core);
    }

    /** 在结构中查找某 token 首次出现的位置，返回 {层,行,列}。 */
    private static int[] findToken(List<List<List<String>>> structure, String token) {
        for (int l = 0; l < structure.size(); l++) {
            List<List<String>> rows = structure.get(l);
            if (rows == null) continue;
            for (int r = 0; r < rows.size(); r++) {
                List<String> cols = rows.get(r);
                if (cols == null) continue;
                for (int c = 0; c < cols.size(); c++) {
                    if (token.equals(cols.get(c))) return new int[] {l, r, c};
                }
            }
        }
        return null;
    }

    /** mapping 解析结果：字符映射表 + 核心偏移。 */
    public static final class MappingResult {
        public final java.util.Map<String, Expect> map;
        /** {层, 行, 列} */
        public final int[] core;
        MappingResult(java.util.Map<String, Expect> map, int[] core) {
            this.map = map;
            this.core = core;
        }
    }
}
