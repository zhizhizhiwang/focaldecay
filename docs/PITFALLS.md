# PITFALLS.md — 技术细节、踩坑与约定

> 本文件装**可复用的技术事实**：环境、API 语义、易碎点、约定。
> 不装玩法设计与待办（那些在 `DESIGN.md` / `BACKLOG.md`）。
>
> 这里的每一条都对应 `docs/progress/` 里一次真实的崩溃、返工或误判。
> 想看"当时到底发生了什么"，去进度里搜现象词；本文件只留结论与做法。
>
> 最后更新：2026-09-25

---

## 1. 构建与运行环境

### 1.1 本机跑客户端必须先设音频后端
```powershell
$env:ALSOFT_DRIVERS = 'null'
.\gradlew.bat runClient
```
**不设会卡死在 OpenAL 的 `alcResetDeviceSOFT`（HRTF 初始化）**，表现为"资源加载到 88% 无响应"，
强杀后 Gradle 报退出码 `-805306369`（`0xCFFFFFFF`）——**那个码只是强杀的产物，不是错误信息**。

根因在 `com.mojang.blaze3d.audio.Library`：只要音频驱动声明支持 `ALC_SOFT_HRTF`，
MC **必定**调用 `alcResetDeviceSOFT`，即使 `options.txt` 里 `directionalAudio:false` 也一样。
属音频驱动层问题，与本模组无关（已用三组对照实验排除：带依赖 / 摘掉依赖 / 摘掉 mixin 配置，卡死点相同）。
代价是没有声音（本项目 `soundCategory_master:0.0`，本来也没在听）。

### 1.2 卡死怎么定位
```powershell
jps -l                      # 找 net.neoforged.devlaunch.Main 的 pid
jstack <pid>                # 直接看 "Render thread" 的栈
```
`jcmd` 附加会被系统拒绝，**`jstack` 可用**。

### 1.3 客户端运行期间不要重新编译
`build/classes/java/main` 就是开发客户端的 classpath。编译之后**已加载的类不会更新、没加载的类会换成新的**，
混出来的状态最容易撞进第三方模组的异常路径（实测：在 JEI 配方界面退出世界即崩于
`RecipeGuiLayouts: Recipe crashed: IllegalStateException: Jei Client Configs have not been created yet`）。

**流程固定为：关客户端 → 编译 → 重开。**

### 1.4 Shell、编码与行尾
- **看构建产物要按时间排（`ls -lat`），不要按名字**（2026-09-29 新增）。
  版本号不是定长字符串：`focal_decay-1.0.10.jar` 在字典序里排在 `focal_decay-1.0.6.jar` **前面**，
  于是 `ls build/libs | tail -1` 会永远显示旧的那一个——本次据此误判了一次"构建没产出 jar"，
  又跑了一遍构建去查一个不存在的问题。
  判据：`ls -lat build/libs | head`，或者直接
  `test -f "build/libs/focal_decay-$(grep ^mod_version gradle.properties | cut -d= -f2).jar" && echo OK`。
- **日志字符串一律 ASCII**。中文写进日志会按 GBK 落盘、按 UTF-8 读就成了乱码，
  而乱码会掩盖关键线索（真实案例：乱码掩盖了 `creative=true` 这个决定性信息）。
- 历史文件（如 `.gitignore`）是 GBK，改它们要显式用 cp936 读写，
  否则一次"顺手格式化"就会把整个文件变成乱码（已经发生过）。
- **shell 首选 Git Bash**（bash 5.3+，`LANG=C.UTF-8`，路径形如 `/c/Users/...`）。
  写文件用 `cat <<'EOF' > file`（**定界符必须加引号**，否则 shell 会展开内容里的
  反引号与 `$`，把文档/代码写坏）；精确替换用 `python - file <<'EOF'`。
  注意**嵌套 heredoc 的定界符不能重名**，否则外层会被内层提前结束
  （本次踩过：外层与内层都叫同一个名字，python 报 `unterminated triple-quoted string`）。
- **行尾由 `.gitattributes` 统一决定**（2026-09-25）：文本扩展名白名单一律 `eol=lf`；
  `gradlew.bat` 特意 `eol=crlf`（LF 会让 cmd 解析出错）；`.png`/`.nbt`/`.jar` 等显式 `-text`。
  **不要用 `* text=auto`**：它靠"前 8000 字节有没有 NUL"猜，而 `.nbt`
  是"看着像文本、其实是二进制"，猜错会在行尾转换里改坏文件。
- **`.gitattributes` 的改动必须先 `git add` 才生效**：属性是从**索引/HEAD** 的内容读的，
  工作区里未暂存的版本不参与解析。本次在这个坑上花了半小时——
  写完属性文件后 `git check-attr` 全是 `unspecified`，原因就是它还躺在工作区没进索引。
- **有待提交改动时绝对不要跑 `git checkout -- .`**：它会用 HEAD 覆盖你**未暂存**的工作。
  本次刚写好的 `.gitattributes` 就是这样被冲掉的。
  要丢弃改动请显式指定路径。
- **绝不要写 `open(p, 'w').write(open(p).read())` 这种"读回来再写回去"的一行式**（2026-09-26 踩到，
  **后果最严重的一次**）。Python **从左到右求值**：先执行 `open(p, 'w')`——那一步**当场把文件截断为 0**
  ——然后才去求值 `open(p).read()`，读到的必然是空字符串，于是把空写回。
  实测把 `docs/VERIFY-device-matrix.md`（620 行）清成 0 字节，
  而且因为当时用 `git add -A` 且**没有看 `--stat`**，空文件被直接提交，
  直到作者问"你是不是不小心把 VERIFY-device-matrix.md 删了"才发现。
  **正确写法**（读→关→改→写，四步分开）：
  ```python
  with io.open(p, encoding='utf-8') as fh:
      s = fh.read()                      # 先完整读出来，句柄关闭
  s = s.replace(old, new)
  with io.open(p, 'w', encoding='utf-8', newline='') as fh:
      fh.write(s)
  ```
  两条防身规矩：
  1. **提交前永远看一眼 `git diff --cached --stat`**。那一次的输出是
     `1 file changed, 621 deletions(-)`——一眼就能看出是删了整个文件，
     而我只看了 `git status --short`（它只显示 `M`，看不出删了多少行）。
  2. 脚本改写**受版本控制的文件**时，改完立刻回读并断言关键锚点仍在
     （本会话后期已经这么做了，但出事那次恰好是唯一一个没做的）。
  教训：**"读回来再写回去"这类操作必须先确认读到的是真内容**，
  而"先截断后读"恰好让它永远读到空的——失败得静默且彻底。

- **`printf '...' >> file` 往没有末尾换行的文件追加，会把两行粘成一行**（2026-09-26 踩到）。
  本项目的 `tools/devtest-datapack/**/*.mcfunction` 原先**末尾都没有换行符**
  （`tail -c 1` 是 `s` 不是 `
`），所以
  `printf 'schedule function devtest:stop 8s
' >> load.mcfunction`
  得到的是 `...stop 60sschedule function devtest:stop 8s` —— 一行无法解析的命令，
  整个函数加载失败：`Failed to load function devtest:load`，
  于是**所有** `schedule` 都不生效、devtest 自测静默不跑（表现为"跑完了但没有任何 `[selftest]` 输出"）。
  两个修法任选：追加前先 `printf '
' >> file`，或者用 `cat >> file <<'EOF'`；
  **并且给这类脚本文件补上末尾换行**（已经在 2026-09-26 补齐了三份副本共 20 个文件）。
  教训与"管道掩盖退出码"同类：**这类静默失败不会报错，只会让后续步骤不执行**。

- **不要用 PowerShell 的 `Set-Content -Encoding UTF8` / `Out-File -Encoding UTF8` 写源码**：
  它的 `UTF8` 会**写入 BOM**（字节 `EF BB BF`），javac 直接报
  `错误: 非法字符: U+FEFF`。必须用 PowerShell 时走
  `[IO.File]::WriteAllText($p, $text, (New-Object Text.UTF8Encoding($false)))`。
  排查：读文件前三个字节，是 `239,187,191` 就是 BOM。
  （`.gitattributes` 与 `.editorconfig` 都管不了它——BOM 是文件内容的一部分。）
- **不要把 gradle 的输出接进管道后再用 `&&` 判成败**（2026-09-25 踩到，危险度高）。
  `./gradlew.bat compileJava -q 2>&1 | tail -10 && echo "COMPILE OK"` 里的 `&&`
  判的是 **`tail` 的退出码**，而 `tail` 永远成功——于是**编译失败也照样打印 "COMPILE OK"**。
  这个坑真实发生过：一次编译错误被掩盖了四轮工具调用，期间我一直在读**旧的 `.class`**、
  对着"为什么新加的断言没生效"查错方向，最后靠比对 `.class` 与 `.java` 的 mtime 才发现
  根本没编译成功。症状（新断言不出现、日志顺序与代码不符）全部指向错误的方向。
  正确写法（任选）：
  ```bash
  ./gradlew.bat compileJava >/dev/null 2>&1; echo "exit=$?"   # 直接看退出码
  set -o pipefail && ./gradlew.bat compileJava 2>&1 | tail -5  # 让管道整体返回失败
  ./gradlew.bat compileJava 2>&1 | tail -5                     # 只看输出，后面不接 &&
  ```
  **通用规则**：任何"用管道截断输出"的地方都不要再用 `&&` 判成败。
  同理，`grep -c` 在无匹配时返回 **1**——把它接在 `&&` 后面会让"没有 FAIL 行"
  变成"命令失败"，也踩过一次。
  **排查手法**：怀疑"代码改了但没生效"时，先比对
  `build/classes/.../X.class` 与 `src/.../X.java` 的 mtime，再看 `javap -p` 里有没有新方法名。
  这一步比读日志快得多，而且能直接排除"根本没编译"这一类。

---

## 2. 查源码与 API 的姿势

```powershell
# 签名
javap -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar <类名>
# 源码（**优先**：能确认行为而不只是签名）
#   build/moddev/artifacts/neoforge-21.1.248-sources.jar
#   jar xf <sources.jar> net/minecraft/server/level/ServerPlayerGameMode.java
# 第三方模组源码也在 gradle 缓存里（JEI 的 mezz/jei/library/... 是实现，不只是 API）
#   ~/.gradle/caches/modules-2/files-2.1/.../jei-*-sources.jar
```

> **通用教训（用一次连修四轮换来的）**：引擎内置量/API 的语义——取值域、是否每帧被覆写、
> 单位、是否回绕——**必须读源码确认**。那次后处理动画 bug 的前三轮都是凭命名推测
> `Time` 和 `getRealtimeDeltaTicks()` 的含义，两次关键突破都来自用户的实际观察而非代码推理。

几个**已经确认过的**语义陷阱：

| API | 陷阱 |
|---|---|
| `PostChain` 的 `Time` | **内置且每帧被覆写**，每秒（20 tick）硬回绕到 `[0,1)`。写 `setUniform("Time", …)` 是无效代码。要做长于 1 秒的平滑周期动画**必须自建**不回绕的 uniform（本项目用 `TotalTime`） |
| `getRealtimeDeltaTicks()` | 单位是 **tick 不是秒**（内部 `/msPerTick`，60fps 下每秒累加 20）。当秒用会让所有周期快 20 倍 |
| `Block.asItem()` | 无 `BlockItem` 时返回 `Items.AIR`。**用它构造 `ItemStack` 会得到空栈**，而空栈物品实体在下一 tick 被 `discard()` → 静默删物品。**原版里就有这种方块**：`minecraft:frosted_ice`（霜冰）。要用方块池当物品池时，必须在构建期过滤掉 `asItem()==AIR` 的成员 |
| `ItemEntity.setItem` | 本身不 discard；删除发生在 `tick()` |
| `canOcclude()` | 只是"有能力遮挡"的开关。**雪片、半砖都是 true 却只挡住面的一部分**，拿它当"被完全遮挡"会误判 |
| `isSolidRender()` | 要求**碰撞形状填满整格**，雪片同样为 false → 一刀切太多 |
| 判断"是否完全遮住某个面" | 正解是 `state.getFaceOcclusionShape(level,pos,face.getOpposite())` 的包围盒是否覆盖整面（原版 `Block.shouldRenderFace` 的口径） |
| `BlockEvent.BreakEvent` 的公开 API | **只有** `getPlayer()` / `setCanceled(boolean)`。它**改不了"被破坏的是哪个方块"**，而原版 `destroyBlock` 在触发事件之前就已经把 `blockstate1` 读进局部变量了。想在破坏路径上换方块，唯一出路是**动世界**（`setBlock` 后再重派发），没有"只改一个变量"的第三条路 |
| `Block.popResource` | **自己门控 `doTileDrops`**。所以"想加一个受规则管住的掉落"就用它，别自己 `new ItemEntity` + `addFreshEntity`（那样绕过规则）。反过来：`Block.getDrops` 内部也走它，所以"手工管线会无视 `doTileDrops`"这个推论是**错的**——除非你真的自建了实体 |
| `BlockBehaviour#spawnAfterBreak` | 原版**只**在 `playerDestroy` 的**默认实现**里调它（`BlockBehaviour#playerDestroy` → `state.spawnAfterBreak(...)`）。方块一旦覆写 `playerDestroy` 且不调 `super`，`spawnAfterBreak` 就**永远不会被调用**——写测试断言它之前先确认这一点（本项目为此浪费了一轮 A/B） |
| `AttachmentType.Builder#copyOnDeath` | 只对**有序列化器**的 attachment 生效（serializer 为 null 时它直接抛 `IllegalStateException`）。所以"锁定跨死亡存活"这个 bug 是**序列化器 + `copyOnDeath` 两条路径一起漏**；要根治就两条一起删，别只删一条 |
| `AttachmentInternals#copyEntityAttachments` | 死亡时**只**复制显式勾了 `copyOnDeath` 的 attachment（`isDeath ? type -> type.copyOnDeath : type -> true`）。这就是上一条的判据来源 |
| `ClassifiedPool.of(tagId, …)` 的 `tagId` | 会被当成 **`ResourceLocation` 解析**，所以只能传合法标签 ID。自测里为了区分缓存而写 `标签 + "@" + 档位` 直接崩成 `ResourceLocationException: Non [a-z0-9/._-] character in path`（2026-09-29，第一次跑就撞上）。要区分变体就在**缓存键**上区分，池本身仍拿基础标签 |
| `BlockBehaviour#useItemOn` 的返回类型 | 是 **`ItemInteractionResult`** 而不是 `InteractionResult`（NeoForge 21.1 起）。照着 `useWithoutItem` 的签名去覆写会编译不过；两者混用时注意 `ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION` 与 `InteractionResult.PASS` 不是同一个东西 |
| `ItemStack#hurtAndBreak` | 有三个重载，`(int, LivingEntity, EquipmentSlot)` 那个仍然可用，是"手上这件工具掉一点耐久"最省事的写法（另外两个要 `ServerLevel` + `Consumer<Item>`） |
| `RightClickBlock.cancellationResult` 默认值 | `InteractionResult.PASS`。而 `ServerPlayerGameMode#useItemOn` 是 `if (event.isCanceled()) return event.getCancellationResult();` |
| `LootTable.getRandomItems` | **不校验参数集**，所以表里写 `generic` 而用 `COMMAND` 参数集建上下文是安全的 |
| `StructureSettings.spawn_overrides` | **必填**（`fieldOf`）。漏了会拒绝加载**整个存档**，空对象 `{}` 即可 |
| `IntProvider` vs `NumberProvider` | 字段名不同：前者 `min_inclusive`/`max_inclusive`，后者 `min`/`max`。JSON 形状一样，写错会让整张表**静默不加载** |
| `ItemStack.saveOptional()` | NeoForge 里返回 **`Tag`** 而不是 `CompoundTag` |
| `StructureProcessor.process` | 方块**真正放置之前**被调用，此时方块实体还不存在；要带 NBT 得**返回带 NBT 的 `StructureBlockInfo`**（原版"箱子自带战利品"就是这个机制） |
| 处理器回调里的坐标 | `relativeBlockInfo.pos()` **已经是世界坐标**，`blockInfo` 才是模板坐标 |
| 结构模板 NBT 的 `size` / `pos` | 必须是 `TAG_List<TAG_Int>`，**不是 `[I; ...]`**。写成 int 数组会 `getList(...,3)` 返回空 → 所有方块**静默堆到 (0,0,0)** 且 `/place template` 照样报成功 |
| 数据包函数里抛异常 | **中断整个函数**。`/data get block`、`execute if data block` 在目标没有方块实体时抛 `CommandSyntaxException`，后面命令全不执行——"函数跑一半停了"先怀疑它 |
| 函数里命令的输出 | 被抑制，`/data get` 不进日志。要结论只能用 `say` |
| NBT 匹配里的列表 | 是"**包含**"语义：`["a","b"]` = a 和 b 都在；`[]` **只**匹配空列表；`{Items:[{...}]}` = 任意一格命中 |
| 结构里的悬挂实体 | 放置时刷 `Block-attached entity at invalid position` ERROR 属原版噪声，**实体位置是对的** |

### 2.1 交互路径上"换一个方块"的正确姿势（2026-09-25 定稿）

挖掘与右键两条路径都已经收敛到同一个套路，**新加交互时照抄它**：

```
事件到达（面对的是真实方块）
  ↓ 旁路标志已置位？ → 是：直接返回，让原版继续（这次面对的就是目标）
  ↓ 事件已被更早的监听器取消？ → 是：尊重它，什么都不做
  ↓ 决定"目标方块"是谁
  ↓ setBlock(pos, target, 2)        ← 唯一改动世界的一步
  ↓ 清掉记录用的一次性状态           ← 重派发会再次触发同一个事件
  ↓ 旁路标志 = true
  ↓     重新派发原版入口（破坏：gameMode.destroyBlock；右键：gameMode.useItemOn）
  ↓ 旁路标志 = false
  ↓ 取消本次事件（+ 右键要显式给 cancellationResult）
```

几条踩过的细节：

- **旁路标志用 `ThreadLocal<Boolean>`**，不是玩家标记：重派发是同线程同步调用，
  作用域天然只覆盖这一次；异常路径上也不会留下脏状态（用 `try/finally`）。
- **破坏与右键用两个不同的 `ThreadLocal`**，不要共用一个。
- **`setBlock` 的 flag 要按语义选**：右键那条用 3（要触发邻块更新，方块真的变了身份）；
  破坏那条用 2（紧接着就要拆掉它，flag 3 会让邻块在几微秒内被通知两次，
  对红石之类是可见抖动；方块形状没变，邻块本来也不需要重算）。
- **一次性状态必须在重派发之前清掉**，否则第二次进入时重复消费同一份数据。
- **别把"记录用的字段"顺手改成判定条件**。`BreakData.periodIndex` 曾被当成"锁定过期就拒绝转换"
  的依据，语义上是反的：锁定的意义就是"玩家看到的是哪个方块"，它必须压过时钟漂移。
  详见 `docs/progress/2026Q4.md` §K。

---

## 3. 数据生成与存档

- **改 `data/` 下任何 Provider 之后必须跑 `.\gradlew.bat runData`**，产物在 `src/generated/resources`。
- **数据文件不要靠"看起来对"**：跑 `.\gradlew.bat runServer` 让开发服务器加载一遍数据包，
  日志会直接给 codec 错误（例如 `No key spawn_overrides in MapLike`）。
- **"概率"在本项目里有三个，说"提高概率"必须点名是哪一个**（2026-09-29 新增）：
  解析链上有三个彼此独立、都叫"概率"的量，加错一个的症状差别很大：

  | 量 | 语义 | 加错的症状 |
  |---|---|---|
  | `chance` / `effectiveChance` | **会不会命中**（这一期变不变） | 世界整体变快或变慢，最容易被当成"没生效" |
  | `wildChance` / `effectiveWild` | 命中之后**从局部池还是大池抽** | 只改"变成什么"，命中数一点不变——**看不出代价** |
  | `upTierChance` | 允许不允许**越级**（目标比源高一档） | 资源凭空升级，是唯一会打破 tier 护栏的量 |

  催化域的 spill 曾经只加在第二个上，于是"域外一圈 `wild` 升高"这个代价在 1% 的命中率下
  几乎不可见；作者一眼看出"变少了"才把它翻出来。**改任何一个之前，先读
  `MutationHelper#resolveInternal` 里它是怎么被用的**，不要按名字猜。
- **方块不掉落，先怀疑"有没有战利品表"**（2026-09-29 新增）：1.21 的方块掉落
  完全由 `data/<ns>/loot_table/blocks/<id>.json` 决定，**没有那张表就是什么都不掉**——
  没有"掉自己"的兜底。本项目战利品表一律手写，所以每加一个可破坏的方块都要**手动**补一张；
  漏掉的症状是"方块没了、东西也没了"，而日志里一个字都没有。
  `[loot]` 段把这件事变成了结构性断言（列出应该掉自己的方块，逐个问引擎要掉落物）。
- **战利品表不能走数据生成**：`LootTableProvider.run()` 写盘时统一用原版 `LootTable.DIRECT_CODEC`
  重新编码，**自定义条件键会被静默丢弃**，所以战利品表一律手写。
- **`neoforge:conditions` 不是 vanilla 战利品条件**。它是 NeoForge **数据包条件**（注册在 `CONDITION_SERIALIZERS`），
  塞进 `pools[].neoforge:conditions` 会让**整张表加载失败**。要做前置门控得注册一个真正的
  vanilla 条件类型到 `Registries.LOOT_CONDITION_TYPE`（本项目：`focal_decay:patchouli_loaded`）。
- **1.21.1 给结构容器配战利品**：正确写法是 NBT 里的 **`LootTable` 字符串**；
  网上说的 `components.minecraft:container_loot` **走不通**
  （`BlockEntity.loadWithComponents` 不会回调 `applyImplicitComponents` → `lootTable` 一直是 null → 箱子是空的）。
  `LootTableSeed` 不用自己写，`placeInWorld` 会自动塞。
- **`/place template` 测不出 `StructureProcessor`**：`PlaceCommand` 只在 `integrity < 1.0` 时才挂处理器。
  验证必须走 `/place structure` 或真实世界生成。
- **结构里的观测者基座自带模型**用 `StructureProcessor` 返回带 NBT 的 `StructureBlockInfo` 实现，
  参考结构 `focal_decay:sample_anchor`，脚手架在 `tools/`。

---

## 4. 多线程（本项目最危险的一块）

**`SectionCompiler.compile` 跑在 ForkJoinPool 的 worker 上，多个区块节同时编译**，
而编译路径会调用 `MutationHelper.resolve` → `MutationStateMapper` 等。因此：

- 任何被编译线程碰到的缓存/状态**必须是线程安全或每线程一份**；
- 键是 `long` 的热路径用 `ThreadLocal` 而不是 `ConcurrentHashMap`
  （后者每次 get/put 都要装箱，等于把刚消掉的分配又加回来）；映射是纯函数时各存一份无一致性代价。
- **已踩的坑**：`MutationStateMapper` 的缓存最初是一张全局共享的 `Long2ObjectOpenHashMap`，
  并发 put 把内部数组写坏 → 线上崩在 `rehash` 的 `ArrayIndexOutOfBoundsException`，
  而在测试里表现为 fastutil 探测循环**死转**，把服务器拖到看门狗超时。
- 不要从 worker 线程做"读主线程字段 → 判空 → 再读一次"的 `check-then-act`
  （`Minecraft.level` 不是 volatile；这是 BACKLOG P1-4）。
- 区块编译相关的事件/钩子先判 `level instanceof RenderChunkRegion`，这样破坏粒子、
  手持方块、方块预览等走 `ClientLevel` 的地方一行都不受影响。

---

## 5. 联机一致性（本项目投入最大的一条线）

**根本原则：`MutationHelper.resolve` 两端共用，但"算得一样"的前提是"喂进去的输入一样"。**

- 客户端天然拿不到的东西有三类，**不要再假设它们一致**：
  1. **世界种子**：1.21 的登录包只给生物群系缩放用的**哈希**种子，真种子只发给管理员；
  2. **SERVER 配置**：客户端读的是它自己那份 toml，专用服务器上两份文件毫无关系；
  3. **不落盘、靠区块加载重建的状态**（有效原型机列表）：登录快照只含已加载区块里的那些。
- 做法：所有静态输入收进 `MutationSettings` 快照，由服务端下发；
  客户端在收到快照前**不渲染任何幽灵**（宁可看不到，也不能看错）；
  原型机与诞生周期走**增量包**，不要每次重发整表（那张表随建造无上限增长）。
- **手写编解码必须配往返自测**：分量超过 `StreamCodec.composite` 的 6 个上限就得手写，
  而 13 个字段里错一个**不会崩**，只会让两端静默地算出不同的世界。
- **显示刻的相位**：客户端的 `level.getGameTime()` 是本地自走的，服务端每 20 tick 才校一次
  （`MinecraftServer#tickChildren`，`tickCount % 20 == 0`）。两边满速时差半个 RTT，
  **服务端掉帧时客户端会跑在前面**，两次校准之间最多领先 20 tick。
  修法是交互时由客户端回报"我看到的显示刻"（`SyncClientViewPacket`），服务端校验后才采用
  （位置对得上 / 足够新 / 偏差在物理可能范围内）。
- 偏差界用**上界比较**而不是 `Math.abs(a-b) <= bound`：后者在客户端报来 `Long.MIN_VALUE`
  时会溢出成负数、反而判成通过。
- **出生周期与闸门必须在同一个时钟域**：闸门比的是显示刻，出生周期就必须记显示刻。
  记存储刻的话，倍率 7 时"刚放下"的方块一上来就过期（放置立刻失焦、右键长按能来回转换），
  倍率 < 1 时反向出错（方块长期不失焦）。记录时取 `max(服务端显示刻, 行动客户端回报的显示刻)`。
- 并列决胜规则要**与列表顺序无关**（本项目按中心坐标字典序）：增量同步之后两端的列表顺序不保证一致。

---

## 6. 渲染与 Mixin

### 6.1 幽灵状态的"同源"原则
一句话：**凡是"比较两个方块"的判定，两边必须来自同一个世界。**

- 网格替换了方块状态、面剔除却用真实状态 → 透过玻璃能一直看进方块内部（世界像破了个洞）。
- 所以需要**两处钩子**：`SectionCompilerMixin`（画成什么）+ `BlockShouldRenderFaceMixin`（这个面画不画）。
- 替换后 `Block.OCCLUSION_CACHE` 的键会自动变成幽灵对，不会拿真实状态的缓存结果去判断幽灵。
- 仍用真实状态的：**环境光遮蔽**那 12 次邻居读取（观感偏差，不是破洞；每方块多 12 次查找，有意不改）。
- 含水状态不参与失焦：幽灵替换会把整格状态换掉，而流体渲染读的正是替换后的状态 →
  干燥幽灵的流体为空 → 水面出现 1 格缺口。判定必须**按状态**（`getFluidState().isEmpty()`）而不是按方块，
  否则 `StairBlock/SlabBlock/FenceBlock/WallBlock/TrapDoorBlock/IronBarsBlock` 这些实现 `waterlogged` 的类
  会被整体砍掉。

### 6.2 缓存的有效期要覆盖**全部**输入

> **实例（2026-09-29）**：催化域的到达改变了域外那圈的 `wild_chance`，而客户端的
> `applyCatalystField` 只更新了镜像、**没有作废幽灵缓存**（诞生周期那条路径一直有这一步）。
> 后果不是"显示旧了"那么轻：客户端与服务端对同一格给出不同目标，玩家一右键，
> "所见即所得"就把方块换成他没在看的那个。**加任何新的同步输入时，问一句
> "它让哪些旧结论失效了"**——答案通常不只是一个坐标。
"时间没过期"不等于"结论还成立"。
- 幽灵是 `(真实方块, 位置, 种子, 周期)` 的函数，只判周期是不够的：
  玩家挖掉方块时周期没变，缓存里"石头显示成钻石矿"还在，而面剔除会拿它当"那里有块不透明方块"
  → 挖出来的洞要等下一轮表面扫描才出现（1~3 秒）。
- 负缓存（"这里没有幽灵"）**同样是真实方块的函数**，也要存真实方块。
- 诊断指标：周期日志里的 `N dropped mid-period because the real block changed`。
  正常游玩应该有数；长期为 0 说明判据失效了。

### 6.3 Mixin 与后处理
- 本项目 mixin 配置是 `"required": true` + `defaultRequire: 1` → **注入失败即启动崩溃**，
  不会静默降级（这个取舍是对的，但意味着版本升级要逐个复验）。
- `SectionCompiler` 的注入点在 **5 参数**的 `compile`（含 additionalRenderers），
  4 参数版本只是委托，`@Redirect` 必须用 5 参数描述符。
- mixin 配置里客户端数组要写 `client`，运行时用官方映射，**不需要 refmap/MixinGradle**。
- 自定义 post 效果的 program 必须放 `assets/minecraft/shaders/program/`，
  而 PostChain JSON 放 `assets/<modid>/shaders/post/`——
  因为 pass 引用 program 时按**默认命名空间**解析。
- `PostChain` 构造**不补** `shaders/post/` 前缀，要传完整路径。
- **program JSON 里漏声明 uniform ⇒ 该 uniform 静默变成 DUMMY**。
  真实案例：漏了 `InSize` → `OutSize / InSize` 除零 → texCoord 变 NaN → 全屏花屏。
- 自定义 PostChain **不跟随 `GameRenderer.resize`**，窗口变化后要自己比对宽高并 `resize`。
- 应用点在 `GameRenderer.renderLevel(DeltaTracker)` 的**尾部**（手部渲染之后），
  处理完要 `getMainRenderTarget().bindWrite(true)` 恢复主目标绑定。

---

## 7. 可选依赖与兼容

- **引用 Patchouli / JEI 的代码必须放在 `compat/` 下的独立类**，宿主类只保留字符串 modid 检查 + 一次静态方法调用。
  否则 `@EventBusSubscriber` 类的加载会被第三方类型拖垮（javac 会把 lambda 编译成签名带第三方类型的合成方法），
  而未装该依赖的整合包**在类加载/校验阶段**直接崩——`ModList.isLoaded()` 和 `try/catch` **都拦不住**
  （失败发生在方法被调用之前）。开发环境永远装了依赖，所以这个问题**只会在别人的整合包里暴露**。
- 加完新引用后用 `javap -c -p -classpath build/classes/java/main <宿主类>` 确认字节码里不残留第三方类型。
- 不要往 `run/mods` 手工放 gradle 已经提供的 jar（会同名双份）。

### Patchouli 专项
- **`book.json` 必须放 `data/<modid>/patchouli_books/<book>/book.json`，不能放 `assets/`**。
  放错的后果是**完全不报错**：书不进 `BookRegistry` → 创造栏/搜索栏都没有 → `/give` 出来显示
  `guide_book.invalid`。日志里的 `preloaded N jsons` 只证明**内容**被读到，**不代表书注册成功**。
- **`en_us/` 是必需的语言集**：索引阶段只枚举 `en_us/`，加载阶段先试当前语言、取不到才回退 `en_us`。
  只放 `zh_cn/` 会导致**整本书空白**，而 `preloaded` 依然照常打印。
- `book.json` 的 `creative_tab` 要写**真实注册 ID**（本项目 `focal_decay_tab`，不是 `focal_decay:focal_decay`）；
  写错**不报错**，只是静默不进栏。
- 物品模型纹理路径要**全限定且带 `textures/`**：`patchouli:item/book_gray`。
  写成 `patchouli:items/book_gray` 会静默变成紫黑方块。
- `$(l)` 在 Patchouli 里**是 bold**（`$(l:entry)` 才是链接），写全称免得误读。
- 正文里的 `$(name:arg)` 形式会**无条件**被当成 function 查找，查不到就打印
  `[MISSING FUNCTION: name]`，后面的 command 查找根本轮不到——所以自定义宏命令名**不要带冒号**。
- `book.json` 的 `macros` 是另一套机制（纯字符串替换、只能静态写死），`registerCommand` 不会往里加东西。
- 手册里**不要硬编码会随配置漂移的数字**（覆盖半径、天数、消耗量），改为机制描述或走宏。
- `/give` 调试写法（**注意引号**）：
  `/give Dev patchouli:guide_book[patchouli:book="focal_decay:observer_manual"]`
  不加引号会被补成 `minecraft:focal_decay` → 书无效 → 触发 Patchouli 自身的崩溃。

### JEI 专项
- **特殊配方**（`CustomRecipe` 子类）JEI **既不展示也不建索引**：
  `CraftingCategoryExtension#isHandled` 就是 `!recipe.isSpecial()`。
  要让它们可查，必须注册**原版工作台类别扩展**（`registerVanillaCategoryExtensions`
  → `registration.getCraftingCategory().addExtension(类, 扩展)`），
  并且配方要实现 `getIngredients()` / `getResultItem()`，让 `matches()` 与展示网格**共用同一个形状定义**。
- **`needsRecipeBorder()` 默认返回 `true`**，自绘背景必须重写为 `false`，否则两层灰框。
- **槽位坐标是图标中心**，不是左上角（按左上角传会整体偏左上约 8px）。
- **文字宽度由布局包围盒决定**，与类别宽度无关：只放一个槽时文字区只有几像素 →
  "一行 4 个字 + 省略号"。解法是放一个**零尺寸空绘制物**把右边界撑开
  （不能用空槽代替：`IRecipeSlotBuilder` 没有可见性开关，空槽会连槽框一起画）。
- **不要用 `IGuiHelper.createDrawable` 贴图做背景**：它走精灵/图集路径，实测大图只画出左上角一小块。
  背景改为纯代码绘制（`GuiGraphics.fill`）。
- **关系方向**：JEI 的"用途(U)"来自催化剂，"来源(R)"来自"哪些配方把该物品作为输出"。
  想让信息出现在 R 侧，就用**物品信息页**或把它放成**输出槽**。
- **JEI 索引的是服务端同步过来的配方表**（`ClientLevel#getRecipeManager`）：
  联机时客户端旧 jar 救不了新服务端的配方。

---

## 8. 测试与断言的可靠性（这一节本身是教训）

- **A/B 是硬要求**：新增断言时要**故意把代码改回坏状态**确认它真的 FAIL。
  不做 A/B 的断言等于没有断言。这个项目里已经**两次**由 A/B 抓出坏断言：
  一次是"现场位置根本不是突变源"导致断言恒真，一次是"随机抽查 256 个目标、而坏条目只有 1 个"
  导致改坏了也 PASS（详见 `docs/progress/2026Q4.md` §C/§D）。
- **断言的覆盖范围必须覆盖风险的全部，不能靠抽样**。后一次就是抽样长度小于风险基数造成的，
  修法是补一条**全量扫描**断言。写断言时先问："最坏情况下，被检查的那一项会不会被跳过？"
- **压力测试的规模如果落在缓存容量以内，它就什么都没验**。
  真实案例：并发测试先用单线程预热了全部样本对 → 并发阶段全是命中（不插入、不扩容、不竞争），
  对着有 bug 的实现跑出 PASS。修法是把键空间放大到远超缓存上限（4624 → 27368 对，上限 4096）。
- **压力测试要"有上限地等待"**：退化实现不再抛异常、而是在 fastutil 探测循环里死转，
  `join()` 无限等会把服务器拖到看门狗超时被强杀。改成守护线程 + `join(timeout)`，
  超时报 FAIL 并给解释，服务器存活。
- **"看得见的现象"很容易验成假象**。结构/渲染类的探针踩过四种假失败：
  ① 服务端 `/fill` **不带 `UPDATE_IMMEDIATE`**，走异步重编译，比表面扫描慢 → 计数为 0；
  ② 探针把玩家自己的格子也填成实心方块 → 玩家窒息死亡 → 客户端停在死亡界面 →
  **什么都不渲染，自然什么都不作废**（加 `doImmediateRespawn` 才暴露）；
  ③ 暂存区用相对坐标 `~ ~300 ~` → 超出世界高度 → `clone` 静默失败 → 只填不还原，**把世界改了**；
  ④ 玩家反复死亡+世界被改后，出生点高度图漂移，探针填的全是空气 → 还是 0。
- 因此：**探针要用绝对坐标、要先 `tp` 到固定地点、要确认玩家活着、要能自己清理自己**。
- 断言的措辞要能被搜到（现象词），便于以后在进度里检索。
- **A/B 的开关必须确认它真的生效**（2026-09-25 新增，危险度最高的一条）。
  Gradle 的 `JavaExec` **不会**把命令行上的 `-Dxxx` 传给 fork 出来的游戏 JVM，
  必须在 `build.gradle` 的 run 配置里显式 `systemProperty` 转发一次。
  不转发的表现是：测试照常跑完、日志里什么都没有、断言全 PASS——
  **和"把 bug 放回去其实也没事"一模一样**，最容易被误读成"实现没问题"。
  做法：A/B 跑完先看断言有没有 FAIL；没 FAIL 就先怀疑开关，而不是先怀疑断言。
- **差分断言的对照物必须落在"被测差异真的能显现"的范围内**（2026-09-25 新增，第五次坏断言）。
  真实案例：`[sync] birth gate` 拿"存储刻算出来的诞生周期"当对照物，断言它与真实诞生周期
  会给出不同判定；但回扫深度有上限 `CUMULATIVE_SCAN_CAP = 128`，
  而两个诞生周期**都让回扫撞上这个上限**，于是差异在被判据看到之前就被抹平了。
  那条断言实际测的是"回扫上限生效了没有"，不是它声称测的东西。
  **判据**：对照物与被测实现若共享同一个截断 / 夹取 / 上限 / 归一化，先问
  "两端会不会都撞到那个边界"；会的话就换一个离边界远的对照物。
  另外记住这个排查顺序：现场不干净（那次是 `offset` 残留）可能是**真实存在的第二个问题**，
  修好它断言仍不过，说明第三个问题在下面——**不要因为第一处修完没好就回头怀疑被测代码**。
- **防空跑守卫只该回答"这次操作到底有没有发生"**（2026-09-25 新增）。
  守卫一旦顺手要求了"被测的性质"，守卫失败就会把真正的判据盖掉。
  真实案例：`doTileDrops=false` 那条断言用"`playerDestroy` 被调用过"当守卫，
  而"走原版管线"正是被测的性质——旧实现下守卫先失败、报出
  "pipeline did not run at all"，把正确的 `diamonds=0` 顶掉了。回归网因此失去鉴别力。
- **断言之间会互相影响，每条断言要自己准备现场**（2026-09-25 新增）。
  真实案例：控制组会破坏目标格，而它后面那条断言假设"目标探针还在原处"。
  只在开头清一次现场时，这条在正常路径下**碰巧通过**（被测代码恰好把现场恢复成了期望的样子），
  只有 A/B 才暴露。凡是"断言 N 依赖断言 N-1 留下的世界状态"都是这种隐患。
- **不要用"按位置圈一个范围"来数实体**（2026-09-25 新增）。
  两个相邻测试点各自圈一个盒子时盒子会重叠，上一条断言留下的东西被下一条数进去
  （实测掉落数报 2、实际只掉过 1）。正解是**破坏前记下 UUID 集合、破坏后取差集**，
  位置歧义从几何上消失，顺带也不依赖盒子大小调参。
- **一段自测抛异常不能拖垮其余段**（2026-09-25 新增，自测本身的可信度问题）。
  真实案例：给 `[model]` 段做 A/B 时故意引入 bug，实现抛 `NullPointerException`，
  结果**整条 `selftest` 命令后面的段一行都没打出来**——`[selftest]`/`[sync]` 的行数从几十变成 **0**，
  日志里只有一条孤零零的异常栈。读日志的人会以为"后面那些段没问题"（其实没跑）。
  修法：命令层把每一段自测包进 try/catch，崩溃的那段打一行能被 grep 到的
  `SECTION CRASHED … FAIL (later sections still run)` + 异常栈，其余段照跑。
  **判据**：诊断工具的价值在于"跑一遍就能信任结论"；任何一段能让整条命令静默截断，
  这个工具就已经在骗人了。同理适用于任何"串多个独立检查"的入口。
- **验收标准本身也要验证"它能不能区分对错"**（2026-09-26 新增）。
  与上面几条同源，但错在**文档里的判据**而不是断言代码里，所以更难被发现——
  断言跑起来会 FAIL，而一条写得不对的验收标准会安静地让"没验到"看起来像"验过了"。
  一轮实机里同时踩到三种：
  ① **看错了值**：让人看日志里 `K cached decisions` 会不会回落，而那一行打的是
     **清空前**的计数，看它不可能判断有没有清空；
  ② **期望值不可能达到**：写了"`ERROR` 计数期望 0"，而本机稳定出现 Yggdrasil 公钥请求失败，
     这条永远不通过；
  ③ **前提没被触发**：让人验"候选体记录上限"，而上限是 512、记录 8 条只到 8%，
     等于什么都没验。
  **判据**：写完一条验收标准，先问三句——"这个观测点真的反映我关心的那个量吗"、
  "它在正常实现下能不能通过"、"它的触发条件在步骤里真的会发生吗"。
- **不要声明一个偶然成立的性质**（2026-09-26 新增，flaky 断言的一般成因）。
  一条断言声明"两个不同的输入**必定**给出不同结果"时，先问一句：
  **在什么情况下它们可以相同？** 答得出来就别那么断言。
  真实案例：诞生周期闸门那条断言声明"`birth=now` 与 `birth=now-5` 必定给出不同判定"，
  而累积转换**允许**它们相同——k=1 抽中而 k=2..5 都没抽中时，两个回溯窗口给出同一个结果。
  于是它按世界当前的周期位置时好时坏（实测 1193 通过 / 1205 失败），
  而"flaky"比"失败"更难查：你会先怀疑被测代码。
  改法：**声明必然成立的那一面**。那条性质的必然版本由回扫窗口的定义直接推出——
  "两个诞生周期只要都早于 `periodIndex - CUMULATIVE_SCAN_CAP + 1`，
  就落在同一个有效窗口里，判定必然相同"。改成声明它之后连跑三次稳定。
- **"两种不同操作表现完全相同"是强线索，不是噪声**（2026-09-26 新增）。
  它说明两者**走同一条代码路径**，于是根因一定在"它们共同经过的地方"，
  而不是在"它们的差异"里。真实案例：作者报告"任务管理器杀进程与正常退出世界表现相同，
  仪式都退回未开始"——这一句直接把排查从"崩溃处理"引到了"离线处理"，
  正确答案是"玩家一离线，`getPlayerByUUID` 返回 null → 无条件 fail → stop() 清空 playerId"，
  与崩溃毫无关系。
- **持久化数据可以直接读，不要靠猜**（2026-09-26 新增）。
  `SavedData` 落盘的是 gzip + big-endian 的 NBT，几十行解析器就能读到字段级内容
  （或者用项目自带的 `tools/structuregen/NbtTop.java`）。
  真实案例：一行"`Player` 字段在 NBT 里**缺失**"就钉死了"进度被 `stop()` 销毁过"这个结论
  ——因为只有 `stop()` 会清空它。比读代码猜快得多，而且不会被自己的假设带偏。
- **给一个格式化函数加了行，别忘了改调用方**（2026-09-28 新增，第二次犯同类错）。
  `ClientStatsCommand#format()` 返回 `String[]`，而命令正文写死了
  `String head = lines[0]; String cache = lines[1];`。后来给 `format()` 加了第三行
  （P1-5 的 `idleShare`）——**那一行永远不会被打印**。
  它的隐蔽之处在于：命令照常工作、日志照常有输出、没有任何报错，
  只是**缺了最关键的那一行**，而看的人会以为"这个观测点没实现"
  （实际发生了：作者跑了一轮，日志里 0 条 `idleShare`，而代码里确实有）。
  **这和"工具提示加在提前返回之后"（§R）是同一类：加了内容，没检查它是否可达。**
  防身两条：
  1. **不要写死索引**，用 `for (String line : lines)` 遍历；
  2. 给"期望产出几条"配一个**具名常量**，并让冒烟/自测把它与实际条数比对
     （本项目的做法见 `ClientStatsCommand#EXPECTED_LINE_COUNT` 与 `smokeReport()`；
     A/B 验证过：把常量改成 4 会打出 `FORMAT/REPORT MISMATCH  FAIL`）。
- **累积语义下，"有没有变"不是"每周期概率 × 周期数"**（2026-09-29 新增，第三次栽在同一族）。
  `resolve` 命中后会<b>回看</b> `[periodIndex-CAP+1, periodIndex]` 这一整段窗口，
  所以"这个位置变了吗"是"窗口内至少中过一次"的概率：
  阶段 1 的 0.01/周期在 128 周期的窗口下是 `1-0.99^128 ≈ 72%`，实测 128 个周期里 95 个变了。
  第一版催化域断言就是按"1%"估的基线，于是<b>正确实现被判成 FAIL</b>。
  防身：写断言前先把被测量的定义写成一式（本例：`1-(1-p)^window`），
  算不出来就说明还没想清楚；基线要用<b>同一次运行里实测的值</b>，而不是推导值。
- **几何 / 边界断言先把边界值手算成一张小表，再写**（2026-09-29 新增）。
  同一轮里写错两次，两次都是**断言的算术错了、实现是对的**：
  ① "半径 4 + 壳厚 2"的外面那一格写成 `dy=6`（其实 6 还在壳里，7 才是外面）；
  ② 两个域中心放在 3 格外，却指望"一个点同时落在两边的壳里"——那个位置早被其中一片**必中**覆盖，
  于是"取最大溢出"的分支根本没被触发。
  防身：把边界列出来贴在断言旁边（`4 | 6 | 7`），并让失败信息打出**实际值**
  （`[shells spill=… forced=…]`），否则红一行字仍然要回去读代码。
- **"边界那一期算不算"和"自环算不算命中"也是上限的一部分**（2026-09-29 新增，同一族的第四次）。
  算验收值之前先问这两句，答案都不是"显然"：
  ① 催化域的有效期是**闭区间**（`at(until)` 仍在域内），第一版断言写成"最后一期什么都没有" → FAIL；
  ② 把落点池锁在"源自己那一档"之后，**池里就包含源方块自己**，而"抽到自己"是合法结果
  （自环，与原实现"抽中即定格"一致）→ 128 期里只有 115 期给出非源目标，
  第一版断言写成 `targets == 128` → FAIL。
  两条都不是缺陷，是**验收值凭"应该全中"想出来的**。
- **断言的输入要来自被测函数的输出，不要手搓**（2026-09-29 新增）：
  "spill 圈两条通道都加"这条断言第一版自己 `new Catalysis(false, 0.75, 0.75, false)`，
  于是它绕过了真正生产这个对象的 `Field#at`——A/B 开关（改的正是 `Field#at`）**漏过它**，
  只有旁边那条几何断言红了。改成 `ringField.at(探针位置, 期)` 之后才真正受开关约束。
  判据：**这个开关如果打开，这条断言会红吗？** 答不上来就说明它的输入不在被测路径上。
- **给断言留一条"控制组"**（2026-09-25 新增）。测"某某回调被调用了没有"这类性质时，
  正确答案恰恰是"没被调用"，最容易写成永远 PASS 的空断言。
  补一条"绕开被测代码、直接调原版入口，探针必须全部响应"的控制组，
  就把"探针本身是活的"单独钉死了——此后主断言的失败一定是被测代码的问题。
- **"客户端起得来"不等于"mixin 生效"**（2026-09-25 新增）。
  Mixin 被跳过时游戏**照样进得去**，只是所有靠注入实现的行为静默退回原版——
  不崩、不报错、日志里也不一定有痕迹，表现是"模组好像没生效"。
  验证必须包含"注入本身发生了没有"：
  `./gradlew.bat runClient -Dmixin.debug.verbose=true`，然后
  `grep -c "Preparing focal_decay.mixins.json"` 与 `grep "Mixing .* from focal_decay.mixins.json"`。
  注意**进世界之前只织入一部分**（目标是懒加载的类），所以要看完整清单必须进过世界。

- **把"某个比例应当接近 1"写成验收标准之前，先算一遍上限**（2026-09-28 新增）。
  `idleShare → 1.0` 写在纸上完全合理，但它隐含一个前提：**幽灵的有效期远长于一次全量重扫**。
  实际是——周期 5 秒、可见集合 ≈505 节、每周期预算 600 节 → **上限只有 0.16**。
  于是"指标没上去"既可以读成实现有问题，也可以读成**标准本身不可满足**，而两者的处置方向相反。
  判别法：把分母的来源写成一行算式（本例 `1 − 505/600`），算不出来就说明标准还没想清楚。
- **先分清"CPU 贵"还是"限流紧"**（2026-09-28 新增）。
  两者的症状一模一样：从不空闲、每次都跑满。处置方向却相反——前者要**减工作量**，后者要**放预算**。
  最省事的判别法是看实际速率是否**恰好等于**每次的预算上限
  （本次：实测 119.6 节/秒，预算 12 节 × 10 次/秒 = 120 —— 贴住上限即限流）。
  只看"它一直很忙"会得出相反结论：这个项目就据此写下过"优化方向是让它停下来"。

---

## 9. 与版本升级相关的易碎点（升级 MC 前逐个复验）

| 位置 | 为什么脆 |
|---|---|
| `SectionCompilerMixin` | 单匹配 `RenderChunkRegion#getBlockState`；原版增删调用点、或被别的模组同样 redirect 就冲突 |
| `BlockShouldRenderFaceMixin` | `Block.shouldRenderFace` 是极热且**常被 mixin 的方法** |
| `MinecraftPickBlockMixin` | 注入 `Minecraft#pickBlock` 中 `ClientLevel#getBlockState` 的两处调用 |
| `MultiPlayerGameModeMixin` | `continueDestroyBlock` 的 `getBlockState` redirect + `startDestroyBlock`/`useItemOn` 的 HEAD inject |
| `GameRendererMixin` | `renderLevel(DeltaTracker)` 的 TAIL |
| `RenderChunkRegionAccessor` | `@Accessor("level")`，**字段改名即崩**（且编译期不报） |
| `LevelRendererAccessor` | `@Accessor("viewArea")`，同上 |
| `ObservationVeil` / `PostChain` 调用 | 渲染后端在 26.2 被整体重写（Vulkan、动态 `VertexFormat`） |
| `client/facade/VanillaText`、`VanillaGui` | 26.2 **删除了 `Font` 的全部绘制方法**（改为 `prepareText` + `GlyphVisitor`）。所有文本绘制已经收进这两个门面，**新代码不要再直接调 `Font`** |
| 落盘/过网的注册表数字 ID | **只有跨越进程/会话/存档边界的数字 ID 才是问题**；纯运行时的数组下标不受影响。已有约定：跨边界的标识一律用 `ResourceLocation` 字符串 |

---

## 10. 热路径性能基准（改 `mutation/` 前后对照用）

`/focaldecay mutation selftest` 会打印。参考量级（本机、历史数据）：

| 场景 | 耗时 |
|---|---|
| `chance = 1.00`（1 步扫描） | ~37–41 ns/次 |
| `chance = 0.01`（阶段 1，期望回扫 ~100 步） | ~176–219 ns/次 |
| 楼梯 + 状态迁移 | ~187 ns/次 |
| 同上，**tier 护栏打开**（2026-09-29 实测，同一进程 A/B） | 50 / 237 / 246 ns —— 护栏本身值 **+6~9 ns** |

> ⚠️ **跨运行的 ns 基线不可信**（2026-09-29 新增，用一次真实的误判换来）：
> 同一个提交、同一台机器，前后两次 `runServer` 的同一条基准给出 **378 ns 与 237 ns**
> —— 差 60%，比要测的效应大一个数量级。当时差点据此认定"tier 护栏让热路径慢了一倍"。
> 归因必须在**同一进程内前后各测一次**（`[selftest]` 现在会自己打这一行：
> `same with the tier gate OFF (in-process A/B)`），或者至少跑三次取最小值。
> 判据：关掉开关后的数字应当回到历史基线（本次 41 / 223 ns，与 37–41 / 176–219 吻合，
> 说明归因是对的）。

客户端扫描的规模（改之前先算一遍，别凭感觉）：
- 每 `surface_update_frequency`（默认 **2 tick**，不是帧）扫最多 `SCAN_SECTION_BUDGET = 12` 节
  × 4096 = **≤49,152 个位置/次，约 49 万/秒**；
- 队列上限 17×17 列 × ≤13 层 = ≤3,757 节，**只在排空后重建** → 走完一圈约 313 次 ≈ **31 秒**。

**改任何热路径前先跑一次 selftest 记下基线**，改完再跑，数字回退就要解释。

---

## 11. 自定义内容标识符总表（查注册名用）

> 加新内容时**先看这里有没有同类**，命名保持一致；加完**回来补一行**。
> 原属 `PROXYAI.md` §13 附录。

**方块**
- `anchor_prototype`（观测者基座 / 原型机）、`training_terminal`（训练终端）、
  `observer_core`（观测者核心，含 `powered` 状态）、`throne_block`（王座岩，不可破坏）

**物品**
- 模型：`observer_model_blank`、`semantic_lock_model`、`guided_mutation_model`、
  `bio_stabilizer_model`、`total_stability_model`（未激活）、`total_stability_model_activated`（已激活）、
  `observer_model_candidate`（候选观测者 OBSR-3，主线）
- 碎片：`semantic_fragment_rose`、`_throne`、`_semantic`、`_42ms`、`_crystal`、`_aaron`、`_cheng`
- 已移除：`rebuilt_observer_protocol`（**不要再引用**）

**方块实体类型**：`anchor_prototype`、`training_terminal`、`observer_core`

**Attachment（原 Capability）**：`break_data`

**方块标签**
- 突变：`mutation_pool/wild`（+ `wild_nether` / `wild_end`）、`mutation_pool/*`（语义池与 16 个颜色池）、
  `shape_class/*`（形态类）、`mutation_immune`、`mutation_source_extra`、`anchor_prototype_immune`
- 概念：`focal_decay:concept/*`（wood/ore/stone/glass/terracotta/wool 等，数据生成策展）

**实体类型标签**：`entity_mutation_pool_passive` / `_neutral` / `_hostile`

**数据组件**：`focal_decay:observer_model_data`

**结构 / 世界生成**：`focal_decay:end_throne`（结构）、`focal_decay:end_throne_spread`（放置类型）、
`focal_decay:single_template`（结构类型）、`focal_decay:anchor_model` / `focal_decay:container_loot`（处理器）

**战利品函数 / 条件**：`focal_decay:random_training`（函数）、`focal_decay:patchouli_loaded`（条件）

**配方序列化器**：`crafting_special_copytrainedmodel`、`crafting_special_feedfragment`、
`crafting_special_derivecandidate`
（已移除：`crafting_special_rebuildobserver` —— 对应的 `rebuilt_observer_protocol` 物品也已删除）

**着色器**：`observer_veil`（program 放 `assets/minecraft/shaders/program/`）

**网络包**（NeoForge Payload API，协议版本 `"1"`）
- S→C：`sync_mutation_settings`、`sync_region_data`、`sync_prototype`、`sync_birth_period`、
  `sync_world_data`、`core_activate`、`throne_ritual`
- C→S：`sync_client_view`

**命令**：`/focaldecay days | throne [selftest] | inspect | trace | unlock | refocus | period | mutation audit | selftest | at`

**JEI 类别 / 信息页**：`focal_decay:model_derivation`、`focal_decay:copy_model`、`focal_decay:feed_fragment`

**Patchouli 手册**：书 ID `focal_decay:observer_manual`（= `data/focal_decay/patchouli_books/observer_manual/` 的目录名）

---

## 12. 约定速查（从历史条目里提炼的规则）

1. 涉及修改既有实现逻辑、或改动核心语义的，**先讨论再动手**。
2. 需求有歧义时，把**取向问题**摆出来让作者拍板。
3. 报告结论时区分**代码确认的 / 文档声称的 / 推测的**。
4. 加了新配置项或新状态，先回答"客户端怎么知道这个值"。
5. 缓存派生值时，有效期要覆盖它的**全部**输入。
6. 改了 `data/` 下 Provider 就跑 `runData`；改了数据格式就跑 `runServer` 让 codec 报错。
7. 日志一律 ASCII；文档与注释可以中文。
8. 可选依赖一律类加载隔离，并用 `javap` 复验字节码。
9. **不要手工复刻原版生命周期**；只替换那个你要改变的变量，让原版管线跑完。
   掉落、经验、耐久、统计、成就、其他模组的钩子全部自动正确，而且以后原版或别的模组新加的
   任何一步也自动正确。手工补调用是一条永远追不上的路（挖掘路径曾漏掉**八项**原版行为，
   2026-09-25 重构掉）。具体套路见 §2.1，经过见 `progress/2026Q4.md` §K。
10. 断言必须做 A/B；"未做实机验证"必须明说。
11. 跨进程/会话/存档的标识用 `ResourceLocation` 字符串，不用数字 ID。
12. **验证一个机制，要看它"构建后的运行时形态"，不是它的输入数据**（2026-09-29 新增）。
    实例：核实"下界/末地的大池是不是分开的"时，读的是标签文件（`wild_nether.json` 45 条全是下界方块），
    于是回答"已经做好了"；而真正决定行为的是 `MutationIndexBuilder` 构建出来的池——
    那里的 `wild_auto_include`（**默认开**）会把所有 cube 方块塞进**每一个**维度的大池，
    所以三张池默认是同一张。判据应当是日志 / 断言 / 命令给出的**产物**：
    `mutation index[the_nether]: wild=600 blocks (+163 auto)` 一眼就能看出问题。
    推论：**默认值也是设计的一部分**，"默认关闭的可选行为"与"默认开启的隐式行为"在文档里长得一样。
12. 完成一项工作就把 BACKLOG 里那条删掉，并在进度里记一节（见 `AGENTS.md` §5）。
