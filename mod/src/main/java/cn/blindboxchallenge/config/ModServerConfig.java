package cn.blindboxchallenge.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 物品参数由服务端配置确定；客户端按播放事件中的限制下载音频，不决定业务结算。 */
public final class ModServerConfig {
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue BLIND_BOX_OPENING_TICKS;
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EFFICIENT_PIG_MAX_SCANNED;
    public static final ForgeConfigSpec.DoubleValue YIJIN_MAX_HEALTH_BONUS;
    public static final ForgeConfigSpec.DoubleValue YIJIN_ATTACK_DAMAGE_BONUS;
    public static final ForgeConfigSpec.DoubleValue YIJIN_DOUBLE_JUMP_VELOCITY;
    public static final ForgeConfigSpec.DoubleValue TELESCOPIC_KNIFE_RETRACT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue STONE_PILLOW_IMPACT_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue DIAMOND_PILLOW_IMPACT_DAMAGE;
    public static final ForgeConfigSpec.IntValue LETTER_MAX_CODE_POINTS;
    public static final ForgeConfigSpec.IntValue LETTER_MAX_LINES;
    public static final ForgeConfigSpec.IntValue DEATH_NOTE_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue DEATH_NOTE_DAMAGE;
    public static final ForgeConfigSpec.IntValue CLOCKWORK_CHICKEN_FUSE_TICKS;
    public static final ForgeConfigSpec.IntValue CLOCKWORK_CHICKEN_EXPLOSION_POWER;
    public static final ForgeConfigSpec.IntValue MUSIC_BOX_MAX_DOWNLOAD_MIB;
    public static final ForgeConfigSpec.IntValue MUSIC_BOX_CONNECT_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue MUSIC_BOX_READ_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue MUSIC_BOX_TOTAL_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec SERVER_SPEC;

    static {
        SERVER_BUILDER.push("blind_box");
        BLIND_BOX_OPENING_TICKS = SERVER_BUILDER.comment("长按开启盲盒所需的服务端刻数，20 刻约为 1 秒；修改后从下一次开启生效")
                .defineInRange("opening_ticks", 40, 10, 200);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("yijin_manual");
        YIJIN_MAX_HEALTH_BONUS = SERVER_BUILDER.comment("易筋经永久增加的最大生命值，2 点等于 1 颗心")
                .defineInRange("maximum_health_bonus", 2.0D, 0.0D, 40.0D);
        YIJIN_ATTACK_DAMAGE_BONUS = SERVER_BUILDER.comment("易筋经永久增加的近战攻击伤害")
                .defineInRange("attack_damage_bonus", 1.0D, 0.0D, 20.0D);
        YIJIN_DOUBLE_JUMP_VELOCITY = SERVER_BUILDER.comment("易筋经二段跳的向上初速度")
                .defineInRange("double_jump_velocity", 0.42D, 0.1D, 1.5D);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("telescopic_knife");
        TELESCOPIC_KNIFE_RETRACT_CHANCE = SERVER_BUILDER.comment("伸出的黑武士伸缩刀命中后自动收缩的概率")
                .defineInRange("retract_chance", 0.20D, 0.0D, 1.0D);
        SERVER_BUILDER.pop();
        SERVER_BUILDER.push("pillow");
        STONE_PILLOW_IMPACT_DAMAGE = SERVER_BUILDER.comment("石墩子抱枕投掷命中的伤害")
                .defineInRange("stone_impact_damage", 3.0D, 0.0D, 40.0D);
        DIAMOND_PILLOW_IMPACT_DAMAGE = SERVER_BUILDER.comment("钻石抱枕投掷命中的伤害")
                .defineInRange("diamond_impact_damage", 5.0D, 0.0D, 40.0D);
        SERVER_BUILDER.pop();
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
        SERVER_BUILDER.push("music_box");
        MUSIC_BOX_MAX_DOWNLOAD_MIB = SERVER_BUILDER.comment("八音盒单次下载的最大文件大小，单位 MiB；修改后从下一次播放生效")
                .defineInRange("max_download_mib", 16, 1, 16);
        MUSIC_BOX_CONNECT_TIMEOUT_SECONDS = SERVER_BUILDER.comment("八音盒连接及 TLS 握手的超时秒数，同时受总下载时限约束")
                .defineInRange("connect_timeout_seconds", 10, 1, 60);
        MUSIC_BOX_READ_TIMEOUT_SECONDS = SERVER_BUILDER.comment("八音盒每次网络读取等待的超时秒数，同时受总下载时限约束")
                .defineInRange("read_timeout_seconds", 10, 1, 60);
        MUSIC_BOX_TOTAL_TIMEOUT_SECONDS = SERVER_BUILDER.comment("八音盒从域名解析到下载完成的总时限秒数，包含重定向")
                .defineInRange("total_timeout_seconds", 60, 1, 120);
        SERVER_BUILDER.pop();
        SERVER_SPEC = SERVER_BUILDER.build();
    }

    private ModServerConfig() {}
}
