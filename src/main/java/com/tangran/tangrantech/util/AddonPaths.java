package com.tangran.tangrantech.util;

import com.tangran.tangrantech.TT;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附属数据路径的统一入口。
 *
 * <p>原 RSC 版把数据放在两个地方，本插件完全沿用同样的相对路径，保证老档不失效：</p>
 * <ul>
 *   <li>{@code addon_configs/糖然科技/**} —— <b>玩家数据</b>（丹方权限、异火权限、境界、任务、
 *       储物空间、浮生当铺缓存、终刻合成台草稿…），按玩家名分文件；</li>
 *   <li>{@code addons/糖然科技 TangranTech/**} —— <b>附属资源</b>（终刻合成台配方、语言文件），
 *       管理员手工维护。</li>
 * </ul>
 *
 * <p>两者都挂在插件数据目录（{@code plugins/TangranTech/}）之下。</p>
 *
 * <p>另外注意：jar 里 {@code data/**} 是<b>随插件发布</b>的初始资源
 * （{@code data/终刻合成台配方/*.json}、{@code data/lang/zh_cn.json}），
 * 与运行期磁盘目录是两回事 —— 需要「发布默认值 + 运行时覆盖」的场合，
 * 请用 {@link #readBundledText(String)}。</p>
 */
public final class AddonPaths {

    private AddonPaths() {}

    /** 玩家数据根目录（相对插件数据目录）。 */
    public static final String CONFIG_ROOT = "addon_configs/糖然科技";
    /** 附属资源目录（相对插件数据目录）。 */
    public static final String ADDON_ROOT = "addons/糖然科技 TangranTech";

    /** 玩家数据子目录名。 */
    public static final String DIR_DANFANG = "丹方权限";
    public static final String DIR_YANHUO = "异火权限";
    public static final String DIR_REALM = "境界";
    public static final String DIR_TASKS = "任务";
    public static final String DIR_STORAGE = "储物空间";
    /** 天道证明目录（击败糖然后写入，终刻合成台的准入凭证）。 */
    public static final String DIR_HEAVEN_PROOF = "天道证明";
    /** 终刻合成台玩家存档目录（玩家数据侧）。 */
    public static final String DIR_FINAL_CRAFT_SAVE = "终刻合成台";
    /** 终刻合成台草稿目录（玩家数据侧，与编译器共用同一目录、不同文件名后缀）。 */
    public static final String DIR_FINAL_CRAFT_DRAFT = "终刻合成台";
    /** 终刻合成台配方目录（附属资源侧，主目录）。 */
    public static final String DIR_FINAL_CRAFT_RECIPE = "终刻合成台配方";
    /** 终刻合成台配方备用目录（附属资源侧，老档可能把配方落在这里）。 */
    public static final String DIR_FINAL_CRAFT_RECIPE_ALT = "终刻合成台";

    /** 插件数据目录；插件未初始化时返回 null。 */
    public static File dataFolder() {
        return TT.plugin == null ? null : TT.plugin.getDataFolder();
    }

    /** 玩家数据根目录（{@code addon_configs/糖然科技}，不创建）。 */
    public static File configRoot() {
        File root = dataFolder();
        return root == null ? null : new File(root, CONFIG_ROOT);
    }

    /** 规范化后的玩家数据根目录 {@code Path}（缓存：{@code dataFolder} 启动后不变；
     *  {@link #docParts} 是所有玩家数据读取的入口，原实现每读一份文档都做两次
     *  {@code toPath().normalize()}）。 */
    private static volatile java.nio.file.Path configRootPath;

    private static java.nio.file.Path configRootPath() {
        java.nio.file.Path p = configRootPath;
        if (p == null) {
            File root = configRoot();
            p = root == null ? null : root.toPath().normalize();
            configRootPath = p;
        }
        return p;
    }

    /** 取（并创建）玩家数据侧的子目录。 */
    public static File configDir(String sub) {
        return ensure(new File(dataFolder(), CONFIG_ROOT + "/" + sub));
    }

    /** 附属资源根目录（{@code addons/糖然科技 TangranTech}）。 */
    public static File addonRoot() {
        return ensure(new File(dataFolder(), ADDON_ROOT));
    }

    /** 取（并创建）附属资源侧的子目录。 */
    public static File addonDir(String sub) {
        return ensure(new File(dataFolder(), ADDON_ROOT + "/" + sub));
    }

    /** 终刻合成台配方目录（附属资源侧）。 */
    public static File finalCraftRecipeDir() {
        return addonDir(DIR_FINAL_CRAFT_RECIPE);
    }

    /** 终刻合成台配方备用目录（附属资源侧）。 */
    public static File finalCraftRecipeAltDir() {
        return addonDir(DIR_FINAL_CRAFT_RECIPE_ALT);
    }

    /** 终刻合成台草稿目录（玩家数据侧）。 */
    public static File finalCraftDraftDir() {
        return configDir(DIR_FINAL_CRAFT_DRAFT);
    }

    /** 终刻合成台玩家存档目录（玩家数据侧）。 */
    public static File finalCraftSaveDir() {
        return configDir(DIR_FINAL_CRAFT_SAVE);
    }

    /** 天道证明目录（玩家数据侧）。 */
    public static File heavenProofDir() {
        return configDir(DIR_HEAVEN_PROOF);
    }

    private static File ensure(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            TT.log("目录创建失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // 玩家数据文档（后端 = SQLite；不可用时回落文件）
    // ------------------------------------------------------------------

    /**
     * 读一份玩家数据文档。
     *
     * <p><b>路径即文档标识</b>：{@code addon_configs/糖然科技/<命名空间>/<键>.json}
     * → 文档 {@code (ns=命名空间, key=键)}，存在 {@link Store}（SQLite）里；
     * 其余路径（{@code addons/} 下的附属资源、{@code .txt} 标记文件）走真实文件。</p>
     *
     * <p>库可用但该键还没有文档时，会去读同名旧文件并<b>顺手入库</b>，
     * 保证「先升级后导入」与「边跑边迁移」都不会读到空档。</p>
     */
    public static Map<String, Object> readJson(File file) {
        if (file == null) return null;
        String[] doc = docParts(file);
        if (doc != null) {
            Map<String, Object> fromDb = Store.load(doc[0], doc[1]);
            if (fromDb != null) return fromDb;
            Map<String, Object> fromFile = readJsonFile(file);
            if (fromFile != null) {
                Store.save(doc[0], doc[1], fromFile);   // 惰性迁移：只补空缺，不覆盖
            }
            return fromFile;
        }
        return readJsonFile(file);
    }

    /**
     * 写一份玩家数据文档（玩家数据目录下的 JSON → SQLite；其余 → 真实文件）。
     *
     * <p>文件后端的写入仍是「先写 .tmp 再原子替换」，避免进程被 kill 时留下半个 JSON。</p>
     */
    public static boolean writeJson(File file, Object data) {
        if (file == null) return false;
        String[] doc = docParts(file);
        if (doc != null) {
            if (Store.save(doc[0], doc[1], data)) return true;
            // 库不可用 → 继续走文件
        }
        return writeJsonFile(file, data);
    }

    /** 文档是否存在（玩家数据目录下的 JSON 查库，其余查文件）。 */
    public static boolean docExists(File file) {
        if (file == null) return false;
        String[] doc = docParts(file);
        if (doc != null) {
            if (Store.available()) return Store.exists(doc[0], doc[1]) || file.isFile();
            return file.isFile();
        }
        return file.isFile();
    }

    /** 删一份文档（库里删不掉时再删文件）。 */
    public static boolean deleteDoc(File file) {
        if (file == null) return false;
        String[] doc = docParts(file);
        if (doc != null) {
            boolean db = Store.delete(doc[0], doc[1]);
            boolean fs = file.isFile() && file.delete();
            return db || fs;
        }
        return file.isFile() && file.delete();
    }

    /**
     * 列出一个玩家数据目录下的全部文档，返回<b>路径形状的虚拟 File</b>。
     *
     * <p>调用方（记录清理、备份枚举等）只用到 {@code getName()} / {@code readJson} /
     * {@code deleteDoc} 这类操作，返回的 File 不保证在磁盘上真实存在 —— 它只是
     * {@code (ns, key)} 的路径表达。真实存在但还没入库的旧文件也会一并列出，
     * 保证迁移期间不漏。</p>
     */
    public static List<File> listDocs(File dir) {
        List<File> out = new java.util.ArrayList<>();
        if (dir == null) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String[] nsProbe = docParts(new File(dir, "probe.json"));
        if (nsProbe != null && Store.available()) {
            for (String key : Store.keys(nsProbe[0])) {
                if (seen.add(key)) out.add(new File(dir, key + ".json"));
            }
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                String key = f.getName().substring(0, f.getName().length() - 5);
                if (seen.add(key)) out.add(f);
            }
        }
        return out;
    }

    /** 文档最后写入时间（毫秒）；文件后端取 mtime。 */
    public static long docUpdatedAt(File file) {
        if (file == null) return 0L;
        String[] doc = docParts(file);
        if (doc != null) {
            long t = Store.updatedAt(doc[0], doc[1]);
            if (t > 0) return t;
        }
        return file.isFile() ? file.lastModified() : 0L;
    }

    /**
     * 把路径拆成 {@code [命名空间, 键]}；不是「玩家数据目录下的 .json」时返回 null。
     *
     * <p>只认 {@code addon_configs/糖然科技/<一级子目录>/<名>.json} 这一种形状 ——
     * 更深层、或不在该根目录下的文件一律当普通文件处理（附属资源、lang、临时件）。</p>
     */
    private static String[] docParts(File file) {
        try {
            String name = file.getName();
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) return null;
            java.nio.file.Path rootPath = configRootPath();
            File parent = file.getParentFile();
            if (rootPath == null || parent == null || parent.getParentFile() == null) return null;
            if (!parent.getParentFile().toPath().normalize().equals(rootPath)) {
                return null;
            }
            String key = name.substring(0, name.length() - 5);
            if (key.isEmpty()) return null;
            return new String[]{parent.getName(), key};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读真实 JSON 文件；不存在或损坏时返回 null。 */
    private static Map<String, Object> readJsonFile(File file) {
        try {
            if (file == null || !file.exists()) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> map = Json.parseObject(text);
            return map.isEmpty() && !text.trim().startsWith("{") ? null : map;
        } catch (Throwable t) {
            TT.log("读 JSON 失败 " + (file == null ? "?" : file.getAbsolutePath()) + ": " + t);
            return null;
        }
    }

    /**
     * 写真实 JSON 文件（2 空格缩进，与原脚本 {@code JSON.stringify(x, null, 2)} 一致）。
     *
     * <p><b>先写 {@code .tmp} 临时文件再替换</b>：直接覆写在进程崩溃 / 服务器被 kill 时
     * 会留下半个 JSON，下次读出来就是空的（调用方的缓存一旦以此为权威就会把玩家存档
     * 清零）。替换尽量走 {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}；
     * 文件系统不支持时退化为普通替换，仍优于直接覆写。失败时旧文件保持原样，返回 false。</p>
     */
    private static boolean writeJsonFile(File file, Object data) {
        try {
            if (file == null) return false;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                TT.log("目录创建失败: " + parent.getAbsolutePath());
                return false;
            }
            java.nio.file.Path target = file.toPath();
            java.nio.file.Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(tmp,
                    Collections.singletonList(Json.writePretty(data)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            TT.log("写 JSON 失败 " + (file == null ? "?" : file.getAbsolutePath()) + ": " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 随插件发布的资源
    // ------------------------------------------------------------------

    /**
     * 读 jar 内 {@code data/<name>} 的文本内容（UTF-8）；不存在返回 null。
     *
     * <p>用于「随插件发布的默认配置」：运行时优先读磁盘目录，取不到再回落到这里。</p>
     */
    public static String readBundledText(String name) {
        if (TT.plugin == null || name == null) return null;
        try (java.io.InputStream in = TT.plugin.getResource("data/" + name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            TT.log("读内置资源失败 data/" + name + ": " + t);
            return null;
        }
    }

    /** 列出 jar 内 {@code data/<dir>/} 下的资源文件名（不含目录前缀）。 */
    public static List<String> listBundledFiles(String dir) {
        List<String> out = new java.util.ArrayList<>();
        if (TT.plugin == null || dir == null) return out;
        try (java.io.InputStream root = TT.plugin.getResource("data/" + dir)) {
            // JarURLConnection 不允许直接 list，改用 jar 条目枚举
        } catch (Throwable ignored) {
            // 下面走 jar 条目
        }
        try {
            java.net.URL url = TT.plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(url.toURI());
            if (!jar.isFile()) return out;
            String prefix = "data/" + dir + "/";
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    String entry = en.nextElement().getName();
                    if (!entry.startsWith(prefix) || entry.equals(prefix)) continue;
                    String rest = entry.substring(prefix.length());
                    if (rest.isEmpty() || rest.contains("/")) continue;
                    out.add(rest);
                }
            }
        } catch (Throwable t) {
            TT.log("枚举内置资源失败 data/" + dir + ": " + t);
        }
        return out;
    }

    /** 空的只读 JSON 对象，避免调用方到处判 null。 */
    public static Map<String, Object> emptyJson() {
        return new LinkedHashMap<>();
    }
}
