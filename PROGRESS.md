# Focal Decay 开发进度清单

> 最后更新：2026-08-20
> 环境：NeoForge 21.1.248 / Minecraft 1.21.1 / Parchment 2024.11.17 / Java 21
> Mod ID：`focal_decay`，包：`com.zhizhiwang.focal_decay`
> 当前状态（2026-08-20）：`compileJava` 通过；`build/libs/focal_decay-1.0.0.jar` 构建于 2026-08-20 09:02。§9 重构整批改动尚未提交（含根目录误提交的 `net/minecraft/*.class` 删除），本地领先 origin/main 4 个提交。

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

### 5. 全局池与确定性随机（完成）
- `mutation/MutationPool.java`：按 BuiltInRegistries.BLOCK id 升序的不可变列表，带 version
- `mutation/MutationHelper.java`：`getTarget(original, pos, worldSeed, periodIndex, pool)`，种子经 SplitMix64 雪崩混合；统一入口 `getVisibleTarget(...)`（含概率、保护、诞生周期）
- `mutation/MutationPoolManager.java`：维度级 SavedData，管理有效原型机效果（位置/半径/模型数据）+ 方块诞生周期 + 全局池，`getEffectivePool(pos, original)`、`isProtected(pos, state)` 按模型判定
- `mutation/MutationEventHandler.java`：原型机放置/换模/破坏更新、维度加载 reloadGlobalPool；已注册游戏总线
- 旧 `RegionOverride`（覆盖列表）与突变控制器覆盖逻辑已删除（2026-08-19，由原型机效果取代）

### 6. 交互与转换（完成）
- `attachment/BreakData.java` + `ModAttachments.java`：玩家挖掘锁定数据（用 NeoForge 21.1 attachment 替代旧 Capability）
- `mutation/InteractionHandler.java`：LeftClickBlock 锁定目标 + BreakEvent setCanceled(true) 后按目标方块生成掉落/经验；创造模式跳过

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

### 8. 末日阶段系统（完成）
- `mutation/FocalDecayWorldData.java`：全局天数 SavedData（`days` + 部分 tick），每 20 分钟游戏日（24000 tick）+1，玩家数为 0 暂停，`ServerTickEvent.Post` 驱动；天数变化经 `SyncWorldDataPacket` 广播，登录/换维时补发
- 阶段判定与周期：`MutationHelper.currentStage(days)`（对照 `stage2_day/stage3_day`，`enable_stage_system=false` 恒为阶段 1）、`intervalForStage`（100/60/40 tick）；服务端与客户端（同步天数）共用
- 阶段影响范围（§6.3，含"不完整方块排除转换源"修复）：`MutationHelper.isConversionSource` —— 阶段1 仅完整方块（`isCollisionShapeFullBlock`）；阶段2+ 增加非完整但有碰撞箱方块（栅栏/玻璃板/台阶）；空气/方块实体/黑名单始终排除；阶段3 影响范围与阶段2 一致
- 实体突变（§6.4）：`mutation/DoomsdayHandler.java` 每阶段周期掷确定性骰子（`mix64(worldSeed ^ pos.asLong() ^ tick)`），`Mob`（排除玩家）从三阶段实体池转换（阶段1 被动 / 阶段2 +中立 / 阶段3 +敌对，源池=目标池），NBT 复制（去 UUID）替换实体；`ItemEntity` 掉落物目标物品改为方块池随机方块物品
- 客户端 `ClientRenderCache` 接入阶段：同步 `worldDays`，`isCandidate`/`computeTarget`/`resolve`/扫描全部按阶段走源范围，阶段变化自动清缓存重算
- 测试命令（2026-08-13）：`/focaldecay days [<n>]` 查询/设定末日天数（设定需权限 2），经 `SyncWorldDataPacket` 广播后客户端即时重算阶段
- 实体突变不生效修复（2026-08-13）：`lastEntityMutationTick` 曾初始化为 `Long.MIN_VALUE`，`serverTick - MIN_VALUE` 溢出恒为负、周期判断永假导致实体转换从不触发；已改为初始 0 并加防回归注释
- 实体突变 NPE 修复（2026-08-13）：遍历活动实体列表期间 `discard`/`addFreshEntity` 会引入 null 墓碑，导致 `entity.isAlive()` 空指针；改为先拷贝快照再遍历并做 null 判空
- 实体转换 NBT 白名单重构（2026-08-13）：`EntityMutation` 抽象出跨物种保留字段白名单（Age/ForcedAge/Health/CustomName/CustomNameVisible/PersistenceRequired/Tags/ActiveEffects），位置/朝向复制、速度清零，飞行/物理/渲染瞬态标志（如 NoGravity）从根上丢弃，替代原先整份 NBT 复制后逐个打补丁的做法
- 累积转换（2026-08-13）：方块失焦改为"有记忆"累积（未抽中的保留上次材质而非回退原方块），实现为回退扫描最近抽中周期
- 移除空气↔方块转换（2026-08-19）：阶段3 的"方块→空气 / 空气→方块"逻辑、配置项（`block_to_air_chance_stage3`/`air_to_block_chance_stage3`）与客户端空气预览全部删除，需求从大纲移除，恢复为仅方块材质间的确定性转换
- 方块诞生周期（2026-08-13）：玩家放置的方块记录诞生周期（`MutationPoolManager` 维度级持久化 `Map<BlockPos, Long>`），`getVisibleTarget`/`cumulativeTarget` 增加起始周期，放置瞬间及同周期保持原方块、之后才崩坏；放置/破坏事件维护并广播，`SyncRegionDataPacket` 携带诞生周期同步客户端，锚固化与破坏转换同样尊重

## 未完成（按推荐实现顺序）

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
  6. 完全稳定锚：仪式升级、半径 32 完美稳定、特殊视觉；完全稳定模型第一枚末影龙掉落、后续"已激活模型 + 空白模型"复制——**已完成（2026-08-20）**：仪式升级（原型机插槽升级为 `total_stability_model_activated`，不覆盖原有模型）、半径 32 完美稳定（`radiusFor(TYPE_TOTAL)=32` + `isProtected`）、复制配方（`CopyTrainedModelRecipe`）、末影龙掉落（`DragonDropHandler`，配置 `ender_dragon_total_stability_drop_chance`，默认 1.0 必掉、可调成设计文档的稀有掉落）、特殊视觉（`TotalStabilityFieldHandler` 旋转光环粒子 + 锚上方漂浮粒子）
  7. 与末日阶段/渲染/网络整合（阶段3模型效果衰减等）
  8. 彩蛋与打磨（粒子/音效/专属贴图/测试）

### 10. 网络通信（承接现有实现）
- `SyncRegionDataPacket`（S→C）：**已完成扩展**——携带有效原型机效果（位置/半径/模型数据）+ 方块诞生周期；登录/换维/原型机变化/方块放置破坏时发送
- `ObserverCoreActivatePacket`（S→C）：核心激活时全服动画——**未实现**
- `ThroneRitualPacket`（S→C，新增）：王座仪式进度/波次/完成同步——**未实现**
- 使用 NeoForge 21.1 Payload API（现有 `ModNetwork` 基础上扩展），协议版本 "1"

### 11. 观测者核心修复路径（保留）
- **现状（2026-08-20）**：`observer_core` 方块已注册（含 powered 状态，亮度随状态变化）；右键 GUI、激活流程、动画/粒子、战利品注入均未实现
- 观测者核心块：右键 GUI（"观测者离线/在线"）、用重建协议激活（动画+粒子，powered=true，触发胜利）
- 语义碎片来源：玫瑰失焦突变、王座/末地城结构、村庄战利品、首次右键核心、铜块突变、所有战利品箱、原型机合成
- 战利品注入（碎片）：`GlobalLootModifier` 或 LootTableLoadEvent

### 12. 收尾
- 原型机 GUI 与训练终端 GUI：自定义占位贴图已替换（`assets/focal_decay/textures/gui/*.png`），专属模型/细化待做
- 粒子（蓝色漂浮、"42ms"、"完备语义分类"字样）
- 测试与平衡调整

## 关键约定与注意事项

1. **AI 守则**：默认 GBK，编辑文件用 UTF-8
2. **NeoForge 21.1 差异**：Capability → attachment；`EntityTypeTags.create` 需要 String 参数（用 `TagKey.create`）；`SavedData.Factory` 三元组构造
3. **反编译源码位置**：`~/.gradle/caches/neoformruntime/intermediate_results/decompile_*_output.jar`（查 MC 类）；`~/.gradle/caches/modules-2/.../neoforge-21.1.248-sources.jar`（查 NeoForge 类）
4. **数据生成**：改 `data/` 下 Provider 后跑 `.\gradlew.bat runData`，输出到 `src/generated/resources`
5. **编译验证**：`.\gradlew.bat compileJava`；完整构建 `.\gradlew.bat build`
6. **配置**：`FocalDecayConfig` 里的 Server 值在 `FocalDecayConfig.BASE_INTERVAL` 等处读取，末日阶段系统后续接入
