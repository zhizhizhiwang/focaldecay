# BACKLOG.md — 待办清单

> **本文件是待办的唯一真源。** 其他文档（设计、进度、守则）都**不要**再各自维护待办。
>
> - 设计意图与开放取舍 → [`DESIGN.md`](DESIGN.md)（§14 是"待讨论的设计问题"，本文件是"要动手的条目"）
> - 交付计划与阶段门槛 → [`ROADMAP.md`](ROADMAP.md)
> - 技术细节与踩坑 → [`PITFALLS.md`](PITFALLS.md)
> - 已完成的条目从这里**删除**，改为在 [`progress/`](progress/INDEX.md) 里记一节
>
> 来源：对 `7b6b408`（工作区干净）做的一轮「代码 + 架构 + 玩法」审查，含三方独立代码审计与
> 反编译原版源码核对。另外吸收了 2026-09-25 的玩法讨论。
> 标注：`[实机]` = 只能靠真人游玩/专用服务器确认；`[拍板]` = 属于设计取向，动手前先确认。

---

## 0. 前提：这份清单针对"重构之后"的代码

外部（GitHub 页面）能看到的是 §13.7「突变池三层模型」重构**之前**的版本，因此一批常见批评已经不成立。
先记录"已解决"，避免以后重新讨论：

| 常见批评 | 当前状态 | 证据 |
|---|---|---|
| 池是手写 `Block[]`、主世界约 218 种 | 已改为**数据包标签驱动**：`mutation_pool/*` + `shape_class/*` + `mutation_immune` | `pool/MutationIndexBuilder.java` |
| `MutationPoolManager` 职责过重 | 池已迁出；保护/概念邻域改为**登记期预算**的 `ClassifiedPool` / `Set<Block>` | `MutationPoolManager.java:40-60` |
| 热路径逐方块 `getKey().toString()` + `List.contains` | 已消除：按注册表 id 的 `boolean[]` / `Block[][]` 直读 | `pool/MutationIndex.java`、`MutationIndexBuilder.java:118-145` |
| 每步 `RandomSource.create` 造成大量分配 | 已换 `MutationRandom`（单 `long`、SplitMix64、零分配、跨版本契约稳定） | `MutationRandom.java` |
| "视觉方块/真实方块"两套状态，各系统各自套公式 | 已收拢为唯一入口 `MutationHelper.resolve` + `MutationTargets`，所有调用点改走 | `MutationHelper.java:100`、`MutationTargets.java` |
| 客户端拿不到种子/服务端配置 | 已用 `MutationSettings` 快照 + `SyncMutationSettingsPacket` 把"约定"变成"数据结构"；拿不到就**不画幽灵** | `MutationSettings.java`、`MutationEventHandler.java:118-135` |
| 客户端缓存只按周期失效 | 已补"算它时的真实方块"（`Entry.real` / `evaluated`），修掉"洞里 1~3 秒不出现" | `ClientRenderCache.java:94-105` |
| 玻璃幽灵面剔除破洞 | 已补 `BlockShouldRenderFaceMixin`，剔除与网格同源 | `mixin/client/BlockShouldRenderFaceMixin.java` |
| `MutationStateMapper` 缓存并发写坏 | 已改每线程一份 `ThreadLocal`，并有 `[stress]` 回归项 | `MutationStateMapper.java:69` |

**并且**：`## 8. 不建议改的` 一节列出的是**刻意设计**，不要当 bug"顺手修"。

---

## P0 — 会卡死 / 丢东西 / 破坏存档内容

> **已完成（2026-09-25）**：`P0-2` 锚固化无视既有保护 → 已修，见
> [`progress/2026Q4.md`](progress/2026Q4.md#c-锚固化无视既有保护2026-09-25)。
> 编号**不复用**：P0 号段里留一个空洞，免得其他地方已经写下的引用失效。

### P0-1 锚固化：单 tick 同步写 65³ = 274,625 个方块

> **2026-09-25 更新：埋点已完成，已有实测数字。剩下的分片/规则层改造仍未做。**
> 实测（无头服务器，devtest 的 `throne selftest` 触发一次半径 32 固化）：
>
> | 项 | 半径 12 | 半径 32 |
> |---|---|---|
> | 遍历坐标数 | 15,625 | 274,625 |
> | 实际扫描 | 1 | **4,879** |
> | 实际改写 | 1 | **821** |
> | 耗时 | 4 ms | **66 ms**（CPU 62 ms） |
>
> **关键读数**：半径 32 的 66 ms <b>只覆盖已加载的区块</b>——274,625 个坐标里只有 4,879 个被扫描，
> 也就是 1.8%（`isLoaded` 守卫把未加载区域整个跳过了）。
> 因此真实游玩中若目标范围内区块全部加载，耗时按扫描量等比放大到**秒级**是合理外推；
> 但**不要把这个外推当成实测数字**——需要在"范围内区块全加载"的条件下再量一次。
> 另外 `setBlock` 的开销远大于解析：4879 次扫描里 821 次真的写了方块，而 821 次写入就占了大部分时间
> （对比半径 12：1 次扫描 + 1 次写入 = 4 ms，其中含固定开销）。

- `MutationEventHandler.convertPrototypeRange`：`betweenClosed(-r..+r)` 逐坐标
  `getBlockState` → `protectionInfo`（硬保护跳过）→ `getGuidedBias` → `resolve`
  （**最多回扫 128 周期**）→ `level.setBlock(p, target, 3)`。
  半径 32（完全稳定模型 / 已完成候选）即 274,625 个坐标，**主线程一次性突发，无分片、无预算**。
- `flag = 3` = `UPDATE_NEIGHBORS | UPDATE_CLIENTS`，于是每次写入都带邻块更新 + 光照重算 + 区块标脏 + 客户端包。
- 调用点：`MutationEventHandler.onBlockPlaced`（放置事件）与 `AnchorPrototypeBlockEntity.setItem`（放入模型）。
- 开关只有 `anchor_normalize_range`（默认 true）。埋点见 `AnchorNormalizeProfiler`，
  `/focaldecay mutation selftest` 末尾会打印累计统计（每次固化也会打一行 INFO）。
- **还剩两件事**：
  1. **分片执行**：按 y 层或按区块节切片，跨 tick 推进；期间**先登记保护**（保护是纯判定，不依赖固化完成）。
  2. 或按 P2-1 降级为纯规则层，直接删掉这个函数——**这是更彻底的方向，但要先拍板**。
- 验收：单 tick 增量 < 50 ms，或明确摊到 ≥20 tick；固化完成后范围内外观与服务端解析逐位一致。

### P0-3 掉落物突变三重破坏（丢件 / 丢组件 / 随机销毁）

- `DoomsdayHandler.java:155-160`：
  ```java
  Block block = blockPool[random.nextInt(blockPool.length)];
  ItemStack stack = itemEntity.getItem();
  itemEntity.setItem(new ItemStack(block.asItem(), stack.getCount()));
  ```
- **丢件**：`blockPool` 是 `wild().flat()`；默认 `wild_auto_include=true` 会把**所有 cube 且无方块实体**的方块纳入大池
  （`MutationIndexBuilder.java:71-83`），其中存在 `asItem() == Items.AIR` 的方块（没注册 BlockItem 的技术/结构方块）。
  `new ItemStack(AIR, n).isEmpty()` 为真，而原版 `ItemEntity.tick` 对空栈物品实体直接 `discard()`
  → **整堆物品静默消失**。（注：`ItemEntity.setItem` 本身不 discard，删除发生在下一 tick。）
- **丢组件**：`new ItemStack(item, count)` 丢弃全部组件（附魔、耐久、命名、潜影盒内容、模组组件）。
- **随机销毁**：这不是"形态漂移"，而是概率性销毁。死亡掉落同样中招。
- 修法：
  1. 掉落物**单独建池**（物品标签，或构建期从大池过滤 `asItem() != Items.AIR`），并缓存成 `Item[]`，
     不要每次 `asItem()`。
  2. `mutation audit` 增加断言"目标池中不存在 `asItem()==AIR` 的方块"，让数据包写错立刻可见。
  3. `[拍板]` 组件策略：保留全部组件只换物品 / 只保留数量（现状，但语义要写进手册）/ 只对无组件物品生效。
- 验收：常见掉落物突变后件数守恒；全池 100% 抽样无空栈；审计断言为 0。

### P0-4 挖掘绕过原版破坏生命周期

- `InteractionHandler.onBlockBreak`（`:348-418`）`setCanceled(true)` 后手工补
  `setBlock(AIR)` → `Block.getDrops` → 自建 `ItemEntity` → `getExpDrop` → `mineBlock` → `awardStat` → `causeFoodExhaustion`。
- 已对照原版 `ServerPlayerGameMode.destroyBlock`（`:250-289`）与 NeoForge 补丁逐行求差集
  （**Fortune / Silk Touch 是保留的**：工具传进了 `getDrops` 的掉落参数）：

  | 原版步骤 | 本模组 | 后果 |
  |---|---|---|
  | `block.playerWillDestroy(...)` | **缺失** | 依赖它的模组方块状态不更新（蜂巢激怒、成就等） |
  | `state.onDestroyedByPlayer(...)`（可拒绝 + **恢复流体**） | **缺失** | 拒绝被破坏的方块会被强行移除；**水logged 方块的水被一并删掉**（水源消失） |
  | `block.destroy(level,pos,state)` | **缺失** | NeoForge 的方块销毁钩子丢失 |
  | `block.playerDestroy(...)` | **缺失** | **自定义方块覆写的掉落逻辑全失效**（容器溢出内容物：变成箱子时装着的东西直接没了） |
  | `state.spawnAfterBreak(level,pos,tool,false)` | **缺失** | 方块专属破坏效果丢失（红石矿额外掉落、sculk 等） |
  | `CommonHooks.handleBlockDrops` → `BlockDropsEvent` | **缺失** | 其他模组改掉落/取消掉落全部失效 |
  | `GameRules.RULE_DOBLOCKDROPS` | **未检查** | 自建 `addFreshEntity` 绕过它 → `doTileDrops=false` 时**照样掉东西**（经验仍受门控） |
  | `EventHooks.onPlayerDestroyItem(...)` | **缺失** | 工具挖坏瞬间的其他模组钩子收不到事件 |
  | `canHarvestBlock(...)` 门控 | 部分 | 原版 `getDrops` 覆盖了绝大多数，模组方块的自定义覆写会被绕过 |
  | `BlockEntity` 传入 `getDrops` | 传 `null` | 少数按方块实体算掉落的方块算错 |

- **建议**：不要继续"补调用"，改为**只替换"被破坏的是哪个方块"这个变量，然后让原版管线跑完**
  —— 破坏时把可见目标真的放回世界（`setBlock(pos, target, 3)`），让原版 `destroyBlock` 对着目标走完全流程。
  这样掉落/耐久/经验/统计/成就/其他模组的 `BreakEvent` 监听**全部自动正确**。
  风险：真的改世界（右键路径已如此），且目标方块的 `BreakEvent` 会二次触发 → 复用右键那套 `ThreadLocal` 旁路标志。
- **需要核实的一条**（审计提出、未验证）：`CommonHooks.fireBlockBreak` 在事件以"取消"收场时会把**破坏前的状态**回发给客户端，
  而本模组是先 `setBlock(AIR)` 再取消 → 客户端可能瞬间收到"那个方块还在"，表现为方块闪一下再消失。
  实机留意，若成立则并入上面的重构一起解决。
- 退路：至少补齐上表八项 + 传真实 `BlockEntity`。
- **顺带修的 `BreakData` 缺陷**：① 破坏时只校验 `pos`、**不校验维度**（`BreakData.java:38-43`、`:362`）
  → 跨维度残留锁定；② `copyOnDeath()`（`ModAttachments.java:36`）让锁定跨死亡存活；
  ③ `getPeriodIndex()` 记了但破坏时**从未被读取**（死数据）。
- 验收：`selftest` 新增 `[break]`，断言"原版 `destroyBlock` / `playerDestroy` 对目标方块被调用"；
  `[实机]` 用装有机器的模组方块、潜影盒/箱子、水logged 方块、`doTileDrops=false` 各验一次。

### P0-5 客户端保护镜像在"同维度重生"后永久失效

- 客户端镜像只在 `ClientLevel` 实例变化时丢弃（`ClientRenderCache:359`，内部 `regions.clear()`），
  而重发只在**登录**与**换维度**（`MutationEventHandler.java:118-135`）。
  仓库内**没有任何 `PlayerRespawnEvent` / `PlayerEvent.Clone` 处理**。
- 后果：同维度死亡重生后，客户端对整局游戏**再也没有保护范围与出生周期数据**：
  在被保护的立方体里照画幽灵（服务端不会转换它），玩家放置的方块被当作世界原生方块。
- 修法：监听 `PlayerEvent.PlayerRespawnEvent`（以及必要时 `Clone`）补发
  `sendMutationSettings` / `sendRegionData` / `sendWorldData`，与登录路径走同一个方法。
- 验收：`[实机]` 死亡重生后，站在基地保护区内确认无幽灵；`/focaldecay` 诊断能打印"区域数据版本/最后同步时间"。

### P0-6 客户端 `evaluated` 负缓存无界增长（内存泄漏）

- `evaluated`（`ClientRenderCache:126`）在**每个编译过的位置**被写入
  （`:201/:205/:217/:232`），面剔除路径也读它。
- 但 `clearCache()` 开头是
  ```java
  if (targetCache.isEmpty() && activeSections.isEmpty()) return;   // :905-907
  ```
  → `evaluated.clear()`（`:912`）**被跳过**。而"`targetCache` 为空"恰恰是 `observerOnline = true`
  （失焦终止）时的常态：`resolve` 仍会对每个方块 `evaluate(...)` 然后返回原方块（`:203-206`）。
  断线也不清（`:340-341` 走同一个 `clearCache`）。
- 后果：整个 JVM 会话内单调增长，约 **4096 条/编译过的区块节**；渲染距离 16 长时间游玩可达千万级，
  且键是装箱的 `Long`。
- 修法：把清理判据与"是否需要重编译"解耦——`evaluated` 的失效条件只有"周期变 / 世界变 / 区域数据变"，
  与 `targetCache` 是否为空无关；`observerOnline` 翻转时也应显式清一次。
- 验收：长时挂机后 `evaluated.size()` 有上限（建议加一条周期日志，与现有
  `N dropped mid-period because the real block changed` 并列）。

### P0-7 服务端 `resolve` 仍读本端配置，与同步快照可能不同

- `MutationTargets.resolveServer:46-49` 走的是**服务端本端配置**入口
  （`MutationHelper.resolve(...)` → `FocalDecayConfig.WILD_CHANCE`，`:82`），
  `MutationPoolManager.protectionInfo:253` 读本端 `SEMANTIC_LOCK_STAGE3_STRENGTH`，
  `MutationIndexBuilder:71` 读本端 `WILD_AUTO_INCLUDE`。
- 而客户端**只**吃同步下来的 `MutationSettings`（含 `wildChance`、`semanticLockStage3`），
  `WILD_AUTO_INCLUDE` 则**不在快照里**（`MutationSettings.java:30-41`）。
- 单人/专用服务器下两者恰好相同，所以从没暴露；**局域网里两台客户端各改各的 toml 就会分叉**
  —— 这正是 `PROGRESS.md` §13.12 修过的那一类，只是漏了这三个量。
- 修法（按代价递增）：
  1. 服务端也统一走 `MutationSettings`（`fromConfig` 结果缓存起来，服务端只用它），彻底消除"两个入口"；
  2. 或最小改法：`WILD_AUTO_INCLUDE` 加进 `MutationSettings`，另两个改为从快照取。
- 顺手：`MutationSettings` 目前**只在登录/换维度发**，仓库内**没有 `ModConfigEvent` 监听**
  → 运行期改配置后客户端一直用旧值直到重连。建议挂配置重载事件重发。
- 验收：`[sync]` 自测扩展为"改任何影响 `resolve` 的配置项后，两端入口仍逐位一致"。

### P0-8 建立"实机验证矩阵"

`PROGRESS.md` 有多条明确写着"未做实机验证"。一次性补一张矩阵，每项写清**步骤 / 期望 / 观测点（日志行或界面）/ 判定**：

1. 锚固化耗时与固化后外观（P0-1）
2. 掉落物突变 1000 次抽样的丢件计数（P0-3）
3. 门/床/高花/`/fill`/`/setblock`/活塞/爆炸之后的出生周期行为（P1-2）
4. 多人：房主 + 客人 + 专用服务器，两人同时挖/右键同一格
5. 死亡重生后保护数据（P0-5）
6. 长时间挂机（≥2h）的内存、SavedData 体积、`evaluated` 条目数（P0-6）
7. `/reload` 后两端 `mutation index` 行数字仍一致
8. 未装 Patchouli / 未装 JEI 的整合包启动（约定 7 的回归）

---

## P1 — 身份、一致性与可复现性

### P1-1 实体突变种子没有实体身份

- `DoomsdayHandler.java:149`：`seed = mix64(worldSeed ^ entity.blockPosition().asLong() ^ tick)`。
  同一 tick、同一 `blockPosition()` 的多个实体得到**完全相同的随机序列**（同一个 `RandomSource`）
  → "是否突变 + 突变成什么"完全一致。牧场/刷怪塔/村庄可见"一群牛同时变成同一只僵尸"。
- 修法：异或进 `entity.getUUID().getLeastSignificantBits()`。**各自先 `mix64` 再异或**，避免低位纠缠
  （纵向熵那一课的教训，见 `PROGRESS.md` §5）。
- 顺带记录的语义：`tick` 是绝对 tick，所以实体突变**读档/重启后不可复现**。方块是 `(pos, seed, period)` 的纯函数，
  实体不是 —— `[拍板]` 是否统一到 `period`（统一后失去"每 tick 都可能变"的紧迫感）。
- 验收：新增 `[entity]` 自测断言"同位置同 tick 的不同实体得到不同结果"。

### P1-1b 实体突变的**范围**没有过滤：Boss、村民、已驯服、`PersistenceRequired` 全在里面

- 现状：源是**所有 `Mob`**（`DoomsdayHandler:161`），没有任何筛选：
  - 没有 Boss 例外 → 末影龙/凋灵可以被转换成别的生物。**龙被换掉后龙战无法完成**（战斗状态只由龙的死亡清除），
    主线（龙遗物宝箱、碎片·Aaron）直接断掉。
  - 没有村民例外 → 村民变被动生物，**交易与职业内容一次性消失**；而 `EntityMutation` 的 9 键白名单
    （`EntityMutation.java:23-32`）丢掉 `Offers`/`HandItems`/`ArmorItems`/`Items`/`NoAI`/乘客/`Silent`
    → 已经装好的农场动物、驯服的宠物、身上的装备、村民的交易**全部蒸发**。
  - 没有 `PersistenceRequired` / 命名生物例外 → 玩家精心命名、拴住的动物照样被换掉。
- 这是"规则不透明"最刺眼的地方：玩家无法从界面或手册知道**哪些东西是安全的**。
- 修法：① 白名单/黑名单标签（Boss、`#minecraft:raiders`、村民、已驯服、命名、`PersistenceRequired`）；
  ② `EntityMutation` 的保留字段从 9 键白名单改成"整份 NBT 减去已知瞬态字段"，
  并显式保留 `Offers` / `HandItems` / `ArmorItems` / `Items` / `NoAI` / 乘客 / `Silent`；
  ③ 手册里明确写出"哪些生物也会失焦"。
- 验收：`[entity]` 自测断言 Boss/村民/已驯服不在源池；实机验证村民交易在阶段 3 仍可用。

### P1-1c `EntityMutation.convert` 先 `discard` 再建新实体

- `EntityMutation.java:65-79`：`source.discard()`（`:70`）发生在 `create` 的**空值检查**（`:72-75`）
  与 `load(...)`（`:76`）**之前**。于是 `create` 返回 null，或 `load` 抛异常时，
  **旧实体已经被删、新实体还不存在** → 生物凭空消失（`load` 抛异常还会直接逃出 tick 处理器）。
- 修法：先建、先 `load`、确认成功，再 `discard` 旧实体；`load` 包在 try/catch 里，失败则放弃本次转换。
- 验收：`[entity]` 用"必定失败的转换目标"构造一条断言，确认旧实体仍在。

### P1-2 出生周期的身份语义：坐标 vs 方块

- `Map<BlockPos, Long> blockBirthPeriods`（`MutationPoolManager.java:72`），语义是**挂在坐标上**。
  写入只有两处：放置（`MutationEventHandler:56`）、右键转换（`InteractionHandler:295`）；
  删除**只有** `BlockEvent.BreakEvent`（`MutationEventHandler:70`）。仓库内只注册了两个方块事件。
- 缺口：
  - **活塞推动**：元数据留在旧坐标 → 新位置"立即失焦"，旧位置留幽灵记录。
  - **爆炸 / `/fill` / `/setblock` / 结构放置 / 其他模组移除**：不触发 `BreakEvent` → 记录残留，
    会在该坐标**最多 128 周期（默认约 10.6 分钟）内抑制新方块的失焦**。
  - 反向：非玩家途径放置的方块没有出生记录 → **立即参与失焦**（`/fill` 造的建筑当场开始烂），与玩家放置不一致。
  - **剪枝不同步**：`pruneBirthPeriods`（`:339-351`）只在服务端删；客户端只在登录/换维度收整表
    → 把失焦时钟回拨到地平线以外时，**服务端闸门开、客户端闸门关**，直到重连。
- `[拍板]` 选一个语义并写进 `PROXYAI.md` 与手册：
  1. **按坐标 + 补清记录**（记录里连"出生时的方块"一起存，方块变了就惰性清除；表变大，但剪枝已让它有界）；
  2. **明确定义"只有玩家途径的方块才有出生保护"**，接受活塞/`/fill` 的两种偏差（最省力，但必须是**声明**而非遗漏）。
- 顺手：剪枝地平线硬绑 `CUMULATIVE_SCAN_CAP = 128`，改回扫上限会让表线性变大 —— 在配置注释里点明这个耦合。

### P1-3 多条服务端路径的"保护/引导"判定仍是 O(原型机数) + 字符串分配

- `DoomsdayHandler.isEntityProtected:174-198`：**每个实体**都
  `BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()`（字符串分配）再线性扫全部效果。
- `MutationPoolManager.protectionInfo:231` / `getGuidedBias:281`：线性扫 + 切比雪夫判定，**无空间索引**
  （全仓 grep 确认没有 `ChunkPos/SectionPos → effects` 的映射）。
- 客户端更重：`resolveGuidedModels()` 在**每个方块**上被调用（`ClientRenderCache:230,318`）
  → `ClientRegionData:265-284` **每次新分配一个 `ArrayList`**，并对每个原型机重做
  `MutationIndexes.get` + `index.tagged(concept)`（字符串哈希）；`resolve` 的注释却写着"每个区块节解析一次"。
- 修法：① `trainedEntities` 在登记期解析成 `Set<EntityType<?>>`（照抄已有的 `Set<Block> trained` 做法）；
  ② 按 `ChunkPos`/`SectionPos` 建索引；③ 客户端的引导模型列表改成**随区域数据快照一起重算并缓存**，
  不要逐方块重建。
- 验收：`[stress]` 或微基准显示单方块/单实体判定无分配；热路径耗时不回退。

### P1-4 客户端区块编译线程上的竞态

- `ClientRegionData.current():196-200` 与 `:267-271`：
  ```java
  if (mc.level == null) return null;
  return byDimension.get(mc.level.dimension());
  ```
  `Minecraft.level` 不是 volatile，从 ForkJoinPool worker 上做 `check-then-act`：
  两次读之间主线程断线 → **编译器 worker NPE**；读到旧值 → 拿到错维度/错保护数据。
  这与 `ClientRenderCache:796` 自己写的"避免跨线程读主世界"注释直接矛盾。
- 另外 `clearCache()` 与在飞的编译之间没有屏障：worker 可以在清空之后用**旧的** `RenderChunkRegion`
  重新插入一条幽灵（跨维度陈旧条目最多存活一个周期）。
- 修法：客户端把"当前维度 + 该维度区域数据"打成**一个不可变快照对象**，主线程换世界时整体替换，
  编译线程只读那一个 volatile 引用（一次性拿到，不再分两步）。
- 验收：编译器路径上不再出现对 `Minecraft.level` 的二次读取；`[实机]` 反复快速换维度/退世界无异常。

### P1-5 客户端的"逐方块重走整个可见体积"

- `scanSurfaces`（`ClientRenderCache:606-624`）每 `surface_update_frequency`（默认 2 **tick**，不是文档写的 frame）
  最多扫 `SCAN_SECTION_BUDGET = 12` 个区块节 × 4096 = **≤49,152 个位置/次，约 49 万/秒**。
- 每个位置都要：`getBlockState`、`isCandidate`、`isProtectedNow`（扫全部原型机）、
  `isExposed`（最多 6 次邻居查询 + `getFaceOcclusionShape`），暴露面还要跑完整 `resolve`（回扫 ≤128 周期）。
- 队列上限 17×17 列 × ≤13 层 = **≤3,757 节**，只在**排空后**重建 → 走完一圈约 313 次 ≈ **31 秒**。
  视锥剔除只到**区块节粒度**，没有逐方块剔除。
- 判断：单次有界，但**永远重复遍历同一体积**，成本与"加载的可见体积"成正比、与"变化量"无关。
  小世界可以，大整合包 + 大视距是客户端 CPU 热点。
- 建议：① 给扫描加**便宜的早期出口**（例如用一个"本节的方块是否可能变化"的粗判据，或把"已判定且有效"的节跳过）；
  ② 加客户端侧计时日志，先量出真实占用；③ 逐方块视锥剔除（视锥平面预计算，避免每块重建 AABB）。
- 验收：给出"某视距下每秒扫描位置数 / 单次扫描耗时"的数字；调整后耗时可量化下降。

### P1-6 杂项真缺陷（都小，但都是真 bug）

1. **世界种子泄露**：`ModNetwork.java:50` 把**真实种子**发给每一个客户端，绕过 `/seed` 的权限门控。
   客户端确实需要它才能算幽灵，但应当至少记录在案（这是模组的既定代价），
   或改为"服务端预计算 + 只下发结果"（代价大，不建议）。
2. **登录整表包的服务端卡顿**：`SyncRegionDataPacket` 携带**整张诞生周期表**
   （`ModNetwork.java:123-140`，两个 `long[]`）。老存档上登录那一刻会在主线程序列化整表。
   建议分批（按区块）或压缩成单个交错 `long[]`。
3. **`pendingSections` 换世界不清**（`ClientRenderCache:354-362` 只清缓存不清队列）。
4. **`activeSections` 幻影条目**：`putEntry` 在 put **之后**才 `incrSection`（`:838-841`），
   而 `incrSection/decrSection` 非原子地改两张表（`:888-901`）→ 多余的重编译。
5. **`getPrototypeEffects()` 返回可变内部列表**（`MutationPoolManager:114-116`），
   只有 `BioStabilizerHandler:46` 迭代前拷了一份；`DoomsdayHandler:176`、`TotalStabilityFieldHandler:26` 直接迭代。
   建议返回 `List.copyOf` 或不可变视图。
6. **`syncedEffects` 只增不减**（`MutationPoolManager:70`，只在 `syncRemoval` 里删）
   → 被爆炸/活塞/`/setblock` 移除的基座留下的条目永不清理。
7. **原型机效果不随区块卸载失效**（无 `ChunkEvent.Unload` 处理）
   → 未加载区块里的基座继续保护"不存在的地方"，且被爆炸摧毁的基座的效果会留到重启。
   `[拍板]` 若确认"未加载 = 无保护"是设计意图，请写进文档；否则要补卸载处理。
8. **`totalStabilityRadius` 的 int 溢出**（`MutationPoolManager:214-221`）：
   `generation*(generation+1)/2*penalty` 在 `copies ≈ 20000` 时溢出。
   实际影响有限（`Math.max(PROTOTYPE_RADIUS, lost)` 会把它夹回基础半径 8，不会变成巨大值传给固化循环），
   但 `copies` 从 NBT/命令读入**没有任何 clamp**，建议在读取处夹一个上限（例如 0..`total_stability_max_copies`）。
9. **`FocalDecayWorldData.partialTicks++` 不 `setDirty()`**（`:145-155`）
   → 崩溃最多丢 20 分钟的游戏日进度。
10. **`ThroneRitualData` 的 setter 不 `setDirty()`**（`:138-156`），靠 `tickRitual` 每 tick 兜底
    → 仪式开始瞬间崩溃会丢进度。
11. **`MutationIndex.tagPools` 无淘汰**（`:48`）：每个概念标签一份 `boolean[registry.size()]`
    （约 1064 bool，还好），但用 `MutationIndexBuilder:106`/`GuidedConcept.fallbackCandidates` 的路径会持续加新标签。
12. **tag 重载后服务端效果持有旧池**：`MutationIndexes.invalidate()`（`MutationEventHandler:88`）
    丢弃索引，但已登记的效果仍拿着登记时建的 `ClassifiedPool`（`MutationPoolManager:142`），
    而客户端每次从新索引重建（`ClientRegionData:277`）→ `/reload` 后引导邻域不一致，直到重新登记。
    修法：`invalidate()` 时把 `prototypeEffects` 的 `concept()` 一起重建。
13. **候选体绕过训练上限**：`ModelTrainingHandler:135` 的上限判定带了 `!candidate &&`，
    于是候选观测者不受 `training_max_targets`（默认 64）约束，只受 `candidate_required_points`
    （可配到 10000，副本还会把需求除以增益）限制 → 一件物品的组件里能塞进**上千个 id 字符串**，
    而 `STREAM_CODEC` 每次记录都要重新序列化整份（`ObserverModelData.java:44-65`）。
    建议给候选体一个独立的、更宽松但仍有界的上限（例如 512），并在 tooltip 里显示"已记录 N 项"。
14. **副手训练会把组件写到主手槽**：`ModelTrainingHandler:151-154` 的重同步固定写
    `serverPlayer.getInventory().selected`，而 `held` 取自 `getItemInHand(event.getHand())`
    （`:41`、`:70`）→ 副手训练时客户端看到的是**主手槽被写入了副手那枚模型的组件**，
    真正的副手组件直到下一次整份同步才更新（表现为"进度显示错位"，不是真的复制）。
    修法：按 `event.getHand()` 选槽位再同步（主手用 `selected`，副手用 `Inventory.SLOT_OFFHAND`）。
15. **右键取消可被后续监听器翻转**：NeoForge 的 `PlayerInteractEvent.setCanceled` **不会**重置
    `cancellationResult`，所以另一个 `receiveCanceled=true` 的 HIGHEST 监听器可以在此之后
    把 `FAIL` 改成别的值（甚至取消取消）。那时原版会拿着**它早先抓取的旧 blockstate**
    再跑一遍 `useItemOn` → 双重效果（目标方块 `use` 两次，或顺手放置手里的方块）。
    本模组的顺序是 `setBlock` → 重派发 → `setCanceled(true)` → `setCancellationResult(FAIL)`，
    已经是"最后取消、最后设结果"的最稳写法；**建议在 mixin/事件层记录一条诊断**，
    当发现 `event.getCancellationResult() != FAIL` 时打一行日志，便于在整合包里定位这类冲突。
    另：本模组注册在 HIGHEST，若别的模组**更早**注册 HIGHEST 并取消，本模组会被跳过（这是正确的），
    但"方块已被本模组改写世界"这一事实没有回滚路径 —— 已在 P0-4 一并讨论。
16. **`spawnAfterBreak` 缺失导致方块专属破坏效果丢失**（与 P0-4 同源，单列以便回归）：
    红石矿的额外掉落、sculk 相关行为等依赖它。修 P0-4 时自动解决。

### P1-7 客户端"看得见但摸不到"的残余

- 已按可见目标处理的：方块模型、面剔除、挖掘速度/工具、中键选取。
- **未处理的**：玩家碰撞与射线命中仍按真实方块；方块选择框（黑框）画真实形状；破坏粒子/音效按真实方块。
- 后果：看着是玻璃/半砖却撞上"看不见的完整方块"；选择框与看到的东西不一致；破坏粒子是另一种材质。
  这与本模组"所见即所得"的取向直接冲突。
- 建议：先加 `/focaldecay debug visual` 分别开关"选择框跟随幽灵 / 碰撞跟随幽灵"，`[实机]` 主观评估。
  混合碰撞（半砖幽灵 vs 完整真实方块）几乎不可能可靠，建议**只做选择框**，并把碰撞不一致写进手册。
- 无论做与不做，都要在手册与 `PROXYAI.md` §7 写明这条边界。

---

## P2 — 架构与玩法

### P2-1 把"稳定"从"一次性写入世界"升级为"规则层" `[拍板]`

- 动机：P0-1 的根因是"保护 = 把世界改成想要的样子"（P0-2 是同一个根因的另一个症状，已修）。
  但保护本质是一个**判定**，
  可以只在解析时叠加，不改世界：
  ```
  visible(pos) = protection.hard() ? anchored(pos, anchorPeriod) : resolve(...)
  ```
- 收益：放置代价从 27 万次 `setBlock` 降到 0；保护与渲染天然同源；取走模型时"世界恢复到当前失焦态"变成自动行为
  （不再需要"固化"这个概念）。
- 代价：`anchored` 怎么表达？走"锚定 period"最省（一个 long、零存储），但要求锚定范围内的**真实世界**从锚定那刻起不变
  —— 而玩家会在基地里建造，所以要"锚定 period + 出生周期"共同解析，复杂度是**转移**而不是消失。
  **这是本清单最需要先讨论的一项。**

### P2-2 资源经济：没有价值结构，矿石可以当目标

- 现状：局部池是语义邻域（好），但 `wild_chance = 0.25` 会以 25% 切到大池，
  而大池是 `wild_auto_include` 自动纳入的 **598 个方块**，**其中包含矿物**
  （`mutation_pool/ore` 里有钻石矿、下界合金块、各种矿物块），且抽中即**等概率**。
- 叠加**累积转换**（回退扫描）后：时间越长越多方块落在"曾被抽中"的状态并保持，
  阶段 3 概率 0.9 时几乎全图重排 → 矿脉价值、生物群系价值、探索价值被抹平。
- 三件可独立做的小事：
  1. **矿物出池**：让矿物"可做源、不做目标" —— 这正是 `mutation_source_extra` 的语义，一行标签改动。
     先做这一条，再玩一局看感受。
  2. **价值分带 + 权重**：`MutationIndex` 目前是均匀索引；构建期可给候选取权重（数据包可选，缺省全 1），
     用累积权重 + 同一个确定性随机步抽取。
  3. **阶段 3 从"概率更高"改为"结构更乱"**（见 P2-4）。
- 验收：`mutation audit` 增加"价值分布"统计；`[实机]` 1 小时后钻石类产出主要来自采矿而非等石头变。

### P2-3 概念系统：从"固定标签集"到"可推理的语义结构" `[拍板]`

- 现状：`focal_decay:concept/*`（wood/ore/stone/glass/terracotta/wool）是**策展的固定标签**，
  训练只是"指认其中之一"，`q = |训练集 ∩ 概念邻域| / |概念邻域|`。玩家能"学会"的只有这 6~7 个概念。
- 建议方向：
  - 概念邻域改为**训练样本的标签闭包**（样本共享的有效标签的并/交），而不是查策展表；
  - 让 q 的产出体现在**预测**上：模型 tooltip / 手册给出"该概念内成员会漂向哪些池"的预测列表，
    且预测**多数情况下成立**（它本来就是确定性函数）。玩家由此获得"利用失焦造资源"的能力。
  - 与 P2-2 配合：高价值池只能靠精确预测拿到，形成正反馈
    （冒险 → 观察 → 训练 → 预测 → 定向生产 → 更深层失焦）。
- 验收：自测"训练 n 个样本后，预测列表与实际解析结果的一致率 ≥ X%"。

### P2-4 阶段设计：用"漂移的维度"替代"概率的斜率"

- 现状 0.01 / 0.3 / 0.9 + 100/60/40：后期不是"更危险"，而是"更快地变成均匀随机资源池"，
  破坏力没变、产出可能上升 —— 越接近末日越富，违背直觉。
- 建议（可与现实现并存，逐条加）：
  - 阶段 2：**属性漂移** —— 楼梯的 facing、原木的 axis、活板门的 open 也参与漂移。
  - 阶段 3：**因果漂移** —— 允许跨越形态类（楼梯小概率变完整方块）、跨越语义池边界，
    或加入有传播半径与次数上限的"连锁失焦"。
  - 让"危险"来自**结构失效**（承重、台阶、容器）而不是"资源变多"。

### P2-5 天气与实体突变接入核心循环 `[拍板]`

- 现状：天气是"每周期掷骰换天气、持续 60~360 秒"，与其它系统**零耦合**；实体突变与方块突变也是三套独立骰子。
- 建议：天气影响**池的取用**（雨 → 大池权重上升；雷暴 → 敌对实体池权重上升 + 方块突变乘数；晴 → 植物类语义池更稳定）；
  实体突变读同一份"突变强度"标量。这样天气从氛围变成可读的规则。
- 验收：手册能用一句话说清"下雨会怎样"，且这句话在游戏里可被验证。

---

## P3 — 工程卫生与文档

### P3-1 文档与代码不一致
| 位置 | 文档 | 代码 |
|---|---|---|
| `PROXYAI.md` §6.5 / `PROGRESS.md` §9 里程碑 7 | `semantic_lock_stage3_strength` 默认 0.5 | `FocalDecayConfig.java:175` 默认 **1.0** |
| `PROXYAI.md` §3.5.1 | 仪式时长"默认 3~5 分钟" | `FocalDecayConfig.java:191` 默认 **30 秒** |
| `PROXYAI.md` §7.3 末段 | 面剔除"已由 `SectionCompilerMixin` 一处覆盖" | 已由 `BlockShouldRenderFaceMixin` 补第二处（§13.8 已修正，正文未改） |
| `PROXYAI.md` §5.0 表 | "存储时钟用于出生周期" | 出生周期已改显示时钟（下文已修正，表本身未更新） |
| `PROXYAI.md` §11 / §12 / §13 | 仍称 `MutationPoolManager` 存池；标识符表缺 `mutation_pool/*`、`shape_class/*`、`concept/*`、`SyncPrototypePacket`、`SyncClientViewPacket`、`SyncMutationSettingsPacket` | 以代码为准 |
| `ClientRenderCache:546-547` 注释 | `applyBirthPeriod`"只清理这个位置，不做整表扫描" | `dropRegionEntries:563-582` **总是**遍历整个 `targetCache`（每个条目一次 `getBlockState`），且 `ClientRegionData:157` 每个包都整份拷贝诞生表 |
| `ClientRenderCache:260-261` 注释 | 客户端引导模型"每个区块节解析一次" | 实际**逐方块**调用（`:230,:318`） |
| `FocalDecayConfig.java:323` | `surface_update_frequency` 描述为 frame | 代码按 **tick** 走（`ClientRenderEvents.java:31-33`） |
| `README.md` | 未提"候选观测者不可复制""副本训练增益递减" | 属于核心平衡规则，建议补一句 |

### P3-2 `PROGRESS.md` 结构问题
两个章节**同时编号为 `§13.15`**（"观测者核心 GUI"与"王座激活 OBSR-EX"）。后续引用会歧义，
建议后者改为 `§13.18` 并在索引处注明。

### P3-3 Mixin 脆弱性备查
`focal_decay.mixins.json` 是 `"required": true` + `defaultRequire: 1` → **注入失败即启动崩溃**（不会静默降级，
这个取舍是对的）。脆弱点集中在：
`SectionCompilerMixin`（单匹配 `RenderChunkRegion.getBlockState`，被其他模组同样 redirect 就会冲突）、
`BlockShouldRenderFaceMixin`（`Block.shouldRenderFace` 是极热且常被 mixin 的方法）、
两个 `@Accessor`（`RenderChunkRegionAccessor:13` 的 `level`、`LevelRendererAccessor:14` 的 `viewArea`，改名字段即崩）。
建议在 `PROGRESS.md` 里维护一份"升级 MC 版本时要逐个复验的注入点清单"。

---

## P2-6 玩法平衡

> 这几项不是缺陷，是**平衡与体感**问题。全部可单独评估、可回滚，且都改在配置或标签层。
> 需要实机体感数据才能定数值，所以排在 P0/P1 之后。
>
> **设计意图见 [`DESIGN.md`](DESIGN.md) §13；每一项对应的开放问题在 §14**
> （编号映射：P2-6a→§14.4、P2-6c→§14.6、P2-6d→§14.5、P2-6e/f→§14.3、P2-6g→§14.7、
> P2-6h→§14.8、P2-6b→§14.10）。设计一旦拍板，结论写回 §13/§14，本条从 BACKLOG 删除。

### P2-6a 阶段 1 没有"逐渐"的体感（我认为这是最伤恐怖感的一条）

- 阶段 1 概率 0.01、周期 100 tick、回扫上限 128。越过 128 周期（约 21 分钟）后
  **每个被扫到的方块都必然已命中过至少一次**；按 `0.99^N < 1%` 算，**约 38 分钟时全图 99% 已非原样**。
  而阶段 1 持续到第 3 天（约 72 分钟）→ **玩家还没进阶段 2，世界已经面目全非**，
  "1 → 2 → 3 逐渐恶化"在画面上看不出来（只有重新洗牌的频率不同）。
- 恐怖来自"你认得的东西变了"。一旦一切都变了，世界退化成噪点，玩家什么都不怕了。
- 建议：**保留累积语义**，改为**回扫窗口随阶段增长**（例如阶段 1 = 8 周期、阶段 2 = 32、阶段 3 = 128）；
  阶段 1 同时把 `wild_chance` 压到接近 0（只用语义邻近池），让"荒谬"成为阶段 3 的专利。
  改动落在 `MutationHelper.resolveInternal` 的 `cap` 与 `MutationSettings` 的一个新字段。
- 验收：`[实机]` 在阶段 1 玩 1 小时，肉眼仍能认出地貌与自己的建筑。

### P2-6b 早期游戏的门槛可能过高

- `anchor_prototype` 配方 = 8 铁块 + 4 末影之眼 + 红石粉，**末影之眼需要下界**。
  于是玩家的第一个稳定装置在"到达下界之后"，而通往那里的路本身被随机化（矿物会自己变走）。
  前 2~4 小时可能是"世界在 38 分钟内烂完，我什么也做不了"。
- 两个方向差别很大，需要拍板：
  1. **稀缺**：开局给一枚一次性的微型锚（例如 3×3×3、限时），让玩家先体验"稳定是可能的"；
  2. **廉价**：把配方降到铁锭 + 紫水晶 + 红石（去掉末影之眼），末影之眼留给更高级型号。
- 我倾向 1（资源稀缺时每次使用才是决策），但这条依赖实机数据。

### P2-6c 让"观察"产出可行动的预测（我认为最值钱的一条）

- 现状：玩家只有"建造"和"破坏"两个动词，"发现规律"没有任何机制在**产出**它。
  但代码已经具备 90%：确定性 `resolve`、完整的池结构、`GuidedBias` 的 `q`、概念邻域。
  **缺的不是机制，是产品。**
- 建议：手持训练中/候选模型右键方块时，除 +1 进度外**直接给出预测**
  （"这个概念下它下一步最可能成为 X，当前置信度 q"）。预测本来就该是真的——它是确定性函数的输出。
  于是模型从"收集进度条"变成"我造出来的分类器"，观察才有产出，训练才有意义。
- 与 P2-3 是同一条线，可以先做这一半（信息），再决定要不要做概念闭包那一半（表达力）。

### P2-6d 矿物不应该成为失焦的目标

- 大池自动纳入 598 个方块、**含矿物**、等概率，叠加累积转换 → 采矿这个动词失去意义
  （"去哪里挖"不再成立，探索/矿脉/群系价值一起归零）。
- 最小改动：矿物进 `mutation_source_extra`（**可做源、不做目标**），一行标签。
- 更好的版本（P2-2 的价值分带）：**失焦不该产生价值，只该产生错位**。
  石头变泥土对（都是便宜地表材料），石头变钻石矿错（语义不相邻）——
  好的失焦应该让人觉得"这不对"，而不是"我赚了"。定向生产才是唯一的致富路径。

### P2-6e "生物稳定 / 完全稳定"机制重合

- 语义锁定（点名保护）vs 生物稳定（全场保护）是很好的区分（精确 vs 粗暴）；
  但**生物稳定 vs 完全稳定机制完全一样**，只是代价与强度不同。
- 建议合并为一个"全域稳定"，让区别来自**成本结构**：生物稳定持续消耗（附近生物掉血，可长期用但需维持电池）、
  完全稳定一次性获得（王座仪式，不可复制）。这样"养牛当电池"从 exploit 变成明确玩法，
  主题上也干净：**用生命维持稳定**。

### P2-6f 稳定不该是"免费且永久"的

- 现在玩家会自然滑向"把农场/村民/仓库/机器全塞进 32 格立方体，然后不出去"——
  这不是玩家的问题，是给了一个无代价的永久安全泡泡。
- 建议：**侵蚀的对象是"稳定性"，不是"世界"**（必须永远保留一种保证稳定的手段，否则不可玩）。
  阶段推进不只是概率变高，而是规则本身被侵蚀：阶段 2 起属性也漂移（楼梯朝向、原木轴向、活板门开关），
  阶段 3 起波及方块实体、`mutation_immune` 方块、**容器内容物**。
  于是"你的基础设施开始不可信"，玩家会自己得出"我必须结束它"，而不是"我再扩一圈基地"。
- 与 P2-4 是同一件事的两个说法。

### P2-6g 七枚碎片目前是纪念品

- 七个一次性里程碑（多数是被动成就 + 两个 RNG），拿到就是收藏品 + 候选体 +10 点，
  **没有一个进入核心循环**。讽刺的是唯一和循环有关的（硫铜结晶，铜块突变掉落）恰好是唯一纯 RNG 的。
- 建议：保持一次性，但**给一个只能用一次的明确机制效果**，用掉就没了。例：
  碎片·完备语义 → 把某个位置**永久**标记为不失焦（一个永远无法撤销的"洞"）。
  一次性 + 不可逆 + 有实效 = 每枚碎片都是一个**决定**。

### P2-6h 胜利条件可以绕开整个世界

- 候选体练满 = 100 点（右键唯一目标 +1、碎片 +10）→ 理论上可以**不下界、不去末地**，
  在出生点附近右键 100 个不同方块把主线走完；王座、龙、资源经济全成了可选内容。
- 建议：让完成候选体**必须**经过多个维度（例如要求包含只在末地/下界出现的方块），
  或必须用到王座仪式的产出。不是为了拖长，是为了让玩家被迫经历你设计的世界层次。

### P2-6i 客户端时钟校准密度（技术问题，但它是玩法的地基）

- 客户端每 **20 tick** 才被校一次，中间最多差 20 tick（一个周期）；交互时虽有 `SyncClientViewPacket`
  回报，但**站立不动时**客户端可能显示周期 N+1 而服务端按 N 解析。
- 整个设计的可信度建立在"你看到的就是真的"上。一旦玩家开始怀疑预览，玩法就不成立了。
- 建议把校准做密（例如 5 tick），改动便宜，保住的是地基。

---

## 建议的推进顺序

1. ~~**P0-2**（改动极小，直接消除一个可见失配）~~ —— **已完成 2026-09-25**，见 `progress/2026Q4.md`
2. **P0-3 掉落物池 + 审计断言**（改动小、收益明确、可自测）
3. **P0-6 + P0-5 + P0-7**（三个客户端/一致性缺陷，同一个"客户端镜像生命周期"主题，建议一次改完）
4. **P1-1b + P1-1c**（实体突变的范围过滤与 discard 顺序：都是几行改动，但直接决定"村民/宠物/龙还在不在"）
5. **P0-4 挖掘生命周期**（技术收益最大，先做方案评审）
6. **P0-8 实机矩阵**（把上面几项的验证一起做掉，含 P0-1 需要的"区块全加载"条件下的耗时复测）
7. **P1-1 / P1-3**（同一个函数，一次改完）
8. **P2-6c 观察产出预测**（最值钱的玩法改动；不依赖前面任何一项，可并行）
9. **P2-6a 回扫窗口随阶段增长** + **P2-6d 矿物出池**（都是小改动、大体感，改完玩一局再定后续数值）
10. **P1-2 出生周期语义拍板** → **P2-1 规则层保护**（同一个设计问题的两端；P2-1 一旦成立，P0-1 直接消失）
11. **P2-6b / P2-6e / P2-6f / P2-6g / P2-6h / P2-6i**（需要实机与拍板，逐项评估）

---

## 不建议改的（刻意设计，别"顺手修"）

- **右键交互真的转换方块再重派发**（`InteractionHandler.onRightClickBlock`）：这是"所见即所得"取向的落点，
  取消事件 + 显式 `setCancellationResult(FAIL)` + `ThreadLocal` 防递归都已正确处理。
  要做的是补 `[实机]` 兼容性回归与一条"结果被翻转"的诊断日志（P1-6 第 15 条），不是拆掉机制。
- **累积转换**（"变了就变了"，未抽中保留上次材质）：世界不可逆崩坏的语义核心。
  要调的是**回扫窗口长度**（P2-6a），不是这个语义。
- **客户端在收到 `MutationSettings` 前不画幽灵**：把静默错误降级为可见缺失，方向正确。
- **`wild_auto_include` 默认开**：保证数据包写漏时池子不会变小。要收紧请改配置，别改默认值。
- **调试时钟不落盘**：刻意设计，避免"忘了 reset"的假 bug。
- **`shape_class` 硬门控 + 双格方块不登记**：几何一致性的结构保证（门/床的配对突变是独立的一块工作）。
- **安全区（基地当避难所）本身**：要加的是**代价**（维护、消耗、会被侵蚀），不是取消它。
