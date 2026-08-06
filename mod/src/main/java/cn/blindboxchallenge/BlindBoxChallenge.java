package cn.blindboxchallenge;

import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.registry.ModMenus;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.config.ModServerConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** P1 核心入口：不在此处持有世界或玩家引用。 */
@Mod(BlindBoxChallenge.MOD_ID)
public final class BlindBoxChallenge {
    public static final String MOD_ID = "blindboxchallenge";

    public BlindBoxChallenge() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModMenus.MENUS.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ModServerConfig.SERVER_SPEC);
        ModNetwork.register();
    }
}
