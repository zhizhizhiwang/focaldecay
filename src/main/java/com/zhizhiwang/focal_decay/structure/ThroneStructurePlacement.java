package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.Optional;

/**
 * 王座专用放置类型：把 random_spread 的候选区块固定为王座所在区块。
 * 继承 RandomSpreadStructurePlacement（而非独立类型），使 /locate 的
 * ChunkGenerator#findNearestMapStructure 仍按 instanceof 分支处理本放置。
 */
public class ThroneStructurePlacement extends RandomSpreadStructurePlacement {
    public static final MapCodec<ThroneStructurePlacement> CODEC = RecordCodecBuilder.mapCodec(instance ->
            placementCodec(instance)
                    .and(instance.group(
                            Codec.intRange(0, 4096).fieldOf("spacing").forGetter(RandomSpreadStructurePlacement::spacing),
                            Codec.intRange(0, 4096).fieldOf("separation").forGetter(RandomSpreadStructurePlacement::separation),
                            RandomSpreadType.CODEC
                                    .optionalFieldOf("spread_type", RandomSpreadType.LINEAR)
                                    .forGetter(RandomSpreadStructurePlacement::spreadType)
                    ))
                    .apply(instance, ThroneStructurePlacement::new));

    public ThroneStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
                                    float frequency, int salt, Optional<ExclusionZone> exclusionZone,
                                    int spacing, int separation, RandomSpreadType spreadType) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, spacing, separation, spreadType);
    }

    public ThroneStructurePlacement(int spacing, int separation, RandomSpreadType spreadType, int salt) {
        super(spacing, separation, spreadType, salt);
    }

    @Override
    public ChunkPos getPotentialStructureChunk(long seed, int x, int z) {
        // locate 的网格扫描会以任意 (x, z) 询问候选区块：恒返回王座区块
        return ThroneStructure.throneChunk(seed);
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        ChunkPos throne = ThroneStructure.throneChunk(state.getLevelSeed());
        return chunkX == throne.x && chunkZ == throne.z;
    }

    @Override
    public StructurePlacementType<?> type() {
        return ModStructures.THRONE_PLACEMENT.get();
    }
}
