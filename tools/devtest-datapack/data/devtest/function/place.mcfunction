# 端到端验证 focal_decay:single_template + AnchorModelProcessor
# 注意：函数里抛异常会中断，所以 data 相关判定都先确认方块实体存在再查。

forceload add 0 0
forceload add 0 2

say [devtest] A place template (no processors, 只验模板本体)
place template focal_decay:sample_anchor 0 120 0
execute if block 0 120 0 focal_decay:throne_block run say [devtest] A_origin_ok
execute if block 0 121 0 focal_decay:throne_block run say [devtest] A_pillar_ok
execute if block 0 122 0 minecraft:end_rod run say [devtest] A_corner_rod_ok
execute if block 3 121 3 focal_decay:anchor_prototype run say [devtest] A_anchor_ok
execute if block 6 120 6 focal_decay:throne_block run say [devtest] A_far_corner_ok

say [devtest] B 默认战利品表 (y=100)
place structure devtest:anchor_test 0 100 0
execute if block 0 100 0 focal_decay:throne_block run say [devtest] B_origin_ok
execute if block 3 101 3 focal_decay:anchor_prototype run say [devtest] B_anchor_ok
execute if block 3 101 3 focal_decay:anchor_prototype run execute if data block 3 101 3 {Model:{}} run say [devtest] B_MODEL_PRESENT
execute if block 3 101 3 focal_decay:anchor_prototype run execute if data block 3 101 3 {Model:{id:"focal_decay:observer_model_blank"}} run say [devtest] B_tier_common_blank
execute if block 3 101 3 focal_decay:anchor_prototype run execute if data block 3 101 3 {Model:{id:"focal_decay:bio_stabilizer_model"}} run say [devtest] B_tier_common_bio

say [devtest] C rare 表 + set_components (y=110)
place structure devtest:anchor_rare 0 110 0
execute if block 3 111 3 focal_decay:anchor_prototype run say [devtest] C_anchor_ok
execute if block 3 111 3 focal_decay:anchor_prototype run execute if data block 3 111 3 {Model:{id:"focal_decay:semantic_lock_model"}} run say [devtest] C_id_semantic_lock_ok
execute if block 3 111 3 focal_decay:anchor_prototype run execute if data block 3 111 3 {Model:{components:{"focal_decay:observer_model_data":{type:"semantic_lock"}}}} run say [devtest] C_component_type_ok
execute if block 3 111 3 focal_decay:anchor_prototype run execute if data block 3 111 3 {Model:{components:{"focal_decay:observer_model_data":{stabilityStrength:1.0d}}}} run say [devtest] C_component_strength_ok
execute if block 3 111 3 focal_decay:anchor_prototype run execute if data block 3 111 3 {Model:{components:{"focal_decay:observer_model_data":{trainedTargets:["minecraft:stone"]}}}} run say [devtest] C_component_targets_ok

say [devtest] D unique 表 + 引导模型 (y=130)
place structure devtest:anchor_unique 0 130 0
execute if block 3 131 3 focal_decay:anchor_prototype run say [devtest] D_anchor_ok
execute if block 3 131 3 focal_decay:anchor_prototype run execute if data block 3 131 3 {Model:{id:"focal_decay:guided_mutation_model"}} run say [devtest] D_id_guided_ok
execute if block 3 131 3 focal_decay:anchor_prototype run execute if data block 3 131 3 {Model:{components:{"focal_decay:observer_model_data":{type:"guided",concept:"focal_decay:concept/stone"}}}} run say [devtest] D_component_guided_ok

say [devtest] E 世界生成路径 (WorldGenRegion)：强制生成区块 0,100
forceload add 0 1600
say [devtest] E_forceload_done
execute if block 3 101 1603 focal_decay:anchor_prototype run say [devtest] E_worldgen_anchor_ok
execute if block 3 101 1603 focal_decay:anchor_prototype run execute if data block 3 101 1603 {Model:{}} run say [devtest] E_worldgen_MODEL_PRESENT
execute if block 3 101 1603 focal_decay:anchor_prototype run execute if data block 3 101 1603 {Model:{id:"focal_decay:observer_model_blank"}} run say [devtest] E_worldgen_tier_common_blank
execute if block 3 101 1603 focal_decay:anchor_prototype run execute if data block 3 101 1603 {Model:{id:"focal_decay:bio_stabilizer_model"}} run say [devtest] E_worldgen_tier_common_bio
execute if block 0 100 1600 focal_decay:throne_block run say [devtest] E_worldgen_origin_ok

say [devtest] done
