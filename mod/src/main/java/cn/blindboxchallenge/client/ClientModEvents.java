package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.registry.ModMenus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterMenuScreensEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.PACKING_MENU.get(), PackingScreen::new);
    }
    private ClientModEvents() {}
}
