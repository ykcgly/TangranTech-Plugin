package com.tangran.tangrantech;

import com.tangran.tangrantech.load.Setup;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 糖然科技 TangranTech —— 独立 Slimefun 附属插件主类（对齐 WorldTaste-Plugin 的单插件自包含形态）。
 *
 * <p>与原 RSC 版的差异：不再解压 addons/ 目录、不再扫描/加载多个附属文件夹、不再有 Graal/JS 脚本层
 * 与依赖轮询等待。内容 YAML 直接打进 jar 根目录，启动即由 {@link Setup#loadAll()} 一次性注册；
 * 原 {@code scripts/} 下的 32 个 JS 脚本全部改写为 {@code native_scripts/} 包下的原生 Java 类。</p>
 *
 * <p>依赖策略：Slimefun 为硬依赖，缺失则自动卸载；LiteXpansion / DynaTech / InfinityExpansion
 * 为软依赖，缺失仅输出提示后继续加载（内容里对它们的引用会因物品未注册而自动跳过）。</p>
 */
public final class TangranTechPlugin extends JavaPlugin implements SlimefunAddon {

    private static TangranTechPlugin instance;

    /** 硬依赖：{插件名, 中文名}，缺任一即自动卸载。 */
    private static final String[][] HARD_DEPS = {
            {"Slimefun", "粘液科技"},
    };

    /** 软依赖：{插件名, 中文名}，缺失仅提示后继续加载。 */
    private static final String[][] SOFT_DEPS = {
            {"LiteXpansion", "LiteXpansion"},
            {"DynaTech", "DynaTech"},
            {"InfinityExpansion", "无尽贪婪"},
    };

    @Override
    public void onEnable() {
        instance = this;
        TT.plugin = this;
        getLogger().info("糖然科技 开始加载（独立版）...");
        if (!checkDependencies()) return;
        // 玩家键索引：进服即记 名字→UUID（之后所有解析走缓存，零成本）
        try {
            com.tangran.tangrantech.util.PlayerKeys.register();
        } catch (Throwable e) {
            getLogger().warning("玩家键索引监听注册失败: " + e);
        }
        try {
            Setup.loadAll();
            // 只「计划」一次登记，绝不在这里真去碰逻辑工艺的类：它的配方供应器靠「第 1 tick
            // 首次 touch 才扫描」来保证快照到全部附属的机器，提前触发会让晚加载的附属整批缺席。
            // 详见 compat/LogiTechStackBridge#scheduleRegister（未装 LogiTech 时内部静默跳过）。
            com.tangran.tangrantech.compat.LogiTechStackBridge.scheduleRegister(this);
            registerCommands();
            getLogger().info("糖然科技 加载成功");
        } catch (Throwable e) {
            getLogger().severe("糖然科技 加载过程中出现异常: " + e);
            e.printStackTrace();
        }
    }

    /** 注册 {@code /tr} 管理指令（数据导入 / 存储状态）。 */
    private void registerCommands() {
        try {
            var cmd = getCommand("tr");
            if (cmd == null) {
                getLogger().warning("plugin.yml 里没有 tr 指令定义，/tr import 不可用");
                return;
            }
            var handler = new com.tangran.tangrantech.command.TrCommand();
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } catch (Throwable t) {
            getLogger().warning("注册 /tr 指令失败: " + t);
        }
    }

    /**
     * 启动期依赖检查。硬依赖缺失 → 输出「缺少 xxx，糖然科技已自动卸载！」并禁用自身，
     * 返回 false 中止加载；软依赖缺失 → 输出「缺少 xxx，部分玩法可能无法加载」后继续。
     */
    private boolean checkDependencies() {
        for (String[] dep : HARD_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().severe("缺少 " + label(dep) + "，糖然科技已自动卸载！");
                Bukkit.getPluginManager().disablePlugin(this);
                return false;
            }
        }
        for (String[] dep : SOFT_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().warning("缺少 " + label(dep) + "，部分玩法可能无法加载");
            }
        }
        return true;
    }

    /** 依赖展示名：中文名与插件名相同（如 LiteXpansion）时只显示一处。 */
    private static String label(String[] dep) {
        return dep[1].equalsIgnoreCase(dep[0]) ? dep[0] : dep[0] + "（" + dep[1] + "）";
    }

    @Override
    public void onDisable() {
        // 停掉脚本层注册的异步调度器，避免插件卸载后线程仍在跑
        try {
            com.tangran.tangrantech.native_scripts.NativeScriptRegistry.shutdown();
        } catch (Throwable ignored) {
            // 卸载阶段不应因清理失败而中断
        }
        // 关掉玩家数据库（SQLite 单连接；不关会留下 -wal/-shm 句柄）
        try {
            com.tangran.tangrantech.util.Store.shutdown();
        } catch (Throwable ignored) {
            // 同上
        }
        getLogger().info("糖然科技 已卸载");
    }

    public static TangranTechPlugin getInstance() {
        return instance;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Override
    public String getBugTrackerURL() {
        return "";
    }
}
