package cn.blindboxchallenge.event;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.command.BlindBoxCommands;
import cn.blindboxchallenge.entity.PillowSeatEntity;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.item.SafetyExitSignShieldItem;
import cn.blindboxchallenge.service.BlindBoxService;
import cn.blindboxchallenge.service.DoorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import cn.blindboxchallenge.registry.ModItems;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID)
public final class ServerLifecycleEvents {
    /** 同一反伤伤害若再次触发格挡事件，必须在服务端同步调用栈内短路，避免互相反射。 */
    private static final ThreadLocal<Boolean> REFLECTING_SHIELD_DAMAGE = ThreadLocal.withInitial(() -> false);

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) { BlindBoxCommands.register(event.getDispatcher(), event.getBuildContext()); }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxService.inspectRecovery(player);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlindBoxItem.cancelUse(player);
            PillowSeatEntity.releasePassenger(player);
            DoorService.clearSelection(player);
        }
    }

    @SubscribeEvent
    public static void dimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlindBoxItem.cancelUse(player);
            PillowSeatEntity.releasePassenger(player);
        }
    }

    /** 门抵达免疫只保护玩家仍在目标门格内的短暂出门过程；真正离开后立即恢复正常反向传送。 */
    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            DoorService.clearArrivalImmunityAfterExit(player);
        }
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
        if (event.getEntity().level().isClientSide) return;
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (event.getEntity() instanceof ServerPlayer player) PillowSeatEntity.releasePassenger(player);
            return;
        }

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = event.getEntity().getItemInHand(hand);
            if (!isCustomTotem(stack)) continue;
            event.setCanceled(true);
            stack.shrink(1);
            event.getEntity().setHealth(1.0F);
            event.getEntity().removeAllEffects();
            event.getEntity().addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
            event.getEntity().addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
            event.getEntity().addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
            event.getEntity().level().broadcastEntityEvent(event.getEntity(), (byte) 35);
            return;
        }
        // 自定义图腾会取消本次死亡，不能在该分支前错误拆掉仍存活玩家的座位。
        if (event.getEntity() instanceof ServerPlayer player) PillowSeatEntity.releasePassenger(player);
    }

    /**
     * 029：只有原版确认“成功格挡”后才会到此事件。客户端不产生伤害，也不决定反伤对象。
     * 仅直接 LivingEntity 攻击者可被反射；投射物及其他间接来源保持原版行为。
     */
    @SubscribeEvent
    public static void shieldBlock(ShieldBlockEvent event) {
        if (event.getEntity().level().isClientSide) return;
        reflectSuccessfulShieldBlock(event.getEntity(), event.getDamageSource(), event.getBlockedDamage());
    }

    /** 供隔离 ciTest 在逻辑服务端验证真实反伤路径；返回本次实际尝试造成的反伤数值。 */
    public static float reflectSuccessfulShieldBlock(net.minecraft.world.entity.LivingEntity blocker,
                                                      net.minecraft.world.damagesource.DamageSource source,
                                                      float blockedDamage) {
        if (REFLECTING_SHIELD_DAMAGE.get() || !blocker.getUseItem().is(ModItems.SAFETY_EXIT_SIGN_SHIELD.get())) return 0.0F;
        net.minecraft.world.entity.Entity directAttacker = source.getDirectEntity();
        if (!(directAttacker instanceof net.minecraft.world.entity.LivingEntity attacker) || attacker == blocker) return 0.0F;
        float reflected = SafetyExitSignShieldItem.reflectedDamage(blockedDamage);
        if (reflected <= 0.0F) return 0.0F;

        REFLECTING_SHIELD_DAMAGE.set(true);
        try {
            attacker.hurt(blocker.damageSources().thorns(blocker), reflected);
            return reflected;
        } finally {
            REFLECTING_SHIELD_DAMAGE.remove();
        }
    }

    /** 头部栏的实际变化由服务端事件驱动，客户端不自行施加或清除失明。 */
    @SubscribeEvent
    public static void equipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getEntity().level().isClientSide || event.getSlot() != net.minecraft.world.entity.EquipmentSlot.HEAD) return;
        boolean wasEyeMask = event.getFrom().is(ModItems.EGGY_EYE_MASK.get());
        boolean isEyeMask = event.getTo().is(ModItems.EGGY_EYE_MASK.get());
        if (!wasEyeMask && isEyeMask) EggyEyeMaskItem.onEquipped(event.getEntity());
        if (wasEyeMask && !isEyeMask) EggyEyeMaskItem.onUnequipped(event.getEntity());
    }

    private static boolean isCustomTotem(ItemStack stack) {
        return stack.is(ModItems.RAT_JERKY_TOTEM.get()) || stack.is(ModItems.WENXU_STANDEE.get());
    }

    private ServerLifecycleEvents() {}
}
