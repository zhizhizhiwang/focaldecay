# AI 守则
1. 使用powershell语法
2. 默认powershell为gbk, 编辑文件请使用utf-8

# Focal Decay: Observer Fallen

**模组完整技术设计大纲**  
版本：1.0.0  
目标平台：NeoForge 1.21.1 
设计参考：SCP-CN-2999 “Observator Ex Machina”

---

## 1. 模组概述

### 1.1 核心概念
- **世界观**：现实由“观测者”维持，其死亡导致“失焦”——事物本质随机漂移，表现为方块与实体不可逆的随机转换。
- **玩家目标**：使用稳定锚与突变控制器保护领地，可选收集语义碎片修复观测者核心以终结末日。
- **核心机制**：
  - 方块在玩家视锥内按全局周期随机切换外观（客户端视觉欺骗）。
  - 交互时在服务端真实转换为目标方块（确定性随机）。
  - 随时间推移的末日阶段系统。
  - 区域抑制/引导转换的装置。

### 1.2 命名空间
- Mod ID: `focal_decay`
- 包名: `com.zhizhiwang.focal_decay`

---

## 2. 世界生成与结构

### 2.1 观测者核心（Observer Core）
- **类型**：不可合成的大型结构，类似废弃研究设施。
- **生成**：在固定坐标生成一个, 位置由世界种子决定，Y=-40 至 Y=-20 之间，位于地下。
- **保护**：方块硬度等于基岩，免疫爆炸（`Block.Properties.explosionResistance(Float.MAX_VALUE).destroyTime(-1.0f)`）。同时自带稳定锚, 核心方块不可被转换
- **状态**：
  - **失效**（默认）：失焦已开始，结构中央有熄灭的核心块。
  - **激活**：玩家使用“重建的观测协议”后，核心块变为发光态，失焦终止。
- **交互**：右键核心块打开 GUI，显示“观测者离线”或“观测者在线”。

### 2.2 语义碎片（Semantic Fragments）
- 7种不同物品
- 来源
  1. 由玫瑰失焦突变出现
  2. 固定生成于观测者核心结构中
  3. 村庄中概率刷新
  4. 第一次右键观测者核心方块时获取
  5. 由铜块突变得到
  6. 所有战利品箱里概率刷新
  7. 由稳定锚合成
- **碎片列表与Lore**
  1. 碎片·玫瑰 —— “玫瑰，是玫瑰，是玫瑰……”
  2. 碎片·王座 —— “它不是能用语言描述的事物。”
  3. 碎片·完备语义 —— “真正的理解。”
  4. 碎片·42ms —— “这是硬性时间要求。”
  5. 碎片·硫铜结晶 —— “一小块Dr. Kacper Darlin”
  6. 碎片·Aaron的誓约 —— “世界会继续美丽。”
  7. 碎片·程玖章的最后传输 —— “那就这么决定了。”
- **合成**：7个碎片无序合成 `重建的观测协议`（`Rebuilt Observer Protocol`），配方类型 `minecraft:crafting_special_rebuildobserver`。

---

## 3. 方块与方块实体

### 3.1 稳定锚（Stable Anchor）
- **注册名**：`stable_anchor`
- **方块属性**: `BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).lightLevel(state -> 7)`
- **方块实体**: 保留, 用于优化遍历和防止被纳入转换池
- **效果**：以自身为中心，切比雪夫距离 8 格（可配置）内的所有方块被保护。
  - 被保护方块不会被加入客户端视锥渲染列表（即不替换模型）。
  - 交互时服务端不会触发真实转换（直接使用原方块状态）。
- **保护检测**：在服务端和客户端分别维护 `Set<BlockPos>`（锚位置集合）。当锚被放置/破坏时更新该集合。
- **粒子**：随机在表面生成漂浮蓝色粒子，偶尔拼出“42ms”字样（客户端粒子效果）。
- **工具提示**：`“OBSR-1β 完备语义保护场”`
- **配方**：
  - 形状：
    ```
    R F R
    F E F
    R F R
    ```
    R = 红石粉, F = 铁块, E = 突变控制器
  - 输出: 1

### 3.2 突变控制器（Mutation Controller）
- **注册名**：`mutation_controller`
- **方块实体**：`MutationControllerBlockEntity`，实现 `MenuProvider`，提供 GUI。
- **存储数据**：
  - `tagExpression: String`（如 `*ore*`）
  - `radius: int`（范围 1-32，默认 16）
  - 编译后的 `Pattern`（或 glob 匹配器），在输入时解析。
- **GUI**：
  - 标题：“语义场限制器”
  - 输入框：标签表达式。
  - 滑条：半径调整。
  - 应用按钮：保存并更新覆盖区域。
- **覆盖逻辑**：
  - 服务端 `MutationPoolManager` 维护 `List<RegionOverride>`。
  - 每个 `RegionOverride` 包含中心坐标、半径、编译后的 `Predicate<TagKey<Block>>`。
  - 某位置若被多个覆盖，取其交集；交集为空集则回到全局池
  - 覆盖仅在控制器方块未被破坏时有效（破坏时移除覆盖）。
- **配方**：
  - 形状：
    ```
    A B A
    B S B
    A B A
    ```
    S = 末影珍珠, B = 书与笔, A = 金锭
  - 输出: 1

### 3.3 观测者核心块（Observer Core Block）
- **注册名**：`observer_core`
- 不可破坏、不可合成。
- 两种状态：`powered=false`（失效）和 `powered=true`（激活），由 `blockstate` 控制。
- 使用 `BlockBehaviour.Properties.of().strength(-1.0f, Float.MAX_VALUE).noLootTable()`。
- 激活逻辑：当玩家右键持有 `Rebuilt Observer Protocol` 时，播放动画（粒子+音效），几秒后设置 `powered=true`，触发胜利事件。

---

## 4. 突变池管理系统

### 4.1 全局池
#### 4.1.1 方块全局池
- 通过方块标签 `focal_decay:global_mutation_pool` 定义。
- 数据生成时默认包含所有符合以下条件的方块：
  - `minecraft:block` 中 `isCollisionShapeFullBlock()` == true
  - 无方块实体（`!hasBlockEntity()`）
  - 非空气、非液体
  - 可自然生成（通过检查 `Block.isPossibleToRespawnInThis()` 不完全准确，手动列表或使用标签 `#minecraft:natural`）
- 全局池在服务端和客户端均从标签加载。
- 带方块实体的方块同时被排除出突变源，即在任何阶段都不会被转换。
- 每当玩家获得不在全局池中并且满足除了可自然生成之外的条件的方块时, 将其加入全局池, 并且随存档持久化储存(创造模式下不执行此条)
```java
public final class MutationPool {
    private final List<Block> blocks; // 按 BuiltInRegistries.BLOCK.getId 升序
    private final long version;
    private MutationPool(List<Block> blocks, long version) { ... }
    public Block get(int index) { return blocks.get(index); }
    public int size() { return blocks.size(); }
    public List<Block> snapshot() { return blocks; }
}
```
- 全局池以不可变排序列表形式存在，新增操作仅在服务端执行，并通过网络包在周期边界同步到客户端。”
#### 4.1.2 实体全局池
  - 所有实体突变逻辑均使用此池
    - 一阶段包括被动实体(不包括marker, 掉落物, 激活的tnt, 火球之类的保留实体, 只包含有ai的动物, 生物一类)
    - 二阶段加入中立实体, 同样要求是有AI的实际生物
    - 三阶段加入敌对实体, 要求同上

### 4.2 区域覆盖（RegionOverride） (不考虑实体)
- 服务端：
  - `MutationPoolManager` 是维度级别的 `SavedData`，存储覆盖区域列表。
  - 每次放置/破坏控制器或修改配置时，标记 dirty，序列化为 NBT。
  - 提供 `getEffectivePool(BlockPos pos, BlockState original)` 方法。
- 客户端：
  - 通过 `SyncRegionDataPacket` 接收所有覆盖信息。
  - 本地 `ClientRegionData` 保存 `List<ClientRegionOverride>`。
  - 渲染时查询有效标签，若标签表达式匹配则使用子池（所有匹配覆盖取交集，为空则回退全局池，需客户端提前计算或从服务端发送池的方块列表）。
  - 标签匹配neoforge提供的标签
  - 区域覆盖的子池解析同样受此规则约束——表达式匹配的方块如果带方块实体，自动从子池中剔除。
如果源方块不在子池中, 使用子池决定突变目标
  - 特别处理: 由于在破坏时才会决定突变目标, 无法模拟突变过程中突变为子池并改变突变目标的情况, 所以直接使用子池

**重要优化**：控制器修改后，服务端会将有效方块列表（List\<Block\>）序列化后同步给客户端，避免客户端重复计算标签。即在网络包中包含 `List<Block>` 及区域定义。

### 4.3 稳定锚保护
- 服务端和客户端均维护 `Set<BlockPos> protectedPositions`（通过锚的放置/破坏事件更新）。
- 当方块坐标在保护集合内时，不参与视觉转换，交互不转换。

---

## 5. 确定性随机与目标计算

### 5.1 算法
```java
public static BlockState getTarget(BlockState original, BlockPos pos, long worldSeed, long periodIndex, List<Block> pool, double probability) {
    if (pool.isEmpty() || probability <= 0.0) return original;
    long seed = mix64(pos.asLong() ^ worldSeed ^ periodIndex); // SplitMix64 雪崩混合
    Random rand = new Random(seed);
    if (probability < 1.0 && rand.nextDouble() >= probability) return original;
    return pool.get(rand.nextInt(pool.size())).defaultBlockState();
}
```
- `periodIndex` = `gameTick / conversionInterval`。
- 池子为 `List<Block>`（不含 `BlockState`，以简化）。
- 服务端与客户端使用相同种子，保证一致。
- **转换概率（2026-08-10 新增）**：每个方块每周期只有一定概率被转换，概率随阶段变化：阶段1/2/3 暂定 `0.1 / 0.6 / 1.0`（Server 配置 `block_mutation_chance_stage1/2/3`）。
  - 概率 roll 与目标选择共用同一确定性种子（`pos.asLong() ^ worldSeed ^ periodIndex`），先 roll 后取目标，两端随机序列完全一致。
  - **种子必须经 SplitMix64 雪崩混合（2026-08-10 修复）**：`periodIndex` 是小数字，直接异或只扰动种子低几位，LCG 首次 `nextDouble` 几乎不变，会导致"同一批固定位置每周期都失焦"。混合后每次周期切换失焦位置集合完全重排。
  - 阶段判定（临时）：`currentStage(gameTick) = gameTick / 24000` 取原版天数，对照 `stage2_day` / `stage3_day`；待 §6 末日天数 SavedData 接入后替换。
- 全局池以不可变排序列表形式存在，新增操作仅在服务端执行，并通过网络包在周期边界同步到客户端。”
- 带有方块实体的目标均不转换

### 5.2 交互锁定
- **挖掘开始**：`PlayerInteractEvent.LeftClickBlock`（服务端）记录：
  - 目标方块状态 `targetState`（此时计算）
  - 周期索引 `periodIndex`(锁定方块)
  - 存储在玩家能力 `Capability<BreakData>` 中。
- **挖掘速度**：原方块硬度决定（默认）。
- **方块破坏**：`BlockEvent.BreakEvent` 中，如果玩家有 `BreakData` ，则取消默认掉落，执行：
  - 服务端将方块直接设置为 `targetState`（无掉落）。
  - 然后调用 `targetState.getDrops()` 生成物品掉落。
  - 给予目标方块的挖掘经验值（`targetState.getExpDrop()`）。
  - 移除 `BreakData`。
- **创造模式支持（2026-08-10 新增，破坏行为最终修正）**：创造模式**破坏保持原版行为**——无掉落、不收入背包、不执行转换（仅生存模式破坏触发转换掉落）；中键选取（pick block）通过 Mixin `Minecraft#pickBlock` 返回可见的"失焦目标"方块（空气目标回退原方块）。

### 5.3 右击交互
- 不触发真实转换。玩家放置方块或使用物品时，均针对原方块。由客户端视觉效果处理，服务端不干预。

---

## 6. 末日阶段系统

### 6.1 全局计时器
- 使用单独的 `SavedData` 记录世界创建后的天数。
- 所有阶段均不转换带方块实体的方块（无论源还是目标）
- 阶段判定：
  - 阶段 1: `days < stage2_day`
  - 阶段 2: `stage2_day <= days < stage3_day`
  - 阶段 3: `days >= stage3_day`
- 阶段可配置关闭。

### 6.2 阶段效果配置
- 每个阶段定义：
  - `mutationInterval`：转换周期 (tick)
  - `blockMutationChance`：方块每周期转换概率（阶段1/2/3 = 0.1/0.6/1.0，Server 配置）
  - `affectNonFullBlocks`：是否影响非完整方块（阶段2+）
  - `affectAir`：是否影响空气（阶段3）
  - `entityMutationChance`：实体每周期转换概率
  - `postIntensity`：后处理强度乘数
- 配置值通过 `FocalDecayConfig` 读取。

### 6.3 方块影响范围扩展
- 阶段1：仅 `isCollisionShapeFullBlock()` 方块。
- 阶段2：增加 `BlockBehaviour.Properties.dynamicShape()` 为非完整但有碰撞箱的方块（如栅栏、玻璃板）。但需在渲染时处理模型替换，可能需特殊处理。
- 阶段3：使方块有小概率转换成空气, 同时空气也有小概率转换成方块, 概率可配置

### 6.4 实体转换
- 根据不同阶段决定池, 源池和目标池始终应该一致
- 每周期（不同阶段周期不同）对每个实体生成随机数，若小于概率则转换。
- 转换方式：从标签中随机选取目标实体类型，使用 `EntityType.create(level)` 生成新实体，复制位置、兼容的NBT（保留年龄等），移除旧实体，生成新实体。应用确定性随机种子：`(worldSeed ^ entity.blockPosition().asLong() ^ tick)` 选择目标类型。
- 特殊处理掉落物, 使其目标物品改变
---

## 7. 渲染系统

### 7.1 客户端方块目标缓存
- 跳过 hasBlockEntity() 的方块。
- `ClientRenderCache` 单例持有：
  - `Map<BlockPos, BlockState> targetCache`：当前周期的目标方块状态。
  - `Set<BlockPos> visibleSurfaces`：上一帧计算的可见表面集合。
- 更新时机：每 `(conversionInterval * 20 / 20) = conversionInterval` tick（即每周期一次）。在 `ClientTickEvent.PRE` 中检测 `gameTick % conversionInterval == 0` 时执行：
  - 清空 `targetCache`。
  - 遍历 `visibleSurfaces`，对每个坐标计算本周期目标，存入缓存（受稳定锚和覆盖影响）。

### 7.2 视锥表面计算
- 执行频率：每 `surface_update_frequency` 帧（默认2）。
- 算法：
  - 每次清空
  - 获取玩家眼部位置、视角方向、FOV，构建视锥体 (Frustum)。
  - 遍历玩家所在区块及模拟区域半径内的 `BlockPos`，使用 `ChunkAccess` 快速获取状态。
  - 判断条件：
    1. 方块状态不为空，且当前阶段应受影响。
    2. 方块在客户端加载范围内（`level.isLoaded(pos)`）。
    3. 方块至少有一个面暴露（即相邻位置不含完整立方体）。
    4. 该暴露面法线与视线方向夹角 < 100°（容忍度）。
    5. 该面中心在视锥体内。
  - 满足条件的加入 `visibleSurfaces`（使用 `HashSet`）。
- 性能优化：
  - 使用 `MutableBlockPos` 遍历。
  - 提前计算视锥包围盒，快速剔除远处区块。
  - 可以使用 `RenderChunk` 的 visibility 信息辅助，但自行实现更可控。

### 7.3 模型替换渲染
- **Mixin 位置**：`net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk.RebuildTask.compile` 方法。
- 在遍历区块内的方块时（`BlockPos.betweenClosed` 循环），对每个坐标：
  - 获取原始 `blockState`。
  - 检查 `targetCache` 是否包含该坐标，若有且目标不等于原始，则使用 `targetCache.get(pos)` 替换 `blockState` 进行模型获取和渲染。
- 注意排除液体、空气等由原版渲染管线的特殊处理，确保替换仅针对固体层。
- 对于非完整方块，在阶段2+启用时，须同样处理它们的模型替换，可能需调整 `isCollisionShapeFullBlock` 判断。
- 对于带方块实体的原方块，直接跳过替换，避免方块实体渲染器残留。

### 7.4 后处理着色器
- 注册自定义 `PostChain`：`focal_decay:observer_veil`。
- 实现方式：
  - 在 `RegisterShadersEvent` 中注册。
  - 在 `RenderLevelStageEvent.Stage.AFTER_LEVEL` 中应用，使用 `Minecraft.getInstance().gameRenderer` 的 `PostChain` 实例。
  - 着色器效果：极轻微的时间性扭曲 + 色散，周期波动，强度由 `postIntensity` 配置。
  - 提供视频设置开关（通过 `FocalDecayConfig.CLIENT.postProcessEnabled` 控制，可在配置中或按键切换）。
- 阶段3时强度乘2。

---

## 8. 网络通信

### 8.1 数据包设计
- **SyncRegionDataPacket**（S→C）：
  - 维度ID、覆盖区域列表（每个覆盖：中心坐标、半径、有效方块列表）。
  - 稳定锚位置集合。
  - 在玩家登录、覆盖更新时发送。
- **ObserverCoreActivatePacket**（S→C）：
  - 当核心被激活时，发送给所有玩家，触发全局动画和成就。
- **BreakDataSyncPacket**（可无需，挖掘数据仅存服务器，客户端无需知道目标）。

### 8.2 网络注册
- 使用 NeoForge `NetworkRegistry` 创建 `SimpleChannel`，协议版本 “1”。

---

## 9. 配置系统

### 9.1 配置文件
- 位置：`config/focal_decay.toml`
- 使用 NeoForge 的 `ModConfigSpec` 构建。
- 分类：
  - **Server**（同步到客户端）：`stage2_day`, `stage3_day`, `base_interval`, `stage2_interval`, `stage3_interval`, `entity_mutation_chance_stage2`, `entity_mutation_chance_stage3`, `block_mutation_chance_stage1/2/3`（0.1/0.6/1.0）, `enable_core_repair`。
  - **Common**（服务器/客户端各自加载）：`anchor_radius`, `post_intensity`。
  - **Client**：`postProcessEnabled`, `surface_update_frequency`, `max_render_distance`。

### 9.2 数据生成
- 方块标签：`focal_decay:global_mutation_pool` 自动生成，通过 `TagsProvider<Block>` 添加所有符合条件的原版方块。
- 实体类型标签：`focal_decay:entity_mutation_pool_passive` 包含如 `minecraft:sheep`, `minecraft:cow` 等。
- 战利品表：语义碎片添加到相应原版战利品表，使用 `GlobalLootModifier` 或直接修改 `LootTableLoadEvent`。
- 配方：稳定锚、突变控制器使用标准 `ShapedRecipeBuilder`。

---

## 10. 事件监听与核心逻辑挂载

### 10.1 服务端事件
- **BlockEvents**：
  - `BlockEvent.BreakEvent`：处理真实转换掉落, 当破坏稳定锚/控制器时，移除相应数据。
  - `BlockEvent.EntityPlaceEvent`：当放置稳定锚/控制器时，更新保护集合或覆盖列表。
- **PlayerEvents**：
  - `PlayerEvent.PlayerLoggedInEvent`：同步区域数据。
  - `PlayerEvent.Clone`：复制能力数据。
- **TickEvents**：`ServerTickEvent.PRE` 或使用 `LevelTickEvent` 驱动末日计时和实体转换。

### 10.2 客户端事件
- **ClientTickEvent.PRE**：更新渲染周期。
- **RenderLevelStageEvent**：应用后处理。
- **RegisterKeyMappingsEvent**（可选）：注册关闭后处理的快捷键。

### 10.3 Mixin 列表
- `MixinChunkRenderDispatcher`：替换编译时的方块状态。
- `MinecraftPickBlockMixin`：中键选取返回"失焦目标"方块（替换 `Minecraft#pickBlock` 中的 `ClientLevel#getBlockState`）。
- 不修改底层网络，仅注入渲染和方块处理。

---

## 11. 状态存储与持久化

- **MutationPoolManager**：`SavedData`，通过 `DimensionDataStorage` 读写，保存：
  - `List<RegionOverride>`（中心坐标、半径、表达式字符串）
  - 稳定锚位置集合（可从方块实体遍历获取，但缓存提高效率）
- **末日计时**：`FocalDecayWorldData`，存储游戏天数，独立维护，每20分钟游戏日更新一次, 服务端运行时在玩家数为0时暂停计时。
- **玩家挖掘数据**：使用 NeoForge Capability `BreakData`，自动同步。

---

## 12. 开发实施顺序（推荐）

1. **项目搭建**：Gradle 配置，注册基本类。
2. **配置与数据生成**：完成标签、配方、战利品注入。
3. **方块注册**：稳定锚、控制器、核心块，实现基础功能。
4. **全局池与确定性随机**：MutationPoolManager 与渲染缓存。
5. **客户端渲染**：视锥计算、Mixin、后处理。
6. **交互与转换**：挖掘锁定、掉落逻辑。
7. **稳定锚与控制器完整GUI**。
8. **末日阶段系统**：计时、实体转换。
9. **观测者核心与语义碎片**：结构生成、修复流程。
10. **网络同步**：覆盖数据包。
11. **彩蛋、粒子、音效**。
12. **测试与平衡调整**。

---

## 13. 附录：所有自定义内容标识符

- 方块：`stable_anchor`, `mutation_controller`, `observer_core`
- 物品：`semantic_fragment_rose`, `semantic_fragment_throne`, `semantic_fragment_semantic`, `semantic_fragment_42ms`, `semantic_fragment_crystal`, `semantic_fragment_aaron`, `semantic_fragment_cheng`, `rebuilt_observer_protocol`
- 方块实体类型：`mutation_controller`
- 能力：`break_data`
- 标签：
  - 方块：`global_mutation_pool`, `conversion_blacklist`, `stable_anchor_immune`
  - 实体类型：`entity_mutation_pool_passive`
- 着色器：`observer_veil`
- 包网络：`sync_region`, `core_activate`
