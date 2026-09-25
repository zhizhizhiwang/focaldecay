# 挖掘路径重构方案（P0-4 评审）

> **状态：已拍板并实施（2026-09-25）。** 本文保留为**决策记录**，不再是待办。
>
> 作者裁定：**选推荐方案（重派发）**；接受"破坏会真的把目标方块写进世界"；
> `BreakData` 的三条附带缺陷一并修；允许注册测试探针方块。
>
> 落地情况：结论进 [`DESIGN.md`](DESIGN.md) §5.2，条目已从 [`BACKLOG.md`](BACKLOG.md) 删除，
> 经过与验收见 [`progress/2026Q4.md`](progress/2026Q4.md) §K。
>
> ⚠️ **本文有一处推测是错的**（已在 §K 记录）：§1 表格里"`RULE_DOBLOCKDROPS` 未检查
> → `doTileDrops=false` 时照样掉东西"经 A/B 证伪——旧实现走的 `Block.getDrops` 内部
> 也会门控该规则。评审阶段的差集表并非全部经过实测。
>
> 背景条目：`BACKLOG.md` §P0-4；相关技术细节：`PITFALLS.md` §2、§6。
> 写于 2026-09-25。

---

## 1. 问题

`InteractionHandler.onBlockBreak` 在 `BlockEvent.BreakEvent` 里 `setCanceled(true)`，
然后**手工复刻**原版 `ServerPlayerGameMode.destroyBlock` 的后续流程：

```
setBlock(AIR) → Block.getDrops → 自建 ItemEntity → getExpDrop → popExperience
→ held.mineBlock（耐久）→ awardStat → causeFoodExhaustion
```

这条路径与"所见即所得"的取向是一致的（掉落按可见目标算），但它是**复制了一部分原版生命周期**。
已核对 NeoForge 补丁后的 `destroyBlock`，差集如下（`PITFALLS.md` §2 有同一张表的简版）：

| 原版步骤 | 现状 | 后果 |
|---|---|---|
| `block.playerWillDestroy(...)` | **缺失** | 依赖它的模组方块状态不更新（蜂巢激怒、成就等） |
| `state.onDestroyedByPlayer(...)` | **缺失** | 拒绝破坏的方块会被强行移除；**水logged 方块的水被一并删掉**（水源消失） |
| `block.destroy(level,pos,state)` | **缺失** | NeoForge 的方块销毁钩子丢失 |
| `block.playerDestroy(...)` | **缺失** | **自定义方块覆写的掉落逻辑全失效**（容器溢出内容物：箱子里的东西直接没了） |
| `state.spawnAfterBreak(...)` | **缺失** | 方块专属破坏效果丢失（红石矿额外掉落、sculk 等） |
| `CommonHooks.handleBlockDrops` → `BlockDropsEvent` | **缺失** | 其他模组改掉落/取消掉落全部失效 |
| `GameRules.RULE_DOBLOCKDROPS` | **未检查** | `doTileDrops=false` 时照样掉东西（经验仍受门控） |
| `EventHooks.onPlayerDestroyItem(...)` | **缺失** | 工具挖坏瞬间的其他模组钩子收不到事件 |
| `canHarvestBlock(...)` 门控 | 部分 | 原版 `getDrops` 覆盖了绝大多数，模组方块的自定义覆写会被绕过 |
| `BlockEntity` 传入 `getDrops` | 传 `null` | 少数按方块实体算掉落的方块算错 |

**这不是"还差几个补调用"的问题**：每补一个都是在给"手工管线"加一条边，
而原版或别的模组每加一条新边，我们就又落后一次。这个结构本身会持续产生偏差。

### 1.1 一条硬约束（决定了方案空间）

`BlockEvent.BreakEvent` 的公开 API 只有：

```java
public Player getPlayer();
public void setCanceled(boolean);
```

**它不能替换"被破坏的是哪个方块"。** 而 `destroyBlock` 在事件触发之前就已经读过 `blockstate1` 了。
所以任何方案都必须**动世界**（把目标方块放进去），没有"只改一个变量"的第三条路。

---

## 2. 推荐方案：只换方块，让原版管线跑完

思路与右键那条完全一致（`InteractionHandler.onRightClickBlock` 已经在用同一个套路）：

```
BreakEvent(真实方块) 到达
  ↓ 防重入标志已置位？ → 是：什么都不做，让原版继续（这次面对的就是目标方块了）
  ↓ 否：
  ↓   校验 BreakData（位置 / 目标）
  ↓   setBlock(pos, 可见目标, 3)          ← 只改这一件事
  ↓   clear BreakData                     ← 防止重入时重复消费
  ↓   REDISPATCHING = true
  ↓       player.gameMode.destroyBlock(pos)   ← 原版管线对着目标方块跑完整流程
  ↓   REDISPATCHING = false
  ↓   event.setCanceled(true)             ← 这一次（真实方块那次）作废
  ↓   event.setCancellationResult(...)
```

于是这些**全部自动正确**，不用我们写一行：掉落（含 `playerDestroy` 覆写、Fortune/Silk Touch）、
经验、耐久、统计、成就、`BlockDropsEvent`、`spawnAfterBreak`、`doTileDrops`、水回收、
`onDestroyedByPlayer` 的否决、以及以后任何新加的原版行为。

### 2.1 为什么不是"破坏时先放目标方块、然后不取消事件"

看起来更简单：在 `BreakEvent` 里 `setBlock(target)` 然后**不取消**事件。
但原版已经抓着**旧** `blockstate1` 往下走了（`playerWillDestroy`/`onDestroyedByPlayer`/
`playerDestroy` 全部用那个旧值），我们改了世界也改不了它手里那份引用——
结果会是"世界里的方块变了，但掉落按旧方块算"，即另一种不一致。所以必须重派发。

### 2.2 防重入

重派发会让 `destroyBlock` 再次触发 `BreakEvent`（这次 state 是目标方块）。
用与右键相同的 `ThreadLocal` 旁路标志：置位期间本处理器直接返回，原版管线继续。
`BreakData` 在重派发**之前**清掉，避免第二次进入时重复消费同一份锁定数据。

### 2.3 已知风险与缓解

| 风险 | 评估 | 缓解 |
|---|---|---|
| 目标方块被**真的写进世界**，玩家中途放弃挖掘就留着一个变过的方块 | **这是既定取向**（右键路径已如此：右键会真的转换）。而且失败方向是"世界变得和看到的一样"，不是数据丢失 | 无需缓解；写进手册 |
| 第二次 `BreakEvent` 触发时，**别的模组**看到的是目标方块 | 这正是我们想要的——它们本来就该按玩家破坏的那个方块响应 | 无需缓解 |
| 有模组**更早**注册 HIGHEST 并在第一次就取消 | 我们被跳过（正确），方块保持原样。**但若我们已经 `setBlock` 过**（例如更早的一次尝试），世界已改 | 无法回滚。属于"取向的代价"，记录在案 |
| `player.gameMode.destroyBlock` 之后，原版 `handleBlockBreakAction` 还会继续跑它自己的收尾（`destroyBlockProgress`、`isDestroyingBlock = false` 等） | 需要核对：这些大多是幂等的 / 下次交互会覆盖 | **实机验证项**（见 §5） |
| 破坏速度门控按**真实**方块算，而玩家看到的硬度是目标的 | 现状已如此（客户端 `MultiPlayerGameModeMixin` 把进度计算改为读可见目标，所以**两端都按目标**） | 已解决 |
| 考古/特殊方块依赖 `destroyBlock` 之外的上下文 | 未评估 | 实机矩阵里加一条（可疑方块清单） |

### 2.4 这个方案**不**解决的事

- **`BreakData` 的既有缺陷**（`BACKLOG.md` P0-4 末尾列的三条）：只校验 `pos` 不校验维度、
  `copyOnDeath()` 让锁定跨死亡存活、`getPeriodIndex()` 记了但从未读取。
  这三条是独立的小修，建议**一起做**（都在同一个函数里）。
- **右键路径的兼容性回归**：与 P0-4 同源（都是"取消事件 + 重派发"），
  但右键那条已经跑了很久，本次不动它。

---

## 3. 退路：补齐差集（不重构）

如果不想引入重派发，最小改动是把上表那八项**逐条补上**：

```
+ block.playerWillDestroy(...)           并采用它返回的状态
+ state.onDestroyedByPlayer(...)         为 false 则放弃破坏（并保留方块）
+ block.destroy(...)
+ block.playerDestroy(...)               传入真实 BlockEntity
+ state.spawnAfterBreak(...)
+ GameRules.RULE_DOBLOCKDROPS 门控自建掉落
+ EventHooks.onPlayerDestroyItem(...)
+ CommonHooks.handleBlockDrops(...)      让 BlockDropsEvent 生效
```

**代价**：这是"继续维护一条手工管线"。它会随版本升级与其他模组的加入不断漂移，
而且很难验证"是否补全了"——因为差集本身要靠人工比对补丁。
**适用条件**：如果实机发现重派发引入了不可接受的问题（见 §2.3），退化到这里。

---

## 4. 两个方案对比

| 维度 | 推荐方案（重派发） | 退路（补齐差集） |
|---|---|---|
| 与原版的一致性 | **结构性一致**（跑的就是原版） | 需要持续人工比对 |
| 未来版本升级 | 自动跟上 | 每次升级都要重新比对补丁 |
| 与其他模组的兼容 | 它们看到的是目标方块（正确） | **它们的 `BlockDropsEvent` 等钩子收不到** |
| 改动量 | 中等（一个函数重写 + 重入标志 + 测试） | 小（八处补调用） |
| 主要风险 | 重派发后原版收尾的幂等性未验证 | 差集永远补不全 |
| 是否动"既有实现逻辑" | **是** | 是（但是加法） |

---

## 5. 验收标准（无论选哪个）

**自测**（新增 `[break]`，进 `/focaldecay mutation selftest`）：

1. 断言"原版 `destroyBlock` / `playerDestroy` 对**目标**方块被调用"——
   用一个人造方块做探针最直接：注册一个测试方块，让它的 `playerDestroy` 里记一个计数器。
   （若不愿注册测试方块，可退化为断言 `BlockDropsEvent` 被触发。）
2. 断言 `doTileDrops=false` 时**不掉落**（现状会掉）。
3. 断言水logged 方块被破坏后**水还在**（现状会连水一起删）。
4. 断言 `playerWillDestroy` 被调用（同样用探针方块）。

**实机矩阵**（并入 `P0-8`）：

- 装有机器的模组方块（`playerDestroy` 覆写）破坏后行为正确
- 潜影盒 / 箱子破坏后**内容物正常溢出**
- 水logged 方块破坏后水源仍在
- `doTileDrops=false` 时不掉落
- 红石矿破坏有额外掉落（`spawnAfterBreak`）
- 创造模式破坏行为不变（本就跳过转换）
- 中途放弃挖掘：方块留在世界里的状态符合预期

**A/B 硬要求**：新增的每条断言都要**故意把实现改回旧行为**确认它会 FAIL
（本项目已经三次由 A/B 抓出坏断言，见 `progress/2026Q4.md` §C/§D/§G）。

---

## 6. 需要作者拍板的点

1. **选推荐方案还是退路？** 我推荐重派发：它把"原版一致性"变成结构性的，
   而不是一件需要持续维护的事。退路的差集是**八项**，而未来只会更多。
2. **是否接受"破坏会真的把目标方块写进世界"？**
   右键已经是这个语义了，我倾向接受并写进手册——但这是取向，需要你确认。
3. **`BreakData` 的三条附带缺陷是否一并修？** 建议一起
   （维度校验、`copyOnDeath`、未读取的 `getPeriodIndex`），因为它们都在同一个函数里。
4. **是否需要注册一个测试方块？** 它能让 `[break]` 的断言直接、可回归；
   代价是给模组加一个仅用于测试的方块注册项（可以做成不进创造栏、无配方）。

---

## 7. 我会改变推荐的信号

- 实机发现重派发之后**方块破坏出现重复音效/粒子**，或原版收尾不幂等导致状态错乱
  → 退化到退路，并把发现写进 `PITFALLS.md`。
- 发现"更早注册 HIGHEST 的模组取消后，我们的 `setBlock` 已经改过世界"在真实整合包里频繁出现
  → 需要重新考虑"是否应该在 `BreakEvent` 之前（`LeftClickBlock` 时）就确定并写入目标方块"，
  那是另一个方案，代价是"玩家中途放弃挖掘也会留下变过的方块"。
