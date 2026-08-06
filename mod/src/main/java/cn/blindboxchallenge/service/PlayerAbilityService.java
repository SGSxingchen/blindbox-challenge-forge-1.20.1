package cn.blindboxchallenge.service;

import cn.blindboxchallenge.capability.ModCapabilities;
import cn.blindboxchallenge.capability.PlayerAbilityData;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.network.SyncPlayerAbilityPacket;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.network.PacketDistributor;

/** P3 永久能力的唯一服务端业务入口；客户端只接收快照，不能写属性或速度。 */
public final class PlayerAbilityService {
    public static final UUID YIJIN_MAX_HEALTH_UUID = UUID.fromString("b41bde8d-5a4c-4d45-aeba-b01e641e9009");
    public static final UUID YIJIN_ATTACK_DAMAGE_UUID = UUID.fromString("c1f3ea45-f1af-48cb-b066-1b8c3ef93d63");
    public static final double YIJIN_MAX_HEALTH_BONUS = 2.0D;
    public static final double YIJIN_ATTACK_DAMAGE_BONUS = 1.0D;
    public static final double DOUBLE_JUMP_VELOCITY = 0.42D;
    public static final int DOUBLE_JUMP_COOLDOWN_TICKS = 5;

    private PlayerAbilityService() {}

    /** 首次学习成功才返回 true；重复使用不消耗书本且绝不重复叠加属性。 */
    public static boolean learnYiJin(ServerPlayer player) {
        return player.getCapability(ModCapabilities.PLAYER_ABILITY).map(data -> {
            if (data.hasLearnedYiJin()) return false;
            data.setLearnedYiJin(true);
            reconcileAttributes(player, data);
            syncTrackingAndSelf(player, data);
            return true;
        }).orElse(false);
    }

    /** 服务器只接受无参数意图，并再次校验学习状态、物理状态及本次滞空次数。 */
    public static boolean requestDoubleJump(ServerPlayer player) {
        return player.getCapability(ModCapabilities.PLAYER_ABILITY).map(data -> {
            long gameTime = player.serverLevel().getGameTime();
            if (!player.isAlive() || player.isSleeping() || !data.hasLearnedYiJin() || data.hasUsedDoubleJump()
                    || data.isDoubleJumpOnCooldown(gameTime) || player.onGround()
                    || player.isSpectator() || player.getAbilities().flying || player.isFallFlying()
                    || player.isPassenger() || player.isInWaterOrBubble() || player.isInLava() || player.onClimbable()) return false;
            data.setUsedDoubleJump(true);
            data.setNextDoubleJumpTick(gameTime + DOUBLE_JUMP_COOLDOWN_TICKS);
            player.setDeltaMovement(player.getDeltaMovement().x, DOUBLE_JUMP_VELOCITY, player.getDeltaMovement().z);
            player.hurtMarked = true;
            return true;
        }).orElse(false);
    }

    /** 落地后才重置本次滞空许可，不需要客户端发送任何状态。 */
    public static void resetAirJumpWhenGrounded(ServerPlayer player) {
        if (!player.onGround()) return;
        player.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> data.setUsedDoubleJump(false));
    }

    /** 克隆、登录、换维后按 Capability 事实源重建属性，固定 UUID 避免重复叠加。 */
    public static void reconcileAttributes(ServerPlayer player, PlayerAbilityData data) {
        reconcile(player.getAttribute(Attributes.MAX_HEALTH), YIJIN_MAX_HEALTH_UUID, "易筋经最大生命", YIJIN_MAX_HEALTH_BONUS,
                data.hasLearnedYiJin());
        reconcile(player.getAttribute(Attributes.ATTACK_DAMAGE), YIJIN_ATTACK_DAMAGE_UUID, "易筋经攻击伤害", YIJIN_ATTACK_DAMAGE_BONUS,
                data.hasLearnedYiJin());
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }

    private static void reconcile(AttributeInstance attribute, UUID id, String name, double amount, boolean enabled) {
        if (attribute == null) return;
        attribute.removeModifier(id);
        if (enabled) attribute.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADDITION));
    }

    public static void syncTrackingAndSelf(ServerPlayer player, PlayerAbilityData data) {
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new SyncPlayerAbilityPacket(player.getId(), data.hasLearnedYiJin()));
    }

    public static void syncTo(ServerPlayer receiver, ServerPlayer target) {
        target.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data ->
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver),
                        new SyncPlayerAbilityPacket(target.getId(), data.hasLearnedYiJin())));
    }
}
