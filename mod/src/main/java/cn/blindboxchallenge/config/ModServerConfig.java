package cn.blindboxchallenge.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 仅由逻辑服务端读取的 P3 养猪保护阈值；客户端不保存、不决定目标或冷却。 */
public final class ModServerConfig {
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_MAX_SCANNED;
    public static final ForgeConfigSpec SERVER_SPEC;

    static {
        SERVER_BUILDER.push("efficient_pig_breeding");
        EFFICIENT_PIG_COOLDOWN_TICKS = SERVER_BUILDER.comment("高效养猪技术成功后的服务端冷却（tick）")
                .defineInRange("cooldown_ticks", 200, 1, 12000);
        EFFICIENT_PIG_MAX_SCANNED = SERVER_BUILDER.comment("单次服务端扫描最多处理的猪数量")
                .defineInRange("maximum_scanned_pigs", 32, 2, 128);
        SERVER_BUILDER.pop();
        SERVER_SPEC = SERVER_BUILDER.build();
    }

    private ModServerConfig() {}
}
