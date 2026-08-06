package cn.blindboxchallenge.event;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.command.BlindBoxCommands;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.service.BlindBoxService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
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
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) { BlindBoxCommands.register(event.getDispatcher(), event.getBuildContext()); }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxService.inspectRecovery(player);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
    }

    @SubscribeEvent
    public static void dimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
        if (event.getEntity().level().isClientSide || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;

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
