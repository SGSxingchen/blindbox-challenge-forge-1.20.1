package cn.blindboxchallenge.event;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.command.BlindBoxCommands;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.service.BlindBoxService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
    }

    @SubscribeEvent
    public static void dimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlindBoxItem.cancelUse(player);
    }

    private ServerLifecycleEvents() {}
}
