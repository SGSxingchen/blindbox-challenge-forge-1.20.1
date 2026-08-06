package cn.blindboxchallenge.capability;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** P3 玩家长期能力的唯一 Capability 声明；实际数据只挂在 Player。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModCapabilities {
    public static final Capability<PlayerAbilityData> PLAYER_ABILITY = CapabilityManager.get(new CapabilityToken<>() {});

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) { event.register(PlayerAbilityData.class); }

    private ModCapabilities() {}
}
