# G6：random_training 战利品函数的验证。
#
# a/b 是单元测试：把 count / pool / extra / extra_chance 全部钉死，结果可以逐字断言。
# c 是抽样：用 /loot insert 把生产表抽 20 次塞进一个箱子，再按"列表包含"语义断言。
#   NBT 匹配的列表语义是"包含"（NbtUtils.compareNbt 的 compareListTag 分支）：
#   {Items:[{...}]} 只要任意一格命中就算中，所以一个箱子能一次性代表 20 次抽样。
#
# 坐标：chunk(0,0) 模板原点 (0,140,0)/(0,150,0)，锚在 (3,+1,3)；测试箱 (20,100,20) 属 chunk(1,1)。

forceload add 0 0
forceload add 16 16

say [devtest] G6a random_training 单元测试：count=2 / 池子 2 个 / extra 必中
place structure devtest:anchor_train_fixed 0 100 0
execute if block 3 141 3 focal_decay:anchor_prototype run say [devtest] G6a_anchor_ok
execute if block 3 141 3 focal_decay:anchor_prototype run execute if data block 3 141 3 {Model:{}} run say [devtest] G6a_MODEL_PRESENT
execute if data block 3 141 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:stone","minecraft:dirt","minecraft:white_concrete"]}}}} run say [devtest] G6a_all_three_ok
execute if data block 3 141 3 {Model:{}} run execute unless data block 3 141 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:[]}}}} run say [devtest] G6a_trained_nonempty_ok
execute if data block 3 141 3 {Model:{}} run execute unless data block 3 141 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:diamond_block"]}}}} run say [devtest] G6a_no_stray_ok

say [devtest] G6b count=1 / extra_chance=0
place structure devtest:anchor_train_count1 0 100 0
execute if block 3 151 3 focal_decay:anchor_prototype run say [devtest] G6b_anchor_ok
execute if block 3 151 3 focal_decay:anchor_prototype run execute if data block 3 151 3 {Model:{}} run say [devtest] G6b_MODEL_PRESENT
execute if data block 3 151 3 {Model:{}} run execute unless data block 3 151 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:[]}}}} run say [devtest] G6b_trained_nonempty_ok
execute if data block 3 151 3 {Model:{}} run execute unless data block 3 151 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:stone","minecraft:dirt"]}}}} run say [devtest] G6b_count_one_ok
execute if data block 3 151 3 {Model:{}} run execute unless data block 3 151 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:white_concrete"]}}}} run say [devtest] G6b_extra_disabled_ok

say [devtest] G6c 生产表抽样 20 次
setblock 20 100 20 minecraft:chest
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model
loot insert 20 100 20 loot focal_decay:structure/site_cn_25_anchor_model

execute if data block 20 100 20 {Items:[{id:"focal_decay:semantic_lock_model"}]} run say [devtest] G6c_obsr2_lock_seen
execute if data block 20 100 20 {Items:[{components:{"focal_decay:observer_model_data":{type:"guided",concept:"focal_decay:concept/stone"}}}]} run say [devtest] G6c_obsr1_guided_seen
execute unless data block 20 100 20 {Items:[{id:"focal_decay:observer_model_blank"}]} run say [devtest] G6c_no_prototype_ok
execute unless data block 20 100 20 {Items:[{id:"focal_decay:bio_stabilizer_model"}]} run say [devtest] G6c_no_bio_ok
execute unless data block 20 100 20 {Items:[{components:{"focal_decay:observer_model_data":{trainedTargets:[]}}}]} run say [devtest] G6c_every_roll_has_training_ok
execute if data block 20 100 20 {Items:[{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:white_concrete"]}}}]} run say [devtest] G6c_extra_white_concrete_seen
execute if data block 20 100 20 {Items:[{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:polished_diorite"]}}}]} run say [devtest] G6c_extra_polished_diorite_seen
execute if data block 20 100 20 {Items:[{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:verdant_froglight"]}}}]} run say [devtest] G6c_extra_verdant_froglight_seen
say [devtest] G6_done

say [devtest] G7 箱子战利品表抽样 20 次（八种物品是否都够得着）
setblock 22 100 20 minecraft:chest
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25
loot insert 22 100 20 loot focal_decay:chests/site_cn_25

execute if data block 22 100 20 {Items:[{id:"focal_decay:observer_model_blank"}]} run say [devtest] G7_obsr_prototype_seen
execute if data block 22 100 20 {Items:[{id:"focal_decay:anchor_prototype"}]} run say [devtest] G7_anchor_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:ender_pearl"}]} run say [devtest] G7_ender_pearl_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:ender_eye"}]} run say [devtest] G7_ender_eye_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:redstone"}]} run say [devtest] G7_redstone_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:copper_block"}]} run say [devtest] G7_copper_block_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:book"}]} run say [devtest] G7_book_seen
execute if data block 22 100 20 {Items:[{id:"minecraft:lapis_lazuli"}]} run say [devtest] G7_lapis_seen
execute unless data block 22 100 20 {Items:[{id:"minecraft:diamond"}]} run say [devtest] G7_no_stray_ok
say [devtest] G7_done
