# TangranTech-Plugin 项目长期记忆

## 项目性质
糖然科技 TangranTech：RSC（RykenSlimefunCustomizer）附属 → 独立 Slimefun4 jar 插件。
包名 `com.tangran.tangrantech`，主类 `TangranTechPlugin`，上下文 `TT`。
目标 Paper 1.21.x + Slimefun4 Beta（`libs/Slimefun4-347d926-Beta.jar`，compileOnly）。
产物 `TangranTech-2.1.3-standalone.jar`（约 7.1 MB，含 xerial sqlite-jdbc 驱动与常用平台原生库）。构建：`./gradlew build`（JDK 21，见 gradle.properties 的 org.gradle.java.home，默认 JDK 25 会崩在配置阶段）。
**发版流程**：改 `build.gradle.kts` 的 `version` + `src/main/resources/plugin.yml` 的 `version` + README 产物路径 → 构建 → 提交 → `git tag -a vX.Y.Z` → 推 main 与 tag → GitHub Release（本机无 gh CLI，走 REST API：`git credential fill` 取已缓存 token 后 curl POST `/releases`，再 POST `uploads.github.com/.../assets` 传 jar）。

## 架构要点（改代码前必读）

- **玩家数据后端 = SQLite**（`plugins/TangranTech/data/tangran.db`，表 `player_doc(ns,key,doc,updated_at)`）。
  **唯一路由点**是 `AddonPaths.readJson/writeJson`：`addon_configs/糖然科技/<一级子目录>/<名>.json`
  → 文档 `(ns=子目录, key=文件名)` 进库，其余（`addons/**`、`.txt` 标记）走真实文件。
  新增玩家数据请一律用 `AddonPaths.configDir(...) + readJson/writeJson`，并配 `docExists/deleteDoc/listDocs`；
  **禁止** `Files.write` / `new File(...addon_configs...)` 裸写（会写进磁盘而模块读库 —— FullAnalysisScript 踩过）。
  旧 JSON 首次启动自动导入（不覆盖）；库打不开时自动降级回文件后端。
- **权限层** = `util/Perms`：有 LuckPerms 走其 API（softdepend + compileOnly），没有回落 Bukkit。
  管理侧只认节点或 OP；玩法资格「存档为主、权限为辅」。节点前缀 `tangrantech.`（admin / admin.compiler /
  admin.arena / command.import / storage.unlocked / yanhuo.<id> / danfang.<id>）。
- **储物空间存档是「桶」模型**：白板物品/旧档 = `"ID": 数量`；带 NBT = `"ID#<sha1前12>": {count, nbt: base64}`，
  白板判定 = 与 `buildCleanStack(cat,id)` 字节相等；取数一律走 `countOf(entry)`，别再直接 `asLong(value)`。
- **内容加载**：`load/Setup.loadAll()` 编排，13 个 content/*.yml 打进 jar 根目录；`TT.preload`（大写 id→展示堆）打破跨文件前向引用；大小写规范：物品 id 一律 `TT.upper`、组 key 一律 `TT.lower`（Slimefun 注册要求大写，yml 键大小写敏感不能先大写再查）。
- **脚本框架**：yml `script:` 绑定 → `ScriptItemListener` → `ScriptItem.onRightClick` → `NativeScript.invoke("onUse"/"tick"/"onButtonGroupClick")`。
  `NativeLifecycleScript.listeners()` 的注册由 `NativeScriptRegistry.registerLifecycleListeners` 统一负责（经 NativeLifecycle 幂等）。
- **五条硬约定**（README）：①右键只走 onUse 单入口，脚本禁止自注册 PlayerInteractEvent 接同一物品；②周期逻辑用 tick 钩子不用 onPlace 起任务（重启免疫）；③物品身份只认 `TT.sfIdOf`，禁显示名兜底（防铁砧伪造）；④投掷类材质右键必须 setCancelled(true)；⑤跨脚本隐式依赖/死代码不照抄。
- **派发层口径**：ScriptItemListener 只放行 RIGHT_CLICK_AIR/BLOCK（2026-10-21 修复左键误触后确立）。

## 2026-10-22 大修复的架构教训
`NativeScriptEval.evalFunction("init")` 曾是 listeners() 的唯一上链路径，但 `ScriptEval.doInit()` 无调用方 → 31 个脚本监听器全没注册（静默失效）。教训：**原生版删掉 RSC「求值」链时，必须把 init 触发的接线补到 registerLifecycleListeners**。同源问题还有三处「导入/方法写了但无调用方」的死代码：MobDrops.register、QiaoleziCoolantScript 的 PlayerQuitEvent 清理、Setup 双份诊断块。

**P1+P2 批（10-22）全部完成，backlog 清零**，另沉淀四条铁律：
① quit 时序：PlayerQuitEvent 先于最终存档（PlayerList.remove，SPIGOT-5799/6145）——下线改状态必须在 quit 处理器做才落盘；
② 重启免疫机器范式：`script:` 机器走 ScriptMachineItem tick 通道（`age % N == 1` 计数器模拟 runTaskTimer），不用 onPlace 起任务；
③ 音效键必须点分（`entity.tnt.primed`），下划线形式无效且被 try 吞掉；
④ 实体 custom-name 兜底仅限安全闸类容错（BossArena.isBossBody 有记载），**经济入口的名字兜底必须加阶段闸**（TwinShop 命名牌伪造案）；
⑤ **菜单按钮认身份要按格子（rawSlot）+ 点击来源守卫（`getClickedInventory() != top`），不要比显示名** —— getCurrentItem() 对玩家自己背包的格子同样生效，铁砧改名即可伪造（TangranBasicMatterScript 案）；
⑥ **末影人免疫投射物伤害**：`EnderMan.hurt()` 里只要 DamageSource 带 `IS_PROJECTILE` 标签（箭/三叉戟/雪球）就随机瞬移并 `return false`；
   且 `EntityDamageByEntityEvent` **不会触发**（hurt 里提前 return）。要给末影人结算伤害/特效，
   **必须手动 `enderman.damage(amount, player)` 以玩家为伤害源**（`ProjectileHitEvent` 会正常触发，可作主路径）；
⑦ **指南/ChestMenu 内监听点击必须用 `EventPriority.LOW`**：Slimefun 内嵌 `ChestMenu.MenuListener` 是无参
   @EventHandler（NORMAL），对返回 false 的槽位 handler 会 setCancelled(true)；同优先级按注册顺序执行
   （Slimefun 先启用先注册），NORMAL + ignoreCancelled=true 的监听器会被整个跳过（配方预览按钮点击失效案）。
   依据：javap 反编译 MenuListener.handleEvent 字节码。RSC 的 RecipeViewListener 同样用 LOW。
⑧ **Slimefun 机器 tick（TickerTask）在异步线程跑**（实测栈：ForkJoinPool worker + AsyncCatcher
   「failed main thread check」）。机器 tick/tick 钩子里**禁止直接做实体/玩家操作**
   （getNearbyPlayers/spawnEntity/getNearbyEntities/remove 实体），必须 runSync 包住
   （超多方块 SuperBlocks.runSync 是范式；RSC 的 Asynchronized.runTask 同款）。
   方块读取与 BlockStorage/StorageCacheUtils 读写异步可用（RSC 同样异步做）。

⑨ **机器配方匹配禁用 ItemStackWrapper，必须用真实物品栈**：运行时 Slimefun 构建
   （f172239）的 `ItemStackWrapper.wrap()` 丢失 PDC/sf_id → `SlimefunItem.getByItem(wrapper)`
   恒 null → 粘液输入永远匹配失败（TR_YTC/TR_HTY「有输入但无配方命中」案，v2 交叉诊断
   用真实物品全过、findMatch 用 wrapper 全拒，分歧唯一落点）。注意 libs/ 里的
   347d926 反编译显示 wrapper 缓存真实 meta（看似无问题）——**运行时构建行为不同，
   静态分析不可替代实测**。配方匹配语义：材质 + sf_id（PDC），不比完整 NBT。

⑩ **原版属性有硬上限**：`Attribute.MAX_HEALTH.setBaseValue` 只接受 [0, 1024]，超了抛
   `IllegalArgumentException: Health value (x) must be between 0 and 1024.0`；紧跟的
   `setHealth(超上限)` 同样会抛。写血量必须 `min(1024, wanted)` 并**回读 `attr.getValue()`**
   当折算分母（三阶段糖然决斗体「血条 20 ×100 = 2000 血」致变身整段中断案，
   `BossPhaseThreeFour.applyDuelHealth`）。RSC 原脚本两处都 try 吞掉 → 身体只有原版 20 血、
   血条却按 2000 折算（读数恒 1，一刀通关），这类**静默错比报错更该查**。

⑪ **BOSS 挑战场已是常驻独立世界** `tangran_space`（`TangranSpace`：两座固定模板 + 每场就地重置，
不再「在玩家身边选址建场」）→ 「按标记反推场地 + 右键基岩清场」整条链路与道具 `Tr_tzqyqcq`
已于 2026-10-26 删除；场地保护走 `BossArenaProtector`（Dominion 式，整个世界即领地）。

⑫ **外部兼容层必须「双向关死」**（LogiTech 堆叠对接，v2.1.2）：`compat/LogiTechStackBridge` 反射写
`RecipeSupporter` 的三张表，但 LogiTech 自己也会在静态初始化里遍历所有 `AContainer` 调
`getMachineRecipes()`，非空就收编 → **想在登记阶段排除某类机器是挡不住自动扫描的**，
必须让 `getMachineRecipes()` 本身返回空（`TTRecipeMachine.supportsLogiTechStacking()`，
多方块核心 `TTSuperMultiBlockMachine` 返回 false；脚本机 `ScriptMachineItem` 继承 `SlimefunItem`
天然不在范围）。三条纪律：只追加不重排（删条目会让 `craftType` 下标串机）、耗电
`ceil(energy/minTicks)`、`registerAll` 里已存在的条目只改耗电不覆盖配方（对方存的是它自己转换后的形态）。
已知限制：0 输入配方在堆叠机侧因 `matchMultiRecipe` 空输入返回 null 需占位物。

⑬ **跨插件反射对接的第一铁律：`Class.forName` 必须带 `initialize=false`**（v2.1.2 回归案）。
LogiTech 把全部重活放在 `RecipeSupporter` 的静态块里，靠「第 1 tick 才首次 touch」实现
「等全体附属注册完再快照」（`Schedules:36-41` 第 1 tick → `PostSetupTasks.schedulePostRegister()`
先置 `startPostRegister` 再调空的 `init()`）；v2.1.2 在 `onEnable` 里 `Class.forName(RS)`
（默认初始化）把扫描提前到第 0 tick → 晚于本插件 enable 的附属配方**整批静默缺席且永不可补**。
现在的做法：`scheduleRegister` 轮询对方 `PostSetupTasks.startPostRegister`（该 clinit 只有一个
HashSet，不牵连 RecipeSupporter），等到才登记；`resolve()` 首行 `if (!postRegisterDone()) return false`。
判断对方是否「已就绪碰表」，只找它自己的**无害标志字段**，绝不读目标类的成员来试探。

## 部署与协作约定
- jar 由用户手动拷到测试服，AI 不写测试服目录。
- 用户协作风格：迭代式验证（重启服务端→反馈日志路径→定位 WARN/error），要求根因分析附源码实证（文件:行号）。
- backlog 约 45 条逻辑漏洞**已全部清理完毕**（P0×3 → P1×4 组 → P2×7 项，2026-10-21/22 两轮，详见 `.workbuddy/memory/2026-10-21.md` 与 `2026-10-22.md`）。
