package com.zhizhiwang.focal_decay.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置，对应 config/focal_decay.toml。
 * 分类与设计大纲 §9.1 一致：
 *  - Server  : 同步到客户端（末日阶段/周期/概率）
 *  - Common  : 服务器与客户端各自加载（锚半径/后处理强度）
 *  - Client  : 仅客户端（后处理开关/表面更新频率/渲染距离）
 */
public final class FocalDecayConfig {

    // ------------------------------------------------------------------
    // Server
    // ------------------------------------------------------------------
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.IntValue STAGE2_DAY;
    public static final ModConfigSpec.IntValue STAGE3_DAY;
    public static final ModConfigSpec.IntValue BASE_INTERVAL;
    public static final ModConfigSpec.IntValue STAGE2_INTERVAL;
    public static final ModConfigSpec.IntValue STAGE3_INTERVAL;
    public static final ModConfigSpec.DoubleValue ENTITY_MUTATION_CHANCE_STAGE2;
    public static final ModConfigSpec.DoubleValue ENTITY_MUTATION_CHANCE_STAGE3;
    public static final ModConfigSpec.BooleanValue ENABLE_CORE_REPAIR;
    public static final ModConfigSpec.IntValue OBSERVER_CORE_ACTIVATION_TICKS;
    public static final ModConfigSpec.DoubleValue FRAGMENT_COPPER_MUTATION_CHANCE;
    public static final ModConfigSpec.DoubleValue BLOCK_MUTATION_CHANCE_STAGE1;
    public static final ModConfigSpec.DoubleValue BLOCK_MUTATION_CHANCE_STAGE2;
    public static final ModConfigSpec.DoubleValue BLOCK_MUTATION_CHANCE_STAGE3;
    public static final ModConfigSpec.DoubleValue WEATHER_MUTATION_CHANCE_STAGE1;
    public static final ModConfigSpec.DoubleValue WEATHER_MUTATION_CHANCE_STAGE2;
    public static final ModConfigSpec.DoubleValue WEATHER_MUTATION_CHANCE_STAGE3;
    public static final ModConfigSpec.BooleanValue ENABLE_STAGE_SYSTEM;
    public static final ModConfigSpec.IntValue TRAINING_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue TRAINING_ENERGY_COST;
    public static final ModConfigSpec.IntValue TRAINING_MAX_TARGETS;
    public static final ModConfigSpec.IntValue BIO_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue BIO_CONVERSION_PER_HP;
    public static final ModConfigSpec.IntValue BIO_DRAIN_PER_SECOND;
    public static final ModConfigSpec.BooleanValue BIO_STAGE3_DOUBLE_DRAIN;
    public static final ModConfigSpec.BooleanValue BIO_STABILIZE_ENTITIES;
    public static final ModConfigSpec.DoubleValue SEMANTIC_LOCK_STAGE3_STRENGTH;
    public static final ModConfigSpec.IntValue GUIDED_MIN_TRAINED;
    public static final ModConfigSpec.DoubleValue GUIDED_Q_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue GUIDED_Q_CAP;
    public static final ModConfigSpec.BooleanValue GUIDED_STAGE3_HALVE;
    public static final ModConfigSpec.IntValue THRONE_RITUAL_SECONDS;
    public static final ModConfigSpec.IntValue THRONE_RITUAL_WAVE_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue THRONE_RITUAL_WAVE_SIZE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> THRONE_RITUAL_WAVE_ENTITIES;
    public static final ModConfigSpec.IntValue THRONE_RITUAL_RADIUS;
    public static final ModConfigSpec.BooleanValue THRONE_RITUAL_PAUSE_ON_LEAVE;
    public static final ModConfigSpec.IntValue CANDIDATE_REQUIRED_POINTS;
    public static final ModConfigSpec.IntValue CANDIDATE_FRAGMENT_POINTS;
    public static final ModConfigSpec.DoubleValue ENDER_DRAGON_TOTAL_STABILITY_DROP_CHANCE;
    public static final ModConfigSpec.IntValue TOTAL_STABILITY_COPY_PENALTY;
    public static final ModConfigSpec.IntValue TOTAL_STABILITY_MAX_COPIES;
    public static final ModConfigSpec.IntValue TOTAL_STABILITY_COPY_TRAIN_PENALTY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Focal Decay server-side settings (synced to clients).")
                .push("server");

        STAGE2_DAY = builder
                .comment("Day count at which the world enters stage 2 (affects non-full blocks).")
                .defineInRange("stage2_day", 3, 0, Integer.MAX_VALUE);
        STAGE3_DAY = builder
                .comment("Day count at which the world enters stage 3 (affects air, entity mutation).")
                .defineInRange("stage3_day", 7, 1, Integer.MAX_VALUE);
        BASE_INTERVAL = builder
                .comment("Mutation period in ticks for stage 1.")
                .defineInRange("base_interval", 100, 20, Integer.MAX_VALUE);
        STAGE2_INTERVAL = builder
                .comment("Mutation period in ticks for stage 2.")
                .defineInRange("stage2_interval", 60, 20, Integer.MAX_VALUE);
        STAGE3_INTERVAL = builder
                .comment("Mutation period in ticks for stage 3.")
                .defineInRange("stage3_interval", 40, 20, Integer.MAX_VALUE);
        ENTITY_MUTATION_CHANCE_STAGE2 = builder
                .comment("Per-cycle chance (0-1) for an entity to mutate in stage 2.")
                .defineInRange("entity_mutation_chance_stage2", 0.1, 0.0, 1.0);
        ENTITY_MUTATION_CHANCE_STAGE3 = builder
                .comment("Per-cycle chance (0-1) for an entity to mutate in stage 3.")
                .defineInRange("entity_mutation_chance_stage3", 0.3, 0.0, 1.0);
        BLOCK_MUTATION_CHANCE_STAGE1 = builder
                .comment("Per-cycle chance (0-1) for a block to mutate in stage 1.")
                .defineInRange("block_mutation_chance_stage1", 0.01, 0.0, 1.0);
        BLOCK_MUTATION_CHANCE_STAGE2 = builder
                .comment("Per-cycle chance (0-1) for a block to mutate in stage 2.")
                .defineInRange("block_mutation_chance_stage2", 0.3, 0.0, 1.0);
        BLOCK_MUTATION_CHANCE_STAGE3 = builder
                .comment("Per-cycle chance (0-1) for a block to mutate in stage 3.")
                .defineInRange("block_mutation_chance_stage3", 0.9, 0.0, 1.0);
        WEATHER_MUTATION_CHANCE_STAGE1 = builder
                .comment("Per-cycle chance (0-1) for the weather to mutate (switch to a different state) in stage 1.")
                .defineInRange("weather_mutation_chance_stage1", 0.0, 0.0, 1.0);
        WEATHER_MUTATION_CHANCE_STAGE2 = builder
                .comment("Per-cycle chance (0-1) for the weather to mutate (switch to a different state) in stage 2.")
                .defineInRange("weather_mutation_chance_stage2", 0.05, 0.0, 1.0);
        WEATHER_MUTATION_CHANCE_STAGE3 = builder
                .comment("Per-cycle chance (0-1) for the weather to mutate (switch to a different state) in stage 3.")
                .defineInRange("weather_mutation_chance_stage3", 0.15, 0.0, 1.0);
        ENABLE_STAGE_SYSTEM = builder
                .comment("Whether the doomsday stage system is enabled. When false the world stays in stage 1.")
                .define("enable_stage_system", true);
        ENABLE_CORE_REPAIR = builder
                .comment("Whether the Observer Core can be repaired to end the defocus.")
                .define("enable_core_repair", true);
        OBSERVER_CORE_ACTIVATION_TICKS = builder
                .comment("Ticks between activating the Observer Core and the defocus ending.")
                .defineInRange("observer_core_activation_ticks", 100, 20, 600);
        FRAGMENT_COPPER_MUTATION_CHANCE = builder
                .comment("Chance (0-1) for a copper block that defocuses to drop the Sulfur-Copper Crystal fragment.")
                .defineInRange("fragment_copper_mutation_chance", 0.15, 0.0, 1.0);
        TRAINING_ENERGY_CAPACITY = builder
                .comment("Training Terminal FE capacity.")
                .defineInRange("training_energy_capacity", 100000, 0, Integer.MAX_VALUE);
        TRAINING_ENERGY_COST = builder
                .comment("FE cost per training session. 0 disables the energy requirement (single-mod default).")
                .defineInRange("training_energy_cost", 0, 0, Integer.MAX_VALUE);
        TRAINING_MAX_TARGETS = builder
                .comment("Max trained records per blank model.")
                .defineInRange("training_max_targets", 64, 1, 1024);
        BIO_ENERGY_CAPACITY = builder
                .comment("Max bioEnergy stored in a Bio Stabilizer model.")
                .defineInRange("bio_energy_capacity", 2000, 0, Integer.MAX_VALUE);
        BIO_CONVERSION_PER_HP = builder
                .comment("bioEnergy gained per 1 HP drained from creatures in range.")
                .defineInRange("bio_conversion_per_hp", 20, 0, Integer.MAX_VALUE);
        BIO_DRAIN_PER_SECOND = builder
                .comment("bioEnergy consumed per second while a Bio Stabilizer is active. 0 disables drain.")
                .defineInRange("bio_drain_per_second", 5, 0, Integer.MAX_VALUE);
        BIO_STAGE3_DOUBLE_DRAIN = builder
                .comment("Whether stage 3 doubles the Bio Stabilizer drain rate.")
                .define("bio_stage3_double_drain", true);
        BIO_STABILIZE_ENTITIES = builder
                .comment("Whether creatures inside a Bio Stabilizer range are kept from entity mutation.")
                .define("bio_stabilize_entities", true);
        SEMANTIC_LOCK_STAGE3_STRENGTH = builder
                .comment("Semantic Lock protection strength during stage 3 (0-1)."
                        + " Each cycle the locked block rolls a 'hold' die with this chance;"
                        + " when it fails the block joins the mutation roll. 0.5 = half effect, 1.0 = no decay.")
                .defineInRange("semantic_lock_stage3_strength", 1.0, 0.0, 1.0);
        GUIDED_MIN_TRAINED = builder
                .comment("Minimum distinct trained targets inside a concept for the Guided Model to have any effect."
                        + " Fewer counts as an incomplete classification (q = 0).")
                .defineInRange("guided_min_trained", 2, 1, 64);
        GUIDED_Q_MULTIPLIER = builder
                .comment("Multiplier applied to the Guided Model's concept coverage when computing q.")
                .defineInRange("guided_q_multiplier", 1.0, 0.0, 10.0);
        GUIDED_Q_CAP = builder
                .comment("Upper cap (0-1) for the Guided Model's q after the multiplier.")
                .defineInRange("guided_q_cap", 1.0, 0.0, 1.0);
        GUIDED_STAGE3_HALVE = builder
                .comment("Whether stage 3 halves the Guided Model's q (effect decay).")
                .define("guided_stage3_halve", true);
        THRONE_RITUAL_SECONDS = builder
                .comment("Duration of the End Throne ritual in seconds (33 minutes is the optional homage cap).")
                .defineInRange("throne_ritual_seconds", 30, 1, 1980);
        THRONE_RITUAL_WAVE_INTERVAL_SECONDS = builder
                .comment("Interval between ritual waves in seconds.")
                .defineInRange("throne_ritual_wave_interval_seconds", 30, 10, 600);
        THRONE_RITUAL_WAVE_SIZE = builder
                .comment("How many enemies spawn per ritual wave.")
                .defineInRange("throne_ritual_wave_size", 3, 1, 20);
        THRONE_RITUAL_WAVE_ENTITIES = builder
                .comment("Entity ids that can spawn during ritual waves.")
                .defineList("throne_ritual_wave_entities",
                        List.of("minecraft:enderman", "minecraft:shulker", "minecraft:phantom"),
                        obj -> obj instanceof String);
        THRONE_RITUAL_RADIUS = builder
                .comment("Distance (blocks) a player may stray from the throne while the ritual is active.")
                .defineInRange("throne_ritual_radius", 16, 4, 64);
        THRONE_RITUAL_PAUSE_ON_LEAVE = builder
                .comment("Pause the ritual when the player leaves the radius; otherwise it fails.")
                .define("throne_ritual_pause_on_leave", true);
        CANDIDATE_REQUIRED_POINTS = builder
                .comment("Training points required for the Candidate Observer model to reach 100%"
                        + " (each unique trained target = +1).")
                .defineInRange("candidate_required_points", 100, 1, 10000);
        CANDIDATE_FRAGMENT_POINTS = builder
                .comment("Training points granted by feeding one Semantic Fragment to the Candidate Observer.")
                .defineInRange("candidate_fragment_points", 10, 1, 1000);
        ENDER_DRAGON_TOTAL_STABILITY_DROP_CHANCE = builder
                .comment("Chance (0-1) for the Ender Dragon to leave a relic chest after death"
                        + " (Total Stability Model + Aaron's Vow fragment). 1.0 = always;"
                        + " lower values make it the 'rare drop' from the design doc.")
                .defineInRange("ender_dragon_total_stability_drop_chance", 1.0, 0.0, 1.0);
        TOTAL_STABILITY_COPY_PENALTY = builder
                .comment("Radius lost per generation when copying a Total Stability (OBSR-EX) model."
                        + " The loss escalates: generation 1 loses 1x, generation 2 loses 3x (triangular)."
                        + " Copying does NOT consume the original, so this plus the copy cap is what"
                        + " prevents an unlimited number of radius-32 hard-protection fields.")
                .defineInRange("total_stability_copy_penalty", 10, 0, 32);
        TOTAL_STABILITY_MAX_COPIES = builder
                .comment("Maximum copy generation of a Total Stability model. 2 means: original,"
                        + " a generation-1 copy and a generation-2 copy may exist, and generation 2"
                        + " can no longer be copied. 0 disables copying entirely.")
                .defineInRange("total_stability_max_copies", 2, 0, 16);
        TOTAL_STABILITY_COPY_TRAIN_PENALTY = builder
                .comment("Extra training points required for a Candidate Observer (OBSR-3) built from a"
                        + " copied OBSR-EX. Escalates with generation: 1x at generation 1, 3x at"
                        + " generation 2. 0 makes copies as good as the original.")
                .defineInRange("total_stability_copy_train_penalty", 50, 0, 10000);

        builder.pop();
        SERVER_SPEC = builder.build();
    }

    // ------------------------------------------------------------------
    // Common
    // ------------------------------------------------------------------
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.IntValue PROTOTYPE_RADIUS;
    public static final ModConfigSpec.DoubleValue POST_INTENSITY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Focal Decay common settings (loaded on both sides).")
                .push("common");

        PROTOTYPE_RADIUS = builder
                .comment("Chebyshev distance radius of the Anchor Prototype effect field.")
                .defineInRange("prototype_radius", 8, 1, 32);
        POST_INTENSITY = builder
                .comment("Post-processing intensity multiplier for the observer veil shader.")
                .defineInRange("post_intensity", 1.0, 0.0, 5.0);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    // ------------------------------------------------------------------
    // Client
    // ------------------------------------------------------------------
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue POST_PROCESS_ENABLED;
    public static final ModConfigSpec.IntValue SURFACE_UPDATE_FREQUENCY;
    public static final ModConfigSpec.IntValue MAX_RENDER_DISTANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Focal Decay client-side settings.")
                .push("client");

        POST_PROCESS_ENABLED = builder
                .comment("Enable the observer veil post-processing shader.")
                .define("postProcessEnabled", true);
        SURFACE_UPDATE_FREQUENCY = builder
                .comment("Frustum surface computation frequency (frames).")
                .defineInRange("surface_update_frequency", 2, 1, 20);
        MAX_RENDER_DISTANCE = builder
                .comment("Maximum chunk radius for surface scanning.")
                .defineInRange("max_render_distance", 16, 2, 32);

        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    private FocalDecayConfig() {
    }
}
