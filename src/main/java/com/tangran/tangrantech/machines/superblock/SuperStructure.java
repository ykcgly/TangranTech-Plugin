package com.tangran.tangrantech.machines.superblock;

import com.tangran.tangrantech.machines.TTSuperMultiBlockMachine;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 超多方块的「结构构造器」——口木科技同款坐标表模型。
 *
 * <p>yml 里的 {@code structure} 仍是 RSC 的「层 → 行 → 列字符矩阵」+ {@code mapping}，
 * <b>配置格式与物品 ID 完全不变</b>；本类在机器注册时把它<b>一次性编译</b>成
 * 「相对核心的坐标表」{@link StructureBlock}{@code (dx, dy, dz, material, sfId)}，
 * 后续所有结构判定 / 缺块收集 / 投影建档都只读这张表，不再各自重复解析字符矩阵。</p>
 *
 * <p>对齐口木 {@code JianLingShiSupport} 的三件套：
 * {@link #checkStructure}（全表检查，首错即返）、{@link #collectMissing}
 * （全量缺块，世界绝对坐标）、{@link #rot} 风格的四向旋转。
 * 旋转直接复用 {@link SuperBlocks.Dir#rx}/{@link #rz} —— 两边公式已逐项核对一致
 * （EAST(-z,x) / SOUTH(-x,-z) / WEST(z,-x)），无需另造一套。</p>
 *
 * <p>两个与口木的差异是有意保留的糖然语义：</p>
 * <ol>
 *   <li><b>比对规则走 {@link TTSuperMultiBlockMachine#matchesBlock}</b>：粘液格按 id、
 *       原版按材质，且支持水/岩浆（含水方块 waterlogged 也算有水）——口木只比
 *       {@code block.getType().name()}，直接照搬会让 yml 里的 {@code a6: water} 永远判缺失；</li>
 *   <li><b>坐标表含核心格 {@code (0,0,0)}</b>（与口木 {@code buildStructure()} 一致），
 *       但 {@link #cells} 供 {@link SuperBlocks} 建档时会排除核心，保持 RSC
 *       {@code blockParts.remove(corePos)} 的原有行为。</li>
 * </ol>
 */
public final class SuperStructure {

    /** 相对核心（基准朝向 NORTH）的一格结构；material 为期望材质，sfId 非空则是粘液物品。 */
    public record StructureBlock(int x, int y, int z, Material material, String sfId) {
        public TTSuperMultiBlockMachine.Expect expect() {
            return new TTSuperMultiBlockMachine.Expect(material, sfId);
        }

        public String label() {
            return sfId != null && !sfId.isEmpty() ? sfId : material.name();
        }
    }

    /** 一处缺失：世界绝对坐标 + 需要摆成的材质 + 需要的粘液 id（可为 null）。 */
    public record MissingBlock(Location location, Material material, String sfId) {}

    /** 结构检查里已解析的一格（世界绝对坐标）。 */
    public record ResolvedBlock(int x, int y, int z, Material material, String sfId) {}

    /** 口木同款检查结果：valid + 错误清单（首错即返时只有一条）+ 已解析格。 */
    public record StructureCheck(boolean valid, List<String> errors, List<ResolvedBlock> blocks) {}

    /** 一格的现状快照（供「大型结构填充棒」统计与补齐）。 */
    public record Cell(Location location, Material wantMaterial, String expectSfId, boolean ok) {}

    private final List<StructureBlock> blocks;

    private SuperStructure(List<StructureBlock> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    /**
     * 把 yml 的字符矩阵编译成坐标表。
     *
     * <p>坐标公式与旧 {@code SuperBlocks.buildInst} / {@code TTSuperMultiBlockMachine.isFormed}
     * 逐字一致：{@code dx = c - coreOffset[2]}、{@code dy = coreOffset[0] - l}、
     * {@code dz = r - coreOffset[1]}。空 token（{@code "__"}）与 AIR 不进表 —— 它们表示
     * 「这格不限材质」，本来就不参与比对。</p>
     */
    public static SuperStructure compile(List<List<List<String>>> structure,
                                         Map<String, TTSuperMultiBlockMachine.Expect> mapping,
                                         int[] coreOffset) {
        List<StructureBlock> out = new ArrayList<>();
        if (structure == null || mapping == null || coreOffset == null || coreOffset.length < 3) {
            return new SuperStructure(out);
        }
        for (int l = 0; l < structure.size(); l++) {
            List<List<String>> rows = structure.get(l);
            if (rows == null) continue;
            for (int r = 0; r < rows.size(); r++) {
                List<String> cols = rows.get(r);
                if (cols == null) continue;
                for (int c = 0; c < cols.size(); c++) {
                    TTSuperMultiBlockMachine.Expect expect = mapping.get(cols.get(c));
                    if (expect == null || expect.material() == null || expect.material().isAir()) continue;
                    out.add(new StructureBlock(
                            c - coreOffset[2],
                            coreOffset[0] - l,
                            r - coreOffset[1],
                            expect.material(), expect.sfId()));
                }
            }
        }
        return new SuperStructure(out);
    }

    /** 基准朝向下的坐标表（不可变，含核心格）。 */
    public List<StructureBlock> blocks() { return blocks; }

    public boolean isEmpty() { return blocks.isEmpty(); }

    private static boolean isCore(StructureBlock b) {
        return b.x() == 0 && b.y() == 0 && b.z() == 0;
    }

    /** 核心放置时持久化的朝向；读不到或损坏时退化为 NORTH。 */
    public static SuperBlocks.Dir dirOf(Location core) {
        try {
            String s = StorageCacheUtils.getData(core, "TTHorizonDirection");
            return (s == null || s.isEmpty()) ? SuperBlocks.Dir.NORTH : SuperBlocks.Dir.valueOf(s);
        } catch (Throwable t) {
            return SuperBlocks.Dir.NORTH;
        }
    }

    /** 把一格旋到指定朝向并换成世界绝对坐标。 */
    private static Location at(Location core, StructureBlock b, SuperBlocks.Dir dir) {
        return new Location(core.getWorld(),
                core.getBlockX() + dir.rx(b.x(), b.y(), b.z()),
                core.getBlockY() + b.y(),
                core.getBlockZ() + dir.rz(b.x(), b.y(), b.z()));
    }

    /** 旋转后的结构格（排除核心），供 {@link SuperBlocks} 建档。 */
    public Map<SuperBlocks.Off, TTSuperMultiBlockMachine.Expect> cells(SuperBlocks.Dir dir) {
        Map<SuperBlocks.Off, TTSuperMultiBlockMachine.Expect> map = new LinkedHashMap<>();
        for (StructureBlock b : blocks) {
            if (isCore(b)) continue;   // 对齐 RSC blockParts.remove(corePos)
            map.put(new SuperBlocks.Off(dir.rx(b.x(), b.y(), b.z()), b.y(),
                            dir.rz(b.x(), b.y(), b.z())),
                    b.expect());
        }
        return map;
    }

    /** 该格当前是否符合期望。 */
    private static boolean matches(Location loc, StructureBlock b) {
        return TTSuperMultiBlockMachine.matchesBlock(loc.getBlock(), b.expect());
    }

    /** 口木同款：全表检查，首错即返（保留已解析格清单）。 */
    public StructureCheck checkStructure(Location core, SuperBlocks.Dir dir) {
        List<String> errors = new ArrayList<>();
        List<ResolvedBlock> resolved = new ArrayList<>();
        World w = core.getWorld();
        if (w == null) return new StructureCheck(false, List.of("核心所在世界不可用"), resolved);
        for (StructureBlock b : blocks) {
            Location loc = at(core, b, dir);
            resolved.add(new ResolvedBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    b.material(), b.sfId()));
            if (!matches(loc, b)) {
                errors.add("位置 (" + (loc.getBlockX() - core.getBlockX()) + ","
                        + (loc.getBlockY() - core.getBlockY()) + ","
                        + (loc.getBlockZ() - core.getBlockZ()) + ") 需要 " + b.label());
                return new StructureCheck(false, errors, resolved);
            }
        }
        return new StructureCheck(true, errors, resolved);
    }

    /** 口木同款：收集全部缺失 / 放错的格（世界绝对坐标，已旋转）。 */
    public List<MissingBlock> collectMissing(Location core, SuperBlocks.Dir dir) {
        List<MissingBlock> out = new ArrayList<>();
        if (core.getWorld() == null) return out;
        for (StructureBlock b : blocks) {
            Location loc = at(core, b, dir);
            if (!matches(loc, b)) out.add(new MissingBlock(loc, b.material(), b.sfId()));
        }
        return out;
    }

    /** 逐格快照，不早退（供大型结构填充棒统计缺失并补齐）。 */
    public List<Cell> scanCells(Location core, SuperBlocks.Dir dir) {
        List<Cell> out = new ArrayList<>();
        if (core.getWorld() == null) return out;
        for (StructureBlock b : blocks) {
            Location loc = at(core, b, dir);
            out.add(new Cell(loc, b.material(), b.sfId(), matches(loc, b)));
        }
        return out;
    }

    /** 结构是否完整成形（含朝向旋转）。 */
    public boolean isFormed(Location core, SuperBlocks.Dir dir) {
        if (blocks.isEmpty() || core.getWorld() == null) return false;
        for (StructureBlock b : blocks) {
            if (!matches(at(core, b, dir), b)) return false;
        }
        return true;
    }

}
