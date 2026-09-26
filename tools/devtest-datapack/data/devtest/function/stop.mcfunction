# Headless verification helper: shuts the dev server down once the probes are done.
#
# ⚠️ Only stops when NOBODY is online (2026-09-26 修)。
# 原实现是无条件 `stop`，于是 `runServer` 从加载那一刻起计时 60 秒必定自杀——
# 作者用外部客户端连进来做联机验证时被直接踢下线（实测 15:09:19 登录、15:09:30 关服），
# 而且它看起来像"服务端进世界一段时间后崩溃"，把排查方向带偏了。
#
# 现在的行为：
#   - 无头自测（无玩家）→ 60 秒时静默关服，与原来完全一致；
#   - 有玩家在线 → 每 10 秒复查一次，直到所有人退出后 10 秒才关。
#
# 想让服务器一直开着（例如长期联机测试）：删掉 load.mcfunction 里那条
# `schedule function devtest:stop`，或者直接删掉本文件。
scoreboard objectives add devtest_autostop dummy
scoreboard players set #online devtest_autostop 0
execute as @a run scoreboard players add #online devtest_autostop 1
execute if score #online devtest_autostop matches 0 run stop
execute if score #online devtest_autostop matches 1.. run schedule function devtest:stop 10s
