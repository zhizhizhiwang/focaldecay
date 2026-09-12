# Focal Decay 开发进度清单

> 最后更新：2026-08-20
> 环境：NeoForge 21.1.248 / Minecraft 1.21.1 / Parchment 2024.11.17 / Java 21
> Mod ID：`focal_decay`，包：`com.zhizhiwang.focal_decay`
> 当前状态（2026-08-21）：`compileJava` / `runData` / `build -x test` 通过。引导模型重设计（方案 A，§9.2）已实施并**提交**（`c6f437c`）。§9 观测稳定系统里程碑 1~7 全部完成；§11 观测者核心修复路径已实施（未提交）；**§11.1 候选观测者主线重构已实施（2026-08-21，未提交）**——碎片知识化 + 候选观测者模型 + 核心安装胜利 + 龙遗物宝箱。

## 已完成

### 1. 项目改造（完成）
- `gradle.properties`：Mod ID/名称/包名改为 focal_decay，版本升级 1.21.1（neo=21.1.248）
- 主类 `FocalDecay.java`（`src/main/java/com/zhizhiwang/focal_decay/FocalDecay.java`）
- 删除 examplemod 模板代码，编译与 build 通过

### 2. 方块/物品/方块实体骨架（完成）
- 方块：`block/AnchorPrototypeBlock.java`、`ObserverCoreBlock.java`（含 powered 状态）、`TrainingTerminalBlock.java` + `block/ModBlocks.java`（2026-08-19 重构：原 StableAnchorBlock/MutationControllerBlock 已移除，改为稳定锚原型机；训练终端已注册）
- 方块实体：`block/entity/AnchorPrototypeBlockEntity.java`（模型插槽 + NBT 持久化 + MenuProvider/Container）、`TrainingTerminalBlockEntity.java` + `block/entity/ModBlockEntities.java`
- 物品：`item/ModItems.java`（3 个方块物品 + 6 个观测模型 `ObserverModelItem`（含 `total_stability_model_activated`）+ 7 个语义碎片 + `rebuilt_observer_protocol`）
- 创造标签：`item/ModCreativeTabs.java`
- 语言：`assets/focal_decay/lang/en_us.json` + `zh_cn.json`（含全部 Lore）
- 资源：blockstates / models 全部 JSON（贴图暂用原版方块占位）

### 3. 配置系统（完成）
- `config/FocalDecayConfig.java`：Server（stage2_day/stage3_day/各阶段 interval/实体概率/enable_core_repair/生物稳定能量参数 bio_energy_capacity・bio_conversion_per_hp・bio_drain_per_second・bio_stage3_double_drain・bio_stabilize_entities）、Common（anchor_radius/post_intensity）、Client（postProcessEnabled/surface_update_frequency/max_render_distance）
- 主类已注册三份 config spec

### 4. 标签与数据生成（完成）
- `data/tags/ModTags.java`：BlockTags（global_mutation_pool/conversion_blacklist/anchor_prototype_immune）+ EntityType 三阶段池标签
- `data/tags/ModBlockTagsProvider.java`、`ModEntityTypeTagsProvider.java`
- `data/recipe/ModRecipeProvider.java`（原型机形状合成、空白模型无序合成、重建协议/复制模型特殊配方）
- `data/recipe/ModRecipeSerializers.java`：`RebuildObserverProtocolRecipe`（7 碎片无序合成，`crafting_special_rebuildobserver`）+ `CopyTrainedModelRecipe`（训练模型复制，`crafting_special_copytrainedmodel`）
- `data/ModDataGenerator.java`：`runData` 已成功生成 JSON 到 `src/generated/resources`
- **维度专属突变池（2026-08-21）**：新增 `nether_mutation_pool` / `end_mutation_pool`，`ModTags.Blocks.poolForDimension` 按维度选池（专属池为空回退主世界池）；主世界池扩充至约 218 种（去皮原木/树皮/矿物块/陶瓦/混凝土/羊毛/珊瑚块/菌类等），下界 45 种（黑石系/玄武岩/下界砖/灵魂沙/岩浆/下界木/矿物），末地 6 种（末地石/紫珀/黑曜石）

### 5. 全局池与确定性随机（完成）
- `mutation/MutationPool.java`：按 BuiltInRegistries.BLOCK id 升序的不可变列表，带 version
- `mutation/MutationHelper.java`：`getTarget(original, pos, worldSeed, periodIndex, pool)`，种子经 SplitMix64 雪崩混合；统一入口 `getVisibleTarget(...)`（含概率、保护、诞生周期）
  - **纵向随机性修复（2026-08-20）**：旧种子公式 `pos.asLong() ^ worldSeed ^ period` 中 y 只占最低 12 位，与 period/worldSeed 低位纠缠，再经 LegacyRandomSource 48 位截断 + 低概率累积回退扫描后纵向熵被吃掉（模拟实测阶段1一列 32 格仅 3 个不同目标、相邻 21 格相同）；改为三坐标分量分别乘不同大常数再异或 + mix64（`seedFor`），纵向与平面随机性一致（模拟实测 27~28/32 不同，三档概率均无相邻重复倾向），服务端/客户端共用同一公式同步不变
- `mutation/MutationPoolManager.java`：维度级 SavedData，管理有效原型机效果（位置/半径/模型数据）+ 方块诞生周期 + 全局池，`getEffectivePool(pos, original)`、`isProtected(pos, state)` 按模型判定
- `mutation/MutationEventHandler.java`：原型机放置/换模/破坏更新、维度加载 reloadGlobalPool；已注册游戏总线
- 旧 `RegionOverride`（覆盖列表）与突变控制器覆盖逻辑已删除（2026-08-19，由原型机效果取代）

### 6. 交互与转换（完成）
- `attachment/BreakData.java` + `ModAttachments.java`：玩家挖掘锁定数据（用 NeoForge 21.1 attachment 替代旧 Capability）
- `mutation/InteractionHandler.java`：LeftClickBlock 锁定目标 + BreakEvent setCanceled(true) 后按目标方块生成掉落/经验；创造模式跳过
- 挖掘速度/工具要求由目标决定（2026-08-21）：`MultiPlayerGameModeMixin` 把挖掘进度改为读可见目标（`ClientRenderCache.miningState` 走缓存，O(1)）；掉落/经验传入玩家主手工具，`requiresCorrectToolForDrops` 生效
- 陈旧挖掘锁定修复（2026-08-21）：`BreakData` 记录锁定位置并序列化；非转换源（门/楼梯/栅栏等）左键时清空锁定，破坏时校验位置不匹配即清空——杜绝"上次目标的掉落泄漏到不完整方块"的问题
- 挖掘原版行为还原（2026-08-21）：`BreakEvent` 取消会跳过原版 `destroyBlock`/`playerDestroy` 的后续逻辑——`InteractionHandler.onBlockBreak` 手动补调 `held.mineBlock`（按目标扣 2 耐久）、`awardStat(Stats.BLOCK_MINED)`（挖掘统计）、`causeFoodExhaustion(0.005F)`（饥饿），均按"当前可见目标"结算
- **突变目标抽取统一入口（2026-09-11）**：新增 `mutation/MutationTargets`——`resolve(...)`（纯函数：转换源判定 + 保护/概率/引导偏向/诞生周期/累积回退扫描，两端共用）与 `resolveServer(level,pos,state)`（服务端装配阶段/池/保护/偏向/周期）。四处调用点全部改走它：世界固化（`MutationEventHandler.convertPrototypeRange`）、左键锁定（`InteractionHandler`）、右键训练（`ModelTrainingHandler.visibleState`）、客户端渲染预览（`ClientRenderCache.computeTarget`）。**修复 bug**：右键训练此前漏掉转换源判定，导致右键门/楼梯/栅栏等不完整方块会记录一个"并未突变"的突变目标；现在所见即所记录（此类方块记录其自身）。

### 7. 客户端渲染缓存系统（完成）
- `client/ClientRenderCache.java`：`Map<Long, Entry> targetCache`（带周期号防跨周期旧值）+ `Set<Long> visibleSurfaces` + `Set<Long> activeSections`（按节计数精确回收）
  - 目标计算与服务器同种子同池（`pos.asLong() ^ worldSeed ^ periodIndex` + 全局池按注册表 id 升序），单人经 `IntegratedServer#overworld#getSeed` 取种子；多人待 §10 同步
  - 周期切换（gameTick / base_interval）自动清缓存并让受影响区块节重编译
- 视锥表面计算：`RenderLevelStageEvent.AFTER_SKY` 捕获 `Frustum`，每 `surface_update_frequency` tick 扫描玩家附近已加载、视锥内、非空区块节（预算 6 节/次，按距离优先，半径硬上限 8 chunk），暴露面判定后写缓存并触发 `LevelRenderer#setSectionDirty`
- Mixin 模型替换（1.21.1 已重构：目标为 `SectionCompiler.compile` 而非旧 `RenderChunk.RebuildTask`；NeoForge 补丁后 RebuildTask 调用 5 参数 compile（含 additionalRenderers），4 参数仅委托，注入点须用 5 参数描述符）：
  - `mixin/client/SectionCompilerMixin.java`：`@Redirect` `RenderChunkRegion#getBlockState`（全类唯一调用点，位于 5 参数 compile 方块循环），把 targetCache 中的目标状态替换进编译网格（只改渲染不改世界）
  - `mixin/client/RenderChunkRegionAccessor.java`：`@Accessor("level")` 暴露编译线程读取的 Level
  - 配置：`focal_decay.mixins.json`（`client` 数组，`defaultRequire=1`）+ mods.toml `[[mixins]]`；NeoForge 运行时用官方映射，无需 refmap/MixinGradle，sponge-mixin 由 neoforge POM 传递提供
- 后处理着色器：`observer_veil` PostChain（`assets/focal_decay/shaders/post/observer_veil.json`），每帧 `setUniform("Fade")` 按 `post_intensity` + 呼吸动画淡化；自定义 program 需放在 `assets/minecraft/shaders/program/`（PostChain 用默认命名空间解析 program/vsh/fsh）
  - 客户端保护集合/原型机效果数据：已由 `SyncRegionDataPacket` 同步（见 §7.6 与 §9 里程碑 3）

### 7.1 首轮实机修复（2026-08-10）
- 崩溃修复：世界卸载/加载过渡期 `LevelRenderer.viewArea` 为 null，`setSectionDirty` 会 NPE；新增 `mixin/client/LevelRendererAccessor` 并在 `markSectionDirty` 中判空跳过
- 着色器修复：`PostChain` 构造不补 `shaders/post/` 前缀，需传完整路径 `focal_decay:shaders/post/observer_veil.json`；失败日志改为只打一次
- 突变池扩充：`global_mutation_pool` 从仅 `#minecraft:stone_ore_replaceables`（6 种石头）扩到约 95 种方块（石头系/泥土/砂石/木材/矿物/下界/末地/海洋等），重跑 `runData` 落盘
- 周期刷新可观测：周期切换时 INFO 日志输出 `period N — cleared X ghost entries in Y sections`，便于确认每周期重编译发生

### 7.2 着色器花屏修复（2026-08-10）
- 根因：program JSON 漏声明 `InSize`。`EffectInstance` 只注册 JSON uniforms 列表里的 uniform，`PostPass` 每帧 `safeGetUniform("InSize")` 落到 DUMMY，GLSL 中 `InSize` 保持 (0,0) → vsh 里 `OutSize / InSize` 除零 → texCoord=NaN → 采样垃圾 → 全屏色块闪烁、世界不可见
- 修复：program JSON 补 `InSize`（float×2）与 `Time`（float×1，供时间扭曲用）；fsh 改为设计大纲 7.4 的"极轻微时间扭曲 + 色散 + 轻微偏色"，注释改 ASCII 防驱动兼容问题
- 实机日志确认：每 5 秒周期切换正常触发（`period 39→48`，每次清 8k~340k 条幽灵方块、重编译 33~1200 个区块节），即"只变化一次"并非刷新机制失效，而是此前池子只有 6 种石头 + 花屏遮住了画面

### 7.3 视角颠倒与窗口缩放错误修复（2026-08-10）
- 视角颠倒根因：顶点着色器错误复用了原版遗留的 `invert.vsh`（带 Y 翻转）；1.21.1 所有采样渲染目标的程序（含 invert）实际都用不翻转的 `blit.vsh`。已改为 blit 风格（`texCoord = Position.xy / OutSize`，无翻转），并移除 JSON 中不再使用的 `InSize`
- 窗口缩放根因：自定义 PostChain 不跟随 `GameRenderer.resize`，窗口变化后内部 target 仍是旧尺寸 → 输出被错误拉伸。已在 `updateVeil` 每帧比对窗口宽高并调用 `veil.resize(w, h)` 同步

### 7.4 功能增补：概率转换 + 创造模式支持（2026-08-10）
- 按阶段方块转换概率：每个方块每周期以概率决定是否突变（阶段1/2/3 = 0.1/0.6/1.0，Server 配置 `block_mutation_chance_stage1/2/3`）
  - `MutationHelper.getTarget` 增加概率参数：同一确定性种子先 roll `nextDouble()` 再选目标，服务端与客户端随机序列一致
  - 阶段判定（临时）：`currentStage(gameTick) = gameTick / 24000` 对照 `stage2_day/stage3_day`，待 §8 末日天数 SavedData 接入后替换
  - 客户端预览与服务器转换共用同一公式（`pos ^ worldSeed ^ period` + 同概率），未命中的方块保持原样、无幽灵
- 创造模式支持：
  - 破坏：创造模式保持原版行为（无掉落、不收入背包、不执行转换），`InteractionHandler` 对创造玩家直接跳过；仅生存模式破坏触发"转换为目标方块 + 目标掉落/经验"
  - 中键选取：新增 `Mixin MinecraftPickBlockMixin`（注入 `pickBlock()V` 中两处 `ClientLevel#getBlockState`），返回可见的失焦目标方块；`ClientRenderCache.visibleState()` 负责查询

### 7.5 修复：固定位置失焦 + 创造掉落行为（2026-08-10）
- 固定位置失焦根因：`periodIndex` 是小数字，`seed = pos ^ worldSeed ^ periodIndex` 直接异或只扰动低几位，而 LCG 首次 `nextDouble` 由高位移位主导 → 同一批位置每周期都通过/不通过概率骰子（模拟验证重叠 100%）
  - 修复：`MutationHelper.getTarget` 对种子做 SplitMix64 雪崩混合后再交给 `RandomSource`（模拟验证跨周期失焦集合重叠降至 ~10%，位置每周期重排）；两端公式相同，同步性不变
- 创造模式破坏修正：最终确定创造破坏保持原版行为（无掉落、不收入背包、不执行转换），撤销此前的转换/掉落逻辑；中键选取返回目标方块保持不变

### 7.6 稳定锚修复 + 统一方块识别（2026-08-10）
- 放置时固化失焦状态：`MutationEventHandler.convertAnchorRange` 在登记保护前，把锚保护范围内所有方块按当前周期确定性公式（含概率 roll）转换为失焦目标，再 `addAnchor`，避免稳定住转换前状态
- 保护不再拦截渲染：新增网络同步 `SyncRegionDataPacket`（S→C：维度 + 锚位置 + 保护半径），登录/切换维度/锚放置破坏时发送；`ClientRenderCache` 按维度存 `RegionData`，`resolve()`/扫描/中键选取均跳过受保护位置，`refreshProtectedArea()` 清掉保护范围旧幽灵并重编译
- 统一方块识别：`MutationHelper.getVisibleTarget(original, pos, worldSeed, periodIndex, pool, probability, isProtected)` 作为生存破坏、创造中键选取、客户端预览的唯一入口
- 网络实现方式更新：NeoForge 21.1 已移除 `SimpleChannel`，改用现代 Payload API（`RegisterPayloadHandlersEvent` + `PayloadRegistrar`），协议版本 "1"（与 §8 一致）；保护半径改用 `FocalDecayConfig.ANCHOR_RADIUS` 并随包下发

### 7.7 手部渲染修复（2026-08-20）
- 症状：第一人称手臂与手持方块模型不渲染。
- 根因：`observer_veil` PostChain 在 `RenderLevelStageEvent.AFTER_LEVEL`（`LevelRenderer.renderLevel` 末尾）整链处理，而原版手部渲染在 `renderLevel` **返回之后**才执行——在"手还没画"时处理完整帧会破坏后续手部渲染的目标/状态。
- 修复：新增 `mixin/client/GameRendererMixin`，注入 `GameRenderer.renderLevel(DeltaTracker)` 尾部（手部渲染之后）执行 `updateVeil`，并在处理后 `getMainRenderTarget().bindWrite(true)` 恢复主目标绑定（与原版 `postEffect.process` 之后的处理一致）；`AFTER_LEVEL` 事件中的调用移除。

### 8. 末日阶段系统（完成）
- `mutation/FocalDecayWorldData.java`：全局天数 SavedData（`days` + 部分 tick），每 20 分钟游戏日（24000 tick）+1，玩家数为 0 暂停，`ServerTickEvent.Post` 驱动；天数变化经 `SyncWorldDataPacket` 广播，登录/换维时补发
- 阶段判定与周期：`MutationHelper.currentStage(days)`（对照 `stage2_day/stage3_day`，`enable_stage_system=false` 恒为阶段 1）、`intervalForStage`（100/60/40 tick）；服务端与客户端（同步天数）共用
- 阶段影响范围（§6.3，2026-08-21 修订）：`MutationHelper.isConversionSource` —— **所有阶段仅"完整立方体碰撞"方块**（`isCollisionShapeFullBlock`，剔除门/楼梯/栅栏/玻璃板等模型不完整方块，移除原阶段2+ 非完整碰撞箱扩展）；空气/方块实体/黑名单始终排除
- 实体突变（§6.4）：`mutation/DoomsdayHandler.java` 每阶段周期掷确定性骰子（`mix64(worldSeed ^ pos.asLong() ^ tick)`），`Mob`（排除玩家）从三阶段实体池转换（阶段1 被动 / 阶段2 +中立 / 阶段3 +敌对，源池=目标池），NBT 复制（去 UUID）替换实体；`ItemEntity` 掉落物目标物品改为方块池随机方块物品
- 天气突变（2026-08-21）：`DoomsdayHandler.mutateWeather` 与实体突变同周期——按 `weather_mutation_chance_stage1/2/3`（默认 0.0/0.05/0.15）掷确定性骰子，命中把主世界天气随机转为不同状态（晴/雨/雷暴，持续 60~360 秒）；观测者在线时不触发
- 客户端 `ClientRenderCache` 接入阶段：同步 `worldDays`，`isCandidate`/`computeTarget`/`resolve`/扫描全部按阶段走源范围，阶段变化自动清缓存重算
- 测试命令（2026-08-13）：`/focaldecay days [<n>]` 查询/设定末日天数（设定需权限 2），经 `SyncWorldDataPacket` 广播后客户端即时重算阶段
- 实体突变不生效修复（2026-08-13）：`lastEntityMutationTick` 曾初始化为 `Long.MIN_VALUE`，`serverTick - MIN_VALUE` 溢出恒为负、周期判断永假导致实体转换从不触发；已改为初始 0 并加防回归注释
- 实体突变 NPE 修复（2026-08-13）：遍历活动实体列表期间 `discard`/`addFreshEntity` 会引入 null 墓碑，导致 `entity.isAlive()` 空指针；改为先拷贝快照再遍历并做 null 判空
- 实体转换 NBT 白名单重构（2026-08-13）：`EntityMutation` 抽象出跨物种保留字段白名单（Age/ForcedAge/Health/CustomName/CustomNameVisible/PersistenceRequired/Tags/ActiveEffects），位置/朝向复制、速度清零，飞行/物理/渲染瞬态标志（如 NoGravity）从根上丢弃，替代原先整份 NBT 复制后逐个打补丁的做法
- 累积转换（2026-08-13）：方块失焦改为"有记忆"累积（未抽中的保留上次材质而非回退原方块），实现为回退扫描最近抽中周期
- 移除空气↔方块转换（2026-08-19）：阶段3 的"方块→空气 / 空气→方块"逻辑、配置项（`block_to_air_chance_stage3`/`air_to_block_chance_stage3`）与客户端空气预览全部删除，需求从大纲移除，恢复为仅方块材质间的确定性转换
- 方块诞生周期（2026-08-13）：玩家放置的方块记录诞生周期（`MutationPoolManager` 维度级持久化 `Map<BlockPos, Long>`），`getVisibleTarget`/`cumulativeTarget` 增加起始周期，放置瞬间及同周期保持原方块、之后才崩坏；放置/破坏事件维护并广播，`SyncRegionDataPacket` 携带诞生周期同步客户端，锚固化与破坏转换同样尊重
- 阶段切换稳定性修复（2026-08-20）：
  - **登录/换维补发天数**：`onPlayerJoin`/`onPlayerChangedDimension` 之前只补发区域数据、未补发 `SyncWorldDataPacket`，导致重进世界后客户端 `worldDays` 归零、按阶段1渲染预览（"大部分改变回退"）；现已补发。
  - **方块周期固定基准**：方块突变周期改为 `blockPeriod = gameTick / base_interval`（固定 100 tick），不再随阶段 interval（100/60/40）跳变——此前阶段切换会让周期编号跳变、全图目标重排。
  - **累积转换最终决策（2026-08-20）**：经确认，`cumulativeTarget` 恢复原始"回退扫描最近抽中周期"公式——命中条件即阶段概率（0.1/0.6/1.0），每周期掷骰、抽中换新材质、未抽中保留上次材质，世界随周期逐渐积累崩坏；阶段切换（含 `/focaldecay days` 指令）改变命中概率导致已失焦方块材质重排，视为预期行为。`MEMORY_CHANCE` 解耦方案已移除；保留固定周期基准与登录天数补发两处真修复。


### 9. 观测稳定系统（2026-08-19 设计修订，取代原"稳定锚 + 突变控制器"）
- **设计变更**：原 `stable_anchor` / `mutation_controller` 统一重构为 **稳定锚原型机**（`anchor_prototype`）+ **观测模型**（物品 + DataComponents）；新增 **训练终端**（`training_terminal`）与 **末地王座**；语义锁定/引导模型继承原突变控制器功能，完全稳定锚为终极形态。
- 里程碑（对应 PROXYAI §12）：
  1. 框架 + 注册原型机、训练终端、模型物品、王座方块（含对现有 stable_anchor/mutation_controller 代码的重构）——**锚 + 训练终端部分已完成（2026-08-19）**：`anchor_prototype` 方块/实体/模型插槽/GUI（菜单+屏幕）、6 个观测模型物品注册、MutationPoolManager 原型机位置、`SyncRegionDataPacket` 更名为原型机语义、配方/语言/资源/标签更新；`mutation_controller` 及其方块实体已删除。**训练终端已注册并实现**：`training_terminal` 方块/方块实体/菜单/屏幕、`ModelTrainingHandler`（右键记录"可见目标"方块/实体）、`CopyTrainedModelRecipe`（复制配方）。王座方块待后续里程碑注册。
  - 打磨（2026-08-19）：两个 GUI 换成自定义占位贴图（`assets/focal_decay/textures/gui/*.png`，替换贴图即换外观）；模型 tooltip 收纳袋风格（默认折叠数量、Shift 展开本地化目标列表）；训练右键改为记录"可见目标"方块（与客户端预览同公式）；训练提示改用本地化名称而非注册键。
  3. 原型机效果应用——**已完成（2026-08-19）**：`MutationPoolManager` 改为"有效原型机效果"列表（位置/半径/模型数据，瞬态、由方块实体在放置/加载/换模时重建）；"无模型无效果"门控打开（移除旧的全范围保护）；`isProtected(pos, state)` 按模型判定（生物稳定/完全稳定范围内全部，语义锁定命中 trainedTargets）；`getEffectivePool` 由引导模型限制突变目标池（多原型机交集，空回退全局池）；插入首个有效模型时固化范围失焦状态；`SyncRegionDataPacket` 携带原型机效果同步客户端，客户端 `isProtected`/引导池接入渲染缓存；`DoomsdayHandler` 实体突变跳过稳定范围内的生物；旧 `RegionOverride`/突变控制器覆盖逻辑已删除。
  4. 生物稳定模型：周围生物生命值消耗、能量换算/衰减、范围内方块/实体稳定、阶段3双倍消耗——**已完成（2026-08-20）**：`BioStabilizerHandler` 每 20 tick 结算——范围内非玩家生物每只损失 1 HP，按 `bio_conversion_per_hp` 补充 bioEnergy（上限 `bio_energy_capacity`）；效果按 `bio_drain_per_second` 消耗能量，阶段 3 双倍（`bio_stage3_double_drain`）；能量耗尽后方块保护与实体跳过失效（`isProtected`/`isEntityProtected` 按 `bioEnergy>0` 判定，实体跳过受 `bio_stabilize_entities` 开关控制）；`SyncRegionDataPacket` 携带 bioEnergy，客户端仅在"活跃↔耗尽"翻转时刷新；原型机 GUI 显示能量/容量，物品 tooltip 显示剩余能量；生物稳定模型物品自带 TYPE_BIO 数据（初始能量 0，无需训练）
  5. 末地王座结构（种子决定、主岛与外岛间虚空环带、规避末影龙机制）与仪式（触发/计时默认 3~5 分钟/波次、`ThroneRitualPacket`）——**已完成（2026-08-20，待实机验证）**：
     - 结构：`structure/ThroneStructure` + `ThronePiece`（**不可破坏王座方块** `throne_block` 构成的基座/四角四边水晶柱/中央空基座/北侧王座），`thronePos(worldSeed)` 由种子决定方向与距离（**650~905 块，主岛 1000 格内的虚空带**，远离外岛与末影龙战斗半径）；**放置修复（2026-08-20）**：原 random_spread 候选区块与"仅王座区块"判定永不重合导致结构不生成/locate 失败，新增 `ThroneStructurePlacement extends RandomSpreadStructurePlacement`（自定义放置类型 `focal_decay:end_throne_spread`，`getPotentialStructureChunk` 恒返回王座区块、`isPlacementChunk` 仅匹配王座区块），生成与 locate 均命中；`worldgen/structure/end_throne.json` + `worldgen/structure_set/end_throne.json` + 生物群系标签由 `ModWorldGenProvider`/`ModBiomeTagsProvider` 数据生成产出；王座区域经 `conversion_blacklist` 注册不会失焦（不再用包围盒 BreakEvent 保护）；调试：结构生成时 INFO 日志输出位置 + `/focaldecay throne` 命令查询坐标。
     - 视觉（2026-08-20）：四根角柱绘制折跃门式信标光束（`ThroneBeamRenderer` 客户端渲染，end_gateway_beam 贴图，结构生成后显示）；常驻粒子（每 2 秒末地棒/传送门漂浮）、仪式开始（传送门+末地棒爆发 + 末地传送门/潮涌核心音效）、进行中（每秒粒子 + 波次龙息粒子与远古守卫者诅咒音效）、完成（末地棒+传送门大爆发 + 信标激活/末地传送门音效）。
     - 仪式：右键基座触发（携带原型机物品或附近已放置 + 未激活完全稳定模型）；`ThroneRitualData`（维度级 SavedData）持久化进度，离开半径按 `throne_ritual_pause_on_leave` 暂停（同玩家返回续仪）或失败；按配置波次生成敌人（`throne_ritual_wave_entities/size/interval`）；完成时只升级"槽内本来就是未激活完全稳定模型"的原型机，其他情况激活模型交还玩家背包（不再覆盖原型机原有内容），广播 `ThroneRitualPacket`（开始/进度/波次/暂停/完成/失败）。
     - 模型接入修正（2026-08-20）：插入有效模型时**先按模型实际半径（完全稳定=32）固化范围失焦状态、再登记保护**（此前先登记保护导致 `getEffectivePool` 返回空、固化无效）；`convertPrototypeRange` 增加 `isLoaded` 防护避免大半径触发未加载区块加载；激活的完全稳定模型 tooltip 不再显示训练目标/Shift 提示。
  6. 完全稳定锚：仪式升级、半径 32 完美稳定、特殊视觉；完全稳定模型第一枚"龙遗物宝箱"、后续"已激活模型 + 空白模型"复制——**已完成（2026-08-20，2026-08-21 修订获取方式）**：仪式升级（原型机插槽升级为 `total_stability_model_activated`，不覆盖原有模型）、半径 32 完美稳定（`radiusFor(TYPE_TOTAL)=32` + `isProtected`）、复制配方（`CopyTrainedModelRecipe`）、**第一枚改由击败末影龙后的"遗物宝箱"产出**（`DragonChestHandler`，传送门平台生成，替代原 `DragonDropHandler` 掉落物防掉虚空/火焰）、特殊视觉（`TotalStabilityFieldHandler` 旋转光环粒子 + 锚上方漂浮粒子）
  7. 与末日阶段/渲染/网络整合（阶段3模型效果衰减等）——**已完成（2026-08-21）**：引导模型阶段3 q 减半（§9.2 随 `guided_stage3_halve` 实现）；语义锁定阶段3转**软保护**——新增 `MutationHelper.Protection`（硬保护/软保护）与 `semantic_lock_stage3_strength`（默认 0.5）：阶段1/2 语义锁定仍硬保护，阶段3 每周期先掷"守住"骰子、失守才参与突变骰（确定性、服务端 `MutationPoolManager.protectionInfo` 与客户端 `ClientRenderCache.protectionInfo` 同一公式）；生物稳定/完全稳定保持硬保护；渲染缓存/中键选取/挖掘锁定统一走该判定。
  8. 彩蛋与打磨（粒子/音效/专属贴图/测试）

### 9.2 引导模型重设计（方案 A，2026-08-20 定稿，2026-08-21 已提交）
- **问题**：现行引导模型把半径内突变目标池硬限制为训练列表，阶段3（概率 1.0、40 tick/周期）下可把任意方块稳定刷成训练目标，过度 OP，且不符合 SCP-CN-2999 苹果实验的"概念一致性"（分类器稳定的不是具体物体，而是与概念相关的一切；概念外物体失焦概率不受影响；单一/残缺分类几乎无效；"变了的就变了"）。
- **定稿设计**（已同步到 PROXYAI.md §3.1 / §3.3 / §4.2 / §4.3 / §5.1 / §6.5 / §9.1 / §13）：
  1. **概念**：训练列表不再直接作为目标池，而是用于**指认概念**；训练完成时解析并固化 `concept` 标签 + 完备度 q 到模型数据。概念标签来源 = 策展 `focal_decay:concept/*`（数据生成）+ 原版标签兜底 + 通用标签黑名单；概念邻域 = 标签下全部方块（过滤空气/带方块实体/`conversion_blacklist`）；无法指认有效概念 → q=0，模型无效。
  2. **源门控**：仅概念内成员突变时被引导；概念外方块照常全局随机。
  3. **目标偏向**：概念内成员抽中突变时以 q 从概念邻域选目标、1−q 回退全局池；q 只作用于目标选择，不改突变骰子（概率/周期/种子）。
  4. **完备度**：`q = clamp(|trainedTargets ∩ 概念邻域| / |概念邻域|, 0, 1)`；少于 `guided_min_trained`（默认 2）视为残缺分类 q=0；可配倍率/上限；阶段3 q 减半（`guided_stage3_halve`，默认开）。
  5. 累积语义不变：引导后的目标同样参与 `cumulativeTarget` 回退扫描。
- **实施清单**：
  1. 数据生成：策展概念标签 `focal_decay:concept/*`（wood/ore/stone/glass/terracotta/wool）——**已完成**（`ModBlockTagsProvider` + `runData` 产出 6 个 tag JSON）
  2. `ObserverModelData` 新增 `concept` 字段，`stabilityStrength` 存 q——**已完成**（Codec/StreamCodec 手写编码，超 `composite` 6 分量上限；复制配方随物品组件自动保留；`SyncRegionDataPacket.PrototypeData` 新增 concept+q）
  3. 训练终端"完成训练"：解析概念（覆盖率最高标签）、算 q、写入模型——**已完成**（`TrainingTerminalBlockEntity.finishTraining` + `GuidedConcept.resolve`，含"无有效概念"提示）
  4. `MutationPoolManager.getGuidedBias` 与 `ClientRenderCache.guidedBias` 概念判定（源门控 + q 偏向），服务端/客户端共用公式——**已完成**（旧 `getEffectivePool`/`resolveTrainedBlocks` 已删除；`MutationHelper.getVisibleTarget/cumulativeTarget` 增加 `GuidedBias` 参数，q 分支用同一确定性随机源）
  5. 原型机 GUI / 模型 tooltip：显示概念名与完备度 q——**已完成**
  6. 配置项：`guided_min_trained` / `guided_q_multiplier` / `guided_q_cap` / `guided_stage3_halve`——**已完成**
  7. `compileJava` / `runData` / `build -x test`——**全部通过（2026-08-21）**；平衡测试待实机
- **状态**：**已实施并提交（`c6f437c`，2026-08-21）**。待实机平衡测试。

### 10. 网络通信（承接现有实现）
- `SyncRegionDataPacket`（S→C）：**已完成扩展**——携带有效原型机效果（位置/半径/模型数据）+ 方块诞生周期；登录/换维/原型机变化/方块放置破坏时发送
- `SyncWorldDataPacket`（S→C）：**已扩展**——携带末日天数 + 观测者在线状态（`observerOnline`），激活/登录/换维时广播
- `ObserverCoreActivatePacket`（S→C）：核心激活完成的全服粒子/音效/胜利提示——**已实现（2026-08-21）**
- `ThroneRitualPacket`（S→C，新增）：王座仪式进度/波次/完成同步——**已实现**（随王座仪式一起落地）
- 使用 NeoForge 21.1 Payload API（现有 `ModNetwork` 基础上扩展），协议版本 "1"

### 11. 观测者核心修复路径（已完成，2026-08-21）
- **核心 GUI**：右键 `observer_core` 打开无槽位菜单（`ObserverCoreMenu`/`ObserverCoreScreen`），显示"旧观测者：离线/新观测者：在线" + 安装按钮；首次右键赠送一枚 `碎片·完备语义`（`FocalDecayWorldData.coreVisited` 持久化防重复）。
- **安装流程（2026-08-21 主线重构后）**：GUI 按钮 → 服务端校验并**消耗 1 个已完成的候选观测者模型**（`ObserverModelItem.isCompletedCandidate`）→ 播放开始特效/音效 → `scheduleTick`（`observer_core_activation_ticks`，默认 100 tick）→ 完成 tick 设置 `powered=true`、`FocalDecayWorldData.observerOnline=true`（**失焦终止**：方块锁定/实体突变/客户端幽灵预览全部停止，`DoomsdayHandler`、`InteractionHandler`、`ModelTrainingHandler`、`MutationEventHandler.convertPrototypeRange`、`ClientRenderCache` 统一门控）→ 广播 `ObserverCoreActivatePacket` + 全服胜利消息。
- **王座内置核心 + 候选定位**：`ThronePiece` 中央空基座生成 `observer_core`（仪式触发跳过核心方块）；已完成的候选模型在末地右键（空处）生成指向王座的 END_ROD 粒子束 + 距离提示（`candidate_locate`）。
- **语义碎片来源（2026-08-21 里程碑化，移除随机箱子注入）**：
  1. 碎片·玫瑰 —— 首次完成任意模型训练（`TrainingTerminalBlockEntity.finishTraining`，位掩码防重复）
  2. 碎片·王座 —— 末地王座基座宝箱固定产出（`ThronePiece` 直接 `setItem`，非随机）
  3. 碎片·完备语义 —— 首次右键观测者核心（已实现）
  4. 碎片·42ms —— 首次完成王座仪式（`ThroneRitualHandler.complete`，位掩码防重复）
  5. 碎片·硫铜结晶 —— 铜块失焦突变概率掉落（`fragment_copper_mutation_chance`，默认 0.15）
  6. 碎片·Aaron的誓约 —— 击败末影龙后的"遗物宝箱"（`DragonChestHandler`，主岛地面、传送门正下方附近，含未激活完全稳定模型 + Aaron碎片）
  7. 碎片·程玖章的最后传输 —— 首次将引导模型概念完备度练到 100%（q=1.0，位掩码防重复）

### 11.1 候选观测者主线重构（2026-08-21 定稿，已实施）
- **背景**：原主线"收集 7 碎片 → 合成重建协议 → 激活旧核心"操作性弱（多为随机战利品）且与原著"亲手造出新观测者并登座"的结局割裂。
- **定稿设计**（已同步到 PROXYAI.md §2.2 / §2.3 / §3.3 / §3.4 / §9.1 / §13）：
  1. **候选观测者模型** `observer_model_candidate`：新物品，本质是无数量上限的空白模型；**不经过训练终端**（终端不加按钮），手持右键世界方块/生物收集目标（每个唯一目标 +1 进度），或配方"候选模型 + 语义碎片"注入知识（+`candidate_fragment_points`）；进度 ≥ `candidate_required_points`（默认 100）即 100% 完成。
  2. **完成态效果**：兼具完全稳定模型效果（插入原型机 = 半径 32 硬保护），**不可复制**。
  3. **碎片知识化**：7 碎片不再是合成钥匙，改为候选模型训练材料；获取全部改为**里程碑/主动探索**（首次完成训练、王座宝箱固定产出、首次右键核心、首次完成王座仪式、铜块突变、首次屠龙、首次概念 q=100%），**移除全部随机箱子注入**；`FocalDecayWorldData` 位掩码持久化防重复。
  4. **胜利触发**：观测者核心 GUI（核心位于末地王座，= 前任观测者遗骸/插座）安装**已完成的候选模型**（消耗）→ 失焦终止（复用 `observerOnline` 机制）→ "新观测者已就位"。
  5. 完全稳定锚链（末地主岛遗物宝箱 + 王座仪式升级）**保留，与主线并行**。
  6. `rebuilt_observer_protocol` 物品与合成配方移除。
- **实施清单**：
  1. `ObserverModelData` 新增 `progress` 字段（Codec/StreamCodec/构造点同步）
  2. `ModItems` 注册 `observer_model_candidate`（默认 TYPE_CANDIDATE、progress 0）
  3. `ModelTrainingHandler` 支持 TYPE_CANDIDATE（右键收集目标、进度 +1、无上限）
  4. 新配方 `FeedSemanticFragmentRecipe`（候选 + 任一碎片 → 进度 +X，消耗碎片）
  5. 里程碑发放：首次完成训练（玫瑰）/ 王座宝箱（王座，`ThronePiece` 固定产出）/ 首次完成仪式（42ms）/ **龙遗物宝箱**（Aaron，`DragonChestHandler`，主岛地面生成）/ 首次概念 q=100%（程玖章）；位掩码持久化
  6. 移除 `rebuilt_observer_protocol` 物品/配方/文案；移除箱子碎片注入（`LootTableAccessor` mixin 与 `onLootTableLoad`）；`DragonDropHandler` 掉落物改为 `DragonChestHandler` 宝箱
  7. 完成态接入原型机：`radiusFor` / `protectionInfo` / 客户端镜像 / 实体保护按"候选已完成 = 完全稳定"处理
  8. 核心 GUI 安装：`ObserverCoreHandler` 改为检查并消耗已完成候选模型；GUI/消息文案更新
  9. 配置：`candidate_required_points` / `candidate_fragment_points` / `ender_dragon_total_stability_drop_chance`（宝箱概率）
  10. 语言 / 文档 / `compileJava` + `build -x test` 验证
- **状态**：**已实施（2026-08-21）**。`compileJava` / `runData` / `build -x test` 通过；待实机验证。

### 12. 收尾
- 原型机 GUI 与训练终端 GUI：自定义占位贴图已替换（`assets/focal_decay/textures/gui/*.png`），专属模型/细化待做
- 彩蛋粒子（2026-08-27 已完成）：`FloatingText`（Text Display 实体 + @Invoker mixin 访问私有 setter）——训练完成时终端上方浮动 "42ms"、观测者核心安装成功时浮动 "完备语义分类"，无重力/透明背景/到点自动移除；王座中央核心激活后每 40 tick 漂浮蓝色 GLOW 光点
- 测试与平衡调整

### 13. 游戏内指引系统（Patchouli 手册 + JEI 信息页 + tooltip/GUI）
- **定位**：三通道并行，互为补充——手册承载长流程与叙事，JEI 承载"手里拿着物品反查来源"，tooltip/GUI 提示"当下这一步"。
- **风格定稿**：手册以 SCP-CN-2999《Observator Ex Machina》为范本，采用**上一迭代基金会档案**立场（公文腔 + 通信记录体），开放引用原文名句与事实设定。
- **依赖（均为可选，不进 `mods.toml` 必需依赖）**：
  - `compileOnly` + `localRuntime`，坐标 `mezz.jei:jei-1.21.1-neoforge:19.39.0.368`、`vazkii.patchouli:Patchouli:1.21.1-93-NEOFORGE`（API 用 `:api` classifier），仓库 `https://maven.blamejared.com`；版本号在 `gradle.properties` 的 `jei_version` / `patchouli_version`
  - Patchouli 内容为纯 JSON，**代码零耦合**；`run/mods` 里手工放置的 JEI jar 已移至 `run/mods.disabled`（改由 gradle 提供，避免同名双份）
- **已完成（2026-09-11，`compileJava` / `runData` / `build -x test` 通过，待实机验证）**：
  1. **构建配置 + 依赖解析（阶段 0）**
  2. **手册骨架与正文（阶段 1）**：`assets/focal_decay/patchouli_books/observer_manual/`
     - 书**定义**在 `data/focal_decay/patchouli_books/observer_manual/book.json`；**内容**在 `assets/…/<locale>/`
     - **`en_us/` 与 `zh_cn/` 两套齐全**，各 6 个分类 + 18 篇条目（`en_us` 是 Patchouli 的索引语言，缺则全书空白）
     - `book.json`：`use_resource_pack`/`i18n`/`creative_tab: focal_decay:focal_decay_tab`/灰色书皮/`pause_game`
     - `models/item/observer_manual.json`：`patchouli:item/book_gray` + 自定义手持 display 参数
     - 6 个分类：档案 / 失焦现象 / 观测者基座 / 语义碎片 / 王座协议 / 重聚焦
     - 18 篇条目：卷首总则、术语与级别、现象概述、附录·现场记录、构造与部署、型号规格、模型训练规程、碎片归档总表、七枚碎片各自条目、OBSR-3、现场位置、登座仪式、核心安装、附录·最后传输
     - 动态数值策略：正文只描述机制并指向"现场界面"，不硬编码配置数字（避免配置一改文档即错）
  3. **自动发书（阶段 2）**：
     - `data/ModAdvancementProvider`：隐藏 advancement `focal_decay:grant_observer_manual`（**`minecraft:tick` 触发器**，无 display），奖励指向战利品表。**与背包内容无关**，创造模式新世界同样发书
     - `data/focal_decay/loot_table/grant_observer_manual.json`：**手写资源**，发放 `patchouli:guide_book` + `patchouli:book` 组件，池级 `neoforge:conditions: [{condition: neoforge:mod_loaded, modid: patchouli}]` 门控
     - `ModDataGenerator` 注册 `AdvancementProvider`
     - **创造模式物品栏（2026-09-11 修复）**：`book.json` 的 `creative_tab` 必须写 **tab 的真实注册 ID**。Patchouli 的实现是
       `if (evt.getTab() == CreativeModeTabRegistry.getTab(b.creativeTab)) evt.accept(book);`
       本模组的 tab 注册名为 `focal_decay_tab`，先前误写成 `focal_decay:focal_decay` → 取不到 tab，书既不进自定义页也不进搜索页。此外 Patchouli 会把所有未设 `noBook` 的书加进**搜索页**，所以正确配好后生存/创造两种模式的搜索都能搜到
  4. **阶段 3：手册位置锁（2026-09-11 完成）**——手册里写着"抵达现场后自动解密"，此前只是个说法（条目没挂 `advancement`，谁都能看）。现已补上真正的锁：
     - `ModAdvancementProvider` 新增 `focal_decay:unlock_throne` / `focal_decay:unlock_core` 两个**无 display** 的 advancement，条件名 `unlocked`，触发器同为 `minecraft:tick`。
       ⚠️ 该触发器**不会**在游戏里自动满足——它只在我们显式 `PlayerAdvancements.award(holder, "unlocked")` 时才判定通过，因此锁的推进完全由代码控制，无需注册自定义 criterion。
     - `mutation/GuideAdvancementHandler`：每 20 tick 检查一次，玩家处于末地且距王座 32 格内即视为"抵达现场"，授予两个解锁条件（王座坐标由 `ThroneStructure.thronePos(seed)` 给出，与结构生成共用同一公式）。
     - 四篇条目挂锁并隐藏（`"advancement"` + `"secret": true`）：`throne/site`、`throne/ritual`、`refocus/core`、`refocus/last_transmission`。`secret` 使锁定条目完全不显示、且不计入完成度。
     - **手动兜底指令**：`/focaldecay unlock`（权限 2，仅玩家可执行），等价于"抵达王座"。
     - 语言键：`message.focal_decay.unlock_done` / `unlock_needs_player`（中英都有）。
  5. **文本修订（2026-09-11，按反馈）**：① 削减反例与"不是…而是…"句式，把**完备度 q 的解释改为实验记录体**（三次重复运行 + 记录表构成/覆盖 q/备注）；② 模糊化过细数据（王座位置不再给"650~905 格"，改为"末地主岛周边的虚空中"；硫酸铜碎片不再列铜块变种清单）；③ 删除纯修辞的语气强化句。
  6. **两卷位置锁的触发条件修正（2026-09-11）**：解锁 advancement 的触发器从 `minecraft:tick` 改为 **`minecraft:impossible`**。
     `minecraft:tick` 是由服务端**每 tick 主动触发**的（送书用的就是它），拿它当锁会让锁在玩家第一个 tick 自己解开；
     `ImpossibleTrigger.addPlayerListener` 是空实现，永不自动满足，锁的推进只能靠 `ModAdvancementProvider.award()` 显式授予。
     同时修掉 `isUnlocked()` 的隐患：原实现调用 Patchouli 的 `ClientAdvancements.hasDone()`，它在服务端（`Minecraft.getInstance().getConnection()` 为 null）会 NPE，改为只读服务端 `PlayerAdvancements` 进度。
- **踩坑记录（重要，按踩到的顺序）**：
  1. **`book.json` 必须放 `data/`，不能放 `assets/`**（最隐蔽的一个）。Patchouli 的 `BookRegistry.init()` 只扫描
     `data/<modid>/patchouli_books/<bookname>/book.json`（`BOOKS_LOCATION = patchouli_books`，maxDepth=2）；
     `assets/<ns>/patchouli_books/<book>/<locale>/…` 只放**内容**（categories/entries）。放错位置**完全不报错**，表现为：
     书不进 BookRegistry → 创造栏/搜索栏都没有 → `/give` 出来的书 tooltip 显示 `guide_book.invalid`。
     ⚠️ 日志里的 `BookContentResourceListenerLoader preloaded N jsons` 只证明**内容**被读到，**不代表书注册成功**，别被它误导。
  2. **`en_us/` 是必需的语言集，不是可选项**（最致命的一个）。Patchouli 的 `BookContentResourceListenerLoader` 有两级机制：
     - **索引阶段**只枚举 `en_us/`：`if (dir.equals(folder) && BookContentsBuilder.DEFAULT_LANG.equals(lang))`，而 `DEFAULT_LANG = "en_us"`；
     - **加载阶段**先试 `file.getPath().replaceAll("en_us", ClientBookRegistry.currentLang)`，**取不到才回退 `en_us`**。
     所以只放 `zh_cn/` 会导致**整本书空白**（只剩 name / landing_text / 空目录），而 `preloaded 26 jsons` 依然照常打印——极其误导。
     正确做法：`en_us/` 与 `zh_cn/` 两套都提供（本项目已两套齐全，各 6 分类 + 18 条目）。
  3. **`neoforge:conditions` 在前置门控上无效——它不是 vanilla 战利品条件**。`ModLoadedCondition` 属于 NeoForge **数据包条件**（注册在 `CONDITION_SERIALIZERS`），塞进 `pools[].neoforge:conditions` 会让**整张战利品表加载失败**：
     `Couldn't parse element ...:focal_decay:grant_observer_manual - Input does not contain a key [type]`，全日志只有这一条 ERROR，表现为书完全发不出来。
     正确做法：注册一个真正的 vanilla 条件类型——`ModLootConditions`（`focal_decay:patchouli_loaded`，注册到 `Registries.LOOT_CONDITION_TYPE`），写在标准的 `pools[].conditions[]` 里。
  4. **战利品表不能走数据生成**：`LootTableProvider.run()` 写盘时统一用 vanilla `LootTable.DIRECT_CODEC` 重新编码，自定义条件键会被静默丢弃。故战利品表手写，advancement 仍走 datagen。
  5. **发书触发器不能用 `has_items`**：新世界/创造模式背包为空，永远不满足 → 拿不到书。必须用 `PlayerTrigger.TriggerInstance.tick()`（即 `minecraft:tick`），它在玩家存在于世界中的第一个 tick 就满足。
  6. **`creative_tab` 写错不会报错**，只是静默不进栏——排查时先核对 `ModCreativeTabs` 里 `register("...")` 的真实字符串（本项目是 `focal_decay_tab`）。
  7. **物品模型纹理路径要全限定且带 `textures/`**：1.21 应写 `patchouli:item/book_gray`（对应 `assets/patchouli/textures/item/book_gray.png`）。写成 `patchouli:items/book_gray` 会静默变成紫黑方块。
  8. **JSON ≠ SNBT**：条件编码结果不能用 `TagParser.parseTag` 解析（数组语法不同）。
- **调试用 `/give` 写法（注意引号！）**：`/give Dev patchouli:guide_book[patchouli:book="focal_decay:observer_manual"]`
  不加引号时参数解析器把无限定名按 `minecraft:` 命名空间补全 → 变成 `minecraft:focal_decay` → 书无效 → tooltip 触发 Patchouli 自身的崩溃（`item.patchouli.guide_book.invalid` 的传参 bug）。书 ID 是 **`focal_decay:observer_manual`**（= 目录名），不是 tab 名。
- **手册贴图现状**：`assets/focal_decay/models/item/observer_manual.json` 用 `patchouli:item/book_gray`（灰色书壳）+ 自定义手持 display。想换成专属贴图只需放 `assets/focal_decay/textures/item/observer_manual.png` 并把 `layer0` 指向它。
- **待做**：阶段 6（实机逐页核对手册排版与中文换行）
- **阶段 4/5 已完成（2026-09-11）**：
  1. **JEI 插件**（`compat/jei/`，仅 JEI 存在时由 JEI 发现并加载）。**核心原则：「怎么获得」用信息页，「能派生出什么」用自定义类别**：
     - **信息页**（`addItemStackInfo`）：七枚碎片、原型、未激活 OBSR-EX、生物稳定模型、基座、训练终端。
       这是 JEI 的原生语义——对物品按 R 就是"来源"，信息页直接挂在该物品的配方页里，
       **不依赖催化剂方向**，因此不会被归到"用途(U)"。
     - **「观测模型派生」类别**（`ModelDerivationCategory`）：原型 → 五个型号。结果模型放在**输出槽**，
       对型号按 R 即可查到来路。⚠️ 若放成输入槽，JEI 会当成"用途"，方向就反了——这是上一版被归错的原因。
     - 催化剂只挂训练终端与王座（派生实际发生的地方），**不再**挂到碎片/模型自己身上。
     - 背景纯代码绘制（`GuiGraphics.fill`），不用贴图。
  2. **新增配方**（`ModRecipeProvider`，2026-09-11）：
     - **生物稳定模型**：`GRG / LOL / GAG`（G=玻璃 R=红色染料 L=拴绳 O=原型 A=紫水晶碎片）
     - **训练终端**：`CRC / EBE / COC`（C=铜块 R=红石粉 E=末影之眼 B=书 O=原型）—— 此前该方块**完全没有配方**
     - **候选观测者 OBSR-3**：`OEO / EXE / OSO`（O=原型 E=末影之眼 X=**已激活的 OBSR-EX** S=下界之星）。
       以"工作中的 OBSR-EX"为材料，呼应主线；合成消耗那枚 EX。
     - 手册同步：`fragments/candidate` 中英各加一页 `patchouli:crafting`「构造」，说明以已激活 OBSR-EX 为核心合成。
  3. **JEI 派生类别的输入槽必须随条目变化**：`ModelDerivationCategory.Entry` 增加 `input` 字段。
     早先把输入槽写死成原型，导致"OBSR-EX（工作中）"被关联到原型——实际它的起点是**未激活的 OBSR-EX**，
     而 OBSR-3 的起点是**已激活的 EX**。写死输入槽会让整张派生图的箭头指向错误。
  4. **OBSR-EX 复制损耗（2026-09-11）**——修掉一个严重的平衡漏洞：复制配方**不消耗原件**
     （`assemble` 只设产物数量，输入扣除走原版 `getRemainingItems`，而它没被重写），
     所以"已训练模型 + 空白模型 → 2 份副本"可以无限增殖，等于无限个半径 32 的硬保护场。
     现在的规则：
     - `ObserverModelData` 新增 `copies` 代数（`optionalFieldOf(..., 0)`，**旧存档模型自然读成原件**）；
       所有构造点都透传 `copies`，避免训练/喂碎片/生物能量等流程把代数清零
     - 半径随代数**加剧递减**：`32 → 22 → 12`（三角数损耗，`total_stability_copy_penalty` 默认 10）
     - `total_stability_max_copies` 默认 2：二代之后不可再复制
     - 用副本合成的 OBSR-3 训练量更高：`+50 / +150`（`total_stability_copy_train_penalty`，三角数递增）
     - 未激活的 EX 不在可复制列表里 → **复制只能在王座仪式之后进行**；仪式产出的是新造原件（代数 0）
     - "是否练满"的判定统一到 `ObserverModelData.candidateComplete()` / `requiredCandidatePoints(int)`，
       **服务端与客户端共用**（客户端只有 `PrototypeData`，拿不到 ItemStack，两边各写一份必然漂移）；
       `PrototypeData` 因此新增 `copies` 字段并同步
     - tooltip 显示"第 N 代副本"；手册「型号规格」中英各加两页说明复制损耗
  5. **JEI 兼容两个自定义序列化器的配方**（`SpecialRecipeCategory`）：复制模型、碎片知识注入。
     这两条用 `CustomRecipe` 序列化器，**JEI 不会自动展示**，必须显式登记类别与配方，
     否则玩家在 JEI 里看不到"模型可以复制""碎片可以喂给候选体"。碎片喂食用 `addItemStacks`
     把七枚碎片放进同一个槽轮播。
  3. **JEI 四个坑（都踩过，务必记住）**：
     - **槽位坐标是图标中心**，不是左上角。按左上角传值会让图标整体偏左上约 8px。
     - **文字宽度由布局包围盒决定，与类别宽度无关**：只在左侧放一个槽时文字区只有几像素可用 →
       「一行 4 个字 + 省略号」。解法是放一个零尺寸空绘制物把右边界撑开；不能用空槽代替
       （`IRecipeSlotBuilder` 没有可见性开关，空槽会连槽框一起画出来）。
     - **`needsRecipeBorder()` 默认返回 `true`**：JEI 会再画一圈边框并向内占边距，与自绘边框叠成"两层灰框"，
       还会盖掉贴图右边与下边。自绘背景必须重写为 `false`。
     - **不要用 `IGuiHelper.createDrawable` 贴图做背景**：它走 JEI 的精灵/图集路径，实测 168×108 的图
       只画出左上角约 112×44。结论：背景改为**纯代码绘制**，`textures/gui/source_page.png` 已删除。
     - **关系方向**：JEI 的"用途(U)"来自催化剂，"来源(R)"来自"哪些配方把该物品作为输出"。
       想让自己写的信息出现在 R 侧，就用物品信息页或把它放成输出槽。
  3. **tooltip 指引**：碎片 tooltip 增加「来源」行（`SemanticFragmentItem` 新增 `sourceKey`，与 JEI 共用同一批文案 key）；
     空白模型 tooltip 增加「训练三步」下一步指引
  4. **中英 lang key 完全对齐**（各 121 条，脚本校验）
- **实机待验证项**：~~Patchouli 与现有 JEI 共存加载~~（**已验证：共存正常**）；~~手册能否正常加载~~（**已验证：Patchouli 预加载 26 个 json 全部成功**）；~~创造栏与搜索可见~~（**已验证**）；~~进服发书~~（**已验证**）；~~两卷位置锁~~（**已验证：初始锁定、`/focaldecay unlock` 后可见**）；手册逐页排版与中文换行

### 13.1 客户端启动卡死（2026-09-11 **已定位并绕过**，与本模组无关）
- **症状**：`runClient` 在资源重载约 88% 处**卡死**（不是崩溃），窗口"无响应"，只能强杀进程。强杀后 Gradle 报退出码 `-805306369`（NTSTATUS `0xCFFFFFFF`）——**该码只是强杀的产物，不是错误信息**。日志停在 `Missing sound for event: minecraft:item.goat_horn.play`，无 Java 堆栈、无 crash-report、无 hs_err。
- **定位手段**：`jstack <pid>` 抓线程转储（`jcmd` 附加会被拒，`jstack` 可用）。转储直接给出：
  ```
  "Render thread" RUNNABLE cpu=5421.88ms elapsed=61.65s
    at org.lwjgl.openal.SOFTHRTF.nalcResetDeviceSOFT(SOFTHRTF.java:101)
    at com.mojang.blaze3d.audio.Library.setHrtf(Library.java:135)
    at com.mojang.blaze3d.audio.Library.init(Library.java:91)
    at net.minecraft.client.sounds.SoundEngine.loadLibrary(SoundEngine.java:146)
  ```
- **根因**：主线程阻塞在 **OpenAL 的 `alcResetDeviceSOFT`（HRTF 初始化）**，永不返回。源码逻辑（`com/mojang/blaze3d/audio/Library.java`）：
  ```java
  this.setHrtf(alccapabilities.ALC_SOFT_HRTF && enableHrtf);   // enableHrtf=false 时依然调用
  private void setHrtf(boolean enableHrtf) { if (ALC10.alcGetInteger(device, 6548) > 0) { SOFTHRTF.alcResetDeviceSOFT(...); } }
  ```
  **只要音频驱动声明支持 `ALC_SOFT_HRTF`，MC 就必定调用 `alcResetDeviceSOFT`，即使 `directionalAudio:false`（`options.txt` 里本来就是 false，照样卡）。** 属音频驱动/设备层问题，与模组、与游戏设置都无关。
- **绕过方案（已验证可行）**：启动前设置环境变量 `ALSOFT_DRIVERS=null`（OpenAL Soft 走 null 后端，不碰真实音频设备）：
  ```powershell
  $env:ALSOFT_DRIVERS = 'null'; .\gradlew.bat runClient
  ```
  验证结果：**成功进入主菜单**（日志出现 `Minecraft: Stopping!`，即由玩家正常退出）。代价是**没有声音**。
- **建议的根治方向**：更新/回滚音频驱动；在"声音设置"里换一个默认输出设备（很可能是当前默认设备——USB 耳机/虚拟声卡——的驱动在 HRTF 重置时不返回）；或干脆用 null 后端开发（本项目 `options.txt` 里 `soundCategory_master:0.0`，本来也没在听声音）。
- **排除过程（三项对照，均复现同一卡死点）**：① `focal_decay + JEI + Patchouli`；② 摘掉 JEI/Patchouli；③ 再摘掉 mixin 配置（等价"无本模组"）。→ 证明与本模组、与 JEI/Patchouli 无关。
- **顺带确认的好消息**：日志出现 `patchouli: BookContentResourceListenerLoader preloaded 26 jsons`，即 **6 个分类 + 18 篇条目 + book.json 全部被 Patchouli 成功加载**；JEI 与 Patchouli 共存加载也正常。
- **保留的诊断设施**：所有运行配置注入 `-XX:ErrorFile=hs_err_pid%p.log`、`-XX:+HeapDumpOnOutOfMemoryError`、`-XX:+PrintCommandLineFlags`；另有运行配置 `runClientNoEarlyWindow`（禁用原生进度窗 + LWJGL 调试输出），用于区分原生层与游戏逻辑层问题。

## 关键约定与注意事项

1. **AI 守则**：默认 GBK，编辑文件用 UTF-8
2. **NeoForge 21.1 差异**：Capability → attachment；`EntityTypeTags.create` 需要 String 参数（用 `TagKey.create`）；`SavedData.Factory` 三元组构造
3. **反编译源码位置**：`~/.gradle/caches/neoformruntime/intermediate_results/decompile_*_output.jar`（查 MC 类）；`~/.gradle/caches/modules-2/.../neoforge-21.1.248-sources.jar`（查 NeoForge 类）
4. **数据生成**：改 `data/` 下 Provider 后跑 `.\gradlew.bat runData`，输出到 `src/generated/resources`
5. **编译验证**：`.\gradlew.bat compileJava`；完整构建 `.\gradlew.bat build`
6. **配置**：`FocalDecayConfig` 里的 Server 值在 `FocalDecayConfig.BASE_INTERVAL` 等处读取，末日阶段系统后续接入
7. **兼容模组（Patchouli / JEI）**：均为可选依赖，坐标在 `gradle.properties`（`jei_version` / `patchouli_version`），仓库 `https://maven.blamejared.com`；JEI 用 `compileOnly` + `localRuntime`，Patchouli 另加 `:api` classifier。**不要**在 `run/mods` 里重复放这两个 jar（会与 gradle 提供的那份冲突），手工 jar 已归档到 `run/mods.disabled`
8. **查 API 签名的可靠姿势**：`javap -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar <类名>`；查源码用同目录的 `neoforge-21.1.248-sources.jar`（含 MC 与 NeoForge 双方源码，可直接确认补丁点行为）
9. **跑客户端前先设音频后端（本机必需）**：`$env:ALSOFT_DRIVERS = 'null'`，否则会卡死在 OpenAL 的 `alcResetDeviceSOFT`（HRTF 初始化），表现为"加载到 88% 无响应"，强杀后 Gradle 报 `-805306369`。详见 §13.1
10. **卡死时怎么定位**：`jps -l` 找 `net.neoforged.devlaunch.Main` 的 pid → `jstack <pid>`（**`jcmd` 附加会被系统拒绝，`jstack` 可用**），直接看 `"Render thread"` 的栈
