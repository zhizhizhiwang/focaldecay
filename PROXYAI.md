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
- **观测者核心（2026-08-21 接入）**：王座中央空基座生成 `observer_core`（前任观测者遗骸/插座），供玩家安装候选观测者模型（§3.4）；仪式触发跳过核心方块。

### 2.2 观测者核心（Observer Core）——修复路径保留
- **类型**：原大型研究设施结构的中央核心块（结构生成改由王座取代后，核心块以独立目标形式保留）。
- **状态**：
  - **失效**（默认）：失焦已开始，核心块熄灭。
  - **激活**：玩家**安装候选观测者模型**后，核心块发光，失焦终止（"新观测者取代旧观测者"路线）。
- **交互（2026-08-21 已实现，主线重构后待调整）**：右键核心块打开 GUI，显示"旧观测者：离线/新观测者：在线"与"安装候选观测者"按钮；首次右键赠送一枚语义碎片。

### 2.3 语义碎片（Semantic Fragments）——观测者的记忆知识（2026-08-21 重定位）
- 7种不同物品；不再是"合成钥匙"，而是**语义知识**：通过配方"候选观测者模型 + 碎片"喂给候选模型，增加训练进度（`candidate_fragment_points`）。
- **获取方式（里程碑化，移除随机箱子注入）**：
  1. 碎片·玫瑰 —— 首次完成任意模型训练（训练终端"完成训练"）
  2. 碎片·王座 —— 末地王座基座宝箱固定产出（非随机）
  3. 碎片·完备语义 —— 第一次右键观测者核心方块时获取（已实现）
  4. 碎片·42ms —— 首次完成王座仪式
  5. 碎片·硫铜结晶 —— 铜块失焦突变概率掉落（已实现，`fragment_copper_mutation_chance`）
  6. 碎片·Aaron的誓约 —— 击败末影龙后的"遗物宝箱"（生成在主岛地面、传送门正下方附近，与完全稳定模型同箱，替代掉落物防掉虚空/火焰）
  7. 碎片·程玖章的最后传输 —— 首次将某个引导模型概念完备度练到 100%（q=1.0）
  - 里程碑发放由 `FocalDecayWorldData` 位掩码持久化，防重复获得。
- **碎片列表与Lore**
  1. 碎片·玫瑰 —— “玫瑰，是玫瑰，是玫瑰……”
  2. 碎片·王座 —— “它不是能用语言描述的事物。”
  3. 碎片·完备语义 —— “真正的理解。”
  4. 碎片·42ms —— “这是硬性时间要求。”
  5. 碎片·硫铜结晶 —— “一小块Dr. Kacper Darlin”
  6. 碎片·Aaron的誓约 —— “世界会继续美丽。”
  7. 碎片·程玖章的最后传输 —— “那就这么决定了。”
- **合成**：`rebuilt_observer_protocol` 物品与合成配方**移除**（碎片不再合成协议；碎片 = 候选模型训练材料）。

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
  - 效果描述：模型效果摘要（引导模型显示"概念名 + 完备度 q"，语义锁定显示目标列表）/ 稳定强度百分比 / 覆盖半径
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
  1. 终端放入空白模型，点击"训练：语义锁定/引导"，GUI 关闭，模型进入"训练中"状态（组件 type=training）；
  2. **模型可随时取出使用**：手持训练中模型右键世界方块/生物，每次右键添加一个目标（方块写入 trainedTargets，生物写入 trainedEntities），直到数量上限；
  3. **可随时继续训练**：将训练中模型放回终端，可继续收集或直接点击"完成训练"，生成对应的语义锁定/引导模型（保留全部目标）。
- **训练逻辑（服务端处理）**：
  - 语义锁定模型：按上述流程收集方块/生物目标
  - 引导模型：同上，收集的是概念的**示例方块**（旧突变控制器的继承），训练完成时解析概念与完备度 q（§4.2）
  - 生物稳定模型：**不需要训练**（见 §4.3）
- **训练限制**：空白模型有记录数量上限；训练消耗输入不可撤销；训练后的模型可通过合成复制；升级版模型预留（后续扩展）
- 训练时长/能量消耗/记录上限：全部可配置

### 3.3 观测模型（Observer Models，物品 + DataComponents）
- 统一用 `ObserverModelData` 组件（NeoForge 1.21.1 `DataComponentType`）存储：
  ```
  type: "semantic_lock" | "guided" | "bio_stabilizer" | "total_stability" | "candidate"
  trainedTargets: List<String>    // 已训练目标（方块ID或标签表达式）
  trainedEntities: List<String>   // 可稳定实体类型
  stabilityStrength: double       // 0.0 - 1.0；引导模型存"完备度 q"，语义锁定保留
  concept: String                 // 引导模型固化的概念标签（focal_decay:concept/* 或原版标签），训练完成时解析
  progress: int                   // 候选观测者训练进度（唯一目标数 + 碎片注入点数）
  bioEnergy: int                  // 生物稳定模型剩余能量
  totalStability: boolean         // 完全稳定模型标记
  ```
- 模型列表：
  1. 空白模型 `observer_model_blank` — 合成（书 + 金锭 + 青金石 + 铜锭，任意形状）
  2. 语义锁定模型 `semantic_lock_model` — 训练；范围内被锁定的方块/生物不参与失焦
  3. 引导模型 `guided_mutation_model` — 训练；范围内"概念内成员"突变时，以完备度 q 偏向概念邻域（方案 A，2026-08-20 定稿，详见 §4.2；替代旧的"目标池硬限制为训练列表"）
  4. 生物稳定模型 `bio_stabilizer_model` — 无需训练；消耗周围生物生命值换取能量，范围内所有方块与生物稳定
  5. 完全稳定模型——**两个独立物品**：`total_stability_model`（未激活，末影龙掉落）与 `total_stability_model_activated`（已激活，王座仪式后）；已激活模型 + 空白模型可复制（获取见 §5.4）
  6. 候选观测者模型 `observer_model_candidate` — **主线道具（2026-08-21 新设计）**：本质是无数量上限的"空白模型"，不经过训练终端；手持右键世界方块/生物收集目标（每个唯一目标 +1 进度），或通过配方"候选模型 + 语义碎片"注入知识（+`candidate_fragment_points`）；进度达到 `candidate_required_points`（默认 100）即 100% 完成。完成后的候选模型**兼具完全稳定模型效果**（插入原型机 = 半径 32 硬保护），并可在观测者核心处**安装为新观测者**（消耗模型 → 失焦终止 = 胜利）。**不可复制**。

### 3.4 观测者核心块（Observer Core Block，修复路径保留）
- **注册名**：`observer_core`
- 不可破坏、不可合成。
- 两种状态：`powered=false`（失效）和 `powered=true`（激活），由 `blockstate` 控制。
- 使用 `BlockBehaviour.Properties.of().strength(-1.0f, Float.MAX_VALUE).noLootTable()`。
- **安装逻辑（2026-08-21 主线重构）**：核心 = 前任观测者的遗骸/插座，位于末地王座。GUI 显示"旧观测者：离线"，携带**已完成的候选观测者模型**点击"安装"→ 消耗模型、播放特效并 `scheduleTick`（`observer_core_activation_ticks`，默认 100 tick）→ 完成时 `powered=true`、`FocalDecayWorldData.observerOnline=true`（方块/实体突变全部停止，客户端清空幽灵预览）、广播 `ObserverCoreActivatePacket` 与胜利消息（"新观测者已就位"）。
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

#### 3.5.2 完全稳定模型获取（2026-08-21 修订）
- **第一枚**：击败末影龙后生成"遗物宝箱"（主岛地面、传送门正下方附近，中心偏移 10~14 格、种子决定方向；内含未激活 `total_stability_model` + 碎片·Aaron的誓约），用箱子替代掉落物防止掉进虚空/火焰，且避开传送门生成时对平台的覆盖；概率 `ender_dragon_total_stability_drop_chance`（默认 1.0，可调成设计文档的稀有掉落）。
- **王座仪式后**：原型机升级为完全稳定锚，同时得到独立物品 `total_stability_model_activated`（已激活）。
- **后续复制**：`total_stability_model_activated` + 空白模型合成可复制出新的已激活完全稳定模型（仅已激活物品可复制，未激活不可复制）。
- 不走训练终端。

---

## 4. 突变池管理系统

### 4.1 三层模型：源 / 池 / 形态类（2026-09-15 重构）
突变关系被拆成三件互相正交的事，全部由**数据包标签**决定，运行时不再读任何标签：

| 层 | 标签 | 回答的问题 |
|---|---|---|
| **突变源** | 由池成员推导 + `focal_decay:mutation_source_extra` / `focal_decay:mutation_immune` | 哪些方块会失焦 |
| **突变池** | `focal_decay:mutation_pool/<名>` | 它会变成什么（语义邻域） |
| **形态类** | `focal_decay:shape_class/<名>` | 变成的东西几何上必须同类 |

#### 4.1.1 突变源（谁会被失焦）
- **池即源**：方块只要属于任意一个"可用池切片"，它就既是源也是目标。这条是结构性的，不是配置纪律。
- `focal_decay:mutation_immune`：**完全豁免**——既不做源也不做目标（原 `conversion_blacklist`，
  并补上了基岩/命令方块/传送门框架等技术性方块）。
- `focal_decay:mutation_source_extra`：**额外源**——会失焦，但永远不会被抽成目标。
  单向但**不会造成冻结方块**（它自己仍然能继续变出去）。
- **拒绝"只做目标不做源"的配置**：那会让方块一旦被变过去就永久冻结，直接违反"不收敛于固定物品"。
  构建器用一条不变式把这种配置结构性地排除了（见 §4.1.4）。
- **含水状态不参与失焦（2026-09-16）**：`!state.getFluidState().isEmpty()` → 直接返回原方块。
  原因是幽灵替换会把整格状态换掉，而 `SectionCompiler` 的流体渲染读的正是替换后的状态，
  干燥幽灵的流体为空 → 水面出现 1 格缺口。
  <b>必须按状态而不是按方块排除</b>：`StairBlock / SlabBlock / FenceBlock / WallBlock /
  TrapDoorBlock / IronBarsBlock` 全都实现了 `waterlogged`，按方块排除等于把整个形态类功能砍掉。
  判定放在 {@code MutationHelper.resolve} 这一唯一入口里，因此服务端与客户端自动一致。
- 旧实现的源门控是逐状态的 `isCollisionShapeFullBlock(level, pos)`；现在改成构建期逐方块算一次并缓存成
  `boolean[]`。等价性有依据：原版 `BlockStateBase#isCollisionShapeFullBlock` 本身就是**逐状态预缓存**
  的布尔字段（`BlockBehaviour.BlockStateBase.Cache`，用 `EmptyBlockGetter.INSTANCE` + 原点计算），
  构建期用同一套算法算一次即可，热路径上连形状查询都省了。

#### 4.1.2 突变池（会变成什么）
- `focal_decay:mutation_pool/<名>`，一个方块**可以进任意多个池**；抽目标时取"它所属全部池的**并集**"。
- 交叉归类是设计手法：`mutation_pool/color/white` 把白色羊毛/混凝土/陶瓦放进同一个池，
  于是白色羊毛既能变成别的羊毛（`mutation_pool/wool`），也能变成白色混凝土（并集抽取）。
- `mutation_pool/wild`（下界/末地各有变体）是**大池**：以 `wild_chance`（默认 0.25）的概率把抽取
  **整枝**切到它身上，保证长尾随机性。它**不是成员关系**——否则所有语义池会立刻塌缩成一个连通分量，
  局部结构全没了。`wild_chance = 0` 是纯局部漂移，`= 1` 就是旧版"一个大池抽所有"的行为。
- `wild_auto_include`（默认开）自动把"所有完整方块且无方块实体"的方块纳入大池，
  保证总池不会因为标签写漏而变小。想收紧到只有标签内容时关掉它。

#### 4.1.3 形态类（几何约束）
- `focal_decay:shape_class/<名>`：**目标必须与源同形态类**。楼梯只变楼梯、半砖只变半砖、
  栏杆只变栏杆，因此不会出现"半砖突变后旁边悬空""栏杆变成完整方块导致连接逻辑失效"。
- 未登记进任何形态类的方块走自动兜底：默认状态是完整方块 → `cube`，否则 → `none`（**不参与突变**）。
  也就是说**非完整方块必须显式登记才会参与**，这保留了旧版"门/楼梯/栅栏不参与"的安全默认。
- 默认登记 stairs / slabs / fences / fence_gates / walls / trapdoors / carpets / panes 八类，
  全部用原版标签（`#minecraft:stairs` 等），模组方块只要进这些标签就自动生效。
- **双格方块（门、床、高花）刻意不登记**：突变是逐坐标的纯函数，上下两半各自独立抽取就会抽出两种不同的门。
  运行时的守卫会基于方块状态里的 `DoubleBlockHalf` / `BedPart` 属性把它们剔除并打汇总警告
  （与具体方块类无关，模组方块同样适用）。要支持它们需要"锚半格"种子 + 配对写入，属于独立的一块工作。
- **状态迁移**：形态类保证几何同类，但状态还得搬。`MutationStateMapper` 把源状态里"目标方块也有"的
  同名属性值拷过去（楼梯的 `facing/half/shape`、半砖的 `type`、原木的 `axis`），
  用原版 `Property#getName(T)` + `Property#getValue(String)` 实现，**完全不需要强转**。
  排除 `waterlogged`（目标保持干燥，否则会被渲染层的流体检查挡掉，造成两端不一致）与
  `in_wall`（栅栏贴墙的下沉标记，真实方块更新会自动修正，预览路径不会）。

#### 4.1.4 不变式：对称 + 永不冻结（结构保证）
构建期对每个（池 × 形态类）切片施加一条规则：**成员少于 2 个就整体作废**。由此可得：
- 若 b 能经局部池被抽到 → 存在含 b 且在该形态类下 ≥2 个成员的切片 P → `local(b) ⊇ P`，即 `|local(b)| ≥ 2`；
- 若 b 能经大池被抽到 → `|wild ∩ 形态类(b)| ≥ 2`。

两种情况下 b 自己都至少有 2 个不同候选，**没有任何状态可以被"停住"**。
同时"共享至少一个池且同形态类"这个关系对两个方块完全对称，因此 `A→B` 与 `B→A` 同时成立或同时不成立。
`/focaldecay mutation audit` 就是量这三条性质的尺子（frozen / asymmetric / crossClass 必须全为 0）。

#### 4.1.5 运行时形态
```java
public final class MutationIndex {          // mutation/pool/MutationIndex.java
    private final Block[][] local;           // blockId -> 所属全部池的并集（按注册表 id 升序）
    private final boolean[] source;          // blockId -> 是否失焦（一次数组读）
    private final ClassifiedPool wild;       // 大池，按形态类切好
    private final ShapeClasses shapeClasses; // blockId -> 形态类
}
```
- 客户端每 2 帧扫描 16 区块半径内所有区块节的所有方块，逐方块做标签查找/字符串比较/集合运算是不可能接受的，
  所以全部标签语义在构建期摊平成数组；**服务端不需要同步任何池数据**（标签本来就同步给客户端）。
- 生命周期：`MutationIndexes` 按维度惰性构建并缓存，`TagsUpdatedEvent`（服务端数据包重载 / 客户端收到标签同步）
  时整体丢弃，下次访问自动重建。两端各自重建但输入完全相同，因此结果依旧一致。
- 引导模型的概念邻域同样走这张表（`MutationIndex#tagged`，带缓存），成员判定是一次 `boolean[]` 读。

#### 4.1.6 确定性随机源（2026-09-15 替换）
- 旧实现每步 `RandomSource.create(seed)` = 一次对象分配；阶段 1 概率 0.01 时期望回扫 100 步，
  等于每个可见方块每周期分配约 100 个对象。现在换成 `MutationRandom`：状态就是一个 `long`，
  SplitMix64 纯函数步进，零分配、零虚调用。
- 另一个理由是**契约稳定性**：`RandomSource.create` 返回什么实现由原版决定，原版换实现会让所有老存档的
  失焦目标整体重排；自己实现的算法只由本文件的常数决定，跨版本跨 JVM 稳定。
- 浮点只走 IEEE754 精确路径（`(long >>> 11) * 2^-53`），索引用 Lemire 乘移位
  （`Math.unsignedMultiplyHigh`，无拒绝采样循环），因此两端逐位一致。

#### 4.1.7 实体全局池（未改动）
  - 所有实体突变逻辑均使用此池
    - 一阶段包括被动实体(不包括marker, 掉落物, 激活的tnt, 火球之类的保留实体, 只包含有ai的动物, 生物一类)
    - 二阶段加入中立实体, 同样要求是有AI的实际生物
    - 三阶段加入敌对实体, 要求同上
  - 掉落物突变成随机方块物品时取大池的跨形态扁平视图（物品没有几何，不受形态类约束）。

### 4.2 区域引导（Region Guidance）——由"引导模型"实现（原突变控制器功能）
- 2026-08-19 修订：原"突变控制器"方块被移除，其功能由**引导模型**（§3.3）继承。
- **2026-08-20 定稿（方案 A）**：废弃"突变目标池硬限制为训练列表"的旧设计（阶段3 可把半径内任意方块稳定刷成训练目标，过度 OP 且不符合原文"苹果实验"的概念一致性），改为**概念引导**：
  - **概念**：训练列表不再直接作为目标池，而是用于**指认概念**。训练完成时解析并固化到模型数据（`concept` 标签 + 完备度 q）：
    - 概念标签来源：策展标签 `focal_decay:concept/*`（数据生成，如 wood/ore/stone/glass/terracotta/wool…）；兜底用原版标签推断（排除通用标签黑名单，如 `#minecraft:mineable/*`、`#minecraft:block`）。
    - 概念邻域 = 概念标签下的全部方块，过滤空气、带方块实体、`mutation_immune`、以及不参与突变的形态类。
    - 训练目标无法指认任何有效概念（不共享有效标签）→ `concept` 置空、q=0，模型无效（对应原文"只输出一个标签的分类器毫无效果"）。
  - **作用规则（服务端与客户端共用同一公式）**：
    1. **源门控**：仅当源方块是概念内成员（属于 `concept` 标签）时，其突变才被引导；概念外方块照常按全局池随机（对应原文"观测雪梨时失焦概率无变化"）。
    2. **目标偏向**：概念内成员抽中突变时，以概率 q 从概念邻域选目标，以 1−q 回退全局池（对应原文"依旧是苹果，有些时候是另外的东西"）。
    3. q 只作用于**目标选择**，不改变阶段突变骰子（概率/周期/种子不变）。
    4. 累积语义不变：引导后的目标同样参与 `cumulativeTarget` 回退扫描，"变了的就变了"。
  - **完备度 q**：`q = clamp(|trainedTargets ∩ 概念邻域| / |概念邻域|, 0, 1)`；少于 `guided_min_trained`（默认 2）视为残缺分类，q 归零；可配置倍率 `guided_q_multiplier` 与上限 `guided_q_cap`；阶段3 q 减半（§6.5，可配置）。
- 实现机制仍复用 `MutationPoolManager`（中心 = 原型机位置，半径 = 模型半径，概念 = 固化标签）：
  - 服务端：`getGuidedBias(pos, state, stage)` 按上述规则判定"概念邻域（q 分支）或常规分支（1−q 分支）"。
  - **概念邻域与成员判定在效果登记时就预计算好**（`PrototypeEffect` 里存 `ClassifiedPool` + `Set<Block> trained`）：
    旧实现逐方块都要 `getKey(state.getBlock()).toString()`（字符串分配）再 `List<String>.contains`，
    在"客户端每 2 帧扫一遍可见范围"的热路径上是必须先拔掉的性能债。
  - 客户端：通过 `SyncRegionDataPacket` 接收原型机效果（含 `concept` 标签与 q），
    渲染时按区块节把概念标签解析成 `ClassifiedPool` 一次，循环体内只做半径比较与 `boolean[]` 成员判定。
    **池本身不需要同步**——标签本来就同步给客户端。
  - 多原型机重叠：同一位置若落在多个引导模型范围内，取"源方块是概念成员且 q 最大"的那个生效（最强引导胜出，确定性）；其余引导模型不参与。
- 引导模型的目标同样受**形态类门控**：一个只训练了原木与木板的引导模型，不会把橡木楼梯变成木板。
- 特别处理：破坏时才会决定突变目标，无法模拟"突变过程中概念成员身份变化"的情况，直接以突变发生时的源方块判定门控。

### 4.3 原型机保护与模型效果
- 服务端与客户端均维护"有效原型机"列表（位置 + 生效模型），通过原型机放置/破坏/换模事件更新。
- 当方块坐标落在某原型机效果范围内时：
  - 语义锁定：被训练目标命中 → 不参与视觉转换、交互不转换（即"保护"）
  - 引导模型：概念内成员突变时以 q 偏向概念邻域（走 §4.2 概念引导）
  - 生物稳定 / 完全稳定：范围内全部不转换
- **客户端同步（2026-08-10 实现，2026-08-19 扩展）**：通过 `SyncRegionDataPacket`（S→C，含维度、原型机位置、半径、模型效果摘要）在玩家登录/切换维度/原型机变化时同步；客户端渲染与中键选取均查询该数据。

---

## 5. 确定性随机与目标计算

### 5.1 算法（2026-09-15 重写）
```java
public static BlockState resolve(BlockState source, BlockPos pos, long worldSeed, long periodIndex,
                                 MutationIndex index, double chance, GuidedBias bias,
                                 Protection protection, long birthPeriod) {
    if (protection.hard() || chance <= 0.0 || index.isEmpty()) return source;
    long fromPeriod = birthPeriod >= 0 ? birthPeriod + 1 : 0;      // 诞生周期门控
    if (periodIndex < fromPeriod) return source;
    Block b = source.getBlock();
    if (!index.isSource(b)) return source;                          // 源门控（一次数组读）
    int shapeClass = index.shapeClass(b);
    if (shapeClass == NONE) return source;

    int cap = (int) Math.min(periodIndex - fromPeriod + 1, CUMULATIVE_SCAN_CAP);
    for (int back = 0; back < cap; back++) {
        long state = MutationRandom.seed(pos, worldSeed, periodIndex - back);
        if (protection.softChance() > 0 && roll(state = next(state), softChance)) continue;
        if (!roll(state = next(state), chance)) continue;           // 本周期没抽中
        ClassifiedPool pool =
              bias.active(shapeClass) && roll(state = next(state), bias.q()) ? bias.pool()
            : (localCount > 0 && wildCount > 0)
                ? (roll(state = next(state), wildChance) ? index.wild() : null)   // null = 局部并集
                : (localCount > 0 ? null : index.wild());
        Block target = pool == null
                ? index.local(b, nextInt(state = next(state), localCount))
                : pool.get(shapeClass, nextInt(state = next(state), pool.count(shapeClass)));
        return MutationStateMapper.get().map(source, target);       // 状态迁移（§4.1.3）
    }
    return source;
}
```
- 抽取规则与不变式见 §4.1；`periodIndex` = `gameTick / base_interval`（**与阶段无关**，见下）。
- 服务端与客户端使用相同种子、相同查表、相同公式，因此结果逐位一致；**唯一需要两端一致的配置是
  `wild_chance` 这类 SERVER 配置（NeoForge 会同步给客户端）**。
- 每一步消耗的随机步数都是 (源方块, 形态类, 配置) 的纯函数，所以两端永远同步推进。
- 抽到自己也是合法结果（自环），与原实现"抽中即定格"的语义一致，不再重抽。
- **转换概率（2026-08-10 新增）**：每个方块每周期只有一定概率被转换，概率随阶段变化：阶段1/2/3 暂定 `0.01 / 0.3 / 0.9`（Server 配置 `block_mutation_chance_stage1/2/3`）。
  - 概率 roll 与目标选择共用同一确定性随机序列，先 roll 后取目标。
  - **种子必须经 SplitMix64 雪崩混合（2026-08-10 修复）**：`periodIndex` 是小数字，直接异或只扰动种子低几位，LCG 首次 `nextDouble` 几乎不变，会导致"同一批固定位置每周期都失焦"。混合后每次周期切换失焦位置集合完全重排。
  - 阶段判定（2026-08-10 已接入 §6）：`currentStage(days)` 取 `FocalDecayWorldData` 末日天数，对照 `stage2_day` / `stage3_day`；统一识别函数 `MutationHelper.resolve(...)` 供生存破坏、右键交互、创造中键选取、客户端预览、锚固化共用。
  - **累积转换（2026-08-13）**：方块转换改为"有记忆"状态——每周期抽中的方块换新材质，未抽中的保留上一次材质，而不是回退原方块，实现世界逐渐崩坏；做法是从当前周期向前回退扫描最近一次抽中周期（确定性、两端一致，扫描上限 128 周期 = `MutationHelper.CUMULATIVE_SCAN_CAP`）。
- **方块诞生周期（2026-08-13 新增，2026-09-15 修订）**：玩家放置的方块、以及被右键交互转换过的方块记录"诞生周期"
  （`MutationPoolManager` 维度级持久化），转换只从"诞生周期 + 1"开始累积——放置/转换瞬间及同一周期内保持原方块。
  记录的动因有两个：旧版的"放置后延迟崩坏"，以及**转换依赖源方块身份**之后必须抑制的抖动
  （方块被转换后候选集就变了，客户端下一帧会按新身份重掷，表现为"右键一次变一次"）。
  - 同步改为**增量包** `SyncBirthPeriodPacket`（位置 + 周期，`period < 0` 表示删除）：
    旧实现每次放置/破坏都重发整张诞生周期表，而那张表随建造无上限增长。
  - **剪枝**：比"当前周期 − 128"更早的记录对结果没有任何影响（回扫本来就够不到），
    服务端每 6000 tick 删一次，语义完全等价而表重新有界。
- **引导偏向（2026-08-20 方案 A）**：引导模型不再硬限制目标池；概念内成员抽中突变后，
  以概率 q 走概念邻域、否则回退常规分支（局部并集 / 大池）。偏向只发生在目标选择阶段，不改变突变骰子。
  概念邻域现在是预计算的 `ClassifiedPool`（按形态类切好、按注册表 id 定序），
  成员判定是一次 `boolean[]` 读——旧实现每次都要解析标签 ID、查标签、再线性扫一遍标签成员。
- 带有方块实体的方块既不是源也不会成为目标。

### 5.2 交互锁定
- **统一方块识别函数（2026-08-10 新增，2026-09-15 收拢为 `MutationHelper.resolve`）**：`MutationHelper.resolve(source, pos, worldSeed, periodIndex, index, chance, bias, protection, birthPeriod)` 为生存破坏、右键交互、创造中键选取、客户端预览、锚固化共用的唯一识别入口；受保护位置、非源方块、没抽中的情况一律返回原方块。
- **挖掘开始**：`PlayerInteractEvent.LeftClickBlock`（服务端）记录：
  - 目标方块状态 `targetState`（此时计算）
  - 周期索引 `periodIndex`(锁定方块)
  - 存储在玩家能力 `Capability<BreakData>` 中。
- **挖掘速度与工具要求（2026-08-21）**：由**当前可见的失焦目标**决定——客户端 `MultiPlayerGameModeMixin` 把挖掘进度计算改为读可见目标（走 `ClientRenderCache.miningState` 缓存，O(1)）；服务端掉落/经验传入玩家主手工具，目标方块的 `requiresCorrectToolForDrops` 生效（拿对工具才有掉落）。
- **方块破坏**：`BlockEvent.BreakEvent` 中，如果玩家有 `BreakData` ，则取消默认掉落，执行：
  - 服务端将方块直接设置为 `targetState`（无掉落）。
  - 然后调用 `targetState.getDrops()` 生成物品掉落（传入玩家主手工具，遵循挖掘等级）。
  - 给予目标方块的挖掘经验值（`targetState.getExpDrop()`，同样传入工具）。
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
- **天气突变（2026-08-21 新增）**：与实体突变同周期同风格——每阶段周期按 `weather_mutation_chance_stage1/2/3`（默认 0.0 / 0.05 / 0.15）掷确定性骰子，命中把主世界天气随机转为与当前不同的状态（晴/雨/雷暴），持续 60~360 秒；观测者在线（失焦终止）时不触发。

### 6.3 方块影响范围扩展
- **2026-08-21 修订：所有阶段仅允许"完整立方体碰撞"方块作为转换源。** 门/楼梯/栅栏/玻璃板等模型不完整方块不再参与失焦。
- **2026-09-15 修订：非完整方块改为"显式登记即可参与"。** 形态类标签（`focal_decay:shape_class/*`）登记过的方块
  会参与失焦，并且**只在自己的形态类内部互相突变**；没登记的非完整方块仍然完全不参与。
  也就是说"门/楼梯/栅栏不参与"从硬编码规则变成了默认配置（默认登记了楼梯/半砖/栏杆/栅栏门/墙/活板门/地毯/玻璃板八类）。
- 空气、带方块实体、`mutation_immune` 方块始终排除。
- **实现**：`MutationIndex#isSource(Block)` 统一判定，构建期预计算成 `boolean[]`（等价性论证见 §4.1.1）；
  客户端 `isCandidate` 与服务器交互共用同一个查表。
- 阶段不再影响源门控（阶段只改变概率与周期），因此该判定与阶段解耦。

### 6.4 实体转换
- 根据不同阶段决定池, 源池和目标池始终应该一致
- 每周期（不同阶段周期不同）对每个实体生成随机数，若小于概率则转换。
- 转换方式：从标签中随机选取目标实体类型，使用 `EntityType.create(level)` 生成新实体，复制位置、兼容的NBT（保留年龄等），移除旧实体，生成新实体。应用确定性随机种子：`(worldSeed ^ entity.blockPosition().asLong() ^ tick)` 选择目标类型。
- 特殊处理掉落物, 使其目标物品改变
- **实现（2026-08-10）**：`DoomsdayHandler` 每阶段周期对 `Mob`（排除玩家）与 `ItemEntity` 掷确定性骰子 `mix64(worldSeed ^ pos.asLong() ^ tick)`；`Mob` 从三阶段实体池（被动/中立/敌对，源池=目标池）选目标类型，NBT 复制（去 UUID）替换；掉落物改为方块池随机方块物品。

### 6.5 与稳定系统的联动（2026-08-19）
- 生物稳定模型：在阶段 3 消耗双倍 `bioEnergy`；范围内被动生物不参与实体转换（§6.4 跳过）。
- 完全稳定锚：无论阶段，范围内方块与实体（含玩家）完全不受失焦影响。
- 语义锁定：阶段 3 效果减半 = 保护强度衰减（`semantic_lock_stage3_strength`，默认 0.5）——被锁定方块每周期先掷"守住"骰子，失守（1−强度）才参与突变骰（确定性、服务端/客户端一致），完全稳定锚不受影响。
- 引导模型：阶段 3 效果减半 = 完备度 q 减半（可配置），完全稳定锚不受影响。
---

## 7. 渲染系统

### 7.1 客户端方块目标缓存
- 跳过 hasBlockEntity() 的方块。
- `ClientRenderCache` 单例持有：
  - `Map<Long, Entry> targetCache`：当前周期的目标方块状态（`Entry` 带周期号，防止跨周期读到旧值）。
  - `Set<Long> evaluated`：本周期已经判定过的位置（**负缓存**）——面剔除路径每个方块要问 6 次邻居，
    没有它每次未命中都得重跑整套判定。详见 §7.3。
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
- **两处钩子，缺一不可**（2026-09-16 修正）：
  1. `SectionCompilerMixin` → 重定向 `SectionCompiler.compile` 里那一次 `RenderChunkRegion.getBlockState`：
     决定**这个位置画成什么**（方块模型、流体、以及 `visgraph.setOpaque` 的透明性标记）。
  2. `BlockShouldRenderFaceMixin` → 重定向 `Block.shouldRenderFace` 内部的 `level.getBlockState(邻居)`：
     决定**这个面画不画**。
- **为什么必须是两处**：网格用幽灵状态、剔除用真实状态，两边对不上。真实石头（幽灵玻璃）旁边的方块
  看到的邻居是"不透明"，于是它朝玻璃的那一面被剔掉，而原版语义下那一面要画——
  结果就是透过玻璃看进方块内部（背面也被剔除），像世界破了个洞。
  同理，幽灵是**不透明**而真实是玻璃时也会错。一句话：**凡是"比较两个方块"的判定，两边必须同源**。
- 替换后 `Block.OCCLUSION_CACHE`（按 `(本方状态, 邻居状态, 面)` 三元组缓存）的键也自动变成幽灵对，
  不会出现"拿真实状态的缓存结果去判断幽灵"的污染。
- **代价控制**：面剔除对每个可见方块要问 6 次邻居，所以 `ClientRenderCache#ghostState` 的顺序是
  "正缓存 → 负缓存（本周期已判定过的位置）→ 才做完整判定"。
  负缓存是必需的：没有它，每次未命中都要重跑整套判定（阶段 1 约 200 ns），
  6 × 4096 个方块就是毫秒级开销。
- **只落在区块编译路径上**：钩子先判 `level instanceof RenderChunkRegion`，
  破坏粒子、手持方块、方块预览等走 `ClientLevel` 的地方一行都不改。
- **没有覆盖的**：环境光遮蔽那 12 次邻居读取仍用真实状态，所以幽灵附近的光影过渡会有一点偏差——
  属于观感而非破洞，且每方块要多 12 次查找，收益不划算，有意不改。
- 注意排除液体、空气等由原版渲染管线的特殊处理，确保替换仅针对固体层。
- 对于带方块实体的方块，源门控直接排除（`MutationIndex#isSource`），因此不会出现"方块实体渲染器残留"。
- **受保护方块（2026-08-10）**：`ClientRenderCache.resolve` 先查 `isProtected(pos)`，保护范围内不替换模型、不入缓存。
- 旧文档里"只劫持 `getBlockState` 一处即可覆盖编译期全部读取"的说法是**错的**，已删除；
  它正是玻璃破洞那个 bug 的思想来源。

### 7.4 后处理着色器
- 程序路径（实现时要留意的坑）：PostChain 文件是 `assets/focal_decay/shaders/post/observer_veil.json`，
  但 pass 里引用的 program 名会按默认命名空间解析，所以 GLSL 实际放在
  `assets/minecraft/shaders/program/observer_veil.{json,vsh,fsh}`。
- 应用点：`GameRendererMixin` 注入 `GameRenderer.renderLevel(DeltaTracker)` <b>尾部</b>
  （手部渲染之后），详见 §7.6 的手部渲染修复。
- 着色器效果：极轻微的时间性扭曲 + 色散 + 极淡着色，周期波动，强度由 `post_intensity`（COMMON）配置。
- 三个效果在 shader 里都由同一个 `Fade` uniform 缩放，所以 `Fade = 0` 等于"整个效果消失"
  （而不是只去掉色调、留下抖动）。
- **重聚焦之后移除（2026-09-16）**：遮罩画的就是"失焦带来的不安定"，观测者核心上线之后这份不安定已经结束。
  - `postProcessAfterRefocus`（CLIENT，默认 **false** = 移除）是开关；置 true 恢复旧的"一直抖"行为。
  - `postProcessRefocusFadeTicks`（CLIENT，默认 50 = 2.5 秒，0 = 立刻切掉）是淡出时长。
    做成淡出而不是硬切，是因为重聚焦那一瞬本来就有音效/粒子/广播，直接抽掉反而突兀。
  - 淡到 0 时**卸载整个 PostChain**，连每帧一次的全屏 pass 都不再付；
    世界若回到失焦状态（或开关被打开），`veilRefocusFade` 复位为 1、下一帧重新加载。
- 时间源：不能用内置 `Time`（PostChain 每秒硬回绕一次，周期长于 1 秒的动画必然跳变），
  自建只增不回绕的 `TotalTime`（秒）。

---

## 8. 网络通信

### 8.1 数据包设计
- **SyncRegionDataPacket**（S→C）：
  - 维度ID、**有效原型机**列表（位置 + 半径 + 模型效果摘要：语义锁定目标 / 引导概念 / 生物稳定 / 完全稳定）、方块诞生周期表（位置 → 周期）（2026-08-10/2026-08-13 已实现锚与诞生周期；2026-08-19 起改为原型机/模型数据，覆盖区域为引导模型的实现载体）。
  - 在玩家登录、切换维度、原型机放置/破坏/换模时发送（**整表**）。
- **SyncBirthPeriodPacket**（S→C，2026-09-15 新增）：
  - 单条诞生周期变化：维度、位置、周期（`period < 0` 表示删除）。
  - 用于方块放置/破坏与右键交互转换。旧实现这三处都重发上面那张**随建造无上限增长的整表**——
    盖一栋房子等于上千个整表包，既是带宽浪费也是每次放置方块时的一次主线程序列化开销。
- **ObserverCoreActivatePacket**（S→C）：
  - 当核心被激活时，发送给所有玩家，触发全局粒子/音效与胜利提示——**已实现（2026-08-21）**。
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
  - 语义锁定：`semantic_lock_stage3_strength`（阶段3保护强度，默认 0.5 = 效果减半；1.0 = 不衰减）
  - 候选观测者：`candidate_required_points`（训练进度要求，默认 100）、`candidate_fragment_points`（单枚碎片注入点数，默认 10）
  - 训练终端：训练所需能量/时长（**FE 默认消耗 0，单模组不启用**）、空白模型记录数量上限、经验瓶回退开关、训练交互冷却
  - 生物稳定模型：生命值→能量换算、`bioEnergy` 消耗速率、阶段3双倍消耗开关、范围内实体稳定开关
  - 引导模型：`guided_min_trained`（最少有效训练数，默认 2）、`guided_q_multiplier` / `guided_q_cap`（q 倍率与上限）、`guided_stage3_halve`（阶段3 q 减半开关，默认开）
  - 王座仪式：仪式时长（默认 3~5 分钟，33 分钟为可选上限）、波次强度/间隔、所需物品

### 9.2 数据生成
- **突变标签全部在 `ModBlockTagsProvider` 里生成**（2026-09-15 重构，见 §4.1）：
  - 形态类 `focal_decay:shape_class/*` 八类，用原版标签（`#minecraft:stairs` 等）而不是逐方块列清单，
    模组方块只要进这些标签就自动生效；玻璃板没有原版标签，列清单。
  - 语义池 `focal_decay:mutation_pool/*`：`stone / dirt / wood / wool / concrete / terracotta / ore /
    quartz / ice / ocean / nether / end / misc`，加八个形态族的池（`stairs / slabs / fences /
    fence_gates / walls / trapdoors / carpets / panes`），加 16 个颜色池（`color/white` …）。
  - 大池 `focal_decay:mutation_pool/wild`（下界/末地各有变体），外加
    `mutation_immune` / `mutation_source_extra`。
  - **破坏性改名**：`global_mutation_pool` → `mutation_pool/wild`，`nether_mutation_pool` →
    `mutation_pool/wild_nether`，`end_mutation_pool` → `mutation_pool/wild_end`，
    `conversion_blacklist` → `mutation_immune`（并补上技术性方块）。
- 引导模型的概念标签 `focal_decay:concept/*` 同数据生成（与突变池是两套东西：概念由训练数据动态指认）。
- 实体类型标签：`focal_decay:entity_mutation_pool_passive` 包含如 `minecraft:sheep`, `minecraft:cow` 等。
- 战利品表：语义碎片添加到相应原版战利品表，使用 `GlobalLootModifier` 或直接修改 `LootTableLoadEvent`。
- 配方：稳定锚、突变控制器使用标准 `ShapedRecipeBuilder`。
- 标签的语言键（`tag.block.focal_decay.*`）写在两个 lang 文件里，供引导模型的概念显示名与 JEI 复用。

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
- `client.SectionCompilerMixin`：替换编译时的方块状态（这个位置画成什么）。
- `client.BlockShouldRenderFaceMixin`：面剔除的邻居读取也走幽灵世界（这个面画不画），见 §7.3。
- `MinecraftPickBlockMixin`：中键选取返回"失焦目标"方块（替换 `Minecraft#pickBlock` 中的 `ClientLevel#getBlockState`）。
- 不修改底层网络，仅注入渲染和方块处理。

### 10.4 测试命令
- `/focaldecay days`：查询当前末日天数与阶段。
- `/focaldecay days <n>`：手动设定天数（权限 2），`FocalDecayWorldData.setDays` 落盘并通过 `SyncWorldDataPacket` 广播，客户端立即按新阶段重算（周期/概率/影响范围）。
- `/focaldecay mutation audit | selftest | at`：突变查表自检 / 运行期自测 / 脚下位置诊断，见 §4.1.4。
- `/focaldecay refocus [true|false]`（2026-09-16，权限 2）：强制翻转"观测者在线"状态。
  走的是和核心激活完全相同的 `FocalDecayWorldData.setObserverOnline`，所以看到的就是真实行为；
  存在的理由是重聚焦之后有一堆只在那一刻生效的表现（客户端遮罩淡出、失焦预览清空、实体突变停止）
  需要反复看，而正常流程要练候选模型、装核心、等 100 tick。
- `/focaldecay throne | inspect | trace | unlock`：王座坐标 / 手持模型数据 / 交互诊断日志 / 解锁手册。

---

## 11. 状态存储与持久化

- **MutationPoolManager**：`SavedData`，通过 `DimensionDataStorage` 读写，保存：
  - 有效原型机效果列表（位置 + 半径 + 模型数据，含引导模型固化的概念标签与 q；瞬态，由方块实体放置/加载/换模时重建）
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
- 主线道具：`observer_model_candidate`（候选观测者，2026-08-21 新增）；`rebuilt_observer_protocol` 已移除
- 方块实体类型：`anchor_prototype`, `training_terminal`
- 能力：`break_data`
- 标签：
  - 方块 · 突变：`mutation_pool/wild`（+ `wild_nether` / `wild_end`）、`mutation_pool/*`（语义池与颜色池）、
    `shape_class/*`（形态类）、`mutation_immune`、`mutation_source_extra`、`anchor_prototype_immune`
  - 方块 · 概念：`focal_decay:concept/*`（wood/ore/stone/glass/terracotta/wool 等，数据生成策展，供引导模型指认概念）
  - 实体类型：`entity_mutation_pool_passive` / `_neutral` / `_hostile`
- 着色器：`observer_veil`
- 包网络：`sync_region_data`, `sync_birth_period`, `sync_world_data`, `core_activate`, `throne_ritual`
- 命令：`/focaldecay days|throne|inspect|trace|unlock|refocus|mutation audit|mutation selftest|mutation at`
