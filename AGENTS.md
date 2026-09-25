# AGENTS.md — Focal Decay 工程与协作守则

> 这是本仓库的**唯一入口**。任何 AI（或新加入的人）在动手之前先读这一份；
> 它会告诉你"这个项目是什么、规矩是什么、该去读哪个文件、改完要写到哪里"。
>
> 本文件是**常驻**的：规则类内容只写在这里，不要往设计文档或进度文档里塞规则。
> 最后更新：2026-09-25

---

## 0. 快速启动（每条任务都从这里开始）

```
1. 读本文件（AGENTS.md）                      ← 你在做的这一步
2. 看 §2 的"按任务类型决定读什么"，只读你需要的那几份
3. 动手前确认 docs/PITFALLS.md 里有没有踩过的同类坑
4. 动手
5. 按 §5 的规矩把结果写进对应的文档（这一步不是可选的）
6. 按 §4 的验证清单自检
```

**不要**在没有读 `docs/PITFALLS.md` 的情况下改 `mixin/`、`mutation/`、`client/` 或任何 `data/` Provider。
那份文件里的每一条都是拿一次真实崩溃或一次返工换来的。

---

## 1. 项目速览（30 秒版）

| 项 | 值 |
|---|---|
| 是什么 | Minecraft 模组：世界的语义会"失焦"，方块/实体/天气确定性漂移；玩家用观测模型建立局部稳定，最终把新观测者送上王座 |
| 取材 | SCP-CN-2999《Observator Ex Machina》（CC BY-SA 3.0），改编为 CC BY-SA 4.0；代码 GPL-3.0 |
| 平台 | Minecraft 1.21.1 / NeoForge 21.1.248 / Parchment 2024.11.17 / Java 21 / ModDevGradle |
| Mod ID | `focal_decay`，包 `com.zhizhiwang.focal_decay` |
| 可选依赖 | Patchouli（手册）、JEI（配方与来源查询）——**都必须做类加载级隔离**，见 §3 |
| 代码规模 | `src/main/java` 约 110 个类；`data/` 走数据生成 |
| 核心不变式 | 失焦是 `(pos, worldSeed, period)` 的**纯函数**，两端必须逐位一致 |

一句话记住这个项目的技术核心：**世界没有逐方块的失焦存档，"世界此刻长什么样"完全由一根周期指针决定。**
这条决定了调试手段（拨指针 = 回滚）、决定了联机一致性的全部要求、也决定了哪些改动是安全的。

---

## 2. 按任务类型决定读什么

不要每次全量通读。按下面这张表取最小充分集合：

| 任务类型 | 必读 | 按需 |
|---|---|---|
| **改玩法 / 加机制 / 调平衡** | `docs/DESIGN.md` §12–§15（原则/意图/取舍/术语）+ 相关机制章节；`docs/BACKLOG.md` 相关条目 | `docs/PITFALLS.md` §热路径基准 |
| **修 bug / 改热路径** | `docs/PITFALLS.md`（全文）；`docs/progress/INDEX.md` 按现象查一遍 | `docs/DESIGN.md` 对应章节 |
| **改渲染 / mixin / 客户端** | `docs/PITFALLS.md` §4（多线程）、§6（渲染与 Mixin） | `docs/DESIGN.md` §7 |
| **改网络 / 联机一致性** | `docs/DESIGN.md` §5、§8；`docs/PITFALLS.md` §5 | 约定速查第 4 条 |
| **改数据生成 / 标签 / 战利品表** | `docs/PITFALLS.md` §3（数据生成）、§2（API 语义陷阱） | `docs/DESIGN.md` §4、§9 |
| **加/改手册与文案** | `docs/PITFALLS.md` §7（Patchouli / JEI） | `docs/DESIGN.md` §13.7 |
| **查某个注册名 / 标识符** | `docs/PITFALLS.md` §11（标识符总表） | — |
| **准备升级 MC / NeoForge 版本** | `docs/PITFALLS.md` §9（易碎点清单） | `docs/ROADMAP.md` §4 |
| **发布 / 打包 / 元数据** | `docs/release/MODRINTH.md` | `docs/release/TRAILER.md` |
| **不知道从哪下手** | 本文件 §0、§5，然后 `docs/ROADMAP.md` §2 的当前阶段 | — |

**查历史**：从 `docs/progress/INDEX.md` 进——那里有约 45 条
`现象 → 小节 → 关键结论` 的速查表。遇到"这个 bug 以前是不是修过"，
先按**现象**查（人记得的是"我看到什么"，不是"那节编号是多少"），
不要重新推一遍——很多坑已经付过一次学费了。

---

## 3. 硬约束（违反会直接出事）

### 3.1 Shell 与编码

**本机可用的 shell 只有 PowerShell。** 想换 Git Bash / WSL 会失败，且原因是环境性的、不是配置问题：

| 尝试 | 结果 |
|---|---|
| `C:\Program Files\Git\bin\bash.exe` | `fatal error - couldn't create signal pipe, Win32 error 5` —— **受限沙箱下程序无法创建命名管道**，而 MSYS 的 fork/信号机制依赖它 |
| `wsl` | `Wsl/EnumerateDistros/Service/E_ACCESSDENIED`（本机没有已安装的发行版） |

所以在 Windows 上就用 PowerShell，并显式处理编码：

```powershell
# 读（UTF-8）
Get-Content -Encoding UTF8 <file>
# 读历史 GBK 文件
[IO.File]::ReadAllLines($p, [Text.Encoding]::GetEncoding(936))
# 写（UTF-8 无 BOM）
[IO.File]::WriteAllText($p, $text, (New-Object Text.UTF8Encoding($false)))
# 递归找文件：优先用 Glob 工具，别用 Get-ChildItem -Recurse
```

- **PowerShell 默认 GBK**。编辑文件一律用 **UTF-8**。
- **写在日志里的字符串一律 ASCII**。本项目控制台是 GBK，日志里写中文会变乱码，
  而乱码恰好会掩盖关键线索（这个坑真实发生过：乱码掩盖了 `creative=true` 这一决定性信息）。
  注释与文档可以写中文，**日志不行**。
- 已知 `.gitignore` 等历史文件是 GBK，改动它们要用
  `[IO.File]::ReadAllLines($p, [Text.Encoding]::GetEncoding(936))` 读写，别用默认编码覆盖。

### 3.2 两端一致（本项目的最高优先级不变量）
凡是进入 `MutationHelper.resolve` 的**静态输入**，都必须由服务端显式下发
（`MutationSettings` + `SyncMutationSettingsPacket`）；客户端拿不到就**不渲染幽灵**，
绝不用本端默认值猜一个。

- 加任何新配置项 / 新状态时，问自己两句：
  **"客户端怎么知道这个值？"** —— 答不上来就说明它必须进同步。
  **"两端不一致时，是静默算错，还是能看出来？"** —— 必须是后者。
- 已知仍**没有**进快照的三个量（`WILD_CHANCE` 服务端入口、「SEMANTIC_LOCK_STAGE3_STRENGTH」、
  `WILD_AUTO_INCLUDE`）是待修项，见 `docs/BACKLOG.md` P0-7。**不要照抄它们的写法。**
- 回归网是 `/focaldecay mutation selftest` 的 `[sync]` 段。改了任何与解析相关的输入，都跑它。

### 3.3 线程
**区块编译跑在 ForkJoinPool 上，多个区块节同时编译。** 因此：
- 任何被编译线程碰到的缓存/状态必须是**线程安全或每线程一份**；
- 键是 `long` 的热路径用 `ThreadLocal` 而非 `ConcurrentHashMap`（后者要装箱，会把你刚消掉的分配加回来）；
- 纯函数 + 每线程缓存是标准解，无一致性代价；
- 从 worker 线程读主线程字段要一次性拿到（不要 `check-then-act`），
  已知 `ClientRegionData.current()` 还有这个问题（BACKLOG P1-4）。

### 3.4 原版生命周期
**不要为了改变一个中间变量而手工复刻原版流程。** 已经付过代价的：
- 挖掘路径取消 `BreakEvent` 后手工补掉落/经验/耐久/统计，结果漏掉了八项原版行为（BACKLOG P0-4）；
- 正确思路是**只替换"被操作的是哪个方块"这个变量，然后让原版管线跑完**。

### 3.5 可选依赖的类加载隔离
引用 Patchouli / JEI 的代码**必须**放在 `compat/` 下的独立类里，
宿主类只保留字符串 modid 检查 + 一次静态方法调用。
否则 `@EventBusSubscriber` 类的加载会被第三方类型拖垮，
而未装该依赖的整合包会**在类加载/校验阶段**直接崩——`ModList.isLoaded()` 和 `try/catch` 都拦不住
（失败发生在方法被调用之前）。加完新引用后用 `javap -c -p` 扫字节码确认。

---

## 4. 验证清单（改完必须过）

按改动范围取用，**不能只跑编译就宣布完成**：

| 范围 | 必跑 |
|---|---|
| 任何 Java 改动 | `.\gradlew.bat compileJava` |
| 改了 `data/` 下的 Provider | `.\gradlew.bat runData`，确认 `src/generated/resources` 变化符合预期 |
| 改了标签 / 池 / 形态类 / 概念 | `runData` + `runServer` 后跑 `/focaldecay mutation audit`（`frozen` / `asymmetric` / `crossClass` 必须全 0） |
| 改了 `MutationHelper` / 输入快照 / 网络包 | `/focaldecay mutation selftest`，`[sync]` 段必须全 PASS |
| 改了并发相关 | `[stress]` 段必须 PASS（注意它的规模是关键，见 PITFALLS） |
| 改了数据包格式 | `runServer` 让它加载一遍，日志会直接给 codec 错误 |
| 准备提交 | `.\gradlew.bat build -x test` |
| 客户端相关 | `$env:ALSOFT_DRIVERS='null'` 后 `runClient`（**本机必需**，否则卡死在 OpenAL 的 HRTF 初始化） |

**A/B 是硬要求**：新增一条断言时，要**故意把代码改回坏状态**确认它真的会 FAIL。
`PROGRESS` 里有多条"测试返工"记录，其中一半是**假通过**（测试规模落在缓存容量以内、
或者断言根本没被执行到）。不做 A/B 的断言等于没有断言。

**"未做客户端实机验证"必须明说**。写进度时把"已验证"和"未验证"分开列，不要含糊过去。

---

## 5. 文档该写到哪里（单一真源规则）

**一条事实只存在于一个文件里。** 跨文件引用请用相对路径链接，不要复制粘贴。

| 文件 | 装什么 | 不装什么 |
|---|---|---|
| `AGENTS.md` | 规则、必读顺序、工作流、验证清单 | 设计内容、进度、待办 |
| `docs/DESIGN.md` | **当前有效的**设计：机制怎么运作、为什么这么设计、边界在哪；§12 原则 / §13 玩法意图 / §14 开放取舍 / §15 术语表 | 实现顺序、待办、缺陷、修复过程 |
| `docs/ROADMAP.md` | 交付计划：阶段目标、进入下一阶段的条件、版本路线、长期目标 | 具体 bug、代码级细节 |
| `docs/BACKLOG.md` | **待办的唯一真源**：分级条目 + 证据 + 修法 + 验收标准 | 已完成的条目（移走） |
| `docs/PITFALLS.md` | 技术细节：环境、API 语义陷阱、多线程、联机一致性、渲染与 Mixin、可选依赖、测试可靠性、版本易碎点、热路径基准、**标识符总表** | 玩法设计、待办 |
| `docs/progress/INDEX.md` | 进度分卷登记 + **按现象查**速查表 + 按主题查 | 具体根因分析 |
| `docs/progress/<卷>.md` | **已发生的事**：按主题分节，每节四段（现象/根因/修法/验证） | 待办（只留指针） |
| `docs/release/*` | 分发用文案（Modrinth 元数据、宣传片脚本） | 工程内容 |

### 写进度的规矩（这一条是为了治好"文档越来越乱"）

1. **按主题分节，就地更新**，不要追加"§13.18、§13.19……"这种无限增长的流水账。
   同一个主题（例如"联机一致性"）永远只有一节，新发现补进那一节。
2. 每节固定四段：**现象 / 根因 / 修法 / 验证**。
   "验证"里必须区分**已验证**与**未验证**。
3. 一节超过约 200 行时，考虑拆成独立文件并在索引里挂上。
4. **待办不要写在进度里**，写进 `BACKLOG.md` 并双向链接。
5. **规律性的教训要提到 `PITFALLS.md` 或 `AGENTS.md`**，
   进度里只留"当时发生了什么"。否则教训会被埋在流水账里没人再读。
6. 完成一条 BACKLOG 条目时：在 BACKLOG 里**删除**该条，在进度里记一节，在索引里加一行。

### 提交前自检
- 根目录只有：`README.md`、`AGENTS.md`、构建文件、许可证、`docs/`。
  **不要往根目录加新的 .md。**
- 新增文档必须在 `AGENTS.md` §5 的表里登记，否则下一个人找不到它。
- 文档改动和代码改动一起提交；文档**不许**再加进 `.gitignore`。
  ⚠️ 本项目踩过这个坑：`.gitignore` 里列着已经**被跟踪**的文档（对它们无效，纯误导），
  同时把**后加的** `TODO.md` / `MODRINTH.md` 静默挡在版本控制外——那两份文档从未提交过。
  所以：**不要把已跟踪的文件写进 `.gitignore`**，也**不要靠 ignore 来"临时不收"某个文档**。

---

## 6. 常用命令与资料位置

### 命令
```powershell
.\gradlew.bat compileJava          # 编译
.\gradlew.bat runData              # 重新生成 src/generated/resources
.\gradlew.bat build -x test        # 完整构建（产物在 build/libs）
.\gradlew.bat runServer            # 开发服务端（会加载数据包并报 codec 错误）
$env:ALSOFT_DRIVERS='null'; .\gradlew.bat runClient        # 开发客户端（本机必须设这个环境变量）
.\gradlew.bat runClientSecond      # 第二个客户端实例（联机测试，独立 run-client/ 目录）
```

### 查源码 / API 的可靠姿势
```powershell
# 反编译后的 MC+NeoForge 合并类（查签名）
javap -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar <类名>
# 带源码的 jar（**优先用这个**：能确认行为，不只是签名）
#   build/moddev/artifacts/neoforge-21.1.248-sources.jar
# 取出单个文件：
#   jar xf <sources.jar> net/minecraft/server/level/ServerPlayerGameMode.java
# Packouli / JEI 的源码也在 gradle 缓存里，别靠猜 API 行为
#   ~/.gradle/caches/modules-2/files-2.1/.../jei-*-sources.jar
```

> **通用教训**：引擎内置的 uniform / API 的语义（取值域、是否被覆写、单位、回绕）
> **必须读源码确认**。这个项目有一次连修四轮的后处理动画 bug，前三轮都是凭命名推测
> `Time` 与 `getRealtimeDeltaTicks()` 的含义。详见 `docs/PITFALLS.md`。

### 无头验证脚手架
`tools/` 下有结构生成与探针脚手架（`devtest-datapack`、`structuregen`、`NbtTop`），
跑法与坑见 `tools/README.md`。**结构 / 世界生成 / 战利品表这类东西不要靠"看起来对"**，
用它们实跑一遍。

---

## 7. 与作者协作的约定

- **涉及修改既有实现逻辑、或改动核心语义的，先讨论再动手。**
  作者明确要求过这一点；"先摆方案 + 取舍 + 推荐项"比直接改完再解释有效得多。
- 需求有歧义时，把**取向问题**摆出来让作者拍板（历史上这么做每次都省下了返工）。
- 报告结论时区分：**代码确认的 / 文档声称的 / 推测的**。三者混在一起会误导后续判断。
- 改动前先 `git status` 与 `git log --oneline -5` 确认基线，
  发现**未提交的既有改动**要先问清楚，不要覆盖。
- 这个项目的文档里出现过"文档说 A、代码是 B"（例如某个配置的默认值、某段时长的默认值）。
  **以代码为准**，发现不一致就顺手修文档，并在提交信息里说明。

---

## 8. 当前状态速查

| 想知道的 | 去哪 |
|---|---|
| 现在做到哪了 | [`docs/ROADMAP.md`](docs/ROADMAP.md) §1 快照 + §2 阶段表 |
| 接下来该做什么 | [`docs/BACKLOG.md`](docs/BACKLOG.md)（按 P0→P3 排；文末有建议推进顺序） |
| 某个机制怎么运作 | [`docs/DESIGN.md`](docs/DESIGN.md) 对应章节 |
| 这个坑踩过没有 | [`docs/PITFALLS.md`](docs/PITFALLS.md)，再按现象查 [`docs/progress/INDEX.md`](docs/progress/INDEX.md) |
| 为什么当初这么设计 | `DESIGN.md` 各章的"为什么"段落 + 进度里对应的根因分析 |
| 某个注册名 / 标识符 | [`docs/PITFALLS.md`](docs/PITFALLS.md) §11 |
| 还没定案的设计问题 | [`docs/DESIGN.md`](docs/DESIGN.md) §14 |

### 一句话现状（写于 2026-09-25）
主线闭环、联机一致性已解决、自动化自检齐全；**当前处于"稳定与加固"阶段**——
主要缺口是 8 条 P0（锚固化卡顿、掉落物突变丢件、挖掘绕过原版生命周期等）与实机验证不足；
玩法上最大的缺口是"观察没有产出"（详见 `DESIGN.md` §14.6）。
