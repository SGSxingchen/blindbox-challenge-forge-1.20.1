package cn.blindboxchallenge.event;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.capability.ModCapabilities;
import cn.blindboxchallenge.capability.PlayerAbilityProvider;
import cn.blindboxchallenge.service.PlayerAbilityService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.StartTracking;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** P3 Capability 的挂载、克隆、服务端对账与最小同步生命周期。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID)
public final class PlayerAbilityEvents {
    private PlayerAbilityEvents() {}

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            PlayerAbilityProvider provider = new PlayerAbilityProvider();
            event.addCapability(PlayerAbilityProvider.ID, provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clone(PlayerEvent.Clone event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof ServerPlayer replacement)) return;
        event.getOriginal().reviveCaps();
        try {
            event.getOriginal().getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(oldData ->
                    replacement.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(newData ->
                            // 以 Capability 自身的持久 NBT 完整复制，避免死亡替换期间读取运行期字段。
                            newData.deserializeNBT(oldData.serializeNBT().copy())));
        } finally {
            event.getOriginal().invalidateCaps();
        }
        replacement.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
            PlayerAbilityService.reconcileAttributes(replacement, data);
            PlayerAbilityService.syncTrackingAndSelf(replacement, data);
        });
    }

    @SubscribeEvent
    public static void login(PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
            PlayerAbilityService.reconcileAttributes(player, data);
            PlayerAbilityService.syncTrackingAndSelf(player, data);
        });
    }

    @SubscribeEvent
    public static void changedDimension(PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
            PlayerAbilityService.reconcileAttributes(player, data);
            PlayerAbilityService.syncTrackingAndSelf(player, data);
        });
    }

    @SubscribeEvent
    public static void startTracking(StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer observer && event.getTarget() instanceof ServerPlayer target) {
            PlayerAbilityService.syncTo(observer, target);
        }
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            PlayerAbilityService.resetAirJumpWhenGrounded(player);
        }
    }
}
