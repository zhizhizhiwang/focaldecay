# AI 守则
1.默认powershell为gbk, 编辑文件请使用utf-8

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

### 2.1 末地王座（The Throne）——取代原"观测者核心结构"
- **类型**：巨大黑曜石王座，环绕末地水晶，中央空基座。
- **生成（2026-08-19 定稿）**：每末地维度仅一个，**生成于主岛与外岛之间的虚空环带**（需末影珍珠/鞘翅到达），位置由世界种子确定（方向 + 距离）；生成时规避末影龙活动/锁定相关机制（如不在龙的战斗半径内、不干扰水晶刷新）。
- **不可破坏**：王座方块硬度等同基岩，仅可仪式操作。
- **用途**：将稳定锚原型机 + 完全稳定模型（未激活）升级为完全稳定锚（仪式详见 §3.5.1）。

### 2.2 观测者核心（Observer Core）——修复路径保留
- **类型**：原大型研究设施结构的中央核心块（结构生成改由王座取代后，核心块以独立目标形式保留）。
- **状态**：
  - **失效**（默认）：失焦已开始，核心块熄灭。
  - **激活**：玩家使用“重建的观测协议”后，核心块发光，失焦终止（"修复世界核心"路线）。
- **交互**：右键核心块打开 GUI，显示“观测者离线”或“观测者在线”。

### 2.3 语义碎片（Semantic Fragments）
- 7种不同物品
- 来源
  1. 由玫瑰失焦突变出现
  2. 固定生成于末地王座/末地城结构中
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

### 3.1 稳定锚原型机（Anchor Prototype）
- **注册名**：`anchor_prototype`（取代原 `stable_anchor` 与 `mutation_controller`）
- **方块实体**：`AnchorPrototypeBlockEntity`，实现 `MenuProvider` 提供 GUI
- **外观**：类似原稳定锚的发光结构，中央有明显插槽孔洞；插入模型后插槽发出对应模型颜色的光
- **功能**：
  - 插槽：可放入一个"观测模型"物品（存储 `ItemStack modelStack`）
  - 无模型：不提供任何保护或转换限制，仅装饰
  - 有模型：根据模型类型与训练数据，以自身为中心、`anchor_radius`（默认 8，切比雪夫）范围内生效
  - 模型可随时取出/更换（右键打开 GUI）
- **模型半径加成**：语义锁定/引导模型 +0；生物稳定模型 +4；完全稳定锚固定 32
- **放置时固化失焦状态（沿用 2026-08-10 行为）**：放置时先把范围内方块转换为当前周期失焦目标，再登记保护，避免稳定住转换前状态
- **保护/效果检测**：服务端与客户端分别维护"有效原型机"集合（位置 + 模型效果），放置/破坏/换模时更新并同步
- **GUI（标题“观测者原型机”）**：
  - 显示当前模型图标与名称
  - 效果描述：稳定目标摘要 / 稳定强度百分比 / 覆盖半径
  - 按钮：取出模型、打开训练界面
- **配方**（比原稳定锚简单，本身不提供稳定）：
  ```
  R F R
  F E F   R = 红石粉, F = 铁块, E = 末影之眼
  R F R
  ```
  输出 1

### 3.2 训练终端（Training Terminal）
- **注册名**：`training_terminal`
- **方块实体**：`TrainingTerminalBlockEntity`，实现 `MenuProvider`
- **GUI**：一个输入槽（放空白模型）+ 能量显示 + 训练进度条
- **能量（2026-08-19 定稿）**：接入 NeoForge FE（`IEnergyStorage` 附件，与通用能量 API 兼容）；**默认 FE 消耗为 0**——单模组游玩时该机制不启用，仅当配置开启且存在 FE 源时才消耗能量；可回退为消耗经验瓶（配置可切换）
- **训练交互（2026-08-19 定稿）**：
  1. 终端放入空白模型，点击"训练"，GUI 关闭，进入训练模式；
  2. 玩家手持空白模型，右键世界方块/生物收集目标（目标写入模型组件，带冷却/数量上限）；
  3. 回到终端打开 GUI，点击"完成"，空白模型变为对应模型物品。
- **训练逻辑（服务端处理，训练中模型不可取出）**：
  - 语义锁定模型：按上述流程收集方块/生物目标
  - 引导模型：同上，但记录的是"突变目标池"方块（旧突变控制器的继承）
  - 生物稳定模型：**不需要训练**（见 §4.3）
- **训练限制**：空白模型有记录数量上限；训练消耗输入不可撤销；训练后的模型可通过合成复制；升级版模型预留（后续扩展）
- 训练时长/能量消耗/记录上限：全部可配置

### 3.3 观测模型（Observer Models，物品 + DataComponents）
- 统一用 `ObserverModelData` 组件（NeoForge 1.21.1 `DataComponentType`）存储：
  ```
  type: "semantic_lock" | "guided" | "bio_stabilizer" | "total_stability"
  trainedTargets: List<String>    // 已训练目标（方块ID或标签表达式）
  trainedEntities: List<String>   // 可稳定实体类型
  stabilityStrength: double       // 0.0 - 1.0，影响保护概率/范围
  bioEnergy: int                  // 生物稳定模型剩余能量
  totalStability: boolean         // 完全稳定模型标记
  ```
- 模型列表：
  1. 空白模型 `observer_model_blank` — 合成（书 + 金锭 + 青金石 + 铜锭，任意形状）
  2. 语义锁定模型 `semantic_lock_model` — 训练；范围内被锁定的方块/生物不参与失焦
  3. 引导模型 `guided_mutation_model` — 训练；范围内突变目标池限制为训练列表
  4. 生物稳定模型 `bio_stabilizer_model` — 无需训练；消耗周围生物生命值换取能量，范围内所有方块与生物稳定
  5. 完全稳定模型 `total_stability_model` — 终极；王座仪式激活（获取见 §5.4）

### 3.4 观测者核心块（Observer Core Block，修复路径保留）
- **注册名**：`observer_core`
- 不可破坏、不可合成。
- 两种状态：`powered=false`（失效）和 `powered=true`（激活），由 `blockstate` 控制。
- 使用 `BlockBehaviour.Properties.of().strength(-1.0f, Float.MAX_VALUE).noLootTable()`。
- 激活逻辑：当玩家右键持有 `Rebuilt Observer Protocol` 时，播放动画（粒子+音效），几秒后设置 `powered=true`，触发胜利事件。
- 原"观测者核心结构"的世界生成由末地王座取代（见 §3.5 / §5）；核心块仍作为"修复世界核心"路线的目标保留。

### 3.5 末地王座（The Throne）
- 结构方块：黑曜石王座 + 环绕末地水晶 + 中央空基座
- 生成：每末地维度仅一个，远离主岛（需末影珍珠/鞘翅到达），位置由世界种子决定
- 不可破坏（硬度等同基岩），仅可通过仪式操作

#### 3.5.1 王座仪式
1. **条件**：玩家在末地，携带稳定锚原型机（物品或已放置）+ **完全稳定模型（未激活）**（获取见 3.5.2），在王座基座处右键。
2. **触发**：周围末地水晶激活，生成大量粒子效果；进入仪式状态（进度持久化，断开/离开可暂停或失败，配置可定）。
3. **维持（2026-08-19 定稿）**：玩家需在仪式时长内（默认 3~5 分钟，可配置；33 分钟仅作致敬原文的可选上限）保持原型机与王座连接；期间按波次生成强敌/环境干扰（生成表与间隔可配置）。
4. **完成**：原型机升级为**完全稳定锚**，模型变为 `total_stability_model`，广播 `ThroneRitualPacket`，播放音效与成就。
5. **结果**：完全稳定锚可拾取并重新放置在任何地方。

#### 3.5.2 完全稳定模型获取（2026-08-19 定稿）
- **第一枚**：击败末影龙后的稀有掉落（未激活）。
- **后续复制**：王座仪式激活后，**已激活的完全稳定模型 + 空白模型**合成可复制出新的完全稳定模型（复制产物是否仍需仪式激活：默认"仍需仪式"，可调）。
- 不走训练终端。

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

### 4.2 区域覆盖（RegionOverride）——由"引导模型"继承（原突变控制器功能）
- 2026-08-19 修订：原"突变控制器"方块被移除，其"缩小突变目标池"能力由**引导模型**（§3.3）继承；实现机制仍复用本节的 `RegionOverride`（中心 = 原型机位置，标签列表 = 模型训练列表）。
- 服务端：
  - `MutationPoolManager` 是维度级别的 `SavedData`，存储覆盖区域列表。
  - 每次放置/破坏原型机、更换/训练模型时，标记 dirty，序列化为 NBT。
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

### 4.3 原型机保护与模型效果
- 服务端与客户端均维护"有效原型机"列表（位置 + 生效模型），通过原型机放置/破坏/换模事件更新。
- 当方块坐标落在某原型机效果范围内时：
  - 语义锁定：被训练目标命中 → 不参与视觉转换、交互不转换（即"保护"）
  - 引导模型：突变目标池限制为训练列表（走 §4.2 RegionOverride）
  - 生物稳定 / 完全稳定：范围内全部不转换
- **客户端同步（2026-08-10 实现，2026-08-19 扩展）**：通过 `SyncRegionDataPacket`（S→C，含维度、原型机位置、半径、模型效果摘要）在玩家登录/切换维度/原型机变化时同步；客户端渲染与中键选取均查询该数据。

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
  - 阶段判定（2026-08-10 已接入 §6）：`currentStage(days)` 取 `FocalDecayWorldData` 末日天数，对照 `stage2_day` / `stage3_day`；统一识别函数 `getVisibleTarget(...)` 供生存破坏、创造中键选取、客户端预览共用（两端同种子）。
  - **累积转换（2026-08-13）**：方块转换改为"有记忆"状态——每周期抽中的方块换新材质，未抽中的保留上一次材质，而不是回退原方块，实现世界逐渐崩坏；做法是从当前周期向前回退扫描最近一次抽中周期（确定性、两端一致，扫描上限 128 周期）。
  - **方块诞生周期（2026-08-13 新增）**：玩家放置的方块记录"诞生周期"（`MutationPoolManager` 维度级持久化），转换只从"诞生周期 + 1"开始累积——放置瞬间及同一周期内保持原方块，之后才随周期逐渐崩坏；世界原生方块仍从周期 0 开始。服务端放置/破坏事件维护该表，`SyncRegionDataPacket` 同步给客户端用于预览，锚固化和破坏转换同样尊重诞生周期。
- 全局池以不可变排序列表形式存在，新增操作仅在服务端执行，并通过网络包在周期边界同步到客户端。”
- 带有方块实体的目标均不转换

### 5.2 交互锁定
- **统一方块识别函数（2026-08-10 新增）**：`MutationHelper.getVisibleTarget(original, pos, worldSeed, periodIndex, pool, probability, isProtected)` 为生存破坏、创造中键选取、客户端预览共用的唯一识别入口；受保护位置一律返回原方块。
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
- **创造模式支持（2026-08-10 新增，破坏行为最终修正）**：创造模式**破坏保持原版行为**——无掉落、不收入背包、不执行转换（仅生存模式破坏触发转换掉落）；中键选取（pick block）通过 Mixin `Minecraft#pickBlock` 返回可见的"失焦目标"方块。

### 5.3 右击交互
- 不触发真实转换。玩家放置方块或使用物品时，均针对原方块。由客户端视觉效果处理，服务端不干预。
- **原型机（2026-08-19）**：右键原型机打开 GUI，查看/取出/更换观测模型。
- **模型训练交互（2026-08-19 定稿，语义锁定/引导模型）**：终端放入空白模型点"训练"→ 关闭 GUI → 手持空白模型右键方块/生物收集目标 → 回终端点"完成"生成对应模型。

---

## 6. 末日阶段系统

### 6.1 全局计时器
- 使用单独的 `SavedData` 记录世界创建后的天数。
- **实现（2026-08-10）**：`FocalDecayWorldData`（存于主世界 DimensionDataStorage），`ServerTickEvent.Post` 驱动，玩家数 >0 时累计，每 24000 tick（20 分钟游戏日）天数 +1 并通过 `SyncWorldDataPacket` 广播；玩家登录/切换维度时补发。
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
  - `entityMutationChance`：实体每周期转换概率
  - `postIntensity`：后处理强度乘数
- 配置值通过 `FocalDecayConfig` 读取。
- **实现（2026-08-10）**：周期 `base_interval/stage2_interval/stage3_interval`（100/60/40）、方块概率 `block_mutation_chance_stage1/2/3`（0.1/0.6/1.0）、实体概率 `entity_mutation_chance_stage2/3`、阶段开关 `enable_stage_system`。

### 6.3 方块影响范围扩展
- 阶段1：仅 `isCollisionShapeFullBlock()` 方块。
- 阶段2：增加 `BlockBehaviour.Properties.dynamicShape()` 为非完整但有碰撞箱的方块（如栅栏、玻璃板）。但需在渲染时处理模型替换，可能需特殊处理。
- 阶段3：影响范围与阶段2一致（完整 + 非完整有碰撞箱方块）。
- **实现（2026-08-10 / 2026-08-13 修订 / 2026-08-19 移除空气转换）**：`MutationHelper.isConversionSource(state, level, pos, stage)` 统一判定（含"不完整方块排除转换源"）：阶段1 仅完整方块；阶段2+ 增加非完整但有碰撞箱方块；空气/方块实体/黑名单始终排除。客户端 `isCandidate` 与服务器交互共用该函数。

### 6.4 实体转换
- 根据不同阶段决定池, 源池和目标池始终应该一致
- 每周期（不同阶段周期不同）对每个实体生成随机数，若小于概率则转换。
- 转换方式：从标签中随机选取目标实体类型，使用 `EntityType.create(level)` 生成新实体，复制位置、兼容的NBT（保留年龄等），移除旧实体，生成新实体。应用确定性随机种子：`(worldSeed ^ entity.blockPosition().asLong() ^ tick)` 选择目标类型。
- 特殊处理掉落物, 使其目标物品改变
- **实现（2026-08-10）**：`DoomsdayHandler` 每阶段周期对 `Mob`（排除玩家）与 `ItemEntity` 掷确定性骰子 `mix64(worldSeed ^ pos.asLong() ^ tick)`；`Mob` 从三阶段实体池（被动/中立/敌对，源池=目标池）选目标类型，NBT 复制（去 UUID）替换；掉落物改为方块池随机方块物品。

### 6.5 与稳定系统的联动（2026-08-19）
- 生物稳定模型：在阶段 3 消耗双倍 `bioEnergy`；范围内被动生物不参与实体转换（§6.4 跳过）。
- 完全稳定锚：无论阶段，范围内方块与实体（含玩家）完全不受失焦影响。
- 语义锁定/引导模型：阶段 3 效果减半（可配置），完全稳定锚不受影响。
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
- **受保护方块（2026-08-10）**：`ClientRenderCache.resolve` 先查 `isProtected(pos)`，保护范围内不替换模型、不入缓存。

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
  - 维度ID、**有效原型机**列表（位置 + 半径 + 模型效果摘要：语义锁定目标 / 引导池 / 生物稳定 / 完全稳定）、方块诞生周期表（位置 → 周期）（2026-08-10/2026-08-13 已实现锚与诞生周期；2026-08-19 起改为原型机/模型数据，覆盖区域为引导模型的实现载体）。
  - 在玩家登录、切换维度、原型机放置/破坏/换模、方块放置/破坏（诞生周期变化）时发送。
- **ObserverCoreActivatePacket**（S→C）：
  - 当核心被激活时，发送给所有玩家，触发全局动画和成就。
- **ThroneRitualPacket**（S→C，2026-08-19 规划）：王座仪式进度/波次/完成事件同步。
- **BreakDataSyncPacket**（可无需，挖掘数据仅存服务器，客户端无需知道目标）。

### 8.2 网络注册
- 使用 NeoForge 21.1 **Payload API**（`RegisterPayloadHandlersEvent` + `PayloadRegistrar`；`SimpleChannel` 已在 21.1 移除），协议版本 “1”。

---

## 9. 配置系统

### 9.1 配置文件
- 位置：`config/focal_decay.toml`
- 使用 NeoForge 的 `ModConfigSpec` 构建。
- 分类：
  - **Server**（同步到客户端）：`stage2_day`, `stage3_day`, `base_interval`, `stage2_interval`, `stage3_interval`, `entity_mutation_chance_stage2`, `entity_mutation_chance_stage3`, `block_mutation_chance_stage1/2/3`（0.1/0.6/1.0）, `enable_stage_system`, `enable_core_repair`, 原型机/模型/王座相关服务端参数（见下）。
  - **Common**（服务器/客户端各自加载）：`anchor_radius`（原型机默认 8）, `post_intensity`。
  - **Client**：`postProcessEnabled`, `surface_update_frequency`, `max_render_distance`。
- **新增可配置项（2026-08-19 规划，均带默认值便于整合包修改）**：
  - 原型机：`prototype_radius`（默认 8，替代 `anchor_radius` 语义）、各模型半径加成（生物稳定 +4）、完全稳定锚半径（固定 32）
  - 训练终端：训练所需能量/时长（**FE 默认消耗 0，单模组不启用**）、空白模型记录数量上限、经验瓶回退开关、训练交互冷却
  - 生物稳定模型：生命值→能量换算、`bioEnergy` 消耗速率、阶段3双倍消耗开关、范围内实体稳定开关
  - 王座仪式：仪式时长（默认 3~5 分钟，33 分钟为可选上限）、波次强度/间隔、所需物品

### 9.2 数据生成
- 方块标签：`focal_decay:global_mutation_pool` 自动生成，通过 `TagsProvider<Block>` 添加所有符合条件的原版方块。
- 实体类型标签：`focal_decay:entity_mutation_pool_passive` 包含如 `minecraft:sheep`, `minecraft:cow` 等。
- 战利品表：语义碎片添加到相应原版战利品表，使用 `GlobalLootModifier` 或直接修改 `LootTableLoadEvent`。
- 配方：稳定锚、突变控制器使用标准 `ShapedRecipeBuilder`。

---

## 10. 事件监听与核心逻辑挂载

### 10.1 服务端事件
- **BlockEvents**：
  - `BlockEvent.BreakEvent`：处理真实转换掉落；破坏原型机/训练终端/王座相关方块时移除对应数据（有效原型机、模型槽、诞生周期等）。
  - `BlockEvent.EntityPlaceEvent`：放置原型机/训练终端时更新有效原型机列表与区域数据。
- **PlayerEvents**：
  - `PlayerEvent.PlayerLoggedInEvent`：同步区域数据。
  - `PlayerEvent.Clone`：复制能力数据。
- **TickEvents**：`ServerTickEvent.PRE` 或使用 `LevelTickEvent` 驱动末日计时、实体转换、生物稳定模型能量消耗与王座仪式计时。
- **训练交互（2026-08-19 规划）**：训练模式下 `PlayerInteractEvent.RightClickBlock` / `RightClickEntity` 收集训练目标；`TrainingTerminalBlockEntity.tick()` 推进训练进度。
- **王座仪式（2026-08-19 规划）**：右键王座基座触发；仪式期间 `ServerLevel.scheduleTick` 或 `LevelTickEvent` 驱动波次生成与计时；完成后替换为完全稳定锚并广播 `ThroneRitualPacket`。

### 10.2 客户端事件
- **ClientTickEvent.PRE**：更新渲染周期。
- **RenderLevelStageEvent**：应用后处理。
- **RegisterKeyMappingsEvent**（可选）：注册关闭后处理的快捷键。

### 10.3 Mixin 列表
- `MixinChunkRenderDispatcher`：替换编译时的方块状态。
- `MinecraftPickBlockMixin`：中键选取返回"失焦目标"方块（替换 `Minecraft#pickBlock` 中的 `ClientLevel#getBlockState`）。
- 不修改底层网络，仅注入渲染和方块处理。

### 10.4 测试命令（2026-08-13 新增）
- `/focaldecay days`：查询当前末日天数与阶段。
- `/focaldecay days <n>`：手动设定天数（权限 2），`FocalDecayWorldData.setDays` 落盘并通过 `SyncWorldDataPacket` 广播，客户端立即按新阶段重算（周期/概率/影响范围）。

---

## 11. 状态存储与持久化

- **MutationPoolManager**：`SavedData`，通过 `DimensionDataStorage` 读写，保存：
  - `List<RegionOverride>`（中心坐标、半径、表达式字符串）
  - 稳定锚位置集合（可从方块实体遍历获取，但缓存提高效率）
  - 方块诞生周期表 `Map<BlockPos, Long>`（玩家放置的方块，放置时记录 periodIndex，破坏时移除）
- **末日计时**：`FocalDecayWorldData`，存储游戏天数，独立维护，每20分钟游戏日更新一次, 服务端运行时在玩家数为0时暂停计时。
- **玩家挖掘数据**：使用 NeoForge Capability `BreakData`，自动同步。

---

## 12. 开发实施顺序（推荐）

1. **框架搭建 + 基础注册**：注册原型机、训练终端、观测模型物品、王座方块；沿用已完成的突变池/末日阶段/渲染/网络基础。
2. **模型 DataComponents 与训练逻辑**：`ObserverModelData` 组件、空白模型合成、训练终端 GUI + 能量（FE/经验瓶）、语义锁定与引导模型训练交互、复制配方。
3. **原型机效果应用**：有效原型机列表、保护范围/引导突变池接入 `getVisibleTarget` 与渲染缓存，`SyncRegionDataPacket` 扩展同步。
4. **生物稳定模型**：周围生物生命值消耗、能量换算与衰减、范围内方块/实体稳定、阶段3双倍消耗。
5. **末地王座结构与仪式**：王座生成（种子决定、远离主岛）、仪式触发/计时/波次、`ThroneRitualPacket`。
6. **完全稳定锚**：仪式升级、半径 32 完美稳定、特殊视觉。
7. **与末日阶段、渲染、网络整合**：模型效果在阶段3的衰减规则、渲染缓存/中键选取适配。
8. **彩蛋与打磨**：粒子（"42ms"、"完备语义分类"）、音效、专属贴图/模型、测试与平衡。

---

## 13. 附录：所有自定义内容标识符

- 方块：`anchor_prototype`, `training_terminal`, `observer_core`, 王座相关（`throne_base` 等，待定）
- 物品：`observer_model_blank`, `semantic_lock_model`, `guided_mutation_model`, `bio_stabilizer_model`, `total_stability_model`, `semantic_fragment_rose`, `semantic_fragment_throne`, `semantic_fragment_semantic`, `semantic_fragment_42ms`, `semantic_fragment_crystal`, `semantic_fragment_aaron`, `semantic_fragment_cheng`, `rebuilt_observer_protocol`
- 方块实体类型：`anchor_prototype`, `training_terminal`
- 能力：`break_data`
- 标签：
  - 方块：`global_mutation_pool`, `conversion_blacklist`, `stable_anchor_immune`
  - 实体类型：`entity_mutation_pool_passive`
- 着色器：`observer_veil`
- 包网络：`sync_region`, `sync_world`, `core_activate`, `throne_ritual`
