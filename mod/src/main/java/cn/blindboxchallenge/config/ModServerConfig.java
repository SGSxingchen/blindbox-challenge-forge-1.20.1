package cn.blindboxchallenge.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 仅由逻辑服务端读取的 P3 养猪保护阈值；客户端不保存、不决定目标或冷却。 */
public final class ModServerConfig {
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_MAX_SCANNED;
    public static final ForgeConfigSpec.IntValue LETTER_MAX_CODE_POINTS;
    public static final ForgeConfigSpec.IntValue LETTER_MAX_LINES;
    public static final ForgeConfigSpec.IntValue DEATH_NOTE_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue DEATH_NOTE_DAMAGE;
    public static final ForgeConfigSpec.IntValue CLOCKWORK_CHICKEN_FUSE_TICKS;
    public static final ForgeConfigSpec.IntValue CLOCKWORK_CHICKEN_EXPLOSION_POWER;
    public static final ForgeConfigSpec SERVER_SPEC;

    static {
        SERVER_BUILDER.push("efficient_pig_breeding");
        EFFICIENT_PIG_COOLDOWN_TICKS = SERVER_BUILDER.comment("高效养猪技术成功后的服务端冷却（tick）")
                .defineInRange("cooldown_ticks", 200, 1, 12000);
        EFFICIENT_PIG_MAX_SCANNED = SERVER_BUILDER.comment("单次服务端扫描最多处理的猪数量")
                .defineInRange("maximum_scanned_pigs", 32, 2, 128);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("letter");
        LETTER_MAX_CODE_POINTS = SERVER_BUILDER.comment("信件正文允许的最多 Unicode 码点数")
                .defineInRange("maximum_code_points", 512, 1, 4096);
        LETTER_MAX_LINES = SERVER_BUILDER.comment("信件正文允许的最多行数")
                .defineInRange("maximum_lines", 16, 1, 16);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("death_note");
        DEATH_NOTE_DELAY_TICKS = SERVER_BUILDER.comment("死亡笔记提交后由服务端等待的 tick 数")
                .defineInRange("delay_ticks", 60, 0, 12000);
        DEATH_NOTE_DAMAGE = SERVER_BUILDER.comment("死亡笔记到期后由服务端施加的绕过无敌原版伤害")
                .defineInRange("damage", 1000.0D, 0.0D, 1000000.0D);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("clockwork_chicken");
        CLOCKWORK_CHICKEN_FUSE_TICKS = SERVER_BUILDER.comment("发条小黄鸡由服务端启动时写入实体的倒计时 tick 数")
                .defineInRange("fuse_ticks", 1200, 1, 32767);
        CLOCKWORK_CHICKEN_EXPLOSION_POWER = SERVER_BUILDER.comment("发条小黄鸡到期后的原版 TNT 爆炸强度")
                .defineInRange("explosion_power", 8, 1, 64);
        SERVER_BUILDER.pop();
        SERVER_SPEC = SERVER_BUILDER.build();
    }

    private ModServerConfig() {}
}
