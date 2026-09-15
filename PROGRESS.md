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

3. **可选依赖必须做"类加载级"隔离**（2026-09-12，**曾导致未装 Patchouli 时整个 mod 构造失败**）：
   手册宏最初直接写在 `ClientSetup` 里，结果未装 Patchouli 的整合包启动即崩：
   ```
   Failed to register automatic subscribers. ModID: focal_decay
   java.lang.NoClassDefFoundError: vazkii/patchouli/api/IStyleStack
   → Failed to wait for future Mod Construction, 1 errors found
   → 后续满屏 "Cowardly refusing to send event ... broken mod state"
   ```
   **根因在字节码层面**：javac 把 lambda 编译成合成方法 `lambda$xxx$0(IStyleStack)`，签名里带着
   Patchouli 的类型；而 `ClientSetup` 挂着 `@EventBusSubscriber`，**NeoForge 会无条件加载它**，
   JVM 校验该类时就必须解析 `IStyleStack`。于是：
   - **`ModList.isLoaded(...)` 判断和 `try/catch` 都拦不住** —— 它们运行在方法体内，
     而失败发生在方法被调用之前的<b>类加载/校验阶段</b>；
   - 这类问题不会在开发环境暴露（我们总是装了 Patchouli）。
   **正确做法**：把引用可选依赖的代码放进<u>只在确认该依赖存在后才会被加载</u>的独立类
   （本项目：`compat/patchouli/GuideMacroCompat`、`compat/jei/*`），调用方只保留
   字符串形式的 modid 检查 + 一次静态方法调用。
   **验证方式**（已实测通过）：临时注释掉 `localRuntime`，`runClient` 启动到主菜单，
   日志无 `NoClassDefFoundError`。另可用 `javap -c` 扫描字节码，确认宿主类里不残留第三方类型。
   同类扫描结果：目前仅 4 个类引用可选依赖 —— `GuideMacroCompat`（Patchouli）与
   JEI 的三个类（由 JEI 自己的插件扫描器加载，不装 JEI 不会加载），全部已隔离。

### 13.2 实机反馈修复（2026-09-11 第二轮）
1. **右键交互按可见目标响应**（`InteractionHandler.onRightClickBlock`，`EventPriority.HIGHEST`）：
   此前工作台、切石机、织布机这类"完整立方体 + 有右键行为 + **无方块实体**"的方块，
   视觉上已变形成别的方块，右键却仍打开原有界面。现在与挖掘同一思路——先真实转换，再让**目标方块**
   接管这次右键（`ServerPlayerGameMode.useItemOn` 重派发）。副作用：右键也会把方块变掉，这是刻意取向。
   - ⚠️ **创造模式不能跳过右键转换**（2026-09-12 二修）：客户端的幽灵预览对**所有游戏模式**都生效，
     所以只在生存模式转换会让创造模式玩家看到"显示成石头、右键却打开合成台"的前后不一致。
     挖掘那边可以跳过是因为创造挖掘本就不产生掉落、无需转换；右键没有这层理由。
     （第一版照抄了挖掘的 `isCreative()` 早退，导致创造模式下右键行为完全没变——而测试正是在创造模式做的。）
   - ⚠️ **日志一律用 ASCII**：本项目控制台是 GBK，日志里写中文会变成乱码，反而看不出关键信息
     （正是乱码掩盖了 `creative=true` 这一决定性线索）。
   - ⚠️ **必须显式设置取消结果**（2026-09-12 修复；第一版漏了，是"工作台照旧打开"的真正原因）：
     `RightClickBlock.cancellationResult` 默认是 `InteractionResult.PASS`，而
     `ServerPlayerGameMode#useItemOn` 写的是 `if (event.isCanceled()) return event.getCancellationResult();`
     —— 只 cancel 不设结果等于告诉原版"我没处理，继续走"。后果两层：① 它**已抓取的 blockstate**
     （转换前的方块）继续走 `useWithoutItem` → 合成台照旧打开；② 返回非 `consumesAction()` 后
     还会尝试 `stack.useOn(...)`，顺手放置手里的方块。现在在 `setCanceled(true)` **之后**调用
     `setCancellationResult(InteractionResult.FAIL)`（顺序重要：`setCanceled` 会重置结果）。
   - 重派发会再次触发 `RightClickBlock`，用 `ThreadLocal` 旁路标志防无限递归
   - 创造模式跳过（与挖掘一致，不执行转换）；只手主手处理，避免双手重复转换
   - 顺带把 `onLeftClickBlock` 里重复的"观测者在线 / 当前阶段"判定抽成 `mutationsActive` / `currentStage`
   - 诊断：`/focaldecay trace true` 打印右键判定链（真实方块 / 是否转换源 / 可见目标 / 是否转换）
2. **"六面被遮挡就跳过材质替换"的判据错了**（`ClientRenderCache.isExposed`，2026-09-12 修正）：
   该优化本身是对的——方块六面都被挡住时不必替换材质，省性能。但判据用的是
   `neighbor.isAir() || !neighbor.canOcclude()`，而 **`canOcclude()` 只是"有能力遮挡"的开关**：
   雪片（高度 2/16）、半砖都是 true，却只挡住相邻面的一小部分。于是这类方块围住的目标
   被判为"不可见"、不参与材质替换，而玩家明明看得见 —— 表现为"被雪覆盖的方块看起来没突变"。
   - 也不能用 `isSolidRender`：它要求**碰撞形状**填满整格（`canOcclude && 形状是完整方块`），
     雪片同样为 false，一刀切掉太多。
   - 正确判据是**邻面遮挡形状是否为完整面**（`coversFaceFully`）：用
     `state.getFaceOcclusionShape(level, pos, face.getOpposite())` 的包围盒判断是否覆盖整面。
     这正是原版 `Block.shouldRenderFace` 所用的口径，因此与游戏自身的剔除判定一致。
   - 顺带**撤掉了上一轮加在 `SectionCompilerMixin` 里的 `isSolidRender` 重定向**：那是基于错误诊断加的
     （我当时以为是可见性图的问题）。它不仅多余，还会在"目标形状与原始形状不同"时剔掉真正可见的邻面，
     属于引入新问题的修法。该 mixin 现在只保留 `getBlockState` 一处重定向——它已经覆盖了编译期所有读取，
     包括 `Block.shouldRenderFace` 内部的 `level.getBlockState`（因为 `level` 就是 `RenderChunkRegion`）。
3. **手册阶段日程改为动态取配置**：正文原先把"你有大约七天"硬编码（中英各一处），改 config 就与事实不符。
   现在正文写 `$(focal_decay:schedule)`，由 `ClientSetup` 注册的 Patchouli 宏在渲染时读
   `stage2_day / stage3_day`（挂在 `RegisterMenuScreensEvent` 上：早于书内容构建、晚于配置加载）。
   文案 key `book.focal_decay.schedule`。未装 Patchouli 时整段跳过（方法引用也会触发类加载，必须先查 `ModList`）。

### 13.3 后处理动画的抽动感（2026-09-12 修好，历经四轮）
用户的两次观察直接锁定了根因：**"突变间隔约 1 秒"** 与 **"Time 一秒内从 0 到 1 再重置"**。

1. **`Time` 是内置且每帧被覆写的 uniform，且它每秒硬回绕 —— 这是本质约束。**
   `PostChain`：
   ```java
   this.time += partialTicks;
   while (this.time > 20.0F) { this.time -= 20.0F; }
   postpass.process(this.time / 20.0F);      // -> Time ∈ [0,1)，每秒（20 tick）回绕
   ```
   shader 里写 `sin(... + Time * TAU * f)` 时，回绕点的相位差是 `2π·f` ——
   **只有 f 取整数才连续**。换言之：**用内置 `Time` 就永远做不出周期长于 1 秒的平滑动画**，
   最慢的非零平滑周期就是 1 秒。（`EffectInstance` 只注册 program JSON `uniforms` 数组里的 uniform，
   `Time` 在其中，所以 `setUniform("Time", …)` 必然被覆盖，是无效代码。）
   - 我先用 `Time * 3.0`（相位跳 3.0 弧度），后来改成 `Time * TAU * 0.35`，
     **两者都不连续** —— 后者只是把跳变换了幅度，数学上同样不成立。
   - **正确的修法：自建连续时间**。program JSON 与 post chain JSON 都声明 `TotalTime`（float 1），
     Java 每帧 `setUniform("TotalTime", veilTime)`，shader 用它算相位。vsh/fsh 里只依赖这个自建值。
2. **`getRealtimeDeltaTicks()` 的单位是 tick，不是秒**：源码 `(time - lastUiMs) / msPerTick`，
   `msPerTick = 1000/20 = 50ms`，60fps 下一帧 0.333，即每秒累加 20。
   当秒用会让所有周期快 20 倍（"20 秒呼吸"实际 1 秒，恰好与 `Time` 回绕周期重合）。
   现在显式 `/ 20.0F`。
3. **时间源要用「每帧真实时间」**而非 `getGameTimeDeltaTicks()`（后者只在 tick 帧非零，相位呈锯齿）。

**最终参数**：呼吸 20 秒（`0.85 ± 0.15`，cos）；波纹 `0.35` 与 `0.20` 周期/秒
（周期约 2.9s / 5.0s，非整数无妨，因为 `TotalTime` 不回绕）；空间频率 `13.0` / `5.0`；
幅度 `0.0007 / 0.0003`（合计约 0.85px@1920）；色散 `0.0007`；偏色 `0.08`。
**判据：看得出波纹在"走"就是太强**，目标是"感觉画面不安定，但说不出哪里在动"。

> **通用教训**：引擎内置的 uniform / API 语义（取值域、回绕、单位、是否被覆写）**必须读源码确认**。
> 这个 bug 连修四轮，前三轮都是凭命名推测 `Time` 与 `getRealtimeDeltaTicks()` 的含义，
> 而两次关键突破都来自用户的实际观察。

### 13.4 结构里的观测者基座随机塞模型（2026-09-12，已端到端验证）

**目标**：玩家用结构方块搭好的结构，放下去时基座里就自带一个随机稳定模型（开箱即取）。

**为什么用 `StructureProcessor` 而不是"直接改方块实体"**：
`StructureProcessor.process` 在方块**真正放置之前**被调用，此时方块实体还不存在，改不到它。
正确做法是**返回带 NBT 的 `StructureTemplate.StructureBlockInfo`** —— 原版"宝箱自带战利品"
就是这个机制。（`StructureModifier` 是定义期改结构元数据的，做不到这件事。）

**已实现**：
- `structure/AnchorModelProcessor.java`（处理器，注册名 `focal_decay:anchor_model`）
- `structure/SingleTemplateStructure.java` + `SingleTemplatePiece.java`
  （结构类型 `focal_decay:single_template`：一份 JSON = 一个 NBT 模板 + 内联处理器，
  不需要 template_pool / processor_list；**默认自带** `AnchorModelProcessor`）
- `ModStructures.PROCESSOR_TYPES` / `SINGLE_TEMPLATE_PIECE` / `SINGLE_TEMPLATE`，均已在主类注册
- `AnchorPrototypeBlockEntity.TAG_MODEL` 提升为 `public`
- 战利品表 5 个：`structure/anchor_model.json`（权重 6/3/1 三档嵌套引用）
  + `common`（空白 ×3 / 生物稳定 ×1）、`rare`（语义锁 ×1，8 个石质目标，强度 1.0）、
  `unique`（引导 ×1，q=0.75，`focal_decay:concept/stone`）
- 参考结构 `focal_decay:sample_anchor`（7×7 基座 + 中心锚），NBT 在
  `data/focal_decay/structure/sample_anchor.nbt`
- 验证脚手架：`tools/`（见 `tools/README.md`）

**读源码确认的关键点（都是踩过就白干的地方）**：
1. 嵌套引用条目的类型是 **`minecraft:loot_table`**（类名 `NestedLootTable`，不是 `LootTable`），
   字段名是 **`value`**（`Codec.either(ResourceKey, LootTable)`），权重/条件来自 `singletonFields`。
   原版先例：`chests/trial_chambers/reward.json`。
2. **处理器回调里 `relativeBlockInfo.pos()` 已经是世界坐标**（`processBlockInfos` 里
   = 相对坐标 + offset），`blockInfo` 才是模板原始坐标。返回值决定最终放置位置，别搞反。
3. `ItemStack.saveOptional()` 在 NeoForge 里返回 **`Tag`**（不是 `CompoundTag`）；
   用 `saveOptional` 与方块实体的读写保持一致。
4. 引导模型的 **`stabilityStrength` 就是存起来的完备度 q**，`concept` 是**方块标签 id**；
   `trainedTargets` 只在训练时参与解析、以及 tooltip 显示，运行时不再用。
   语义锁的 `trainedTargets` 才是"锁定哪些方块"的真数据。
5. `LootTable.getRandomItems` **不校验参数集**，所以表里写 `"type": "minecraft:generic"`
   而用 `LootContextParamSets.COMMAND`（只需 ORIGIN）建上下文是安全的。
6. **`StructureSettings` 的 `spawn_overrides` 是必填**（`fieldOf` 而非 `optionalFieldOf`）。
   漏了会报 `No key spawn_overrides in MapLike` 并**拒绝加载整个存档**，空对象 `{}` 即可。
7. **结构模板 NBT 的 `size` 与每个方块的 `pos` 必须是 `TAG_List<TAG_Int>`，不是 `[I; ...]`。**
   写成 int 数组时 `CompoundTag.getList("pos", 3)` 返回空列表 → 所有方块**静默堆到 (0,0,0)**，
   `/place template` 还照样报成功。这个坑没有任何报错，只能靠探针发现。
8. **函数里抛异常会中断整个函数**：`/data get block`、`execute if data block` 在目标位置没有
   方块实体时抛 `CommandSyntaxException`，后面命令全不执行 —— "函数跑一半停了"先怀疑它。

**用法（数据侧）**：结构 JSON 写
```json
{
  "type": "focal_decay:single_template",
  "template": "focal_decay:自己的模板名",
  "biomes": "#minecraft:is_overworld",
  "step": "surface_structures",
  "spawn_overrides": {},
  "y_offset": 0
}
```
`processors` 默认就是 `[{"processor_type": "focal_decay:anchor_model"}]`，不用写；
要换战利品表就显式写出来并加 `"loot_table": "命名空间:路径"`，写 `[]` 则完全不放模型。
`y`（绝对高度）与 `y_offset`（相对地表）二选一，`y` 优先。
NBT 放 `data/<命名空间>/structure/<模板名>.nbt`。

**验证结论（2026-09-12，无头开发服务器实跑，全部通过）**：
A 模板本体加载/坐标正确 → B `/place structure` 放下且锚里有模型（空白档）→
C 处理器的 `loot_table` 字段生效、`set_components` 的语义锁数据（type / strength / targets）正确 →
D 引导档（type + concept）正确 → **E 真实世界生成（`WorldGenRegion` 路径）同样注入成功**。
跑法与坑见 `tools/README.md`。

> **注意 `/place template` 测不出处理器**：`PlaceCommand` 只在 `integrity < 1.0` 时才
> `clearProcessors().addProcessor(new BlockRotProcessor(...))`，**永远不会带上自定义处理器**。
> 验证必须走 `/place structure`（会走 `StructurePlaceSettings`）或真实世界生成。
>
> 另外**王座结构已改为数据驱动模板**（见 §13.5），处理器对它同样不生效
> —— 王座里没有观测者基座（2026-09-12 与用户确认），本来也不需要。

### 13.5 末地王座改为 NBT 模板（2026-09-12，已端到端验证）

用户重新搭了王座并导出 `throne.nbt`（13×18×13），要求**替换掉原来的代码版结构**。
里面没有 `anchor_prototype`（用户明确选择不加），但保留了 `observer_core`。

**做法**：`ThroneStructure` 继续作为结构类型（`focal_decay:end_throne` 的 JSON 不用动，
datagen 也不用重跑），只把"加什么部件"从 `ThronePiece` 换成 `SingleTemplatePiece`：

```java
BlockPos templatePos = pos.offset(-6, 0, -6);   // 模板中心列对齐王座原点
new SingleTemplatePiece(ctx.structureTemplateManager(), TEMPLATE, Rotation.NONE, List.of(), templatePos)
```

**为什么是 −(6,0,6)**：模板 13 宽，中心列是 6。这样模板里的关键方块落点与旧代码版**完全相同**：

| 方块 | 模板坐标 | 世界坐标 |
|------|----------|----------|
| 信标（原点那一格） | (6,0,6) | 原点 +(0,0,0) |
| `observer_core` | (6,1,6) | 原点 +(0,1,0) |
| 王座碎片箱 | (6,1,8) | 原点 +(0,1,2) |
| 四角柱顶末地棒 | (±6,17,±6) | 原点 +(±6,17,±6) |

于是 `ThroneRitualHandler`（`corePos = throne.offset(0,1,0)`、`isThroneBase` 的 ±2 判定）
和 `ThroneBeamRenderer`（四角 ±6、光束起点 +17）**一行都不用改**。
`HALF_X/HALF_Z/BELOW/ABOVE` 更新为 6/6/0/17（= 模板真实范围）。

**改动的文件**：
- `ThroneStructure`：加 `TEMPLATE` / `TEMPLATE_OFFSET_X|Z`，`findGenerationPoint` 改用模板部件
- `ThronePiece`：**降级为空壳**，只保留反序列化（旧存档区块里存着 `focal_decay:end_throne_piece`
  这个 id，从注册表摘掉会让那些区块刷 "Failed Start" 错误）。旧几何代码已删
- `ThroneBeamRenderer`：判定从"原点是 throne_block"改成"原点上方一格是 observer_core"
  （新模板原点那格是信标）
- 新增 `data/focal_decay/structure/end_throne.nbt`

**验证（无头服务器 + 固定种子 20260912，`tools/structuregen/ThronePos.java` 预算坐标）**：
日志 `End Throne structure start at -752, 70, 45 (template at -758, 70, 39), chunk -47, 2`
与预算完全一致；F 段探针全绿：原点信标 ✓、核心在原点+(0,1,0) ✓、箱子在原点+(0,1,2) 且装着
`focal_decay:semantic_fragment_throne` ✓、两根角柱顶末地棒在 ±6/+17 ✓、
**旧代码版的整片 `throne_block` 平台确认不再出现** ✓。
结构跨 4 个区块（-48/-47 × 2/3）也正确摆放，说明跨区块裁剪没问题。

> **注意：新王座的平台是可破坏的。** 旧版整片用 `focal_decay:throne_block`
> （`strength(-1.0F, MAX_VALUE)`，等同基岩，且无掉落物），新版构成是
> `throne_block` 95 + 黑曜石 77 + 紫水晶块 26 + 哭泣的黑曜石 26 —— 后三者玩家能挖。
> 代码里本来也没有强制保护（`ThroneStructure.insideThrone` 声明了但**从未被调用**）。
> 要把平台锁死的话，把 NBT 里那三种方块换成 `focal_decay:throne_block` 即可。

### 13.6 Site-CN-25 地下站点（2026-09-15，已端到端验证）

用户提供 `site-cn-25.nbt`（9×6×9 的白色混凝土房间，SCP 站点风格），要求三件事：
① 里面的观测者基座随机带 OBSR-1/-2，**且带随机训练数据**；其中 OBSR-2 另有概率带
白混凝土 / 磨制闪长岩 / 青翠蛙鸣灯的训练数据；② 箱子按给定八种物品出产，战利品表**挂到 config**；
③ 生成位置在地表到地下一定深度之间，密度参照原版村庄。

**模板清单**（`DumpStructure` 输出，重搭模板时的硬约束）：

| 内容 | 模板坐标 | 说明 |
|------|----------|------|
| `focal_decay:anchor_prototype` | (4,1,4) | 基座；`AnchorModelProcessor` 靠方块 id 找它，位置随意 |
| `minecraft:chest` | (1,1,3) | 箱子；`ContainerLootProcessor` 靠"是不是 RandomizableContainer"找它 |
| `minecraft:training_terminal` | (3,1,1) | 自带 `Energy/FinalType` |
| 外壳 | y=0 地板 / y=5 天花板 / 四周墙 | 全 `white_concrete`，**密封**（水下也不会进水） |
| 物品展示框（末影之眼） | 实体，blockPos (4,2,1) | 模板里唯一的实体，见下面第 6 条 |

**① 埋深：`single_template` 新增 `depth_range`**

```json
{ "depth_range": [1, 8] }
```

含义是**模板最顶层方块位于地表以下的格数**，每次生成在范围内随机取（取的是区块自己的
`ctx.random()`，同种子可复现）。推导：`surface` 是地表第一格空气，所以地表最高方块在
`surface - 1`；要让模板顶面低 depth 格，则 `originY = surface - depth - 模板高度`。
**高度从 `StructureTemplateManager` 读真实模板尺寸**，所以模板加高一层埋深不变，不用手算偏移。

高度三选一的优先级：`y`（写死） > `depth_range`（埋地下） > `y_offset`（贴地表）。
最后一个 `max(minBuildHeight, ...)` 是保险，防止范围配得过大时结构掉出世界后**静默消失**。

**② 随机训练数据：新战利品函数 `focal_decay:random_training`**

原版战利品表能随机"掉什么、掉几个"，但**不能随机一个 DataComponent 内部的字符串列表**。
多写几条权重不同的固定条目只能随机出很小的几种组合，做不出"每一枚都不一样"，所以写了这个函数：

```json
{ "function": "focal_decay:random_training",
  "count": { "type": "minecraft:uniform", "min": 6, "max": 12 },
  "pool":  [ "minecraft:stone", "..." ],
  "extra": [ "minecraft:white_concrete", "minecraft:polished_diorite", "minecraft:verdant_froglight" ],
  "extra_chance": 0.5 }
```

- `count` 无放回抽样条数（洗完牌取前 N，`LinkedHashSet` 顺带保住池子顺序）；
- `extra` 里**每一条独立**掷 `extra_chance`，所以"三条中一条"和"三条全中"都会出现；
- 只认 `focal_decay:observer_model_data` 组件，其余字段（型号/q/概念/进度/代数）原样保留；
- 写在 `set_components` **之后**，否则读到的是默认的空模型。

**③ 站点的两张战利品表**

- `focal_decay:structure/site_cn_25_anchor_model`：只出 OBSR-1（`guided`，q=0.75，
  `concept/stone`，石材池抽 8~16 条）与 OBSR-2（`semantic_lock`，q=1.0，通用建材池抽 6~12 条
  + 三条站点建材各 50%）。**不出原型、不出 OBSR-Health** —— 这是"上一迭代站点人员留下的模型"。
- `focal_decay:chests/site_cn_25`：两池。器材池 1 抽 1（原型 4 / 书 3 / 末影之眼 2 / 基座 2），
  耗材池 2 抽 2（红石 5、青金石 5、铜块 4、末影珍珠 3、书 3）。取向是"补齐入门链条"：
  原型 + 基座 + 末影之眼是开树的卡点，红石/铜块/书/青金石正好是原型的合成材料。

**④ 容器战利品：新处理器 `focal_decay:container_loot` + 配置项 `server.site_loot_table`**

```json
{ "processor_type": "focal_decay:container_loot" }
```

表 ID 取配置项 `server.site_loot_table`（默认 `focal_decay:chests/site_cn_25`；SERVER 配置，
专用服务器上按世界存放，开发环境在 `run/config/focal_decay-server.toml`），
要在这个结构里临时换表就写 `"loot_table": "命名空间:路径"` 覆盖。
配置值非法时只记一条警告并跳过，不让世界生成崩掉。

**读源码确认的关键点（都是踩过就白干的地方）**：

1. **1.21.1 里给结构容器配战利品，正确写法是 NBT 里的 `LootTable` 字符串**，
   网上说的 `components.minecraft:container_loot` **走不通**：
   `BlockEntity.loadWithComponents` 只把 `components` 解析进 `this.components` 字段，
   **不会**回调 `applyImplicitComponents`，于是 `RandomizableContainerBlockEntity.lootTable`
   一直是 null，箱子开了是空的。真正读 NBT 的是各容器自己 ——
   `ChestBlockEntity.loadAdditional` 第一句就是 `if (!this.tryLoadLootTable(tag)) ...`
   （注意 `tryLoadLootTable` 定义在 `RandomizableContainer` 接口上，不在基类里，
   所以只对实现了接口的容器有效）。
2. **`LootTableSeed` 不用自己写**：`StructureTemplate.placeInWorld` 在放置带 NBT 的方块实体时，
   只要它实现了 `RandomizableContainer`，就会自动塞一个随机种子。
3. **`IntProvider` 与 `NumberProvider` 的 `uniform` 字段名不一样**：
   `IntProvider`（如 `set_potion`、`IntProvider.CODEC`）用 `min_inclusive`/`max_inclusive`，
   而 `NumberProvider`（`set_count` 的 `count`、本模组的 `random_training` 的 `count`）用
   `min`/`max`。JSON 形状一样，写错会让**整张战利品表解析失败**：
   `Couldn't parse element ... No key max_inclusive in MapLike[...]`。
   这个函数最初写成 `IntProvider`，结果表整个没加载，处理器只报一句"loot table is missing"。
4. **NBT 匹配里列表是"包含"语义**（`NbtUtils.compareNbt` 的 `compareListTag=true` 分支）：
   `["a","b"]` = a 和 b 都在；`[]` **只**匹配空列表（所以 `unless data` + `[]` 可以断言"非空"）；
   `{Items:[{...}]}` = 箱子里任意一格命中。验证脚本大量依赖这三条。
5. **函数里命令的输出是被抑制的**，`/data get` 不会进日志；要拿到结论只能用 `say`。
   加上"命令抛异常会中断整个函数"，所以探针一律写成
   `execute if block <pos> <方块> run execute if data block <pos> {...} run say ...`。
6. **结构里的悬挂实体（物品展示框/画）放置时会刷 ERROR**：结构方块存的是绝对坐标
   `TileX/Y/Z`，放置时只重写 `Pos`，`BlockAttachedEntity.readAdditionalSaveData` 发现两者
   差 16 格以上就报 `Block-attached entity at invalid position`。**实体位置是对的**
   （该分支只是不覆盖 `Pos`），属原版噪声。本模板里有 1 个（装着末影之眼的展示框，blockPos (4,2,1)）。

**验证结论（2026-09-15，无头开发服务器实跑，全部通过）**：

| 探针 | 结论 |
|------|------|
| `G1_surface_y=63` / `G1_anchor_y=53` | `/place structure` 路径：模板原点 52 → 埋深 5 ∈ [1,8] ✓ |
| `G5_surface_y=63` / `G5_anchor_y=50` | **真实世界生成**（新区块 0,200）：原点 49 → 埋深 8 ∈ [1,8] ✓ |
| `G2_anchor_ok` / `G2_chest_ok` / `G2_loottable_ok` | 固定 y=100 的定点检查：箱子里确实写进了 `LootTable` 字符串 |
| `G2_id_obsr2_lock` / `G6c_obsr1_guided_seen` | 基座里的模型是 OBSR-1 或 OBSR-2，且不带 `blank`/`bio`/`EX` |
| `G6a_all_three_ok` / `G6a_no_stray_ok` | `random_training` 单元测试：抽 2 条 + extra 必中 → 恰好三条、无杂项 |
| `G6b_count_one_ok` / `G6b_extra_disabled_ok` | 抽 1 条就只有 1 条；`extra_chance=0` 时一条附加都不写 |
| `G6c_every_roll_has_training_ok` | 生产表抽 20 次，**每一枚**都带非空训练数据 |
| `G6c_extra_*_seen` | 白混凝土 / 磨制闪长岩 / 青翠蛙鸣灯三种站点建材都出现过 |
| `G3_loot_rolled_ok` | 漏斗能从站点箱子里抽出东西 → `LootTable` 真被读取，不只是写了个字符串 |
| `G7_*_seen` × 8 / `G7_no_stray_ok` | 八种物品全部够得着，且不出表外物品 |

**本次改动**：`SingleTemplateStructure`（`depth_range`）、`ModLootFunctions`（新）、
`ContainerLootProcessor`（新）、`ModStructures.CONTAINER_LOOT_PROCESSOR`、
`FocalDecayConfig.SITE_LOOT_TABLE`、主类注册、`data/focal_decay/structure/site_cn_25.nbt`、
两份 worldgen JSON、两张战利品表；脚手架新增 `NbtTop.java` 与 devtest 的 G 段。

### 13.7 文案复审（2026-09-15，SCP 文风整备）

范围：`lang/zh_cn.json`、`lang/en_us.json`、手册全部 24 篇条目（中英各一套）。
**改的是文本，不动玩法**；两处顺带修掉的是实打实的 bug。

**两个真 bug（都在手册上，之前没人看出来）**：

1. **动态日程从来没显示过。** `GuideMacroCompat` 用 `PatchouliAPI.registerCommand("focal_decay:schedule", …)`
   注册，正文写 `$(focal_decay:schedule)`。但 `BookTextParser.processCommand` 的查找顺序是
   「颜色码 → 十六进制色 → 列表 → **`名字:参数` 形式的 function** → 无参 command」，
   `lookupFunctionProcessor` 只要发现冒号就**无条件**返回（查不到时返回
   `[MISSING FUNCTION: focal_decay]`），后面的 command 查找根本轮不到。
   也就是说：书上一直印着 `[MISSING FUNCTION: focal_decay]`。
   （`book.json` 的 `macros` 是另一套机制——纯字符串替换、只能静态写死在 JSON 里，
   `registerCommand` 不往里加东西，所以此路不通。）
   **修法**：命令名去掉冒号 → `focal_decay_schedule`，正文写 `$(focal_decay_schedule)`。
2. **`book.focal_decay.schedule` 的占位符与实参对不上。** Java 传的是
   `(STAGE2_DAY, STAGE3_DAY)`，而原句是「你有大约 %s 天：第 %s 天起，……第一次扩大」——
   默认配置（3 / 7）下会印成「你有大约 3 天：**第 7 天**起……第一次扩大」，
   而第一次扩大其实是第 3 天。改为「你有大约 %s 天。第 %s 天，……会再扩大一次。」，
   两个实参各就各位，且不再硬编码天数（`landing_text` 里的"你有大约七天"同步删掉）。

**术语与事实对齐**：

- 方块早已从「稳定锚原型机」改名为**观测者基座**，但 tooltip、仪式消息（"原型机已升级为完全稳定锚"）、
  手册正文（"观测者基座（原型机）"、步骤里的"基座"混用）都还是旧名。本轮统一为**观测者基座**；
  `tag.block.focal_decay.anchor_prototype_immune` 同步改为「观测者基座免疫」。
- `message.focal_decay.candidate_locate` 原文「王座位于 %s **格外**」——会被读成"格外"（especially）。
  改为「王座距此 %s 格」。
- `block.focal_decay.throne_block` 从占位名「末地王座方块」改为**王座岩**（EN: Throne Rock）。
- 补上 `entity_mutation_pool_neutral` / `entity_mutation_pool_hostile` 两个译名（此前只有 passive，
  另两个标签在 `ModTags` 里存在但没有对应 key）。

**文风处理（这一轮的主要工作）**：

- 删「不是 A，而是 B」「终止不是修复」一类对举句（全篇只留 `refocus/core` 的"终止不是修复"一处，
  它是那一节的本体）；删感叹号（所有 message 收尾改为句号）；删解释性尾巴——
  作者替读者总结的那一句通常是全篇最像 AI 的地方。
- 标点统一为全角；`tooltip` 与 `gui` 的冒号/波浪线用法统一。
- 手册里 `$(l)` 全部改写为 `$(bold)`。`$(l)` 在 Patchouli 里**是 bold**（`$(l:entry)` 才是链接），
  写全称免得下一个人误读。
- **不再硬编码会随配置漂移的数字**（§13 既定策略的补课）：型号规格一节的「原件覆盖 32 格 /
  一代丢失 10 / 二代丢失 30」「一代 +50 点、二代 +150 点」「二代之后无法再重铸」全部改为
  机制描述（"覆盖范围随之缩减"「有代数上限」）。32 虽是代码常量，但 `total_stability_copy_penalty`
  与 `total_stability_copy_train_penalty` 都是配置项，写死必然过期。
- `lore.focal_decay.fragment_42ms` 原为「这是硬性时间要求」——**原文里没有这句**。
  换成原文台词「把所有东西都压到 42ms 内。」（程玖章语）。
- JEI 信息页改档案索引体：只陈述"是什么、从哪来"，不写教程口吻（"放入…选择方向后开始训练"）。

**两处内容增补（可单独撤销）**：

- `refocus/core` 末页补了源文结尾的那段对话（"所以，他们成功了？／是的。现在观测者完全处于
  基金会的控制之下。／……从历史上来看，不能。"）。它给手册一个真正的收束；不要就删掉那一页。
- `refocus/last_transmission` 补回"肾上腺素"那一拍（之前只有碎片条目提到，通信记录里没有），
  并补上 Aaron 的"你需要再等等吗"，否则程的"不用了"没有指代对象。

**校验**：中英 lang 各 **135** 条、key 完全一致；手册中英 24 篇的
**页数 / 每页 type / recipe / entity / advancement / secret / category / icon / sortnum 逐页一致**
（脚本比对，0 处不符）；全部 52 个手册 JSON + 2 个 lang JSON 通过解析；
`compileJava` 通过。**排版与中文换行仍需实机逐页核对**（§13 阶段 6 未做）。

### 13.7 突变源 / 突变对象系统重构（2026-09-15，已端到端验证）

用户需求：① 突变源与突变目标分别可配置，最好走标签 + 数据包；② 失焦过程必须**对称**，长时间下不收敛于固定物品；
③ 门/栏杆/楼梯/半砖在**各自范围之内**互相突变；④ 一个方块可纳入多种突变池、随机时一并抽取（类似杂交），
并且仍需要一个足够大的总池；⑤ 确定性随机与性能必须正常。补充约束：核心部分要保证可维护性，
**可以拒绝一部分影响性能的需求**，性能是第一要务，拿不准的先讨论，代码结构要解耦。

**先讨论后动手**：把 5 个取向问题摆出来让用户拍板，结论是
① 只做方块（实体池不动）；② 池即源 + `mutation_source_extra` / `mutation_immune` 两个补丁标签；
③ 局部并集 + 概率回退大池（不是纯并集，也不是让大池当父标签）；④ 转换后记录诞生周期抑制抖动；
⑤ waterlogged 不迁移 + 形态类硬门控 + 完整方块也迁移同名属性（**门/床的配对突变用户没有选，因此不做**）。

**三层模型**（详见 PROXYAI.md §4.1）：

| 层 | 标签 | 作用 |
|---|---|---|
| 突变源 | 由池成员推导 + `mutation_source_extra` / `mutation_immune` | 谁会被失焦 |
| 突变池 | `focal_decay:mutation_pool/<名>`（可入多池，取并集） | 会变成什么 |
| 形态类 | `focal_decay:shape_class/<名>`（目标必须与源同类） | 几何约束 |

抽取：`候选 = (本方块所属全部池的并集) ∩ 同形态类`，以概率 `wild_chance`（默认 0.25）整枝改用大池。

**"对称 + 永不冻结"是结构保证的，不是配置纪律**：构建期对每个（池 × 形态类）切片施加
"成员少于 2 个就整体作废"。于是任何能被抽到的方块 b 必然属于某个 ≥2 成员的切片 P，
即 `local(b) ⊇ P`、`|local(b)| ≥ 2` —— 没有任何状态可以被停住；而"共享至少一个池且同形态类"
对两个方块完全对称，所以 A→B 与 B→A 同时成立或同时不成立。旧设计里"源 = 所有完整方块、
目标 = 一个全局标签"其实是单向的（池外方块能变出去、变不回来），这次一并修掉。

**关键实现点**：
- **预计算查表** `MutationIndex`：源门控 / 形态类 / 候选集全部在构建期摊平成按
  `BuiltInRegistries.BLOCK.getId` 索引的数组，热路径只剩几次数组读。等价性有依据：
  原版 `BlockStateBase#isCollisionShapeFullBlock` 本身就是逐状态预缓存的布尔字段
  （`BlockBehaviour.BlockStateBase.Cache`，`EmptyBlockGetter.INSTANCE` + 原点），构建期用同一套算法算一次即可。
- **无分配随机源** `MutationRandom`：状态就是一个 `long`，SplitMix64 纯函数步进。
  旧实现每步 `RandomSource.create(seed)`（= `new LegacyRandomSource` + `AtomicLong`），
  阶段 1 概率 0.01 时期望回扫 100 步 → 每个可见方块每周期约 100 次对象分配。
  顺带解决契约问题：`RandomSource.create` 的实现由原版决定，原版换实现会让老存档目标整体重排。
- **状态迁移** `MutationStateMapper`：只拷"目标方块也有的同名属性"，用 `Property#getName(T)` +
  `Property#getValue(String)` 实现，**不需要强转**；排除 `waterlogged`（含水的目标会被渲染层的流体检查
  挡掉，造成"服务端已转换、客户端不显示幽灵"）与 `in_wall`（真实方块更新会自动修正，预览路径不会）。
  有属性的源走 `Long2ObjectOpenHashMap` 缓存，无属性的源（绝大多数完整方块）走空属性快速路径。
- **形态类登记用原版标签**（`#minecraft:stairs` 等），模组方块进了这些标签就自动生效；
  玻璃板没有原版标签，数据生成里列清单。
- **双格方块守卫**：门/床/高花按"状态里存在 `DoubleBlockHalf` / `BedPart` 取值的属性"识别并剔除 +
  汇总警告，与具体方块类无关。

**顺带修掉的三个既有性能问题**（都不改变玩法）：
1. `GuidedConcept` 的 `isMember` / `neighborhood` 在逐方块扫描循环里解析标签 ID、线性扫标签成员、
   新建并排序列表 → 现在概念邻域在**效果登记时**预计算成 `ClassifiedPool`，成员判定是一次 `boolean[]` 读；
2. 客户端 `protectionInfo` 逐方块 `getKey(state.getBlock()).toString()` 再 `List<String>.contains`
   → 现在 `PrototypeEffect` / `ClientPrototype` 存的是 `Set<Block>`；
3. 每次放置/破坏方块都重发**整张**诞生周期表（那张表随建造无上限增长）→ 新增增量包
   `SyncBirthPeriodPacket`（单条位置 + 周期），整表只在登录/切维度/原型机变化时发。

另外两处：
- **诞生周期剪枝**：比"当前周期 − 128"更早的记录对结果没有任何影响（回扫上限就是 128），
  每 6000 tick 删一次，**语义完全等价**而表重新有界；
- **`convertPrototypeRange`**：半径 32（完全稳定模型）对应 65³ ≈ 27 万个坐标的逐块读写 + 客户端更新，
  是一次实打实的一次性卡顿。加了 `anchor_normalize_range` 开关（默认 true = 保持原行为），
  并把一切与坐标无关的量提到循环外。

**验证**（无头服务器，seed 20260912，`/focaldecay mutation audit` + `selftest`，devtest 数据包自动跑）：

| 项 | 结果 |
|---|---|
| 池 / 大池 / 源 | `pools=37 wild=598 sources=598`（共 1064 个方块） |
| 形态类 | `cube=427 carpets=16 fence_gates=11 fences=12 panes=18 slabs=60 stairs=56 trapdoors=20 walls=25` |
| 不变式 | `reachable=598 frozen=0 asymmetric=0 crossClass=0 OK`；`sources with no inbound edge: 0` |
| 确定性 | 同 (位置, 周期, 源方块) 重复 3 次求值逐位相同：PASS |
| 不收敛 | 固定位置扫 256 个周期：`distinct=85`，最大期望占比 ~1%：PASS |
| 对称 | 实测目标全部能反向变回源方块：PASS |
| 状态迁移 | 楼梯 `facing/half/shape` 保留、`waterlogged` 丢弃：PASS |
| 形态类门控 | 橡木楼梯 256 个周期内没有一次变成非楼梯：PASS |
| 热路径开销 | `chance=1.00`（1 步扫描）**37 ns/次**；`chance=0.01`（阶段1，期望 ~100 步）**176 ns/次**；楼梯 + 状态迁移 187 ns/次 |
| 旧回归 | devtest 既有 A/B/C/D/E/F/G1-G7 全部探针照旧通过，无新增 ERROR |

热路径的量级参考：阶段 1 的最坏情况（每次都要回扫约 100 个周期）是 176 ns/方块，
即 5 万个可见方块一次全量扫描约 9 ms，而扫描本身是分帧排队做的。
旧实现在同一条路径上每次回扫都 `RandomSource.create`，也就是**每方块每周期约 100 次对象分配**，
这里的收益主要是把分配降到 0（没有测旧实现的绝对耗时，不做倍数声明）。

**破坏性变更（标签改名，数据包需要跟着改）**：
`global_mutation_pool` → `mutation_pool/wild`，`nether_mutation_pool` → `mutation_pool/wild_nether`，
`end_mutation_pool` → `mutation_pool/wild_end`，`conversion_blacklist` → `mutation_immune`。
`mutation_immune` 默认还补上了基岩/屏障/光源/结构空位/命令方块/结构方块/拼图/移动活塞/
传送门/末地传送门/末地折跃门/强化深板岩/紫水晶母岩/刷怪笼/试炼刷怪笼/vault/水/岩浆——
它们本来就是"完整方块"，旧实现里**是可以被失焦的**（源门控只看形状），让世界突变出基岩显然不是设计意图。

**新增文件**：`mutation/MutationRandom.java`、`mutation/MutationStateMapper.java`、
`mutation/pool/{ShapeClasses,ClassifiedPool,MutationIndex,MutationIndexBuilder,MutationIndexes,MutationAudit}.java`、
`network/SyncBirthPeriodPacket.java`。
**删除**：`mutation/MutationPool.java`（被 `MutationIndex` 取代）。
**新命令**：`/focaldecay mutation audit | selftest | at`。

**没做 / 待定**：
- 门、床、高花等双格方块的配对突变（用户未选择该选项）；已在形态类登记处与守卫处留了说明与警告。
- 实体突变池维持原样（用户选择）。
- 形态类目前只有"硬门控"一种模式；如果以后想要"楼梯有小概率变成完整方块"，需要另加一档概率配置。

### 13.8 玻璃幽灵的面剔除破洞（2026-09-16，已定位并修复）

**症状**（用户实机发现）：方块突变成玻璃类之后，面剔除不对，看起来像世界破了个洞。

**根因**：幽灵替换只做了一半。
`SectionCompilerMixin` 重定向的是 `SectionCompiler.compile` 里那一次
`RenderChunkRegion.getBlockState`（决定"这个位置画成什么"），
而**面剔除的邻居状态是在另一个方法里读的**：`Block#shouldRenderFace` 内部的
`level.getBlockState(neighborPos)` —— 那处没有被替换。

于是两边不同源：**网格用幽灵状态，剔除判据用真实状态**。
真实石头（幽灵玻璃）旁边的方块读到的邻居是"石头"（不透明），
于是它朝玻璃的那一面被剔掉；可那一面在原版语义里是"不透明方块挨着玻璃"，**是要画的**。
那一面没了，背面又被自身剔除，于是透过玻璃能一直看进方块内部——就是那个"洞"。

反过来（真实玻璃、幽灵不透明）同样会错。一句话概括：
**凡是"比较两个方块"的判定，两边必须来自同一个世界。**

**修法**：新增 `mixin/client/BlockShouldRenderFaceMixin`，重定向
`Block.shouldRenderFace` 里那唯一一次邻居读取，让它也走 `ClientRenderCache#ghostState`。
改这一处就够，因为：
- 它是所有模型面剔除的唯一判据（`ModelBlockRenderer` 的两个 `tesselateBlock` 重载都调它）；
- 它是该方法里**唯一**一次 `getBlockState`（`javap` 逐字节确认过，`@Redirect` 单匹配）；
- 替换后 `Block.OCCLUSION_CACHE`（按 `(本方状态, 邻居状态, 面)` 三元组缓存）的键自动变成幽灵对，
  不会出现"拿真实状态的缓存结果判断幽灵"的污染。

**代价控制**：面剔除对每个可见方块要问 6 次邻居，如果每次都跑完整判定
（阶段 1 约 200 ns）就是 6 × 4096 个方块的毫秒级开销。因此：
- `ClientRenderCache#ghostState` 的顺序是**正缓存 → 负缓存 → 才做完整判定**；
- 新增负缓存 `evaluated`（"本周期已判定过的位置"），`resolve()` 的每条"不替换"分支都会写它；
- `removeEntry` 不再顺手删负缓存（那会让面剔除反复重算），
  真正需要重新判定的场合（保护范围变化、诞生周期变化）由 `refreshRegionData` / `refreshBirths` 显式摘除；
- `lastPeriodIndex` 改成 `volatile`（编译线程要读它做周期校验）。

**边界**：钩子先判 `level instanceof RenderChunkRegion`，只在区块编译路径生效；
破坏粒子、手持方块、方块预览走 `ClientLevel`，一行都不改。
环境光遮蔽那 12 次邻居读取仍用真实状态——影响的是幽灵附近的光影过渡（观感），不是破洞，
而且每方块要多 12 次查找，收益不划算，**有意不改**（已写进 mixin 的 javadoc）。

**验证**：
- `javap -c` 反汇编 `net.minecraft.world.level.block.Block`，确认
  `shouldRenderFace(BlockState, BlockGetter, BlockPos, Direction, BlockPos)Z` 存在、
  返回类型为 `Z`、且**恰好一次** `invokeinterface BlockGetter.getBlockState`（`@Redirect` 单匹配）；
- 启动开发客户端验证注入真的生效：日志出现
  `Mixing client.BlockShouldRenderFaceMixin from focal_decay.mixins.json into net.minecraft.world.level.block.Block`，
  无 `InvalidInjectionException` / `MixinApplyError`，客户端正常加载到贴图集阶段。

**顺带发现的同类问题（2026-09-16 已按用户选择处理）**：如果一个**含水**的方块（waterlogged 楼梯/半砖）
被替换成干燥的幽灵，`SectionCompiler` 的 `blockstate.getFluidState()` 读的是幽灵状态，
于是那一格的水不会被渲染——水面会出现一个 1 格的缺口。同属"幽灵状态泄漏到本该用真实状态的环节"。

**处理方式：含水状态不参与失焦**。判定加在 `MutationHelper.resolve` 这一唯一入口里
（`!source.getFluidState().isEmpty()` → 返回原方块），所以服务端与客户端自动一致，
不需要两处各写一遍。

**必须按状态而不是按方块排除**：`StairBlock` / `SlabBlock` / `FenceBlock` / `WallBlock` /
`TrapDoorBlock` / `IronBarsBlock` 全都实现了 `waterlogged`（SimpleWaterloggedBlock），
如果按"方块带不带 waterlogged 属性"排除，等于把整个形态类功能（楼梯/半砖/栏杆互变）砍掉。
用 `getFluidState().isEmpty()` 而不是直接查 `waterlogged` 属性，是为了把"任何带流体的状态"
（含模组方块）一并覆盖。

**验证**（`selftest` 新增两条断言，都是 chance=1 强制命中）：
- `waterlogged never mutates (chance=1 forced): PASS`
- `same stairs still mutate when dry: PASS`（确认没有误伤干燥楼梯）

热路径开销几乎没变（`chance=1.00` 37→41 ns，`chance=0.01` 176→186 ns，在运行间波动范围内）。

### 13.9 重聚焦之后移除 observer_veil 遮罩（2026-09-16，已实测配置与命令）

**症状**（用户实机发现）：重聚焦（观测者核心上线）之后，画面上的"不安定感"还在继续。

**根因**：`ClientRenderCache.updateVeil` 从头到尾没读过 `observerOnline`。
遮罩画的就是"失焦带来的不安定"，而失焦已经结束了——纯粹是漏做状态处理。
（同一时刻其它东西都正确停了：方块失焦预览、实体突变、交互转换都查 `observerOnline`。）

**修法**：
- 新增两个 CLIENT 配置：
  - `postProcessAfterRefocus`（默认 **false**）——即"重聚焦后移除"；置 true 恢复旧的"一直抖"。
  - `postProcessRefocusFadeTicks`（默认 50 = 2.5 秒，0 = 立刻切掉）。
- 做成**淡出而不是硬切**：重聚焦那一瞬本来就有音效/粒子/广播，直接抽掉反而突兀。
- 淡到 0 时**卸载整个 PostChain**，连每帧一次的全屏 pass 都不再付；
  世界若回到失焦状态（或开关被打开），`veilRefocusFade` 复位为 1，下一帧重新加载。
- 淡的是 `Fade` uniform，而 shader 里 `Fade` 同时乘在**漂移、色散、着色**三项上，
  所以淡到 0 就是"整个效果消失"，不是只去掉色调却留下抖动（这一点是读 fsh 确认的，不是猜的）。

**新增调试命令** `/focaldecay refocus [true|false]`（权限 2）：
走的是和核心激活完全相同的 `FocalDecayWorldData.setObserverOnline`，所以看到的就是真实行为。
存在的理由是重聚焦之后有一堆只在那一刻生效的表现需要反复看，
而正常流程要练候选模型 → 装核心 → 等 100 tick。

**验证**：
- `runClient` 启动无异常，配置文件被重新生成且含新键
  （`postProcessAfterRefocus = false`，`postProcessRefocusFadeTicks = 50`）；
- devtest 数据包新增 `focaldecay refocus true` + `refocus false` 两条（**故意在末尾回到 false，
  不留副作用**），服务端日志确认命令已注册且两条都执行成功：
  ```
  Focal Decay observerOnline = true (client veil fades out, defocus preview cleared)
  Focal Decay observerOnline = false (defocus resumes)
  ```
- 同一次运行的突变自检全部照旧通过（`checks: frozen=0 asymmetric=0 crossClass=0 OK`，
  selftest 十项全 PASS，热路径 35 / 147 ns）。

**未经自动化验证的**：淡出动画本身要用眼睛看。进游戏执行 `/focaldecay refocus` 即可复现
（2.5 秒内遮罩消失，日志出现 `observer veil removed after refocus`）。

## 关键约定与注意事项

1. **AI 守则**：默认 GBK，编辑文件用 UTF-8
2. **NeoForge 21.1 差异**：Capability → attachment；`EntityTypeTags.create` 需要 String 参数（用 `TagKey.create`）；`SavedData.Factory` 三元组构造
3. **反编译源码位置**：`~/.gradle/caches/neoformruntime/intermediate_results/decompile_*_output.jar`（查 MC 类）；`~/.gradle/caches/modules-2/.../neoforge-21.1.248-sources.jar`（查 NeoForge 类）
4. **数据生成**：改 `data/` 下 Provider 后跑 `.\gradlew.bat runData`，输出到 `src/generated/resources`
5. **编译验证**：`.\gradlew.bat compileJava`；完整构建 `.\gradlew.bat build`
6. **配置**：`FocalDecayConfig` 里的 Server 值在 `FocalDecayConfig.BASE_INTERVAL` 等处读取，末日阶段系统后续接入
7. **兼容模组（Patchouli / JEI）**：均为可选依赖，坐标在 `gradle.properties`（`jei_version` / `patchouli_version`），仓库 `https://maven.blamejared.com`；JEI 用 `compileOnly` + `localRuntime`，Patchouli 另加 `:api` classifier。**不要**在 `run/mods` 里重复放这两个 jar（会与 gradle 提供的那份冲突），手工 jar 已归档到 `run/mods.disabled`
   - ⚠️ **引用可选依赖的代码必须放进 `compat/` 下的独立类**，宿主类里只留字符串 modid 检查 + 静态方法调用。
     否则 `@EventBusSubscriber` 类的加载会被第三方类型拖垮（详见 §13.1 第 3 条）
   - 加入新的可选依赖引用后，用 `javap -c -p -classpath build/classes/java/main <宿主类>` 确认字节码里
     不残留第三方类型；必要时临时注释 `localRuntime` 跑一次 `runClient` 实测
8. **查 API 签名的可靠姿势**：`javap -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar <类名>`；查源码用同目录的 `neoforge-21.1.248-sources.jar`（含 MC 与 NeoForge 双方源码，可直接确认补丁点行为）
9. **跑客户端前先设音频后端（本机必需）**：`$env:ALSOFT_DRIVERS = 'null'`，否则会卡死在 OpenAL 的 `alcResetDeviceSOFT`（HRTF 初始化），表现为"加载到 88% 无响应"，强杀后 Gradle 报 `-805306369`。详见 §13.1
10. **卡死时怎么定位**：`jps -l` 找 `net.neoforged.devlaunch.Main` 的 pid → `jstack <pid>`（**`jcmd` 附加会被系统拒绝，`jstack` 可用**），直接看 `"Render thread"` 的栈
11. **数据文件不要靠"看起来对"**：跑 `.\gradlew.bat runServer` 让开发服务器加载一遍数据包，
    日志里会直接给出 codec 错误（例如 `No key spawn_overrides in MapLike`）。
    结构相关的端到端验证用 `tools/` 里的脚手架，跑法与坑见 `tools/README.md`。
    `run/eula.txt` 已置 `eula=true`（用户已同意）
12. **两个"uniform"字段名不同，别混**：`IntProvider` 用 `min_inclusive`/`max_inclusive`，
    `NumberProvider`（`set_count` 的 `count` 等）用 `min`/`max`。JSON 形状一样，
    写错会让整张战利品表**静默不加载**（只有 `LootDataType` 一条 ERROR，引用方只报
    "loot table is missing"）。详见 §13.6 第 3 条
