# Focal Decay 开发进度清单

> 最后更新：2026-08-10
> 环境：NeoForge 21.1.248 / Minecraft 1.21.1 / Parchment 2024.11.17 / Java 21
> Mod ID：`focal_decay`，包：`com.zhizhiwang.focal_decay`

## 已完成

### 1. 项目改造（完成）
- `gradle.properties`：Mod ID/名称/包名改为 focal_decay，版本升级 1.21.1（neo=21.1.248）
- 主类 `FocalDecay.java`（`src/main/java/com/zhizhiwang/focal_decay/FocalDecay.java`）
- 删除 examplemod 模板代码，编译与 build 通过

### 2. 方块/物品/方块实体骨架（完成）
- 方块：`block/StableAnchorBlock.java`、`MutationControllerBlock.java`、`ObserverCoreBlock.java`（含 powered 状态）+ `block/ModBlocks.java`
- 方块实体：`block/entity/StableAnchorBlockEntity.java`、`MutationControllerBlockEntity.java`（tagExpression + radius，NBT 持久化）+ `block/entity/ModBlockEntities.java`
- 物品：`item/ModItems.java`（3 个方块物品 + 7 个语义碎片 `SemanticFragmentItem` + `rebuilt_observer_protocol`）
- 创造标签：`item/ModCreativeTabs.java`
- 语言：`assets/focal_decay/lang/en_us.json` + `zh_cn.json`（含全部 Lore）
- 资源：blockstates / models 全部 JSON（贴图暂用原版方块占位）

### 3. 配置系统（完成）
- `config/FocalDecayConfig.java`：Server（stage2_day/stage3_day/各阶段 interval/实体概率/enable_core_repair）、Common（anchor_radius/post_intensity）、Client（postProcessEnabled/surface_update_frequency/max_render_distance）
- 主类已注册三份 config spec

### 4. 标签与数据生成（完成）
- `data/tags/ModTags.java`：BlockTags（global_mutation_pool/conversion_blacklist/stable_anchor_immune）+ EntityType 三阶段池标签
- `data/tags/ModBlockTagsProvider.java`、`ModEntityTypeTagsProvider.java`
- `data/recipe/ModRecipeProvider.java`（稳定锚、突变控制器形状合成）
- `data/recipe/ModRecipeSerializers.java` + `RebuildObserverProtocolRecipe.java`（7 碎片无序合成，`crafting_special_rebuildobserver`）
- `data/ModDataGenerator.java`：`runData` 已成功生成 JSON 到 `src/generated/resources`

### 5. 全局池与确定性随机（完成）
- `mutation/MutationPool.java`：按 BuiltInRegistries.BLOCK id 升序的不可变列表，带 version
- `mutation/MutationHelper.java`：`getTarget(original, pos, worldSeed, periodIndex, pool)`，seed = pos.asLong() ^ worldSeed ^ periodIndex
- `mutation/RegionOverride.java`：中心/半径/标签表达式（支持通配符），用 `RegistryLookup.listTags()` 编译，NBT 序列化
- `mutation/MutationPoolManager.java`：维度级 SavedData，管理覆盖列表/锚集合/全局池，`getEffectivePool(pos, original)`
- `mutation/MutationEventHandler.java`：锚/控制器放置破坏更新、维度加载 reloadGlobalPool；已注册游戏总线

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
- 客户端保护集合/覆盖数据：仍待网络包 `SyncRegionDataPacket`（见第 10）

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
  - 中键选取：新增 `Mixin MinecraftPickBlockMixin`（注入 `pickBlock()V` 中两处 `ClientLevel#getBlockState`），返回可见的失焦目标方块；`ClientRenderCache.visibleState()` 负责查询（空气目标回退原方块）

### 7.5 修复：固定位置失焦 + 创造掉落行为（2026-08-10）
- 固定位置失焦根因：`periodIndex` 是小数字，`seed = pos ^ worldSeed ^ periodIndex` 直接异或只扰动低几位，而 LCG 首次 `nextDouble` 由高位移位主导 → 同一批位置每周期都通过/不通过概率骰子（模拟验证重叠 100%）
  - 修复：`MutationHelper.getTarget` 对种子做 SplitMix64 雪崩混合后再交给 `RandomSource`（模拟验证跨周期失焦集合重叠降至 ~10%，位置每周期重排）；两端公式相同，同步性不变
- 创造模式破坏修正：最终确定创造破坏保持原版行为（无掉落、不收入背包、不执行转换），撤销此前的转换/掉落逻辑；中键选取返回目标方块保持不变

## 未完成（按推荐实现顺序）

### 8. 末日阶段系统
- `mutation/FocalDecayWorldData.java`：全局天数 SavedData，每 20 分钟游戏日 +1，玩家数为 0 暂停
- 阶段判定（Server config 值）、实体突变（三阶段池 + 确定性种子 `worldSeed ^ pos.asLong() ^ tick`）
- 阶段影响范围：阶段1 完整方块 / 阶段2 动态形状 / 阶段3 空气↔方块小概率
- 掉落物特殊处理（目标物品改变）

### 9. 观测者核心与语义碎片
- 观测者核心：世界结构生成（固定坐标、Y=-40~-20、种子决定）、右键 GUI（"观测者离线/在线"）、用重建协议激活（动画+粒子，powered=true，触发胜利）
- 语义碎片来源：玫瑰失焦突变、核心结构内生成、村庄战利品、首次右键核心、铜块突变、所有战利品箱、稳定锚合成
- 战利品注入（碎片）：`GlobalLootModifier` 或 LootTableLoadEvent

### 10. 网络通信
- `SyncRegionDataPacket`（S→C）：维度 ID、覆盖区域列表（含有效方块列表）、稳定锚位置集合；玩家登录/覆盖更新时发送
- `ObserverCoreActivatePacket`（S→C）：核心激活时全服动画
- 用 `NetworkRegistry` 创建 `SimpleChannel`，协议版本 "1"

### 11. 收尾
- 稳定锚/控制器 GUI（突变控制器 MenuProvider：标签表达式输入 + 半径滑条 + 应用按钮）
- 稳定锚粒子（蓝色漂浮、"42ms"字样）
- 专属贴图/模型（目前用原版占位）
- 测试与平衡调整

## 关键约定与注意事项

1. **AI 守则**：用 PowerShell 语法；默认 GBK，编辑文件用 UTF-8
2. **NeoForge 21.1 差异**：Capability → attachment；`EntityTypeTags.create` 需要 String 参数（用 `TagKey.create`）；`SavedData.Factory` 三元组构造
3. **反编译源码位置**：`~/.gradle/caches/neoformruntime/intermediate_results/decompile_*_output.jar`（查 MC 类）；`~/.gradle/caches/modules-2/.../neoforge-21.1.248-sources.jar`（查 NeoForge 类）
4. **数据生成**：改 `data/` 下 Provider 后跑 `.\gradlew.bat runData`，输出到 `src/generated/resources`
5. **编译验证**：`.\gradlew.bat compileJava`；完整构建 `.\gradlew.bat build`
6. **配置**：`FocalDecayConfig` 里的 Server 值在 `FocalDecayConfig.BASE_INTERVAL` 等处读取，末日阶段系统后续接入
