# 王座端到端验证（末地，世界种子必须是 20260912）
#
# 坐标由 tools/structuregen/ThronePos.java 算出：
#   throne   = -752 70 45   （原点；模板里的信标）
#   core     = -752 71 45   （observer_core，= 原点 +(0,1,0)）
#   chest    = -752 71 47   （王座碎片箱，= 原点 +(0,1,2)）
#   角柱顶   = -758 87 39 / -746 87 51（= 原点 +(±6,17,±6)）
# 换种子要重新算并改这些数字，同时改 run/server.properties 的 level-seed。

say [devtest] F 王座：强制生成末地王座区块
execute in minecraft:the_end run forceload add -758 39 -746 51

execute in minecraft:the_end if block -752 70 45 minecraft:beacon run say [devtest] F_origin_beacon_ok
execute in minecraft:the_end if block -752 71 45 focal_decay:observer_core run say [devtest] F_core_ok
execute in minecraft:the_end if block -752 71 47 minecraft:chest run say [devtest] F_chest_ok
execute in minecraft:the_end if block -752 71 47 minecraft:chest run execute if data block -752 71 47 {Items:[{id:"focal_decay:semantic_fragment_throne"}]} run say [devtest] F_chest_has_throne_fragment
execute in minecraft:the_end if block -758 87 39 minecraft:end_rod run say [devtest] F_corner_rod_a_ok
execute in minecraft:the_end if block -746 87 51 minecraft:end_rod run say [devtest] F_corner_rod_b_ok
execute in minecraft:the_end if block -752 80 45 minecraft:air run say [devtest] F_void_above_ok

# 反向确认：旧代码版的"整片 throne_block 平台"不该再出现
execute in minecraft:the_end if block -752 70 45 focal_decay:throne_block run say [devtest] F_LEGACY_platform_detected
execute in minecraft:the_end unless block -752 70 45 focal_decay:throne_block run say [devtest] F_no_legacy_platform_ok
