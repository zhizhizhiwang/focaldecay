package com.zhizhiwang.focal_decay.config;

import net.neoforged.neoforge.common.ModConfigSpec;

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
                .defineInRange("entity_mutation_chance_stage2", 0.01, 0.0, 1.0);
        ENTITY_MUTATION_CHANCE_STAGE3 = builder
                .comment("Per-cycle chance (0-1) for an entity to mutate in stage 3.")
                .defineInRange("entity_mutation_chance_stage3", 0.05, 0.0, 1.0);
        ENABLE_CORE_REPAIR = builder
                .comment("Whether the Observer Core can be repaired to end the defocus.")
                .define("enable_core_repair", true);

        builder.pop();
        SERVER_SPEC = builder.build();
    }

    // ------------------------------------------------------------------
    // Common
    // ------------------------------------------------------------------
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.IntValue ANCHOR_RADIUS;
    public static final ModConfigSpec.DoubleValue POST_INTENSITY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Focal Decay common settings (loaded on both sides).")
                .push("common");

        ANCHOR_RADIUS = builder
                .comment("Chebyshev distance radius of the Stable Anchor protection field.")
                .defineInRange("anchor_radius", 8, 1, 32);
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
