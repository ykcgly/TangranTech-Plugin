package com.tangran.tangrantech.compat;

import com.tangran.tangrantech.TT;
import com.tangran.tangrantech.items.ScriptMachineItem;
import com.tangran.tangrantech.machines.TTCustomMachineRecipe;
import com.tangran.tangrantech.machines.TTRecipe;
import com.tangran.tangrantech.machines.TTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 与「逻辑工艺（LogiTech）」堆叠机的对接层（<b>只改本附属自身，不碰 LogiTech 源码</b>）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>LogiTech 在 {@code RecipeSupporter} 的静态初始化里扫描全部已注册机器，
 * 对 {@code AContainer} 直接调 {@code getMachineRecipes()} 收集配方；而糖然科技的
 * {@link TTRecipeMachine} 把 {@code registerDefaultRecipes()} 覆盖成了空实现
 * （配方由自己管理），于是 LogiTech 读到的是<b>空列表</b>；空列表既满足
 * {@code isGeneratorRecipe} 也满足 {@code isMachineRecipe}，但随后
 * {@code if (!recipes.isEmpty())} 直接跳过入库 —— 结果是
 * <b>糖然科技所有机器在堆叠机里完全不可见</b>。</p>
 *
 * <h2>本层的两条通道（对齐 LogiTech 自己的转换管线）</h2>
 * <ol>
 *   <li><b>生成机器</b>（造物匣：配方无输入、产出全 100%、非 chooseOne）：
 *       构造 LogiTech 的 {@code MGeneratorRecipe(ticks-1, 空输入, 产出)} →
 *       其 {@code isGeneratorRecipe} 判定为真 → 进 {@code STACKMGENERATOR_LIST}。</li>
 *   <li><b>配方机器</b>（有真实输入、或带概率 / chooseOne / noConsume）：
 *       先转 {@link TTCustomMachineRecipe}（类名以 {@code CustomMachineRecipe} 结尾），
 *       再调用 LogiTech 自己的 {@code transferRSCRecipes} → {@code stackFromMachine}，
 *       <b>概率产出与 noConsume 语义原样保留</b> → 进 {@code STACKMACHINE_LIST}。</li>
 * </ol>
 *
 * <h2>五条纪律</h2>
 * <ul>
 *   <li><b>只追加、不重排</b>：绝不从 LogiTech 的表里 {@code remove} 任何条目
 *       （删除会让后续条目下标前移，老存档里 {@code craftType} 会串机）。</li>
 *   <li><b>耗电按 tickRate 均摊</b>：本附属机器 {@code takeCharge} 是「每次操作扣一次」，
 *       而 LogiTech 是「每 tick × 份数」；直接透传会高估 {@code ticks} 倍，
 *       因此写入 {@code ceil(energy / ticks)}（同键 put 不改 {@code LinkedHashMap} 顺序）。</li>
 *   <li><b>未装 LogiTech 全静默</b>：反射探测失败即跳过，不产生任何硬依赖。</li>
 *   <li><b>只对接「配方可脱离方块存在」的机器</b>：脚本机器（{@code ScriptMachineItem}）
 *       与多方块核心机器（{@code TTSuperMultiBlockMachine}）一律不登记——
 *       前者产能来自 NativeScript 的 tick 钩子、后者依赖结构成形与核心朝向，
 *       堆叠机只认「输入槽 + 配方 + 耗电」，登进去等于绕过玩法约束。</li>
 *   <li><b>绝不提前初始化对方的类</b>（v2.1.3 教训）：对方的配方供应器把重活放在静态块里，
 *       靠「第 1 tick 才首次 touch」来保证「全体附属注册完毕之后才快照」。只要我们在
 *       {@code onEnable} 里 {@code Class.forName} 一次（默认 {@code initialize=true}），
 *       扫描就会提前到「其他附属还没注册」的时点，<b>晚于本插件 enable 的附属配方会整批缺席，
 *       而且第 1 tick 的 {@code init()} 是空方法，永远补不回来</b>。
 *       因此探测一律用 {@code Class.forName(name, false, loader)}，真正读表只在
 *       {@link #postRegisterDone()} 为真之后。</li>
 * </ul>
 */
public final class LogiTechStackBridge {

    private static final String RS = "me.matl114.logitech.core.Registries.RecipeSupporter";
    private static final String MRU = "me.matl114.logitech.utils.MachineRecipeUtils";
    private static final String MGEN = "me.matl114.logitech.utils.UtilClass.RecipeClass.MGeneratorRecipe";
    private static final String PST = "me.matl114.logitech.manager.PostSetupTasks";

    private static boolean probed;
    private static boolean available;

    /** {@link #classPresent()} 的缓存（未装逻辑工艺时只探测一次）。 */
    private static boolean classChecked;
    private static boolean classExists;

    /** 逻辑工艺 post-register 标志的解析状态（{@link #postRegisterDone()}）。 */
    private static boolean pstResolved;
    private static boolean pstMissing;
    private static Field fPostRegister;

    private static Field fRecipeList;      // RecipeSupporter.MACHINE_RECIPELIST
    private static Field fMachineList;     // RecipeSupporter.STACKMACHINE_LIST
    private static Field fGeneratorList;   // RecipeSupporter.STACKMGENERATOR_LIST
    private static Constructor<?> genCtor; // MGeneratorRecipe(int, ItemStack[], ItemStack[])
    private static Class<?> genClass;      // MGeneratorRecipe
    private static Method mTransfer;       // RecipeSupporter.transferRSCRecipes(MachineRecipe)
    private static Method mTryGen;         // MachineRecipeUtils.tryGenerateMGFromMachine(MachineRecipe)
    private static Method mStackFrom;      // MachineRecipeUtils.stackFromMachine(MachineRecipe)

    private LogiTechStackBridge() {}

    /** 逻辑工艺是否可用（首次探测后缓存；硬失败不重试）。 */
    public static boolean available() {
        if (!probed) {
            synchronized (LogiTechStackBridge.class) {
                if (!probed) probed = resolve();
            }
        }
        return available;
    }

    /** 探测已完成、且对方已进入 post-register 阶段——只有此时才允许把转换结果写进缓存。 */
    public static boolean ready() {
        return available() && postRegisterDone();
    }

    /**
     * 逻辑工艺是否已进入它自己的 post-register 阶段（= 配方供应器扫描的起点）。
     *
     * <p>对方 {@code PostSetupTasks.schedulePostRegister()} 是「<b>先把
     * {@code startPostRegister} 置 true，再调 {@code RecipeSupporter.init()} 触发静态块扫描」，
     * 所以这个标志为真，就说明扫描已经开始或已经结束，此刻碰它的配方表不会再打乱时序。</p>
     *
     * <p>只读 {@code PostSetupTasks} 的布尔字段：该类的静态初始化只有一个 {@code HashSet}
     * 与两个 boolean，<b>不会</b>连带触发 {@code RecipeSupporter} 的静态块。</p>
     */
    public static boolean postRegisterDone() {
        if (!pstResolved) {
            try {
                Class<?> c = Class.forName(PST, false, LogiTechStackBridge.class.getClassLoader());
                fPostRegister = c.getField("startPostRegister");
            } catch (Throwable t) {
                pstMissing = true;
            }
            pstResolved = true;
        }
        if (pstMissing) return false;
        try {
            return Boolean.TRUE.equals(fPostRegister.get(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 对方的 post-register 标志是否拿不到（版本字段名差异）——此时只能按时间兜底。 */
    public static boolean postRegisterFlagMissing() {
        postRegisterDone();
        return pstMissing;
    }

    /** 只判对方的类是否存在，<b>不</b>触发它初始化（{@code initialize=false}）；结果缓存一次。 */
    public static boolean classPresent() {
        if (!classChecked) {
            try {
                Class.forName(RS, false, LogiTechStackBridge.class.getClassLoader());
                classExists = true;
            } catch (Throwable t) {
                classExists = false;
            }
            classChecked = true;
        }
        return classExists;
    }

    /**
     * 反射探测。
     *
     * <p>两道保险：① 没有 {@link #classPresent()} 就返回 {@code false}，绝不 throw；
     * ② <b>对方还没进入 post-register 阶段时一律不探</b>（返回 {@code false} 且不写
     * {@code probed}）—— {@code Class.forName(..)} 默认会初始化类，提前初始化会把对方
     * 「等全体附属注册完再扫描」的快照时机整体打乱。</p>
     *
     * @return {@code true} 表示「已有结论」（成功，或类压根不存在这类硬失败，不必重试）；
     *         {@code false} 表示「暂时还没有结论」（对方未到 post-register、或静态表尚未就绪），
     *         下次调用重新探测。
     */
    private static boolean resolve() {
        if (!postRegisterDone()) return false;
        ClassLoader cl = LogiTechStackBridge.class.getClassLoader();
        try {
            Class<?> rs = Class.forName(RS, false, cl);
            Class<?> mru = Class.forName(MRU, false, cl);
            genClass = Class.forName(MGEN, false, cl);
            genCtor = genClass.getConstructor(int.class, ItemStack[].class, ItemStack[].class);
            fRecipeList = rs.getField("MACHINE_RECIPELIST");
            fMachineList = rs.getField("STACKMACHINE_LIST");
            fGeneratorList = rs.getField("STACKMGENERATOR_LIST");
            mTransfer = rs.getMethod("transferRSCRecipes", MachineRecipe.class);
            mTryGen = mru.getMethod("tryGenerateMGFromMachine", MachineRecipe.class);
            mStackFrom = mru.getMethod("stackFromMachine", MachineRecipe.class);
            // 静态字段在静态块之前初始化（RecipeSupporter.java:604-608 早于 :1889），
            // 扫描期读它一定是就绪的；真为 null 说明对方版本结构不同，本次不给结论
            if (fRecipeList.get(null) == null || fMachineList.get(null) == null || fGeneratorList.get(null) == null) {
                available = false;
                return false;
            }
            available = true;
            return true;
        } catch (Throwable t) {
            available = false;
            TT.log("[兼容] 逻辑工艺对接层探测失败，堆叠机对接跳过: " + t);
            return true;
        }
    }

    /**
     * 把一台糖然科技机器的配方列表转换成 LogiTech 可识别的形态。
     *
     * <p>判定顺序（「类型优先 + 语义兜底」）：</p>
     * <ol>
     *   <li>全部配方都是「纯生成」语义（无输入、产出全 100%、非 chooseOne）→ 生成配方列表；</li>
     *   <li>否则整台机器按配方机器处理（混合形态不做拆分，避免堆叠机匹配到半截语义）。</li>
     * </ol>
     */
    public static List<MachineRecipe> buildRecipes(List<TTRecipe> source) {
        // ready() = 「对方已完成/已进入配方供应器扫描」且探测成功。
        // 在这之前一律返回空表：绝不能因为一次提前的调用就把对方的类初始化掉。
        if (!ready() || source == null || source.isEmpty()) return Collections.emptyList();
        boolean allPureGenerator = true;
        for (TTRecipe r : source) {
            if (!isPureGenerator(r)) {
                allPureGenerator = false;
                break;
            }
        }
        List<MachineRecipe> out = new ArrayList<>(source.size());
        if (allPureGenerator) {
            for (TTRecipe r : source) {
                MachineRecipe g = newGeneratorRecipe(r);
                if (g != null) out.add(g);
            }
            return out;
        }
        for (TTRecipe r : source) {
            MachineRecipe converted = convertRecipeMachine(r);
            if (converted != null) out.add(converted);
        }
        return out;
    }

    /** 纯生成语义：无输入 + 产出全部 100% + 非 chooseOne + 周期有效。 */
    private static boolean isPureGenerator(TTRecipe r) {
        if (r == null || r.getTicks() < 0) return false;
        ItemStack[] in = r.getInput();
        if (in != null) {
            for (ItemStack it : in) {
                if (it != null && !it.getType().isAir()) return false;
            }
        }
        ItemStack[] out = r.getOutput();
        if (out == null || out.length == 0) return false;
        if (r.isChooseOne()) return false;
        for (int i = 0; i < out.length; i++) {
            if (r.outputChance(i) < 100) return false;
        }
        return true;
    }

    /** 造物匣 → LogiTech 的 {@code MGeneratorRecipe}（ticks 按 LogiTech 惯例取 tickRate-1）。 */
    private static MachineRecipe newGeneratorRecipe(TTRecipe r) {
        try {
            ItemStack[] out = r.getOutput();
            if (out == null || out.length == 0) return null;
            ItemStack[] cloned = new ItemStack[out.length];
            for (int i = 0; i < out.length; i++) {
                cloned[i] = out[i] == null ? null : out[i].clone();
            }
            int ticks = Math.max(0, r.getTicks() - 1);
            return (MachineRecipe) genCtor.newInstance(ticks, new ItemStack[0], cloned);
        } catch (Throwable t) {
            TT.log("[兼容] 构造生成器配方失败（" + r.getTicks() + "t）: " + t);
            return null;
        }
    }

    /** 配方机器 → 走 LogiTech 的 RSC 兼容通道，保留概率 / noConsume 语义。 */
    private static MachineRecipe convertRecipeMachine(TTRecipe r) {
        try {
            MachineRecipe carrier = TTCustomMachineRecipe.of(r);
            Object transferred = mTransfer.invoke(null, carrier);
            if (!(transferred instanceof MachineRecipe mr)) return null;
            Object gen = mTryGen.invoke(null, mr);
            if (gen instanceof MachineRecipe g) return g;
            Object stacked = mStackFrom.invoke(null, mr);
            return stacked instanceof MachineRecipe s ? s : null;
        } catch (Throwable t) {
            TT.log("[兼容] 配方转换失败（" + r.getTicks() + "t）: " + t);
            return null;
        }
    }

    /**
     * 计划一次登记：等逻辑工艺自己完成「配方供应器」扫描之后，再补耗电修正与兜底补登。
     *
     * <p><b>为什么不能立刻登记</b>：对方的扫描是在它自己的静态块里完成的
     * （{@code RecipeSupporter.java:1889-1892}），触发点是第 1 tick 的
     * {@code PostSetupTasks.schedulePostRegister()}（{@code Schedules.java:36-41}）。
     * 只要我们在 {@code onEnable} 里提前 {@code Class.forName} 一次，这份「等全体附属注册完
     * 再快照」的时序就被打乱，晚于本插件 enable 的附属机器会整批缺席——而且是静默的。</p>
     *
     * <p>所以：① 只轮询对方无害的 {@code PostSetupTasks.startPostRegister} 标志
     * （不碰 {@code RecipeSupporter}）；② 该标志出现即说明扫描已开始/完成；③ 兜底最多等
     * {@value #POST_REGISTER_MAX_WAIT} tick，超时按时间继续（应对对方字段名随版本变化）。</p>
     */
    public static void scheduleRegister(org.bukkit.plugin.Plugin plugin) {
        if (!classPresent()) return;   // 没装逻辑工艺：连定时器都不起
        new org.bukkit.scheduler.BukkitRunnable() {
            private int waited;

            @Override
            public void run() {
                waited++;
                boolean done = postRegisterDone();
                if (!done && waited < POST_REGISTER_MAX_WAIT) return;
                cancel();
                if (!done) {
                    TT.log("[兼容] 逻辑工艺的 post-register 标志在 " + waited + " tick 内未出现"
                            + (postRegisterFlagMissing() ? "（字段名可能随版本变化）" : "")
                            + "，按预设时机继续登记");
                }
                registerAll();
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    /** 等对方 post-register 标志的最长 tick 数（1 秒）。 */
    private static final int POST_REGISTER_MAX_WAIT = 20;

    /**
     * 把已注册的糖然科技机器登记进逻辑工艺的堆叠表，并修正单机耗电。
     *
     * <p>只应由 {@link #scheduleRegister} 在对方扫描结束后调用。<b>不要</b>在
     * {@code onEnable} 里直接调——那会把对方的静态扫描提前到「其他附属还没注册」的时点。</p>
     */
    public static void registerAll() {
        if (!ready()) return;
        try {
            Map<SlimefunItem, List<MachineRecipe>> recipeList = map(fRecipeList);
            Map<SlimefunItem, Integer> machineList = map(fMachineList);
            Map<SlimefunItem, Integer> generatorList = map(fGeneratorList);

            int gen = 0;
            int mach = 0;
            int skipped = 0;
            int excluded = 0;
            int adopted = 0;
            for (SlimefunItem item : new ArrayList<>(Slimefun.getRegistry().getEnabledSlimefunItems())) {
                // 脚本机器（命轨织机等）的产能由 NativeScript 的 tick 钩子驱动，没有「输入槽 + 配方」
                // 这层语义，堆叠机无从复制：显式排除，防止将来它被改成继承 TTRecipeMachine 后误入堆叠表
                if (item instanceof ScriptMachineItem) continue;
                if (!(item instanceof TTRecipeMachine machine)) continue;
                // 依赖方块上下文的机器（多方块核心）由子类自行拒绝
                if (!machine.supportsLogiTechStacking()) {
                    excluded++;
                    continue;
                }
                List<MachineRecipe> recipes = machine.getMachineRecipes();
                if (recipes == null || recipes.isEmpty()) {
                    skipped++;
                    continue;
                }
                boolean isGenerator = isAllGenerator(recipes);
                int energy = perTickEnergy(machine);
                // 扫描期对方的表里已经有我们（它调过 getMachineRecipes()）：那条是它自己转换后的
                // 形态，保留不要覆盖；只有缺席时才补登
                if (recipeList.containsKey(item)) adopted++;
                else recipeList.put(item, recipes);
                Map<SlimefunItem, Integer> target = isGenerator ? generatorList : machineList;
                Map<SlimefunItem, Integer> other = isGenerator ? machineList : generatorList;
                if (target.containsKey(item)) {
                    // 同键 put 只更新耗电，不改变 LinkedHashMap 的插入顺序
                    target.put(item, energy);
                } else if (other.containsKey(item)) {
                    // 只追加不重排：上一次扫描已把它归到另一侧，保持原分类不动
                    other.put(item, energy);
                    TT.log("[兼容] " + item.getId() + " 已被逻辑工艺归为另一类堆叠，保持原分类（未移动）");
                } else {
                    target.put(item, energy);
                }
                if (isGenerator) gen++;
                else mach++;
                syncCachedList(item, energy, isGenerator);
            }
            TT.log("[兼容] 逻辑工艺堆叠对接完成：生成机器 " + gen + " 台 / 配方机器 " + mach + " 台"
                    + (adopted > 0 ? "（其中 " + adopted + " 台由对方扫描自动收编）" : "")
                    + (skipped > 0 ? "（" + skipped + " 台无可用配方已跳过）" : "")
                    + (excluded > 0 ? "（" + excluded + " 台多方块核心不参与堆叠）" : ""));
        } catch (Throwable t) {
            TT.log("[兼容] 逻辑工艺堆叠对接失败（不影响本插件运行）: " + t);
        }
    }

    /**
     * 同步 LogiTech 的 {@code BW_LIST} 缓存（首次使用时它会把两张表<b>拷贝</b>成列表）。
     *
     * <p>若缓存早于本层登记（{@code hasInit == true}），新机器不会出现在选择界面：
     * 这里<b>只在尾部追加</b>，并按同样的下标写耗电数组、更新 {@code BWSIZE}——
     * 既有下标一个不动，老存档的 {@code craftType} 依旧有效。</p>
     *
     * <p>仅在「列表长度与耗电数组长度一致」时追加（否则说明缓存处于半初始化状态，
     * 强行追加会造成下标错位，宁可跳过并记日志）。</p>
     */
    private static void syncCachedList(SlimefunItem item, int energy, boolean generator) {
        String cls = "me.matl114.logitech.core.Machines.AutoMachines."
                + (generator ? "StackMGenerator" : "StackMachine");
        try {
            Class<?> c = Class.forName(cls, false, LogiTechStackBridge.class.getClassLoader());
            Field fInit = c.getDeclaredField("hasInit");
            fInit.setAccessible(true);
            if (!fInit.getBoolean(null)) return;   // 未固化：惰性初始化时会自带我们的条目

            Field fList = c.getDeclaredField("BW_LIST");
            Field fEnergy = c.getDeclaredField("BW_LIST_ENERGYCOMSUME");
            Field fSize = c.getDeclaredField("BWSIZE");
            fList.setAccessible(true);
            fEnergy.setAccessible(true);
            fSize.setAccessible(true);

            List<SlimefunItem> list = (List<SlimefunItem>) fList.get(null);
            int[] arr = (int[]) fEnergy.get(null);
            if (list == null || arr == null) return;
            if (list.contains(item)) return;
            if (list.size() != arr.length) {
                TT.log("[兼容] " + cls + " 缓存长度不一致（列表 " + list.size() + " / 耗电 " + arr.length
                        + "），跳过缓存追加以免下标错位");
                return;
            }
            list.add(item);
            int[] grown = java.util.Arrays.copyOf(arr, list.size());
            grown[list.size() - 1] = energy;
            fEnergy.set(null, grown);
            fSize.setInt(null, list.size());
        } catch (Throwable ignored) {
            // 字段名随版本变化时静默跳过：不影响主流程，玩家重开服务器后缓存自然重建
        }
    }

    private static boolean isAllGenerator(List<MachineRecipe> recipes) {
        if (genClass == null) return false;
        for (MachineRecipe r : recipes) {
            if (!genClass.isInstance(r)) return false;
        }
        return true;
    }

    /**
     * 单机每 tick 耗电：本附属机器「每次操作扣一次」，LogiTech「每 tick × 份数」，
     * 按最短周期均摊（取全部配方里最小的 ticks，宁可略贵不可漏扣）。
     */
    private static int perTickEnergy(TTRecipeMachine machine) {
        int base = machine.getEnergyConsumption();
        if (base <= 0) return 0;
        int ticks = Integer.MAX_VALUE;
        for (TTRecipe r : machine.getRecipes()) {
            if (r != null && r.getTicks() > 0) ticks = Math.min(ticks, r.getTicks());
        }
        if (ticks == Integer.MAX_VALUE || ticks <= 1) return base;
        return Math.max(1, (int) Math.ceil((double) base / ticks));
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> map(Field f) throws IllegalAccessException {
        return (Map<K, V>) f.get(null);
    }
}
