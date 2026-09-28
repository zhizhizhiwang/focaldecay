# Focal Decay: Observer Fallen — 设计大纲

> **本文件是设计的唯一真源**，记录**当前有效**的机制、为什么这么设计、以及边界在哪。
>
> - 规则与工作流 → [`../AGENTS.md`](../AGENTS.md)
> - 交付计划与当前状态 → [`ROADMAP.md`](ROADMAP.md)
> - 待办条目的唯一真源 → [`BACKLOG.md`](BACKLOG.md)
> - 技术细节与踩坑 → [`PITFALLS.md`](PITFALLS.md)
> - 已发生的事（按现象查） → [`progress/INDEX.md`](progress/INDEX.md)
>
> **本文件不收**：实现顺序、待办、缺陷清单、修复过程、历史决策的流水账。
> 设计发生变更时**就地改写**对应章节（保留一句"为什么改"，不要保留旧设计的全文）。
> 尚未定案的取舍集中在 §14。
>
> 版本：1.0.0 · 平台：NeoForge 1.21.1 · 设计参考：SCP-CN-2999《Observator Ex Machina》
> 最后更新：2026-09-25

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
     - **训练增益按复制代数递减（2026-09-17 修正）**：顶点恒为 100%（`candidate_required_points`），副本只是每点涨得少——`candidate_copy_gain`（默认 `[1.0, 0.7, 0.5]`）按 `copies` 取值，所以原件/一代副本/二代副本分别要 **100 / 143 / 200** 点练满，一枚碎片（默认 10 点）分别给 **10% / 7% / 5%**。
     - 派生配方（OBSR-3）因此必须是**特殊配方**而不是工作台 shaped 配方：结果要把所用 OBSR-EX 的 `copies` 抄过去，而 shaped 配方只能产出注册时的默认物品（2026-09-17 之前就是这样，代数整个丢掉，"副本更难练"从未生效）。
     - **特殊配方必须显式登记 JEI 工作台扩展**（2026-09-22 修）：JEI 自带的工作台扩展只接手 `!recipe.isSpecial()` 的配方，特殊配方既不展示也不进 R/U 索引——表现就是"对 OBSR-3 按 R 什么都查不到"。做法见 `compat/jei/DeriveCandidateExtension` + `FocalDecayJeiPlugin#registerVanillaCategoryExtensions`：用 `ICraftingGridHelper` 铺网格与产物，配方同时实现 `getIngredients()` / `getResultItem()`，并让 `matches()` 与展示网格共用同一个形状定义（形状只写在 `DeriveCandidateRecipe#materialAt` 一处）。详见 PROGRESS §13.17 与约定 16。⚠️ JEI 索引的是**服务端同步过来的配方表**，联机时客户端旧 jar 救不了新服务端的配方。
     - **配方只给通用提示，不铺开摆法**：特殊配方不进原版配方书（摆法由 JEI 的工作台配方页展示），
       所以来路写在这三处——手册《OBSR-3 候选观测者》的"构造"页（一句"在工作台以一枚已激活的
       OBSR-EX 为核心合成" + 指向 JEI）、JEI 的工作台配方页（摆法由 JEI 自己画，2026-09-22 起按 R/U 可查）
       与 JEI 信息页 `jei.focal_decay.info.candidate`（补充"用副本合成的需要更多训练"）。
       物品提示同样简短：普通模型显示"第 %s 代副本"，OBSR-3 只显示"第 %s 代"，
       训练量的差别写在手册《型号规格》页。

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

#### 3.5.1 王座仪式（2026-09-17 修订触发与维持规则）
1. **条件**：玩家在末地，**手持未激活的 OBSR-EX**，且王座附近（`throne_ritual_radius` 内）
   **已放置**一座观测者基座（获取见 3.5.2）。基座只带在背包里不算数。
2. **触发**：**右键那座基座**——手里的 EX 被放进基座插槽，仪式随即开始。
   （2026-09-17 之前是"右键王座区域"触发、且基座可以在背包里，与设计不符。）
   触发时周围末地水晶激活、生成大量粒子；进入仪式状态（进度持久化，断开/离开可暂停或失败，配置可定）。
3. **维持（2026-08-19 定稿，2026-09-17 补上现场校验）**：玩家需在仪式时长内
   （默认 3~5 分钟，可配置；33 分钟仅作致敬原文的可选上限）留在王座范围内；
   期间按波次生成强敌/环境干扰（生成表与间隔可配置）。
   **那座基座与插槽里的 EX 必须一直在原位**：取走模型（丢出/收进容器、换成别的模型）或拆掉基座 →
   立即中断且**不给任何产出**。判定按记录下来的基座坐标做（`ThroneRitualData#prototypePos`），
   不是"半径里随便找一座"，所以多座基座时也确定。
4. **完成**：基座插槽里那枚未激活模型被**就地**替换为已激活版本（完全稳定锚，半径 32），
   广播 `ThroneRitualPacket`，播放音效与成就。
5. **结果**：完全稳定锚可拾取并重新放置在任何地方。失败/中断时模型留在基座里（基座被拆则随掉落物掉出），
   可以捡回重来。

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
  **缓存必须每线程一份**（2026-09-16 修崩溃）：区块编译并发跑在 ForkJoinPool 上，多个 worker 同时调用本类；
  原先全局共享一张 `Long2ObjectOpenHashMap`，并发 put 把内部数组写坏，线上崩在 `rehash` 的越界上
  （详见 PROGRESS §13.11）。用 `ThreadLocal` 而不是 `ConcurrentHashMap`：键是 `long`，
  后者每次 get/put 都要装箱，而这里是逐方块的编译热路径；映射是纯函数，各存一份无一致性代价。

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
- **不变式：保护是单向的，任何写世界的操作都必须先问保护**（2026-09-25 补）。
  锚固化会改写范围内的方块，所以它必须先查既有保护并跳过硬保护命中的坐标——
  否则"新基座放进已有稳定场"会把那个场写坏，而客户端仍按保护不显示幽灵，两边对不上。
  这条不随实现方式变化：即便将来改成 §14.1 的规则层（不再写世界），
  "保护优先于一切改写"这一条依然成立。
  回归网是 `/focaldecay mutation selftest` 的 `[anchor]` 一行。

---

## 5. 确定性随机与目标计算

### 5.0 两根时钟：存储时钟 vs 显示时钟（2026-09-16，2026-09-17 修订）

失焦是 `(pos, worldSeed, period)` 的**纯函数**，没有逐方块的存档——所以"世界此刻长什么样"
完全由 `period` 一根指针决定。`/focaldecay period` 拨的就是这根指针，因此**回滚不需要保存任何历史**。

| 时钟 | 函数 | 用途 | 受调试倍率影响 |
|---|---|---|---|
| 显示时钟 | `MutationHelper.displayPeriod(gameTick, speed, offset)` | 失焦解析（服务端 + 客户端预览）、锚固化、**出生周期**、**剪枝地平线** | ✓ |
| 存储时钟 | `MutationHelper.blockPeriod(gameTick)` | 只有自测拿它当参照物（证明默认档位下显示时钟与真实时间轴逐位相同） | ✗ |

```
scaled = floor(gameTick * speed)
period = floorDiv(scaled, base_interval) + offset
```
- **先乘后除**：`speed = 1` 时 `floor(gameTick * 1.0) == gameTick`，于是 `floorDiv(gameTick, interval)`
  与存储时钟逐位相同 —— 默认档位下这个函数**完全不改变现有行为**（`/focaldecay period selftest` 会实测这一点）。
  写成 `floor(gameTick * speed / interval)` 则会在整除边界上有浮点少 1 的风险。
- 用 `Math.floorDiv`：负倍率倒带时除法要向负无穷取整，否则 `-250/100` 会被截断成 `-2` 而不是 `-3`，倒带出现台阶。
- **出生周期也记在显示时钟上**（2026-09-17 修正，见 PROGRESS §13.14）：闸门
  （`resolve` 里的 `periodIndex < birthPeriod + 1`）比的就是显示刻，两个操作数必须同域。
  以前出生周期记存储刻，倍率 7 时显示刻是存储刻的 7 倍，"刚出生"的方块一上来就过期——
  放下的方块立刻失焦、右键长按能把方块来回转换；倍率 &lt; 1 时反向出错（方块长期不失焦）。
  记录时取 `max(服务端显示刻, 行动客户端回报的显示刻)`：两端时钟会漂，取更晚的读数让两边的闸门只会晚开。
- 剪枝地平线同样用显示时钟（与诞生记录同域）：用存储刻会在加速时永不删除（表无界增长），
  在减速时把刚放下的记录删掉（等于"所有方块立刻失焦"）。
- 锚固化用显示时钟：它固化的是"当前看得见的样子"，必须和客户端预览同一根指针。
- 调试档位**不落盘**（见 §10.4），重启即恢复 `speed=1, offset=0`。
- 回滚语义在新方案下依然成立：回滚后 `periodIndex` 小于出生时的显示刻，闸门保持关闭，
  那些"在未来才被放下"的方块显示为原样。

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
- **两端的输入必须逐位一致（2026-09-17 重写）**：函数只有一份，但"两端算得一样"的前提是
  **喂进去的输入一样**。以前这个前提是靠约定维持的（注释里写"Server 配置会同步到客户端"，
  实际没有任何代码在做），于是局域网里房主与客人看到的世界不同、挖下去掉落也对不上。现在：
  - 所有静态输入收进 `MutationSettings` 快照（世界种子 + 周期长度 + 阶段划分 + 三阶段概率 +
    `wild_chance` + 语义锁定强度 + 引导 q 折半 + 候选体训练点数），由服务端下发（§8.1）；
  - `resolve` 有两个重载：服务端入口吃本端配置与 `level.getSeed()`，
    **客户端入口只吃同步下来的快照**；两者共用一个私有实现，公式仍然只有一份；
  - 客户端在收到快照之前**不渲染任何幽灵**（宁可看不到，也不能看错）；
  - 度量它的尺子是 `/focaldecay mutation selftest` 的 `[sync]` 段：手写编解码逐字段往返，
    且"服务端入口 == 客户端入口"在 5 种源方块 × 3 阶段 × 64 周期上逐位比对。
  - 仍然**不在**快照里的量（各有自己的通道）：末日天数/观测者/调试时钟（`SyncWorldDataPacket`）、
    原型机效果与诞生周期（区域数据）。
- 每一步消耗的随机步数都是 (源方块, 形态类, 配置) 的纯函数，所以两端永远同步推进。
- **周期由"玩家看到的那一刻"决定**（2026-09-17）：交互路径（左键锁定、右键转换）用
  客户端回报的显示刻（`SyncClientViewPacket`，§8.1），其余场合（锚固化等）用服务端自己的。
  因为客户端时钟是本地自走的、每 20 tick 才校一次，掉帧时两者会差出一个周期。
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
  **这一条同时决定了"失焦不会吞掉玩家的箱子"**：带方块实体的方块不会成为目标，
  所以一个普通方块变成容器（然后丢掉里面的东西）在结构上不可能发生；
  真正的容器永远不会被失焦改写。转换只换"这一格是什么方块"，不碰任何实体、
  不碰方块实体内容。破坏路径重构成"让原版管线跑完"（§5.2）也维持了这一点，
  而且顺带让"目标恰好是带方块实体的方块"这种情况不需要特殊处理——
  那种方块根本进不了目标池。
- **形态类不会跨类突变，而且同类内形状逐位相同**（2026-09-26 明确记为设计保证）。
  两条机制合起来保证这一点：
  1. `shapeClass` 由**源方块**算出、再用来切**目标池**（`wild.get(shapeClass, …)` /
     `tagPool.get(shapeClass, …)`）——目标必然与源同类；
  2. `MutationStateMapper` 把两方块**共有**的属性从源复制到目标
     （只有 `waterlogged` 与 `in_wall` 排除），其中包括决定形状的
     `facing` / `half` / `shape`，以及 `type`（半砖）/ `axis`（原木朝向）。

  ⇒ **源与幽灵的碰撞形状在几何上逐位相同**（完整方块恒为整格；台阶的形状由
  `facing`/`half`/`shape` 决定，而这三个都被复制）。
  <p>
  **这条推论有三个直接后果，改这一块之前必须知道**：
  - **选择框不需要跟随幽灵**——它用的是真实方块的形状，而两者本来就相同；
    曾经作为 `P1-7` 一部分实现的 `outline_follows_ghost` 开关因此是个**恒等开关**。
  - **碰撞与幽灵的不一致只剩"材质/视觉"层面**：玩家撞到的形状和看到的形状是一样的，
    不一致的是"这个形状看起来是什么材料、能不能穿过去"（例如看着像玻璃却是实心石头）。
    这一条写进手册，不做（混合碰撞不可靠）。
  - **想让突变跨形态（例如完整方块变半砖）时，要动的是上面两条机制本身**，
    而不是加一个例外——因为"形状一致"这个保证是**用法则**换来的，
    破例会让选择框、碰撞、面剔除同时失去依据。

### 5.2 交互锁定
- **统一方块识别函数（2026-08-10 新增，2026-09-15 收拢为 `MutationHelper.resolve`）**：`MutationHelper.resolve(source, pos, worldSeed, periodIndex, index, chance, bias, protection, birthPeriod)` 为生存破坏、右键交互、创造中键选取、客户端预览、锚固化共用的唯一识别入口；受保护位置、非源方块、没抽中的情况一律返回原方块。
- **挖掘开始**：`PlayerInteractEvent.LeftClickBlock`（服务端）记录：
  - 目标方块状态 `targetState`（此时计算）
  - 解析用的显示刻 `periodIndex`
  - 锁定位置 `pos` 与**锁定维度** `dimension`
  - 存储在玩家 attachment `ModAttachments.BREAK_DATA`（`BreakData`）中。
- **挖掘速度与工具要求（2026-08-21）**：由**当前可见的失焦目标**决定——客户端 `MultiPlayerGameModeMixin` 把挖掘进度计算改为读可见目标（走 `ClientRenderCache.miningState` 缓存，O(1)）；服务端掉落/经验传入玩家主手工具，目标方块的 `requiresCorrectToolForDrops` 生效（拿对工具才有掉落）。
- **方块破坏（2026-09-25 重构定稿，取向：只换那一格，让原版跑完）**：
  在 `BlockEvent.BreakEvent`（`HIGHEST`）里**只替换"被破坏的是哪个方块"这一个变量**：
  - 校验锁定（位置 + 维度，见下），不通过则清掉并按原版处理；
  - `setBlock(pos, targetState, 2)`——把**可见目标**真的写进世界；
  - 清掉 `BreakData`（重派发会再次触发 `BreakEvent`，不清就会重复消费同一份锁定）；
  - 置 `BREAK_REDISPATCHING` 旁路标志后重派发 `player.gameMode.destroyBlock(pos)`，
    于是掉落（含自定义 `playerDestroy` 覆写、Fortune / Silk Touch、容器溢出内容物）、经验、耐久、
    统计、成就、`BlockDropsEvent`、`spawnAfterBreak`、`doTileDrops`、水回收、
    `onDestroyedByPlayer` 的否决**全部由原版负责**——模组不再手工复刻任何一步；
  - 取消触发本次事件的真实方块那一次（它已经被原版正常破坏掉了）。
  - **本模组自己产生的语义掉落**（铜块突变的硫铜结晶碎片）留在重派发之后单独结算：
    它不是原版行为，没有对应的管线可挂。
  - 为什么不能"放进去然后不取消事件"：原版在触发 `BreakEvent` 之前就把 `blockstate1`
    读进局部变量了，`playerWillDestroy` / `playerDestroy` 用的都是那一份；
    只改世界改不了它手里那个引用，结果会是"方块变了、掉落却按旧方块算"。详见 §12.4。
- **代价（明确接受）**：破坏会**真的把目标方块写进世界**。这与 §5.3 右键路径同一取向，
  在设定上也自洽——这些方块本来就已经在世界上了，转换是把玩家看到的样子落实，而不是凭空生成。
  副作用是"挖掘中途放弃会留下一个变过的方块"，已写进手册。
- **锁定的有效性与失效**：
  - **位置 + 维度**必须一致，否则视为陈旧锁定并清掉。维度这一条是必须的：
    `BlockPos` 只是三个整数，同一组坐标在下界与主世界都会相等，
    只校验位置挡不住"在主世界开始挖、走进传送门、落地后同一坐标恰好有方块"这种情况。
  - **显示刻翻页<b>不</b>使锁定失效**。锁定的语义是"玩家看到的是哪个方块"，
    它压过时钟漂移：玩家看着 A 挖下去就该拿到 A 的掉落，
    不该因为"世界该漂移了"改按真实方块 B 结算。周期只用于诊断（跨周期时打一行 trace）。
  - 锁定**不落盘、不跨死亡**：`ModAttachments` 只保留 `builder`，既无序列化器也无 `copyOnDeath`。
    它描述的是"这一次挖掘开始时玩家看到的东西"，跨会话与跨死亡都没有意义。
- **创造模式支持（2026-08-10 新增，破坏行为最终修正）**：创造模式**破坏保持原版行为**——无掉落、不收入背包、不执行转换（仅生存模式破坏触发转换掉落）；中键选取（pick block）通过 Mixin `Minecraft#pickBlock` 返回可见的"失焦目标"方块。

### 5.3 右击交互
- **右键会触发真实转换（2026-09-25 更正）**：本节原先写"不触发真实转换"，与实现相反。
  现状是：方块属于转换源、且本周期解析出的目标与真实方块不同时，
  先把那一格真的换成可见目标，再让**目标方块**接管这次右键（`REDISPATCHING` 旁路标志防递归），
  最后取消原事件并显式给出 `InteractionResult.FAIL`。
  不做这件事的后果是工作台、切石机、织布机这类"完整立方体 + 有右键行为 + 无方块实体"的方块
  在视觉上已经变成别的方块、右键却仍打开原有界面。
- 非转换源、或本周期没抽中时，一切走原版，服务端不干预。
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
  - `Map<Long, Entry> targetCache`：当前周期里"这个位置显示成什么"。
    `Entry` 带**周期号**与**算它时那个位置的真实方块**（`real`），两者任一不匹配即失效——
    **只判周期是不够的**：玩家把方块挖掉时周期并没变，缓存里那条"石头显示成钻石矿"还在，
    而邻块的面剔除会拿它当"那里有块不透明方块"，于是挖出来的洞要等下一轮表面扫描才出现
    （2026-09-17 修，详见 PROGRESS §13.13）。
  - `Map<Long, BlockState> evaluated`：本周期已经判定过的位置 -> 判定时的真实方块（**负缓存**）——
    面剔除路径每个方块要问 6 次邻居，没有它每次未命中都得重跑整套判定。详见 §7.3。
    存真实方块的理由同 `Entry.real`："这里没有幽灵"这条结论同样是真实方块的函数。
  - 周期日志里带 `N dropped mid-period because the real block changed`：
    本周期里因为真实方块变化而作废的条目数（挖掉/放下方块都会让它涨）。长期为 0 说明有效期判据失效。
- 更新时机：显示刻每变化一次（`gameTick / base_interval` 跨过整数边界）就整体清空并重排重编译；
  期间由表面扫描与区块编译两条路径增量维护。

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

> **总原则（2026-09-17 明确）：失焦预览是客户端自己算的，所以"两端算得一样"必须靠
> 输入完全一致来保证，而不是靠约定。** 凡是进入 `MutationHelper.resolve` 的静态输入
> （世界种子、周期长度、阶段划分、概率、保护强度）都属于 `SyncMutationSettingsPacket`；
> 随进程变化的量属于 `SyncWorldDataPacket`；随玩家行为变化的量属于区域数据那两条；
> 而"客户端的指针此刻指向哪一刻"属于 `SyncClientViewPacket`（C→S）。
> 客户端在收到输入快照之前**不渲染任何幽灵**——显示不出来是可诊断的，"显示错了"才是 bug。

- **SyncMutationSettingsPacket**（S→C，2026-09-17 新增）：
  - 整份 `MutationSettings` 快照：世界种子、`base_interval`、阶段开关与天数、三阶段概率、
    `wild_chance`、阶段3语义锁定强度、引导 q 折半开关、候选体训练点数。
  - **为什么必须有**：客户端天然拿不到这两样——
    ①**世界种子**：1.21 的登录包里只有给生物群系缩放用的**哈希**种子，真实种子只发给管理员；
    ②**SERVER 配置**：客户端读的是它自己那份 toml。以前客户端在多人模式下把种子退回 `0`，
    于是房主（集成服务器，拿得到真种子）和别人看到的是两个不同的世界，挖下去掉落自然对不上。
  - 只在登录/换维度时发送（静态快照，不需要重复发）。
- **SyncWorldDataPacket**（S→C，2026-09-16 扩展）：
  - 末日天数、观测者在线状态、**调试时钟（失焦倍率 / 偏移）**。
  - 放同一个包，因为它们满足同一个条件：**服务端与客户端必须逐位一致**——
    任何一项两端不同，失焦预览就会和真实转换对不上（客户端显示 A、服务端给 B）。
  - 在玩家登录、切换维度、天数变化、核心激活、`/focaldecay period` 变化时发送。
- **SyncRegionDataPacket**（S→C）：
  - 维度ID、**有效原型机**列表（位置 + 半径 + 模型效果摘要：语义锁定目标 / 引导概念 / 生物稳定是否生效 / 完全稳定）、方块诞生周期表（位置 → 周期）。
  - **只在登录/换维度时发送（整表快照）**。注意里面的原型机名单天然**不完整**：
    有效原型机列表不落盘，由方块实体的 `onLoad` 重建，所以只有"此刻已加载区块"里的那些在表里。
- **SyncPrototypePacket**（S→C，2026-09-17 新增）：
  - 单条原型机效果的增删改：维度、位置、存在与否、效果摘要（`present=false` 表示删除）。
  - **为什么必须有**：上面那条"名单不完整"是真的会咬人的——玩家走到远处某个原型机旁边时，
    区块加载让服务端开始保护那片区域，客户端却一直以为没人保护、继续画幽灵；
    玩家挖下去，服务端按硬保护返回原方块，掉落就对不上了。
    修法不是"每次区块加载重发整表"（那会把随建造无上限增长的诞生周期表也带上），而是单条增量。
  - 服务端只在**客户端能观察到的字段**真的变了时才发：生物稳定发的是"是否生效"而不是能量值
    （能量每刻都在变），所以 `PrototypeData.equals` 恰好等于"客户端看到的东西变了没有"。
- **SyncBirthPeriodPacket**（S→C，2026-09-15 新增）：
  - 单条诞生周期变化：维度、位置、周期（`period < 0` 表示删除）。
  - 用于方块放置/破坏与右键交互转换。旧实现这三处都重发上面那张**随建造无上限增长的整表**——
    盖一栋房子等于上千个整表包，既是带宽浪费也是每次放置方块时的一次主线程序列化开销。
- **SyncClientViewPacket**（C→S，2026-09-17 新增）：
  - 交互位置 + **客户端解析时用的显示刻**。左键开始挖掘、右键交互前发出
    （`MultiPlayerGameMode` 的 HEAD 注入），让服务端按**玩家看到的那一刻**解析目标。
  - **为什么需要**：`level.getGameTime()` 在客户端是**本地自走**的计数器，服务端每 20 tick
    才校一次（`MinecraftServer#tickChildren`，`tickCount % 20 == 0`）。两边都满速时差半个 RTT，
    但服务端掉帧时客户端会跑到前面、最多领先 20 tick——那段窗口里客户端显示周期 N、
    服务端按 N+1 解析，挖下去就是"看到的和掉出来的对不上"。
  - **服务端不盲信**：位置要对得上、回报要够新（40 tick）、偏差要在
    `ceil(|speed| × 100 / interval) + 1` 个周期以内（`InteractionHandler#periodWithinSkew`），
    否则退回服务端自己的显示刻。取舍规则是纯函数，`[sync]` 段逐条自测。
- **ObserverCoreActivatePacket**（S→C）：
  - 当核心被激活时，发送给所有玩家，触发全局粒子/音效与胜利提示——**已实现（2026-08-21）**。
- **ThroneRitualPacket**（S→C，2026-08-19 规划）：王座仪式进度/波次/完成事件同步。
- **BreakDataSyncPacket**（可无需，挖掘数据仅存服务器，客户端无需知道目标）。

### 8.2 网络注册
- 使用 NeoForge 21.1 **Payload API**（`RegisterPayloadHandlersEvent` + `PayloadRegistrar`；`SimpleChannel` 已在 21.1 移除），协议版本 “1”。
- 内容包（`SyncMutationSettingsPacket` / `SyncPrototypePacket`）的编解码手写，
  因为分量数超过 `StreamCodec.composite` 的重载上限（6）。
  **手写编解码必须配往返自测**（`/focaldecay mutation selftest` 的 `[sync]` 段）：
  13 个字段里错一个（例如种子被读成周期长度）不会崩，只会让两端静默地算出不同的世界。

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
  - 候选观测者：`candidate_required_points`（**顶点**/100% 线，默认 100）、`candidate_fragment_points`（单枚碎片注入**点数**，默认 10，实际百分比按增益折算）、`candidate_copy_gain`（每点增益，按复制代数取值，默认 `[1.0, 0.7, 0.5]` —— 2026-09-17 取代原 `total_stability_copy_train_penalty`：副本的代价体现在"每点涨得慢"，而不是"顶点更高"）
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
- `/focaldecay period [speed <x> | offset <n> | set <n> | reset | selftest]`（2026-09-16，权限 2）：
  拨动"失焦刻"这根指针（见 §5.0）。
  - `speed <x>`：失焦进程的流速倍率。**1 = 正常，>1 = 加速，0.5 = 减速，0 = 冻结，负 = 倒带**（上限 ±64）。
    方块失焦刻与实体/天气节拍一起乘倍率（用户选择）；**末日天数不受影响**（跳阶段用 `days`）。
  - `offset <n>`：在当前时间轴上前/后平移 n 刻，**负数就是回滚**。
  - `set <n>`：冻结在第 n 刻（= `speed 0` + `offset n` 的糖；不再引入第三种状态）。
    冻结之后用 `offset` 就能逐刻前后步进，看崩坏怎么一步一步长出来。
  - `reset`：回到 `speed 1 / offset 0`。
  - `selftest`：把每一档都验一遍（默认档位逐位等价、各档位映射正确、冻结不改变画面、回滚可重放），
    **结束时恢复调用前的档位**。
  - **边界**：只回滚"失焦外观"；玩家真实放置/破坏的方块、锚固化写进世界的方块、右键转换过的方块
    都是真实的世界改动，没有历史可以回退。实体/天气也不倒带（它们的转换同样是真实改动），
    负倍率下它们的节拍夹在 0（= 停住）。
  - **深回滚的已知偏差**：出生周期表有 128 刻的剪枝地平线，偏移超过 -128 之后那些被剪掉的位置
    会退化成"世界原生方块"，显示为已崩坏而不是原样。
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
  同时承载调试时钟（失焦倍率/偏移），**刻意不落盘**（`setClock` 不调 `setDirty`）：测试档位忘了 reset
  会让世界看起来"卡住了"，那是最难查的一类假 bug。
- **玩家挖掘数据**：使用 NeoForge Capability `BreakData`，自动同步。

---

## 12. 设计原则

这四条是判断"某个具体做法对不对"的尺子。加新机制时先拿它们量一遍。

### 12.1 所见即所得
**玩家操作的是他看到的东西。** 挖掘掉落、右键交互、中键选取、挖掘速度与工具要求，
全部按"当前可见的失焦目标"结算，而不是按真实方块。

这条决定了几个看起来可疑、但**是刻意**的做法：
- 右键会**真的**把方块转换成可见目标，然后让目标方块接管这次交互（而不是模拟交互）；
- 创造模式同样转换（客户端的幽灵预览对所有游戏模式生效，只给生存转换会造成前后不一致）；
- 服务端在交互时采用**客户端回报的显示刻**（玩家是按他那一刻看到的东西下的手）。

### 12.2 失焦是纯函数，且不可逆
`(pos, worldSeed, period)` → 显示状态，没有逐方块的失焦存档。
- **推论 1**：回滚不需要保存历史，拨周期指针即可。
- **推论 2**：两端必须逐位一致，所以所有静态输入必须由服务端下发。
- **推论 3**："变了的就变了"（累积转换）——未抽中的周期保留上次材质，世界单调地远离原样。
  不可逆才有叙事重量；可逆会把它变成"一个可以撤销的干扰"。

### 12.3 结构保证优先于配置纪律
能做成"结构上不可能违反"的，就不要写成"请注意不要……"。
- 池 × 形态类的切片**成员少于 2 个就整体作废** → 数学上不存在能被"停住"的方块（见 §4.1.4）；
- "两端一致"从约定变成 `MutationSettings` 数据结构（见 §8.1）；
- "客户端拿不到输入就不画幽灵"→ 把静默错误降级成可见缺失。

### 12.4 不要手工复刻原版
为了改变一个中间变量而复制原版流程，会随版本升级与其他模组持续产生偏差。
正确做法是**只替换那个你要改变的变量**（这里就是"被操作的是哪个方块"），让原版管线跑完。

---

## 13. 玩法设计意图

> 这里写的是**设计意图**（想让玩家体验到什么）。具体机制在 §2–§10，数值在配置项里。
> 尚未定案的取舍在 §14。

### 13.1 核心体验
玩家应该逐渐发现：**这个世界的随机不是随机，而是有语义结构的**，并且学会利用它。
"失焦"不是需要忍受的噪音，而是一门需要读懂的现象。

恐怖感的来源不是"无法预测"，而是**读懂了规则之后，知道规则指向什么**。
因此"让规则可读"与"让世界显得陌生"不冲突——把确定性藏起来才是浪费。

**三条主轴规则**（2026-09-28 定案，推导见 [`REVIEW-gameplay-spine.md`](REVIEW-gameplay-spine.md) §2）：

- **R1 词汇即样本**——玩家能说的概念只能来自他收集过的东西：**没有菜单，只有词汇表**；
- **R2 形可变，量不减**——他能决定它变成什么，但不能减少变化的总量。
  每一次定向失焦都在附近制造他控制不了的失焦；**基地不可能同时是工厂与堡垒**；
- **R3 阶段 3 起，词会失效**——`drift = 0.5 × t²`（`t` = 阶段 3 内的归一化进度，增速递增，上限 0.5），
  引导池按 `(1-drift)` 混入邻近概念。**模型没有变弱，是它的词被重新解释了。**

### 13.2 目标情绪曲线

| 阶段 | 玩家应该感到 | 机制上靠什么支撑 |
|---|---|---|
| 开局 | "有点不对，但还能应付" | 阶段 1 只在语义邻近内漂移，地貌整体仍可辨认 |
| 中期 | "它在朝我蔓延，我得做点什么" | 漂移范围与语义距离扩大；稳定装置成为必需品 |
| 后期 | "我认得的东西开始不可信了" | 侵蚀的对象从"世界"转向"稳定性"（见 §14 待定） |
| 结局 | "我必须结束它，而不是继续躲" | 王座 + 核心的胜利路径 |
> **后期情绪已定案（2026-09-28）**：「能力上涨」与「概念漂移」**两者都要**——
> 能力一路涨到阶段 2 的顶点，阶段 3 由 R3 接手：玩家的词汇开始被世界重新解释。
> 恐怖因此从"世界"转移到"他自己建立的那套理解"上，而那正是他投入最多的东西。

### 13.3 玩家动词

四个动词构成一个**闭环**（2026-09-28 定案），每一步都在同时改变"玩家知道什么"与"世界是什么"：

```
采集（观察）→ 概念（理解）→ 定向失焦（操纵）→ 固化收获（固定）→ 回到采集
```

1. **采集**——用模型右键方块，把样本喂进词汇表；
2. **理解**——样本解析出概念与完备度 `q`，`q` 到门槛就解锁新的动词（§13.10）；
3. **操纵**——**催化域**：在选定区域强制触发一次由该概念决定的失焦（§13.8）；
4. **固定**——把结果收走，或用稳定装置护住它。

> R1 治"与我无关"，R2 治"龟缩在基地"，R3 治"快餐挑战"。
> 旧版本在这里标过"第 3、4 个动词机制支撑不足"，2026-09-28 由 §13.8 补上（原条目见 §14.6 墓志）。

### 13.4 资源与经济意图
- 失焦**也应该**是资源机会，但不能是"抽奖暴富"。
- 目标语义：**失焦产生的是"错位"，不是"价值"**。
  石头变泥土是对的（都是便宜的地表材料），石头变钻石矿是错的（语义不相邻）。
  好的失焦应该让玩家觉得"这不对"，而不是"我赚了"。
- 因此采矿、探索、生物群系的价值必须保住；定向生产（用概念引导）才是致富的正确路径。
    > **2026-09-28 定案**：护栏加在**目标池**上，不是加在概率上——见 §13.9。
  > 病灶有两处：① **幽灵可挖**（挖幽灵拿的是幽灵的掉落，§5.2），所以"失焦"本身就是一条采矿路径；
  > ② **概念跨 tier**（`focal_decay:concept/ore` 里同时有 `coal_ore` 和 `netherite_block`）。
  > 把 `wild_chance` 压到 0 解决不了 ②。

### 13.5 稳定装置的设计张力
- "精确保护"（语义锁定：护住你点名的那些方块）与"粗暴保护"（全场稳定）是**两种游戏方式**，应保留；
- 稳定**不应该免费且永久**，否则玩家会退化成"把一切塞进安全泡泡然后不出去"；
- 但必须永远存在一种"保证稳定"的手段，否则游戏不可玩。
  → 要侵蚀的是**稳定性**（护盾会衰减、需要维护），不是"世界"。
- **2026-09-28 定案**：R2 的代价落在**定向失焦**上（域外一圈 `wild` 升高），
  语义锁定缩小为**便宜、小范围、选择性**的保护——**基地是研究站，不是掩体**。

### 13.6 模型型号的设计分工

| 型号 | 玩家用它做什么 | 张力来源 |
|---|---|---|
| 语义锁定 | **防御工具**：精确护住具体的东西（小范围、便宜、选择性） | 记录数量上限；阶段 3 转为软保护 |
| 引导突变 | **生产工具**：决定定向失焦的产出，**不再兼任防御** | 完备度 `q`——越高越准，能触及的 tier 越深 |
| 生物稳定 | 全域保护，代价是吸附近生物的生命（**是否改成"只管生物"待定**，见 §14 与 BACKLOG P2-6e） | 需要维持"电池"；阶段 3 双倍消耗 |
| 完全稳定 | **终局技术**：全域保护，半径 32 | 一次性获得，不可无限复制（代数衰减） |
| 候选观测者 | **胜利条件本身**：一个模型承载**多个独立的训练进度**（多概念 `q`），达标 = 取 `q` 最高的 **5** 个概念且每个 `q ≥ 0.75` | **不再作为普通防御模型投放**；`N` 与阈值随 copies 递增，要求玩家多处探索 |

### 13.7 叙事与机制的对应
七枚语义碎片各自对应上一迭代的一个事件，全部是**一次性里程碑**。
它们是"观测者的记忆知识"，用途是训练候选观测者（而不是合成钥匙）。
> **2026-09-28 定案**：碎片先承担**观测者的进度补偿**——直接给候选体**补 `q`**
> （用来补你拿不到的成员，例如只能靠冰霜行者获得的霜冰）。
> "扩展成新的感知维度"仍然保留为 P2 的方向（§14.7）。

---

### 13.8 定向失焦（催化域 / 沉降仪式）

玩家第一次能**指定失焦的结果**。两级形态共用同一组输入（概念 + 完备度 `q`）：

| | **催化域** | **沉降仪式** |
|---|---|---|
| 定位 | **工具** | **产能** |
| 载体 | **专用催化剂方块 + 火种道具** | 同一方块的大型仪式模式 |
| 动作 | 点火，立即生效 | 投入**一个训练好的模型**，等待并守在附近 |
| 产出 | 区域内可突变方块在**本周期内**变为该概念成员 | 半径 R 的球体，内含**多种该概念的方块** |
| 代价 | ① 区域内保护被压制；② **域外一圈 `wild` 升高**（R2） | 投入的模型 + 仪式期间失焦加剧 + **重聚焦后成本增加** |

- **为什么不是印钞机**：产出 tier 受 §13.9 的"只降不升"与 `q` 分级约束，默认不盈利；
  盈利只能来自"引导准确 + 时运 + 规模化"。
- **为什么它在架构上便宜**（读代码确认，不是推测）：区域效果管线
  （`PrototypeEffect` + 整表/增量同步 + 客户端镜像）已经存在；
  `MutationHelper.resolveInternal` 里"是否发生"就是 `chance` 一个 `double`，
  所以"区域级必中"是一次参数覆盖、不需要新的判定分支；
  回扫用的是 `MutationRandom.seed(pos, worldSeed, period)`，是纯函数——
  催化**不破坏"两端逐位一致"，也不需要逐方块存档**。
- **产出 tier 由模型的 `q` 分级**（仪式没有源方块，"只降不升"无从判断）：
  `q ≥ 0.75 → 可产出到 T1`、`q ≥ 0.9 → T2`、**`q = 1.0 → T4`**（顶端随 T4 的引入抬了一级）。
  于是**理解深度 = tier 权限**——"想产一球铁矿"**不需要见过钻石**，需要把这个概念理解得足够深。
  副作用是自洽的：`q = 1.0` 意味着你几乎记录了整个概念（**含那一块下界合金块本身**），
  所以**仪式是"量产"而不是"凭空造"**——它不能变出你从未见过的东西，这与 R1 一致。
- 形式一的载体与道具已定：**催化剂方块**（放下形成待激发区，界面显示当前区域的概念与 `q`）
  + **火种道具**（右键点火）。配方在 P0 末期定。

### 13.9 tier：获得门槛与"只降不升"

- **tier 的语义 = 获得它的文明门槛**，默认取原版 `needs_*_tool`
  （T0 无门槛 / T1 `needs_stone_tool` / T2 `needs_iron_tool` / T3 `needs_diamond_tool`），
  另加**覆盖标签**修代理失真的地方。需要人维护的只有**四条**（完整名单见评审文档 §3.3）：
  ① 铜装饰 76 条降到 T0；② 染色装饰 / 陶瓦 / 羊毛提到 T1；③ 黑曜石那一类降到 T1；
  ④ 信标这类"挖着容易、得到很难"的合成品提到 T2/T3（原版**没有**能表达这件事的标签）。
  实测依据：`needs_stone_tool` 的 84 条里有 **76 条是廉价铜装饰**，
  而 `beacon` 在三个 `needs_*_tool` 里**命中 0 次**。其余各层全部由现成标签推出。
- **自然失焦只降不升**：`tier(target) ≤ tier(source)`。于是失焦**永远不产矿**，
  而矿脉会自己烂掉（钻石矿 → 金矿）——这既是新的恐怖来源，也让**语义锁定第一次有了非抽象的用途**。
- **唯一的小概率例外**：引导 / 催化下**有 10% 的概率跨一级**（配置项 `guide_up_tier_chance`，默认 `0.1`），
  **最多跨一级**——石头永远不可能直接出钻石。**自然失焦不升 tier**，否则本节第一条就白定了。
- **装饰方块不按镐子分层**（2026-09-28）：染色混凝土 / 陶瓦 / 带釉陶瓦 / 羊毛 / 染色玻璃
  **不需要任何等级的镐子**（实测：在三个 `needs_*_tool` 里**命中 0 次**），
  所以只按工具门槛会把它们全判成 T0 —— 于是"石头变橙色混凝土"在开局就能发生。
  改用第二条代理规则：**T0 = "挖或烧就有"；T1 = "要出门或要养东西"**：
  染色方块（`c:dyed/*`，16 色 × 全部染色类型）、陶瓦（`minecraft:terracotta`，只在恶地生成）、
  羊毛与地毯（羊 + 剪刀）全部提到 T1；**玻璃留在 T0**（沙 + 熔炉，不需要出门）。
  标签清单与实测见评审文档 §3.2。
- **压缩形态 = 原矿 tier + 1**（2026-09-28 定案）：`raw_*_block` 与材料块（`c:storage_blocks/*`）
  比它们的原矿高一级。于是"一块矿石变成它的块形态"被"只降不升"挡住，而**这些方块仍然留在池里**——
  可作源、可被训练记录、可被仪式产出（比"把它们排除出目标池"更平衡）。
  推论：**`netherite_block` 落到 T4，而自然失焦的源最高只到 T3 ⇒ 它只能靠仪式获取**（§13.8）。
- **概念不拆**：`focal_decay:concept/ore` 保持统一，tier 过滤在**解析时按源**施加（"概念按源分层"）。
- **下界 / 末地不按 tier 处理**（2026-09-28 定案）：氛围由**维度池**承担，而这条**已经实现**——
  `ModTags.Blocks.poolForDimension` 让下界与末地各自换掉整个大池
  （`wild_nether` 45 条全是下界方块 / `wild_end` 6 条全是末地物），语义邻域也另有
  `mutation_pool/nether` 与 `mutation_pool/end`；**主世界不管**。
  实测与判读见评审文档 §3.4（那里同时记着 `end_stone` 只可能经**引导路径**进入主世界）。

### 13.10 知识 → 能力

完备度 `q` 不再只是一根缩放引导强度的系数，而是**四个动词门槛**：

| `q` | 解锁 | 动词 |
|---|---|---|
| ≥ 0.25 | 该概念下的漂移在画面上被标出来 | 看得见 |
| ≥ 0.50 | 可点火催化域 | 让它变 |
| ≥ 0.75 | 可做沉降仪式 | 让它产出 |
| = 1.00 | 点火时可**点名**概念里的某一种方块 | 说了算 |

- **`q` 的算法**：`q = sqrt(已记录成员数 / size_eff)` —— **上凸曲线**：
  30 个成员里记录 15 个就已经是 **70%**，但 **100% 仍然要求全部记录**。
  `size_eff = min(可训练成员数, HARD_CAP)`，其中"可训练成员"排除没有 `BlockItem` 的方块
  （`minecraft:frosted_ice` 正是这一类：霜冰只能靠冰霜行者获得，不排除则 `ice` 概念的 `q` 永远停在 75%），
  `HARD_CAP` 默认 **32**（防止概念种类真的太多）。
  这条曲线同时给了"早解锁来得快、最后一截很长"的手感：`q ≥ 0.25` 只要 6% 的成员，`q = 1.0` 要 100%。
- **候选体的达标条件**：取**记录过的概念里 `q` 最高的 5 个**，每个 `q ≥ 0.75`；
  `N` 与阈值随**已完成的候选体数量（copies）**递增（步长待实机，见评审文档 L-2）。
- **碎片**：先做**直接给观测者补 `q`**（用来补你拿不到的成员）；其余功能留到 P2。
- 训练界面在**首次训练出可用模型**时揭示"概念由你的样本决定"这条**规则**，
  此后常驻显示概念名 / `q` / 该概念规模 / 世界概念总数——**揭规则，不揭答案**。

## 14. 开放问题与待定取舍

> 这里放**尚未定案**的设计问题。定了之后：结论写进上面相应章节，本条从这里删除。

> **2026-09-28 起**：为避免其它文档里的"§14.x"引用失效，已拍板的条目**保留编号**，
> 正文换成一行"已拍板 → 见 §13.x"的墓志（决策记录在 `docs/REVIEW-*.md`）。
> 对应的实施条目在 `BACKLOG.md`。

### 14.1 稳定的表达方式：写世界 vs 规则层（影响最大）
**现状**：登记保护时先把范围内方块"固化"成当前失焦态（真的写进世界），再登记保护。
半径 32 = 65³ ≈ 27 万次坐标遍历，单 tick 同步执行。
**2026-09-25 实测**（仅覆盖已加载区块，详见 [`progress/2026Q4.md`](progress/2026Q4.md) §C）：
27 万坐标里实际扫描 4,879、改写 821、耗时 66 ms；按扫描量等比外推到"区块全加载"是秒级。
固化会**尊重既有原型机的硬保护**（2026-09-25 修，见同一节）。

**待定**：是否改为"保护只是解析时叠加的规则"，不改世界：
```
visible(pos) = protection.hard() ? anchored(pos, anchorPeriod) : resolve(...)
```
- 好处：放置代价归零；保护与渲染天然同源；取走模型时"恢复到当前失焦态"变成自动行为。
- 代价：`anchored` 需要"锚定周期 + 出生周期"共同解析（因为玩家会在基地里建造），
  复杂度是**转移**而不是消失。
- 若成立，`convertPrototypeRange` 整个删除，BACKLOG `P0-1` 自动关闭。

### 14.2 出生周期的身份：跟坐标还是跟方块
记录目前挂在坐标上（`Map<BlockPos, Long>`），只有玩家放置与右键转换会写入、只有玩家破坏会删除。
活塞 / 爆炸 / `/fill` / `/setblock` / 结构放置都不维护它。
- 方案 A：保持按坐标，但补"方块变了就清记录"（记录里连出生时的方块一起存）；
- 方案 B：明确定义"只有玩家途径产生的方块才有出生保护"，接受其余偏差（最省力，但必须是**声明**）。

### 14.3 阶段推进的语义：概率变高 vs 规则被侵蚀 `[已拍板 2026-09-28]`

**结论**：选"规则被侵蚀"。阶段 3 起**概念本身开始漂移**（R3，见 §13.1 / §13.2）：
`drift = 0.5 × t²`，增速递增、上限 0.5，取代旧配置 `guided_stage3_halve`（把 `q` 直接砍半）。
曲线理由与作者原话见 [`REVIEW-gameplay-spine.md`](REVIEW-gameplay-spine.md) 裁定 3。

### 14.4 阶段 1 的"逐渐"体感
累积回扫窗口固定 128 周期 → 约 38 分钟后全图 99% 的方块已经不是原样，
而阶段 1 持续到第 3 天（约 72 分钟）。**玩家还没进阶段 2，世界就已经面目全非**，
"逐渐恶化"在画面上看不出来。
- 候选方向：**回扫窗口随阶段增长**（例如阶段 1 = 8 周期 → 阶段 2 = 32 → 阶段 3 = 128），
  并让阶段 1 的 `wild_chance` 接近 0（只用语义邻近池）。
- 前提：**保留累积语义**，只调窗口长度。

### 14.5 大池的权重（`wild_chance`）
默认 0.25 意味着"每四次突变就有一次完全跨语义"。而大池自动纳入 598 个方块
（含装饰方块、含矿物）。
- 后果不是"太随机"，而是"没有意义"——玩家看到最多的是"羊毛变成带釉陶瓦"，
  连"不对"都算不上，只是无聊。
- 候选方向：压到 0.02~0.05，让跨语义漂移成为**罕见且显眼的灾难**。
- 配套：矿物应"可做源、不做目标"（`mutation_source_extra` 语义）。
  > **2026-09-28 部分解决**：矿物护栏改由 **tier 的"只降不升"**（§13.9）承担，
  > 比"压概率"更硬——压概率只是让抽奖变稀有，tier 让它**不可能**。
  > `wild_chance` 本身的数值仍需实机调，本条其余部分仍然有效。

### 14.6 观察产出预测 `[已拍板 2026-09-28]`

**结论**：**不采用**"给出预测读数"的方案——那会把玩家放到世界外面读数。
产出改为**动词**：概念 → 催化域 → 收获（§13.3、§13.8）。
"拿扫描仪预测下一个方块"这条方向是三方一致否掉的。见评审文档裁定 16 / 21。

### 14.7 碎片的最终形态
现状：七个一次性里程碑 = 收藏品 + 候选体 +10 点，没有一个进入核心循环。
- 候选方向：保持一次性，但给**只能用一次的明确机制效果**，用掉就没了。
  最贴合主题的是"新的感知维度"——例如让失焦的**时间轴**对玩家可见
  （手持时能看到"下一周期会变成什么"），或给出整个概念的漂移谱。

### 14.8 胜利条件是否应该屏蔽绕行 `[已拍板 2026-09-28]`

**结论**：屏蔽，两条一起生效——
① 未完成候选体**不再作为普通防御模型**投放；
② 候选体的进度单位从"见过多少方块"改成**"理解了多少概念"**（一个模型、多个独立的训练进度）。
于是"在出生点附近右键 100 个方块通关"不再成立。见评审文档裁定 4 / 5。

### 14.9 客户端"看得见但摸不到"的边界
碰撞与射线命中仍按真实方块，选择框画真实形状，破坏粒子用真实材质。
- 待定：是否让**选择框**跟随幽灵（碰撞几乎不可能可靠地混合）；
- 无论做不做，都要在手册里写明这条边界。

### 14.10 早期门槛
第一个稳定装置需要 8 铁块 + 4 末影之眼（**需要下界**），而通往那里的路本身被随机化。
- 方向 A（稀缺）：开局给一枚一次性微型锚，让玩家先体验"稳定是可能的"；
- 方向 B（廉价）：降配方，末影之眼留给更高级型号。
- 两个方向导向完全不同的游戏，需要实机数据支撑。

### 14.11 实体突变的边界
- 源池目前是**所有 `Mob`**（无 Boss / 村民 / 已驯服 / 命名生物过滤），
  转换用 9 键白名单（丢装备、背包、村民交易）——这些**不是设计意图，是缺陷**，见 `BACKLOG.md` P1-1b；
- 需要设计的是：**哪些生物应该免于失焦**，以及"被转换的生物带走了什么"这条规则怎么向玩家交代。

---

## 15. 术语表

| 术语 | 含义 |
|---|---|
| **失焦（defocus）** | 事物语义漂移、表现为形态改变的现象。本模组的核心机制 |
| **周期（period）** | 失焦的时间单位（默认 100 tick）。失焦状态是 `(pos, worldSeed, period)` 的纯函数 |
| **显示刻 / 存储刻** | 两根时钟：显示刻受 `/focaldecay period` 的倍率与偏移影响（用于解析与出生周期），存储刻只跟真实时间走（仅作自测参照）。见 §5.0 |
| **突变源** | 会被失焦的方块。由"池即源"推导，加 `mutation_source_extra` / `mutation_immune` 两个补丁标签 |
| **突变池** | 失焦的目标集合（语义邻域）。一个方块可进多个池，取并集 |
| **大池（wild）** | 以 `wild_chance` 概率**整枝**命中的跨语义池，用来保证长尾随机性 |
| **形态类（shape class）** | 几何约束：目标必须与源同类（楼梯只变楼梯） |
| **累积转换** | 未抽中的周期保留上次材质而不是回退原方块，世界单调地远离原样 |
| **诞生周期（birth period）** | 玩家放置/交互过的方块记录的"出生时刻"，闸门从 `诞生周期 + 1` 才开始崩坏 |
| **固化（normalize）** | 登记保护前把范围内方块写成当前失焦态（§14.1 正在讨论是否取消） |
| **观测者基座（原型机）** | `anchor_prototype`。现场唯一可部署的稳定装置，插入观测模型后生效 |
| **观测模型 / OBSR** | 可插入基座的模型物品，五种型号 + 候选观测者。数据存 `ObserverModelData` 组件 |
| **概念（concept）** | 引导模型训练完成后固化的语义标签（`focal_decay:concept/*` 或原版标签） |
| **完备度 q** | 引导模型对概念的指认完备程度，决定偏向概念邻域的概率 |
| **语义锁定 / 硬保护 / 软保护** | 保护形态：完全不动 / 阶段 1-2 的锁定 / 阶段 3 的"每周期掷守住骰" |
| **重聚焦（refocus）** | 观测者核心上线、失焦终止 |
| **候选观测者（OBSR-3）** | 主线道具：**一个模型承载多个独立的训练进度**（多概念 `q`）。达标后可在核心处安装为"新观测者"，**不再作为普通防御模型**（§13.6） |
| **王座仪式** | 在末地王座把未激活 OBSR-EX 升级为已激活（完全稳定锚）的流程 |
| **定向失焦** | 玩家主动指定失焦结果的能力，两级形态：催化域（工具）与沉降仪式（产能）。见 §13.8 |
| **催化域** | 点火后在选定区域内**强制触发一次**由该概念决定的失焦；期间区域内保护被压制，域外一圈 `wild` 升高（R2 的代价） |
| **沉降仪式** | 消耗**一个训练好的模型**，在半径 R 内生成一球该概念的方块（对标血魔法的坠星仪式）。重聚焦后仍可做，代价增加 |
| **火种** | 点燃催化域的专用道具（§13.8；具体形态待定，见 `REVIEW-gameplay-spine.md` P-6） |
| **压缩形态（T4）** | `raw_*_block` 与材料块 = 其原矿 **+1 级**。自然失焦的源最高到 T3，所以 T4（实测只有 `netherite_block`）**只能由仪式产出**（§13.9） |
| **tier（获得门槛）** | "要多少文明才能拿到它"，默认取原版 `needs_*_tool`，由覆盖标签修正。自然失焦**只降不升**（§13.9） |
| **维度池** | 大池与语义邻域都按维度分开（`poolForDimension` → `wild_nether` / `wild_end`）。下界与末地的失焦因此只产本维度方块，氛围不靠 tier 维持（§13.9） |
| **概念按源分层** | 概念保持统一，但被 tier 规则按**源方块**过滤（石头 + `ore` 概念 ⇒ 池里只剩 T0 成员） |
| **概念漂移（R3）** | 阶段 3 起引导池按 `drift = 0.5 × t²` 混入邻近概念：模型没变弱，是它的词被重新解释了（§14.3） |
| **词汇表** | R1 的说法：玩家能说的概念只能来自他收集过的样本。**没有菜单，只有词汇表** |
| **失焦终止** | `observerOnline = true`：所有突变、幽灵预览、交互转换停止 |
